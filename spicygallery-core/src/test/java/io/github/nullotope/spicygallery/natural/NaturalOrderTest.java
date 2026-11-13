package io.github.nullotope.spicygallery.natural;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NaturalOrderTest {

    @Test
    void digit_runs_compare_numerically() {
        assertTrue(NaturalOrder.compare("page2", "page10") < 0);
        assertTrue(NaturalOrder.compare("page10", "page2") > 0);
        assertEquals(0, NaturalOrder.compare("page10", "page10"));
    }

    @Test
    void zero_padded_equal_value_compares_equal() {
        assertEquals(0, NaturalOrder.compare("03", "3"));
        assertEquals(0, NaturalOrder.compare("page007", "page7"));
    }

    @Test
    void text_runs_compare_case_insensitively() {
        assertEquals(0, NaturalOrder.compare("Cover", "cover"));
        assertTrue(NaturalOrder.compare("COVER2", "cover10") < 0);
    }

    @Test
    void digit_runs_sort_before_text_runs() {
        assertTrue(NaturalOrder.compare("001", "cover") < 0);
        assertTrue(NaturalOrder.compare("cover", "001") > 0);
    }

    @Test
    void divergent_text_runs_compare_as_whole_units_longer_run_sorts_after() {
        // "ab" vs "a" decides the comparison BEFORE any digit-vs-text rule can apply: the
        // longer text run sorts after, so "ab2" > "a3" even though 'b' (text) meets '3'
        // (digit) at the divergence point — digit-before-text does NOT re-apply char-wise
        assertTrue(NaturalOrder.compare("ab2", "a3") > 0);
        assertTrue(NaturalOrder.compare("a3", "ab2") < 0); // antisymmetry mirror
    }

    @Test
    void mixed_names_break_ties_after_equal_digit_runs() {
        assertTrue(NaturalOrder.compare("2abc", "2abd") < 0);
        assertTrue(NaturalOrder.compare("2abd", "2abc") > 0);
    }

    @Test
    void antisymmetry_spot_checks() {
        String[][] pairs = {
                {"page2", "page10"},
                {"03", "3"},
                {"2abc", "2abd"},
                {"001", "cover"},
                {"Cover", "cover"},
                {"10", "9a"},
        };
        for (String[] pair : pairs) {
            assertEquals(-Integer.signum(NaturalOrder.compare(pair[1], pair[0])),
                    Integer.signum(NaturalOrder.compare(pair[0], pair[1])),
                    String.join(" vs ", pair));
        }
    }

    @Test
    void null_arguments_throw_npe() {
        assertThrows(NullPointerException.class, () -> NaturalOrder.compare(null, "a"));
        assertThrows(NullPointerException.class, () -> NaturalOrder.compare("a", null));
    }
}
