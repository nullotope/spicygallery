package io.github.nullotope.spicygallery.server.importjob;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.github.nullotope.spicygallery.domain.ImportStats;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Review C2: one disconnecting client (container completes the emitter) must never
 * abort the import run — {@code SseEmitter.send} throws UNCHECKED
 * {@code IllegalStateException} on a completed emitter, which used to unwind through
 * the reporter into the pipeline and mark the whole run failed.
 */
class ImportSseHubTest {

    @Test
    void completed_emitter_is_dropped_without_aborting_broadcast() {
        ImportSseHub hub = new ImportSseHub();

        SseEmitter dead = new SseEmitter(0L);
        dead.complete(); // the state a disconnected client's emitter is left in
        hub.add(dead);
        SseEmitter live = new SseEmitter(0L);
        hub.add(live);

        // broadcast must survive the dead emitter and still reach the live one
        assertDoesNotThrow(() -> hub.progress(new ImportStats(1, 1, 0, 0, 0, 0, "alpha/x.png")));
        assertDoesNotThrow(() -> hub.progress(new ImportStats(2, 2, 0, 0, 0, 0, "alpha/y.png")));
        assertDoesNotThrow(() -> hub.done(null));
        assertDoesNotThrow(() -> hub.failed("boom"));
    }
}
