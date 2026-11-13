package io.github.nullotope.spicygallery.probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external tool and captures raw stdout (review I1):
 *
 * <ul>
 *   <li>stdout is drained on a daemon helper thread, so a hung process that never
 *       closes its stdout cannot wedge the caller — the timeout stays reachable;</li>
 *   <li>after {@code destroyForcibly()} the caller waits for the process to actually
 *       die before consulting {@code exitValue()} (it throws
 *       {@link IllegalThreadStateException} otherwise);</li>
 *   <li>the stderr temp log is always deleted, also on the failure paths.</li>
 * </ul>
 */
final class Processes {

    private Processes() {}

    static byte[] run(Path exe, List<String> args, String tool, long timeoutSeconds)
            throws IOException {
        Path errLog = Files.createTempFile("spicygallery-" + tool + "-", ".log");
        Process p = new ProcessBuilder(concat(List.of(exe.toString()), args))
                .redirectError(errLog.toFile())
                .start();

        // Drain stdout concurrently: if the process fills the pipe (or never closes
        // it), readAllBytes on the caller thread would block past any timeout.
        byte[][] stdout = new byte[1][];
        IOException[] readFailure = new IOException[1];
        Thread reader = new Thread(() -> {
            try (var in = p.getInputStream()) {
                stdout[0] = in.readAllBytes();
            } catch (IOException e) {
                readFailure[0] = new IOException(tool + " stdout read failed", e);
            }
        }, "spicygallery-" + tool + "-stdout");
        reader.setDaemon(true);
        reader.start();

        boolean finished = false;
        try {
            finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(tool + " interrupted", e);
        } finally {
            if (!finished) {
                p.destroyForcibly();
                try {
                    p.waitFor(5, TimeUnit.SECONDS); // exitValue() throws if not yet dead
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        String stderr;
        try {
            stderr = Files.readString(errLog).strip();
        } catch (IOException e) {
            stderr = "";
        } finally {
            try {
                Files.deleteIfExists(errLog);
            } catch (IOException ignored) {
                // best effort
            }
        }

        if (!finished) {
            throw new IOException(tool + " timed out after " + timeoutSeconds + "s"
                    + tail(stderr));
        }
        try {
            reader.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (readFailure[0] != null) {
            throw readFailure[0];
        }
        if (p.exitValue() != 0) {
            throw new IOException(tool + " failed (" + p.exitValue() + ")" + tail(stderr));
        }
        return stdout[0] == null ? new byte[0] : stdout[0];
    }

    private static String tail(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        String stripped = stderr.strip();
        return stripped.length() <= 400
                ? ": " + stripped
                : ": …" + stripped.substring(stripped.length() - 400);
    }

    private static List<String> concat(List<String> head, List<String> tail) {
        return new java.util.ArrayList<>(head.size() + tail.size()) {{
            addAll(head);
            addAll(tail);
        }};
    }
}
