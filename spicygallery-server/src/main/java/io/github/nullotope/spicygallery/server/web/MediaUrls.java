package io.github.nullotope.spicygallery.server.web;

import java.time.Instant;

/**
 * Content-versioned media URLs (spec §7): {@code ?v=<updatedAt millis>} busts caches
 * after re-ingest; the parameter is ignored server-side.
 */
public final class MediaUrls {

    private MediaUrls() {}

    public static String thumbUrl(long id, Instant updatedAt) {
        return "/api/entries/" + id + "/thumb?v=" + updatedAt.toEpochMilli();
    }

    public static String fileUrl(long id, Instant updatedAt) {
        return "/api/entries/" + id + "/file?v=" + updatedAt.toEpochMilli();
    }
}
