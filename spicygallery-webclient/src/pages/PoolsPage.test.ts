import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';

const apiGet = vi.fn();
const apiPost = vi.fn();
const apiPatch = vi.fn();
vi.mock('src/boot/api', () => ({
  api: {
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
    patch: (...args: unknown[]) => apiPatch(...args),
    delete: vi.fn(),
  },
}));

const push = vi.fn();
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ query: {} }),
  // data-to mirrors the `to` prop onto the DOM so link targets are assertable
  RouterLink: { props: ['to'], template: '<a class="router-link-stub" :data-to="to"><slot /></a>' },
}));

const { default: PoolsPage } = await import('src/pages/PoolsPage.vue');

const ENTRY = {
  id: 10,
  kind: 'image',
  fileName: 'a.png',
  width: 4,
  height: 3,
  durationMs: null,
  thumbUrl: '/api/entries/10/thumb',
};

/** A stored pool tile — mirrors PoolService.PoolDto. */
const POOL = {
  id: 3,
  name: 'Saga',
  description: 'dragons',
  entryCount: 30,
  updatedAt: '2026-11-12T10:00:00Z',
  origin: { rootName: 'lib', dirPath: 'dragons' },
  tags: [],
  cover: ENTRY,
};

const FOLDERS = [
  { rootName: 'lib', dirPath: 'dragons/west', entryCount: 12, cover: ENTRY },
  { rootName: 'lib', dirPath: 'scenic/lakes', entryCount: 8, cover: null },
];

/** Server facet order: pool_type first, then count desc / name asc. */
const FACETS = [
  { name: 'pool', category: 'pool_type', count: 2 },
  { name: 'suggested', category: 'pool_type', count: 1 },
  { name: 'dragon', category: 'general', count: 5 },
];

const CREATED = { ...POOL, id: 9, name: 'west', origin: { rootName: 'lib', dirPath: 'dragons/west' } };

/** URL-routed api mocks — installed fresh before every test. */
function installApi() {
  apiGet.mockImplementation((url: string) => {
    if (url === '/api/pools') {
      return Promise.resolve({ data: [POOL] });
    }
    if (url === '/api/pools/facets') {
      return Promise.resolve({ data: FACETS });
    }
    if (url === '/api/pools/folders') {
      return Promise.resolve({ data: FOLDERS });
    }
    return Promise.resolve({ data: null });
  });
  apiPost.mockResolvedValue({ data: CREATED });
  apiPatch.mockResolvedValue({ data: [] });
}

function mountPage() {
  return mount(PoolsPage, {
    global: { plugins: [Quasar, createPinia()] },
  });
}

/** q-dialog teleports to document.body — VTU's wrapper can't see it. */
function creatorEl(testid: string): HTMLElement {
  return document.querySelector(`[data-testid="${testid}"]`) as HTMLElement;
}

function inputEl(testid: string): HTMLInputElement {
  return creatorEl(testid) as HTMLInputElement;
}

/** VTU setValue for content inside the teleported dialog. */
async function setDialogInput(testid: string, value: string) {
  const el = inputEl(testid);
  el.value = value;
  el.dispatchEvent(new Event('input'));
  await flushPromises();
}

/** Typing into a wrapper-hosted input WITHOUT the change event — VTU's setValue also
 *  dispatches `change`, which flushes QInput's pending debounced emission on the spot. */
async function type(wrapper: ReturnType<typeof mount>, testid: string, value: string) {
  const el = wrapper.find(`[data-testid="${testid}"]`).element as HTMLInputElement;
  el.value = value;
  el.dispatchEvent(new Event('input'));
  await flushPromises();
}

