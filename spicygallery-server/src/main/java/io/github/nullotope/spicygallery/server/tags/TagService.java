package io.github.nullotope.spicygallery.server.tags;

import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.query.ImplicationGraph;
import io.github.nullotope.spicygallery.query.QueryNode;
import io.github.nullotope.spicygallery.query.TagName;
import io.github.nullotope.spicygallery.server.web.GlobalExceptionHandler.MissingEntryException;
import io.github.nullotope.spicygallery.server.store.CompiledQuery;
import io.github.nullotope.spicygallery.server.store.EntryQueries;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore.AliasRow;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore.Expansion;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore.ImplicationRow;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore.TagRow;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore.TagSort;
import io.github.nullotope.spicygallery.server.store.QuerySqlCompiler;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tag semantics (spec §6) — the single write choke point for all tag state: entry-tag
 * replacement with alias resolution + transitive implication closure + exact post_count
 * maintenance; alias merge; guarded implication management. Every mutation is transactional.
 */
@Service
public class TagService {

    /**
     * Wire input for a tag assignment: name (raw user input) + optional category for new tags
     * + provenance stamped onto rows this input creates ({@code "user"} unless set by the
     * AI pipeline, which passes e.g. {@code "hydra-3.5"}).
     */
    public record TagInput(String name, TagCategory category, String taggedBy) {
        public TagInput(String name, TagCategory category) {
            this(name, category, "user");
        }
    }

    /** Expansion cap for query-side tag patterns (spec §3.3). */
    public static final int EXPANSION_CAP = 200;

    private final JdbcTagStore tags;
    private final EntryQueries entries;
    private final JdbcClient jdbc;

    public TagService(JdbcTagStore tags, EntryQueries entries, JdbcClient jdbc) {
        this.tags = tags;
        this.entries = entries;
        this.jdbc = jdbc;
    }

    // --- entry tagging ------------------------------------------------------

    @Transactional
    public List<TagRow> setEntryTags(long entryId, List<TagInput> inputs) {
        if (entries.byId(entryId).isEmpty()) {
            throw new MissingEntryException(entryId);
        }
        LinkedHashMap<Long, TagInput> requestedIds = resolveRequested(inputs);
        // transitive implications over the requested set
        Set<Long> target = ImplicationGraph.closure(requestedIds.keySet(), tags.allImplicationsEdges());
        // diff against current rows; counts move with the rows
        Set<Long> current = tags.tagIdsOf(entryId);
        for (Long id : target) {
            TagInput input = requestedIds.get(id);
            if (!current.contains(id)) {
                tags.tagEntry(entryId, id, input == null ? "user" : input.taggedBy());
                tags.bumpPostCount(id, 1);
            } else if (input != null && input.taggedBy() != null) {
                // re-affirmation: the user re-supplied an existing row, so its provenance is
                // upgraded to the input's value (no bump) — otherwise the next
                // setEntryTagsPreservingUser would drop a tag the user just kept
                tags.tagEntry(entryId, id, input.taggedBy()); // DO UPDATE rewrites tagged_by in place
            }
        }
        for (Long id : current) {
            if (!target.contains(id)) {
                tags.untagEntry(entryId, id);
                tags.bumpPostCount(id, -1);
            }
        }
        return tags.tagsOf(entryId);
    }

