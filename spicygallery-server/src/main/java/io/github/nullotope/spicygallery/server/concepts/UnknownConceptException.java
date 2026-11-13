package io.github.nullotope.spicygallery.server.concepts;

/**
 * No concept under that NAME — the {@code concept:} metatag resolution path only
 * (spec §5), a clean 400, the analogue of a query's unknown-tag 400. Unknown concept
 * <em>ids</em> are the house 404 instead
 * ({@code GlobalExceptionHandler.MissingConceptException}).
 */
public class UnknownConceptException extends RuntimeException {

    public UnknownConceptException(String name) {
        super("unknown concept '" + name + "'");
    }
}
