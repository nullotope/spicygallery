<template>
  <div class="entries-layout">
    <aside class="entries-sidebar">
      <div class="q-pa-sm column q-gutter-y-md">
        <q-input
          :model-value="q"
          data-testid="pool-search"
          dense
          outlined
          debounce="300"
          placeholder="search"
          @update:model-value="onSearch"
        >
          <template #prepend>
            <q-icon name="search" />
          </template>
        </q-input>

        <div>
          <q-btn
            data-testid="pool-new"
            outline
            no-caps
            dense
            color="primary"
            icon="add"
            label="New"
            @click="openCreator(null)"
          />
        </div>

        <div v-if="facetCategories.length > 0">
          <template v-for="cat in facetCategories" :key="cat">
            <div class="text-caption text-grey-7 q-mt-sm">{{ cat }}</div>
            <div
              v-for="f in facetsByCategory[cat]"
              :key="cat + ':' + f.name"
              class="row items-center no-wrap"
            >
              <TagChip
                :name="f.name"
                :category="f.category"
                link
                :data-testid="'pool-facet-' + f.name"
                @select="appendFacet(f)"
              />
              <span class="text-caption text-grey-7 q-ml-xs">({{ f.count }})</span>
            </div>
          </template>
        </div>
      </div>
    </aside>
    <main class="entries-main q-pa-md">
      <div v-if="store.loading" class="row justify-center q-my-md">
        <q-spinner-dots color="primary" size="40px" />
      </div>

      <div class="tile-grid">
        <RouterLink
          v-for="p in store.items"
          :key="p.id"
          class="tile"
          :to="'/pools/' + p.id"
          :data-testid="'pool-tile-' + p.id"
        >
          <span class="tile-media">
            <img v-if="p.cover" class="tile-thumb" :src="p.cover.thumbUrl" loading="lazy" alt="" />
            <span v-else class="tile-thumb tile-placeholder row flex-center">
              <q-icon name="photo_library" size="36px" />
            </span>
          </span>
          <span class="tile-name text-caption">{{ p.name }}</span>
          <span class="row items-center no-wrap q-px-sm q-pb-xs q-gutter-x-sm">
            <q-badge outline color="grey-7">{{ p.entryCount }}</q-badge>
            <span v-if="p.updatedAt" class="text-caption text-grey-7">
              updated {{ p.updatedAt.slice(0, 10) }}
            </span>
          </span>
        </RouterLink>

        <button
          v-for="d in filteredDrafts"
          :key="d.rootName + '/' + d.dirPath"
          type="button"
          class="tile tile-draft"
          data-testid="pool-draft-tile"
          @click="openCreator(d)"
        >
          <span class="tile-media">
            <img v-if="d.cover" class="tile-thumb" :src="d.cover.thumbUrl" loading="lazy" alt="" />
            <span v-else class="tile-thumb tile-placeholder row flex-center">
              <q-icon name="folder_open" size="36px" />
            </span>
            <span class="draft-badge">draft</span>
          </span>
          <span class="tile-name text-caption">{{ draftCaption(d) }}</span>
        </button>
      </div>

      <div v-if="!store.loading && store.items.length === 0 && filteredDrafts.length === 0" class="text-grey-7 q-pa-lg">
        {{ q.trim() ? 'no pools match' : 'no pools yet — create one with New or pick a draft' }}
      </div>
    </main>

    <PoolCreatorModal v-model="creatorOpen" :draft="creatorDraft" @created="onCreated" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import { usePoolsStore } from 'src/stores/pools';
import TagChip from 'src/components/TagChip.vue';
import PoolCreatorModal from 'src/components/PoolCreatorModal.vue';
import { TAG_CATEGORIES, type FacetDto, type TagCategory } from 'src/types/tags';
import type { FolderSuggestion, PoolDto } from 'src/types/pools';

/** Spec §3.7: drafts implicitly carry the seeded pool_type:suggested tag. */
const SUGGESTED_REF = 'pool_type:suggested';

