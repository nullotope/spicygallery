package io.github.nullotope.spicygallery.server.store;

import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.query.ImplicationGraph;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tag persistence (spec §4 schema): canonical tags, name→tag aliases, implications,
 * entry-tag links, and the keyset-paginated /tags listing. All tag-adjacent reads that
 * don't need business rules live here; {@code TagService} owns the write semantics.
 */
@Repository
public class JdbcTagStore {

    /**
     * One tag row. {@code taggedBy} is the media_tag provenance of this tag's link to an
     * entry (spec §4); it is populated ONLY by {@link #tagsOf} (the entry-context read) and
     * is {@code null} everywhere else (the tag table itself has no provenance).
     */
    public record TagRow(long id, String name, String category, int postCount, Instant createdAt,
                         String taggedBy) {}

    public record Expansion(List<Long> ids, boolean truncated) {}

    public record ImplicationRow(String antecedent, String consequent) {}

    public record AliasRow(String antecedent, String target) {}

    public enum TagSort { NAME, COUNT, NEW }

    private final JdbcClient jdbc;

    public JdbcTagStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- tags ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<TagRow> byName(String name) {
        return jdbc.sql("select id, name, category, post_count, created_at from tag where name = :name")
                .param("name", name)
                .query((rs, i) -> row(rs))
                .optional();
    }

    @Transactional
    public TagRow create(String name, TagCategory category) {
        return jdbc.sql("""
                        insert into tag (name, category) values (:name, :category)
                        returning id, name, category, post_count, created_at
                        """)
                .param("name", name)
                .param("category", category.wireName())
                .query((rs, i) -> row(rs))
                .single();
    }

    /** The entry's tags with per-row provenance (media_tag.tagged_by, spec §4). */
    @Transactional(readOnly = true)
    public List<TagRow> tagsOf(long entryId) {
        return jdbc.sql("""
                        select t.id, t.name, t.category, t.post_count, t.created_at, mt.tagged_by
                        from media_tag mt join tag t on t.id = mt.tag_id
                        where mt.media_entry_id = :id
                        order by t.category, t.name
                        """)
                .param("id", entryId)
                .query((rs, i) -> new TagRow(rs.getLong("id"), rs.getString("name"),
                        rs.getString("category"), rs.getInt("post_count"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("tagged_by")))
                .list();
    }

    @Transactional
    public void bumpPostCount(long tagId, int delta) {
        jdbc.sql("update tag set post_count = post_count + :delta where id = :id")
                .param("delta", delta)
                .param("id", tagId)
                .update();
    }

    // --- entry-tag links ----------------------------------------------------

    @Transactional
    public void tagEntry(long entryId, long tagId) {
        tagEntry(entryId, tagId, "user");
    }

    /**
     * Links a tag to an entry with provenance. A conflicting PK row has its tagged_by
     * rewritten (tagged_at keeps its original value) — never a duplicate row.
     */
    @Transactional
    public void tagEntry(long entryId, long tagId, String taggedBy) {
        jdbc.sql("""
                        insert into media_tag (media_entry_id, tag_id, tagged_by)
                        values (:e, :t, :by)
                        on conflict (media_entry_id, tag_id) do update set tagged_by = excluded.tagged_by
                        """)
                .param("e", entryId)
                .param("t", tagId)
                .param("by", taggedBy)
                .update();
    }

    @Transactional
    public void untagEntry(long entryId, long tagId) {
        jdbc.sql("delete from media_tag where media_entry_id = :e and tag_id = :t")
                .param("e", entryId)
                .param("t", tagId)
                .update();
    }

    @Transactional(readOnly = true)
    public Set<Long> tagIdsOf(long entryId) {
        return new LinkedHashSet<>(jdbc.sql("select tag_id from media_tag where media_entry_id = :id")
                .param("id", entryId)
                .query(Long.class)
                .list());
    }

    /** Tag rows for the given ids (order undefined) — the pool service's TagDto assembly. */
    @Transactional(readOnly = true)
    public List<TagRow> byIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select id, name, category, post_count, created_at from tag where id in (:ids)")
                .param("ids", ids)
                .query((rs, i) -> row(rs))
                .list();
    }

