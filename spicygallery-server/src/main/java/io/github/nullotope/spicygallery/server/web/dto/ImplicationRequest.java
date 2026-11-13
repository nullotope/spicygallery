package io.github.nullotope.spicygallery.server.web.dto;

/** POST /api/tags/implications body (spec §7). */
public record ImplicationRequest(String antecedent, String consequent) {}
