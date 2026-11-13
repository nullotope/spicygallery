package io.github.nullotope.spicygallery.server.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Detail-page prev/next under the current query (spec §8); null = absent. The pool-scoped
 * reading variant (spec §3.9 {@code ?pool=}) additionally carries the 1-based
 * {@code position} and the flattened {@code total} — NON_NULL components, so those keys
 * exist ONLY on the pool variant and the plain shape stays byte-identical (the pools-list
 * additive {@code member} flag house pattern).
 */
public record NeighborsDto(Long prevId, Long nextId,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Integer position,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Long total) {

    /** The plain (query-scoped) shape — no position/total on the wire. */
    public NeighborsDto(Long prevId, Long nextId) {
        this(prevId, nextId, null, null);
    }
}
