<template>
  <div class="entries-layout">
    <aside class="entries-sidebar">
      <SideBar :entry-tags="entry?.tags ?? null" @tags-saved="onTagsSaved" />
      <div v-if="entry" class="autotag-row q-px-sm q-pb-sm">
        <q-btn
          dense
          outline
          size="sm"
          no-caps
          icon="auto_fix_high"
          label="Autotag"
          data-testid="autotag-open"
          @click="autotagOpen = true"
        >
          <q-tooltip>suggest tags with the tagging model</q-tooltip>
        </q-btn>
        <q-btn-dropdown
          dense
          outline
          size="sm"
          no-caps
          label="add to concept"
          data-testid="add-to-concept"
          @before-show="reloadConcepts"
        >
          <q-list v-if="!creating" dense>
            <template v-if="concepts.items.length">
              <q-item
                v-for="c in concepts.items"
                :key="c.id"
                clickable
                v-close-popup
                :data-testid="'concept-option-' + c.id"
                @click="addToConcept(c)"
              >
                <q-item-section>
                  {{ c.name }}
                  <span v-if="c.exampleCount > 0" class="text-caption text-grey-7">
                    · +{{ c.exampleCount }} examples
                  </span>
                </q-item-section>
              </q-item>
              <q-separator />
            </template>
            <q-item clickable :data-testid="'concept-option-new'" @click="creating = true">
              <q-item-section>new concept…</q-item-section>
            </q-item>
          </q-list>
          <div v-else class="q-pa-sm column q-gutter-y-xs" style="min-width: 190px">
            <q-input
              v-model="newConceptName"
              dense
              outlined
              data-testid="new-concept-name"
              placeholder="name"
              @keyup.enter="createAndAdd"
            />
            <q-input
              v-model="newConceptPhrase"
              dense
              outlined
              data-testid="new-concept-phrase"
              placeholder="anchor phrase (optional)"
              @keyup.enter="createAndAdd"
            />
            <div class="row q-gutter-x-xs">
              <q-btn
                dense
                outline
                no-caps
                label="create"
                data-testid="new-concept-create"
                :loading="busy"
                v-close-popup
                @click="createAndAdd"
              />
              <q-btn
                dense
                flat
                no-caps
                label="cancel"
                data-testid="new-concept-cancel"
                @click="cancelCreate"
              />
            </div>
          </div>
        </q-btn-dropdown>
        <q-btn-dropdown
          dense
          outline
          size="sm"
          no-caps
          label="add to pool"
          data-testid="add-to-pool"
          @before-show="reloadPools"
        >
          <q-list v-if="!creatingPool" dense>
            <template v-if="poolOptions.length">
              <q-item
                v-for="p in poolOptions"
                :key="p.id"
                clickable
                v-close-popup
                :data-testid="'pool-option-' + p.id"
                @click="addToPool(p)"
              >
                <q-item-section>
                  {{ p.name }}
                  <span v-if="p.member" class="text-caption text-grey-7">✓ member</span>
                </q-item-section>
              </q-item>
              <q-separator />
            </template>
            <q-item clickable :data-testid="'pool-option-new'" @click="creatingPool = true">
              <q-item-section>new pool…</q-item-section>
            </q-item>
            <q-item clickable v-close-popup data-testid="pool-option-folder" @click="addPoolFromFolder">
              <q-item-section>pool from this folder</q-item-section>
            </q-item>
          </q-list>
          <div v-else class="q-pa-sm column q-gutter-y-xs" style="min-width: 190px">
            <q-input
              v-model="newPoolName"
              dense
              outlined
              data-testid="new-pool-name"
              placeholder="name"
              @keyup.enter="createAndAddToPool"
            />
            <div class="row q-gutter-x-xs">
              <q-btn
                dense
                outline
                no-caps
                label="create"
                data-testid="new-pool-create"
                :loading="poolBusy"
                v-close-popup
                @click="createAndAddToPool"
              />
              <q-btn
                dense
                flat
                no-caps
                label="cancel"
                data-testid="new-pool-cancel"
                @click="cancelPoolCreate"
              />
            </div>
          </div>
        </q-btn-dropdown>
        <div
          v-if="conceptNotice"
          data-testid="add-to-concept-notice"
          class="text-caption"
          :class="conceptNoticeError ? 'text-negative' : 'text-positive'"
        >
          {{ conceptNotice }}
        </div>
        <div
          v-if="poolNotice"
          data-testid="add-to-pool-notice"
          class="text-caption"
          :class="poolNoticeError ? 'text-negative' : 'text-positive'"
        >
          {{ poolNotice }}
        </div>
      </div>
    </aside>
    <main class="q-pa-md" style="flex: 1; min-width: 0">
      <div class="row items-center q-gutter-sm q-mb-sm">
        <q-btn
          data-testid="prev-btn"
          dense
          flat
          icon="chevron_left"
          :disable="prevId === null"
          @click="go(prevId)"
        />
        <q-btn
          data-testid="next-btn"
          dense
          flat
          icon-right="chevron_right"
          :disable="nextId === null"
          @click="go(nextId)"
        />
        <span
          v-if="poolName !== null && position !== null && total !== null"
          data-testid="pool-reading-header"
          class="text-caption text-grey-8"
        >page {{ position }} of {{ total }} · {{ poolName }}</span>
        <span v-if="entry" class="text-caption text-grey-8">{{ entry.path }}</span>
      </div>

      <template v-if="entry">
        <!-- browser-playable image -->
        <img
          v-if="entry.kind === 'image'"
          class="viewer"
          :src="entry.fileUrl"
          :alt="entry.fileName"
        />

        <!-- browser-playable video -->
        <video
          v-else-if="browserPlayableVideo"
          class="viewer"
          controls
          :src="entry.fileUrl"
        ></video>

        <!-- fallback: browser cannot play this format -->
        <q-card v-else flat bordered class="q-pa-md fallback-card">
          <q-icon name="movie" size="48px" class="text-grey-6" />
          <div class="text-subtitle1">The browser may not play this format ({{ entry.ext }})</div>
          <div class="text-caption text-grey-7">{{ entry.mime }}</div>
          <q-btn
            class="q-mt-sm"
            no-caps
            outline
            color="primary"
            :href="entry.fileUrl"
            download
            label="Open / download"
          />
        </q-card>

        <!-- metadata panel -->
        <div class="text-caption text-grey-8 q-mt-md metadata" data-testid="metadata">
          <div>{{ entry.root }} · {{ entry.path }}</div>
          <div>
            <template v-if="entry.width && entry.height">{{ entry.width }}×{{ entry.height }} · </template>
            {{ humanSize(entry.sizeBytes) }}
            <template v-if="entry.durationMs"> · {{ humanDuration(entry.durationMs) }}</template>
            · imported {{ new Date(entry.importedAt).toLocaleString() }}
          </div>
        </div>

        <!-- semantic Related strip (hidden without vectors / on provider errors) -->
        <RelatedStrip :entry-id="id" />
      </template>
      <div v-else class="text-grey-7 q-pa-lg">loading…</div>

      <!-- autotag review before write (spec §4.1); applied tags refresh via onTagsSaved -->
      <TaggingReviewDialog v-model="autotagOpen" :entry-id="id" @applied="onTagsSaved" />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import axios from 'axios';
