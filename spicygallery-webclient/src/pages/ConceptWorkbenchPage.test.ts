import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, type Pinia } from 'pinia';
import { Quasar } from 'quasar';
import { createMemoryHistory, createRouter } from 'vue-router';
import type { GridItem } from 'src/stores/entries';

const apiGet = vi.fn();
const apiPost = vi.fn();
const apiPatch = vi.fn();
const apiDelete = vi.fn();
vi.mock('src/boot/api', () => ({
  api: {
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
    patch: (...args: unknown[]) => apiPatch(...args),
    delete: (...args: unknown[]) => apiDelete(...args),
  },
}));

const { default: ConceptWorkbenchPage } = await import('src/pages/ConceptWorkbenchPage.vue');
const { useEntriesStore } = await import('src/stores/entries');

function entry(id: number, fileName: string, overrides: Partial<GridItem> = {}): GridItem {
  return {
    id,
    kind: 'image',
    fileName,
    width: 4,
    height: 3,
    durationMs: null,
    thumbUrl: '/api/entries/' + id + '/thumb',
    ...overrides,
  };
}

/** The in-memory server behind the api mocks: mutations update it the way the
 *  real endpoints do — the membership sets drive the DTO counts, the member
 *  lists and the suggestion filtering (examples/exclusions/tagged are excluded
 *  server-side). Member tiles resolve through `entryById`; ids without a known
 *  entry row are skipped exactly like the server's byIds stitch. */
const db = {
  concept: {
    id: 3,
    name: 'duck',
    phrase: 'a waterfowl',
    exampleCount: 0,
    excludedCount: 0,
    taggedCount: 0,
    applyable: 0,
    anchorStale: false,
    createdAt: '2026-11-09T10:00:00Z',
    updatedAt: '2026-11-10T12:00:00Z',
  },
  suggestions: [] as { entry: GridItem; score: number }[],
  entryById: new Map<number, GridItem>(),
  exampleIds: new Set<number>(),
  excludedIds: new Set<number>(),
  taggedIds: new Set<number>(),
};

function conceptDto() {
  const applyable = [...db.exampleIds].filter((id) => !db.taggedIds.has(id)).length;
  return {
    ...db.concept,
    exampleCount: db.exampleIds.size,
    excludedCount: db.excludedIds.size,
    taggedCount: db.taggedIds.size,
    applyable,
  };
}

function membersDto() {
  const list = (ids: Set<number>) =>
    [...ids].sort((a, b) => a - b).flatMap((id) => (db.entryById.has(id) ? [db.entryById.get(id)!] : []));
  return { examples: list(db.exampleIds), excluded: list(db.excludedIds) };
}

function resetDb() {
  db.concept = {
    id: 3,
    name: 'duck',
    phrase: 'a waterfowl',
    exampleCount: 0,
    excludedCount: 0,
    taggedCount: 0,
    applyable: 0,
    anchorStale: false,
    createdAt: '2026-11-09T10:00:00Z',
    updatedAt: '2026-11-10T12:00:00Z',
  };
  db.suggestions = [
    { entry: entry(10, 'a.png'), score: 0.912 },
    { entry: entry(11, 'b.mp4', { kind: 'video', durationMs: 6500 }), score: 0.42 },
  ];
  db.entryById = new Map(db.suggestions.map((s) => [s.entry.id, s.entry]));
  db.exampleIds = new Set();
  db.excludedIds = new Set();
  db.taggedIds = new Set();
}

/** Axios-shaped rejection carrying an RFC-7807 problem body (the store unwraps `detail`). */
function problemError(status: number, detail: string) {
  return Object.assign(new Error('Request failed with status code ' + status), {
    isAxiosError: true,
    response: { status, data: { title: 'Error', status, detail } },
  });
}

function callCount(fn: ReturnType<typeof vi.fn>, url: string): number {
  return fn.mock.calls.filter((args) => args[0] === url).length;
}

