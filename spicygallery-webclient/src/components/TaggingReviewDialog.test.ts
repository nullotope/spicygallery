import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { Quasar } from 'quasar';

const apiPost = vi.fn();
vi.mock('src/boot/api', () => ({
  api: {
    post: (...args: unknown[]) => apiPost(...args),
    get: vi.fn(),
  },
}));

const { default: TaggingReviewDialog } = await import('src/components/TaggingReviewDialog.vue');

const PREDICT_URL = '/api/entries/7/autotag/predict';
const APPLY_URL = '/api/entries/7/autotag/apply';

/** [name, category, probability] — already sorted probability desc (the server sorts). */
const DUCK_SET: Array<[string, string, number]> = [
  ['duck', 'general', 0.92],
  ['heron', 'artist', 0.75],
  ['hydra', 'character', 0.6],
];

const APPLIED = [{ id: 1, name: 'duck', category: 'general', postCount: 2 }];

function prediction(tags: Array<[string, string, number]>, calibration = 'f1.0@0.1') {
  return {
    data: {
      model: 'hydra-3.5',
      calibration,
      tags: tags.map(([name, category, probability]) => ({ name, category, probability })),
    },
  };
}

function mockPredict(tags: Array<[string, string, number]> = DUCK_SET, applied: unknown[] = APPLIED) {
  apiPost.mockImplementation((url: string) =>
    url === PREDICT_URL ? Promise.resolve(prediction(tags)) : Promise.resolve({ data: applied }),
  );
}

function mountDialog(props: Record<string, unknown> = {}) {
  return mount(TaggingReviewDialog, {
    props: { modelValue: true, entryId: 7, ...props },
    global: { plugins: [Quasar, createPinia()] },
  });
}

function dialogEl(): HTMLElement | null {
  return document.querySelector('[data-testid="autotag-dialog"]');
}

function chipEl(name: string): HTMLElement {
  return document.querySelector(`[data-testid="autotag-chip-${name}"]`) as HTMLElement;
}

