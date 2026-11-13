<template>
  <div v-if="visible" data-testid="tag-facets">
    <template v-for="cat in categories" :key="cat">
      <div class="text-caption text-grey-7 q-mt-sm">{{ cat }}</div>
      <div v-for="f in byCategory[cat]" :key="f.name" class="row items-center no-wrap">
        <q-btn
          dense
          flat
          round
          size="xs"
          icon="add"
          :data-testid="'facet-add-' + f.name"
          :aria-label="'search ' + f.name"
          @click="add(f.name)"
        >
          <q-tooltip>search {{ f.name }}</q-tooltip>
        </q-btn>
        <q-btn
          dense
          flat
          round
          size="xs"
          icon="remove"
          :data-testid="'facet-remove-' + f.name"
          :aria-label="'exclude ' + f.name"
          @click="exclude(f.name)"
        >
          <q-tooltip>exclude {{ f.name }}</q-tooltip>
        </q-btn>
        <TagChip :name="f.name" :category="f.category" link @select="goToTag" />
        <span class="text-caption text-grey-7">({{ f.count }})</span>
      </div>
    </template>
    <RouterLink to="/tags" data-testid="browse-tags" class="text-caption">browse all tags →</RouterLink>
    <RouterLink to="/concepts" data-testid="concepts-link" class="text-caption q-ml-sm">concepts →</RouterLink>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import { useEntriesStore } from 'src/stores/entries';
import { USER_TAG_CATEGORIES, type TagCategory } from 'src/types/tags';
import TagChip from 'src/components/TagChip.vue';
import { useRouter } from 'vue-router';

const router = useRouter();

/** Clicking the tag itself sets the search to it, clearing any existing query. */
function goToTag(name: string) {
  void router.push({ path: '/', query: { q: name } });
}

const store = useEntriesStore();

const visible = computed(() => store.facets.length > 0 && store.queryError === null);

const byCategory = computed<Record<TagCategory, typeof store.facets>>(() => {
  const grouped = {
    pool_type: [],
    general: [],
    artist: [],
    character: [],
    copyright: [],
    meta: [],
  } as Record<TagCategory, typeof store.facets>;
  for (const f of store.facets) {
    grouped[f.category].push(f);
  }
  return grouped;
});

const categories = computed(() => USER_TAG_CATEGORIES.filter((c) => byCategory.value[c].length > 0));

function appendTerm(term: string) {
  store.q = (store.q ? store.q.trimEnd() + ' ' : '') + term;
  void store.reset();
}

function add(name: string) {
  appendTerm(name);
}

function exclude(name: string) {
  appendTerm('-' + name);
}
</script>
