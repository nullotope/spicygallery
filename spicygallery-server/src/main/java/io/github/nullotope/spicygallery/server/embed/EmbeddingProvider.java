package io.github.nullotope.spicygallery.server.embed;

import java.io.IOException;
import java.util.List;

/**
 * Embedding model abstraction (spec §3.2). Exactly one of the input's fields is set:
 * {@code text} for query phrases, {@code image}+{@code mime} for prepared pixels.
 * ONE request per call — input chunking into batches is EmbeddingService's job.
 */
public interface EmbeddingProvider {

    record EmbeddingInput(String text, byte[] image, String mime) {}

    /** Vectors in request order; same length as {@code inputs}. Throws IOException on failure. */
    List<float[]> embed(List<EmbeddingInput> inputs) throws IOException;
}
