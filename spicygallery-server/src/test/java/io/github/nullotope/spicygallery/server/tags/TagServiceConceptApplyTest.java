package io.github.nullotope.spicygallery.server.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Add-only user-provenance apply (slice 5): {@code addTagsToEntries} inserts only the rows
 * an entry is missing — existing rows keep their provenance untouched (no re-affirmation
 * path, unlike {@code setEntryTags}), post counts bump once per genuinely added row, and
 * the {@link TagService.AddResult} reports requested/tagged/alreadyTagged.
 */
@SpringBootTest
class TagServiceConceptApplyTest extends PostgresBase {

    @Autowired TagService tagsService;
    @Autowired JdbcTagStore tags;
    @Autowired JdbcMediaStoreSink sink;
    @Autowired JdbcClient jdbc;

    private long entry1;
    private long entry2;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from media_tag").update();
        jdbc.sql("delete from tag_implication").update();
        jdbc.sql("delete from tag_alias").update();
        jdbc.sql("delete from tag").update();
        jdbc.sql("delete from media_entry").update();
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        entry1 = sink.insert(new NewEntry(Kind.IMAGE, "api", "a.png", "a.png", "png", "image/png",
                1, "hash-a", 8, 4, null, t)).value();
        entry2 = sink.insert(new NewEntry(Kind.IMAGE, "api", "b.png", "b.png", "png", "image/png",
                1, "hash-b", 8, 4, null, t)).value();
    }

    private static Set<String> names(List<TagRow> rows) {
        return rows.stream().map(TagRow::name).collect(Collectors.toSet());
    }

    private long tagId(String name) {
        return tags.byName(name).orElseThrow().id();
    }

    @Test
    void apply_never_rewrites_existing_rows() {
        // review focus #3: the concept name coincides with a tag whose row carries AI provenance
        tagsService.setEntryTags(entry1, List.of(
                new TagService.TagInput("sunset", TagCategory.GENERAL, "hydra-3.5"),
                new TagService.TagInput("landscape", TagCategory.GENERAL)));
        assertEquals("hydra-3.5", tags.taggedByOf(entry1).get(tagId("sunset"))); // seed precondition

        var result = tagsService.addTagsToEntries(List.of(entry1, entry2),
                new TagService.TagInput("sunset", TagCategory.GENERAL, "user"));

        assertEquals(new TagService.AddResult(2, 1, 1), result);
        var by = tags.taggedByOf(entry1);
        assertEquals("hydra-3.5", by.get(tagId("sunset"))); // row untouched — NOT re-stamped
        assertEquals("user", by.get(tagId("landscape"))); // no other tag moved
        assertEquals("user", tags.taggedByOf(entry2).get(tagId("sunset"))); // only the missing row inserted
        assertEquals(Set.of("sunset"), names(tagsService.tagsOf(entry2)));
    }

    @Test
    void implied_tags_are_added_with_user_provenance() {
        tags.create("screenshot", TagCategory.GENERAL);
        tags.create("duck", TagCategory.GENERAL);
        tagsService.createImplication("screenshot", "duck");

        var result = tagsService.addTagsToEntries(List.of(entry1),
                new TagService.TagInput("screenshot", TagCategory.GENERAL, "user"));

        assertEquals(new TagService.AddResult(1, 2, 0), result);
        var by = tags.taggedByOf(entry1);
        assertEquals("user", by.get(tagId("screenshot")));
        assertEquals("user", by.get(tagId("duck"))); // implied row carries the operation's provenance
        assertEquals(Set.of("screenshot", "duck"), names(tagsService.tagsOf(entry1)));
    }

    @Test
    void second_apply_is_idempotent_and_reports_already_tagged() {
        var first = tagsService.addTagsToEntries(List.of(entry1),
                new TagService.TagInput("sunset", TagCategory.GENERAL, "user"));
        assertEquals(new TagService.AddResult(1, 1, 0), first);
        Map<Long, String> rowsAfterFirst = tags.taggedByOf(entry1);

        var second = tagsService.addTagsToEntries(List.of(entry1),
                new TagService.TagInput("sunset", TagCategory.GENERAL, "user"));

        assertEquals(new TagService.AddResult(1, 0, 1), second);
        assertEquals(rowsAfterFirst, tags.taggedByOf(entry1)); // rows unchanged
    }

    @Test
    void post_counts_bump_once_per_genuinely_added_row() {
        tags.create("screenshot", TagCategory.GENERAL);
        tags.create("duck", TagCategory.GENERAL);
        tagsService.createImplication("screenshot", "duck");

        tagsService.addTagsToEntries(List.of(entry1, entry2),
                new TagService.TagInput("screenshot", TagCategory.GENERAL, "user"));

        assertEquals(2, tags.byName("screenshot").orElseThrow().postCount());
        assertEquals(2, tags.byName("duck").orElseThrow().postCount()); // implied fires per entry

        tagsService.addTagsToEntries(List.of(entry1, entry2),
                new TagService.TagInput("screenshot", TagCategory.GENERAL, "user"));

        assertEquals(2, tags.byName("screenshot").orElseThrow().postCount()); // re-apply never bumps
        assertEquals(2, tags.byName("duck").orElseThrow().postCount());
    }

    @Test
    void missing_entries_are_skipped_silently() {
        long missing = 424_242L;

        // null taggedBy → the operation's provenance is 'user'; requested includes the skipped id
        var result = tagsService.addTagsToEntries(List.of(entry1, missing),
                new TagService.TagInput("sunset", TagCategory.GENERAL, null));

        assertEquals(new TagService.AddResult(2, 1, 0), result);
        assertEquals("user", tags.taggedByOf(entry1).get(tagId("sunset")));
        assertEquals(TagCategory.GENERAL.wireName(), tags.byName("sunset").orElseThrow().category());
    }

    @Test
    void alias_name_lands_on_its_target_tag() {
        long heron = tags.create("heron", TagCategory.ARTIST).id();
        tags.putAlias("oldname", heron);

        var result = tagsService.addTagsToEntries(List.of(entry1),
                new TagService.TagInput("Oldname!", TagCategory.GENERAL, "user"));

        assertEquals(new TagService.AddResult(1, 1, 0), result);
        assertEquals("user", tags.taggedByOf(entry1).get(heron)); // the alias target, not a new tag
        assertEquals(Set.of("heron"), names(tagsService.tagsOf(entry1)));
        assertTrue(tags.byName("oldname").isEmpty()); // no stray tag created
    }
}
