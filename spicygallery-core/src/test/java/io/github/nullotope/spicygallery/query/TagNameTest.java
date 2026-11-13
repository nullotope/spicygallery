package io.github.nullotope.spicygallery.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.nullotope.spicygallery.domain.TagCategory;
import org.junit.jupiter.api.Test;

/** Tag identity rules (spec §5): normalization + category wire names. */
class TagNameTest {

    @Test
    void lowercases_and_underscores() {
        assertEquals("heron_oldname", TagName.normalize("  Heron   Oldname! "));
    }

    @Test
    void allowed_charset() {
        assertEquals("rezero_kara", TagName.normalize("Re:Zero kara")); // input ':' is dropped — names never contain ':'
    }

    @Test
    void rejects_blank_and_all_punctuation() {
        assertThrows(IllegalArgumentException.class, () -> TagName.normalize("   "));
        assertThrows(IllegalArgumentException.class, () -> TagName.normalize("---"));
    }

    @Test
    void rejects_too_long_and_bad_chars() {
        assertThrows(IllegalArgumentException.class, () -> TagName.normalize("x".repeat(101)));
        assertThrows(IllegalArgumentException.class, () -> TagName.normalize("ab/cd"));
    }

    @Test
    void category_wire_roundtrip() {
        assertEquals("copyright", TagCategory.COPYRIGHT.wireName());
        assertEquals(TagCategory.COPYRIGHT, TagCategory.fromWireName("copyright"));
        assertThrows(IllegalArgumentException.class, () -> TagCategory.fromWireName("mood"));
    }
}
