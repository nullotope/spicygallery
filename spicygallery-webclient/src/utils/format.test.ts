import { describe, expect, it } from 'vitest';
import { humanDuration } from 'src/utils/format';

describe('humanDuration', () => {
  it('renders sub-minute durations as ROUNDED seconds', () => {
    expect(humanDuration(0)).toBe('0s');
    expect(humanDuration(500)).toBe('1s'); // 0.5s rounds up
    expect(humanDuration(1500)).toBe('2s'); // not truncated
    expect(humanDuration(6500)).toBe('7s');
    expect(humanDuration(59_400)).toBe('59s');
  });

  it('renders one minute and up as clock M:SS with a padded seconds part', () => {
    expect(humanDuration(60_000)).toBe('1:00');
    expect(humanDuration(61_000)).toBe('1:01');
    expect(humanDuration(65_000)).toBe('1:05');
    expect(humanDuration(600_000)).toBe('10:00');
  });

  it('has no hours/days branch — long durations stay minute-leading', () => {
    expect(humanDuration(3_600_000)).toBe('60:00');
    expect(humanDuration(86_400_000)).toBe('1440:00');
  });
});
