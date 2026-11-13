import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';
import { createMemoryHistory, createRouter } from 'vue-router';

const apiGet = vi.fn();
const apiPost = vi.fn();
const apiDelete = vi.fn();
vi.mock('src/boot/api', () => ({
  api: {
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
    delete: (...args: unknown[]) => apiDelete(...args),
  },
}));

const { default: ConceptsPage } = await import('src/pages/ConceptsPage.vue');

const CONCEPT = (id: number, name: string, overrides: Record<string, unknown> = {}) => ({
  id,
  name,
  phrase: '',
  exampleCount: 5,
  excludedCount: 0,
  taggedCount: 3,
  applyable: 2,
  anchorStale: false,
  createdAt: '2026-11-09T10:00:00Z',
  updatedAt: '2026-11-10T12:00:00Z',
  ...overrides,
});

/** Axios-shaped rejection carrying an RFC-7807 problem body (the store unwraps `detail`). */
function problemError(status: number, detail: string) {
  return Object.assign(new Error('Request failed with status code ' + status), {
    isAxiosError: true,
    response: { status, data: { title: 'Error', status, detail } },
  });
}

function inputValue(wrapper: ReturnType<typeof mount>, testid: string): string {
  return (wrapper.find(`[data-testid=${testid}]`).element as HTMLInputElement).value;
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/concepts', component: { template: '<div />' } },
      { path: '/concepts/:id', component: { template: '<div />' } },
    ],
  });
  await router.push('/concepts');
  await router.isReady();
  return mount(ConceptsPage, { global: { plugins: [Quasar, createPinia(), router] } });
}

describe('ConceptsPage', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiPost.mockReset();
    apiDelete.mockReset();
    apiGet.mockResolvedValue({ data: [] });
  });

  it('renders rows with coverage, stale badge, updated date, and workbench links', async () => {
    apiGet.mockResolvedValueOnce({
      data: [
        CONCEPT(1, 'heron'),
        CONCEPT(2, 'windragon', { anchorStale: true, taggedCount: 38, exampleCount: 42 }),
      ],
    });
    const wrapper = await mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid=concepts-page]').exists()).toBe(true);
    expect(wrapper.text()).toContain('heron');
    expect(wrapper.text()).toContain('3/5');
    expect(wrapper.text()).toContain('38/42');
    expect(wrapper.find('[data-testid=concept-stale-windragon]').exists()).toBe(true);
    expect(wrapper.find('[data-testid=concept-stale-heron]').exists()).toBe(false);
    expect(wrapper.text()).toContain('2026-11-10');
    expect(wrapper.find('[data-testid=concept-link-heron]').attributes('href')).toBe('/concepts/1');
  });

  it('create calls the store, reloads, and clears the form; empty phrase is omitted', async () => {
    apiGet
      .mockResolvedValueOnce({ data: [] })
      .mockResolvedValueOnce({ data: [CONCEPT(3, 'duck', { taggedCount: 0, exampleCount: 0, applyable: 0 })] })
      .mockResolvedValueOnce({ data: [CONCEPT(3, 'duck', { taggedCount: 0, exampleCount: 0, applyable: 0 })] });
    apiPost
      .mockResolvedValueOnce({ data: CONCEPT(3, 'duck') })
      .mockResolvedValueOnce({ data: CONCEPT(4, 'goose') });
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid=concept-create-name]').setValue('duck');
    await wrapper.find('[data-testid=concept-create-phrase]').setValue('a duck is a bird');
    await wrapper.find('[data-testid=concept-create-create]').trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenLastCalledWith('/api/concepts', { name: 'duck', phrase: 'a duck is a bird' });
    expect(inputValue(wrapper, 'concept-create-name')).toBe('');
    expect(inputValue(wrapper, 'concept-create-phrase')).toBe('');
    expect(wrapper.text()).toContain('duck'); // list reloaded with the new concept

    await wrapper.find('[data-testid=concept-create-name]').setValue('goose');
    await wrapper.find('[data-testid=concept-create-create]').trigger('click');
    await flushPromises();
    expect(apiPost).toHaveBeenLastCalledWith('/api/concepts', { name: 'goose' });
  });

  it('duplicate-name create renders the server detail inline', async () => {
    apiPost.mockRejectedValueOnce(problemError(409, "a concept named 'duck' already exists"));
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid=concept-create-name]').setValue('duck');
    await wrapper.find('[data-testid=concept-create-create]').trigger('click');
    await flushPromises();

    const err = wrapper.find('[data-testid=concept-create-error]');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain("a concept named 'duck' already exists");
  });

  it('provider-unavailable create surfaces the server message inline', async () => {
    apiPost.mockRejectedValueOnce(problemError(503, 'embedding provider unreachable'));
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid=concept-create-name]').setValue('windragon');
    await wrapper.find('[data-testid=concept-create-phrase]').setValue('winddragon-like');
    await wrapper.find('[data-testid=concept-create-create]').trigger('click');
    await flushPromises();

    const err = wrapper.find('[data-testid=concept-create-error]');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain('embedding provider unreachable');
  });

  it('delete confirms before calling the store, then reloads', async () => {
    apiGet
      .mockResolvedValueOnce({ data: [CONCEPT(7, 'duck')] })
      .mockResolvedValueOnce({ data: [] }); // after delete
    apiDelete.mockResolvedValueOnce({ data: undefined });
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid=concept-delete-7]').trigger('click');
    await flushPromises();
    expect(apiDelete).not.toHaveBeenCalled(); // first click only arms the confirm
    expect(wrapper.text()).toContain('confirm');

    await wrapper.find('[data-testid=concept-delete-7]').trigger('click');
    await flushPromises();
    expect(apiDelete).toHaveBeenCalledTimes(1);
    expect(apiDelete).toHaveBeenCalledWith('/api/concepts/7');
    expect(apiGet).toHaveBeenCalledTimes(2); // initial load + reload after delete
  });
});
