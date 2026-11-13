<template>
  <span
    class="tag-chip"
    :class="[{ 'tag-chip--link': link }, 'tag-cat-' + category]"
    :role="link ? 'button' : undefined"
    @click="link && emit('select', name)"
  >
    {{ name }}
    <q-tooltip v-if="taggedBy && taggedBy !== 'user'">tagged by {{ taggedBy }}</q-tooltip>
  </span>
</template>

<script setup lang="ts">
import type { TagCategory } from 'src/types/tags';

withDefaults(defineProps<{ name: string; category: TagCategory; taggedBy?: string | null; link?: boolean }>(), {
  taggedBy: null,
  link: false,
});

const emit = defineEmits<{ select: [name: string] }>();
</script>

<style scoped>
.tag-chip--link {
  cursor: pointer;
}
.tag-chip--link:hover {
  text-decoration: underline;
}
</style>
