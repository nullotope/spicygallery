package io.github.nullotope.spicygallery.server.embed;

import io.github.nullotope.spicygallery.server.store.EmbeddingStore;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Embedding management API (spec §6): backfill trigger + SSE progress, status for the
 * Settings page. API-only — the UI reads these; no admin surface beyond this.
 */
@RestController
public class EmbeddingController {

    public record StatusDto(boolean enabled, String model, boolean breakerOpen,
                            long withEmbeddings, long missing) {}

    private final EmbeddingService service;
    private final EmbeddingBackfill backfill;
    private final EmbeddingStore store;

    public EmbeddingController(EmbeddingService service, EmbeddingBackfill backfill, EmbeddingStore store) {
        this.service = service;
        this.backfill = backfill;
        this.store = store;
    }

    @GetMapping("/api/embeddings/status")
    public StatusDto status() {
        String model = service.config().model();
        long entries = store.countEntries();
        long with = store.countWithModel(model);
        return new StatusDto(service.config().enabled(), model,
                !service.breakerClosed(), with, Math.max(0, entries - with));
    }

    /** Starts a backfill (spec §3.2); {@code force=true} re-embeds everything (model change). */
    @PostMapping("/api/embeddings/backfill")
    public ResponseEntity<Object> backfill(@RequestParam(defaultValue = "false") boolean force) {
        try {
            backfill.start(force);
            return ResponseEntity.accepted().body(Map.of("accepted", true));
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (io.github.nullotope.spicygallery.server.tagging.SchedulingConflictException e) {
            return conflict(e.getMessage()); // a tagging bulk run holds the shared GPU guard
        }
    }

    private static ResponseEntity<Object> conflict(String detail) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail));
    }

    @GetMapping("/api/embeddings/backfill")
    public EmbeddingBackfill.BackfillStatus backfillStatus() {
        return backfill.status();
    }

    @GetMapping("/api/embeddings/backfill/events")
    public SseEmitter backfillEvents() {
        return backfill.hub().register();
    }
}
