package io.github.nullotope.spicygallery.server.web.dto;

/** Autocomplete hit (spec §7): prefix matches first, then substring, then alias names. */
public record AutocompleteDto(String name, String category, int postCount, String aliasOf) {}
