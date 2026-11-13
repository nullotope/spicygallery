package io.github.nullotope.spicygallery.server.web.dto;

/** POST /api/tags/aliases body (spec §7): merge semantics. */
public record AliasRequest(String antecedent, String consequent) {}
