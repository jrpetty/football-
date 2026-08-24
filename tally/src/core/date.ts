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
