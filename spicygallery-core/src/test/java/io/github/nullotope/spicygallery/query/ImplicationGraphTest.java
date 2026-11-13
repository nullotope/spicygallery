package io.github.nullotope.spicygallery.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Implication closure + path check (spec §6); must tolerate hostile/self-loop edges. */
class ImplicationGraphTest {

    private final Map<Long, Set<Long>> edges =
            Map.of(1L, Set.of(2L), 2L, Set.of(3L), 5L, Set.of(5L)); // 5 self-loop must not hang

    @Test
    void closure_is_transitive_and_includes_requested() {
        assertEquals(Set.of(1L, 2L, 3L), ImplicationGraph.closure(Set.of(1L), edges));
        assertEquals(Set.of(7L), ImplicationGraph.closure(Set.of(7L), edges));
    }

    @Test
    void closure_merges_requested_and_handles_self_loops() {
        assertEquals(Set.of(5L), ImplicationGraph.closure(Set.of(5L), edges));
        assertEquals(Set.of(2L, 3L, 7L), ImplicationGraph.closure(Set.of(2L, 7L), edges));
    }

    @Test
    void reachable_finds_indirect_paths() {
        assertTrue(ImplicationGraph.reachable(1L, 3L, edges));
        assertFalse(ImplicationGraph.reachable(3L, 1L, edges));
    }
}