import { api } from 'src/boot/api';
import { useEntriesStore, type KindFilter } from 'src/stores/entries';
import { useConceptsStore } from 'src/stores/concepts';
import { usePoolsStore } from 'src/stores/pools';
import { useNeighbors } from 'src/composables/useNeighbors';
import SideBar from 'src/components/SideBar.vue';
import RelatedStrip from 'src/components/RelatedStrip.vue';
import TaggingReviewDialog from 'src/components/TaggingReviewDialog.vue';
import type { TagDto } from 'src/types/tags';
import type { ConceptDto } from 'src/types/concepts';
import type { PoolDto } from 'src/types/pools';
import { humanDuration } from 'src/utils/format';

interface EntryDetail {
  id: number;
  kind: 'image' | 'video';
  status: 'ok' | 'missing' | 'error';
  root: string;
  path: string;
  fileName: string;
  ext: string;
  mime: string;
  sizeBytes: number;
  width: number | null;
  height: number | null;
  durationMs: number | null;
  importedAt: string;
  updatedAt: string;
  fileUrl: string;
  thumbUrl: string;
  tags: TagDto[];
}

const route = useRoute();
const router = useRouter();
const entries = useEntriesStore();

const entry = ref<EntryDetail | null>(null);
const id = ref<number>(Number(route.params.id));
const autotagOpen = ref(false);

// add-to-concept dropdown (spec §3.5): the entry as an example of a concept
const concepts = useConceptsStore();
/** The "new concept…" mini-form replaces the menu list while set. */
const creating = ref(false);
const newConceptName = ref('');
const newConceptPhrase = ref('');
const busy = ref(false);
/** Inline notice (the TaggingControls pattern): persists until the next add attempt. */
const conceptNotice = ref<string | null>(null);
const conceptNoticeError = ref(false);

