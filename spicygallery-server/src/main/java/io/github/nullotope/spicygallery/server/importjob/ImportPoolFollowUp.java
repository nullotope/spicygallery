package io.github.nullotope.spicygallery.server.importjob;

import java.util.List;

/**
 * The import follow-up port (spec §3.8): drafts a pool of one run's added entries.
 * Implementations must not be trusted to succeed — the caller (ImportRunReporter) guards
 * with WARN-only failure handling, since the import never depends on the follow-up.
 */
public interface ImportPoolFollowUp {

    /**
     * Creates the pool (name deduped, system tag attached, members in spec order) and
     * returns its id for {@code import_run.pool_id}.
     */
    long createPool(List<Long> addedEntryIds);
}
