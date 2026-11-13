package io.github.nullotope.spicygallery.server.tagging;

import io.github.nullotope.spicygallery.domain.Status;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.store.EntryQueries;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore;
import io.github.nullotope.spicygallery.server.tags.TagService;
import io.github.nullotope.spicygallery.server.web.GlobalExceptionHandler;
import io.github.nullotope.spicygallery.server.web.dto.TagDto;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tagging API (spec §3.2/§6): model observability ({@code /info}), per-entry predict
 * (writes nothing) and apply (the user-checked subset, model provenance), and the bulk
 * run — start / last — under the shared {@link GpuJobGuard}. Bulk runs are
 * singletons-in-effect (the guard refuses overlap) but per-request objects: each run's
 * scope is fixed at start, and {@code /last} reconciles the most recently started run
 * (the client polls it — the SSE transport was removed, it starved the browser's
 * HTTP/1.1 6-connections-per-host pool). Lives in the tagging package: it assembles
 * the per-run {@link TaggingBackfill} from the wiring's beans.
 */
@RestController
public class TaggingController {

    public record PredictRequest(String calibration) {}

    public record ApplyRequest(List<String> tags) {}

    /** Exactly one of {@code q} / {@code untagged} selects the bulk scope. */
    public record BulkRequest(String q, Boolean untagged) {}

    public record PredictedTagDto(String name, String category, double probability) {}

    public record PredictionDto(String model, String calibration, List<PredictedTagDto> tags) {}

    /**
     * Model observability (spec §6): {@code reachable} mirrors the live upstream, the
     * rest reports configuration and the (possibly cached) vocabulary size. No
     * concurrency echo: bulk runs serial in slice 4, the knob is reserved (§3.2).
     */
    public record ModelInfoDto(boolean enabled, boolean reachable, String model, Integer labels,
                               String calibration, String implications) {}

