<template>
  <q-dialog v-model="open">
    <q-card data-testid="pool-creator" class="pool-creator-card">
      <q-card-section class="text-subtitle2 q-pb-none">new pool</q-card-section>
      <q-card-section class="q-gutter-y-sm">
        <q-input
          v-model="name"
          data-testid="pool-creator-name"
          dense
          outlined
          label="title"
          @keyup.enter="create"
        />
        <q-input
          v-model="description"
          data-testid="pool-creator-description"
          dense
          outlined
          label="description"
          @keyup.enter="create"
        />
        <q-input
          v-model="additionalTags"
          data-testid="pool-creator-tags"
          dense
          outlined
          label="additional tags (comma-separated)"
          @keyup.enter="create"
        />
        <div v-if="draft" class="text-caption text-grey-7">
          {{ draft.rootName + '/' + draft.dirPath }} · {{ draft.entryCount }} entries become members
        </div>
        <div v-if="titleError" data-testid="pool-creator-title-error" class="text-caption text-negative">
          {{ titleError }}
        </div>
        <div v-if="error" class="text-caption text-negative">{{ error }}</div>
      </q-card-section>
      <q-card-actions align="right">
        <q-btn flat no-caps label="cancel" data-testid="pool-creator-cancel" @click="cancel" />
        <q-btn
          outline
          no-caps
          color="primary"
          label="create"
          data-testid="pool-creator-create"
          :loading="store.saving"
          @click="create"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { usePoolsStore } from 'src/stores/pools';
import type { FolderSuggestion, PoolDto } from 'src/types/pools';

const props = withDefaults(defineProps<{ modelValue: boolean; draft?: FolderSuggestion | null }>(), {
  draft: null,
});
const emit = defineEmits<{ 'update:modelValue': [open: boolean]; created: [pool: PoolDto] }>();

const store = usePoolsStore();

const name = ref('');
const description = ref('');
const additionalTags = ref('');
const error = ref<string | null>(null);
/** Blank manual title: a visible validation caption instead of a silent no-op
 *  (the create request must not fire). Drafts stay allowed — the server
 *  defaults a blank folder body's name. */
const titleError = ref<string | null>(null);
/** The pool a previous attempt already created — a retry after a failed
 *  post-create setTags must NOT re-POST create (auto-dedupe would fork a second
 *  pool, a name clash would 409); it skips straight to the tags step. */
const createdPool = ref<PoolDto | null>(null);

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});

/** Every open pre-fills from the draft (spec §3.3): title = directory basename,
 *  description = the auto-discovery note; New opens empty. */
watch(
  () => props.modelValue,
  (opened) => {
    if (!opened) {
      return;
    }
    error.value = null;
    titleError.value = null;
    createdPool.value = null;
    name.value = props.draft ? dirBasename(props.draft) : '';
    description.value = props.draft
      ? 'Auto-discovered from ' + props.draft.rootName + '/' + props.draft.dirPath
      : '';
    additionalTags.value = '';
  },
);

/** The directory's own name ('' dirPath = the root's top level → the root's name). */
function dirBasename(d: FolderSuggestion): string {
  if (!d.dirPath) {
    return d.rootName;
  }
  const parts = d.dirPath.split('/').filter(Boolean);
  return parts[parts.length - 1] ?? d.rootName;
}

/** Comma-separated input → deduped bare tag names (the server normalizes + 409s bad input). */
const additionalNames = computed(() => {
  const names = additionalTags.value.split(',').map((t) => t.trim()).filter(Boolean);
  return [...new Set(names)];
});

/** Typing clears the stale caption again. */
watch(name, () => {
  titleError.value = null;
});

async function create() {
  // manual create needs a title (the server 400s blank names) — say so instead
  // of silently no-opping; a draft may keep the field empty — omitting `name`
  // lets the server default + auto-dedupe it
  if (!props.draft && !name.value.trim()) {
    titleError.value = 'a title is required';
    return;
  }
  error.value = null;
  const trimmedName = name.value.trim();
  const trimmedDescription = description.value.trim();
  const body = props.draft
    ? {
        ...(trimmedName ? { name: trimmedName } : {}),
        ...(trimmedDescription ? { description: trimmedDescription } : {}),
        rootName: props.draft.rootName,
        dirPath: props.draft.dirPath,
      }
    : {
        name: trimmedName,
        ...(trimmedDescription ? { description: trimmedDescription } : {}),
      };
  try {
    // a remembered create means this is a retry — the pool exists, only the
    // tags step is re-attempted (never a second POST /api/pools)
    const pool = createdPool.value ?? (await store.create(body));
    createdPool.value = pool;
    if (additionalNames.value.length > 0) {
      await store.setTags(pool.id, additionalNames.value);
    }
    emit('created', pool);
    open.value = false;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

function cancel() {
  open.value = false;
}
</script>

<style scoped>
.pool-creator-card {
  min-width: 360px;
}
</style>
