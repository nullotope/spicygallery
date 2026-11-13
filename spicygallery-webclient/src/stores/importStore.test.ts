import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const apiGet = vi.fn();
const apiPost = vi.fn();
vi.mock('src/boot/api', () => ({
  api: {
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
  },
}));

const { useImportStore } = await import('src/stores/importStore');

/** shape helper — a running run as /api/imports/last answers it (with transient fields) */
function runningRun(overrides: Record<string, unknown> = {}) {
  return {
    id: 9,
    status: 'running',
    startedAt: '2026-11-13T10:00:00Z',
    finishedAt: null,
    scanned: 100,
    added: 0,
    updated: 0,
    missingCount: 0,
    errorCount: 0,
    message: null,
    processed: 40,
    current: 'downloads/alpha/x.png',
    ...overrides,
  };
}

describe('import store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    apiGet.mockReset();
    apiPost.mockReset();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('startPolling reconciles immediately and keeps the fast cadence while a run is active', async () => {
    apiGet.mockResolvedValue({ data: { run: runningRun() } });
    const store = useImportStore();
    store.startPolling();
    await vi.advanceTimersByTimeAsync(0);
    expect(apiGet).toHaveBeenCalledWith('/api/imports/last');
    expect(store.run!.processed).toBe(40);
    expect(store.run!.current).toBe('downloads/alpha/x.png');

    await vi.advanceTimersByTimeAsync(999);
    expect(apiGet).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(apiGet).toHaveBeenCalledTimes(2);
    store.stopPolling();
  });

  it('drops to the idle cadence once no run is active', async () => {
    apiGet
      .mockResolvedValueOnce({ data: { run: runningRun() } })
      .mockResolvedValue({ data: { run: runningRun({ status: 'completed', processed: undefined, current: undefined }) } });
    const store = useImportStore();
    store.startPolling();
    await vi.advanceTimersByTimeAsync(0);
    expect(store.run!.status).toBe('running');

    await vi.advanceTimersByTimeAsync(1_000); // fast tick sees the completed run
    expect(apiGet).toHaveBeenCalledTimes(2);
    expect(store.run!.status).toBe('completed');

    await vi.advanceTimersByTimeAsync(14_999); // …then backs off to the idle cadence
    expect(apiGet).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(apiGet).toHaveBeenCalledTimes(3);
    store.stopPolling();
  });

  it('startPolling is idempotent — one poller per store, not per mount', async () => {
    apiGet.mockResolvedValue({ data: { run: null } });
    const store = useImportStore();
    store.startPolling();
    store.startPolling();
    store.startPolling();
    await vi.advanceTimersByTimeAsync(0);
    expect(apiGet).toHaveBeenCalledTimes(1);
    store.stopPolling();
  });

  it('stopPolling cancels pending passes', async () => {
    apiGet.mockResolvedValue({ data: { run: null } });
    const store = useImportStore();
    store.startPolling();
    await vi.advanceTimersByTimeAsync(0);
    store.stopPolling();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(apiGet).toHaveBeenCalledTimes(1);
  });

  it('a failed reconcile does not stop the poller', async () => {
    apiGet.mockRejectedValueOnce(new Error('boom')).mockResolvedValue({ data: { run: null } });
    const store = useImportStore();
    store.startPolling();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(15_000);
    expect(apiGet).toHaveBeenCalledTimes(2);
    store.stopPolling();
  });

  it('startImport posts, then the nudged poll reconciles (flag sent explicitly, off)', async () => {
    apiPost.mockResolvedValue({ data: { runId: 9 } });
    apiGet.mockResolvedValue({ data: { run: runningRun({ processed: undefined, current: undefined }) } });
    const store = useImportStore();
    store.startPolling(); // the component starts polling on mount, before any click
    await vi.advanceTimersByTimeAsync(0);
    apiGet.mockClear();
    await store.startImport(false);
    expect(apiPost).toHaveBeenCalledWith('/api/imports', { createPool: false });
    await vi.advanceTimersByTimeAsync(0); // the nudge's immediate pass
    expect(store.run!.id).toBe(9);
    store.stopPolling();
  });

  it('startImport sends createPool: true when the pool follow-up is checked', async () => {
    apiPost.mockResolvedValue({ data: { runId: 9 } });
    apiGet.mockResolvedValue({ data: { run: runningRun() } });
    const store = useImportStore();
    await store.startImport(true);
    expect(apiPost).toHaveBeenCalledWith('/api/imports', { createPool: true });
  });
});
