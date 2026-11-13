package io.github.nullotope.spicygallery.server.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.store.JdbcMediaStoreSink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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

/** Tag management API (spec §7): PUT entry tags, /api/tags, autocomplete, aliases, implications. */
@SpringBootTest
@AutoConfigureMockMvc
class TagApiTest extends PostgresBase {

    @Autowired MockMvc mvc;
    @Autowired JdbcMediaStoreSink sink;
    @Autowired JdbcClient jdbc;

    private long entry1;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from media_tag").update();
        jdbc.sql("delete from tag_implication").update();
        jdbc.sql("delete from tag_alias").update();
        jdbc.sql("delete from tag").update();
        jdbc.sql("delete from media_entry").update();
        entry1 = sink.insert(new NewEntry(Kind.IMAGE, "api", "a.png", "a.png", "png", "image/png",
                1, "hash-a", 8, 4, null, Instant.parse("2026-01-01T00:00:00Z"))).value();
    }

    private String putTags(long entryId, String json) throws Exception {
        return mvc.perform(put("/api/entries/" + entryId + "/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void put_tags_roundtrip_normalizes_and_implies() throws Exception {
        // alias exists before the PUT: typing the alias lands on the canonical tag
        putTags(entry1, "{\"tags\":[{\"name\":\"heron\"}]}");
        mvc.perform(post("/api/tags/aliases").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"antecedent\":\"oldname\",\"consequent\":\"heron\"}"))
                .andExpect(status().isNoContent());

        String body = putTags(entry1, "{\"tags\":[{\"name\":\"Heron Oldname!\"}]}");
        org.junit.jupiter.api.Assertions.assertEquals("heron_oldname",
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(body).get(0).get("name").asText());

        // implications over the wire: screenshot -> duck
        putTags(entry1, "{\"tags\":[{\"name\":\"screenshot\"},{\"name\":\"duck\"}]}");
        mvc.perform(post("/api/tags/implications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"antecedent\":\"screenshot\",\"consequent\":\"duck\"}"))
                .andExpect(status().isNoContent());
        String after = putTags(entry1, "{\"tags\":[{\"name\":\"screenshot\"}]}");
        var tags = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(after);
        List<String> names = new ArrayList<>();
        tags.forEach(t -> names.add(t.get("name").asText()));
        org.junit.jupiter.api.Assertions.assertTrue(names.contains("duck"),
                "implied tag re-added on save: " + after);
    }

    @Test
    void put_tags_404_and_400() throws Exception {
        mvc.perform(put("/api/entries/999999/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[{\"name\":\"duck\"}]}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/entries/" + entry1 + "/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[{\"name\":\"ab/cd\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/entries/" + entry1 + "/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[{\"name\":\"duck\",\"category\":\"mood\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tags_list_keyset_and_filters() throws Exception {
        for (int i = 0; i < 30; i++) {
            putTags(entry1, "{\"tags\":[{\"name\":\"bulk" + String.format("%03d", i) + "\"}]}");
        }
        // walk NAME sort by cursor
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (guard++ < 10) {
            String url = cursor == null ? "/api/tags?sort=name&limit=10" : "/api/tags?sort=name&limit=10&cursor=" + cursor;
            MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
            var page = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(result.getResponse().getContentAsString());
            page.get("items").forEach(t -> seen.add(t.get("name").asText()));
            var nc = page.get("nextCursor");
            if (nc == null || nc.isNull()) {
                break;
            }
            cursor = nc.asText();
        }
        org.junit.jupiter.api.Assertions.assertEquals(30, seen.size());
        org.junit.jupiter.api.Assertions.assertEquals(30, new HashSet<>(seen).size());
        org.junit.jupiter.api.Assertions.assertEquals(seen.stream().sorted().toList(), seen);

        // category filter
        putTags(entry1, "{\"tags\":[{\"name\":\"artist_one\",\"category\":\"artist\"}]}");
        mvc.perform(get("/api/tags").param("category", "artist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("artist_one"));

        // sort=count ordering: counts non-increasing (ties break by id desc)
        MvcResult countPage = mvc.perform(get("/api/tags").param("sort", "count").param("limit", "3"))
                .andExpect(status().isOk())
                .andReturn();
        var countItems = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(countPage.getResponse().getContentAsString()).get("items");
        org.junit.jupiter.api.Assertions.assertEquals(3, countItems.size());
        long previous = Long.MAX_VALUE;
        for (var item : countItems) {
            long count = item.get("postCount").asLong();
            org.junit.jupiter.api.Assertions.assertTrue(count <= previous, "count sort violated");
            previous = count;
        }
    }

    @Test
    void autocomplete_prefixes_first_then_substring_then_alias() throws Exception {
        putTags(entry1, "{\"tags\":[{\"name\":\"duck\"},{\"name\":\"duckling\"},{\"name\":\"dark_duck\"}]}");
        mvc.perform(post("/api/tags/aliases").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"antecedent\":\"ducky\",\"consequent\":\"duck\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/tags/autocomplete").param("q", "du"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].name").value("duck"))
                .andExpect(jsonPath("$[1].name").value("duckling"))
                .andExpect(jsonPath("$[2].name").value("dark_duck"))
                .andExpect(jsonPath("$[3].name").value("ducky"))
                .andExpect(jsonPath("$[3].aliasOf").value("duck"))
                .andExpect(jsonPath("$[0].category").value("general"))
                .andExpect(jsonPath("$[0].postCount").exists());
    }

    @Test
    void patch_category_updates() throws Exception {
        putTags(entry1, "{\"tags\":[{\"name\":\"duck\"}]}");
        mvc.perform(patch("/api/tags/duck").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"character\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("character"));
        mvc.perform(get("/api/tags").param("category", "character"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("duck"));
        mvc.perform(patch("/api/tags/ghost").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"artist\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void alias_endpoints_merge_and_list_and_delete() throws Exception {
        putTags(entry1, "{\"tags\":[{\"name\":\"old\"}]}");
        mvc.perform(post("/api/tags/aliases").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"antecedent\":\"old\",\"consequent\":\"new\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/entries/" + entry1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0].name").value("new"));
        mvc.perform(get("/api/tags/aliases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].antecedent").value("old"))
                .andExpect(jsonPath("$[0].target").value("new"));
        mvc.perform(delete("/api/tags/aliases/old")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/tags/aliases/old")).andExpect(status().isNoContent()); // idempotent
        mvc.perform(get("/api/tags/aliases")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void implication_endpoints_guard() throws Exception {
        putTags(entry1, "{\"tags\":[{\"name\":\"t1\"},{\"name\":\"t2\"}]}");
        mvc.perform(post("/api/tags/implications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"antecedent\":\"t1\",\"consequent\":\"t1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tags/implications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"antecedent\":\"t1\",\"consequent\":\"t2\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/tags/implications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"antecedent\":\"t2\",\"consequent\":\"t1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tags/implications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].antecedent").value("t1"))
                .andExpect(jsonPath("$[0].consequent").value("t2"));
        mvc.perform(delete("/api/tags/implications/t1/t2")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/tags/implications/t1/t2")).andExpect(status().isNoContent());
        mvc.perform(get("/api/tags/implications")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void expandFor_caps_at_200() throws Exception {
        // 201 tags matching bulk* — the wildcard expansion must refuse, not hang
        for (int i = 0; i < 201; i++) {
            putTags(entry1, "{\"tags\":[{\"name\":\"bulk" + String.format("%04d", i) + "\"}]}");
            putTags(entry1, "{\"tags\":[]}"); // keep only the last tag on the entry
        }
        mvc.perform(get("/api/entries").param("q", "bulk*").param("limit", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("be more specific")));
    }
}
