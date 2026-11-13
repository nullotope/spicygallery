import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { FakeEventSource } from 'src/test-support/fake-event-source';

const apiGet = vi.fn();
const apiPost = vi.fn();
vi.mock('src/boot/api', () => ({
  api: {
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
  },
}));

const { useTaggingStore } = await import('src/stores/tagging');

describe('tagging store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    apiGet.mockReset();
    apiPost.mockReset();
    vi.stubGlobal('EventSource', FakeEventSource);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('connect subscribes and progress updates the run', () => {
    const store = useTaggingStore();
    store.connect();
    expect(FakeEventSource.last?.url).toBe('/api/tagging/events');

    FakeEventSource.last!.emit('progress', {
      done: 10,
      total: 40,
      current: 'alpha/x.png',
      errors: 1,
    });
    expect(store.run).not.toBeNull();
    expect(store.run!.running).toBe(true);
    expect(store.run!.done).toBe(10);
    expect(store.run!.total).toBe(40);
    expect(store.run!.errors).toBe(1);
  });

  it('connect is idempotent — one EventSource per app session, not per mount', () => {
    // same guard rationale as importStore (review I6): no SSE connection leak per mount
    FakeEventSource.createdCount = 0;
    FakeEventSource.all = [];
    const store = useTaggingStore();
    store.connect();
    store.connect();
    store.connect();
    expect(FakeEventSource.createdCount).toBe(1);
  });

  it('done triggers a /api/tagging/last reconcile (REST is the source of truth)', async () => {
    apiGet.mockResolvedValue({
      data: { running: false, done: 40, total: 40, errors: 2, error: null },
    });
    const store = useTaggingStore();
    store.connect();
    FakeEventSource.last!.emit('done', { done: 40, total: 40, current: null, errors: 2 });
    await vi.waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/tagging/last'));
    expect(store.run!.running).toBe(false);
    expect(store.run!.done).toBe(40);
  });

  it('failed also reconciles — /last carries the authoritative error', async () => {
    apiGet.mockResolvedValue({
      data: { running: false, done: 3, total: 40, errors: 1, error: 'model exploded' },
    });
    const store = useTaggingStore();
    store.connect();
    FakeEventSource.last!.emit('failed', { message: 'model exploded' });
    await vi.waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/tagging/last'));
    expect(store.run!.running).toBe(false);
    expect(store.run!.error).toBe('model exploded');
  });

  it('onerror marks disconnected and reconciles; reconnect re-fires onopen', async () => {
    // mirrors importStore's onerror pin: a dropped SSE connection must not leave
    // a stale connected badge — EventSource auto-reconnect re-fires onopen.
    apiGet.mockResolvedValue({
      data: { running: false, done: 0, total: 0, errors: 0, error: null },
    });
    const store = useTaggingStore();
    store.connect();
    FakeEventSource.last!.onopen!(); // initial connection established
    expect(store.connected).toBe(true);

    FakeEventSource.last!.onerror!();
    expect(store.connected).toBe(false);
    await vi.waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/tagging/last'));

    FakeEventSource.last!.onopen!(); // EventSource auto-reconnect
    expect(store.connected).toBe(true);
  });

  it('startBulk posts the scope and reconciles', async () => {
    apiPost.mockResolvedValue({ data: { accepted: true } });
    apiGet.mockResolvedValue({
      data: { running: true, done: 0, total: 0, errors: 0, error: null },
    });
    const store = useTaggingStore();
    await store.startBulk({ untagged: true });
    expect(apiPost).toHaveBeenCalledWith('/api/tagging/bulk', { untagged: true });
    expect(apiGet).toHaveBeenCalledWith('/api/tagging/last');
    expect(store.run!.running).toBe(true);
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

  it('apply posts the checked names and returns the updated tags', async () => {
    apiPost.mockResolvedValue({
      data: [{ id: 5, name: '1girl', category: 'general', postCount: 12 }],
    });
    const store = useTaggingStore();
    const tags = await store.apply(7, ['1girl']);
    expect(apiPost).toHaveBeenCalledWith('/api/entries/7/autotag/apply', { tags: ['1girl'] });
    expect(tags).toHaveLength(1);
    expect(tags[0]!.name).toBe('1girl');
  });
});
