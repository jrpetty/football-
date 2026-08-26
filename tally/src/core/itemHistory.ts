// ---------------------------------------------------------------------------
// One line off the till, followed through everything the app knows.
//
// "Is Alpine dying?" cannot be answered from a dashboard total. It needs the
// one item's own story: how many went out each night, whether that rate is
// falling, what it goes over the bar at, what it makes, and which nights of
// the week actually sell it. Every one of those figures already exists
// somewhere in the app; this file's whole job is telling them for one item.
//
// The subtlety worth naming is the RATE. Nights entered without a till roll
// have no item list at all, and counting them as "sold nothing" would drag
// every rate towards zero in exact proportion to how often the roll was
// skipped. So rates here divide by nights WITH a roll, and the card says how
// many that is — a per-week figure is only as honest as its denominator.
// ---------------------------------------------------------------------------

import type { DayStats } from './analytics.ts'
import { normalise } from './match.ts'

export interface ItemNight {
  date: string
  weekday: string
  qtyMilli: number
  pence: number
}

export interface ItemProfile {
  code: string
  /** As the till prints it, from the most recent night it appeared. */
  name: string
  /** Every night it sold, oldest first. */
  nights: ItemNight[]
  /** Nights that had an item list at all — the honest denominator. */
  nightsWithRoll: number
  totalQtyMilli: number
  totalPence: number
  /** Takings ÷ quantity — what one actually went over the bar at, on average. */
  avgPencePerItem: number | null
  /** Sold per week, over the span nights-with-roll cover. */
  perWeek: number | null
  firstSeen: string | null
  lastSeen: string | null
  /** Quantity by weekday, Monday first, over every night it sold. */
  byWeekday: Array<{ weekday: string; qtyMilli: number }>
  /**
   * The last four roll-weeks against the four before them, as basis points of
   * change in the nightly rate. Null until both halves have enough nights to
   * mean anything.
   */
  recentChangeBp: number | null
  recentNights: number
  previousNights: number
}

export const WEEKDAY_ORDER = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

/** Enough nights on each side before a trend is worth stating. */
const MIN_FOR_TREND = 6

/**
 * Everything one item has done, from the nights' own item lists.
 *
 * Matched by code first and printed name second — the same rule the price
 * book and the pours use, so "the same item" means the same thing everywhere.
 */
export function itemProfile(all: readonly DayStats[], code: string, name: string): ItemProfile {
  const wantCode = code.trim().toUpperCase()
  const wantName = normalise(name)

  const withRoll = all
    .filter((d) => d.items.length > 0)
    .sort((a, b) => a.date.localeCompare(b.date))

  const nights: ItemNight[] = []
  let latestName = name
  for (const day of withRoll) {
    const line = day.items.find(
      (i) => (wantCode !== '' && i.code.trim().toUpperCase() === wantCode) || normalise(i.name) === wantName,
    )
    if (!line) continue
    latestName = line.name
    nights.push({ date: day.date, weekday: day.weekday, qtyMilli: line.qtyMilli, pence: line.pence })
  }

  const totalQtyMilli = nights.reduce((a, n) => a + n.qtyMilli, 0)
  const totalPence = nights.reduce((a, n) => a + n.pence, 0)

  const byWeekday = WEEKDAY_ORDER.map((weekday) => ({
    weekday,
    qtyMilli: nights.filter((n) => n.weekday === weekday).reduce((a, n) => a + n.qtyMilli, 0),
  }))

  // The span the rolls cover, not the span the item sold over: an item that
  // vanished three weeks ago has a falling rate, and measuring only to its
  // last sale would hide exactly that.
  const first = withRoll[0]?.date ?? null
  const last = withRoll[withRoll.length - 1]?.date ?? null
  const spanDays =
    first && last ? Math.max(1, Math.round((Date.parse(last) - Date.parse(first)) / 86_400_000) + 1) : 0
  const perWeek =
    spanDays > 0 ? Math.round(((totalQtyMilli / 1000) / spanDays) * 7 * 10) / 10 : null

  // Recent four roll-weeks against the four before, by nightly rate so a
  // fortnight's holiday does not read as the beer dying.
  const recent = withRoll.slice(-28)
  const previous = withRoll.slice(-56, -28)
  const rate = (days: typeof withRoll): number => {
    if (days.length === 0) return 0
    const dates = new Set(days.map((d) => d.date))
    const qty = nights.filter((n) => dates.has(n.date)).reduce((a, n) => a + n.qtyMilli, 0)
    return qty / days.length
  }
  const recentRate = rate(recent)
  const previousRate = rate(previous)
  const recentChangeBp =
    recent.length >= MIN_FOR_TREND && previous.length >= MIN_FOR_TREND && previousRate > 0
      ? Math.round(((recentRate - previousRate) / previousRate) * 10000)
      : null

  return {
    code: wantCode,
    name: latestName,
    nights,
    nightsWithRoll: withRoll.length,
    totalQtyMilli,
    totalPence,
    avgPencePerItem: totalQtyMilli > 0 ? Math.round(totalPence / (totalQtyMilli / 1000)) : null,
    perWeek,
    firstSeen: nights[0]?.date ?? null,
    lastSeen: nights[nights.length - 1]?.date ?? null,
    byWeekday,
    recentChangeBp,
    recentNights: recent.length,
    previousNights: previous.length,
  }
}

/** The lines matching a search, for the type-ahead. */
export function searchItems<T extends { code: string; name: string }>(items: readonly T[], query: string): T[] {
  const q = normalise(query)
  if (!q) return [...items]
  return items.filter((i) => normalise(i.name).includes(q) || i.code.trim().toUpperCase() === q.toUpperCase())
}
