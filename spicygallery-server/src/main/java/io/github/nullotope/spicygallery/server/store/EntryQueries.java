package io.github.nullotope.spicygallery.server.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.MediaEntry;
import io.github.nullotope.spicygallery.domain.Status;
import io.github.nullotope.spicygallery.natural.NaturalOrder;
import io.github.nullotope.spicygallery.server.web.MediaUrls;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read queries for the grid/detail/neighbors API (spec §8): keyset pagination over
 * {@code status='ok'} rows; the WHERE/ORDER plan comes from the compiled query
 * ({@link QuerySqlCompiler}, spec §3.4).
 */
@Repository
public class EntryQueries {

    public record EntryDto(long id, Kind kind, String fileName, Integer width, Integer height,
                           Long durationMs, String thumbUrl,
                           @com.fasterxml.jackson.annotation.JsonInclude(
                                   com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                           Double score) {

        public EntryDto(long id, Kind kind, String fileName, Integer width, Integer height,
                        Long durationMs, String thumbUrl) {
            this(id, kind, fileName, width, height, durationMs, thumbUrl, null);
        }
    }

    /** Sidebar facet (spec §7): tag usage count over the whole current filter. */
    public record FacetDto(String name, String category, long count) {}

    /**
     * One folder suggestion row (spec §3.3): a directory with >= 2 direct entries, its
     * entry count, and the folder's cover — its first natural-ordered entry (null only
     * defensively; a >= 2 group always has one). {@code dirPath} is {@code ''} for the
     * root top level.
     */
    public record FolderSuggestionRow(String rootName, String dirPath, long entryCount, EntryDto cover) {}

    /** rs + facets are optional on the wire (absent, not null); nextCursor keeps the
     *  slice-1 explicit-null contract. */
    public record Page(List<EntryDto> items, String nextCursor,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String rs,
                       @JsonInclude(JsonInclude.Include.NON_NULL) List<FacetDto> facets) {

        public Page(List<EntryDto> items, String nextCursor) {
            this(items, nextCursor, null, null);
        }
    }

    private final JdbcClient jdbc;

    public EntryQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Keyset pagination over the compiled plan. Cursor semantics depend on the order:
     * plain orders page on {@code id} (numeric cursor); {@code order:random} pages on the
     * row's {@code md5(id||seed)} hash (string cursor); semantic queries page on the
     * best cosine distance ("{@code <dist>:<id>}" cursor). Random ordering wins when
     * combined with a distance filter (both predicates compose).
     */
    @Transactional(readOnly = true)
    public Page page(CompiledQuery cq, String cursor, int limit) {
        boolean random = cq.randomSeed() != null;
        boolean semantic = cq.distanceExpr() != null;
        StringBuilder sql = new StringBuilder(
                "select id, kind, file_name, width, height, duration_ms, updated_at");
        if (random) {
            sql.append(", md5(id::text || ':' || :seed) as cursor_key");
        }
        if (semantic) {
            sql.append(", (").append(cq.distanceExpr()).append(") as entry_distance");
        }
        sql.append(" from media_entry where status = 'ok'");
        if (!cq.whereSql().isEmpty()) {
            sql.append(" and (").append(cq.whereSql()).append(")");
        }
        if (cursor != null) {
            if (semantic && !random) {
                // the whole distance predicate MUST be parenthesized: SQL binds AND
                // tighter than OR, and an un-parenthesized tie branch would escape
                // status='ok' and every query filter on page 2+
                sql.append(" and ((").append(cq.distanceExpr()).append(") > :cdist")
                        .append(" or ((").append(cq.distanceExpr()).append(") = :cdist and id > :ccid))");
            } else if (random) {
                sql.append(" and md5(id::text || ':' || :seed) > :cursor");
            } else {
                sql.append(" and id < :cursor");
            }
        }
        sql.append(" ").append(cq.orderBySql()).append(" limit :limit");

        var query = bind(cq, jdbc.sql(sql.toString()));
        if (cursor != null) {
            if (semantic && !random) {
                double[] distId = parseDistanceCursor(cursor);
                query = query.param("cdist", distId[0]).param("ccid", (long) distId[1]);
            } else if (random) {
                query = query.param("cursor", cursor);
            } else {
                query = query.param("cursor", parseCursor(cursor));
            }
        }
        query = query.param("limit", limit + 1);

        final List<EntryDto> allRows = new ArrayList<>();
        final List<String> allKeys = new ArrayList<>();
        query.query((rs, i) -> {
            Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
            long id = rs.getLong("id");
            Double score = semantic ? 1 - rs.getDouble("entry_distance") : null;
            allRows.add(new EntryDto(id,
                    Kind.fromWireName(rs.getString("kind")),
                    rs.getString("file_name"),
                    (Integer) rs.getObject("width"),
                    (Integer) rs.getObject("height"),
                    (Long) rs.getObject("duration_ms"),
                    MediaUrls.thumbUrl(id, updatedAt),
                    score));
            allKeys.add(random ? rs.getString("cursor_key")
                    : semantic ? rs.getDouble("entry_distance") + ":" + id
                    : String.valueOf(id));
            return null;
        }).list();

        boolean hasMore = allRows.size() > limit;
        List<EntryDto> rows = hasMore ? allRows.subList(0, limit) : allRows;
        String nextCursor = hasMore ? allKeys.get(limit - 1) : null;
        return new Page(List.copyOf(rows), nextCursor, cq.randomSeed(), null);
    }

    /** {@code "<dist>:<id>"} → {dist, id} (dist is a plain double string, id long). */
    private static double[] parseDistanceCursor(String cursor) {
        int idx = cursor.lastIndexOf(':');
        if (idx <= 0) {
            throw new IllegalArgumentException("invalid cursor: " + cursor);
        }
        try {
            return new double[] {Double.parseDouble(cursor.substring(0, idx)),
                    Long.parseLong(cursor.substring(idx + 1))};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid cursor: " + cursor);
        }
    }

    private static long parseCursor(String cursor) {
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid cursor: " + cursor);
        }
    }