const store = usePoolsStore();
const router = useRouter();

const q = ref('');
const creatorOpen = ref(false);
const creatorDraft = ref<FolderSuggestion | null>(null);

onMounted(() => {
  // drafts load once — the client filters them per q (spec §3.7)
  void store.folders();
  void reloadSearch();
});

/** list + facets take q server-side; drafts are filtered client-side below. */
async function reloadSearch() {
  await Promise.all([
    store.list(q.value.trim() ? { q: q.value.trim() } : undefined),
    store.facets(q.value.trim() || undefined),
  ]);
}

function onSearch(value: string | number | null) {
  q.value = String(value ?? '');
  void reloadSearch();
}

/** Chip click appends the tag's FULL reference (bare for general, category:name
 *  otherwise) — the form §3.7(b)'s term match understands. */
function appendFacet(f: FacetDto) {
  const term = f.category === 'general' ? f.name : f.category + ':' + f.name;
  q.value = q.value.trim() ? q.value.trimEnd() + ' ' + term : term;
  void reloadSearch();
}

/** One row per (category, name); a degenerate dual "suggested" row (real tag +
 *  fixed pseudo-facet) keeps the higher count. */
const dedupedFacets = computed<FacetDto[]>(() => {
  const byKey = new Map<string, FacetDto>();
  for (const f of store.facetItems) {
    const key = f.category + ':' + f.name;
    const seen = byKey.get(key);
    if (!seen || f.count > seen.count) {
      byKey.set(key, f);
    }
  }
  return [...byKey.values()];
});

const facetsByCategory = computed<Record<TagCategory, FacetDto[]>>(() => {
  const grouped = {
    pool_type: [],
    general: [],
    artist: [],
    character: [],
    copyright: [],
    meta: [],
  } as Record<TagCategory, FacetDto[]>;
  for (const f of dedupedFacets.value) {
    grouped[f.category].push(f);
  }
  return grouped;
});

const facetCategories = computed(() => TAG_CATEGORIES.filter((c) => facetsByCategory.value[c].length > 0));

/** Client-side mirror of PoolService.draftMatches: every term is a case-insensitive
 *  substring of `rootName/dirPath`, except pool_type:suggested which matches all drafts. */
const filteredDrafts = computed<FolderSuggestion[]>(() => {
  const terms = q.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (terms.length === 0) {
    return store.folderItems;
  }
  return store.folderItems.filter((d) => {
    const path = (d.rootName + '/' + d.dirPath).toLowerCase();
    return terms.every((t) => path.includes(t) || t === SUGGESTED_REF);
  });
});

function draftCaption(d: FolderSuggestion): string {
  return d.dirPath ? d.rootName + '/' + d.dirPath : d.rootName;
}

function openCreator(draft: FolderSuggestion | null) {
  creatorDraft.value = draft;
  creatorOpen.value = true;
}

function onCreated(pool: PoolDto) {
  void router.push('/pools/' + pool.id);
}
</script>

<style scoped>
.entries-layout {
  display: flex;
  min-height: calc(100vh - 64px);
}
.entries-sidebar {
  width: 230px;
  border-right: 1px solid #ddd;
  flex-shrink: 0;
}
.entries-main {
  flex: 1;
  min-width: 0;
}

/* draft tiles: a button styled as a tile with the §4.1 dashed border */
.tile-draft {
  padding: 0;
  font: inherit;
  text-align: left;
  cursor: pointer;
  border-style: dashed;
  border-color: #999;
  background: transparent;
}
.body--dark .tile-draft {
  border-color: #555;
}
.draft-badge {
  position: absolute;
  left: 4px;
  top: 4px;
  background: rgba(0, 0, 0, 0.72);
  color: #fff;
  font-size: 11px;
  line-height: 1.4;
  padding: 1px 6px;
  border-radius: 3px;
}
.tile-placeholder {
  color: #bbb;
}
.body--dark .tile-placeholder {
  color: #555;
}
</style>
