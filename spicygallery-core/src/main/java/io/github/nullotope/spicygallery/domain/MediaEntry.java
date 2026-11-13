package io.github.nullotope.spicygallery.domain;

import java.time.Instant;

/** A media entry as stored (spec §5). Immutable snapshot of one row. */
public record MediaEntry(
        EntryId id,
        Kind kind,
        Status status,
        String rootName,
        String relPath,
        String fileName,
        String ext,
        String mime,
        long sizeBytes,
        String contentHash,
        Integer width,
        Integer height,
        Long durationMs,
        Instant mediaMtime,
        Instant importedAt,
        Instant updatedAt) {}
