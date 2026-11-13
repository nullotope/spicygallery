/**
 * REST polling for run progress — replaces the SSE transport (2026-11: SSE streams
 * were held open forever per tab and starved the browser's HTTP/1.1 6-connections-
 * per-host pool, wedging new tabs entirely). Polling uses only short-lived requests:
 * fast cadence while a run is active, slow cadence while idle, and background tabs
 * poll not at all (Page Visibility API) — N open tabs cost nothing when idle.
 *
 * `work()` is one reconcile pass (REST is the source of truth, spec §8); it returns
 * whether a run is currently active, which selects the next interval.
 */

export interface RunPollerOptions {
  /** One reconcile pass; resolves to `true` while a run is active (fast cadence). */
  work: () => Promise<boolean>;
  /** Poll interval while a run is active. Default 1000 ms. */
  activeMs?: number;
  /** Poll interval while idle. Default 15000 ms. */
  idleMs?: number;
}

export interface RunPoller {
  /** Begins polling (immediate first pass). Idempotent. */
  start(): void;
  /** Cancels the pending pass and detaches listeners. Idempotent; restart-safe. */
  stop(): void;
  /** Requests an immediate pass (e.g. right after starting a run from this tab,
   *  so progress appears without waiting for the idle tick). No-op when hidden
   *  or a pass is already in flight — the in-flight pass re-arms the cadence. */
  nudge(): void;
}

export function createRunPoller(options: RunPollerOptions): RunPoller {
  const activeMs = options.activeMs ?? 1_000;
  const idleMs = options.idleMs ?? 15_000;

  let started = false;
  let inFlight = false;
  let timer: ReturnType<typeof setTimeout> | null = null;

  const schedule = (ms: number) => {
    timer = setTimeout(() => void tick(), ms);
  };

  const clearTimer = () => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  };

  async function tick(): Promise<void> {
    if (inFlight || document.hidden) {
      return; // late timer after a slow pass, or paused — visibility resume handles it
    }
    inFlight = true;
    let active = false;
    try {
      active = await options.work();
    } catch {
      active = false; // a failed reconcile must not stop polling; retry at idle cadence
    } finally {
      inFlight = false;
    }
    if (started && !document.hidden) {
      schedule(active ? activeMs : idleMs);
    }
  }

  const onVisibility = () => {
    if (!started) {
      return;
    }
    if (document.hidden) {
      clearTimer(); // pause; no timer to fire while hidden
    } else if (timer === null && !inFlight) {
      void tick(); // immediate reconcile on return to the foreground
    }
  };

  return {
    start() {
      if (started) {
        return;
      }
      started = true;
      document.addEventListener('visibilitychange', onVisibility);
      void tick();
    },
    stop() {
      if (!started) {
        return;
      }
      started = false;
      document.removeEventListener('visibilitychange', onVisibility);
      clearTimer();
    },
    nudge() {
      if (!started || inFlight || document.hidden) {
        return;
      }
      clearTimer();
      void tick();
    },
  };
}
