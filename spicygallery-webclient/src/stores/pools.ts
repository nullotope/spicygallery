import { defineStore } from 'pinia';
import axios from 'axios';
import { api } from 'src/boot/api';
import type { FacetDto } from 'src/types/tags';
import type { FolderSuggestion, PoolDetail, PoolDto } from 'src/types/pools';

/** Unwraps a failed request into an Error carrying the RFC-7807 detail
 *  (GlobalExceptionHandler's problem body); anything else passes through.
 *  The HTTP status rides along on the unwrapped Error — callers match on the
 *  status (the wire contract: 409 duplicate name, 404 unknown pool, 400 bad
 *  input), never on the detail text (which the server may reword). */
export function toError(e: unknown): Error {
  if (axios.isAxiosError(e)) {
    const detail = (e.response?.data as { detail?: string } | undefined)?.detail;
    if (detail) {
      const err: Error & { status?: number } = new Error(detail);
      const status = e.response?.status;
      if (status !== undefined) {
        err.status = status;
      }
      return err;
    }
  }
  return e instanceof Error ? e : new Error(String(e));
}

/**
 * Pools state (spec §3.3–§3.9): pure request/response over the pool endpoints — no
 * SSE; pages refetch after each mutation. `q` is plain whitespace terms, NOT the
 * entries query language (spec §3.7). Errors reject with the server's problem-detail
 * message (status preserved on the Error; callers decide the UI).
 */
export const usePoolsStore = defineStore('pools', {
  state: () => ({
    /** The pools grid — set by list(). */
    items: [] as PoolDto[],
    /** The pools sidebar facets — set by facets(). (Not named `facets`:
     *  in an options store that name belongs to the action.) */
    facetItems: [] as FacetDto[],
    /** The draft tiles — set by folders(). (Not named `folders`:
     *  that name belongs to the action, same as facets above.) */
    folderItems: [] as FolderSuggestion[],
    /** The pool page payload — set by get(). */
    current: null as PoolDetail | null,
    /** list + get — the reads that fill a page. */
    loading: false,
    /** facets + folders — the grid chrome reads. */
    loadingFacets: false,
    /** create/patch/setTags/remove/reorder — pool-level writes. */
    saving: false,
    /** addMembers/removeMember — membership writes. */
    savingMembers: false,
  }),
  actions: {
    /** GET /api/pools — `q` filters name/description/tags; with `entryId` every tile
     *  carries the additive `member` flag (spec §4.3, the add-to-pool dropdown). */
    async list(filter?: { q?: string; entryId?: number }) {
      this.loading = true;
      try {
        const { data } = await api.get<PoolDto[]>(
          '/api/pools',
          filter === undefined ? undefined : { params: { ...filter } },
        );
        this.items = data;
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.loading = false;
      }
    },
    /** GET /api/pools/facets — the pools sidebar; drafts are the folders' output. */
    async facets(q?: string) {
      this.loadingFacets = true;
      try {
        const { data } = await api.get<FacetDto[]>(
          '/api/pools/facets',
          q === undefined ? undefined : { params: { q } },
        );
        this.facetItems = data;
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.loadingFacets = false;
      }
    },
    /** GET /api/pools/folders — the draft tiles (virtual, never stored). */
    async folders() {
      this.loadingFacets = true;
      try {
        const { data } = await api.get<FolderSuggestion[]>('/api/pools/folders', undefined);
        this.folderItems = data;
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.loadingFacets = false;
      }
    },
    /** POST /api/pools — 201 with the created pool; a present `rootName` routes to
     *  the folder create (spec §3.3), otherwise manual. */
    async create(input: { name?: string; description?: string; rootName?: string; dirPath?: string }) {
      this.saving = true;
      try {
        const { data } = await api.post<PoolDto>('/api/pools', input);
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.saving = false;
      }
    },
    /** GET /api/pools/{id} — the pool page payload. */
    async get(id: number) {
      this.loading = true;
      try {
        const { data } = await api.get<PoolDetail>(`/api/pools/${id}`, undefined);
        this.current = data;
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.loading = false;
      }
    },
    /** PATCH /api/pools/{id} — absent fields are server-side no-ops. */
    async patch(id: number, changes: { name?: string; description?: string }) {
      this.saving = true;
      try {
        const { data } = await api.patch<PoolDto>(`/api/pools/${id}`, changes);
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.saving = false;
      }
    },
    /** PATCH /api/pools/{id}/tags — the replace-set; returns the pool's own tags
     *  incl. the re-added `pool_type:pool` system tag. */
    async setTags(id: number, names: string[]) {
      this.saving = true;
      try {
        const { data } = await api.patch<PoolDto['tags']>(`/api/pools/${id}/tags`, { tags: names });
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.saving = false;
      }
    },
    /** POST /api/pools/{id}/members — appends entries then sub-pools (duplicates
     *  skipped silently); returns how many member rows were actually added. */
    async addMembers(id: number, input: { entryIds?: number[]; poolIds?: number[] }) {
      this.savingMembers = true;
      try {
        const { data } = await api.post<{ added: number }>(`/api/pools/${id}/members`, input);
        return data.added;
      } catch (e) {
        throw toError(e);
      } finally {
        this.savingMembers = false;
      }
    },
    /** DELETE /api/pools/{id}/members/{memberId} — 204; unknown rows are no-ops. */
    async removeMember(id: number, memberId: number) {
      this.savingMembers = true;
      try {
        await api.delete(`/api/pools/${id}/members/${memberId}`);
      } catch (e) {
        throw toError(e);
      } finally {
        this.savingMembers = false;
      }
    },
    /** POST /api/pools/{id}/order — `orderedMemberIds` must be a permutation of the
     *  current member-row ids; returns the refreshed detail. */
    async reorder(id: number, orderedMemberIds: number[]) {
      this.saving = true;
      try {
        const { data } = await api.post<PoolDetail>(`/api/pools/${id}/order`, { orderedMemberIds });
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.saving = false;
      }
    },
    /** DELETE /api/pools/{id} — 204; the subtree and parent memberships cascade away. */
    async remove(id: number) {
      this.saving = true;
      try {
        await api.delete(`/api/pools/${id}`);
      } catch (e) {
        throw toError(e);
      } finally {
        this.saving = false;
      }
    },
  },
});