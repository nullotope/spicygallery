package io.github.nullotope.spicygallery.server.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.store.JdbcMediaStoreSink;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore.TagRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Provenance-aware merge (slice 4): {@code setEntryTagsPreservingUser} keeps user-tagged
 * rows, replaces AI-tagged ones, and folds implication provenance with user-wins.
 */
@SpringBootTest
class TagServiceAutotagTest extends PostgresBase {

    @Autowired TagService tagsService;
    @Autowired JdbcTagStore tags;
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

    private static Set<String> names(List<TagRow> rows) {
        return rows.stream().map(TagRow::name).collect(Collectors.toSet());
    }

    private long tagId(String name) {
        return tags.byName(name).orElseThrow().id();
    }

    private Map<String, Integer> postCountsByName() {
        return jdbc.sql("select name, post_count from tag")
                .query((rs, i) -> Map.entry(rs.getString("name"), rs.getInt("post_count")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Test
    void setEntryTagsPreservingUser_keeps_user_tags_and_replaces_ai_tags() {
        tagsService.setEntryTags(entry1, List.of(
                new TagService.TagInput("user_tag", TagCategory.GENERAL),
                new TagService.TagInput("old_ai", TagCategory.GENERAL, "hydra-3.5")));
        assertEquals("hydra-3.5", tags.taggedByOf(entry1).get(tagId("old_ai"))); // seed precondition

        var out = tagsService.setEntryTagsPreservingUser(entry1,
                List.of(new TagService.TagInput("new_ai", TagCategory.GENERAL, "hydra-3.5")), "hydra-3.5");

        assertEquals(Set.of("user_tag", "new_ai"), names(out)); // old_ai dropped, user_tag kept
        assertEquals("user", tags.taggedByOf(entry1).get(tagId("user_tag")));
        assertEquals("hydra-3.5", tags.taggedByOf(entry1).get(tagId("new_ai")));
        // the returned rows carry the same provenance (what the API's TagDto maps over)
        Map<String, String> byName = out.stream()
                .collect(Collectors.toMap(TagRow::name, r -> r.taggedBy() == null ? "null" : r.taggedBy()));
        assertEquals(Map.of("user_tag", "user", "new_ai", "hydra-3.5"), byName);
    }

    @Test
    void implied_tags_inherit_the_provenance_of_the_implying_row() {
        tags.create("implied_by_ai", TagCategory.GENERAL);
        tags.create("implied_by_user", TagCategory.GENERAL);
        tagsService.setEntryTags(entry1, List.of(new TagService.TagInput("user_tag", TagCategory.GENERAL)));
        tagsService.setEntryTagsPreservingUser(entry1,
                List.of(new TagService.TagInput("new_ai", TagCategory.GENERAL, "hydra-3.5")), "hydra-3.5");
        tagsService.createImplication("new_ai", "implied_by_ai");
        tagsService.createImplication("user_tag", "implied_by_user");

        // re-apply: the implications now fire inside the merge
        tagsService.setEntryTagsPreservingUser(entry1,
                List.of(new TagService.TagInput("new_ai", TagCategory.GENERAL, "hydra-3.5")), "hydra-3.5");

        var by = tags.taggedByOf(entry1);
        assertEquals("hydra-3.5", by.get(tagId("implied_by_ai")));
        assertEquals("user", by.get(tagId("implied_by_user")));
    }

    @Test
    void second_apply_is_idempotent_and_reports_no_change() {
        tagsService.setEntryTags(entry1, List.of(new TagService.TagInput("user_tag", TagCategory.GENERAL)));
        var first = tagsService.setEntryTagsPreservingUser(entry1,
                List.of(new TagService.TagInput("new_ai", TagCategory.GENERAL, "hydra-3.5")), "hydra-3.5");
        Map<String, Integer> countsAfterFirst = postCountsByName();

        var second = tagsService.setEntryTagsPreservingUser(entry1,
                List.of(new TagService.TagInput("new_ai", TagCategory.GENERAL, "hydra-3.5")), "hydra-3.5");

        assertEquals(names(first), names(second));
        assertEquals(countsAfterFirst, postCountsByName());
        assertEquals("hydra-3.5", tags.taggedByOf(entry1).get(tagId("new_ai"))); // provenance stable
    }

    @Test
    void null_category_ai_input_is_rejected_not_silently_general() {
        assertThrows(IllegalArgumentException.class, () -> tagsService.setEntryTagsPreservingUser(entry1,
                List.of(new TagService.TagInput("unmapped", null, "hydra-3.5")), "hydra-3.5"));
    }

    @Test
    void implied_row_reached_by_both_user_and_ai_roots_stays_user() {
        // review round 1 (C1): diamond u_root(user) -> shared -> leaf, ai_root -> shared;
        // the user root must win on `shared` no matter which root is processed first
        tags.create("ai_root", TagCategory.GENERAL);
        tags.create("shared", TagCategory.GENERAL);
        tags.create("leaf", TagCategory.GENERAL);
        tagsService.setEntryTags(entry1, List.of(new TagService.TagInput("u_root", TagCategory.GENERAL)));
        tagsService.createImplication("u_root", "shared");
        tagsService.createImplication("ai_root", "shared");
        tagsService.createImplication("shared", "leaf");

        tagsService.setEntryTagsPreservingUser(entry1,
                List.of(new TagService.TagInput("ai_root", TagCategory.GENERAL, "hydra-3.5")), "hydra-3.5");

        var by = tags.taggedByOf(entry1);
        assertEquals("user", by.get(tagId("u_root")));
        assertEquals("hydra-3.5", by.get(tagId("ai_root")));
        assertEquals("user", by.get(tagId("shared"))); // user root beats the AI root
        assertEquals("user", by.get(tagId("leaf"))); // ... and the win propagates transitively
    }

    @Test
    void setEntryTags_reaffirming_an_existing_row_upgrades_its_provenance() {
        // review round 1 (I1): keeping an AI-stamped tag via a plain setEntryTags call is user
        // re-affirmation — the row must become 'user' or the next merge drops the kept tag
        tagsService.setEntryTagsPreservingUser(entry1,
                List.of(new TagService.TagInput("ai_kept", TagCategory.GENERAL, "hydra-3.5")), "hydra-3.5");
        assertEquals("hydra-3.5", tags.taggedByOf(entry1).get(tagId("ai_kept"))); // seed precondition
        int countBefore = tags.byName("ai_kept").orElseThrow().postCount();

        tagsService.setEntryTags(entry1, List.of(new TagService.TagInput("ai_kept", TagCategory.GENERAL)));

        assertEquals("user", tags.taggedByOf(entry1).get(tagId("ai_kept")));
        assertEquals(countBefore, tags.byName("ai_kept").orElseThrow().postCount()); // rewrite never bumps
    }
}
