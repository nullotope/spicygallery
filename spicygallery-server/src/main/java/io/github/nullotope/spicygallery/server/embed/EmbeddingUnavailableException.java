package io.github.nullotope.spicygallery.server.embed;

/** The provider is disabled or its breaker is open (spec §7): mapped to 503. */
public final class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message) {
        super(message);
    }
}
