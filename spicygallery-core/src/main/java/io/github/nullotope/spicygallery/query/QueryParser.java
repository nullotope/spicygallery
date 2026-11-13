package io.github.nullotope.spicygallery.query;

import io.github.nullotope.spicygallery.domain.TagCategory;
import java.util.ArrayList;
import java.util.List;

/**
 * Query-language parser (spec §3.1). Tokens are whitespace-separated with double-quoted
 * phrases; leading '-' negates, leading '~' marks an optional term (all ~-terms of the
 * query form one OR-group); 'category:term' restricts a tag term to a category. Unknown
 * 'prefix:' tokens are parse errors — tag names cannot contain ':'.
 */
public final class QueryParser {

    static final int MAX_TOKENS = 64;
    static final int MAX_PATTERN = 100;

    private QueryParser() {
    }

    /** Parses q into a {@link QueryNode.Query}; null/blank yields null (no filter). */
    public static QueryNode.Query parse(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        List<QueryNode> clauses = new ArrayList<>();
        List<QueryNode> optional = new ArrayList<>();
        int i = 0;
        int n = q.length();
        int tokens = 0;
        while (i < n) {
            if (Character.isWhitespace(q.charAt(i))) {
                i++;
                continue;
            }
            boolean optionalFlag = false;
            boolean negated = false;
            while (i < n && (q.charAt(i) == '~' || q.charAt(i) == '-')) {
                if (q.charAt(i) == '~') {
                    optionalFlag = true;
                } else {
                    negated = true;
                }
                i++;
            }
            if (i >= n || Character.isWhitespace(q.charAt(i))) {
                throw new QueryParseException("dangling '" + (negated ? "-" : "~") + "'",
                        q.substring(Math.max(0, i - 1)), Math.max(0, i - 1));
            }
            if (q.charAt(i) == '"') {
                int close = q.indexOf('"', i + 1);
                if (close < 0) {
                    throw new QueryParseException("unterminated phrase", q.substring(i), i);
                }
                String text = q.substring(i + 1, close);
                limit(text, i);
                tokens++;
                tokenLimit(tokens, q.substring(i, close + 1), i);
                addClause(clauses, optional, new QueryNode.PhraseTerm(text, negated, optionalFlag));
                i = close + 1;
                continue;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(q.charAt(i))) {
                i++;
                // a metatag value may be quoted ("similar:"Sunset over Water"") — whitespace
                // inside the quote is part of the value; consume through the closing quote
                if (i < n && q.charAt(i - 1) == ':' && i < n && q.charAt(i) == '"') {
                    int close = q.indexOf('"', i + 1);
                    if (close < 0) {
                        throw new QueryParseException("unterminated phrase", q.substring(start), start);
                    }
                    i = close + 1;
                }
            }
            String token = q.substring(start, i);
            tokens++;
            tokenLimit(tokens, token, start);
            limit(token, start);
            addClause(clauses, optional, term(token, start, negated, optionalFlag));
        }
        flushOptional(clauses, optional);
        return new QueryNode.Query(List.copyOf(clauses));
    }

    /** Max tokens per query (spec §3.3). */
    private static void tokenLimit(int tokens, String token, int start) {
        if (tokens > MAX_TOKENS) {
            throw new QueryParseException("too many terms (max " + MAX_TOKENS + ")", token, start);
        }
    }

    /** Max chars per term/phrase (spec §3.3). */
    private static void limit(String s, int start) {
        if (s.length() > MAX_PATTERN) {
            throw new QueryParseException("term too long (max " + MAX_PATTERN + " chars)", s, start);
        }
    }

    private static QueryNode term(String token, int start, boolean negated, boolean optionalFlag) {
        int colon = token.indexOf(':');
        if (colon < 0) {
            return new QueryNode.TagTerm(token.toLowerCase(java.util.Locale.ROOT), null, negated, optionalFlag);
        }
        String prefix = token.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
        String value = token.substring(colon + 1);
        if (value.isEmpty()) {
            throw new QueryParseException("missing value after '" + prefix + ":'", token, start);
        }
        // quoted metatag value: strip the quotes (whitespace inside was preserved by the scanner)
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        TagCategory category = categoryOrNull(prefix);
        if (category != null) {
            return new QueryNode.TagTerm(value.toLowerCase(java.util.Locale.ROOT), category, negated, optionalFlag);
        }
        Metatag metatag;
        try {
            metatag = Metatag.fromName(prefix);
        } catch (IllegalArgumentException e) {
            throw new QueryParseException("unknown category or metatag '" + prefix + ":'", token, start);
        }
        if ((metatag == Metatag.SIMILAR || metatag == Metatag.CONCEPT) && (negated || optionalFlag)) {
            String name = metatag.name().toLowerCase(java.util.Locale.ROOT);
            throw new QueryParseException(negated
                    ? name + ": cannot be negated — use -tag or type filters instead"
                    : name + ": cannot be optional", token, start);
        }
        Metatag.MetaValue mv;
        try {
            mv = metatag.parseValue(value);
        } catch (IllegalArgumentException e) {
            throw new QueryParseException(e.getMessage(), token, start);
        }
        return new QueryNode.MetaTerm(metatag, mv.op(), mv.value(), negated, optionalFlag);
    }

    /** Metatags register themselves here in Task 4; categories only until then. */
    private static TagCategory categoryOrNull(String prefix) {
        try {
            return TagCategory.fromWireName(prefix);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void addClause(List<QueryNode> clauses, List<QueryNode> optional, QueryNode node) {
        if (isOptional(node)) {
            optional.add(node);
            return;
        }
        flushOptional(clauses, optional);
        clauses.add(node);
    }

    private static boolean isOptional(QueryNode node) {
        return switch (node) {
            case QueryNode.TagTerm t -> t.optional();
            case QueryNode.MetaTerm m -> m.optional();
            case QueryNode.PhraseTerm p -> p.optional();
            default -> false;
        };
    }

    private static void flushOptional(List<QueryNode> clauses, List<QueryNode> optional) {
        if (optional.isEmpty()) {
            return;
        }
        clauses.add(new QueryNode.OrGroup(List.copyOf(optional)));
        optional.clear();
    }
}
