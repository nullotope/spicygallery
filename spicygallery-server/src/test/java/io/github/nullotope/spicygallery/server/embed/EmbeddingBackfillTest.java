package io.github.nullotope.spicygallery.server.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.store.EmbeddingStore;
import io.github.nullotope.spicygallery.server.tagging.GpuJobGuard;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Backfill job (spec §3.2): walk missing, model changes, force, failure + resume. */
@SpringBootTest
class EmbeddingBackfillTest extends PostgresBase {

    @Autowired JdbcClient jdbc;
    @Autowired EmbeddingStore store;

    private final RecordingProvider provider = new RecordingProvider();
    private final GpuJobGuard guard = new GpuJobGuard();
    private EmbeddingBackfill backfill;
    private Path dataDir;

    @BeforeEach
    void clean(@TempDir Path dir) throws Exception {
        jdbc.sql("delete from media_embedding").update();
        jdbc.sql("delete from media_entry").update();
        provider.reset();
        dataDir = dir;
        // a real decodable image per entry name the tests create
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                8, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", dir.resolve("a.png").toFile());
        ImageIO.write(img, "png", dir.resolve("b.png").toFile());
        ImageIO.write(img, "png", dir.resolve("c.png").toFile());
        EmbeddingService service = new EmbeddingService(provider, store, cfg(), dir.resolve("appdata"),
                (video, workDir, tools, maxFrames) -> List.of(), java.time.Clock.systemUTC());
        backfill = new EmbeddingBackfill(service, store, this::lookup, guard);
    }

    private static SpicyGalleryProperties.Embedding cfg() {
        return new SpicyGalleryProperties.Embedding(
                true, "http://x/v1", "m1", null, 8, 64, 1024, 300L, 60L);
    }

    private java.util.Map<Long, EmbeddingBackfill.EntryRef> lookup(List<Long> ids) {
        return jdbc.sql("select id, kind, rel_path from media_entry "
                        + "where id in (:ids) and status = 'ok'")
                .param("ids", ids)
                .query((rs, i) -> new EmbeddingBackfill.EntryRef(rs.getLong("id"),
                        Kind.fromWireName(rs.getString("kind")),
                        dataDir.resolve(rs.getString("rel_path"))))
                .list()
                .stream()
                .collect(Collectors.toMap(EmbeddingBackfill.EntryRef::id, r -> r));
    }

    private long entry(String name) {
        return jdbc.sql("""
                        insert into media_entry (kind, status, root_name, rel_path, file_name, ext, mime,
                                                 size_bytes, content_hash, media_mtime, imported_at, updated_at)
                        values ('image', 'ok', 'api', :p, :p, 'png', 'image/png', 1, :h,
                                timestamptz '2026-01-01T00:00:00Z', now(), now())
                        returning id
                        """)
                .param("p", name)
                .param("h", "hash-" + name)
                .query(Long.class).single();
    }

    static final class RecordingProvider implements EmbeddingProvider {
        final List<List<EmbeddingProvider.EmbeddingInput>> calls = new ArrayList<>();
        Function<EmbeddingProvider.EmbeddingInput, float[]> answer = in -> seven68();
        volatile boolean failFromSecondCall;

        static float[] seven68() {
            float[] v = new float[768];
            v[0] = 1;
            return v;
        }

        void reset() {
            calls.clear();
            answer = in -> seven68();
            failFromSecondCall = false;
        }

        @Override
        public List<float[]> embed(List<EmbeddingProvider.EmbeddingInput> inputs) throws IOException {
            calls.add(List.copyOf(inputs));
            if (failFromSecondCall && calls.size() >= 2) {
                throw new IOException("provider down mid-run");
            }
            return inputs.stream().map(answer).toList();
        }
    }

