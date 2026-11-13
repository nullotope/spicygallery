package io.github.nullotope.spicygallery.server.tags;

/** Tag name does not exist (spec §7): 404 mapping lands in GlobalExceptionHandler. */
public final class UnknownTagException extends RuntimeException {

    public UnknownTagException(String name) {
        super("no tag " + name);
    }
}
