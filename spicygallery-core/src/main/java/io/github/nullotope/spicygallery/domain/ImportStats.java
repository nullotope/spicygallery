package io.github.nullotope.spicygallery.domain;

/**
 * Live counters of a running import (SSE progress payload, run-row touch).
 * {@code scanned} counts files discovered by the walk; {@code processed} counts files
 * fully accounted for (ingested, skipped as unchanged, or counted as unknown-ext) —
 * the pair drives the banner's "processing X of Y" bar and converges when the run ends.
 * {@code current} is the display path ({@code rootName/relPath}) most recently seen,
 * null when idle.
 */
public record ImportStats(int scanned, int processed, int added, int updated, int missing, int errors,
                          String current) {}
