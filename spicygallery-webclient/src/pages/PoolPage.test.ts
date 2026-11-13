import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';
import { createMemoryHistory, createRouter } from 'vue-router';
import type { GridItem } from 'src/stores/entries';
import type { AutocompleteDto, TagDto } from 'src/types/tags';
import type { PoolDto, PoolDetail, PoolMember } from 'src/types/pools';

const apiGet = vi.fn();
const apiPost = vi.fn();
const apiPatch = vi.fn();
const apiDelete = vi.fn();
vi.mock('src/boot/api', () => ({
  api: {
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
    patch: (...args: unknown[]) => apiPatch(...args),
    delete: (...args: unknown[]) => apiDelete(...args),
  },
}));

const { default: PoolPage } = await import('src/pages/PoolPage.vue');

function entry(id: number, fileName: string, overrides: Partial<GridItem> = {}): GridItem {
  return {
    id,
    kind: 'image',
    fileName,
    width: 4,
    height: 3,
    durationMs: null,
    thumbUrl: '/api/entries/' + id + '/thumb',
    ...overrides,
  };
}

/** A stored pool tile — mirrors PoolService.PoolDto. */
const POOL: PoolDto = {
  id: 3,
  name: 'Saga',
  description: 'dragons',
  entryCount: 30,
  updatedAt: '2026-11-12T10:00:00Z',
  origin: null,
  tags: [],
  cover: null,
};

/** A nested sub-pool as the detail rows embed it: SUMMARY (name/entryCount only). */
function childPool(id: number, name: string, entryCount: number): PoolDto {
  return {
    id,
    name,
    description: null,
    entryCount,
    updatedAt: null,
    origin: null,
    tags: [],
    cover: null,
  };
}

/** §3.5: every stored pool carries the `pool_type:pool` system tag (fixed chip). */
const SYSTEM_TAG: TagDto = { id: 1, name: 'pool', category: 'pool_type', postCount: 30 };
const DRAGON_TAG: TagDto = { id: 2, name: 'dragon', category: 'general', postCount: 12 };

/** The in-memory server behind the api mocks: mutations update it the way the
 *  real endpoints do — order re-permutes the rows and recomputes positions,
 *  remove drops the row, patch renames; the detail serves the rows fresh. */
const db = {
  pool: { ...POOL },
  members: [] as PoolMember[],
  /** GET /api/pools payload — the add modal's Pools mode enumerates it. */
  pools: [] as PoolDto[],
  /** GET /api/pools/{id} answers for OTHER pools (the add modal's BFS walks these). */
  childDetails: {} as Record<number, PoolDetail>,
  /** GET /api/entries payload — the add modal's Entries mode searches it. */
  entries: [] as GridItem[],
  /** GET /api/tags/autocomplete payload — the pool tag editor's suggestions. */
  autocomplete: [] as AutocompleteDto[],
};

function detailDto() {
  return { pool: db.pool, members: db.members };
}

function resetDb() {
  db.pool = { ...POOL };
  db.pools = [];
  db.childDetails = {};
  db.entries = [];
  db.autocomplete = [];
  // mixed members in position order: two entries sandwiching a sub-pool
  db.members = [
    { memberId: 21, position: 1, entry: entry(10, 'a.png'), pool: null },
    { memberId: 22, position: 2, entry: null, pool: childPool(5, 'Lakes', 7) },
    {
      memberId: 23,
      position: 3,
      entry: entry(11, 'b.mp4', { kind: 'video', durationMs: 6500 }),
      pool: null,
    },
  ];
}

/** Axios-shaped rejection carrying an RFC-7807 problem body (the store unwraps `detail`). */
function problemError(status: number, detail: string) {
  return Object.assign(new Error('Request failed with status code ' + status), {
    isAxiosError: true,
    response: { status, data: { title: 'Error', status, detail } },
  });
}

function callCount(fn: ReturnType<typeof vi.fn>, url: string): number {
  return fn.mock.calls.filter((args) => args[0] === url).length;
}

