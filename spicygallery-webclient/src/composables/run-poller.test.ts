import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { createRunPoller } = await import('src/composables/run-poller');

describe('run poller', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  /** visibility is read-only in DOM land — stub it per test */
  function setHidden(hidden: boolean) {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
    document.dispatchEvent(new Event('visibilitychange'));
  }

  it('polls immediately on start, then at the idle cadence while no run is active', async () => {
    const work = vi.fn().mockResolvedValue(false);
    const poller = createRunPoller({ work, activeMs: 1_000, idleMs: 15_000 });
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(work).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(14_999);
    expect(work).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(work).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('switches to the fast cadence while a run is active', async () => {
    const work = vi.fn().mockResolvedValue(true);
    const poller = createRunPoller({ work, activeMs: 1_000, idleMs: 15_000 });
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(work).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(999);
    expect(work).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(work).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('never overlaps work: a slow pass delays the next tick instead of stacking', async () => {
    let release!: (active: boolean) => void;
    const work = vi.fn().mockImplementation(
      () => new Promise<boolean>((resolve) => (release = resolve)),
    );
    const poller = createRunPoller({ work, activeMs: 1_000, idleMs: 15_000 });
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(work).toHaveBeenCalledTimes(1);

    // the fast-cadence window passes while the first pass is still in flight
    await vi.advanceTimersByTimeAsync(5_000);
    expect(work).toHaveBeenCalledTimes(1);

    release(false);
    await vi.advanceTimersByTimeAsync(15_000);
    expect(work).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('start is idempotent and stop cancels the pending tick', async () => {
    const work = vi.fn().mockResolvedValue(false);
    const poller = createRunPoller({ work, activeMs: 1_000, idleMs: 15_000 });
    poller.start();
    poller.start();
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(work).toHaveBeenCalledTimes(1);

    poller.stop();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(work).toHaveBeenCalledTimes(1);
    poller.stop(); // double stop is harmless
  });

  it('a poller can be restarted after stop', async () => {
    const work = vi.fn().mockResolvedValue(false);
    const poller = createRunPoller({ work, activeMs: 1_000, idleMs: 15_000 });
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    poller.stop();

    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(work).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('pauses while the tab is hidden and reconciles immediately on return', async () => {
    const work = vi.fn().mockResolvedValue(false);
    const poller = createRunPoller({ work, activeMs: 1_000, idleMs: 15_000 });
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(work).toHaveBeenCalledTimes(1);

    setHidden(true);
    await vi.advanceTimersByTimeAsync(120_000);
    expect(work).toHaveBeenCalledTimes(1);

    setHidden(false);
    await vi.advanceTimersByTimeAsync(0);
    expect(work).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('stop detaches the visibilitychange listener', async () => {
    const removeSpy = vi.spyOn(document, 'removeEventListener');
    const work = vi.fn().mockResolvedValue(false);
    const poller = createRunPoller({ work });
    poller.start();
    poller.stop();
    expect(removeSpy).toHaveBeenCalledWith('visibilitychange', expect.any(Function));
  });

  it('nudge polls immediately and switches to the fast cadence (local run start)', async () => {
    const work = vi.fn().mockResolvedValue(false);
    const poller = createRunPoller({ work, activeMs: 1_000, idleMs: 15_000 });
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(work).toHaveBeenCalledTimes(1); // idle pass → 15s away

    work.mockResolvedValue(true);
    poller.nudge();
    await vi.advanceTimersByTimeAsync(0);
    expect(work).toHaveBeenCalledTimes(2); // immediate — no waiting for the idle tick

    await vi.advanceTimersByTimeAsync(999);
    expect(work).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(work).toHaveBeenCalledTimes(3); // fast cadence from here
    poller.stop();
  });

  it('nudge while a pass is in flight does not overlap', async () => {
    let release!: (active: boolean) => void;
    const work = vi.fn().mockImplementation(
      () => new Promise<boolean>((resolve) => (release = resolve)),
    );
    const poller = createRunPoller({ work, activeMs: 1_000, idleMs: 15_000 });
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    poller.nudge();
    await vi.advanceTimersByTimeAsync(5_000);
    expect(work).toHaveBeenCalledTimes(1); // still the first pass

    release(true);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(work).toHaveBeenCalledTimes(2);
    poller.stop();
  });
});
