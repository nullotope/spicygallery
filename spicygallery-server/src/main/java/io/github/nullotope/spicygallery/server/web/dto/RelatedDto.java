package io.github.nullotope.spicygallery.server.web.dto;

import io.github.nullotope.spicygallery.server.store.EntryQueries;
import java.util.List;

/** Detail-page Related strip (spec §5): best-match first; empty without vectors. */
public record RelatedDto(List<EntryQueries.EntryDto> items) {}