// add-to-pool dropdown (spec §4.3): the entry into one of the pools
/** The dropdown's own list — the RETURN VALUE of `list({entryId})` (each tile
 *  carrying its additive `member` flag). Deliberately NOT the store `items`:
 *  that state is shared with the pools grid, and this page must not read it. */
const poolOptions = ref<PoolDto[]>([]);
/** The "new pool…" mini-form replaces the menu list while set (the concept pattern). */
const creatingPool = ref(false);
const newPoolName = ref('');
const poolBusy = ref(false);
const poolNotice = ref<string | null>(null);
const poolNoticeError = ref(false);

const q = computed(() => (typeof route.query.q === 'string' ? route.query.q : ''));
const kind = computed(() =>
  route.query.kind === 'image' || route.query.kind === 'video'
    ? (route.query.kind as KindFilter)
    : '',
);
// pool reading flow (spec §3.9): present when linked from a pool page
const pool = computed(() => (typeof route.query.pool === 'string' ? route.query.pool : ''));

const { prevId, nextId, position, total } = useNeighbors(
  id, computed(() => entries.items), q, kind, pool,
);

const pools = usePoolsStore();
/** The reading pool's name for the header — null while loading or when the id is
 *  unknown/not-contained: the page degrades to plain entry chrome (the pool-scoped
 *  neighbors swallow their own 404 the same way). */
const poolName = ref<string | null>(null);

async function loadPoolName() {
  poolName.value = null;
  const poolId = Number(pool.value);
  if (!pool.value || !Number.isFinite(poolId)) {
    return;
  }
  try {
    poolName.value = (await pools.get(poolId)).pool.name;
  } catch {
    poolName.value = null;
  }
}
watch(pool, () => void loadPoolName(), { immediate: true });

const browserPlayableVideo = computed(() =>
  entry.value !== null
    && entry.value.kind === 'video'
    && (entry.value.mime === 'video/mp4' || entry.value.mime === 'video/webm'),
);

async function load() {
  id.value = Number(route.params.id);
  entry.value = null;
  conceptNotice.value = null; // a notice belongs to the entry it was raised on
  conceptNoticeError.value = false;
  poolNotice.value = null;
  poolNoticeError.value = false;
  const { data } = await api.get<EntryDetail>(`/api/entries/${id.value}`);
  entry.value = data;
}

function onTagsSaved(): void {
  void load(); // re-render from the server response (implications may have added tags)
}

/** The store rejects with the problem-detail Error (or a raw AxiosError for
 *  non-problem failures) — extract a message that works for both (the
 *  ConceptsPage pattern). */
function messageOf(e: unknown): string {
  if (axios.isAxiosError(e)) {
    const detail = (e.response?.data as { detail?: string } | undefined)?.detail;
    if (detail) {
      return detail;
    }
  }
  return e instanceof Error ? e.message : String(e);
}

/** The dropdown fetches lazily on before-show — never on page mount. */
function reloadConcepts() {
  creating.value = false;
  newConceptName.value = '';
  newConceptPhrase.value = '';
  void concepts.list().catch((e: unknown) => {
    conceptNotice.value = messageOf(e);
    conceptNoticeError.value = true;
  });
}

async function addToConcept(concept: ConceptDto) {
  if (busy.value) {
    return;
  }
  conceptNotice.value = null;
  busy.value = true;
  try {
    const added = await concepts.addExamples(concept.id, [id.value]);
    conceptNotice.value = added > 0 ? `added to ${concept.name}` : `already in ${concept.name}`;
    conceptNoticeError.value = false;
  } catch (e) {
    conceptNotice.value = messageOf(e);
    conceptNoticeError.value = true;
  } finally {
    busy.value = false;
  }
}

async function createAndAdd() {
  const name = newConceptName.value.trim();
  if (!name || busy.value) {
    return;
  }
  conceptNotice.value = null;
  busy.value = true;
  const phrase = newConceptPhrase.value.trim();
  try {
    const created = await concepts.create(phrase ? { name, phrase } : { name });
    const added = await concepts.addExamples(created.id, [id.value]);
    conceptNotice.value = added > 0 ? `added to ${created.name}` : `already in ${created.name}`;
    conceptNoticeError.value = false;
  } catch (e) {
    conceptNotice.value = messageOf(e);
    conceptNoticeError.value = true;
  } finally {
    busy.value = false;
  }
}

function cancelCreate() {
  creating.value = false;
  newConceptName.value = '';
  newConceptPhrase.value = '';
}

