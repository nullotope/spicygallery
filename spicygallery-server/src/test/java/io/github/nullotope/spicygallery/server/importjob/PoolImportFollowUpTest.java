package io.github.nullotope.spicygallery.server.importjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.pools.PoolService;
import io.github.nullotope.spicygallery.server.pools.PoolService.PoolDetail;
import io.github.nullotope.spicygallery.server.store.JdbcMediaStoreSink;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The import follow-up's real implementation (spec §3.8): members ordered by
 * (directory, NaturalOrder filename, id), the {@code Import YYYY-MM-DD HH:MM} name and
 * the system tag — with the pool-name dedupe covered at the PoolService level.
 */
@SpringBootTest
class PoolImportFollowUpTest extends PostgresBase {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired PoolImportFollowUp followUp;
    @Autowired PoolService poolsService;
    @Autowired JdbcMediaStoreSink sink;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from import_run").update(); // BEFORE pool: import_run.pool_id FK (no cascade)
        jdbc.sql("delete from pool").update();
        jdbc.sql("delete from media_entry").update();
    }

    private long entry(String relPath) {
        String fileName = relPath.substring(relPath.lastIndexOf('/') + 1);
        return sink.insert(new NewEntry(Kind.IMAGE, "library", relPath, fileName, "png",
                "image/png", 10, "hash-" + relPath, 8, 4, null, T)).value();
    }

    @Test
    void orders_members_by_directory_then_natural_filename_then_id() {
        long top = entry("top.png");       // root top level: directory ''
        long b1 = entry("b/1.png");
        long b2 = entry("b/2.png");
        long b10 = entry("b/10.png");
        long z1 = entry("z/a1.png");
        long z10 = entry("z/a10.png");

        // deliberately shuffled: the follow-up must impose (directory, natural, id) itself
        long poolId = followUp.createPool(List.of(z10, b2, top, b10, z1, b1));

        PoolDetail detail = poolsService.detail(poolId);
        assertEquals(List.of(top, b1, b2, b10, z1, z10),
                detail.members().stream().map(m -> m.entry().id()).toList(),
                "directory '' < 'b' < 'z'; natural: 1 < 2 < 10; ties by id");
        assertTrue(detail.pool().name().matches("Import \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"),
                "name must be 'Import YYYY-MM-DD HH:MM', was: " + detail.pool().name());
        assertTrue(detail.pool().tags().stream()
                        .anyMatch(t -> "pool".equals(t.name()) && "pool_type".equals(t.category())),
                "system tag attached");
    }

    @Test
    void entries_deleted_between_run_and_follow_up_drop_out_silently() {
        long kept = entry("kept.png");
        long gone = entry("gone.png");
        jdbc.sql("delete from media_entry where id = :id").param("id", gone).update();

        long poolId = followUp.createPool(List.of(gone, kept));

        assertEquals(List.of("e:" + kept),
                poolsService.detail(poolId).members().stream()
                        .map(m -> "e:" + m.entry().id()).toList());
    }
}
