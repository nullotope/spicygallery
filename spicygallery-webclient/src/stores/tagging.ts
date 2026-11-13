import { defineStore } from 'pinia';
import { api } from 'src/boot/api';
import type { PredictResponse, TaggingInfo } from 'src/types/tagging';
import type { TagDto } from 'src/types/tags';

/** The most recently started bulk run — mirrors TaggingBackfill.RunStatus. */
export interface RunDto {
  running: boolean;
  done: number;
  total: number;
  errors: number;
  error: string | null;
}

interface ProgressStats {
  done: number;
  total: number;
  current: string | null;
  errors: number;
}

/**
 * Tagging run state. SSE notifies, REST reconciles (single-mapper rule):
 * onerror, done and failed always refetch /api/tagging/last.
 */
export const useTaggingStore = defineStore('tagging', {
  state: () => ({
    info: null as TaggingInfo | null,
    run: null as RunDto | null,
    connected: false,
    /** One EventSource per app session — the guard also protects against
     *  accidental double connects (see importStore, review I6). */
    source: null as EventSource | null,
  }),
  actions: {
    connect() {
      if (this.source) {
        return;
      }
      const source = new EventSource('/api/tagging/events');
      this.source = source;
      source.onopen = () => {
        this.connected = true;
      };
      source.addEventListener('progress', (event) => {
        const stats = JSON.parse((event as MessageEvent).data) as ProgressStats;
        this.run = {
          running: true,
          done: stats.done,
          total: stats.total,
          errors: stats.errors,
          error: null,
        };
      });
      source.addEventListener('done', () => {
        void this.reconcile();
      });
      source.addEventListener('failed', () => {
        void this.reconcile();
      });
      source.onerror = () => {
        this.connected = false;
        void this.reconcile();
      };
    },
    /** REST reconcile — the source of truth (spec §8). */
    async reconcile() {
      const { data } = await api.get<RunDto>('/api/tagging/last');
      this.run = data;
    },
    async startBulk(scope: { q?: string; untagged?: boolean }) {
      await api.post('/api/tagging/bulk', scope);
      await this.reconcile();
    },
    async refreshInfo() {
      const { data } = await api.get<TaggingInfo>('/api/tagging/info');
      this.info = data;
    },
    async predict(entryId: number, calibration?: string) {
      const { data } = await api.post<PredictResponse>(
        `/api/entries/${entryId}/autotag/predict`,
        calibration === undefined ? undefined : { calibration },
      );
      return data;
    },
    async apply(entryId: number, names: string[]) {
      const { data } = await api.post<TagDto[]>(`/api/entries/${entryId}/autotag/apply`, {
        tags: names,
      });
      return data;
    },
  },
});
