import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

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

const { usePoolsStore } = await import('src/stores/pools');

const ENTRY = {
  id: 10,
  kind: 'image',
  fileName: 'a.png',
  width: 4,
  height: 3,
  durationMs: null,
  thumbUrl: '/api/entries/10/thumb',
};

const TAGS = [
  { id: 1, name: 'pool', category: 'pool_type', postCount: 1, taggedBy: null },
  { id: 2, name: 'dragon', category: 'general', postCount: 4, taggedBy: null },
];

/** A pool tile — mirrors PoolService.PoolDto; `member` is absent without entryId. */
const pool = {
  id: 3,
  name: 'Saga',
  description: 'dragons',
  entryCount: 30,
  updatedAt: '2026-11-12T10:00:00Z',
  origin: { rootName: 'lib', dirPath: 'dragons' },
  tags: TAGS,
  cover: ENTRY,
};

/** A tile from the §4.3 variant (GET /api/pools?entryId=) — the additive member flag. */
const memberPool = { ...pool, member: true };

/** GET /api/pools/{id} — tile + members in position order. */
const detail = {
  pool,
  members: [
    { memberId: 100, position: 1, entry: ENTRY, pool: null },
    {
      memberId: 101,
      position: 2,
      entry: null,
      pool: {
        id: 7,
        name: 'wyverns',
        description: null,
        entryCount: 5,
        updatedAt: null,
        origin: null,
        tags: [],
        cover: null,
      },
    },
  ],
};

/** A folder suggestion — a virtual draft tile; cover may be null. */
const folder = { rootName: 'lib', dirPath: 'dragons', entryCount: 30, cover: ENTRY };

const facets = [
  { name: 'pool', category: 'pool_type', count: 3 },
  { name: 'dragon', category: 'general', count: 5 },
];

/** A request gate: the action stays in flight until release() resolves it. */
const deferred = (value: unknown) => {
  let resolve!: (v: { data: unknown }) => void;
  const promise = new Promise<{ data: unknown }>((r) => (resolve = r));
  return { promise, release: () => resolve({ data: value }) };
};

