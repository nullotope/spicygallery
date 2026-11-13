<template>
  <div v-if="items.length > 0" data-testid="related-strip" class="q-mt-md related-strip">
    <div class="text-subtitle2 text-grey-8 q-mb-xs">Related</div>
    <div class="row q-col-gutter-sm">
      <div
        v-for="item in items"
        :key="item.id"
        data-testid="related-tile"
        class="related-tile"
        :title="item.fileName"
        @click="go(item.id)"
      >
        <img :src="item.thumbUrl" loading="lazy" alt="" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { api } from 'src/boot/api';
import type { GridItem } from 'src/stores/entries';

const props = defineProps<{ entryId: number }>();

/** Detail-page Related strip (spec slice-3 §5): hidden when empty or on any fetch
 *  error (no vectors yet, provider down — the detail page must stay usable). */
const items = ref<GridItem[]>([]);
const router = useRouter();

async function load() {
  items.value = [];
  try {
    const { data } = await api.get<{ items: GridItem[] }>(
      `/api/entries/${props.entryId}/related`,
      { params: { limit: 8 } },
    );
    items.value = data.items ?? [];
  } catch {
    // silent: an un-embedded entry simply shows no strip
  }
}

function go(id: number) {
  void router.push(`/entries/${id}`);
}

onMounted(() => void load());
watch(() => props.entryId, () => void load());
</script>

<style scoped>
.related-tile {
  width: 96px;
  height: 72px;
  overflow: hidden;
  border-radius: 4px;
  cursor: pointer;
  background: rgba(128, 128, 128, 0.15);
  flex-shrink: 0;
}
.related-tile img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
</style>