    /** The entry's source/frames could not be read — a LOCAL failure, mapped to 422. */
    public static final class UnreadableSourceException extends RuntimeException {
        public UnreadableSourceException(long id, IOException cause) {
            super("cannot read media for entry " + id + ": " + cause.getMessage(), cause);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(TaggingController.class);

    private final TaggingService tagging;
    private final TaggingClient client;
    private final JdbcTagStore store;
    private final TaggingBackfill.EntryLookup lookup;
    private final TaggingBackfill.PageSource untaggedPages;
    private final GpuJobGuard guard;
    private final EntryQueries queries;
    private final TagService tags;
    private final SpicyGalleryProperties props;

    private volatile TaggingBackfill lastRun;

    public TaggingController(TaggingService tagging, TaggingClient client, JdbcTagStore store,
                             TaggingBackfill.EntryLookup lookup,
                             TaggingBackfill.PageSource untaggedPages,
                             GpuJobGuard guard, EntryQueries queries, TagService tags,
                             SpicyGalleryProperties props) {
        this.tagging = tagging;
        this.client = client;
        this.store = store;
        this.lookup = lookup;
        this.untaggedPages = untaggedPages;
        this.guard = guard;
        this.queries = queries;
        this.tags = tags;
        this.props = props;
    }

    /**
     * Observability first: a live {@code refresh()} so an upstream-only model change is
     * visible here immediately (the stale-cache case Task 3's reviewer flagged), then the
     * cached view. Unreachable is a REPORTED fact, never a thrown one.
     */
    @GetMapping("/api/tagging/info")
    public ModelInfoDto info() {
        SpicyGalleryProperties.Tagging cfg = props.tagging();
        TaggingModelInfo info = null;
        boolean reachable = false;
        try {
            client.refresh();
            info = client.info();
            reachable = true;
        } catch (IOException e) {
            LOG.warn("tagging provider /info unreachable ({}); reporting reachable=false",
                    e.getMessage());
        }
        return new ModelInfoDto(cfg.enabled(), reachable,
                info != null ? info.model() : cfg.model(),
                info != null ? info.labelCount() : null,
                cfg.calibration(), cfg.implications());
    }

    /** Classifies without writing (spec §3.2); a local read failure is a 422, not a 500. */
    @PostMapping("/api/entries/{id}/autotag/predict")
    public PredictionDto predict(@PathVariable long id,
                                 @RequestBody(required = false) PredictRequest request) {
        TaggingBackfill.EntryRef ref = entryRef(id);
        try {
            TaggingService.Prediction p = tagging.predict(id, ref.absPath(), ref.kind(),
                    request == null ? null : request.calibration());
            List<PredictedTagDto> tags = p.tags().stream()
                    .map(t -> new PredictedTagDto(t.name(), t.category().wireName(), t.probability()))
                    .toList();
            return new PredictionDto(p.model(), p.calibration(), tags);
        } catch (IOException e) {
            // local failure (unreadable source/frames): the breaker is never involved (§3.2)
            throw new UnreadableSourceException(id, e);
        }
    }

    /** Applies the user-checked subset with model provenance; answers the updated tags. */
    @PostMapping("/api/entries/{id}/autotag/apply")
    public List<TagDto> apply(@PathVariable long id, @RequestBody ApplyRequest request) {
        entryRef(id);
        List<JdbcTagStore.TagRow> rows = tagging.apply(id,
                request.tags() == null ? List.of() : request.tags(), null);
        return rows.stream()
                .map(r -> new TagDto(r.id(), r.name(), r.category(), r.postCount(), r.taggedBy()))
                .toList();
    }

    /**
     * Starts the bulk run for the request's scope; 202 when scheduled, 409 when the
     * shared GPU guard is held (or a tagging run is somehow still going), 503 via the
     * global handler when tagging is disabled/broken.
     */
    @PostMapping("/api/tagging/bulk")
    public ResponseEntity<Object> bulk(@RequestBody BulkRequest request) {
        boolean untagged = Boolean.TRUE.equals(request.untagged());
        String q = untagged ? null : request.q();
        if (!untagged && (q == null || q.isBlank())) {
            throw new IllegalArgumentException("bulk tagging needs a query (q) or untagged=true");
        }
        TaggingBackfill.PageSource queryPages;
        try {
            queryPages = untagged
                    ? untaggedPages
                    : TaggingWiring.queryPageSource(queries, tags, q.trim());
        } catch (IllegalStateException e) {
            // e.g. a similar: scope — resolving it would couple tagging to embeddings (§3.4)
            throw new IllegalArgumentException("bulk scope unsupported: " + e.getMessage());
        }
        TaggingBackfill run = new TaggingBackfill(tagging, store, lookup, queryPages,
                untaggedPages, TaggingBackfill.Events.NOOP, guard);
        try {
            run.start(untagged ? "untagged" : "query", q, untagged);
        } catch (SchedulingConflictException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()));
        }
        lastRun = run;
        return ResponseEntity.accepted().body(Map.of("accepted", true));
    }

    /** The most recently started run's status (zeroed before the first run — reconcile shape). */
    @GetMapping("/api/tagging/last")
    public TaggingBackfill.RunStatus last() {
        TaggingBackfill run = lastRun;
        return run != null ? run.status() : new TaggingBackfill.RunStatus(false, 0, 0, 0, null, null);
    }

    /** 404 for unknown ids (and missing media); resolves the absolute path for the rest. */
    private TaggingBackfill.EntryRef entryRef(long id) {
        var entry = queries.byId(id)
                .orElseThrow(() -> new GlobalExceptionHandler.MissingEntryException(id));
        if (entry.status() == Status.MISSING) {
            throw new GlobalExceptionHandler.MissingEntryException(id);
        }
        TaggingBackfill.EntryRef ref = lookup.byIds(List.of(id)).get(id);
        if (ref == null) {
            throw new GlobalExceptionHandler.MissingEntryException(id); // unresolvable root
        }
        return ref;
    }
}
