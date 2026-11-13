package io.github.nullotope.spicygallery.server.pools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.pools.PoolService.FolderSuggestion;
import io.github.nullotope.spicygallery.server.pools.PoolService.MemberDto;
import io.github.nullotope.spicygallery.server.pools.PoolService.PoolDetail;
import io.github.nullotope.spicygallery.server.pools.PoolService.PoolDto;
import io.github.nullotope.spicygallery.server.store.EntryQueries;
import io.github.nullotope.spicygallery.server.store.JdbcMediaStoreSink;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore.TagRow;
import io.github.nullotope.spicygallery.server.store.PoolStore;
import io.github.nullotope.spicygallery.server.tags.TagService;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PoolService semantics (spec §3.3–§3.7): flatten order, cycle rule, folder seeding with
 * auto-dedupe, pool tags with the system-tag discipline, the pools-page q filter and the
 * facet aggregation over the same filtered set. Real stores on the Testcontainers PG
 * (no Mockito) — the service IS the SQL orchestration under test.
 */
@SpringBootTest
class PoolServiceTest extends PostgresBase {

    @Autowired PoolService poolsService;
    @Autowired PoolStore poolStore;
    @Autowired TagService tagService;
    @Autowired JdbcTagStore tags;
    @Autowired JdbcMediaStoreSink sink;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from import_run").update(); // BEFORE pool: pool_id FK (no cascade)
        jdbc.sql("delete from pool").update(); // cascades pool_member + pool_tag
        jdbc.sql("delete from media_tag").update();
        jdbc.sql("delete from tag_implication").update();
        jdbc.sql("delete from tag_alias").update();
        jdbc.sql("delete from tag").update();
        jdbc.sql("delete from media_entry").update();
    }

    // --- fixtures -------------------------------------------------------------

    private long entry(String fileName) {
        return sink.insert(new NewEntry(Kind.IMAGE, "library", fileName, fileName, "png", "image/png",
                1, "hash-" + fileName, 8, 4, null, Instant.parse("2026-01-01T00:00:00Z"))).value();
    }

    private long entryIn(String dir, String fileName) {
        String rel = dir + "/" + fileName;
        return sink.insert(new NewEntry(Kind.IMAGE, "library", rel, fileName, "png", "image/png",
                1, "hash-" + rel, 8, 4, null, Instant.parse("2026-01-01T00:00:00Z"))).value();
    }

    private void tag(long entryId, String... names) {
        tagService.setEntryTags(entryId, Arrays.stream(names)
                .map(n -> new TagService.TagInput(n, TagCategory.GENERAL)).toList());
    }

    private long manualPool(String name, String description) {
        return poolsService.createManual(name, description).pool().id();
    }

    private List<Long> flattenIds(long poolId) {
        return poolsService.flatten(poolId).stream().map(EntryQueries.EntryDto::id).toList();
    }

    private List<Long> listIds(String q) {
        return poolsService.list(q).stream().map(PoolDto::id).toList();
    }

    private List<Integer> memberPositions(long poolId) {
        return poolsService.detail(poolId).members().stream().map(MemberDto::position).toList();
    }

    private List<String> poolTagNames(long poolId) {
        return tags.byIds(poolStore.tagIdsOf(poolId)).stream().map(TagRow::name).toList();
    }

    /** The member sequence as "e:<entryId>" / "p:<poolId>" tokens — readable order assertions. */
    private List<String> memberTokens(long poolId) {
        return poolsService.detail(poolId).members().stream()
                .map(m -> m.entry() != null ? "e:" + m.entry().id() : "p:" + m.pool().id())
                .toList();
    }

    // --- flatten (spec §3.4, Review Focus #1) -----------------------------------

    @Test
    void flatten_is_depth_first_over_members_in_position_order_on_the_nested_shape() {
        long looseA = entry("insert-a.png");
        long c1p1 = entry("ch1-page1.png");
        long c1p2 = entry("ch1-page2.png");
        long c1p3 = entry("ch1-page3.png");
        long looseB = entry("insert-b.png");
        long c2p1 = entry("ch2-page1.png");
        long c2p2 = entry("ch2-page2.png");
        long looseC = entry("insert-c.png");
        long ch1 = manualPool("Chapter 1", null);
        poolStore.addMembers(ch1, List.of(c1p1, c1p2, c1p3), List.of());
        long ch2 = manualPool("Chapter 2", null);
        poolStore.addMembers(ch2, List.of(c2p1, c2p2), List.of());
        long work = manualPool("Sample Saga", null);
        poolStore.addMembers(work, List.of(looseA), List.of(ch1));
        poolStore.addMembers(work, List.of(looseB), List.of(ch2));
        poolStore.addMembers(work, List.of(looseC), List.of());

        // DFS in position order: the work's own loose entries interleave with the
        // chapters' full flatten — reading order, entryCount, cover and facets all
        // read through this one traversal.
        assertEquals(List.of(looseA, c1p1, c1p2, c1p3, looseB, c2p1, c2p2, looseC),
                flattenIds(work));
        assertEquals(List.of(c1p1, c1p2, c1p3), flattenIds(ch1));
    }

    @Test
    void flatten_visited_set_is_defensive_against_a_planted_cycle() {
        // The write path rejects cycles (cycle-rule tests below) — the traversal never
        // trusts that: plant A ⊃ B ⊃ A directly through the store and flatten must
        // still terminate, yielding each pool's entries exactly once.
        long a = manualPool("A", null);
        long b = manualPool("B", null);
        long ea = entry("a.png");
        long eb = entry("b.png");
        poolStore.addMembers(a, List.of(ea), List.of(b));
        poolStore.addMembers(b, List.of(eb), List.of());
        jdbc.sql("insert into pool_member (pool_id, position, child_pool_id) values (:p, 2, :c)")
                .param("p", b).param("c", a).update(); // the back edge the service would reject

        assertEquals(List.of(ea, eb), flattenIds(a));
        assertEquals(List.of(eb, ea), flattenIds(b));
    }

    @Test
    void entryCount_is_flattened_and_cover_is_the_first_flattened_entry() {
        long looseA = entry("insert-a.png");
        long c1p1 = entry("ch1-page1.png");
        long looseB = entry("insert-b.png");
        long ch1 = manualPool("Chapter 1", null);
        poolStore.addMembers(ch1, List.of(c1p1), List.of());
        long work = manualPool("Work", null);
        poolStore.addMembers(work, List.of(looseA), List.of(ch1));
        poolStore.addMembers(work, List.of(looseB), List.of());

        PoolDto workDto = poolsService.detail(work).pool();
        assertEquals(3, workDto.entryCount()); // the parent shows 3, not its 2 direct rows
        assertEquals(looseA, workDto.cover().id()); // cover = first FLATTENED entry
        assertEquals(1, poolsService.detail(ch1).pool().entryCount());

        long empty = manualPool("Empty", null);
        PoolDto emptyDto = poolsService.detail(empty).pool();
        assertEquals(0, emptyDto.entryCount());
        assertNull(emptyDto.cover()); // empty pool → placeholder tile on the wire
    }

    // --- detail -----------------------------------------------------------------

    @Test
    void detail_mixes_entry_dtos_and_child_pool_summaries_in_position_order() {
        long e1 = entry("a.png");
        long childEntry = entry("c.png");
        long ch = manualPool("Chapter", null);
        poolStore.addMembers(ch, List.of(childEntry), List.of());
        long work = manualPool("Work", null);
        poolStore.addMembers(work, List.of(e1), List.of(ch));

        PoolDetail detail = poolsService.detail(work);
        assertEquals(2, detail.members().size());
        MemberDto first = detail.members().get(0);
        assertEquals(1, first.position());
        assertEquals(e1, first.entry().id());
        assertNull(first.pool());
        MemberDto second = detail.members().get(1);
        assertEquals(2, second.position());
        assertNull(second.entry());
        assertEquals(ch, second.pool().id());
        assertEquals("Chapter", second.pool().name());
        assertEquals(1, second.pool().entryCount()); // summary entryCount is the child's FLATTENED size
    }

    @Test
    void detail_unknown_pool_throws_MissingPoolException() {
        assertThrows(MissingPoolException.class, () -> poolsService.detail(999_999));
    }

    // --- cycle rule (spec §3.3) ---------------------------------------------------

    @Test
    void addMembers_rejects_self_direct_and_transitive_cycles_with_the_exact_message() {
        long work = manualPool("Work", null);
        long ch1 = manualPool("Chapter 1", null);
        long ch2 = manualPool("Chapter 2", null);

        IllegalArgumentException self = assertThrows(IllegalArgumentException.class,
                () -> poolsService.addMembers(work, List.of(), List.of(work)));
        assertEquals("would create a cycle", self.getMessage());

        poolsService.addMembers(work, List.of(), List.of(ch1)); // legit: work ⊃ ch1
        poolsService.addMembers(ch1, List.of(), List.of(ch2)); // legit: ch1 ⊃ ch2

        IllegalArgumentException direct = assertThrows(IllegalArgumentException.class,
                () -> poolsService.addMembers(ch1, List.of(), List.of(work)));
        assertEquals("would create a cycle", direct.getMessage());

        IllegalArgumentException transitive = assertThrows(IllegalArgumentException.class,
                () -> poolsService.addMembers(ch2, List.of(), List.of(work))); // work is a grandchild
        assertEquals("would create a cycle", transitive.getMessage());

        // nothing was written by the rejected calls: ch1 ⊅ work (direct rejected),
        // ch2 ⊅ work (transitive rejected) — ch1 ⊃ ch2 is the legitimate setup only
        assertTrue(poolStore.members(ch1).stream().noneMatch(r -> r.childPoolId() != null
                && r.childPoolId() == work));
        assertTrue(poolStore.members(ch2).stream().noneMatch(r -> r.childPoolId() != null));
    }

    @Test
    void addMembers_appends_entries_then_pools_skips_duplicates_and_returns_the_inserted_count() {
        long e1 = entry("a.png");
        long e2 = entry("b.png");
        long e3 = entry("c.png");
        long ch = manualPool("Chapter", null);
        long work = manualPool("Work", null);

        int first = poolsService.addMembers(work, List.of(e2, e1), List.of(ch));
        assertEquals(3, first); // all three requested rows were new → inserted

        // idempotent re-append: e2/e1/ch already members → skipped silently; only e3 lands
        int second = poolsService.addMembers(work, List.of(e2, e3), List.of(ch));
        assertEquals(1, second);

        // fully idempotent re-post: everything skipped → 0 inserted, nothing changed
        int third = poolsService.addMembers(work, List.of(e2, e1, e3), List.of(ch));
        assertEquals(0, third);

        assertEquals(List.of("e:" + e2, "e:" + e1, "p:" + ch, "e:" + e3), memberTokens(work));
        assertEquals(List.of(1, 2, 3, 4), memberPositions(work));
    }

    @Test
    void addMembers_unknown_entry_or_unknown_pool_is_a_400_vehicle() {
        long work = manualPool("Work", null);
        assertThrows(IllegalArgumentException.class,
                () -> poolsService.addMembers(work, List.of(424_242L), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> poolsService.addMembers(work, List.of(), List.of(424_242L)));
    }

    // --- reorder / remove (spec §3.6) --------------------------------------------

    @Test
    void reorder_requires_a_permutation_of_current_member_rows_and_renumbers() {
        long e1 = entry("a.png");
        long e2 = entry("b.png");
        long ch = manualPool("Chapter", null);
        long work = manualPool("Work", null);
        poolStore.addMembers(work, List.of(e1, e2), List.of(ch));
        List<Long> rowIds = poolStore.members(work).stream().map(PoolStore.MemberRow::memberId).toList();

        // stale-client guard: a SUBSET is not a permutation → IAE (400 in T4)
        assertThrows(IllegalArgumentException.class,
                () -> poolsService.reorder(work, rowIds.subList(0, 2)));

        // stale-client guard: a REPEATED row id is not a permutation either — the payload
        // matches on size and containsAll, but renumber would write b twice and never touch
        // c (duplicate/gapped positions, no DB backstop) — IAE, and NO write may happen
        List<Long> duplicated = List.of(rowIds.get(0), rowIds.get(1), rowIds.get(1));
        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
                () -> poolsService.reorder(work, duplicated));
        assertTrue(dup.getMessage().contains("permutation"));
        assertEquals(List.of(1, 2, 3), memberPositions(work)); // untouched by the rejected call

        poolsService.reorder(work, rowIds.reversed());

        assertEquals(List.of("p:" + ch, "e:" + e2, "e:" + e1), memberTokens(work));
        assertEquals(List.of(1, 2, 3), memberPositions(work));
    }

    @Test
    void removeMember_removes_and_renumbers_survivors() {
        long e1 = entry("a.png");
        long e2 = entry("b.png");
        long e3 = entry("c.png");
        long work = manualPool("Work", null);
        poolStore.addMembers(work, List.of(e1, e2, e3), List.of());
        long middle = poolStore.members(work).get(1).memberId();

        poolsService.removeMember(work, middle);

        assertEquals(List.of("e:" + e1, "e:" + e3), memberTokens(work));
        assertEquals(List.of(1, 2), memberPositions(work)); // renumbered, no gap

        // unknown member row → idempotent no-op (DELETE stays 204 on the wire, T4)
        poolsService.removeMember(work, 987_654);
        assertEquals(2, poolsService.detail(work).members().size());
    }

    // --- folder seeding (spec §3.3, §5) --------------------------------------------

    @Test
    void createFromFolder_seeds_natural_ordered_direct_entries_with_origin_and_system_tag() {
        long page10 = entryIn("chapter-1", "page10.png");
        long page2 = entryIn("chapter-1", "page2.png");
        long page1 = entryIn("chapter-1", "1.png");
        entryIn("chapter-1/deeper", "inside.png"); // a subdirectory level: NOT direct
        long rootLevelEntry = entry("top-level.png"); // root top level: a different folder ('')

        PoolDetail detail = poolsService.createFromFolder("library", "chapter-1", null,
                "Auto-discovered from library/chapter-1");

        PoolDto pool = detail.pool();
        assertEquals("chapter-1", pool.name()); // defaulted name = directory basename
        assertEquals("library", pool.origin().rootName());
        assertEquals("chapter-1", pool.origin().dirPath());
        // natural order over direct entries only: digits before text, page2 < page10
        assertEquals(List.of("e:" + page1, "e:" + page2, "e:" + page10), memberTokens(pool.id()));
        assertEquals(Set.of("pool"), Set.copyOf(poolTagNames(pool.id()))); // system tag attached
        assertEquals("pool_type", tags.byName("pool").orElseThrow().category());

        // root top level ('') is a folder of its own: its direct entries are the rel_paths
        // without a '/', and the defaulted name falls back to the root name
        long rootFile = entry("root-file.png");
        PoolDto rootPool = poolsService.createFromFolder("library", "", null, null).pool();
        assertEquals("library", rootPool.name());
        assertEquals("", rootPool.origin().dirPath());
        assertEquals(List.of("e:" + rootFile, "e:" + rootLevelEntry), memberTokens(rootPool.id()));
    }

    @Test
    void createFromFolder_auto_dedupes_defaulted_names_and_is_strict_on_explicit_names() {
        entryIn("W", "a.png");
        long first = poolsService.createFromFolder("library", "W", null, null).pool().id();
        long second = poolsService.createFromFolder("library", "W", null, null).pool().id(); // re-seed stays possible
        long third = poolsService.createFromFolder("library", "W", null, null).pool().id();
        assertEquals("W", poolStore.byId(first).orElseThrow().name());
        assertEquals("W (2)", poolStore.byId(second).orElseThrow().name());
        assertEquals("W (3)", poolStore.byId(third).orElseThrow().name());
        // both origins coexist: re-seeding a folder is legal, retirement is the folders list's job

        DuplicatePoolException dup = assertThrows(DuplicatePoolException.class,
                () -> poolsService.createFromFolder("library", "W", "W (2)", null));
        assertTrue(dup.getMessage().contains("W (2)"));
        long explicit = poolsService.createFromFolder("library", "W", "Free Name", null).pool().id();
        assertEquals("Free Name", poolStore.byId(explicit).orElseThrow().name());
    }

    @Test
    void createFromFolder_zero_entries_is_rejected_with_the_exact_message() {
        entryIn("Elsewhere", "a.png"); // a different folder
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> poolsService.createFromFolder("library", "Empty", null, null));
        assertEquals("no entries in folder", e.getMessage()); // spec §5 verbatim
    }

    // --- import follow-up create (spec §3.8) ----------------------------------------

    @Test
    void createImported_dedupes_name_appends_members_in_order_and_attaches_system_tag() {
        long first = entry("a.png");
        long second = entry("b.png");

        long poolId = poolsService.createImported("Import 2026-11-12 08:15", List.of(second, first));
        // the follow-up is a one-click action: a name collision dedupes, never fails
        long colliding = poolsService.createImported("Import 2026-11-12 08:15", List.of(first));

        assertEquals("Import 2026-11-12 08:15", poolStore.byId(poolId).orElseThrow().name());
        assertEquals("Import 2026-11-12 08:15 (2)", poolStore.byId(colliding).orElseThrow().name());
        // members in the CALLER's order — the run's (directory, natural filename, id) order
        assertEquals(List.of("e:" + second, "e:" + first), memberTokens(poolId));
        assertEquals(Set.of("pool"), Set.copyOf(poolTagNames(poolId))); // system tag attached
        assertNull(poolStore.byId(poolId).orElseThrow().originRoot()); // no folder origin
    }

    @Test
    void createManual_rejects_blank_and_duplicate_names() {
        assertThrows(IllegalArgumentException.class, () -> poolsService.createManual("   ", null));
        long p = manualPool("Favorites", null);
        assertThrows(DuplicatePoolException.class, () -> poolsService.createManual("Favorites", null));
        PoolDto back = poolsService.detail(p).pool();
        assertEquals("Favorites", back.name());
        assertNull(back.origin()); // manual pool → no origin on the wire
        assertEquals(Set.of("pool"), Set.copyOf(poolTagNames(p))); // manual creates attach the system tag
    }

    // --- pool tags (spec §3.5) ------------------------------------------------------

    @Test
    void setTags_is_a_replace_set_that_re_adds_the_system_tag_and_resolves_aliases() {
        long p = manualPool("P", null);
        tags.create("red", TagCategory.GENERAL);
        tags.create("detective_comics", TagCategory.GENERAL);
        tagService.createAlias("dc", "detective_comics");

        poolsService.setTags(p, List.of("Red", " DC ")); // normalize + alias-resolve
        assertEquals(Set.of("red", "detective_comics", "pool"), Set.copyOf(poolTagNames(p)));

        poolsService.setTags(p, List.of("red")); // replace-set drops detective_comics; omits pool...
        assertEquals(Set.of("red", "pool"), Set.copyOf(poolTagNames(p))); // ...system tag re-added

        poolsService.setTags(p, List.of()); // empty replace-set still keeps the system tag
        assertEquals(Set.of("pool"), Set.copyOf(poolTagNames(p)));
    }

    @Test
    void setTags_creates_unknown_names_as_general_without_implications_or_provenance() {
        long p = manualPool("P", null);
        tags.create("red", TagCategory.GENERAL);
        tags.create("warm", TagCategory.GENERAL);
        tagService.createImplication("red", "warm"); // implications are an ENTRY-tag rule only

        poolsService.setTags(p, List.of("red", "brand_new_tag"));

        assertEquals(Set.of("red", "brand_new_tag", "pool"), Set.copyOf(poolTagNames(p))); // NO warmth implied
        assertEquals("general", tags.byName("brand_new_tag").orElseThrow().category()); // created as general
        assertEquals("pool_type", tags.byName("pool").orElseThrow().category()); // never demoted
        // no provenance: pool tags carry no media_tag rows at all — the TagDtos read back taggedBy=null
        assertTrue(poolsService.detail(p).pool().tags().stream()
                .allMatch(t -> t.taggedBy() == null));
    }

    @Test
    void resolvePoolTags_normalizes_dedupes_and_alias_resolves() {
        tags.create("detective_comics", TagCategory.GENERAL);
        tagService.createAlias("dc", "detective_comics");

        List<TagRow> resolved = tagService.resolvePoolTags(List.of(" DC! ", "brand_x", "dc"));

        assertEquals(List.of("detective_comics", "brand_x"),
                resolved.stream().map(TagRow::name).toList()); // first occurrence wins, alias lands on the target
        assertEquals("general", tags.byName("brand_x").orElseThrow().category()); // create-missing as GENERAL
    }

    // --- q filter (spec §3.7) --------------------------------------------------------

    @Test
    void list_q_matches_name_description_own_tags_and_flattened_content_tags() {
        long tealEntry = entry("teal.png");
        tag(tealEntry, "teal");
        long alpha = manualPool("Alpha Work", null);
        poolsService.setTags(alpha, List.of("red")); // alpha's OWN tag
        long ch = manualPool("Chapter One", null);
        poolStore.addMembers(ch, List.of(tealEntry), List.of());
        poolStore.addMembers(alpha, List.of(), List.of(ch)); // alpha's flattened content carries teal
        long beta = manualPool("Beta Files", "something watery here"); // description hit only

        // (a) name substring, case-insensitive
        assertEquals(List.of(alpha), listIds("ALPHA"));
        // (a) description substring
        assertEquals(List.of(beta), listIds("watery"));
        // (b) own-tag exact match
        assertEquals(List.of(alpha), listIds("red"));
        // (b) flattened-content-tag match: teal lives on an entry INSIDE ch;
        //     alpha matches through the flatten, ch matches directly
        assertEquals(List.of(alpha, ch), listIds("teal"));
        // (b) the system tag via its FULL reference (pool_type:pool, never bare "pool")
        assertEquals(List.of(alpha, ch, beta), listIds("pool_type:pool"));
        assertTrue(listIds("pool").isEmpty()); // bare "pool" is NOT the tag's reference
        // AND across whitespace terms
        assertEquals(List.of(alpha), listIds("alpha red"));
        assertTrue(listIds("beta red").isEmpty());
        // empty/absent q → all pools (id asc)
        assertEquals(List.of(alpha, ch, beta), listIds(null));
        assertEquals(List.of(alpha, ch, beta), listIds("  "));
    }

    // --- facets (spec §3.7) -----------------------------------------------------------

    @Test
    void facets_count_over_the_filtered_set_and_add_the_suggested_pseudo_facet() {
        long ea = entry("a.png");
        tag(ea, "blue");
        long a = manualPool("A", null);
        poolsService.setTags(a, List.of("red")); // a's OWN tag
        poolStore.addMembers(a, List.of(ea), List.of()); // a's content tag
        long eb = entry("b.png");
        tag(eb, "blue");
        long b = manualPool("B", null);
        poolStore.addMembers(b, List.of(eb), List.of());
        manualPool("C", null); // matches, but carries only the system tag
        List<FolderSuggestion> drafts = List.of(
                new FolderSuggestion("library", "01 Dingo", 17),
                new FolderSuggestion("library", "02 Cat", 13));

        // union semantics: pool_type:pool 3, blue 2 (a's content ∪ b's content), red 1 (a's own);
        // pool_type rows first, then count desc / name asc
        assertEquals(
                List.of(new EntryQueries.FacetDto("pool", "pool_type", 3),
                        new EntryQueries.FacetDto("blue", "general", 2),
                        new EntryQueries.FacetDto("red", "general", 1)),
                poolsService.facets(null, List.of()));

        // drafts contribute the fixed pseudo-facet: both drafts match the empty q
        assertEquals(
                List.of(new EntryQueries.FacetDto("pool", "pool_type", 3),
                        new EntryQueries.FacetDto("suggested", "pool_type", 2),
                        new EntryQueries.FacetDto("blue", "general", 2),
                        new EntryQueries.FacetDto("red", "general", 1)),
                poolsService.facets(null, drafts));

        // the SAME filtered set as list(): q=red → only a's union counts
        assertEquals(
                List.of(new EntryQueries.FacetDto("pool", "pool_type", 1),
                        new EntryQueries.FacetDto("blue", "general", 1),
                        new EntryQueries.FacetDto("red", "general", 1)),
                poolsService.facets("red", List.of()));

        // a draft matches a text term via its directory path (root/dir)…
        assertEquals(List.of(new EntryQueries.FacetDto("suggested", "pool_type", 1)),
                poolsService.facets("dingo", drafts));
        // …and pool_type:suggested always matches drafts
        assertEquals(List.of(new EntryQueries.FacetDto("suggested", "pool_type", 2)),
                poolsService.facets("pool_type:suggested", drafts));
        // nothing matches → no rows at all, no pseudo-facet
        assertEquals(List.of(), poolsService.facets("zzz", drafts));
    }

    // --- folder suggestions (spec §3.3, T5) ------------------------------------------------

    @Test
    void folders_suggest_directories_with_two_or_more_direct_entries_excluding_pool_origins() {
        // both Saga chapters, seeded page10-first so the natural cover differs from id order
        long p10 = entryIn("Sample Saga/chapter-1", "page10.png");
        entryIn("Sample Saga/chapter-1", "page2.png");
        entryIn("Sample Saga/chapter-1", "page3.png");
        long p1 = entryIn("Sample Saga/chapter-1", "page1.png");
        entryIn("Sample Saga/chapter-1/deeper", "buried.png"); // subdirectory level: NOT direct
        long m1 = entryIn("Sample Saga/02 The Middle", "a.png");
        entryIn("Sample Saga/02 The Middle", "b.png");
        entryIn("Singles", "only.png"); // <2 direct entries → no suggestion
        long looseA = entry("loose-a.png"); // root top level ('') is a folder of its own
        entry("loose-b.png");
        entry("loose-c.png");

        List<FolderSuggestion> folders = poolsService.folders();

        // count desc (4, 3, 2); <2-entry dirs (Singles, the deeper subdir) are out; the
        // chapter's count is DIRECT entries only (buried.png doesn't inflate chapter-1)
        assertEquals(List.of("Sample Saga/chapter-1", "", "Sample Saga/02 The Middle"),
                folders.stream().map(FolderSuggestion::dirPath).toList());
        assertEquals(List.of(4L, 3L, 2L), folders.stream().map(FolderSuggestion::entryCount).toList());
        assertEquals("library", folders.get(0).rootName());
        // cover = the folder's first NATURAL-ordered entry (page1, not id-order page10)
        assertEquals(p1, folders.get(0).cover().id());
        assertEquals("page1.png", folders.get(0).cover().fileName());
        assertEquals(looseA, folders.get(1).cover().id());
        assertEquals(m1, folders.get(2).cover().id());

        // origin retirement: seeding a pool from a folder retires it from the list —
        // re-seeding stays possible (create auto-dedupes), the LIST side drops the origin
        poolsService.createFromFolder("library", "Sample Saga/chapter-1", null, null);
        assertEquals(List.of("", "Sample Saga/02 The Middle"),
                poolsService.folders().stream().map(FolderSuggestion::dirPath).toList());

        // a second pool on the '' origin keeps that folder retired too
        poolsService.createFromFolder("library", "", null, null);
        assertEquals(List.of("Sample Saga/02 The Middle"),
                poolsService.folders().stream().map(FolderSuggestion::dirPath).toList());
    }

    // --- ?entryId= membership flags (spec §4.3, T5) ---------------------------------------

    @Test
    void list_with_entryId_flags_flattened_membership_and_stays_absent_without_the_param() {
        long direct = entry("direct.png");
        long nested = entry("nested.png");
        long outside = entry("outside.png");
        long ch = manualPool("Chapter", null);
        poolStore.addMembers(ch, List.of(nested), List.of());
        long work = manualPool("Work", null);
        poolStore.addMembers(work, List.of(direct), List.of(ch));
        manualPool("Other", null); // empty — no member ever flags it
        // pools are id-ascending: ch, work, other

        // direct member: true on the pool holding the row, false elsewhere
        assertEquals(List.of(false, true, false),
                poolsService.list(null, direct).stream().map(PoolDto::member).toList());
        // NESTED member: the entry lives in ch, but work's FLATTEN contains it (grandchildren count)
        assertEquals(List.of(true, true, false),
                poolsService.list(null, nested).stream().map(PoolDto::member).toList());
        // a non-member is false everywhere — even for the empty pool
        assertEquals(List.of(false, false, false),
                poolsService.list(null, outside).stream().map(PoolDto::member).toList());
        // an unknown entry id can never be a member
        assertEquals(List.of(false, false, false),
                poolsService.list(null, 424_242L).stream().map(PoolDto::member).toList());
        // the flag composes with the q filter
        assertEquals(List.of(true),
                poolsService.list("Work", nested).stream().map(PoolDto::member).toList());
        // no param → the flag is absent (null at service level, NON_NULL on the wire);
        // the one-arg grid overload reads identically
        assertTrue(poolsService.list(null).stream().allMatch(p -> p.member() == null));
        assertEquals(poolsService.list(null), poolsService.list(null, (Long) null));
    }

    // --- poolPosition (the neighbors helper, spec §3.9) ---------------------------------

    @Test
    void poolPosition_reports_the_one_based_position_within_the_flatten() {
        long looseA = entry("insert-a.png");
        long c1 = entry("ch-page1.png");
        long c2 = entry("ch-page2.png");
        long looseB = entry("insert-b.png");
        long outside = entry("elsewhere.png");
        long ch = manualPool("Chapter", null);
        poolStore.addMembers(ch, List.of(c1, c2), List.of());
        long work = manualPool("Work", null);
        poolStore.addMembers(work, List.of(looseA), List.of(ch));
        poolStore.addMembers(work, List.of(looseB), List.of());

        assertEquals(1, poolsService.poolPosition(work, looseA).orElseThrow()[0]);
        assertEquals(4, poolsService.poolPosition(work, looseB).orElseThrow()[0]);
        assertEquals(4, poolsService.poolPosition(work, looseB).orElseThrow()[1]); // total = flattened size
        assertEquals(2, poolsService.poolPosition(ch, c2).orElseThrow()[0]);
        assertEquals(2, poolsService.poolPosition(ch, c2).orElseThrow()[1]);
        assertTrue(poolsService.poolPosition(work, outside).isEmpty()); // not flattened-contained
    }
}
