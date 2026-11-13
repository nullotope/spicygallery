package io.github.nullotope.spicygallery.server.concepts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure anchor math (spec §3.1): duplicates exact, order-independent, null-skipping. */
class AnchorMathTest {

    @Test
    void mean_of_duplicates_equals_input() {
        float[] a = {0.25f, -0.5f, 1f, 0f};
        assertArrayEquals(a, AnchorMath.mean(List.of(a, a)), 1e-6f);
    }

    @Test
    void mean_is_order_independent() {
        float[] a = {1f, 2f, 3f};
        float[] b = {-1f, 0.5f, 2f};
        assertArrayEquals(AnchorMath.mean(List.of(a, b)), AnchorMath.mean(List.of(b, a)), 1e-6f);
    }

    @Test
    void mean_of_single_input_is_the_input() {
        float[] a = {3.5f, -2f, 0f};
        assertArrayEquals(a, AnchorMath.mean(List.of(a)), 1e-6f);
    }

    @Test
    void null_elements_are_skipped() {
        float[] a = {1f, 2f};
        assertArrayEquals(a, AnchorMath.mean(Arrays.asList(null, a, null)), 1e-6f);
    }

    @Test
    void null_empty_or_all_null_list_yields_null() {
        assertNull(AnchorMath.mean(null));
        assertNull(AnchorMath.mean(List.of()));
        assertNull(AnchorMath.mean(Arrays.asList(null, null)));
    }

    @Test
    void dimension_mismatch_throws_naming_both_dimensions() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AnchorMath.mean(List.of(new float[] {1f, 2f, 3f}, new float[] {1f, 2f})));
        assertTrue(e.getMessage().contains("3") && e.getMessage().contains("2"), e.getMessage());
    }
}
