package io.github.nullotope.spicygallery.server.embed;

import io.github.nullotope.spicygallery.embed.FrameExtractor;
import io.github.nullotope.spicygallery.embed.ImagePreparer;
import io.github.nullotope.spicygallery.probe.FfmpegTools;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.store.EmbeddingStore;
import io.github.nullotope.spicygallery.server.store.EmbeddingStore.FrameVector;
import io.github.nullotope.spicygallery.thumb.ThumbLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single write choke point for embeddings (spec §3.2): prepares inputs per entry
 * (original-capped image or 1 fps frames — persisted for slice 4), calls the provider
 * in batches, replaces rows atomically. Soft-fail semantics: a provider failure trips
 * a circuit breaker (all calls skip for the cooldown, one WARN); a local read/decode
 * failure just logs and leaves the entry un-embedded (backfill retries). Dimension
 * mismatches are loud IllegalStateException — nothing is ever stored truncated.
 * Constructed by {@link EmbeddingWiring} (test seam: VideoFrames + Clock).
 */
public class EmbeddingService {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingService.class);

    /** Test seam over video extraction (production delegates to FrameExtractor). */
    @FunctionalInterface
    public interface VideoFrames {
        List<FrameExtractor.VideoFrame> extract(Path video, Path workDir, FfmpegTools tools, int maxFrames)
                throws IOException;
    }

    private static final int CACHE_MAX = 128;
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final EmbeddingProvider provider;
    private final EmbeddingStore store;
    private final SpicyGalleryProperties.Embedding cfg;
    private final Path appData;
    private final VideoFrames videoFrames;
    private final FfmpegTools tools;
    private final Clock clock;

    private final java.util.concurrent.ConcurrentHashMap<String, CachedVector> textCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Instant breakerOpenUntil = Instant.EPOCH;
    private volatile boolean breakerWarned;

    public EmbeddingService(EmbeddingProvider provider, EmbeddingStore store,
                            SpicyGalleryProperties.Embedding cfg, Path appData,
                            VideoFrames videoFrames, Clock clock) {
        this.provider = provider;
        this.store = store;
        this.cfg = cfg;
        this.appData = appData;
        this.videoFrames = videoFrames;
        this.tools = FfmpegTools.resolve("ffmpeg", "ffprobe").orElse(null);
        this.clock = clock;
    }

    public SpicyGalleryProperties.Embedding config() {
        return cfg;
    }

    /** {@code true} when the next provider call would actually be attempted. */
    public boolean breakerClosed() {
        return cfg.enabled() && Instant.now(clock).isAfter(breakerOpenUntil);
    }

    /** Throws when the caller must not expect provider work (similar:, backfill entry points). */
    public void failFast() {
        if (!cfg.enabled()) {
            throw new EmbeddingUnavailableException("embedding provider disabled");
        }
        if (!breakerClosed()) {
            throw new EmbeddingUnavailableException("embedding provider unreachable");
        }
    }

    /** Clears an open breaker — a user-initiated backfill is the retry mechanism. */
    public void resetBreaker() {
        breakerOpenUntil = Instant.EPOCH;
        breakerWarned = false;
    }

    /** Embeds one entry: prepare inputs → batched provider calls → replaceAll. Soft-fail. */
    public void embedEntry(long entryId, Path absPath, io.github.nullotope.spicygallery.domain.Kind kind) {
        if (!cfg.enabled()) {
            return; // disabled: pipeline slot is a no-op
        }
        if (!breakerClosed()) {
            throw new EmbeddingUnavailableException("embedding provider unreachable");
        }
        try {
            Prepared prepared;
            try {
                prepared = prepareInputs(entryId, absPath, kind);
            } catch (IOException e) {
                // LOCAL failure (ffmpeg missing/failed, unreadable media, slow mount) —
                // never the provider's fault: no breaker, no error to callers; the entry
                // stays un-embedded and the backfill retries it later (spec §7).
                LOG.warn("input preparation failed for entry {} ({}); leaving it un-embedded",
                        entryId, e.toString());
                return;
            }
            if (prepared.inputs().isEmpty()) {
                LOG.warn("no embeddable input for entry {} ({}); leaving it un-embedded", entryId, absPath);
                return; // local data issue, not a provider problem: no breaker
            }
            List<FrameVector> vectors = new ArrayList<>(prepared.inputs().size());
            for (int from = 0; from < prepared.inputs().size(); from += cfg.batchSize()) {
                int to = Math.min(from + cfg.batchSize(), prepared.inputs().size());
                List<EmbeddingProvider.EmbeddingInput> chunk = prepared.inputs().subList(from, to);
                List<float[]> chunkVectors = provider.embed(chunk);
                for (int i = 0; i < chunk.size(); i++) {
                    requireDimension(chunkVectors.get(i));
                    int idx = from + i;
                    boolean video = kind == io.github.nullotope.spicygallery.domain.Kind.VIDEO;
                    vectors.add(new FrameVector(video ? idx : 0,
                            video ? prepared.timestamps().get(idx) : null,
                            chunkVectors.get(i)));
                }
            }
            store.replaceAll(entryId, cfg.model(), vectors);
            breakerOpenUntil = Instant.EPOCH; // success resets
            breakerWarned = false;
        } catch (IOException e) {
            openBreaker(e);
            throw new UncheckedIOException("embedding provider failed for entry " + entryId, e);
        } catch (RuntimeException e) {
            if (!(e instanceof EmbeddingUnavailableException)) {
                LOG.warn("embedding failed for entry {}: {}", entryId, e.toString());
            }
            throw e;
        }
    }

    private record Prepared(List<EmbeddingProvider.EmbeddingInput> inputs, List<Long> timestamps) {}

    private Prepared prepareInputs(long entryId, Path absPath,
                                   io.github.nullotope.spicygallery.domain.Kind kind)
            throws IOException {
        List<EmbeddingProvider.EmbeddingInput> inputs = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();
        if (kind == io.github.nullotope.spicygallery.domain.Kind.VIDEO) {
            if (tools == null) {
                LOG.warn("no ffmpeg — cannot extract frames for entry {}", entryId);
                return new Prepared(inputs, timestamps);
            }
            Path framesDir = appData.resolve("frames").resolve(String.valueOf(entryId));
            Files.createDirectories(framesDir); // persistence guarantee: dir exists even if extraction yields 0
            List<FrameExtractor.VideoFrame> frames =
                    videoFrames.extract(absPath, framesDir, tools, cfg.maxFrames());
            for (FrameExtractor.VideoFrame f : frames) {
                inputs.add(new EmbeddingProvider.EmbeddingInput(null, f.jpeg(), "image/jpeg"));
                timestamps.add(f.timestampMs());
            }
            return new Prepared(inputs, timestamps);
        }
        byte[] thumb = readThumb(entryId);
        byte[] jpeg = ImagePreparer.prepare(absPath, thumb, cfg.imageMaxSide());
        if (jpeg == null || jpeg.length == 0) {
            return new Prepared(inputs, timestamps);
        }
        inputs.add(new EmbeddingProvider.EmbeddingInput(null, jpeg, "image/jpeg"));
        timestamps.add(null);
        return new Prepared(inputs, timestamps);
    }

    private byte[] readThumb(long entryId) {
        try {
            Path p = ThumbLayout.pathFor(appData, entryId);
            return Files.exists(p) ? Files.readAllBytes(p) : new byte[0];
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private void requireDimension(float[] vec) {
        if (vec.length != 768) {
            throw new IllegalStateException(
                    "embedding provider returned " + vec.length + " dimensions; the schema pins 768 "
                            + "(model/config mismatch?) — refusing to store. Fix spicygallery.embedding.model "
                            + "or migrate the column.");
        }
    }

    private void openBreaker(Exception cause) {
        breakerOpenUntil = Instant.now(clock).plus(Duration.ofSeconds(cfg.failureCooldownSeconds()));
        if (!breakerWarned) {
            LOG.warn("embedding provider failing ({}); skipping provider calls for {}s",
                    cause.getMessage(), cfg.failureCooldownSeconds());
            breakerWarned = true;
        }
    }

    /**
     * Text → vector with a small TTL cache (spec §3.2): paging a similar: query reuses
     * it. Lives on Tomcat request threads — the map must be concurrent (two overlapping
     * misses race eviction/insert; the previous key-aligned LinkedHashMap pair threw
     * ConcurrentModificationException under load). Eviction is time-ordered FIFO at
     * {@value #CACHE_MAX} entries — strict LRU is not worth the structure at this size.
     */
    public float[] textVector(String text) {
        if (!cfg.enabled()) {
            throw new EmbeddingUnavailableException("embedding provider disabled");
        }
        Instant now = Instant.now(clock);
        CachedVector hit = textCache.get(text);
        if (hit != null && now.isBefore(hit.cachedAt().plus(CACHE_TTL))) {
            return hit.vec();
        }
        // bounded eviction: expired first, then oldest-inserted while over capacity
        if (textCache.size() >= CACHE_MAX) {
            textCache.entrySet().removeIf(e -> now.isAfter(e.getValue().cachedAt().plus(CACHE_TTL)));
            while (textCache.size() >= CACHE_MAX) {
                String eldest = textCache.entrySet().stream()
                        .min(java.util.Map.Entry.comparingByValue(
                                java.util.Comparator.comparing(CachedVector::cachedAt)))
                        .map(java.util.Map.Entry::getKey).orElse(null);
                if (eldest == null) {
                    break;
                }
                textCache.remove(eldest);
            }
        }
        float[] vec;
        try {
            vec = provider.embed(List.of(new EmbeddingProvider.EmbeddingInput(text, null, null))).get(0);
        } catch (IOException e) {
            openBreaker(e); // provider down is provider down, whichever surface noticed
            throw new EmbeddingUnavailableException("embedding provider unreachable");
        }
        requireDimension(vec);
        textCache.put(text, new CachedVector(vec, now));
        return vec;
    }

    private record CachedVector(float[] vec, Instant cachedAt) {}
}
