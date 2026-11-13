/**
 * Minimal EventSource fake for vitest: records subscriptions, exposes emit() and
 * the last created instance (FakeEventSource.last) for assertions. Stubbed into
 * the environment with vi.stubGlobal('EventSource', FakeEventSource) — happy-dom
 * ships no EventSource, and ImportControls.connect() creates one on mount.
 */
export class FakeEventSource {
  static last: FakeEventSource | null = null;
  /** Total instances constructed — lets tests pin connection reuse vs. leaks. */
  static createdCount = 0;
  /** Every instance constructed this run; tests pick by url when several exist. */
  static all: FakeEventSource[] = [];

  url: string;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private listeners = new Map<string, ((e: { data: string }) => void)[]>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.last = this;
    FakeEventSource.createdCount++;
    FakeEventSource.all.push(this);
  }

  static findByUrl(url: string): FakeEventSource | undefined {
    return FakeEventSource.all.find((s) => s.url === url);
  }

  close() {
    // no-op bookkeeping; real EventSource closes the connection
  }

  addEventListener(type: string, handler: (e: { data: string }) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), handler]);
  }

  emit(type: string, data: unknown) {
    for (const handler of this.listeners.get(type) ?? []) {
      handler({ data: JSON.stringify(data) });
    }
  }
}
