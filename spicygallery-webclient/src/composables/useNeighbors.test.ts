import { describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';

const apiGet = vi.fn();
vi.mock('src/boot/api', () => ({
  api: { get: (...args: unknown[]) => apiGet(...args) },
}));

const { useNeighbors } = await import('src/composables/useNeighbors');
import type { GridItem } from 'src/stores/entries';

function item(id: number): GridItem {
  return {
    id,
    kind: 'image',
    fileName: 'f' + id + '.png',
    width: 8,
    height: 4,
    durationMs: null,
    thumbUrl: '/t',
  };
}

describe('useNeighbors', () => {
  it('in-list neighbors come from the current result list', async () => {
    const id = ref(2);
    const items = ref([item(1), item(2), item(3)]);
    const q = ref('');
    const kind = ref<'image' | 'video' | ''>('');
    const { prevId, nextId } = useNeighbors(id, items, q, kind);
    await vi.waitFor(() => {
      expect(prevId.value).toBe(1);
      expect(nextId.value).toBe(3);
    });
    expect(apiGet).not.toHaveBeenCalled();
  });

  it('falls back to the neighbors endpoint for deep-linked entries', async () => {
    apiGet.mockResolvedValue({ data: { prevId: 41, nextId: null } });
    const id = ref(42);
    const items = ref<GridItem[]>([]); // deep link: no in-memory list
    const q = ref('duck');
    const kind = ref<'image' | 'video' | ''>('image');
    const { prevId, nextId } = useNeighbors(id, items, q, kind);
    await vi.waitFor(() => {
      expect(prevId.value).toBe(41);
      expect(nextId.value).toBeNull();
    });
    expect(apiGet).toHaveBeenCalledWith('/api/entries/42/neighbors', {
      params: { q: 'duck', kind: 'image' },
    });
  });

  it('list edges yield null neighbors', async () => {
    const id = ref(9);
    const items = ref([item(9)]);
    const { prevId, nextId } = useNeighbors(id, items, ref(''), ref(''));
    await vi.waitFor(() => {
      expect(prevId.value).toBeNull();
      expect(nextId.value).toBeNull();
    });
  });
});
