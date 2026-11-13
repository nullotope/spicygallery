package io.github.nullotope.spicygallery.query;

/** A query the parser rejected (spec §3.3): carries the offending token and its 0-based offset. */
public final class QueryParseException extends RuntimeException {

    private final String token;
    private final int position;

    public QueryParseException(String message, String token, int position) {
        super(message);
        this.token = token;
        this.position = position;
    }

    public String token() {
        return token;
    }

    public int position() {
        return position;
    }
}
