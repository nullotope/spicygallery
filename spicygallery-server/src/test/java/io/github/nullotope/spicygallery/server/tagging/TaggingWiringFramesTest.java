package io.github.nullotope.spicygallery.server.tagging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.nullotope.spicygallery.probe.FfmpegTools;
import io.github.nullotope.spicygallery.server.config.ImportWiring;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.tagging.TaggingService.Frames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The production frames seam (spec §3.2, wired in {@link TaggingWiring#taggingFrames}):
 * persisted frames are reused as-is, an empty directory with a toolchain present goes
 * through on-demand extraction, and — final review M-1 — a video with no frames AND no
 * ffmpeg is a LOCAL failure ({@link IOException}) rather than a predict-empty success
 * (which a bulk run would turn into a wholesale AI-tag drop). Plain unit test: the bean
 * method is a factory over {@code {appData}/frames/{id}}, no Spring context needed.
 */
class TaggingWiringFramesTest {

    private static Frames seam(Path appData, ImportWiring.Toolchain toolchain) {
        SpicyGalleryProperties props = new SpicyGalleryProperties(
                null, appData.toString(), null, null, null, null, null, null, null);
        return new TaggingWiring().taggingFrames(props, toolchain);
    }

    @Test
    void video_without_frames_and_without_ffmpeg_is_a_local_failure(@TempDir Path appData)
            throws IOException {
        // frames dir missing entirely …
        Frames frames = seam(appData, ImportWiring.Toolchain.NONE);
        var e1 = assertThrows(IOException.class,
                () -> frames.frames(1L, appData.resolve("clip.mp4"), TaggingService.MAX_FRAMES));
        assertEquals(true, e1.getMessage().contains("no ffmpeg"));
        // … and present but empty: still nothing classifiable
        Files.createDirectories(appData.resolve("frames").resolve("2"));
        assertThrows(IOException.class,
                () -> frames.frames(2L, appData.resolve("clip.mp4"), TaggingService.MAX_FRAMES));
    }

    @Test
    void persisted_frames_are_reused_without_a_toolchain(@TempDir Path appData) throws IOException {
        Path dir = appData.resolve("frames").resolve("7");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("f0002.jpg"), "second");
        Files.writeString(dir.resolve("f0001.jpg"), "first");
        Files.writeString(dir.resolve("notes.txt"), "foreign files never parse");

        List<Path> frames = seam(appData, ImportWiring.Toolchain.NONE)
                .frames(7L, appData.resolve("clip.mp4"), TaggingService.MAX_FRAMES);

        assertEquals(List.of(dir.resolve("f0001.jpg"), dir.resolve("f0002.jpg")), frames);
    }

    @Test
    void empty_frames_dir_with_a_toolchain_present_extracts(@TempDir Path dir) throws Exception {
        // a fake ffmpeg that answers the extractor's exact invocation shape: it writes
        // two numbered frames next to the -y output pattern and exits 0 (probe.FfmpegTools
        // record ctor skips version verification, so no real binary is needed)
        Path bin = dir.resolve("bin");
        Files.createDirectories(bin);
        Path ffmpeg = bin.resolve("ffmpeg");
        Files.writeString(ffmpeg, """
                #!/bin/sh
                out=""
                prev=""
                for a in "$@"; do
                  if [ "$prev" = "-y" ]; then out="$a"; fi
                  prev="$a"
                done
                printf 'fake-jpeg' > "$(dirname "$out")/f0001.jpg"
                printf 'fake-jpeg' > "$(dirname "$out")/f0002.jpg"
                """);
        Files.setPosixFilePermissions(ffmpeg, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path appData = dir.resolve("appdata");
        Files.createDirectories(appData);

        List<Path> frames = seam(appData, new ImportWiring.Toolchain(
                        Optional.of(new FfmpegTools(ffmpeg, ffmpeg))))
                .frames(9L, dir.resolve("clip.mp4"), TaggingService.MAX_FRAMES);

        assertEquals(List.of(appData.resolve("frames").resolve("9").resolve("f0001.jpg"),
                appData.resolve("frames").resolve("9").resolve("f0002.jpg")), frames);
    }
}
