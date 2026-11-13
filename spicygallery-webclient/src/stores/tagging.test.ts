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

const { useTaggingStore } = await import('src/stores/tagging');

/** shape helper — a running bulk run as /api/tagging/last answers it */
function runningRun(overrides: Record<string, unknown> = {}) {
  return { running: true, done: 10, total: 40, errors: 1, error: null, current: 'alpha/x.png', ...overrides };
}

describe('tagging store', () => {
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
    apiGet.mockResolvedValue({ data: runningRun() });
    const store = useTaggingStore();
    store.startPolling();
    await vi.advanceTimersByTimeAsync(0);
    expect(apiGet).toHaveBeenCalledWith('/api/tagging/last');
    expect(store.run!.running).toBe(true);
    expect(store.run!.done).toBe(10);
    expect(store.run!.total).toBe(40);
    expect(store.run!.errors).toBe(1);
    expect(store.run!.current).toBe('alpha/x.png');

    await vi.advanceTimersByTimeAsync(999);
    expect(apiGet).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(apiGet).toHaveBeenCalledTimes(2);
    store.stopPolling();
  });

  it('drops to the idle cadence once the run finishes', async () => {
    apiGet
      .mockResolvedValueOnce({ data: runningRun() })
      .mockResolvedValue({ data: { running: false, done: 40, total: 40, errors: 2, error: null, current: null } });
    const store = useTaggingStore();
    store.startPolling();
    await vi.advanceTimersByTimeAsync(0);
    expect(store.run!.running).toBe(true);

    await vi.advanceTimersByTimeAsync(1_000); // fast tick sees the finished run
    expect(apiGet).toHaveBeenCalledTimes(2);
    expect(store.run!.running).toBe(false);
    expect(store.run!.error).toBeNull();

    await vi.advanceTimersByTimeAsync(14_999); // …then backs off to the idle cadence
    expect(apiGet).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(apiGet).toHaveBeenCalledTimes(3);
    store.stopPolling();
  });

  it('/last carries the authoritative error (failed run shape)', async () => {
    apiGet.mockResolvedValue({ data: { running: false, done: 3, total: 40, errors: 1, error: 'model exploded' } });
    const store = useTaggingStore();
    await store.reconcile();
    expect(store.run!.running).toBe(false);
    expect(store.run!.error).toBe('model exploded');
  });

  it('startPolling is idempotent — one poller per store, not per mount', async () => {
    apiGet.mockResolvedValue({ data: { running: false, done: 0, total: 0, errors: 0, error: null } });
    const store = useTaggingStore();
    store.startPolling();
    store.startPolling();
    store.startPolling();
    await vi.advanceTimersByTimeAsync(0);
    expect(apiGet).toHaveBeenCalledTimes(1);
    store.stopPolling();
  });

  it('stopPolling cancels pending passes', async () => {
    apiGet.mockResolvedValue({ data: { running: false, done: 0, total: 0, errors: 0, error: null } });
    const store = useTaggingStore();
    store.startPolling();
    await vi.advanceTimersByTimeAsync(0);
    store.stopPolling();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(apiGet).toHaveBeenCalledTimes(1);
  });

  it('a failed reconcile does not stop the poller', async () => {
    apiGet
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValue({ data: { running: false, done: 0, total: 0, errors: 0, error: null } });
    const store = useTaggingStore();
    store.startPolling();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(15_000);
    expect(apiGet).toHaveBeenCalledTimes(2);
    store.stopPolling();
  });

  it('startBulk posts the scope; the nudged poll reconciles', async () => {
    apiPost.mockResolvedValue({ data: { accepted: true } });
    apiGet.mockResolvedValue({ data: runningRun({ done: 0, total: 0 }) });
    const store = useTaggingStore();
    store.startPolling(); // the component starts polling on mount, before any click
    await vi.advanceTimersByTimeAsync(0);
    apiGet.mockClear();
    await store.startBulk({ untagged: true });
    expect(apiPost).toHaveBeenCalledWith('/api/tagging/bulk', { untagged: true });
    await vi.advanceTimersByTimeAsync(0); // the nudge's immediate pass
    expect(apiGet).toHaveBeenCalledWith('/api/tagging/last');
    expect(store.run!.running).toBe(true);
    store.stopPolling();
  });

  it('predict posts the optional calibration and returns the prediction', async () => {
    apiPost.mockResolvedValue({
      data: {
        model: 'hydra-3.5',
        calibration: 'f1.0@0.1',
        tags: [{ name: '1girl', category: 'general', probability: 0.97 }],
      },
    });
    const store = useTaggingStore();
    const prediction = await store.predict(7, 'f1.0@0.1');
    expect(apiPost).toHaveBeenCalledWith('/api/entries/7/autotag/predict', {
      calibration: 'f1.0@0.1',
    });
    expect(prediction.model).toBe('hydra-3.5');
    expect(prediction.tags[0]!.name).toBe('1girl');

    await store.predict(7);
    expect(apiPost).toHaveBeenLastCalledWith('/api/entries/7/autotag/predict', undefined);
  });
});