describe('TaggingReviewDialog', () => {
  beforeEach(() => {
    apiPost.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
    document.querySelectorAll('[data-testid="autotag-dialog"]').forEach((el) => el.remove()); // q-dialog teleports to body
  });

  it('shows pre-checked chips with probabilities and an accuracy line', async () => {
    mockPredict();
    const wrapper = mountDialog();
    await flushPromises();
    const dialog = dialogEl();
    expect(dialog).not.toBeNull();
    // server order (probability desc) is preserved — no client re-sort
    const names = [...document.querySelectorAll('[data-testid^="autotag-chip-"]')].map((el) =>
      (el.getAttribute('data-testid') as string).slice('autotag-chip-'.length),
    );
    expect(names).toEqual(['duck', 'heron', 'hydra']);
    for (const name of names) {
      expect(chipEl(name).getAttribute('aria-checked'), name).toBe('true');
    }
    expect(dialog?.textContent).toContain('92%');
    expect(dialog?.textContent).toContain('75%');
    expect(dialog?.textContent).toContain('60%');
    expect(dialog?.textContent).toContain('3 / 3');
    // opening predicts once with the server's default calibration,
    // and the slider is seeded from the response's calibration arg
    expect(apiPost).toHaveBeenCalledTimes(1);
    expect(apiPost).toHaveBeenCalledWith(PREDICT_URL, undefined);
    expect((wrapper.vm as unknown as { threshold: number }).threshold).toBe(1.0);
  });

  it('unchecking a chip excludes it from apply', async () => {
    mockPredict();
    const wrapper = mountDialog();
    await flushPromises();

    chipEl('heron').click();
    await flushPromises();
    expect(dialogEl()?.textContent).toContain('2 / 3');

    (document.querySelector('[data-testid="autotag-confirm"]') as HTMLElement).click();
    await flushPromises();

    expect(apiPost).toHaveBeenCalledWith(APPLY_URL, { tags: ['duck', 'hydra'] });
    expect(wrapper.emitted('applied')).toBeTruthy();
    expect((wrapper.emitted('applied') as unknown[][])[0]![0]).toEqual(APPLIED);
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false]);
    // parent applies the emitted update (v-model in EntryPage)
    await wrapper.setProps({ modelValue: false });
    await flushPromises();
    expect(dialogEl()).toBeNull();
  });

  it('unchecking a chip survives a threshold re-predict', async () => {
    mockPredict();
    vi.useFakeTimers();
    const wrapper = mountDialog();
    await vi.advanceTimersByTimeAsync(0);
    expect(apiPost).toHaveBeenCalledTimes(1);

    chipEl('heron').click();
    await vi.advanceTimersByTimeAsync(0);
    expect(dialogEl()?.textContent).toContain('2 / 3');

    const vm = wrapper.vm as unknown as { threshold: number };
    vm.threshold = 1.5;
    await vi.advanceTimersByTimeAsync(300); // debounce fires the re-predict
    await vi.advanceTimersByTimeAsync(0); // let the response settle
    expect(apiPost).toHaveBeenLastCalledWith(PREDICT_URL, { calibration: 'f1.5@0.1' });

    // the user's exclusion is authoritative regardless of threshold (spec §4.1)
    expect(chipEl('heron').getAttribute('aria-checked'), 'heron').toBe('false');
    expect(dialogEl()?.textContent).toContain('2 / 3');

    (document.querySelector('[data-testid="autotag-confirm"]') as HTMLElement).click();
    await vi.advanceTimersByTimeAsync(0);
    expect(apiPost).toHaveBeenCalledWith(APPLY_URL, { tags: ['duck', 'hydra'] });
  });

  it('threshold change re-requests predict with the new calibration', async () => {
    mockPredict();
    vi.useFakeTimers();
    const wrapper = mountDialog();
    await vi.advanceTimersByTimeAsync(0);
    expect(apiPost).toHaveBeenCalledTimes(1);

    const vm = wrapper.vm as unknown as { threshold: number };
    vm.threshold = 1.5;
    await wrapper.vm.$nextTick();
    expect(apiPost).toHaveBeenCalledTimes(1); // debounced — no request yet

    await vi.advanceTimersByTimeAsync(300);
    expect(apiPost).toHaveBeenCalledTimes(2);
    expect(apiPost).toHaveBeenLastCalledWith(PREDICT_URL, { calibration: 'f1.5@0.1' });
  });

  it('last-request-wins when predicts resolve out of order', async () => {
    const deferred: Array<(value: unknown) => void> = [];
    apiPost.mockImplementation((url: string) => {
      if (url === PREDICT_URL) {
        return new Promise((resolve) => deferred.push(resolve));
      }
      return Promise.resolve({ data: [] });
    });
    vi.useFakeTimers();
    const wrapper = mountDialog();
    await vi.advanceTimersByTimeAsync(0);
    expect(apiPost).toHaveBeenCalledTimes(1); // initial, still pending

    const vm = wrapper.vm as unknown as { threshold: number };
    vm.threshold = 1.5;
    await vi.advanceTimersByTimeAsync(300);
    vm.threshold = 2.0;
    await vi.advanceTimersByTimeAsync(300);
    expect(apiPost).toHaveBeenCalledTimes(3);

    deferred[2]!(prediction([['fresh_b', 'general', 0.9]], 'f2.0@0.1')); // newest resolves first
    await vi.advanceTimersByTimeAsync(0);
    deferred[1]!(prediction([['stale_a', 'general', 0.9]], 'f1.5@0.1')); // stale resolves last
    await vi.advanceTimersByTimeAsync(0);

    const dialog = dialogEl();
    expect(dialog?.textContent).toContain('fresh_b');
    expect(dialog?.textContent).not.toContain('stale_a');
  });

  it('cancel writes nothing', async () => {
    mockPredict();
    const wrapper = mountDialog();
    await flushPromises();
    expect(apiPost).toHaveBeenCalledTimes(1);

    (document.querySelector('[data-testid="autotag-cancel"]') as HTMLElement).click();
    await flushPromises();

    expect(apiPost).toHaveBeenCalledTimes(1); // predict only — no apply POST
    expect(apiPost.mock.calls.some(([url]) => url === APPLY_URL)).toBe(false);
    expect(wrapper.emitted('applied')).toBeFalsy();
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false]);
    // parent applies the emitted update (v-model in EntryPage)
    await wrapper.setProps({ modelValue: false });
    await flushPromises();
    expect(dialogEl()).toBeNull();
  });
});
