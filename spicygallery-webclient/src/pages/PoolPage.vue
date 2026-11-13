<template>
  <div class="q-pa-md" data-testid="pool-page">
    <RouterLink to="/pools" class="text-caption" data-testid="pool-back-link">← pools</RouterLink>

    <div v-if="store.loading" class="row justify-center q-my-md">
      <q-spinner-dots color="primary" size="40px" />
    </div>

    <div v-if="notFound" data-testid="pool-not-found" class="text-grey-7 q-pa-lg">
      pool not found
    </div>

    <template v-else-if="detail">
      <div class="row items-center q-gutter-sm q-mb-xs">
        <q-input
          v-model="nameDraft"
          data-testid="pool-rename"
          dense
          outlined
          class="pool-name-input"
          @keyup.enter="saveName"
        />
        <span data-testid="pool-count" class="text-caption text-grey-7">
          {{ detail.pool.entryCount }} entries
        </span>
      </div>
      <div v-if="detail.pool.description" class="text-caption text-grey-7 q-mb-sm">
        {{ detail.pool.description }}
      </div>

      <!-- pool tags (spec §3.5): own editor on TagEditor's interaction pattern —
           chips with category colors, autocomplete add, remove; save is the
           replace-set PATCH; `pool_type:pool` renders as a FIXED system chip -->
      <div data-testid="pool-tags" class="q-mb-sm">
        <template v-for="cat in tagCategories" :key="cat">
          <div class="text-caption text-grey-7 q-mt-sm">{{ cat }}</div>
          <div class="row items-center no-wrap" v-for="t in tagsByCategory[cat]" :key="t.name">
            <TagChip :name="t.name" :category="t.category" />
            <q-btn
              v-if="isSystemTag(t)"
              dense
              flat
              round
              size="xs"
              icon="lock"
              aria-label="system tag (fixed)"
              :data-testid="'pool-tag-system-' + t.name"
            />
            <q-btn
              v-else
              dense
              flat
              round
              size="xs"
              icon="close"
              :data-testid="'pool-tag-remove-' + t.name"
              :aria-label="'remove ' + t.name"
              @click="removeTag(t.name)"
            />
          </div>
        </template>

        <q-input
          v-model="tagQuery"
          data-testid="pool-tag-input"
          dense
          outlined
          :debounce="200"
          placeholder="add tag…"
          @update:model-value="suggestTags"
          @keydown.enter.prevent="onTagEnter"
        />
        <div v-if="suggestions.length || createVisible" class="suggestions">
          <div
            v-for="s in suggestions"
            :key="s.name"
            class="row items-center suggestion cursor-pointer"
            :data-testid="'pool-tag-suggestion-' + s.name"
            @click="pickTag(s)"
          >
            <TagChip :name="s.aliasOf ?? s.name" :category="s.category" />
            <span class="text-caption text-grey-7 q-ml-xs">({{ s.postCount }})</span>
            <span v-if="s.aliasOf" class="text-caption text-grey-7 q-ml-xs">alias of {{ s.name }}</span>
          </div>
          <div
            v-if="createVisible"
            class="row items-center suggestion cursor-pointer"
            data-testid="pool-tag-create"
            @click="addNewTag"
          >
            <span class="text-caption">create '{{ normalizedQuery }}'</span>
          </div>
        </div>

        <div class="row q-gutter-sm q-mt-sm">
          <q-btn dense outline no-caps data-testid="pool-tag-save" label="save tags" @click="saveTags" />
          <q-btn dense flat no-caps data-testid="pool-tag-cancel" label="cancel" @click="resetTagEditor" />
        </div>
      </div>

      <div v-if="actionError" data-testid="pool-action-error" class="text-caption text-negative q-mb-sm">
        {{ actionError }}
      </div>

      <div class="row q-gutter-sm q-mb-sm">
        <q-btn
          dense
          outline
          no-caps
          icon="add"
          label="add"
          data-testid="pool-add-btn"
          @click="addOpen = true"
        />
      </div>

      <!-- mixed member grid in the pool's unified 1..N position sequence (spec §3.9) -->
      <div class="tile-grid">
        <div
          v-for="(m, i) in members"
          :key="m.memberId"
          class="tile"
          :data-testid="'pool-member-' + m.memberId"
        >
          <!-- the tile media/name link into the reading view exactly like the gallery
               grid; the ordering/removal buttons sit OUTSIDE the link so they never
               navigate (workbench precedent) -->
          <RouterLink
            class="member-link"
            :to="memberTo(m)"
            :data-testid="'pool-member-link-' + m.memberId"
          >
            <span class="tile-media">
              <img v-if="m.entry" class="tile-thumb" :src="m.entry.thumbUrl" loading="lazy" alt="" />
              <span v-else class="tile-thumb tile-placeholder row flex-center">
                <q-icon name="photo_library" size="36px" />
              </span>
              <span
                v-if="m.entry && m.entry.durationMs !== null"
                class="duration-badge"
              >{{ humanDuration(m.entry.durationMs) }}</span>
              <span class="position-badge" :data-testid="'pool-member-position-' + m.memberId">{{ m.position }}</span>
            </span>
            <span class="tile-name text-caption">{{ memberName(m) }}</span>
            <!-- sub-pool members are summary tiles: icon + flattened count badge -->
            <span v-if="m.pool" class="row items-center no-wrap q-px-sm q-pb-xs">
              <q-badge outline color="grey-7" :data-testid="'pool-badge-' + m.memberId">
                <q-icon name="photo_library" size="12px" class="q-mr-xs" />
                {{ m.pool.entryCount }}
              </q-badge>
            </span>
          </RouterLink>
          <div class="row justify-end q-gutter-xs q-px-xs q-pb-xs">
            <q-btn
              dense
              flat
              no-caps
              size="sm"
              icon="arrow_upward"
              :disable="i === 0"
              :aria-label="'move member ' + m.memberId + ' up'"
              :data-testid="'pool-member-up-' + m.memberId"
              @click="move(m, -1)"
            />
            <q-btn
              dense
              flat
              no-caps
              size="sm"
              icon="arrow_downward"
              :disable="i === members.length - 1"
              :aria-label="'move member ' + m.memberId + ' down'"
              :data-testid="'pool-member-down-' + m.memberId"
              @click="move(m, 1)"
            />
            <q-btn
              dense
              flat
              no-caps
              size="sm"
              :label="armed === m.memberId ? 'confirm?' : 'remove'"
              :color="armed === m.memberId ? 'negative' : 'grey-7'"
              :aria-label="(armed === m.memberId ? 'confirm remove member ' : 'remove member ') + m.memberId"
              :data-testid="'pool-member-remove-' + m.memberId"
              @click="removeMember(m)"
            />
          </div>
        </div>
      </div>
      <div v-if="members.length === 0" class="text-grey-7 q-pa-lg">
        no members yet — use “add” to search entries or pick sub-pools
      </div>
    </template>

    <div v-else-if="loadError" data-testid="pool-error" class="text-caption text-negative q-pa-sm">
      {{ loadError }}
    </div>

    <PoolAddModal v-model="addOpen" :pool-id="poolId" @added="refetchDetail" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import axios from 'axios';