describe('PoolsPage', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiPost.mockReset();
    apiPatch.mockReset();
    push.mockClear();
    installApi();
  });

  afterEach(() => {
    document.querySelectorAll('[data-testid="pool-creator"]').forEach((el) => el.remove()); // q-dialog teleports to body
    vi.useRealTimers();
  });

  it('renders the entries layout: sidebar (search + New) beside a tile grid', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const sidebar = wrapper.find('.entries-sidebar');
    expect(sidebar.exists()).toBe(true);
    expect(sidebar.find('[data-testid="pool-search"]').exists()).toBe(true);
    expect(sidebar.find('[data-testid="pool-new"]').exists()).toBe(true);
    expect(wrapper.find('.entries-main .tile-grid').exists()).toBe(true);
    expect(wrapper.find('[data-testid="pool-tile-3"]').exists()).toBe(true);
    expect(wrapper.findAll('[data-testid="pool-draft-tile"]').length).toBe(2);
  });

  it('groups facets by category with pool_type first', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const chips = wrapper
      .findAll('[data-testid^="pool-facet-"]')
      .map((c) => c.attributes('data-testid'));
    expect(chips).toEqual(['pool-facet-pool', 'pool-facet-suggested', 'pool-facet-dragon']);
    const captions = wrapper.findAll('.entries-sidebar .text-caption').map((c) => c.text());
    expect(captions).toContain('pool_type');
    expect(captions).toContain('general');
    expect(captions.indexOf('general')).toBeGreaterThan(captions.indexOf('pool_type'));
  });

  it('the debounced search refetches list + facets with q', async () => {
    vi.useFakeTimers();
    const wrapper = mountPage();
    await vi.advanceTimersByTimeAsync(0); // initial loads
    apiGet.mockClear();
    await type(wrapper, 'pool-search', 'dragons');
    expect(apiGet).not.toHaveBeenCalled(); // debounced
    await vi.advanceTimersByTimeAsync(300);
    await flushPromises();
    expect(apiGet).toHaveBeenCalledWith('/api/pools', { params: { q: 'dragons' } });
    expect(apiGet).toHaveBeenCalledWith('/api/pools/facets', { params: { q: 'dragons' } });
    expect(apiGet.mock.calls.filter(([url]) => url === '/api/pools/folders').length).toBe(0);
  });

  it('pool tiles render cover, name, flattened count, updated date, and link to the pool page', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const tile = wrapper.find('[data-testid="pool-tile-3"]');
    expect(tile.exists()).toBe(true);
    expect(tile.attributes('data-to')).toBe('/pools/3');
    expect(tile.find('img.tile-thumb').attributes('src')).toBe('/api/entries/10/thumb');
    expect(tile.text()).toContain('Saga');
    expect(tile.text()).toContain('30');
    expect(tile.text()).toContain('2026-11-12');
  });

  it('draft tiles render the draft badge, directory caption and cover, and open the pre-filled creator', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const drafts = wrapper.findAll('[data-testid="pool-draft-tile"]');
    expect(drafts.length).toBe(2);
    expect(drafts[0]!.text()).toContain('draft');
    expect(drafts[0]!.text()).toContain('lib/dragons/west');
    expect(drafts[0]!.find('img.tile-thumb').attributes('src')).toBe('/api/entries/10/thumb');
    // coverless draft renders the placeholder, not an img
    expect(drafts[1]!.find('img.tile-thumb').exists()).toBe(false);

    await drafts[0]!.trigger('click');
    await flushPromises();
    expect(inputEl('pool-creator-name').value).toBe('west'); // directory basename
    expect(inputEl('pool-creator-description').value).toBe('Auto-discovered from lib/dragons/west');
    expect(creatorEl('pool-creator').textContent).toContain('12'); // member-count preview
  });

  it('the New button opens the empty creator modal', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="pool-new"]').trigger('click');
    await flushPromises();
    expect(inputEl('pool-creator-name').value).toBe('');
    expect(inputEl('pool-creator-description').value).toBe('');
    expect(creatorEl('pool-creator').textContent).not.toContain('entries');
  });

  it('create from a draft posts the folder body and navigates to the new pool', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.findAll('[data-testid="pool-draft-tile"]')[0]!.trigger('click');
    await flushPromises();
    creatorEl('pool-creator-create').click();
    await flushPromises();
    expect(apiPost).toHaveBeenCalledWith('/api/pools', {
      name: 'west',
      description: 'Auto-discovered from lib/dragons/west',
      rootName: 'lib',
      dirPath: 'dragons/west',
    });
    expect(push).toHaveBeenCalledWith('/pools/9');
  });

  it('create from New posts the manual body (no folder keys) and navigates', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="pool-new"]').trigger('click');
    await flushPromises();
    await setDialogInput('pool-creator-name', 'Lakes');
    creatorEl('pool-creator-create').click();
    await flushPromises();
    expect(apiPost).toHaveBeenCalledWith('/api/pools', { name: 'Lakes' });
    expect(push).toHaveBeenCalledWith('/pools/9');
  });

  it('a blank manual title shows the validation caption and never posts', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="pool-new"]').trigger('click');
    await flushPromises();
    creatorEl('pool-creator-create').click();
    await flushPromises();

    const err = creatorEl('pool-creator-title-error');
    expect(err).not.toBeNull();
    expect(err.textContent).toContain('title');
    expect(apiPost).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });

  it('typing a title clears the validation caption and create posts', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="pool-new"]').trigger('click');
    await flushPromises();
    creatorEl('pool-creator-create').click();
    await flushPromises();
    expect(creatorEl('pool-creator-title-error')).not.toBeNull();

    await setDialogInput('pool-creator-name', 'Lakes');
    expect(creatorEl('pool-creator-title-error')).toBeNull();
    creatorEl('pool-creator-create').click();
    await flushPromises();
    expect(apiPost).toHaveBeenCalledWith('/api/pools', { name: 'Lakes' });
    expect(push).toHaveBeenCalledWith('/pools/9');
  });

  it('a draft keeps allowing a blank title — the server defaults the name', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.findAll('[data-testid="pool-draft-tile"]')[0]!.trigger('click');
    await flushPromises();
    await setDialogInput('pool-creator-name', '   ');
    creatorEl('pool-creator-create').click();
    await flushPromises();
    // no validation caption in draft mode; the folder body goes out nameless
    expect(creatorEl('pool-creator-title-error')).toBeNull();
    expect(apiPost).toHaveBeenCalledWith('/api/pools', {
      description: 'Auto-discovered from lib/dragons/west',
      rootName: 'lib',
      dirPath: 'dragons/west',
    });
    expect(push).toHaveBeenCalledWith('/pools/9');
  });

  it('an additional-tags value triggers the post-create setTags before navigation', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="pool-new"]').trigger('click');
    await flushPromises();
    await setDialogInput('pool-creator-name', 'Lakes');
    await setDialogInput('pool-creator-tags', 'dragon, fire, dragon');
    creatorEl('pool-creator-create').click();
    await flushPromises();
    expect(apiPost).toHaveBeenCalledWith('/api/pools', { name: 'Lakes' });
    expect(apiPatch).toHaveBeenCalledWith('/api/pools/9/tags', { tags: ['dragon', 'fire'] });
    expect(push).toHaveBeenCalledWith('/pools/9');
  });

  it('a failed post-create setTags shows the error; a retry skips create and only re-PATCHes the tags', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="pool-new"]').trigger('click');
    await flushPromises();
    await setDialogInput('pool-creator-name', 'Lakes');
    await setDialogInput('pool-creator-tags', 'dragon');
    apiPatch.mockRejectedValueOnce(new Error('tags patch failed'));
    creatorEl('pool-creator-create').click();
    await flushPromises();

    expect(apiPost).toHaveBeenCalledTimes(1); // the create went out exactly once
    expect(creatorEl('pool-creator').textContent).toContain('tags patch failed');
    expect(push).not.toHaveBeenCalled(); // no navigation while the tags step errors

    // fix the tags and retry: only the PATCH fires again — never a second POST /api/pools
    await setDialogInput('pool-creator-tags', 'dragon, fire');
    creatorEl('pool-creator-create').click();
    await flushPromises();

    expect(apiPost).toHaveBeenCalledTimes(1);
    expect(apiPatch).toHaveBeenCalledTimes(2);
    expect(apiPatch).toHaveBeenLastCalledWith('/api/pools/9/tags', { tags: ['dragon', 'fire'] });
    expect(push).toHaveBeenCalledWith('/pools/9');
  });

  it('cancel closes the creator without posting', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="pool-new"]').trigger('click');
    await flushPromises();
    creatorEl('pool-creator-cancel').click();
    await flushPromises();
    expect(apiPost).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
    await flushPromises(); // let the hide transition settle
    expect(document.querySelector('[data-testid="pool-creator"]')).toBeNull();
  });

  it('drafts filter client-side: q terms match the directory path, pool_type:suggested matches all', async () => {
    vi.useFakeTimers();
    const wrapper = mountPage();
    await vi.advanceTimersByTimeAsync(0);
    expect(wrapper.findAll('[data-testid="pool-draft-tile"]').length).toBe(2);

    await wrapper.find('[data-testid="pool-search"]').setValue('dragons');
    await vi.advanceTimersByTimeAsync(300);
    await flushPromises();
    let drafts = wrapper.findAll('[data-testid="pool-draft-tile"]');
    expect(drafts.length).toBe(1);
    expect(drafts[0]!.text()).toContain('lib/dragons/west');

    await wrapper.find('[data-testid="pool-search"]').setValue('pool_type:suggested');
    await vi.advanceTimersByTimeAsync(300);
    await flushPromises();
    expect(wrapper.findAll('[data-testid="pool-draft-tile"]').length).toBe(2);
  });

  it('facet chip clicks append the full tag reference to q and refetch', async () => {
    const wrapper = mountPage();
    await flushPromises();
    apiGet.mockClear();
    await wrapper.find('[data-testid="pool-facet-pool"]').trigger('click');
    await flushPromises();
    expect(apiGet).toHaveBeenCalledWith('/api/pools', { params: { q: 'pool_type:pool' } });
    await wrapper.find('[data-testid="pool-facet-dragon"]').trigger('click');
    await flushPromises();
    expect(apiGet).toHaveBeenCalledWith('/api/pools', { params: { q: 'pool_type:pool dragon' } });
    // the search box shows the accumulated terms
    expect((wrapper.find('[data-testid="pool-search"]').element as HTMLInputElement).value).toBe(
      'pool_type:pool dragon',
    );
  });

  it('shows empty states for the untouched and the filtered-out grid', async () => {
    vi.useFakeTimers();
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/pools' || url === '/api/pools/facets' || url === '/api/pools/folders') {
        return Promise.resolve({ data: [] });
      }
      return Promise.resolve({ data: null });
    });
    const wrapper = mountPage();
    await vi.advanceTimersByTimeAsync(0);
    await flushPromises();
    expect(wrapper.find('.entries-main').text()).toContain('no pools yet');

    await wrapper.find('[data-testid="pool-search"]').setValue('nothing');
    await vi.advanceTimersByTimeAsync(300);
    await flushPromises();
    expect(wrapper.find('.entries-main').text()).toContain('no pools match');
  });
});
