package io.github.nullotope.spicygallery.server.tagging;

/** The tagging provider is disabled or its breaker is open (spec §7): mapped to 503. */
public final class TaggingUnavailableException extends RuntimeException {

    public TaggingUnavailableException(String message) {
        super(message);
    }
}
