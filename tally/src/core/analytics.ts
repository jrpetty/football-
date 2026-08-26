// ---------------------------------------------------------------------------
// Turning a pile of nights into the things worth knowing.
//
// All pure, all integer arithmetic, all tested. The dashboard renders what this
// file returns and does no counting of its own — which is what keeps a chart
// from quietly disagreeing with the table beside it.
// ---------------------------------------------------------------------------

import { departmentLabel, sortByRegistry } from './departments.ts'
import { addDays, fromDateKey, weekdayOf } from './date.ts'
import { reconcileFull, DEFAULT_TOLERANCE_PENCE, type Verdict } from './reconcile.ts'
import type { DayRecord } from './types.ts'
import { shareBp } from './zread.ts'

export interface DeptStat {
  code: string
  label: string
  pence: number
  /** Items sold — the figure the till prints against Q on a department line. */
  qtyMilli: number
  /** Share of the total in the current selection, not as printed on any roll. */
  percentBp: number
  /**
   * Average taken per item sold.
   *
   * Worth having because it is the one number here that moves for a reason
   * other than how busy the night was: a shifting average means the mix inside
   * a department changed, or a price did.
   */
  avgPencePerItem: number | null
}

/** One night, flattened into the numbers a dashboard asks for. */
export interface DayStats {
  date: string
  weekday: string
  /** What the pub took, by the till. */
  takingsPence: number | null
  cashPence: number | null
  cardPence: number | null
  guestCount: number | null
  avePence: number | null
  departments: Array<{ code: string; label: string; pence: number; qtyMilli: number }>
  variancePence: number | null
  cashVariancePence: number | null
  cardVariancePence: number | null
  verdict: Verdict
  hasZRead: boolean
  /** Transactions rung up and then cancelled. */
  voidCount: number | null
  voidPence: number | null
  /** The drawer opened without a sale. */
  noSaleCount: number | null
  clerks: Array<{ code: string; name: string; pence: number; sales: number | null; voids: number | null }>
  /** Line-by-line item sales, when the item list was captured. */
  items: Array<{ code: string; name: string; qtyMilli: number; pence: number }>
}

export function dayStats(day: DayRecord, tolerancePence = DEFAULT_TOLERANCE_PENCE): DayStats {
  const z = day.zRead
  const r = reconcileFull({
    tillPence: day.till.pence,
    cardPence: day.card.pence,
    cashPence: day.cashPence,
    tolerancePence,
    ...(z ? { zRead: z } : {}),
  })

  const takings = z?.deptTotal?.pence ?? z?.transaction.paidTotalPence ?? day.till.pence ?? null

  return {
    date: day.date,
    weekday: weekdayOf(day.date),
    takingsPence: takings,
    // The till's own split when the roll was read; otherwise what she entered.
    cashPence: z?.transaction.cashPence ?? day.cashPence,
    cardPence: z?.transaction.cardPence ?? day.card.pence,
    guestCount: z?.transaction.guestCount ?? null,
    avePence: z?.transaction.avePence ?? null,
    departments: (z?.departments ?? []).map((d) => ({
      code: d.code,
      label: departmentLabel(d.code, d.name),
      pence: d.pence,
      qtyMilli: d.qtyMilli,
    })),
    variancePence: r.overall.complete ? r.overall.variancePence : null,
    cashVariancePence: r.cash?.variancePence ?? null,
    cardVariancePence: r.card?.variancePence ?? null,
    verdict: r.overall.verdict,
    hasZRead: !!z,
    voidCount: z?.transaction.voidCount ?? null,
    voidPence: z?.transaction.voidPence ?? null,
    noSaleCount: z?.transaction.noSaleCount ?? null,
    clerks: (z?.clerks ?? [])
      // A clerk who rang nothing up is noise on a summary.
      .filter((c) => (c.paidTotalPence ?? 0) > 0)
      .map((c) => ({
        code: c.code,
        name: c.name ?? c.code,
        pence: c.paidTotalPence ?? 0,
        sales: c.guestCount ?? null,
        voids: c.voidCount ?? null,
      })),
    items: (z?.plus ?? []).map((p) => ({
      code: p.code,
      name: p.name || p.code,
      qtyMilli: p.qtyMilli,
      pence: p.pence,
    })),
  }
}

