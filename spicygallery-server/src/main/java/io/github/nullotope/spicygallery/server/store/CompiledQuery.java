package io.github.nullotope.spicygallery.server.store;

import java.util.Map;

/**
 * SQL-ready query (spec §3.4/§4): WHERE fragment over bare {@code media_entry} columns —
 * the caller prepends {@code status = 'ok'} — plus named params and the ORDER-BY plan.
 * {@code randomSeed} is the seed string echoed to the client as the {@code rs} param.
 * {@code distanceExpr} is non-null iff the query carries {@code similar:} — a scalar
 * subquery giving the per-entry best cosine distance ({@code :qv}/{@code :emodel} in
 * params); EntryQueries then orders/pages on it and fills {@code score}.
 */
public record CompiledQuery(String whereSql, Map<String, Object> params, String orderBySql,
                            String randomSeed, String distanceExpr) {

    /** Convenience for the common non-semantic case. */
    public CompiledQuery(String whereSql, Map<String, Object> params, String orderBySql, String randomSeed) {
        this(whereSql, params, orderBySql, randomSeed, null);
    }
}