/** URL-routed api mocks backed by db — installed fresh before every test. */
function installApi() {
  apiGet.mockImplementation((url: string) => {
    if (url === '/api/tags/autocomplete') {
      return Promise.resolve({ data: db.autocomplete });
    }
    if (url === '/api/entries') {
      return Promise.resolve({ data: { items: db.entries, nextCursor: null } });
    }
    if (url === '/api/pools') {
      return Promise.resolve({ data: db.pools });
    }
    const match = url.match(/^\/api\/pools\/(\d+)$/);
    if (match) {
      const id = Number(match[1]);
      if (id === db.pool.id) {
        return Promise.resolve({ data: detailDto() });
      }
      if (db.childDetails[id]) {
        return Promise.resolve({ data: db.childDetails[id] });
      }
      return Promise.reject(problemError(404, 'no pool ' + id));
    }
    return Promise.reject(new Error('unexpected GET ' + url));
  });
  apiPost.mockImplementation(
    (url: string, body?: { orderedMemberIds?: number[]; entryIds?: number[]; poolIds?: number[] }) => {
      if (url === '/api/pools/3/order') {
        const byId = new Map(db.members.map((m) => [m.memberId, m]));
        db.members = (body?.orderedMemberIds ?? []).map((id, i) => ({
          ...byId.get(id)!,
          position: i + 1, // the endpoint recomputes the 1..N sequence
        }));
        return Promise.resolve({ data: detailDto() });
      }
      if (url === '/api/pools/3/members') {
        const added = (body?.entryIds?.length ?? 0) + (body?.poolIds?.length ?? 0);
        return Promise.resolve({ data: { added } });
      }
      return Promise.reject(new Error('unexpected POST ' + url));
    },
  );
  apiPatch.mockImplementation((url: string, body?: { name?: string; tags?: string[] }) => {
    if (url === '/api/pools/3') {
      if (body?.name) {
        db.pool = { ...db.pool, name: body.name };
      }
      return Promise.resolve({ data: db.pool });
    }
    if (url === '/api/pools/3/tags') {
      // the replace-set endpoint: the server re-adds pool_type:pool when missing
      // and answers the pool's fresh tag rows — the mock mirrors that wire shape
      const fresh: TagDto[] = (body?.tags ?? []).map((name, i) => ({
        id: 100 + i,
        name,
        category: name === 'pool' ? 'pool_type' : 'general',
        postCount: 1,
      }));
      db.pool = { ...db.pool, tags: fresh };
      return Promise.resolve({ data: fresh });
    }
    return Promise.reject(new Error('unexpected PATCH ' + url));
  });
  apiDelete.mockImplementation((url: string) => {
    const member = url.match(/^\/api\/pools\/3\/members\/(\d+)$/);
    if (member) {
      db.members = db.members.filter((m) => m.memberId !== Number(member[1]));
      return Promise.resolve({ data: '' });
    }
    return Promise.reject(new Error('unexpected DELETE ' + url));
  });
}

/** The route's `props: true` passes `id`; the direct mount passes it the same
 *  way (the router plugin is installed so RouterLinks resolve for real). */
async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/entries/:id', component: { template: '<div />' }, props: true },
      { path: '/pools', component: { template: '<div />' } },
      { path: '/pools/:id', component: { template: '<div />' }, props: true },
    ],
  });
  await router.push('/pools/3');
  await router.isReady();
  return mount(PoolPage, {
    props: { id: '3' },
    global: { plugins: [Quasar, createPinia(), router] },
  });
}

/** q-dialog teleports to document.body — VTU's wrapper can't see the add modal
 *  (house pattern from PoolsPage.test.ts). */
function modalEl(testid: string): HTMLElement | null {
  return document.querySelector(`[data-testid="${testid}"]`);
}

/** Typing into the teleported dialog: the `change` event flushes QInput's pending
 *  debounced emission on the spot (PoolsPage.test.ts), the small timeout is the
 *  TagEditor.test.ts debounce-safety net. */
async function typeInModal(testid: string, value: string) {
  const el = modalEl(testid) as HTMLInputElement;
  el.value = value;
  el.dispatchEvent(new Event('input'));
  el.dispatchEvent(new Event('change'));
  await flushPromises();
  await new Promise((r) => setTimeout(r, 20));
}

