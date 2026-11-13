package io.github.nullotope.spicygallery.domain;

import java.time.Instant;

/** What the store knows about one (rootName, relPath) — the scanner's diff input. */
public record StoredLocation(EntryId id, Kind kind, String ext, long sizeBytes,
                             Instant mediaMtime, Status status) {}
