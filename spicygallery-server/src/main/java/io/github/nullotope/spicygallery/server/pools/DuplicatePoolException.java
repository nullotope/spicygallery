package io.github.nullotope.spicygallery.server.pools;

/**
 * A pool with the (trimmed) name already exists (spec §5) — 409; names are identity keys
 * for the pools grid. Defaulted folder-pool names never take this path (they auto-dedupe,
 * spec §3.3); explicit names are strict. The HTTP mapping lands in T4.
 */
public class DuplicatePoolException extends RuntimeException {

    public DuplicatePoolException(String name) {
        super("pool '" + name + "' already exists");
    }
}
