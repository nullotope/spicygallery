package io.github.nullotope.spicygallery.server.importjob;

import io.github.nullotope.spicygallery.domain.ImportResult;
import io.github.nullotope.spicygallery.domain.ImportStats;
import io.github.nullotope.spicygallery.domain.LibraryRoot;
import io.github.nullotope.spicygallery.importer.ImportPipeline;
import io.github.nullotope.spicygallery.importer.ImportReporter;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.store.ImportRunRow;
import io.github.nullotope.spicygallery.server.store.ImportRunStore;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

/**
 * Run lifecycle (spec §6.1/§8): one active run, 409 on second start, 400 when every
 * configured root is inaccessible, run row as REST-reconcile source. The reporter
 * funnels the pipeline's throttled events into the run events seam immediately and
 * into the run row at most once per second.
 */
@Service
public class ImportService {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ImportService.class);

    private static final long TOUCH_THROTTLE_MS = 1000;

    private final SpicyGalleryProperties props;
    private final ImportRunStore runs;
    private final ImportRunEvents events;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Live stats of the active run (transient — merged into /api/imports/last). */
    private volatile ImportStats latestStats;

    private volatile PipelineFactory factory;
    private volatile ImportPoolFollowUp followUp;

    public ImportService(SpicyGalleryProperties props, ImportRunStore runs,
                         ImportRunEvents events, PipelineFactory factory, ImportPoolFollowUp followUp) {
        this.props = props;
        this.runs = runs;
        this.events = events;
        this.factory = factory;
        this.followUp = followUp;
    }

    /** Test/ops seam: swap the pipeline factory (per-test stubs, re-wiring). */
    void useFactory(PipelineFactory newFactory) {
        this.factory = newFactory;
    }

    /** Test/ops seam: swap the pool follow-up (per-test stubs, re-wiring). */
    void useFollowUp(ImportPoolFollowUp newFollowUp) {
        this.followUp = newFollowUp;
    }

    @PostConstruct
    public void recoverInterrupted() {
        runs.markInterrupted();
    }

    public boolean running() {
        return running.get();
    }

    /**
     * The active run's live stats (the pipeline throttles to ~4/s) — the polling
     * transport merges these into {@code GET /api/imports/last} while a run is active.
     * Empty when idle.
     */
    public java.util.Optional<ImportStats> activeStats() {
        return running.get() ? java.util.Optional.ofNullable(latestStats) : java.util.Optional.empty();
    }

    public synchronized long start() throws ImportInProgressException, AllRootsInaccessibleException {
        return start(false);
    }

    /**
     * Starts one run; {@code createPool} is the spec §3.8 follow-up flag. It lives in THIS
     * run's reporter (the running job's memory) — a restart mid-run loses it, exactly like
     * the run itself.
     */
    public synchronized long start(boolean createPool)
            throws ImportInProgressException, AllRootsInaccessibleException {
        if (running.get()) {
            long active = runs.last()
                    .filter(r -> "running".equals(r.status()))
                    .map(ImportRunRow::id)
                    .orElse(-1L);
            throw new ImportInProgressException(active);
        }
        List<String> inaccessible = props.libraries().stream()
                .filter(l -> !accessible(Path.of(l.root())))
                .map(SpicyGalleryProperties.Library::name)
                .toList();
        if (props.libraries().isEmpty() || inaccessible.size() == props.libraries().size()) {
            throw new AllRootsInaccessibleException(inaccessible);
        }

        long runId = runs.start();
        running.set(true);
        ImportRunReporter reporter = new ImportRunReporter(runId, createPool);
        executor.submit(() -> {
            try {
                factory.create(reporter).call();
            } catch (Throwable t) {
                // Throwable, not Exception: a StackOverflowError (e.g. a symlink cycle
                // slipping through) or OOM must still fail the run row and release the
                // running flag — otherwise the import button 409s until restart.
                LOG.error("import run {} failed", runId, t);
                reporter.fatal("import failed: " + t);
            }
        });
        return runId;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static boolean accessible(Path root) {
        return Files.isDirectory(root) && Files.isReadable(root);
    }

    /**
     * One run's funnel: events fan-out immediately (the pipeline already throttles to
     * ~4/s), run-row touch at most once per second, terminal events exactly once.
     */
    private final class ImportRunReporter implements ImportReporter {

        private final long runId;
        private final boolean createPool;
        private final AtomicBoolean fatalReported = new AtomicBoolean(false);
        private long lastTouchAt;

        ImportRunReporter(long runId, boolean createPool) {
            this.runId = runId;
            this.createPool = createPool;
        }

        @Override
        public void progress(ImportStats stats) {
            latestStats = stats;
            events.progress(stats);
            long now = System.currentTimeMillis();
            if (now - lastTouchAt >= TOUCH_THROTTLE_MS) {
                lastTouchAt = now;
                runs.touch(runId, stats);
            }
        }

        @Override
        public void done(ImportResult result) {
            latestStats = null;
            runs.complete(runId, result);
            draftPool(result);
            events.done(result);
            running.set(false);
        }

        /**
         * The import follow-up (spec §3.8): with this run's flag set and >= 1 added entry,
         * draft a pool of the run's added entries and record it on the run row — BEFORE the
         * done event, so the completion report's reconcile read sees {@code pool_id}.
         * Failure is WARN-only: the run is already completed and the import never depends
         * on the follow-up (0 added → nothing; failure → pool_id stays null).
         */
        private void draftPool(ImportResult result) {
            List<Long> addedIds = result.addedEntryIds();
            if (!createPool || addedIds == null || addedIds.isEmpty()) {
                return;
            }
            try {
                runs.setPoolId(runId, followUp.createPool(addedIds));
            } catch (RuntimeException e) {
                LOG.warn("import run {}: creating the follow-up pool failed; "
                        + "the run completes without it", runId, e);
            }
        }

        @Override
        public void failed(String message) {
            fatal(message);
        }

        void fatal(String message) {
            if (fatalReported.compareAndSet(false, true)) {
                latestStats = null;
                runs.fail(runId, message);
                events.failed(message);
                running.set(false);
            }
        }
    }
}
