import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';

const apiGet = vi.fn().mockResolvedValue({ data: { run: null } });
const apiPost = vi.fn();
vi.mock('src/boot/api', () => ({
  api: { get: (...args: unknown[]) => apiGet(...args), post: (...args: unknown[]) => apiPost(...args) },
}));

const RouterLink = { props: ['to'], template: '<a :href="to"><slot /></a>' };
const RouterView = { template: '<div />' };

const { default: App } = await import('src/App.vue');

function mountApp() {
  return mount(App, {
    global: {
      plugins: [Quasar, createPinia()],
      components: { RouterLink, RouterView },
    },
  });
}

describe('App header', () => {
  beforeEach(() => {
    apiGet.mockClear();
    apiGet.mockResolvedValue({ data: { run: null } });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('title links home', () => {
    const wrapper = mountApp();
    const link = wrapper.find('[data-testid="app-title"]');
    expect(link.exists()).toBe(true);
    expect(link.attributes('href')).toBe('/');
  });

  it('header hosts the import controls', () => {
    const wrapper = mountApp();
    expect(wrapper.find('[data-testid="import-btn"]').exists()).toBe(true);
  });

  it('the Pools header tab is enabled (spec §4.1)', () => {
    const wrapper = mountApp();
    const pools = wrapper.findAll('.q-tab').find((t) => t.text().includes('Pools'));
    expect(pools).toBeDefined();
    // Quasar renders aria-disabled="true" only on disabled tabs
    expect(pools!.attributes('aria-disabled')).toBeUndefined();
    expect(wrapper.text()).not.toContain('pools arrive in slice 6');
  });
});
