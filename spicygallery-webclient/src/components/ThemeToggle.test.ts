import { beforeEach, describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';

const { default: ThemeToggle } = await import('src/components/ThemeToggle.vue');
const { useThemeStore } = await import('src/stores/theme');

function mountToggle() {
  return mount(ThemeToggle, {
    global: { plugins: [Quasar, createPinia()] },
  });
}

describe('ThemeToggle', () => {
  beforeEach(() => {
    localStorage.clear();
    document.body.className = '';
  });

  it('applies the stored mode on mount (real Quasar Dark plugin)', async () => {
    localStorage.setItem('spicygallery.theme', 'dark');
    mountToggle();
    expect(document.body.classList.contains('body--dark')).toBe(true);
  });

  it('clicking cycles light → dark → auto → light and persists', async () => {
    const wrapper = mountToggle();
    const store = useThemeStore();
    store.setMode('light');
    const btn = wrapper.find('[data-testid=theme-toggle]');

    await btn.trigger('click');
    expect(store.mode).toBe('dark');
    expect(document.body.classList.contains('body--dark')).toBe(true);
    expect(localStorage.getItem('spicygallery.theme')).toBe('dark');

    await btn.trigger('click');
    expect(store.mode).toBe('auto');

    await btn.trigger('click');
    expect(store.mode).toBe('light');
    expect(document.body.classList.contains('body--dark')).toBe(false);
    expect(localStorage.getItem('spicygallery.theme')).toBe('light');
  });

  it('shows the icon for the current mode', async () => {
    const wrapper = mountToggle();
    const store = useThemeStore();
    store.setMode('light');
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('light_mode');
    store.setMode('dark');
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('dark_mode');
    store.setMode('auto');
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('brightness_auto');
  });
});
