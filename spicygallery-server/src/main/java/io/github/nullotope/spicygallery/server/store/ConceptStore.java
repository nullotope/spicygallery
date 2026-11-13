package io.github.nullotope.spicygallery.server.store;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concept persistence (spec §3.1): named concepts with an optional bootstrap phrase and
 * nullable phrase/anchor vectors, plus per-entry membership ({@code EXAMPLE}|{@code EXCLUDED}).
 * Vectors bind as escaped string literals cast to {@code vector} and are read back as text
 * ({@code col::text}); SQL NULL columns map to null arrays. {@code ConceptService} (slice 5)
 * owns the write semantics on top of this store.
 */
@Repository
public class ConceptStore {

    /** One concept row. {@code phrase}, the vectors and {@code anchorModel} are all nullable. */
    public record ConceptRow(long id, String name, String phrase, float[] phraseVector,
                             float[] anchor, String anchorModel, Instant createdAt, Instant updatedAt) {}

    public record Counts(long examples, long excluded) {}

    private static final String COLS = """
            id, name, phrase, phrase_vector::text as phrase_vector, anchor::text as anchor,
            anchor_model, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public ConceptStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- concepts -------------------------------------------------------------

    @Transactional
    public ConceptRow create(String name, String phrase, float[] phraseVector, float[] anchor,
                             String anchorModel) {
        return jdbc.sql("""
                        insert into concept (name, phrase, phrase_vector, anchor, anchor_model)
                        values (:name, :phrase, cast(:pv as vector), cast(:av as vector), :am)
                        returning %s
                        """.formatted(COLS))
                .param("name", name)
                .param("phrase", phrase)
                .param("pv", vector(phraseVector))
                .param("av", vector(anchor))
                .param("am", anchorModel)
                .query((rs, i) -> row(rs))
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<ConceptRow> byId(long id) {
        return jdbc.sql("select " + COLS + " from concept where id = :id")
                .param("id", id)
                .query((rs, i) -> row(rs))
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<ConceptRow> byName(String name) {
        return jdbc.sql("select " + COLS + " from concept where name = :name")
                .param("name", name)
                .query((rs, i) -> row(rs))
                .optional();
    }

    /** {@code "recent"} → updated_at desc (id desc tiebreak); anything else → name asc. */
    @Transactional(readOnly = true)
    public List<ConceptRow> list(String sort) {
        String order = "recent".equals(sort) ? " order by updated_at desc, id desc" : " order by name asc, id asc";
        return jdbc.sql("select " + COLS + " from concept" + order)
                .query((rs, i) -> row(rs))
                .list();
    }

    @Transactional
    public void rename(long id, String name) {
        jdbc.sql("update concept set name = :name, updated_at = now() where id = :id")
                .param("name", name)
                .param("id", id)
                .update();
    }

    @Transactional
    public void updatePhraseAndAnchor(long id, String phrase, float[] phraseVector, float[] anchor,
                                      String anchorModel) {
        jdbc.sql("""
                        update concept
                        set phrase = :phrase, phrase_vector = cast(:pv as vector),
                            anchor = cast(:av as vector), anchor_model = :am, updated_at = now()
                        where id = :id
                        """)
                .param("id", id)
                .param("phrase", phrase)
                .param("pv", vector(phraseVector))
                .param("av", vector(anchor))
                .param("am", anchorModel)
                .update();
    }

    @Transactional
    public void updateAnchor(long id, float[] anchor, String anchorModel) {
        jdbc.sql("update concept set anchor = cast(:av as vector), anchor_model = :am, updated_at = now() "
                        + "where id = :id")
                .param("id", id)
                .param("av", vector(anchor))
                .param("am", anchorModel)
                .update();
    }

    @Transactional
    public void delete(long id) {
        jdbc.sql("delete from concept where id = :id").param("id", id).update();
    }

    // --- membership -----------------------------------------------------------

    /** Upsert: a conflicting PK row has its status rewritten — never a duplicate. */
    @Transactional
    public void setStatus(long conceptId, long entryId, String status) {
        jdbc.sql("""
                        insert into concept_entry (concept_id, media_entry_id, status)
                        values (:c, :e, :status)
                        on conflict (concept_id, media_entry_id) do update set status = excluded.status
                        """)
                .param("c", conceptId)
                .param("e", entryId)
                .param("status", status)
                .update();
    }

    /** Deletes the membership row regardless of status; true when one existed. */
    @Transactional
    public boolean removeEntry(long conceptId, long entryId) {
        return jdbc.sql("delete from concept_entry where concept_id = :c and media_entry_id = :e")
                .param("c", conceptId)
                .param("e", entryId)
                .update() > 0;
    }

    @Transactional(readOnly = true)
    public List<Long> entryIds(long conceptId, String status) {
        return jdbc.sql("""
                        select media_entry_id from concept_entry
                        where concept_id = :c and status = :status
                        order by media_entry_id
                        """)
                .param("c", conceptId)
                .param("status", status)
                .query(Long.class)
                .list();
    }

    @Transactional(readOnly = true)
    public Counts counts(long conceptId) {
        return jdbc.sql("""
                        select count(*) filter (where status = 'EXAMPLE') as examples,
                               count(*) filter (where status = 'EXCLUDED') as excluded
                        from concept_entry where concept_id = :c
                        """)
                .param("c", conceptId)
                .query((rs, i) -> new Counts(rs.getLong("examples"), rs.getLong("excluded")))
                .single();
    }

    /** Entries (distinct via media_tag's PK) carrying the named tag, any provenance. */
    @Transactional(readOnly = true)
    public Set<Long> taggedEntryIdsByName(String tagName) {
        return new LinkedHashSet<>(jdbc.sql("""
                        select mt.media_entry_id
                        from media_tag mt join tag t on t.id = mt.tag_id
                        where t.name = :name
                        """)
                .param("name", tagName)
                .query(Long.class)
                .list());
    }

    // --- mapping ---------------------------------------------------------------

    private static ConceptRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConceptRow(rs.getLong("id"), rs.getString("name"), rs.getString("phrase"),
                vector(rs, "phrase_vector"), vector(rs, "anchor"), rs.getString("anchor_model"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    /** SQL NULL vector column → null array; otherwise parse the pgvector text form. */
    private static float[] vector(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        String text = rs.getString(col);
        return text == null ? null : PgVector.parse(text);
    }

    /** null array → null SQL param (the cast maps it to a NULL vector); else the pgvector literal. */
    private static String vector(float[] v) {
        return v == null ? null : PgVector.of(v);
    }
}
