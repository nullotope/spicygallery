package io.github.nullotope.spicygallery.domain;

/** Typed id of a media entry — no raw longs in domain logic. */
public record EntryId(long value) {}
