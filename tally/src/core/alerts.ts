// ---------------------------------------------------------------------------
// The handful of things worth interrupting somebody for.
//
// The test every rule here has to pass is simple: would a landlady, reading
// this on a Monday morning, do something about it? A dashboard can afford to
// show a figure that turns out to be nothing. An alert cannot — the second one
// of these is noise, the rest stop being read, and then the one that mattered
// goes past unnoticed too.
//
// So every rule has a threshold chosen to fire rarely, every alert names the
// figure and where to look, and the whole thing is capped. Five things is a
// morning's reading. Twenty is a wall of text nobody finishes.
//
// Nothing here fetches, stores or notifies. It takes what the app already knows
// and returns a list, which makes the judgement calls testable — and the
// judgement calls are the entire product.
// ---------------------------------------------------------------------------

import type { DayStats, LikeForLike } from './analytics.ts'
import type { MarginMove } from './history.ts'
import type { MarginReport } from './margin.ts'
import type { DeadStockLine } from './stock.ts'
import { formatMoney } from './money.ts'

export type AlertLevel = 'bad' | 'warn' | 'info'
export type AlertScreen = 'trade' | 'cellar' | 'rota' | 'nights'

export interface Alert {
  /** Stable across weeks, so the same finding can be recognised as the same. */
  id: string
  level: AlertLevel
  /** The finding, in one line, with the figure in it. */
  headline: string
  /** What to do about it, or what it means. */
  detail: string
  screen: AlertScreen
}

/** At most this many, because a list nobody finishes is a list nobody reads. */
export const MAX_ALERTS = 5

/** A weekday has to be this far down on last year before it is worth saying. */
const WEEKDAY_FALL_BP = 1000

/** A line's GP below this is worth a look, whatever else is going on. */
const GP_FLOOR_BP = 5500

/** Cellar variance worth mentioning, in pence. */
const CELLAR_GAP_PENCE = 20000

/** Money in slow stock worth mentioning. */
const DEAD_STOCK_PENCE = 20000

export interface AlertInput {
  /** Every night, so a run of short drawers can be spotted. */
  recent: readonly DayStats[]
  yearOnYear?: LikeForLike | null
  /** Per weekday, so "Fridays are down" can be said rather than "trade is down". */
  weekdayYoY?: ReadonlyArray<{ weekday: string; change: LikeForLike }>
  gp?: MarginReport | null
  moves?: readonly MarginMove[]
  deadStock?: readonly DeadStockLine[]
  /** Cellar variance at the last stock take: what was really there, less expected. */
  cellarGapPence?: number | null
  unpricedCount?: number
}

/**
 * This week's findings, worst first.
 *
 * Ordered by how much money is behind each one rather than by category, so the
 * top of the list is the thing to deal with first.
 */