    /**
     * AI merge (slice 4): the final tag set is the current USER-tagged rows ∪ the AI inputs,
     * implication closure applied — rows tagged by anything else (previous AI runs, other
     * models) are dropped unless re-supplied here. Provenance per output row: retained user
     * rows stay {@code "user"}; explicit AI inputs get {@code aiProvenance}; implied-only
     * rows inherit from their implying rows folded user-wins (see {@link #foldProvenance}).
     * A {@code null} category on an AI input is rejected: the caller (model info mapping)
     * must resolve it, so a missing mapping can never silently become {@code general}.
     */
    @Transactional
    public List<TagRow> setEntryTagsPreservingUser(long entryId, List<TagInput> aiInputs, String aiProvenance) {
        if (entries.byId(entryId).isEmpty()) {
            throw new MissingEntryException(entryId);
        }
        for (TagInput input : aiInputs) {
            if (input.category() == null) {
                throw new IllegalArgumentException("AI tag input has no category mapping: " + input.name());
            }
        }
        LinkedHashMap<Long, TagInput> aiRequested = resolveRequested(aiInputs);
        // retain only rows the user tagged
        Map<Long, String> taggedBy = tags.taggedByOf(entryId);
        Set<Long> retained = new LinkedHashSet<>();
        for (Long id : tags.tagIdsOf(entryId)) {
            if ("user".equals(taggedBy.get(id))) {
                retained.add(id);
            }
        }
        Set<Long> requested = new LinkedHashSet<>(retained);
        requested.addAll(aiRequested.keySet());
        Map<Long, Set<Long>> edges = tags.allImplicationsEdges();
        Set<Long> target = ImplicationGraph.closure(requested, edges);
        Map<Long, String> provenance = foldProvenance(target, retained, aiRequested.keySet(), edges, aiProvenance);
        // diff against current rows; counts move with the rows; provenance rewrites never bump
        Set<Long> current = tags.tagIdsOf(entryId);
        for (Long id : target) {
            // unreachable fallback keeps user rows on the safe side of any future drift
            String by = provenance.getOrDefault(id, "user");
            if (!current.contains(id)) {
                tags.tagEntry(entryId, id, by);
                tags.bumpPostCount(id, 1);
            } else if (!by.equals(taggedBy.get(id))) {
                tags.tagEntry(entryId, id, by); // DO UPDATE rewrites tagged_by in place
            }
        }
        for (Long id : current) {
            if (!target.contains(id)) {
                tags.untagEntry(entryId, id);
                tags.bumpPostCount(id, -1);
            }
        }
        return tags.tagsOf(entryId);
    }

    /**
     * Accounting for {@link #addTagsToEntries}: {@code requested} = {@code entryIds.size()}
     * (skipped/missing ids included), {@code tagged} = rows inserted, {@code alreadyTagged} =
     * existing entries that already carried the resolved tag (aliases resolve to the target).
     */
    public record AddResult(long requested, long tagged, long alreadyTagged) {}

    /**
     * Add-only apply (slice 5 concepts): tags every existing entry with the input's single
     * resolved tag — normalize → alias → create with {@code input.category()} — plus its
     * implication closure, inserting only the rows the entry is missing with provenance
     * {@code input.taggedBy() != null ? input.taggedBy() : "user"} and one post-count bump
     * per inserted row. Existing rows are never rewritten or removed — an entry already
     * carrying the tag keeps its original provenance (no re-affirmation path here, unlike
     * {@link #setEntryTags}). Missing entries are skipped silently: the apply path curates
     * from FK-guaranteed rows, but mid-flight deletion is legal.
     */
    @Transactional
    public AddResult addTagsToEntries(List<Long> entryIds, TagInput input) {
        // one input → exactly one resolved tag (normalize → alias → create)
        long tagId = resolveRequested(List.of(input)).keySet().iterator().next();
        String provenance = input.taggedBy() != null ? input.taggedBy() : "user";
        Set<Long> target = ImplicationGraph.closure(Set.of(tagId), tags.allImplicationsEdges());
        long tagged = 0;
        long alreadyTagged = 0;
        for (Long entryId : entryIds) {
            if (entries.byId(entryId).isEmpty()) {
                continue; // skip silently — no MissingEntryException on the curation path
            }
            Set<Long> current = tags.tagIdsOf(entryId);
            if (current.contains(tagId)) {
                alreadyTagged++; // resolved-name basis: the explicit tag itself
            }
            for (Long id : target) {
                if (!current.contains(id)) {
                    tags.tagEntry(entryId, id, provenance); // insert-only: existing rows untouched
                    tags.bumpPostCount(id, 1);
                    tagged++;
                }
            }
        }
        return new AddResult(entryIds.size(), tagged, alreadyTagged);
    }