// --- filtering ---------------------------------------------------------------

export interface Filter {
  /** Inclusive `YYYY-MM-DD` bounds. Either end may be left open. */
  from?: string
  to?: string
  /** Keep only these weekdays, by full English name. Empty means all. */
  weekdays?: string[]
  /** Keep only these department codes when breaking down. Empty means all. */
  departments?: string[]
  /** Keep only nights that did not balance. */
  onlyUnbalanced?: boolean
}

export function filterDays(stats: DayStats[], filter: Filter): DayStats[] {
  return stats.filter((s) => {
    if (filter.from && s.date < filter.from) return false
    if (filter.to && s.date > filter.to) return false
    if (filter.weekdays?.length && !filter.weekdays.includes(s.weekday)) return false
    if (filter.onlyUnbalanced && (s.verdict === 'balanced' || s.verdict === 'incomplete')) return false
    return true
  })
}

/** The last `days` calendar days ending at `today`, as a filter bound. */
export function lastNDays(today: string, days: number): string {
  const d = fromDateKey(today)
  d.setDate(d.getDate() - (days - 1))
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  return `${y}-${m}-${String(d.getDate()).padStart(2, '0')}`
}

// --- aggregates --------------------------------------------------------------

export interface Totals {
  nights: number
  takingsPence: number
  cashPence: number
  cardPence: number
  guestCount: number
  /** Items sold across the selection, from the department quantities. */
  itemsMilli: number
  /** Items per sale — how much is on an average round. */
  itemsPerSale: number | null
  /** Weighted by guests across the selection, not an average of averages. */
  avePence: number | null
  /** Nights where a variance could be computed at all. */
  reconciledNights: number
  netVariancePence: number
  /** Sum of the absolute variances — a small net can hide a bad fortnight. */
  absVariancePence: number
  balancedNights: number
  shortNights: number
  overNights: number
  /** Cancelled transactions, and what they came to. */
  voidCount: number
  voidPence: number
  /** Drawer openings with no sale behind them. */
  noSaleCount: number
}

export function totals(stats: DayStats[]): Totals {
  let takingsPence = 0
  let cashPence = 0
  let cardPence = 0
  let guestCount = 0
  let itemsMilli = 0
  let reconciledNights = 0
  let netVariancePence = 0
  let absVariancePence = 0
  let balancedNights = 0
  let shortNights = 0
  let overNights = 0
  let voidCount = 0
  let voidPence = 0
  let noSaleCount = 0

  for (const s of stats) {
    takingsPence += s.takingsPence ?? 0
    cashPence += s.cashPence ?? 0
    cardPence += s.cardPence ?? 0
    guestCount += s.guestCount ?? 0
    for (const d of s.departments) itemsMilli += d.qtyMilli
    if (s.variancePence !== null) {
      reconciledNights++
      netVariancePence += s.variancePence
      absVariancePence += Math.abs(s.variancePence)
    }
    if (s.verdict === 'balanced') balancedNights++
    if (s.verdict === 'short') shortNights++
    if (s.verdict === 'over') overNights++
    voidCount += s.voidCount ?? 0
    voidPence += s.voidPence ?? 0
    noSaleCount += s.noSaleCount ?? 0
  }

  return {
    nights: stats.length,
    takingsPence,
    cashPence,
    cardPence,
    guestCount,
    itemsMilli,
    // Rounded to one place: "2.6 items a round" is the useful precision, and
    // three decimals would imply the till counts more finely than it does.
    itemsPerSale: guestCount > 0 ? Math.round((itemsMilli / 1000 / guestCount) * 10) / 10 : null,
    // Recomputed from the totals rather than averaging the nightly averages,
    // which would weight a quiet Monday the same as a packed Saturday.
    avePence: guestCount > 0 ? Math.round(takingsPence / guestCount) : null,
    reconciledNights,
    netVariancePence,
    absVariancePence,
    balancedNights,
    shortNights,
    overNights,
    voidCount,
    voidPence,
    noSaleCount,
  }
}

export interface ClerkStat {
  code: string
  name: string
  /** Nights this clerk appears on. */
  nights: number
  pence: number
  sales: number
  /** Weighted across the selection, not a mean of nightly means. */
  avgPence: number | null
  voids: number
  /** Share of the takings in the selection. */
  percentBp: number
}

