package io.github.nullotope.spicygallery.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.nullotope.spicygallery.domain.TagCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Query grammar (spec §3.1): terms, negation, ~-OR groups, phrases, wildcards, category prefixes. */
class QueryParserTest {

    @Test
    void blank_is_null() {
        assertNull(QueryParser.parse("   "));
        assertNull(QueryParser.parse(null));
    }

    @Test
    void bare_terms_are_tag_terms() {
        var q = (QueryNode.Query) QueryParser.parse("heron duck");
        assertEquals(List.of(new QueryNode.TagTerm("heron", null, false, false),
                new QueryNode.TagTerm("duck", null, false, false)), q.clauses());
    }

    @Test
    void negation_category_and_wildcard() {
        var q = (QueryNode.Query) QueryParser.parse("-duck artist:her*");
        assertEquals(new QueryNode.TagTerm("duck", null, true, false), q.clauses().get(0));
        assertEquals(new QueryNode.TagTerm("her*", TagCategory.ARTIST, false, false), q.clauses().get(1));
    }

    @Test
    void tilde_terms_form_one_or_group() {
        var q = (QueryNode.Query) QueryParser.parse("a ~b ~c");
        assertInstanceOf(QueryNode.OrGroup.class, q.clauses().get(1));
        assertEquals(2, ((QueryNode.OrGroup) q.clauses().get(1)).alternatives().size());
    }

    @Test
    void tilde_and_negation_combine() {
        var q = (QueryNode.Query) QueryParser.parse("~-duck");
        var group = (QueryNode.OrGroup) q.clauses().get(0);
        assertEquals(new QueryNode.TagTerm("duck", null, true, true), group.alternatives().get(0));
    }

    @Test
    void quoted_phrase_is_phrase_term() {
        var q = (QueryNode.Query) QueryParser.parse("\"foo: bar\"");
        assertEquals(new QueryNode.PhraseTerm("foo: bar", false, false), q.clauses().get(0));
    }

    @Test
    void unknown_prefix_is_parse_error_with_position() {
        var e = assertThrows(QueryParseException.class, () -> QueryParser.parse("duck foo:bar"));
        assertEquals("foo:bar", e.token());
        assertEquals(5, e.position());
    }

    @Test
    void unterminated_phrase_is_parse_error() {
        assertThrows(QueryParseException.class, () -> QueryParser.parse("\"oops"));
    }

    @Test
    void category_prefix_is_case_insensitive() {
        var q = (QueryNode.Query) QueryParser.parse("ARTIST:heron");
        assertEquals(new QueryNode.TagTerm("heron", TagCategory.ARTIST, false, false), q.clauses().get(0));
    }
}