    /**
     * Shared core of both merge paths: normalize + dedupe (first occurrence wins), then
     * resolve through aliases; unknown names are created (null category → GENERAL).
     * Returns canonical tag id → winning input.
     */
    private LinkedHashMap<Long, TagInput> resolveRequested(List<TagInput> inputs) {
        LinkedHashMap<String, TagInput> requested = new LinkedHashMap<>();
        for (TagInput input : inputs) {
            requested.putIfAbsent(TagName.normalize(input.name()), input);
        }
        LinkedHashMap<Long, TagInput> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, TagInput> e : requested.entrySet()) {
            TagRow row = tags.byName(e.getKey())
                    .or(() -> tags.aliasTarget(e.getKey()))
                    .orElseGet(() -> tags.create(e.getKey(),
                            e.getValue().category() == null ? TagCategory.GENERAL : e.getValue().category()));
            resolved.putIfAbsent(row.id(), e.getValue());
        }
        return resolved;
    }

    /**
     * The provenance-folding rule for the final target set: explicit rows carry their own
     * provenance (a retained user row wins over an AI input naming the same tag); implied-only
     * rows inherit from the rows that imply them, folded user-wins along the implication
     * graph — a row ends up {@code "user"} iff some user row reaches it, independent of the
     * order the roots are processed: the first arriving value claims the row, later arrivals
     * may only upgrade {@code ai → user}, never overwrite a claimed value with
     * {@code aiProvenance}. Edges form a DAG (cycles rejected at creation), and a row's value
     * only ever moves {@code null → x} or {@code ai → user}, so each row is enqueued at most
     * twice and the worklist terminates.
     */
    private static Map<Long, String> foldProvenance(Set<Long> target, Set<Long> retained, Set<Long> aiIds,
            Map<Long, Set<Long>> edges, String aiProvenance) {
        Map<Long, String> provenance = new HashMap<>();
        for (Long id : aiIds) {
            provenance.put(id, aiProvenance);
        }
        for (Long id : retained) {
            provenance.put(id, "user"); // user-wins on the explicit overlap
        }
        Deque<Long> queue = new ArrayDeque<>(retained);
        for (Long id : aiIds) {
            queue.add(id);
        }
        while (!queue.isEmpty()) {
            long antecedent = queue.poll();
            String by = provenance.get(antecedent);
            for (Long consequent : edges.getOrDefault(antecedent, Set.of())) {
                if (!target.contains(consequent) || retained.contains(consequent) || aiIds.contains(consequent)) {
                    continue; // outside the target, or an explicit row keeps its own provenance
                }
                String folded = "user".equals(by) ? "user" : aiProvenance;
                String cur = provenance.get(consequent);
                if (cur == null || ("user".equals(folded) && !"user".equals(cur))) {
                    provenance.put(consequent, folded);
                    queue.add(consequent);
                }
            }
        }
        return provenance;
    }

    // --- pool tags (slice 6, spec §3.5) -----------------------------------------

    /**
     * Pool-tag resolution: normalize → alias-resolve → create-missing as {@code GENERAL} —
     * the entry-tag rule WITHOUT implication closure and WITHOUT provenance (pool tags are
     * user curation, plain {@code pool_tag} links). Reuses {@link #resolveRequested}, so
     * duplicates collapse to the first occurrence and the returned rows follow request
     * order. The caller (PoolService) enforces the system-tag discipline on top.
     */
    @Transactional
    public List<TagRow> resolvePoolTags(List<String> rawNames) {
        List<TagInput> inputs = rawNames.stream().map(n -> new TagInput(n, null)).toList();
        LinkedHashMap<Long, TagInput> resolved = resolveRequested(inputs);
        Map<Long, TagRow> rowsById = tags.byIds(resolved.keySet()).stream()
                .collect(Collectors.toMap(TagRow::id, r -> r));
        return resolved.keySet().stream().map(rowsById::get).toList();
    }

    /**
     * Resolves a SYSTEM tag by exact name, creating it in the given category when absent
     * (spec §3.5: {@code pool_type:pool} is always present on every stored pool — V5 seeds
     * it, but a wiped tag table must self-heal). No alias resolution: a system tag's
     * identity is its name; user aliasing must never redirect it.
     */
    @Transactional
    public TagRow systemTag(String name, TagCategory category) {
        String normalized = TagName.normalize(name);
        return tags.byName(normalized).orElseGet(() -> tags.create(normalized, category));
    }

    // --- aliases (merge) ------------------------------------------------------

    @Transactional
    public void createAlias(String antecedentRaw, String consequentRaw) {
        String antecedent = TagName.normalize(antecedentRaw);
        String consequent = TagName.normalize(consequentRaw);
        TagRow target = resolveOrCreate(consequent);
        if (target.name().equals(antecedent)) {
            throw new IllegalArgumentException("tag cannot alias itself: " + antecedent);
        }
        tags.byName(antecedent).ifPresent(victim -> moveEntries(victim, target));
        tags.putAlias(antecedent, target.id());
    }

    /** Moves victim's entry rows to target, deletes the victim tag, keeps counts exact.
     *  The bump uses the INSERT's update count — rows already carrying the target tag are
     *  skipped by ON CONFLICT DO NOTHING and must not count as moved (post_count drift). */
    private void moveEntries(TagRow victim, TagRow target) {
        int moved = jdbc.sql("""
                        insert into media_tag (media_entry_id, tag_id, tagged_by)
                        select media_entry_id, :targetId, tagged_by from media_tag where tag_id = :victimId
                        on conflict do nothing
                        """)
                .param("targetId", target.id())
                .param("victimId", victim.id())
                .update();
        jdbc.sql("delete from media_tag where tag_id = :id").param("id", victim.id()).update();
        jdbc.sql("delete from tag where id = :id").param("id", victim.id()).update();
        if (moved > 0) {
            tags.bumpPostCount(target.id(), moved);
        }
    }

    @Transactional
    public void removeAlias(String nameRaw) {
        tags.removeAlias(TagName.normalize(nameRaw));
    }

    // --- implications -----------------------------------------------------------

    @Transactional
    public void createImplication(String antecedentRaw, String consequentRaw) {
        String antecedent = requireTag(antecedentRaw);
        String consequent = requireTag(consequentRaw);
        if (antecedent.equals(consequent)) {
            throw new IllegalArgumentException("tag cannot imply itself: " + antecedent);
        }
        long a = tags.byName(antecedent).orElseThrow().id();
        long b = tags.byName(consequent).orElseThrow().id();
        Map<Long, Set<Long>> edges = tags.allImplicationsEdges();
        if (edges.getOrDefault(a, Set.of()).contains(b)) {
            throw new IllegalArgumentException("implication already exists: " + antecedent + " -> " + consequent);
        }
        if (ImplicationGraph.reachable(b, a, edges)) {
            throw new IllegalArgumentException(
                    "implication cycle rejected: " + consequent + " already implies " + antecedent);
        }
        tags.addImplication(a, b);
    }

    @Transactional
    public void removeImplication(String antecedentRaw, String consequentRaw) {
        Optional<TagRow> a = tags.byName(TagName.normalize(antecedentRaw));
        Optional<TagRow> b = tags.byName(TagName.normalize(consequentRaw));
        if (a.isPresent() && b.isPresent()) {
            tags.removeImplication(a.get().id(), b.get().id());
        }
    }

    // --- tag management -------------------------------------------------------------

    @Transactional
    public TagRow changeCategory(String nameRaw, TagCategory category) {
        String name = TagName.normalize(nameRaw);
        TagRow row = tags.byName(name).orElseThrow(() -> new UnknownTagException(name));
        jdbc.sql("update tag set category = :category where id = :id")
                .param("category", category.wireName())
                .param("id", row.id())
                .update();
        return tags.byName(name).orElseThrow();
    }

    // --- reads (delegates) -------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TagRow> tagsOf(long entryId) {
        return tags.tagsOf(entryId);
    }

    @Transactional(readOnly = true)
    public List<TagRow> listTags(String q, TagCategory category, TagSort sort, String cursorKey, long cursorId,
                                 int limit) {
        return tags.listTags(q, category, sort, cursorKey, cursorId, limit);
    }

    @Transactional(readOnly = true)
    public List<AliasRow> listAliases() {
        return tags.listAliases();
    }

    @Transactional(readOnly = true)
    public List<ImplicationRow> listImplications() {
        return tags.listImplications();
    }

    /** Facet aggregation over the compiled filter (spec §7 tagFacets). */
    @Transactional(readOnly = true)
    public List<EntryQueries.FacetDto> facets(CompiledQuery cq, Set<Long> excludedTagIds, int limit) {
        return tags.facets(cq, excludedTagIds, limit);
    }

    /** Tiered autocomplete (spec §7): prefix, then substring, then alias names. */
    @Transactional(readOnly = true)
    public List<JdbcTagStore.AutocompleteHit> autocomplete(String q, int limit) {
        return tags.autocomplete(q, limit);
    }

    /**
     * Expands every tag term of the parsed query to canonical IDs (spec §3.4); wildcards
     * and aliased names both land on canonical tags. Errors when a pattern is too broad.
     * {@code positiveIds} is the union over non-negated terms — the facet-exclusion set.
     */
    public record QueryExpansions(Map<String, List<Long>> byTerm, Set<Long> positiveIds) {}

    @Transactional(readOnly = true)
    public QueryExpansions expandFor(QueryNode ast) {
        LinkedHashMap<String, List<Long>> expansions = new LinkedHashMap<>();
        Set<Long> positive = new LinkedHashSet<>();
        if (ast instanceof QueryNode.Query q) {
            collectTerms(q, expansions, positive);
        }
        return new QueryExpansions(expansions, positive);
    }

    private void collectTerms(QueryNode.Query q, Map<String, List<Long>> expansions, Set<Long> positive) {
        for (QueryNode clause : q.clauses()) {
            switch (clause) {
                case QueryNode.TagTerm t -> expansions.computeIfAbsent(termKey(t),
                        k -> expandTerm(t, !t.negated(), positive));
                case QueryNode.OrGroup g -> g.alternatives().stream()
                        .filter(QueryNode.TagTerm.class::isInstance)
                        .map(QueryNode.TagTerm.class::cast)
                        .forEach(t -> expansions.computeIfAbsent(termKey(t),
                                k -> expandTerm(t, !t.negated(), positive)));
                default -> {
                    // metatags and phrases need no expansion
                }
            }
        }
    }

    private static String termKey(QueryNode.TagTerm t) {
        return t.category() == null ? t.pattern() : t.category().wireName() + ":" + t.pattern();
    }

    private List<Long> expandTerm(QueryNode.TagTerm t, boolean positive, Set<Long> positiveSink) {
        // likeEscape leaves '*' untouched (only \\, %, _ are escaped) so '*' → '%' is safe after
        String pattern = "%" + QuerySqlCompiler.likeEscape(t.pattern()).replace("*", "%") + "%";
        Expansion e = tags.expand(pattern, t.category(), EXPANSION_CAP);
        if (e.truncated()) {
            throw new IllegalArgumentException(
                    "too many tags match '" + t.pattern() + "'; be more specific");
        }
        if (positive) {
            positiveSink.addAll(e.ids());
        }
        return e.ids();
    }

    private TagRow resolveOrCreate(String name) {
        return tags.byName(name)
                .or(() -> tags.aliasTarget(name))
                .orElseGet(() -> tags.create(name, TagCategory.GENERAL));
    }

    private String requireTag(String raw) {
        String name = TagName.normalize(raw);
        if (tags.byName(name).isEmpty()) {
            throw new IllegalArgumentException("unknown tag: " + name);
        }
        return name;
    }
}
