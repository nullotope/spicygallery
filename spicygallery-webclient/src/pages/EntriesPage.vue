<template>
  <div class="entries-layout">
    <aside class="entries-sidebar">
      <SideBar />
      <TaggingControls />
    </aside>
    <main class="entries-main q-pa-md">
      <div class="q-mb-sm q-gutter-x-sm">
        <q-btn
          data-testid="kind-all"
          size="sm"
          :outline="store.kind !== ''"
          :color="store.kind === '' ? 'primary' : 'grey-7'"
          no-caps
          label="all"
          @click="setKind('')"
        />
        <q-btn
          data-testid="kind-image"
          size="sm"
          :outline="store.kind !== 'image'"
          :color="store.kind === 'image' ? 'primary' : 'grey-7'"
          no-caps
          label="images"
          @click="setKind('image')"
        />
        <q-btn
          data-testid="kind-video"
          size="sm"
          :outline="store.kind !== 'video'"
          :color="store.kind === 'video' ? 'primary' : 'grey-7'"
          no-caps
          label="videos"
          @click="setKind('video')"
        />
      </div>

      <q-infinite-scroll :offset="400" :disable="store.nextCursor === null" @load="onLoad">
        <div class="tile-grid">
          <RouterLink
            v-for="item in store.items"
            :key="item.id"
            class="tile"
            :to="{ path: `/entries/${item.id}`, query: routeQuery }"
          >
            <span class="tile-media">
              <img class="tile-thumb" :src="item.thumbUrl" loading="lazy" alt="" />
              <span v-if="item.durationMs !== null" class="duration-badge">{{ humanDuration(item.durationMs) }}</span>
            </span>
            <span class="tile-name text-caption">{{ item.fileName }}</span>
          </RouterLink>
        </div>
        <template #loading>
          <div class="row justify-center q-my-md">
            <q-spinner-dots color="primary" size="40px" />
          </div>
        </template>
      </q-infinite-scroll>

      <div v-if="!store.loading && store.items.length === 0" class="text-grey-7 q-pa-lg">
        no entries match — run an import from the sidebar
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { useEntriesStore, type KindFilter } from 'src/stores/entries';
import { useImportStore } from 'src/stores/importStore';
import SideBar from 'src/components/SideBar.vue';
import TaggingControls from 'src/components/TaggingControls.vue';
import { humanDuration } from 'src/utils/format';

const store = useEntriesStore();
const imports = useImportStore();
const route = useRoute();

// a finished import changes the library — reload the grid so new entries appear
watch(
  () => imports.run?.status,
  (status, before) => {
    if (status === 'completed' && before === 'running') {
      void store.reset();
    }
  },
);

const routeQuery = computed(() => {
  const q: Record<string, string> = {};
  if (store.q) {
    q.q = store.q;
  }
  if (store.kind) {
    q.kind = store.kind;
  }
  return q;
});

onMounted(() => {
  // deep link: /?q=duck&kind=video
  const q = route.query.q;
  const kind = route.query.kind;
  if (typeof q === 'string' && q !== store.q) {
    store.q = q;
  }
  if ((kind === 'image' || kind === 'video') && kind !== store.kind) {
    store.kind = kind;
  }
  void store.reset();
});

function setKind(kind: KindFilter) {
  store.kind = kind;
  void store.reset();
}

async function onLoad(_index: number, done: (stop?: boolean) => void) {
  await store.loadMore();
  done(store.nextCursor === null);
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
</style>
