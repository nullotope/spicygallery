<template>
  <div class="tagging-panel q-mt-lg" data-testid="tagging">
    <div class="text-subtitle2">
      <q-icon name="sell" class="q-mr-xs" />Autotagging
    </div>

    <div v-if="info" class="text-caption q-mt-sm" data-testid="tagging-status">
      <template v-if="!info.enabled">
        <span class="text-negative">disabled</span> — autotagging is turned off in the server config
      </template>
      <template v-else-if="!info.reachable">
        <span class="text-warning">unreachable</span> — model provider not responding, will retry automatically
      </template>
      <template v-else>
        <span class="text-positive">reachable</span>
      </template>
      · model <code>{{ info.model }}</code>
    </div>

    <div v-if="info" class="text-caption text-grey-8" data-testid="tagging-config">
      labels: {{ info.labels ?? 'unknown' }} · calibration <code>{{ info.calibration }}</code> ·
      implications: {{ info.implications }}
    </div>

    <div class="q-mt-sm row q-gutter-sm">
      <q-btn
        data-testid="autotag-btn"
        no-caps
        color="primary"
        outline
        label="Autotag untagged"
        :disable="!canStart"
        @click="start"
      >
        <q-tooltip>tag every entry without tags</q-tooltip>
      </q-btn>
    </div>

    <div v-if="notice" class="text-caption text-negative q-mt-sm" data-testid="tagging-error">
      {{ notice }}
    </div>

    <div v-if="run" class="text-caption text-grey-8 q-mt-sm" data-testid="tagging-run">
      <template v-if="run.running">tagging {{ run.done }} / {{ run.total }} · {{ run.errors }} errors</template>
      <template v-else>
        last run: {{ run.done }}/{{ run.total }} tagged, {{ run.errors }} errors<template v-if="run.error">
          · {{ run.error }}</template>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useTaggingStore } from 'src/stores/tagging';

const store = useTaggingStore();
const notice = ref<string | null>(null);

const info = computed(() => store.info);
const run = computed(() => store.run);
const canStart = computed(() => info.value?.enabled === true && run.value?.running !== true);

async function start() {
  notice.value = null;
  try {
    await store.startBulk({ untagged: true });
  } catch (e) {
    // e.g. a run started in another tab — the server 409s; non-breaking notice
    const status = (e as { response?: { status?: number } } | null)?.response?.status;
    notice.value = status === 409 ? 'a bulk run is already active' : 'could not start autotagging';
  }
}

onMounted(() => {
  store.startPolling(); // REST reconciles (spec §8) — fast while a run is active
  void store.refreshInfo(); // the store never fetches info on its own
});
onBeforeUnmount(() => store.stopPolling());
</script>