export function weeklyAlerts(input: AlertInput): Alert[] {
  const out: Alert[] = []

  // --- a weekday that has fallen away -----------------------------------------
  for (const { weekday, change } of input.weekdayYoY ?? []) {
    if (!change.comparable || change.changeBp === null) continue
    if (change.matchedNights < 3) continue
    if (change.changeBp > -WEEKDAY_FALL_BP) continue
    out.push({
      id: `weekday-down-${weekday}`,
      level: 'warn',
      headline: `${weekday}s down ${Math.abs(change.changeBp / 100).toFixed(0)}% on last year`,
      detail: `${formatMoney(change.matchedPence)} across ${change.matchedNights} of them, against ${formatMoney(change.matchedLastYearPence)} the year before.`,
      screen: 'trade',
    })
  }

  // --- a line not making what it should ---------------------------------------
  for (const line of input.gp?.lines ?? []) {
    if (!line.margin || line.margin.gpBp >= GP_FLOOR_BP) continue
    // Only worth saying about something that actually sells.
    if (line.qtyMilli < 20_000) continue
    out.push({
      id: `gp-low-${line.code}`,
      level: line.margin.gpBp < 4500 ? 'bad' : 'warn',
      headline: `${line.name} is making ${(line.margin.gpBp / 100).toFixed(0)}%`,
      detail: `Selling at ${formatMoney(line.margin.sellPence)} and costing ${formatMoney(line.margin.costPence)}. ${formatMoney(line.periodProfitPence ?? 0)} of profit over the period.`,
      screen: 'trade',
    })
  }

  // --- a cost rise nobody passed on -------------------------------------------
  for (const move of input.moves ?? []) {
    if (move.verdict !== 'squeezed') continue
    out.push({
      id: `squeezed-${move.code}`,
      level: 'warn',
      headline: `${move.name} costs ${formatMoney(move.costNowPence)} now, up from ${formatMoney(move.costThenPence)}`,
      detail: `The board still says ${formatMoney(move.priceNowPence)}, so the margin has fallen ${Math.abs(move.gpChangeBp / 100).toFixed(1)} points to ${(move.now.gpBp / 100).toFixed(0)}%.`,
      screen: 'trade',
    })
  }

  // --- the cellar disagreeing with the till ------------------------------------
  const gap = input.cellarGapPence
  if (gap !== null && gap !== undefined && Math.abs(gap) >= CELLAR_GAP_PENCE) {
    out.push({
      id: 'cellar-gap',
      level: gap < 0 ? 'bad' : 'warn',
      headline: `The cellar is ${formatMoney(Math.abs(gap))} ${gap < 0 ? 'light' : 'heavy'}`,
      detail:
        gap < 0
          ? 'Less stock than the till says was poured. Line cleaning and spillage land here too, but this is the loss no balanced night can show.'
          : 'More stock than expected — usually a delivery booked in twice, or a stock take counted short.',
      screen: 'cellar',
    })
  }

  // --- a run of short drawers ---------------------------------------------------
  const judged = input.recent.filter((d) => d.variancePence !== null).slice(0, 7)
  const short = judged.filter((d) => d.verdict === 'short')
  if (judged.length >= 5 && short.length >= 4) {
    const total = short.reduce((a, d) => a + Math.abs(d.variancePence ?? 0), 0)
    out.push({
      id: 'short-run',
      level: 'bad',
      headline: `The drawer has been short ${short.length} of the last ${judged.length}`,
      detail: `${formatMoney(total)} in total. One short night is a miscount; four is a pattern worth looking at.`,
      screen: 'nights',
    })
  }

  // --- money standing still ------------------------------------------------------
  const dead = (input.deadStock ?? []).filter((l) => l.reason === 'not selling')
  const deadMoney = dead.reduce((a, l) => a + (l.tiedUpPence ?? 0), 0)
  if (deadMoney >= DEAD_STOCK_PENCE) {
    out.push({
      id: 'dead-stock',
      level: 'info',
      headline: `${formatMoney(deadMoney)} sitting in lines that barely sell`,
      detail: `${dead.length} ${dead.length === 1 ? 'line' : 'lines'} going out at under two a week. ${dead[0]?.item.name ?? ''} is the biggest of them.`,
      screen: 'cellar',
    })
  }

  // --- the price list going stale -------------------------------------------------
  if ((input.unpricedCount ?? 0) >= 10) {
    out.push({
      id: 'unpriced',
      level: 'info',
      headline: `${input.unpricedCount} lines still have no price set`,
      detail: 'Anything unpriced is left out of every margin figure, so the GP shown is only for the part of the trade that has been priced.',
      screen: 'trade',
    })
  }

  const rank: Record<AlertLevel, number> = { bad: 0, warn: 1, info: 2 }
  return out.sort((a, b) => rank[a.level] - rank[b.level]).slice(0, MAX_ALERTS)
}

/** One line summing the week up, for a notification that has room for nothing else. */
export function alertSummary(alerts: readonly Alert[]): string {
  if (alerts.length === 0) return 'Nothing worth bothering you about this week.'
  if (alerts.length === 1) return alerts[0]!.headline
  return `${alerts[0]!.headline} — and ${alerts.length - 1} other ${alerts.length === 2 ? 'thing' : 'things'}.`
}
