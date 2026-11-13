import { defineStore } from 'pinia';
import { api } from 'src/boot/api';
import { createRunPoller, type RunPoller } from 'src/composables/run-poller';

export interface RunDto {
  id: number;
  status: 'running' | 'completed' | 'failed';
  startedAt: string;
  finishedAt: string | null;
  scanned: number;
  added: number;
  updated: number;
  missingCount: number;
  errorCount: number;
  message: string | null;
  /** id of the pool drafted from this run's new imports, if any (slice6) */
  poolId?: number | null;
  /** transient live-progress fields — present only while the run is active */
  processed?: number;
  current?: string | null;
}

/**
 * Import run state. Polled, not pushed: the SSE transport held one browser socket
 * open per tab forever and starved the HTTP/1.1 6-connections-per-host pool. REST
 * stays the source of truth (spec §8) — the poller reconciles /api/imports/last at
 * a fast cadence while a run is active and a slow one otherwise (paused entirely
 * for background tabs; see run-poller).
 */
export const useImportStore = defineStore('import', {
  state: () => ({
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
      const { data } = await api.get<{ run: RunDto | null }>('/api/imports/last');
      this.run = data.run;
      return data.run?.status === 'running';
    },
    async startImport(createPool: boolean) {
      await api.post('/api/imports', { createPool });
      this.poller?.nudge(); // immediate progress read + fast cadence from here
    },
  },
});
