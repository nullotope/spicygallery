import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';
import type { RunDto } from 'src/stores/tagging';

const apiGet = vi.fn();
const apiPost = vi.fn();
let lastRun: { data: RunDto } = {
  data: { running: false, done: 0, total: 0, errors: 0, error: null },
};
vi.mock('src/boot/api', () => ({
  api: {
    get: (url: string) => {
      apiGet(url);
      return url === '/api/tagging/last'
        ? Promise.resolve(lastRun)
        : Promise.resolve({ data: null });
    },
    post: (...args: unknown[]) => apiPost(...args),
  },
}));

const { default: TaggingControls } = await import('src/components/TaggingControls.vue');
const { useEntriesStore } = await import('src/stores/entries');

function mountControls(pinia = createPinia()) {
  return mount(TaggingControls, {
    global: { plugins: [Quasar, pinia] },
  });
}

/** one poll pass after the poller scheduled its next tick (idle cadence) */
async function pollTick() {
  await vi.advanceTimersByTimeAsync(15_000);
  await flushPromises();
}

function dialogEl(): HTMLElement | null {
  return document.querySelector('[data-testid="tagging-dialog"]');
}

describe('TaggingControls — sidebar bulk actions + seamless progress dialog', () => {
  beforeEach(() => {
    lastRun = { data: { running: false, done: 0, total: 0, errors: 0, error: null } };
    apiGet.mockClear();
    apiPost.mockClear();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    document.querySelectorAll('.q-dialog').forEach((el) => el.remove()); // q-dialog teleports to body
  });

  it('"Autotag results" is disabled without a query and posts the q scope when enabled', async () => {
    const pinia = createPinia();
    const wrapper = mountControls(pinia);
    await flushPromises();
    const results = () => wrapper.find('[data-testid="tag-results-btn"]');
    expect(results().attributes('disabled')).toBeDefined(); // q empty → no search intent (spec §4.2)

    useEntriesStore(pinia).q = 'duck';
    await flushPromises();
    expect(results().attributes('disabled')).toBeUndefined();

    await results().trigger('click');
    await flushPromises();
    expect(apiPost).toHaveBeenCalledWith('/api/tagging/bulk', { q: 'duck' });
  });

  it('"Autotag untagged" works with an empty query and posts the untagged scope', async () => {
    const wrapper = mountControls();
    await flushPromises();
    const untagged = wrapper.find('[data-testid="tag-untagged-btn"]');
    expect(untagged.attributes('disabled')).toBeUndefined();
    await untagged.trigger('click');
    await flushPromises();
    expect(apiPost).toHaveBeenCalledWith('/api/tagging/bulk', { untagged: true });
  });

  it('seamless bottom dialog auto-opens with live counters and the current entry', async () => {
    // a run already active when the page loads (e.g. reload mid-run) — the mount-time
    // poll sees it and opens the monitor
    lastRun = { data: { running: true, done: 2, total: 10, errors: 1, error: null, current: 'duck.jpg' } };
    mountControls();
    await flushPromises();

    const dialog = dialogEl();
    expect(dialog).not.toBeNull();
    expect(dialog?.textContent).toContain('2/10 tagged');
    expect(dialog?.textContent).toContain('1 errors');
    expect(dialog?.textContent).toContain('duck.jpg');
    expect(document.querySelector('[data-testid="tagging-progress"]')).not.toBeNull();
  });

  it('dismissed dialog reopens from a button while the run is active — no second start', async () => {
    lastRun = { data: { running: true, done: 2, total: 10, errors: 0, error: null, current: 'duck.jpg' } };
    const wrapper = mountControls();
    await flushPromises();
    expect(dialogEl()).not.toBeNull();

    (document.querySelector('[data-testid="tagging-dialog-close"]') as HTMLElement).click();
    await flushPromises();
    expect(dialogEl()).toBeNull();

    apiPost.mockClear();
    await wrapper.find('[data-testid="tag-untagged-btn"]').trigger('click');
    await flushPromises();
    expect(apiPost).not.toHaveBeenCalled();
    expect(dialogEl()).not.toBeNull();
  });

  it('shows the final report when the run completes, until dismissed', async () => {
    lastRun = { data: { running: true, done: 2, total: 10, errors: 0, error: null, current: 'duck.jpg' } };
    mountControls();
    await flushPromises();
    expect(dialogEl()).not.toBeNull();

    lastRun = { data: { running: false, done: 10, total: 10, errors: 2, error: null } };
    await pollTick();

    const dialog = dialogEl();
    expect(dialog).not.toBeNull();
    expect(dialog?.textContent).toContain('10/10 tagged');
    expect(dialog?.textContent).toContain('2 errors');
    expect(dialog?.textContent).not.toContain('duck.jpg'); // current entry is a live-progress-only detail
  });

  it('shows the run error message on a failed run', async () => {
    lastRun = { data: { running: true, done: 3, total: 10, errors: 2, error: null, current: 'cat.jpg' } };
    mountControls();
    await flushPromises();

    lastRun = { data: { running: false, done: 3, total: 10, errors: 2, error: 'provider down' } };
    await pollTick();

    const dialog = dialogEl();
    expect(dialog).not.toBeNull();
    expect(dialog?.textContent).toContain('3/10 tagged');
    expect(dialog?.textContent).toContain('provider down');
  });

  it('does not open the dialog for an idle reconcile result', async () => {
    lastRun = { data: { running: false, done: 4, total: 4, errors: 0, error: null } };
    mountControls();
    await flushPromises();
    expect(dialogEl()).toBeNull();
  });

  it('a 409 from a second start surfaces as a notice, not a crash', async () => {
    const pinia = createPinia();
    useEntriesStore(pinia).q = 'duck';
    const wrapper = mountControls(pinia);
    await flushPromises();

    apiPost.mockRejectedValueOnce({ response: { status: 409 } });
    await wrapper.find('[data-testid="tag-results-btn"]').trigger('click');
    await flushPromises();
    const notice = wrapper.find('[data-testid="tag-notice"]');
    expect(notice.exists()).toBe(true);
    expect(notice.text()).toContain('already active');

    apiPost.mockRejectedValueOnce({ response: { status: 503 } });
    await wrapper.find('[data-testid="tag-results-btn"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="tag-notice"]').text()).toContain('could not start');
  });

  it('keeps one poller per app session across mounts (store guard)', async () => {
    const pinia = createPinia();
    mountControls(pinia);
    await flushPromises();
    mountControls(pinia);
    await flushPromises();
    // one immediate reconcile from the first mount's poller start — the second
    // mount's startPolling is idempotent and does not re-poll
    expect(apiGet).toHaveBeenCalledTimes(1);
  });
});
