package io.github.nullotope.spicygallery.server.web;

import io.github.nullotope.spicygallery.server.importjob.AllRootsInaccessibleException;
import io.github.nullotope.spicygallery.server.importjob.ImportInProgressException;
import io.github.nullotope.spicygallery.server.importjob.ImportService;
import io.github.nullotope.spicygallery.server.store.ImportRunStore;
import io.github.nullotope.spicygallery.server.web.dto.RunDto;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Import triggers + run status (spec §8). The status transport is REST polling: the
 * client reconciles {@code GET /api/imports/last} at a fast cadence while a run is
 * active (the SSE stream that used to push these events held a browser socket open
 * per tab forever and starved the HTTP/1.1 6-connections-per-host pool — removed).
 */
@RestController
public class ImportController {

    private final ImportService service;
    private final ImportRunStore runs;

    public ImportController(ImportService service, ImportRunStore runs) {
        this.service = service;
        this.runs = runs;
    }

    /** Optional body of POST /api/imports (spec §3.8) — every field optional; an absent
     *  body is tolerated and means the defaults. */
    public record StartRequest(Boolean createPool) {}

    @PostMapping("/api/imports")
    public ResponseEntity<Map<String, Long>> start(
            @RequestBody(required = false) StartRequest body)
            throws ImportInProgressException, AllRootsInaccessibleException {
        boolean createPool = body != null && Boolean.TRUE.equals(body.createPool());
        long runId = service.start(createPool);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("runId", runId));
    }

    @GetMapping(value = "/api/imports/last", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> last() {
        // Map.of rejects null values — a fresh install (zero runs) must answer
        // {"run": null} (spec §8: this endpoint is the REST reconcile source). While a
        // run is active the answer carries its live progress (polling transport).
        return runs.last()
                .<Map<String, Object>>map(row -> {
                    RunDto dto = service.activeStats()
                            .map(stats -> RunDto.withLiveProgress(row, stats))
                            .orElseGet(() -> RunDto.from(row));
                    return Map.of("run", dto);
                })
                .orElseGet(() -> java.util.Collections.singletonMap("run", null));
    }
}
