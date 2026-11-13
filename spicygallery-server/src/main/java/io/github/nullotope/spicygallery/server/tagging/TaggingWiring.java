package io.github.nullotope.spicygallery.server.tagging;

import io.github.nullotope.spicygallery.embed.FrameExtractor;
import io.github.nullotope.spicygallery.probe.FfmpegTools;
import io.github.nullotope.spicygallery.query.QueryNode;
import io.github.nullotope.spicygallery.query.QueryParser;
import io.github.nullotope.spicygallery.server.config.ImportWiring;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.store.CompiledQuery;
import io.github.nullotope.spicygallery.server.store.EntryQueries;
import io.github.nullotope.spicygallery.server.store.QuerySqlCompiler;
import io.github.nullotope.spicygallery.server.tags.TagService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production wiring for the tagging stack (spec §3.2): the real Hydra client, the
 * frames seam over the persisted {@code {appData}/frames/{id}} directories, the
 * service, the entry lookup, the untagged bulk scope, the SSE hub, and the shared
 * {@link GpuJobGuard} that serializes the GPU jobs across the embed and tagging
 * features (the single bean both runners hold).
 */
@Configuration
public class TaggingWiring {

    private static final Logger LOG = LoggerFactory.getLogger(TaggingWiring.class);

    /**
     * Fixed provider HTTP budget. {@code SpicyGalleryProperties.Tagging} deliberately has
     * no timeout-seconds field this slice; 60 s mirrors the embedding client's default
     * rather than inventing an unrequested config knob.
     */
    static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(60);

    /** One shared bean: the embedding backfill and the tagging bulk run mutually exclude. */
    @Bean
    public GpuJobGuard gpuJobGuard() {
        return new GpuJobGuard();
    }

    @Bean
    public TaggingClient taggingClient(SpicyGalleryProperties props) {
        return new TaggingClient(props.tagging().baseUrl(), CLIENT_TIMEOUT);
    }

    @Bean
    public TaggingSseHub taggingHub() {
        return new TaggingSseHub();
    }

    /**
     * Production frames seam (spec §3.2): the JPEGs the embedding pipeline persisted
     * under {@code {appData}/frames/{id}} are reused as-is; a video with no frames yet is
     extracted on demand via FrameExtractor (cap passed through from
     {@link TaggingService#MAX_FRAMES}). Images never reach this seam —
     TaggingService asks for frames only on videos. A video with no persisted frames AND
     no ffmpeg toolchain is a LOCAL failure ({@link IOException} — error-counted in bulk,
     422 on predict, the breaker is never involved): an empty answer would let a bulk run
     apply zero predictions and wholesale-drop the entry's AI tags (final review M-1).
     */
    @Bean
    public TaggingService.Frames taggingFrames(SpicyGalleryProperties props,
                                               ImportWiring.Toolchain toolchain) {
        Path appData = Path.of(props.appData());
        FfmpegTools tools = toolchain.tools().orElse(null);
        return (entryId, video, maxFrames) -> {
            Path dir = appData.resolve("frames").resolve(Long.toString(entryId));
            List<Path> persisted = listFrames(dir);
            if (!persisted.isEmpty()) {
                return persisted; // reuse the embedding pipeline's frames — never re-extract
            }
            if (tools == null) {
                // genuinely unclassifiable: fail locally instead of answering predict-empty
                LOG.warn("no ffmpeg — cannot extract frames for video entry {} ({})", entryId, video);
                throw new IOException("no ffmpeg on this system: video entry " + entryId
                        + " has no persisted frames and none can be extracted (" + video + ")");
            }
            FrameExtractor.extract(video, dir, tools, maxFrames);
            return listFrames(dir);
        };
    }

    /** {@code f<digits>.jpg} sorted by name (zero-padded ⇒ index order). */
    private static List<Path> listFrames(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(f -> f.getFileName().toString().matches("f\\d+\\.jpg"))
                    .sorted()
                    .toList();
        }
    }

    @Bean
    public TaggingService taggingService(TaggingClient client, TagService tags,
                                         SpicyGalleryProperties props, TaggingService.Frames frames) {
        return new TaggingService(client::classify, client::info, tags, props.tagging(),
                Files::readAllBytes, frames, Clock.systemUTC());
    }

    /** Resolves entry ids to absolute library paths (root name → configured root dir). */
    @Bean
    public TaggingBackfill.EntryLookup taggingEntryLookup(SpicyGalleryProperties props,
                                                          EntryQueries queries) {
        var roots = props.libraries().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SpicyGalleryProperties.Library::name,
                        l -> Path.of(l.root())));
        return ids -> {
            var out = new java.util.LinkedHashMap<Long, TaggingBackfill.EntryRef>();
            for (Long id : ids) {
                var entry = queries.byId(id).orElse(null);
                if (entry == null) {
                    continue;
                }
                Path base = roots.get(entry.rootName());
                if (base != null) {
                    out.put(id, new TaggingBackfill.EntryRef(id, entry.kind(),
                            base.resolve(entry.relPath())));
                }
            }
            return out;
        };
    }

    /** The untagged bulk scope: every 'ok' entry without any tag (spec §3.2). */
    @Bean
    public TaggingBackfill.PageSource untaggedPages(EntryQueries queries, TagService tags) {
        return queryPageSource(queries, tags, "is:untagged");
    }

    /**
     * Keyset-ascending id pages for a parsed query via the search compile path
     * (QueryParser → TagService expansions → QuerySqlCompiler, spec §3.4): the same
     * machinery the grid endpoint uses. A bulk run's afterId cursor must always advance,
     * so pages go id ASC — the grid's id-DESC order is a display concern, and
     * {@code order:random} makes no sense for a wholesale pass. Stateless: every page
     * derives its id window from (afterId, limit) alone.
     */
    static TaggingBackfill.PageSource queryPageSource(EntryQueries queries, TagService tags,
                                                      String q) {
        QueryNode ast = QueryParser.parse(q);
        CompiledQuery cq = QuerySqlCompiler.compile(ast, null, tags.expandFor(ast).byTerm(),
                null, null, null);
        return (afterId, limit) -> queries.idsAfter(cq, afterId, limit);
    }
}
