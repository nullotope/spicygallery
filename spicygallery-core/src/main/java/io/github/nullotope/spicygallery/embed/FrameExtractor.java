package io.github.nullotope.spicygallery.embed;

import io.github.nullotope.spicygallery.probe.FfmpegTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Video frame extraction (spec §2): 1 fps JPEG frames with timestamps — the semantic
 * content of a video for search (and slice-4 tagging). Frames are written to
 * {@code workDir} as {@code f%04d.jpg} and READ BACK but NOT deleted: the service
 * points workDir at {appData}/frames/{entryId} so slice 4 reuses the JPEGs without
 * re-running ffmpeg. A re-extract clears the directory first (lower cap, shorter
 * video — no stale frames). ffmpeg failure ⇒ IOException; the caller decides softness.
 */
public final class FrameExtractor {

    public record VideoFrame(int index, long timestampMs, byte[] jpeg) {}

    private FrameExtractor() {
    }

    public static List<VideoFrame> extract(Path video, Path workDir, FfmpegTools tools, int maxFrames)
            throws IOException {
        Files.createDirectories(workDir);
        // clear stale frames from a previous (possibly longer) extraction; only OUR
        // frame files (f<digits>.jpg) are touched — slice 4 may keep other artifacts here
        try (var s = Files.list(workDir)) {
            for (Path p : s.filter(f -> f.getFileName().toString().matches("f\\d+\\.jpg")).toList()) {
                Files.deleteIfExists(p);
            }
        }
        FfmpegTools.runTool(tools.ffmpeg(), List.of(
                        "-i", video.toString(),
                        "-vf", "fps=1",
                        "-frames:v", String.valueOf(maxFrames),
                        "-q:v", "4",
                        "-y", workDir.resolve("f%04d.jpg").toString()),
                "frameextract", 120);
        List<Path> files;
        try (var s = Files.list(workDir)) {
            // read back only ffmpeg's numbered output — foreign files must not parse
            files = s.filter(f -> f.getFileName().toString().matches("f\\d+\\.jpg"))
                    .sorted(Comparator.comparingInt(FrameExtractor::frameIndex)).toList();
        }
        List<VideoFrame> frames = new ArrayList<>(files.size());
        for (Path p : files) {
            int idx = frameIndex(p);
            frames.add(new VideoFrame(idx, idx * 1000L, Files.readAllBytes(p)));
        }
        // frames stay on disk (slice-4 reuse)
        return List.copyOf(frames);
    }

    /** {@code f%04d.jpg} → 1-based index, minus 1 for a 0-based frame index. */
    private static int frameIndex(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return Integer.parseInt(name.substring(1, dot)) - 1;
    }
}
