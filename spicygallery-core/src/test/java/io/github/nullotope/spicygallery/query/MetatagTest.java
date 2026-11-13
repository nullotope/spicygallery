package io.github.nullotope.spicygallery.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Metatag parsing (spec §3.2): comparators, unit suffixes, strict values, limits. */
class MetatagTest {

    @Test
    void metatag_with_comparator() {
        var q = (QueryNode.Query) QueryParser.parse("width:>1000 duration:<=2m filesize:<5mb");
        var w = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(0));
        assertEquals(Metatag.WIDTH, w.metatag());
        assertEquals(Metatag.Op.GT, w.op());
        assertEquals(1000L, w.value());
        var d = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(1));
        assertEquals(Metatag.DURATION, d.metatag());
        assertEquals(Metatag.Op.LTE, d.op());
        assertEquals(120_000L, d.value());
        var f = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(2));
        assertEquals(5 * 1024 * 1024L, f.value());
        assertEquals(Metatag.Op.LT, f.op());
    }

    @Test
    void date_eq_and_order_and_is() {
        var q = (QueryNode.Query) QueryParser.parse("date:2026-11-08 is:untagged order:random");
        var date = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(0));
        assertEquals(LocalDate.of(2026, 11, 8), date.value());
        assertEquals(Metatag.Op.EQ, date.op());
        assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(1));
        var order = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(2));
        assertEquals("random", order.value());
    }

    @Test
    void type_ext_root_hash_and_negation() {
        var q = (QueryNode.Query) QueryParser.parse("type:image -ext:jpg root:library -hash:abc");
        var type = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(0));
        assertEquals("image", type.value());
        assertEquals(Metatag.Op.EQ, type.op());
        var ext = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(1));
        assertEquals("jpg", ext.value());
        assertEquals(true, ext.negated());
        var root = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(2));
        assertEquals("library", root.value());
        var hash = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(3));
        assertEquals("abc", hash.value());
        assertEquals(true, hash.negated());
    }

    @Test
    void malformed_values_are_errors() {
        assertThrows(QueryParseException.class, () -> QueryParser.parse("width:>abc"));
        assertThrows(QueryParseException.class, () -> QueryParser.parse("duration:5x"));
        assertThrows(QueryParseException.class, () -> QueryParser.parse("date:yesterday"));
        assertThrows(QueryParseException.class, () -> QueryParser.parse("type:sound"));
        assertThrows(QueryParseException.class, () -> QueryParser.parse("is:maybe"));
        assertThrows(QueryParseException.class, () -> QueryParser.parse("filesize:5tb"));
    }

    @Test
    void similar_quoted_value_preserves_spaces_and_case() {
        var q = (QueryNode.Query) QueryParser.parse("similar:\"Sunset over Water\"");
        var m = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(0));
        assertEquals(Metatag.SIMILAR, m.metatag());
        assertEquals("Sunset over Water", m.value());
        assertEquals(Metatag.Op.EQ, m.op());
        assertEquals(false, m.negated());
    }

    @Test
    void similar_bare_word() {
        var q = (QueryNode.Query) QueryParser.parse("similar:sunset");
        var m = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(0));
        assertEquals("sunset", m.value());
    }

    @Test
    void similar_composes() {
        var q = (QueryNode.Query) QueryParser.parse("duck similar:\"a b\" -type:video");
        assertEquals(3, q.clauses().size());
        assertInstanceOf(QueryNode.TagTerm.class, q.clauses().get(0));
        assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(1));
        var type = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(2));
        assertEquals(Metatag.TYPE, type.metatag());
        assertEquals(true, type.negated());
    }

    @Test
    void similar_negation_and_optional_rejected() {
        var neg = assertThrows(QueryParseException.class, () -> QueryParser.parse("-similar:x"));
        assertTrue(neg.getMessage().contains("similar"));
        var opt = assertThrows(QueryParseException.class, () -> QueryParser.parse("~similar:x"));
        assertTrue(opt.getMessage().contains("similar"));
    }

    @Test
    void similar_unterminated_quote_rejected() {
        assertThrows(QueryParseException.class, () -> QueryParser.parse("similar:\"oops"));
    }

    @Test
    void similar_value_over_100_chars_rejected() {
        assertThrows(QueryParseException.class, () -> QueryParser.parse("similar:" + "x".repeat(101)));
    }

    @Test
    void quoted_metatag_value_containing_colon() {
        var q = (QueryNode.Query) QueryParser.parse("similar:\"foo: bar\"");
        var m = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(0));
        assertEquals("foo: bar", m.value());
    }

    @Test
    void concept_bare_word_parses() {
        var q = (QueryNode.Query) QueryParser.parse("concept:dragons");
        var m = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(0));
        assertEquals(Metatag.CONCEPT, m.metatag());
        assertEquals(Metatag.Op.EQ, m.op());
        assertEquals("dragons", m.value());
        assertEquals(false, m.negated());
        assertEquals(false, m.optional());
    }

    @Test
    void concept_quoted_value_preserves_spaces_and_case() {
        var q = (QueryNode.Query) QueryParser.parse("concept:\"Blue Dragon\"");
        var m = assertInstanceOf(QueryNode.MetaTerm.class, q.clauses().get(0));
        assertEquals(Metatag.CONCEPT, m.metatag());
        assertEquals("Blue Dragon", m.value());
        assertEquals(Metatag.Op.EQ, m.op());
        assertEquals(false, m.negated());
    }

    @Test
    void concept_negation_rejected() {
        var neg = assertThrows(QueryParseException.class, () -> QueryParser.parse("-concept:x"));
        assertTrue(neg.getMessage().contains("concept: cannot be negated — use -tag or type filters instead"));
    }

    @Test
    void concept_optional_rejected() {
        var opt = assertThrows(QueryParseException.class, () -> QueryParser.parse("~concept:x"));
        assertTrue(opt.getMessage().contains("concept: cannot be optional"));
    }

    @Test
    void limits_are_enforced() {
        assertThrows(QueryParseException.class, () -> QueryParser.parse("a ".repeat(64) + "b")); // 65 tokens
        assertThrows(QueryParseException.class, () -> QueryParser.parse("x".repeat(101)));
        assertThrows(QueryParseException.class, () -> QueryParser.parse("\"" + "y".repeat(101) + "\""));
    }
}
