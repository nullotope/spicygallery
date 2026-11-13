import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const { useThemeStore } = await import('src/stores/theme');

describe('theme store', () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
  });

  it('defaults to auto when nothing is stored', () => {
    const store = useThemeStore();
    expect(store.mode).toBe('auto');
  });

  it('restores a stored mode', () => {
    localStorage.setItem('spicygallery.theme', 'dark');
    const store = useThemeStore();
    expect(store.mode).toBe('dark');
  });

  it('falls back to auto on a corrupted stored value', () => {
    localStorage.setItem('spicygallery.theme', 'sepia');
    const store = useThemeStore();
    expect(store.mode).toBe('auto');
  });

  it('setMode validates and persists', () => {
    const store = useThemeStore();
    store.setMode('dark');
    expect(store.mode).toBe('dark');
    expect(localStorage.getItem('spicygallery.theme')).toBe('dark');
    expect(() => store.setMode('sepia' as never)).toThrow();
    expect(localStorage.getItem('spicygallery.theme')).toBe('dark');
  });

  it('cycles light → dark → auto → light', () => {
    const store = useThemeStore();
    store.setMode('light');
    store.cycle();
    expect(store.mode).toBe('dark');
    store.cycle();
    expect(store.mode).toBe('auto');
    store.cycle();
    expect(store.mode).toBe('light');
    expect(localStorage.getItem('spicygallery.theme')).toBe('light');
  });
});