/** URL-routed api mocks backed by db — installed fresh before every test. */
function installApi() {
  apiGet.mockImplementation((url: string) => {
    if (url === '/api/concepts/3') {
      return Promise.resolve({ data: conceptDto() });
    }
    if (url === '/api/concepts/3/members') {
      return Promise.resolve({ data: membersDto() });
    }
    if (url === '/api/concepts/3/suggestions') {
      const items = db.suggestions.filter(
        (s) =>
          !db.exampleIds.has(s.entry.id) && !db.excludedIds.has(s.entry.id) && !db.taggedIds.has(s.entry.id),
      );
      return Promise.resolve({ data: { items } });
    }
    return Promise.reject(new Error('unexpected GET ' + url));
  });
  apiPost.mockImplementation((url: string, body?: { entryIds?: number[] }) => {
    if (url === '/api/concepts/3/examples') {
      const ids = body?.entryIds ?? [];
      const added = ids.filter((id) => !db.exampleIds.has(id)).length; // re-adding counts nothing
      for (const id of ids) {
        db.excludedIds.delete(id); // EXCLUDED → EXAMPLE demotion
        db.exampleIds.add(id);
      }
      return Promise.resolve({ data: { added } });
    }
    if (url === '/api/concepts/3/exclusions') {
      const ids = body?.entryIds ?? [];
      const excluded = ids.filter((id) => !db.excludedIds.has(id)).length;
      for (const id of ids) {
        db.exampleIds.delete(id); // rejecting an example demotes it
        db.excludedIds.add(id);
      }
      return Promise.resolve({ data: { excluded } });
    }
    if (url === '/api/concepts/3/apply') {
      const targets = [...db.exampleIds].filter((id) => !db.taggedIds.has(id));
      for (const id of targets) {
        db.taggedIds.add(id);
      }
      return Promise.resolve({
        data: {
          tag: 'duck',
          examples: db.exampleIds.size,
          tagged: targets.length,
          alreadyTagged: db.exampleIds.size - targets.length,
        },
      });
    }
    if (url === '/api/concepts/3/refresh') {
      db.concept.anchorStale = false;
      return Promise.resolve({ data: conceptDto() });
    }
    return Promise.reject(new Error('unexpected POST ' + url));
  });
  apiPatch.mockImplementation((url: string, body?: { phrase?: string }) => {
    if (url === '/api/concepts/3') {
      if (body?.phrase) {
        db.concept.phrase = body.phrase;
      }
      return Promise.resolve({ data: conceptDto() });
    }
    return Promise.reject(new Error('unexpected PATCH ' + url));
  });
  apiDelete.mockImplementation((url: string) => {
    const example = url.match(/^\/api\/concepts\/3\/examples\/(\d+)$/);
    if (example) {
      db.exampleIds.delete(Number(example[1]));
      return Promise.resolve({ data: '' });
    }
    const exclusion = url.match(/^\/api\/concepts\/3\/exclusions\/(\d+)$/);
    if (exclusion) {
      db.excludedIds.delete(Number(exclusion[1]));
      return Promise.resolve({ data: '' });
    }
    return Promise.reject(new Error('unexpected DELETE ' + url));
  });
}

/** The route's `props: true` passes `id`; the direct mount passes it the same
 *  way (the router plugin is installed so RouterLinks resolve for real). */
async function mountWorkbench(pinia?: Pinia) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/concepts', component: { template: '<div />' } },
      { path: '/concepts/:id', component: { template: '<div />' }, props: true },
    ],
  });
  await router.push('/concepts/3');
  await router.isReady();
  return mount(ConceptWorkbenchPage, {
    props: { id: '3' },
    global: { plugins: [Quasar, pinia ?? createPinia(), router] },
  });
}

