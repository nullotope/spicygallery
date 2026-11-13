package io.github.nullotope.spicygallery.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.query.QueryNode;
import io.github.nullotope.spicygallery.query.QueryParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Semantic compilation (spec §4): distance expr, exists filter, order override, cursor. */
class QuerySqlCompilerSimilarTest {

    private static QueryNode.Query ast(String q) {
        return QueryParser.parse(q);
    }

    private static float[] vec(float... prefix) {
        float[] v = new float[768];
        System.arraycopy(prefix, 0, v, 0, prefix.length);
        return v;
    }

    @Test
    void similar_compiles_exists_distance_and_default_order() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("similar:\"a b\""), null, Map.of(), null,
                vec(1, 0, 0), "m1");
        assertTrue(c.whereSql().contains("exists (select 1 from media_embedding me"
                + " where me.media_entry_id = media_entry.id and me.model = :emodel)"), c.whereSql());
        assertTrue(c.distanceExpr().contains("min(me.embedding <=> cast(:qv as vector))"), c.distanceExpr());
        assertTrue(c.distanceExpr().contains("me.model = :emodel"), c.distanceExpr());
        assertTrue(c.orderBySql().startsWith("order by (select min(me.embedding <=> cast(:qv as vector))"), c.orderBySql());
        assertTrue(c.orderBySql().endsWith("asc, id asc"), c.orderBySql());
        assertTrue(c.params().get("qv").toString().startsWith("[1.0,0.0,0.0,"),
                String.valueOf(c.params().get("qv")));
        assertEquals("m1", c.params().get("emodel"));
    }

    @Test
    void similar_with_explicit_order_keeps_plain_order() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("similar:x order:filesize_desc"), null, Map.of(),
                null, vec(1), "m1");
        assertEquals("order by size_bytes desc, id desc", c.orderBySql());
        assertTrue(c.distanceExpr() != null, "distance still filters");
        assertTrue(c.whereSql().contains("media_embedding"));
    }

    @Test
    void similar_with_random_keeps_random_order() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("similar:x order:random"), null, Map.of(), "abc",
                vec(1), "m1");
        assertEquals("order by md5(id::text || ':' || :seed)", c.orderBySql());
        assertEquals("abc", c.params().get("seed")); // seed arg
        assertTrue(c.randomSeed() != null);
        assertTrue(c.distanceExpr() != null, "exists filter still applies");
    }

    @Test
    void similar_without_vector_is_programmer_error() {
        assertThrows(IllegalStateException.class, () -> QuerySqlCompiler.compile(
                ast("similar:x"), null, Map.of(), null, null, "m1"));
    }

    @Test
    void no_similar_leaves_distance_null() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("duck"), null, Map.of("duck", List.of(1L)), null,
                null, "m1");
        assertNull(c.distanceExpr());
        assertNull(c.params().get("qv"));
    }

    @Test
    void kind_chip_composes_with_similar() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("similar:x"), Kind.VIDEO, Map.of(), null,
                vec(1), "m1");
        assertTrue(c.whereSql().contains("kind = :kind"));
        assertTrue(c.whereSql().contains("media_embedding"));
        assertFalse(c.whereSql().contains("false"));
    }
}
