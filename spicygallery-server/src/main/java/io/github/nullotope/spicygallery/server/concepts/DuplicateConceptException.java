package io.github.nullotope.spicygallery.server.concepts;

/**
 * A concept with the (normalized) name already exists (spec §3.5) — 409, names are
 * identity keys and double as the applied tag name.
 */
public class DuplicateConceptException extends RuntimeException {

    public DuplicateConceptException(String name) {
        super("concept '" + name + "' already exists");
    }
}
