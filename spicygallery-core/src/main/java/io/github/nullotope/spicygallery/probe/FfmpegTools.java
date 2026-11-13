package io.github.nullotope.spicygallery.probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Resolved ffmpeg/ffprobe pair (spec §6.3 ladder): configured path if it verifies, else
 * the bare name on PATH, else empty. A binary counts only if {@code <exe> -version}
 * succeeds and its output names the tool.
 */
public record FfmpegTools(Path ffmpeg, Path ffprobe) {

    public static Optional<FfmpegTools> resolve(String configuredFfmpeg, String configuredFfprobe) {
        Path ffmpeg = resolveOne(configuredFfmpeg, "ffmpeg");
        Path ffprobe = resolveOne(configuredFfprobe, "ffprobe");
        if (ffmpeg == null || ffprobe == null) {
            return Optional.empty();
        }
        return Optional.of(new FfmpegTools(ffmpeg, ffprobe));
    }

    private static Path resolveOne(String configured, String tool) {
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured);
            if (verifies(candidate, tool)) {
                return candidate;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir, tool);
                if (verifies(candidate, tool)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean verifies(Path exe, String tool) {
        return versionLine(exe)
                .map(line -> line.contains(tool))
                .orElse(false);
    }

    /** First line of {@code <exe> -version}, or empty when the binary does not answer. */
    public static Optional<String> versionLine(Path exe) {
        try {
            String output = new String(Processes.run(exe, List.of("-version"),
                    "version-check", 10), java.nio.charset.StandardCharsets.UTF_8);
            String first = output.isBlank() ? null : output.split("\n", 2)[0].strip();
            return Optional.ofNullable(first);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Human-readable version (first {@code -version} line), for /api/system/info. */
    public String describe(Path exe) {
        return versionLine(exe).orElse(null);
    }

    /** Public bridge over the package-private process runner (slice-3 embed package uses it). */
    public static byte[] runTool(Path exe, List<String> args, String tool, long timeoutSeconds)
            throws IOException {
        return Processes.run(exe, args, tool, timeoutSeconds);
    }
}
