package io.github.nullotope.spicygallery.server.importjob;

/** A run is already active — second {@code POST /api/imports} (spec §8: 409). */
public class ImportInProgressException extends Exception {

    private final long runId;

    public ImportInProgressException(long runId) {
        super("import already running (run " + runId + ")");
        this.runId = runId;
    }

    public long runId() {
        return runId;
    }
}