import { api } from 'src/boot/api';
import { usePoolsStore } from 'src/stores/pools';
import { TAG_CATEGORIES, normalizeTagName, type AutocompleteDto, type TagCategory, type TagDto } from 'src/types/tags';
import TagChip from 'src/components/TagChip.vue';
import PoolAddModal from 'src/components/PoolAddModal.vue';
import type { PoolMember } from 'src/types/pools';
import { humanDuration } from 'src/utils/format';

const props = defineProps<{ id: string }>();

const store = usePoolsStore();

const poolId = computed(() => Number(props.id));

/** Only the pool this page loaded — never a previously visited one still
 *  sitting in the shared store while the fresh get() is in flight. */
const detail = computed(() => (store.current?.pool.id === poolId.value ? store.current : null));

/** The member rows in position order — the wire contract guarantees the server
 *  sends them sorted; the grid renders that order as-is. */
const members = computed<PoolMember[]>(() => detail.value?.members ?? []);

const notFound = ref(false);
const loadError = ref<string | null>(null);
const actionError = ref<string | null>(null);
const nameDraft = ref('');
/** Remove is a two-click confirm: the first click arms the member's button. */
const armed = ref<number | null>(null);

/** The add-members modal (Entries / sub-Pools modes). */
const addOpen = ref(false);

// --- pool tag editor (own logic on TagEditor's interaction pattern, spec §3.5) ---

/** The system tag is ALWAYS on every stored pool (V5 seeds it; setTags re-adds it
 *  when a patch omits it) — the editor renders it as a fixed, non-removable chip. */
const SYSTEM_TAG_NAME = 'pool';

function isSystemTag(t: TagDto): boolean {
  return t.name === SYSTEM_TAG_NAME && t.category === 'pool_type';
}

