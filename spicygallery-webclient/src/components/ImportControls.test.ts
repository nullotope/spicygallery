import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';
import { FakeEventSource } from 'src/test-support/fake-event-source';
import type { RunDto } from 'src/stores/importStore';

const apiPost = vi.fn();
let lastRun: { data: { run: RunDto | null } } = { data: { run: null } };
vi.mock('src/boot/api', () => ({
  api: {
    get: (url: string) =>
      url === '/api/imports/last' ? Promise.resolve(lastRun) : Promise.resolve({ data: null }),
    post: (...args: unknown[]) => apiPost(...args),
  },
}));

// data-to mirrors the `to` prop onto the DOM so link targets are assertable
vi.mock('vue-router', () => ({
  RouterLink: { props: ['to'], template: '<a class="router-link-stub" :data-to="to"><slot /></a>' },
}));

const { default: ImportControls } = await import('src/components/ImportControls.vue');
const { useImportStore } = await import('src/stores/importStore');

function mountControls(pinia = createPinia()) {
  return mount(ImportControls, {
    global: { plugins: [Quasar, pinia] },
  });
}

function emitProgress(stats: {
  scanned: number;
  added: number;
  updated: number;
  missing: number;
  errors: number;
  processed?: number;
  current?: string | null;
}) {
  // spread last: absent `processed` stays absent (legacy-shape events → indeterminate bar)
  FakeEventSource.last?.emit('progress', { current: null, ...stats });
}

function dialogEl(): HTMLElement | null {
  return document.querySelector('[data-testid="import-dialog"]');
}

function runningRun(): RunDto {
  return {
    id: 1,
    status: 'running',
    startedAt: '2026-11-08T00:00:00Z',
    finishedAt: null,
    scanned: 0,
    added: 0,
    updated: 0,
    missingCount: 0,
    errorCount: 0,
    message: null,
  };
}

