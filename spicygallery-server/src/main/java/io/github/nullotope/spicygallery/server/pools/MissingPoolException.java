package io.github.nullotope.spicygallery.server.pools;

/** Pool id does not exist (spec §5) — the house 404; the HTTP mapping lands in T4. */
public class MissingPoolException extends RuntimeException {

    public MissingPoolException(long id) {
        super("no pool " + id);
    }
}