/** The chips under edit. */
const tagWorking = ref<TagDto[]>([]);
/** Last known server truth (load response / save response) — what cancel restores. */
const tagServerTags = ref<TagDto[]>([]);
const tagQuery = ref('');
const suggestions = ref<AutocompleteDto[]>([]);

const tagsByCategory = computed<Record<TagCategory, TagDto[]>>(() => {
  const grouped = { pool_type: [], general: [], artist: [], character: [], copyright: [], meta: [] } as Record<
    TagCategory,
    TagDto[]
  >;
  for (const t of tagWorking.value) {
    grouped[t.category].push(t);
  }
  return grouped;
});
const tagCategories = computed(() => TAG_CATEGORIES.filter((c) => tagsByCategory.value[c].length > 0));

/** Seed the editor once per pool: the immediate first run covers a pool already
 *  sitting in the shared store, the id guard keeps local edits alive across the
 *  page's unrelated refetches (rename, reorder, membership changes). */
watch(
  detail,
  (d, prev) => {
    if (!d || (prev && prev.pool.id === d.pool.id)) {
      return;
    }
    tagServerTags.value = d.pool.tags;
    tagWorking.value = d.pool.tags.map((t) => ({ ...t }));
    tagQuery.value = '';
    suggestions.value = [];
  },
  { immediate: true },
);

async function suggestTags() {
  if (!tagQuery.value.trim()) {
    suggestions.value = [];
    return;
  }
  const { data } = await api.get<AutocompleteDto[]>('/api/tags/autocomplete', {
    params: { q: tagQuery.value.trim() },
  });
  suggestions.value = data;
}

const normalizedQuery = computed(() => normalizeTagName(tagQuery.value));

/** The create row (TagEditor's pattern, §3.5 → §4.2): an unknown name is added
 *  locally as `general` — the save path posts bare names and the server creates
 *  unknown ones as general. Hidden while the query already sits in the working
 *  set (the fixed system chip's name can never be re-created) or matches a
 *  suggestion (Enter picks that instead). */
const createVisible = computed(
  () =>
    normalizedQuery.value.length > 0 &&
    !tagWorking.value.some((t) => t.name === normalizedQuery.value) &&
    !suggestions.value.some((s) => s.name === normalizedQuery.value),
);

function addTag(name: string, category: TagCategory) {
  if (!tagWorking.value.some((t) => t.name === name)) {
    tagWorking.value.push({ id: 0, name, category, postCount: 0 });
  }
  tagQuery.value = '';
  suggestions.value = [];
}

function pickTag(s: AutocompleteDto) {
  addTag(s.aliasOf ?? s.name, s.category);
}

function addNewTag() {
  addTag(normalizedQuery.value, 'general');
}

/** Enter picks an exact suggestion; only when none matches does it create. The
 *  lookup normalizes FIRST (createVisible's own yardstick) so a raw query like
 *  "blue dragon" picks the existing blue_dragon instead of silently no-opping. */
function onTagEnter() {
  const exact = suggestions.value.find((s) => s.name === normalizedQuery.value);
  if (exact) {
    pickTag(exact);
  } else if (createVisible.value) {
    addNewTag();
  }
}

function removeTag(name: string) {
  tagWorking.value = tagWorking.value.filter((t) => t.name !== name);
}

/** Save is the replace-set PATCH: the editor posts exactly what it shows (the
 *  fixed system chip included — the server tolerates and re-adds it), then adopts
 *  the response's fresh tag rows. */
async function saveTags() {
  if (!detail.value) {
    return;
  }
  actionError.value = null;
  try {
    const fresh = await store.setTags(poolId.value, tagWorking.value.map((t) => t.name));
    tagServerTags.value = fresh;
    tagWorking.value = fresh.map((t) => ({ ...t }));
    tagQuery.value = '';
    suggestions.value = [];
  } catch (e) {
    actionError.value = messageOf(e);
  }
}

/** Cancel discards local edits back to the last server truth. */
function resetTagEditor() {
  tagWorking.value = tagServerTags.value.map((t) => ({ ...t }));
  tagQuery.value = '';
  suggestions.value = [];
}

onMounted(() => {
  void reload();
});

/** Param-only navigation (browser back/forward, pool→pool links) reuses this
 *  instance — the route's `props: true` hands us a new id without a remount.
 *  Reload so the shared store's previous pool never lingers on screen. */