describe('ImportControls — header import button + seamless progress dialog', () => {
  beforeEach(() => {
    FakeEventSource.last = null;
    FakeEventSource.all = [];
    FakeEventSource.createdCount = 0;
    lastRun = { data: { run: null } };
    vi.stubGlobal('EventSource', FakeEventSource);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    document.querySelectorAll('.q-dialog').forEach((el) => el.remove()); // q-dialog teleports to body
  });

  it('seamless bottom dialog shows live counters while a run is active', async () => {
    mountControls();
    await flushPromises();
    emitProgress({ scanned: 5, added: 2, updated: 1, missing: 0, errors: 0 });
    await flushPromises();
    const dialog = dialogEl();
    expect(dialog).not.toBeNull();
    expect(dialog?.textContent).toContain('5 scanned');
    expect(dialog?.textContent).toContain('2 added');
    expect(dialog?.textContent).toContain('1 updated');
  });

  it('progress dialog shows processed/total with a determinate bar and the current file', async () => {
    mountControls();
    await flushPromises();
    emitProgress({
      scanned: 10, processed: 3, added: 2, updated: 1, missing: 0, errors: 0,
      current: 'downloads/sub/page.jpg',
    });
    await flushPromises();
    const line = document.querySelector('[data-testid="import-progress-line"]');
    expect(line?.textContent).toContain('processing 3 / 10');
    expect(line?.textContent).toContain('30%');
    expect(line?.textContent).toContain('downloads/sub/page.jpg');
    const bar = document.querySelector('[data-testid="import-progress"]');
    // Quasar marks indeterminacy on the model child, not the root
    expect(bar?.querySelector('.q-linear-progress__model--determinate')).not.toBeNull();
    expect(bar?.querySelector('.q-linear-progress__model--indeterminate')).toBeNull();
    expect(bar?.getAttribute('aria-valuenow')).toBe('0.3');
  });

  it('legacy progress events without processed keep the indeterminate bar and no progress line', async () => {
    mountControls();
    await flushPromises();
    emitProgress({ scanned: 5, added: 2, updated: 1, missing: 0, errors: 0 });
    await flushPromises();
    expect(document.querySelector('[data-testid="import-progress-line"]')).toBeNull();
    const bar = document.querySelector('[data-testid="import-progress"]');
    expect(bar?.querySelector('.q-linear-progress__model--indeterminate')).not.toBeNull();
  });

  it('dismissed dialog reopens from the button while the run is active — no second import', async () => {
    const wrapper = mountControls();
    await flushPromises();
    emitProgress({ scanned: 5, added: 2, updated: 1, missing: 0, errors: 0 });
    await flushPromises();
    expect(dialogEl()).not.toBeNull();

    (document.querySelector('[data-testid="import-dialog-close"]') as HTMLElement).click();
    await flushPromises();
    expect(dialogEl()).toBeNull();

    apiPost.mockClear();
    await wrapper.find('[data-testid="import-btn"]').trigger('click');
    await flushPromises();
    expect(apiPost).not.toHaveBeenCalled();
    expect(dialogEl()).not.toBeNull();
  });

  it('shows the final report when the run completes, until dismissed', async () => {
    mountControls();
    await flushPromises();
    const source = FakeEventSource.last;
    expect(source).not.toBeNull();
    emitProgress({ scanned: 5, added: 2, updated: 1, missing: 0, errors: 0 });
    await flushPromises();

    lastRun = {
      data: {
        run: {
          id: 1,
          status: 'completed',
          startedAt: '2026-11-08T00:00:00Z',
          finishedAt: '2026-11-08T00:01:00Z',
          scanned: 10,
          added: 3,
          updated: 2,
          missingCount: 1,
          errorCount: 0,
          message: null,
        },
      },
    };
    source!.emit('done', {});
    await flushPromises();

    const dialog = dialogEl();
    expect(dialog).not.toBeNull();
    expect(dialog?.textContent).toContain('completed');
    expect(dialog?.textContent).toContain('10 scanned');
    expect(dialog?.textContent).toContain('3 added');
  });

  it('flags the button when the last run failed, without opening the dialog', async () => {
    lastRun = {
      data: {
        run: {
          id: 1,
          status: 'failed',
          startedAt: '2026-11-08T00:00:00Z',
          finishedAt: '2026-11-08T00:01:00Z',
          scanned: 4,
          added: 0,
          updated: 0,
          missingCount: 0,
          errorCount: 2,
          message: 'boom',
        },
      },
    };
    const wrapper = mountControls();
    await flushPromises();
    expect(wrapper.find('[data-testid="import-failed-badge"]').exists()).toBe(true);
    expect(dialogEl()).toBeNull();
  });

  it('keeps one EventSource per app session (store guard, review I6)', async () => {
    const pinia = createPinia();
    mountControls(pinia);
    await flushPromises();
    mountControls(pinia);
    await flushPromises();
    expect(FakeEventSource.createdCount).toBe(1);
    expect(FakeEventSource.last?.url).toBe('/api/imports/events');
  });

  it('button opens the import modal instead of posting directly', async () => {
    const wrapper = mountControls();
    await flushPromises();
    await wrapper.find('[data-testid="import-btn"]').trigger('click');
    await flushPromises();
    expect(document.querySelector('[data-testid="import-modal"]')).not.toBeNull();
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('modal offers the auto-tag follow-up, checked by default', async () => {
    const wrapper = mountControls();
    await flushPromises();
    await wrapper.find('[data-testid="import-btn"]').trigger('click');
    await flushPromises();
    const inner = document.querySelector(
      '[data-testid="import-followup"] .q-checkbox__inner',
    ) as HTMLElement;
    expect(inner).not.toBeNull();
    expect(inner.className).toContain('q-checkbox__inner--truthy');
  });

  it('modal start posts the import and closes the modal', async () => {
    const wrapper = mountControls();
    await flushPromises();
    await wrapper.find('[data-testid="import-btn"]').trigger('click');
    await flushPromises();
    (document.querySelector('[data-testid="import-start"]') as HTMLElement).click();
    await flushPromises();
    expect(apiPost).toHaveBeenCalledWith('/api/imports', { createPool: false });
    expect(document.querySelector('[data-testid="import-modal"]')).toBeNull();
  });

  it('completed run fires the checked follow-up: bulk autotag over untagged', async () => {
    const pinia = createPinia();
    const wrapper = mountControls(pinia);
    await flushPromises();
    await wrapper.find('[data-testid="import-btn"]').trigger('click');
    await flushPromises();
    (document.querySelector('[data-testid="import-start"]') as HTMLElement).click();
    await flushPromises();
    apiPost.mockClear();

    const source = FakeEventSource.last;
    expect(source).not.toBeNull();
    emitProgress({ scanned: 5, added: 5, updated: 0, missing: 0, errors: 0 });
    await flushPromises();
    lastRun = {
      data: {
        run: {
          id: 2,
          status: 'completed',
          startedAt: '2026-11-09T00:00:00Z',
          finishedAt: '2026-11-09T00:01:00Z',
          scanned: 5,
          added: 5,
          updated: 0,
          missingCount: 0,
          errorCount: 0,
          message: null,
        },
      },
    };
    source!.emit('done', {});
    await flushPromises();

    const bulkCalls = apiPost.mock.calls.filter(([url]) => url === '/api/tagging/bulk');
    expect(bulkCalls.length).toBe(1);
    expect(bulkCalls[0]?.[1]).toEqual({ untagged: true });
  });

  it('unchecked follow-up does not fire the bulk run', async () => {
    const pinia = createPinia();
    const wrapper = mountControls(pinia);
    await flushPromises();
    await wrapper.find('[data-testid="import-btn"]').trigger('click');
    await flushPromises();
    (document.querySelector('[data-testid="import-followup"] input') as HTMLInputElement).click();
    (document.querySelector('[data-testid="import-start"]') as HTMLElement).click();
    await flushPromises();
    apiPost.mockClear();

    const source = FakeEventSource.last;
    emitProgress({ scanned: 5, added: 5, updated: 0, missing: 0, errors: 0 });
    await flushPromises();
    lastRun = {
      data: {
        run: {
          id: 2,
          status: 'completed',
          startedAt: '2026-11-09T00:00:00Z',
          finishedAt: '2026-11-09T00:01:00Z',
          scanned: 5,
          added: 5,
          updated: 0,
          missingCount: 0,
          errorCount: 0,
          message: null,
        },
      },
    };
    source!.emit('done', {});
    await flushPromises();

    expect(apiPost.mock.calls.filter(([url]) => url === '/api/tagging/bulk').length).toBe(0);
  });

  it('failed run does not fire the follow-up', async () => {
    const pinia = createPinia();
    const wrapper = mountControls(pinia);
    await flushPromises();
    await wrapper.find('[data-testid="import-btn"]').trigger('click');
    await flushPromises();
    (document.querySelector('[data-testid="import-start"]') as HTMLElement).click();
    await flushPromises();
    apiPost.mockClear();

    const source = FakeEventSource.last;
    emitProgress({ scanned: 5, added: 0, updated: 0, missing: 0, errors: 5 });
    await flushPromises();
    lastRun = {
      data: {
        run: {
          id: 2,
          status: 'failed',
          startedAt: '2026-11-09T00:00:00Z',
          finishedAt: '2026-11-09T00:01:00Z',
          scanned: 5,
          added: 0,
          updated: 0,
          missingCount: 0,
          errorCount: 5,
          message: 'boom',
        },
      },
    };
    source!.emit('done', {});
    await flushPromises();

    expect(apiPost.mock.calls.filter(([url]) => url === '/api/tagging/bulk').length).toBe(0);
  });

  it('modal offers the pool follow-up, unchecked by default, below the auto-tag checkbox', async () => {
    const wrapper = mountControls();
    await flushPromises();
    await wrapper.find('[data-testid="import-btn"]').trigger('click');
    await flushPromises();
    const autoTag = document.querySelector('[data-testid="import-followup"]');
    const pool = document.querySelector('[data-testid="import-pool-followup"]');
    expect(pool).not.toBeNull();
    const inner = document.querySelector(
      '[data-testid="import-pool-followup"] .q-checkbox__inner',
    ) as HTMLElement;
    expect(inner.className).not.toContain('q-checkbox__inner--truthy');
    // spec §4.4: the pool follow-up sits below the auto-tag follow-up
    expect(
      !!(autoTag!.compareDocumentPosition(pool!) & Node.DOCUMENT_POSITION_FOLLOWING),
    ).toBe(true);
  });

  it('checked pool follow-up posts createPool: true', async () => {
    const wrapper = mountControls();
    await flushPromises();
    await wrapper.find('[data-testid="import-btn"]').trigger('click');
    await flushPromises();
    (document.querySelector(
      '[data-testid="import-pool-followup"] input',
    ) as HTMLInputElement).click();
    (document.querySelector('[data-testid="import-start"]') as HTMLElement).click();
    await flushPromises();
    expect(apiPost).toHaveBeenCalledWith('/api/imports', { createPool: true });
  });

  it('completed run that drafted a pool links to it in the report', async () => {
    const wrapper = mountControls();
    await flushPromises();
    await wrapper.find('[data-testid="import-btn"]').trigger('click');
    await flushPromises();
    (document.querySelector('[data-testid="import-start"]') as HTMLElement).click();
    await flushPromises();
    apiPost.mockClear();

    const source = FakeEventSource.last;
    expect(source).not.toBeNull();
    emitProgress({ scanned: 5, added: 5, updated: 0, missing: 0, errors: 0 });
    await flushPromises();
    lastRun = {
      data: {
        run: {
          id: 2,
          status: 'completed',
          startedAt: '2026-11-09T00:00:00Z',
          finishedAt: '2026-11-09T00:01:00Z',
          scanned: 5,
          added: 5,
          updated: 0,
          missingCount: 0,
          errorCount: 0,
          message: null,
          poolId: 7,
        },
      },
    };
    source!.emit('done', {});
    await flushPromises();

    const link = document.querySelector('[data-testid="import-pool-link"]');
    expect(link).not.toBeNull();
    expect(link?.getAttribute('data-to')).toBe('/pools/7');
    expect(document.querySelector('[data-testid="import-pool-none"]')).toBeNull();
  });

  it('pool follow-up on but nothing drafted: report shows the nothing-new note', async () => {
    const wrapper = mountControls();
    await flushPromises();
    await wrapper.find('[data-testid="import-btn"]').trigger('click');
    await flushPromises();
    (document.querySelector(
      '[data-testid="import-pool-followup"] input',
    ) as HTMLInputElement).click();
    (document.querySelector('[data-testid="import-start"]') as HTMLElement).click();
    await flushPromises();

    const source = FakeEventSource.last;
    emitProgress({ scanned: 5, added: 5, updated: 0, missing: 0, errors: 0 });
    await flushPromises();
    lastRun = {
      data: {
        run: {
          id: 2,
          status: 'completed',
          startedAt: '2026-11-09T00:00:00Z',
          finishedAt: '2026-11-09T00:01:00Z',
          scanned: 5,
          added: 5,
          updated: 0,
          missingCount: 0,
          errorCount: 0,
          message: null,
          poolId: null,
        },
      },
    };
    source!.emit('done', {});
    await flushPromises();

    const note = document.querySelector('[data-testid="import-pool-none"]');
    expect(note).not.toBeNull();
    expect(note?.textContent).toContain('nothing new — no pool created');
    expect(document.querySelector('[data-testid="import-pool-link"]')).toBeNull();
  });

  it('pool follow-up off: report shows neither pool link nor nothing-new note', async () => {
    const wrapper = mountControls();
    await flushPromises();
    await wrapper.find('[data-testid="import-btn"]').trigger('click');
    await flushPromises();
    (document.querySelector('[data-testid="import-start"]') as HTMLElement).click();
    await flushPromises();

    const source = FakeEventSource.last;
    emitProgress({ scanned: 5, added: 5, updated: 0, missing: 0, errors: 0 });
    await flushPromises();
    lastRun = {
      data: {
        run: {
          id: 2,
          status: 'completed',
          startedAt: '2026-11-09T00:00:00Z',
          finishedAt: '2026-11-09T00:01:00Z',
          scanned: 5,
          added: 5,
          updated: 0,
          missingCount: 0,
          errorCount: 0,
          message: null,
          poolId: null,
        },
      },
    };
    source!.emit('done', {});
    await flushPromises();

    expect(document.querySelector('[data-testid="import-pool-link"]')).toBeNull();
    expect(document.querySelector('[data-testid="import-pool-none"]')).toBeNull();
  });
});
