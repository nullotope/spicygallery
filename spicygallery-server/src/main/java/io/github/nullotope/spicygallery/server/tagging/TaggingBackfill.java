package io.github.nullotope.spicygallery.server.tagging;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bulk tagging runner (spec §3.2): predicts and applies the model's tags for every entry
 * in the started scope, keyset-paged 50 ids at a time. Per-entry failure handling mirrors
 * the embedding backfill with the tagging failure split: LOCAL failures — unreadable
 * source/frames, {@link IOException} — count as errors and the run continues (the breaker
 * is never involved); PROVIDER faults — {@link TaggingUnavailableException} — count the
 * entry as an error, then pause the job until the breaker closes (1 s polls) so the
 * remaining entries hit a healthy provider, aborting if the job is stopped. A paused-and-
 * skipped entry is picked up by the next run (resume-aware accounting), never retried
 * inside the run. All breaker interaction stays on the single worker thread — reads and
 * resets never race a concurrent tagging call (Task 5 forward note).
 *
 * <p>Resume-aware accounting: for the untagged scope — where every entry a previous run
 * completed has LEFT the page set (it now carries tags) — {@code done} starts at the count
 * of entries already carrying model-provenance tags ({@link JdbcTagStore#countWithProvenance}),
 * so a resumed run still reaches 100%. A query scope re-includes already-tagged entries by
 * design (wholesale re-apply, idempotent via {@code setEntryTagsPreservingUser}), so seeding
 * there would double-count and it starts at zero.
 *
 * <p>SSE progress mirrors {@code BackfillSseHub} with an errors field; REST status
 * reconciles via {@link #status()}. Not a {@code @Service}: the scope-dependent page
 * sources are supplied by the tagging wiring.
 *
 * <p>GPU-job exclusivity: {@link #start} takes the shared {@link GpuJobGuard} (the
 * wiring's single bean, shared with the embedding backfill) and the worker releases it
 * in its finally block — a throw, an abort or a stop can never leave the guard stuck.
 * The worker is a fresh per-run daemon thread, not a per-instance executor: the
 * controller builds a new instance per bulk POST, so a per-instance single-thread
 * executor would park one idle thread per run forever.
 */
public class TaggingBackfill {

    public record EntryRef(long id, Kind kind, Path absPath) {}

    public record RunStatus(boolean running, long done, long total, long errors, String error) {}

    /**
     * One page of the scope's entry ids, strictly after {@code afterId} (null = from the
     * beginning); applying the scope filter is the source's job (spec §3.2).
     */
    @FunctionalInterface
    public interface PageSource {
        List<Long> page(Long afterId, int limit);
    }

    /** Entry rows for a page of ids (seam over EntryQueries; DB-free tests inject a fake). */
    @FunctionalInterface
    public interface EntryLookup {
        Map<Long, EntryRef> byIds(List<Long> ids);
    }

    private static final Logger LOG = LoggerFactory.getLogger(TaggingBackfill.class);
    private static final int PAGE = 50;

    private final TaggingService tagging;
    private final JdbcTagStore store;
    private final EntryLookup lookup;
    private final PageSource queryPages;
    private final PageSource untaggedPages;
    private final TaggingSseHub hub;
    private final GpuJobGuard guard;

    private volatile boolean running;
    private volatile boolean jobDone = true; // join before any start is a no-op
    private volatile long done;
    private volatile long total;
    private volatile long errors;
    private volatile String error;

    public TaggingBackfill(TaggingService tagging, JdbcTagStore store, EntryLookup lookup,
                           PageSource queryPages, PageSource untaggedPages) {
        this(tagging, store, lookup, queryPages, untaggedPages, new TaggingSseHub(), new GpuJobGuard());
    }

    /** Test seam: a recording hub instead of the real SSE transport (own guard). */
    TaggingBackfill(TaggingService tagging, JdbcTagStore store, EntryLookup lookup,
                    PageSource queryPages, PageSource untaggedPages, TaggingSseHub hub) {
        this(tagging, store, lookup, queryPages, untaggedPages, hub, new GpuJobGuard());
    }

    /** Production seam: the SHARED GPU-job guard (mutual exclusion with the embedding backfill). */
    TaggingBackfill(TaggingService tagging, JdbcTagStore store, EntryLookup lookup,
                    PageSource queryPages, PageSource untaggedPages, TaggingSseHub hub, GpuJobGuard guard) {
        this.tagging = tagging;
        this.store = store;
        this.lookup = lookup;
        this.queryPages = queryPages;
        this.untaggedPages = untaggedPages;
        this.hub = hub;
        this.guard = guard;
    }

    /**
     * Starts the single bulk job; throws when one is already running. {@code scope} labels
     * the run report ("query" / "untagged"); {@code untagged} selects the page source and
     * enables the resume-aware done seed. An explicit bulk start IS the retry: the breaker
     * is reset first (mirror of the embedding backfill), so a disabled provider is the only
     * way to be refused here.
     */
    public synchronized void start(String scope, String q, boolean untagged) {
        if (running) {
            throw new IllegalStateException("tagging backfill already running");
        }
        tagging.resetBreaker(); // an explicit bulk run IS the retry: user says try again
        if (!tagging.breakerClosed()) {
            throw new TaggingUnavailableException("tagging provider disabled");
        }
        // GPU-job exclusivity (spec §3.2): refuses while the embedding backfill holds the guard
        guard.begin("tagging-bulk");
        try {
            running = true;
            jobDone = false;
            done = 0;
            total = 0;
            errors = 0;
            error = null;
            // per-run daemon thread, not a per-instance executor: the controller builds a
            // new instance per bulk POST, and a single-thread executor would park one idle
            // thread per run forever (final review I-1) — the runner is strictly single-job
            Thread worker = new Thread(() -> run(scope, q, untagged), "tagging-backfill");
            worker.setDaemon(true);
            worker.start();
        } catch (Error | RuntimeException e) {
            guard.end("tagging-bulk"); // the worker never started — release what begin took
            throw e;
        }
    }

    /** Stops the run at the next entry boundary (or immediately out of a breaker pause). */
    public void stop() {
        running = false;
    }

    private void run(String scope, String q, boolean untagged) {
        boolean stopped = false;
        try {
            List<Long> ids = drain(untagged ? untaggedPages : queryPages);
            // resume-aware accounting (untagged scope): entries a previous run completed have
            // left the page set — seed done with them so the run still reaches 100%
            long seed = untagged ? store.countWithProvenance(tagging.model()) : 0;
            done = seed;
            total = seed + ids.size();
            for (Long id : ids) {
                if (!running) {
                    stopped = true;
                    break;
                }
                EntryRef ref = lookup.byIds(List.of(id)).get(id);
                if (ref == null) {
                    // vanished (deleted mid-run) or an unresolvable library root —
                    // surface it: a silent skip lets backfill "complete" forever incomplete
                    LOG.warn("tagging backfill cannot resolve entry {} (deleted or unknown root); skipping", id);
                    done++;
                    hub.progress(done, total, "#" + id, errors);
                    continue;
                }
                try {
                    TaggingService.Prediction p = tagging.predict(id, ref.absPath(), ref.kind(), null);
                    tagging.apply(id, p.tags().stream().map(TaggingService.PredictedTag::name).toList(), p);
                    done++;
                    hub.progress(done, total, ref.absPath().getFileName().toString(), errors);
                } catch (TaggingUnavailableException e) {
                    errors++;
                    LOG.warn("tagging unavailable for entry {} ({}); waiting for the breaker", id, e.getMessage());
                    if (!awaitBreaker()) {
                        stopped = true;
                        break;
                    }
                } catch (IOException e) {
                    // local failure (unreadable media/frames): never the breaker's business
                    errors++;
                    LOG.warn("tagging backfill cannot read entry {}: {}", id, e.getMessage());
                }
            }
            if (stopped) {
                error = "tagging backfill stopped before completion";
                hub.failed(error);
                LOG.warn("tagging backfill ({}) stopped after {}/{} entries", scope, done, total);
            } else {
                error = null;
                hub.done(done, total, errors);
                LOG.info("tagging backfill ({}{}) complete: {} entries tagged, {} errors",
                        scope, q == null || q.isBlank() ? "" : ", q=" + q, done, errors);
            }
        } catch (Exception e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            hub.failed(error);
            LOG.warn("tagging backfill failed after {}: {}", done, error);
        } finally {
            running = false;
            jobDone = true; // release joinForTests even when stopped mid-pause
            guard.end("tagging-bulk"); // release even when a failure escapes the worker loop
        }
    }

    /**
     * Provider-fault pause: wait for the breaker to close (worker thread only), reporting
     * abort when the job was stopped while waiting.
     */
    private boolean awaitBreaker() {
        while (!tagging.breakerClosed()) {
            if (!running) {
                return false;
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Drains the whole scope up front (keyset pages of {@link #PAGE}) so total is exact. */
    private List<Long> drain(PageSource source) {
        List<Long> ids = new ArrayList<>();
        Long after = null;
        while (true) {
            List<Long> page = source.page(after, PAGE);
            if (page == null || page.isEmpty()) {
                return ids;
            }
            long last = page.get(page.size() - 1);
            if (after != null && last <= after) {
                throw new IllegalStateException("page source did not advance past " + after);
            }
            ids.addAll(page);
            after = last;
        }
    }

    public RunStatus status() {
        return new RunStatus(running, done, total, errors, error);
    }

    public TaggingSseHub hub() {
        return hub;
    }

    /**
     * Test seam: wait for the single-threaded job to actually finish — a stop() clears
     * {@code running} while the worker may still be inside a breaker pause, so the join
     * waits on the worker's own completion flag, not on {@code running}.
     */
    public void joinForTests() {
        while (!jobDone) {
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
