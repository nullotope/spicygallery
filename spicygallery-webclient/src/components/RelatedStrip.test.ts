import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Quasar } from 'quasar';

const apiGet = vi.fn();
vi.mock('src/boot/api', () => ({
  api: { get: (...args: unknown[]) => apiGet(...args) },
}));

const push = vi.fn();
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  RouterLink: { template: '<a><slot /></a>' },
}));

const { default: RelatedStrip } = await import('src/components/RelatedStrip.vue');

function item(id: number, fileName: string) {
  return {
    id,
    kind: 'image',
    fileName,
    width: 10,
    height: 10,
    durationMs: null,
    thumbUrl: `/api/entries/${id}/thumb?v=1`,
  };
}

function mountStrip(entryId: number) {
  return mount(RelatedStrip, {
    props: { entryId },
    global: { plugins: [Quasar] },
  });
}

describe('RelatedStrip', () => {
  beforeEach(() => {
    apiGet.mockReset();
    push.mockReset();
    apiGet.mockResolvedValue({ data: { items: [item(1, 'a.png'), item(2, 'b.png')] } });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders related tiles and navigates on click', async () => {
    const wrapper = mountStrip(42);
    await flushPromises();
    const tiles = wrapper.findAll('[data-testid="related-tile"]');
    expect(tiles.length).toBe(2);
    expect(wrapper.text()).toContain('Related');
    const img = wrapper.find('img');
    expect(img.exists()).toBe(true);
    expect(img.attributes('src')).toBe('/api/entries/1/thumb?v=1');
    await tiles[1]!.trigger('click');
    expect(push).toHaveBeenCalledWith('/entries/2');
    // fetched with the default limit
    expect(apiGet).toHaveBeenCalledWith('/api/entries/42/related', { params: { limit: 8 } });
  });

  it('is hidden when empty or errored', async () => {
    apiGet.mockResolvedValue({ data: { items: [] } });
    const empty = mountStrip(42);
    await flushPromises();
    expect(empty.find('[data-testid="related-strip"]').exists()).toBe(false);

    apiGet.mockRejectedValue(new Error('503'));
    const errored = mountStrip(43);
    await flushPromises();
    expect(errored.find('[data-testid="related-strip"]').exists()).toBe(false);
  });

  it('refetches on entry id change', async () => {
    const wrapper = mountStrip(42);
    await flushPromises();
    expect(apiGet).toHaveBeenCalledTimes(1);
    await wrapper.setProps({ entryId: 77 });
    await flushPromises();
    expect(apiGet).toHaveBeenCalledTimes(2);
    expect(apiGet).toHaveBeenLastCalledWith('/api/entries/77/related', { params: { limit: 8 } });
  });
});
