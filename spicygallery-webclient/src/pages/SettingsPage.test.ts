import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';

const apiGet = vi.fn();
const apiPost = vi.fn();
vi.mock('src/boot/api', () => ({
  api: { get: (...args: unknown[]) => apiGet(...args), post: (...args: unknown[]) => apiPost(...args) },
}));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ params: {}, query: {} }),
  RouterLink: { template: '<a><slot /></a>' },
}));

const { default: SettingsPage } = await import('src/pages/SettingsPage.vue');

const systemInfo = {
  libraries: [{ name: 'library', root: '/media/library', accessible: true }],
  ffmpeg: { found: true, source: 'path', version: 'ffmpeg 6' },
  ffprobe: { found: true, source: 'path', version: 'ffprobe 6' },
  appData: '/data',
  workers: 4,
};

function status(overrides: Record<string, unknown>) {
  return {
    data: {
      enabled: true,
      model: 'embeddinggemma-2',
      breakerOpen: false,
      withEmbeddings: 5,
      missing: 2,
      ...overrides,
    },
  };
}

function mountPage() {
  return mount(SettingsPage, {
    global: { plugins: [Quasar, createPinia()] },
  });
}

describe('SettingsPage — embeddings', () => {
  let counts: { withEmbeddings: number; missing: number };
  let backfill: { running: boolean; done: number; total: number; error: string | null; current?: string | null };

  beforeEach(() => {
    apiGet.mockReset();
    apiPost.mockReset();
    counts = { withEmbeddings: 5, missing: 2 };
    backfill = { running: false, done: 0, total: 0, error: null };
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/system/info') return Promise.resolve({ data: systemInfo });
      if (url === '/api/imports/last') return Promise.resolve({ data: { run: null } });
      if (url === '/api/tagging/last') {
        return Promise.resolve({ data: { running: false, done: 0, total: 0, errors: 0, error: null } });
      }
      if (url === '/api/embeddings/status') {
        return Promise.resolve(status({ withEmbeddings: counts.withEmbeddings, missing: counts.missing }));
      }
      if (url === '/api/embeddings/backfill') {
        return Promise.resolve({ data: backfill });
      }
      return Promise.resolve({ data: null });
    });
    apiPost.mockResolvedValue({ data: { accepted: true } });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders status and counts', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const section = wrapper.find('[data-testid="embeddings"]');
    expect(section.exists()).toBe(true);
    expect(section.text()).toContain('embeddinggemma-2');
    expect(section.text()).toContain('5');
    expect(section.text()).toContain('2');
    expect(wrapper.find('[data-testid="backfill-btn"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="reembed-btn"]').exists()).toBe(true);
  });

  it('backfill button posts, polling drives progress, done reconciles', async () => {
    vi.useFakeTimers();
    try {
      const wrapper = mountPage();
      await flushPromises();
      const btn = wrapper.find('[data-testid="backfill-btn"]');
      expect(btn.attributes('disabled')).toBeUndefined();
      backfill = { running: true, done: 0, total: 3, error: null };
      await btn.trigger('click');
      expect(apiPost).toHaveBeenCalledTimes(1);
      expect(apiPost.mock.calls[0]![0]).toBe('/api/embeddings/backfill');
      // running: button disabled, progress bar appears (optimistic + nudged poll)
      await flushPromises();
      expect(wrapper.find('[data-testid="backfill-btn"]').attributes('disabled')).toBeDefined();
      expect(wrapper.find('[data-testid="backfill-progress"]').exists()).toBe(true);

      // a progress pass at the fast cadence while the run is active
      backfill = { running: true, done: 1, total: 3, error: null, current: 'a.png' };
      await vi.advanceTimersByTimeAsync(1_000);
      await flushPromises();
      expect(wrapper.find('[data-testid="backfill-progress"]').exists()).toBe(true);
      expect(wrapper.find('[data-testid="backfill-counts"]').text()).toContain('1 / 3');

      // the server reconciles to all-embedded on completion
      counts = { withEmbeddings: 7, missing: 0 };
      backfill = { running: false, done: 3, total: 3, error: null };
      await vi.advanceTimersByTimeAsync(1_000);
      await flushPromises();
      // reconciled: running cleared, button re-enabled, counts updated
      expect(wrapper.find('[data-testid="backfill-btn"]').attributes('disabled')).toBeUndefined();
      expect(wrapper.find('[data-testid="embeddings"]').text()).toContain('7'); // 5+2 now embedded
    } finally {
      vi.useRealTimers();
    }
  });

  it('409 while running shows a message', async () => {
    const err = Object.assign(new Error('Conflict'), {
      isAxiosError: true,
      response: { status: 409, data: { detail: 'backfill already running' } },
    });
    apiPost.mockRejectedValueOnce(err);
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="backfill-btn"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="embeddings"]').text()).toContain('already running');
  });

  it('re-embed all posts force=true', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="reembed-btn"]').trigger('click');
    expect(apiPost).toHaveBeenCalledTimes(1);
    const [url, , config] = apiPost.mock.calls[0]!;
    expect(url).toBe('/api/embeddings/backfill');
    expect(config?.params).toEqual({ force: true });
  });

  it('disabled provider shows a disabled state', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/system/info') return Promise.resolve({ data: systemInfo });
      if (url === '/api/imports/last') return Promise.resolve({ data: { run: null } });
      if (url === '/api/embeddings/status') {
        return Promise.resolve(status({ enabled: false, breakerOpen: true }));
      }
      if (url === '/api/embeddings/backfill') {
        return Promise.resolve({ data: { running: false, done: 0, total: 0, error: null } });
      }
      return Promise.resolve({ data: null });
    });
    const wrapper = mountPage();
    await flushPromises();
    const section = wrapper.find('[data-testid="embeddings"]');
    expect(section.text()).toMatch(/disabled|unreachable/);
    expect(wrapper.find('[data-testid="backfill-btn"]').attributes('disabled')).toBeDefined();
  });
});
