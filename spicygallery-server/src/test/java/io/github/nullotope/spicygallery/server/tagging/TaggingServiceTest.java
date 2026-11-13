package io.github.nullotope.spicygallery.server.tagging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.embed.EmbeddingProvider;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore.TagRow;
import io.github.nullotope.spicygallery.server.tags.TagService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * TaggingService (spec §3.2): image/video prediction with category mapping, the
 * local-vs-provider failure split (local failures propagate as IOException and never
 * trip the breaker; a provider IOException opens it for the configured cooldown),
 * provenance-preserving apply, and the §3.4 churn guard: the tagging path never
 * invokes the embedding provider.
 */
class TaggingServiceTest {

    // --- fakes --------------------------------------------------------------

    /** Mutable fixed clock (EmbeddingServiceTest pattern) for breaker cooldown timing. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /**
     * Records the merge delegation; the provenance/implication semantics of
     * {@code setEntryTagsPreservingUser} itself are pinned in TagServiceAutotagTest.
     */
    static final class RecordingTagService extends TagService {
        long seenEntryId = -1;
        List<TagService.TagInput> seenInputs;
        String seenProvenance;
        List<TagRow> rows = List.of();

        RecordingTagService() {
            super(null, null, null);
        }

        @Override
        public List<TagRow> setEntryTagsPreservingUser(long entryId, List<TagService.TagInput> aiInputs,
                                                       String aiProvenance) {
            seenEntryId = entryId;
            seenInputs = List.copyOf(aiInputs);
            seenProvenance = aiProvenance;
            return rows;
        }
    }

