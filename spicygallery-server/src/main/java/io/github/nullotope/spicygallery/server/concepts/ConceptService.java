package io.github.nullotope.spicygallery.server.concepts;

import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.query.TagName;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.embed.EmbeddingService;
import io.github.nullotope.spicygallery.server.store.ConceptStore;
import io.github.nullotope.spicygallery.server.store.ConceptStore.ConceptRow;
import io.github.nullotope.spicygallery.server.store.EmbeddingStore;
import io.github.nullotope.spicygallery.server.store.EmbeddingStore.RelatedRow;
import io.github.nullotope.spicygallery.server.store.EntryQueries;
import io.github.nullotope.spicygallery.server.store.EntryQueries.EntryDto;
import io.github.nullotope.spicygallery.server.tags.TagService;
import io.github.nullotope.spicygallery.server.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concept orchestration (spec §3.2/§3.3): anchors, suggestions and the add-only apply.
 * The ONLY provider call in the whole feature is the single on-demand
 * {@code textVector} when a phrase is created or changed — every recompute is pure DB
 * ({@link AnchorMath} over stored vectors) and never calls the provider.
 *
 * <p>Wire contract consumed by T6/T7: {@link ConceptDto}, {@link Suggestion},
 * {@link ApplyResult} (see ConceptController for the HTTP shapes around them).</p>
 */
@Service
public class ConceptService {

    /**
     * Workbench read model (spec §3.5). {@code taggedCount} = entries carrying the
     * concept's tag (any provenance); {@code applyable} = examples missing the tag
     * (the "apply to N" preview); {@code anchorStale} = an anchor exists but was built
     * under a different embedding model than the current one.
     */
    public record ConceptDto(long id, String name, String phrase, long exampleCount, long excludedCount,
                             long taggedCount, long applyable, boolean anchorStale,
                             Instant createdAt, Instant updatedAt) {}

    /** One suggestion: grid-shaped entry plus its best-frame cosine to the anchor. */
    public record Suggestion(EntryDto entry, double score) {}

    public record Suggestions(List<Suggestion> items) {}

    /**
     * The workbench member lists (spec §4.2): the EXAMPLE and EXCLUDED member sets as
     * grid-shaped DTOs (score-less, id-ascending — the store's membership order).
     */
    public record Members(List<EntryDto> examples, List<EntryDto> excluded) {}

    /**
     * Apply accounting (spec §3.3): {@code examples} = the concept's example count (the
     * scope), {@code tagged} = media_tag rows inserted by this apply (the name tag plus
     * its implication closure), {@code alreadyTagged} = examples that already carried
     * the tag before the call (re-apply reports everything as already-tagged).
     */
    public record ApplyResult(String tag, long examples, long tagged, long alreadyTagged) {}

    private static final int SUGGESTION_LIMIT_DEFAULT = 50;
    private static final int SUGGESTION_LIMIT_MAX = 200;

    private final ConceptStore concepts;
    private final EmbeddingStore embeddingStore;
    private final EmbeddingService embeddings;
    private final TagService tags;
    private final EntryQueries queries;
    private final SpicyGalleryProperties props;

    public ConceptService(ConceptStore concepts, EmbeddingStore embeddingStore, EmbeddingService embeddings,
                          TagService tags, EntryQueries queries, SpicyGalleryProperties props) {
        this.concepts = concepts;
        this.embeddingStore = embeddingStore;
        this.embeddings = embeddings;
        this.tags = tags;
        this.queries = queries;
        this.props = props;
    }

    // --- create / read ----------------------------------------------------------

    /**
     * Name is normalized (400 on junk); a duplicate name is a 409. A phrase is resolved
     * NOW through the provider (failFast → 503 when disabled/breaker open) and, with no
     * members yet, becomes the anchor directly. Without a phrase the concept starts
     * anchorless (null anchor, null anchor_model) — examples never need the provider.
     */
    @Transactional
    public ConceptDto create(String nameRaw, String phraseRaw) {
        String name = TagName.normalize(nameRaw);
        if (concepts.byName(name).isPresent()) {
            throw new DuplicateConceptException(name);
        }
        String phrase = phraseRaw == null || phraseRaw.isBlank() ? null : phraseRaw.trim();
        float[] phraseVector = null;
        if (phrase != null) {
            embeddings.failFast();
            phraseVector = embeddings.textVector(phrase);
        }
        String model = props.embedding().model();
        ConceptRow row = concepts.create(name, phrase, phraseVector, phraseVector,
                phraseVector == null ? null : model);
        return toDto(row);
    }

