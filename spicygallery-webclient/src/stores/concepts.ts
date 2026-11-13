import { defineStore } from 'pinia';
import axios from 'axios';
import { api } from 'src/boot/api';
import type { ApplyResult, ConceptDto, ConceptMembers, SuggestionItem } from 'src/types/concepts';

/** Server-side list order: name-asc or most-recent-first (the default). */
export type ConceptSort = 'name' | 'recent';

/** Unwraps a failed request into an Error carrying the RFC-7807 detail
 *  (GlobalExceptionHandler's problem body); anything else passes through.
 *  The HTTP status rides along on the unwrapped Error — callers match on the
 *  status (the wire contract), never on the detail text (which the server may
 *  reword without notice). */
function toError(e: unknown): Error {
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
 * Concepts state (spec §3.5 + the §4.2 members list): pure request/response over the
 * concept endpoints — no SSE; the workbench refetches after each mutation. Errors
 * reject with the server's problem-detail message (status preserved on the Error;
 * callers decide the UI).
 */
export const useConceptsStore = defineStore('concepts', {
  state: () => ({
    /** The concept list — set by list(). */
    items: [] as ConceptDto[],
    /** The workbench concept — set by get() and refresh(). */
    current: null as ConceptDto | null,
    /** The last suggestion page — set by suggestions(). (Not named `suggestions`:
     *  in an options store that name belongs to the action.) */
    suggestionItems: [] as SuggestionItem[],
    /** The workbench member lists — set by members(). (Not named `members`:
     *  that name belongs to the action, same as suggestions above.) */
    memberLists: null as ConceptMembers | null,
    /** list + get — the reads that fill the workbench. */
    loading: false,
    /** create/patch/remove — concept-level writes. */
    saving: false,
    /** examples/exclusions membership writes. */
    savingMembers: false,
    loadingSuggestions: false,
    /** apply + refresh — the anchor operations. */
    applying: false,
  }),
  actions: {
    /** GET /api/concepts?sort=name|recent — the server default is recent. */
    async list(sort?: ConceptSort) {
      this.loading = true;
      try {
        const { data } = await api.get<ConceptDto[]>(
          '/api/concepts',
          sort === undefined ? undefined : { params: { sort } },
        );
        this.items = data;
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.loading = false;
      }
    },
    /** POST /api/concepts — 201 with the created concept. */
    async create(input: { name: string; phrase?: string }) {
      this.saving = true;
      try {
        const { data } = await api.post<ConceptDto>('/api/concepts', input);
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.saving = false;
      }
    },
    /** GET /api/concepts/{id}. */
    async get(id: number) {
      this.loading = true;
      try {
        const { data } = await api.get<ConceptDto>(`/api/concepts/${id}`, undefined);
        this.current = data;
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.loading = false;
      }
    },
    /** PATCH /api/concepts/{id} — absent fields are server-side no-ops. */
    async patch(id: number, changes: { name?: string; phrase?: string }) {
      this.saving = true;
      try {
        const { data } = await api.patch<ConceptDto>(`/api/concepts/${id}`, changes);
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.saving = false;
      }
    },
    /** DELETE /api/concepts/{id} — 204. */
    async remove(id: number) {
      this.saving = true;
      try {
        await api.delete(`/api/concepts/${id}`);
      } catch (e) {
        throw toError(e);
      } finally {
        this.saving = false;
      }
    },
    /** GET /api/concepts/{id}/members — the real examples/excluded tiles (spec §4.2). */
    async members(id: number) {
      this.loading = true;
      try {
        const { data } = await api.get<ConceptMembers>(`/api/concepts/${id}/members`, undefined);
        this.memberLists = data;
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.loading = false;
      }
    },
    /** POST /api/concepts/{id}/examples — returns how many were actually added. */
    async addExamples(id: number, entryIds: number[]) {
      this.savingMembers = true;
      try {
        const { data } = await api.post<{ added: number }>(
          `/api/concepts/${id}/examples`,
          { entryIds },
        );
        return data.added;
      } catch (e) {
        throw toError(e);
      } finally {
        this.savingMembers = false;
      }
    },
    /** DELETE /api/concepts/{id}/examples/{entryId} — 204. */
    async removeExample(id: number, entryId: number) {
      this.savingMembers = true;
      try {
        await api.delete(`/api/concepts/${id}/examples/${entryId}`);
      } catch (e) {
        throw toError(e);
      } finally {
        this.savingMembers = false;
      }
    },
    /** POST /api/concepts/{id}/exclusions — returns how many were excluded. */
    async addExclusions(id: number, entryIds: number[]) {
      this.savingMembers = true;
      try {
        const { data } = await api.post<{ excluded: number }>(
          `/api/concepts/${id}/exclusions`,
          { entryIds },
        );
        return data.excluded;
      } catch (e) {
        throw toError(e);
      } finally {
        this.savingMembers = false;
      }
    },
    /** DELETE /api/concepts/{id}/exclusions/{entryId} — 204. */
    async removeExclusion(id: number, entryId: number) {
      this.savingMembers = true;
      try {
        await api.delete(`/api/concepts/${id}/exclusions/${entryId}`);
      } catch (e) {
        throw toError(e);
      } finally {
        this.savingMembers = false;
      }
    },
    /** GET /api/concepts/{id}/suggestions?limit= — entries pass through untouched. */
    async suggestions(id: number, limit?: number) {
      this.loadingSuggestions = true;
      try {
        const { data } = await api.get<{ items: SuggestionItem[] }>(
          `/api/concepts/${id}/suggestions`,
          limit === undefined ? undefined : { params: { limit } },
        );
        this.suggestionItems = data.items;
        return data.items;
      } catch (e) {
        throw toError(e);
      } finally {
        this.loadingSuggestions = false;
      }
    },
    /** POST /api/concepts/{id}/apply — writes the tag to every example. */
    async apply(id: number) {
      this.applying = true;
      try {
        const { data } = await api.post<ApplyResult>(`/api/concepts/${id}/apply`);
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.applying = false;
      }
    },
    /** POST /api/concepts/{id}/refresh — rebuilds the anchor from the examples. */
    async refresh(id: number) {
      this.applying = true;
      try {
        const { data } = await api.post<ConceptDto>(`/api/concepts/${id}/refresh`);
        this.current = data;
        return data;
      } catch (e) {
        throw toError(e);
      } finally {
        this.applying = false;
      }
    },
  },
});
