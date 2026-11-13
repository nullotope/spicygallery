<template>
  <q-dialog v-model="open">
    <q-card data-testid="pool-add-modal" class="pool-add-card">
      <q-card-section class="text-subtitle2 q-pb-none">add to pool</q-card-section>
      <q-card-section class="q-gutter-y-sm">
        <!-- mode toggle: entries search vs sub-pool pick -->
        <div class="row q-gutter-sm">
          <q-btn
            dense
            no-caps
            label="entries"
            data-testid="pool-add-mode-entries"
            :outline="mode === 'entries'"
            :color="mode === 'entries' ? 'primary' : 'grey-7'"
            @click="mode = 'entries'"
          />
          <q-btn
            dense
            no-caps
            label="pools"
            data-testid="pool-add-mode-pools"
            :outline="mode === 'pools'"
            :color="mode === 'pools' ? 'primary' : 'grey-7'"
            @click="mode = 'pools'"
          />
        </div>

        <!-- Entries mode: the grid's search API (GET /api/entries?q=), page 1 -->
        <template v-if="mode === 'entries'">
          <q-input
            v-model="query"
            data-testid="pool-add-entry-search"
            dense
            outlined
            :debounce="200"
            placeholder="search entries…"
            @update:model-value="searchEntries"
          />
          <div
            v-for="it in results"
            :key="it.id"
            class="row items-center no-wrap cursor-pointer result"
            :class="{ selected: selectedEntryIds.includes(it.id) }"
            :data-testid="'pool-add-result-' + it.id"
            @click="toggleEntry(it.id)"
          >
            <img class="result-thumb" :src="it.thumbUrl" loading="lazy" alt="" />
            <span class="text-caption q-ml-sm">{{ it.fileName }}</span>
            <q-icon v-if="selectedEntryIds.includes(it.id)" name="check" color="primary" class="q-ml-auto" />
          </div>
          <div v-if="searched && results.length === 0" class="text-caption text-grey-7">
            no entries match
          </div>
        </template>

        <!-- Pools mode: every pool EXCEPT this one and its descendants -->
        <template v-else>
          <div v-if="poolsLoading" class="row justify-center q-my-md">
            <q-spinner-dots color="primary" size="28px" />
          </div>
          <div
            v-for="p in addablePools"
            :key="p.id"
            class="row items-center no-wrap cursor-pointer result"
            :class="{ selected: selectedPoolIds.includes(p.id) }"
            :data-testid="'pool-add-pool-' + p.id"
            @click="togglePool(p.id)"
          >
            <q-icon name="photo_library" size="16px" class="q-mr-sm" />
            <span class="text-caption">{{ p.name }}</span>
            <span class="text-caption text-grey-7 q-ml-xs">{{ p.entryCount }} entries</span>
            <q-icon v-if="selectedPoolIds.includes(p.id)" name="check" color="primary" class="q-ml-auto" />
          </div>
        </template>

        <div v-if="error" data-testid="pool-add-error" class="text-caption text-negative">{{ error }}</div>
      </q-card-section>
      <q-card-actions align="right">
        <q-btn flat no-caps label="cancel" data-testid="pool-add-cancel" @click="close" />
        <q-btn
          outline
          no-caps
          color="primary"
          label="add"
          data-testid="pool-add-confirm"
          :disable="!hasSelection || saving"
          @click="add"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { api } from 'src/boot/api';
import { toError, usePoolsStore } from 'src/stores/pools';
import type { GridItem } from 'src/stores/entries';
import type { PoolDetail, PoolDto } from 'src/types/pools';

const props = defineProps<{ modelValue: boolean; poolId: number }>();
const emit = defineEmits<{ 'update:modelValue': [open: boolean]; added: [] }>();

const store = usePoolsStore();

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});

const mode = ref<'entries' | 'pools'>('entries');
const query = ref('');
const results = ref<GridItem[]>([]);
const searched = ref(false);
const selectedEntryIds = ref<number[]>([]);
const selectedPoolIds = ref<number[]>([]);
const poolsLoading = ref(false);
const addablePools = ref<PoolDto[]>([]);
const error = ref<string | null>(null);
/** The members POST is in flight — the Add button must not double-fire. */
const saving = ref(false);

const hasSelection = computed(() => selectedEntryIds.value.length > 0 || selectedPoolIds.value.length > 0);

