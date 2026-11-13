package io.github.nullotope.spicygallery.server.pools;

import io.github.nullotope.spicygallery.server.store.EntryQueries;
import io.github.nullotope.spicygallery.server.web.dto.TagDto;
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
 * Pools API (spec §3.9): the pools grid, detail page and member curation. Thin
 * pass-through over {@link PoolService} — the cycle rule, the reorder permutation and
 * the dedupe-silently append all live in the service. Error mapping lives in
 * {@code GlobalExceptionHandler}: unknown pool 404, duplicate name 409, bad input
 * (blank name, unknown entry/child pool, "would create a cycle", non-permutation,
 * "no entries in folder") 400 via the {@code IllegalArgumentException} mapper.
 */
@RestController
public class PoolController {

    /** Manual body {@code {name, description?}}; folder adds rootName/dirPath — a present
     *  {@code rootName} routes to the folder create (spec §3.3). */
    public record CreateRequest(String name, String description, String rootName, String dirPath) {}

    public record PatchRequest(String name, String description) {}

    public record TagsRequest(List<String> tags) {}

    public record MemberIdsRequest(List<Long> entryIds, List<Long> poolIds) {}

    public record OrderRequest(List<Long> orderedMemberIds) {}

    public record Added(long added) {}

    private final PoolService pools;

    public PoolController(PoolService pools) {
        this.pools = pools;
    }

    /**
     * The pools grid (spec §3.7): plain whitespace terms — NOT the entries query language.
     * With {@code entryId} (spec §4.3, the entry page's add-to-pool dropdown) every tile
     * carries {@code member}; without it the key is absent (house NON_NULL style).
     */
    @GetMapping("/api/pools")
    public List<PoolService.PoolDto> list(@RequestParam Optional<String> q,
                                          @RequestParam Optional<Long> entryId) {
        return pools.list(q.orElse(null), entryId.orElse(null));
    }

    /** Folder suggestions (spec §3.3): the pools grid's draft tiles — virtual, never stored. */
    @GetMapping("/api/pools/folders")
    public List<PoolService.FolderSuggestion> folders() {
        return pools.folders();
    }

    /** The pools sidebar (spec §3.7); drafts are the folders query's output. */
    @GetMapping("/api/pools/facets")
    public List<EntryQueries.FacetDto> facets(@RequestParam Optional<String> q) {
        return pools.facets(q.orElse(null), pools.folders());
    }

    /** Folder create when {@code rootName} is present (spec §3.3), manual otherwise → 201. */
    @PostMapping("/api/pools")
    public ResponseEntity<PoolService.PoolDto> create(@RequestBody CreateRequest request) {
        PoolService.PoolDetail detail = request.rootName() == null
                ? pools.createManual(request.name(), request.description())
                : pools.createFromFolder(request.rootName(), request.dirPath(),
                        request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(detail.pool());
    }

    @GetMapping("/api/pools/{id}")
    public PoolService.PoolDetail get(@PathVariable long id) {
        return pools.detail(id);
    }

    /** Rename / re-describe (spec §3.9): partial update → the updated PoolDto. */
    @PatchMapping("/api/pools/{id}")
    public PoolService.PoolDto patch(@PathVariable long id, @RequestBody PatchRequest request) {
        return pools.rename(id, request.name(), request.description()).pool();
    }

    /** Pool tags replace-set (spec §3.5) → the pool's own tags incl. the re-added system tag. */
    @PatchMapping("/api/pools/{id}/tags")
    public List<TagDto> setTags(@PathVariable long id, @RequestBody TagsRequest request) {
        return pools.setTags(id, request.tags()).pool().tags();
    }

    /**
     * Append (spec §3.6): entries in the order given, then sub-pools; duplicates are
     * skipped silently, so {@code added} — the modal's toast number — is the member-row
     * growth across the call: exactly the count of rows the service actually inserted,
     * not the requested count.
     */
    @PostMapping("/api/pools/{id}/members")
    public Added addMembers(@PathVariable long id, @RequestBody MemberIdsRequest request) {
        return new Added(pools.addMembers(id, request.entryIds(), request.poolIds()));
    }

    /** Reorder (spec §3.6): {@code orderedMemberIds} must be a member-row permutation → refreshed detail. */
    @PostMapping("/api/pools/{id}/order")
    public PoolService.PoolDetail order(@PathVariable long id, @RequestBody OrderRequest request) {
        return pools.reorder(id, request.orderedMemberIds());
    }

    /** Remove (spec §3.6): an unknown/foreign member row is an idempotent no-op — still 204. */
    @DeleteMapping("/api/pools/{id}/members/{memberId}")
    public ResponseEntity<Void> removeMember(@PathVariable long id, @PathVariable long memberId) {
        pools.removeMember(id, memberId);
        return ResponseEntity.noContent().build();
    }

    /** Delete (spec §3.6): the subtree and every parent membership row cascade away. */
    @DeleteMapping("/api/pools/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        pools.delete(id);
        return ResponseEntity.noContent().build();
    }
}
