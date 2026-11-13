package io.github.nullotope.spicygallery.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.importer.ContentHasher;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.store.JdbcMediaStoreSink;
import io.github.nullotope.spicygallery.server.tagging.GpuJobGuard;
import io.github.nullotope.spicygallery.server.tagging.TaggingClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tagging API over real HTTP handlers (ApiTest pattern): a stubbed Hydra upstream — a
 * local JDK HTTP server speaking the real {@code /info} + {@code /classify} contract —
 * behind the production {@link TaggingClient} bean (imported from the tagging package).
 * Covers the brief's cases: predict writes nothing, apply writes model provenance, bulk
 * start/last progress, the shared-guard conflict (409 naming the running job), 422 on an
 * unreadable source (Review Focus #2), and the /info facts. The disabled-provider case
 * (tagging.enabled=false) needs its own Spring context and lives in
 * {@link TaggingDisabledApiTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaggingApiTest extends PostgresBase {

    static final Path FIX = Path.of("../spicygallery-core/src/test/resources/fixtures");
    static final Path LIB;
    static final Path APPDATA;
    static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    /** The stub upstream's /info payload: two mappable general labels. */
    static final String INFO_JSON =
            "{\"model\":\"hydra-3.5\",\"labels\":{\"smile\":{\"category\":\"general\"},"
                    + "\"red_hair\":{\"category\":\"general\"}}}";
    /** The stub upstream's /classify payload: fixed, deterministic scores. */
    static final String CLASSIFY_JSON = "{\"smile\":0.9,\"red_hair\":0.8}";

    static final int HYDRA_PORT;

    static {
        try {
            LIB = Files.createTempDirectory("sg-tag-lib");
            APPDATA = Files.createTempDirectory("sg-tag-app");
            Files.copy(FIX.resolve("plain.png"), LIB.resolve("plain.png"));
            // readable files behind the seeded bulk scope (bulk must not hit local errors)
            Files.createDirectories(LIB.resolve("seed"));
            for (int i = 1; i <= 5; i++) {
                Files.copy(FIX.resolve("plain.png"), LIB.resolve("seed/f" + i + ".png"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            HttpServer hydra = HttpServer.create(new InetSocketAddress(0), 0);
            hydra.createContext("/info", exchange -> respond(exchange, INFO_JSON));
            hydra.createContext("/classify", exchange -> respond(exchange, CLASSIFY_JSON));
            hydra.start();
            HYDRA_PORT = hydra.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json)
            throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @DynamicPropertySource
    static void galleryProps(DynamicPropertyRegistry r) {
        r.add("spicygallery.libraries[0].name", () -> "api");
        r.add("spicygallery.libraries[0].root", () -> LIB.toString());
        r.add("spicygallery.app-data", () -> APPDATA.toString());
        // the production client bean, pointed at the in-process stub upstream
        r.add("spicygallery.tagging.base-url", () -> "http://localhost:" + HYDRA_PORT);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcMediaStoreSink sink;
    @Autowired GpuJobGuard guard;

    private long plainPngId;
    private long unreadableId;
    private final List<Long> seedIds = new ArrayList<>();

    @BeforeEach
    void cleanAndSeed() throws Exception {
        jdbc.sql("delete from media_tag").update();
        jdbc.sql("delete from tag_implication").update();
        jdbc.sql("delete from tag_alias").update();
        jdbc.sql("delete from tag").update();
        jdbc.sql("delete from media_entry").update();
        jdbc.sql("delete from media_embedding").update();

        // plain.png + seed rows: real readable files; "unreadable.png" has NO file on disk
        byte[] realPng = Files.readAllBytes(LIB.resolve("plain.png"));
        String realHash = ContentHasher.sha256Hex(LIB.resolve("plain.png"));
        plainPngId = sink.insert(new NewEntry(Kind.IMAGE, "api", "plain.png", "plain.png", "png",
                "image/png", realPng.length, realHash, 8, 4, null, T)).value();
        for (int i = 1; i <= 5; i++) {
            seedIds.add(sink.insert(entry("seed/f" + i + ".png")).value());
        }
        unreadableId = sink.insert(entry("unreadable.png")).value();
    }

    private static NewEntry entry(String rel) {
        return new NewEntry(Kind.IMAGE, "api", rel, fileName(rel), "png", "image/png",
                77, "hash-" + rel, 8, 4, null, T);
    }

    private static String fileName(String rel) {
        return rel.substring(rel.lastIndexOf('/') + 1);
    }

    private long mediaTagCount() {
        return jdbc.sql("select count(*) from media_tag").query(Long.class).single();
    }

    private List<String> provenances(long entryId) {
        return jdbc.sql("select distinct tagged_by from media_tag where media_entry_id = :id")
                .param("id", entryId).query(String.class).list();
    }

    private String lastRun() throws Exception {
        return mvc.perform(get("/api/tagging/last")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static JsonNode json(String body) throws IOException {
        return JsonMapper.builder().build().readTree(body);
    }

    @Test
    void predict_returns_tags_and_writes_nothing() throws Exception {
        mvc.perform(post("/api/entries/" + plainPngId + "/autotag/predict"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("hydra-3.5"))
                .andExpect(jsonPath("$.calibration").value("f1.0@0.1"))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0].name").value("smile"))
                .andExpect(jsonPath("$.tags[0].category").value("general"))
                .andExpect(jsonPath("$.tags[0].probability").value(0.9))
                .andExpect(jsonPath("$.tags[1].name").value("red_hair"))
                .andExpect(jsonPath("$.tags[1].probability").value(0.8));

        // an explicit calibration override is echoed back, and still nothing is written
        mvc.perform(post("/api/entries/" + plainPngId + "/autotag/predict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calibration\":\"f0.5@0.2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calibration").value("f0.5@0.2"));
        assertEquals(0L, mediaTagCount(), "predict must not write tags");
        assertEquals(0L, jdbc.sql("select count(*) from tag").query(Long.class).single(),
                "predict must not create tags");
    }

    @Test
    void apply_writes_checked_tags_with_provenance() throws Exception {
        mvc.perform(post("/api/entries/" + plainPngId + "/autotag/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"smile\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("smile"))
                .andExpect(jsonPath("$[0].category").value("general"))
                .andExpect(jsonPath("$[0].postCount").value(1))
                // TagDto flows the row's provenance through to the chip tooltip
                .andExpect(jsonPath("$[0].taggedBy").value("hydra-3.5"));

        assertEquals(List.of("hydra-3.5"), provenances(plainPngId),
                "AI-written rows carry the model as provenance");
    }

    @Test
    void bulk_starts_and_last_reports_progress() throws Exception {
        mvc.perform(post("/api/tagging/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"untagged\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true));

        // SSE endpoint subscribes (async request started; the stream stays open)
        mvc.perform(get("/api/tagging/events")).andExpect(request().asyncStarted());

        // wait for the run to finish, then reconcile via REST (the unreadable.png row
        // counts as an error; per-entry failures never abort the run)
        String body;
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode status;
        do {
            Thread.sleep(100);
            assertTrue(System.currentTimeMillis() < deadline, "bulk run did not finish");
            status = json(lastRun());
        } while (status.get("running").asBoolean());

        assertEquals(6, status.get("done").asLong(), lastRun());
        assertEquals(7, status.get("total").asLong(), lastRun());
        assertEquals(1, status.get("errors").asLong(), lastRun());
        assertTrue(status.get("error").isNull(), lastRun());

        // every untagged entry now carries both predictions with model provenance
        List<Long> all = new ArrayList<>(seedIds);
        all.add(0, plainPngId);
        for (long id : all) {
            mvc.perform(get("/api/entries/" + id))
                    .andExpect(jsonPath("$.tags.length()").value(2))
                    .andExpect(jsonPath("$.tags[0].name").value("red_hair")) // name order
                    .andExpect(jsonPath("$.tags[0].taggedBy").value("hydra-3.5")) // detail flows provenance
                    .andExpect(jsonPath("$.tags[1].name").value("smile"));
        }
        assertEquals(List.of("hydra-3.5"), provenances(plainPngId));
    }

    @Test
    void bulk_conflicts_with_running_embedding_backfill() throws Exception {
        guard.begin("embedding-backfill");
        try {
            mvc.perform(post("/api/tagging/bulk").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"untagged\":true}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail",
                            org.hamcrest.Matchers.containsString("embedding-backfill")));
        } finally {
            guard.end("embedding-backfill");
        }

        // and the mirror direction: a tagging run blocks the embedding backfill start
        guard.begin("tagging-bulk");
        try {
            mvc.perform(post("/api/embeddings/backfill"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail",
                            org.hamcrest.Matchers.containsString("tagging-bulk")));
        } finally {
            guard.end("tagging-bulk");
        }
    }

    @Test
    void predict_422_on_unreadable_source() throws Exception {
        mvc.perform(post("/api/entries/" + unreadableId + "/autotag/predict"))
                .andExpect(status().isUnprocessableEntity());
        // a local failure never trips the breaker: the next provider call still happens
        mvc.perform(post("/api/entries/" + plainPngId + "/autotag/predict"))
                .andExpect(status().isOk());
    }

    @Test
    void info_reports_enabled_reachable_and_model_facts() throws Exception {
        mvc.perform(get("/api/tagging/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.model").value("hydra-3.5"))
                .andExpect(jsonPath("$.labels").value(2))
                .andExpect(jsonPath("$.calibration").value("f1.0@0.1"))
                .andExpect(jsonPath("$.implications").value("inherit"))
                // bulk runs serial in slice 4 — the concurrency knob is reserved, never echoed
                .andExpect(jsonPath("$.concurrency").doesNotExist());
    }
}
