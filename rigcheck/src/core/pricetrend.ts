/**
 * Price movement over time, from observed snapshots.
 *
 * Observations live in dated weekly files under data/prices-observed/, and the
 * importer keeps every dated point for a part rather than collapsing them into
 * one number. These functions read that series. They exist so that a screen
 * saying "down 12% since August" and a caption saying the same thing are
 * computing the same thing from the same points — and so that the claim is
 * refused when the points do not support it.
 *
 * The rules that matter:
 *
 *   - A change needs two points on different dates. One observation is a
 *     price, not a trend.
 *   - "Since August" means against a point observed IN August. If nothing was
 *     recorded that month the claim cannot be made, and the answer is null
 *     rather than the nearest thing to it.
 *   - The comparison runs to the latest point, which has to fall outside the
 *     month being compared against.
 *
 * Nothing here knows about seeds. A recalled figure has no date it was
 * observed on, so it cannot be the start of a trend.
 */

export interface SeriesPoint {
  /** YYYY-MM-DD. */
  date: string;
  price: number;
  sampleSize: number;
  basis: 'sold' | 'asking' | 'retail';
}

export interface PriceChange {
  from: SeriesPoint;
  to: SeriesPoint;
  /** to − from, in currency units. */
  abs: number;
  /** Whole percent, rounded. Negative is a fall. */
  pct: number;
}

const byDate = (series: SeriesPoint[]) => [...series].sort((a, b) => a.date.localeCompare(b.date));

/** The most recent point. */
export function latest(series: SeriesPoint[]): SeriesPoint | null {
  const s = byDate(series);
  return s.length ? s[s.length - 1] : null;
}

/** The most recent point on or before a date. */
export function priceAt(series: SeriesPoint[], date: string): SeriesPoint | null {
  const s = byDate(series).filter((p) => p.date <= date);
  return s.length ? s[s.length - 1] : null;
}

/** The most recent point within a calendar month, given as YYYY-MM. */
export function pointInMonth(series: SeriesPoint[], month: string): SeriesPoint | null {
  const s = byDate(series).filter((p) => p.date.slice(0, 7) === month);
  return s.length ? s[s.length - 1] : null;
}

/** The change between two points, or null when they cannot be compared. */
export function changeBetween(from: SeriesPoint | null, to: SeriesPoint | null): PriceChange | null {
  if (!from || !to || from.price <= 0 || from.date >= to.date) return null;
  const abs = to.price - from.price;
  return { from, to, abs, pct: Math.round((abs / from.price) * 100) };
}

/**
 * Change from a given month to now. Null unless there is a point in that month
 * and a later point outside it — the two things "since August" actually claims.
 */
export function changeSinceMonth(series: SeriesPoint[], month: string): PriceChange | null {
  const from = pointInMonth(series, month);
  const to = latest(series);
  if (!from || !to || to.date.slice(0, 7) === month) return null;
  return changeBetween(from, to);
}

/** Change over a trailing window ending on `today` (YYYY-MM-DD). */
export function changeOverDays(series: SeriesPoint[], days: number, today: string): PriceChange | null {
  const start = new Date(Date.parse(`${today}T00:00:00Z`) - days * 86400000).toISOString().slice(0, 10);
  const from = priceAt(series, start);
  const to = latest(series);
  return changeBetween(from, to);
}

/** First observation to latest — the whole recorded history of a part. */
export function changeOverall(series: SeriesPoint[]): PriceChange | null {
  const s = byDate(series);
  return s.length >= 2 ? changeBetween(s[0], s[s.length - 1]) : null;
}

/** "August" or "Aug" from YYYY-MM. */
export function monthLabel(month: string, short = false): string {
  const [y, m] = month.split('-').map(Number);
  if (!y || !m) return month;
  return new Date(Date.UTC(y, m - 1, 1)).toLocaleString('en-GB', { month: short ? 'short' : 'long', timeZone: 'UTC' });
}

/** The sentence a screen or a caption may use: "down 12% since August". */
export function describeChange(c: PriceChange, sinceLabel: string): string {
  if (c.pct === 0) return `unchanged since ${sinceLabel}`;
  return `${c.pct < 0 ? 'down' : 'up'} ${Math.abs(c.pct)}% since ${sinceLabel}`;
}

/**
 * The weekly snapshot file a date belongs to: the Monday of its week, as
 * YYYY-MM-DD. One file per week keeps the directory readable and makes "how
 * often do we look" a visible thing rather than a good intention.
 */
export function snapshotWeek(date: string): string {
  const t = Date.parse(`${date}T00:00:00Z`);
  if (Number.isNaN(t)) throw new Error(`not a date: ${date}`);
  const d = new Date(t);
  const back = (d.getUTCDay() + 6) % 7; // Monday = 0
  return new Date(t - back * 86400000).toISOString().slice(0, 10);
}
