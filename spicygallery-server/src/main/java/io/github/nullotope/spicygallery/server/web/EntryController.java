package io.github.nullotope.spicygallery.server.web;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.MediaEntry;
import io.github.nullotope.spicygallery.query.Metatag;
import io.github.nullotope.spicygallery.query.QueryNode;
import io.github.nullotope.spicygallery.query.QueryParser;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.concepts.ConceptService;
import io.github.nullotope.spicygallery.server.embed.EmbeddingService;
import io.github.nullotope.spicygallery.server.store.CompiledQuery;
import io.github.nullotope.spicygallery.server.store.EntryQueries;
import io.github.nullotope.spicygallery.server.store.EmbeddingStore;
import io.github.nullotope.spicygallery.server.pools.PoolService;
import io.github.nullotope.spicygallery.server.store.QuerySqlCompiler;
import io.github.nullotope.spicygallery.server.tags.TagService;
import io.github.nullotope.spicygallery.server.web.dto.NeighborsDto;
import io.github.nullotope.spicygallery.server.web.dto.PutTagsRequest;
import io.github.nullotope.spicygallery.server.web.dto.TagDto;
import io.github.nullotope.spicygallery.thumb.ThumbLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read side of the API (spec §8): grid, detail, neighbors, media, runs, system info. */
@RestController
public class EntryController {

    private static final long CACHE_AGE_SECONDS = 31_536_000; // 1 year, immutable + ?v= busting
    private static final int FACET_LIMIT = 25;

    private final EntryQueries queries;
    private final TagService tags;
    private final SpicyGalleryProperties props;
    private final EmbeddingService embeddings;
    private final EmbeddingStore embeddingStore;
    private final ConceptService concepts;
    private final PoolService pools;

    public EntryController(EntryQueries queries, TagService tags, SpicyGalleryProperties props,
                           EmbeddingService embeddings, EmbeddingStore embeddingStore, ConceptService concepts,
                           PoolService pools) {
        this.queries = queries;
        this.tags = tags;
        this.props = props;
        this.embeddings = embeddings;
        this.embeddingStore = embeddingStore;
        this.concepts = concepts;
        this.pools = pools;
    }

    @GetMapping("/api/entries")
    public EntryQueries.Page list(@RequestParam Optional<String> q,
                                  @RequestParam Optional<String> kind,
                                  @RequestParam Optional<String> cursor,
                                  @RequestParam Optional<Integer> limit,
                                  @RequestParam Optional<String> rs) {
        Kind kindValue = kind.filter(k -> !k.isBlank()).map(Kind::fromWireName).orElse(null);
        String cursorValue = cursor.filter(c -> !c.isBlank()).orElse(null);
        int limitValue = limit.orElse(60);
        if (limitValue < 1 || limitValue > 200) {
            throw new IllegalArgumentException("limit out of range 1..200: " + limitValue);
        }
        String qValue = q.map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        CompiledQuery cq = compiled(qValue, kindValue, rs);
        EntryQueries.Page page = queries.page(cq, cursorValue, limitValue);
        if (cursorValue != null) {
            return page; // facets ride page 1 only; the client keeps them while scrolling
        }
        return new EntryQueries.Page(page.items(), page.nextCursor(), page.rs(),
                tags.facets(cq, tags.expandFor(QueryParser.parse(qValue)).positiveIds(), FACET_LIMIT));
    }

