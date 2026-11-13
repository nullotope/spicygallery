package io.github.nullotope.spicygallery.server.store;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** The one place a float[] becomes a pgvector literal string ("[f1,f2,…]"). */
public final class PgVector {

    private PgVector() {
    }

    public static String of(float[] vec) {
        return IntStream.range(0, vec.length)
                .mapToObj(i -> Float.toString(vec[i]))
                .collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * Inverse of {@link #of}: parses the pgvector text form; {@code null} or blank
     * → empty (a null column reads back as an empty vector, matching the blank case).
     */
    public static float[] parse(String text) {
        if (text == null) {
            return new float[0];
        }
        String body = text.strip();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].strip());
        }
        return out;
    }
}
