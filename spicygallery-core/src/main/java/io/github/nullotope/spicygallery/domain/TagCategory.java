package io.github.nullotope.spicygallery.domain;

/** Booru tag categories (spec §5); wire names are lowercase. POOL_TYPE leads: facet grouping follows constant order. */
public enum TagCategory {
    POOL_TYPE, GENERAL, ARTIST, CHARACTER, COPYRIGHT, META;

    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static TagCategory fromWireName(String wire) {
        for (TagCategory c : values()) {
            if (c.wireName().equals(wire)) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown tag category: " + wire);
    }
}
