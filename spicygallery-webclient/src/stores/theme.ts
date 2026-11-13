import { defineStore } from 'pinia';

export type ThemeMode = 'light' | 'dark' | 'auto';

const STORAGE_KEY = 'spicygallery.theme';
const MODES: ThemeMode[] = ['light', 'dark', 'auto'];

/** Stored preference, or 'auto' when absent/corrupted (per-browser setting). */
function storedMode(): ThemeMode {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return MODES.includes(raw as ThemeMode) ? (raw as ThemeMode) : 'auto';
  } catch {
    return 'auto';
  }
}

/**
 * User theme preference (spec: light/dark/auto). Owns the mode + persistence only —
 * applying it to Quasar's Dark plugin happens in ThemeToggle's watcher (component
 * context), so this store stays trivially testable.
 */
export const useThemeStore = defineStore('theme', {
  state: () => ({
    mode: storedMode(),
  }),
  actions: {
    setMode(mode: ThemeMode) {
      if (!MODES.includes(mode)) {
        throw new Error('invalid theme mode: ' + String(mode));
      }
      this.mode = mode;
      try {
        localStorage.setItem(STORAGE_KEY, mode);
      } catch {
        // storage unavailable (private mode) — keep the in-memory mode
      }
    },
    cycle() {
      const idx = MODES.indexOf(this.mode);
      const next = MODES[(idx + 1) % MODES.length] ?? 'auto';
      this.setMode(next);
    },
  },
});