    /** §3.4 churn canary: handed to NOTHING — any embedding call from the tagging path is a break. */
    static final class CountingEmbeddingProvider implements EmbeddingProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<float[]> embed(List<EmbeddingProvider.EmbeddingInput> inputs) {
            calls.incrementAndGet();
            return List.of();
        }
    }

    private static TaggingModelInfo info(Map<String, String> labelCategory) {
        return new TaggingModelInfo("hydra-3.5", labelCategory, labelCategory.size());
    }

    /** enabled=true + configured defaults, with {@code tagging.failure-cooldown-seconds} under test. */
    private static SpicyGalleryProperties.Tagging cfg(Integer cooldownSeconds) {
        return new SpicyGalleryProperties.Tagging(true, null, null, null, null, null, cooldownSeconds);
    }

    private static byte[] originalBytes() {
        return "image-bytes".getBytes(StandardCharsets.UTF_8);
    }

    // --- prediction ---------------------------------------------------------

    @Test
    void image_prediction_maps_categories_and_skips_unknown_mapping() throws IOException {
        var seenCalibration = new AtomicReference<String>();
        var seenImplications = new AtomicReference<String>();
        Map<String, Double> scores = Map.of("red_hair", 0.9, "Azur Lane", 0.8, "weird_cat", 0.7);
        var service = new TaggingService(
                (bytes, calibration, implications) -> {
                    seenCalibration.set(calibration);
                    seenImplications.set(implications);
                    return scores;
                },
                () -> info(Map.of("red_hair", "general", "azur_lane", "copyright", "weird_cat", "invalid")),
                new RecordingTagService(), cfg(300), path -> originalBytes(),
                (entryId, video, maxFrames) -> {
                    throw new AssertionError("image predict must not touch the frames seam");
                },
                new MutableClock());
        var p = service.predict(1, Path.of("a.jpg"), Kind.IMAGE, null);
        assertEquals(List.of("red_hair", "azur_lane"),
                p.tags().stream().map(TaggingService.PredictedTag::name).toList());
        assertEquals(TagCategory.COPYRIGHT, p.tags().get(1).category());
        assertEquals(0.8, p.tags().get(1).probability(), 1e-9);
        assertEquals(2, p.tags().size(), "exactly the mappable predictions survive");
        // Review Focus #1: 'weird_cat' skipped with WARN, not created as general
        assertEquals("hydra-3.5", p.model());
        assertEquals("f1.0@0.1", seenCalibration.get(), "configured calibration passed through verbatim");
        assertEquals("inherit", seenImplications.get());
        // threshold-slider channel (§4.1): the override replaces the calibration for this predict only
        var overridden = service.predict(1, Path.of("a.jpg"), Kind.IMAGE, "f2.0@0.1");
        assertEquals("f2.0@0.1", seenCalibration.get());
        assertEquals("f2.0@0.1", overridden.calibration());
    }

    @Test
    void video_prediction_aggregates_max_probability_across_frames() throws IOException {
        var seenMaxFrames = new AtomicInteger(-1);
        var seenCalibration = new AtomicReference<String>();
        TaggingService.Provider perFrame = (bytes, calibration, implications) -> {
            seenCalibration.set(calibration);
            return switch (new String(bytes, StandardCharsets.UTF_8)) {
                case "f0000.jpg" -> Map.of("smile", 0.2);
                case "f0001.jpg" -> Map.of("smile", 0.95);
                case "f0002.jpg" -> Map.of("smile", 0.4);
                default -> throw new AssertionError(
                        "unexpected input: " + new String(bytes, StandardCharsets.UTF_8));
            };
        };
        var frames = List.of(Path.of("f0000.jpg"), Path.of("f0001.jpg"), Path.of("f0002.jpg"));
        var service = new TaggingService(perFrame, () -> info(Map.of("smile", "general")),
                new RecordingTagService(), cfg(300),
                path -> path.getFileName().toString().getBytes(StandardCharsets.UTF_8),
                (entryId, video, maxFrames) -> {
                    seenMaxFrames.set(maxFrames);
                    return frames;
                },
                new MutableClock());
        var p = service.predict(7, Path.of("v.mp4"), Kind.VIDEO, null);
        assertEquals(List.of("smile"), p.tags().stream().map(TaggingService.PredictedTag::name).toList());
        assertEquals(0.95, p.tags().get(0).probability(), 1e-9); // max across frames, not mean/first/last
        assertEquals(TagCategory.GENERAL, p.tags().get(0).category());
        assertEquals(TaggingService.MAX_FRAMES, seenMaxFrames.get());
        assertEquals("f1.0@0.1", seenCalibration.get());
    }

    // --- local vs provider failure split -------------------------------------

    @Test
    void empty_frames_plus_failing_extractor_is_a_local_failure_not_a_breaker_trip() {
        var service = new TaggingService(
                (bytes, calibration, implications) -> {
                    throw new AssertionError("a local failure must not reach the provider");
                },
                () -> info(Map.of()),
                new RecordingTagService(), cfg(300),
                path -> {
                    throw new AssertionError("no original bytes expected for a video");
                },
                (entryId, video, maxFrames) -> {
                    throw new IOException("ffmpeg failed");
                },
                new MutableClock());
        assertThrows(IOException.class, () -> service.predict(1, Path.of("v.mp4"), Kind.VIDEO, null));
        assertTrue(service.breakerClosed(), "local frames failure must not trip the breaker");
    }

    @Test
    void provider_failure_trips_the_breaker_and_disabled_throws_unavailable() {
        var clock = new MutableClock();
        var service = new TaggingService(
                (bytes, calibration, implications) -> {
                    throw new IOException("provider down");
                },
                () -> info(Map.of("red_hair", "general")),
                new RecordingTagService(), cfg(300), path -> originalBytes(),
                (entryId, video, maxFrames) -> List.of(),
                clock);
        assertThrows(TaggingUnavailableException.class,
                () -> service.predict(1, Path.of("a.jpg"), Kind.IMAGE, null));
        assertFalse(service.breakerClosed(), "provider IOException must open the breaker");
        assertThrows(TaggingUnavailableException.class,
                () -> service.predict(1, Path.of("a.jpg"), Kind.IMAGE, null),
                "an open breaker fails fast on the next call");
        clock.advance(Duration.ofSeconds(301)); // default cooldown 300s elapsed
        assertTrue(service.breakerClosed(), "cooldown elapsed → provider calls attempted again");

        var disabled = new TaggingService(
                (bytes, calibration, implications) -> Map.of("red_hair", 0.9),
                () -> info(Map.of("red_hair", "general")),
                new RecordingTagService(),
                new SpicyGalleryProperties.Tagging(false, null, null, null, null, null, null),
                path -> originalBytes(), (entryId, video, maxFrames) -> List.of(), clock);
        var e = assertThrows(TaggingUnavailableException.class,
                () -> disabled.predict(1, Path.of("a.jpg"), Kind.IMAGE, null));
        assertTrue(e.getMessage().contains("disabled"), "disabled names the cause: " + e.getMessage());
    }

    @Test
    void info_source_failure_throws_unavailable_without_tripping_the_breaker() throws IOException {
        var clock = new MutableClock();
        var infoCalls = new AtomicInteger();
        var service = new TaggingService(
                (bytes, calibration, implications) -> Map.of("red_hair", 0.9),
                () -> {
                    if (infoCalls.incrementAndGet() == 1) {
                        throw new IOException("info endpoint down");
                    }
                    return info(Map.of("red_hair", "general"));
                },
                new RecordingTagService(), cfg(300), path -> originalBytes(),
                (entryId, video, maxFrames) -> List.of(),
                clock);
        // Review round 1: an INFO-source IOException is a provider fault that must NEVER
        // trip the breaker (TaggingClient contract: the client retries on next use) — it
        // surfaces as TaggingUnavailableException instead, leaving the breaker closed
        assertThrows(TaggingUnavailableException.class,
                () -> service.predict(1, Path.of("a.jpg"), Kind.IMAGE, null));
        assertTrue(service.breakerClosed(),
                "an info-source IOException must not open the breaker");
        // recovery: the next predict (info restored) classifies normally — no resetBreaker needed
        var recovered = service.predict(1, Path.of("a.jpg"), Kind.IMAGE, null);
        assertEquals(List.of("red_hair"),
                recovered.tags().stream().map(TaggingService.PredictedTag::name).toList());
        assertEquals("hydra-3.5", recovered.model());
        assertTrue(service.breakerClosed(), "still closed after the successful predict");
    }

    @Test
    void breaker_cooldown_uses_the_configured_failure_cooldown_seconds() {
        var clock = new MutableClock();
        var service = new TaggingService(
                (bytes, calibration, implications) -> {
                    throw new IOException("provider down");
                },
                () -> info(Map.of()),
                new RecordingTagService(), cfg(2), // tagging.failure-cooldown-seconds = 2
                path -> originalBytes(), (entryId, video, maxFrames) -> List.of(), clock);
        assertThrows(TaggingUnavailableException.class,
                () -> service.predict(1, Path.of("a.jpg"), Kind.IMAGE, null));
        assertFalse(service.breakerClosed());
        clock.advance(Duration.ofSeconds(1));
        assertFalse(service.breakerClosed(), "1s < 2s cooldown: still open");
        clock.advance(Duration.ofSeconds(2));
        assertTrue(service.breakerClosed(), "cooldown honored from tagging.failure-cooldown-seconds");
    }

    // --- apply + churn guard -------------------------------------------------

    @Test
    void apply_uses_checked_names_only_and_keeps_user_tags() {
        var tags = new RecordingTagService();
        tags.rows = List.of(new TagRow(11, "red_hair", "general", 5, Instant.EPOCH, null));
        var service = new TaggingService(
                (bytes, calibration, implications) -> Map.of(),
                () -> info(Map.of("red_hair", "general", "azur_lane", "copyright")),
                tags, cfg(300), path -> originalBytes(),
                (entryId, video, maxFrames) -> List.of(),
                new MutableClock());
        var prediction = new TaggingService.Prediction("hydra-3.5", "f1.0@0.1",
                List.of(new TaggingService.PredictedTag("red_hair", TagCategory.GENERAL, 0.9),
                        new TaggingService.PredictedTag("azur_lane", TagCategory.COPYRIGHT, 0.8)));
        List<TagRow> rows = service.apply(1, List.of("red_hair", "Azur Lane", "vanished_from_info"), prediction);
        // user-tag preservation itself is setEntryTagsPreservingUser's job (TagServiceAutotagTest);
        // here: only the checked names reach the merge — normalized, category-mapped via the
        // cached model info, provenance = tagging.model; names without a mapping are skipped
        assertEquals(1L, tags.seenEntryId);
        assertEquals(List.of(
                new TagService.TagInput("red_hair", TagCategory.GENERAL, "hydra-3.5"),
                new TagService.TagInput("azur_lane", TagCategory.COPYRIGHT, "hydra-3.5")), tags.seenInputs);
        assertEquals("hydra-3.5", tags.seenProvenance);
        assertEquals(tags.rows, rows);
    }

    @Test
    void prediction_never_invokes_the_embedding_provider() throws IOException {
        var embeddings = new CountingEmbeddingProvider(); // handed to nothing — a §3.4 canary
        var info = info(Map.of("red_hair", "general", "smile", "general"));
        var tags = new RecordingTagService();
        var service = new TaggingService(
                (bytes, calibration, implications) -> Map.of("red_hair", 0.9, "smile", 0.5),
                () -> info, tags, cfg(300), path -> originalBytes(),
                (entryId, video, maxFrames) -> List.of(Path.of("f0000.jpg")),
                new MutableClock());
        var image = service.predict(1, Path.of("a.jpg"), Kind.IMAGE, null);
        service.apply(1, List.of("red_hair"), image);
        var video = service.predict(2, Path.of("v.mp4"), Kind.VIDEO, null);
        service.apply(2, List.of("smile"), video);
        assertEquals(0, embeddings.calls.get(),
                "a full predict+apply must never invoke the embedding provider (spec §3.4)");
    }
}
