package io.github.nullotope.spicygallery.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.query.QueryNode;
import io.github.nullotope.spicygallery.query.QueryParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code concept:} compiles exactly like {@code similar:} (spec §3.4): one distance
 *  ordering off the resolved anchor vector; repeated concept: terms are one ordering
 *  (the controller resolves the first); mixing similar: and concept: is a 400. */
class QuerySqlCompilerConceptTest {

    private static QueryNode.Query ast(String q) {
        return QueryParser.parse(q);
    }

    private static float[] vec(float... prefix) {
        float[] v = new float[768];
        System.arraycopy(prefix, 0, v, 0, prefix.length);
        return v;
    }

    @Test
    void concept_compiles_exists_distance_and_default_order() {
        CompiledQuery c = QuerySqlCompiler.compile(ast("concept:herbert"), null, Map.of(), null,
                vec(1, 0, 0), "m1");
        assertTrue(c.whereSql().contains("exists (select 1 from media_embedding me"
                + " where me.media_entry_id = media_entry.id and me.model = :emodel)"), c.whereSql());
        assertTrue(c.distanceExpr().contains("min(me.embedding <=> cast(:qv as vector))"), c.distanceExpr());
        assertTrue(c.orderBySql().startsWith("order by (select min(me.embedding <=> cast(:qv as vector))"),
                c.orderBySql());
        assertTrue(c.orderBySql().endsWith("asc, id asc"), c.orderBySql());
        assertTrue(c.params().get("qv").toString().startsWith("[1.0,0.0,0.0,"),
                String.valueOf(c.params().get("qv")));
        assertEquals("m1", c.params().get("emodel"));
    }

    @Test
    void repeated_concept_terms_are_one_distance_ordering() {
        // the parser allows repeats (first-wins is the controller's rule); compile itself
        // must stay a single exists/distance plan
        CompiledQuery c = QuerySqlCompiler.compile(ast("concept:a concept:b"), null, Map.of(), null,
                vec(1), "m1");
        assertTrue(c.distanceExpr() != null, "repeats still carry the distance ordering");
        assertTrue(c.orderBySql().startsWith("order by (select min("), c.orderBySql());
    }

    @Test
    void concept_without_vector_is_programmer_error_naming_both() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> QuerySqlCompiler.compile(ast("concept:x"), null, Map.of(), null, null, "m1"));
        assertTrue(e.getMessage().contains("similar:/concept:"), e.getMessage());
    }

    @Test
    void mixing_similar_and_concept_is_400_either_order() {
        for (String q : new String[] {"similar:x concept:y", "concept:y similar:x"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> QuerySqlCompiler.compile(ast(q), null, Map.of(), null, vec(1), "m1"),
                    "query: " + q);
            assertEquals("only one of similar:/concept: per query", e.getMessage());
        }
    }
}
