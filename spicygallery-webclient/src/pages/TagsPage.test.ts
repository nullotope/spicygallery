import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';
import { createMemoryHistory, createRouter } from 'vue-router';

const apiGet = vi.fn();
vi.mock('src/boot/api', () => ({
  api: { get: (...args: unknown[]) => apiGet(...args) },
}));

const { default: TagsPage } = await import('src/pages/TagsPage.vue');

const TAG = (id: number, name: string, category: string, postCount: number) => ({
  id,
  name,
  category,
  postCount,
});

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/tags', component: { template: '<div />' } },
    ],
  });
  await router.push('/tags');
  await router.isReady();
  return mount(TagsPage, { global: { plugins: [Quasar, createPinia(), router] } });
}

describe('TagsPage', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiGet.mockResolvedValue({ data: { items: [], nextCursor: null } });
  });

  it('renders rows and loads more via cursor', async () => {
    apiGet
      .mockResolvedValueOnce({
        data: { items: [TAG(1, 'alpha', 'general', 5), TAG(2, 'beta', 'artist', 3)], nextCursor: 'beta:2' },
      })
      .mockResolvedValueOnce({ data: { items: [TAG(3, 'gamma', 'general', 1)], nextCursor: null } });
    const wrapper = await mountPage();
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('alpha');
    expect(wrapper.text()).toContain('beta');

    await wrapper.find('[data-testid=tags-load-more]').trigger('click');
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('gamma');
    expect(apiGet).toHaveBeenLastCalledWith(
      '/api/tags',
      expect.objectContaining({ params: expect.objectContaining({ cursor: 'beta:2' }) }),
    );
    expect(wrapper.find('[data-testid=tags-load-more]').exists()).toBe(false);
  });

  it('filters and sort changes refetch', async () => {
    const wrapper = await mountPage();
    await wrapper.vm.$nextTick();
    const calls = apiGet.mock.calls.length;

    await wrapper.find('[data-testid=tags-name-filter]').setValue('duck');
    await new Promise((r) => setTimeout(r, 350)); // filter debounce
    expect(apiGet).toHaveBeenCalledWith(
      '/api/tags',
      expect.objectContaining({ params: expect.objectContaining({ q: 'duck' }) }),
    );

    // category chip (order: all, pool_type, general, artist, …)
    await wrapper.findAll('[data-testid=tags-category-chip]')[3]!.trigger('click');
    expect(apiGet).toHaveBeenLastCalledWith(
      '/api/tags',
      expect.objectContaining({ params: expect.objectContaining({ category: 'artist' }) }),
    );

    // sort via the q-select's update event
    wrapper.getComponent({ name: 'QSelect' }).vm.$emit('update:model-value', 'count');
    await wrapper.vm.$nextTick();
    expect(apiGet).toHaveBeenLastCalledWith(
      '/api/tags',
      expect.objectContaining({ params: expect.objectContaining({ sort: 'count' }) }),
    );
    expect(apiGet.mock.calls.length).toBeGreaterThan(calls);
  });

  it('row links to the grid query', async () => {
    apiGet.mockResolvedValueOnce({ data: { items: [TAG(1, 'alpha', 'general', 5)], nextCursor: null } });
    const wrapper = await mountPage();
    await wrapper.vm.$nextTick();
    const link = wrapper.find('[data-testid=tag-link-alpha]');
    expect(link.attributes('href')).toBe('/?q=alpha');
  });
});
