package io.github.nullotope.spicygallery.query;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Implication edges as {@code tagId -> directly-implied tagIds}. Closure and path checks are
 * iterative (worklist/DFS, visited-set) so hostile graphs cannot blow the stack (spec §6).
 */
public final class ImplicationGraph {

    private ImplicationGraph() {
    }

    /** The requested tags plus every transitively implied tag. */
    public static Set<Long> closure(Collection<Long> requested, Map<Long, Set<Long>> edges) {
        Set<Long> result = new HashSet<>(requested);
        Deque<Long> worklist = new ArrayDeque<>(result);
        while (!worklist.isEmpty()) {
            Long current = worklist.pop();
            for (Long next : edges.getOrDefault(current, Set.of())) {
                if (result.add(next)) {
                    worklist.push(next);
                }
            }
        }
        return result;
    }

    /** Whether a directed path from → to exists (from == to is a trivial cycle: true). */
    public static boolean reachable(long from, long to, Map<Long, Set<Long>> edges) {
        if (from == to) {
            return true;
        }
        Set<Long> seen = new HashSet<>();
        Deque<Long> worklist = new ArrayDeque<>();
        worklist.push(from);
        while (!worklist.isEmpty()) {
            Long current = worklist.pop();
            for (Long next : edges.getOrDefault(current, Set.of())) {
                if (next == to) {
                    return true;
                }
                if (seen.add(next)) {
                    worklist.push(next);
                }
            }
        }
        return false;
    }
}