/**
 * Who rang up what, across the selection.
 *
 * A deliberate limitation, and an important one: this says nothing about who a
 * shortfall belongs to. The drawer is counted once for the whole night, so a
 * missing twelve pounds cannot be attributed to a person from this receipt —
 * only a till with a drawer per clerk could do that. What this shows is who
 * took what, how busy each was, and who cancelled the most transactions.
 */
export function clerkTotals(stats: DayStats[]): ClerkStat[] {
  const acc = new Map<string, ClerkStat>()

  for (const day of stats) {
    for (const c of day.clerks) {
      const found = acc.get(c.code)
      if (found) {
        found.nights++
        found.pence += c.pence
        found.sales += c.sales ?? 0
        found.voids += c.voids ?? 0
      } else {
        acc.set(c.code, {
          code: c.code,
          name: c.name,
          nights: 1,
          pence: c.pence,
          sales: c.sales ?? 0,
          voids: c.voids ?? 0,
          avgPence: null,
          percentBp: 0,
        })
      }
    }
  }

  const rows = [...acc.values()].sort((a, b) => b.pence - a.pence)
  const total = rows.reduce((a, r) => a + r.pence, 0)
  return rows.map((r) => ({
    ...r,
    avgPence: r.sales > 0 ? Math.round(r.pence / r.sales) : null,
    percentBp: shareBp(r.pence, total),
  }))
}

/**
 * Department totals across the selection, with each one's share.
 *
 * The percentage is of the departments actually included, so filtering to two
 * departments gives their split of each other — which is what a filter is for.
 */
export function departmentTotals(stats: DayStats[], codes?: string[]): DeptStat[] {
  const keep = codes?.length ? new Set(codes) : null
  const acc = new Map<string, { code: string; label: string; pence: number; qtyMilli: number }>()

  for (const day of stats) {
    for (const d of day.departments) {
      if (keep && !keep.has(d.code)) continue
      const found = acc.get(d.code)
      if (found) {
        found.pence += d.pence
        found.qtyMilli += d.qtyMilli
      } else {
        acc.set(d.code, { ...d })
      }
    }
  }

  const rows = sortByRegistry([...acc.values()])
  const total = rows.reduce((a, r) => a + r.pence, 0)
  return rows.map((r) => ({
    ...r,
    percentBp: shareBp(r.pence, total),
    avgPencePerItem: r.qtyMilli > 0 ? Math.round(r.pence / (r.qtyMilli / 1000)) : null,
  }))
}

export interface WeekdayStat {
  weekday: string
  nights: number
  takingsPence: number
  avgTakingsPence: number
  netVariancePence: number
  /** The number the "are Fridays always short?" question actually wants. */
  avgVariancePence: number | null
  shortNights: number
}

/** Monday first — the way a week reads on a rota. */
const WEEK = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

export function weekdayTotals(stats: DayStats[]): WeekdayStat[] {
  return WEEK.map((weekday) => {
    const nights = stats.filter((s) => s.weekday === weekday)
    const reconciled = nights.filter((s) => s.variancePence !== null)
    const takingsPence = nights.reduce((a, s) => a + (s.takingsPence ?? 0), 0)
    const netVariancePence = reconciled.reduce((a, s) => a + (s.variancePence ?? 0), 0)
    return {
      weekday,
      nights: nights.length,
      takingsPence,
      avgTakingsPence: nights.length ? Math.round(takingsPence / nights.length) : 0,
      netVariancePence,
      avgVariancePence: reconciled.length ? Math.round(netVariancePence / reconciled.length) : null,
      shortNights: nights.filter((s) => s.verdict === 'short').length,
    }
  }).filter((w) => w.nights > 0)
}

/** Oldest first — a trend is read left to right. */
export function timeSeries(stats: DayStats[]): DayStats[] {
  return [...stats].sort((a, b) => a.date.localeCompare(b.date))
}

/** Every department code seen in the selection, in registry order. */
export function departmentsPresent(stats: DayStats[]): Array<{ code: string; label: string }> {
  const seen = new Map<string, string>()
  for (const day of stats) for (const d of day.departments) seen.set(d.code, d.label)
  return sortByRegistry([...seen].map(([code, label]) => ({ code, label })))
}


