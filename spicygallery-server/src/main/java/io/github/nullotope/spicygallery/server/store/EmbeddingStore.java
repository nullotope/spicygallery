package io.github.nullotope.spicygallery.server.store;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * pgvector persistence (spec §3.3): one row per embedded input (images: frame_idx 0;
 * videos: one row per extracted frame). Vectors bind as escaped string literals cast
 * to {@code vector} — the only format the plain JDBC stack speaks. All rows carry the
 * configured model string; a model change makes rows invisible to queries until the
 * backfill re-embeds them (Review Focus 2).
 */
@Repository
public class EmbeddingStore {

    public record FrameVector(int frameIdx, Long timestampMs, float[] vec) {}

    public record RelatedRow(long entryId, double score) {}

    public record MissingPage(List<Long> ids, Long nextCursor) {}

    private final JdbcClient jdbc;

    public EmbeddingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Deletes the entry's rows then inserts the new set; atomic per entry. */
    @Transactional
    public void replaceAll(long entryId, String model, List<FrameVector> frames) {
        jdbc.sql("delete from media_embedding where media_entry_id = :id")
                .param("id", entryId).update();
        for (FrameVector f : frames) {
            jdbc.sql("""
                            insert into media_embedding (media_entry_id, frame_idx, timestamp_ms, model, embedding)
                            values (:entry, :idx, :ts, :model, cast(:vec as vector))
                            """)
                    .param("entry", entryId)
                    .param("idx", f.frameIdx())
                    .param("ts", f.timestampMs())
                    .param("model", model)
                    .param("vec", PgVector.of(f.vec()))
                    .update();
        }
    }

    @Transactional(readOnly = true)
    public boolean hasForModel(long entryId, String model) {
        return jdbc.sql("select count(*) from media_embedding "
                        + "where media_entry_id = :id and model = :model")
                .param("id", entryId)
                .param("model", model)
                .query(Integer.class).single() > 0;
    }

    @Transactional(readOnly = true)
    public long countWithModel(String model) {
        return jdbc.sql("select count(distinct media_entry_id) from media_embedding where model = :model")
                .param("model", model)
                .query(Long.class).single();
    }

    @Transactional(readOnly = true)
    public long countEntries() {
        return jdbc.sql("select count(*) from media_entry where status = 'ok'")
                .query(Long.class).single();
    }

    /** Removes every embedding row (force re-embed path, spec §6). */
    @Transactional
    public void deleteAll() {
        jdbc.sql("delete from media_embedding").update();
    }

    /** Keyset over entries lacking a row for {@code model}; raw limit+1 window, caller trims. */
    @Transactional(readOnly = true)
    public MissingPage entriesMissing(String model, long cursorId, int limit) {
        List<Long> ids = jdbc.sql("""
                        select me.id from media_entry me
                        where me.status = 'ok' and me.id > :cursor
                          and not exists (select 1 from media_embedding e
                                          where e.media_entry_id = me.id and e.model = :model)
                        order by me.id asc limit :limit
                        """)
                .param("cursor", cursorId)
                .param("model", model)
                .param("limit", limit + 1)
                .query(Long.class).list();
        if (ids.size() > limit) {
            return new MissingPage(ids.subList(0, limit), ids.get(limit - 1));
        }
        return new MissingPage(ids, null);
    }

    /** Best-frame cosine similarity of every other entry (spec §5); self excluded. */
    @Transactional(readOnly = true)
    public List<RelatedRow> related(long entryId, String model, int limit) {
        return jdbc.sql("""
                        select cand.media_entry_id as eid,
                               1 - min(cand.embedding <=> ref.embedding) as score
                        from media_embedding ref
                        join media_embedding cand
                          on cand.model = ref.model and cand.media_entry_id <> ref.media_entry_id
                        where ref.media_entry_id = :entryId and ref.model = :model
                        group by cand.media_entry_id
                        order by score desc, eid desc
                        limit :limit
                        """)
                .param("entryId", entryId)
                .param("model", model)
                .param("limit", limit)
                .query((rs, i) -> new RelatedRow(rs.getLong("eid"), rs.getDouble("score")))
                .list();
    }

    /**
     * Component-wise mean over each entry's frames for {@code model} (spec §5 concepts:
     * a concept anchor is the mean of its members' vectors). Ids without rows for the
     * model are absent from the map — never null arrays (Review Focus 2).
     */
    @Transactional(readOnly = true)
    public Map<Long, float[]> meanVectorByEntry(List<Long> entryIds, String model) {
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        List<Map.Entry<Long, float[]>> rows = jdbc.sql("""
                        select media_entry_id as eid, avg(embedding)::text as v
                        from media_embedding
                        where model = :model and media_entry_id in (:ids)
                        group by media_entry_id
                        """)
                .param("model", model)
                .param("ids", entryIds)
                .query((rs, i) -> Map.entry(rs.getLong("eid"), PgVector.parse(rs.getString("v"))))
                .list();
        Map<Long, float[]> out = new LinkedHashMap<>();
        for (Map.Entry<Long, float[]> row : rows) {
            out.put(row.getKey(), row.getValue());
        }
        return out;
    }

    /**
     * Best-frame cosine similarity of every status-ok entry to {@code anchor} (spec §5
     * concept suggestions), excluding the given ids — the {@code not in} clause is
     * omitted entirely when the set is empty. Excludes self implicitly: callers pass the
     * concept's own members in {@code excludeIds}.
     */
    @Transactional(readOnly = true)
    public List<RelatedRow> suggestions(float[] anchor, String model, Set<Long> excludeIds, int limit) {
        String sql = """
                select me.id as eid,
                       1 - min(cand.embedding <=> cast(:qv as vector)) as score
                from media_entry me
                join media_embedding cand
                  on cand.media_entry_id = me.id and cand.model = :model
                where me.status = 'ok'
                """;
        if (!excludeIds.isEmpty()) {
            sql += "  and me.id not in (:excluded)\n";
        }
        sql += """
                group by me.id
                order by score desc, eid desc
                limit :limit
                """;
        var spec = jdbc.sql(sql)
                .param("qv", PgVector.of(anchor))
                .param("model", model)
                .param("limit", limit);
        if (!excludeIds.isEmpty()) {
            spec = spec.param("excluded", List.copyOf(excludeIds));
        }
        return spec.query((rs, i) -> new RelatedRow(rs.getLong("eid"), rs.getDouble("score"))).list();
    }
}
