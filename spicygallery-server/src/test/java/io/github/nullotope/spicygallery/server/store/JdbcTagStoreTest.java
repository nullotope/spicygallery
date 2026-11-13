package io.github.nullotope.spicygallery.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore.TagSort;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class JdbcTagStoreTest extends PostgresBase {

    @Autowired JdbcTagStore tags;
    @Autowired JdbcMediaStoreSink sink;
    @Autowired JdbcClient jdbc;

    private long entryId;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from media_tag").update();
        jdbc.sql("delete from tag_implication").update();
        jdbc.sql("delete from tag_alias").update();
        jdbc.sql("delete from tag").update();
        jdbc.sql("delete from media_entry").update();
        entryId = sink.insert(new NewEntry(Kind.IMAGE, "api", "a.png", "a.png", "png", "image/png",
                1, "hash-a", 8, 4, null, Instant.parse("2026-01-01T00:00:00Z"))).value();
    }

    private long tag(String name, TagCategory category) {
        return tags.create(name, category).id();
    }

    @Test
    void create_find_roundtrip() {
        long id = tag("heron", TagCategory.ARTIST);
        JdbcTagStore.TagRow row = tags.byName("heron").orElseThrow();
        assertEquals(id, row.id());
        assertEquals("artist", row.category());
        assertEquals(0, row.postCount());
        assertTrue(tags.byName("missing").isEmpty());
    }

    @Test
    void alias_target_resolution() {
        long target = tag("heron", TagCategory.ARTIST);
        tags.putAlias("oldname", target);
        assertEquals(target, tags.aliasTarget("oldname").orElseThrow().id());
        // re-put replaces (no duplicate rows)
        tags.putAlias("oldname", target);
        assertEquals(target, tags.aliasTarget("oldname").orElseThrow().id());
        assertTrue(tags.aliasTarget("nope").isEmpty());
    }

    @Test
    void expand_matches_names_and_aliases() {
        long heron = tag("heron", TagCategory.ARTIST);
        long duck = tag("duck", TagCategory.GENERAL);
        tags.putAlias("oldname", heron);
        JdbcTagStore.Expansion e = tags.expand("%her%", null, 200);
        assertEquals(List.of(heron), e.ids());
        assertFalse(e.truncated());
        // an alias name matching the pattern expands to its canonical tag
        assertEquals(List.of(heron), tags.expand("%oldname%", null, 200).ids());
        // category filter respected ('%u%' matches duck; heron has no 'u' and is ARTIST anyway)
        assertEquals(List.of(duck), tags.expand("%u%", TagCategory.GENERAL, 200).ids());
    }

    @Test
    void expand_truncates_at_cap() {
        for (int i = 0; i < 5; i++) {
            tag("bulk" + i, TagCategory.GENERAL);
        }
        JdbcTagStore.Expansion e = tags.expand("%bulk%", null, 3);
        assertEquals(3, e.ids().size());
        assertTrue(e.truncated());
    }

    @Test
    void tagsOf_and_tagEntry_untagEntry() {
        long a = tag("a", TagCategory.GENERAL);
        long b = tag("b", TagCategory.ARTIST);
        tags.tagEntry(entryId, a);
        tags.tagEntry(entryId, a); // PK idempotence: no duplicate row
        tags.tagEntry(entryId, b);
        assertEquals(Set.of(a, b), tags.tagIdsOf(entryId));
        assertEquals(List.of("b", "a"), tags.tagsOf(entryId).stream().map(JdbcTagStore.TagRow::name).toList());
        tags.untagEntry(entryId, a);
        assertEquals(Set.of(b), tags.tagIdsOf(entryId));
    }

    @Test
    void tagEntry_records_provenance_and_taggedByOf_reports_it() {
        long userTagId = tag("user_tag", TagCategory.GENERAL);
        long aiTagId = tag("ai_tag", TagCategory.GENERAL);
        tags.tagEntry(entryId, userTagId, "user");
        tags.tagEntry(entryId, aiTagId, "hydra-3.5");
        assertEquals(Map.of(userTagId, "user", aiTagId, "hydra-3.5"), tags.taggedByOf(entryId));
    }

    @Test
    void tagEntry_conflict_updates_provenance_in_place() {
        long id = tag("flipped", TagCategory.GENERAL);
        tags.tagEntry(entryId, id, "hydra-3.5");
        tags.tagEntry(entryId, id, "user"); // same PK row: DO UPDATE rewrites tagged_by
        assertEquals(Map.of(id, "user"), tags.taggedByOf(entryId));
    }

    @Test
    void countWithProvenance_counts_distinct_entries_not_rows() {
        long redHair = tag("red_hair", TagCategory.GENERAL);
        long smile = tag("smile", TagCategory.GENERAL);
        tags.tagEntry(entryId, redHair, "hydra-3.5");
        tags.tagEntry(entryId, smile, "hydra-3.5"); // second row, same entry — still one entry
        long other = sink.insert(new NewEntry(Kind.IMAGE, "api", "b.png", "b.png", "png", "image/png",
                1, "hash-b", 8, 4, null, Instant.parse("2026-01-01T00:00:00Z"))).value();
        tags.tagEntry(other, redHair, "hydra-3.5");
        tags.tagEntry(other, smile, "user"); // user rows never count

        assertEquals(2, tags.countWithProvenance("hydra-3.5"));
        assertEquals(0, tags.countWithProvenance("hydra-9.9"));
    }

    @Test
    void implication_roundtrip_and_path() {
        long t1 = tag("t1", TagCategory.GENERAL);
        long t2 = tag("t2", TagCategory.GENERAL);
        long t3 = tag("t3", TagCategory.GENERAL);
        tags.addImplication(t1, t2);
        tags.addImplication(t2, t3);
        assertTrue(tags.implicationPathExists(t1, t3));
        assertFalse(tags.implicationPathExists(t3, t1));
        assertEquals(Map.of(t1, Set.of(t2), t2, Set.of(t3)), tags.allImplicationsEdges());
        assertEquals(List.of(new JdbcTagStore.ImplicationRow("t1", "t2"),
                new JdbcTagStore.ImplicationRow("t2", "t3")), tags.listImplications());
        tags.removeImplication(t1, t2);
        assertFalse(tags.implicationPathExists(t1, t3));
    }

    @Test
    void bumpPostCount_adjusts() {
        long id = tag("counted", TagCategory.GENERAL);
        tags.bumpPostCount(id, 2);
        tags.bumpPostCount(id, -1);
        assertEquals(1, tags.byName("counted").orElseThrow().postCount());
    }

    @Test
    void listTags_keyset_per_sort() {
        long a = tag("alpha", TagCategory.GENERAL);
        long b = tag("beta", TagCategory.ARTIST);
        long c = tag("gamma", TagCategory.GENERAL);
        tags.bumpPostCount(a, 5);
        tags.bumpPostCount(b, 5);
        tags.bumpPostCount(c, 1);

        // NAME ascending over all rows
        List<JdbcTagStore.TagRow> all = walk(TagSort.NAME, null, null);
        assertEquals(List.of("alpha", "beta", "gamma"), all.stream().map(JdbcTagStore.TagRow::name).toList());

        // COUNT descending, id tiebreak: alpha and beta both 5, gamma 1
        List<JdbcTagStore.TagRow> byCount = walk(TagSort.COUNT, null, null);
        assertEquals("gamma", byCount.get(2).name());
        assertNotEquals(byCount.get(0).name(), byCount.get(1).name());

        // NEW descending = creation order reversed
        List<JdbcTagStore.TagRow> byNew = walk(TagSort.NEW, null, null);
        assertEquals(List.of("gamma", "beta", "alpha"), byNew.stream().map(JdbcTagStore.TagRow::name).toList());

        // category filter rides along
        List<JdbcTagStore.TagRow> artists = walk(TagSort.NAME, null, TagCategory.ARTIST);
        assertEquals(List.of("beta"), artists.stream().map(JdbcTagStore.TagRow::name).toList());

        // name filter rides along (raw substring — the store escapes + wraps it)
        List<JdbcTagStore.TagRow> gam = walk(TagSort.NAME, "gamma", null);
        assertEquals(List.of("gamma"), gam.stream().map(JdbcTagStore.TagRow::name).toList());

        // ids referenced above stay used
        assertTrue(Set.of(a, b, c).containsAll(all.stream().map(JdbcTagStore.TagRow::id).toList()));
    }

    private List<JdbcTagStore.TagRow> walk(TagSort sort, String q, TagCategory category) {
        List<JdbcTagStore.TagRow> seen = new java.util.ArrayList<>();
        String cursorKey = null;
        long cursorId = 0;
        int guard = 0;
        while (guard++ < 10) {
            List<JdbcTagStore.TagRow> page = tags.listTags(q, category, sort, cursorKey, cursorId, 2);
            if (page.isEmpty()) {
                break;
            }
            seen.addAll(page);
            JdbcTagStore.TagRow last = page.get(page.size() - 1);
            cursorKey = switch (sort) {
                case NAME -> last.name();
                case COUNT -> String.valueOf(last.postCount());
                case NEW -> String.valueOf(last.createdAt().getEpochSecond() * 1_000_000
                        + last.createdAt().getNano() / 1000);
            };
            cursorId = last.id();
        }
        // no duplicates across pages
        assertEquals(seen.size(), new HashSet<>(seen.stream().map(JdbcTagStore.TagRow::id).toList()).size());
        return seen;
    }
}
