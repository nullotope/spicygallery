package io.github.nullotope.spicygallery.probe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.ProbeResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class VideoKindHandlerTest {

    static final Path FIX = Path.of("src/test/resources/fixtures");

    @Test
    @EnabledIf("ffprobeAvailable")
    void probes_tiny_mp4() throws IOException {
        FfmpegTools tools = FfmpegTools.resolve("ffmpeg", "ffprobe").orElseThrow();
        VideoKindHandler h = new VideoKindHandler(tools, Set.of("mp4", "webm", "mkv", "mov"));

        ProbeResult r = h.probe(FIX.resolve("tiny.mp4"));
        assertEquals(64, r.width());
        assertEquals(32, r.height());
        assertNotNull(r.durationMs());
        assertTrue(Math.abs(r.durationMs() - 600) < 60);
        assertEquals("video/mp4", r.mime());

        java.awt.image.BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(
                h.thumbnail(FIX.resolve("tiny.mp4"), r)));
        assertNotNull(thumb);
        assertTrue(Math.max(thumb.getWidth(), thumb.getHeight()) <= 512);

        assertTrue(tools.describe(tools.ffmpeg()).contains("ffmpeg version"));
    }

    static boolean ffprobeAvailable() {
        return FfmpegTools.resolve("ffmpeg", "ffprobe").isPresent();
    }

    @Test
    void missing_tools_fail_probe() {
        VideoKindHandler h = new VideoKindHandler(
                new FfmpegTools(Path.of("/nonexistent/ffmpeg"), Path.of("/nonexistent/ffprobe")),
                Set.of("mp4"));
        assertThrows(IOException.class, () -> h.probe(FIX.resolve("tiny.mp4")));
    }

    @Test
    void parse_duration_rejects_na_and_garbage() {
        // review I2: ffprobe reports "N/A" for some containers — a NumberFormatException
        // must not escape as a RuntimeException (it produced rows-less phantom errors)
        assertThrows(IOException.class, () -> VideoKindHandler.parseDurationMs("N/A"));
        assertThrows(IOException.class, () -> VideoKindHandler.parseDurationMs("n/a"));
        assertThrows(IOException.class, () -> VideoKindHandler.parseDurationMs("garbage"));
        assertThrows(IOException.class, () -> VideoKindHandler.parseDurationMs(null));
        assertThrows(IOException.class, () -> VideoKindHandler.parseDurationMs("  "));
        assertThrows(IOException.class, () -> VideoKindHandler.parseDurationMs("1.5.5"));
        assertThrows(IOException.class, () -> VideoKindHandler.parseDurationMs("1e999"));
        assertDoesNotThrow(() -> assertEquals(600L, VideoKindHandler.parseDurationMs("0.6")));
    }

    @Test
    void hung_ffprobe_times_out_instead_of_blocking_forever() throws Exception {
        // review I1: stdout is drained on a helper thread so the timeout is reachable
        // even when a hung process never closes its stdout (review's exact scenario)
        Path fakeProbe = Path.of("target/fake-probe.sh");
        Files.writeString(fakeProbe, "#!/bin/sh\nexec sleep 30\n");
        java.nio.file.attribute.PosixFileAttributeView perms = Files.getFileAttributeView(
                fakeProbe, java.nio.file.attribute.PosixFileAttributeView.class);
        perms.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));

        VideoKindHandler h = new VideoKindHandler(
                new FfmpegTools(Path.of("/nonexistent/ffmpeg"), fakeProbe),
                Set.of("mp4"), 1 /* second timeout */);
        long start = System.currentTimeMillis();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                assertThrows(IOException.class, () -> h.probe(FIX.resolve("tiny.mp4"))),
                "probe must fail fast on a hung tool, not block on stdout");
        assertTrue(System.currentTimeMillis() - start < 9_000,
                "timeout must fire after ~1s, not after the process exits");
    }
}
