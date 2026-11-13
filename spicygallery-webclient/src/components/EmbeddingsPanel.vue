<template>
  <div class="embeddings-panel q-mt-lg" data-testid="embeddings">
    <div class="text-subtitle2">
      <q-icon name="image_search" class="q-mr-xs" />Embeddings
    </div>

    <div v-if="status" class="text-caption q-mt-sm" data-testid="embedding-status">
      <template v-if="!status.enabled">
        <span class="text-negative">disabled</span> — embeddings are turned off in the server config
      </template>
      <template v-else-if="status.breakerOpen">
        <span class="text-warning">unreachable</span> — provider not responding, will retry automatically
      </template>
      <template v-else>
        <span class="text-positive">reachable</span>
      </template>
      · model <code>{{ status.model }}</code>
    </div>

    <div v-if="status" class="text-caption text-grey-8">
      {{ status.withEmbeddings }} embedded · {{ status.missing }} missing
    </div>

    <div class="q-mt-sm row q-gutter-sm">
      <q-btn
        data-testid="backfill-btn"
        no-caps
        color="primary"
        outline
        label="Backfill embeddings"
        :disable="!canStart"
        @click="startBackfill(false)"
      />
      <q-btn
        data-testid="reembed-btn"
        no-caps
        flat
        label="Re-embed all (model changed)"
        :disable="!canStart"
        @click="startBackfill(true)"
      />
    </div>

    <div v-if="running" class="q-mt-sm" data-testid="backfill-progress">
      <q-linear-progress
        :value="progressFraction"
        color="primary"
        size="8px"
        rounded
      />
      <div class="text-caption text-grey-8 q-mt-xs" data-testid="backfill-counts">
        <template v-if="progress">embedding {{ progress.done }} / {{ progress.total }}</template>
        <template v-else>starting…</template>
        <template v-if="progress?.current"> · {{ progress.current }}</template>
      </div>
    </div>

    <div v-if="errorMessage" class="text-caption text-negative q-mt-sm" data-testid="backfill-error">
      {{ errorMessage }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { api } from 'src/boot/api';
import type { BackfillStatus, EmbeddingStatus } from 'src/types/embedding';

const status = ref<EmbeddingStatus | null>(null);
const running = ref(false);
const progress = ref<{ done: number; total: number; current: string | null } | null>(null);
const errorMessage = ref<string | null>(null);
let source: EventSource | null = null;

const canStart = computed(() => status.value?.enabled === true && !running.value);
const progressFraction = computed(() => {
  const p = progress.value;
  return p && p.total > 0 ? Math.min(1, p.done / p.total) : 0;
});

async function reconcileStatus() {
  try {
    const { data } = await api.get<EmbeddingStatus>('/api/embeddings/status');
    status.value = data;
  } catch {
    // keep the last known status; the panel simply shows nothing new
  }
}

async function reconcileBackfill() {
  try {
    const { data } = await api.get<BackfillStatus>('/api/embeddings/backfill');
    running.value = data.running;
    if (data.running) {
      progress.value = { done: data.done, total: data.total, current: null };
    }
    if (data.error) {
      errorMessage.value = data.error;
    }
  } catch {
    // ignore — status is best-effort
  }
}

function connect() {
  if (source) {
    return;
  }
  const es = new EventSource('/api/embeddings/backfill/events');
  source = es;
  es.addEventListener('progress', (event) => {
    running.value = true;
    progress.value = JSON.parse((event as MessageEvent).data) as {
      done: number;
      total: number;
      current: string | null;
    };
  });
  es.addEventListener('done', () => {
    running.value = false;
    progress.value = null;
    errorMessage.value = null;
    void reconcileStatus();
    void reconcileBackfill();
  });
  es.addEventListener('failed', (event) => {
    running.value = false;
    const data = JSON.parse((event as MessageEvent).data) as { message?: string };
    errorMessage.value = data.message ?? 'backfill failed';
    void reconcileStatus();
  });
}

async function startBackfill(force: boolean) {
  errorMessage.value = null;
  try {
    await api.post('/api/embeddings/backfill', null, force ? { params: { force: true } } : undefined);
    running.value = true;
    progress.value = null;
  } catch (e) {
    const status = (e as { response?: { status?: number; data?: { detail?: string } } }).response;
    if (status?.status === 409) {
      errorMessage.value = status.data?.detail ?? 'backfill already running';
      running.value = true;
      return;
    }
    errorMessage.value = status?.data?.detail ?? 'could not start backfill';
  }
}

onMounted(() => {
  void reconcileStatus();
  void reconcileBackfill();
  connect();
});
onBeforeUnmount(() => {
  source?.close();
  source = null;
});
</script>
