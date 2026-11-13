package io.github.nullotope.spicygallery.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.ImportResult;
import io.github.nullotope.spicygallery.domain.ImportStats;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.LibraryRoot;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.Status;
import io.github.nullotope.spicygallery.domain.StoredLocation;
import io.github.nullotope.spicygallery.probe.ImageKindHandler;
import io.github.nullotope.spicygallery.probe.KindHandlers;
import io.github.nullotope.spicygallery.thumb.ThumbLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportPipelineTest {

    static final Path FIX = Path.of("src/test/resources/fixtures");
    static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
    static final KindHandlers IMAGES = new KindHandlers(List.of(new ImageKindHandler(
            Set.of("jpg", "jpeg", "jpe", "png", "gif", "webp", "bmp"))));

    static final class RecordingReporter implements ImportReporter {
        final List<ImportStats> stats = new ArrayList<>();
        volatile ImportResult result;
        final List<String> failures = new ArrayList<>();

        @Override
        public void progress(ImportStats s) {
            stats.add(s);
        }

        @Override
        public void done(ImportResult r) {
            this.result = r;
        }

        @Override
        public void failed(String message) {
            failures.add(message);
        }
    }

    static void setMtime(Path p, Instant t) throws Exception {
        Files.setLastModifiedTime(p, FileTime.from(t));
    }

    static ImportResult run(List<LibraryRoot> roots, FakeSink sink, Path appData,
                            int workers, RecordingReporter reporter) throws Exception {
        return new ImportPipeline(roots, IMAGES, sink, appData, workers, reporter).call();
    }

    @Test
    void end_to_end_new_image(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        Path png = Files.copy(FIX.resolve("plain.png"), alpha.resolve("plain.png"));
        String expectedHash = ContentHasher.sha256Hex(png);

        RecordingReporter reporter = new RecordingReporter();
        FakeSink sink = new FakeSink();
        ImportResult r = run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2, reporter);

        NewEntry e = sink.row("alpha/plain.png");
        assertNotNull(e);
        assertEquals(Kind.IMAGE, e.kind());
        assertEquals(8, e.width());
        assertEquals(4, e.height());
        assertEquals("image/png", e.mime());
        assertEquals(expectedHash, e.contentHash());
        assertEquals(Status.OK, sink.statusOf(sink.find("alpha", "plain.png").orElseThrow().id().value()));
        long id = sink.find("alpha", "plain.png").orElseThrow().id().value();
        assertTrue(Files.exists(ThumbLayout.pathFor(appData, id)));
        assertEquals(1, r.added());
        assertEquals(0, r.errors());
        assertNotNull(reporter.result);
        assertTrue(reporter.failures.isEmpty());
    }

    @Test
    void changed_keeps_id_and_overwrites_thumb(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        Path png = Files.copy(FIX.resolve("plain.png"), alpha.resolve("changed.png"));
        setMtime(png, T);
        Path oldThumb = ThumbLayout.pathFor(appData, 7);
        Files.createDirectories(oldThumb.getParent());
        Files.write(oldThumb, new byte[] {1, 2, 3});

        FakeSink sink = new FakeSink();
        sink.byPath.put("alpha/changed.png", new StoredLocation(new EntryId(7), Kind.IMAGE, "png",
                999, T.plusSeconds(3600), Status.OK)); // size+mtime differ

        ImportResult r = run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2,
                new RecordingReporter());

        assertTrue(sink.calls.contains("update:7"), "calls: " + sink.calls);
        assertEquals(1, r.updated());
        assertEquals(8, sink.rows.get(7L).width());
        assertTrue(Files.exists(oldThumb));
        assertTrue(Files.size(oldThumb) > 3, "thumb overwritten with real bytes");
    }

    @Test
    void unchanged_skips_writes(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        Path png = Files.copy(FIX.resolve("plain.png"), alpha.resolve("same.png"));
        setMtime(png, T);
        FakeSink sink = new FakeSink();
        sink.byPath.put("alpha/same.png", new StoredLocation(new EntryId(7), Kind.IMAGE, "png",
                Files.size(png), T, Status.OK));

        ImportResult r = run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2,
                new RecordingReporter());

        assertTrue(sink.calls.isEmpty(), "calls: " + sink.calls);
        assertEquals(0, r.added());
        assertEquals(0, r.updated());
    }

    @Test
    void current_file_tracks_active_ingest_not_dispatch(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        for (String name : List.of("a.png", "b.png", "c.png")) {
            Files.copy(FIX.resolve("plain.png"), alpha.resolve(name));
        }
        FakeSink sink = new FakeSink();
        CountDownLatch gate = new CountDownLatch(1);
        sink.insertGate = gate;

        RecordingReporter reporter = new RecordingReporter();
        ImportPipeline pipeline = new ImportPipeline(List.of(new LibraryRoot("alpha", alpha)),
                IMAGES, sink, appData, 2, reporter);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            var future = exec.submit(pipeline::call);
            // both workers park in blocked inserts (a and b); c stays queued
            long deadline = System.currentTimeMillis() + 5000;
            boolean started = false;
            while (System.currentTimeMillis() < deadline) {
                String cur = pipeline.currentFile();
                if ("alpha/a.png".equals(cur) || "alpha/b.png".equals(cur)) {
                    started = true;
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(started, "pipeline never started an ingest; current=" + pipeline.currentFile());
            Thread.sleep(200); // the walk (µs) has long since dispatched c
            // the bug signature: `current` froze on the last-DISPATCHED file (c) while
            // the queue drains — it must name the last STARTED file instead
            assertNotEquals("alpha/c.png", pipeline.currentFile(),
                    "current tracks dispatch, not active ingest");
            gate.countDown();
            ImportResult r = future.get(10, TimeUnit.SECONDS);
            assertEquals(3, r.added());
        } finally {
            gate.countDown();
            exec.shutdownNow();
        }
    }

    @Test
    void progress_tracks_processed_converging_to_scanned(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        // one new (ingested), one unchanged (skipped at scan), one unknown ext (counted, never ingested)
        Files.copy(FIX.resolve("plain.png"), alpha.resolve("new.png"));
        Path same = Files.copy(FIX.resolve("plain.png"), alpha.resolve("same.png"));
        setMtime(same, T);
        Files.writeString(alpha.resolve("notes.txt"), "not an image");
        FakeSink sink = new FakeSink();
        sink.byPath.put("alpha/same.png", new StoredLocation(new EntryId(7), Kind.IMAGE, "png",
                Files.size(same), T, Status.OK));

        RecordingReporter reporter = new RecordingReporter();
        ImportResult r = run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2, reporter);

        assertEquals(3, r.scanned());
        assertFalse(reporter.stats.isEmpty());
        ImportStats last = reporter.stats.get(reporter.stats.size() - 1);
        // every discovered file is accounted for: ingested, skipped, or counted — the
        // banner's "processing X of Y" converges to 100% when the run ends
        assertEquals(last.scanned(), last.processed(), "stats: " + reporter.stats);
    }

    @Test
    void missing_marks_missing(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        FakeSink sink = new FakeSink();
        sink.byPath.put("alpha/gone.png", new StoredLocation(new EntryId(7), Kind.IMAGE, "png",
                1, T, Status.OK));

        ImportResult r = run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2,
                new RecordingReporter());

        assertEquals(List.of("status:7:missing"), sink.calls);
        assertEquals(1, r.missing());
        assertEquals(Status.MISSING, sink.statusOf(7));
    }

    @Test
    void resurrect_keeps_thumb_or_regenerates(@TempDir Path tmp) throws Exception {
        // (a) thumb still present → only setStatus(OK)
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        Path png = Files.copy(FIX.resolve("plain.png"), alpha.resolve("back.png"));
        setMtime(png, T);
        FakeSink sink = new FakeSink();
        sink.byPath.put("alpha/back.png", new StoredLocation(new EntryId(7), Kind.IMAGE, "png",
                Files.size(png), T, Status.MISSING));
        Path thumb = ThumbLayout.pathFor(appData, 7);
        Files.createDirectories(thumb.getParent());
        Files.write(thumb, new byte[] {9});

        run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2, new RecordingReporter());
        assertEquals(List.of("status:7:ok"), sink.calls);
        assertEquals(9, Files.readAllBytes(thumb)[0]); // thumb untouched

        // (b) thumb deleted → regenerated
        Files.delete(thumb);
        sink.calls.clear();
        run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2, new RecordingReporter());
        assertTrue(Files.exists(thumb), "thumb regenerated");
        assertEquals(Status.OK, sink.statusOf(7));
    }

    @Test
    void truncated_image_becomes_error_row_and_run_continues(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        byte[] full = Files.readAllBytes(FIX.resolve("plain.jpg"));
        Files.write(alpha.resolve("broken.jpg"), java.util.Arrays.copyOf(full, 50));
        Files.copy(FIX.resolve("plain.png"), alpha.resolve("fine.png"));
        String brokenHash = ContentHasher.sha256Hex(alpha.resolve("broken.jpg"));

        RecordingReporter reporter = new RecordingReporter();
        FakeSink sink = new FakeSink();
        ImportResult r = run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2, reporter);

        assertEquals(1, r.errors());
        assertEquals(1, r.added());
        NewEntry broken = sink.row("alpha/broken.jpg");
        assertNotNull(broken, "error row written");
        assertEquals(Status.ERROR, sink.statusOf(
                sink.find("alpha", "broken.jpg").orElseThrow().id().value()));
        assertEquals(brokenHash, broken.contentHash());
        assertEquals("image/jpeg", broken.mime());
        assertNull(broken.width());
        assertNotNull(sink.row("alpha/fine.png"));
        assertNotNull(reporter.result);
        // the error row's id must NOT join addedEntryIds — only fully ok adds do (spec §3.8)
        assertEquals(List.of(sink.find("alpha", "fine.png").orElseThrow().id().value()),
                r.addedEntryIds());
    }

    @Test
    void added_entry_ids_collected_for_new_entries(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        Files.copy(FIX.resolve("plain.png"), alpha.resolve("a.png"));
        Files.copy(FIX.resolve("plain.png"), alpha.resolve("b.png"));

        FakeSink sink = new FakeSink();
        ImportResult r = run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2,
                new RecordingReporter());

        assertEquals(2, r.added());
        assertEquals(2, r.addedEntryIds().size());
        // ingest fans out to workers, so the collection order is nondeterministic
        assertEquals(Set.of(
                        sink.find("alpha", "a.png").orElseThrow().id().value(),
                        sink.find("alpha", "b.png").orElseThrow().id().value()),
                Set.copyOf(r.addedEntryIds()));
    }

    @Test
    void updated_or_missing_runs_collect_no_added_ids(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        Path png = Files.copy(FIX.resolve("plain.png"), alpha.resolve("changed.png"));
        setMtime(png, T);
        FakeSink sink = new FakeSink();
        sink.byPath.put("alpha/changed.png", new StoredLocation(new EntryId(7), Kind.IMAGE, "png",
                999, T.plusSeconds(3600), Status.OK)); // size+mtime differ → changed

        ImportResult updated = run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2,
                new RecordingReporter());
        assertEquals(1, updated.updated());
        assertEquals(0, updated.added());
        assertTrue(updated.addedEntryIds().isEmpty(),
                "updated-only run must not collect ids: " + updated.addedEntryIds());

        Files.delete(png);
        ImportResult missing = run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2,
                new RecordingReporter());
        assertEquals(1, missing.missing());
        assertEquals(0, missing.added());
        assertTrue(missing.addedEntryIds().isEmpty(),
                "missing-only run must not collect ids: " + missing.addedEntryIds());
    }

    @Test
    void skipped_roots_reported_and_others_processed(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        Path notADir = Files.write(tmp.resolve("bad"), new byte[] {1});
        Files.copy(FIX.resolve("plain.png"), alpha.resolve("fine.png"));

        RecordingReporter reporter = new RecordingReporter();
        FakeSink sink = new FakeSink();
        ImportResult r = run(List.of(
                new LibraryRoot("bad", notADir), new LibraryRoot("alpha", alpha)),
                sink, appData, 2, reporter);

        assertEquals(List.of("bad"), r.skippedRoots());
        assertNotNull(sink.row("alpha/fine.png"));
    }

    @Test
    void hashes_failing_file_gets_no_row(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        Path locked = Files.copy(FIX.resolve("plain.png"), alpha.resolve("locked.png"));
        Files.setPosixFilePermissions(locked, java.util.Set.of());

        RecordingReporter reporter = new RecordingReporter();
        FakeSink sink = new FakeSink();
        ImportResult r = run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2, reporter);

        assertEquals(1, r.errors());
        assertNull(sink.row("alpha/locked.png"), "no row when even hashing fails");
    }

    @Test
    void concurrency_processes_all_files(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        for (int i = 0; i < 20; i++) {
            Files.copy(FIX.resolve("plain.png"), alpha.resolve("f" + i + ".png"));
        }
        RecordingReporter reporter = new RecordingReporter();
        FakeSink sink = new FakeSink();
        ImportResult r = run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 4, reporter);
        assertEquals(20, r.added());
        assertEquals(20, sink.rows.size());
    }

    @Test
    void reporter_sees_progress_and_final_done(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        for (int i = 0; i < 5; i++) {
            Files.copy(FIX.resolve("plain.png"), alpha.resolve("f" + i + ".png"));
        }
        RecordingReporter reporter = new RecordingReporter();
        FakeSink sink = new FakeSink();
        run(List.of(new LibraryRoot("alpha", alpha)), sink, appData, 2, reporter);

        assertTrue(reporter.stats.size() >= 1, "at least one progress event");
        assertNotNull(reporter.result);
        assertTrue(reporter.failures.isEmpty());
        // counters are cumulative and monotonic
        AtomicInteger lastAdded = new AtomicInteger(0);
        for (ImportStats s : reporter.stats) {
            assertTrue(s.added() >= lastAdded.get());
            lastAdded.set(s.added());
        }
    }
}