describe('pools store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    apiGet.mockReset();
    apiPost.mockReset();
    apiPatch.mockReset();
    apiDelete.mockReset();
  });

  it('list fetches /api/pools without params by default and fills items', async () => {
    apiGet.mockResolvedValue({ data: [pool] });
    const store = usePoolsStore();
    const items = await store.list();
    expect(apiGet).toHaveBeenCalledWith('/api/pools', undefined);
    expect(store.items).toEqual([pool]);
    expect(items).toEqual([pool]);
    expect(store.loading).toBe(false);
  });

  it('list passes q through as the grid filter', async () => {
    apiGet.mockResolvedValue({ data: [] });
    const store = usePoolsStore();
    await store.list({ q: 'dragon' });
    expect(apiGet).toHaveBeenCalledWith('/api/pools', { params: { q: 'dragon' } });
  });

  it('list passes entryId through — the tiles then carry the member flags', async () => {
    apiGet.mockResolvedValue({ data: [memberPool] });
    const store = usePoolsStore();
    const items = await store.list({ entryId: 10 });
    expect(apiGet).toHaveBeenCalledWith('/api/pools', { params: { entryId: 10 } });
    expect(items).toEqual([memberPool]); // the additive member flag survives untouched
  });

  it('list passes both q and entryId together', async () => {
    apiGet.mockResolvedValue({ data: [] });
    const store = usePoolsStore();
    await store.list({ q: 'dragon', entryId: 10 });
    expect(apiGet).toHaveBeenCalledWith('/api/pools', { params: { q: 'dragon', entryId: 10 } });
  });

  it('facets fetches the sidebar counts and caches them', async () => {
    apiGet.mockResolvedValue({ data: facets });
    const store = usePoolsStore();
    const got = await store.facets();
    expect(apiGet).toHaveBeenCalledWith('/api/pools/facets', undefined);
    expect(store.facetItems).toEqual(facets);
    expect(got).toEqual(facets);

    await store.facets('dragon');
    expect(apiGet).toHaveBeenLastCalledWith('/api/pools/facets', { params: { q: 'dragon' } });
  });

  it('folders fetches the draft suggestions and caches them', async () => {
    apiGet.mockResolvedValue({ data: [folder] });
    const store = usePoolsStore();
    const got = await store.folders();
    expect(apiGet).toHaveBeenCalledWith('/api/pools/folders', undefined);
    expect(store.folderItems).toEqual([folder]);
    expect(got).toEqual([folder]);
  });

  it('create posts the manual body and returns the created pool', async () => {
    apiPost.mockResolvedValue({ data: pool });
    const store = usePoolsStore();
    const created = await store.create({ name: 'Saga', description: 'dragons' });
    expect(apiPost).toHaveBeenCalledWith('/api/pools', { name: 'Saga', description: 'dragons' });
    expect(created).toEqual(pool);

    await store.create({ name: 'solo' });
    expect(apiPost).toHaveBeenLastCalledWith('/api/pools', { name: 'solo' });
  });

  it('create posts the folder body with rootName/dirPath (a present rootName routes server-side)', async () => {
    apiPost.mockResolvedValue({ data: pool });
    const store = usePoolsStore();
    await store.create({ rootName: 'lib', dirPath: 'dragons' });
    expect(apiPost).toHaveBeenCalledWith('/api/pools', { rootName: 'lib', dirPath: 'dragons' });
  });

  it('get fetches one pool detail and caches it as current', async () => {
    apiGet.mockResolvedValue({ data: detail });
    const store = usePoolsStore();
    const got = await store.get(3);
    expect(apiGet).toHaveBeenCalledWith('/api/pools/3', undefined);
    expect(store.current).toEqual(detail);
    expect(got).toEqual(detail);
  });

  it('patch sends only the given fields and returns the updated tile', async () => {
    apiPatch.mockResolvedValue({ data: pool });
    const store = usePoolsStore();
    const updated = await store.patch(3, { description: 'new' });
    expect(apiPatch).toHaveBeenCalledWith('/api/pools/3', { description: 'new' });
    expect(updated).toEqual(pool);

    await store.patch(3, { name: 'Drakes', description: '' });
    expect(apiPatch).toHaveBeenLastCalledWith('/api/pools/3', { name: 'Drakes', description: '' });
  });

  it('setTags patches the replace-set and returns the pool tags', async () => {
    apiPatch.mockResolvedValue({ data: TAGS });
    const store = usePoolsStore();
    const tags = await store.setTags(3, ['dragon']);
    expect(apiPatch).toHaveBeenCalledWith('/api/pools/3/tags', { tags: ['dragon'] });
    expect(tags).toEqual(TAGS);
  });

  it('addMembers posts the ids and returns the added count', async () => {
    apiPost.mockResolvedValue({ data: { added: 2 } });
    const store = usePoolsStore();
    const added = await store.addMembers(3, { entryIds: [10, 11], poolIds: [7] });
    expect(apiPost).toHaveBeenCalledWith('/api/pools/3/members', { entryIds: [10, 11], poolIds: [7] });
    expect(added).toBe(2);

    await store.addMembers(3, { entryIds: [12] });
    expect(apiPost).toHaveBeenLastCalledWith('/api/pools/3/members', { entryIds: [12] });
  });

  it('removeMember deletes the member row', async () => {
    apiDelete.mockResolvedValue({ data: '' });
    const store = usePoolsStore();
    await store.removeMember(3, 100);
    expect(apiDelete).toHaveBeenCalledWith('/api/pools/3/members/100');
  });

  it('reorder posts the member-row permutation and returns the refreshed detail', async () => {
    apiPost.mockResolvedValue({ data: detail });
    const store = usePoolsStore();
    const reordered = await store.reorder(3, [101, 100]);
    expect(apiPost).toHaveBeenCalledWith('/api/pools/3/order', { orderedMemberIds: [101, 100] });
    expect(reordered).toEqual(detail);
  });

  it('remove deletes the pool', async () => {
    apiDelete.mockResolvedValue({ data: '' });
    const store = usePoolsStore();
    await store.remove(3);
    expect(apiDelete).toHaveBeenCalledWith('/api/pools/3');
  });

  it('keeps one loading flag per action group', async () => {
    const store = usePoolsStore();

    let d = deferred([]);
    apiGet.mockReturnValueOnce(d.promise);
    const listing = store.list();
    expect(store.loading).toBe(true);
    d.release();
    await listing;
    expect(store.loading).toBe(false);

    d = deferred(pool);
    apiPost.mockReturnValueOnce(d.promise);
    const creating = store.create({ name: 'Saga' });
    expect(store.saving).toBe(true);
    expect(store.loading).toBe(false); // groups are independent
    d.release();
    await creating;
    expect(store.saving).toBe(false);

    d = deferred(facets);
    apiGet.mockReturnValueOnce(d.promise);
    const faceting = store.facets();
    expect(store.loadingFacets).toBe(true);
    expect(store.loading).toBe(false);
    d.release();
    await faceting;
    expect(store.loadingFacets).toBe(false);

    d = deferred([folder]);
    apiGet.mockReturnValueOnce(d.promise);
    const foldersing = store.folders();
    expect(store.loadingFacets).toBe(true);
    d.release();
    await foldersing;
    expect(store.loadingFacets).toBe(false);

    d = deferred({ added: 2 });
    apiPost.mockReturnValueOnce(d.promise);
    const adding = store.addMembers(3, { entryIds: [10] });
    expect(store.savingMembers).toBe(true);
    expect(store.saving).toBe(false);
    d.release();
    await adding;
    expect(store.savingMembers).toBe(false);
  });

  it('a problem-detail rejection surfaces the detail message', async () => {
    apiPost.mockRejectedValueOnce({
      isAxiosError: true,
      response: {
        status: 409,
        data: { title: 'Conflict', status: 409, detail: "pool 'Saga' already exists" },
      },
    });
    const store = usePoolsStore();
    await expect(store.create({ name: 'Saga' })).rejects.toThrow(
      "pool 'Saga' already exists",
    );
  });

  it('a problem-detail rejection preserves the HTTP status on the error', async () => {
    // the status is the wire contract — callers match on it, never on detail wording
    apiGet.mockRejectedValueOnce({
      isAxiosError: true,
      response: {
        status: 404,
        data: { title: 'Not Found', status: 404, detail: 'no pool 3' },
      },
    });
    const store = usePoolsStore();
    await expect(store.get(3)).rejects.toMatchObject({ message: 'no pool 3', status: 404 });
  });

  it('a failure without a problem detail still rejects as an Error', async () => {
    apiGet.mockRejectedValueOnce(new Error('network down'));
    const store = usePoolsStore();
    await expect(store.list()).rejects.toThrow('network down');
  });
});