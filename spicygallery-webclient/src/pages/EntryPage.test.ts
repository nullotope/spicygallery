import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import { mount, flushPromises, DOMWrapper } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';

const apiGet = vi.fn();
const apiPost = vi.fn();
vi.mock('src/boot/api', () => ({
  api: {
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
  },
}));

const push = vi.fn();
// `query` widened: pool tests assign the pool-reading arrival shape (?pool=)
const routeState = reactive({
  params: { id: '42' },
  query: { q: 'duck', kind: 'image' } as Record<string, string>,
});
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => routeState,
  RouterLink: { template: '<a><slot /></a>' },
}));

const { default: EntryPage } = await import('src/pages/EntryPage.vue');

function detail(overrides: Record<string, unknown>) {
  return {
    data: {
      id: 42,
      kind: 'video',
      status: 'ok',
      root: 'library',
      path: 'library/clip.mkv',
      fileName: 'clip.mkv',
      ext: 'mkv',
      mime: 'video/x-matroska',
      sizeBytes: 123_456,
      width: 640,
      height: 360,
      durationMs: 6000,
      importedAt: '2026-11-08T10:00:00Z',
      updatedAt: '2026-11-08T10:00:00Z',
      fileUrl: '/api/entries/42/file?v=1',
      thumbUrl: '/api/entries/42/thumb?v=1',
      ...overrides,
    },
  };
}

/** The concept list the shared GET /api/concepts mock returns (set per test). */
let conceptList: unknown[] = [];

/** The pool list the shared GET /api/pools mock returns (set per test). */
let poolList: unknown[] = [];

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

/** q-menu teleports to document.body — query the open menu's content by testid. */
function menuItem(testid: string): DOMWrapper<Element> {
  const el = document.querySelector(`.q-menu [data-testid="${testid}"]`);
  expect(el, `menu item "${testid}" should be visible`).not.toBeNull();
  return new DOMWrapper(el as Element);
}

function mountPage() {
  return mount(EntryPage, {
    global: {
      plugins: [Quasar, createPinia()],
    },
  });
}

