package io.github.nullotope.spicygallery.query;

import io.github.nullotope.spicygallery.domain.TagCategory;
import java.util.List;

/** Query AST (spec §3.1). Clauses AND together; an {@link OrGroup} ORs its alternatives. */
public sealed interface QueryNode {

    /** Tag-name pattern ('*' wildcards allowed); category null = unrestricted. */
    record TagTerm(String pattern, TagCategory category, boolean negated, boolean optional) implements QueryNode {
    }

    /** Verbatim filename/path substring — tag names cannot contain spaces. */
    record PhraseTerm(String text, boolean negated, boolean optional) implements QueryNode {
    }

    /** Structured predicate (spec §3.2); value type per {@link Metatag#parseValue}. */
    record MetaTerm(Metatag metatag, Metatag.Op op, Object value, boolean negated, boolean optional)
            implements QueryNode {
    }

    /** All ~-terms of a query, ORed together and ANDed with the remaining clauses. */
    record OrGroup(List<QueryNode> alternatives) implements QueryNode {
    }

    record Query(List<QueryNode> clauses) implements QueryNode {
    }
}
