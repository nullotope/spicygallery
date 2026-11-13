package io.github.nullotope.spicygallery.server.importjob;

import io.github.nullotope.spicygallery.probe.FfmpegTools;
import io.github.nullotope.spicygallery.server.config.ImportWiring;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Data behind GET /api/system/info (spec §8): libraries, tool detection, config echo. */
@Service
public class SystemInfoService {

    public record ToolInfo(boolean found, String source, String version) {}

    public record LibraryInfo(String name, String root, boolean accessible) {}

    public record SystemInfo(List<LibraryInfo> libraries, ToolInfo ffmpeg, ToolInfo ffprobe,
                             String appData, int workers) {}

    private final SpicyGalleryProperties props;
    private final ImportWiring.Toolchain toolchain;

    public SystemInfoService(SpicyGalleryProperties props, ImportWiring.Toolchain toolchain) {
        this.props = props;
        this.toolchain = toolchain;
    }

    public SystemInfo info() {
        List<LibraryInfo> libraries = props.libraries().stream()
                .map(l -> new LibraryInfo(l.name(), l.root(), accessible(Path.of(l.root()))))
                .toList();
        return new SystemInfo(libraries,
                toolInfo(props.ffmpegPath(), toolchain.tools().map(FfmpegTools::ffmpeg)),
                toolInfo(props.ffprobePath(), toolchain.tools().map(FfmpegTools::ffprobe)),
                props.appData(), props.workers());
    }

    private static boolean accessible(Path root) {
        return Files.isDirectory(root) && Files.isReadable(root);
    }

    private ToolInfo toolInfo(String configured, Optional<Path> resolved) {
        if (resolved.isEmpty()) {
            return new ToolInfo(false, null, null);
        }
        // bare name (no separator) was resolved from PATH; anything else is a configured path
        String source = configured.contains("/") || configured.contains("\\")
                ? "configured" : "path";
        String version = FfmpegTools.versionLine(resolved.get()).orElse(null);
        return new ToolInfo(true, source, version);
    }
}
