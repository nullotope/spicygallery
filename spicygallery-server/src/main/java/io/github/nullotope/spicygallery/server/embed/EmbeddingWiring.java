package io.github.nullotope.spicygallery.server.embed;

import io.github.nullotope.spicygallery.embed.FrameExtractor;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.store.EmbeddingStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Production wiring for the embed stack (spec §3.2): real extractor, system clock. */
@Configuration
public class EmbeddingWiring {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public EmbeddingService.VideoFrames videoFrames() {
        return FrameExtractor::extract;
    }

    @Bean
    public EmbeddingProvider embeddingProvider(SpicyGalleryProperties props) {
        var cfg = props.embedding();
        return new OpenAiEmbeddings(cfg.baseUrl(), cfg.model(), cfg.apiKey(),
                Duration.ofSeconds(cfg.timeoutSeconds()));
    }

    @Bean
    public EmbeddingService embeddingService(EmbeddingProvider provider, EmbeddingStore store,
                                             SpicyGalleryProperties props, EmbeddingService.VideoFrames frames,
                                             Clock clock) {
        return new EmbeddingService(provider, store, props.embedding(),
                Path.of(props.appData()), frames, clock);
    }

    /** Resolves entry ids to absolute library paths (root name → configured root dir). */
    @Bean
    public EmbeddingBackfill.EntryLookup entryLookup(SpicyGalleryProperties props,
                                                     io.github.nullotope.spicygallery.server.store.EntryQueries queries) {
        var roots = props.libraries().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SpicyGalleryProperties.Library::name,
                        l -> Path.of(l.root())));
        return ids -> {
            var out = new java.util.LinkedHashMap<Long, EmbeddingBackfill.EntryRef>();
            for (Long id : ids) {
                var entry = queries.byId(id).orElse(null);
                if (entry == null) {
                    continue;
                }
                Path base = roots.get(entry.rootName());
                if (base != null) {
                    out.put(id, new EmbeddingBackfill.EntryRef(id, entry.kind(),
                            base.resolve(entry.relPath())));
                }
            }
            return out;
        };
    }
}
