import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';
import { FakeEventSource } from 'src/test-support/fake-event-source';
import type { TaggingInfo } from 'src/types/tagging';

const apiGet = vi.fn();
const apiPost = vi.fn();
vi.mock('src/boot/api', () => ({
  api: { get: (...args: unknown[]) => apiGet(...args), post: (...args: unknown[]) => apiPost(...args) },
}));

const { default: TaggingPanel } = await import('src/components/TaggingPanel.vue');

const info: TaggingInfo = {
  enabled: true,
  reachable: true,
  model: 'hydra-3.5',
  labels: 2,
  calibration: 'f1.0@0.1',
  implications: 'inherit',
};

function mountPanel() {
  return mount(TaggingPanel, {
    global: { plugins: [Quasar, createPinia()] },
  });
}

describe('TaggingPanel — settings page autotagging section', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiPost.mockReset();
    FakeEventSource.last = null;
    FakeEventSource.all = [];
    FakeEventSource.createdCount = 0;
    vi.stubGlobal('EventSource', FakeEventSource);
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/tagging/info') return Promise.resolve({ data: info });
      if (url === '/api/tagging/last') {
        return Promise.resolve({
          data: { running: false, done: 3, total: 5, errors: 1, error: null },
        });
      }
      return Promise.resolve({ data: null });
    });
    apiPost.mockResolvedValue({ data: { accepted: true } });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders health, model and label count from /api/tagging/info', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.find('[data-testid="tagging"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="tagging-status"]').text()).toContain('reachable');
    expect(wrapper.find('[data-testid="tagging-status"]').text()).toContain('hydra-3.5');
    expect(wrapper.find('[data-testid="tagging-config"]').text()).toContain('2');
    expect(wrapper.find('[data-testid="tagging-config"]').text()).toContain('f1.0@0.1');
  });

  it('reports a disabled / unreachable provider and disables the trigger', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/tagging/info') {
        return Promise.resolve({ data: { ...info, enabled: false, reachable: false, labels: null } });
      }
      if (url === '/api/tagging/last') {
        return Promise.resolve({ data: { running: false, done: 0, total: 0, errors: 0, error: null } });
      }
      return Promise.resolve({ data: null });
    });
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.find('[data-testid="tagging-status"]').text()).toMatch(/disabled|unreachable/);
    expect(wrapper.find('[data-testid="autotag-btn"]').attributes('disabled')).toBeDefined();
  });

  it('shows the last-run report from /api/tagging/last', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.find('[data-testid="tagging-run"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="tagging-run"]').text()).toContain('3/5');
    expect(wrapper.find('[data-testid="tagging-run"]').text()).toContain('1 errors');
  });

  it('the trigger posts the untagged bulk scope', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.find('[data-testid="autotag-btn"]').attributes('disabled')).toBeUndefined();
    await wrapper.find('[data-testid="autotag-btn"]').trigger('click');
    await flushPromises();
    expect(apiPost).toHaveBeenCalledWith('/api/tagging/bulk', { untagged: true });
  });

  it('a 409 from a run started elsewhere surfaces as a notice, not a crash', async () => {
    apiPost.mockRejectedValueOnce({ response: { status: 409 } });
    const wrapper = mountPanel();
    await flushPromises();
    await wrapper.find('[data-testid="autotag-btn"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="tagging-error"]').text()).toContain('already active');

    apiPost.mockRejectedValueOnce({ response: { status: 503 } });
    await wrapper.find('[data-testid="autotag-btn"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="tagging-error"]').text()).toContain('could not start');
  });
});