async function clickModal(testid: string) {
  (modalEl(testid) as HTMLElement).click();
  await flushPromises();
}

describe('PoolPage', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiPost.mockReset();
    apiPatch.mockReset();
    apiDelete.mockReset();
    resetDb();
    installApi();
  });

  afterEach(() => {
    document.querySelectorAll('[data-testid="pool-add-modal"]').forEach((el) => el.remove()); // q-dialog teleports to body
  });

  it('renders the header: back link, inline rename, description, flattened count', async () => {
    const wrapper = await mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid="pool-back-link"]').attributes('href')).toBe('/pools');
    const rename = wrapper.find('[data-testid="pool-rename"]');
    expect(rename.exists()).toBe(true);
    expect((rename.element as HTMLInputElement).value).toBe('Saga');
    expect(wrapper.find('[data-testid="pool-count"]').text()).toContain('30');
    expect(wrapper.text()).toContain('dragons'); // description
  });

  it('renders mixed members in position order with position badges', async () => {
    const wrapper = await mountPage();
    await flushPromises();

    const badges = wrapper
      .findAll('[data-testid^="pool-member-position-"]')
      .map((el) => el.attributes('data-testid'));
    expect(badges).toEqual(['pool-member-position-21', 'pool-member-position-22', 'pool-member-position-23']);
    expect(wrapper.findAll('[data-testid^="pool-member-position-"]').map((el) => el.text())).toEqual([
      '1',
      '2',
      '3',
    ]);

    // entry members render the thumbnail tile; the sub-pool member the placeholder
    const tile21 = wrapper.find('[data-testid="pool-member-21"]');
    expect(tile21.find('img.tile-thumb').attributes('src')).toBe('/api/entries/10/thumb');
    const tile22 = wrapper.find('[data-testid="pool-member-22"]');
    expect(tile22.find('img.tile-thumb').exists()).toBe(false);
    expect(tile22.text()).toContain('Lakes');
    const tile23 = wrapper.find('[data-testid="pool-member-23"]');
    expect(tile23.text()).toContain('7s'); // video duration badge

    // the sub-pool badge: icon + flattened count
    const badge = tile22.find('[data-testid="pool-badge-22"]');
    expect(badge.exists()).toBe(true);
    expect(badge.text()).toContain('7');
    expect(badge.find('.q-icon').exists()).toBe(true);
  });

  it('entry tiles link to the reading view carrying the pool param', async () => {
    const wrapper = await mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid="pool-member-link-21"]').attributes('href')).toBe('/entries/10?pool=3');
    expect(wrapper.find('[data-testid="pool-member-link-23"]').attributes('href')).toBe('/entries/11?pool=3');
  });

  it('sub-pool tiles link to the child pool page', async () => {
    const wrapper = await mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid="pool-member-link-22"]').attributes('href')).toBe('/pools/5');
  });

  it('move up/down swaps the neighbors and posts the full member-id permutation, then refetches', async () => {
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="pool-member-down-21"]').trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenCalledWith('/api/pools/3/order', { orderedMemberIds: [22, 21, 23] });
    expect(callCount(apiGet, '/api/pools/3')).toBe(2); // every mutation refetches
    // the grid reflects the swapped order with recomputed positions
    expect(
      wrapper
        .findAll('[data-testid^="pool-member-position-"]')
        .map((el) => el.attributes('data-testid')),
    ).toEqual(['pool-member-position-22', 'pool-member-position-21', 'pool-member-position-23']);
    expect(wrapper.findAll('[data-testid^="pool-member-position-"]').map((el) => el.text())).toEqual([
      '1',
      '2',
      '3',
    ]);

    await wrapper.find('[data-testid="pool-member-up-23"]').trigger('click');
    await flushPromises();
    expect(apiPost).toHaveBeenCalledWith('/api/pools/3/order', { orderedMemberIds: [22, 23, 21] });
  });

  it('move up/down are disabled at the grid edges and an edge click posts nothing', async () => {
    const wrapper = await mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid="pool-member-up-21"]').attributes('disabled')).toBeDefined();
    expect(wrapper.find('[data-testid="pool-member-down-21"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-testid="pool-member-up-23"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-testid="pool-member-down-23"]').attributes('disabled')).toBeDefined();

    await wrapper.find('[data-testid="pool-member-up-21"]').trigger('click');
    await flushPromises();
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('edge-disable follows the grid ROW index, not gapped wire positions', async () => {
    // a legacy/externally patched pool can carry gaps in the 1..N sequence —
    // the buttons must disable off the row's array index, not its position value
    db.members = [
      { memberId: 31, position: 2, entry: entry(10, 'a.png'), pool: null },
      { memberId: 32, position: 3, entry: null, pool: childPool(5, 'Lakes', 7) },
      { memberId: 33, position: 5, entry: entry(11, 'b.png'), pool: null },
    ];
    const wrapper = await mountPage();
    await flushPromises();

    // first row: up disabled; last row: down disabled — interior rows keep both
    expect(wrapper.find('[data-testid="pool-member-up-31"]').attributes('disabled')).toBeDefined();
    expect(wrapper.find('[data-testid="pool-member-down-31"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-testid="pool-member-up-32"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-testid="pool-member-down-32"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-testid="pool-member-up-33"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-testid="pool-member-down-33"]').attributes('disabled')).toBeDefined();
  });

  it('remove is a two-click confirm that deletes the member row and refetches', async () => {
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="pool-member-remove-22"]').trigger('click');
    await flushPromises();
    expect(apiDelete).not.toHaveBeenCalled(); // first click only arms the confirm
    expect(wrapper.find('[data-testid="pool-member-remove-22"]').text()).toContain('confirm');

    await wrapper.find('[data-testid="pool-member-remove-22"]').trigger('click');
    await flushPromises();
    expect(apiDelete).toHaveBeenCalledWith('/api/pools/3/members/22');
    expect(callCount(apiGet, '/api/pools/3')).toBe(2); // every mutation refetches
    expect(wrapper.find('[data-testid="pool-member-22"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="pool-member-21"]').exists()).toBe(true);
  });

  it('rename patches the name and refetches; an unchanged draft is a no-op', async () => {
    const wrapper = await mountPage();
    await flushPromises();

    // enter on the untouched draft never round-trips
    await wrapper.find('[data-testid="pool-rename"]').trigger('keyup.enter');
    await flushPromises();
    expect(apiPatch).not.toHaveBeenCalled();

    await wrapper.find('[data-testid="pool-rename"]').setValue('Wyvern');
    await wrapper.find('[data-testid="pool-rename"]').trigger('keyup.enter');
    await flushPromises();

    expect(apiPatch).toHaveBeenCalledWith('/api/pools/3', { name: 'Wyvern' });
    expect(callCount(apiGet, '/api/pools/3')).toBe(2); // every mutation refetches
    // the editor shows the server-truth name after the refetch (input values are
    // not part of text() — read the element)
    expect((wrapper.find('[data-testid="pool-rename"]').element as HTMLInputElement).value).toBe('Wyvern');
  });

  it('an unknown pool id (404) shows the not-found state with a back link', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/pools/3') {
        // the server's contract: unknown pool ids are a 404 problem-detail —
        // matched by STATUS only, the detail wording is irrelevant to the page
        return Promise.reject(problemError(404, 'no pool 3'));
      }
      return Promise.reject(new Error('unexpected GET ' + url));
    });
    let wrapper = await mountPage();
    await flushPromises();

    const nf = wrapper.find('[data-testid="pool-not-found"]');
    expect(nf.exists()).toBe(true);
    expect(nf.text()).toContain('pool not found');
    expect(wrapper.find('[data-testid="pool-back-link"]').attributes('href')).toBe('/pools');
    expect(wrapper.find('[data-testid="pool-rename"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="pool-member-21"]').exists()).toBe(false);

    // a plain 404 without a problem body passes through the store raw — the
    // proxy/gateway shape — and is detected by the same status check
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/pools/3') {
        return Promise.reject(
          Object.assign(new Error('Request failed with status code 404'), {
            isAxiosError: true,
            response: { status: 404, data: '' },
          }),
        );
      }
      return Promise.reject(new Error('unexpected GET ' + url));
    });
    wrapper = await mountPage();
    await flushPromises();
    expect(wrapper.find('[data-testid="pool-not-found"]').exists()).toBe(true);
  });

  it('a non-404 load failure is an error state, not the not-found state', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/pools/3') {
        return Promise.reject(problemError(500, 'pool lookup exploded'));
      }
      return Promise.reject(new Error('unexpected GET ' + url));
    });
    const wrapper = await mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid="pool-not-found"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="pool-error"]').text()).toContain('pool lookup exploded');
  });

  it('reloads when the route param changes without remounting (props.id watcher)', async () => {
    const wrapper = await mountPage();
    await flushPromises();
    expect(wrapper.find('[data-testid="pool-member-21"]').exists()).toBe(true);

    // param-only navigation (browser back/forward, pool→pool links): the same
    // component instance receives a new id — it must fetch THAT pool, not keep
    // showing the old one
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/pools/7') {
        return Promise.resolve({
          data: { pool: { ...db.pool, id: 7, name: 'Wests' }, members: [] },
        });
      }
      return Promise.reject(new Error('unexpected GET ' + url));
    });
    await wrapper.setProps({ id: '7' });
    await flushPromises();

    expect(apiGet).toHaveBeenCalledWith('/api/pools/7', undefined);
    // the editor shows the freshly loaded pool's name (input values are not part
    // of text() — read the element)
    expect((wrapper.find('[data-testid="pool-rename"]').element as HTMLInputElement).value).toBe('Wests');
    expect(wrapper.find('[data-testid="pool-member-21"]').exists()).toBe(false);
  });
});

