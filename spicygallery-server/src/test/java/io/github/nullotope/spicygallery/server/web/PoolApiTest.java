package io.github.nullotope.spicygallery.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.store.JdbcMediaStoreSink;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Pools API (spec §3.9): the wire contract over {@code PoolService} — full CRUD, member
 * append/order/remove, the error contract (404 missing pool on EVERY byId route, 409
 * duplicate create AND rename, 400 blank name / unknown entry / non-permutation / cycle /
 * empty folder), folder create via {@code rootName} presence, and the pools-page q + facets
 * end-to-end. Real stores on the Testcontainers PG (house ApiTest pattern, no Mockito).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PoolApiTest extends PostgresBase {

    @Autowired MockMvc mvc;
    @Autowired JdbcMediaStoreSink sink;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcTagStore tagStore;

    private long page1;
    private long page2;
    private long page10;
    private long nested;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from import_run").update(); // BEFORE pool: pool_id FK (no cascade)
        jdbc.sql("delete from pool").update(); // cascades pool_member + pool_tag
        jdbc.sql("delete from media_tag").update();
        jdbc.sql("delete from tag_implication").update();
        jdbc.sql("delete from tag_alias").update();
        jdbc.sql("delete from tag").update();
        jdbc.sql("delete from media_entry").update();
        // insert order = id order; names chosen so the NATURAL order differs from id order
        page10 = entryIn("chapters", "page10.png");
        page2 = entryIn("chapters", "page2.png");
        page1 = entryIn("chapters", "page1.png");
        nested = entryIn("chapters/sub", "nested.png");
    }

    private long entryIn(String dir, String fileName) {
        String rel = dir + "/" + fileName;
        return sink.insert(new NewEntry(Kind.IMAGE, "library", rel, fileName, "png", "image/png",
                1, "hash-" + rel, 8, 4, null, Instant.parse("2026-01-01T00:00:00Z"))).value();
    }

    /** A root top-level entry: rel_path has no '/', so its folder is {@code ''} (spec §3.3). */
    private long rootEntry(String fileName) {
        return sink.insert(new NewEntry(Kind.IMAGE, "library", fileName, fileName, "png", "image/png",
                1, "hash-" + fileName, 8, 4, null, Instant.parse("2026-01-01T00:00:00Z"))).value();
    }

    // --- helpers ---------------------------------------------------------------

    private static JsonNode tree(MvcResult res) throws Exception {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(res.getResponse().getContentAsString());
    }

    private long createPool(String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/pools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return tree(res).get("id").asLong();
    }

    private JsonDetail getDetail(long id) throws Exception {
        return new JsonDetail(tree(mvc.perform(get("/api/pools/" + id))
                .andExpect(status().isOk()).andReturn()));
    }

    /** Read helpers over a PoolDetail body — keeps the member assertions readable. */
    private record JsonDetail(JsonNode node) {

        List<Long> memberEntryIds() {
            List<Long> ids = new ArrayList<>();
            node.get("members").forEach(m -> ids.add(m.get("entry").get("id").asLong()));
            return ids;
        }

        List<Long> memberIds() {
            List<Long> ids = new ArrayList<>();
            node.get("members").forEach(m -> ids.add(m.get("memberId").asLong()));
            return ids;
        }

        List<Integer> positions() {
            List<Integer> ps = new ArrayList<>();
            node.get("members").forEach(m -> ps.add(m.get("position").asInt()));
            return ps;
        }
    }

    // --- full CRUD ---------------------------------------------------------------

    @Test
    void crud_lifecycle_from_create_to_delete() throws Exception {
        // CREATE manual → 201: flattened-empty shape (no origin, no cover) + system tag on
        MvcResult created = mvc.perform(post("/api/pools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Work\",\"description\":\"stuff\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Work"))
                .andExpect(jsonPath("$.description").value("stuff"))
                .andExpect(jsonPath("$.entryCount").value(0))
                .andExpect(jsonPath("$.origin").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.cover").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        JsonNode pool = tree(created);
        long id = pool.get("id").asLong();
        assertEquals(1, pool.get("tags").size(), "fresh pool carries the system tag");
        assertEquals("pool", pool.get("tags").get(0).get("name").asText());
        assertEquals("pool_type", pool.get("tags").get(0).get("category").asText());

        // GET detail: tile + (empty) member sequence
        mvc.perform(get("/api/pools/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pool.id").value(id))
                .andExpect(jsonPath("$.members.length()").value(0));

        // PATCH: rename + re-describe → the updated PoolDto
        mvc.perform(patch("/api/pools/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Work renamed\",\"description\":\"better\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Work renamed"))
                .andExpect(jsonPath("$.description").value("better"));

        // PATCH tags: replace-set; the system tag is re-added even when omitted (spec §3.5)
        MvcResult tagged = mvc.perform(patch("/api/pools/" + id + "/tags")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[\"dragon\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        List<String> tagNames = new ArrayList<>();
        tree(tagged).forEach(t -> tagNames.add(t.get("name").asText()));
        assertEquals(List.of("pool", "dragon"), tagNames, "system tag re-added after the replace-set");

        // APPEND: entries land in REQUEST order (page2 first here, deliberately not natural)
        mvc.perform(post("/api/pools/" + id + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + page2 + "," + page1 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(2));
        JsonDetail detail = getDetail(id);
        assertEquals(List.of(page2, page1), detail.memberEntryIds());
        assertEquals(List.of(1, 2), detail.positions());
        // exactly one of entry/pool per row (spec §3.9): entry rows carry entry, not pool
        assertEquals(page2, detail.node().get("members").get(0).get("entry").get("id").asLong());
        assertFalse(detail.node().get("members").get(0).get("entry").isNull());
        assertFalse(detail.node().get("members").get(0).hasNonNull("pool"));

        // ORDER: a valid permutation reverses; the response is the refreshed detail
        List<Long> rows = detail.memberIds();
        mvc.perform(post("/api/pools/" + id + "/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedMemberIds\":[" + rows.get(1) + "," + rows.get(0) + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].entry.id").value(page1))
                .andExpect(jsonPath("$.members[1].entry.id").value(page2));

        // REMOVE: unknown member row is an idempotent no-op → still 204 (spec §3.6)
        mvc.perform(delete("/api/pools/" + id + "/members/999999")).andExpect(status().isNoContent());
        // removing the first survivor renumbers the rest 1..N
        mvc.perform(delete("/api/pools/" + id + "/members/" + rows.get(1)))
                .andExpect(status().isNoContent());
        detail = getDetail(id);
        assertEquals(List.of(page2), detail.memberEntryIds());
        assertEquals(List.of(1), detail.positions());

        // DELETE the pool → 204, then every read 404s
        mvc.perform(delete("/api/pools/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/pools/" + id)).andExpect(status().isNotFound());
    }

    // --- folder create (rootName presence routes the body) -------------------------

    @Test
    void folder_body_creates_pool_from_the_directory() throws Exception {
        // no name → defaulted from the directory basename; members = DIRECT entries in
        // NATURAL order (page1 < page2 < page10 — id order would read page10 first)
        MvcResult created = mvc.perform(post("/api/pools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rootName\":\"library\",\"dirPath\":\"chapters\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("chapters"))
                .andExpect(jsonPath("$.origin.rootName").value("library"))
                .andExpect(jsonPath("$.origin.dirPath").value("chapters"))
                .andExpect(jsonPath("$.entryCount").value(3))
                .andExpect(jsonPath("$.cover.id").value(page1))
                .andReturn();
        long id = tree(created).get("id").asLong();
        assertEquals(List.of(page1, page2, page10), getDetail(id).memberEntryIds(),
                "natural order, direct entries only — the nested chapters/sub row is out");

        // re-seed with an EXPLICIT duplicate name → strict 409 (auto-dedupe is defaulted-only)
        mvc.perform(post("/api/pools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rootName\":\"library\",\"dirPath\":\"chapters\",\"name\":\"chapters\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("pool 'chapters' already exists"));

        // a second DEFAULTED create auto-dedupes — a one-click action never fails (spec §3.3)
        mvc.perform(post("/api/pools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rootName\":\"library\",\"dirPath\":\"chapters\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("chapters (2)"));

        // unknown library root → 400
        mvc.perform(post("/api/pools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rootName\":\"nope\",\"dirPath\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("unknown library root: nope"));
    }

    @Test
    void empty_folder_is_400_with_verbatim_detail() throws Exception {
        mvc.perform(post("/api/pools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rootName\":\"library\",\"dirPath\":\"empty\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("no entries in folder"));
    }

    // --- folder suggestions (spec §3.3, T5) ---------------------------------------------

    @Test
    void folders_return_count_desc_suggestions_with_natural_covers() throws Exception {
        // BeforeEach seeded chapters (page1/page2/page10 direct, 3 entries) and
        // chapters/sub (nested.png alone, 1 entry — under the >= 2 threshold);
        // 'solo' is another lone directory; the root top level needs >= 2 loose entries
        long looseA = rootEntry("loose-a.png");
        rootEntry("loose-b.png");
        entryIn("solo", "only.png");

        MvcResult folders = mvc.perform(get("/api/pools/folders")).andExpect(status().isOk()).andReturn();
        JsonNode list = tree(folders);
        assertEquals(2, list.size(), list.toString());
        // count desc: chapters (3) before the root top level ('', 2)
        assertEquals("chapters", list.get(0).get("dirPath").asText());
        assertEquals("library", list.get(0).get("rootName").asText());
        assertEquals(3, list.get(0).get("entryCount").asLong());
        // cover = the folder's first NATURAL-ordered entry (page1, not id-order page10)
        assertEquals(page1, list.get(0).get("cover").get("id").asLong());
        assertEquals("page1.png", list.get(0).get("cover").get("fileName").asText());
        // '' = root top level is a folder of its own; nested/solo stay under the threshold
        assertEquals("", list.get(1).get("dirPath").asText());
        assertEquals(2, list.get(1).get("entryCount").asLong());
        assertEquals(looseA, list.get(1).get("cover").get("id").asLong());

        // seeding a pool from a folder retires the suggestion (list-side retirement)
        mvc.perform(post("/api/pools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rootName\":\"library\",\"dirPath\":\"chapters\"}"))
                .andExpect(status().isCreated());
        MvcResult retired = mvc.perform(get("/api/pools/folders")).andExpect(status().isOk()).andReturn();
        assertEquals(1, tree(retired).size(), retired.getResponse().getContentAsString());
        assertEquals("", tree(retired).get(0).get("dirPath").asText());
    }

    // --- member append semantics (spec §3.6) -----------------------------------------

    @Test
    void members_append_entries_then_pools_and_dedupe_silently() throws Exception {
        long a = createPool("A");
        long b = createPool("B");

        mvc.perform(post("/api/pools/" + a + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + page1 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));
        // re-posting an existing member is skipped silently — only page2 is new
        mvc.perform(post("/api/pools/" + a + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + page1 + "," + page2 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));
        // sub-pools append AFTER the entries, in one shared position sequence
        mvc.perform(post("/api/pools/" + a + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"poolIds\":[" + b + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));

        JsonDetail detail = getDetail(a);
        assertEquals(List.of(1, 2, 3), detail.positions());
        assertEquals(page1, detail.node().get("members").get(0).get("entry").get("id").asLong());
        assertEquals(page2, detail.node().get("members").get(1).get("entry").get("id").asLong());
        var poolRow = detail.node().get("members").get(2);
        assertFalse(poolRow.get("pool").isNull(), "pool member row carries the summary");
        assertEquals(b, poolRow.get("pool").get("id").asLong());
        assertEquals("B", poolRow.get("pool").get("name").asText());
        assertEquals(0, poolRow.get("pool").get("entryCount").asLong());
        assertFalse(poolRow.hasNonNull("entry"), "exactly one of entry/pool per row");

        // fully idempotent re-post: added 0, rows and order untouched
        JsonDetail beforeRepost = getDetail(a);
        mvc.perform(post("/api/pools/" + a + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + page1 + "," + page2 + "],\"poolIds\":[" + b + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(0));
        JsonDetail afterRepost = getDetail(a);
        assertEquals(beforeRepost.memberIds(), afterRepost.memberIds(), "re-post appended nothing");
        assertEquals(beforeRepost.positions(), afterRepost.positions());
    }

    // --- error contract ---------------------------------------------------------------

    @Test
    void missing_pool_is_404_on_every_byId_route() throws Exception {
        createPool("Real");
        long phantom = 424242;
        mvc.perform(get("/api/pools/" + phantom)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/pools/" + phantom).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\"}")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/pools/" + phantom + "/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[]}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/pools/" + phantom + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[]}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/pools/" + phantom + "/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedMemberIds\":[]}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/pools/" + phantom + "/members/1")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/pools/" + phantom)).andExpect(status().isNotFound());
    }

    @Test
    void duplicate_name_is_409_on_create_and_rename() throws Exception {
        createPool("alpha");
        long beta = createPool("beta");
        mvc.perform(post("/api/pools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"alpha\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("pool 'alpha' already exists"));
        mvc.perform(patch("/api/pools/" + beta).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"alpha\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("pool 'alpha' already exists"));
        // renaming to the pool's OWN current name is a no-op, never a self-409
        mvc.perform(patch("/api/pools/" + beta).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"beta\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("beta"));
    }

    @Test
    void blank_name_is_400_on_create_and_patch() throws Exception {
        long id = createPool("Named");
        mvc.perform(post("/api/pools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("pool name is required"));
        mvc.perform(patch("/api/pools/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("pool name is required"));
    }

    @Test
    void unknown_member_ids_are_400() throws Exception {
        long id = createPool("P");
        mvc.perform(post("/api/pools/" + id + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[999999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("no media entry 999999"));
        mvc.perform(post("/api/pools/" + id + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"poolIds\":[999999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("no pool 999999"));
    }

    @Test
    void cycle_rule_is_400_with_verbatim_detail() throws Exception {
        long a = createPool("A");
        long b = createPool("B");
        long c = createPool("C");
        mvc.perform(post("/api/pools/" + a + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"poolIds\":[" + b + "]}")).andExpect(status().isOk());
        mvc.perform(post("/api/pools/" + b + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"poolIds\":[" + c + "]}")).andExpect(status().isOk());
        // self
        mvc.perform(post("/api/pools/" + a + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"poolIds\":[" + a + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("would create a cycle"));
        // direct ancestor: putting A under its own child B
        mvc.perform(post("/api/pools/" + b + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"poolIds\":[" + a + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("would create a cycle"));
        // transitive ancestor: putting A under its grandchild C (A → B → C)
        mvc.perform(post("/api/pools/" + c + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"poolIds\":[" + a + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("would create a cycle"));
        // the legal direction: nesting a DESCENDANT deeper is fine — flatten sees it all
        mvc.perform(post("/api/pools/" + a + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"poolIds\":[" + c + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));
        assertEquals(List.of(b, c), poolMemberIdsOf(a));
    }

    private List<Long> poolMemberIdsOf(long id) throws Exception {
        List<Long> poolIds = new ArrayList<>();
        getDetail(id).node().get("members")
                .forEach(m -> poolIds.add(m.get("pool").get("id").asLong()));
        return poolIds;
    }

    @Test
    void reorder_requires_a_true_permutation() throws Exception {
        long id = createPool("P");
        mvc.perform(post("/api/pools/" + id + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + page1 + "," + page2 + "]}"))
                .andExpect(status().isOk());
        JsonDetail detail = getDetail(id);
        long m1 = detail.memberIds().get(0);
        long m2 = detail.memberIds().get(1);
        // subset → stale client → 400 verbatim (spec §5)
        mvc.perform(post("/api/pools/" + id + "/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedMemberIds\":[" + m1 + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("orderedMemberIds must be a permutation of the pool's current member rows"));
        // a REPEATED row id passes size/containsAll but is not a permutation (review focus #2)
        mvc.perform(post("/api/pools/" + id + "/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedMemberIds\":[" + m1 + "," + m1 + "]}"))
                .andExpect(status().isBadRequest());
        // valid permutation: reverse → refreshed detail in the new order, positions 1..N
        mvc.perform(post("/api/pools/" + id + "/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedMemberIds\":[" + m2 + "," + m1 + "]}"))
                .andExpect(status().isOk());
        assertEquals(List.of(page2, page1), getDetail(id).memberEntryIds());
        assertEquals(List.of(1, 2), getDetail(id).positions());
    }

    // --- ?entryId= membership flags (spec §4.3, T5) ---------------------------------------

    @Test
    void entryId_param_flags_member_per_pool_and_is_absent_without_it() throws Exception {
        long work = createPool("Work");
        long ch = createPool("Chapter");
        mvc.perform(post("/api/pools/" + ch + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + page2 + "]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/pools/" + work + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + page1 + "],\"poolIds\":[" + ch + "]}"))
                .andExpect(status().isOk());
        // pool list is id-ascending: $[0] = Work, $[1] = Chapter

        // direct member: Work holds the row; Chapter does not
        mvc.perform(get("/api/pools").param("entryId", String.valueOf(page1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(work))
                .andExpect(jsonPath("$[0].member").value(true))
                .andExpect(jsonPath("$[1].member").value(false));
        // NESTED member: page2 lives in Chapter, but Work's FLATTEN contains it
        mvc.perform(get("/api/pools").param("entryId", String.valueOf(page2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].member").value(true))
                .andExpect(jsonPath("$[1].member").value(true));
        // a non-member entry flags false on every pool
        mvc.perform(get("/api/pools").param("entryId", String.valueOf(page10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].member").value(false))
                .andExpect(jsonPath("$[1].member").value(false));
        // param ABSENT → no member key at all (house NON_NULL style)
        MvcResult plain = mvc.perform(get("/api/pools")).andExpect(status().isOk()).andReturn();
        tree(plain).forEach(p -> assertFalse(p.has("member"), "member key must be absent without ?entryId="));
    }

    // --- pools-page q + facets end-to-end (spec §3.7) ---------------------------------

    @Test
    void empty_gallery_yields_empty_list_and_facets() throws Exception {
        mvc.perform(get("/api/pools")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // no pools — but the BeforeEach-seeded chapters folder qualifies as a draft, so the
        // T5-wired drafts contribute exactly the suggested pseudo-facet (T4 passed none)
        MvcResult facets = mvc.perform(get("/api/pools/facets")).andExpect(status().isOk()).andReturn();
        assertEquals(List.of("suggested"), facetNames(facets), facets.getResponse().getContentAsString());
    }

    @Test
    void q_filter_and_facets_end_to_end() throws Exception {
        long saga = createPoolNamed("Saga", "work collection");
        mvc.perform(post("/api/pools/" + saga + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + page1 + "," + page2 + "]}"))
                .andExpect(status().isOk());
        long loose = createPool("Loose");
        // content tag on Saga' members; own pool tag via the API's replace-set
        long duck = tagStore.create("duck", TagCategory.GENERAL).id();
        tagStore.tagEntry(page1, duck);
        tagStore.tagEntry(page2, duck);
        mvc.perform(patch("/api/pools/" + saga + "/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"curated\"]}"))
                .andExpect(status().isOk());

        // unfiltered → both pools, id-ascending
        MvcResult all = mvc.perform(get("/api/pools")).andExpect(status().isOk()).andReturn();
        List<String> names = new ArrayList<>();
        tree(all).forEach(p -> names.add(p.get("name").asText()));
        assertEquals(List.of("Saga", "Loose"), names);
        // name substring, case-insensitive
        mvc.perform(get("/api/pools").param("q", "sag"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Saga"));
        // description substring
        mvc.perform(get("/api/pools").param("q", "work"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // exact tag match among FLATTENED CONTENT tags
        mvc.perform(get("/api/pools").param("q", "duck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // exact tag match among the pool's OWN tags
        mvc.perform(get("/api/pools").param("q", "curated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // the system tag matches as its FULL reference — bare "pool" matches nothing
        mvc.perform(get("/api/pools").param("q", "pool_type:pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/pools").param("q", "pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // AND across terms
        mvc.perform(get("/api/pools").param("q", "duck curated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/pools").param("q", "duck loose"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // facets over the SAME filtered set: pool_type first, then count desc / name asc.
        // Since T5 the facets endpoint passes the real drafts: the seeded chapters folder
        // contributes the fixed suggested pseudo-facet (pool(2) sorts before suggested(1))
        MvcResult facets = mvc.perform(get("/api/pools/facets")).andExpect(status().isOk()).andReturn();
        assertEquals(List.of("pool", "suggested", "curated", "duck"), facetNames(facets), facets.getResponse().getContentAsString());
        List<String> categories = new ArrayList<>();
        tree(facets).forEach(f -> categories.add(f.get("category").asText()));
        assertEquals(List.of("pool_type", "pool_type", "general", "general"), categories);
        List<Long> counts = new ArrayList<>();
        tree(facets).forEach(f -> counts.add(f.get("count").asLong()));
        assertEquals(List.of(2L, 1L, 1L, 1L), counts);
        assertTrue(facets.getResponse().getContentAsString().contains("suggested"));

        // facets respect q: only Saga survives the duck filter, and the draft's path
        // ("library/chapters") does not match "duck" → no suggested row on the filtered set
        MvcResult filtered = mvc.perform(get("/api/pools/facets").param("q", "duck"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(List.of("pool", "curated", "duck"), facetNames(filtered));
        List<Long> filteredCounts = new ArrayList<>();
        tree(filtered).forEach(f -> filteredCounts.add(f.get("count").asLong()));
        assertEquals(List.of(1L, 1L, 1L), filteredCounts);
    }

    @Test
    void facets_suggested_pseudo_facet_counts_the_drafts_matching_q() throws Exception {
        entryIn("solo", "only.png"); // a lone directory never qualifies as a draft

        // a draft matches a text term via its rootName/dirPath — "library/chapters"
        mvc.perform(get("/api/pools/facets").param("q", "chapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("suggested"))
                .andExpect(jsonPath("$[0].category").value("pool_type"))
                .andExpect(jsonPath("$[0].count").value(1));
        // nothing matches → no rows at all, no pseudo-facet
        mvc.perform(get("/api/pools/facets").param("q", "zzz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private long createPoolNamed(String name, String description) throws Exception {
        MvcResult res = mvc.perform(post("/api/pools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"" + description + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return tree(res).get("id").asLong();
    }

    private List<String> facetNames(MvcResult res) throws Exception {
        List<String> names = new ArrayList<>();
        tree(res).forEach(f -> names.add(f.get("name").asText()));
        return names;
    }
}
