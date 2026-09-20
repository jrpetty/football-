// ---------------------------------------------------------------------------
// Dates, and the one subtlety in them.
//
// A pub's day does not end at midnight. The reconciliation happens after last
// orders, and often after the clock has rolled over — a count finished at half
// past midnight on Saturday belongs to Friday's trade. Defaulting to the
// calendar date would mislabel every late night, and the mislabelling would be
// invisible until someone tried to explain a Friday that looked empty.
//
// So "today" here means the trading day: before the cutoff, the date is
// yesterday's. She can always change it — the default just needs to be right
// far more often than not.
// ---------------------------------------------------------------------------

/** Before this hour, the takings belong to the previous calendar day. */
export const TRADING_DAY_CUTOFF_HOUR = 5

/** `YYYY-MM-DD` in the local timezone. */
export function dateKey(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/**
 * The trading day a count taken at `now` belongs to.
 *
 * Local time throughout. `toISOString()` would be UTC, which in British Summer
 * Time is an hour behind local — enough on its own to file an 00:30 count under
 * the wrong day even after the cutoff had done its job.
 */
export function tradingDayKey(now: Date = new Date()): string {
  const d = new Date(now.getTime())
  if (d.getHours() < TRADING_DAY_CUTOFF_HOUR) d.setDate(d.getDate() - 1)
  return dateKey(d)
}

/**
 * The trading day a receipt belongs to, off the receipt's own printed stamp.
 *
 * The till prints "18/09/2026 22:46:49" and it is a British till, so that is the
 * eighteenth of September. Day first, always. Nothing here ever hands the string
 * to `new Date()`, which reads 05/09/2026 as the fifth of September in one
 * browser and the ninth of May in another, and would file a third of the year
 * under the wrong month without ever looking wrong on screen.
 *
 * The cutoff then does its usual job: a roll rung off at half past midnight
 * belongs to the night before, which is what she would call it.
 *
 * Returns null on anything it cannot read, because a guessed date is worse than
 * no date — it would file a night under a day she never worked.
 */
export function tradingDayFromPrinted(printedAt: string | undefined): string | null {
  if (!printedAt) return null
  const m = /^\s*(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})(?:\s+(\d{1,2}):(\d{2}))?/.exec(printedAt)
  if (!m) return null
  const day = Number(m[1])
  const month = Number(m[2])
  const year = Number(m[3])
  // A till that printed a thirteenth month, or a 31st of February, has been
  // misread. Saying so beats filing the night under whatever Date rolls it into.
  if (month < 1 || month > 12 || day < 1 || day > 31) return null
  const hour = m[4] === undefined ? 12 : Number(m[4])
  const minute = m[5] === undefined ? 0 : Number(m[5])
  if (hour > 23 || minute > 59) return null

  const d = new Date(year, month - 1, day, hour, minute)
  if (d.getMonth() !== month - 1 || d.getDate() !== day) return null
  if (hour < TRADING_DAY_CUTOFF_HOUR) d.setDate(d.getDate() - 1)
  return dateKey(d)
}

/** True when the count is being entered in the small hours after that night. */
export function isAfterMidnightForTradingDay(now: Date = new Date()): boolean {
  return now.getHours() < TRADING_DAY_CUTOFF_HOUR
}

/** Parse `YYYY-MM-DD` as a local date, never UTC. */
export function fromDateKey(key: string): Date {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(key)
  if (!m) return new Date(NaN)
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
}

const LONG = new Intl.DateTimeFormat('en-GB', { weekday: 'long', day: 'numeric', month: 'long' })
const SHORT = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short' })

/** "Friday 23 August" — the heading on the day being counted. */
export function formatLong(key: string): string {
  const d = fromDateKey(key)
  return Number.isNaN(d.getTime()) ? key : LONG.format(d)
}

/** "Fri 23 Aug" — the history list. */
export function formatShort(key: string): string {
  const d = fromDateKey(key)
  return Number.isNaN(d.getTime()) ? key : SHORT.format(d)
}

/** Day name alone, for grouping by weekday later on. */
export function weekdayOf(key: string): string {
  const d = fromDateKey(key)
  if (Number.isNaN(d.getTime())) return ''
  return LONG.formatToParts(d).find((p) => p.type === 'weekday')?.value ?? ''
}

/** Shift a date key by whole days, staying in local time. */
export function addDays(key: string, days: number): string {
  const d = fromDateKey(key)
  if (Number.isNaN(d.getTime())) return key
  d.setDate(d.getDate() + days)
  return dateKey(d)
}
