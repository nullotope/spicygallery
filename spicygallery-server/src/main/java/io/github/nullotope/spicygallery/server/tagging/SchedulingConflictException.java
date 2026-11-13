package io.github.nullotope.spicygallery.server.tagging;

/**
 * A GPU job could not be scheduled because the other one is running (spec §3.2):
 * mapped to 409, with the message naming the running job.
 */
public final class SchedulingConflictException extends RuntimeException {

    public SchedulingConflictException(String message) {
        super(message);
    }
}
