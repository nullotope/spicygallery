<template>
  <q-dialog v-model="open">
    <q-card data-testid="autotag-dialog" class="autotag-dialog">
      <q-card-section class="row items-center q-pb-none q-gutter-x-sm">
        <div class="text-subtitle2">Autotag review</div>
        <q-space />
        <q-spinner v-if="loading" data-testid="autotag-loading" size="1.1em" />
      </q-card-section>
      <q-card-section class="text-caption text-grey-7 q-pb-none">
        {{ model ?? '…' }} · {{ calibration ?? '…' }}
      </q-card-section>

      <!-- threshold = the calibration arg of f<arg>@0.1 (spec §4.1) -->
      <q-card-section class="row items-center q-gutter-x-sm">
        <q-slider
          v-model="threshold"
          data-testid="autotag-threshold"
          class="col"
          :min="THRESHOLD_MIN"
          :max="THRESHOLD_MAX"
          :step="0.1"
          label
          label-always
        />
        <span class="text-caption text-grey-7 threshold-label">f{{ threshold.toFixed(1) }}@0.1</span>
      </q-card-section>

      <div v-if="error" class="text-caption text-negative q-px-md" data-testid="autotag-error">
        {{ error }}
      </div>

      <!-- predicted tags, server order (probability desc) preserved -->
      <q-card-section v-if="tags.length" class="scroll chip-list q-py-none">
        <div v-for="t in tags" :key="t.name" class="row items-center no-wrap q-py-xs">
          <q-checkbox
            :model-value="checked.has(t.name)"
            dense
            class="col"
            :data-testid="'autotag-chip-' + t.name"
            @update:model-value="(value: boolean) => toggle(t.name, value)"
          >
            <TagChip :name="t.name" :category="t.category" />
          </q-checkbox>
          <q-badge outline color="grey-7" class="q-ml-xs">{{ percent(t.probability) }}</q-badge>
        </div>
      </q-card-section>

      <q-card-section data-testid="autotag-accuracy" class="text-caption text-grey-7 q-py-none">
        {{ checkedCount }} / {{ tags.length }}
      </q-card-section>

      <q-card-actions align="right">
        <q-btn flat no-caps label="cancel" data-testid="autotag-cancel" @click="cancel" />
        <q-btn
          outline
          no-caps
          color="primary"
          label="confirm"
          data-testid="autotag-confirm"
          :disable="loading || applying"
          @click="confirm"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useTaggingStore } from 'src/stores/tagging';
import type { PredictedTag } from 'src/types/tagging';
import type { TagDto } from 'src/types/tags';
import TagChip from 'src/components/TagChip.vue';

const props = withDefaults(defineProps<{ entryId: number; modelValue?: boolean }>(), {
  modelValue: false,
});
const emit = defineEmits<{ 'update:modelValue': [boolean]; applied: [tags: TagDto[]] }>();

const THRESHOLD_MIN = 0.1;
const THRESHOLD_MAX = 5.0;
const REPREDICT_DEBOUNCE_MS = 300;

const tagging = useTaggingStore();

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});

const loading = ref(false);
const applying = ref(false);
const error = ref<string | null>(null);
const model = ref<string | null>(null);
const calibration = ref<string | null>(null);
const tags = ref<PredictedTag[]>([]);
const checked = ref(new Set<string>());
/** Chips the user unchecked this session — exclusion is authoritative across re-predicts (spec §4.1). */
const excluded = new Set<string>();
const threshold = ref(1.0);

defineExpose({ threshold });

/** Last request wins (review focus #5): stale responses never overwrite newer ones. */
let requestSeq = 0;
let debounceTimer: ReturnType<typeof setTimeout> | null = null;

/** The leading float of calibration strings like `f1.0@0.1`, clamped to slider range. */
function calibrationArg(value: string): number {
  const parsed = Number.parseFloat(value.replace(/^f/, ''));
  if (!Number.isFinite(parsed)) {
    return 1.0;
  }
  return Math.min(THRESHOLD_MAX, Math.max(THRESHOLD_MIN, Math.round(parsed * 10) / 10));
}

function clearDebounce() {
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer);
    debounceTimer = null;
  }
}

async function requestPredict(calibrationOverride: string | undefined) {
  const seq = ++requestSeq;
  clearDebounce();
  loading.value = true;
  error.value = null;
  try {
    const response = await tagging.predict(props.entryId, calibrationOverride);
    if (seq !== requestSeq || !props.modelValue) {
      return; // stale response or dialog closed in the meantime
    }
    model.value = response.model;
    calibration.value = response.calibration;
    tags.value = response.tags;
    checked.value = new Set(
      response.tags.map((t) => t.name).filter((name) => !excluded.has(name)),
    );
    if (calibrationOverride === undefined) {
      // seed the slider from the server's configured calibration
      threshold.value = calibrationArg(response.calibration);
    }
  } catch {
    if (seq !== requestSeq) {
      return;
    }
    error.value = 'prediction failed — is the tagging provider up?';
  } finally {
    if (seq === requestSeq) {
      loading.value = false;
    }
  }
}

watch(
  () => props.modelValue,
  (value) => {
    if (value) {
      tags.value = [];
      checked.value = new Set();
      excluded.clear();
      model.value = null;
      calibration.value = null;
      error.value = null;
      void requestPredict(undefined); // server default; response seeds the slider
    } else {
      clearDebounce();
    }
  },
  { immediate: true },
);

watch(threshold, (value) => {
  if (!props.modelValue) {
    return;
  }
  // seeding the slider from the response's own calibration is not a change
  if (calibration.value !== null && calibrationArg(calibration.value) === value) {
    return;
  }
  clearDebounce();
  debounceTimer = setTimeout(() => {
    void requestPredict('f' + value.toFixed(1) + '@0.1');
  }, REPREDICT_DEBOUNCE_MS);
});

onBeforeUnmount(clearDebounce);

const checkedCount = computed(() => tags.value.filter((t) => checked.value.has(t.name)).length);

function toggle(name: string, value: boolean) {
  if (value) {
    excluded.delete(name);
    checked.value.add(name);
  } else {
    excluded.add(name);
    checked.value.delete(name);
  }
}

function percent(probability: number): string {
  return Math.round(probability * 100) + '%';
}

async function confirm() {
  if (applying.value) {
    return;
  }
  applying.value = true;
  error.value = null;
  try {
    const names = tags.value.filter((t) => checked.value.has(t.name)).map((t) => t.name);
    const updated = await tagging.apply(props.entryId, names);
    emit('applied', updated);
    open.value = false;
  } catch {
    error.value = 'apply failed — tags not written';
  } finally {
    applying.value = false;
  }
}

function cancel() {
  clearDebounce();
  open.value = false;
}
</script>

<style scoped>
.autotag-dialog {
  min-width: 320px;
  max-width: 560px;
}
.chip-list {
  max-height: 45vh;
}
.threshold-label {
  white-space: nowrap;
}
</style>
