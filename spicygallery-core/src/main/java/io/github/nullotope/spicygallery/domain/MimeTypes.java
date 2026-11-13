package io.github.nullotope.spicygallery.domain;

import java.util.Map;
import java.util.Optional;

/**
 * Extension → mime map (spec §6.3). Used for video entries, for the mime of error rows,
 * and as the image fallback when a file cannot be decoded. Input is a lowercased
 * extension without the dot.
 */
public final class MimeTypes {

    private static final Map<String, String> BY_EXT = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("jpe", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mkv", "video/x-matroska"),
            Map.entry("mov", "video/quicktime"));

    private MimeTypes() {}

    public static Optional<String> forExt(String ext) {
        return Optional.ofNullable(BY_EXT.get(ext));
    }
}
