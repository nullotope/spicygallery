package io.github.nullotope.spicygallery.server.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** The pgvector literal codec: {@link PgVector#of} and {@link PgVector#parse} round-trip. */
class PgVectorTest {

    @Test
    void parse_null_is_empty() {
        assertArrayEquals(new float[0], PgVector.parse(null), 0f);
    }

    @Test
    void parse_blank_is_empty() {
        assertArrayEquals(new float[0], PgVector.parse("   "), 0f);
    }

    @Test
    void parse_roundtrips_of() {
        float[] v = {0.25f, -0.5f, 3f, 0f};
        assertArrayEquals(v, PgVector.parse(PgVector.of(v)), 0f);
    }

    @Test
    void parse_tolerates_missing_brackets_and_spaces() {
        assertArrayEquals(new float[] {1f, 2f}, PgVector.parse("1, 2"), 0f);
        assertArrayEquals(new float[] {1f, 2f}, PgVector.parse("[1,2]"), 0f);
    }
}