    /**
     * Parse → expand → compile (spec §3.4); the single place q turns into SQL.
     * {@code similar:} resolves the phrase through the embedding service first
     * (failFast → 503 when disabled/breaker open; provider IOException → 503 too);
     * {@code concept:} resolves the concept's stored anchor (unknown name → 400,
     * anchorless/stale → 409). Multiple similar:/concept: terms: the first wins
     * (the parser allows more; one vector per query); mixing similar: and concept:
     * is a 400, rejected at compile.
     */
    private CompiledQuery compiled(String q, Kind kind, Optional<String> rs) {
        QueryNode ast = QueryParser.parse(q);
        float[] queryVector = null;
        if (ast instanceof QueryNode.Query query) {
            for (QueryNode clause : query.clauses()) {
                if (clause instanceof QueryNode.MetaTerm m
                        && (m.metatag() == Metatag.SIMILAR || m.metatag() == Metatag.CONCEPT)) {
                    if (m.metatag() == Metatag.SIMILAR) {
                        embeddings.failFast();
                        queryVector = embeddings.textVector((String) m.value());
                    } else {
                        queryVector = concepts.anchorVectorForQuery((String) m.value());
                    }
                    break; // repeated terms: the first wins (same rule as similar:)
                }
            }
        }
        return QuerySqlCompiler.compile(ast, kind, tags.expandFor(ast).byTerm(), rs.orElse(null),
                queryVector, props.embedding().model());
    }

    /**
     * Semantic Related for the detail page (spec §5): best-frame cosine neighbors.
     * Entries without vectors return an empty list (the strip hides itself); an unknown
     * id is a plain 404. limit defaults to 8, capped at 24.
     */
    @GetMapping("/api/entries/{id}/related")
    public io.github.nullotope.spicygallery.server.web.dto.RelatedDto related(
            @PathVariable long id, @RequestParam(defaultValue = "8") int limit) {
        limit = Math.min(Math.max(limit, 1), 24); // default 8, capped at 24 (spec §5)
        queries.byId(id).orElseThrow(() -> new GlobalExceptionHandler.MissingEntryException(id));
        List<EmbeddingStore.RelatedRow> rows = embeddingStore.related(id, props.embedding().model(), limit);
        if (rows.isEmpty()) {
            return new io.github.nullotope.spicygallery.server.web.dto.RelatedDto(List.of());
        }
        return new io.github.nullotope.spicygallery.server.web.dto.RelatedDto(
                queries.byIds(rows.stream().map(EmbeddingStore.RelatedRow::entryId).toList(), rows));
    }

    @GetMapping("/api/entries/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        MediaEntry e = queries.byId(id).orElseThrow(() -> new GlobalExceptionHandler.MissingEntryException(id));
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", e.id().value());
        dto.put("kind", e.kind().wireName());
        dto.put("status", e.status().wireName());
        dto.put("root", e.rootName());
        dto.put("path", e.rootName() + "/" + e.relPath());
        dto.put("fileName", e.fileName());
        dto.put("ext", e.ext());
        dto.put("mime", e.mime());
        dto.put("sizeBytes", e.sizeBytes());
        dto.put("width", e.width());
        dto.put("height", e.height());
        dto.put("durationMs", e.durationMs());
        dto.put("importedAt", e.importedAt());
        dto.put("updatedAt", e.updatedAt());
        dto.put("fileUrl", MediaUrls.fileUrl(id, e.updatedAt()));
        dto.put("thumbUrl", MediaUrls.thumbUrl(id, e.updatedAt()));
        dto.put("tags", tags.tagsOf(id).stream()
                .map(t -> new TagDto(t.id(), t.name(), t.category(), t.postCount(), t.taggedBy()))
                .toList());
        return dto;
    }

    /**
     * Detail-page neighbors (spec §8; pool scope §3.9): without {@code pool}, prev/next
     * under the current query — the wire shape is unchanged. With {@code pool=<id>},
     * reading order is that pool's FLATTEN (the entry may sit at ANY depth): prev/next
     * plus the additive 1-based {@code position}/{@code total} keys; q/kind do not apply
     * there (the flatten IS the reading order). Not flatten-contained or unknown pool → 404.
     */
    @GetMapping("/api/entries/{id}/neighbors")
    public NeighborsDto neighbors(@PathVariable long id,
                                  @RequestParam Optional<String> q,
                                  @RequestParam Optional<String> kind,
                                  @RequestParam Optional<Long> pool) {
        queries.byId(id).orElseThrow(() -> new GlobalExceptionHandler.MissingEntryException(id));
        Long poolId = pool.orElse(null);
        if (poolId != null) {
            return pools.poolNeighbors(poolId, id)
                    .map(n -> new NeighborsDto(n.prevId(), n.nextId(), n.position(), n.total()))
                    .orElseThrow(() -> new GlobalExceptionHandler.EntryNotInPoolException(id, poolId));
        }
        Kind kindValue = kind.filter(k -> !k.isBlank()).map(Kind::fromWireName).orElse(null);
        String qValue = q.map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        Optional<long[]> neighbors = queries.neighbors(compiled(qValue, kindValue, Optional.empty()), id);
        return neighbors
                .map(n -> new NeighborsDto(n[0] == 0 ? null : n[0], n[1] == 0 ? null : n[1]))
                .orElse(new NeighborsDto(null, null));
    }

