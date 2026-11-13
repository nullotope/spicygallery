package io.github.nullotope.spicygallery.domain;

/**
 * Metadata probed from a media file. {@code durationMs} is null for images; the mime
 * is the canonical type of the successfully decoded content (images) or of the
 * container extension (video, per spec §6.3).
 */
public record ProbeResult(int width, int height, Long durationMs, String mime) {}
