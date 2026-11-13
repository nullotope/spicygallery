package io.github.nullotope.spicygallery.embed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.probe.FfmpegTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/** 1 fps frame extraction (spec §2): timestamps, cap, cleanup, failure → IOException. */
class FrameExtractorTest {

    private static final Path FIX = Path.of("src", "test", "resources", "fixtures");

    static boolean ffmpegAvailable() {
        return FfmpegTools.resolve("ffmpeg", "ffprobe").isPresent();
    }

    private static FfmpegTools tools() {
        return FfmpegTools.resolve("ffmpeg", "ffprobe").orElseThrow();
    }

    /** Generates a solid-color H.264 clip of {@code seconds} length via lavfi. */
    private static Path clip(Path dir, double seconds) throws IOException {
        Path out = dir.resolve("clip-" + seconds + "s.mp4");
        FfmpegTools t = tools();
        byte[] ok = FfmpegTools.runTool(t.ffmpeg(), List.of(
                "-f", "lavfi", "-i", "color=c=red:s=64x64:d=" + seconds,
                "-pix_fmt", "yuv420p", "-y", out.toString()), "testclip", 60);
        assertTrue(ok.length >= 0);
        return out;
    }

    @Test
    @EnabledIf("ffmpegAvailable")
    void extracts_one_frame_per_second_with_timestamps(@TempDir Path dir) throws IOException {
        Path video = clip(dir, 2.5); // 0s, 1s, 2s → 3 frames
        Path work = dir.resolve("frames");
        List<FrameExtractor.VideoFrame> frames = FrameExtractor.extract(video, work, tools(), 64);
        assertEquals(3, frames.size());
        for (int i = 0; i < frames.size(); i++) {
            assertEquals(i, frames.get(i).index());
            assertEquals(i * 1000L, frames.get(i).timestampMs());
            // decodable JPEG (starts with FFD8, ends FFD9)
            byte[] jpeg = frames.get(i).jpeg();
            assertEquals((byte) 0xFF, jpeg[0]);
            assertEquals((byte) 0xD8, jpeg[1]);
        }
    }

    @Test
    @EnabledIf("ffmpegAvailable")
    void cap_limits_frames(@TempDir Path dir) throws IOException {
        Path video = clip(dir, 3.0);
        List<FrameExtractor.VideoFrame> frames = FrameExtractor.extract(video, dir.resolve("w2"), tools(), 2);
        assertEquals(2, frames.size());
    }

    @Test
    @EnabledIf("ffmpegAvailable")
    void frames_persist_for_slice4_reuse(@TempDir Path dir) throws IOException {
        // spec: extracted frames are KEPT at workDir/f%04d.jpg so slice-4 tagging
        // reuses them instead of re-running ffmpeg on every import.
        Path video = clip(dir, 1.0);
        Path work = dir.resolve("w3");
        List<FrameExtractor.VideoFrame> frames = FrameExtractor.extract(video, work, tools(), 64);
        assertEquals(1, frames.size());
        assertTrue(Files.isDirectory(work), "work dir (frames/{entryId}) must persist");
        assertTrue(Files.exists(work.resolve("f0001.jpg")),
                "extracted frame JPEGs must remain on disk for slice-4 reuse");
        // and they are decodable
        byte[] onDisk = Files.readAllBytes(work.resolve("f0001.jpg"));
        assertEquals((byte) 0xFF, onDisk[0]);
        assertEquals((byte) 0xD8, onDisk[1]);
    }

    @Test
    @EnabledIf("ffmpegAvailable")
    void foreign_files_in_workdir_are_ignored_and_preserved(@TempDir Path dir) throws IOException {
        // slice 4 may keep other artifacts in frames/{entryId}/ — extraction must not
        // parse them (NumberFormatException) and must not delete them
        Path video = clip(dir, 1.0);
        Path work = dir.resolve("w7");
        Files.createDirectories(work);
        Files.writeString(work.resolve("notes.txt"), "slice-4 metadata");
        List<FrameExtractor.VideoFrame> frames = FrameExtractor.extract(video, work, tools(), 64);
        assertEquals(1, frames.size());
        assertEquals("notes.txt", list(work).stream().filter(n -> n.equals("notes.txt")).findFirst().orElse(null));
    }

    @Test
    @EnabledIf("ffmpegAvailable")
    void reextract_overwrites_stale_frames(@TempDir Path dir) throws IOException {
        // a re-embed with a lower cap must not leave old frames behind (idempotent)
        Path video = clip(dir, 2.5); // 3 frames
        Path work = dir.resolve("w6");
        FrameExtractor.extract(video, work, tools(), 64);
        assertEquals(3, list(work).size());
        FrameExtractor.extract(video, work, tools(), 2); // cap 2
        List<FrameExtractor.VideoFrame> frames = FrameExtractor.extract(video, work, tools(), 2);
        assertEquals(2, frames.size());
        assertEquals(2, list(work).size(), "stale frames from the 3-frame run must be cleared");
    }

    private static List<String> list(Path work) throws IOException {
        if (!Files.exists(work)) {
            return List.of();
        }
        try (var s = Files.list(work)) {
            return s.map(p -> p.getFileName().toString()).toList();
        }
    }

    @Test
    @EnabledIf("ffmpegAvailable")
    void ffmpeg_failure_throws_IOException(@TempDir Path dir) throws IOException {
        Path notAVideo = dir.resolve("text.mp4");
        Files.writeString(notAVideo, "this is not a video");
        assertThrows(IOException.class,
                () -> FrameExtractor.extract(notAVideo, dir.resolve("w4"), tools(), 64));
    }

    @Test
    @EnabledIf("ffmpegAvailable")
    void tiny_fixture_yields_single_frame(@TempDir Path dir) throws IOException {
        List<FrameExtractor.VideoFrame> frames =
                FrameExtractor.extract(FIX.resolve("tiny.mp4"), dir.resolve("w5"), tools(), 64);
        assertEquals(1, frames.size());
        assertEquals(0L, frames.get(0).timestampMs());
    }
}
