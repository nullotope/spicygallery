package io.github.nullotope.spicygallery.server.web.dto;

/**
 * Tag on the wire (spec §7). {@code taggedBy} is the media_tag provenance of THIS entry's
 * link ('user' or e.g. 'hydra-3.5'); it is {@code null} for tag rows without an entry
 * context (the /tags listing, category change) — the client shows a provenance tooltip
 * only for non-user values.
 */
public record TagDto(long id, String name, String category, int postCount, String taggedBy) {}
