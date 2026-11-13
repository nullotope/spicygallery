<template>
  <div class="q-pa-md tags-page">
    <div class="text-h6 q-mb-sm">Tags</div>

    <div class="row items-center q-gutter-sm q-mb-md">
      <q-input
        v-model="q"
        data-testid="tags-name-filter"
        dense
        outlined
        debounce="300"
        placeholder="filter by name"
        class="tags-filter"
        @update:model-value="reload"
      >
        <template #prepend>
          <q-icon name="search" />
        </template>
      </q-input>

      <q-btn
        v-for="cat in [''].concat(TAG_CATEGORIES)"
        :key="cat"
        data-testid="tags-category-chip"
        size="sm"
        no-caps
        dense
        :outline="category !== cat"
        :color="category === cat ? 'primary' : 'grey-7'"
        :label="cat === '' ? 'all' : cat"
        @click="setCategory(cat)"
      />

      <q-select
        v-model="sort"
        :options="SORTS"
        dense
        outlined
        data-testid="tags-sort"
        label="sort"
        style="min-width: 120px"
        @update:model-value="reload"
      />
    </div>

    <div class="tags-list">
      <RouterLink
        v-for="t in items"
        :key="t.id"
        class="row items-center tag-row"
        :data-testid="'tag-link-' + t.name"
        :to="{ path: '/', query: { q: t.name } }"
      >
        <TagChip :name="t.name" :category="t.category" />
        <span class="text-caption text-grey-7 q-ml-sm">({{ t.postCount }})</span>
      </RouterLink>
      <div v-if="!loading && items.length === 0" class="text-caption text-grey-7 q-pa-sm">
        no tags{{ q ? ' matching “' + q + '”' : ' yet — tag something from its detail page' }}
      </div>
    </div>

    <div v-if="nextCursor !== null" class="q-mt-md">
      <q-btn outline no-caps data-testid="tags-load-more" label="load more" :loading="loading" @click="loadMore" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { api } from 'src/boot/api';
import { TAG_CATEGORIES, type TagCategory, type TagDto } from 'src/types/tags';
import TagChip from 'src/components/TagChip.vue';

const SORTS = ['name', 'count', 'new'];

const q = ref('');
const category = ref<TagCategory | ''>('');
const sort = ref<'name' | 'count' | 'new'>('name');
const items = ref<TagDto[]>([]);
const nextCursor = ref<string | null>(null);
const loading = ref(false);

onMounted(() => {
  void reload();
});

async function reload() {
  items.value = [];
  nextCursor.value = null;
  await fetchPage();
}

async function loadMore() {
  await fetchPage();
}

async function fetchPage() {
  loading.value = true;
  try {
    const params: Record<string, string | number> = { sort: sort.value, limit: 50 };
    if (q.value) {
      params.q = q.value;
    }
    if (category.value) {
      params.category = category.value;
    }
    if (nextCursor.value) {
      params.cursor = nextCursor.value;
    }
    const { data } = await api.get<{ items: TagDto[]; nextCursor: string | null }>('/api/tags', { params });
    items.value.push(...data.items);
    nextCursor.value = data.nextCursor;
  } finally {
    loading.value = false;
  }
}

function setCategory(cat: string) {
  category.value = cat as TagCategory | '';
  void reload();
}
</script>

<style scoped>
.tags-filter {
  min-width: 220px;
}
.tag-row {
  padding: 2px 0;
}
</style>