/** Every open starts clean (fresh searches, fresh selections, fresh server truth). */
watch(
  () => props.modelValue,
  (opened) => {
    if (!opened) {
      return;
    }
    mode.value = 'entries';
    query.value = '';
    results.value = [];
    searched.value = false;
    selectedEntryIds.value = [];
    selectedPoolIds.value = [];
    addablePools.value = [];
    error.value = null;
  },
);

/** The pools list loads lazily on the first Pools activation of each open —
 *  users adding only entries never pay for the descendant walk. */
watch(mode, (m) => {
  if (m === 'pools') {
    void loadPools();
  }
});

/**
 * The current pool's descendants, CLIENT-SIDE (controller ruling): BFS over the
 * child-pool edges — walk each pool's detail members, collect every sub-pool id,
 * recurse into those pools' details. The `seen` guard makes it cycle-safe even
 * though the server forbids membership cycles (spec §3.3). Pools are small, so
 * one GET per subtree node is fine.
 *
 * Deliberately raw api.get, NOT store.get: the store action overwrites `current`,
 * which is exactly what the pool page BEHIND this modal renders — the page would
 * blank out while the walk runs.
 */
async function descendantIdsOf(rootId: number): Promise<Set<number>> {
  const seen = new Set<number>([rootId]);
  const queue: number[] = [rootId];
  while (queue.length > 0) {
    const id = queue.shift()!;
    const { data } = await api.get<PoolDetail>(`/api/pools/${id}`, undefined);
    for (const m of data.members) {
      if (m.pool && !seen.has(m.pool.id)) {
        seen.add(m.pool.id);
        queue.push(m.pool.id);
      }
    }
  }
  return seen;
}

/** GET /api/pools filtered by the BFS result: self + every descendant hidden. */
async function loadPools() {
  error.value = null;
  poolsLoading.value = true;
  try {
    const hidden = await descendantIdsOf(props.poolId);
    const all = await store.list();
    addablePools.value = all.filter((p) => !hidden.has(p.id));
  } catch (e) {
    // raw api.get rejections carry no unwrap — route them through the store's
    // RFC-7807 helper so problem-detail text surfaces
    error.value = toError(e).message;
  } finally {
    poolsLoading.value = false;
  }
}

/** The entries grid search API shape (EntriesPage's store: GET /api/entries with a
 *  `q` param, page 1). Called directly — the entries store is the grid page's
 *  singleton state and must not be clobbered by a modal search. */
async function searchEntries() {
  const q = query.value.trim();
  if (!q) {
    results.value = [];
    searched.value = false;
    return;
  }
  try {
    const { data } = await api.get<{ items: GridItem[]; nextCursor: string | null }>('/api/entries', {
      params: { q },
    });
    results.value = data.items;
    searched.value = true;
  } catch (e) {
    // same surface as the BFS failure; the catch keeps the async template
    // handler from ever rejecting unhandled
    error.value = toError(e).message;
  }
}

/** Click-order multi-select: push appends, clicking a selected row removes it —
 *  the Add post preserves the selection order as-is. */
function toggleEntry(id: number) {
  selectedEntryIds.value = selectedEntryIds.value.includes(id)
    ? selectedEntryIds.value.filter((x) => x !== id)
    : [...selectedEntryIds.value, id];
}

function togglePool(id: number) {
  selectedPoolIds.value = selectedPoolIds.value.includes(id)
    ? selectedPoolIds.value.filter((x) => x !== id)
    : [...selectedPoolIds.value, id];
}

/** POST /api/pools/{id}/members with both id lists in selection order (empty lists
 *  omitted); the page refetches on `added`. */
async function add() {
  if (!hasSelection.value) {
    return;
  }
  error.value = null;
  const payload: { entryIds?: number[]; poolIds?: number[] } = {};
  if (selectedEntryIds.value.length > 0) {
    payload.entryIds = [...selectedEntryIds.value];
  }
  if (selectedPoolIds.value.length > 0) {
    payload.poolIds = [...selectedPoolIds.value];
  }
  try {
    saving.value = true;
    await store.addMembers(props.poolId, payload);
    emit('added');
    open.value = false;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    saving.value = false;
  }
}

function close() {
  open.value = false;
}
</script>

<style scoped>
.pool-add-card {
  min-width: 420px;
}
.result {
  padding: 2px 4px;
}
.result.selected {
  background: rgba(0, 0, 0, 0.06);
}
.body--dark .result.selected {
  background: rgba(255, 255, 255, 0.08);
}
.result-thumb {
  width: 48px;
  height: 36px;
  object-fit: cover;
  border-radius: 3px;
  background: #ddd;
}
.body--dark .result-thumb {
  background: #444;
}
</style>