describe('PoolPage pool tag editor', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiPost.mockReset();
    apiPatch.mockReset();
    apiDelete.mockReset();
    resetDb();
    installApi();
  });

  afterEach(() => {
    document.querySelectorAll('[data-testid="pool-add-modal"]').forEach((el) => el.remove());
  });

  it('renders the pool tags grouped by category with the system chip fixed (non-removable)', async () => {
    db.pool = { ...POOL, tags: [SYSTEM_TAG, DRAGON_TAG] };
    const wrapper = await mountPage();
    await flushPromises();

    const editor = wrapper.find('[data-testid="pool-tags"]');
    expect(editor.exists()).toBe(true);
    // chips grouped by category, pool_type leads (TAG_CATEGORIES order)
    const chips = editor.findAll('.tag-chip');
    expect(chips.map((c) => c.text())).toEqual(['pool', 'dragon']);
    expect(chips[0]!.classes()).toContain('tag-cat-pool_type');
    expect(chips[1]!.classes()).toContain('tag-cat-general');
    // §3.5: pool_type:pool is a system tag — rendered fixed, with NO remove control
    expect(editor.find('[data-testid="pool-tag-remove-pool"]').exists()).toBe(false);
    expect(editor.find('[data-testid="pool-tag-remove-dragon"]').exists()).toBe(true);
  });

  it('autocomplete adds a tag, remove drops one, save PATCHes the replace-set and adopts the response', async () => {
    db.pool = { ...POOL, tags: [SYSTEM_TAG, DRAGON_TAG] };
    db.autocomplete = [{ name: 'blue_dragon', category: 'general', postCount: 5, aliasOf: null }];
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="pool-tag-input"]').setValue('blue');
    await new Promise((r) => setTimeout(r, 20)); // debounce flush
    expect(apiGet).toHaveBeenCalledWith('/api/tags/autocomplete', { params: { q: 'blue' } });

    await wrapper.find('[data-testid="pool-tag-suggestion-blue_dragon"]').trigger('click');
    await wrapper.find('[data-testid="pool-tag-remove-dragon"]').trigger('click');
    await wrapper.find('[data-testid="pool-tag-save"]').trigger('click');
    await flushPromises();

    // the posted replace-set is the editor state: system tag kept, add + remove applied
    expect(apiPatch).toHaveBeenCalledWith('/api/pools/3/tags', { tags: ['pool', 'blue_dragon'] });
    // the editor re-renders from the server's fresh tag rows
    const chips = wrapper.findAll('[data-testid="pool-tags"] .tag-chip').map((c) => c.text());
    expect(chips).toEqual(['pool', 'blue_dragon']);
    expect(wrapper.find('[data-testid="pool-tag-suggestion-blue_dragon"]').exists()).toBe(false);
  });

  it('an unknown query shows the create row; picking it adds the chip and save posts the bare name', async () => {
    db.pool = { ...POOL, tags: [SYSTEM_TAG, DRAGON_TAG] };
    db.autocomplete = []; // no hits — the name does not exist yet
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="pool-tag-input"]').setValue('blue saga');
    await new Promise((r) => setTimeout(r, 20)); // debounce flush
    expect(apiGet).toHaveBeenCalledWith('/api/tags/autocomplete', { params: { q: 'blue saga' } });

    const createRow = wrapper.find('[data-testid="pool-tag-create"]');
    expect(createRow.exists()).toBe(true);
    expect(createRow.text()).toContain("create 'blue_saga'"); // normalized preview

    await createRow.trigger('click');
    expect(wrapper.find('[data-testid="pool-tag-create"]').exists()).toBe(false); // query consumed
    expect(wrapper.findAll('[data-testid="pool-tags"] .tag-chip').map((c) => c.text())).toEqual([
      'pool',
      'dragon',
      'blue_saga',
    ]);

    await wrapper.find('[data-testid="pool-tag-save"]').trigger('click');
    await flushPromises();

    // bare names only — the server creates unknown ones as general (spec §4.2)
    expect(apiPatch).toHaveBeenCalledWith('/api/pools/3/tags', {
      tags: ['pool', 'dragon', 'blue_saga'],
    });
    expect(wrapper.findAll('[data-testid="pool-tags"] .tag-chip').map((c) => c.text())).toEqual([
      'pool',
      'dragon',
      'blue_saga',
    ]);
  });

  it('Enter creates an unknown query, but an exact suggestion match wins the pick', async () => {
    db.pool = { ...POOL, tags: [SYSTEM_TAG, DRAGON_TAG] };
    db.autocomplete = [{ name: 'blue_dragon', category: 'general', postCount: 5, aliasOf: null }];
    const wrapper = await mountPage();
    await flushPromises();

    // an exact suggestion hides the create row and Enter picks it
    await wrapper.find('[data-testid="pool-tag-input"]').setValue('blue_dragon');
    await new Promise((r) => setTimeout(r, 20)); // debounce flush
    expect(wrapper.find('[data-testid="pool-tag-create"]').exists()).toBe(false);
    await wrapper.find('[data-testid="pool-tag-input"]').trigger('keydown.enter');
    expect(wrapper.findAll('[data-testid="pool-tags"] .tag-chip').map((c) => c.text())).toEqual([
      'pool',
      'dragon',
      'blue_dragon',
    ]);

    // an unknown query: Enter creates
    await wrapper.find('[data-testid="pool-tag-input"]').setValue('fire_dragon');
    await new Promise((r) => setTimeout(r, 20));
    await wrapper.find('[data-testid="pool-tag-input"]').trigger('keydown.enter');
    expect(wrapper.findAll('[data-testid="pool-tags"] .tag-chip').map((c) => c.text())).toEqual([
      'pool',
      'dragon',
      'blue_dragon',
      'fire_dragon',
    ]);
  });

  it('Enter normalizes before the exact-suggestion lookup: "blue dragon" picks blue_dragon', async () => {
    // the suggestion matches the NORMALIZED query, not the raw one — Enter must
    // still pick it instead of silently doing nothing
    db.pool = { ...POOL, tags: [SYSTEM_TAG, DRAGON_TAG] };
    db.autocomplete = [{ name: 'blue_dragon', category: 'artist', postCount: 5, aliasOf: null }];
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="pool-tag-input"]').setValue('blue dragon');
    await new Promise((r) => setTimeout(r, 20)); // debounce flush
    expect(wrapper.find('[data-testid="pool-tag-create"]').exists()).toBe(false);
    await wrapper.find('[data-testid="pool-tag-input"]').trigger('keydown.enter');

    // picked, not created and not a silent no-op: the query was consumed, the
    // suggestion row cleared, and the working chip carries the suggestion's
    // category (a create would have added it as general)
    expect((wrapper.find('[data-testid="pool-tag-input"]').element as HTMLInputElement).value).toBe('');
    expect(wrapper.find('[data-testid="pool-tag-suggestion-blue_dragon"]').exists()).toBe(false);
    const chips = wrapper.findAll('[data-testid="pool-tags"] .tag-chip');
    expect(chips.map((c) => c.text())).toEqual(['pool', 'dragon', 'blue_dragon']);
    expect(chips[2]!.classes()).toContain('tag-cat-artist');
  });

  it('the system tag name never shows a create row — the fixed chip cannot be duplicated', async () => {
    db.pool = { ...POOL, tags: [SYSTEM_TAG, DRAGON_TAG] };
    db.autocomplete = [];
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="pool-tag-input"]').setValue('pool');
    await new Promise((r) => setTimeout(r, 20)); // debounce flush

    expect(wrapper.find('[data-testid="pool-tag-create"]').exists()).toBe(false);
    // the fixed system chip is untouched and save still posts exactly the shown set
    expect(wrapper.find('[data-testid="pool-tag-system-pool"]').exists()).toBe(true);
    await wrapper.find('[data-testid="pool-tag-save"]').trigger('click');
    await flushPromises();
    expect(apiPatch).toHaveBeenCalledWith('/api/pools/3/tags', { tags: ['pool', 'dragon'] });
  });
});

