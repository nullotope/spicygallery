package io.github.nullotope.spicygallery.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class MigrationTest extends PostgresBase {

    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @org.junit.jupiter.api.BeforeEach
    void clean() {
        jdbc.sql("delete from media_tag").update();
        jdbc.sql("delete from tag_implication").update();
        jdbc.sql("delete from tag_alias").update();
        jdbc.sql("delete from tag").update();
        jdbc.sql("delete from media_entry").update();
        jdbc.sql("delete from import_run").update();
    }

    @Test
    void v1_tables_exist(@Autowired JdbcClient jdbc) {
        Integer entries = jdbc.sql("select count(*) from media_entry")
                .query(Integer.class).single();
        assertEquals(0, entries);
        Integer runs = jdbc.sql("select count(*) from import_run")
                .query(Integer.class).single();
        assertEquals(0, runs);
    }

    @Test
    void v3_embedding_tables_exist(@Autowired JdbcClient jdbc) {
        assertEquals(0, jdbc.sql("select count(*) from media_embedding").query(Integer.class).single());
        String ext = jdbc.sql("select extname from pg_extension where extname = 'vector'")
                .query(String.class).optional().orElse("");
        assertEquals("vector", ext);
        // column is typed vector(768): inserting a 3-dim literal must fail
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.sql("insert into media_embedding (media_entry_id, model, embedding) "
                        + "values (0, 'm', '[1,2,3]')").update());
    }

    @Test
    void v2_tag_tables_exist(@Autowired JdbcClient jdbc) {
        for (String table : List.of("tag", "tag_alias", "tag_implication", "media_tag")) {
            assertEquals(0, jdbc.sql("select count(*) from " + table).query(Integer.class).single(),
                    table);
        }
        String trgm = jdbc.sql("select extname from pg_extension where extname = 'pg_trgm'")
                .query(String.class).optional().orElse("");
        assertEquals("pg_trgm", trgm);
    }

    @Test
    void entry_identity_is_root_plus_rel_path(@Autowired JdbcClient jdbc) {
        jdbc.sql("""
                insert into media_entry (kind, status, root_name, rel_path, file_name, ext, mime,
                        size_bytes, content_hash, media_mtime)
                values ('image', 'ok', 'alpha', 'x.png', 'x.png', 'png', 'image/png',
                        1, 'hash', now())
                """).update();
        // same rel_path under another root is a different entry — no collision
        jdbc.sql("""
                insert into media_entry (kind, status, root_name, rel_path, file_name, ext, mime,
                        size_bytes, content_hash, media_mtime)
                values ('image', 'ok', 'beta', 'x.png', 'x.png', 'png', 'image/png',
                        1, 'hash', now())
                """).update();
        assertEquals(2, jdbc.sql("select count(*) from media_entry")
                .query(Integer.class).single());
    }
}
