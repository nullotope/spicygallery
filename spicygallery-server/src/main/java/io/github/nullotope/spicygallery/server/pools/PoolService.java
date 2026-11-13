package io.github.nullotope.spicygallery.server.pools;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.store.EntryQueries;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore.TagRow;
import io.github.nullotope.spicygallery.server.store.PoolStore;
import io.github.nullotope.spicygallery.server.store.PoolStore.MemberRow;
import io.github.nullotope.spicygallery.server.store.PoolStore.PoolRow;
import io.github.nullotope.spicygallery.server.tags.TagService;
import io.github.nullotope.spicygallery.server.web.dto.TagDto;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pool semantics (spec §3.3–§3.7): the ordered-set service behind the pools grid. All
 * reads compose through ONE traversal — {@linkplain #flatten flatten} (spec §3.4) — so
 * reading order, {@code entryCount}, the cover, the q filter's content-tag matching and
 * the facet aggregation all agree. Filtering is deliberately IN-MEMORY over bulk-fetched
 * data (spec §3.7's documented choice: pools are small, the folders list is tiny); the
 * write path validates what the store's constraints cannot: the cycle rule, the reorder
 * permutation, the dedupe-silently append, and the pool-tag system-tag discipline.
 *
 * <p>Concurrency (three parked review races): EVERY mutating method first takes the store's
 * transaction-scoped advisory lock ({@link PoolStore#lockMutation}) — single-user gallery,
 * mutations are rare and interactive, so one global lock costs nothing and makes every
 * check-then-act section (cycle rule, free-name check, name dedupe, renumber) atomic against
 * concurrent mutations. The store's unique indexes stay as the last-resort backstop.</p>
 */
@Service
public class PoolService {

    /** The system tag every stored pool carries (spec §3.5) — the grid's type facet. */
    public static final String SYSTEM_TAG_POOL = "pool";

    /** The drafts' fixed type tag (spec §3.3): virtual, never stored on a pool. */
    public static final String SUGGESTED_REF = "pool_type:suggested";

    /**
     * One pool tile (spec §3.7): {@code entryCount} and {@code cover} are FLATTENED
     * values (a parent pool shows 30, not 2; empty pool → null cover / placeholder
     * tile); {@code tags} are the pool's OWN tags incl. the system tag; {@code origin} is
     * null for manual pools ({@code ''} = root top level). {@code member} is the §4.3
     * add-to-pool flag: non-null ONLY on the {@code GET /api/pools?entryId=} variant
     * (true iff the entry is flattened-contained — grandchildren count), absent on the
     * wire otherwise (NON_NULL house style).
     */
    public record PoolDto(long id, String name, String description, long entryCount, Instant updatedAt,
                          Origin origin, List<TagDto> tags, EntryQueries.EntryDto cover,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Boolean member) {

        /** The folder a pool was seeded from (spec §3.3). */
        public record Origin(String rootName, String dirPath) {}
    }

    /**
     * One detail row: exactly one of {@code entry}/{@code pool} is non-null. The nested
     * pool is a SUMMARY (id/name/entryCount — its flattened size); position is the row's
     * slot in the pool's unified 1..N member sequence.
     */
    public record MemberDto(long memberId, int position, EntryQueries.EntryDto entry, PoolDto pool) {}

    /** The pool page payload (spec §3.9): tile + members in position order. */
    public record PoolDetail(PoolDto pool, List<MemberDto> members) {}

    /**
     * A folder suggestion (spec §3.3) — a directory with >= 2 direct entries, retired when
     * it is an existing pool's origin. The service-level shape the {@link #folders} list
     * returns and {@link #facets} consumes; {@code cover} (the folder's first natural-ordered
     * entry, the draft tile's thumbnail) is null when absent. Drafts are virtual, never stored.
     */
    public record FolderSuggestion(String rootName, String dirPath, long entryCount, EntryQueries.EntryDto cover) {

        /** Draft-only convenience: path/count pairs without a cover (facet tests). */
        public FolderSuggestion(String rootName, String dirPath, long entryCount) {
            this(rootName, dirPath, entryCount, null);
        }
    }

    private final PoolStore poolStore;
    private final EntryQueries entries;
    private final JdbcTagStore tagStore;
    private final TagService tagService;
    private final SpicyGalleryProperties properties;

    public PoolService(PoolStore poolStore, EntryQueries entries, JdbcTagStore tagStore,
                       TagService tagService, SpicyGalleryProperties properties) {
        this.poolStore = poolStore;
        this.entries = entries;
        this.tagStore = tagStore;
        this.tagService = tagService;
        this.properties = properties;
    }

    // --- reads -----------------------------------------------------------------

    /** The pool page payload; unknown id → {@link MissingPoolException} (404 in T4). */
    @Transactional(readOnly = true)
    public PoolDetail detail(long id) {
        Snapshot s = load();
        PoolRow row = s.pool(id);
        if (row == null) {
            throw new MissingPoolException(id);
        }
        List<MemberDto> members = new ArrayList<>();
        for (MemberRow r : s.membersByPool().getOrDefault(id, List.of())) {
            if (r.entryId() != null) {
                EntryQueries.EntryDto entry = s.entryById().get(r.entryId());
                if (entry != null) {
                    // a member row's entry cascades away on delete (spec §3.6); a row whose
                    // entry is no longer 'ok' drops out of the reading surfaces the same way
                    members.add(new MemberDto(r.memberId(), r.position(), entry, null));
                }
            } else {
                members.add(new MemberDto(r.memberId(), r.position(), null, summary(s.pool(r.childPoolId()), s)));
            }
        }
        return new PoolDetail(toDto(row, s, null), List.copyOf(members));
    }

    /**
     * The folders list (spec §3.3): directories with >= 2 direct entries (any depth, any
     * root; {@code ''} = root top level), count desc, minus the ones an existing pool was
     * seeded from — the pools grid's draft tiles (virtual, never stored) and the facets
     * endpoint's drafts. Cover = the folder's first natural-ordered entry.
     */
    @Transactional(readOnly = true)
    public List<FolderSuggestion> folders() {
        return entries.folderSuggestions().stream()
                .map(f -> new FolderSuggestion(f.rootName(), f.dirPath(), f.entryCount(), f.cover()))
                .toList();
    }

    /**
     * The pools grid (spec §3.7) without membership flags — {@link #list(String, Long)}
     * with no entryId.
     */
    @Transactional(readOnly = true)
    public List<PoolDto> list(String q) {
        return list(q, null);
    }

    /**
     * The pools grid (spec §3.7): server-side filter over stored pools, id-ascending.
     * A term matches if it is a case-insensitive substring of name or description, or an
     * EXACT match of a tag's full reference (bare name for {@code general},
     * {@code category:name} otherwise — so {@code pool_type:pool} matches via the system
     * tag while bare {@code pool} matches nothing) among the pool's OWN ∪ FLATTENED
     * CONTENT tags. Terms AND across whitespace; empty/absent {@code q} → all pools.
     * With {@code entryId} (spec §4.3's lightweight add-to-pool variant) every tile
     * additionally carries {@code member}: the entry's FLATTENED containment.
     */
    @Transactional(readOnly = true)
    public List<PoolDto> list(String q, Long entryId) {
        Snapshot s = load();
        List<String> terms = terms(q);
        List<PoolDto> out = new ArrayList<>();
        for (PoolRow row : s.pools()) {
            if (matches(row, references(s, row.id()), terms)) {
                out.add(toDto(row, s, entryId));
            }
        }
        return List.copyOf(out);
    }

    /**
     * The pools sidebar (spec §3.7): tag counts over the SAME filtered set as
     * {@link #list}, counting each matching pool once per tag of its own ∪ flattened
     * content union, ordered pool_type first (facet grouping follows the category order),
     * then count desc / name asc. Drafts contribute the fixed {@code suggested}
     * pseudo-facet — they match text terms via their {@code rootName/dirPath} and
     * {@code pool_type:suggested} always — only when at least one draft matches.
     */
    @Transactional(readOnly = true)
    public List<EntryQueries.FacetDto> facets(String q, List<FolderSuggestion> drafts) {
        Snapshot s = load();
        List<String> terms = terms(q);
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (PoolRow row : s.pools()) {
            if (matches(row, references(s, row.id()), terms)) {
                for (Long tagId : s.tagUnion(row.id())) {
                    counts.merge(tagId, 1L, Long::sum);
                }
            }
        }
        Map<Long, TagRow> rowsById = tagStore.byIds(counts.keySet()).stream()
                .collect(Collectors.toMap(TagRow::id, r -> r));
        List<EntryQueries.FacetDto> out = new ArrayList<>();
        for (Map.Entry<Long, Long> e : counts.entrySet()) {
            TagRow t = rowsById.get(e.getKey());
            out.add(new EntryQueries.FacetDto(t.name(), t.category(), e.getValue()));
        }
        long suggested = drafts == null ? 0
                : drafts.stream().filter(d -> draftMatches(d, terms)).count();
        if (suggested > 0) {
            out.add(new EntryQueries.FacetDto("suggested", TagCategory.POOL_TYPE.wireName(), suggested));
        }
        out.sort(Comparator
                .comparingInt((EntryQueries.FacetDto f) -> TagCategory.fromWireName(f.category()).ordinal())
                .thenComparing(Comparator.comparingLong(EntryQueries.FacetDto::count).reversed())
                .thenComparing(EntryQueries.FacetDto::name));
        return List.copyOf(out);
    }

    /**
     * The neighbors helper (spec §3.9 {@code ?pool=}): the entry's 1-based position within
     * the pool's FLATTEN and the flattened total, empty when the entry is not
     * flattened-contained (an unknown pool throws — see {@link #poolNeighbors}).
     */
    @Transactional(readOnly = true)
    public Optional<int[]> poolPosition(long poolId, long entryId) {
        return poolNeighbors(poolId, entryId).map(n -> new int[] {n.position(), (int) n.total()});
    }

    /**
     * One pool-scoped reading row (spec §3.9 {@code ?pool=}): prev/next in FLATTEN order —
     * the entry may sit at ANY depth, grandchildren count — plus the entry's 1-based
     * position and the flattened total. {@code prevId}/{@code nextId} are null at the ends.
     */
    public record PoolNeighbors(Long prevId, Long nextId, int position, long total) {}

    /**
     * The pool-scoped neighbors (spec §3.9 {@code ?pool=}): reading order is the pool's
     * FLATTEN, empty when the entry is not flattened-contained; an unknown pool throws
     * {@link MissingPoolException} — both are the endpoint's 404s.
     */
    @Transactional(readOnly = true)
    public Optional<PoolNeighbors> poolNeighbors(long poolId, long entryId) {
        Snapshot s = load();
        if (s.pool(poolId) == null) {
            throw new MissingPoolException(poolId);
        }
        List<EntryQueries.EntryDto> flat = s.flatten(poolId);
        for (int i = 0; i < flat.size(); i++) {
            if (flat.get(i).id() == entryId) {
                return Optional.of(new PoolNeighbors(
                        i > 0 ? flat.get(i - 1).id() : null,
                        i + 1 < flat.size() ? flat.get(i + 1).id() : null,
                        i + 1, flat.size()));
            }
        }
        return Optional.empty();
    }

    /**
     * Flatten (spec §3.4) — the one traversal everything reads through. Depth-first over
     * members in position order: an entry yields itself, a child pool recurses into its
     * own flatten. Package-private for tests; production reads go through the DTO methods
     * above. DEFENSIVE visited set (Review Focus #1): the write path rejects cycles, but
     * the traversal never trusts it — a pool already visited in this traversal is skipped.
     */
    List<EntryQueries.EntryDto> flatten(long poolId) {
        return load().flatten(poolId);
    }

    // --- creates -----------------------------------------------------------------

    /** Manual create (strict): blank name → 400 vehicle; duplicate name → 409 vehicle. */
    @Transactional
    public PoolDetail createManual(String name, String description) {
        poolStore.lockMutation();
        String trimmed = requireName(name);
        requireFreeName(trimmed);
        PoolRow row = poolStore.create(trimmed, blankToNull(description), null, null);
        attachSystemTag(row.id());
        return detail(row.id());
    }
    /**
     * Folder-pool create (spec §3.3): members = the folder's DIRECT entries in natural
     * order, origin recorded. Defaulted names (null/blank) derive from the directory
     * basename and AUTO-DEDUPE {@code (2)}, {@code (3)}… — a one-click action never fails;
     * explicit names are strict (duplicate → 409 vehicle). A folder with 0 direct entries
     * → 400 vehicle "no entries in folder" (spec §5 verbatim). Re-seeding a folder is
     * legal (multi-pool membership); retirement is the folders list's job.
     */
    @Transactional
    public PoolDetail createFromFolder(String rootName, String dirPath, String name, String description) {
        poolStore.lockMutation();
        String dir = dirPath == null ? "" : dirPath;
        if (properties.libraries().stream().noneMatch(l -> l.name().equals(rootName))) {
            throw new IllegalArgumentException("unknown library root: " + rootName);
        }
        List<EntryQueries.EntryDto> direct = entries.directEntries(rootName, dir);
        if (direct.isEmpty()) {
            throw new IllegalArgumentException("no entries in folder");
        }
        String finalName;
        if (name == null || name.isBlank()) {
            finalName = dedupeName(defaultFolderName(rootName, dir));
        } else {
            finalName = name.trim();
            requireFreeName(finalName);
        }
        PoolRow row = poolStore.create(finalName, blankToNull(description), rootName, dir);
        poolStore.addMembers(row.id(), direct.stream().map(EntryQueries.EntryDto::id).toList(), List.of());
        attachSystemTag(row.id());
        return detail(row.id());
    }

    /**
     * Import follow-up create (spec §3.8): the pool an import run drafts after completing.
     * The name AUTO-DEDUPES like folder pools' defaulted names ({@code (2)}, {@code (3)}…)
     * — a one-click follow-up never fails; members are appended in the order given (the
     * run's added entries, already ordered by the caller) and the system tag is attached.
     * Returns the new pool's id for {@code import_run.pool_id}.
     */
    @Transactional
    public long createImported(String name, List<Long> entryIds) {
        poolStore.lockMutation();
        PoolRow row = poolStore.create(dedupeName(name), null, null, null);
        poolStore.addMembers(row.id(), entryIds == null ? List.of() : entryIds, List.of());
        attachSystemTag(row.id());
        return row.id();
    }

    // --- mutations (spec §3.6) ------------------------------------------------------

    /**
     * Append: entries in the order given, then sub-pools in the order given, at the end.
     * Duplicates (within the request or against existing members) are SKIPPED SILENTLY —
     * idempotent re-posts are the modal's retry story. Unknown entry ids → 400 vehicle
     * (spec §5); the cycle rule rejects child C for pool P iff C == P or P is reachable
     * from C through child-pool edges, message "would create a cycle" verbatim (§3.3).
     * Returns the number of member rows actually INSERTED — skipped duplicates count as
     * not-inserted — i.e. the §3.6 member-row growth across the call (the wire's
     * {@code added}).
     */
    @Transactional
    public int addMembers(long id, List<Long> entryIds, List<Long> poolIds) {
        poolStore.lockMutation();
        Snapshot s = load();
        requirePool(s, id);
        List<Long> requestedEntries = entryIds == null ? List.of() : entryIds;
        List<Long> requestedPools = poolIds == null ? List.of() : poolIds;
        for (Long entryId : requestedEntries) {
            if (entryId == null || entries.byId(entryId).isEmpty()) {
                throw new IllegalArgumentException("no media entry " + entryId);
            }
        }
        for (Long childId : requestedPools) {
            if (childId == null || s.pool(childId) == null) {
                // the store's FK is the backstop; a clean 400 beats a constraint violation
                throw new IllegalArgumentException("no pool " + childId);
            }
            if (childId == id || reachable(id, childId, s.membersByPool())) {
                throw new IllegalArgumentException("would create a cycle");
            }
        }
        List<MemberRow> current = s.membersByPool().getOrDefault(id, List.of());
        Set<Long> presentEntries = current.stream().map(MemberRow::entryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> presentPools = current.stream().map(MemberRow::childPoolId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        List<Long> newEntries = requestedEntries.stream()
                .filter(e -> !presentEntries.contains(e)).distinct().toList();
        List<Long> newPools = requestedPools.stream()
                .filter(p -> !presentPools.contains(p)).distinct().toList();
        if (newEntries.isEmpty() && newPools.isEmpty()) {
            return 0; // fully idempotent re-post: nothing inserted
        }
        return poolStore.addMembers(id, newEntries, newPools).size();
    }

    /**
     * Reorder: {@code orderedMemberIds} must be a PERMUTATION of the pool's current
     * member ROW ids (surrogate ids — entries and sub-pools reorder uniformly); anything
     * else is a stale client → 400 vehicle. Positions are rewritten 1..N.
     */
    @Transactional
    public PoolDetail reorder(long id, List<Long> orderedMemberIds) {
        poolStore.lockMutation();
        Snapshot s = load();
        requirePool(s, id);
        List<Long> ordered = orderedMemberIds == null ? List.of() : orderedMemberIds;
        Set<Long> currentIds = s.membersByPool().getOrDefault(id, List.of()).stream()
                .map(MemberRow::memberId).collect(Collectors.toSet());
        // a permutation is a same-size, all-present, DISTINCT ordering — a repeated row id
        // would pass size/containsAll while renumber writes it twice and skips another row
        // (duplicate/gapped positions, and V5's unique indexes do not backstop positions)
        if (ordered.size() != currentIds.size() || !currentIds.containsAll(ordered)
                || ordered.stream().distinct().count() != currentIds.size()) {
            throw new IllegalArgumentException(
                    "orderedMemberIds must be a permutation of the pool's current member rows");
        }
        poolStore.renumber(id, ordered);
        return detail(id);
    }

    /**
     * Remove: row removed, survivors renumbered 1..N (spec §3.6). An unknown member row
     * (or one of another pool) is an idempotent no-op — DELETE stays 204 on the wire.
     */
    @Transactional
    public PoolDetail removeMember(long id, long memberId) {
        poolStore.lockMutation();
        Snapshot s = load();
        requirePool(s, id);
        List<MemberRow> current = s.membersByPool().getOrDefault(id, List.of());
        if (current.stream().anyMatch(r -> r.memberId() == memberId)) {
            poolStore.removeMember(memberId);
            List<Long> survivors = current.stream().filter(r -> r.memberId() != memberId)
                    .map(MemberRow::memberId).toList();
            if (!survivors.isEmpty()) {
                poolStore.renumber(id, survivors);
            }
        }
        return detail(id);
    }

    /**
     * Pool tags (spec §3.5): replace-set via {@link TagService#resolvePoolTags}
     * (normalize → alias-resolve → create-missing as {@code general}; NO implications, NO
     * provenance) plus the system-tag discipline: {@code pool_type:pool} is ALWAYS present
     * — a patch that omits it gets it re-added.
     */
    @Transactional
    public PoolDetail setTags(long id, List<String> rawNames) {
        poolStore.lockMutation();
        Snapshot s = load();
        requirePool(s, id);
        List<Long> ids = tagService.resolvePoolTags(rawNames == null ? List.of() : rawNames).stream()
                .map(TagRow::id).collect(Collectors.toCollection(ArrayList::new));
        long systemTagId = tagService.systemTag(SYSTEM_TAG_POOL, TagCategory.POOL_TYPE).id();
        if (!ids.contains(systemTagId)) {
            ids.add(systemTagId);
        }
        poolStore.setTags(id, ids);
        return detail(id);
    }

    /**
     * Rename / re-describe (spec §3.9 PATCH): partial update — a null field keeps the
     * current value, an explicit (possibly blank) description replaces it ('' clears).
     * An explicit name is strict like create, minus the self-collision: any OTHER pool
     * holding the trimmed name → {@link DuplicatePoolException}; renaming to the pool's
     * own current name is a no-op; blank → IAE.
     */
    @Transactional
    public PoolDetail rename(long id, String name, String description) {
        poolStore.lockMutation();
        PoolRow row = poolStore.byId(id).orElseThrow(() -> new MissingPoolException(id));
        String newName = name == null ? row.name() : requireName(name);
        if (name != null) {
            requireFreeName(newName, id); // self excluded: rename-to-own-name stays a no-op
        }
        poolStore.rename(id, newName, description == null ? row.description() : blankToNull(description));
        return detail(id);
    }

    /**
     * Delete (spec §3.6): the store's cascade removes the pool's member rows, pool tags
     * and the parent membership rows it appears in — its whole subtree goes with it.
     */
    @Transactional
    public void delete(long id) {
        poolStore.lockMutation();
        requirePool(load(), id);
        poolStore.delete(id);
    }

    // --- internals -----------------------------------------------------------------

    /**
     * One bulk read pass over the whole pool graph (spec §3.7's documented in-memory
     * choice: pools are small, so every service call composes from a fixed set of queries
     * instead of per-pool SQL). {@code entryById} holds only {@code status='ok'} rows —
     * deleted entries cascade out of member rows, non-ok ones drop out of every read.
     */
    private Snapshot load() {
        List<PoolRow> poolRows = poolStore.list();
        Map<Long, List<MemberRow>> membersByPool = new LinkedHashMap<>();
        Set<Long> referencedEntries = new LinkedHashSet<>();
        for (MemberRow r : poolStore.allMembers()) {
            membersByPool.computeIfAbsent(r.poolId(), k -> new ArrayList<>()).add(r);
            if (r.entryId() != null) {
                referencedEntries.add(r.entryId());
            }
        }
        Map<Long, EntryQueries.EntryDto> entryById = new LinkedHashMap<>();
        for (EntryQueries.EntryDto dto : entries.byIds(List.copyOf(referencedEntries), List.of())) {
            entryById.put(dto.id(), dto);
        }
        return new Snapshot(poolRows, membersByPool, entryById,
                tagStore.tagIdsByEntry(referencedEntries), poolStore.allTagIds());
    }

    /** The bulk-fetched pool graph; per-pool derivations (flatten, tag union) are methods. */
    private record Snapshot(List<PoolRow> pools,
                            Map<Long, List<MemberRow>> membersByPool,
                            Map<Long, EntryQueries.EntryDto> entryById,
                            Map<Long, Set<Long>> tagIdsByEntry,
                            Map<Long, List<Long>> ownTagIdsByPool) {

        PoolRow pool(long id) {
            return pools.stream().filter(p -> p.id() == id).findFirst().orElse(null);
        }

        /** DFS over members in position order (allMembers is position-ordered per pool). */
        List<EntryQueries.EntryDto> flatten(long poolId) {
            return flatten(poolId, new HashSet<>());
        }

        private List<EntryQueries.EntryDto> flatten(long poolId, Set<Long> visited) {
            if (!visited.add(poolId)) {
                return List.of(); // defensive: cycles are prevented at write; never trusted here
            }
            List<EntryQueries.EntryDto> out = new ArrayList<>();
            for (MemberRow r : membersByPool.getOrDefault(poolId, List.of())) {
                if (r.entryId() != null) {
                    EntryQueries.EntryDto dto = entryById.get(r.entryId());
                    if (dto != null) {
                        out.add(dto); // gone / not 'ok' → the flatten skips it (spec §3.6)
                    }
                } else {
                    out.addAll(flatten(r.childPoolId(), visited));
                }
            }
            return out;
        }

        /** Own ∪ flattened-content tag ids (spec §3.7's union) — q matching and facets. */
        Set<Long> tagUnion(long poolId) {
            Set<Long> union = new LinkedHashSet<>(ownTagIdsByPool.getOrDefault(poolId, List.<Long>of()));
            for (EntryQueries.EntryDto e : flatten(poolId)) {
                union.addAll(tagIdsByEntry.getOrDefault(e.id(), Set.of()));
            }
            return union;
        }
    }

    private PoolDto toDto(PoolRow row, Snapshot s, Long entryId) {
        List<Long> ownIds = s.ownTagIdsByPool().getOrDefault(row.id(), List.<Long>of());
        Map<Long, TagRow> tagRowsById = tagStore.byIds(ownIds).stream()
                .collect(Collectors.toMap(TagRow::id, r -> r));
        List<TagDto> tags = ownIds.stream().map(tagRowsById::get).filter(Objects::nonNull)
                .map(t -> new TagDto(t.id(), t.name(), t.category(), t.postCount(), null)) // no provenance, §3.5
                .toList();
        List<EntryQueries.EntryDto> flat = s.flatten(row.id());
        // §4.3: member only on the ?entryId= variant — flattened containment, grandchildren count
        Boolean member = entryId == null ? null : flat.stream().anyMatch(e -> e.id() == entryId);
        return new PoolDto(row.id(), row.name(), row.description(), flat.size(), row.updatedAt(),
                row.originRoot() == null ? null : new PoolDto.Origin(row.originRoot(), row.originDir()),
                tags, flat.isEmpty() ? null : flat.get(0), member);
    }

    /** The child-pool member summary: id/name/entryCount (flattened); the rest stays null. */
    private PoolDto summary(PoolRow child, Snapshot s) {
        return new PoolDto(child.id(), child.name(), null, s.flatten(child.id()).size(),
                null, null, List.of(), null, null);
    }

    private static void requirePool(Snapshot s, long id) {
        if (s.pool(id) == null) {
            throw new MissingPoolException(id);
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("pool name is required");
        }
        return name.trim();
    }

    /** Explicit names are strict (409 vehicle): any existing pool with the trimmed name rejects. */
    private void requireFreeName(String trimmed) {
        // checked against the store's rows here (clean 409 vehicle); the unique index backs it
        if (poolStore.list().stream().anyMatch(p -> p.name().equals(trimmed))) {
            throw new DuplicatePoolException(trimmed);
        }
    }

    /**
     * The rename variant: {@code selfId}'s own row is EXCLUDED from the duplicate check —
     * renaming a pool to its own current name is a no-op, never a self-409 (spec §3.9 PATCH).
     */
    private void requireFreeName(String trimmed, long selfId) {
        // checked against the store's rows here (clean 409 vehicle); the unique index backs it
        if (poolStore.list().stream().anyMatch(p -> p.id() != selfId && p.name().equals(trimmed))) {
            throw new DuplicatePoolException(trimmed);
        }
    }

    private String dedupeName(String base) {
        Set<String> taken = poolStore.list().stream().map(PoolRow::name).collect(Collectors.toSet());
        String candidate = base;
        int n = 2;
        while (taken.contains(candidate)) {
            candidate = base + " (" + n++ + ")";
        }
        return candidate;
    }

    private static String defaultFolderName(String rootName, String dirPath) {
        return dirPath.isEmpty() ? rootName : dirPath.substring(dirPath.lastIndexOf('/') + 1);
    }

    private static String blankToNull(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void attachSystemTag(long poolId) {
        poolStore.setTags(poolId, List.of(tagService.systemTag(SYSTEM_TAG_POOL, TagCategory.POOL_TYPE).id()));
    }

    /** DFS over child-pool edges: is {@code target} reachable FROM {@code from}? (spec §3.3) */
    private static boolean reachable(long target, long from, Map<Long, List<MemberRow>> membersByPool) {
        Deque<Long> stack = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        stack.push(from);
        seen.add(from);
        while (!stack.isEmpty()) {
            long current = stack.pop();
            if (current == target) {
                return true;
            }
            for (MemberRow r : membersByPool.getOrDefault(current, List.of())) {
                if (r.childPoolId() != null && seen.add(r.childPoolId())) {
                    stack.push(r.childPoolId());
                }
            }
        }
        return false;
    }

    private static List<String> terms(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        return List.of(q.trim().split("\\s+"));
    }

    /** Full references (bare for general, category:name otherwise) of the pool's tag union. */
    private Set<String> references(Snapshot s, long poolId) {
        return tagStore.byIds(s.tagUnion(poolId)).stream().map(PoolService::reference)
                .collect(Collectors.toSet());
    }

    private static String reference(TagRow t) {
        return TagCategory.GENERAL.wireName().equals(t.category())
                ? t.name()
                : t.category() + ":" + t.name();
    }

    private static boolean matches(PoolRow row, Set<String> references, List<String> terms) {
        for (String term : terms) {
            String t = term.toLowerCase(Locale.ROOT);
            boolean hit = (row.name() != null && row.name().toLowerCase(Locale.ROOT).contains(t))
                    || (row.description() != null && row.description().toLowerCase(Locale.ROOT).contains(t))
                    || references.contains(t);
            if (!hit) {
                return false; // AND across terms
            }
        }
        return true;
    }

    private static boolean draftMatches(FolderSuggestion d, List<String> terms) {
        String path = (d.rootName() + "/" + d.dirPath()).toLowerCase(Locale.ROOT);
        for (String term : terms) {
            String t = term.toLowerCase(Locale.ROOT);
            if (!path.contains(t) && !t.equals(SUGGESTED_REF)) {
                return false;
            }
        }
        return true;
    }
}
