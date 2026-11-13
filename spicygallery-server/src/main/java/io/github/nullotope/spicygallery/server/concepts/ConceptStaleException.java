package io.github.nullotope.spicygallery.server.concepts;

/**
 * The concept has no usable anchor for the current model (spec §3.2/§5) — 409.
 * The message distinguishes the two workbench notices: never computed (no inputs
 * yet) vs stale (anchor built under a different embedding model).
 */
public class ConceptStaleException extends RuntimeException {

    public ConceptStaleException(String message) {
        super(message);
    }
}