export interface ItemStat {
  code: string
  name: string
  qtyMilli: number
  pence: number
  /** What one went for, averaged over the selection. */
  avgPencePerItem: number | null
  /** Share of the takings across the items shown. */
  percentBp: number
}

/**
 * Every item sold across the selection, biggest first.
 *
 * This is the finest grain the roll offers, and the only place a question like
 * "are we selling more Taddy than Alpine" can be answered. Ordered by takings
 * rather than by quantity: a hundred mixers at £1.85 matter less to the till
 * than forty pints at £5.30, and the till is what this app is about.
 */
export function itemTotals(stats: DayStats[], sortBy: 'value' | 'quantity' = 'value'): ItemStat[] {
  const acc = new Map<string, { code: string; name: string; qtyMilli: number; pence: number }>()

  for (const day of stats) {
    for (const item of day.items) {
      const found = acc.get(item.code)
      if (found) {
        found.qtyMilli += item.qtyMilli
        found.pence += item.pence
      } else {
        acc.set(item.code, { ...item })
      }
    }
  }

  const rows = [...acc.values()]
  const total = rows.reduce((a, r) => a + r.pence, 0)
  return rows
    .map((r) => ({
      ...r,
      avgPencePerItem: r.qtyMilli > 0 ? Math.round(r.pence / (r.qtyMilli / 1000)) : null,
      percentBp: shareBp(r.pence, total),
    }))
    .sort((a, b) =>
      sortBy === 'quantity' ? b.qtyMilli - a.qtyMilli || b.pence - a.pence : b.pence - a.pence || b.qtyMilli - a.qtyMilli,
    )
}


// --- against this time last year ----------------------------------------------

/**
 * A year ago, in weeks.
 *
 * 364 days rather than 365, because 52 whole weeks lands on the same weekday.
 * In a trade where Saturday takes four times what Tuesday does, comparing a
 * Saturday with a Friday because the calendar says "same date" is worse than
 * not comparing at all.
 */
export const YEAR_IN_DAYS = 364

export interface LikeForLike {
  /** The window being reported. */
  nights: number
  takingsPence: number
  /** The matching window a year earlier, on the same weekdays. */
  lastYearNights: number
  lastYearTakingsPence: number
  /** Only nights present in BOTH windows, which is the honest comparison. */
  matchedNights: number
  matchedPence: number
  matchedLastYearPence: number
  changeBp: number | null
  /** True when there is enough of last year to say anything at all. */
  comparable: boolean
}

/**
 * This period against the same period last year, like for like.
 *
 * The "like for like" is the whole point: a pub that opened three extra days
 * this year has not grown, and totting up both windows regardless would say it
 * had. So the headline change is computed only across nights that traded in
 * both windows, and the raw totals are reported beside it rather than instead
 * of it.
 */
export function likeForLike(all: readonly DayStats[], from: string, to: string): LikeForLike {
  const inWindow = (d: DayStats, a: string, b: string) => d.date >= a && d.date <= b
  const lastFrom = addDays(from, -YEAR_IN_DAYS)
  const lastTo = addDays(to, -YEAR_IN_DAYS)

  const now = all.filter((d) => inWindow(d, from, to) && d.takingsPence !== null)
  const then = all.filter((d) => inWindow(d, lastFrom, lastTo) && d.takingsPence !== null)
  const thenByDate = new Map(then.map((d) => [d.date, d]))

  let matchedNights = 0
  let matchedPence = 0
  let matchedLastYearPence = 0
  for (const night of now) {
    const other = thenByDate.get(addDays(night.date, -YEAR_IN_DAYS))
    if (!other) continue
    matchedNights++
    matchedPence += night.takingsPence ?? 0
    matchedLastYearPence += other.takingsPence ?? 0
  }

  return {
    nights: now.length,
    takingsPence: now.reduce((a, d) => a + (d.takingsPence ?? 0), 0),
    lastYearNights: then.length,
    lastYearTakingsPence: then.reduce((a, d) => a + (d.takingsPence ?? 0), 0),
    matchedNights,
    matchedPence,
    matchedLastYearPence,
    changeBp:
      matchedLastYearPence > 0
        ? Math.round(((matchedPence - matchedLastYearPence) / matchedLastYearPence) * 10000)
        : null,
    comparable: matchedNights > 0,
  }
}
