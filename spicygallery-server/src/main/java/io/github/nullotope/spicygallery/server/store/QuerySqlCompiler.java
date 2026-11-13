package io.github.nullotope.spicygallery.server.store;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.query.Metatag;
import io.github.nullotope.spicygallery.query.QueryNode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Compiles a parsed query (spec §3) to a WHERE fragment over bare {@code media_entry}
 * columns plus an ORDER-BY plan. Pure — the caller expands tag patterns to canonical
 * IDs (spec §3.4) and passes them in; this class never touches the DB.
 */
public final class QuerySqlCompiler {

    private static final Set<String> ORDERS = Set.of(
            "id_asc", "id_desc", "filesize_asc", "filesize_desc", "random");

    private QuerySqlCompiler() {
    }

    public static CompiledQuery compile(QueryNode ast, Kind kind, Map<String, List<Long>> expansions,
                                        String seed, float[] queryVector, String embeddingModel) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Integer> counters = new java.util.HashMap<>();
        Supplier<String> nextKey = () -> prefixKey(counters, "m");
        Supplier<String> nextTagKey = () -> prefixKey(counters, "t");
        Supplier<String> nextPatternKey = () -> prefixKey(counters, "p");
        StringBuilder where = new StringBuilder();
        String order = null;
        Metatag vectorTag = null; // similar:/concept: share the one query-vector slot
        if (kind != null) {
            and(where, "kind = :kind");
            params.put("kind", kind.wireName());
        }
        if (ast instanceof QueryNode.Query q) {
            for (QueryNode clause : q.clauses()) {
                if (clause instanceof QueryNode.MetaTerm m && m.metatag() == Metatag.ORDER) {
                    if (order == null) {
                        order = (String) m.value(); // ORDER applies globally; negation is meaningless
                    }
                    continue;
                }
                if (clause instanceof QueryNode.MetaTerm m
                        && (m.metatag() == Metatag.SIMILAR || m.metatag() == Metatag.CONCEPT)) {
                    if (vectorTag != null && vectorTag != m.metatag()) {
                        throw new IllegalArgumentException("only one of similar:/concept: per query");
                    }
                    vectorTag = m.metatag(); // repeats are fine; the controller resolves the first
                    continue;
                }
                and(where, predicate(clause, expansions, params, nextKey, nextTagKey, nextPatternKey));
            }
        }
        String distanceExpr = null;
        if (vectorTag != null) {
            if (queryVector == null) {
                throw new IllegalStateException(
                        "similar:/concept: query without a resolved query vector — the controller must "
                                + "resolve one (EmbeddingService.textVector / ConceptService.anchorVectorForQuery)");
            }
            params.put("qv", PgVector.of(queryVector));
            params.put("emodel", embeddingModel);
            distanceExpr = "(select min(me.embedding <=> cast(:qv as vector)) from media_embedding me"
                    + " where me.media_entry_id = media_entry.id and me.model = :emodel)";
            and(where, "exists (select 1 from media_embedding me"
                    + " where me.media_entry_id = media_entry.id and me.model = :emodel)");
        }
        String orderBySql;
        String randomSeed = null;
        if ("random".equals(order)) {
            orderBySql = orderBy(order); // validates
            String s = seed != null && seed.matches("[a-zA-Z0-9]{1,64}") ? seed
                    : Long.toHexString(ThreadLocalRandom.current().nextLong());
            params.put("seed", s);
            randomSeed = s;
        } else if (distanceExpr != null && order == null) {
            orderBySql = "order by " + distanceExpr + " asc, id asc";
        } else {
            orderBySql = orderBy(order);
        }
        return new CompiledQuery(where.toString(), params, orderBySql, randomSeed, distanceExpr);
    }

    private static String prefixKey(Map<String, Integer> counters, String prefix) {
        return prefix + (counters.merge(prefix, 1, Integer::sum) - 1);
    }

    private static String orderBy(String order) {
        if (order == null) {
            return "order by id desc";
        }
        if (!ORDERS.contains(order)) {
            throw new IllegalArgumentException("unknown order:" + order);
        }
        return switch (order) {
            case "id_asc" -> "order by id asc";
            case "id_desc" -> "order by id desc";
            case "filesize_asc" -> "order by size_bytes asc, id asc";
            case "filesize_desc" -> "order by size_bytes desc, id desc";
            case "random" -> "order by md5(id::text || ':' || :seed)";
            default -> "order by id desc";
        };
    }

    private static String predicate(QueryNode node, Map<String, List<Long>> expansions, Map<String, Object> params,
                                    Supplier<String> nextKey, Supplier<String> nextTagKey,
                                    Supplier<String> nextPatternKey) {
        return switch (node) {
            case QueryNode.TagTerm t -> tagTerm(t, expansions, params, nextTagKey, nextPatternKey);
            case QueryNode.PhraseTerm p -> wrap(p.negated(), phrase(p.text(), params, nextPatternKey));
            case QueryNode.MetaTerm m -> wrap(m.negated(), meta(m, params, nextKey));
            case QueryNode.OrGroup g -> orGroup(g, expansions, params, nextKey, nextTagKey, nextPatternKey);
            case QueryNode.Query q -> throw new IllegalArgumentException("nested query");
        };
    }

    private static String tagTerm(QueryNode.TagTerm t, Map<String, List<Long>> expansions, Map<String, Object> params,
                                  Supplier<String> nextTagKey, Supplier<String> nextPatternKey) {
        String key = t.category() == null ? t.pattern() : t.category().wireName() + ":" + t.pattern();
        List<Long> ids = expansions.getOrDefault(key, List.of());
        String tagMatch = null;
        if (!ids.isEmpty()) {
            String idParam = nextTagKey.get();
            params.put(idParam, ids);
            tagMatch = "exists (select 1 from media_tag mt"
                    + " where mt.media_entry_id = media_entry.id and mt.tag_id in (:" + idParam + "))";
        }
        if (t.category() != null) {
            if (tagMatch == null) {
                return wrap(t.negated(), "false");
            }
            return wrap(t.negated(), tagMatch);
        }
        String patternParam = nextPatternKey.get();
        params.put(patternParam, containsPattern(t.pattern()));
        String fileMatch = "(file_name ilike :" + patternParam + " escape '\\'"
                + " or root_name || '/' || rel_path ilike :" + patternParam + " escape '\\')";
        if (tagMatch == null) {
            return wrap(t.negated(), fileMatch);
        }
        return wrap(t.negated(), "(" + tagMatch + " or " + fileMatch + ")");
    }

    private static String orGroup(QueryNode.OrGroup g, Map<String, List<Long>> expansions, Map<String, Object> params,
                                  Supplier<String> nextKey, Supplier<String> nextTagKey,
                                  Supplier<String> nextPatternKey) {
        StringBuilder sb = new StringBuilder("(");
        boolean any = false;
        for (QueryNode alt : g.alternatives()) {
            String pred = predicate(alt, expansions, params, nextKey, nextTagKey, nextPatternKey);
            if (pred == null) {
                continue; // e.g. an ORDER term inside a ~-group carries no predicate
            }
            if (any) {
                sb.append(" or ");
            }
            sb.append(pred);
            any = true;
        }
        return any ? sb.append(")").toString() : null;
    }

    private static String phrase(String text, Map<String, Object> params, Supplier<String> nextPatternKey) {
        String patternParam = nextPatternKey.get();
        params.put(patternParam, containsPattern(text));
        return "(file_name ilike :" + patternParam + " escape '\\'"
                + " or root_name || '/' || rel_path ilike :" + patternParam + " escape '\\')";
    }

    private static String meta(QueryNode.MetaTerm m, Map<String, Object> params, Supplier<String> nextKey) {
        return switch (m.metatag()) {
            case TYPE -> {
                String key = nextKey.get();
                params.put(key, m.value());
                yield "kind = :" + key;
            }
            case EXT -> {
                String key = nextKey.get();
                params.put(key, wildPattern((String) m.value()));
                yield "ext ilike :" + key;
            }
            case ROOT -> {
                String key = nextKey.get();
                params.put(key, m.value());
                yield "root_name = :" + key;
            }
            case WIDTH -> {
                String key = nextKey.get();
                params.put(key, m.value());
                yield "width " + opSql(m.op()) + " :" + key;
            }
            case HEIGHT -> {
                String key = nextKey.get();
                params.put(key, m.value());
                yield "height " + opSql(m.op()) + " :" + key;
            }
            case DURATION -> {
                String key = nextKey.get();
                params.put(key, m.value());
                yield "duration_ms " + opSql(m.op()) + " :" + key;
            }
            case FILESIZE -> {
                String key = nextKey.get();
                params.put(key, m.value());
                yield "size_bytes " + opSql(m.op()) + " :" + key;
            }
            case TAGCOUNT -> {
                String key = nextKey.get();
                params.put(key, m.value());
                yield "(select count(*) from media_tag mt"
                        + " where mt.media_entry_id = media_entry.id) " + opSql(m.op()) + " :" + key;
            }
            case IS -> "untagged".equals(m.value())
                    ? "not exists (select 1 from media_tag mt where mt.media_entry_id = media_entry.id)"
                    : "exists (select 1 from media_tag mt where mt.media_entry_id = media_entry.id)";
            case HASH -> {
                String key = nextKey.get();
                params.put(key, likeEscape((String) m.value()) + "%");
                yield "content_hash ilike :" + key;
            }
            case DATE -> datePredicate(m, nextKey.get(), params);
            case ORDER -> null; // handled globally in compile()
            case SIMILAR -> null; // handled globally in compile() (distance expr)
            case CONCEPT -> null; // handled globally in compile() (distance expr)
        };
    }

    private static String datePredicate(QueryNode.MetaTerm m, String key, Map<String, Object> params) {
        LocalDate d = (LocalDate) m.value();
        LocalDate next = d.plusDays(1);
        return switch (m.op()) {
            case EQ -> {
                params.put(key + "a", d);
                params.put(key + "b", next);
                yield "imported_at >= :" + key + "a and imported_at < :" + key + "b";
            }
            case GT -> {
                params.put(key, next);
                yield "imported_at >= :" + key;
            }
            case GTE -> {
                params.put(key, d);
                yield "imported_at >= :" + key;
            }
            case LT -> {
                params.put(key, d);
                yield "imported_at < :" + key;
            }
            case LTE -> {
                params.put(key, next);
                yield "imported_at < :" + key;
            }
        };
    }

    private static String opSql(Metatag.Op op) {
        return switch (op) {
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case EQ -> "=";
        };
    }

    private static String wrap(boolean negated, String pred) {
        if (pred == null) {
            return null;
        }
        return negated ? "not (" + pred + ")" : pred;
    }

    private static void and(StringBuilder where, String pred) {
        if (pred == null) {
            return;
        }
        if (!where.isEmpty()) {
            where.append(" and ");
        }
        where.append(pred);
    }

    /** Escapes \\, % and _ and wraps in %…% (EntryQueries.likePattern semantics). */
    static String containsPattern(String s) {
        return "%" + likeEscape(s) + "%";
    }

    /** Escapes LIKE specials, then '*' wildcards become '%'. */
    static String wildPattern(String s) {
        return likeEscape(s).replace("*", "%");
    }

    public static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
