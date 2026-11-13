package io.github.nullotope.spicygallery.server.importjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.ImportResult;
import io.github.nullotope.spicygallery.domain.ImportStats;
import io.github.nullotope.spicygallery.importer.ImportPipeline;
import io.github.nullotope.spicygallery.importer.ImportReporter;
import io.github.nullotope.spicygallery.probe.ImageKindHandler;
import io.github.nullotope.spicygallery.probe.KindHandlers;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.store.ImportRunRow;
import io.github.nullotope.spicygallery.server.store.JdbcImportRunStore;
import io.github.nullotope.spicygallery.server.store.ImportRunStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ImportServiceTest extends PostgresBase {

    static final Path FIX = Path.of("../spicygallery-core/src/test/resources/fixtures");
    static final Path LIB;
    static final Path APPDATA;
    static final Path NOT_A_DIR;

    static {
        try {
            LIB = Files.createTempDirectory("sg-lib");
            APPDATA = Files.createTempDirectory("sg-app");
            NOT_A_DIR = Files.createTempFile("sg-notdir", ".tmp");
            Files.copy(FIX.resolve("plain.png"), LIB.resolve("one.png"));
            for (int i = 0; i < 20; i++) {
                Files.copy(FIX.resolve("plain.png"), LIB.resolve("f" + i + ".png"));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void galleryProps(DynamicPropertyRegistry r) {
        r.add("spicygallery.libraries[0].name", () -> "alpha");
        r.add("spicygallery.libraries[0].root", () -> LIB.toString());
        r.add("spicygallery.libraries[1].name", () -> "bad");
        r.add("spicygallery.libraries[1].root", () -> NOT_A_DIR.toString());
        r.add("spicygallery.app-data", () -> APPDATA.toString());
        r.add("spicygallery.workers", () -> "1"); // serial → deterministic slow runs
    }

    /** Recording hub (no Mockito — JDK 25 breaks the BOM's inline mock maker). */
    static final class RecordingHub extends ImportSseHub {
        final List<ImportResult> doneResults = new CopyOnWriteArrayList<>();
        final List<String> failures = new CopyOnWriteArrayList<>();
        final AtomicInteger progressCount = new AtomicInteger();

        @Override
        public void progress(ImportStats s) {
            progressCount.incrementAndGet();
        }

        @Override
        public void done(ImportResult r) {
            doneResults.add(r);
        }

        @Override
        public void failed(String message) {
            failures.add(message);
        }
    }

    @TestConfiguration
    static class Cfg {
        @Bean
        @Primary
        ImportSseHub recordingHub() {
            return new RecordingHub();
        }
    }

    @Autowired ImportService service;
    @Autowired JdbcImportRunStore runs;
    @Autowired RecordingHub hub;
    @Autowired io.github.nullotope.spicygallery.server.store.JdbcMediaStoreSink jdbcSink;

    /**
     * The corpus files carry sub-microsecond mtimes (Files.copy → nanos); Postgres
     * timestamptz keeps microseconds. The diff must still see an untouched file as
     * unchanged on the second run — otherwise every import re-ingests those files.
     */
    @Test
    void reimport_is_noop_with_subsecond_mtimes() throws Exception {
        cleanDb(); // the follow-up tests share the real DB — this test needs an empty library
        service.useFactory(reporter -> new ImportPipeline(
                List.of(new io.github.nullotope.spicygallery.domain.LibraryRoot("alpha", LIB)),
                new KindHandlers(List.of(new ImageKindHandler(Set.of("png", "jpg", "jpe")))),
                jdbcSink, APPDATA, 1, reporter));

        service.start();
        awaitCompletion();
        ImportRunRow first = runs.last().orElseThrow();
        assertEquals("completed", first.status());
        assertTrue(first.added() >= 21, "first run added: " + first.added());

        service.start();
        awaitCompletion();
        ImportRunRow second = runs.last().orElseThrow();
        assertEquals(0, second.added(), "second run must change nothing — added");
        assertEquals(0, second.updated(), "second run must change nothing — updated");    }

    /** Real pipeline over BOTH configured roots (as production wiring does); the sink sleeps. */
    private io.github.nullotope.spicygallery.server.importjob.PipelineFactory realPipelineWithSlowSink() {
        return reporter -> new ImportPipeline(
                List.of(new io.github.nullotope.spicygallery.domain.LibraryRoot("alpha", LIB),
                        new io.github.nullotope.spicygallery.domain.LibraryRoot("bad", NOT_A_DIR)),
                new KindHandlers(List.of(new ImageKindHandler(Set.of("png", "jpg", "jpe")))),
                new SlowSink(), APPDATA, 1, reporter);
    }

    private void awaitCompletion() {
        long deadline = System.currentTimeMillis() + 30_000;
        while (service.running()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("import run did not finish within 30 s");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static void awaitTrue(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 10 s: " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void run_completes_with_counts_done_event_and_progress() throws Exception {
        service.useFactory(realPipelineWithSlowSink());

        long runId = service.start();
        assertTrue(runId > 0);
        assertEquals(Optional.of("running"), runs.last().map(ImportRunRow::status));

        awaitCompletion();
        ImportRunRow row = runs.last().orElseThrow();
        assertEquals("completed", row.status());
        assertEquals(21, row.added());
        awaitTrue("done event", () -> !hub.doneResults.isEmpty());
        assertEquals(21, hub.doneResults.get(0).added());
        assertTrue(hub.progressCount.get() >= 1);
    }

    @Test
    void second_start_conflicts_and_run_row_is_touched_mid_run() throws Exception {
        service.useFactory(realPipelineWithSlowSink());

        service.start();
        assertTrue(service.running());
        assertThrows(ImportInProgressException.class, () -> service.start());

        // mid-run: the run row's counters were touched (throttle ≤1 s)
        awaitTrue("touched counters", () -> runs.last().orElseThrow().scanned() > 0);

        awaitCompletion();
        assertEquals("completed", runs.last().orElseThrow().status());
    }

    @Test
    void factory_failure_marks_run_failed() throws Exception {
        service.useFactory(reporter -> {
            throw new IllegalStateException("boom");
        });

        long runId = service.start();
        awaitCompletion();
        ImportRunRow row = runs.last().orElseThrow();
        assertEquals(runId, row.id());
        assertEquals("failed", row.status());
        assertTrue(row.message() != null && row.message().contains("boom"));
        awaitTrue("failed event", () -> !hub.failures.isEmpty());
        assertTrue(hub.failures.get(0).contains("boom"));
    }

    @Test
    void mixed_roots_partial_skip_completes_with_skipped_message() throws Exception {
        service.useFactory(realPipelineWithSlowSink());

        service.start();
        awaitCompletion();
        ImportRunRow row = runs.last().orElseThrow();
        assertEquals("completed", row.status());
        assertTrue(row.message() != null && row.message().contains("skipped: bad"),
                "message: " + row.message());
    }

    @Test
    void recover_interrupted_marks_running_rows_failed() {
        long seeded = runs.start();
        service.recoverInterrupted();
        Optional<ImportRunRow> row = runs.last();
        assertEquals("failed", row.orElseThrow().status());
        assertEquals("interrupted by restart", row.orElseThrow().message());
        assertTrue(seeded > 0);
    }

    // --- import follow-up: createPool (spec §3.8) ------------------------------------

    @Autowired io.github.nullotope.spicygallery.server.pools.PoolService poolsService;
    @Autowired io.github.nullotope.spicygallery.server.store.PoolStore poolStore;
    @Autowired PoolImportFollowUp realFollowUp;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    /**
     * The follow-up tests run the REAL pipeline over the REAL sink, so they need a clean
     * DB: import_run first (pool_id FK has no cascade), then pool, then the entries.
     */
    private void cleanDb() {
        jdbc.sql("delete from import_run").update();
        jdbc.sql("delete from pool").update();
        jdbc.sql("delete from media_entry").update();
    }

    /** Real pipeline over the real DB sink — pool members must be actual rows. */
    private void useRealDbPipeline() {
        service.useFollowUp(realFollowUp);
        service.useFactory(reporter -> new ImportPipeline(
                List.of(new io.github.nullotope.spicygallery.domain.LibraryRoot("alpha", LIB)),
                new KindHandlers(List.of(new ImageKindHandler(Set.of("png", "jpg", "jpe")))),
                jdbcSink, APPDATA, 1, reporter));
    }

    @Test
    void createPool_true_drafts_pool_of_added_entries_in_natural_order() throws Exception {
        cleanDb();
        useRealDbPipeline();

        service.start(true);
        awaitCompletion();

        ImportRunRow row = runs.last().orElseThrow();
        assertEquals("completed", row.status());
        assertTrue(row.poolId() != null, "pool_id must be recorded on the run row");
        io.github.nullotope.spicygallery.server.pools.PoolService.PoolDetail detail =
                poolsService.detail(row.poolId());
        assertTrue(detail.pool().name().matches("Import \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"),
                "name must be 'Import YYYY-MM-DD HH:MM', was: " + detail.pool().name());
        assertEquals(21, detail.members().size());
        // natural filename order within the flat root directory: f2 before f10
        List<String> expected = new java.util.ArrayList<>(List.of(
                "f0.png", "f1.png", "f2.png", "f3.png", "f4.png", "f5.png", "f6.png", "f7.png",
                "f8.png", "f9.png", "f10.png", "f11.png", "f12.png", "f13.png", "f14.png",
                "f15.png", "f16.png", "f17.png", "f18.png", "f19.png"));
        expected.add("one.png");
        assertEquals(expected, detail.members().stream()
                .map(m -> m.entry().fileName()).toList());
        assertTrue(detail.pool().tags().stream()
                        .anyMatch(t -> "pool".equals(t.name()) && "pool_type".equals(t.category())),
                "system tag attached");
    }

    @Test
    void createPool_true_on_zero_added_rerun_creates_nothing() throws Exception {
        cleanDb();
        useRealDbPipeline();

        service.start(true); // first run: adds 21 → one pool
        awaitCompletion();
        assertEquals(1, poolStore.list().size());

        service.start(true); // re-run: nothing new → no pool, pool_id stays null
        awaitCompletion();
        ImportRunRow row = runs.last().orElseThrow();
        assertEquals("completed", row.status());
        assertEquals(0, row.added());
        assertNull(row.poolId());
        assertEquals(1, poolStore.list().size(), "no second pool for a 0-added run");
    }

    @Test
    void createPool_failure_never_fails_the_run() throws Exception {
        cleanDb();
        useRealDbPipeline();
        service.useFollowUp(ids -> {
            throw new IllegalStateException("pool boom");
        });

        service.start(true);
        awaitCompletion();

        ImportRunRow row = runs.last().orElseThrow();
        assertEquals("completed", row.status(), "the run completes despite the follow-up failure");
        assertNull(row.poolId());
        assertEquals(0, poolStore.list().size());
    }

    @Test
    void createPool_false_creates_nothing() throws Exception {
        cleanDb();
        useRealDbPipeline();

        service.start(); // default flag: off
        awaitCompletion();

        ImportRunRow row = runs.last().orElseThrow();
        assertEquals("completed", row.status());
        assertEquals(21, row.added());
        assertNull(row.poolId());
        assertEquals(0, poolStore.list().size());
    }

    /** Server-side minimal sink (core's test fake is not on the server classpath). */
    static final class SlowSink implements io.github.nullotope.spicygallery.importer.MediaStoreSink {
        private final java.util.Map<String, io.github.nullotope.spicygallery.domain.StoredLocation> byPath =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(0);

        @Override
        public Optional<io.github.nullotope.spicygallery.domain.StoredLocation> find(String rootName, String relPath) {
            return Optional.ofNullable(byPath.get(rootName + "/" + relPath));
        }

        @Override
        public java.util.Map<String, io.github.nullotope.spicygallery.domain.StoredLocation> allInRoot(String rootName) {
            java.util.Map<String, io.github.nullotope.spicygallery.domain.StoredLocation> out = new java.util.HashMap<>();
            byPath.forEach((k, v) -> {
                String prefix = rootName + "/";
                if (k.startsWith(prefix)) {
                    out.put(k.substring(prefix.length()), v);
                }
            });
            return out;
        }

        @Override
        public io.github.nullotope.spicygallery.domain.EntryId insert(io.github.nullotope.spicygallery.domain.NewEntry e) {
            try {
                Thread.sleep(40); // keep the run observable
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            long id = seq.incrementAndGet();
            byPath.put(e.rootName() + "/" + e.relPath(),
                    new io.github.nullotope.spicygallery.domain.StoredLocation(
                            new io.github.nullotope.spicygallery.domain.EntryId(id), e.kind(), e.ext(),
                            e.sizeBytes(), e.mediaMtime(), io.github.nullotope.spicygallery.domain.Status.OK));
            return new io.github.nullotope.spicygallery.domain.EntryId(id);
        }

        @Override
        public void update(io.github.nullotope.spicygallery.domain.EntryId id, io.github.nullotope.spicygallery.domain.NewEntry e) {
            // not exercised by these tests
        }

        @Override
        public void setStatus(io.github.nullotope.spicygallery.domain.EntryId id, io.github.nullotope.spicygallery.domain.Status status) {
            // not exercised by these tests
        }
    }
}
