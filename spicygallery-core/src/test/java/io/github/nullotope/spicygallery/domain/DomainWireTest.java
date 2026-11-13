package io.github.nullotope.spicygallery.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DomainWireTest {

    @Test
    void kind_wire_names() {
        assertEquals("image", Kind.IMAGE.wireName());
        assertEquals("video", Kind.VIDEO.wireName());
        assertEquals(Kind.IMAGE, Kind.fromWireName("image"));
        assertEquals(Kind.VIDEO, Kind.fromWireName("video"));
        assertThrows(IllegalArgumentException.class, () -> Kind.fromWireName("audio"));
    }

    @Test
    void status_wire_names() {
        assertEquals("ok", Status.OK.wireName());
        assertEquals("missing", Status.MISSING.wireName());
        assertEquals("error", Status.ERROR.wireName());
        assertEquals(Status.MISSING, Status.fromWireName("missing"));
        assertThrows(IllegalArgumentException.class, () -> Status.fromWireName("gone"));
    }

    @Test
    void tag_category_wire_names() {
        assertArrayEquals(new TagCategory[] {
                TagCategory.POOL_TYPE, TagCategory.GENERAL, TagCategory.ARTIST,
                TagCategory.CHARACTER, TagCategory.COPYRIGHT, TagCategory.META},
                TagCategory.values()); // pool_type first: facet grouping follows constant order
        assertEquals("pool_type", TagCategory.POOL_TYPE.wireName());
        assertEquals(TagCategory.POOL_TYPE, TagCategory.fromWireName("pool_type"));
    }

    @Test
    void mime_map() {
        assertEquals("image/jpeg", MimeTypes.forExt("jpe").orElseThrow());
        assertEquals("image/jpeg", MimeTypes.forExt("jpg").orElseThrow());
        assertEquals("image/jpeg", MimeTypes.forExt("jpeg").orElseThrow());
        assertEquals("image/png", MimeTypes.forExt("png").orElseThrow());
        assertEquals("image/gif", MimeTypes.forExt("gif").orElseThrow());
        assertEquals("image/webp", MimeTypes.forExt("webp").orElseThrow());
        assertEquals("image/bmp", MimeTypes.forExt("bmp").orElseThrow());
        assertEquals("video/mp4", MimeTypes.forExt("mp4").orElseThrow());
        assertEquals("video/webm", MimeTypes.forExt("webm").orElseThrow());
        assertEquals("video/x-matroska", MimeTypes.forExt("mkv").orElseThrow());
        assertEquals("video/quicktime", MimeTypes.forExt("mov").orElseThrow());
        assertTrue(MimeTypes.forExt("txt").isEmpty());
        assertTrue(MimeTypes.forExt("").isEmpty());
    }
}