watch(
  () => props.id,
  () => {
    armed.value = null;
    actionError.value = null;
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

/** GET /api/pools/{id} answers 404 for an unknown id (the house pattern —
 *  missing-pool mapped like a missing entry). Detection matches the RESPONSE
 *  STATUS only, never the detail text: a server-side rewording must not change
 *  this page. The store preserves the status on unwrapped problem-detail Errors;
 *  raw AxiosError pass-throughs (no problem body) carry response.status. */
function isUnknownPool(e: unknown): boolean {
  const status = (e as { status?: number } | null | undefined)?.status
    ?? (axios.isAxiosError(e) ? e.response?.status : undefined);
  return status === 404;
}

async function reload() {
  notFound.value = false;
  loadError.value = null;
  try {
    const d = await store.get(poolId.value);
    nameDraft.value = d.pool.name;
  } catch (e) {
    if (isUnknownPool(e)) {
      notFound.value = true;
      return;
    }
    loadError.value = messageOf(e);
  }
}

/** Fresh server-truth after every mutation (house pattern) — never clobbers the
 *  rename draft mid-edit. */
async function refetchDetail() {
  try {
    await store.get(poolId.value);
  } catch (e) {
    actionError.value = messageOf(e);
  }
}

/** Entry members link into the reading view carrying the pool context (?pool=
 *  powers the reading neighbors, spec §3.9); sub-pool members drill into the
 *  child pool page. */
function memberTo(m: PoolMember): string | { path: string; query: Record<string, string> } {
  const pid = detail.value?.pool.id;
  if (m.entry && pid !== undefined) {
    return { path: '/entries/' + m.entry.id, query: { pool: String(pid) } };
  }
  if (m.pool) {
    return '/pools/' + m.pool.id;
  }
  return '/pools';
}

function memberName(m: PoolMember): string {
  return m.entry?.fileName ?? m.pool?.name ?? '';
}

/** The move up/down always posts the reordered FULL member-id list — the
 *  endpoint takes a permutation of all member-row ids, not a delta. */
async function move(m: PoolMember, delta: -1 | 1) {
  const rows = members.value;
  const i = rows.findIndex((x) => x.memberId === m.memberId);
  const j = i + delta;
  if (i < 0 || j < 0 || j >= rows.length) {
    return;
  }
  actionError.value = null;
  const order = rows.map((x) => x.memberId);
  // both indices are in range (guarded above) — swap the neighbors in place
  const a = order[i]!;
  const b = order[j]!;
  order[i] = b;
  order[j] = a;
  try {
    await store.reorder(poolId.value, order);
  } catch (e) {
    actionError.value = messageOf(e);
    return;
  }
  await refetchDetail();
}

async function removeMember(m: PoolMember) {
  if (armed.value !== m.memberId) {
    armed.value = m.memberId;
    return;
  }
  armed.value = null;
  actionError.value = null;
  try {
    await store.removeMember(poolId.value, m.memberId);
  } catch (e) {
    actionError.value = messageOf(e);
    return;
  }
  await refetchDetail();
}

/** Blank is never dirty — a PATCH name is a round-trip and the server treats
 *  blank as a no-op, so an empty editor saves nothing. */
const nameDirty = computed(() => {
  const p = detail.value?.pool;
  if (!p) {
    return false;
  }
  const next = nameDraft.value.trim();
  return next !== '' && next !== p.name;
});

async function saveName() {
  const p = detail.value?.pool;
  if (!p || !nameDirty.value) {
    return;
  }
  const next = nameDraft.value.trim();
  actionError.value = null;
  try {
    await store.patch(p.id, { name: next });
    // every mutation refetches: fresh name, fresh flattened counts
    const fresh = await store.get(poolId.value);
    nameDraft.value = fresh.pool.name;
  } catch (e) {
    actionError.value = messageOf(e);
  }
}
</script>

<style scoped>
.pool-name-input {
  min-width: 260px;
}
/* the member tile's media/name live inside the link (reading navigation like the
   gallery grid); the block children carry the tile styling */
.member-link {
  display: block;
  color: inherit;
  text-decoration: none;
}
.position-badge {
  position: absolute;
  top: 4px;
  left: 4px;
  background: rgba(0, 0, 0, 0.72);
  color: #fff;
  font-size: 11px;
  line-height: 1.4;
  padding: 1px 6px;
  border-radius: 3px;
}
.tile-placeholder {
  color: #bbb;
}
.body--dark .tile-placeholder {
  color: #555;
}
/* tag editor suggestion rows (TagEditor's pattern) */
.suggestion {
  padding: 2px 4px;
}
</style>