    /**
     * Entry → its tag ids, bulk (the pool service's flattened-content-tag seed, spec §3.7).
     * Entries without rows are absent from the map; per-entry sets are tag_id ascending.
     */
    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> tagIdsByEntry(Collection<Long> entryIds) {
        Map<Long, Set<Long>> byEntry = new LinkedHashMap<>();
        if (entryIds.isEmpty()) {
            return byEntry;
        }
        jdbc.sql("""
                        select media_entry_id, tag_id from media_tag
                        where media_entry_id in (:ids) order by media_entry_id, tag_id
                        """)
                .param("ids", entryIds)
                .query((rs, i) -> {
                    byEntry.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>()).add(rs.getLong(2));
                    return null;
                })
                .list();
        return byEntry;
    }

    /** Provenance per linked tag (spec §4 media_tag.tagged_by): 'user' or e.g. 'hydra-3.5'. */
    @Transactional(readOnly = true)
    public Map<Long, String> taggedByOf(long entryId) {
        Map<Long, String> byTag = new LinkedHashMap<>();
        jdbc.sql("select tag_id, tagged_by from media_tag where media_entry_id = :id")
                .param("id", entryId)
                .query((rs, i) -> {
                    byTag.put(rs.getLong("tag_id"), rs.getString("tagged_by"));
                    return null;
                })
                .list();
        return byTag;
    }

    /**
     * Entries (distinct) carrying at least one tag row provenance-stamped {@code taggedBy}
     * (e.g. 'hydra-3.5') — the bulk runner's resume-aware done seed (spec §3.2), the
     * tag-side mirror of {@link EmbeddingStore#countWithModel}. Rows stamped otherwise
     * (notably 'user') never count.
     */
    @Transactional(readOnly = true)
    public long countWithProvenance(String taggedBy) {
        return jdbc.sql("select count(distinct media_entry_id) from media_tag where tagged_by = :by")
                .param("by", taggedBy)
                .query(Long.class).single();
    }

    // --- aliases ------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<TagRow> aliasTarget(String aliasName) {
        return jdbc.sql("""
                        select t.id, t.name, t.category, t.post_count, t.created_at
                        from tag_alias a join tag t on t.id = a.tag_id
                        where a.antecedent_name = :name
                        """)
                .param("name", aliasName)
                .query((rs, i) -> row(rs))
                .optional();
    }

    @Transactional
    public void putAlias(String aliasName, long tagId) {
        jdbc.sql("delete from tag_alias where antecedent_name = :name").param("name", aliasName).update();
        jdbc.sql("insert into tag_alias (antecedent_name, tag_id) values (:name, :tag)")
                .param("name", aliasName)
                .param("tag", tagId)
                .update();
    }

    @Transactional
    public void removeAlias(String aliasName) {
        jdbc.sql("delete from tag_alias where antecedent_name = :name").param("name", aliasName).update();
    }

    @Transactional(readOnly = true)
    public List<AliasRow> listAliases() {
        return jdbc.sql("""
                        select a.antecedent_name as antecedent, t.name as target
                        from tag_alias a join tag t on t.id = a.tag_id
                        order by a.antecedent_name
                        """)
                .query((rs, i) -> new AliasRow(rs.getString("antecedent"), rs.getString("target")))
                .list();
    }

    // --- implications ---------------------------------------------------------

    @Transactional
    public void addImplication(long antecedentId, long consequentId) {
        jdbc.sql("""
                        insert into tag_implication (antecedent_tag_id, consequent_tag_id)
                        values (:a, :c) on conflict do nothing
                        """)
                .param("a", antecedentId)
                .param("c", consequentId)
                .update();
    }

    @Transactional
    public void removeImplication(long antecedentId, long consequentId) {
        jdbc.sql("delete from tag_implication where antecedent_tag_id = :a and consequent_tag_id = :c")
                .param("a", antecedentId)
                .param("c", consequentId)
                .update();
    }

    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> allImplicationsEdges() {
        Map<Long, Set<Long>> edges = new LinkedHashMap<>();
        jdbc.sql("select antecedent_tag_id, consequent_tag_id from tag_implication")
                .query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .list()
                .forEach(pair -> edges.computeIfAbsent(pair[0], k -> new LinkedHashSet<>()).add(pair[1]));
        return edges;
    }

    /** Small table — load edges and walk the graph instead of a recursive CTE. */
    @Transactional(readOnly = true)
    public boolean implicationPathExists(long from, long to) {
        return ImplicationGraph.reachable(from, to, allImplicationsEdges());
    }

    @Transactional(readOnly = true)
    public List<ImplicationRow> listImplications() {
        return jdbc.sql("""
                        select a.name as antecedent, c.name as consequent
                        from tag_implication ti
                        join tag a on a.id = ti.antecedent_tag_id
                        join tag c on c.id = ti.consequent_tag_id
                        order by a.name, c.name
                        """)
                .query((rs, i) -> new ImplicationRow(rs.getString("antecedent"), rs.getString("consequent")))
                .list();
    }

    // --- expansion ------------------------------------------------------------

    /**
     * Canonical tag IDs whose name matches the (already escaped) ILIKE pattern, unioned with
     * canonicals reachable through a matching alias (spec §3.4). Truncated past the cap.
     */
    @Transactional(readOnly = true)
    public Expansion expand(String escapedPattern, TagCategory category, int cap) {
        String sql = """
                select t.id from tag t
                where t.name ilike :pattern escape '\\'
                   or t.id in (select a.tag_id from tag_alias a where a.antecedent_name ilike :pattern escape '\\')
                """;
        if (category != null) {
            sql = "select t.id from tag t where t.category = :category and (t.name ilike :pattern escape '\\'"
                    + " or t.id in (select a.tag_id from tag_alias a"
                    + " where a.antecedent_name ilike :pattern escape '\\'))";
        }
        var query = jdbc.sql(sql + " order by t.id limit :limit")
                .param("pattern", escapedPattern)
                .param("limit", cap + 1);
        if (category != null) {
            query = query.param("category", category.wireName());
        }
        List<Long> ids = query.query(Long.class).list();
        if (ids.size() > cap) {
            return new Expansion(ids.subList(0, cap), true);
        }
        return new Expansion(ids, false);
    }

    // --- autocomplete -----------------------------------------------------------

    public record AutocompleteHit(String name, String category, int postCount, String aliasOf) {}

    /**
     * Tiered suggestion query (spec §7): prefix matches first (ordered by name), then
     * substring matches, then alias names mapping to canonicals. Total capped at limit.
     */
    @Transactional(readOnly = true)
    public List<AutocompleteHit> autocomplete(String q, int limit) {
        String escaped = QuerySqlCompiler.likeEscape(q.toLowerCase(java.util.Locale.ROOT));
        String prefix = escaped + "%";
        String contains = "%" + escaped + "%";
        List<AutocompleteHit> hits = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        jdbc.sql("""
                        select name, category, post_count from tag
                        where name ilike :prefix escape '\\' order by name limit :limit
                        """)
                .param("prefix", prefix)
                .param("limit", limit)
                .query((rs, i) -> new AutocompleteHit(rs.getString("name"), rs.getString("category"),
                        rs.getInt("post_count"), null))
                .list()
                .forEach(h -> {
                    if (hits.size() < limit && seen.add(h.name())) {
                        hits.add(h);
                    }
                });
        jdbc.sql("""
                        select name, category, post_count from tag
                        where name ilike :contains escape '\\' order by name limit :limit
                        """)
                .param("contains", contains)
                .param("limit", limit)
                .query((rs, i) -> new AutocompleteHit(rs.getString("name"), rs.getString("category"),
                        rs.getInt("post_count"), null))
                .list()
                .forEach(h -> {
                    if (hits.size() < limit && seen.add(h.name())) {
                        hits.add(h);
                    }
                });
        jdbc.sql("""
                        select a.antecedent_name as name, t.category, t.post_count, t.name as alias_of
                        from tag_alias a join tag t on t.id = a.tag_id
                        where a.antecedent_name ilike :contains escape '\\'
                        order by a.antecedent_name limit :limit
                        """)
                .param("contains", contains)
                .param("limit", limit)
                .query((rs, i) -> new AutocompleteHit(rs.getString("name"), rs.getString("category"),
                        rs.getInt("post_count"), rs.getString("alias_of")))
                .list()
                .forEach(h -> {
                    if (hits.size() < limit && seen.add(h.name())) {
                        hits.add(h);
                    }
                });
        return hits;
    }

    // --- facets -----------------------------------------------------------------

    /**
     * Tag usage counts over the WHOLE current filter (spec §7 tagFacets): top {@code limit}
     * by count, excluding tags already positively included in the query. Reuses the compiled
     * WHERE fragment and its params verbatim.
     */
    @Transactional(readOnly = true)
    public List<EntryQueries.FacetDto> facets(CompiledQuery cq, Set<Long> excludedTagIds, int limit) {
        StringBuilder sql = new StringBuilder("""
                select t.name, t.category, count(*) as cnt
                from media_entry
                join media_tag mt on mt.media_entry_id = media_entry.id
                join tag t on t.id = mt.tag_id
                where media_entry.status = 'ok'
                """);
        if (!cq.whereSql().isEmpty()) {
            sql.append(" and (").append(cq.whereSql()).append(")");
        }
        if (!excludedTagIds.isEmpty()) {
            sql.append(" and t.id not in (:fexcluded)");
        }
        sql.append(" group by t.name, t.category order by cnt desc, t.name asc limit :limit");

        var query = bind(cq, jdbc.sql(sql.toString())).param("limit", limit);
        if (!excludedTagIds.isEmpty()) {
            query = query.param("fexcluded", excludedTagIds);
        }
        return query.query((rs, i) -> new EntryQueries.FacetDto(
                rs.getString("name"), rs.getString("category"), rs.getLong("cnt"))).list();
    }

    // --- /tags listing ----------------------------------------------------------

    /**
     * Keyset-paginated tag listing for the /tags page (spec §7). Cursor semantics per sort:
     * NAME ascending {@code (name, id) > (k, id)}; COUNT and NEW descending with the id
     * tiebreak also descending, so the row-value comparison direction stays consistent.
     * NEW's cursor key is created_at as epoch MICROseconds (Postgres timestamptz precision).
     */
    @Transactional(readOnly = true)
    public List<TagRow> listTags(String q, TagCategory category, TagSort sort,
                                 String cursorKey, long cursorId, int limit) {
        StringBuilder sql = new StringBuilder("""
                select t.id, t.name, t.category, t.post_count, t.created_at
                from tag t where 1 = 1
                """);
        if (q != null && !q.isBlank()) {
            sql.append(" and t.name ilike :q escape '\\'");
        }
        if (category != null) {
            sql.append(" and t.category = :category");
        }
        switch (sort) {
            case NAME -> {
                if (cursorKey != null) {
                    sql.append(" and (t.name, t.id) > (:key, :cursorId)");
                }
                sql.append(" order by t.name asc, t.id asc");
            }
            case COUNT -> {
                if (cursorKey != null) {
                    sql.append(" and (t.post_count, t.id) < (:key, :cursorId)");
                }
                sql.append(" order by t.post_count desc, t.id desc");
            }
            case NEW -> {
                if (cursorKey != null) {
                    sql.append(" and (t.created_at, t.id) < (:key, :cursorId)");
                }
                sql.append(" order by t.created_at desc, t.id desc");
            }
        }
        sql.append(" limit :limit");

        var query = jdbc.sql(sql.toString()).param("limit", limit + 1).param("cursorId", cursorId);
        if (q != null && !q.isBlank()) {
            query = query.param("q", "%" + QuerySqlCompiler.likeEscape(q) + "%");
        }
        if (category != null) {
            query = query.param("category", category.wireName());
        }
        if (cursorKey != null) {
            query = switch (sort) {
                case NAME -> query.param("key", cursorKey);
                case COUNT -> query.param("key", Long.parseLong(cursorKey));
                case NEW -> query.param("key", java.sql.Timestamp.from(microsToInstant(Long.parseLong(cursorKey))));
            };
        }
        List<TagRow> rows = query.query((rs, i) -> row(rs)).list();
        // NOT trimmed: callers detect "more exist" via size > limit and trim themselves
        return rows;
    }

    /** Tag-table-only mapper: no media_tag context, so the provenance stays null. */
    private static TagRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TagRow(rs.getLong("id"), rs.getString("name"), rs.getString("category"),
                rs.getInt("post_count"), rs.getTimestamp("created_at").toInstant(), null);
    }

    private static JdbcClient.StatementSpec bind(CompiledQuery cq, JdbcClient.StatementSpec query) {
        for (Map.Entry<String, Object> e : cq.params().entrySet()) {
            query = query.param(e.getKey(), e.getValue());
        }
        return query;
    }

    private static Instant microsToInstant(long micros) {
        return Timestamp.from(Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1000)).toInstant();
    }
}
