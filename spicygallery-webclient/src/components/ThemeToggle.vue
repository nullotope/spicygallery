<template>
  <q-btn
    flat
    round
    dense
    size="sm"
    data-testid="theme-toggle"
    :aria-label="`Theme: ${store.mode}`"
    @click="store.cycle()"
  >
    <q-icon :name="icon" size="20px" />
    <q-tooltip>Theme: {{ store.mode }}{{ store.mode === 'auto' ? ' (follows system)' : '' }} — click to change</q-tooltip>
  </q-btn>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue';
import { useQuasar } from 'quasar';
import { useThemeStore } from 'src/stores/theme';

const store = useThemeStore();
const $q = useQuasar();

const ICONS: Record<string, string> = {
  light: 'light_mode',
  dark: 'dark_mode',
  auto: 'brightness_auto',
};
const icon = computed(() => ICONS[store.mode] ?? 'brightness_auto');

// Quasar's Dark plugin accepts only true | false | 'auto' — the strings
// 'light'/'dark' are silently treated as false (probed: set('dark') → isActive false)
const DARK_VALUE: Record<string, boolean | 'auto'> = {
  light: false,
  dark: true,
  auto: 'auto',
};

// Applies the mode (including 'auto' = follow prefers-color-scheme) and re-applies
// on change. immediate + running in setup() = applied before first paint, no flash.
watch(
  () => store.mode,
  (mode) => {
    $q.dark.set(DARK_VALUE[mode] ?? 'auto');
  },
  { immediate: true },
);
</script>
