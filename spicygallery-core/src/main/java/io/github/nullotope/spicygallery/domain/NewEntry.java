package io.github.nullotope.spicygallery.domain;

import java.time.Instant;

/** The data a successful (or failed) ingest writes for an entry. No id — the sink assigns one. */
public record NewEntry(Kind kind, String rootName, String relPath, String fileName, String ext,
                       String mime, long sizeBytes, String contentHash, Integer width,
                       Integer height, Long durationMs, Instant mediaMtime) {}
