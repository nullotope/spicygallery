package io.github.nullotope.spicygallery.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.ImportResult;
import io.github.nullotope.spicygallery.domain.ImportStats;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.MediaEntry;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.Status;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.store.EntryQueries.Page;
import io.github.nullotope.spicygallery.server.store.PoolStore.PoolRow;
import java.util.Optional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
class JdbcStoresTest extends PostgresBase {

    static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired JdbcMediaStoreSink sink;
    @Autowired EntryQueries queries;
    @Autowired JdbcImportRunStore runs;
    @Autowired PoolStore poolStore;

    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from media_entry").update();
        jdbc.sql("delete from import_run").update();
        jdbc.sql("delete from pool").update(); // after import_run: import_run.pool_id FK (slice 6)
    }

    static NewEntry entry(String root, String rel) {
        return new NewEntry(Kind.IMAGE, root, rel, fileName(rel), extOf(rel), "image/png",
                10, "hash-" + rel, 8, 4, null, T);
    }

    static String fileName(String rel) {
        return rel.substring(rel.lastIndexOf('/') + 1);
    }

    static String extOf(String rel) {
        int dot = rel.lastIndexOf('.');
        return dot < 0 ? "" : rel.substring(dot + 1);
    }

    @Test
    void sink_roundtrip_all_fields() {
        EntryId id = sink.insert(entry("alpha", "x.png"));
        MediaEntry e = queries.byId(id.value()).orElseThrow();
        assertEquals("alpha", e.rootName());
        assertEquals("x.png", e.relPath());
        assertEquals(Kind.IMAGE, e.kind());
        assertEquals(Status.OK, e.status());
        assertEquals("hash-x.png", e.contentHash());
        assertEquals(8, e.width());
        assertEquals(T, e.mediaMtime());
    }

    @Test
    void update_keeps_id_and_bumps_updated_at() throws InterruptedException {
        EntryId id = sink.insert(entry("alpha", "x.png"));
        Instant before = queries.byId(id.value()).orElseThrow().updatedAt();
        Thread.sleep(5); // now() resolution
        sink.update(id, entry("alpha", "x.png"));
        MediaEntry after = queries.byId(id.value()).orElseThrow();
        assertEquals(id, after.id());
        assertTrue(after.updatedAt().isAfter(before));
    }

    @Test
    void duplicate_location_rejected() {
        sink.insert(entry("alpha", "x.png"));
        assertThrows(DataIntegrityViolationException.class, () -> sink.insert(entry("alpha", "x.png")));
    }

    @Test
    void set_status_and_find_semantics() {
        EntryId id = sink.insert(entry("alpha", "gone.png"));
        sink.setStatus(id, Status.MISSING);
        assertEquals(Status.MISSING, queries.byId(id.value()).orElseThrow().status());
        sink.setStatus(id, Status.OK);
        assertEquals(Status.OK, queries.byId(id.value()).orElseThrow().status());
    }

    private List<EntryId> seed(int n) {
        List<EntryId> ids = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            ids.add(sink.insert(entry("library", "f" + i + ".png")));
        }
        return ids;
    }

    private Page page(String q, Kind kind, String cursor, int limit) {
        var ast = io.github.nullotope.spicygallery.query.QueryParser.parse(q);
        return queries.page(io.github.nullotope.spicygallery.server.store.QuerySqlCompiler
                .compile(ast, kind, java.util.Map.of(), null, null, null), cursor, limit);
    }

    @Test
    void page_keyset_pagination_over_250_rows() {
        List<EntryId> ids = seed(250);
        long maxId = ids.get(ids.size() - 1).value();       // insertion order → last = max
        long sixtiethId = ids.get(250 - 60).value();        // 60th item of page 1 (desc)

        Page p1 = page(null, null, null, 60);
        assertEquals(60, p1.items().size());
        assertEquals(maxId, p1.items().get(0).id());        // id desc
        assertEquals(String.valueOf(sixtiethId), p1.nextCursor());

        // walk all pages → all 250 items, strictly descending, last page cursor null.
        // NOTE: identity continues across tests (delete ≠ truncate), so only relative
        // ids are asserted — seed() returns insertion order, last = max.
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        while (true) {
            Page page = page(null, null, cursor, 60);
            page.items().forEach(i -> seen.add(i.id()));
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }
        assertEquals(250, seen.size());
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i - 1) > seen.get(i));
        }
        assertEquals(maxId, seen.get(0));
    }

    @Test
    void kind_filter() {
        seed(3);
        sink.insert(new NewEntry(Kind.VIDEO, "library", "v.mp4", "v.mp4", "mp4",
                "video/mp4", 10, "hash-v", 64, 32, 600L, T));
        Page videos = page(null, Kind.VIDEO, null, 60);
        assertEquals(1, videos.items().size());
        assertEquals(600L, videos.items().get(0).durationMs());
    }

    @Test
    void q_matches_display_path_and_escapes_literals() {
        seed(2);
        sink.insert(entry("library", "sub/inner.png"));
        sink.insert(entry("other", "50%.png"));

        assertEquals(1, page("sub/inner", null, null, 60).items().size());
        assertEquals(1, page("inner.png", null, null, 60).items().size());
        assertEquals(1, page("50%", null, null, 60).items().size()); // literal % — 50%.png contains it
        assertEquals(1, page("%", null, null, 60).items().size());   // also literal → same file
        assertEquals(0, page("f1_.png", null, null, 60).items().size()); // literal _ → no such file
    }

    @Test
    void missing_and_error_excluded_from_page() {
        seed(2);
        EntryId gone = sink.insert(entry("library", "gone.png"));
        sink.setStatus(gone, Status.MISSING);
        EntryId bad = sink.insert(entry("library", "bad.png"));
        sink.setStatus(bad, Status.ERROR);
        Page page = page(null, null, null, 60);
        assertEquals(2, page.items().size());
        assertNull(page.nextCursor());
    }

    @Test
    void semantic_cursor_pages_are_disjoint_and_complete() {
        List<EntryId> ids = seed(6);
        // two cluster centers: even ids near [1,0,…], odd ids near [0,1,…]
        EmbeddingStore embeddings = new EmbeddingStore(jdbc);
        for (int i = 0; i < ids.size(); i++) {
            float[] v = new float[768];
            v[i % 2 == 0 ? 0 : 1] = 1;
            embeddings.replaceAll(ids.get(i).value(), "m1",
                    List.of(new EmbeddingStore.FrameVector(0, null, v)));
        }
        // a FILTERED-OUT row (status error) sharing an exact vector with the even
        // cluster: its distance ties the cursor at the cluster boundary, so a cursor
        // predicate whose tie branch escapes the status filter (SQL precedence bug)
        // would leak it into the walk. Inserted last → id above every even id.
        EntryId hidden = sink.insert(entry("library", "hidden-twin.png"));
        sink.setStatus(hidden, Status.ERROR);
        float[] twin = new float[768];
        twin[0] = 1;
        embeddings.replaceAll(hidden.value(), "m1",
                List.of(new EmbeddingStore.FrameVector(0, null, twin)));

        float[] query = new float[768];
        query[0] = 1;
        var ast = io.github.nullotope.spicygallery.query.QueryParser.parse("similar:x");
        CompiledQuery cq = io.github.nullotope.spicygallery.server.store.QuerySqlCompiler
                .compile(ast, null, java.util.Map.of(), null, query, "m1");

        List<Long> seen = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (guard++ < 10) {
            Page page = queries.page(cq, cursor, 2);
            page.items().forEach(it -> {
                seen.add(it.id());
                scores.add(it.score());
            });
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }
        assertEquals(6, seen.size());
        assertEquals(6, new HashSet<>(seen).size(), "no repeats across distance pages");
        assertFalse(seen.contains(hidden.value()), "error-status row must never leak via the tie branch");
        // even-id cluster (distance 0) before odd-id cluster (distance 1): scores descending
        assertTrue(scores.get(0) > scores.get(scores.size() - 1), String.valueOf(scores));
    }

    @Test
    void neighbors_prev_next_with_edges() {
        List<EntryId> ids = seed(5); // insertion order: ids[0] = lowest
        long lo = ids.get(0).value(), second = ids.get(1).value();
        long mid = ids.get(2).value(), fourth = ids.get(3).value(), hi = ids.get(4).value();

        // grid order is id DESC: prev = tile before in the grid (higher id),
        // next = tile after (lower id) — must match the client's in-memory list
        long[] midN = neighbors(null, mid).orElseThrow();
        assertEquals(fourth, midN[0]);
        assertEquals(second, midN[1]);
        long[] first = neighbors(null, hi).orElseThrow();
        assertEquals(0, first[0]); // first grid tile: no prev
        assertEquals(fourth, first[1]);
        long[] last = neighbors(null, lo).orElseThrow();
        assertEquals(second, last[0]);
        assertEquals(0, last[1]); // last grid tile: no next
        // filter narrows the set: no matches around the entry
        assertTrue(neighbors("nomatch", mid).isEmpty());
    }

    private Optional<long[]> neighbors(String q, long id) {
        var ast = io.github.nullotope.spicygallery.query.QueryParser.parse(q);
        return queries.neighbors(io.github.nullotope.spicygallery.server.store.QuerySqlCompiler
                .compile(ast, null, java.util.Map.of(), null, null, null), id);
    }

    @Test
    void run_store_lifecycle() {
        assertTrue(runs.last().isEmpty());
        long id = runs.start();
        assertEquals("running", runs.last().orElseThrow().status());
        runs.touch(id, new ImportStats(10, 10, 5, 1, 0, 0, "alpha/x.png"));
        ImportRunRow touched = runs.last().orElseThrow();
        assertEquals(10, touched.scanned());
        assertEquals(5, touched.added());
        runs.complete(id, new ImportResult(10, 5, 1, 0, 0, List.of("bad"), List.of()));
        ImportRunRow done = runs.last().orElseThrow();
        assertEquals("completed", done.status());
        assertEquals(5, done.added());
        assertNotNull(done.finishedAt());
        assertEquals("skipped: bad", done.message());

        long id2 = runs.start();
        runs.fail(id2, "boom");
        assertEquals("failed", runs.last().orElseThrow().status());
        assertEquals("boom", runs.last().orElseThrow().message());
    }

    @Test
    void mark_interrupted_flips_running_rows() {
        runs.start();
        runs.start();
        runs.markInterrupted();
        ImportRunRow row = runs.last().orElseThrow();
        assertEquals("failed", row.status());
        assertEquals("interrupted by restart", row.message());
    }

    @Test
    void run_pool_id_roundtrip_and_cleared_when_pool_deleted() {
        long runId = runs.start();
        assertNull(runs.last().orElseThrow().poolId(), "fresh run: no pool link");

        PoolRow pool = poolStore.create("imported", null, null, null);
        runs.setPoolId(runId, pool.id());
        assertEquals(pool.id(), runs.last().orElseThrow().poolId());

        runs.setPoolId(runId, null);
        assertNull(runs.last().orElseThrow().poolId());

        runs.setPoolId(runId, pool.id());
        // import_run.pool_id has NO on delete cascade (V5 spec-verbatim): deleting the
        // referenced pool must clear the run's link, never FK-fail (T7 routed finding)
        poolStore.delete(pool.id());
        assertFalse(poolStore.byId(pool.id()).isPresent());
        ImportRunRow survivor = runs.last().orElseThrow();
        assertNull(survivor.poolId(), "the run survives with the link cleared");
        assertEquals("running", survivor.status());
    }
}