    private static JdbcClient.StatementSpec bind(CompiledQuery cq, JdbcClient.StatementSpec query) {
        for (Map.Entry<String, Object> e : cq.params().entrySet()) {
            query = query.param(e.getKey(), e.getValue());
        }
        return query;
    }

    @Transactional(readOnly = true)
    public Optional<MediaEntry> byId(long id) {
        return jdbc.sql("""
                        select id, kind, status, root_name, rel_path, file_name, ext, mime,
                               size_bytes, content_hash, width, height, duration_ms,
                               media_mtime, imported_at, updated_at
                        from media_entry where id = ?
                        """)
                .param(id)
                .query((rs, i) -> new MediaEntry(
                        new EntryId(rs.getLong("id")),
                        Kind.fromWireName(rs.getString("kind")),
                        Status.fromWireName(rs.getString("status")),
                        rs.getString("root_name"),
                        rs.getString("rel_path"),
                        rs.getString("file_name"),
                        rs.getString("ext"),
                        rs.getString("mime"),
                        rs.getLong("size_bytes"),
                        rs.getString("content_hash"),
                        (Integer) rs.getObject("width"),
                        (Integer) rs.getObject("height"),
                        (Long) rs.getObject("duration_ms"),
                        rs.getTimestamp("media_mtime").toInstant(),
                        rs.getTimestamp("imported_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    /**
     * Related stitching (spec §5): the requested ids mapped to grid-shaped DTOs,
     * ordered by the given per-id scores (descending). Ids whose row disappeared in
     * the meantime are skipped — the strip never shows a stale tile.
     */
    @Transactional(readOnly = true)
    public List<EntryDto> byIds(List<Long> ids, List<EmbeddingStore.RelatedRow> scores) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> scoreBy = new java.util.HashMap<>();
        for (EmbeddingStore.RelatedRow r : scores) {
            scoreBy.put(r.entryId(), r.score());
        }
        var rows = jdbc.sql("""
                        select id, kind, file_name, width, height, duration_ms, updated_at
                        from media_entry where id in (:ids) and status = 'ok'
                        """)
                .param("ids", ids)
                .query((rs, i) -> new EntryDto(
                        rs.getLong("id"),
                        Kind.fromWireName(rs.getString("kind")),
                        rs.getString("file_name"),
                        (Integer) rs.getObject("width"),
                        (Integer) rs.getObject("height"),
                        (Long) rs.getObject("duration_ms"),
                        MediaUrls.thumbUrl(rs.getLong("id"),
                                rs.getTimestamp("updated_at").toInstant()),
                        scoreBy.get(rs.getLong("id"))))
                .list();
        rows.sort((a, b) -> Double.compare(b.score() == null ? -1 : b.score(),
                a.score() == null ? -1 : a.score()));
        return List.copyOf(rows);
    }

    /**
     * Direct entries of one directory (spec §3.3): {@code status='ok'} rows whose
     * {@code rel_path} lies exactly IN the directory — root top level ({@code ''}) means
     * rel_path without a '/'; otherwise rel_path = dir + '/' + file name. Natural order
     * (file name, id tiebreak) is applied in Java — SQL has no natural-order collation.
     * The folder-pool seeding ({@code PoolService.createFromFolder}) and the folders
     * suggestions read through this.
     */
    @Transactional(readOnly = true)
    public List<EntryDto> directEntries(String rootName, String dirPath) {
        boolean rootLevel = dirPath.isEmpty();
        StringBuilder sql = new StringBuilder("""
                select id, kind, file_name, width, height, duration_ms, updated_at
                from media_entry where status = 'ok' and root_name = :root and
                """);
        if (rootLevel) {
            sql.append("position('/' in rel_path) = 0");
        } else {
            // one slash after the directory = direct entry; a second one is a subdirectory
            sql.append("rel_path like :prefix escape '\\' and rel_path not like :deeper escape '\\'");
        }
        var query = jdbc.sql(sql.toString()).param("root", rootName);
        if (!rootLevel) {
            // the named params must exist in the SQL they bind to — the root-level branch has neither
            query = query.param("prefix", QuerySqlCompiler.likeEscape(dirPath) + "/%")
                    .param("deeper", QuerySqlCompiler.likeEscape(dirPath) + "/%/%");
        }
        List<EntryDto> rows = query.query(EntryQueries::gridEntry).list();
        rows.sort(EntryQueries::compareNaturalThenId);
        return List.copyOf(rows);
    }

    /**
     * Folder suggestions (spec §3.3) in ONE grouping pass: groups {@code status='ok'}
     * rows by {@code (root_name, directory)} — the directory being {@code rel_path} minus
     * its last segment ({@code ''} = root top level) — keeps groups with >= 2 DIRECT
     * entries, and retires directories that are the origin of an existing pool (the
     * list-side half of retirement; re-seeding a folder stays possible, Review Focus #3's
     * auto-dedupe lives in the create path). Each group's cover — its first natural-ordered
     * entry — is picked in Java (SQL has no natural-order collation), so the same statement
     * also returns the group's entry rows instead of a per-directory query. Result order:
     * count desc, then root/dir ascending as a deterministic tiebreak (the spec pins count
     * desc only).
     */
    @Transactional(readOnly = true)
    public List<FolderSuggestionRow> folderSuggestions() {
        record Row(String rootName, String dirPath, EntryDto entry) {}
        record Key(String rootName, String dirPath) {}
        List<Row> rows = jdbc.sql("""
                        with folders as (
                            select root_name,
                                   case when position('/' in rel_path) = 0 then ''
                                        else regexp_replace(rel_path, '/[^/]+$', '') end as dir_path,
                                   count(*) as entry_count
                            from media_entry
                            where status = 'ok'
                            group by root_name, dir_path
                            having count(*) >= 2
                        )
                        select f.root_name, f.dir_path,
                               m.id, m.kind, m.file_name, m.width, m.height, m.duration_ms, m.updated_at
                        from folders f
                        join media_entry m on m.status = 'ok' and m.root_name = f.root_name
                            and (case when position('/' in m.rel_path) = 0 then ''
                                 else regexp_replace(m.rel_path, '/[^/]+$', '') end) = f.dir_path
                        where not exists (select 1 from pool p
                                          where p.origin_root = f.root_name and p.origin_dir = f.dir_path)
                        """)
                .query((rs, i) -> new Row(rs.getString("root_name"), rs.getString("dir_path"),
                        gridEntry(rs, i)))
                .list();
        Map<Key, List<EntryDto>> entriesByFolder = new LinkedHashMap<>();
        for (Row r : rows) {
            entriesByFolder.computeIfAbsent(new Key(r.rootName(), r.dirPath()), k -> new ArrayList<>())
                    .add(r.entry());
        }
        return entriesByFolder.entrySet().stream()
                // the HAVING guarantees >= 2 rows per group, so the min is always present
                .map(e -> new FolderSuggestionRow(e.getKey().rootName(), e.getKey().dirPath(),
                        e.getValue().size(),
                        e.getValue().stream().min(EntryQueries::compareNaturalThenId).orElseThrow()))
                .sorted(Comparator.comparingLong(FolderSuggestionRow::entryCount).reversed()
                        .thenComparing(FolderSuggestionRow::rootName)
                        .thenComparing(FolderSuggestionRow::dirPath))
                .toList();
    }

    /** The grid-shaped entry DTO every entry-returning read maps rows into. */
    private static EntryDto gridEntry(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new EntryDto(rs.getLong("id"),
                Kind.fromWireName(rs.getString("kind")),
                rs.getString("file_name"),
                (Integer) rs.getObject("width"),
                (Integer) rs.getObject("height"),
                (Long) rs.getObject("duration_ms"),
                MediaUrls.thumbUrl(rs.getLong("id"), rs.getTimestamp("updated_at").toInstant()));
    }

    /** Natural filename order (spec §3.3) with the id tiebreak — direct entries and covers. */
    private static int compareNaturalThenId(EntryDto a, EntryDto b) {
        int cmp = NaturalOrder.compare(a.fileName(), b.fileName());
        return cmp != 0 ? cmp : Long.compare(a.id(), b.id());
    }

    /**
     * {prevId, nextId} under the same filters; null = absent on the wire. Directions
     * follow the GRID order (id DESC — spec §8 consistency with the client's
     * in-memory list): prev = the tile before the current one (higher id), next =
     * the tile after it (lower id).
     */
    @Transactional(readOnly = true)
    public Optional<long[]> neighbors(CompiledQuery cq, long id) {
        Optional<Long> prev = edgeId(cq, id, false); // grid-previous: next higher id
        Optional<Long> next = edgeId(cq, id, true);  // grid-next: next lower id
        if (prev.isEmpty() && next.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new long[] {prev.orElse(0L), next.orElse(0L)});
    }

    /**
     * Ascending keyset over entry ids for a compiled query — the bulk-tagging scope
     * sources (spec §3.2): the grid pages id-DESC for display, but a bulk run's afterId
     * cursor must always advance, so scopes page id ASC. Same compile path as the search
     * endpoint (the caller passes the CompiledQuery); {@code randomSeed}/order terms are
     * deliberately ignored.
     */
    @Transactional(readOnly = true)
    public List<Long> idsAfter(CompiledQuery cq, Long afterId, int limit) {
        StringBuilder sql = new StringBuilder("select id from media_entry where status = 'ok'");
        if (!cq.whereSql().isEmpty()) {
            sql.append(" and (").append(cq.whereSql()).append(")");
        }
        if (afterId != null) {
            sql.append(" and id > :after");
        }
        sql.append(" order by id asc limit :limit");
        var query = bind(cq, jdbc.sql(sql.toString()));
        if (afterId != null) {
            query = query.param("after", afterId);
        }
        return query.param("limit", limit).query(Long.class).list();
    }

    private Optional<Long> edgeId(CompiledQuery cq, long id, boolean below) {
        StringBuilder sql = new StringBuilder("""
                select id from media_entry where status = 'ok' and id %s :id
                """.formatted(below ? "<" : ">"));
        if (!cq.whereSql().isEmpty()) {
            sql.append(" and (").append(cq.whereSql()).append(")");
        }
        sql.append(below ? " order by id desc limit 1" : " order by id asc limit 1");

        var query = bind(cq, jdbc.sql(sql.toString())).param("id", id);
        return query.query(Long.class).optional();
    }
}
