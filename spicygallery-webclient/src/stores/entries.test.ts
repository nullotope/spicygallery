import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const apiGet = vi.fn();
vi.mock('src/boot/api', () => ({
  api: { get: (...args: unknown[]) => apiGet(...args) },
}));

const { useEntriesStore } = await import('src/stores/entries');

function page(items: Array<{ id: number }>, nextCursor: string | null) {
  return {
    data: {
      items: items.map((i) => ({
        id: i.id,
        kind: 'image',
        fileName: 'f' + i.id + '.png',
        width: 8,
        height: 4,
        durationMs: null,
        thumbUrl: `/api/entries/${i.id}/thumb?v=1`,
      })),
      nextCursor,
    },
  };
}

describe('entries store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    apiGet.mockReset();
  });

  it('reset clears and fetches page 1 with current filters', async () => {
    apiGet.mockResolvedValueOnce(page([{ id: 20 }, { id: 19 }], '19'));
    const store = useEntriesStore();
    store.q = 'duck';
    store.kind = 'image';
    store.items = [{ id: 1 } as never]; // stale content must go
    await store.reset();
    expect(store.items.map((i) => i.id)).toEqual([20, 19]);
    expect(store.nextCursor).toBe('19');
    expect(apiGet).toHaveBeenCalledWith('/api/entries', {
      params: { q: 'duck', kind: 'image' },
    });
  });

  it('loadMore appends via cursor and stops at null', async () => {
    apiGet
      .mockResolvedValueOnce(page([{ id: 3 }, { id: 2 }], '2'))
      .mockResolvedValueOnce(page([{ id: 1 }], null));
    const store = useEntriesStore();
    await store.reset();
    await store.loadMore();
    expect(store.items.map((i) => i.id)).toEqual([3, 2, 1]);
    expect(apiGet).toHaveBeenLastCalledWith('/api/entries', {
      params: { cursor: '2' },
    });
    await store.loadMore(); // nextCursor null → no further call
    expect(apiGet).toHaveBeenCalledTimes(2);
  });

  it('loading guard blocks concurrent loadMore', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    apiGet.mockImplementation(() => gate.then(() => page([{ id: 5 }], null)));
    const store = useEntriesStore();
    const first = store.reset();
    const second = store.loadMore(); // must not issue a second request while loading
    release();
    await Promise.all([first, second]);
    expect(apiGet).toHaveBeenCalledTimes(1);
  });

  it('reset clears seed/error and applies facets; loadMore keeps facets and echoes rs', async () => {
    const withFacets = page([{ id: 9 }], '9') as {
      data: { rs?: string; facets?: Array<{ name: string; category: string; count: number }> };
    };
    withFacets.data.rs = 'abc';
    withFacets.data.facets = [{ name: 'duck', category: 'general', count: 2 }];
    apiGet.mockResolvedValueOnce(withFacets).mockResolvedValueOnce(page([{ id: 8 }], null));
    const store = useEntriesStore();
    store.randomSeed = 'stale';
    store.queryError = 'stale';
    await store.reset();
    expect(store.randomSeed).toBe('abc');
    expect(store.queryError).toBeNull();
    expect(store.facets).toEqual([{ name: 'duck', category: 'general', count: 2 }]);

    await store.loadMore();
    expect(apiGet).toHaveBeenLastCalledWith('/api/entries', {
      params: { cursor: '9', rs: 'abc' },
    });
    expect(store.facets).toEqual([{ name: 'duck', category: 'general', count: 2 }]); // untouched
  });

  it('a 400 sets queryError and keeps items', async () => {
    apiGet.mockRejectedValueOnce({
      isAxiosError: true,
      response: {
        status: 400,
        data: { detail: "unknown category or metatag 'foo:bar'", token: 'foo:bar', position: 0 },
      },
    });
    const store = useEntriesStore();
    store.q = 'foo:bar';
    await store.reset();
    expect(store.queryError).toContain("unknown category or metatag 'foo:bar'");
    expect(store.nextCursor).toBeNull();
  });
});
