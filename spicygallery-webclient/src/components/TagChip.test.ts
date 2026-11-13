import { describe, expect, it } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import { Quasar } from 'quasar';

const { default: TagChip } = await import('src/components/TagChip.vue');

function mountChip(props: { name: string; category: 'general' | 'artist'; taggedBy?: string | null }) {
  return mount(TagChip, { props, global: { plugins: [Quasar] } });
}

/**
 * Quasar's QTooltip portal never renders its content in happy-dom (no real hover
 * pipeline), but the slotted content is fully resolvable from the component's vnode —
 * deterministic in jsdom, unlike synthetic mouse events.
 */
function tooltipText(wrapper: VueWrapper): string | null {
  const qtip = wrapper.findComponent({ name: 'QTooltip' });
  if (!qtip.exists()) {
    return null;
  }
  const slots = (qtip.vm.$.vnode as { children?: Record<string, () => unknown[]> }).children;
  return (slots?.default?.() ?? [])
    .map((v) => String((v as { children?: unknown }).children ?? ''))
    .join('');
}

describe('TagChip — provenance tooltip', () => {
  it('explains AI provenance ("tagged by hydra-3.5")', () => {
    const wrapper = mountChip({ name: 'old_ai', category: 'general', taggedBy: 'hydra-3.5' });
    expect(tooltipText(wrapper)).toBe('tagged by hydra-3.5');
  });

  it('user-tagged and provenance-less chips carry no tooltip', () => {
    const user = mountChip({ name: 'user_tag', category: 'general', taggedBy: 'user' });
    expect(tooltipText(user)).toBeNull();

    const none = mountChip({ name: 'plain', category: 'artist' });
    expect(tooltipText(none)).toBeNull();
  });
});

describe('TagChip — link mode', () => {
  it('link chips are clickable and emit select with the tag name', async () => {
    const wrapper = mount(TagChip, {
      props: { name: 'dragon', category: 'general', link: true },
      global: { plugins: [Quasar] },
    });
    expect(wrapper.find('.tag-chip--link').exists()).toBe(true);
    await wrapper.find('.tag-chip--link').trigger('click');
    expect(wrapper.emitted('select')).toEqual([['dragon']]);
  });

  it('non-link chips are not clickable and emit nothing', async () => {
    const wrapper = mountChip({ name: 'plain', category: 'general' });
    expect(wrapper.find('.tag-chip--link').exists()).toBe(false);
    await wrapper.find('.tag-chip').trigger('click');
    expect(wrapper.emitted('select')).toBeUndefined();
  });
});
