<template>
  <div class="q-pa-md concepts-page" data-testid="concepts-page">
    <div class="text-h6 q-mb-sm">Concepts</div>

    <div class="row items-center q-gutter-sm q-mb-md">
      <q-input
        v-model="newName"
        data-testid="concept-create-name"
        dense
        outlined
        placeholder="name"
        class="concept-name-input"
        @keyup.enter="create"
      />
      <q-input
        v-model="newPhrase"
        data-testid="concept-create-phrase"
        dense
        outlined
        placeholder="anchor phrase (optional)"
        class="concept-phrase-input"
        @keyup.enter="create"
      />
      <q-btn
        data-testid="concept-create-create"
        outline
        no-caps
        dense
        label="create"
        :loading="store.saving"
        @click="create"
      />
    </div>

    <div v-if="createError" data-testid="concept-create-error" class="text-caption text-negative q-mb-md">
      {{ createError }}
    </div>
    <div v-if="listError" data-testid="concepts-error" class="text-caption text-negative q-mb-md">
      {{ listError }}
    </div>

    <div class="concepts-list">
      <div v-for="c in store.items" :key="c.id" class="row items-center no-wrap concept-row">
        <RouterLink
          class="row items-center concept-link"
          :data-testid="'concept-link-' + c.name"
          :to="'/concepts/' + c.id"
        >
          <span>{{ c.name }}</span>
          <span class="text-caption text-grey-7 q-ml-sm">{{ c.taggedCount }}/{{ c.exampleCount }}</span>
          <span
            v-if="c.anchorStale"
            class="text-caption text-warning q-ml-sm"
            :data-testid="'concept-stale-' + c.name"
          >stale</span>
          <span class="text-caption text-grey-7 q-ml-sm">updated {{ c.updatedAt.slice(0, 10) }}</span>
        </RouterLink>
        <q-btn
          dense
          flat
          no-caps
          size="sm"
          :label="armed === c.id ? 'confirm?' : 'delete'"
          :color="armed === c.id ? 'negative' : 'grey-7'"
          :data-testid="'concept-delete-' + c.id"
          :aria-label="armed === c.id ? 'confirm delete ' + c.name : 'delete ' + c.name"
          @click="remove(c)"
        />
      </div>
      <div v-if="!store.loading && store.items.length === 0 && !listError" class="text-caption text-grey-7 q-pa-sm">
        no concepts yet — create one above
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import axios from 'axios';
import { useConceptsStore } from 'src/stores/concepts';
import type { ConceptDto } from 'src/types/concepts';

const store = useConceptsStore();

const newName = ref('');
const newPhrase = ref('');
const createError = ref<string | null>(null);
const listError = ref<string | null>(null);
/** Delete is a two-click confirm: the first click arms the row's button. */
const armed = ref<number | null>(null);

onMounted(() => {
  void reload();
});

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

async function reload() {
  listError.value = null;
  try {
    await store.list();
  } catch (e) {
    listError.value = messageOf(e);
  }
}

async function create() {
  const name = newName.value.trim();
  if (!name) {
    return;
  }
  createError.value = null;
  const phrase = newPhrase.value.trim();
  try {
    await store.create(phrase ? { name, phrase } : { name });
    newName.value = '';
    newPhrase.value = '';
    await reload();
  } catch (e) {
    // duplicate name (409), provider down (503) — the server detail explains
    createError.value = messageOf(e);
  }
}

async function remove(c: ConceptDto) {
  if (armed.value !== c.id) {
    armed.value = c.id;
    return;
  }
  armed.value = null;
  try {
    await store.remove(c.id);
    await reload();
  } catch (e) {
    listError.value = messageOf(e);
  }
}
</script>

<style scoped>
.concept-name-input {
  min-width: 220px;
}
.concept-phrase-input {
  min-width: 260px;
}
.concept-row {
  padding: 2px 0;
}
</style>
