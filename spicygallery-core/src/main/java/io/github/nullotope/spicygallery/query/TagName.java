package io.github.nullotope.spicygallery.query;

/**
 * Tag identity rules (spec §5): names are lowercase, underscore-joined, from a small
 * charset — the absence of ':' in names is what makes query prefixes unambiguous.
 */
public final class TagName {

    public static final int MAX_LENGTH = 100;

    private TagName() {
    }

    /** Normalizes user input to a canonical tag name; throws on invalid input. */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("tag name is required");
        }
        // ':' is dropped anywhere (copy/paste of prefixed queries); names never contain it.
        String name = raw.trim().toLowerCase(java.util.Locale.ROOT).replace(":", "");
        name = name.replaceAll("\\s+", "_");
        // punctuation outside the charset is tolerated at the edges ("Oldname!" → oldname),
        // but an interior disallowed character is a validation error ("ab/cd").
        name = name.replaceAll("^[^a-z0-9_()+.'-]+", "").replaceAll("[^a-z0-9_()+.'-]+$", "");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("tag name is empty: '" + raw + "'");
        }
        if (name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("tag name longer than " + MAX_LENGTH + " chars: '" + raw + "'");
        }
        if (!name.matches("[a-z0-9_()+.'-]+")) {
            throw new IllegalArgumentException("tag name contains invalid characters: '" + raw + "'");
        }
        if (!name.matches(".*[a-z0-9].*")) {
            throw new IllegalArgumentException("tag name has no letters or digits: '" + raw + "'");
        }
        return name;
    }
}
