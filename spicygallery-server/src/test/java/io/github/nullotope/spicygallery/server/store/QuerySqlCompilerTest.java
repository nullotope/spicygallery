package io.github.nullotope.spicygallery.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.query.QueryNode;
import io.github.nullotope.spicygallery.query.QueryParser;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** AST→SQL compilation (spec §3.4): fragments + params, no DB involved. */
class QuerySqlCompilerTest {

    private static QueryNode.Query ast(String q) {
        return QueryParser.parse(q);
    }

    @Test
    void null_ast_gives_unfiltered_default_order() {
        CompiledQuery c = QuerySqlCompiler.compile(null, null, Map.of(), null, null, null);
        assertEquals("", c.whereSql());
        assertEquals("order by id desc", c.orderBySql());
        assertTrue(c.params().isEmpty());
        assertNull(c.randomSeed());
    }

    @Test
    void bare_term_compiles_to_tag_or_filename() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("duck"), null, Map.of("duck", List.of(7L, 9L)), null, null, null);
        assertTrue(c.whereSql().contains("exists (select 1 from media_tag mt"
                + " where mt.media_entry_id = media_entry.id and mt.tag_id in (:t0))"));
        assertTrue(c.whereSql().contains("file_name ilike :p0"));
        assertTrue(c.whereSql().contains("root_name || '/' || rel_path ilike :p0"));
        assertEquals(List.of(7L, 9L), c.params().get("t0"));
        assertEquals("%duck%", c.params().get("p0"));
    }

    @Test
    void negated_and_or_group_shapes() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("-duck a ~b ~c"),
                null, Map.of("duck", List.of(7L), "a", List.of(1L), "b", List.of(2L), "c", List.of(3L)), null, null, null);
        assertTrue(c.whereSql().startsWith("not ((exists (select 1 from media_tag mt"
                + " where mt.media_entry_id = media_entry.id and mt.tag_id in (:t0))"), c.whereSql());
        // ~-terms keep their filename fallback inside the OR-group
        String expectedGroup = "((exists (select 1 from media_tag mt where mt.media_entry_id = media_entry.id"
                + " and mt.tag_id in (:t2)) or (file_name ilike :p2 escape '\\'"
                + " or root_name || '/' || rel_path ilike :p2 escape '\\'))"
                + " or (exists (select 1 from media_tag mt where mt.media_entry_id = media_entry.id"
                + " and mt.tag_id in (:t3)) or (file_name ilike :p3 escape '\\'"
                + " or root_name || '/' || rel_path ilike :p3 escape '\\')))";
        assertTrue(c.whereSql().endsWith(expectedGroup), c.whereSql());
    }

    @Test
    void empty_expansion_of_categorised_term_matches_nothing() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("artist:ghost"),
                null, Map.of("artist:ghost", List.of()), null, null, null);
        assertEquals("false", c.whereSql());
    }

    @Test
    void empty_expansion_of_bare_term_still_matches_filename() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("ghost"),
                null, Map.of("ghost", List.of()), null, null, null);
        assertFalse(c.whereSql().contains("false"));
        assertTrue(c.whereSql().contains("file_name ilike :p0"));
    }

    @Test
    void every_metatag_predicate() {
        CompiledQuery c = QuerySqlCompiler.compile(ast(
                "type:image ext:jpe root:library width:>1000 duration:<=2m filesize:<5mb"
                        + " date:2026-11-08 tagcount:>5 is:untagged hash:abc"),
                null, Map.of(), null, null, null);
        String w = c.whereSql();
        assertTrue(w.contains("kind = :m0"));
        assertEquals("image", c.params().get("m0"));
        assertTrue(w.contains("ext ilike :m1"));
        assertEquals("jpe", c.params().get("m1"));
        assertTrue(w.contains("root_name = :m2"));
        assertTrue(w.contains("width > :m3"));
        assertEquals(1000L, c.params().get("m3"));
        assertTrue(w.contains("duration_ms <= :m4"));
        assertEquals(120_000L, c.params().get("m4"));
        assertTrue(w.contains("size_bytes < :m5"));
        assertEquals(5 * 1024 * 1024L, c.params().get("m5"));
        assertTrue(w.contains("imported_at >= :m6a and imported_at < :m6b"));
        assertEquals(LocalDate.of(2026, 11, 8), c.params().get("m6a"));
        assertEquals(LocalDate.of(2026, 11, 9), c.params().get("m6b"));
        assertTrue(w.contains("(select count(*) from media_tag mt"
                + " where mt.media_entry_id = media_entry.id) > :m7"));
        assertEquals(5L, c.params().get("m7"));
        assertTrue(w.contains("not exists (select 1 from media_tag mt"
                + " where mt.media_entry_id = media_entry.id)"));
        assertTrue(w.contains("content_hash ilike :m8"));
        assertEquals("abc%", c.params().get("m8"));
    }

    @Test
    void order_variants_and_random_seed() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("order:random"), null, Map.of(), "abc123", null, null);
        assertEquals("order by md5(id::text || ':' || :seed)", c.orderBySql());
        assertEquals("abc123", c.params().get("seed"));
        assertEquals("abc123", c.randomSeed());

        CompiledQuery f = QuerySqlCompiler.compile(ast("order:filesize_desc"), null, Map.of(), null, null, null);
        assertEquals("order by size_bytes desc, id desc", f.orderBySql());
        assertNull(f.randomSeed());

        CompiledQuery a = QuerySqlCompiler.compile(ast("order:id_asc"), null, Map.of(), null, null, null);
        assertEquals("order by id asc", a.orderBySql());
    }

    @Test
    void unknown_order_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> QuerySqlCompiler.compile(ast("order:chaos"), null, Map.of(), null, null, null));
    }

    @Test
    void kind_chip_anded_in() {
        CompiledQuery c = QuerySqlCompiler.compile(null, Kind.VIDEO, Map.of(), null, null, null);
        assertTrue(c.whereSql().contains("kind = :kind"));
        assertEquals("video", c.params().get("kind"));
    }

    @Test
    void only_negation_matches_rest() {
        // Review Focus 3: a pure exclusion must keep matching everything else
        CompiledQuery c = QuerySqlCompiler.compile(ast("-type:video"), null, Map.of(), null, null, null);
        assertFalse(c.whereSql().contains("false"));
        assertTrue(c.whereSql().contains("not (kind = :m0)"));
    }

    @Test
    void phrase_matches_filename_only() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("\"foo: bar\""), null, Map.of(), null, null, null);
        assertTrue(c.whereSql().contains("file_name ilike :p0"));
        assertTrue(c.whereSql().contains("root_name || '/' || rel_path ilike :p0"));
        assertFalse(c.whereSql().contains("media_tag"));
        assertEquals("%foo: bar%", c.params().get("p0"));
    }

    @Test
    void wildcard_ext_translates_stars() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("ext:jp*"), null, Map.of(), null, null, null);
        assertEquals("jp%", c.params().get("m0"));
    }
}
