package io.github.nullotope.spicygallery.probe;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.MimeTypes;
import io.github.nullotope.spicygallery.domain.ProbeResult;
import io.github.nullotope.spicygallery.json.SpicyGalleryJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Video kind handler (spec §6.3): ffprobe JSON for dimensions/duration, ffmpeg for the
 * thumbnail frame (fit inside 512×512, mjpeg quality ~3, frame at min(1 s, duration/2)).
 */
public final class VideoKindHandler implements KindHandler {

    /** Hard ceiling for one probe/thumbnail tool invocation (review I1). */
    static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final FfmpegTools tools;
    private final Set<String> extensions;
    private final long timeoutSeconds;

    public VideoKindHandler(FfmpegTools tools, Set<String> extensions) {
        this(tools, extensions, DEFAULT_TIMEOUT_SECONDS);
    }

    /** Timeout override — tests (and patient operators) only. */
    VideoKindHandler(FfmpegTools tools, Set<String> extensions, long timeoutSeconds) {
        this.tools = tools;
        this.extensions = Set.copyOf(extensions);
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public Kind kind() {
        return Kind.VIDEO;
    }

    @Override
    public Set<String> extensions() {
        return extensions;
    }

    @Override
    public ProbeResult probe(Path file) throws IOException {
        String json = new String(Processes.run(tools.ffprobe(), List.of(
                "-v", "quiet",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                file.toString()), "ffprobe", timeoutSeconds),
                java.nio.charset.StandardCharsets.UTF_8);
        FfprobeOut out;
        try {
            out = SpicyGalleryJson.mapper().readValue(json, FfprobeOut.class);
        } catch (IOException e) {
            throw new IOException("ffprobe output unparsable for " + file + ": " + e.getMessage(), e);
        }
        if (out == null || out.format() == null || out.format().duration() == null) {
            throw new IOException("ffprobe reports no duration (not a media file?): " + file);
        }
        FfStream video = out.streams() == null ? null : out.streams().stream()
                .filter(s -> "video".equals(s.codecType()))
                .findFirst()
                .orElse(null);
        if (video == null || video.width() == null || video.height() == null) {
            throw new IOException("ffprobe reports no video stream: " + file);
        }
        long durationMs = parseDurationMs(out.format().duration());
        String ext = extOf(file.getFileName().toString());
        String mime = MimeTypes.forExt(ext)
                .orElseThrow(() -> new IOException("no mime mapping for video extension '" + ext + "'"));
        return new ProbeResult(video.width(), video.height(), durationMs, mime);
    }

    /**
     * ffprobe duration ("seconds.fraction") → millis. ffprobe reports {@code "N/A"}
     * for some containers — that (and any other unparsable value) must surface as
     * {@link IOException} (→ error row), never as a raw NumberFormatException
     * (review I2: it produced row-less phantom errors).
     */
    static long parseDurationMs(String raw) throws IOException {
        if (raw == null || raw.isBlank() || "N/A".equalsIgnoreCase(raw.trim())) {
            throw new IOException("ffprobe reports no usable duration: " + raw);
        }
        try {
            double seconds = Double.parseDouble(raw.trim());
            if (!Double.isFinite(seconds) || seconds < 0) {
                throw new NumberFormatException("out of range: " + raw);
            }
            return Math.round(seconds * 1000);
        } catch (NumberFormatException e) {
            throw new IOException("ffprobe duration unparsable: " + raw, e);
        }
    }

    @Override
    public byte[] thumbnail(Path file, ProbeResult probe) throws IOException {
        double durationSeconds = probe.durationMs() == null ? 0 : probe.durationMs() / 1000.0;
        double at = Math.min(1.0, durationSeconds / 2);
        return Processes.run(tools.ffmpeg(), List.of(
                "-nostdin",
                "-ss", Double.toString(at),
                "-i", file.toString(),
                "-frames:v", "1",
                "-vf", "scale='min(512,iw)':'min(512,ih)':force_original_aspect_ratio=decrease",
                "-q:v", "3",
                "-f", "image2",
                "-vcodec", "mjpeg",
                "pipe:1"), "ffmpeg", timeoutSeconds);
    }

    private static String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FfprobeOut(List<FfStream> streams, FfFormat format) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FfStream(@JsonProperty("codec_type") String codecType, Integer width, Integer height) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FfFormat(String duration) {}
}
