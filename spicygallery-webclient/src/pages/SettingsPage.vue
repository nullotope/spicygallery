<template>
  <div class="q-pa-md settings" style="max-width: 720px">
    <div class="text-h6 q-mb-md">Settings</div>

    <q-markup-table flat bordered data-testid="libraries-table">
      <thead>
        <tr><th>Library</th><th>Path</th><th>Accessible</th></tr>
      </thead>
      <tbody>
        <tr v-for="lib in info?.libraries ?? []" :key="lib.name">
          <td>{{ lib.name }}</td>
          <td>{{ lib.root }}</td>
          <td>
            <q-icon
              :name="lib.accessible ? 'check_circle' : 'cancel'"
              :color="lib.accessible ? 'positive' : 'negative'"
            />
          </td>
        </tr>
      </tbody>
    </q-markup-table>

    <div class="q-mt-md text-caption text-grey-8" data-testid="tools">
      <div>
        ffmpeg: <template v-if="info?.ffmpeg.found">found ({{ info.ffmpeg.source }}) — {{ info.ffmpeg.version }}</template>
        <template v-else>not found</template>
      </div>
      <div>
        ffprobe: <template v-if="info?.ffprobe.found">found ({{ info.ffprobe.source }}) — {{ info.ffprobe.version }}</template>
        <template v-else>not found</template>
      </div>
      <div>thumbnail storage: {{ info?.appData }} · workers: {{ info?.workers }}</div>
    </div>

    <EmbeddingsPanel />
    <TaggingPanel />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { api } from 'src/boot/api';
import type { SystemInfo } from 'src/types/system';
import EmbeddingsPanel from 'src/components/EmbeddingsPanel.vue';
import TaggingPanel from 'src/components/TaggingPanel.vue';

const info = ref<SystemInfo | null>(null);

onMounted(async () => {
  const { data } = await api.get<SystemInfo>('/api/system/info');
  info.value = data;
});
</script>
