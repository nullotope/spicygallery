package io.github.nullotope.spicygallery.server.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.nullotope.spicygallery.server.store.ImportRunRow;
import java.time.Instant;

/** One import run for the REST reconcile endpoint (spec §8). */
public record RunDto(long id, String status, Instant startedAt, Instant finishedAt,
                     int scanned, int added, int updated, int missingCount,
                     int errorCount, String message,
                     @JsonInclude(JsonInclude.Include.NON_NULL) Long poolId) {

    public static RunDto from(ImportRunRow row) {
        return new RunDto(row.id(), row.status(), row.startedAt(), row.finishedAt(),
                row.scanned(), row.added(), row.updated(), row.missingCount(),
                row.errorCount(), row.message(), row.poolId());
    }
}
