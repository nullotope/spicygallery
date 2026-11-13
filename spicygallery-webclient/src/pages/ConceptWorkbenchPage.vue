<template>
  <div class="q-pa-md concept-workbench" data-testid="concept-workbench">
    <RouterLink to="/concepts" class="text-caption" data-testid="concept-back-link">← concepts</RouterLink>

    <div v-if="notFound" data-testid="concept-not-found" class="text-grey-7 q-pa-lg">
      concept not found
    </div>

    <template v-else-if="concept">
      <div class="row items-center q-gutter-sm q-mb-sm">
        <span class="text-h6">{{ concept.name }}</span>
        <span class="text-caption text-grey-7">{{ concept.taggedCount }}/{{ concept.exampleCount }} tagged</span>
      </div>

      <div
        v-if="concept.anchorStale"
        class="row items-center q-gutter-sm q-mb-sm"
        data-testid="concept-stale-banner"
      >
        <span class="text-warning">anchor is stale — the embedding model changed; refresh rebuilds it from stored inputs</span>
        <q-btn
          data-testid="concept-refresh"
          dense
          outline
          no-caps
          label="refresh"
          :loading="store.applying"
          @click="doRefresh"
        />
      </div>

      <div v-if="actionError" data-testid="concept-action-error" class="text-caption text-negative q-mb-sm">
        {{ actionError }}
      </div>

      <div class="row items-center q-gutter-sm q-mb-sm">
        <q-input
          v-model="phraseDraft"
          data-testid="concept-phrase"
          dense
          outlined
          placeholder="anchor phrase"
          class="phrase-input"
          @keyup.enter="savePhrase"
        />
        <q-btn
          data-testid="concept-phrase-save"
          dense
          outline
          no-caps
          label="save"
          :disable="!phraseDirty"
          :loading="store.saving"
          @click="savePhrase"
        />
      </div>

      <div class="row items-center q-gutter-sm q-mb-md">
        <q-btn
          data-testid="concept-apply"
          dense
          outline
          no-caps
          :label="'apply to ' + concept.applyable"
          :disable="concept.applyable === 0"
          :loading="store.applying"
          @click="doApply"
        />
        <q-btn
          data-testid="concept-add-loaded"
          dense
          outline
          no-caps
          :label="'add loaded results (' + loadedCount + ')'"
          :disable="loadedCount === 0"
          @click="addLoaded"
        />
      </div>

      <div v-if="applyResult" data-testid="concept-apply-result" class="text-caption q-mb-sm">
        tagged {{ applyResult.tagged }} ({{ applyResult.alreadyTagged }} already)
      </div>
      <div v-if="seedAdded !== null" data-testid="concept-seed-notice" class="text-caption q-mb-sm">
        added {{ seedAdded }} examples
      </div>

      <div class="text-subtitle2 q-mb-xs">suggestions</div>
      <div v-if="suggestionsError" data-testid="suggestions-error" class="text-caption text-negative q-mb-sm">
        {{ suggestionsError }}
      </div>
      <div v-if="store.suggestionItems.length > 0" class="tile-grid q-mb-md">
        <div
          v-for="s in store.suggestionItems"
          :key="s.entry.id"
          class="tile"
          :data-testid="'suggestion-tile-' + s.entry.id"
        >
          <!-- the tile media/name links to the detail page exactly like the gallery
               grid; the decision buttons sit OUTSIDE the link so they never navigate -->
          <RouterLink
            class="suggestion-link"
            :to="'/entries/' + s.entry.id"
            :data-testid="'suggestion-link-' + s.entry.id"
          >
            <span class="tile-media">
              <img class="tile-thumb" :src="s.entry.thumbUrl" loading="lazy" alt="" />
              <span v-if="s.entry.durationMs !== null" class="duration-badge">{{ humanDuration(s.entry.durationMs) }}</span>
              <q-badge outline color="grey-7" class="score-badge" :data-testid="'suggestion-score-' + s.entry.id">
                {{ percent(s.score) }}
              </q-badge>
            </span>
            <span class="tile-name text-caption">{{ s.entry.fileName }}</span>
          </RouterLink>
          <div class="row justify-end q-gutter-xs q-px-xs q-pb-xs">
            <q-btn
              dense
              flat
              no-caps
              size="sm"
              label="accept"
              :data-testid="'suggestion-accept-' + s.entry.id"
              @click="accept(s)"
            />
            <q-btn
              dense
              flat
              no-caps
              size="sm"
              label="reject"
              :data-testid="'suggestion-reject-' + s.entry.id"
              @click="reject(s)"
            />
          </div>
        </div>
      </div>
      <div v-else-if="!store.loadingSuggestions && !suggestionsError" class="text-caption text-grey-7 q-mb-md">
        no suggestions — add examples or save a phrase
      </div>

      <div v-if="membersError" data-testid="members-error" class="text-caption text-negative q-mb-sm">
        {{ membersError }}
      </div>

      <div class="text-subtitle2">examples ({{ concept.exampleCount }})</div>
      <div v-if="(store.memberLists?.examples.length ?? 0) > 0" class="tile-grid q-mb-md">
        <div v-for="g in store.memberLists?.examples" :key="g.id" class="tile" :data-testid="'example-tile-' + g.id">
          <span class="tile-media">
            <img class="tile-thumb" :src="g.thumbUrl" loading="lazy" alt="" />
            <span v-if="g.durationMs !== null" class="duration-badge">{{ humanDuration(g.durationMs) }}</span>
          </span>
          <span class="tile-name text-caption">{{ g.fileName }}</span>
          <div class="row justify-end q-px-xs q-pb-xs">
            <q-btn
              dense
              flat
              no-caps
              size="sm"
              label="remove"
              :data-testid="'example-remove-' + g.id"
              @click="removeExample(g)"
            />
          </div>
        </div>
      </div>

      <q-btn
        dense
        flat
        no-caps
        size="sm"
        color="grey-7"
        :label="'excluded (' + concept.excludedCount + ')'"
        data-testid="excluded-toggle"
        @click="excludedOpen = !excludedOpen"
      />
      <div v-if="excludedOpen && (store.memberLists?.excluded.length ?? 0) > 0" class="tile-grid q-mb-md">
        <div v-for="g in store.memberLists?.excluded" :key="g.id" class="tile" :data-testid="'exclusion-tile-' + g.id">
          <span class="tile-media">
            <img class="tile-thumb" :src="g.thumbUrl" loading="lazy" alt="" />
          </span>
          <span class="tile-name text-caption">{{ g.fileName }}</span>
          <div class="row justify-end q-px-xs q-pb-xs">
            <q-btn
              dense
              flat
              no-caps
              size="sm"
              label="un-exclude"
              :data-testid="'exclusion-unexclude-' + g.id"
              @click="unexclude(g)"
            />
          </div>
        </div>
      </div>
    </template>

    <div v-else-if="loadError" data-testid="concept-error" class="text-caption text-negative q-pa-sm">
      {{ loadError }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import axios from 'axios';
import { useConceptsStore } from 'src/stores/concepts';
import { useEntriesStore, type GridItem } from 'src/stores/entries';
import type { ApplyResult, SuggestionItem } from 'src/types/concepts';
import { humanDuration } from 'src/utils/format';

const props = defineProps<{ id: string }>();

const store = useConceptsStore();
const entries = useEntriesStore();

const conceptId = computed(() => Number(props.id));

/** Only the concept this page loaded — never a previously visited one still
 *  sitting in the shared store while the fresh get() is in flight. */
const concept = computed(() => (store.current?.id === conceptId.value ? store.current : null));

const notFound = ref(false);
const loadError = ref<string | null>(null);
const suggestionsError = ref<string | null>(null);
const membersError = ref<string | null>(null);
const actionError = ref<string | null>(null);
const phraseDraft = ref('');
const applyResult = ref<ApplyResult | null>(null);
const seedAdded = ref<number | null>(null);

const excludedOpen = ref(false);

/** Blank is never dirty — a PATCH phrase is a provider round-trip and the
 *  server treats blank as a no-op, so an empty editor saves nothing. */
const phraseDirty = computed(() => {
  const c = concept.value;
  if (!c) {
    return false;
  }
  const next = phraseDraft.value.trim();
  return next !== '' && next !== c.phrase;
});

const loadedCount = computed(() => entries.items.length);

onMounted(() => {
  void reload();
});

/** Param-only navigation (browser back/forward, concept→concept links) reuses
 *  this instance — the route's `props: true` hands us a new id without a remount.
 *  Reload so the shared store's previous concept never lingers on screen. */
watch(
  () => props.id,
  () => {
    applyResult.value = null;
    seedAdded.value = null;
    actionError.value = null;
    excludedOpen.value = false;
    void reload();
  },
);

/** The store rejects with the problem-detail Error (or a raw AxiosError for
 *  non-problem failures) — extract a message that works for both. */
function messageOf(e: unknown): string {
  if (axios.isAxiosError(e)) {
    const detail = (e.response?.data as { detail?: string } | undefined)?.detail;
    if (detail) {
      return detail;
    }
  }
  return e instanceof Error ? e.message : String(e);
}

/** GET /api/concepts/{id} answers 404 for an unknown id (the house pattern —
 *  MissingConceptException, mapped like a missing entry). Detection matches the
 *  RESPONSE STATUS only, never the detail text: a server-side rewording must not
 *  change this page. The store preserves the status on unwrapped problem-detail
 *  Errors; raw AxiosError pass-throughs (no problem body) carry response.status. */
function isUnknownConcept(e: unknown): boolean {
  const status = (e as { status?: number } | null | undefined)?.status
    ?? (axios.isAxiosError(e) ? e.response?.status : undefined);
  return status === 404;
}

async function reload() {
  notFound.value = false;
  loadError.value = null;
  suggestionsError.value = null;
  membersError.value = null;
  try {
    const c = await store.get(conceptId.value);
    phraseDraft.value = c.phrase ?? '';
  } catch (e) {
    if (isUnknownConcept(e)) {
      notFound.value = true;
      return;
    }
    loadError.value = messageOf(e);
    return;
  }
  // the member tiles and the ranked suggestions are independent reads — a
  // suggestions 409 (stale/anchorless) leaves the workbench usable either way
  await Promise.allSettled([refetchSuggestions(), refetchMembers()]);
}

/** Fresh counts; never clobbers the phrase editor mid-edit. */
async function refetchConcept() {
  try {
    await store.get(conceptId.value);
  } catch (e) {
    loadError.value = messageOf(e);
  }
}

/** Server-truth member tiles (spec §4.2) — re-fetched after every membership
 *  mutation. Failure leaves the rest of the workbench usable. */
async function refetchMembers() {
  try {
    await store.members(conceptId.value);
    membersError.value = null;
  } catch (e) {
    membersError.value = messageOf(e);
  }
}

/** Re-ranked after every anchor/exclusion change. */
async function refetchSuggestions() {
  try {
    await store.suggestions(conceptId.value);
    suggestionsError.value = null;
  } catch (e) {
    suggestionsError.value = messageOf(e);
  }
}

async function accept(s: SuggestionItem) {
  actionError.value = null;
  try {
    await store.addExamples(conceptId.value, [s.entry.id]);
    // the anchor moved and the example set grew — re-rank, fresh counts, fresh tiles
    await Promise.all([refetchSuggestions(), refetchConcept(), refetchMembers()]);
  } catch (e) {
    actionError.value = messageOf(e);
  }
}

async function reject(s: SuggestionItem) {
  actionError.value = null;
  try {
    await store.addExclusions(conceptId.value, [s.entry.id]);
    // the exclusion set changed — the next suggestions page drops the entry
    await Promise.all([refetchSuggestions(), refetchConcept(), refetchMembers()]);
  } catch (e) {
    actionError.value = messageOf(e);
  }
}

async function removeExample(g: GridItem) {
  actionError.value = null;
  try {
    await store.removeExample(conceptId.value, g.id);
    // the removed example's vector leaves the anchor — re-rank too
    await Promise.all([refetchSuggestions(), refetchConcept(), refetchMembers()]);
  } catch (e) {
    actionError.value = messageOf(e);
  }
}

async function unexclude(g: GridItem) {
  actionError.value = null;
  try {
    await store.removeExclusion(conceptId.value, g.id);
    await Promise.all([refetchConcept(), refetchMembers()]);
  } catch (e) {
    actionError.value = messageOf(e);
  }
}

async function doApply() {
  const c = concept.value;
  if (!c || c.applyable === 0) {
    return;
  }
  actionError.value = null;
  applyResult.value = null;
  try {
    applyResult.value = await store.apply(conceptId.value);
    // applyable drops — re-fetch the concept. Suggestions need no re-fetch:
    // already-tagged entries are excluded server-side on the next call.
    await refetchConcept();
  } catch (e) {
    actionError.value = messageOf(e);
  }
}

async function doRefresh() {
  actionError.value = null;
  try {
    await store.refresh(conceptId.value); // caches the refreshed concept as current
    // the anchor moved — re-rank; a successful refresh also clears the stale banner
    await refetchSuggestions();
  } catch (e) {
    // 422: "no inputs for the current model; re-embed entries or re-save the phrase"
    actionError.value = messageOf(e);
  }
}

async function savePhrase() {
  const c = concept.value;
  if (!c || !phraseDirty.value) {
    return;
  }
  actionError.value = null;
  try {
    // the response is the fresh DTO — a re-resolved phrase can heal a stale anchor
    store.current = await store.patch(c.id, { phrase: phraseDraft.value.trim() });
    // the anchor was re-resolved and recomputed — the ranked list is stale until now
    await refetchSuggestions();
  } catch (e) {
    // nothing re-fetched on failure: the stale banner state is untouched (the
    // failed PATCH wrote nothing server-side)
    actionError.value = messageOf(e);
  }
}

async function addLoaded() {
  const ids = entries.items.map((i) => i.id);
  if (ids.length === 0) {
    return;
  }
  actionError.value = null;
  seedAdded.value = null;
  try {
    seedAdded.value = await store.addExamples(conceptId.value, ids);
    // the anchor moved and the member tiles changed
    await Promise.all([refetchSuggestions(), refetchConcept(), refetchMembers()]);
  } catch (e) {
    actionError.value = messageOf(e);
  }
}

/** Review-dialog style: the best-frame cosine as a whole percent. */
function percent(score: number): string {
  return Math.round(score * 100) + '%';
}
</script>

<style scoped>
.phrase-input {
  min-width: 260px;
}
/* the suggestion tile's media/name live inside the link (detail-page navigation
   like the gallery grid); the block children carry the tile styling */
.suggestion-link {
  display: block;
  color: inherit;
  text-decoration: none;
}
.score-badge {
  position: absolute;
  top: 4px;
  left: 4px;
}
</style>
