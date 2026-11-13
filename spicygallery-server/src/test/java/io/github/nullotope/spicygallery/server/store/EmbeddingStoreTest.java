package io.github.nullotope.spicygallery.server.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.server.PostgresBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** pgvector persistence (spec §3.3): replaceAll, missing-model keyset, best-frame related. */
@SpringBootTest
class EmbeddingStoreTest extends PostgresBase {

    @Autowired EmbeddingStore store;
    @Autowired JdbcMediaStoreSink sink;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from media_embedding").update();
        jdbc.sql("delete from media_entry").update();
    }

    private long entry(String name) {
        return sink.insert(new NewEntry(Kind.IMAGE, "api", name, name, "png", "image/png",
                1, "hash-" + name, 8, 4, null, Instant.parse("2026-01-01T00:00:00Z"))).value();
    }

    private static float[] full(float... v) {
        float[] full = new float[768];
        System.arraycopy(v, 0, full, 0, v.length);
        return full;
    }

    private static EmbeddingStore.FrameVector fv(int idx, Long ts, float... v) {
        return new EmbeddingStore.FrameVector(idx, ts, full(v));
    }

    @Test
    void replace_all_roundtrips_floats() {
        long id = entry("a.png");
        store.replaceAll(id, "m1", List.of(
                fv(0, null, 0.1f, 0.2f, 0.3f),
                fv(1, 1000L, 0.4f, 0.5f, 0.6f)));
        record Row(int idx, Long ts, String text) {}
        List<Row> rows = jdbc.sql("select frame_idx, timestamp_ms, embedding::text as t "
                        + "from media_embedding where media_entry_id = :id order by frame_idx")
                .param("id", id)
                .query((rs, i) -> new Row(rs.getInt("frame_idx"), rs.getObject("timestamp_ms", Long.class),
                        rs.getString("t")))
                .list();
        assertEquals(2, rows.size());
        assertEquals(0, rows.get(0).idx());
        assertEquals(1000L, rows.get(1).ts());
        // 5 significant digits of float fidelity survive the vector literal (pgvector prints 0, not 0.0)
        assertTrue(rows.get(0).text().startsWith("[0.1,0.2,0.3,0,0"), rows.get(0).text());
        assertTrue(rows.get(1).text().startsWith("[0.4,0.5,0.6,0,0"), rows.get(1).text());
    }

    @Test
    void has_for_model_and_counts() {
        long a = entry("b.png");
        store.replaceAll(a, "m1", List.of(fv(0, null, 1, 0, 0)));
        assertTrue(store.hasForModel(a, "m1"));
        assertFalse(store.hasForModel(a, "m2"));
        assertEquals(1, store.countWithModel("m1"));
        assertEquals(0, store.countWithModel("m2"));
    }

    @Test
    void entries_missing_keyset_walk() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(entry("m" + i + ".png"));
        }
        store.replaceAll(ids.get(0), "m1", List.of(fv(0, null, 1, 0, 0)));
        store.replaceAll(ids.get(1), "m1", List.of(fv(0, null, 1, 0, 0)));

        // walk all missing-m1 entries in pages of 2, no dupes, terminates
        List<Long> seen = new ArrayList<>();
        long cursor = 0;
        int guard = 0;
        while (guard++ < 10) {
            EmbeddingStore.MissingPage page = store.entriesMissing("m1", cursor, 2);
            seen.addAll(page.ids());
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }
        assertEquals(3, seen.size());
        assertEquals(3, new HashSet<>(seen).size());

        // after embedding one, it disappears from the walk
        store.replaceAll(seen.get(0), "m1", List.of(fv(0, null, 1, 0, 0)));
        assertEquals(2, store.entriesMissing("m1", 0, 10).ids().size());
    }

    @Test
    void related_orders_by_best_frame_and_excludes_self() {
        long a = entry("a");
        long b = entry("b");
        long c = entry("c");
        long d = entry("d");
        long e = entry("e");
        // A frames: [1,0,0] and [0,1,0] — B close to A's first frame, C far, D identical
        store.replaceAll(a, "m1", List.of(fv(0, null, 1, 0, 0), fv(1, 1000L, 0, 1, 0)));
        store.replaceAll(b, "m1", List.of(fv(0, null, 0.9f, 0.1f, 0)));
        store.replaceAll(c, "m1", List.of(fv(0, null, 0, 0, 1)));
        store.replaceAll(d, "m1", List.of(fv(0, null, 1, 0, 0)));
        // an m2 row on a DIFFERENT entry must be ignored (model filter); note replaceAll
        // replaces an entry's rows across models — this is why e carries the m2 row
        store.replaceAll(e, "m2", List.of(fv(0, null, 1, 0, 0)));

        List<EmbeddingStore.RelatedRow> related = store.related(a, "m1", 8);
        assertEquals(List.of(d, b, c), related.stream().map(EmbeddingStore.RelatedRow::entryId).toList());
        assertTrue(related.get(0).score() > 0.99);
        assertTrue(related.get(2).score() < 0.01);
    }

    @Test
    void cascade_delete_on_entry() {
        long a = entry("gone");
        store.replaceAll(a, "m1", List.of(fv(0, null, 1, 0, 0)));
        jdbc.sql("delete from media_entry where id = :id").param("id", a).update();
        assertEquals(0, jdbc.sql("select count(*) from media_embedding").query(Integer.class).single());
    }

    @Test
    void mean_vector_by_entry_averages_frames_and_omits_vectorless() {
        long a = entry("ma.png");
        long b = entry("mb.png");
        long c = entry("mc.png");
        long d = entry("md.png");
        // a: two frames → component-wise frame mean; b: one frame; c: no rows at all;
        // d: rows only for another model (model filter must hide them)
        store.replaceAll(a, "m1", List.of(fv(0, null, 1, 0, 0), fv(1, 1000L, 0, 3, 0)));
        store.replaceAll(b, "m1", List.of(fv(0, null, 2, 4, 0)));
        store.replaceAll(d, "m2", List.of(fv(0, null, 9, 9, 0)));

        Map<Long, float[]> means = store.meanVectorByEntry(List.of(a, b, c, d), "m1");

        assertEquals(Set.of(a, b), means.keySet());
        assertArrayEquals(full(0.5f, 1.5f, 0), means.get(a), 1e-6f);
        assertArrayEquals(full(2, 4, 0), means.get(b), 1e-6f);
        assertFalse(means.containsKey(c));
        assertFalse(means.containsKey(d));
        assertTrue(store.meanVectorByEntry(List.of(), "m1").isEmpty());
    }

    @Test
    void suggestions_rank_closest_first_honor_exclusion_skip_vectorless_and_non_ok() {
        long a = entry("sa.png");
        long b = entry("sb.png");
        long c = entry("sc.png");
        long d = entry("sd.png");
        long bad = entry("sbad.png");
        store.replaceAll(a, "m1", List.of(fv(0, null, 1, 0, 0)));       // identical → score 1
        store.replaceAll(b, "m1", List.of(fv(0, null, 0.9f, 0.1f, 0))); // close
        store.replaceAll(c, "m1", List.of(fv(0, null, 0, 0, 1)));       // far
        store.replaceAll(d, "m2", List.of(fv(0, null, 1, 0, 0)));       // wrong model → absent
        store.replaceAll(bad, "m1", List.of(fv(0, null, 1, 0, 0)));
        jdbc.sql("update media_entry set status = 'error' where id = :id").param("id", bad).update();

        float[] anchor = full(1, 0, 0);

        // no exclusions: closest first, non-ok and vector-less entries absent
        List<EmbeddingStore.RelatedRow> all = store.suggestions(anchor, "m1", Set.of(), 8);
        assertEquals(List.of(a, b, c), all.stream().map(EmbeddingStore.RelatedRow::entryId).toList());
        assertEquals(1.0, all.get(0).score(), 1e-6);
        assertTrue(all.get(1).score() > 0.9);
        assertTrue(all.get(2).score() < 0.01);

        // the exclusion set drops its entries; limit truncates after ranking
        assertEquals(List.of(b, c),
                store.suggestions(anchor, "m1", Set.of(a), 8).stream()
                        .map(EmbeddingStore.RelatedRow::entryId).toList());
        assertEquals(List.of(a, b),
                store.suggestions(anchor, "m1", Set.of(), 2).stream()
                        .map(EmbeddingStore.RelatedRow::entryId).toList());

        // everything excluded → empty, not an error
        assertTrue(store.suggestions(anchor, "m1", Set.of(a, b, c), 8).isEmpty());
    }
}
