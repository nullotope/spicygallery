package io.github.nullotope.spicygallery.importer;

import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.ScannedFile;

/**
 * One diff decision of the scanner (spec §6.2). File-carrying ops go to ingest;
 * id-carrying ops are store-only actions.
 */
public sealed interface ScanOp {

    record New(ScannedFile file) implements ScanOp {}

    record Changed(ScannedFile file, EntryId id) implements ScanOp {}

    /** size+mtime unchanged, status ok — no write; carries the file for progress display. */
    record Unchanged(EntryId id, ScannedFile file) implements ScanOp {}

    /** file is back (size+mtime unchanged) and the row was missing. Carries the file —
     *  the pipeline may need to regenerate a lost thumbnail from it. */
    record Resurrect(ScannedFile file, EntryId id) implements ScanOp {}

    /** row exists but the file is gone from the walk. */
    record Missing(EntryId id) implements ScanOp {}

    /** extension not in the allowlist — counted, never ingested. */
    record UnknownExt(ScannedFile file) implements ScanOp {}
}