    /** {@code sort}: {@code "recent"} → updated_at desc, {@code "name"} → name asc.
     *  The controller validates the raw param; anything reaching here is one of the two. */
    @Transactional(readOnly = true)
    public List<ConceptDto> list(String sort) {
        return concepts.list(sort).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ConceptDto get(long id) {
        return toDto(require(id));
    }

    /**
     * Workbench member tiles (spec §4.2): both membership statuses as grid-shaped
     * DTOs, assembled through the same {@link EntryQueries#byIds} stitch the
     * suggestion items use. Entries that vanish while listed never break the
     * response — byIds silently skips ids whose row is gone or no longer
     * {@code status='ok'} (the FK cascade removes outright-deleted membership rows,
     * spec §5), so a deleted entry's tile just drops out.
     */
    @Transactional(readOnly = true)
    public Members members(long id) {
        require(id);
        return new Members(memberDtos(id, "EXAMPLE"), memberDtos(id, "EXCLUDED"));
    }

    /** byIds is score-ordered; without scores it leaves the SQL order, so the store's
     *  id-ascending membership order is re-applied for a deterministic tile row. */
    private List<EntryDto> memberDtos(long conceptId, String status) {
        List<EntryDto> entries =
                new ArrayList<>(queries.byIds(concepts.entryIds(conceptId, status), List.of()));
        entries.sort(Comparator.comparingLong(EntryDto::id));
        return entries;
    }

    /**
     * Partial update: {@code nameRaw != null} renames (normalized; duplicate → 409;
     * renaming to the own normalized name is a no-op — already-applied tags are NOT
     * retroactively renamed, spec §3.5); {@code phraseRaw} non-blank re-resolves the
     * phrase through the provider (503 rules as at create — nothing is written on
     * failure) and recomputes the anchor with it. Absent/blank fields are no-ops.
     */
    @Transactional
    public ConceptDto patch(long id, String nameRaw, String phraseRaw) {
        ConceptRow row = require(id);
        String newName = null;
        if (nameRaw != null) {
            newName = TagName.normalize(nameRaw);
            if (!newName.equals(row.name()) && concepts.byName(newName).isPresent()) {
                throw new DuplicateConceptException(newName);
            }
        }
        boolean phraseChange = phraseRaw != null && !phraseRaw.isBlank();
        if (phraseChange) {
            // resolve BEFORE any write: a 503 must leave the concept untouched
            embeddings.failFast();
            float[] phraseVector = embeddings.textVector(phraseRaw.trim());
            if (newName != null && !newName.equals(row.name())) {
                concepts.rename(id, newName);
            }
            concepts.updatePhraseAndAnchor(id, phraseRaw.trim(), phraseVector,
                    recompute(id, phraseVector, props.embedding().model()), props.embedding().model());
        } else {
            if (newName != null && !newName.equals(row.name())) {
                concepts.rename(id, newName);
            }
        }
        return toDto(concepts.byId(id).orElseThrow());
    }

    @Transactional
    public void delete(long id) {
        require(id);
        concepts.delete(id);
    }

    // --- membership ---------------------------------------------------------------

    /**
     * Upserts every id to EXAMPLE (EXCLUDED → EXAMPLE demotion included). Unknown entry
     * ids are a 400 (the workbench seeds from real grid rows). Returns how many ids
     * were NOT already examples — re-adding counts nothing. The anchor recomputes only
     * when the example set changed.
     */
    @Transactional
    public long addExamples(long id, List<Long> entryIds) {
        require(id);
        LinkedHashSet<Long> ids = new LinkedHashSet<>(entryIds == null ? List.of() : entryIds);
        for (Long entryId : ids) {
            if (entryId == null || queries.byId(entryId).isEmpty()) {
                throw new IllegalArgumentException("no media entry " + entryId);
            }
        }
        Set<Long> existing = new HashSet<>(concepts.entryIds(id, "EXAMPLE"));
        long added = ids.stream().filter(entryId -> !existing.contains(entryId)).count();
        if (added == 0) {
            return 0;
        }
        for (Long entryId : ids) {
            concepts.setStatus(id, entryId, "EXAMPLE");
        }
        storeRecompute(require(id));
        return added;
    }

    /** Removes the membership row regardless of status; idempotent 204 either way.
     *  The entry may resurface in suggestions (spec §3.5). */
    @Transactional
    public void removeExample(long id, long entryId) {
        require(id);
        if (concepts.removeEntry(id, entryId)) {
            storeRecompute(require(id)); // the removed example's vector leaves the anchor
        }
    }

    /**
     * Upserts every id to EXCLUDED — rejecting an example demotes it. Returns how many
     * ids were NOT already excluded. Exclusions never affect the anchor (spec §3.2);
     * they only filter suggestions.
     */
    @Transactional
    public long addExclusions(long id, List<Long> entryIds) {
        require(id);
        LinkedHashSet<Long> ids = new LinkedHashSet<>(entryIds == null ? List.of() : entryIds);
        for (Long entryId : ids) {
            if (entryId == null || queries.byId(entryId).isEmpty()) {
                throw new IllegalArgumentException("no media entry " + entryId);
            }
        }
        Set<Long> existing = new HashSet<>(concepts.entryIds(id, "EXCLUDED"));
        long excluded = ids.stream().filter(entryId -> !existing.contains(entryId)).count();
        for (Long entryId : ids) {
            concepts.setStatus(id, entryId, "EXCLUDED");
        }
        return excluded;
    }

    @Transactional
    public void removeExclusion(long id, long entryId) {
        require(id);
        concepts.removeEntry(id, entryId);
    }

    // --- suggestions ----------------------------------------------------------------

    /**
     * Best-frame cosine to the anchor over status-ok entries (spec §3.2), excluding the
     * concept's examples, its exclusions, and everything already carrying the concept's
     * tag (any provenance — they are done). Entries without current-model vectors drop
     * out silently. {@code limit} defaults to 50, clamped 1..200 (related's cap pattern,
     * not a 400). Stale or anchorless concepts answer 409.
     */
    @Transactional(readOnly = true)
    public Suggestions suggestions(long id, Integer limit) {
        ConceptRow row = require(id);
        requireCurrentAnchor(row);
        Set<Long> exclude = new LinkedHashSet<>(concepts.entryIds(id, "EXAMPLE"));
        exclude.addAll(concepts.entryIds(id, "EXCLUDED"));
        exclude.addAll(concepts.taggedEntryIdsByName(row.name()));
        int clamped = limit == null ? SUGGESTION_LIMIT_DEFAULT
                : Math.min(Math.max(limit, 1), SUGGESTION_LIMIT_MAX);
        List<RelatedRow> rows = embeddingStore.suggestions(row.anchor(), props.embedding().model(),
                exclude, clamped);
        // grid-shaped DTOs in rank order (byIds skips rows that vanished mid-flight)
        List<EntryDto> entries = queries.byIds(rows.stream().map(RelatedRow::entryId).toList(), rows);
        var scoreBy = new java.util.HashMap<Long, Double>();
        for (RelatedRow r : rows) {
            scoreBy.put(r.entryId(), r.score());
        }
        return new Suggestions(entries.stream()
                .map(entry -> new Suggestion(entry, scoreBy.get(entry.id())))
                .toList());
    }

    // --- apply ------------------------------------------------------------------------

    /**
     * Tags every example missing the concept's tag through the slice-4 add-only path —
     * provenance {@code 'user'}, implication closure included (spec §3.3). Pure DB: no
     * job, no GPU, works on anchorless concepts. Zero examples is a 400 (accident guard).
     */
    @Transactional
    public ApplyResult apply(long id) {
        ConceptRow row = require(id);
        List<Long> examples = concepts.entryIds(id, "EXAMPLE");
        if (examples.isEmpty()) {
            throw new IllegalArgumentException(
                    "concept '" + row.name() + "' has no examples to apply");
        }
        Set<Long> taggedBefore = concepts.taggedEntryIdsByName(row.name());
        List<Long> targets = examples.stream().filter(entryId -> !taggedBefore.contains(entryId)).toList();
        TagService.AddResult result = tags.addTagsToEntries(targets,
                new TagService.TagInput(row.name(), TagCategory.GENERAL, "user"));
        long alreadyTagged = examples.stream().filter(taggedBefore::contains).count()
                + result.alreadyTagged(); // targets can carry the ALIAS-resolved tag (slice-2 machinery)
        return new ApplyResult(row.name(), examples.size(), result.tagged(), alreadyTagged);
    }

    // --- refresh & staleness -------------------------------------------------------------

    /**
     * Recomputes the anchor from stored inputs only — never the provider (spec §3.2) —
     * and stamps the current model. With no input carrying a current-model vector
     * (model changed, library not re-embedded) the concept cannot recover here: 422,
     * nothing written (Review Focus #4; recovery = re-embed, or re-save the phrase).
     */
    @Transactional
    public ConceptDto refresh(long id) {
        ConceptRow row = require(id);
        Recompute recompute = recompute(row);
        if (!recompute.hasInputs()) {
            throw new ConceptController.NoCurrentModelInputsException(row.name());
        }
        concepts.updateAnchor(id, recompute.anchor(), props.embedding().model());
        return toDto(concepts.byId(id).orElseThrow());
    }

    /**
     * Anchor resolution for the {@code concept:} metatag (spec §3.4): normalize, unknown
     * → 400, anchorless or stale → 409 (distinguishable messages for the workbench
     * notice), else the stored anchor vector.
     */
    @Transactional(readOnly = true)
    public float[] anchorVectorForQuery(String nameRaw) {
        String name = TagName.normalize(nameRaw);
        ConceptRow row = concepts.byName(name).orElseThrow(() -> new UnknownConceptException(name));
        requireCurrentAnchor(row);
        return row.anchor();
    }

    // --- anchor recompute (the decision table) ---------------------------------------------

    /**
     * One recompute input set (spec §3.2): every EXAMPLE entry contributes the mean of
     * its stored vectors for the CURRENT model (entries without current-model rows drop
     * out silently); the stored phrase_vector contributes iff it belongs to the current
     * model. The phrase's model is the concept's {@code anchor_model} — the V4 schema
     * pins "phrase_vector … (model = anchor_model)": it is stamped at create/phrase-change
     * together with the anchor. After a model change the phrase is therefore old-model
     * and stays dormant until re-saved ("re-save the phrase to re-resolve it", spec §5) —
     * which is exactly what makes a phrase-only concept 422 on refresh instead of
     * silently anchoring on stale vectors.
     *
     * <p>Decision table (model M*, current model M, examples' per-entry frame means E,
     * phrase vector P):</p>
     * <pre>
     * anchor_model          | inputs                      | anchor result
     * ----------------------+-----------------------------+--------------------------------
     * M (or null, P null)   | E(M) non-empty              | mean(E ∪ [P iff model current])
     * M (P null)            | E(M) empty                  | 422 on refresh / null on triggers
     * foreign (P resolved   | E(M) empty, P present       | P is old-model → NOT an input
     *  under anchor_model)  |                             | → no current-model inputs → 422
     * foreign               | E(M) non-empty (re-embed)   | mean(E) — P dormant until re-save
     * </pre>
     */
    private record Recompute(float[] anchor, boolean hasInputs) {}

    /** Recompute with an override phrase vector (PATCH-phrase: it was just resolved
     *  under the current model and always contributes). */
    private float[] recompute(long id, float[] phraseVector, String anchorModel) {
        String model = props.embedding().model();
        List<float[]> inputs = new ArrayList<>(
                embeddingStore.meanVectorByEntry(concepts.entryIds(id, "EXAMPLE"), model).values());
        if (phraseVector != null && model.equals(anchorModel)) {
            inputs.add(phraseVector);
        }
        return AnchorMath.mean(inputs);
    }

    /** Persists a trigger recompute (example add/remove). A null result clears a
     *  previous anchor; nothing is written while the concept has never had one. */
    private void storeRecompute(ConceptRow row) {
        Recompute recompute = recompute(row);
        if (recompute.anchor() == null && row.anchor() == null) {
            return; // never computable — no point stamping updated_at
        }
        concepts.updateAnchor(row.id(), recompute.anchor(),
                recompute.anchor() == null ? null : props.embedding().model());
    }

    /** Row-level recompute used by refresh and the example triggers: reads the STORED
     *  phrase (never the provider); {@code hasInputs} is false exactly when the anchor
     *  comes out null — the refresh 422 condition. */
    private Recompute recompute(ConceptRow row) {
        float[] anchor = recompute(row.id(), row.phraseVector(), row.anchorModel());
        return new Recompute(anchor, anchor != null);
    }

    private void requireCurrentAnchor(ConceptRow row) {
        String model = props.embedding().model();
        if (row.anchor() == null) {
            throw new ConceptStaleException("concept '" + row.name()
                    + "' has no anchor yet: add examples or save a phrase");
        }
        if (!model.equals(row.anchorModel())) {
            throw new ConceptStaleException("concept '" + row.name() + "' is stale: anchor was built for "
                    + "model '" + row.anchorModel() + "', current model is '" + model + "' — refresh it");
        }
    }

    // --- read model ---------------------------------------------------------------------

    private ConceptDto toDto(ConceptRow row) {
        ConceptStore.Counts counts = concepts.counts(row.id());
        Set<Long> tagged = concepts.taggedEntryIdsByName(row.name());
        long applyable = concepts.entryIds(row.id(), "EXAMPLE").stream()
                .filter(entryId -> !tagged.contains(entryId)).count();
        boolean stale = row.anchorModel() != null && !row.anchorModel().equals(props.embedding().model());
        return new ConceptDto(row.id(), row.name(), row.phrase(), counts.examples(), counts.excluded(),
                tagged.size(), applyable, stale, row.createdAt(), row.updatedAt());
    }

    private ConceptRow require(long id) {
        return concepts.byId(id)
                .orElseThrow(() -> new GlobalExceptionHandler.MissingConceptException(id));
    }
}
