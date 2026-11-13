import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';
import { createMemoryHistory, createRouter } from 'vue-router';

const { default: TagFacetSection } = await import('src/components/TagFacetSection.vue');
const { useEntriesStore } = await import('src/stores/entries');

const apiGet = vi.fn();
vi.mock('src/boot/api', () => ({
  api: { get: (...args: unknown[]) => apiGet(...args) },
}));

async function mountSection() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/tags', component: { template: '<div />' } },
      { path: '/concepts', component: { template: '<div />' } },
    ],
  });
  await router.push('/');
  await router.isReady();
  return mount(TagFacetSection, { global: { plugins: [Quasar, createPinia(), router] } });
}

describe('TagFacetSection', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiGet.mockResolvedValue({ data: { items: [], nextCursor: null } });
  });

  it('renders facets grouped by category with counts', async () => {
    const wrapper = await mountSection();
    const store = useEntriesStore();
    store.facets = [
      { name: 'heron', category: 'artist', count: 7 },
      { name: 'duck', category: 'general', count: 2 },
    ];
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('artist');
    expect(wrapper.text()).toContain('heron');
    expect(wrapper.text()).toContain('(7)');
    expect(wrapper.text()).toContain('duck');
    expect(wrapper.text()).toContain('(2)');
    expect(wrapper.find('[data-testid=browse-tags]').attributes('href')).toBe('/tags');
    expect(wrapper.find('[data-testid=concepts-link]').attributes('href')).toBe('/concepts');
  });

  it('never groups a pool_type facet — the system category is not entry-facing here', async () => {
    const wrapper = await mountSection();
    const store = useEntriesStore();
    store.facets = [
      { name: 'pool', category: 'pool_type', count: 2 },
      { name: 'duck', category: 'general', count: 3 },
    ];
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).not.toContain('pool_type');
    expect(wrapper.find('[data-testid="facet-add-pool"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('duck');
  });

  it('+ appends the tag to the query and re-queries', async () => {
    const wrapper = await mountSection();
    const store = useEntriesStore();
    store.q = 'type:image';
    store.facets = [{ name: 'duck', category: 'general', count: 2 }];
    await wrapper.vm.$nextTick();
    await wrapper.find('[data-testid=facet-add-duck]').trigger('click');
    expect(store.q).toBe('type:image duck');
    expect(apiGet).toHaveBeenCalled();
  });

  it('- appends an exclusion', async () => {
    const wrapper = await mountSection();
    const store = useEntriesStore();
    store.q = '';
    store.facets = [{ name: 'duck', category: 'general', count: 2 }];
    await wrapper.vm.$nextTick();
    await wrapper.find('[data-testid=facet-remove-duck]').trigger('click');
    expect(store.q).toBe('-duck');
  });

  it('is hidden when facets are empty or the query errored', async () => {
    const wrapper = await mountSection();
    const store = useEntriesStore();
    store.facets = [];
    await wrapper.vm.$nextTick();
    expect(wrapper.find('[data-testid=tag-facets]').exists()).toBe(false);
    store.facets = [{ name: 'duck', category: 'general', count: 2 }];
    store.queryError = "unknown category or metatag 'foo:bar'";
    await wrapper.vm.$nextTick();
    expect(wrapper.find('[data-testid=tag-facets]').exists()).toBe(false);
  });
});
