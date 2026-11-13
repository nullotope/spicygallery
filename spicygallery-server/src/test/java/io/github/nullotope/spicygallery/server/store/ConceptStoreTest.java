package io.github.nullotope.spicygallery.server.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.server.PostgresBase;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class ConceptStoreTest extends PostgresBase {

    @Autowired ConceptStore concepts;
    @Autowired JdbcTagStore tags;
    @Autowired JdbcMediaStoreSink sink;
    @Autowired JdbcClient jdbc;

    private long entryId;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from concept").update(); // cascades concept_entry
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
    void create_roundtrip_all_fields() {
        float[] pv = {0.25f, -0.5f, 0.75f};
        float[] av = {1.0f, 0.0f, -0.25f};
        ConceptStore.ConceptRow row = concepts.create("sunset", "golden hour photos", pv, av, "embeddinggemma-2");
        ConceptStore.ConceptRow back = concepts.byId(row.id()).orElseThrow();
        assertEquals("sunset", back.name());
        assertEquals("golden hour photos", back.phrase());
        assertArrayEquals(pv, back.phraseVector());
        assertArrayEquals(av, back.anchor());
        assertEquals("embeddinggemma-2", back.anchorModel());
        assertEquals(back.createdAt(), back.updatedAt()); // both default to the same insert-time now()
    }

    @Test
    void create_with_null_phrase_and_vectors_roundtrips_as_nulls() {
        ConceptStore.ConceptRow row = concepts.create("bare", null, null, null, null);
        ConceptStore.ConceptRow back = concepts.byId(row.id()).orElseThrow();
        assertNull(back.phrase());
        assertNull(back.phraseVector()); // SQL NULL vector column → null array, not empty
        assertNull(back.anchor());
        assertNull(back.anchorModel());
    }

    @Test
    void byName_hit_and_miss() {
        ConceptStore.ConceptRow row = concepts.create("findme", "p", new float[] {0.5f}, null, null);
        assertEquals(row.id(), concepts.byName("findme").orElseThrow().id());
        assertTrue(concepts.byName("nope").isEmpty());
        assertTrue(concepts.byId(999_999).isEmpty());
    }

    @Test
    void duplicate_name_insert_fails() {
        concepts.create("dup", null, null, null, null);
        assertThrows(DataIntegrityViolationException.class,
                () -> concepts.create("dup", null, null, null, null));
    }

    @Test
    void list_orders_by_name_and_recent() throws InterruptedException {
        ConceptStore.ConceptRow first = concepts.create("zeta", null, null, null, null);
        ConceptStore.ConceptRow second = concepts.create("alpha", null, null, null, null);
        ConceptStore.ConceptRow third = concepts.create("mid", null, null, null, null);

        assertEquals(List.of("alpha", "mid", "zeta"),
                concepts.list("name").stream().map(ConceptStore.ConceptRow::name).toList());
        // recent = updated_at desc; fresh rows created in order → strict reverse creation order
        assertEquals(List.of(third.id(), second.id(), first.id()),
                concepts.list("recent").stream().map(ConceptStore.ConceptRow::id).toList());

        // renaming the OLDEST row bumps its updated_at, so it jumps to the front of "recent"
        Thread.sleep(10); // guarantee a strictly later now() than the creations above
        concepts.rename(first.id(), "zeta_renamed");
        assertEquals(List.of(first.id(), third.id(), second.id()),
                concepts.list("recent").stream().map(ConceptStore.ConceptRow::id).toList());
        assertEquals("zeta_renamed", concepts.byId(first.id()).orElseThrow().name());
    }

    @Test
    void updatePhraseAndAnchor_and_updateAnchor_set_fields_and_bump_updated_at() throws InterruptedException {
        ConceptStore.ConceptRow row = concepts.create("up", "old phrase", null, null, null);
        Thread.sleep(10); // guarantee a strictly later now() for the update below

        concepts.updatePhraseAndAnchor(row.id(), "new phrase", new float[] {0.25f, 0.5f},
                new float[] {0.75f}, "m1");
        ConceptStore.ConceptRow back = concepts.byId(row.id()).orElseThrow();
        assertEquals("new phrase", back.phrase());
        assertArrayEquals(new float[] {0.25f, 0.5f}, back.phraseVector());
        assertArrayEquals(new float[] {0.75f}, back.anchor());
        assertEquals("m1", back.anchorModel());
        assertNotEquals(row.updatedAt(), back.updatedAt());

        concepts.updateAnchor(row.id(), new float[] {-0.5f}, "m2");
        ConceptStore.ConceptRow after = concepts.byId(row.id()).orElseThrow();
        assertArrayEquals(new float[] {-0.5f}, after.anchor());
        assertEquals("m2", after.anchorModel());
        assertEquals("new phrase", after.phrase()); // anchor-only update leaves the phrase alone

        // the update path must also accept nulls (clears the vector columns back to SQL NULL)
        concepts.updatePhraseAndAnchor(row.id(), null, null, null, null);
        ConceptStore.ConceptRow cleared = concepts.byId(row.id()).orElseThrow();
        assertNull(cleared.phrase());
        assertNull(cleared.phraseVector());
        assertNull(cleared.anchor());
        assertNull(cleared.anchorModel());
    }

    @Test
    void setStatus_upserts_flipping_excluded_to_example() {
        long cid = concepts.create("c", null, null, null, null).id();
        concepts.setStatus(cid, entryId, "EXCLUDED");
        assertEquals(List.of(entryId), concepts.entryIds(cid, "EXCLUDED"));
        assertEquals(List.of(), concepts.entryIds(cid, "EXAMPLE"));
        concepts.setStatus(cid, entryId, "EXAMPLE"); // same PK row flips in place — no duplicate
        assertEquals(List.of(entryId), concepts.entryIds(cid, "EXAMPLE"));
        assertEquals(List.of(), concepts.entryIds(cid, "EXCLUDED"));
    }

    @Test
    void removeEntry_reports_whether_a_row_existed() {
        long cid = concepts.create("c", null, null, null, null).id();
        assertFalse(concepts.removeEntry(cid, entryId));
        concepts.setStatus(cid, entryId, "EXAMPLE");
        assertTrue(concepts.removeEntry(cid, entryId));
        assertFalse(concepts.removeEntry(cid, entryId));
    }

    @Test
    void deleting_entry_cascades_to_concept_membership() {
        long cid = concepts.create("c", null, null, null, null).id();
        long other = entry("hash-b");
        concepts.setStatus(cid, entryId, "EXAMPLE");
        concepts.setStatus(cid, other, "EXCLUDED");
        jdbc.sql("delete from media_entry where id = :id").param("id", other).update();
        assertEquals(List.of(entryId), concepts.entryIds(cid, "EXAMPLE"));
        assertEquals(List.of(), concepts.entryIds(cid, "EXCLUDED"));
    }

    @Test
    void delete_removes_concept_and_its_membership() {
        long cid = concepts.create("gone", null, null, null, null).id();
        concepts.setStatus(cid, entryId, "EXAMPLE");
        concepts.delete(cid);
        assertTrue(concepts.byId(cid).isEmpty());
        assertEquals(List.of(), concepts.entryIds(cid, "EXAMPLE"));
    }

    @Test
    void counts_examples_and_excluded() {
        long cid = concepts.create("c", null, null, null, null).id();
        long e2 = entry("hash-b");
        long e3 = entry("hash-c");
        assertEquals(new ConceptStore.Counts(0, 0), concepts.counts(cid));
        concepts.setStatus(cid, entryId, "EXAMPLE");
        concepts.setStatus(cid, e2, "EXAMPLE");
        concepts.setStatus(cid, e3, "EXCLUDED");
        assertEquals(new ConceptStore.Counts(2, 1), concepts.counts(cid));
        concepts.removeEntry(cid, e3);
        assertEquals(new ConceptStore.Counts(2, 0), concepts.counts(cid));
    }

    @Test
    void taggedEntryIdsByName_returns_only_entries_tagged_with_that_tag() {
        long smile = tags.create("smile", TagCategory.GENERAL).id();
        long frown = tags.create("frown", TagCategory.GENERAL).id();
        long other = entry("hash-b");
        tags.tagEntry(entryId, smile);
        tags.tagEntry(other, frown);
        assertEquals(Set.of(entryId), concepts.taggedEntryIdsByName("smile"));
        assertEquals(Set.of(), concepts.taggedEntryIdsByName("untagged"));
    }
}
