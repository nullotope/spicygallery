package io.github.nullotope.spicygallery.server.web;

import io.github.nullotope.spicygallery.server.importjob.ImportInProgressException;
import io.github.nullotope.spicygallery.server.importjob.AllRootsInaccessibleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** API error mapping (spec §8): 404 unknown entry, 409 run in progress, 400 bad input. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Entry id does not exist. */
    public static final class MissingEntryException extends RuntimeException {
        public MissingEntryException(long id) {
            super("no media entry " + id);
        }
    }

    @ExceptionHandler(MissingEntryException.class)
    ResponseEntity<ProblemDetail> missingEntry(MissingEntryException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    /** Concept id does not exist (spec §4.2 workbench reads/mutations) — the house 404. */
    public static final class MissingConceptException extends RuntimeException {
        public MissingConceptException(long id) {
            super("no concept " + id);
        }
    }

    @ExceptionHandler(MissingConceptException.class)
    ResponseEntity<ProblemDetail> missingConcept(MissingConceptException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(ImportInProgressException.class)
    ResponseEntity<ProblemDetail> importInProgress(ImportInProgressException e) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        body.setProperty("runId", e.runId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(AllRootsInaccessibleException.class)
    ResponseEntity<ProblemDetail> allRootsInaccessible(AllRootsInaccessibleException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(io.github.nullotope.spicygallery.query.QueryParseException.class)
    ResponseEntity<ProblemDetail> queryParseError(io.github.nullotope.spicygallery.query.QueryParseException e) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        body.setProperty("token", e.token());
        body.setProperty("position", e.position());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Provider disabled or breaker open (spec §7) — a clean 503, never a 500. */
    @ExceptionHandler(io.github.nullotope.spicygallery.server.embed.EmbeddingUnavailableException.class)
    ResponseEntity<ProblemDetail> embeddingUnavailable(
            io.github.nullotope.spicygallery.server.embed.EmbeddingUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
    }

    /** Tagging provider disabled or breaker open (spec §7) — 503 with a retry hint. */
    @ExceptionHandler(io.github.nullotope.spicygallery.server.tagging.TaggingUnavailableException.class)
    ResponseEntity<ProblemDetail> taggingUnavailable(
            io.github.nullotope.spicygallery.server.tagging.TaggingUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, "5")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
    }

    /** The other GPU job holds the shared guard (spec §3.2) — 409 naming the running job. */
    @ExceptionHandler(io.github.nullotope.spicygallery.server.tagging.SchedulingConflictException.class)
    ResponseEntity<ProblemDetail> schedulingConflict(
            io.github.nullotope.spicygallery.server.tagging.SchedulingConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()));
    }

    /** A tagging predict whose source/frames cannot be read (Review Focus #2) — 422. */
    @ExceptionHandler(
            io.github.nullotope.spicygallery.server.tagging.TaggingController.UnreadableSourceException.class)
    ResponseEntity<ProblemDetail> unreadableSource(
            io.github.nullotope.spicygallery.server.tagging.TaggingController.UnreadableSourceException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                        e.getMessage()));
    }

    @ExceptionHandler(io.github.nullotope.spicygallery.server.tags.UnknownTagException.class)
    ResponseEntity<ProblemDetail> unknownTag(io.github.nullotope.spicygallery.server.tags.UnknownTagException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    /** Unknown concept NAME (the {@code concept:} metatag resolution, spec §5) — 400,
     *  the query's unknown-tag analogue. Unknown concept IDS are the house 404
     *  ({@link MissingConceptException}). */
    @ExceptionHandler(io.github.nullotope.spicygallery.server.concepts.UnknownConceptException.class)
    ResponseEntity<ProblemDetail> unknownConcept(
            io.github.nullotope.spicygallery.server.concepts.UnknownConceptException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    /** Duplicate concept name — names are identity keys (spec §3.5) — 409. */
    @ExceptionHandler(io.github.nullotope.spicygallery.server.concepts.DuplicateConceptException.class)
    ResponseEntity<ProblemDetail> duplicateConcept(
            io.github.nullotope.spicygallery.server.concepts.DuplicateConceptException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()));
    }

    /** Anchorless or stale concept (suggestions, concept: queries) — 409; the detail
     *  distinguishes "no anchor yet" from "stale, refresh it" for the workbench. */
    @ExceptionHandler(io.github.nullotope.spicygallery.server.concepts.ConceptStaleException.class)
    ResponseEntity<ProblemDetail> conceptStale(
            io.github.nullotope.spicygallery.server.concepts.ConceptStaleException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()));
    }

    /** Refresh with no input carrying a current-model vector (spec §5, Review Focus #4) — 422. */
    @ExceptionHandler(
            io.github.nullotope.spicygallery.server.concepts.ConceptController.NoCurrentModelInputsException.class)
    ResponseEntity<ProblemDetail> noCurrentModelInputs(
            io.github.nullotope.spicygallery.server.concepts.ConceptController.NoCurrentModelInputsException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage()));
    }

    /** Pool id does not exist (spec §5) — the house 404. */
    @ExceptionHandler(io.github.nullotope.spicygallery.server.pools.MissingPoolException.class)
    ResponseEntity<ProblemDetail> missingPool(
            io.github.nullotope.spicygallery.server.pools.MissingPoolException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    /** Duplicate pool name — names are identity keys (spec §3.3/§5) — 409. */
    @ExceptionHandler(io.github.nullotope.spicygallery.server.pools.DuplicatePoolException.class)
    ResponseEntity<ProblemDetail> duplicatePool(
            io.github.nullotope.spicygallery.server.pools.DuplicatePoolException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()));
    }

    /** Entry is not flatten-contained in the neighbors {@code ?pool=} target (spec §3.9) —
     *  the house 404; the detail names the pool (unknown pools 404 via MissingPoolException). */
    public static final class EntryNotInPoolException extends RuntimeException {
        public EntryNotInPoolException(long entryId, long poolId) {
            super("entry " + entryId + " is not in pool " + poolId);
        }
    }

    @ExceptionHandler(EntryNotInPoolException.class)
    ResponseEntity<ProblemDetail> entryNotInPool(EntryNotInPoolException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }


    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                        e.getMessage() == null ? "bad request" : e.getMessage()));
    }
}