describe('ConceptWorkbenchPage', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiPost.mockReset();
    apiPatch.mockReset();
    apiDelete.mockReset();
    resetDb();
    installApi();
  });

  it('renders suggestions as grid tiles with percentage scores in rank order, linking to the detail page', async () => {
    const wrapper = await mountWorkbench();
    await flushPromises();

    expect(wrapper.find('[data-testid="concept-workbench"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="suggestion-tile-10"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="suggestion-tile-11"]').exists()).toBe(true);
    const scores = wrapper.findAll('[data-testid^="suggestion-score-"]');
    expect(scores.map((s) => s.text())).toEqual(['91%', '42%']);
    const accepts = wrapper.findAll('[data-testid^="suggestion-accept-"]');
    expect(accepts.map((a) => a.attributes('data-testid'))).toEqual([
      'suggestion-accept-10',
      'suggestion-accept-11',
    ]);
    // F-3: each tile links like the gallery grid (EntriesPage tiles → /entries/:id)
    expect(wrapper.find('[data-testid="suggestion-link-10"]').attributes('href')).toBe('/entries/10');
    expect(wrapper.find('[data-testid="suggestion-link-11"]').attributes('href')).toBe('/entries/11');
  });

  it('accept posts the example, re-fetches suggestions and members, and the tile joins the examples', async () => {
    const wrapper = await mountWorkbench();
    await flushPromises();

    await wrapper.find('[data-testid="suggestion-accept-10"]').trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenCalledWith('/api/concepts/3/examples', { entryIds: [10] });
    expect(callCount(apiGet, '/api/concepts/3/suggestions')).toBe(2); // re-ranked after the anchor moved
    expect(callCount(apiGet, '/api/concepts/3/members')).toBe(2); // fresh server-truth tiles
    expect(wrapper.find('[data-testid="suggestion-accept-10"]').exists()).toBe(false); // left the grid
    expect(wrapper.find('[data-testid="example-remove-10"]').exists()).toBe(true); // now an example
    expect(wrapper.text()).toContain('examples (1)');
  });

  it('accept does not navigate — the decision buttons sit outside the tile link', async () => {
    const wrapper = await mountWorkbench();
    await flushPromises();

    await wrapper.find('[data-testid="suggestion-accept-10"]').trigger('click');
    await flushPromises();

    expect(wrapper.vm.$route.path).toBe('/concepts/3'); // no navigation happened
    expect(apiPost).toHaveBeenCalledWith('/api/concepts/3/examples', { entryIds: [10] });
  });

  it('reject posts the exclusion, re-fetches suggestions and members, and the excluded list stays collapsed', async () => {
    const wrapper = await mountWorkbench();
    await flushPromises();

    await wrapper.find('[data-testid="suggestion-reject-11"]').trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenCalledWith('/api/concepts/3/exclusions', { entryIds: [11] });
    expect(callCount(apiGet, '/api/concepts/3/suggestions')).toBe(2);
    expect(callCount(apiGet, '/api/concepts/3/members')).toBe(2);
    expect(wrapper.find('[data-testid="suggestion-reject-11"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('excluded (1)');
    // collapsed behind the count — the un-exclude control needs expanding first
    expect(wrapper.find('[data-testid="exclusion-unexclude-11"]').exists()).toBe(false);
    await wrapper.find('[data-testid="excluded-toggle"]').trigger('click');
    expect(wrapper.find('[data-testid="exclusion-unexclude-11"]').exists()).toBe(true);
  });

  it('apply is disabled at 0, enabled otherwise, and shows the result counts', async () => {
    db.exampleIds = new Set([10]);
    let wrapper = await mountWorkbench();
    await flushPromises();

    const apply = wrapper.find('[data-testid="concept-apply"]');
    expect(apply.text()).toContain('apply to 1');
    expect(apply.attributes('disabled')).toBeUndefined();
    await apply.trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenCalledWith('/api/concepts/3/apply');
    expect(wrapper.find('[data-testid="concept-apply-result"]').text()).toContain('tagged 1 (0 already)');
    // the concept was re-fetched (applyable dropped) — but suggestions/members were NOT re-fetched
    expect(callCount(apiGet, '/api/concepts/3')).toBe(2);
    expect(callCount(apiGet, '/api/concepts/3/suggestions')).toBe(1);
    expect(callCount(apiGet, '/api/concepts/3/members')).toBe(1);
    expect(wrapper.find('[data-testid="concept-apply"]').attributes('disabled')).toBeDefined();

    // zero applyable from the start renders the disabled button
    db.exampleIds = new Set();
    wrapper = await mountWorkbench();
    await flushPromises();
    expect(wrapper.find('[data-testid="concept-apply"]').text()).toContain('apply to 0');
    expect(wrapper.find('[data-testid="concept-apply"]').attributes('disabled')).toBeDefined();
  });

  it('stale banner renders from anchorStale; refresh rebuilds and re-ranks the suggestions', async () => {
    db.concept.anchorStale = true;
    const wrapper = await mountWorkbench();
    await flushPromises();

    expect(wrapper.find('[data-testid="concept-stale-banner"]').exists()).toBe(true);
    expect(callCount(apiGet, '/api/concepts/3/suggestions')).toBe(1);

    await wrapper.find('[data-testid="concept-refresh"]').trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenCalledWith('/api/concepts/3/refresh');
    expect(wrapper.find('[data-testid="concept-stale-banner"]').exists()).toBe(false); // refreshed dto is no longer stale
    expect(callCount(apiGet, '/api/concepts/3/suggestions')).toBe(2); // the anchor moved
  });

  it('a 422 from refresh surfaces the server detail inline and the banner stays', async () => {
    db.concept.anchorStale = true;
    apiPost.mockImplementation((url: string) => {
      if (url === '/api/concepts/3/refresh') {
        return Promise.reject(
          problemError(422, "concept 'duck' has no inputs for the current model; re-embed entries or re-save the phrase"),
        );
      }
      return Promise.reject(new Error('unexpected POST ' + url));
    });
    const wrapper = await mountWorkbench();
    await flushPromises();

    await wrapper.find('[data-testid="concept-refresh"]').trigger('click');
    await flushPromises();

    const err = wrapper.find('[data-testid="concept-action-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain('no inputs for the current model');
    expect(wrapper.find('[data-testid="concept-stale-banner"]').exists()).toBe(true);
  });

  it('phrase save calls patch and re-fetches the suggestions (the anchor was re-resolved)', async () => {
    const wrapper = await mountWorkbench();
    await flushPromises();

    await wrapper.find('[data-testid="concept-phrase"]').setValue('a duck swimming on a pond');
    await wrapper.find('[data-testid="concept-phrase-save"]').trigger('click');
    await flushPromises();

    expect(apiPatch).toHaveBeenCalledWith('/api/concepts/3', { phrase: 'a duck swimming on a pond' });
    expect(callCount(apiGet, '/api/concepts/3/suggestions')).toBe(2); // F-4: the anchor moved
  });

  it('a provider-down phrase save surfaces the server detail inline and re-fetches nothing', async () => {
    apiPatch.mockImplementation(() => Promise.reject(problemError(503, 'embedding provider unreachable')));
    const wrapper = await mountWorkbench();
    await flushPromises();

    await wrapper.find('[data-testid="concept-phrase"]').setValue('new phrase');
    await wrapper.find('[data-testid="concept-phrase-save"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="concept-action-error"]').text()).toContain('embedding provider unreachable');
    // the failed PATCH wrote nothing — no re-rank, no stale-banner state churn
    expect(callCount(apiGet, '/api/concepts/3/suggestions')).toBe(1);
  });

  it('add loaded results posts exactly the entries-store ids and reports the added count', async () => {
    const pinia = createPinia();
    useEntriesStore(pinia).items = [entry(20, 'x.png'), entry(21, 'y.png')];
    db.entryById.set(20, entry(20, 'x.png'));
    db.entryById.set(21, entry(21, 'y.png'));
    const wrapper = await mountWorkbench(pinia);
    await flushPromises();

    const btn = wrapper.find('[data-testid="concept-add-loaded"]');
    expect(btn.text()).toContain('add loaded results (2)');
    expect(btn.attributes('disabled')).toBeUndefined();

    await btn.trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenCalledWith('/api/concepts/3/examples', { entryIds: [20, 21] });
    expect(wrapper.text()).toContain('added 2');
    expect(wrapper.find('[data-testid="example-remove-20"]').exists()).toBe(true); // member tiles re-fetched
    expect(callCount(apiGet, '/api/concepts/3/suggestions')).toBe(2); // the anchor moved
    expect(callCount(apiGet, '/api/concepts/3/members')).toBe(2);
  });

  it('add loaded results is disabled when the grid has no loaded entries', async () => {
    const wrapper = await mountWorkbench();
    await flushPromises();

    const btn = wrapper.find('[data-testid="concept-add-loaded"]');
    expect(btn.text()).toContain('add loaded results (0)');
    expect(btn.attributes('disabled')).toBeDefined();
  });

  it('remove-example deletes the membership and drops the server-truth tile', async () => {
    // the example exists on the server from an earlier visit — the tile renders on
    // a cold load (no session tracking), then remove re-fetches members + suggestions
    db.exampleIds = new Set([10]);
    const wrapper = await mountWorkbench();
    await flushPromises();

    expect(wrapper.find('[data-testid="example-remove-10"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain("showing this session's picks"); // the cold-reload caption is gone

    await wrapper.find('[data-testid="example-remove-10"]').trigger('click');
    await flushPromises();

    expect(apiDelete).toHaveBeenCalledWith('/api/concepts/3/examples/10');
    expect(wrapper.find('[data-testid="example-remove-10"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('examples (0)');
    expect(callCount(apiGet, '/api/concepts/3/members')).toBe(2);
    expect(callCount(apiGet, '/api/concepts/3/suggestions')).toBe(2); // the anchor lost a vector
  });

  it('un-exclude deletes the exclusion and drops the count', async () => {
    // an earlier visit's exclusion is visible without any session decision
    db.excludedIds = new Set([11]);
    const wrapper = await mountWorkbench();
    await flushPromises();

    expect(wrapper.text()).toContain('excluded (1)');
    await wrapper.find('[data-testid="excluded-toggle"]').trigger('click');
    expect(wrapper.find('[data-testid="exclusion-unexclude-11"]').exists()).toBe(true);

    await wrapper.find('[data-testid="exclusion-unexclude-11"]').trigger('click');
    await flushPromises();

    expect(apiDelete).toHaveBeenCalledWith('/api/concepts/3/exclusions/11');
    expect(wrapper.find('[data-testid="exclusion-unexclude-11"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('excluded (0)');
    expect(callCount(apiGet, '/api/concepts/3/members')).toBe(2);
  });

  it('a suggestions 409 (stale anchor) renders the message but keeps the workbench usable', async () => {
    db.concept.anchorStale = true;
    db.exampleIds = new Set([10]);
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/concepts/3') {
        return Promise.resolve({ data: conceptDto() });
      }
      if (url === '/api/concepts/3/suggestions') {
        return Promise.reject(problemError(409, 'concept is stale — refresh the anchor'));
      }
      return Promise.resolve({ data: membersDto() }); // members is an independent read
    });
    const wrapper = await mountWorkbench();
    await flushPromises();

    expect(wrapper.find('[data-testid="concept-stale-banner"]').exists()).toBe(true);
    const err = wrapper.find('[data-testid="suggestions-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain('refresh the anchor');
    // the workbench itself still renders its actions AND the member tiles
    expect(wrapper.find('[data-testid="concept-phrase"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="concept-apply"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="example-remove-10"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="members-error"]').exists()).toBe(false);
  });

  it('an unknown concept id (404) shows the not-found state with a back link', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/concepts/3') {
        // the server's contract: unknown concept ids are a 404 problem-detail
        // (the house MissingConceptException pattern) — matched by STATUS, so the
        // detail wording is irrelevant to the page
        return Promise.reject(problemError(404, 'no concept 3'));
      }
      return Promise.reject(new Error('unexpected GET ' + url));
    });
    let wrapper = await mountWorkbench();
    await flushPromises();

    const nf = wrapper.find('[data-testid="concept-not-found"]');
    expect(nf.exists()).toBe(true);
    expect(nf.text()).toContain('concept not found');
    expect(wrapper.find('[data-testid="concept-back-link"]').attributes('href')).toBe('/concepts');
    expect(wrapper.find('[data-testid="concept-apply"]').exists()).toBe(false);

    // a plain 404 without a problem body passes through the store raw — the
    // proxy/gateway shape — and is detected by the same status check
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/concepts/3') {
        return Promise.reject(
          Object.assign(new Error('Request failed with status code 404'), {
            isAxiosError: true,
            response: { status: 404, data: '' },
          }),
        );
      }
      return Promise.reject(new Error('unexpected GET ' + url));
    });
    wrapper = await mountWorkbench();
    await flushPromises();
    expect(wrapper.find('[data-testid="concept-not-found"]').exists()).toBe(true);
  });

  it('a non-404 load failure is an error state, not the not-found state', async () => {
    // the old detail-text sniffing would misread any reworded message as
    // not-found; only the status decides
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/concepts/3') {
        return Promise.reject(problemError(500, 'concept lookup exploded'));
      }
      return Promise.reject(new Error('unexpected GET ' + url));
    });
    const wrapper = await mountWorkbench();
    await flushPromises();

    expect(wrapper.find('[data-testid="concept-not-found"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="concept-error"]').text()).toContain('concept lookup exploded');
  });

  it('reloads when the route param changes without remounting (props.id watcher)', async () => {
    const wrapper = await mountWorkbench();
    await flushPromises();
    expect(wrapper.find('[data-testid="suggestion-tile-10"]').exists()).toBe(true);

    // param-only navigation (browser back/forward, next-concept links): the same
    // component instance receives a new id — it must fetch THAT concept, not keep
    // showing the old one or render a blank frame
    await wrapper.setProps({ id: '7' });
    await flushPromises();
    expect(apiGet).toHaveBeenCalledWith('/api/concepts/7', undefined);
  });
});
