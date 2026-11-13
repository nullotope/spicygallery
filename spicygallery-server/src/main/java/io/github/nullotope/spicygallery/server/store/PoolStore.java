package io.github.nullotope.spicygallery.server.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pool persistence (spec §3.1): named ordered sets whose members are entries AND sub-pools
 * in one shared position sequence, plus pool-level tags and folder origins. The renumber rule
 * is binding: every membership/order mutation rewrites {@code position} 1..N over the pool's
 * current members — this store provides the {@link #renumber} write and inserts verbatim
 * (duplicates hit the partial unique indexes); callers validate, dedupe and orchestrate.
 */
@Repository
public class PoolStore {

    /** One pool row. {@code originRoot}/{@code originDir} are null for manual pools ('' = root top level). */
    public record PoolRow(long id, String name, String description, String originRoot, String originDir,
                          Instant createdAt, Instant updatedAt) {}

    /**
     * One membership row. Exactly one of {@code entryId}/{@code childPoolId} is non-null
     * (XOR check in the schema).
     */
    public record MemberRow(long memberId, long poolId, int position, Long entryId, Long childPoolId,
                            Instant addedAt) {}

    private static final String COLS = "id, name, description, origin_root, origin_dir, created_at, updated_at";

    /**
     * The fixed key for {@link #lockMutation}: the 64-bit FNV-1a hash of the string
     * "spicygallery-pool-mutation" ({@code 0x3e44ec0765046eea} = {@code 4486970645271572202}
     * signed). A single GLOBAL key by design: single-user gallery, mutations are rare and
     * interactive — one lock serializes every check-then-act (cycle rule, free-name check,
     * name dedupe) atomically; a collision with some future advisory-lock user would only
     * over-serialize, never corrupt.
     */
    static final long MUTATION_LOCK_KEY = 0x3e44ec0765046eeaL; // fnv1a64("spicygallery-pool-mutation")

    private final JdbcClient jdbc;

    public PoolStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- mutation serialization ----------------------------------------------------

    /**
     * Takes the transaction-scoped Postgres advisory lock that serializes ALL pool
     * mutations. {@code pg_advisory_xact_lock} releases at TRANSACTION END, so this only
     * protects a caller that runs inside one transaction — enforced with MANDATORY
     * propagation (a non-transactional caller fails loudly instead of silently
     * serializing nothing). Reentrant within its transaction: a mutating service method
     * calling another one in the same transaction re-acquires without blocking.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockMutation() {
        // transaction-scoped: held until the caller's transaction commits or rolls back.
        // pg_advisory_xact_lock returns void — read it as a string, never a number
        jdbc.sql("select pg_advisory_xact_lock(:key)")
                .param("key", MUTATION_LOCK_KEY)
                .query((rs, i) -> rs.getString(1))
                .single();
    }

    // --- pools ---------------------------------------------------------------

    @Transactional
    public PoolRow create(String name, String description, String originRoot, String originDir) {
        return jdbc.sql("""
                        insert into pool (name, description, origin_root, origin_dir)
                        values (:name, :description, :originRoot, :originDir)
                        returning %s
                        """.formatted(COLS))
                .param("name", name)
                .param("description", description)
                .param("originRoot", originRoot)
                .param("originDir", originDir)
                .query((rs, i) -> row(rs))
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<PoolRow> byId(long id) {
        return jdbc.sql("select " + COLS + " from pool where id = :id")
                .param("id", id)
                .query((rs, i) -> row(rs))
                .optional();
    }

    @Transactional(readOnly = true)
    public List<PoolRow> list() {
        return jdbc.sql("select " + COLS + " from pool order by id")
                .query((rs, i) -> row(rs))
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<PoolRow> byOrigin(String rootName, String dirPath) {
        return jdbc.sql("select " + COLS + " from pool where origin_root = :root and origin_dir = :dir")
                .param("root", rootName)
                .param("dir", dirPath)
                .query((rs, i) -> row(rs))
                .optional();
    }

    @Transactional
    public void rename(long id, String name, String description) {
        jdbc.sql("""
                        update pool set name = :name, description = :description, updated_at = now()
                        where id = :id
                        """)
                .param("id", id)
                .param("name", name)
                .param("description", description)
                .update();
    }

    /**
     * Cascades to the pool's member rows, pool tags, and parent membership rows (child_pool_id).
     * {@code import_run.pool_id} has NO {@code on delete cascade} (V5 is spec-verbatim and
     * immutable), so the run's reference is cleared first in the same transaction — deleting
     * an import-drafted pool must not FK-fail (T7 routed finding).
     */
    @Transactional
    public void delete(long id) {
        jdbc.sql("update import_run set pool_id = null where pool_id = :id")
                .param("id", id)
                .update();
        jdbc.sql("delete from pool where id = :id").param("id", id).update();
    }

    // --- membership ------------------------------------------------------------

    /**
     * Appends the given entries (in order) and then sub-pools (in order) after the pool's
     * current max position. No dedupe: a repeat member violates the partial unique index.
     */
    @Transactional
    public List<MemberRow> addMembers(long poolId, List<Long> entryIds, List<Long> poolIds) {
        int position = jdbc.sql("select coalesce(max(position), 0) from pool_member where pool_id = :p")
                .param("p", poolId)
                .query(Integer.class)
                .single();
        List<MemberRow> added = new ArrayList<>(entryIds.size() + poolIds.size());
        for (Long entryId : entryIds) {
            added.add(insertMember(poolId, ++position, entryId, null));
        }
        for (Long childPoolId : poolIds) {
            added.add(insertMember(poolId, ++position, null, childPoolId));
        }
        return added;
    }

    private MemberRow insertMember(long poolId, int position, Long entryId, Long childPoolId) {
        return jdbc.sql("""
                        insert into pool_member (pool_id, position, entry_id, child_pool_id)
                        values (:p, :position, :e, :c)
                        returning id, pool_id, position, entry_id, child_pool_id, added_at
                        """)
                .param("p", poolId)
                .param("position", position)
                .param("e", entryId)
                .param("c", childPoolId)
                .query((rs, i) -> memberRow(rs))
                .single();
    }

    /** Deletes one membership row; survivors may gap until the caller renumbers (spec §3.6). */
    @Transactional
    public void removeMember(long memberId) {
        jdbc.sql("delete from pool_member where id = :id").param("id", memberId).update();
    }

    @Transactional(readOnly = true)
    public List<MemberRow> members(long poolId) {
        return jdbc.sql("""
                        select id, pool_id, position, entry_id, child_pool_id, added_at
                        from pool_member where pool_id = :p
                        order by position asc, id asc
                        """)
                .param("p", poolId)
                .query((rs, i) -> memberRow(rs))
                .list();
    }

    /** Bulk read over every pool's members (pool_id, then position) — the service's flatten seed. */
    @Transactional(readOnly = true)
    public List<MemberRow> allMembers() {
        return jdbc.sql("""
                        select id, pool_id, position, entry_id, child_pool_id, added_at
                        from pool_member
                        order by pool_id asc, position asc, id asc
                        """)
                .query((rs, i) -> memberRow(rs))
                .list();
    }

    /**
     * Writes positions 1..N in the given row-id order (surrogate ids; entries and sub-pools
     * reorder uniformly). The caller guarantees the ids are a permutation of the pool's
     * current member rows.
     */
    @Transactional
    public void renumber(long poolId, List<Long> orderedMemberIds) {
        int position = 1;
        for (Long memberId : orderedMemberIds) {
            jdbc.sql("update pool_member set position = :position where id = :id and pool_id = :p")
                    .param("position", position++)
                    .param("id", memberId)
                    .param("p", poolId)
                    .update();
        }
    }

    // --- pool tags ---------------------------------------------------------------

    /** Replace-set: wipes the pool's tag rows, then links the given tags. */
    @Transactional
    public void setTags(long poolId, List<Long> tagIds) {
        jdbc.sql("delete from pool_tag where pool_id = :p").param("p", poolId).update();
        for (Long tagId : tagIds) {
            jdbc.sql("insert into pool_tag (pool_id, tag_id) values (:p, :t)")
                    .param("p", poolId)
                    .param("t", tagId)
                    .update();
        }
    }

    @Transactional(readOnly = true)
    public List<Long> tagIdsOf(long poolId) {
        return jdbc.sql("select tag_id from pool_tag where pool_id = :p order by tag_id")
                .param("p", poolId)
                .query(Long.class)
                .list();
    }

    /** Bulk read: every pool's tag ids (pool_id, then tag_id) — the service's facet seed. */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> allTagIds() {
        Map<Long, List<Long>> byPool = new LinkedHashMap<>();
        jdbc.sql("select pool_id, tag_id from pool_tag order by pool_id, tag_id")
                .query((rs, i) -> {
                    byPool.computeIfAbsent(rs.getLong("pool_id"), k -> new ArrayList<>())
                            .add(rs.getLong("tag_id"));
                    return null;
                })
                .list();
        return byPool;
    }

    // --- mapping ---------------------------------------------------------------

    private static PoolRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PoolRow(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("origin_root"), rs.getString("origin_dir"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static MemberRow memberRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MemberRow(rs.getLong("id"), rs.getLong("pool_id"), rs.getInt("position"),
                rs.getObject("entry_id", Long.class), rs.getObject("child_pool_id", Long.class),
                rs.getTimestamp("added_at").toInstant());
    }
}
