package io.github.nullotope.spicygallery.server.store;

import java.time.Instant;

/**
 * One import_run row (spec §5) — REST reconcile source (spec §8). {@code poolId} is the
 * import follow-up's drafted pool (spec §3.8), null unless the run added entries with
 * the flag set.
 */
public record ImportRunRow(long id, String status, Instant startedAt, Instant finishedAt,
                           int scanned, int added, int updated, int missingCount,
                           int errorCount, String message, Long poolId) {}
