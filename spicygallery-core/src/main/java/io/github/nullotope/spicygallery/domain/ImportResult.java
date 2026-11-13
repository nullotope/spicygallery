package io.github.nullotope.spicygallery.domain;

import java.util.List;

/**
 * Final outcome of one import run. {@code skippedRoots} names inaccessible libraries;
 * {@code addedEntryIds} holds the ok-path inserts' ids (fully ingested, status ok) — the
 * import follow-up's pool members (spec §3.8), unordered (ingest fans out to workers).
 */
public record ImportResult(int scanned, int added, int updated, int missing, int errors,
                           List<String> skippedRoots, List<Long> addedEntryIds) {}
