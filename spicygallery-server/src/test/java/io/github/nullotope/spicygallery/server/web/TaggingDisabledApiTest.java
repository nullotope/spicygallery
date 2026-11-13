package io.github.nullotope.spicygallery.server.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.importer.ContentHasher;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.store.JdbcMediaStoreSink;
import java.nio.file.Files;
import java.time.Instant;
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
 * The tagging endpoints when {@code spicygallery.tagging.enabled=false} (spec §7):
 * predict/apply/bulk answer a clean 503 with a retry hint — never a 500 — while the
 * observability endpoint stays 200 and reports the disabled fact. A separate class
 * because {@code tagging.enabled} is bound once at context startup (a @Nested class's
 * own @DynamicPropertySource does not produce a second context in Spring 6.1).
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaggingDisabledApiTest extends PostgresBase {

    @DynamicPropertySource
    static void galleryProps(DynamicPropertyRegistry r) {
        r.add("spicygallery.libraries[0].name", () -> "api");
        r.add("spicygallery.libraries[0].root", () -> TaggingApiTest.LIB.toString());
        r.add("spicygallery.app-data", () -> TaggingApiTest.APPDATA.toString());
        r.add("spicygallery.tagging.base-url",
                () -> "http://localhost:" + TaggingApiTest.HYDRA_PORT);
        r.add("spicygallery.tagging.enabled", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcMediaStoreSink sink;

    private long plainPngId;

    @BeforeEach
    void cleanAndSeed() throws Exception {
        jdbc.sql("delete from media_tag").update();
        jdbc.sql("delete from media_entry").update();
        byte[] realPng = Files.readAllBytes(TaggingApiTest.LIB.resolve("plain.png"));
        String realHash = ContentHasher.sha256Hex(TaggingApiTest.LIB.resolve("plain.png"));
        plainPngId = sink.insert(new NewEntry(Kind.IMAGE, "api", "plain.png", "plain.png", "png",
                "image/png", realPng.length, realHash, 8, 4, null,
                Instant.parse("2026-01-01T00:00:00Z"))).value();
    }

    @Test
    void endpoints_answer_503_when_provider_disabled() throws Exception {
        mvc.perform(post("/api/entries/" + plainPngId + "/autotag/predict"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value("tagging provider disabled"));
        mvc.perform(post("/api/entries/" + plainPngId + "/autotag/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"smile\"]}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/tagging/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"untagged\":true}"))
                .andExpect(status().isServiceUnavailable());
        // the observability endpoint stays 200 and simply reports the facts
        mvc.perform(get("/api/tagging/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }
}
