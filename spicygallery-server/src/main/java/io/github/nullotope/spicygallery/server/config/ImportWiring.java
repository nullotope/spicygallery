package io.github.nullotope.spicygallery.server.config;

import io.github.nullotope.spicygallery.probe.FfmpegTools;
import io.github.nullotope.spicygallery.probe.ImageKindHandler;
import io.github.nullotope.spicygallery.probe.KindHandler;
import io.github.nullotope.spicygallery.probe.KindHandlers;
import io.github.nullotope.spicygallery.probe.VideoKindHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the core probe stack from configuration. ffmpeg is OPTIONAL: without it,
 * only the image handler registers (video imports produce error rows;
 * /api/system/info reports found=false) and the context still loads (spec §6.3).
 */
@Configuration
public class ImportWiring {

    /** Resolved ffmpeg/ffprobe pair, or empty when unavailable. */
    public record Toolchain(Optional<FfmpegTools> tools) {
        public static final Toolchain NONE = new Toolchain(Optional.empty());
    }

    @Bean
    public Toolchain toolchain(SpicyGalleryProperties props) {
        props.validate();
        return new Toolchain(FfmpegTools.resolve(props.ffmpegPath(), props.ffprobePath()));
    }

    @Bean
    public KindHandlers kindHandlers(SpicyGalleryProperties props, Toolchain toolchain) {
        List<KindHandler> handlers = new ArrayList<>();
        handlers.add(new ImageKindHandler(Set.copyOf(props.imageExts())));
        toolchain.tools().ifPresent(tools ->
                handlers.add(new VideoKindHandler(tools, Set.copyOf(props.videoExts()))));
        return new KindHandlers(handlers);
    }

    @Bean
    public io.github.nullotope.spicygallery.server.importjob.PipelineFactory pipelineFactory(
            SpicyGalleryProperties props, KindHandlers handlers,
            io.github.nullotope.spicygallery.server.store.JdbcMediaStoreSink sink,
            io.github.nullotope.spicygallery.server.embed.EmbeddingService embeddingService) {
        List<io.github.nullotope.spicygallery.domain.LibraryRoot> roots = props.libraries().stream()
                .map(l -> new io.github.nullotope.spicygallery.domain.LibraryRoot(l.name(), Path.of(l.root())))
                .toList();
        return reporter -> new io.github.nullotope.spicygallery.importer.ImportPipeline(
                roots, handlers, sink, Path.of(props.appData()), props.workers(), reporter,
                (id, absPath, kind) -> embeddingService.embedEntry(id.value(), absPath, kind));
    }

    /**
     * No-op run-events fan-out: the SSE transport was removed (it held a browser socket
     * open per tab forever and starved the HTTP/1.1 6-connections-per-host pool) —
     * progress is polled via {@code GET /api/imports/last}. The seam stays for tests
     * and future subscribers.
     */
    @Bean
    public io.github.nullotope.spicygallery.server.importjob.ImportRunEvents importRunEvents() {
        return io.github.nullotope.spicygallery.server.importjob.ImportRunEvents.NOOP;
    }
}
