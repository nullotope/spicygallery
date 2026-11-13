<template>
  <div data-testid="tag-editor">
    <!-- working set, grouped by category -->
    <template v-for="cat in categories" :key="cat">
      <div class="text-caption text-grey-7 q-mt-sm">{{ cat }}</div>
      <div v-for="t in byCategory[cat]" :key="t.name" class="row items-center no-wrap">
        <TagChip :name="t.name" :category="t.category" />
        <q-btn
          dense
          flat
          round
          size="xs"
          icon="close"
          :data-testid="'tag-remove-' + t.name"
          :aria-label="'remove ' + t.name"
          @click="remove(t.name)"
        />
      </div>
    </template>

    <!-- add: autocomplete -->
    <q-input
      v-model="query"
      data-testid="tag-editor-input"
      dense
      outlined
      :debounce="debounceMs"
      placeholder="add tag…"
      @update:model-value="suggest"
      @keydown.enter.prevent="onEnter"
    />
    <div v-if="suggestions.length || createVisible" class="suggestions">
      <div
        v-for="s in suggestions"
        :key="s.name"
        class="row items-center suggestion cursor-pointer"
        :data-testid="'suggestion-' + s.name"
        @click="pick(s)"
      >
        <TagChip :name="s.aliasOf ?? s.name" :category="s.category" />
        <span class="text-caption text-grey-7 q-ml-xs">({{ s.postCount }})</span>
        <span v-if="s.aliasOf" class="text-caption text-grey-7 q-ml-xs">alias of {{ s.name }}</span>
      </div>
      <div
        v-if="createVisible"
        class="row items-center suggestion cursor-pointer"
        data-testid="create-tag"
        @click="addNew"
      >
        <span class="text-caption">create '{{ normalizedQuery }}'</span>
        <q-select
          v-model="newCategory"
          :options="USER_TAG_CATEGORIES"
          dense
          borderless
          data-testid="create-category"
          class="q-ml-sm"
        />
      </div>
    </div>

    <div class="row q-gutter-sm q-mt-sm">
      <q-btn dense outline no-caps data-testid="tag-editor-save" label="save" @click="save" />
      <q-btn dense flat no-caps data-testid="tag-editor-cancel" label="cancel" @click="cancel" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { api } from 'src/boot/api';
import { TAG_CATEGORIES, USER_TAG_CATEGORIES, normalizeTagName, type AutocompleteDto, type TagCategory, type TagDto } from 'src/types/tags';
import TagChip from 'src/components/TagChip.vue';

const props = withDefaults(defineProps<{ entryId: number; tags: TagDto[]; debounceMs?: number }>(), {
  debounceMs: 200,
});
const emit = defineEmits<{ saved: [tags: TagDto[]]; cancel: [] }>();

interface WorkingTag {
  name: string;
  category: TagCategory;
}

const working = ref<WorkingTag[]>(props.tags.map((t) => ({ name: t.name, category: t.category })));
const query = ref('');
const suggestions = ref<AutocompleteDto[]>([]);
const newCategory = ref<TagCategory>('general');

defineExpose({ working, newCategory });

const byCategory = computed<Record<TagCategory, WorkingTag[]>>(() => {
  const grouped = { pool_type: [], general: [], artist: [], character: [], copyright: [], meta: [] } as Record<
    TagCategory,
    WorkingTag[]
  >;
  for (const t of working.value) {
    grouped[t.category].push(t);
  }
  return grouped;
});
const categories = computed(() => TAG_CATEGORIES.filter((c) => byCategory.value[c].length > 0));

const normalizedQuery = computed(() => normalizeTagName(query.value));
const createVisible = computed(
  () => normalizedQuery.value.length > 0 && !working.value.some((t) => t.name === normalizedQuery.value),
);

async function suggest() {
  if (!query.value.trim()) {
    suggestions.value = [];
    return;
  }
  const { data } = await api.get<AutocompleteDto[]>('/api/tags/autocomplete', {
    params: { q: query.value.trim() },
  });
  suggestions.value = data;
}

function contains(name: string): boolean {
  return working.value.some((t) => t.name === name);
}

function add(name: string, category: TagCategory) {
  if (!contains(name)) {
    working.value.push({ name, category });
  }
  query.value = '';
  suggestions.value = [];
}

function pick(s: AutocompleteDto) {
  add(s.aliasOf ?? s.name, s.category);
}

function addNew() {
  add(normalizedQuery.value, newCategory.value);
}

function onEnter() {
  // same yardstick as the filter/create-row: match on the NORMALIZED query so
  // 'blue dragon' picks suggestion 'blue_dragon' (PoolPage parity, f664748)
  const exact = suggestions.value.find((s) => s.name === normalizedQuery.value);
  if (exact) {
    pick(exact);
  } else if (createVisible.value) {
    addNew();
  }
}

function remove(name: string) {
  working.value = working.value.filter((t) => t.name !== name);
}

async function save() {
  const { data } = await api.put<TagDto[]>(`/api/entries/${props.entryId}/tags`, {
    tags: working.value.map((t) => ({ name: t.name, category: t.category })),
  });
  emit('saved', data);
}

function cancel() {
  working.value = props.tags.map((t) => ({ name: t.name, category: t.category }));
  query.value = '';
  suggestions.value = [];
  emit('cancel');
}
</script>

<style scoped>
.suggestion {
  padding: 2px 4px;
}
</style>
