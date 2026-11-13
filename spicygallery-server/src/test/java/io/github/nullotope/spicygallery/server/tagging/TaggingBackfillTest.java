package io.github.nullotope.spicygallery.server.tagging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore;
import io.github.nullotope.spicygallery.server.tags.TagService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bulk tagging runner (spec §3.2): per-entry predict→apply with model provenance,
 * per-entry error accounting without aborting the run, resume-aware done seeding for the
 * untagged scope, idempotent rerun over a query scope, and the single-job guard.
 */
@SpringBootTest
class TaggingBackfillTest extends PostgresBase {

    @Autowired JdbcClient jdbc;
    @Autowired JdbcTagStore store;
    @Autowired TagService tagService;

    private final AtomicInteger providerCalls = new AtomicInteger();
    private final AtomicReference<String> providerFailsFor = new AtomicReference<>();
    private final AtomicReference<String> readerFailsFor = new AtomicReference<>();
    private final RecordingEvents hub = new RecordingEvents();
    private final FakePages pages = new FakePages();
    private TaggingService tagging;
    private TaggingBackfill backfill;
    private Path dataDir;

    @BeforeEach
    void clean(@TempDir Path dir) {
        jdbc.sql("delete from media_tag").update();
        jdbc.sql("delete from tag_implication").update();
        jdbc.sql("delete from tag_alias").update();
        jdbc.sql("delete from tag").update();
        jdbc.sql("delete from media_entry").update();
        providerCalls.set(0);
        providerFailsFor.set(null);
        readerFailsFor.set(null);
        hub.reset();
        pages.reset();
        dataDir = dir;
        tagging = new TaggingService(
                (bytes, calibration, implications) -> {
                    providerCalls.incrementAndGet();
                    String name = new String(bytes, StandardCharsets.UTF_8);
                    if (name.equals(providerFailsFor.get())) {
                        throw new IOException("provider down for " + name);
                    }
                    return Map.of("red_hair", 0.9, "smile", 0.8);
                },
                () -> new TaggingModelInfo("hydra-3.5",
                        Map.of("red_hair", "general", "smile", "general"), 2),
                tagService,
                cfg(),
                path -> {
                    String name = path.getFileName().toString();
                    if (name.equals(readerFailsFor.get())) {
                        throw new IOException("unreadable: " + name);
                    }
                    return name.getBytes(StandardCharsets.UTF_8);
                },
                (entryId, video, maxFrames) -> List.of(),
                Clock.systemUTC());
        backfill = new TaggingBackfill(tagging, store, this::lookup, pages, pages, hub);
    }

    /** cooldown 0: a provider fault opens the breaker, the very next poll re-closes it. */
    private static SpicyGalleryProperties.Tagging cfg() {
        return new SpicyGalleryProperties.Tagging(true, null, null, null, null, null, 0);
    }