/** The pool dropdown fetches lazily on before-show — never on page mount — with the
 *  entry's id so tiles carry their `member` flags; the result is kept locally. */
function reloadPools() {
  creatingPool.value = false;
  newPoolName.value = '';
  void pools
    .list({ entryId: id.value })
    .then((items) => {
      poolOptions.value = items;
    })
    .catch((e: unknown) => {
      poolNotice.value = messageOf(e);
      poolNoticeError.value = true;
    });
}

async function addToPool(p: PoolDto) {
  if (poolBusy.value) {
    return;
  }
  poolNotice.value = null;
  poolBusy.value = true;
  try {
    const added = await pools.addMembers(p.id, { entryIds: [id.value] });
    poolNotice.value = added > 0 ? `added to ${p.name}` : `already in ${p.name}`;
    poolNoticeError.value = false;
  } catch (e) {
    poolNotice.value = messageOf(e);
    poolNoticeError.value = true;
  } finally {
    poolBusy.value = false;
  }
}

async function createAndAddToPool() {
  const name = newPoolName.value.trim();
  if (!name || poolBusy.value) {
    return;
  }
  poolNotice.value = null;
  poolBusy.value = true;
  try {
    const created = await pools.create({ name });
    const added = await pools.addMembers(created.id, { entryIds: [id.value] });
    poolNotice.value = added > 0 ? `added to ${created.name}` : `already in ${created.name}`;
    poolNoticeError.value = false;
  } catch (e) {
    poolNotice.value = messageOf(e);
    poolNoticeError.value = true;
  } finally {
    poolBusy.value = false;
  }
}

/** "pool from this folder" (spec §3.3): seed a pool from the directory the entry lives
 *  in. The wire `path` is root-prefixed (`root + '/' + relPath`), so the directory-of-
 *  path has the root stripped back off — a directory equal to the root (entry at the
 *  root top level) yields '' per the wire contract. No `name` is sent: the server
 *  defaults it to the directory basename and auto-dedupes. The folder's direct entries
 *  are seeded server-side; the explicit addMembers keeps the current entry's membership
 *  certain regardless. */
async function addPoolFromFolder() {
  const e = entry.value;
  if (!e || poolBusy.value) {
    return;
  }
  poolNotice.value = null;
  poolBusy.value = true;
  try {
    const slash = e.path.lastIndexOf('/');
    const dir = slash < 0 ? '' : e.path.slice(0, slash);
    const dirPath = dir.startsWith(e.root + '/') ? dir.slice(e.root.length + 1) : '';
    const created = await pools.create({ rootName: e.root, dirPath });
    const added = await pools.addMembers(created.id, { entryIds: [id.value] });
    poolNotice.value = added > 0 ? `added to ${created.name}` : `already in ${created.name}`;
    poolNoticeError.value = false;
  } catch (err) {
    poolNotice.value = messageOf(err);
    poolNoticeError.value = true;
  } finally {
    poolBusy.value = false;
  }
}

function cancelPoolCreate() {
  creatingPool.value = false;
  newPoolName.value = '';
}

onMounted(() => {
  window.addEventListener('keydown', onKey);
  void load();
});
onBeforeUnmount(() => window.removeEventListener('keydown', onKey));
watch(() => route.params.id, () => void load());

function onKey(event: KeyboardEvent) {
  if (event.key === 'ArrowLeft' && prevId.value !== null) {
    go(prevId.value);
  } else if (event.key === 'ArrowRight' && nextId.value !== null) {
    go(nextId.value);
  }
}

function go(target: number | null) {
  if (target === null) {
    return;
  }
  const query: Record<string, string> = {};
  if (q.value) {
    query.q = q.value;
  }
  if (kind.value) {
    query.kind = kind.value;
  }
  if (pool.value) {
    query.pool = pool.value;
  }
  void router.push({ path: `/entries/${target}`, query });
}

function humanSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KiB', 'MiB', 'GiB', 'TiB'];
  let value = bytes;
  let unit = 'B';
  for (const next of units) {
    if (value < 1024) {
      break;
    }
    value /= 1024;
    unit = next;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${unit}`;
}
</script>

<style scoped>
.entries-layout {
  display: flex;
  min-height: calc(100vh - 64px);
}
.entries-sidebar {
  width: 230px;
  border-right: 1px solid #ddd;
  flex-shrink: 0;
}
.viewer {
  max-width: 100%;
  max-height: 78vh;
  display: block;
}
.fallback-card {
  max-width: 480px;
}
</style>
