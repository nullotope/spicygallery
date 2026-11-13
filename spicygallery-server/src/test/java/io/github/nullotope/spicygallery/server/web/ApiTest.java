package io.github.nullotope.spicygallery.server.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.Status;
import io.github.nullotope.spicygallery.importer.ContentHasher;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.embed.EmbeddingProvider;
import io.github.nullotope.spicygallery.server.embed.EmbeddingService;
import io.github.nullotope.spicygallery.server.store.EmbeddingStore;
import io.github.nullotope.spicygallery.server.store.JdbcMediaStoreSink;
import io.github.nullotope.spicygallery.thumb.ThumbLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ApiTest extends PostgresBase {

    static final Path FIX = Path.of("../spicygallery-core/src/test/resources/fixtures");
    static final Path LIB;
    static final Path APPDATA;
    static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    static {
        try {
            LIB = Files.createTempDirectory("sg-api-lib");
            APPDATA = Files.createTempDirectory("sg-api-app");
            Files.copy(FIX.resolve("plain.png"), LIB.resolve("plain.png"));
            Files.copy(FIX.resolve("plain.png"), LIB.resolve("50%.png"));
            Files.createDirectories(LIB.resolve("bulk"));
            for (int i = 0; i < 100; i++) { // bulk files → the first import run stays running a while
                Files.copy(FIX.resolve("plain.png"), LIB.resolve("bulk/f" + i + ".png"));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void galleryProps(DynamicPropertyRegistry r) {
        r.add("spicygallery.libraries[0].name", () -> "api");
        r.add("spicygallery.libraries[0].root", () -> LIB.toString());
        r.add("spicygallery.app-data", () -> APPDATA.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcMediaStoreSink sink;
    @Autowired JdbcClient jdbc;
    @Autowired io.github.nullotope.spicygallery.server.store.JdbcTagStore tagStore;
    @Autowired io.github.nullotope.spicygallery.server.store.ConceptStore conceptStore;
    @Autowired io.github.nullotope.spicygallery.server.store.JdbcImportRunStore runStore;
    @Autowired io.github.nullotope.spicygallery.server.store.PoolStore poolStore;

    private long plainPngId;
    private long duckTagId;

    @BeforeEach
    void cleanAndSeed() throws Exception {
        jdbc.sql("delete from media_tag").update();
        jdbc.sql("delete from tag_implication").update();
        jdbc.sql("delete from tag_alias").update();
        jdbc.sql("delete from tag").update();
        jdbc.sql("delete from concept_entry").update();
        jdbc.sql("delete from concept").update(); // concept names are unique: no leftovers
        jdbc.sql("delete from media_entry").update();
        jdbc.sql("delete from import_run").update();
        jdbc.sql("delete from pool").update(); // cascades pool_member + pool_tag (slice 6)

        // plain.png: real hash + real size (media endpoints assert against the file)
        byte[] realPng = Files.readAllBytes(LIB.resolve("plain.png"));
        String realHash = ContentHasher.sha256Hex(LIB.resolve("plain.png"));
        plainPngId = sink.insert(new NewEntry(Kind.IMAGE, "api", "plain.png", "plain.png", "png",
                "image/png", realPng.length, realHash, 8, 4, null, T)).value();
        sink.insert(entry("50%.png"));
        // 248 DB-only ok rows → 250 'ok' rows for the grid walk
        for (int i = 1; i <= 248; i++) {
            sink.insert(entry("seed/f" + i + ".png"));
        }
        EntryId gone = sink.insert(entry("vanished.png"));
        sink.setStatus(gone, Status.MISSING);
        EntryId bad = sink.insert(entry("broken.png"));
        sink.setStatus(bad, Status.ERROR);

        // two entries tagged 'duck' (plain.png + one seed file) for tag-query tests
        duckTagId = tagStore.create("duck", io.github.nullotope.spicygallery.domain.TagCategory.GENERAL).id();
        tagStore.tagEntry(plainPngId, duckTagId);
        tagStore.tagEntry(sink.find("api", "seed/f42.png").orElseThrow().id().value(), duckTagId);
        tagStore.bumpPostCount(duckTagId, 2);
    }

    private static NewEntry entry(String rel) {
        return new NewEntry(Kind.IMAGE, "api", rel, fileName(rel), "png", "image/png",
                77, "hash-" + rel, 8, 4, null, T);
    }

    private static String fileName(String rel) {
        return rel.substring(rel.lastIndexOf('/') + 1);
    }

    private MvcResult getOk(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
    }

    @Test
    void list_is_camelcase_keyset_and_bounded() throws Exception {
        mvc.perform(get("/api/entries")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(60))
                .andExpect(jsonPath("$.items[0].id").isNumber())
                .andExpect(jsonPath("$.items[0].kind").value("image"))
                .andExpect(jsonPath("$.items[0].thumbUrl").exists())
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());

        // walk all pages → 250 items, strictly descending ids
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (true) {
            String url = cursor == null ? "/api/entries" : "/api/entries?cursor=" + cursor;
            String body = mvc.perform(get(url)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var page = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(body);
            page.get("items").forEach(i -> seen.add(i.get("id").asLong()));
            var nc = page.get("nextCursor");
            if (nc == null || nc.isNull()) {
                break;
            }
            cursor = nc.asText();
            if (++guard > 20) {
                throw new AssertionError("pagination did not terminate");
            }
        }
        assertTrue(seen.size() == 250, "walked " + seen.size());
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i - 1) > seen.get(i));
        }
    }

    @Test
    void q_matches_display_path_and_escapes_literals() throws Exception {
        mvc.perform(get("/api/entries").param("q", "50%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].fileName").value("50%.png"));
        mvc.perform(get("/api/entries").param("q", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/entries").param("q", "seed/f7.png"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void detail_fields_and_404() throws Exception {
        mvc.perform(get("/api/entries/" + plainPngId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(plainPngId))
                .andExpect(jsonPath("$.root").value("api"))
                .andExpect(jsonPath("$.path").value("api/plain.png"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.mime").value("image/png"))
                .andExpect(jsonPath("$.fileUrl").exists())
                .andExpect(jsonPath("$.thumbUrl").exists());
        mvc.perform(get("/api/entries/99999")).andExpect(status().isNotFound());
    }

    @Test
    void neighbors_follow_grid_order() throws Exception {
        // the grid is id DESC: "prev" is the tile BEFORE the current one in the grid
        // (the higher id), "next" is the tile AFTER it (the lower id). The endpoint
        // must agree with the client's in-memory list (spec §8 consistency) — a
        // deep-linked /entries/221 must have no prev and a next, same as in-grid.
        long lowest = plainPngId;
        long highest = sink.find("api", "seed/f248.png").orElseThrow().id().value();

        mvc.perform(get("/api/entries/" + highest + "/neighbors")).andExpect(status().isOk())
                .andExpect(jsonPath("$.prevId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.nextId").isNumber());
        mvc.perform(get("/api/entries/" + lowest + "/neighbors")).andExpect(status().isOk())
                .andExpect(jsonPath("$.prevId").isNumber())
                .andExpect(jsonPath("$.nextId").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/entries/99999/neighbors")).andExpect(status().isNotFound());
    }

    @Test
    void file_serves_with_range_and_etag() throws Exception {
        String hash = ContentHasher.sha256Hex(LIB.resolve("plain.png"));
        long size = Files.size(LIB.resolve("plain.png"));
        mvc.perform(get("/api/entries/" + plainPngId + "/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("ETag", "\"" + hash + "\""))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")));

        mvc.perform(get("/api/entries/" + plainPngId + "/file").header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-3/" + size));
    }

    @Test
    void thumb_serves_immutable_and_v_param_is_ignored() throws Exception {
        byte[] fakeThumb = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        Path thumb = ThumbLayout.pathFor(Path.of(APPDATA.toString()), plainPngId);
        Files.createDirectories(thumb.getParent());
        Files.write(thumb, fakeThumb);

        mvc.perform(get("/api/entries/" + plainPngId + "/thumb").param("v", "12345"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(fakeThumb))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")));
    }

    @Test
    void traversal_relpath_is_never_served() throws Exception {
        // review I5: the served path is built from the STORED rel_path; a planted
        // row whose rel_path escapes the library (e.g. via a backslash-named file
        // or DB tampering) must 404, never serve files outside the root
        sink.insert(new NewEntry(Kind.IMAGE, "api", "../../etc/hostname", "hostname",
                "png", "image/png", 5, "hash-x", null, null, null, T));
        long id = sink.find("api", "../../etc/hostname").orElseThrow().id().value();
        mvc.perform(get("/api/entries/" + id + "/file")).andExpect(status().isNotFound());
    }

    @Test
    void missing_entry_file_is_404() throws Exception {
        long goneId = sink.find("api", "vanished.png").orElseThrow().id().value();
        mvc.perform(get("/api/entries/" + goneId + "/file"))
                .andExpect(status().isNotFound());
    }

    @Test
    void error_status_row_hidden_from_grid_but_detail_works() throws Exception {
        mvc.perform(get("/api/entries").param("q", "broken.png"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        long badId = sink.find("api", "broken.png").orElseThrow().id().value();
        mvc.perform(get("/api/entries/" + badId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void last_run_endpoint_on_fresh_install_returns_null_run_not_500() throws Exception {
        // regression (review C1): Map.of rejects null values — a fresh install with
        // zero runs must answer 200 {"run": null}, the reconcile source on first load
        jdbc.sql("delete from import_run").update();
        mvc.perform(get("/api/imports/last"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void last_run_surfaces_the_follow_up_pool_id() throws Exception {
        long runId = runStore.start();
        long poolId = poolStore.create("surfaced", null, null, null).id();
        runStore.setPoolId(runId, poolId);

        mvc.perform(get("/api/imports/last"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.poolId").value(poolId));
    }

    @Test
    void import_start_with_create_pool_body_completes_and_links_pool() throws Exception {
        mvc.perform(post("/api/imports")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"createPool\": true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").isNumber());

        // wait for completion (bulk ingest of 102 files + the pool follow-up)
        String body;
        long deadline = System.currentTimeMillis() + 60_000;
        String status;
        do {
            Thread.sleep(100);
            assertTrue(System.currentTimeMillis() < deadline, "import did not finish");
            body = getOk("/api/imports/last").getResponse().getContentAsString();
            status = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(body).get("run").get("status").asText();
        } while (status.equals("running"));
        org.junit.jupiter.api.Assertions.assertEquals("completed", status);

        mvc.perform(get("/api/imports/last"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.poolId").isNumber());
    }

    @Test
    void import_start_conflict_last_and_events() throws Exception {
        MvcResult started = mvc.perform(post("/api/imports"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").isNumber())
                .andReturn();
        assertTrue(started.getResponse().getContentAsString().contains("runId"));

        // while the bulk import runs → 409
        mvc.perform(post("/api/imports")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.runId").isNumber());

        mvc.perform(get("/api/imports/last")).andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("running"));

        // SSE endpoint subscribes (async request started; the stream stays open)
        mvc.perform(get("/api/imports/events"))
                .andExpect(request().asyncStarted());

        // wait for completion (bulk ingest of 102 files)
        String body;
        long deadline = System.currentTimeMillis() + 60_000;
        String status;
        do {
            Thread.sleep(100);
            assertTrue(System.currentTimeMillis() < deadline, "import did not finish");
            body = getOk("/api/imports/last").getResponse().getContentAsString();
            status = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(body).get("run").get("status").asText();
        } while (status.equals("running"));
        org.junit.jupiter.api.Assertions.assertEquals("completed", status);
    }

    @Test
    void system_info_reports_libraries_and_ffmpeg() throws Exception {
        mvc.perform(get("/api/system/info")).andExpect(status().isOk())
                .andExpect(jsonPath("$.libraries[0].name").value("api"))
                .andExpect(jsonPath("$.libraries[0].accessible").value(true))
                .andExpect(jsonPath("$.ffmpeg.found").value(true))
                .andExpect(jsonPath("$.ffmpeg.source").value("path"))
                .andExpect(jsonPath("$.workers").value(4))
                .andExpect(jsonPath("$.appData").exists());
    }

    @Test
    void param_validation_rejects_bad_input() throws Exception {
        mvc.perform(get("/api/entries").param("cursor", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/entries").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/entries").param("limit", "201")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/entries").param("kind", "dvd")).andExpect(status().isBadRequest());
    }

    // --- slice 2: query language, facets, random seed, detail tags ---

    @Test
    void list_parses_tag_and_metatag_queries() throws Exception {
        mvc.perform(get("/api/entries").param("q", "duck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        // all seeded rows are 8px-wide images: >7 matches all, >8 matches none
        mvc.perform(get("/api/entries").param("q", "type:image width:>7").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/entries").param("q", "width:>8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        // a pure exclusion keeps matching everything else (no 500, no forced-empty)
        mvc.perform(get("/api/entries").param("q", "-type:image"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void parse_error_is_400_with_token_and_position() throws Exception {
        mvc.perform(get("/api/entries").param("q", "foo:bar"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.token").value("foo:bar"))
                .andExpect(jsonPath("$.position").value(0));
    }

    @Test
    void facets_on_first_page_only_and_exclude_included_terms() throws Exception {
        String body = mvc.perform(get("/api/entries"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var page = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        var duckFacet = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        page.get("facets").forEach(f -> duckFacet.add(f));
        var duck = duckFacet.stream()
                .filter(f -> "duck".equals(f.get("name").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("no duck facet: " + body));
        org.junit.jupiter.api.Assertions.assertEquals(2, duck.get("count").asLong());

        String duckBody = mvc.perform(get("/api/entries").param("q", "duck"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var duckPage = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(duckBody);
        boolean duckInFacets = false;
        for (var f : duckPage.get("facets")) {
            if ("duck".equals(f.get("name").asText())) {
                duckInFacets = true;
            }
        }
        assertFalse(duckInFacets, "included tag must not appear in facets: " + duckBody);

        String page2 = mvc.perform(get("/api/entries").param("cursor", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(page2.contains("facets"), "page 2+ carries no facets key");
    }

    @Test
    void random_order_pages_are_disjoint_and_complete() throws Exception {
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (true) {
            String url = cursor == null
                    ? "/api/entries?q=order:random&limit=50&rs=abc123"
                    : "/api/entries?q=order:random&limit=50&rs=abc123&cursor=" + cursor;
            String body = mvc.perform(get(url)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.rs").value("abc123"))
                    .andReturn().getResponse().getContentAsString();
            var page = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body);
            page.get("items").forEach(i -> seen.add(i.get("id").asLong()));
            if (page.get("nextCursor").isNull()) {
                break;
            }
            cursor = page.get("nextCursor").asText();
            if (++guard > 20) {
                throw new AssertionError("random pagination did not terminate");
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(250, seen.size());
        org.junit.jupiter.api.Assertions.assertEquals(250, new HashSet<>(seen).size(), "no repeats across pages");

        // a fresh request generates a seed; replaying it reproduces the shuffle
        String first = mvc.perform(get("/api/entries?q=order:random&limit=5"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var firstPage = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(first);
        String seed = firstPage.get("rs").asText();
        assertFalse(seed.isEmpty());
        String replay = mvc.perform(get("/api/entries?q=order:random&limit=5").param("rs", seed))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(
                firstPage.get("items").toString(),
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(replay).get("items").toString());
    }

    @Test
    void detail_embeds_tags() throws Exception {
        mvc.perform(get("/api/entries/" + plainPngId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0].name").value("duck"))
                .andExpect(jsonPath("$.tags[0].category").value("general"))
                .andExpect(jsonPath("$.tags[0].postCount").value(2));
    }

    @Test
    void neighbors_respect_tag_query() throws Exception {
        long lowest = plainPngId; // tagged duck, lowest id
        long highest = sink.find("api", "seed/f42.png").orElseThrow().id().value(); // tagged duck, higher id

        mvc.perform(get("/api/entries/" + highest + "/neighbors").param("q", "duck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prevId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.nextId").value(lowest));
        mvc.perform(get("/api/entries/" + lowest + "/neighbors").param("q", "duck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prevId").value(highest))
                .andExpect(jsonPath("$.nextId").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---- slice 6: pool-scoped reading — neighbors?pool= (spec §3.9) ----------------

    /** A DB-only 'ok' row for pool fixtures (never served; ids follow insertion order). */
    private long poolEntry(String rel) {
        return sink.insert(entry(rel)).value();
    }

    private long createPool(String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/pools")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private void addPoolMembers(long poolId, String json) throws Exception {
        mvc.perform(post("/api/pools/" + poolId + "/members")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    @Test
    void neighbors_pool_scoped_follow_flatten_order() throws Exception {
        // direct members: prev/next follow the POOL's member order (append order here),
        // not the grid's id order — and the pool keys ride along (additive, spec §3.9)
        long a = poolEntry("pool/a.png");
        long b = poolEntry("pool/b.png");
        long c = poolEntry("pool/c.png");
        long work = createPool("Work");
        addPoolMembers(work, "{\"entryIds\":[" + c + "," + a + "," + b + "]}");

        // middle member: position 2 of 3 (append order [c, a, b] → a is the middle)
        mvc.perform(get("/api/entries/" + a + "/neighbors").param("pool", String.valueOf(work)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prevId").value(c))
                .andExpect(jsonPath("$.nextId").value(b))
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.total").value(3));
        // first member: no prev, position 1
        mvc.perform(get("/api/entries/" + c + "/neighbors").param("pool", String.valueOf(work)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prevId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.nextId").value(a))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.total").value(3));
        // last member: no next, position = total
        mvc.perform(get("/api/entries/" + b + "/neighbors").param("pool", String.valueOf(work)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prevId").value(a))
                .andExpect(jsonPath("$.nextId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.position").value(3))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void neighbors_pool_scoped_walk_nested_grandchildren() throws Exception {
        // Review Focus #5: the entry sits in the pool ONLY via nesting (Work → Chapter →
        // entry); the flatten — not direct membership — decides the reading order
        long g1 = poolEntry("ch/sub/first.png");
        long g2 = poolEntry("ch/sub/second.png");
        long chapter = createPool("Chapter");
        addPoolMembers(chapter, "{\"entryIds\":[" + g2 + "," + g1 + "]}");
        long work = createPool("Work");
        addPoolMembers(work, "{\"poolIds\":[" + chapter + "]}");

        mvc.perform(get("/api/entries/" + g1 + "/neighbors").param("pool", String.valueOf(work)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prevId").value(g2))
                .andExpect(jsonPath("$.nextId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/entries/" + g2 + "/neighbors").param("pool", String.valueOf(work)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prevId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.nextId").value(g1))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void neighbors_pool_position_and_total_cross_chapter_boundaries() throws Exception {
        // Work = [Chapter 1 (c1a, c1b), Chapter 2 (c2a), loose] — the flatten runs straight
        // through both chapters into the direct member; ids are scrambled on purpose so a
        // grid-order answer would differ from the flatten answer
        long loose = poolEntry("loose.png");
        long c1b = poolEntry("ch1/page2.png");
        long c1a = poolEntry("ch1/page1.png");
        long c2a = poolEntry("ch2/page1.png");
        long ch1 = createPool("Chapter 1");
        addPoolMembers(ch1, "{\"entryIds\":[" + c1a + "," + c1b + "]}");
        long ch2 = createPool("Chapter 2");
        addPoolMembers(ch2, "{\"entryIds\":[" + c2a + "]}");
        long work = createPool("Work");
        // append is entries-then-pools per call; two calls put the direct member LAST so the
        // flatten runs [Chapter 1, Chapter 2, loose] and crosses back OUT of the nesting
        addPoolMembers(work, "{\"poolIds\":[" + ch1 + "," + ch2 + "]}");
        addPoolMembers(work, "{\"entryIds\":[" + loose + "]}");

        // end of Chapter 1 → next is the FIRST entry of Chapter 2 (the boundary crossing)
        mvc.perform(get("/api/entries/" + c1b + "/neighbors").param("pool", String.valueOf(work)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prevId").value(c1a))
                .andExpect(jsonPath("$.nextId").value(c2a))
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.total").value(4));
        // first of Chapter 2 → prev stays in Chapter 1, next leaves the nesting
        mvc.perform(get("/api/entries/" + c2a + "/neighbors").param("pool", String.valueOf(work)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prevId").value(c1b))
                .andExpect(jsonPath("$.nextId").value(loose))
                .andExpect(jsonPath("$.position").value(3))
                .andExpect(jsonPath("$.total").value(4));
        // the direct member is position 4 of 4
        mvc.perform(get("/api/entries/" + loose + "/neighbors").param("pool", String.valueOf(work)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prevId").value(c2a))
                .andExpect(jsonPath("$.nextId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.position").value(4))
                .andExpect(jsonPath("$.total").value(4));
    }

    @Test
    void neighbors_pool_not_contained_is_404_naming_the_pool() throws Exception {
        long work = createPool("Work");
        long outsider = poolEntry("elsewhere/outsider.png");

        mvc.perform(get("/api/entries/" + outsider + "/neighbors").param("pool", String.valueOf(work)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("entry " + outsider + " is not in pool " + work));
        // an EMPTY pool contains nothing → every entry is "not contained"
        mvc.perform(get("/api/entries/" + plainPngId + "/neighbors").param("pool", String.valueOf(work)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("entry " + plainPngId + " is not in pool " + work));
    }

    @Test
    void neighbors_pool_unknown_pool_is_404() throws Exception {
        mvc.perform(get("/api/entries/" + plainPngId + "/neighbors").param("pool", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("no pool 999999"));
    }

    @Test
    void neighbors_without_pool_stay_byte_identical() throws Exception {
        // ?pool absent → today's two-key shape, exactly: no position/total keys may leak
        long highest = sink.find("api", "seed/f248.png").orElseThrow().id().value();
        long secondHighest = sink.find("api", "seed/f247.png").orElseThrow().id().value();
        long secondLowest = sink.find("api", "50%.png").orElseThrow().id().value();

        mvc.perform(get("/api/entries/" + highest + "/neighbors")).andExpect(status().isOk())
                .andExpect(jsonPath("$.position").doesNotExist())
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(content().string("{\"prevId\":null,\"nextId\":" + secondHighest + "}"));
        mvc.perform(get("/api/entries/" + plainPngId + "/neighbors")).andExpect(status().isOk())
                .andExpect(jsonPath("$.position").doesNotExist())
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(content().string("{\"prevId\":" + secondLowest + ",\"nextId\":null}"));
    }

    // ---- slice 3: similar: + related (spec §4/§5) --------------------------------

    /** Deterministic fake provider replacing the real client (plan: @Primary test bean). */
    @org.springframework.boot.test.context.TestConfiguration
    static class FakeEmbeddingProvider {
        final List<String> texts = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean fail;

        @org.springframework.context.annotation.Primary
        @org.springframework.context.annotation.Bean
        EmbeddingProvider fakeProvider() {
            return inputs -> {
                var out = new java.util.ArrayList<float[]>(inputs.size());
                for (var in : inputs) {
                    if (in.image() != null) {
                        throw new IllegalStateException("ApiTest fakes only answer text");
                    }
                    texts.add(in.text());
                    if (fail) {
                        throw new java.io.IOException("fake provider down");
                    }
                    float[] v = new float[768];
                    v[0] = 1; // deterministic query axis: [1,0,0,…]
                    out.add(v);
                }
                return out;
            };
        }
    }

    @Autowired FakeEmbeddingProvider fake;
    @Autowired EmbeddingService embedService; // resetBreaker() between tests (shared context)
    @Autowired EmbeddingStore embeddings;

    @BeforeEach
    void resetFakeProvider() {
        fake.texts.clear();
        fake.fail = false;
        jdbc.sql("delete from media_embedding").update();
        embedService.resetBreaker();
    }

    private long seedEmbed(String rel, float... near) {
        long id = sink.find("api", rel).orElseThrow().id().value();
        float[] v = new float[768];
        for (int i = 0; i < near.length; i++) {
            v[i] = near[i];
        }
        embeddings.replaceAll(id, "embeddinggemma-2",
                List.of(new EmbeddingStore.FrameVector(0, null, v)));
        return id;
    }

    @Test
    void similar_query_ranks_by_semantic_distance() throws Exception {
        long near = seedEmbed("seed/f1.png", 1, 0);
        long mid = seedEmbed("seed/f2.png", 0.6f, 0.8f);
        long far = seedEmbed("seed/f3.png", 0, 1);

        MvcResult res = mvc.perform(get("/api/entries").param("q", "similar:needle-near"))
                .andExpect(status().isOk())
                .andReturn();
        var items = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(res.getResponse().getContentAsString()).get("items");
        org.junit.jupiter.api.Assertions.assertEquals(3, items.size());
        org.junit.jupiter.api.Assertions.assertEquals(near, items.get(0).get("id").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(mid, items.get(1).get("id").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(far, items.get(2).get("id").asLong());
        double score = items.get(0).get("score").asDouble();
        org.junit.jupiter.api.Assertions.assertTrue(score > 0.9f && score <= 1.0f, String.valueOf(score));
    }

    @Test
    void similar_composes_with_tags_and_kind() throws Exception {
        long video = seedEmbedVideo("seed/f5.png", 0.5f, 0.5f); // f5 is the only video row
        seedEmbed("seed/f42.png", 1, 0); // duck-tagged image
        seedEmbed("seed/f1.png", 1, 0);

        // duck similar:"…" → only the tagged image, ranked (the video carries no duck tag)
        String duckBody = mvc.perform(get("/api/entries").param("q", "duck similar:needle-tag").param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var duckItems = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(duckBody).get("items");
        org.junit.jupiter.api.Assertions.assertEquals(1, duckItems.size(), duckBody);
        org.junit.jupiter.api.Assertions.assertEquals(
                sink.find("api", "seed/f42.png").orElseThrow().id().value(),
                duckItems.get(0).get("id").asLong());
        var duckTree = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(duckBody);
        org.junit.jupiter.api.Assertions.assertTrue(duckTree.get("facets").isArray(), duckBody);

        // type:video → only the video candidate survives (images f42/f1 excluded by kind)
        mvc.perform(get("/api/entries").param("q", "similar:needle-tag2 type:video").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(video));

        // and the negated form -type:video keeps the embedded IMAGES (f42 + f1), not the video
        mvc.perform(get("/api/entries").param("q", "similar:needle-tag3 -type:video").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    private long seedEmbedVideo(String rel, float... near) {
        long id = sink.find("api", rel).orElseThrow().id().value();
        jdbc.sql("update media_entry set kind = 'video' where id = :id").param("id", id).update();
        float[] v = new float[768];
        for (int i = 0; i < near.length; i++) {
            v[i] = near[i];
        }
        embeddings.replaceAll(id, "embeddinggemma-2",
                List.of(new EmbeddingStore.FrameVector(0, null, v)));
        return id;
    }

    @Test
    void similar_pagination_does_not_repeat_and_reuses_text_vector() throws Exception {
        for (int i = 1; i <= 4; i++) {
            float jitter = 1f - i / 10f;
            seedEmbed("seed/f" + (i + 10) + ".png", jitter, 0);
        }
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (guard++ < 10) {
            String url = cursor == null ? "/api/entries?q=similar:needle-page&limit=2"
                    : "/api/entries?q=similar:needle-page&limit=2&cursor=" + cursor;
            String body = mvc.perform(get(url)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var page = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body);
            page.get("items").forEach(it -> seen.add(it.get("id").asLong()));
            var nc = page.get("nextCursor");
            if (nc == null || nc.isNull()) {
                break;
            }
            cursor = nc.asText();
        }
        org.junit.jupiter.api.Assertions.assertEquals(4, seen.size(), "complete walk: " + seen);
        org.junit.jupiter.api.Assertions.assertEquals(4, new HashSet<>(seen).size(), "no repeats");
        // the phrase is embedded ONCE for the whole walk (text cache, Review Focus 3)
        org.junit.jupiter.api.Assertions.assertEquals(
                1, fake.texts.stream().filter("needle-page"::equals).count(), String.valueOf(fake.texts));
    }

    @Test
    void score_absent_for_non_semantic_queries() throws Exception {
        String body = mvc.perform(get("/api/entries").param("q", "duck").param("limit", "5"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var page = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        page.get("items").forEach(it ->
                org.junit.jupiter.api.Assertions.assertFalse(it.has("score"), body));
    }

    @Test
    void similar_with_unreachable_provider_is_503() throws Exception {
        fake.fail = true;
        // provider call fails → 503 with the breaker message, never a 500
        mvc.perform(get("/api/entries").param("q", "similar:needle-broken2"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("embedding provider unreachable"));
        // the breaker is open now: the follow-up 503 is a fast-fail (no provider call)
        int callsSoFar = fake.texts.size();
        mvc.perform(get("/api/entries").param("q", "similar:needle-broken3"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("embedding provider unreachable"));
        org.junit.jupiter.api.Assertions.assertEquals(callsSoFar, fake.texts.size(), "no retry while breaker open");
    }

    @Test
    void related_orders_excludes_self_and_404s() throws Exception {
        long a = seedEmbed("seed/f20.png", 1, 0);
        long b = seedEmbed("seed/f21.png", 0.9f, 0.1f);
        long c = seedEmbed("seed/f22.png", 0, 1);

        String body = mvc.perform(get("/api/entries/" + a + "/related").param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var items = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("items");
        org.junit.jupiter.api.Assertions.assertEquals(2, items.size());
        org.junit.jupiter.api.Assertions.assertEquals(b, items.get(0).get("id").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(c, items.get(1).get("id").asLong());
        org.junit.jupiter.api.Assertions.assertTrue(items.get(0).get("score").asDouble()
                        >= items.get(1).get("score").asDouble(),
                "score descending: " + body);

        // limit cap 24 is not exceeded by the param
        mvc.perform(get("/api/entries/" + a + "/related").param("limit", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        // un-embedded entry: 200 + empty
        long bare = sink.find("api", "seed/f30.png").orElseThrow().id().value();
        mvc.perform(get("/api/entries/" + bare + "/related"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        // unknown entry: 404
        mvc.perform(get("/api/entries/99999999/related"))
                .andExpect(status().isNotFound());
    }

    // ---- slice 5: concept: metatag end-to-end (spec §3.4) --------------------------

    /** Creates a concept via the API and seeds its examples; the anchor recomputes from
     *  the examples' STORED vectors (no provider involvement — the recompute is pure DB). */
    private long createConceptWithExamples(String name, long... exampleIds) throws Exception {
        String res = mvc.perform(post("/api/concepts")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(res).get("id").asLong();
        StringBuilder ids = new StringBuilder("{\"entryIds\":[");
        for (int i = 0; i < exampleIds.length; i++) {
            ids.append(i > 0 ? "," : "").append(exampleIds[i]);
        }
        mvc.perform(post("/api/concepts/" + id + "/examples")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(ids.append("]}").toString()))
                .andExpect(status().isOk());
        return id;
    }

    @Test
    void concept_query_ranks_by_anchor_and_composes_with_kind() throws Exception {
        long a1 = seedEmbed("seed/f1.png", 1, 0);
        long a2 = seedEmbed("seed/f2.png", 1, 0);
        long b1 = seedEmbed("seed/f3.png", 0, 1);
        long b2 = seedEmbed("seed/f4.png", 0, 1);
        long video = seedEmbedVideo("seed/f5.png", 1, 0); // f5 is the only video row
        createConceptWithExamples("cluster-a", a1, a2); // anchor = [1,0,…]

        String body = mvc.perform(get("/api/entries").param("q", "concept:cluster-a").param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var page = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        var items = page.get("items");
        // every embedded entry survives, ranked by anchor distance; ties broken by id asc
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of(a1, a2, video, b1, b2),
                List.of(items.get(0).get("id").asLong(), items.get(1).get("id").asLong(),
                        items.get(2).get("id").asLong(), items.get(3).get("id").asLong(),
                        items.get(4).get("id").asLong()),
                body);
        double score = items.get(0).get("score").asDouble();
        org.junit.jupiter.api.Assertions.assertTrue(score > 0.9f && score <= 1.0f, String.valueOf(score));
        org.junit.jupiter.api.Assertions.assertTrue(page.get("facets").isArray(), body);

        // composes: kind: keeps only the video member of the ranked set
        mvc.perform(get("/api/entries").param("q", "concept:cluster-a type:video").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(video));
        // …and the negated kind keeps the images
        mvc.perform(get("/api/entries").param("q", "concept:cluster-a -type:video").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].id").value(a1));
    }

    @Test
    void concept_query_unknown_name_is_400() throws Exception {
        mvc.perform(get("/api/entries").param("q", "concept:nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("unknown concept 'nope'"));
    }

    @Test
    void concept_and_similar_together_is_400() throws Exception {
        // one distance ordering per query (spec §3.4) — rejected at compile, 400 via the
        // existing IllegalArgumentException mapper
        mvc.perform(get("/api/entries").param("q", "similar:needle concept:cluster-a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("only one of similar:/concept: per query"));
    }

    @Test
    void concept_query_with_stale_anchor_is_409() throws Exception {
        long a1 = seedEmbed("seed/f1.png", 1, 0);
        long a2 = seedEmbed("seed/f2.png", 1, 0);
        long conceptId = createConceptWithExamples("stale-c", a1, a2);
        // a foreign anchorModel = built for another model; comparing it against
        // current-model vectors is garbage (spec §3.4) → 409, message distinguishes
        // "stale, refresh it" from "no anchor yet"
        conceptStore.updateAnchor(conceptId, conceptStore.byId(conceptId).orElseThrow().anchor(), "older-model");
        mvc.perform(get("/api/entries").param("q", "concept:stale-c"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("concept 'stale-c' is stale: anchor was built for "
                        + "model 'older-model', current model is 'embeddinggemma-2' — refresh it"));
    }

    @Test
    void repeated_concept_terms_first_wins() throws Exception {
        long a1 = seedEmbed("seed/f1.png", 1, 0);
        long a2 = seedEmbed("seed/f2.png", 1, 0);
        long b1 = seedEmbed("seed/f3.png", 0, 1);
        long b2 = seedEmbed("seed/f4.png", 0, 1);
        createConceptWithExamples("first-a", a1, a2);
        createConceptWithExamples("second-b", b1, b2);

        // both terms parse (like repeated similar:); the FIRST concept drives the ordering
        String body = mvc.perform(get("/api/entries")
                        .param("q", "concept:first-a concept:second-b").param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var items = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("items");
        org.junit.jupiter.api.Assertions.assertEquals(a1, items.get(0).get("id").asLong(), body);
        org.junit.jupiter.api.Assertions.assertEquals(a2, items.get(1).get("id").asLong(), body);
    }
}
