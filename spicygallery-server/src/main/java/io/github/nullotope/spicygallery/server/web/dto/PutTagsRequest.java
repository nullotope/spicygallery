package io.github.nullotope.spicygallery.server.web.dto;

import java.util.List;

/** PUT /api/entries/{id}/tags body (spec §7): full replacement set; category optional. */
public record PutTagsRequest(List<TagInput> tags) {

    /** Wire form of a tag assignment; category as an optional wire string. */
    public record TagInput(String name, String category) {}
}