    private Map<Long, TaggingBackfill.EntryRef> lookup(List<Long> ids) {
        return jdbc.sql("select id, kind, rel_path from media_entry "
                        + "where id in (:ids) and status = 'ok'")
                .param("ids", ids)
                .query((rs, i) -> new TaggingBackfill.EntryRef(rs.getLong("id"),
                        Kind.fromWireName(rs.getString("kind")),
                        dataDir.resolve(rs.getString("rel_path"))))
                .list()
                .stream()
                .collect(Collectors.toMap(TaggingBackfill.EntryRef::id, r -> r));
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

    /** Tags entries the way a previous bulk run would have: model-provenance rows. */
    private void seedHydra(long entryId, String... names) {
        List<TagService.TagInput> inputs = new ArrayList<>(names.length);
        for (String name : names) {
            inputs.add(new TagService.TagInput(name, TagCategory.GENERAL));
        }
        tagService.setEntryTagsPreservingUser(entryId, inputs, "hydra-3.5");
    }

    private List<String> tagNames(long entryId) {
        return jdbc.sql("""
                        select t.name from media_tag mt join tag t on t.id = mt.tag_id
                        where mt.media_entry_id = :id order by t.name
                        """)
                .param("id", entryId)
                .query(String.class).list();
    }

    private List<String> provenances(long entryId) {
        return jdbc.sql("select distinct tagged_by from media_tag "
                        + "where media_entry_id = :id order by tagged_by")
                .param("id", entryId)
                .query(String.class).list();
    }

    private List<String> tagTableSnapshot() {
        return jdbc.sql("select name || ':' || post_count from tag order by name")
                .query(String.class).list();
    }

    // --- fakes --------------------------------------------------------------

    /** Keyset source over a fixed id list; a null cursor starts from the beginning. */
    static final class FakePages implements TaggingBackfill.PageSource {
        private List<Long> ids = List.of();

        void set(List<Long> ids) {
            this.ids = ids;
        }

        void reset() {
            ids = List.of();
        }

        @Override
        public List<Long> page(Long afterId, int limit) {
            List<Long> out = new ArrayList<>();
            for (Long id : ids) {
                if (afterId == null || id > afterId) {
                    out.add(id);
                    if (out.size() == limit) {
                        break;
                    }
                }
            }
            return out;
        }
    }

    /** Records emitted events; the REST status carries the same live detail (poll transport). */
    static final class RecordingEvents implements TaggingBackfill.Events {
        final List<TaggingBackfill.Events.Progress> progress = new ArrayList<>();
        final List<TaggingBackfill.Events.Progress> doneEvents = new ArrayList<>();
        final List<String> failed = new ArrayList<>();

        void reset() {
            progress.clear();
            doneEvents.clear();
            failed.clear();
        }

        @Override
        public void progress(long done, long total, String current, long errors) {
            progress.add(new TaggingBackfill.Events.Progress(done, total, current, errors));
        }

        @Override
        public void done(long done, long total, long errors) {
            doneEvents.add(new TaggingBackfill.Events.Progress(done, total, null, errors));
        }

        @Override
        public void failed(String message) {
            failed.add(message);
        }
    }

    // --- brief tests ----------------------------------------------------------

    @Test
    void applies_predictions_per_entry_and_counts_errors_without_aborting() {
        long e1 = entry("e1.png");
        long e2 = entry("e2.png");
        long e3 = entry("e3.png");
        long e4 = entry("e4.png");
        long e5 = entry("e5.png");
        providerFailsFor.set("e3.png"); // provider fault for exactly one entry
        pages.set(List.of(e1, e2, e3, e4, e5));
        backfill.start("query", "red_hair", false);
        backfill.joinForTests();

        TaggingBackfill.RunStatus s = backfill.status();
        assertFalse(s.running());
        assertEquals(4, s.done(), "the failed entry is not done");
        assertEquals(1, s.errors());
        assertEquals(5, s.total());
        assertNull(s.error(), "a per-entry failure must not fail the job");
        assertEquals("e5.png", s.current(), "the last applied entry's file name is the live detail");
        assertTrue(hub.failed.isEmpty());
        assertEquals(4, hub.progress.size(), "one progress event per applied entry");
        assertEquals(4, hub.progress.get(3).done());
        assertEquals(1, hub.doneEvents.size());
        assertEquals(4, hub.doneEvents.get(0).done());
        assertEquals(5, hub.doneEvents.get(0).total());
        assertEquals(1, hub.doneEvents.get(0).errors());
        // the faulted entry carries no tags; the rest carry both predictions, model-provenance
        assertEquals(List.of(), tagNames(e3));
        for (long id : List.of(e1, e2, e4, e5)) {
            assertEquals(List.of("red_hair", "smile"), tagNames(id));
            assertEquals(List.of("hydra-3.5"), provenances(id));
        }
    }

    @Test
    void resume_aware_accounting_starts_done_at_entries_already_tagged_with_the_model() {
        long a = entry("a.png");
        long b = entry("b.png");
        long c = entry("c.png");
        long d = entry("d.png");
        long e = entry("e.png");
        seedHydra(a, "red_hair");
        seedHydra(b, "smile");
        seedHydra(c, "red_hair", "smile");
        // untagged scope: the three already-tagged entries left it — only d and e remain
        pages.set(List.of(d, e));
        backfill.start("untagged", null, true);
        backfill.joinForTests();

        TaggingBackfill.RunStatus s = backfill.status();
        assertFalse(s.running());
        assertEquals(5, s.total(), "3 already tagged + 2 remaining");
        assertEquals(5, s.done());
        assertEquals(0, s.errors());
        assertNull(s.error());
        // the resume seed is visible in the FIRST progress event: done already counts the 3
        assertEquals(2, hub.progress.size());
        assertEquals(4, hub.progress.get(0).done());
        assertEquals(5, hub.progress.get(0).total());
        assertEquals(1, hub.doneEvents.size());
        assertEquals(2, providerCalls.get(), "only the remaining entries are predicted");
        // the seeded entries' tags were left untouched
        assertEquals(List.of("red_hair", "smile"), tagNames(c));
        assertEquals(List.of("hydra-3.5"), provenances(c));
    }

    @Test
    void second_start_while_running_throws_illegal_state() {
        pages.set(List.of(entry("a.png")));
        backfill.start("query", null, false);
        assertThrows(IllegalStateException.class, () -> backfill.start("query", null, false));
        backfill.joinForTests();
        assertFalse(backfill.status().running());
        assertEquals(1, backfill.status().done());
    }

    @Test
    void a_finished_run_leaves_no_tagging_backfill_thread_behind() throws Exception {
        // final review I-1: the controller builds a NEW instance per bulk POST — a
        // per-instance single-thread executor parks one idle daemon thread per run,
        // so finished runs must not leave any tagging-backfill worker thread alive
        pages.set(List.of(entry("a.png")));
        backfill.start("query", null, false);
        backfill.joinForTests();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            boolean anyWorker = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> t.isAlive() && t.getName().startsWith("tagging-backfill"));
            if (!anyWorker) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20); // the worker may still be exiting its finally
        }
        fail("a tagging-backfill worker thread is still alive after the run finished");
    }

    @Test
    void rerun_is_idempotent() {
        long a = entry("a.png");
        long b = entry("b.png");
        pages.set(List.of(a, b));
        backfill.start("query", "silly", false);
        backfill.joinForTests();
        assertEquals(2, providerCalls.get());
        List<String> snapshotAfterFirst = tagTableSnapshot();

        hub.reset();
        backfill.start("query", "silly", false);
        backfill.joinForTests();

        TaggingBackfill.RunStatus s = backfill.status();
        assertFalse(s.running());
        assertEquals(2, s.done());
        assertEquals(0, s.errors());
        assertNull(s.error());
        assertEquals(4, providerCalls.get(), "the rerun re-predicts every entry (wholesale replace)");
        assertEquals(snapshotAfterFirst, tagTableSnapshot(), "zero added/updated changes on rerun");
        assertEquals(List.of("red_hair", "smile"), tagNames(a));
        assertEquals(List.of("red_hair", "smile"), tagNames(b));
        assertEquals(List.of("hydra-3.5"), provenances(a));
        assertEquals(1, hub.doneEvents.size());
        assertEquals(2, hub.doneEvents.get(0).done());
    }

    // --- failure-split + abort --------------------------------------------------

    @Test
    void local_io_error_counts_and_continues_without_tripping_the_breaker() {
        long a = entry("a.png");
        long b = entry("b.png");
        readerFailsFor.set("b.png");
        pages.set(List.of(a, b));
        backfill.start("query", null, false);
        backfill.joinForTests();

        TaggingBackfill.RunStatus s = backfill.status();
        assertFalse(s.running());
        assertEquals(1, s.done());
        assertEquals(1, s.errors());
        assertNull(s.error());
        assertTrue(tagging.breakerClosed(), "a local failure never trips the breaker");
        assertEquals(List.of("red_hair", "smile"), tagNames(a));
        assertEquals(List.of(), tagNames(b));
    }

    @Test
    void stop_aborts_a_run_paused_on_an_open_breaker() throws Exception {
        TaggingService stuck = new TaggingService(null, null, null, null, null, null, null) {
            private boolean opened;

            @Override
            public boolean breakerClosed() {
                return !opened;
            }

            @Override
            public TaggingService.Prediction predict(long entryId, Path absPath, Kind kind,
                                                     String calibrationOverride) {
                opened = true; // the provider faulted: the breaker stays open until stopped
                throw new TaggingUnavailableException("tagging provider unreachable");
            }
        };
        pages.set(List.of(entry("a.png")));
        TaggingBackfill stopped = new TaggingBackfill(stuck, store, this::lookup, pages, pages, hub);
        stopped.start("query", null, false);
        for (int i = 0; i < 250 && stopped.status().errors() == 0; i++) {
            TimeUnit.MILLISECONDS.sleep(20); // wait until the worker entered the breaker pause
        }
        stopped.stop();
        stopped.joinForTests();

        TaggingBackfill.RunStatus s = stopped.status();
        assertFalse(s.running());
        assertEquals(1, s.errors());
        assertEquals(0, s.done());
        assertNotNull(s.error(), "an aborted run reports why");
        assertEquals(1, hub.failed.size());
    }
}
