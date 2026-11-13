<template>
  <div class="q-pa-sm column q-gutter-y-xs" data-testid="tagging-controls">
    <div class="row q-gutter-x-xs">
      <q-btn
        data-testid="tag-results-btn"
        dense
        outline
        size="sm"
        no-caps
        label="Autotag results"
        :disable="!entries.q.trim()"
        @click="start({ q: entries.q })"
      >
        <q-tooltip>tag every entry matching the current search</q-tooltip>
      </q-btn>
      <q-btn
        data-testid="tag-untagged-btn"
        dense
        outline
        size="sm"
        no-caps
        label="Autotag untagged"
        @click="start({ untagged: true })"
      >
        <q-tooltip>tag every entry without tags</q-tooltip>
      </q-btn>
    </div>
    <div v-if="notice" data-testid="tag-notice" class="text-caption text-negative">
      {{ notice }}
    </div>

    <q-dialog v-model="show" seamless position="bottom">
      <q-card data-testid="tagging-dialog" class="tagging-dialog">
        <q-card-section class="row items-center q-py-sm q-pl-md q-pr-sm q-gutter-x-sm">
          <template v-if="running">
            <q-spinner size="1.1em" />
            <span class="text-subtitle2">Autotagging…</span>
          </template>
          <span v-else class="text-subtitle2">last autotag run</span>
          <q-space />
          <q-btn
            dense
            flat
            round
            size="sm"
            icon="close"
            data-testid="tagging-dialog-close"
            @click="show = false"
          >
            <q-tooltip>dismiss</q-tooltip>
          </q-btn>
        </q-card-section>
        <q-linear-progress v-if="running" data-testid="tagging-progress" indeterminate />
        <q-card-section v-if="run" class="text-caption q-pt-none" data-testid="tagging-run-report">
          <template v-if="running">
            {{ run.done }}/{{ run.total }} tagged · {{ run.errors }} errors<template v-if="current">
              · {{ current }}</template>
          </template>
          <template v-else>
            last run: {{ run.done }}/{{ run.total }} tagged, {{ run.errors }} errors<template
              v-if="run.error"
            >
              · {{ run.error }}</template>
          </template>
        </q-card-section>
      </q-card>
    </q-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useTaggingStore } from 'src/stores/tagging';
import { useEntriesStore } from 'src/stores/entries';

const store = useTaggingStore();
const entries = useEntriesStore();
const run = computed(() => store.run);
const running = computed(() => run.value?.running === true);
const show = ref(false);
const notice = ref<string | null>(null);
/** Current entry from the SSE progress events (RunDto omits it); only shown while running. */
const current = ref<string | null>(null);

// a run becoming active opens the monitor — including a run discovered by the
// mount-time reconcile (e.g. after a page reload mid-run)
watch(running, (active) => {
  if (active) {
    show.value = true;
    notice.value = null;
  } else {
    current.value = null;
  }
});

onMounted(() => {
  store.connect(); // SSE notifies…
  void store.reconcile(); // …REST reconciles (spec §8)
  // tap the shared source for the progress event's `current` field — one
  // EventSource per app session (store guard), no second connection here
  store.source?.addEventListener('progress', onProgress);
});

onBeforeUnmount(() => {
  // FakeEventSource (tests) ships no removeEventListener
  store.source?.removeEventListener?.('progress', onProgress);
});

function onProgress(event: Event) {
  const stats = JSON.parse((event as MessageEvent).data) as { current: string | null };
  current.value = stats.current;
}

async function start(scope: { q?: string; untagged?: boolean }) {
  if (running.value) {
    show.value = true; // re-open the dismissed monitor; never double-start
    return;
  }
  notice.value = null;
  try {
    await store.startBulk(scope);
  } catch (e) {
    // e.g. a run started in another tab — the server 409s; non-breaking notice
    const status = (e as { response?: { status?: number } } | null)?.response?.status;
    notice.value = status === 409 ? 'a bulk run is already active' : 'could not start autotagging';
  }
}
</script>

<style scoped>
.tagging-dialog {
  min-width: 320px;
  max-width: 560px;
}
</style>
