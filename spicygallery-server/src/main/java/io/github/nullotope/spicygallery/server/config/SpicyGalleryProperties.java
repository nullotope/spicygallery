package io.github.nullotope.spicygallery.server.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * spicygallery.* configuration (spec §10.1). Canonical constructor applies the pinned
 * defaults; {@link #validate()} rejects duplicate library names (names are identity keys).
 */
@ConfigurationProperties(prefix = "spicygallery")
public record SpicyGalleryProperties(List<Library> libraries, String appData,
                                     String ffmpegPath, String ffprobePath, Integer workers,
                                     List<String> imageExts, List<String> videoExts,
                                     Embedding embedding, Tagging tagging) {

    public SpicyGalleryProperties {
        if (appData == null) {
            appData = "./data";
        }
        if (ffmpegPath == null) {
            ffmpegPath = "ffmpeg";
        }
        if (ffprobePath == null) {
            ffprobePath = "ffprobe";
        }
        if (workers == null) {
            workers = 4;
        }
        if (imageExts == null || imageExts.isEmpty()) {
            imageExts = List.of("jpg", "jpeg", "jpe", "png", "gif", "webp", "bmp");
        }
        if (videoExts == null || videoExts.isEmpty()) {
            videoExts = List.of("mp4", "webm", "mkv", "mov");
        }
        libraries = libraries == null ? List.of() : List.copyOf(libraries);
        imageExts = List.copyOf(imageExts);
        videoExts = List.copyOf(videoExts);
        if (embedding == null) {
            embedding = new Embedding(true, null, null, null, null, null, null, null, null);
        }
        if (tagging == null) {
            tagging = new Tagging(true, null, null, null, null, null, null);
        }
    }

    public record Library(String name, String root) {}

    /**
     * Embedding provider block (spec §3.4). Defaults: enabled, compose-profile URL
     * http://embedding:8080/v1, model "embeddinggemma-2", batch 8, 64 frames/video,
     * image cap 1024 px, 5 min failure cooldown, 60 s HTTP timeout. {@code enabled}
     * must be a {@link Boolean}: Boot's record constructor-binding defaults a primitive
     * to {@code false} when any single {@code embedding.*} property is set, which would
     * silently disable embeddings for a base-url-only configuration.
     */
    public record Embedding(Boolean enabled, String baseUrl, String model, String apiKey,
                            Integer batchSize, Integer maxFrames, Integer imageMaxSide,
                            Long failureCooldownSeconds, Long timeoutSeconds) {

        public static final String DEFAULT_BASE_URL = "http://embedding:8080/v1";
        public static final String DEFAULT_MODEL = "embeddinggemma-2";

        public Embedding {
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = DEFAULT_BASE_URL;
            }
            if (model == null || model.isBlank()) {
                model = DEFAULT_MODEL;
            }
            if (batchSize == null) {
                batchSize = 8;
            }
            if (maxFrames == null) {
                maxFrames = 64;
            }
            if (imageMaxSide == null) {
                imageMaxSide = 1024;
            }
            if (failureCooldownSeconds == null) {
                failureCooldownSeconds = 300L;
            }
            if (timeoutSeconds == null) {
                timeoutSeconds = 60L;
            }
        }
    }

    /**
     * Tagging provider block (spec §3.2). Defaults: enabled, llama-swap upstream URL
     * http://host.docker.internal:9292/upstream/hydra-3.5, model "hydra-3.5", calibration
     * "f1.0@0.1", implications "inherit", concurrency 4, failure cooldown 300 s.
     * {@code enabled} must be a {@link Boolean}: Boot's record constructor-binding
     * defaults a primitive to {@code false} when any single {@code tagging.*} property
     * is set, which would silently disable tagging for a base-url-only configuration.
     */
    public record Tagging(Boolean enabled, String baseUrl, String model, String calibration,
                          String implications, Integer concurrency, Integer failureCooldownSeconds) {

        public static final String DEFAULT_BASE_URL = "http://host.docker.internal:9292/upstream/hydra-3.5";
        public static final String DEFAULT_MODEL = "hydra-3.5";

        public Tagging {
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = DEFAULT_BASE_URL;
            }
            if (model == null || model.isBlank()) {
                model = DEFAULT_MODEL;
            }
            if (calibration == null || calibration.isBlank()) {
                calibration = "f1.0@0.1";
            }
            if (implications == null || implications.isBlank()) {
                implications = "inherit";
            }
            if (concurrency == null || concurrency < 1) {
                concurrency = 4;
            }
            if (failureCooldownSeconds == null) {
                failureCooldownSeconds = 300;
            }
        }
    }

    public void validate() {
        long distinct = libraries.stream().map(Library::name).distinct().count();
        if (distinct != libraries.size()) {
            throw new IllegalStateException(
                    "duplicate library names in spicygallery.libraries — names are identity keys");
        }
    }
}
