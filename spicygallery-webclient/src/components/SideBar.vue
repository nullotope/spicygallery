<template>
  <div class="q-pa-sm column q-gutter-y-md sidebar">
    <!-- search -->
    <q-input
      v-model="searchModel"
      data-testid="sidebar-search"
      dense
      outlined
      debounce="300"
      placeholder="search"
      :error="!!entries.queryError"
      :error-message="entries.queryError ?? ''"
      @update:model-value="onSearch"
    >
      <template #prepend>
        <q-icon name="search" />
      </template>
      <template #append>
        <q-icon name="help_outline" class="cursor-pointer" data-testid="query-help" @click="help = true">
          <q-tooltip>query syntax</q-tooltip>
        </q-icon>
      </template>
    </q-input>

    <q-dialog v-model="help">
      <q-card class="query-help">
        <q-card-section>
          <div class="text-subtitle2">Query syntax</div>
          <div class="text-caption q-mt-sm">
            <div><code>term</code> — tag or filename (substring)</div>
            <div><code>-term</code> — exclude</div>
            <div><code>~term</code> — optional (OR-group)</div>
            <div><code>"foo: bar"</code> — quoted filename (colons need quotes)</div>
            <div><code>artist:name</code> · <code>character:</code> · <code>copyright:</code> · <code>meta:</code> — category-restricted tag</div>
            <div class="q-mt-sm">
              <code>type:image</code> · <code>ext:jpg</code> · <code>root:library</code> · <code>width:&gt;1000</code> ·
              <code>height:&lt;=800</code> · <code>duration:&gt;30s</code> · <code>filesize:&lt;5mb</code> ·
              <code>date:2026-01-01</code> · <code>tagcount:&gt;5</code> · <code>is:untagged</code> ·
              <code>hash:abc123</code> · <code>order:random</code>
            </div>
            <div class="q-mt-sm">
              <code>similar:&quot;a sunset beach scene&quot;</code> — semantic search (ranks by visual
              content; quotes keep multi-word phrases together)
            </div>
          </div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat label="close" v-close-popup />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- tags: detail view shows the entry's tags (+ editor); grid view shows facets -->
    <template v-if="entryTags !== null">
      <div data-testid="entry-tags">
        <template v-for="cat in entryCategories" :key="cat">
          <div class="text-caption text-grey-7 q-mt-sm">{{ cat }}</div>
          <div v-for="t in entryByCategory[cat]" :key="t.name" class="row items-center no-wrap">
            <q-btn
              dense
              flat
              round
              size="xs"
              icon="add"
              :data-testid="'entry-tag-add-' + t.name"
              :aria-label="'search ' + t.name"
              @click="searchTag(t.name)"
            >
              <q-tooltip>search {{ t.name }}</q-tooltip>
            </q-btn>
            <q-btn
              dense
              flat
              round
              size="xs"
              icon="remove"
              :data-testid="'entry-tag-remove-' + t.name"
              :aria-label="'exclude ' + t.name"
              @click="excludeTag(t.name)"
            >
              <q-tooltip>exclude {{ t.name }}</q-tooltip>
            </q-btn>
            <TagChip
              :name="t.name"
              :category="t.category"
              :tagged-by="t.taggedBy ?? null"
              :data-testid="'entry-tag-' + t.name"
              link
              @select="goToTag"
            />
          </div>
        </template>
        <div class="row items-center q-mt-xs">
          <q-btn dense flat round size="xs" icon="edit" data-testid="tag-edit" @click="editing = !editing">
            <q-tooltip>edit tags</q-tooltip>
          </q-btn>
          <span class="text-caption text-grey-7">edit tags</span>
        </div>
        <TagEditor
          v-if="editing"
          :entry-id="entryId"
          :tags="entryTags ?? []"
          @saved="onSaved"
          @cancel="editing = false"
        />
      </div>
    </template>
    <TagFacetSection v-else />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useEntriesStore } from 'src/stores/entries';
import TagFacetSection from 'src/components/TagFacetSection.vue';
import TagEditor from 'src/components/TagEditor.vue';
import TagChip from 'src/components/TagChip.vue';
import { TAG_CATEGORIES, type TagCategory, type TagDto } from 'src/types/tags';

const props = withDefaults(defineProps<{ entryTags?: TagDto[] | null }>(), { entryTags: null });
const emit = defineEmits<{ 'tags-saved': [tags: TagDto[]] }>();

const entries = useEntriesStore();
const route = useRoute();
const router = useRouter();
const help = ref(false);
const editing = ref(false);

const entryId = computed(() => Number(route.params.id));

const entryByCategory = computed<Record<TagCategory, TagDto[]>>(() => {
  const grouped = { pool_type: [], general: [], artist: [], character: [], copyright: [], meta: [] } as Record<
    TagCategory,
    TagDto[]
  >;
  for (const t of props.entryTags ?? []) {
    grouped[t.category].push(t);
  }
  return grouped;
});
const entryCategories = computed(() => TAG_CATEGORIES.filter((c) => entryByCategory.value[c].length > 0));

function navigateWith(term: string) {
  const current = typeof route.query.q === 'string' ? route.query.q : '';
  const q = [current.trim(), term].filter(Boolean).join(' ');
  void router.push({ path: '/', query: q ? { q } : {} });
}

/** Clicking the tag itself sets the search to it, clearing any existing query. */
function goToTag(name: string) {
  void router.push({ path: '/', query: { q: name } });
}

function searchTag(name: string) {
  navigateWith(name);
}

function excludeTag(name: string) {
  navigateWith('-' + name);
}

function onSaved(tags: TagDto[]) {
  editing.value = false;
  emit('tags-saved', tags);
}

const searchModel = computed({
  get: () => entries.q,
  set: (value: string) => {
    entries.q = value;
    void entries.reset();
  },
});

function onSearch() {
  // debounce handled by q-input's debounce prop; reset already triggered in the setter
}
</script>

<style scoped>
/* background lives in app.scss (theme-aware: .body--dark .sidebar) */
</style>

