// ---------------------------------------------------------------------------
// Turning a pile of nights into the things worth knowing.
//
// All pure, all integer arithmetic, all tested. The dashboard renders what this
// file returns and does no counting of its own — which is what keeps a chart
// from quietly disagreeing with the table beside it.
// ---------------------------------------------------------------------------

import { departmentLabel, sortByRegistry } from './departments.ts'
import { fromDateKey, weekdayOf } from './date.ts'
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
  }
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
