package io.github.nullotope.spicygallery.server.concepts;

import io.github.nullotope.spicygallery.server.concepts.ConceptService.ApplyResult;
import io.github.nullotope.spicygallery.server.concepts.ConceptService.ConceptDto;
import io.github.nullotope.spicygallery.server.concepts.ConceptService.Members;
import io.github.nullotope.spicygallery.server.concepts.ConceptService.Suggestions;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Concepts API (spec §3.5 plus the §4.2 workbench members list): the 12 endpoints and
 * {@code GET /api/concepts/{id}/members}. Error mapping lives in
 * {@code GlobalExceptionHandler}: unknown concept id 404 (house pattern), unknown
 * concept name 400 ({@code concept:} metatag), duplicate 409, stale/anchorless 409,
 * refresh-without-inputs 422, provider down 503, bad input 400.
 */
@RestController
public class ConceptController {

    public record CreateRequest(String name, String phrase) {}

    public record PatchRequest(String name, String phrase) {}

    public record EntryIdsRequest(List<Long> entryIds) {}

    public record Added(long added) {}

    public record Excluded(long excluded) {}

    /**
     * Refresh found no input carrying a current-model vector (model changed, library
     * not re-embedded) — 422 with the recovery hint (spec §5, the
     * {@code TaggingController.UnreadableSourceException} pattern: controller-nested,
     * mapped in the global handler).
     */
    public static final class NoCurrentModelInputsException extends RuntimeException {
        public NoCurrentModelInputsException(String name) {
            super("concept '" + name
                    + "' has no inputs for the current model; re-embed entries or re-save the phrase");
        }
    }

    private final ConceptService concepts;

    public ConceptController(ConceptService concepts) {
        this.concepts = concepts;
    }

    /** {@code sort=name|recent} (default recent); unknown values are a 400 — the store
     *  itself treats anything non-"recent" as name-asc, so the contract is pinned here. */
    @GetMapping("/api/concepts")
    public List<ConceptDto> list(@RequestParam Optional<String> sort) {
        String value = sort.map(String::trim).filter(s -> !s.isEmpty()).orElse("recent");
        if (!"name".equals(value) && !"recent".equals(value)) {
            throw new IllegalArgumentException("sort must be name or recent: " + value);
        }
        return concepts.list(value);
    }

    @PostMapping("/api/concepts")
    public ResponseEntity<ConceptDto> create(@RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(concepts.create(request.name(), request.phrase()));
    }

    @GetMapping("/api/concepts/{id}")
    public ConceptDto get(@PathVariable long id) {
        return concepts.get(id);
    }

    /** The workbench member lists (spec §4.2): real examples/excluded tiles. */
    @GetMapping("/api/concepts/{id}/members")
    public Members members(@PathVariable long id) {
        return concepts.members(id);
    }

    @PatchMapping("/api/concepts/{id}")
    public ConceptDto patch(@PathVariable long id, @RequestBody PatchRequest request) {
        return concepts.patch(id, request.name(), request.phrase());
    }

    @DeleteMapping("/api/concepts/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        concepts.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/concepts/{id}/examples")
    public Added addExamples(@PathVariable long id, @RequestBody EntryIdsRequest request) {
        return new Added(concepts.addExamples(id, request.entryIds()));
    }

    @DeleteMapping("/api/concepts/{id}/examples/{entryId}")
    public ResponseEntity<Void> removeExample(@PathVariable long id, @PathVariable long entryId) {
        concepts.removeExample(id, entryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/concepts/{id}/exclusions")
    public Excluded addExclusions(@PathVariable long id, @RequestBody EntryIdsRequest request) {
        return new Excluded(concepts.addExclusions(id, request.entryIds()));
    }

    @DeleteMapping("/api/concepts/{id}/exclusions/{entryId}")
    public ResponseEntity<Void> removeExclusion(@PathVariable long id, @PathVariable long entryId) {
        concepts.removeExclusion(id, entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/concepts/{id}/suggestions")
    public Suggestions suggestions(@PathVariable long id, @RequestParam Optional<Integer> limit) {
        return concepts.suggestions(id, limit.orElse(null));
    }

    @PostMapping("/api/concepts/{id}/apply")
    public ApplyResult apply(@PathVariable long id) {
        return concepts.apply(id);
    }

    @PostMapping("/api/concepts/{id}/refresh")
    public ConceptDto refresh(@PathVariable long id) {
        return concepts.refresh(id);
    }
}
