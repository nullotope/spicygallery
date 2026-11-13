package io.github.nullotope.spicygallery.server.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.nullotope.spicygallery.domain.ImportStats;
import io.github.nullotope.spicygallery.server.store.ImportRunRow;
import java.time.Instant;

/**
 * One import run for the REST reconcile endpoint (spec §8). {@code processed} and
 * {@code current} are transient live-progress fields — carried only while the run is
 * active (the polling transport that replaced SSE), absent from every settled run.
 */
public record RunDto(long id, String status, Instant startedAt, Instant finishedAt,
                     int scanned, int added, int updated, int missingCount,
                     int errorCount, String message,
                     @JsonInclude(JsonInclude.Include.NON_NULL) Long poolId,
                     @JsonInclude(JsonInclude.Include.NON_NULL) Integer processed,
                     @JsonInclude(JsonInclude.Include.NON_NULL) String current) {

    public static RunDto from(ImportRunRow row) {
        return new RunDto(row.id(), row.status(), row.startedAt(), row.finishedAt(),
                row.scanned(), row.added(), row.updated(), row.missingCount(),
                row.errorCount(), row.message(), row.poolId(), null, null);
    }

    /** The row's settled fields plus the active run's live stats (poll transport). */
    public static RunDto withLiveProgress(ImportRunRow row, ImportStats stats) {
        return new RunDto(row.id(), row.status(), row.startedAt(), row.finishedAt(),
                row.scanned(), row.added(), row.updated(), row.missingCount(),
                row.errorCount(), row.message(), row.poolId(),
                stats.processed(), stats.current());
    }
}
