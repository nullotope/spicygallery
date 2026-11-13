import { ref, watch, type ComputedRef, type Ref, computed } from 'vue';
import { api } from 'src/boot/api';
import type { GridItem, KindFilter } from 'src/stores/entries';

/** The neighbors wire shape (spec §8): `position`/`total` ride along ONLY on
 *  pool-scoped reads (spec §3.9 `?pool=`). */
interface NeighborsDto {
  prevId: number | null;
  nextId: number | null;
  position?: number;
  total?: number;
}

/**
 * Prev/next ids for the detail page (spec §9): in-memory list when the entry is part
 * of it, `GET /api/entries/{id}/neighbors` fallback for deep-linked opens — both share
 * the same filter semantics. With a non-empty `pool` (spec §3.9) the request is
 * pool-scoped instead: the flatten IS the reading order, so the in-memory list and
 * q/kind do not apply, and the response's `position`/`total` are exposed for the
 * reading header.
 */
export function useNeighbors(
  id: Ref<number>,
  items: Ref<GridItem[]>,
  q: Ref<string>,
  kind: Ref<KindFilter>,
  pool?: Ref<string>,
): {
  prevId: ComputedRef<number | null>;
  nextId: ComputedRef<number | null>;
  position: ComputedRef<number | null>;
  total: ComputedRef<number | null>;
} {
  const prev = ref<number | null>(null);
  const next = ref<number | null>(null);
  /** 1-based reading position + flattened pool size — pool-scoped responses only. */
  const position = ref<number | null>(null);
  const total = ref<number | null>(null);

  async function recompute() {
    position.value = null;
    total.value = null;
    if (pool !== undefined && pool.value) {
      try {
        const { data } = await api.get<NeighborsDto>(
            `/api/entries/${id.value}/neighbors`, { params: { pool: pool.value } });
        prev.value = data.prevId;
        next.value = data.nextId;
        position.value = data.position ?? null;
        total.value = data.total ?? null;
      } catch {
        prev.value = null;
        next.value = null;
      }
      return;
    }
    const list = items.value;
    const idx = list.findIndex((i) => i.id === id.value);
    if (idx >= 0) {
      prev.value = idx > 0 ? list[idx - 1]!.id : null;
      next.value = idx < list.length - 1 ? list[idx + 1]!.id : null;
      return;
    }
    const params: Record<string, string> = {};
    if (q.value) {
      params.q = q.value;
    }
    if (kind.value) {
      params.kind = kind.value;
    }
    try {
      const { data } = await api.get<NeighborsDto>(
          `/api/entries/${id.value}/neighbors`, { params });
      prev.value = data.prevId;
      next.value = data.nextId;
    } catch {
      prev.value = null;
      next.value = null;
    }
  }

  watch([id, () => items.value.length, q, kind, () => pool?.value ?? ''], () => void recompute(), { immediate: true });

  return {
    prevId: computed(() => prev.value),
    nextId: computed(() => next.value),
    position: computed(() => position.value),
    total: computed(() => total.value),
  };
}
