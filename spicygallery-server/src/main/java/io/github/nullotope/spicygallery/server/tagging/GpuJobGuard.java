package io.github.nullotope.spicygallery.server.tagging;

import java.util.Optional;

/**
 * Serializes the two GPU jobs (spec §3.2): the embedding backfill and the tagging bulk
 * run must never overlap — both hammer the same provider upstream. One shared bean
 * (provided by the tagging wiring, injected into both runners); {@link #begin} throws
 * {@link SchedulingConflictException} naming the running job so the API can answer 409
 * with a useful message. Release discipline lives with the runners: every begin is
 * paired with an end in the worker's finally block — a throw, an abort or a cancelled
 * job can never leave the guard stuck (a stuck guard would permanently block the other
 * job type until restart).
 */
public class GpuJobGuard {

    private volatile String running;

    /** Acquires the GPU for {@code jobName}; fails when another job currently holds it. */
    public synchronized void begin(String jobName) {
        if (running != null) {
            throw new SchedulingConflictException(
                    "another GPU job is already running: " + running);
        }
        running = jobName;
    }

    /** Releases the GPU; a mismatched name (never expected) leaves the guard untouched. */
    public synchronized void end(String jobName) {
        if (jobName.equals(running)) {
            running = null;
        }
    }

    /** The currently running job's name, if any. */
    public Optional<String> runningJob() {
        return Optional.ofNullable(running);
    }
}
