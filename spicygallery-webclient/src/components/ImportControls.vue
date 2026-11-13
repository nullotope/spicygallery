<template>
  <span>
    <q-btn
      data-testid="import-btn"
      dense
      flat
      round
      :aria-label="tooltip"
      @click="onClick"
    >
      <q-icon v-if="!running" name="refresh" />
      <q-spinner v-else size="1.2em" />
      <q-badge
        v-if="failed"
        data-testid="import-failed-badge"
        color="negative"
        rounded
        floating
      />
      <q-tooltip>{{ tooltip }}</q-tooltip>
    </q-btn>

    <!-- start modal: pick follow-up operations, then launch -->
    <q-dialog v-model="modal">
      <q-card data-testid="import-modal" class="import-modal">
        <q-card-section class="q-pb-none">
          <div class="text-subtitle1">Import / update index</div>
          <div class="text-caption text-grey-7">
            scans the libraries and imports new or changed files
          </div>
        </q-card-section>
        <q-card-section class="q-gutter-y-xs">
          <q-checkbox
            v-model="followUp"
            dense
            data-testid="import-followup"
            label="Auto-tag newly imported entries"
          />
          <q-checkbox
            v-model="poolFollowUp"
            dense
            data-testid="import-pool-followup"
            label="Create a pool of newly imported entries"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat no-caps label="cancel" data-testid="import-modal-cancel" v-close-popup />
          <q-btn
            outline
            no-caps
            color="primary"
            label="start import"
            data-testid="import-start"
            :disable="running"
            @click="onStart"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <q-dialog v-model="show" seamless position="bottom">
      <q-card data-testid="import-dialog" class="import-dialog">
        <q-card-section class="row items-center q-py-sm q-pl-md q-pr-sm q-gutter-x-sm">
          <template v-if="running">
            <q-spinner size="1.1em" />
            <span class="text-subtitle2">Import running…</span>
          </template>
          <span v-else class="text-subtitle2">last import</span>
          <q-space />
          <q-btn
            dense
            flat
            round
            size="sm"
            icon="close"
            data-testid="import-dialog-close"
            @click="show = false"
          >
            <q-tooltip>dismiss</q-tooltip>
          </q-btn>
        </q-card-section>
        <q-linear-progress
          v-if="running"
          data-testid="import-progress"
          :indeterminate="run?.processed === undefined"
          :value="progressFraction"
        />
        <q-card-section
          v-if="running && run?.processed !== undefined"
          class="text-caption q-py-none"
          data-testid="import-progress-line"
        >
          processing {{ run.processed.toLocaleString() }} / {{ run.scanned.toLocaleString() }}
          ({{ progressPercent }}%)<template v-if="run.current"> · {{ run.current }}</template>
        </q-card-section>
        <q-card-section v-if="run" class="text-caption q-pt-none" data-testid="run-report">
          <template v-if="running">
            {{ run.scanned }} scanned · {{ run.added }} added · {{ run.updated }} updated ·
            {{ run.missingCount }} missing · {{ run.errorCount }} errors
          </template>
          <template v-else>
            last run: {{ run.status }} — {{ run.scanned }} scanned, {{ run.added }} added,
            {{ run.updated }} updated, {{ run.missingCount }} missing, {{ run.errorCount }} errors
            <template v-if="run.message"> · {{ run.message }}</template>
            <template v-if="run.poolId">
              ·
              <RouterLink :to="'/pools/' + run.poolId" data-testid="import-pool-link">
                view pool
              </RouterLink>
            </template>
            <template v-else-if="poolFollowUpPending">
              · <span data-testid="import-pool-none">nothing new — no pool created</span>
            </template>
          </template>
        </q-card-section>
        <q-card-section v-if="followUpNotice" class="text-caption text-negative q-pt-none">
          {{ followUpNotice }}
        </q-card-section>
      </q-card>
    </q-dialog>
  </span>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { useImportStore } from 'src/stores/importStore';
import { useTaggingStore } from 'src/stores/tagging';

const store = useImportStore();
const tagging = useTaggingStore();
const run = computed(() => store.run);
const running = computed(() => run.value?.status === 'running');
// determinate bar only while the SSE payload carries `processed` (REST reconcile omits it —
// the next tick ≤1s later restores it)
const progressFraction = computed(() => {
  const r = run.value;
  if (!r || r.processed === undefined || r.scanned <= 0) return 0;
  return Math.min(1, r.processed / r.scanned);
});
const progressPercent = computed(() => Math.round(progressFraction.value * 100));
const failed = computed(() => !running.value && run.value?.status === 'failed');
const show = ref(false);
const modal = ref(false);
const followUp = ref(true);
const followUpPending = ref(false);
const poolFollowUp = ref(false);
/** whether the last started import ran with the pool follow-up checked —
 *  survives completion so the report can explain a missing pool draft */
const poolFollowUpPending = ref(false);
const followUpNotice = ref<string | null>(null);

const tooltip = computed(() =>
  failed.value ? `last import failed — ${run.value?.errorCount ?? 0} errors` : 'Import / update index',
);

// a run becoming active opens the monitor — including a run discovered by the
// mount-time reconcile (e.g. after a page reload mid-import)
watch(running, (active, was) => {
  if (active) {
    show.value = true;
    return;
  }
  // run finished: fire the checked follow-up exactly once, only on success
  if (was && run.value?.status === 'completed' && followUpPending.value) {
    followUpPending.value = false;
    startFollowUp();
  }
  if (!active && run.value?.status !== 'running') {
    followUpPending.value = false;
  }
});

onMounted(() => {
  store.connect(); // SSE notifies…
  void store.reconcile(); // …REST reconciles (spec §8)
});

function onClick() {
  if (running.value) {
    show.value = true; // re-open the dismissed monitor; never double-start
    return;
  }
  modal.value = true;
}

function onStart() {
  followUpPending.value = followUp.value;
  poolFollowUpPending.value = poolFollowUp.value;
  followUpNotice.value = null;
  modal.value = false;
  void store.startImport(poolFollowUp.value);
}

async function startFollowUp() {
  try {
    await tagging.startBulk({ untagged: true });
  } catch (ex) {
    followUpNotice.value =
      ex instanceof Error && /409|conflict/i.test(ex.message)
        ? 'auto-tagging skipped — another bulk run is active'
        : 'auto-tagging of newly imported entries failed';
    show.value = true; // surface the notice on the monitor
  }
}
</script>

<style scoped>
.import-dialog {
  min-width: 320px;
  max-width: 560px;
}
.import-modal {
  min-width: 320px;
  max-width: 480px;
}
</style>
