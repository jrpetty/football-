import { describe, expect, it } from 'vitest';
import {
  changeBetween, changeOverDays, changeOverall, changeSinceMonth, describeChange,
  monthLabel, pointInMonth, priceAt, snapshotWeek, type SeriesPoint,
} from '../src/core/pricetrend.ts';

const pt = (date: string, price: number): SeriesPoint => ({ date, price, sampleSize: 5, basis: 'sold' });
// A card watched weekly through the summer, falling steadily.
const S = [pt('2026-07-06', 220), pt('2026-08-03', 200), pt('2026-08-24', 190), pt('2026-09-07', 176)];

describe('snapshot weeks', () => {
  it('files a date under the Monday of its week', () => {
    expect(snapshotWeek('2026-09-02')).toBe('2026-08-31'); // a Wednesday
    expect(snapshotWeek('2026-08-31')).toBe('2026-08-31'); // the Monday itself
    expect(snapshotWeek('2026-09-06')).toBe('2026-08-31'); // Sunday belongs to the week before
    expect(snapshotWeek('2026-09-07')).toBe('2026-09-07');
  });
  it('refuses something that is not a date', () => {
    expect(() => snapshotWeek('yesterday')).toThrow();
  });
});

describe('reading a series', () => {
  it('priceAt is the latest point on or before the date', () => {
    expect(priceAt(S, '2026-08-10')?.price).toBe(200);
    expect(priceAt(S, '2026-08-24')?.price).toBe(190);
    expect(priceAt(S, '2026-07-01')).toBeNull();
  });

  it('pointInMonth takes the latest point inside that month', () => {
    expect(pointInMonth(S, '2026-08')?.date).toBe('2026-08-24');
    expect(pointInMonth(S, '2026-06')).toBeNull();
  });

  it('"since August" compares the August point with the latest, and says so', () => {
    const c = changeSinceMonth(S, '2026-08')!;
    expect(c.from.date).toBe('2026-08-24');
    expect(c.to.date).toBe('2026-09-07');
    expect(c.pct).toBe(-7);
    expect(describeChange(c, monthLabel('2026-08'))).toBe('down 7% since August');
  });

  it('refuses "since a month" with no observation in it', () => {
    // Nothing was recorded in June. The nearest point is not the June price,
    // and saying "since June" from it would be a claim the data does not make.
    expect(changeSinceMonth(S, '2026-06')).toBeNull();
  });

  it('refuses when the latest point is still inside the month being compared against', () => {
    expect(changeSinceMonth([pt('2026-08-03', 200), pt('2026-08-24', 190)], '2026-08')).toBeNull();
  });

  it('needs two points on different dates', () => {
    expect(changeBetween(S[0], S[0])).toBeNull();
    expect(changeOverall([pt('2026-09-02', 40)])).toBeNull();
    expect(changeOverall([])).toBeNull();
  });

  it('changeOverDays measures from the point in force at the start of the window', () => {
    // Thirty days before 8 September is 9 August; the price in force then was
    // the 3 August observation. 200 → 176 is the "down 12% since August" line.
    const c = changeOverDays(S, 30, '2026-09-08')!;
    expect(c.from.date).toBe('2026-08-03');
    expect(c.pct).toBe(-12);
    expect(describeChange(c, monthLabel(c.from.date.slice(0, 7)))).toBe('down 12% since August');
  });

  it('rounds to a whole percent and words both directions', () => {
    expect(changeBetween(pt('2026-08-01', 100), pt('2026-09-01', 103))!.pct).toBe(3);
    expect(describeChange(changeBetween(pt('2026-08-01', 100), pt('2026-09-01', 118))!, 'August')).toBe('up 18% since August');
    expect(describeChange(changeBetween(pt('2026-08-01', 100), pt('2026-09-01', 100))!, 'August')).toBe('unchanged since August');
  });

  it('will not divide by a zero price', () => {
    expect(changeBetween(pt('2026-08-01', 0), pt('2026-09-01', 50))).toBeNull();
  });

  it('labels months for a screen or a caption', () => {
    expect(monthLabel('2026-08')).toBe('August');
    expect(monthLabel('2026-08', true)).toBe('Aug');
    expect(monthLabel('nonsense')).toBe('nonsense');
  });
});
