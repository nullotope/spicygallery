import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';

const apiGet = vi.fn();
const apiPut = vi.fn();
vi.mock('src/boot/api', () => ({
  api: { get: (...args: unknown[]) => apiGet(...args), put: (...args: unknown[]) => apiPut(...args) },
}));

const { default: TagEditor } = await import('src/components/TagEditor.vue');

const DUCK = { id: 1, name: 'duck', category: 'general' as const, postCount: 2 };
const HERON = { id: 2, name: 'heron', category: 'artist' as const, postCount: 3 };

function mountEditor(props: Record<string, unknown> = {}) {
  return mount(TagEditor, {
    props: { entryId: 5, tags: [DUCK, HERON], ...props },
    global: { plugins: [Quasar, createPinia()] },
  });
}

describe('TagEditor', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiPut.mockReset();
    apiGet.mockResolvedValue({ data: [] });
    apiPut.mockResolvedValue({ data: [DUCK] });
  });

  it('renders existing tags grouped and removable', async () => {
    const wrapper = mountEditor();
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('duck');
    expect(wrapper.text()).toContain('heron');
    expect(wrapper.text()).toContain('artist');
    expect(wrapper.find('[data-testid=tag-remove-duck]').exists()).toBe(true);

    await wrapper.find('[data-testid=tag-remove-duck]').trigger('click');
    const editor = wrapper.vm as unknown as { working: Array<{ name: string }> };
    expect(editor.working.map((t) => t.name)).toEqual(['heron']);
  });

  it('autocomplete pick adds the canonical tag, not the alias', async () => {
    apiGet.mockResolvedValue({
      data: [{ name: 'ducky', category: 'general', postCount: 2, aliasOf: 'duck' }],
    });
    const wrapper = mountEditor({ debounceMs: 0 });
    await wrapper.find('[data-testid=tag-editor-input]').setValue('du');
    await new Promise((r) => setTimeout(r, 20)); // debounce flush
    expect(apiGet).toHaveBeenCalledWith('/api/tags/autocomplete', { params: { q: 'du' } });
    await wrapper.find('[data-testid=suggestion-ducky]').trigger('click');
    const editor = wrapper.vm as unknown as { working: Array<{ name: string }> };
    expect(editor.working.map((t) => t.name)).toContain('duck');
    expect(editor.working.map((t) => t.name)).not.toContain('ducky');
  });

  it('Enter normalizes before the exact-suggestion lookup: "blue dragon" picks blue_dragon', async () => {
    // mirrors PoolPage's enter-pick semantics (f664748): the suggestion matches the
    // NORMALIZED query, not the raw one — with the raw text the lookup missed and
    // Enter silently no-oped while the click row still worked
    apiGet.mockResolvedValue({
      data: [{ name: 'blue_dragon', category: 'character', postCount: 9, aliasOf: null }],
    });
    const wrapper = mountEditor({ debounceMs: 0 });
    await wrapper.find('[data-testid=tag-editor-input]').setValue('blue dragon');
    await new Promise((r) => setTimeout(r, 20)); // debounce flush
    await wrapper.find('[data-testid=tag-editor-input]').trigger('keydown.enter');

    // picked, not created and not a silent no-op: the query was consumed, the
    // suggestion row cleared, and the chip carries the suggestion's real category
    // (a create-row fallback would have stamped the default 'general')
    const editor = wrapper.vm as unknown as { working: Array<{ name: string; category: string }> };
    expect((wrapper.find('[data-testid=tag-editor-input]').element as HTMLInputElement).value).toBe('');
    expect(wrapper.find('[data-testid=suggestion-blue_dragon]').exists()).toBe(false);
    const chip = editor.working.find((t) => t.name === 'blue_dragon');
    expect(chip).toBeDefined();
    expect(chip!.category).toBe('character');
  });

  it('the create-category selector offers the user categories only — never pool_type', async () => {
    const wrapper = mountEditor({ debounceMs: 0 });
    await wrapper.find('[data-testid=tag-editor-input]').setValue('new thing');
    await new Promise((r) => setTimeout(r, 20));
    const options = wrapper.getComponent({ name: 'QSelect' }).props('options') as string[];
    // pool_type is internally generated vocabulary — creation must not offer it
    expect(options).toEqual(['general', 'artist', 'character', 'copyright', 'meta']);
    expect(options).not.toContain('pool_type');
  });

  it('creates an unknown tag with the chosen category', async () => {
    const wrapper = mountEditor({ debounceMs: 0 });
    await wrapper.find('[data-testid=tag-editor-input]').setValue('New Tag!');
    await new Promise((r) => setTimeout(r, 20));
    const editor = wrapper.vm as unknown as { working: Array<{ name: string; category: string }>; newCategory: string };
    editor.newCategory = 'artist';
    await wrapper.find('[data-testid=create-tag]').trigger('click');
    await wrapper.find('[data-testid=tag-editor-save]').trigger('click');
    expect(editor.working.map((t) => t.name)).toContain('new_tag');
    const body = apiPut.mock.calls[0]![1] as { tags: Array<{ name: string; category?: string }> };
    expect(apiPut.mock.calls[0]![0]).toBe('/api/entries/5/tags');
    expect(body.tags).toContainEqual({ name: 'new_tag', category: 'artist' });
  });

  it('save PUTs the full set and emits the response', async () => {
    apiGet.mockResolvedValue({
      data: [{ name: 'heron2', category: 'artist', postCount: 1, aliasOf: null }],
    });
    const wrapper = mountEditor({ debounceMs: 0 });
    await wrapper.find('[data-testid=tag-editor-input]').setValue('heron2');
    await new Promise((r) => setTimeout(r, 20));
    await wrapper.find('[data-testid=suggestion-heron2]').trigger('click');
    await wrapper.find('[data-testid=tag-editor-save]').trigger('click');

    const body = apiPut.mock.calls[0]![1] as { tags: Array<{ name: string }> };
    expect(apiPut.mock.calls[0]![0]).toBe('/api/entries/5/tags');
    expect(body.tags.map((t) => t.name).sort()).toEqual(['duck', 'heron', 'heron2']);
    expect(wrapper.emitted('saved')).toBeTruthy();
    expect((wrapper.emitted('saved') as unknown[][])[0]![0]).toEqual([DUCK]);
  });

  it('cancel discards changes', async () => {
    const wrapper = mountEditor();
    await wrapper.find('[data-testid=tag-remove-duck]').trigger('click');
    await wrapper.find('[data-testid=tag-editor-cancel]').trigger('click');
    expect(wrapper.emitted('cancel')).toBeTruthy();
    expect(apiPut).not.toHaveBeenCalled();
    const editor = wrapper.vm as unknown as { working: Array<{ name: string }> };
    expect(editor.working.map((t) => t.name).sort()).toEqual(['duck', 'heron']);
  });
});
