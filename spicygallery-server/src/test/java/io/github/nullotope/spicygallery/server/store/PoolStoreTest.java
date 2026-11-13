package io.github.nullotope.spicygallery.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.server.PostgresBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class PoolStoreTest extends PostgresBase {

    @Autowired PoolStore pools;
    @Autowired JdbcTagStore tags;
    @Autowired JdbcMediaStoreSink sink;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    private long entryId;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from import_run").update(); // BEFORE pool: pool_id FK (no cascade)
        jdbc.sql("delete from pool").update(); // cascades pool_member + pool_tag
        jdbc.sql("delete from tag").update();
        jdbc.sql("delete from media_entry").update();
        entryId = sink.insert(new NewEntry(Kind.IMAGE, "api", "a.png", "a.png", "png", "image/png",
                1, "hash-a", 8, 4, null, Instant.parse("2026-01-01T00:00:00Z"))).value();
    }

    private long entry(String hash) {
        return sink.insert(new NewEntry(Kind.IMAGE, "api", hash + ".png", hash + ".png", "png", "image/png",
                1, hash, 8, 4, null, Instant.parse("2026-01-01T00:00:00Z"))).value();
    }

    @Test
    void create_manual_and_folder_roundtrip_all_fields() {
        PoolStore.PoolRow manual = pools.create("favorites", "hand-picked", null, null);
        PoolStore.PoolRow manualBack = pools.byId(manual.id()).orElseThrow();
        assertEquals("favorites", manualBack.name());
        assertEquals("hand-picked", manualBack.description());
        assertNull(manualBack.originRoot()); // null origins = manual pool
        assertNull(manualBack.originDir());
        assertEquals(manualBack.createdAt(), manualBack.updatedAt()); // both default to the same insert-time now()

        PoolStore.PoolRow folder = pools.create("chapter-1", null, "library", "chapter-1");
        PoolStore.PoolRow folderBack = pools.byId(folder.id()).orElseThrow();
        assertEquals("library", folderBack.originRoot());
        assertEquals("chapter-1", folderBack.originDir());
        assertNull(folderBack.description()); // description is optional

        // '' = library root top level round-trips verbatim (spec §3.1)
        PoolStore.PoolRow rootLevel = pools.create("root top", null, "library", "");
        assertEquals("", pools.byId(rootLevel.id()).orElseThrow().originDir());

        assertEquals(List.of(manual.id(), folder.id(), rootLevel.id()),
                pools.list().stream().map(PoolStore.PoolRow::id).toList()); // id asc
        assertTrue(pools.byId(999_999).isEmpty());
    }

    @Test
    void duplicate_name_insert_fails() {
        pools.create("dup", null, null, null);
        assertThrows(DataIntegrityViolationException.class,
                () -> pools.create("dup", null, null, null));
    }

    @Test
    void rename_sets_name_and_description_and_bumps_updated_at() throws InterruptedException {
        PoolStore.PoolRow row = pools.create("old", "old desc", null, null);
        Thread.sleep(10); // guarantee a strictly later now() for the update below
        pools.rename(row.id(), "new", "new desc");
        PoolStore.PoolRow back = pools.byId(row.id()).orElseThrow();
        assertEquals("new", back.name());
        assertEquals("new desc", back.description());
        assertTrue(back.updatedAt().isAfter(row.updatedAt()));
    }

    @Test
    void byOrigin_hits_folder_pools_only_and_misses_otherwise() {
        PoolStore.PoolRow folder = pools.create("chapter", null, "library", "chapter-1");
        pools.create("manual", null, null, null); // null origins never match the equality lookup
        assertEquals(folder.id(), pools.byOrigin("library", "chapter-1").orElseThrow().id());
        assertTrue(pools.byOrigin("library", "chapter-2").isEmpty());
        assertTrue(pools.byOrigin("other-root", "chapter-1").isEmpty());
    }

    @Test
    void addMembers_appends_entries_then_pools_after_max_position_in_given_order() {
        long pool = pools.create("work", null, null, null).id();
        long child = pools.create("chapter", null, null, null).id();
        long e1 = entry("hash-b");
        long e2 = entry("hash-c");

        List<PoolStore.MemberRow> first = pools.addMembers(pool, List.of(e2, e1), List.of(child));
        assertEquals(List.of(1, 2, 3), first.stream().map(PoolStore.MemberRow::position).toList());
        assertEquals(e2, first.get(0).entryId()); // entries in the given order...
        assertNull(first.get(0).childPoolId());
        assertEquals(e1, first.get(1).entryId());
        assertEquals(child, first.get(2).childPoolId()); // ...then pools in the given order
        assertNull(first.get(2).entryId());
        assertTrue(first.stream().allMatch(r -> r.poolId() == pool));

        // appended after the CURRENT max position, not after the row count (positions may gap)
        List<PoolStore.MemberRow> second = pools.addMembers(pool, List.of(entryId), List.of());
        assertEquals(List.of(4), second.stream().map(PoolStore.MemberRow::position).toList());

        // members() reads position asc across both member kinds
        List<PoolStore.MemberRow> all = pools.members(pool);
        assertEquals(List.of(e2, e1, child, entryId),
                all.stream().map(r -> r.entryId() != null ? r.entryId() : r.childPoolId()).toList());
    }

    @Test
    void addMembers_appends_after_max_position_even_when_positions_have_gapped() {
        // the gapless case above cannot distinguish max+1 from count+1 (they coincide) —
        // so: store-level removeMember leaves the gap (no renumber), and the next append
        // must pin to max(position)=3 → 4, NOT to the surviving row count 2 → 3
        long pool = pools.create("gapped", null, null, null).id();
        long e2 = entry("hash-b");
        long e3 = entry("hash-c");
        pools.addMembers(pool, List.of(entryId, e2, e3), List.of()); // positions 1, 2, 3
        PoolStore.MemberRow first = pools.members(pool).get(0);
        pools.removeMember(first.memberId()); // gap at 1; survivors keep positions 2, 3

        List<PoolStore.MemberRow> appended = pools.addMembers(pool, List.of(entry("hash-d")), List.of());

        assertEquals(4, appended.get(0).position());
        assertEquals(List.of(2, 3, 4), pools.members(pool).stream()
                .map(PoolStore.MemberRow::position).toList());
    }

    @Test
    void renumber_writes_positions_1_to_n_in_the_given_row_id_order() {
        long pool = pools.create("work", null, null, null).id();
        long child = pools.create("chapter", null, null, null).id();
        long e1 = entry("hash-b");
        long e2 = entry("hash-c");
        List<PoolStore.MemberRow> added = pools.addMembers(pool, List.of(e1, e2), List.of(child));

        // reversed row-id order — entries and sub-pools reorder uniformly via surrogate ids
        List<Long> reversed = added.stream().map(PoolStore.MemberRow::memberId).toList().reversed();
        pools.renumber(pool, reversed);

        List<PoolStore.MemberRow> after = pools.members(pool);
        assertEquals(reversed, after.stream().map(PoolStore.MemberRow::memberId).toList());
        assertEquals(List.of(1, 2, 3), after.stream().map(PoolStore.MemberRow::position).toList());
    }

    @Test
    void removeMember_deletes_only_that_row() {
        long pool = pools.create("p", null, null, null).id();
        long e1 = entry("hash-b");
        pools.addMembers(pool, List.of(entryId, e1), List.of());
        PoolStore.MemberRow second = pools.members(pool).get(1);
        pools.removeMember(second.memberId());
        List<PoolStore.MemberRow> left = pools.members(pool);
        assertEquals(1, left.size());
        assertEquals(entryId, left.get(0).entryId().longValue());
    }

    @Test
    void allMembers_is_the_bulk_read_over_every_pool() {
        long p1 = pools.create("p1", null, null, null).id();
        long p2 = pools.create("p2", null, null, null).id();
        long e = entry("hash-b");
        pools.addMembers(p1, List.of(entryId, e), List.of());
        pools.addMembers(p2, List.of(e), List.of());

        List<PoolStore.MemberRow> all = pools.allMembers();
        assertEquals(List.of(p1, p1, p2), all.stream().map(PoolStore.MemberRow::poolId).toList());
        assertEquals(List.of(1, 2, 1), all.stream().map(PoolStore.MemberRow::position).toList());
    }

    @Test
    void setTags_replaces_the_whole_set() {
        long pool = pools.create("p", null, null, null).id();
        long other = pools.create("other", null, null, null).id();
        long t1 = tags.create("red", TagCategory.GENERAL).id();
        long t2 = tags.create("blue", TagCategory.GENERAL).id();
        long t3 = tags.create("green", TagCategory.GENERAL).id();

        pools.setTags(pool, List.of(t1, t2));
        assertEquals(Set.of(t1, t2), Set.copyOf(pools.tagIdsOf(pool)));

        pools.setTags(pool, List.of(t2, t3)); // replace-set: t1 is gone
        assertEquals(Set.of(t2, t3), Set.copyOf(pools.tagIdsOf(pool)));

        pools.setTags(pool, List.of()); // empty list clears
        assertEquals(List.of(), pools.tagIdsOf(pool));

        pools.setTags(other, List.of(t1)); // other pools' tag sets are independent
        assertEquals(Map.of(other, List.of(t1)), pools.allTagIds());
    }

    @Test
    void deleting_entry_cascades_its_membership_rows_and_positions_may_gap() {
        long pool = pools.create("p", null, null, null).id();
        long e2 = entry("hash-b");
        pools.addMembers(pool, List.of(entryId, e2), List.of());

        jdbc.sql("delete from media_entry where id = :id").param("id", entryId).update();

        List<PoolStore.MemberRow> left = pools.members(pool);
        assertEquals(1, left.size());
        assertEquals(e2, left.get(0).entryId().longValue());
        assertEquals(2, left.get(0).position()); // gap at 1 is harmless until the next renumber (spec §3.6)
    }

    @Test
    void deleting_child_pool_vanishes_from_parent_and_takes_its_own_members() {
        long parent = pools.create("work", null, null, null).id();
        long child = pools.create("chapter", null, null, null).id();
        long e = entry("hash-b");
        pools.addMembers(parent, List.of(entryId), List.of(child));
        pools.addMembers(child, List.of(e), List.of());

        pools.delete(child);

        assertTrue(pools.byId(child).isEmpty());
        List<PoolStore.MemberRow> parentRows = pools.members(parent);
        assertEquals(1, parentRows.size()); // the child's membership row in the parent cascaded away...
        assertEquals(entryId, parentRows.get(0).entryId().longValue()); // ...the entry row survived
        assertTrue(pools.allMembers().stream().noneMatch(r -> r.poolId() == child)); // child's own members went too
    }

    @Test
    void delete_removes_pool_its_members_and_its_tags() {
        long pool = pools.create("gone", null, null, null).id();
        long t = tags.create("red", TagCategory.GENERAL).id();
        pools.addMembers(pool, List.of(entryId), List.of());
        pools.setTags(pool, List.of(t));

        pools.delete(pool);

        assertTrue(pools.byId(pool).isEmpty());
        assertTrue(pools.members(pool).isEmpty());
        assertTrue(pools.tagIdsOf(pool).isEmpty());
    }

    @Test
    void xor_check_rejects_neither_and_both() {
        long pool = pools.create("p", null, null, null).id();
        long child = pools.create("c", null, null, null).id();
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.sql("insert into pool_member (pool_id, position) values (:p, 1)")
                        .param("p", pool).update());
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.sql("insert into pool_member (pool_id, position, entry_id, child_pool_id) values (:p, 1, :e, :c)")
                        .param("p", pool).param("e", entryId).param("c", child).update());
    }

    @Test
    void partial_unique_indexes_reject_duplicate_members() {
        long pool = pools.create("p", null, null, null).id();
        long child = pools.create("c", null, null, null).id();
        pools.addMembers(pool, List.of(entryId), List.of(child));
        // the store inserts verbatim; the caller dedupes (spec §3.6) — the indexes are the backstop
        assertThrows(DataIntegrityViolationException.class,
                () -> pools.addMembers(pool, List.of(entryId), List.of()));
        assertThrows(DataIntegrityViolationException.class,
                () -> pools.addMembers(pool, List.of(), List.of(child)));
    }

    @Test
    void lockMutation_excludes_a_second_transaction_and_releases_at_transaction_end() {
        // deterministic two-transaction proof of the pool-mutation serialization (no
        // threads): transaction A takes the advisory lock uncommitted; a SEPARATE
        // autocommit connection must be REFUSED while A is open and must SUCCEED once
        // A has committed — exclusive AND transaction-scoped, closing the parked
        // check-then-act races (cycle check, free-name, name dedupe)
        Boolean blockedWhileOpen = new TransactionTemplate(txManager).execute(status -> {
            pools.lockMutation(); // tx A holds the lock (uncommitted)
            return tryMutationLockFromFreshConnection(); // tx B must be refused
        });
        assertFalse(blockedWhileOpen, "a second transaction must not take the mutation lock while tx A holds it");
        assertTrue(tryMutationLockFromFreshConnection(), "the mutation lock must be released when tx A ends");
    }

    /** Probe on a plain JDBC connection (NOT the transaction-bound one): tx B's try-lock. */
    private boolean tryMutationLockFromFreshConnection() {
        try (Connection c = dataSource.getConnection()) {
            try (ResultSet rs = c.createStatement()
                    .executeQuery("select pg_try_advisory_xact_lock(" + PoolStore.MUTATION_LOCK_KEY + ")")) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("mutation lock probe failed", e);
        }
    }

    @Test
    void pool_type_seed_leaves_exactly_one_row_per_name_and_reruns_idempotently() {
        // the migration seeded at startup; after the clean() wipe, re-running its seed
        // statement verbatim must produce exactly the two pool_type tags — and a re-run
        // must not duplicate them (on conflict do update, spec §3.2)
        seedPoolTypeTags();
        assertPoolTypeTags();
        seedPoolTypeTags();
        assertPoolTypeTags();
    }

    private void seedPoolTypeTags() {
        jdbc.sql("""
                        insert into tag (name, category) values ('pool', 'pool_type'), ('suggested', 'pool_type')
                          on conflict (name) do update set category = excluded.category
                        """)
                .update();
    }

    private void assertPoolTypeTags() {
        List<String> names = jdbc.sql("select name from tag where category = 'pool_type' order by name")
                .query(String.class)
                .list();
        assertEquals(List.of("pool", "suggested"), names);
    }
}
