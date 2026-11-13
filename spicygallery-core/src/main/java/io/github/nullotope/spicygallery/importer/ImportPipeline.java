package io.github.nullotope.spicygallery.importer;

import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.ImportResult;
import io.github.nullotope.spicygallery.domain.ImportStats;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.LibraryRoot;
import io.github.nullotope.spicygallery.domain.MimeTypes;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.ProbeResult;
import io.github.nullotope.spicygallery.domain.ScannedFile;
import io.github.nullotope.spicygallery.domain.Status;
import io.github.nullotope.spicygallery.domain.StoredLocation;
import io.github.nullotope.spicygallery.probe.KindHandler;
import io.github.nullotope.spicygallery.probe.KindHandlers;
import io.github.nullotope.spicygallery.thumb.ThumbLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes the scanner's ops (spec §6.2/§6.5): ingest = hash → probe → thumbnail →
 * store row → write thumb; failures become {@code error} rows (a file whose hash itself
 * fails gets no row); Resurrect restores status and regenerates lost thumbnails;
 * Missing marks rows. Roots that are not readable dirs are skipped and reported.
 * Blocking; ingest work fans out to a fixed pool; progress is throttled to ≤ ~4/s.
 */
public final class ImportPipeline {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ImportPipeline.class);

    private static final long PROGRESS_THROTTLE_MS = 250;

    private final List<LibraryRoot> roots;
    private final KindHandlers handlers;
    private final MediaStoreSink sink;
    private final Path appData;
    private final int workers;
    private final ImportReporter reporter;
    private final PostIngestHook postIngestHook;

    private final AtomicLong scanned = new AtomicLong();
    // files fully accounted for: ingested, skipped as unchanged, or counted as unknown-ext.
    // trails `scanned` while the ingest queue drains — the pair drives the progress bar.
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong added = new AtomicLong();
    private final AtomicLong updated = new AtomicLong();
    private final AtomicLong missing = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    // ingest fans out to workers — the collection must be thread-safe; order is NOT
    // meaningful (the follow-up re-orders by directory/name/id, spec §3.8)
    private final List<Long> addedEntryIds = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<String> current = new AtomicReference<>();
    private final AtomicLong lastProgressAt = new AtomicLong();
    private final AtomicBoolean failedReported = new AtomicBoolean();
    private ExecutorService pool;

    public ImportPipeline(List<LibraryRoot> roots, KindHandlers handlers, MediaStoreSink sink,
                          Path appData, int workers, ImportReporter reporter) {
        this(roots, handlers, sink, appData, workers, reporter, null);
    }

    public ImportPipeline(List<LibraryRoot> roots, KindHandlers handlers, MediaStoreSink sink,
                          Path appData, int workers, ImportReporter reporter, PostIngestHook postIngestHook) {
        this.roots = List.copyOf(roots);
        this.handlers = handlers;
        this.sink = sink;
        this.appData = appData;
        this.workers = Math.max(1, workers);
        this.reporter = reporter;
        this.postIngestHook = postIngestHook == null ? (id, p, k) -> {} : postIngestHook;
    }

    public ImportResult call() throws InterruptedException {
        List<LibraryRoot> usable = new ArrayList<>();
        List<String> skippedRoots = new ArrayList<>();
        for (LibraryRoot root : roots) {
            Path p = root.root();
            if (Files.isDirectory(p) && Files.isReadable(p)) {
                usable.add(root);
            } else {
                skippedRoots.add(root.name());
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        this.pool = executor;
        try {
            new LibraryScanner(usable, handlers.allowlist()).scan(sink, this::dispatch);
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                executor.shutdownNow();
            }
        } catch (IOException e) {
            executor.shutdownNow();
            if (failedReported.compareAndSet(false, true)) {
                reporter.failed("scan failed: " + e.getMessage());
            }
            throw new UncheckedIOException("scan failed", e);
        } catch (RuntimeException e) {
            // a RuntimeException from the scan (DB hiccup in the sink, an SSE emitter
            // racing completion…) must not leak the worker pool (review I4)
            executor.shutdownNow();
            if (failedReported.compareAndSet(false, true)) {
                reporter.failed("scan failed: " + e);
            }
            throw e;
        }
        // final unthrottled flush: the converged counters reach the banner before 'done'
        // (a fast run can otherwise finish entirely inside the progress throttle window)
        reporter.progress(new ImportStats((int) scanned.get(), (int) processed.get(), (int) added.get(),
                (int) updated.get(), (int) missing.get(), (int) errors.get(), current.get()));
        ImportResult result = new ImportResult((int) scanned.get(), (int) added.get(),
                (int) updated.get(), (int) missing.get(), (int) errors.get(), skippedRoots,
                List.copyOf(addedEntryIds));
        if (failedReported.compareAndSet(false, true)) {
            reporter.done(result);
        }
        return result;
    }

    private void dispatch(ScanOp op) {
        if (op instanceof ScanOp.New n) {
            scanned.incrementAndGet();
            dispatch(n.file(), null);
        } else if (op instanceof ScanOp.Changed c) {
            scanned.incrementAndGet();
            dispatch(c.file(), c.id());
        } else if (op instanceof ScanOp.Resurrect r) {
            scanned.incrementAndGet();
            submit(() -> {
                try {
                    current.set(display(r.file()));
                    resurrect(r.file(), r.id());
                } finally {
                    processed.incrementAndGet();
                    maybeReportProgress();
                }
            });
        } else if (op instanceof ScanOp.Unchanged u) {
            scanned.incrementAndGet();
            // nothing to ingest — the walk IS the work; accounting is synchronous so the
            // banner's bar advances with the walk on re-scans
            current.set(display(u.file()));
            processed.incrementAndGet();
        } else if (op instanceof ScanOp.UnknownExt x) {
            scanned.incrementAndGet();
            current.set(display(x.file()));
            processed.incrementAndGet();
        } else if (op instanceof ScanOp.Missing m) {
            submit(() -> {
                try {
                    sink.setStatus(m.id(), Status.MISSING);
                    missing.incrementAndGet();
                } finally {
                    processed.incrementAndGet();
                    maybeReportProgress();
                }
            });
        }
        maybeReportProgress();
    }

    private void dispatch(ScannedFile file, EntryId existingId) {
        submit(() -> {
            try {
                // `current` is set by the WORKER at ingest start — during the drain phase
                // (walk finished, queue still draining) dispatch-time updates would freeze
                // it on the last-dispatched file
                current.set(display(file));
                ingest(file, existingId);
            } finally {
                processed.incrementAndGet();
                // workers must report too: once the walk ends, dispatch stops and only
                // ingest completions move the banner
                maybeReportProgress();
            }
        });
    }

    private void ingest(ScannedFile file, EntryId existingId) {
        try {
            String hash;
            try {
                hash = ContentHasher.sha256Hex(file.absPath());
            } catch (IOException e) {
                errors.incrementAndGet(); // even hashing failed → no row (spec §6.2)
                return;
            }
            KindHandler handler = handlers.forExtension(file.ext()).orElse(null);
            if (handler == null) {
                errors.incrementAndGet(); // allowlist drift between scan and ingest
                return;
            }
            ProbeResult probe;
            byte[] thumb;
            try {
                probe = handler.probe(file.absPath());
            } catch (IOException e) {
                probe = null;
            }
            NewEntry entry;
            if (probe == null) {
                entry = errorEntry(file, handler.kind(), hash);
                writeErrorRow(entry, existingId);
                return;
            }
            try {
                thumb = handler.thumbnail(file.absPath(), probe);
            } catch (IOException e) {
                thumb = null;
            }
            entry = new NewEntry(handler.kind(), file.root().name(), file.relPath(),
                    fileNameOf(file), file.ext(), probe.mime(), file.sizeBytes(), hash,
                    probe.width(), probe.height(), probe.durationMs(), file.mtime());
            if (thumb == null) {
                writeErrorRow(entry, existingId);
                return;
            }
            EntryId id = existingId != null ? withUpdate(existingId, entry) : sink.insert(entry);
            try {
                writeThumb(id.value(), thumb);
            } catch (IOException e) {
                sink.setStatus(id, Status.ERROR);
                errors.incrementAndGet();
                return;
            }
            fireHook(id, file);
            if (existingId != null) {
                updated.incrementAndGet();
            } else {
                added.incrementAndGet();
                // the ok-entry path's fully ingested row joins the follow-up's member
                // candidates — error rows (writeErrorRow) and thumb failures never do
                addedEntryIds.add(id.value());
            }
        } catch (RuntimeException e) {
            LOG.warn("ingest failed for {}: {}", display(file), e.toString(), e);
            errors.incrementAndGet();
        }
    }

    private void resurrect(ScannedFile file, EntryId id) {
        try {
            Path thumb = ThumbLayout.pathFor(appData, id.value());
            if (Files.exists(thumb)) {
                sink.setStatus(id, Status.OK);
                updated.incrementAndGet();
                return;
            }
            StoredLocation stored = sink.find(file.root().name(), file.relPath()).orElse(null);
            if (stored == null) {
                return; // vanished between scan and ingest
            }
            KindHandler handler = handlers.forKind(stored.kind()).orElse(null);
            if (handler == null) {
                sink.setStatus(id, Status.ERROR);
                errors.incrementAndGet();
                return;
            }
            try {
                ProbeResult probe = handler.probe(file.absPath());
                byte[] thumbBytes = handler.thumbnail(file.absPath(), probe);
                writeThumb(id.value(), thumbBytes);
                sink.setStatus(id, Status.OK);
                updated.incrementAndGet();
            } catch (IOException e) {
                sink.setStatus(id, Status.ERROR);
                errors.incrementAndGet();
            }
        } catch (RuntimeException e) {
            LOG.warn("resurrect failed for {} ({}): {}", display(file), id.value(), e.toString(), e);
            errors.incrementAndGet();
        }
    }

    private void fireHook(EntryId id, ScannedFile file) {
        try {
            postIngestHook.onIngested(id, file.absPath(), handlers.forExtension(file.ext())
                    .map(KindHandler::kind).orElseThrow());
        } catch (RuntimeException e) {
            // a hook must never fail the import (embeddings are best-effort, Review Focus 4)
            LOG.warn("post-ingest hook failed for {}: {}", display(file), e.toString());
        }
    }

    private void writeErrorRow(NewEntry entry, EntryId existingId) {
        EntryId id = existingId != null ? withUpdate(existingId, entry) : sink.insert(entry);
        sink.setStatus(id, Status.ERROR);
        errors.incrementAndGet();
    }

    private EntryId withUpdate(EntryId id, NewEntry entry) {
        sink.update(id, entry);
        return id;
    }

    private NewEntry errorEntry(ScannedFile file, Kind kind, String hash) {
        String mime = MimeTypes.forExt(file.ext()).orElse("application/octet-stream");
        return new NewEntry(kind, file.root().name(), file.relPath(), fileNameOf(file),
                file.ext(), mime, file.sizeBytes(), hash, null, null, null, file.mtime());
    }

    private void writeThumb(long id, byte[] bytes) throws IOException {
        Path target = ThumbLayout.pathFor(appData, id);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static String fileNameOf(ScannedFile file) {
        String rel = file.relPath();
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? rel : rel.substring(slash + 1);
    }

    private static String display(ScannedFile file) {
        return file.root().name() + "/" + file.relPath();
    }

    /** Unthrottled view of the in-flight file for tests (same value the stats carry). */
    String currentFile() {
        return current.get();
    }

    private void maybeReportProgress() {
        long now = System.currentTimeMillis();
        long last = lastProgressAt.get();
        if (now - last >= PROGRESS_THROTTLE_MS && lastProgressAt.compareAndSet(last, now)) {
            reporter.progress(new ImportStats((int) scanned.get(), (int) processed.get(), (int) added.get(),
                    (int) updated.get(), (int) missing.get(), (int) errors.get(), current.get()));
        }
    }

    private void submit(Runnable task) {
        pool.submit(task);
    }
}
