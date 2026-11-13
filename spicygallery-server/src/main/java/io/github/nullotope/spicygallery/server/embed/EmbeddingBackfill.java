package io.github.nullotope.spicygallery.server.embed;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.server.store.EmbeddingStore;
import io.github.nullotope.spicygallery.server.tagging.GpuJobGuard;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Embedding backfill (spec §3.2): embeds every entry missing vectors for the currently
 * configured model (model mismatch counts — Review Focus 2), batched pages of 50,
 * resumable by construction (re-running continues where rows exist). Live progress is
 * part of the REST status ({@code BackfillStatus.current}) and read by polling
 * {@code GET /api/embeddings/backfill} — the SSE transport that used to push it was
 * removed (it held a browser socket open per tab forever and starved the HTTP/1.1
 * 6-connections-per-host pool). Holds the shared GpuJobGuard for the whole run (wired
 * into {@code start}/the worker's finally) so the tagging bulk run can never overlap it.
 */
@Service
public class EmbeddingBackfill {

    public record EntryRef(long id, Kind kind, Path absPath) {}

    /** Reconcile shape for {@code GET /api/embeddings/backfill}; {@code current} is the live detail. */
    public record BackfillStatus(boolean running, long done, long total, String error,
                                 String current) {}

    /** Entry rows for a page of ids (seam over EntryQueries; DB-free tests inject a fake). */
    @FunctionalInterface
    public interface EntryLookup {
        Map<Long, EntryRef> byIds(List<Long> ids);
    }

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingBackfill.class);
    private static final int PAGE = 50;

    private final EmbeddingService embeddings;
    private final EmbeddingStore store;
    private final EntryLookup lookup;
    private final GpuJobGuard guard;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "embedding-backfill");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running;
    private volatile long done;
    private volatile long total;
    private volatile String error;
    private volatile String current;

    public EmbeddingBackfill(EmbeddingService embeddings, EmbeddingStore store, EntryLookup lookup,
                             GpuJobGuard guard) {
        this.embeddings = embeddings;
        this.store = store;
        this.lookup = lookup;
        this.guard = guard;
    }

    public synchronized void start(boolean force) {
        if (running) {
            throw new IllegalStateException("backfill already running");
        }
        if (!embeddings.config().enabled()) {
            throw new EmbeddingUnavailableException("embedding provider disabled");
        }
        embeddings.resetBreaker(); // an explicit backfill IS the retry: user says try again
        // GPU-job exclusivity (spec §3.2): refuses while a tagging bulk run holds the guard
        guard.begin("embedding-backfill");
        try {
            running = true;
            done = 0;
            total = 0;
            error = null;
            current = null;
            if (force) {
                store.deleteAll();
            }
            // resume-aware accounting: entries that already carry vectors count as done so
            // a resumed run still reaches 100% (and force, having deleted all, starts at 0)
            total = store.countEntries();
            done = Math.min(store.countWithModel(embeddings.config().model()), total);
            executor.submit(() -> run());
        } catch (Error | RuntimeException e) {
            guard.end("embedding-backfill"); // the worker never started — release what begin took
            throw e;
        }
    }

    private void run() {
        String model = embeddings.config().model();
        long cursor = 0;
        try {
            while (true) {
                if (!running) {
                    return; // only self-clears on failure/finish; no external stop this slice
                }
                EmbeddingStore.MissingPage page = store.entriesMissing(model, cursor, PAGE);
                for (Long id : page.ids()) {
                    EntryRef ref = lookup.byIds(List.of(id)).get(id);
                    if (ref == null) {
                        // vanished (deleted mid-run) or an unresolvable library root —
                        // surface it: a silent skip lets backfill "complete" forever incomplete
                        LOG.warn("backfill cannot resolve entry {} (deleted or unknown root); skipping", id);
                        done++;
                        continue;
                    }
                    embeddings.embedEntry(id, ref.absPath(), ref.kind());
                    done++;
                    current = ref.absPath().getFileName().toString();
                }
                if (page.nextCursor() == null) {
                    break;
                }
                cursor = page.nextCursor();
            }
            error = null;
            LOG.info("embedding backfill complete: {} entries", done);
        } catch (Exception e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            LOG.warn("embedding backfill failed after {}: {}", done, error);
        } finally {
            running = false;
            guard.end("embedding-backfill"); // release even when a failure escapes the worker loop
        }
    }

    public BackfillStatus status() {
        return new BackfillStatus(running, done, total, error, current);
    }

    /** Test seam: wait for the single-threaded job to finish. */
    public void joinForTests() {
        while (running) {
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
