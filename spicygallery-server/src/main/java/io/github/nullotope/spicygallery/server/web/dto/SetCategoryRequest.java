package io.github.nullotope.spicygallery.server.web.dto;

/** PATCH /api/tags/{name} body (spec §7). */
public record SetCategoryRequest(String category) {}