describe('PoolPage pool add modal', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiPost.mockReset();
    apiPatch.mockReset();
    apiDelete.mockReset();
    resetDb();
    installApi();
  });

  afterEach(() => {
    document.querySelectorAll('[data-testid="pool-add-modal"]').forEach((el) => el.remove());
  });

  it('opens from pool-add-btn; Entries mode searches entries and multi-selects results', async () => {
    db.entries = [entry(12, 'd1.png'), entry(13, 'd2.mp4', { kind: 'video', durationMs: 1500 })];
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="pool-add-btn"]').trigger('click');
    await flushPromises();
    expect(modalEl('pool-add-modal')).not.toBeNull();

    await typeInModal('pool-add-entry-search', 'dragon');
    expect(apiGet).toHaveBeenCalledWith('/api/entries', { params: { q: 'dragon' } });

    // results render with thumbnails + file names
    const r12 = modalEl('pool-add-result-12');
    expect(r12).not.toBeNull();
    expect(r12!.querySelector('img')?.getAttribute('src')).toBe('/api/entries/12/thumb');
    expect(r12!.textContent).toContain('d1.png');
    expect(modalEl('pool-add-result-13')!.textContent).toContain('d2.mp4');

    // multi-select toggles in click order
    await clickModal('pool-add-result-12');
    await clickModal('pool-add-result-13');
    expect(modalEl('pool-add-result-12')!.className).toContain('selected');
    expect(modalEl('pool-add-result-13')!.className).toContain('selected');
    await clickModal('pool-add-result-12'); // toggle off…
    expect(modalEl('pool-add-result-12')!.className).not.toContain('selected');
    await clickModal('pool-add-result-12'); // …back on → re-appends: order [13, 12]

    await clickModal('pool-add-confirm');
    expect(apiPost).toHaveBeenCalledWith('/api/pools/3/members', { entryIds: [13, 12] });
    expect(callCount(apiGet, '/api/pools/3')).toBe(2); // mount + refetch after add
    expect(modalEl('pool-add-modal')).toBeNull(); // closes after a successful add
  });

  it('Pools mode lists OTHER pools only — self + descendants (incl. grandchild) hidden', async () => {
    // 3 → 5 → 7: the modal must walk TWO child-pool edges (BFS) to hide the grandchild
    db.childDetails = {
      5: {
        pool: childPool(5, 'Lakes', 7),
        members: [{ memberId: 31, position: 1, entry: null, pool: childPool(7, 'Caves', 2) }],
      },
      7: { pool: childPool(7, 'Caves', 2), members: [] },
    };
    db.pools = [db.pool, childPool(5, 'Lakes', 7), childPool(7, 'Caves', 2), childPool(9, 'Wests', 4)];
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="pool-add-btn"]').trigger('click');
    await flushPromises();
    await clickModal('pool-add-mode-pools');

    // client-side BFS over child-pool edges: 3 → 5 → 7
    expect(apiGet).toHaveBeenCalledWith('/api/pools/5', undefined);
    expect(apiGet).toHaveBeenCalledWith('/api/pools/7', undefined);

    const ids = [...document.querySelectorAll('[data-testid^="pool-add-pool-"]')].map((el) =>
      el.getAttribute('data-testid'),
    );
    // self 3, child 5 AND grandchild 7 hidden; only the unrelated pool remains
    expect(ids).toEqual(['pool-add-pool-9']);
  });

  it('Add posts BOTH id lists in selection order, closes and refetches', async () => {
    db.entries = [entry(13, 'b.png'), entry(12, 'a.png')];
    db.childDetails = { 5: { pool: childPool(5, 'Lakes', 7), members: [] } };
    db.pools = [db.pool, childPool(9, 'Wests', 4)];
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="pool-add-btn"]').trigger('click');
    await flushPromises();
    await typeInModal('pool-add-entry-search', 'a');
    await clickModal('pool-add-result-13');
    await clickModal('pool-add-result-12');
    await clickModal('pool-add-mode-pools');
    await clickModal('pool-add-pool-9');

    await clickModal('pool-add-confirm');
    // click order preserved, NOT sorted: [13, 12]
    expect(apiPost).toHaveBeenCalledWith('/api/pools/3/members', { entryIds: [13, 12], poolIds: [9] });
    expect(callCount(apiGet, '/api/pools/3')).toBe(3); // mount + BFS root + refetch after add
    expect(modalEl('pool-add-modal')).toBeNull();
  });

  it('a failed pools-mode BFS shows the problem-detail text, not raw axios text', async () => {
    const wrapper = await mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="pool-add-btn"]').trigger('click');
    await flushPromises();
    apiGet.mockRejectedValueOnce(problemError(500, 'pool subtree lookup exploded')); // the BFS root GET
    await clickModal('pool-add-mode-pools');

    const err = modalEl('pool-add-error');
    expect(err!.textContent).toContain('pool subtree lookup exploded');
    expect(err!.textContent).not.toContain('Request failed with status code');
  });

  it('a rejected entries search routes to the error surface (no unhandled rejection)', async () => {
    const wrapper = await mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="pool-add-btn"]').trigger('click');
    await flushPromises();
    apiGet.mockRejectedValueOnce(problemError(500, 'entry search exploded')); // the /api/entries call
    await typeInModal('pool-add-entry-search', 'dragon');

    const err = modalEl('pool-add-error');
    expect(err!.textContent).toContain('entry search exploded');
    expect(err!.textContent).not.toContain('Request failed with status code');
  });

  it('the Add button is disabled while the members POST is in flight', async () => {
    db.entries = [entry(12, 'a.png')];
    const wrapper = await mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="pool-add-btn"]').trigger('click');
    await flushPromises();
    await typeInModal('pool-add-entry-search', 'a');
    await clickModal('pool-add-result-12');

    let resolvePost!: (value: { data: { added: number } }) => void;
    apiPost.mockImplementationOnce(
      () => new Promise((resolve) => { resolvePost = resolve; }), // the deferred gate
    );
    await clickModal('pool-add-confirm');
    expect(apiPost).toHaveBeenCalledWith('/api/pools/3/members', { entryIds: [12] });
    expect((modalEl('pool-add-confirm') as HTMLButtonElement).disabled).toBe(true);

    resolvePost({ data: { added: 1 } });
    await flushPromises();
    expect(modalEl('pool-add-confirm')).toBeNull(); // released once the POST settled
  });

  it('cancel closes the modal without posting', async () => {
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-testid="pool-add-btn"]').trigger('click');
    await flushPromises();
    await clickModal('pool-add-cancel');

    expect(apiPost).not.toHaveBeenCalled();
    expect(modalEl('pool-add-modal')).toBeNull();
  });
});
