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

const { useImportStore } = await import('src/stores/importStore');

describe('import store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    apiGet.mockReset();
    apiPost.mockReset();
    vi.stubGlobal('EventSource', FakeEventSource);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('connect subscribes and progress updates counters', () => {
    const store = useImportStore();
    store.connect();
    expect(FakeEventSource.last?.url).toBe('/api/imports/events');

    FakeEventSource.last!.emit('progress', {
      runId: 1,
      scanned: 10,
      added: 7,
      updated: 1,
      missing: 2,
      errors: 0,
      current: 'alpha/x.png',
    });
    expect(store.run).not.toBeNull();
    expect(store.run!.scanned).toBe(10);
    expect(store.run!.added).toBe(7);
  });

  it('connect is idempotent — one EventSource per app session, not per mount', () => {
    // review I6: remounting import UI must not leak one SSE connection per mount
    // (HTTP/1.1 caps ~6 per host)
    FakeEventSource.createdCount = 0;
    FakeEventSource.all = [];
    const store = useImportStore();
    store.connect();
    store.connect();
    store.connect();
    expect(FakeEventSource.createdCount).toBe(1);
  });

  it('done triggers a /api/imports/last reconcile (REST is the source of truth)', async () => {
    apiGet.mockResolvedValue({
      data: {
        run: {
          id: 1,
          status: 'completed',
          startedAt: '2026-11-08T10:00:00Z',
          finishedAt: '2026-11-08T10:01:00Z',
          scanned: 12,
          added: 10,
          updated: 1,
          missingCount: 1,
          errorCount: 0,
          message: null,
        },
      },
    });
    const store = useImportStore();
    store.connect();
    FakeEventSource.last!.emit('done', { runId: 1, status: 'completed' });
    await vi.waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/imports/last'));
    expect(store.run!.status).toBe('completed');
    expect(store.run!.added).toBe(10);
  });

  it('onerror marks disconnected and reconciles', async () => {
    apiGet.mockResolvedValue({ data: { run: null } });
    const store = useImportStore();
    store.connect();
    FakeEventSource.last!.onerror!();
    expect(store.connected).toBe(false);
    await vi.waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/imports/last'));
    expect(store.run).toBeNull();
  });

  it('startImport posts and reconciles (flag sent explicitly, off)', async () => {
    apiPost.mockResolvedValue({ data: { runId: 9 } });
    apiGet.mockResolvedValue({ data: { run: { id: 9, status: 'running', added: 0 } } });
    const store = useImportStore();
    await store.startImport(false);
    expect(apiPost).toHaveBeenCalledWith('/api/imports', { createPool: false });
    expect(store.run!.id).toBe(9);
  });

  it('startImport sends createPool: true when the pool follow-up is checked', async () => {
    apiPost.mockResolvedValue({ data: { runId: 9 } });
    apiGet.mockResolvedValue({ data: { run: { id: 9, status: 'running', added: 0 } } });
    const store = useImportStore();
    await store.startImport(true);
    expect(apiPost).toHaveBeenCalledWith('/api/imports', { createPool: true });
  });
});
