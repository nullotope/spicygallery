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

const { useConceptsStore } = await import('src/stores/concepts');

const concept = {
  id: 3,
  name: 'duck',
  phrase: 'a waterfowl',
  exampleCount: 2,
  excludedCount: 1,
  taggedCount: 5,
  applyable: 2,
  anchorStale: false,
  createdAt: '2026-11-09T10:00:00Z',
  updatedAt: '2026-11-09T10:05:00Z',
};

/** A request gate: the action stays in flight until release() resolves it. */
const deferred = (value: unknown) => {
  let resolve!: (v: { data: unknown }) => void;
  const promise = new Promise<{ data: unknown }>((r) => (resolve = r));
  return { promise, release: () => resolve({ data: value }) };
};

describe('concepts store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    apiGet.mockReset();
    apiPost.mockReset();
    apiPatch.mockReset();
    apiDelete.mockReset();
  });

  it('list fetches /api/concepts and fills items', async () => {
    apiGet.mockResolvedValue({ data: [concept] });
    const store = useConceptsStore();
    const items = await store.list();
    expect(apiGet).toHaveBeenCalledWith('/api/concepts', undefined);
    expect(store.items).toEqual([concept]);
    expect(items).toEqual([concept]);
    expect(store.loading).toBe(false);
  });

  it('list passes the sort param through', async () => {
    apiGet.mockResolvedValue({ data: [] });
    const store = useConceptsStore();
    await store.list('name');
    expect(apiGet).toHaveBeenCalledWith('/api/concepts', { params: { sort: 'name' } });
  });

  it('keeps one loading flag per action group', async () => {
    const store = useConceptsStore();

    let d = deferred([]);
    apiGet.mockReturnValueOnce(d.promise);
    const listing = store.list();
    expect(store.loading).toBe(true);
    d.release();
    await listing;
    expect(store.loading).toBe(false);

    d = deferred(concept);
    apiPost.mockReturnValueOnce(d.promise);
    const creating = store.create({ name: 'duck' });
    expect(store.saving).toBe(true);
    expect(store.loading).toBe(false); // groups are independent
    d.release();
    await creating;
    expect(store.saving).toBe(false);

    d = deferred({ added: 2 });
    apiPost.mockReturnValueOnce(d.promise);
    const adding = store.addExamples(3, [10]);
    expect(store.savingMembers).toBe(true);
    d.release();
    await adding;
    expect(store.savingMembers).toBe(false);

    d = deferred({ items: [] });
    apiGet.mockReturnValueOnce(d.promise);
    const suggesting = store.suggestions(3);
    expect(store.loadingSuggestions).toBe(true);
    d.release();
    await suggesting;
    expect(store.loadingSuggestions).toBe(false);

    d = deferred({ tag: 'duck', examples: 2, tagged: 2, alreadyTagged: 0 });
    apiPost.mockReturnValueOnce(d.promise);
    const applying = store.apply(3);
    expect(store.applying).toBe(true);
    d.release();
    await applying;
    expect(store.applying).toBe(false);
  });

  it('create posts the name and optional phrase, returning the created concept', async () => {
    apiPost.mockResolvedValue({ data: concept });
    const store = useConceptsStore();
    const created = await store.create({ name: 'duck', phrase: 'a waterfowl' });
    expect(apiPost).toHaveBeenCalledWith('/api/concepts', { name: 'duck', phrase: 'a waterfowl' });
    expect(created).toEqual(concept);

    await store.create({ name: 'goose' });
    expect(apiPost).toHaveBeenLastCalledWith('/api/concepts', { name: 'goose' });
  });

  it('patch sends only the given fields (absent ones are server-side no-ops)', async () => {
    apiPatch.mockResolvedValue({ data: concept });
    const store = useConceptsStore();
    await store.patch(3, { phrase: 'new phrase' });
    expect(apiPatch).toHaveBeenCalledWith('/api/concepts/3', { phrase: 'new phrase' });

    await store.patch(3, { name: 'goose', phrase: 'x' });
    expect(apiPatch).toHaveBeenLastCalledWith('/api/concepts/3', { name: 'goose', phrase: 'x' });
  });

  it('remove deletes the concept', async () => {
    apiDelete.mockResolvedValue({ data: '' });
    const store = useConceptsStore();
    await store.remove(3);
    expect(apiDelete).toHaveBeenCalledWith('/api/concepts/3');
  });

  it('get fetches one concept and caches it as current', async () => {
    apiGet.mockResolvedValue({ data: concept });
    const store = useConceptsStore();
    const got = await store.get(3);
    expect(apiGet).toHaveBeenCalledWith('/api/concepts/3', undefined);
    expect(store.current).toEqual(concept);
    expect(got).toEqual(concept);
  });

  it('addExamples posts the entry ids and returns the added count', async () => {
    apiPost.mockResolvedValue({ data: { added: 2 } });
    const store = useConceptsStore();
    const added = await store.addExamples(3, [10, 11]);
    expect(apiPost).toHaveBeenCalledWith('/api/concepts/3/examples', { entryIds: [10, 11] });
    expect(added).toBe(2);
  });

  it('removeExample deletes the membership', async () => {
    apiDelete.mockResolvedValue({ data: '' });
    const store = useConceptsStore();
    await store.removeExample(3, 10);
    expect(apiDelete).toHaveBeenCalledWith('/api/concepts/3/examples/10');
  });

  it('addExclusions posts the entry ids and returns the excluded count', async () => {
    apiPost.mockResolvedValue({ data: { excluded: 1 } });
    const store = useConceptsStore();
    const excluded = await store.addExclusions(3, [12]);
    expect(apiPost).toHaveBeenCalledWith('/api/concepts/3/exclusions', { entryIds: [12] });
    expect(excluded).toBe(1);
  });

  it('removeExclusion deletes the exclusion', async () => {
    apiDelete.mockResolvedValue({ data: '' });
    const store = useConceptsStore();
    await store.removeExclusion(3, 12);
    expect(apiDelete).toHaveBeenCalledWith('/api/concepts/3/exclusions/12');
  });

  it('members fetches the member lists and caches them', async () => {
    const members = {
      examples: [
        { id: 10, kind: 'image', fileName: 'a.png', width: 4, height: 3, durationMs: null, thumbUrl: '/api/entries/10/thumb' },
      ],
      excluded: [
        { id: 12, kind: 'video', fileName: 'c.mp4', width: null, height: null, durationMs: 5000, thumbUrl: '/api/entries/12/thumb' },
      ],
    };
    apiGet.mockResolvedValue({ data: members });
    const store = useConceptsStore();
    const got = await store.members(3);
    expect(apiGet).toHaveBeenCalledWith('/api/concepts/3/members', undefined);
    expect(got).toEqual(members); // score-less grid entries, untouched
    expect(store.memberLists).toEqual(members);
  });

  it('suggestions maps the {items} envelope through unchanged', async () => {
    const items = [
      {
        entry: {
          id: 10,
          kind: 'image',
          fileName: 'a.png',
          width: 4,
          height: 3,
          durationMs: null,
          thumbUrl: '/api/entries/10/thumb',
          score: 0.91,
        },
        score: 0.91,
      },
      {
        entry: {
          id: 11,
          kind: 'video',
          fileName: 'b.mp4',
          width: null,
          height: null,
          durationMs: 5000,
          thumbUrl: '/api/entries/11/thumb',
        },
        score: 0.42,
      },
    ];
    apiGet.mockResolvedValue({ data: { items } });
    const store = useConceptsStore();
    const got = await store.suggestions(3);
    expect(apiGet).toHaveBeenCalledWith('/api/concepts/3/suggestions', undefined);
    expect(got).toEqual(items); // entries pass through with their scores, untouched
    expect(store.suggestionItems).toEqual(items);

    await store.suggestions(3, 25);
    expect(apiGet).toHaveBeenLastCalledWith('/api/concepts/3/suggestions', {
      params: { limit: 25 },
    });
  });

  it('apply posts and returns the accounting', async () => {
    const result = { tag: 'duck', examples: 2, tagged: 2, alreadyTagged: 0 };
    apiPost.mockResolvedValue({ data: result });
    const store = useConceptsStore();
    const applied = await store.apply(3);
    expect(apiPost).toHaveBeenCalledWith('/api/concepts/3/apply');
    expect(applied).toEqual(result);
  });

  it('refresh posts and caches the updated concept as current', async () => {
    apiPost.mockResolvedValue({ data: concept });
    const store = useConceptsStore();
    const refreshed = await store.refresh(3);
    expect(apiPost).toHaveBeenCalledWith('/api/concepts/3/refresh');
    expect(refreshed).toEqual(concept);
    expect(store.current).toEqual(concept);
  });

  it('a problem-detail rejection surfaces the detail message', async () => {
    apiPost.mockRejectedValueOnce({
      isAxiosError: true,
      response: {
        status: 409,
        data: { title: 'Conflict', status: 409, detail: "concept 'duck' already exists" },
      },
    });
    const store = useConceptsStore();
    await expect(store.create({ name: 'duck' })).rejects.toThrow(
      "concept 'duck' already exists",
    );
  });

  it('a problem-detail rejection preserves the HTTP status on the error', async () => {
    // the status is the wire contract — callers match on it, never on detail wording
    apiGet.mockRejectedValueOnce({
      isAxiosError: true,
      response: {
        status: 404,
        data: { title: 'Not Found', status: 404, detail: 'no concept 3' },
      },
    });
    const store = useConceptsStore();
    await expect(store.get(3)).rejects.toMatchObject({ message: 'no concept 3', status: 404 });
  });

  it('a failure without a problem detail still rejects as an Error', async () => {
    apiGet.mockRejectedValueOnce(new Error('network down'));
    const store = useConceptsStore();
    await expect(store.list()).rejects.toThrow('network down');
  });
});
