import { defineStore } from 'pinia';
import { api } from 'src/boot/api';

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
  /** transient SSE progress fields — not persisted on the run row (REST reconcile omits them) */
  processed?: number;
  current?: string | null;
}

interface ProgressStats {
  scanned: number;
  processed: number;
  added: number;
  updated: number;
  missing: number;
  errors: number;
  current: string | null;
}

/**
 * Import run state. SSE notifies, REST reconciles (single-mapper rule):
 * onerror and done always refetch /api/imports/last.
 */
export const useImportStore = defineStore('import', {
  state: () => ({
    run: null as RunDto | null,
    connected: false,
    /** One EventSource per app session — ImportControls lives in App.vue and
     *  mounts once; the guard also protects against accidental double connects
     *  (review I6). */
    source: null as EventSource | null,
  }),
  actions: {
    connect() {
      if (this.source) {
        return;
      }
      const source = new EventSource('/api/imports/events');
      this.source = source;
      source.onopen = () => {
        this.connected = true;
      };
      source.addEventListener('progress', (event) => {
        const stats = JSON.parse((event as MessageEvent).data) as ProgressStats;
        const base: RunDto = this.run ?? {
          // the server's ImportStats carries no runId — the id comes from REST reconcile
          id: 0,
          status: 'running',
          startedAt: new Date().toISOString(),
          finishedAt: null,
          scanned: 0,
          added: 0,
          updated: 0,
          missingCount: 0,
          errorCount: 0,
          message: null,
        };
        this.run = {
          ...base,
          status: 'running',
          scanned: stats.scanned,
          processed: stats.processed,
          added: stats.added,
          updated: stats.updated,
          missingCount: stats.missing,
          errorCount: stats.errors,
          current: stats.current,
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
      const { data } = await api.get<{ run: RunDto | null }>('/api/imports/last');
      this.run = data.run;
    },
    async startImport(createPool: boolean) {
      await api.post('/api/imports', { createPool });
      await this.reconcile();
    },
  },
});
