package io.github.nullotope.spicygallery.server.web;

import io.github.nullotope.spicygallery.server.importjob.AllRootsInaccessibleException;
import io.github.nullotope.spicygallery.server.importjob.ImportInProgressException;
import io.github.nullotope.spicygallery.server.importjob.ImportService;
import io.github.nullotope.spicygallery.server.importjob.ImportSseHub;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Import triggers + run status (spec §8): SSE notifies, REST reconciles. */
@RestController
public class ImportController {

    private final ImportService service;
    private final ImportSseHub hub;
    private final ImportRunStore runs;

    public ImportController(ImportService service, ImportSseHub hub, ImportRunStore runs) {
        this.service = service;
        this.hub = hub;
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
        // {"run": null} (spec §8: this endpoint is the REST reconcile source)
        return runs.last()
                .<Map<String, Object>>map(row -> Map.of("run", RunDto.from(row)))
                .orElseGet(() -> java.util.Collections.singletonMap("run", null));
    }

    @GetMapping(value = "/api/imports/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return hub.register();
    }
}