    /** Full tag replacement (spec §7): alias resolve → create → implication closure → diff, atomically. */
    @org.springframework.web.bind.annotation.PutMapping("/api/entries/{id}/tags")
    public List<TagDto> putTags(@PathVariable long id,
                                @org.springframework.web.bind.annotation.RequestBody PutTagsRequest request) {
        queries.byId(id).orElseThrow(() -> new GlobalExceptionHandler.MissingEntryException(id));
        List<TagService.TagInput> inputs = request.tags().stream()
                .map(t -> new TagService.TagInput(t.name(),
                        t.category() == null || t.category().isBlank()
                                ? null
                                : io.github.nullotope.spicygallery.domain.TagCategory.fromWireName(t.category())))
                .toList();
        return tags.setEntryTags(id, inputs).stream()
                .map(r -> new TagDto(r.id(), r.name(), r.category(), r.postCount(), r.taggedBy()))
                .toList();
    }

    @GetMapping("/api/entries/{id}/file")
    public ResponseEntity<FileSystemResource> file(@PathVariable long id) {
        MediaEntry e = requireServable(id);
        Path libraryRoot = libraryRoot(e.rootName());
        // belt against a stored rel_path that escapes the library (e.g. planted via
        // a backslash-named file or DB tampering): normalize + confine (review I5)
        Path file = libraryRoot.resolve(e.relPath()).normalize();
        if (!file.startsWith(libraryRoot) || !Files.isReadable(file)) {
            throw new GlobalExceptionHandler.MissingEntryException(id);
        }
        FileSystemResource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(e.fileName()))
                .cacheControl(org.springframework.http.CacheControl.maxAge(
                                java.time.Duration.ofSeconds(CACHE_AGE_SECONDS)).cachePrivate().immutable())
                .eTag(e.contentHash())
                .contentLength(e.sizeBytes())
                .contentType(MediaType.parseMediaType(e.mime()))
                .body(resource);
    }

    /** RFC 6266 inline disposition with a properly encoded filename. */
    private static String contentDisposition(String fileName) {
        return org.springframework.http.ContentDisposition.inline()
                .filename(fileName, java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    @GetMapping("/api/entries/{id}/thumb")
    public ResponseEntity<FileSystemResource> thumb(@PathVariable long id) {
        MediaEntry e = queries.byId(id)
                .orElseThrow(() -> new GlobalExceptionHandler.MissingEntryException(id));
        Path thumb = ThumbLayout.pathFor(Path.of(props.appData()), id);
        if (!Files.isReadable(thumb)) {
            throw new GlobalExceptionHandler.MissingEntryException(id);
        }
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.maxAge(
                                java.time.Duration.ofSeconds(CACHE_AGE_SECONDS)).cachePrivate().immutable())
                .contentLength(thumb.toFile().length())
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(thumb));
    }

    private MediaEntry requireServable(long id) {
        MediaEntry e = queries.byId(id)
                .orElseThrow(() -> new GlobalExceptionHandler.MissingEntryException(id));
        if (e.status() == io.github.nullotope.spicygallery.domain.Status.MISSING) {
            throw new GlobalExceptionHandler.MissingEntryException(id);
        }
        return e;
    }

    private Path libraryRoot(String name) {
        return props.libraries().stream()
                .filter(l -> l.name().equals(name))
                .map(l -> Path.of(l.root()))
                .findFirst()
                .orElseThrow(() -> new GlobalExceptionHandler.MissingEntryException(0L));
    }
}