describe('EntryPage', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiPost.mockReset();
    conceptList = [];
    poolList = [];
    routeState.params = { id: '42' };
    push.mockReset();
    // neighbors fallback (no in-memory list in the test)
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/entries/42') {
        return Promise.resolve(detail({}));
      }
      if (url === '/api/entries/43') {
        return Promise.resolve({ data: { ...detail({}).data, id: 43 } }); // the notice test navigates here
      }
      if (url === '/api/concepts') {
        return Promise.resolve({ data: conceptList });
      }
      if (url === '/api/pools') {
        return Promise.resolve({ data: poolList });
      }
      if (url.includes('/neighbors')) {
        return Promise.resolve({ data: { prevId: 41, nextId: 43 } });
      }
      if (url.includes('/related')) {
        return Promise.resolve({ data: { items: [] } });
      }
      if (url === '/api/imports/last') {
        return Promise.resolve({ data: { run: null } });
      }
      throw new Error('unexpected url ' + url);
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    document.querySelectorAll('.q-menu').forEach((el) => el.remove()); // q-menu teleports to body
  });

  it('renders the fallback card for a non-browser format (mkv)', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.text()).toContain('may not play');
    expect(wrapper.find('video').exists()).toBe(false);
    expect(wrapper.find('a[download]').exists()).toBe(true);
  });

  it('places the autotag action below the sidebar content (consistent with the entries list)', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const btn = wrapper.find('[data-testid="autotag-open"]');
    expect(btn.exists()).toBe(true);
    const aside = wrapper.find('.entries-sidebar');
    const kids = Array.from(aside.element.children);
    const btnWrapper = btn.element.closest('.autotag-row');
    expect(btnWrapper).not.toBeNull();
    const rowIdx = kids.indexOf(btnWrapper as Element);
    const sideBarIdx = kids.findIndex((el) => el.querySelector('[data-testid="sidebar-search"]'));
    expect(rowIdx).toBeGreaterThan(sideBarIdx);
    expect(btn.classes().some((c) => c.includes('dense') || c.includes('q-btn--dense'))).toBe(true);
  });

  it('renders a video element for mp4', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/entries/42') {
        return Promise.resolve(detail({ mime: 'video/mp4', ext: 'mp4', fileName: 'clip.mp4' }));
      }
      if (url === '/api/imports/last') {
        return Promise.resolve({ data: { run: null } });
      }
      if (url.includes('/related')) {
        return Promise.resolve({ data: { items: [] } });
      }
      return Promise.resolve({ data: { prevId: 41, nextId: 43 } });
    });
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.find('video').exists()).toBe(true);
  });

  it('renders an image element for image kinds and shows metadata', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/entries/42') {
        return Promise.resolve(detail({
          kind: 'image',
          mime: 'image/png',
          ext: 'png',
          fileName: 'duck.png',
          path: 'library/duck.png',
        }));
      }
      if (url === '/api/imports/last') {
        return Promise.resolve({ data: { run: null } });
      }
      if (url.includes('/related')) {
        return Promise.resolve({ data: { items: [] } });
      }
      return Promise.resolve({ data: { prevId: 41, nextId: 43 } });
    });
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.find('img.viewer').exists()).toBe(true);
    expect(wrapper.text()).toContain('library/duck.png');
    expect(wrapper.text()).toContain('640×360'); // dimensions from metadata panel
  });

  it('prev/next navigate preserving q and kind', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const next = wrapper.find('[data-testid="next-btn"]');
    expect(next.exists()).toBe(true);
    await next.trigger('click');
    expect(push).toHaveBeenCalledWith({ path: '/entries/43', query: { q: 'duck', kind: 'image' } });
    await wrapper.find('[data-testid="prev-btn"]').trigger('click');
    expect(push).toHaveBeenCalledWith({ path: '/entries/41', query: { q: 'duck', kind: 'image' } });
  });

  /** A minimal pool tile — the reading flow only needs the name for the header.
   *  `overrides`: e.g. `{ member: true }` — the ?entryId= variant's additive flag. */
  const POOL = (id: number, name: string, overrides: Record<string, unknown> = {}) => ({
    id,
    name,
    description: null,
    entryCount: 17,
    updatedAt: '2026-11-12T10:00:00Z',
    origin: null,
    tags: [],
    cover: null,
    ...overrides,
  });

  /** The pool-mode api mock: detail, pool detail, pool-scoped neighbors, strip chrome.
   *  Tests set `routeState.query` themselves (the arrival shape under test). */
  function mockPoolReading(poolId: number, name: string, neighbors: object) {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/entries/42') {
        return Promise.resolve(detail({}));
      }
      if (url === `/api/pools/${poolId}`) {
        return Promise.resolve({ data: { pool: POOL(poolId, name), members: [] } });
      }
      if (url.includes('/neighbors')) {
        return Promise.resolve({ data: neighbors });
      }
      if (url.includes('/related')) {
        return Promise.resolve({ data: { items: [] } });
      }
      if (url === '/api/imports/last') {
        return Promise.resolve({ data: { run: null } });
      }
      throw new Error('unexpected url ' + url);
    });
  }

  it('with ?pool= the neighbors call is pool-scoped and the header shows the reading position', async () => {
    routeState.query = { q: 'duck', kind: 'image', pool: '7' };
    mockPoolReading(7, 'chapter-1', { prevId: 41, nextId: 43, position: 3, total: 17 });
    try {
      const wrapper = mountPage();
      await flushPromises();
      // the flatten IS the reading order (spec §3.9) — q/kind are not sent there
      expect(apiGet).toHaveBeenCalledWith('/api/entries/42/neighbors', { params: { pool: '7' } });
      const header = wrapper.find('[data-testid="pool-reading-header"]');
      expect(header.exists()).toBe(true);
      expect(header.text()).toBe('page 3 of 17 · chapter-1');
    } finally {
      routeState.query = { q: 'duck', kind: 'image' };
    }
  });

  it('prev/next preserve ?pool= across entries (reading flow)', async () => {
    routeState.query = { pool: '7' }; // the shape PoolPage's member links produce
    mockPoolReading(7, 'chapter-1', { prevId: 41, nextId: 43, position: 3, total: 17 });
    try {
      const wrapper = mountPage();
      await flushPromises();
      await wrapper.find('[data-testid="next-btn"]').trigger('click');
      expect(push).toHaveBeenCalledWith({ path: '/entries/43', query: { pool: '7' } });
      await wrapper.find('[data-testid="prev-btn"]').trigger('click');
      expect(push).toHaveBeenCalledWith({ path: '/entries/41', query: { pool: '7' } });
    } finally {
      routeState.query = { q: 'duck', kind: 'image' };
    }
  });

  it('without ?pool= there is no reading header and the neighbors call is unchanged', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(apiGet).toHaveBeenCalledWith('/api/entries/42/neighbors', {
      params: { q: 'duck', kind: 'image' },
    });
    expect(apiGet.mock.calls.some(([url]) => String(url).includes('/api/pools/'))).toBe(false);
    expect(wrapper.find('[data-testid="pool-reading-header"]').exists()).toBe(false);
  });

  it('a pool that 404s (unknown or not containing the entry) degrades to the plain entry', async () => {
    routeState.query = { pool: '999' };
    mockPoolReading(999, 'ghost', {});
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/entries/42') {
        return Promise.resolve(detail({}));
      }
      if (url === '/api/pools/999') {
        return Promise.reject(problemError(404, 'pool 999 does not exist'));
      }
      if (url.includes('/neighbors')) {
        return Promise.reject(problemError(404, 'entry 42 is not contained in pool 999'));
      }
      if (url.includes('/related')) {
        return Promise.resolve({ data: { items: [] } });
      }
      if (url === '/api/imports/last') {
        return Promise.resolve({ data: { run: null } });
      }
      throw new Error('unexpected url ' + url);
    });
    try {
      const wrapper = mountPage();
      await flushPromises();
      // the pool fetch was attempted, its 404 swallowed — the entry still renders
      expect(apiGet).toHaveBeenCalledWith('/api/pools/999', undefined);
      expect(wrapper.text()).toContain('may not play');
      expect(wrapper.find('[data-testid="pool-reading-header"]').exists()).toBe(false);
    } finally {
      routeState.query = { q: 'duck', kind: 'image' };
    }
  });

  it('does not fetch concepts until the dropdown opens, then lists them', async () => {
    conceptList = [CONCEPT(1, 'duck'), CONCEPT(2, 'goose')];
    const wrapper = mountPage();
    await flushPromises();
    expect(apiGet).not.toHaveBeenCalledWith('/api/concepts'); // lazy — nothing on page mount

    await wrapper.find('[data-testid="add-to-concept"]').trigger('click');
    await flushPromises();
    expect(apiGet).toHaveBeenCalledWith('/api/concepts', undefined);

    expect(menuItem('concept-option-1').text()).toContain('duck');
    expect(menuItem('concept-option-2').text()).toContain('goose');
    expect(document.querySelector('[data-testid="concept-option-new"]')).not.toBeNull();
  });

  it('lists each concept with its example-count hint (spec §4.3)', async () => {
    conceptList = [CONCEPT(1, 'duck', { exampleCount: 12 }), CONCEPT(2, 'goose', { exampleCount: 0 })];
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="add-to-concept"]').trigger('click');
    await flushPromises();
    expect(menuItem('concept-option-1').text()).toContain('+12 examples');
    expect(menuItem('concept-option-2').text()).not.toContain('examples');
  });

  it('clears the previous entry\'s concept notice when navigating to another entry', async () => {
    conceptList = [CONCEPT(1, 'duck')];
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="add-to-concept"]').trigger('click');
    await flushPromises();
    await menuItem('concept-option-1').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="add-to-concept-notice"]').exists()).toBe(true);

    try {
      // route param changes → the page reloads and the stale "added to duck" must not linger
      routeState.params = { id: '43' };
      await flushPromises();
      expect(wrapper.find('[data-testid="add-to-concept-notice"]').exists()).toBe(false);
    } finally {
      routeState.params = { id: '42' }; // restore for the other tests sharing the mock
    }
  });

  it('picking a concept adds the current entry as an example and shows the notice', async () => {
    conceptList = [CONCEPT(1, 'duck')];
    apiPost.mockResolvedValueOnce({ data: { added: 1 } });
    const wrapper = mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="add-to-concept"]').trigger('click');
    await flushPromises();
    await menuItem('concept-option-1').trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenCalledTimes(1);
    expect(apiPost).toHaveBeenCalledWith('/api/concepts/1/examples', { entryIds: [42] });
    const notice = wrapper.find('[data-testid="add-to-concept-notice"]');
    expect(notice.exists()).toBe(true);
    expect(notice.text()).toContain('added to duck');
  });

  it('"new concept…" creates the concept, then adds the example', async () => {
    conceptList = [CONCEPT(1, 'duck')];
    apiPost
      .mockResolvedValueOnce({ data: CONCEPT(9, 'geese') })
      .mockResolvedValueOnce({ data: { added: 1 } });
    const wrapper = mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="add-to-concept"]').trigger('click');
    await flushPromises();
    await menuItem('concept-option-new').trigger('click');
    await flushPromises();

    await menuItem('new-concept-name').setValue('geese');
    await menuItem('new-concept-phrase').setValue('a goose is a bird');
    await menuItem('new-concept-create').trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenNthCalledWith(1, '/api/concepts', { name: 'geese', phrase: 'a goose is a bird' });
    expect(apiPost).toHaveBeenNthCalledWith(2, '/api/concepts/9/examples', { entryIds: [42] });
    expect(wrapper.find('[data-testid="add-to-concept-notice"]').text()).toContain('added to geese');
  });

  it('a 4xx failure on add renders the server detail in the notice', async () => {
    conceptList = [CONCEPT(1, 'duck')];
    apiPost.mockRejectedValueOnce(problemError(404, 'concept no longer exists'));
    const wrapper = mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="add-to-concept"]').trigger('click');
    await flushPromises();
    await menuItem('concept-option-1').trigger('click');
    await flushPromises();

    const notice = wrapper.find('[data-testid="add-to-concept-notice"]');
    expect(notice.exists()).toBe(true);
    expect(notice.text()).toContain('concept no longer exists');
  });

  it('cancel in the new-concept form returns to the list without creating', async () => {
    conceptList = [CONCEPT(1, 'duck')];
    const wrapper = mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="add-to-concept"]').trigger('click');
    await flushPromises();
    await menuItem('concept-option-new').trigger('click');
    await flushPromises();
    expect(document.querySelector('[data-testid="new-concept-name"]')).not.toBeNull();

    await menuItem('new-concept-cancel').trigger('click');
    await flushPromises();

    expect(document.querySelector('[data-testid="new-concept-name"]')).toBeNull();
    expect(menuItem('concept-option-1').text()).toContain('duck');
    expect(apiPost).not.toHaveBeenCalled();
  });

  // ---- add-to-pool dropdown (spec §4.3) ----

  it('does not fetch pools until the dropdown opens, then lists them with the entryId param', async () => {
    poolList = [POOL(3, 'chapter-1'), POOL(4, '02 Finale')];
    const wrapper = mountPage();
    await flushPromises();
    expect(apiGet).not.toHaveBeenCalledWith('/api/pools', { params: { entryId: 42 } }); // lazy

    await wrapper.find('[data-testid="add-to-pool"]').trigger('click');
    await flushPromises();
    expect(apiGet).toHaveBeenCalledWith('/api/pools', { params: { entryId: 42 } });

    expect(menuItem('pool-option-3').text()).toContain('chapter-1');
    expect(menuItem('pool-option-4').text()).toContain('02 Finale');
    expect(document.querySelector('[data-testid="pool-option-new"]')).not.toBeNull();
    expect(document.querySelector('[data-testid="pool-option-folder"]')).not.toBeNull();
  });

  it('marks pools that already contain the entry with a member ✓ (from the entryId flags)', async () => {
    poolList = [POOL(3, 'chapter-1', { member: true }), POOL(4, '02 Finale', { member: false })];
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="add-to-pool"]').trigger('click');
    await flushPromises();
    expect(menuItem('pool-option-3').text()).toContain('✓');
    expect(menuItem('pool-option-4').text()).not.toContain('✓');
  });

  it('picking a pool adds the current entry as a member and shows the notice', async () => {
    poolList = [POOL(3, 'chapter-1')];
    apiPost.mockResolvedValueOnce({ data: { added: 1 } });
    const wrapper = mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="add-to-pool"]').trigger('click');
    await flushPromises();
    await menuItem('pool-option-3').trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenCalledTimes(1);
    expect(apiPost).toHaveBeenCalledWith('/api/pools/3/members', { entryIds: [42] });
    const notice = wrapper.find('[data-testid="add-to-pool-notice"]');
    expect(notice.exists()).toBe(true);
    expect(notice.text()).toContain('added to chapter-1');
  });

  it('"new pool…" creates the pool, then adds the entry as a member', async () => {
    poolList = [POOL(3, 'chapter-1')];
    apiPost
      .mockResolvedValueOnce({ data: POOL(9, 'watch later') })
      .mockResolvedValueOnce({ data: { added: 1 } });
    const wrapper = mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="add-to-pool"]').trigger('click');
    await flushPromises();
    await menuItem('pool-option-new').trigger('click');
    await flushPromises();

    await menuItem('new-pool-name').setValue('watch later');
    await menuItem('new-pool-create').trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenNthCalledWith(1, '/api/pools', { name: 'watch later' });
    expect(apiPost).toHaveBeenNthCalledWith(2, '/api/pools/9/members', { entryIds: [42] });
    expect(wrapper.find('[data-testid="add-to-pool-notice"]').text()).toContain('added to watch later');
  });

  it('"pool from this folder" seeds from the entry\'s directory (root top level → dirPath \'\')', async () => {
    poolList = [];
    apiPost
      .mockResolvedValueOnce({ data: POOL(9, 'library') })
      .mockResolvedValueOnce({ data: { added: 1 } });
    const wrapper = mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="add-to-pool"]').trigger('click');
    await flushPromises();
    await menuItem('pool-option-folder').trigger('click');
    await flushPromises();

    // the default entry is root 'library' / path 'library/clip.mkv' — its directory IS
    // the root → dirPath '' (root top level); no name → server defaults + auto-dedupes
    expect(apiPost).toHaveBeenNthCalledWith(1, '/api/pools', { rootName: 'library', dirPath: '' });
    expect(apiPost).toHaveBeenNthCalledWith(2, '/api/pools/9/members', { entryIds: [42] });
    expect(wrapper.find('[data-testid="add-to-pool-notice"]').exists()).toBe(true);
  });

  it('"pool from this folder" strips the root prefix for a nested entry', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/entries/42') {
        return Promise.resolve(detail({ path: 'library/season 1/clip.mkv' }));
      }
      if (url === '/api/pools') {
        return Promise.resolve({ data: [] });
      }
      if (url.includes('/neighbors')) {
        return Promise.resolve({ data: { prevId: 41, nextId: 43 } });
      }
      if (url.includes('/related')) {
        return Promise.resolve({ data: { items: [] } });
      }
      if (url === '/api/imports/last') {
        return Promise.resolve({ data: { run: null } });
      }
      throw new Error('unexpected url ' + url);
    });
    apiPost
      .mockResolvedValueOnce({ data: POOL(9, 'season 1') })
      .mockResolvedValueOnce({ data: { added: 1 } });
    const wrapper = mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="add-to-pool"]').trigger('click');
    await flushPromises();
    await menuItem('pool-option-folder').trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenNthCalledWith(1, '/api/pools', { rootName: 'library', dirPath: 'season 1' });
  });

  it('a 4xx failure on add renders the server detail in the notice with error styling', async () => {
    poolList = [POOL(3, 'chapter-1')];
    apiPost.mockRejectedValueOnce(problemError(404, 'pool no longer exists'));
    const wrapper = mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="add-to-pool"]').trigger('click');
    await flushPromises();
    await menuItem('pool-option-3').trigger('click');
    await flushPromises();

    const notice = wrapper.find('[data-testid="add-to-pool-notice"]');
    expect(notice.exists()).toBe(true);
    expect(notice.text()).toContain('pool no longer exists');
    expect(notice.classes()).toContain('text-negative');
  });

  it('clears the previous entry\'s pool notice when navigating to another entry', async () => {
    poolList = [POOL(3, 'chapter-1')];
    apiPost.mockResolvedValueOnce({ data: { added: 1 } });
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="add-to-pool"]').trigger('click');
    await flushPromises();
    await menuItem('pool-option-3').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="add-to-pool-notice"]').exists()).toBe(true);

    try {
      // route param changes → the page reloads and the stale "added to" must not linger
      routeState.params = { id: '43' };
      await flushPromises();
      expect(wrapper.find('[data-testid="add-to-pool-notice"]').exists()).toBe(false);
    } finally {
      routeState.params = { id: '42' }; // restore for the other tests sharing the mock
    }
  });
});
