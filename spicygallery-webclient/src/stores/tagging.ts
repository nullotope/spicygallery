import { defineStore } from 'pinia';
import { api } from 'src/boot/api';
import { createRunPoller, type RunPoller } from 'src/composables/run-poller';
import type { PredictResponse, TaggingInfo } from 'src/types/tagging';
import type { TagDto } from 'src/types/tags';

/** The most recently started bulk run — mirrors TaggingBackfill.RunStatus. */
export interface RunDto {
  running: boolean;
  done: number;
  total: number;
  errors: number;
  error: string | null;
  /** transient live-progress detail — the entry the run is currently on */
  current?: string | null;
}

/**
 * Tagging run state. Polled, not pushed: the SSE transport held one browser socket
 * open per tab forever and starved the HTTP/1.1 6-connections-per-host pool. REST
 * stays the source of truth (spec §8) — the poller reconciles /api/tagging/last at
 * a fast cadence while a run is active and a slow one otherwise (paused entirely
 * for background tabs; see run-poller).
 */
export const useTaggingStore = defineStore('tagging', {
  state: () => ({
    info: null as TaggingInfo | null,
    run: null as RunDto | null,
    poller: null as RunPoller | null,
  }),
  actions: {
    /** Idempotent: one poller per app session regardless of mount count. */
    startPolling() {
      if (this.poller) {
        this.poller.start();
        return;
      }
      this.poller = createRunPoller({ work: () => this.reconcile() });
      this.poller.start();
    },
    stopPolling() {
      this.poller?.stop();
    },
    /** REST reconcile — the source of truth (spec §8). Resolves to whether a run
     *  is active (drives the poller's cadence). */
    async reconcile(): Promise<boolean> {
      const { data } = await api.get<RunDto>('/api/tagging/last');
      this.run = data;
      return data.running;
    },
    async startBulk(scope: { q?: string; untagged?: boolean }) {
      await api.post('/api/tagging/bulk', scope);
      this.poller?.nudge(); // immediate progress read + fast cadence from here
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
