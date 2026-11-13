package io.github.nullotope.spicygallery.server.tagging;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.query.TagName;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore.TagRow;
import io.github.nullotope.spicygallery.server.tags.TagService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hydra predictions and their application (spec §3.2): {@link #predict} classifies the
 * original image bytes — or every persisted video frame, aggregating the max probability
 * per tag — and returns them WITHOUT writing; {@link #apply} merges the user-checked
 * subset through {@link TagService#setEntryTagsPreservingUser} with the model as
 * provenance. No churn (§3.4): this service contains no reference into the embed
 * package — tagging and embedding never invoke each other.
 *
 * <p>Failure split (§3.2, mirrors EmbeddingService): LOCAL failures — unreadable
 * source file, unreadable frame, frames-seam (extraction) failure — propagate as
 * {@link IOException} and never touch the breaker. PROVIDER faults — a classify
 * {@link IOException} — open the circuit for {@code tagging.failure-cooldown-seconds}
 * (one WARN); an open breaker (or {@code tagging.enabled=false}) fails fast with
 * {@link TaggingUnavailableException}. An info-source IOException is a provider fault
 * that must never trip the breaker (TaggingClient contract: the client retries on next
 * use) — it surfaces as {@link TaggingUnavailableException} instead.
 *
 * <p>Category mapping uses the cached {@link TaggingModelInfo}: labels without a
 * mappable category (e.g. {@code invalid}) are skipped with WARN — never defaulted to
 * {@code general}; in {@link #apply} the same skip applies defensively to checked names
 * that a stale client may still reference. Constructed by the tagging wiring (test
 * seams: {@link Provider}, {@link Reader}, {@link Frames}, {@link Clock}).
 */
public class TaggingService {

    private static final Logger LOG = LoggerFactory.getLogger(TaggingService.class);

    /**
     * Extraction cap hint passed to the {@link Frames} seam for on-demand extraction;
     * mirrors the embedding pipeline's default so both features' frame sets match.
     * The production seam may honor the embedding configuration's cap instead.
     */
    public static final int MAX_FRAMES = 64;

    /** One prediction: normalized tag name, resolved category, calibrated probability. */
    public record PredictedTag(String name, TagCategory category, double probability) {}

    /** The outcome of one predict run: reported model, calibration in use, tags sorted by probability desc. */
    public record Prediction(String model, String calibration, List<PredictedTag> tags) {
        public Prediction {
            tags = List.copyOf(tags);
        }
    }

    /** Test seam over the Hydra classify call (production: {@code TaggingClient::classify}). */
    @FunctionalInterface
    public interface Provider {
        Map<String, Double> classify(byte[] bytes, String calibration, String implications) throws IOException;
    }

    /** Cached model info source (production: {@code TaggingClient::info}); see the class javadoc on info failures. */
    @FunctionalInterface
    public interface TaggingModelInfoSource {
        TaggingModelInfo info() throws IOException;
    }

    /** Test seam over reading source/frame bytes (production: {@code Files::readAllBytes}). */
    @FunctionalInterface
    public interface Reader {
        byte[] read(Path file) throws IOException;
    }

    /**
     * Test seam over video frames (production: persisted {@code {appData}/frames/{entryId}/f*.jpg},
     * extracting on demand via FrameExtractor when the directory is empty/incomplete).
     */
    @FunctionalInterface
    public interface Frames {
        List<Path> frames(long entryId, Path video, int maxFrames) throws IOException;
    }

    private final Provider provider;
    private final TaggingModelInfoSource info;
    private final TagService tags;
    private final SpicyGalleryProperties.Tagging cfg;
    private final Reader reader;
    private final Frames frames;
    private final Clock clock;

    private volatile Instant breakerOpenUntil = Instant.EPOCH;
    private volatile boolean breakerWarned;

    public TaggingService(Provider provider, TaggingModelInfoSource info, TagService tags,
                          SpicyGalleryProperties.Tagging cfg, Reader reader, Frames frames, Clock clock) {
        this.provider = provider;
        this.info = info;
        this.tags = tags;
        this.cfg = cfg;
        this.reader = reader;
        this.frames = frames;
        this.clock = clock;
    }

    /** {@code true} when the next provider call would actually be attempted. */
    public boolean breakerClosed() {
        return cfg.enabled() && Instant.now(clock).isAfter(breakerOpenUntil);
    }

    /**
     * The configured model string — the provenance {@link #apply} stamps onto AI-created
     * rows and the bulk runner's resume-accounting key (spec §3.2).
     */
    public String model() {
        return cfg.model();
    }

    /** Clears an open breaker — a user-initiated retry is the recovery path. */
    public void resetBreaker() {
        breakerOpenUntil = Instant.EPOCH;
        breakerWarned = false;
    }

    /**
     * Classifies without writing (spec §3.2 AutotagService). Local inputs are gathered first so a
     * local failure never generates a provider call; images are read once, videos classified
     * per frame with per-tag max aggregation (Hydra is already calibrated — no threshold
     * pass). Unmappable labels are skipped with WARN.
     */
    public Prediction predict(long entryId, Path absPath, Kind kind, String calibrationOverride)
            throws IOException {
        failFast();
        String calibration = calibrationOverride != null && !calibrationOverride.isBlank()
                ? calibrationOverride
                : cfg.calibration();
        List<byte[]> inputs = collectInputs(entryId, absPath, kind);
        TaggingModelInfo modelInfo = modelInfo();
        LinkedHashMap<String, Double> max = new LinkedHashMap<>();
        for (byte[] bytes : inputs) {
            Map<String, Double> scores;
            try {
                scores = provider.classify(bytes, calibration, cfg.implications());
            } catch (IOException e) {
                openBreaker(e);
                throw new TaggingUnavailableException("tagging provider unreachable");
            }
            mergeMax(max, scores);
        }
        breakerOpenUntil = Instant.EPOCH; // success resets (no-op when nothing was classified)
        breakerWarned = false;
        return new Prediction(modelInfo.model(), calibration, mapPredicted(max, modelInfo));
    }

    /**
     * Merges the user-checked subset of a prior predict (spec §3.2): each checked name is
     * category-resolved via the cached model info and passed to
     * {@link TagService#setEntryTagsPreservingUser} with the configured model as
     * provenance — user-tagged rows survive, replaced AI rows are dropped. Names without
     * a mapping (stale client, vocabulary drift) are skipped with WARN. {@code from} is
     * the predict run the names were reviewed from — only its names are consumed;
     * categories re-resolve via the CURRENT cached model info (never the prediction).
     */
    public List<TagRow> apply(long entryId, List<String> checkedNames, Prediction from) {
        failFast();
        TaggingModelInfo modelInfo = modelInfo();
        List<TagService.TagInput> inputs = new ArrayList<>(checkedNames.size());
        for (String checked : checkedNames) {
            String name;
            try {
                name = TagName.normalize(checked);
            } catch (IllegalArgumentException invalid) {
                LOG.warn("checked tag '{}' is not a valid tag name — skipped", checked);
                continue;
            }
            Optional<TagCategory> category = modelInfo.categoryOf(name);
            if (category.isEmpty()) {
                // defensive: checked names can only come from a prior predict — a stale client
                // may reference labels that have since vanished from /info; skip, never default
                LOG.warn("no category mapping for checked tag '{}' — skipped", name);
                continue;
            }
            inputs.add(new TagService.TagInput(name, category.get(), cfg.model()));
        }
        return tags.setEntryTagsPreservingUser(entryId, inputs, cfg.model());
    }

    /** Throws when the caller must not expect provider work (predict/apply entry points, §3.2). */
    private void failFast() {
        if (!cfg.enabled()) {
            throw new TaggingUnavailableException("tagging provider disabled");
        }
        if (!breakerClosed()) {
            throw new TaggingUnavailableException("tagging provider unreachable");
        }
    }

    /**
     * LOCAL input gathering: image = the original file read once; video = the persisted
     * frames from the {@link Frames} seam, each read from disk. Any {@link IOException}
     * here is local (unreadable media, failed extraction) — it propagates untouched so
     * the breaker never trips for a local problem.
     */
    private List<byte[]> collectInputs(long entryId, Path absPath, Kind kind) throws IOException {
        if (kind == Kind.VIDEO) {
            List<Path> framePaths = frames.frames(entryId, absPath, MAX_FRAMES);
            if (framePaths.isEmpty()) {
                // the seam extracts on demand when empty — an empty return is genuinely no frames
                LOG.warn("no frames for video entry {} ({}); nothing to classify", entryId, absPath);
            }
            List<byte[]> inputs = new ArrayList<>(framePaths.size());
            for (Path frame : framePaths) {
                inputs.add(readLocal(frame));
            }
            return inputs;
        }
        return List.of(readLocal(absPath));
    }

    private byte[] readLocal(Path file) throws IOException {
        byte[] bytes = reader.read(file); // IOException propagates: local failure, breaker untouched
        if (bytes == null || bytes.length == 0) {
            throw new IOException("no readable bytes for " + file);
        }
        return bytes;
    }

    private TaggingModelInfo modelInfo() {
        try {
            return info.info();
        } catch (IOException e) {
            // provider fault, but an INFO failure must never trip the breaker (TaggingClient
            // contract: §3.2 — the client retries on next use); surface as unavailable instead
            LOG.warn("tagging model info unavailable ({}); breaker left closed", e.getMessage(), e);
            throw new TaggingUnavailableException("tagging model info unavailable");
        }
    }

    /** Folds per-frame scores into per-tag maxima keyed by the normalized tag name. */
    private static void mergeMax(LinkedHashMap<String, Double> max, Map<String, Double> scores) {
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            String name;
            try {
                name = TagName.normalize(e.getKey());
            } catch (IllegalArgumentException invalid) {
                LOG.warn("classify returned a label outside the tag charset: '{}' — skipped", e.getKey());
                continue;
            }
            max.merge(name, e.getValue(), Math::max);
        }
    }

    private List<PredictedTag> mapPredicted(LinkedHashMap<String, Double> max, TaggingModelInfo modelInfo) {
        List<PredictedTag> predicted = new ArrayList<>(max.size());
        for (Map.Entry<String, Double> e : max.entrySet()) {
            Optional<TagCategory> category = modelInfo.categoryOf(e.getKey());
            if (category.isEmpty()) {
                // Review Focus #1: an unmappable label ('invalid', unexpected) is skipped with
                // WARN — never created as general (spec §3.2 mapping table, last row)
                LOG.warn("no category mapping for predicted tag '{}' — skipped", e.getKey());
                continue;
            }
            predicted.add(new PredictedTag(e.getKey(), category.get(), e.getValue()));
        }
        predicted.sort(Comparator.comparingDouble(PredictedTag::probability).reversed());
        return predicted;
    }

    private void openBreaker(Exception cause) {
        breakerOpenUntil = Instant.now(clock).plus(Duration.ofSeconds(cfg.failureCooldownSeconds()));
        if (!breakerWarned) {
            LOG.warn("tagging provider failing ({}); skipping provider calls for {}s",
                    cause.getMessage(), cfg.failureCooldownSeconds());
            breakerWarned = true;
        }
    }
}
