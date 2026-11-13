package io.github.nullotope.spicygallery.importer;

import io.github.nullotope.spicygallery.domain.ImportResult;
import io.github.nullotope.spicygallery.domain.ImportStats;

/**
 * Progress listener of a run. The pipeline throttles {@link #progress} to ≤ ~4/s,
 * calls {@link #done} exactly once on success, or {@link #failed} instead on a fatal
 * error (spec §6.5).
 */
public interface ImportReporter {

    void progress(ImportStats stats);

    void done(ImportResult result);

    void failed(String message);
}
