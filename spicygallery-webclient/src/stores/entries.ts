import { defineStore } from 'pinia';
import axios from 'axios';
import { api } from 'src/boot/api';
import type { FacetDto } from 'src/types/tags';

export interface GridItem {
  id: number;
  kind: 'image' | 'video';
  fileName: string;
  width: number | null;
  height: number | null;
  durationMs: number | null;
  thumbUrl: string;
  /** Cosine similarity of a semantic (similar:) hit; absent otherwise. */
  score?: number;
}

interface PageDto {
  items: GridItem[];
  nextCursor: string | null;
  rs?: string | null;
  facets?: FacetDto[] | null;
}

export type KindFilter = '' | 'image' | 'video';

/**
 * Grid query state (spec §8): keyset pagination, q/kind filters, cursor chain —
 * plus slice-2 facets (page-1 tag aggregation), the random-order seed echo, and
 * the query-error state for the sidebar search box.
 */
export const useEntriesStore = defineStore('entries', {
  state: () => ({
    q: '',
    kind: '' as KindFilter,
    items: [] as GridItem[],
    nextCursor: null as string | null,
    loading: false,
    facets: [] as FacetDto[],
    randomSeed: null as string | null,
    queryError: null as string | null,
  }),
  actions: {
    /** Clears results and fetches page 1 with the current q/kind. */
    async reset() {
      this.items = [];
      this.nextCursor = null;
      this.randomSeed = null;
      this.queryError = null;
      await this.fetchPage();
    },
    /** Appends the next page; no-op while loading or when the cursor chain ended. */
    async loadMore() {
      if (this.loading || this.nextCursor === null) {
        return;
      }
      await this.fetchPage();
    },
    async fetchPage() {
      this.loading = true;
      try {
        const params: Record<string, string> = {};
        if (this.q) {
          params.q = this.q;
        }
        if (this.kind) {
          params.kind = this.kind;
        }
        if (this.nextCursor) {
          params.cursor = this.nextCursor;
        }
        if (this.randomSeed) {
          params.rs = this.randomSeed;
        }
        const { data } = await api.get<PageDto>('/api/entries', { params });
        this.items.push(...data.items);
        this.nextCursor = data.nextCursor;
        if (data.rs) {
          this.randomSeed = data.rs;
        }
        if (data.facets) {
          this.facets = data.facets; // page 1 only; loadMore responses carry none
        }
      } catch (e) {
        if (axios.isAxiosError(e) && e.response?.status === 400) {
          const d = e.response.data as { detail?: string; token?: string; position?: number };
          this.queryError = d.detail ?? 'invalid query';
          return; // keep the last good results on screen
        }
        throw e;
      } finally {
        this.loading = false;
      }
    },
  },
});
