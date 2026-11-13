package io.github.nullotope.spicygallery.server.importjob;

import io.github.nullotope.spicygallery.domain.ImportResult;
import io.github.nullotope.spicygallery.domain.ImportStats;

/**
 * Fan-out for import run events. The SSE transport was removed (2026-11: it held a
 * browser socket open per tab forever and starved the HTTP/1.1 6-connections-per-host
 * pool) — progress reaches clients by polling {@code GET /api/imports/last}, whose
 * answer carries the live stats of an active run. This seam remains for tests and
 * future subscribers; every method is a no-op by default.
 */
public interface ImportRunEvents {

    ImportRunEvents NOOP = new ImportRunEvents() {};

    default void progress(ImportStats stats) {}

    default void done(ImportResult result) {}

    default void failed(String message) {}
}
