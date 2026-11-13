package io.github.nullotope.spicygallery.server.tagging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Shared GPU-job exclusivity (spec §3.2): one guard bean serializes the embedding
 * backfill and the tagging bulk run — including the release discipline (a worker that
 * throws must still release the guard, or the other job type is locked out forever).
 */
class GpuJobGuardTest {

    private final GpuJobGuard guard = new GpuJobGuard();

    @Test
    void tagging_and_embedding_jobs_are_mutually_exclusive_and_the_error_names_the_running_job() {
        guard.begin("embedding-backfill");
        var ex = assertThrows(SchedulingConflictException.class, () -> guard.begin("tagging-bulk"));
        assertTrue(ex.getMessage().contains("embedding-backfill"));
        guard.end("embedding-backfill");
        guard.begin("tagging-bulk"); // ok now
    }

    @Test
    void running_job_reports_the_held_name_until_released() {
        assertTrue(guard.runningJob().isEmpty());
        guard.begin("tagging-bulk");
        assertEquals(Optional.of("tagging-bulk"), guard.runningJob());
        guard.end("tagging-bulk");
        assertTrue(guard.runningJob().isEmpty());
    }

    @Test
    void a_tagging_worker_that_throws_still_releases_the_guard() {
        // the scope source explodes and the hub explodes while reporting it — the failure
        // escapes the worker loop entirely; the finally-block release must still happen
        TaggingBackfill.PageSource boom = (afterId, limit) -> {
            throw new RuntimeException("scope exploded");
        };
        TaggingSseHub failingHub = new TaggingSseHub() {
            @Override
            public void failed(String message) {
                throw new IllegalStateException("hub exploded");
            }
        };
        TaggingService tagging = new TaggingService(null, null, null,
                new SpicyGalleryProperties.Tagging(true, null, null, null, null, null, null),
                null, null, Clock.systemUTC());
        TaggingBackfill backfill = new TaggingBackfill(tagging, null, ids -> Map.of(),
                boom, boom, failingHub, guard);
        backfill.start("query", null, false);
        backfill.joinForTests();

        assertTrue(backfill.status().error() != null || backfill.status().running() == false,
                "the job must not hang");
        assertTrue(guard.runningJob().isEmpty(), "a throwing worker must release the guard");
        guard.begin("embedding-backfill"); // the other job type can start again
        guard.end("embedding-backfill");
    }
}
