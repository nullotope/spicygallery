/**
 * Shared formatting helpers.
 */

/**
 * Milliseconds → a compact duration badge: `7s` under a minute, then the clock
 * form `M:SS` (`1:05`, `10:00`). Rounds to whole seconds. There is deliberately
 * no hours/days branch — long videos stay minute-leading (`61:00`).
 *
 * This is the shared home of the duration badge that EntriesPage, PoolPage and
 * ConceptWorkbenchPage had each copied verbatim; EntryPage's divergent
 * `1m 5s` variant was reconciled onto this majority behavior.
 */
export function humanDuration(ms: number): string {
  const total = Math.round(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return m > 0 ? `${m}:${String(s).padStart(2, '0')}` : `${s}s`;
}
