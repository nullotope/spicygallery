import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';

vi.mock('src/boot/api', () => ({
  api: {
    get: vi.fn().mockResolvedValue({ data: null }),
    post: vi.fn(),
  },
}));
const push = vi.fn();
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ params: {}, query: {} }),
  RouterLink: { template: '<a><slot /></a>' },
}));

const { default: SideBar } = await import('src/components/SideBar.vue');

function mountBar(entryTags: { id: number; name: string; category: 'general'; postCount: number }[] | null = null) {
  return mount(SideBar, {
    props: { entryTags },
    global: { plugins: [Quasar, createPinia()] },
  });
}

describe('SideBar — tag chip navigation', () => {
  it('clicking an entry tag chip sets the search to that tag, replacing the query', async () => {
    const wrapper = mountBar([{ id: 1, name: 'dragon', category: 'general', postCount: 2 }]);
    await flushPromises();
    push.mockClear();
    await wrapper.find('[data-testid="entry-tag-dragon"]').trigger('click');
    expect(push).toHaveBeenCalledTimes(1);
    expect(push).toHaveBeenCalledWith({ path: '/', query: { q: 'dragon' } });
  });

  it('the +/- buttons keep their append/exclude behavior', async () => {
    const wrapper = mountBar([{ id: 1, name: 'dragon', category: 'general', postCount: 2 }]);
    await flushPromises();
    push.mockClear();
    await wrapper.find('[data-testid="entry-tag-add-dragon"]').trigger('click');
    expect(push).toHaveBeenCalledWith({ path: '/', query: { q: 'dragon' } });
    await wrapper.find('[data-testid="entry-tag-remove-dragon"]').trigger('click');
    expect(push).toHaveBeenCalledWith({ path: '/', query: { q: '-dragon' } });
  });
});

describe('SideBar — query help', () => {
  afterEach(() => {
    document.querySelector('.query-help')?.remove(); // q-dialog teleports to body
  });

  it('help dialog documents the similar: metatag', async () => {
    const wrapper = mountBar();
    await flushPromises();
    await wrapper.find('[data-testid="query-help"]').trigger('click');
    await flushPromises();
    // q-dialog teleports to document.body
    const dialog = document.querySelector('.query-help');
    expect(dialog).not.toBeNull();
    expect(dialog?.textContent).toContain('similar:');
  });
});
