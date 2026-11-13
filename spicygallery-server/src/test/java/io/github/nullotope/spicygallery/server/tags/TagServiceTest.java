package io.github.nullotope.spicygallery.server.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.server.web.GlobalExceptionHandler.MissingEntryException;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.store.JdbcMediaStoreSink;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore.TagRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class TagServiceTest extends PostgresBase {

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

    private TagService.TagInput input(String name) {
        return new TagService.TagInput(name, null);
    }

    @Test
    void put_tags_creates_resolves_and_expands_implications() {
        long heron = tags.create("heron", TagCategory.ARTIST).id();
        tags.putAlias("oldname", heron);
        tags.create("duck", TagCategory.GENERAL);
        tags.create("screenshot", TagCategory.GENERAL);
        long duckId = tags.byName("duck").orElseThrow().id();
        tags.addImplication(tags.byName("screenshot").orElseThrow().id(), duckId);

        List<TagRow> result = tagsService.setEntryTags(entry1, List.of(
                input("oldname"), input("duck"), input("screenshot")));

        assertEquals(List.of("duck", "heron", "screenshot"),
                result.stream().map(TagRow::name).sorted().toList());
        // the alias was resolved, not stored
        assertTrue(result.stream().noneMatch(r -> r.name().equals("oldname")));
        // post_count incremented exactly once per applied tag
        assertEquals(1, tags.byName("heron").orElseThrow().postCount());
        assertEquals(1, tags.byName("duck").orElseThrow().postCount()); // via implication
        assertEquals(1, tags.byName("screenshot").orElseThrow().postCount());
    }

    @Test
    void removing_an_implied_tag_re_adds_it() {
        tags.create("screenshot", TagCategory.GENERAL);
        tags.create("duck", TagCategory.GENERAL);
        tags.addImplication(tags.byName("screenshot").orElseThrow().id(),
                tags.byName("duck").orElseThrow().id());

        tagsService.setEntryTags(entry1, List.of(input("screenshot"), input("duck")));
        List<TagRow> second = tagsService.setEntryTags(entry1, List.of(input("screenshot")));

        assertTrue(second.stream().anyMatch(r -> r.name().equals("duck")), "implied tag stays");
        assertEquals(1, tags.byName("duck").orElseThrow().postCount());
    }

    @Test
    void post_count_stays_exact_after_replace() {
        tagsService.setEntryTags(entry1, List.of(input("alpha"), input("beta")));
        tagsService.setEntryTags(entry2, List.of(input("alpha")));
        tagsService.setEntryTags(entry1, List.of(input("gamma"), input("delta")));

        assertEquals(1, tags.byName("alpha").orElseThrow().postCount()); // entry2 only now
        assertEquals(0, tags.byName("beta").orElseThrow().postCount());
        assertEquals(1, tags.byName("gamma").orElseThrow().postCount());
        assertEquals(1, tags.byName("delta").orElseThrow().postCount());
        Integer rows = jdbc.sql("select count(*) from media_tag").query(Integer.class).single();
        Integer sum = jdbc.sql("select coalesce(sum(post_count), 0) from tag").query(Integer.class).single();
        assertEquals(rows, sum);
    }

    @Test
    void invalid_name_is_400_shaped() {
        assertThrows(IllegalArgumentException.class,
                () -> tagsService.setEntryTags(entry1, List.of(input("ab/cd"))));
    }

    @Test
    void missing_entry_throws() {
        assertThrows(MissingEntryException.class,
                () -> tagsService.setEntryTags(999_999, List.of(input("duck"))));
    }

    @Test
    void alias_merge_moves_rows_and_deletes_antecedent() {
        tags.create("old", TagCategory.GENERAL);
        tags.create("new", TagCategory.GENERAL);
        tagsService.setEntryTags(entry1, List.of(input("old")));
        tagsService.setEntryTags(entry2, List.of(input("old")));

        tagsService.createAlias("old", "new");

        assertEquals(List.of("new"), tags.tagsOf(entry1).stream().map(TagRow::name).toList());
        assertEquals(List.of("new"), tags.tagsOf(entry2).stream().map(TagRow::name).toList());
        assertTrue(tags.byName("old").isEmpty());
        assertEquals(2, tags.byName("new").orElseThrow().postCount());
        assertEquals("new", tags.listAliases().get(0).target());
    }

    @Test
    void alias_merge_with_overlapping_tags_keeps_post_count_exact() {
        // regression (final review): an entry already carrying BOTH tags must not be
        // counted as "moved" — post_count drifted by the overlap before the fix
        tags.create("old", TagCategory.GENERAL);
        tags.create("new", TagCategory.GENERAL);
        tagsService.setEntryTags(entry1, List.of(input("old"), input("new"))); // overlap
        tagsService.setEntryTags(entry2, List.of(input("old")));

        tagsService.createAlias("old", "new");

        assertEquals(2, tags.byName("new").orElseThrow().postCount()); // E1 + E2, not 3
        Integer rows = jdbc.sql("select count(*) from media_tag").query(Integer.class).single();
        Integer sum = jdbc.sql("select coalesce(sum(post_count), 0) from tag").query(Integer.class).single();
        assertEquals(rows, sum);
    }

    @Test
    void create_implication_rejects_self_dup_and_cycle() {
        tags.create("t1", TagCategory.GENERAL);
        tags.create("t2", TagCategory.GENERAL);
        tagsService.createImplication("t1", "t2");

        assertThrows(IllegalArgumentException.class, () -> tagsService.createImplication("t1", "t1"));
        assertThrows(IllegalArgumentException.class, () -> tagsService.createImplication("t1", "t2"));
        var cycle = assertThrows(IllegalArgumentException.class,
                () -> tagsService.createImplication("t2", "t1"));
        assertTrue(cycle.getMessage().contains("cycle"));
        var unknown = assertThrows(IllegalArgumentException.class,
                () -> tagsService.createImplication("t1", "ghost"));
        assertTrue(unknown.getMessage().contains("unknown tag"));
    }

    @Test
    void remove_implication_keeps_existing_tags() {
        tags.create("t1", TagCategory.GENERAL);
        tags.create("t2", TagCategory.GENERAL);
        tagsService.createImplication("t1", "t2");
        tagsService.setEntryTags(entry1, List.of(input("t1")));
        assertTrue(tags.tagIdsOf(entry1).size() == 2);

        tagsService.removeImplication("t1", "t2");
        assertEquals(2, tags.tagIdsOf(entry1).size()); // no retro-strip
        assertFalse(tags.listImplications().contains(new JdbcTagStore.ImplicationRow("t1", "t2")));
    }
}
