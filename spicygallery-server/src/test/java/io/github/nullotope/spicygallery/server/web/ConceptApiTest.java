package io.github.nullotope.spicygallery.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.embed.EmbeddingProvider;
import io.github.nullotope.spicygallery.server.embed.EmbeddingService;
import io.github.nullotope.spicygallery.server.store.ConceptStore;
import io.github.nullotope.spicygallery.server.store.EmbeddingStore;
import io.github.nullotope.spicygallery.server.store.JdbcMediaStoreSink;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Concepts REST API (spec §3.5, slice 5 task 5, plus the §4.2 members list): the
 * endpoints and the exception contract — 404 unknown concept id (the house pattern) /
 * 400 unknown name ({@code concept:} metatag) / bad input, 409 duplicate + stale, 422
 * refresh with no current-model inputs, 503 provider down at phrase resolution. Vectors
 * are seeded straight into {@link EmbeddingStore}; the phrase fake is deterministic per
 * text so PATCH-phrase provably moves the anchor. Anchor recompute never calls the provider.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConceptApiTest extends PostgresBase {

    static final Path FIX = Path.of("../spicygallery-core/src/test/resources/fixtures");
    static final Path LIB;
    static final Path APPDATA;
    static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
    static final String MODEL = "embeddinggemma-2"; // SpicyGalleryProperties default

    static {
        try {
            LIB = Files.createTempDirectory("sg-concept-lib");
            APPDATA = Files.createTempDirectory("sg-concept-app");
            Files.copy(FIX.resolve("plain.png"), LIB.resolve("c1.png"));
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

    /** Same override pattern as ApiTest's fake, but scripted per text so distinct
     *  phrases produce distinct, assertable vectors (PATCH-phrase moves the anchor). */
    @TestConfiguration
    static class FakeEmbeddingProvider {
        final List<String> texts = new CopyOnWriteArrayList<>();
        final Map<String, float[]> scripted = new ConcurrentHashMap<>();
        volatile boolean fail;

        @Primary
        @Bean
        EmbeddingProvider fakeProvider() {
            return inputs -> {
                var out = new java.util.ArrayList<float[]>(inputs.size());
                for (var in : inputs) {
                    if (in.image() != null) {
                        throw new IllegalStateException("ConceptApiTest fakes only answer text");
                    }
                    texts.add(in.text());
                    if (fail) {
                        throw new java.io.IOException("fake provider down");
                    }
                    float[] v = scripted.get(in.text());
                    if (v == null) {
                        v = new float[768];
                        v[0] = 1;
                    }
                    out.add(v);
                }
                return out;
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcMediaStoreSink sink;
    @Autowired JdbcClient jdbc;
    @Autowired EmbeddingStore embeddings;
    @Autowired ConceptStore concepts;
    @Autowired JdbcTagStore tagStore;
    @Autowired EmbeddingService embedService;
    @Autowired FakeEmbeddingProvider fake;

    private long e1;
    private long e2;
    private long e3;
    private long e4;
    private long e5;

    @BeforeEach
    void cleanAndSeed() {
        jdbc.sql("delete from media_tag").update();
        jdbc.sql("delete from tag_implication").update();
        jdbc.sql("delete from tag_alias").update();
        jdbc.sql("delete from tag").update();
        jdbc.sql("delete from concept_entry").update();
        jdbc.sql("delete from concept").update();
        jdbc.sql("delete from media_embedding").update();
        jdbc.sql("delete from media_entry").update();

        fake.texts.clear();
        fake.scripted.clear();
        fake.fail = false;
        embedService.resetBreaker();

        e1 = insert("c1.png");
        e2 = insert("c2.png");
        e3 = insert("c3.png");
        e4 = insert("c4.png");
        e5 = insert("c5.png"); // stays vector-less: drops out of suggestions silently
    }

    private long insert(String rel) {
        return sink.insert(new NewEntry(Kind.IMAGE, "api", rel, rel, "png", "image/png",
                77, "hash-" + rel, 8, 4, null, T)).value();
    }

    /** 768-dim vector with the first dims set — cosine rank order follows the prefix. */
    private static float[] vec(float... dims) {
        float[] v = new float[768];
        System.arraycopy(dims, 0, v, 0, dims.length);
        return v;
    }

    private void seedEmbed(long entryId, float... dims) {
        embeddings.replaceAll(entryId, MODEL, List.of(new EmbeddingStore.FrameVector(0, null, vec(dims))));
    }

    private long createConcept(String name) throws Exception {
        return createConcept(name, null);
    }

    private long createConcept(String name, String phrase) throws Exception {
        String body = phrase == null ? "{\"name\":\"" + name + "\"}"
                : "{\"name\":\"" + name + "\",\"phrase\":\"" + phrase + "\"}";
        String res = mvc.perform(post("/api/concepts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(res).get("id").asLong();
    }

    private void addExamples(long conceptId, long... entryIds) throws Exception {
        StringBuilder json = new StringBuilder("{\"entryIds\":[");
        for (int i = 0; i < entryIds.length; i++) {
            json.append(i > 0 ? "," : "").append(entryIds[i]);
        }
        mvc.perform(post("/api/concepts/" + conceptId + "/examples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.append("]}").toString()))
                .andExpect(status().isOk());    }

    private ConceptStore.ConceptRow row(long conceptId) {
        return concepts.byId(conceptId).orElseThrow();
    }

    private static void assertVecEquals(float[] expected, float[] actual) {
        assertTrue(actual != null, "anchor is null");
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-6f, "dim " + i);
        }
    }

    // --- create -------------------------------------------------------------------

    @Test
    void create_with_phrase_resolves_now_and_computes_anchor() throws Exception {
        fake.scripted.put("phrase-create-alpha", vec(1, 0));
        mvc.perform(post("/api/concepts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Oldname!\",\"phrase\":\"phrase-create-alpha\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("oldname"))
                .andExpect(jsonPath("$.phrase").value("phrase-create-alpha"))
                .andExpect(jsonPath("$.exampleCount").value(0))
                .andExpect(jsonPath("$.excludedCount").value(0))
                .andExpect(jsonPath("$.taggedCount").value(0))
                .andExpect(jsonPath("$.applyable").value(0))
                .andExpect(jsonPath("$.anchorStale").value(false))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
        assertTrue(fake.texts.contains("phrase-create-alpha"), "phrase resolved at create: " + fake.texts);
        assertVecEquals(vec(1, 0), row(concepts.byName("oldname").orElseThrow().id()).anchor());
        assertEquals(MODEL, row(concepts.byName("oldname").orElseThrow().id()).anchorModel());
    }

    @Test
    void create_without_phrase_leaves_anchor_null_and_suggestions_409() throws Exception {
        long id = createConcept("anchorless");
        mvc.perform(get("/api/concepts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phrase").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.anchorStale").value(false));
        assertTrue(row(id).anchor() == null, "no inputs ever → anchor null");
        mvc.perform(get("/api/concepts/" + id + "/suggestions"))
                .andExpect(status().isConflict());
    }

    @Test
    void duplicate_name_is_409() throws Exception {
        createConcept("dupe");
        mvc.perform(post("/api/concepts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  DUPE  \"}")) // normalization must not bypass uniqueness
                .andExpect(status().isConflict());
    }

    // --- read ---------------------------------------------------------------------

    @Test
    void unknown_concept_id_is_404_on_every_route() throws Exception {
        // the house pattern (MissingEntryException's 404), not a 400
        mvc.perform(get("/api/concepts/424242")).andExpect(status().isNotFound());
        mvc.perform(get("/api/concepts/424242/members")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/concepts/424242").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/concepts/424242")).andExpect(status().isNotFound());
        mvc.perform(get("/api/concepts/424242/suggestions")).andExpect(status().isNotFound());
        mvc.perform(post("/api/concepts/424242/apply")).andExpect(status().isNotFound());
        mvc.perform(post("/api/concepts/424242/refresh")).andExpect(status().isNotFound());
        mvc.perform(post("/api/concepts/424242/examples").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[]}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/concepts/424242/exclusions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[]}")).andExpect(status().isNotFound());
    }

    @Test
    void members_lists_both_statuses_grid_shaped_and_id_ordered() throws Exception {
        long id = createConcept("members");
        addExamples(id, e2, e1); // insertion order differs from id order
        mvc.perform(post("/api/concepts/" + id + "/exclusions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + e3 + "]}"))
                .andExpect(status().isOk());

        String body = mvc.perform(get("/api/concepts/" + id + "/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examples.length()").value(2))
                .andExpect(jsonPath("$.examples[0].id").value(e1))
                .andExpect(jsonPath("$.examples[1].id").value(e2))
                .andExpect(jsonPath("$.examples[0].thumbUrl").exists())
                .andExpect(jsonPath("$.examples[0].fileName").exists())
                .andExpect(jsonPath("$.excluded.length()").value(1))
                .andExpect(jsonPath("$.excluded[0].id").value(e3))
                .andReturn().getResponse().getContentAsString();
        // score-less grid entries — the score field is absent, not null
        assertTrue(com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body)
                .get("examples").get(0).get("score") == null, "no score on member tiles: " + body);
    }

    @Test
    void members_of_a_fresh_concept_are_empty_sets() throws Exception {
        long id = createConcept("emptymembers");
        mvc.perform(get("/api/concepts/" + id + "/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examples.length()").value(0))
                .andExpect(jsonPath("$.excluded.length()").value(0));
    }

    @Test
    void members_skip_entries_that_vanish_while_listed() throws Exception {
        long id = createConcept("vanish");
        addExamples(id, e1, e2, e3);
        // e1 leaves the grid (status demoted): the membership row survives, but byIds
        // only stitches status-ok rows — the tile drops out, the response holds
        jdbc.sql("update media_entry set status = 'missing' where id = :id").param("id", e1).update();
        // e2 is deleted outright: the FK cascade removes its membership row (spec §5)
        jdbc.sql("delete from media_entry where id = :id").param("id", e2).update();

        mvc.perform(get("/api/concepts/" + id + "/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examples.length()").value(1))
                .andExpect(jsonPath("$.examples[0].id").value(e3));
    }

    @Test
    void list_sorts_by_name_and_recent_and_rejects_unknown_sort() throws Exception {
        createConcept("alpha");
        createConcept("beta");
        // force distinct updated_at so both orders disagree deterministically
        jdbc.sql("update concept set updated_at = now() + interval '1 hour' where name = 'beta'").update();

        String byName = mvc.perform(get("/api/concepts").param("sort", "name"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var names = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(byName).findValuesAsText("name");
        assertEquals(List.of("alpha", "beta"), names);

        String byRecent = mvc.perform(get("/api/concepts").param("sort", "recent"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        names = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(byRecent).findValuesAsText("name");
        assertEquals(List.of("beta", "alpha"), names);

        // absent → recent (freshest edits first on the concepts page)
        String defaultSort = mvc.perform(get("/api/concepts"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        names = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(defaultSort).findValuesAsText("name");
        assertEquals(List.of("beta", "alpha"), names);

        mvc.perform(get("/api/concepts").param("sort", "bogus")).andExpect(status().isBadRequest());
    }

    // --- membership: examples -------------------------------------------------------

    @Test
    void examples_add_counts_only_newly_added_and_rejects_unknown_entries() throws Exception {
        long id = createConcept("excon");
        mvc.perform(post("/api/concepts/" + id + "/examples").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + e1 + "," + e2 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(2));
        mvc.perform(get("/api/concepts/" + id)).andExpect(jsonPath("$.exampleCount").value(2));

        // re-adding an existing example is not "added"
        mvc.perform(post("/api/concepts/" + id + "/examples").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + e2 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(0));
        // mixed batch counts only the newcomer
        mvc.perform(post("/api/concepts/" + id + "/examples").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + e2 + "," + e3 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));
        // duplicate ids within one request count once
        mvc.perform(post("/api/concepts/" + id + "/examples").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + e4 + "," + e4 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));

        mvc.perform(post("/api/concepts/" + id + "/examples").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + e1 + ",999999]}"))
                .andExpect(status().isBadRequest());

        // removal is status-blind and idempotent
        mvc.perform(delete("/api/concepts/" + id + "/examples/" + e3)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/concepts/" + id + "/examples/" + e3)).andExpect(status().isNoContent());
        mvc.perform(get("/api/concepts/" + id)).andExpect(jsonPath("$.exampleCount").value(3));
    }

    // --- membership: exclusions -----------------------------------------------------

    @Test
    void exclusions_upsert_demote_example_and_unexclude() throws Exception {
        long id = createConcept("rejex");
        addExamples(id, e1, e2);

        // rejecting an example demotes it (upsert EXAMPLE → EXCLUDED)
        mvc.perform(post("/api/concepts/" + id + "/exclusions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + e1 + "," + e3 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excluded").value(2));
        mvc.perform(get("/api/concepts/" + id))
                .andExpect(jsonPath("$.exampleCount").value(1))
                .andExpect(jsonPath("$.excludedCount").value(2));

        // re-excluding an already-excluded entry is not "excluded"
        mvc.perform(post("/api/concepts/" + id + "/exclusions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + e3 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excluded").value(0));

        mvc.perform(post("/api/concepts/" + id + "/exclusions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[999999]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/concepts/" + id + "/exclusions/" + e3)).andExpect(status().isNoContent());
        mvc.perform(get("/api/concepts/" + id)).andExpect(jsonPath("$.excludedCount").value(1));
    }

    // --- suggestions ----------------------------------------------------------------

    @Test
    void suggestions_rank_exclude_and_clamp_limit() throws Exception {
        long id = createConcept("herbert");
        seedEmbed(e1, 1, 0);      // the example → anchor (1,0)
        seedEmbed(e4, 0.95f, 0.05f);
        seedEmbed(e2, 0.9f, 0.1f);
        seedEmbed(e3, 0, 1);
        addExamples(id, e1);

        String body = mvc.perform(get("/api/concepts/" + id + "/suggestions"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var tree = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        var ids = new java.util.ArrayList<Long>();
        tree.get("items").forEach(it -> {
            ids.add(it.get("entry").get("id").asLong());
            assertTrue(it.get("score").isNumber(), "score-carrying item: " + it);
            assertTrue(it.get("entry").has("thumbUrl"), "grid-shaped entry: " + it);
        });
        assertEquals(List.of(e4, e2, e3), ids, "ranked by anchor cosine; vector-less e5 absent: " + body);

        // limit clamped 1..200 (mirrors related's cap, no 400)
        mvc.perform(get("/api/concepts/" + id + "/suggestions").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/concepts/" + id + "/suggestions").param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/concepts/" + id + "/suggestions").param("limit", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));

        // an exclusion leaves the candidate set
        mvc.perform(post("/api/concepts/" + id + "/exclusions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[" + e2 + "]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/concepts/" + id + "/suggestions").param("limit", "999"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].entry.id").value(e4))
                .andExpect(jsonPath("$.items[1].entry.id").value(e3));

        // an entry already carrying the concept's tag is done — excluded (any provenance)
        long herbertTag = tagStore.create("herbert", io.github.nullotope.spicygallery.domain.TagCategory.GENERAL).id();
        tagStore.tagEntry(e3, herbertTag);
        mvc.perform(get("/api/concepts/" + id + "/suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].entry.id").value(e4));
    }

    // --- apply ----------------------------------------------------------------------

    @Test
    void apply_happy_path_provenance_and_idempotent_reapply() throws Exception {
        long id = createConcept("solo");
        addExamples(id, e1, e2);
        mvc.perform(get("/api/concepts/" + id))
                .andExpect(jsonPath("$.applyable").value(2)); // the "apply to N" preview

        mvc.perform(post("/api/concepts/" + id + "/apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag").value("solo"))
                .andExpect(jsonPath("$.examples").value(2))
                .andExpect(jsonPath("$.tagged").value(2))
                .andExpect(jsonPath("$.alreadyTagged").value(0));
        // user provenance on the created rows
        mvc.perform(get("/api/entries/" + e1))
                .andExpect(jsonPath("$.tags[0].name").value("solo"))
                .andExpect(jsonPath("$.tags[0].taggedBy").value("user"));
        mvc.perform(get("/api/concepts/" + id))
                .andExpect(jsonPath("$.taggedCount").value(2))
                .andExpect(jsonPath("$.applyable").value(0));

        // idempotent: re-apply reports everything as already-tagged, inserts nothing
        mvc.perform(post("/api/concepts/" + id + "/apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tagged").value(0))
                .andExpect(jsonPath("$.alreadyTagged").value(2))
                .andExpect(jsonPath("$.examples").value(2));
    }

    @Test
    void apply_closes_implications_with_user_provenance() throws Exception {
        long heroTag = tagStore.create("duo", io.github.nullotope.spicygallery.domain.TagCategory.GENERAL).id();
        long wingTag = tagStore.create("wing", io.github.nullotope.spicygallery.domain.TagCategory.GENERAL).id();
        tagStore.addImplication(heroTag, wingTag);

        long id = createConcept("duo");
        addExamples(id, e1, e2);
        // each example gets duo + wing rows, both tagged_by='user' (slice-2 manual semantics)
        mvc.perform(post("/api/concepts/" + id + "/apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag").value("duo"))
                .andExpect(jsonPath("$.examples").value(2))
                .andExpect(jsonPath("$.tagged").value(4))
                .andExpect(jsonPath("$.alreadyTagged").value(0));
        mvc.perform(get("/api/entries/" + e1))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[?(@.name=='wing')].taggedBy").value("user"));
    }

    @Test
    void apply_with_zero_examples_is_400() throws Exception {
        long id = createConcept("emptycon");
        mvc.perform(post("/api/concepts/" + id + "/apply")).andExpect(status().isBadRequest());
    }

    // --- PATCH ----------------------------------------------------------------------

    @Test
    void patch_phrase_reresolves_moves_anchor_and_503s_when_provider_down() throws Exception {
        fake.scripted.put("phrase-patch-alpha", vec(1, 0));
        fake.scripted.put("phrase-patch-beta", vec(0, 1));
        long id = createConcept("phrases", "phrase-patch-alpha");
        assertVecEquals(vec(1, 0), row(id).anchor());

        mvc.perform(patch("/api/concepts/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phrase\":\"phrase-patch-beta\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phrase").value("phrase-patch-beta"));
        assertTrue(fake.texts.contains("phrase-patch-beta"), "re-resolved via provider: " + fake.texts);
        assertVecEquals(vec(0, 1), row(id).anchor()); // the anchor moved
        assertEquals(MODEL, row(id).anchorModel());

        // provider down → 503, and nothing is written
        int callsBefore = fake.texts.size();
        fake.fail = true;
        mvc.perform(patch("/api/concepts/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phrase\":\"phrase-patch-gamma\"}"))
                .andExpect(status().isServiceUnavailable());
        assertEquals("phrase-patch-beta", row(id).phrase(), "failed PATCH writes nothing");
        assertVecEquals(vec(0, 1), row(id).anchor());
        assertEquals(callsBefore + 1, fake.texts.size(), "the failing attempt did reach the provider");
    }

    @Test
    void patch_name_renames_normalizes_and_409s_on_duplicate() throws Exception {
        fake.scripted.put("phrase-rename", vec(1, 0));
        long id = createConcept("oldname", "phrase-rename");
        createConcept("taken");

        mvc.perform(patch("/api/concepts/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Fancy Name!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("fancy_name"));
        // rename does not touch phrase or anchor
        assertEquals("phrase-rename", row(id).phrase());
        assertVecEquals(vec(1, 0), row(id).anchor());

        mvc.perform(patch("/api/concepts/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"taken\"}"))
                .andExpect(status().isConflict());

        // renaming to the own (normalized) name is a no-op, not a 409
        mvc.perform(patch("/api/concepts/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Fancy Name!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("fancy_name"));

        mvc.perform(patch("/api/concepts/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    // --- refresh & staleness ----------------------------------------------------------

    @Test
    void refresh_recomputes_from_stored_inputs_only_and_never_calls_the_provider() throws Exception {
        fake.scripted.put("phrase-refresh", vec(1, 0));
        long id = createConcept("refreshy", "phrase-refresh");
        seedEmbed(e1, 0.9f, 0.1f);
        addExamples(id, e1);
        float[] before = row(id).anchor(); // mean of (phrase, example frame mean)
        assertVecEquals(vec(0.95f, 0.05f), before);

        int callsBefore = fake.texts.size();
        mvc.perform(post("/api/concepts/" + id + "/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.anchorStale").value(false));
        assertVecEquals(before, row(id).anchor()); // no-op recompute is stable
        assertEquals(MODEL, row(id).anchorModel());
        assertEquals(callsBefore, fake.texts.size(), "refresh never calls the provider");
    }

    @Test
    void refresh_with_no_current_model_inputs_is_422() throws Exception {
        long id = createConcept("ghost");
        // foreign anchorModel via direct store update (Review Focus #4)
        concepts.updateAnchor(id, vec(1, 0), "foreign-model");
        mvc.perform(post("/api/concepts/" + id + "/refresh"))
                .andExpect(status().isUnprocessableEntity());
        // the failed refresh writes nothing — the concept stays stale
        assertEquals("foreign-model", row(id).anchorModel());
        assertVecEquals(vec(1, 0), row(id).anchor());
    }

    @Test
    void refresh_recovers_a_stale_concept_whose_examples_have_current_model_vectors() throws Exception {
        long id = createConcept("recovered");
        seedEmbed(e1, 1, 0);
        addExamples(id, e1);
        concepts.updateAnchor(id, vec(0, 1), "foreign-model"); // simulate a model change
        mvc.perform(post("/api/concepts/" + id + "/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anchorStale").value(false));
        assertEquals(MODEL, row(id).anchorModel());
        assertVecEquals(vec(1, 0), row(id).anchor()); // recomputed from the stored example vector
    }

    @Test
    void stale_seed_flags_dto_and_list_and_409s_suggestions() throws Exception {
        long id = createConcept("staley");
        seedEmbed(e1, 1, 0);
        addExamples(id, e1);
        concepts.updateAnchor(id, vec(1, 0), "foreign-model");

        mvc.perform(get("/api/concepts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anchorStale").value(true));
        mvc.perform(get("/api/concepts"))
                .andExpect(jsonPath("$[0].anchorStale").value(true));
        mvc.perform(get("/api/concepts/" + id + "/suggestions"))
                .andExpect(status().isConflict());
        // a fresh concept is never stale
        long fresh = createConcept("freshy");
        mvc.perform(get("/api/concepts/" + fresh))
                .andExpect(jsonPath("$.anchorStale").value(false));
    }

    // --- delete -----------------------------------------------------------------------

    @Test
    void delete_concept_is_204_and_gone() throws Exception {
        long id = createConcept("goner");
        addExamples(id, e1);
        mvc.perform(delete("/api/concepts/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/concepts/" + id)).andExpect(status().isNotFound());
        assertTrue(concepts.byId(id).isEmpty(), "concept row deleted");
        assertTrue(jdbc.sql("select count(*) from concept_entry where concept_id = :id")
                .param("id", id).query(Long.class).single() == 0, "membership cascaded");
    }
}