    @Test
    void backfills_all_missing_and_reports_progress() {
        entry("a.png");
        entry("b.png");
        entry("c.png");
        backfill.start(false);
        backfill.joinForTests();
        EmbeddingBackfill.BackfillStatus status = backfill.status();
        assertFalse(status.running());
        assertEquals(3, status.done());
        assertEquals(3, status.total());
        assertEquals(3, store.countWithModel("m1"));
        assertNull(status.error());
        // the transient live-progress detail survives into the reconcile shape — the last
        // entry the run embedded (polling transport; the SSE stream is gone)
        assertEquals("c.png", status.current());
    }

    @Test
    void reembeds_model_mismatch() {
        long a = entry("a.png");
        store.replaceAll(a, "old-model", List.of(new EmbeddingStore.FrameVector(0, null,
                RecordingProvider.seven68())));
        // a has no m1 row (model changed) → backfill re-embeds and replaces the old rows
        backfill.start(false);
        backfill.joinForTests();
        assertTrue(store.hasForModel(a, "m1"));
        assertFalse(store.hasForModel(a, "old-model"));
        assertNull(backfill.status().error());
    }

    @Test
    void force_reembeds_even_when_current() {
        entry("a.png");
        backfill.start(false);
        backfill.joinForTests();
        int callsAfterFirst = provider.calls.size();
        backfill.start(true);
        backfill.joinForTests();
        assertTrue(provider.calls.size() > callsAfterFirst, "force re-embeds embedded entries");
        assertEquals(1, store.countWithModel("m1"));
    }

    @Test
    void double_start_throws() {
        entry("a.png");
        backfill.start(false);
        assertThrows(IllegalStateException.class, () -> backfill.start(false));
        backfill.joinForTests();
        assertTrue(store.countWithModel("m1") == 1);
    }

    @Test
    void a_worker_that_throws_still_releases_the_guard() {
        entry("a.png");
        // an Error escapes the worker's catch(Exception) — the finally-block guard
        // release is the only thing standing between this and a permanently blocked
        // tagging bulk run (plan Step 3 release discipline)
        provider.answer = in -> {
            throw new AssertionError("escape the worker loop");
        };
        backfill.start(false);
        backfill.joinForTests();

        assertFalse(backfill.status().running());
        assertTrue(guard.runningJob().isEmpty(), "a throwing worker must release the guard");
        guard.begin("tagging-bulk"); // the other GPU job type can start again
        guard.end("tagging-bulk");
    }

    @Test
    void an_error_before_the_worker_starts_still_releases_the_guard() {
        // final review M-4: the START path releases the guard on Error too — an Error
        // thrown between guard.begin and the worker submit (here: store accounting)
        // must not leave the shared GPU guard held, or the other job type is locked out
        entry("a.png");
        EmbeddingStore boom = new EmbeddingStore(jdbc) {
            @Override
            public long countEntries() {
                throw new AssertionError("start-path error before the worker runs");
            }
        };
        EmbeddingBackfill failing = new EmbeddingBackfill(
                new EmbeddingService(provider, boom, cfg(), dataDir.resolve("appdata"),
                        (video, workDir, tools, maxFrames) -> List.of(), java.time.Clock.systemUTC()),
                boom, this::lookup, guard);
        assertThrows(AssertionError.class, () -> failing.start(false));
        assertTrue(guard.runningJob().isEmpty(), "a start-path Error must release the guard");
        guard.begin("tagging-bulk"); // the other GPU job type can start again
        guard.end("tagging-bulk");
    }

    @Test
    void provider_failure_fails_job_with_error_and_is_resumable() {
        long a = entry("a.png");
        long b = entry("b.png");
        provider.failFromSecondCall = true;
        backfill.start(false);
        backfill.joinForTests();
        EmbeddingBackfill.BackfillStatus status = backfill.status();
        assertNotNull(status.error(), "job failed with an error");
        assertTrue(store.hasForModel(a, "m1"), "first entry embedded before the failure");
        assertFalse(store.hasForModel(b, "m1"));

        // heal the provider and resume (Review Focus 1: resumable)
        provider.failFromSecondCall = false;
        backfill.start(false);
        backfill.joinForTests();
        assertNull(backfill.status().error());
        assertTrue(store.hasForModel(b, "m1"));
        assertTrue(a > 0 && b > 0);
    }
}
