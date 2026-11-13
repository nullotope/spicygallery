import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { h } from 'vue';
import { Quasar } from 'quasar';

const apiGet = vi.fn();
vi.mock('src/boot/api', () => ({
  api: { get: (...args: unknown[]) => apiGet(...args), post: vi.fn() },
}));

const RouterLinkStub = {
  name: 'RouterLink',
  props: ['to'],
  setup: (_props: unknown, ctx: { slots: any }) => () => h('a', ctx.slots.default?.()),
};

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn() }),
  RouterLink: RouterLinkStub,
}));

const { default: EntriesPage } = await import('src/pages/EntriesPage.vue');

function gridPage(ids: number[]) {
  return {
    data: {
      items: ids.map((id) => ({
        id,
        kind: 'image',
        fileName: 'f' + id + '.png',
        width: 8,
        height: 4,
        durationMs: null,
        thumbUrl: `/api/entries/${id}/thumb?v=1`,
      })),
      nextCursor: null,
    },
  };
}

function mountPage(pinia = createPinia()) {
  return mount(EntriesPage, {
    global: {
      plugins: [Quasar, pinia],
    },
  });
}

function runDto(status: 'running' | 'completed') {
  return {
    id: 1,
    status,
    startedAt: '2026-11-09T00:00:00Z',
    finishedAt: status === 'completed' ? '2026-11-09T00:01:00Z' : null,
    scanned: 3,
    added: 3,
    updated: 0,
    missingCount: 0,
    errorCount: 0,
    message: null,
  };
}

describe('EntriesPage', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiGet.mockResolvedValue(gridPage([7, 3, 1]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders one tile per grid item', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.findAll('.tile').length).toBe(3);
    expect(wrapper.findAll('img.tile-thumb').length).toBe(3);
  });

  it('renders no tiles when the store is empty', async () => {
    apiGet.mockResolvedValue({ data: { items: [], nextCursor: null } });
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.findAll('.tile').length).toBe(0);
  });

  it('kind chips set the filter and refetch', async () => {
    const wrapper = mountPage();
    await flushPromises();
    apiGet.mockClear();
    const videoChip = wrapper.find('[data-testid="kind-video"]');
    await videoChip.trigger('click');
    await flushPromises();
    expect(apiGet).toHaveBeenCalledWith('/api/entries', {
      params: { kind: 'video' },
    });
  });

  it('renders the bulk autotag action row in the sidebar, below the facets', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const sidebar = wrapper.find('.entries-sidebar');
    expect(sidebar.find('[data-testid="tag-results-btn"]').exists()).toBe(true);
    expect(sidebar.find('[data-testid="tag-untagged-btn"]').exists()).toBe(true);
    // the action row is a sibling placed after the SideBar content in the column
    const kids = Array.from(sidebar.element.children);
    const controlsIdx = kids.findIndex((el) => el.getAttribute('data-testid') === 'tagging-controls');
    expect(controlsIdx).toBe(kids.length - 1);
  });

  it('reloads the grid when an import run completes', async () => {
    const pinia = createPinia();
    const wrapper = mountPage(pinia);
    await flushPromises();
    const entryLoadsBefore = apiGet.mock.calls.filter(([url]) => url === '/api/entries').length;
    expect(entryLoadsBefore).toBe(1);

    const { useImportStore } = await import('src/stores/importStore');
    const imports = useImportStore(pinia);
    imports.run = runDto('running');
    await flushPromises();
    apiGet.mockClear();

    imports.run = runDto('completed');
    await flushPromises();
    expect(apiGet.mock.calls.filter(([url]) => url === '/api/entries').length).toBe(1);
    void wrapper;
  });

  it('does not reload the grid while an import is only running', async () => {
    const pinia = createPinia();
    const wrapper = mountPage(pinia);
    await flushPromises();
    apiGet.mockClear();

    const { useImportStore } = await import('src/stores/importStore');
    const imports = useImportStore(pinia);
    imports.run = runDto('running');
    await flushPromises();
    expect(apiGet.mock.calls.filter(([url]) => url === '/api/entries').length).toBe(0);
    void wrapper;
  });
});
