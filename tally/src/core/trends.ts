// ---------------------------------------------------------------------------
// What each category and each line has been doing, week by week.
//
// The roll prints two levels of detail every night: the department totals, and
// the item list underneath them. Both are already captured; this file is what
// turns a pile of nights into a shape — is the draught holding up, is the
// Alpine dying, did the wine pick up when the price went on the board.
//
// Three rules run through all of it, and each one exists because getting it
// wrong produces a confident, wrong answer.
//
// RATES DIVIDE BY NIGHTS WITH A ROLL. A night entered without an item list did
// not sell nothing — it was simply not captured at that level. Counting it as
// a zero drags every rate towards the floor in exact proportion to how often
// the roll was skipped, which is worst precisely when the app is being used
// least carefully.
//
// A PART-FINISHED WEEK IS NOT A WEEK. Three nights into Wednesday, this week's
// takings are lower than last week's and mean nothing at all. Partial weeks are
// carried, because seeing the week fill up is useful, and flagged, because
// comparing one is not.
//
// TOO FEW NIGHTS IS NOT A TREND. Two Saturdays against two Tuesdays is noise
// wearing a percentage. Below the floor, the change is null and the interface
// says nothing rather than something plausible.
// ---------------------------------------------------------------------------

import type { DayStats } from './analytics.ts'
import { departmentLabel, sortByRegistry } from './departments.ts'
import { addDays } from './date.ts'
import { weekStart } from './rota.ts'
import { normalise } from './match.ts'

/** One week of one thing: a category, a line, or the pub as a whole. */
export interface WeekBucket {
  /** The Monday the week opens on. */
  start: string
  /** The Sunday it closes on. */
  end: string
  /** Nights in the week with a takings figure. */
  nights: number
  /** Nights in the week whose receipt carried an item list — the denominator. */
  rollNights: number
  pence: number
  /** Quantity in thousandths, as the till prints it. */
  qtyMilli: number
  /**
   * True while the week is still being traded, or while its nights are still
   * going in. Carried so the week can be watched filling up, flagged so it is
   * never compared with a finished one.
   */
  partial: boolean
}

export interface WeekSeries {
  /** Department code, or item code. */
  key: string
  label: string
  /** Oldest first, one per week in the span — including weeks that sold none. */
  buckets: WeekBucket[]
  totalPence: number
  totalQtyMilli: number
  /**
   * The last finished week against the one before it, in basis points of
   * change in the per-roll-night rate. Null when either week has too few
   * nights to mean anything.
   */
  changeBp: number | null
}

/** Nights on each side before a week-on-week change is worth stating. */
export const MIN_NIGHTS_FOR_CHANGE = 3

/** Nights on each side before a four-week trend is worth stating. */
export const MIN_NIGHTS_FOR_TREND = 6

/** How much movement is worth calling movement at all. */
export const MOVER_BP = 1500

/** The Mondays of the last `count` weeks, oldest first, ending with this week's. */
export function weeksBack(today: string, count: number): string[] {
  const thisMonday = weekStart(today)
  const out: string[] = []
  for (let i = count - 1; i >= 0; i--) out.push(addDays(thisMonday, -7 * i))
  return out
}

/** "w/c 15 Sep" — short enough for an axis. */
export function weekLabel(monday: string): string {
  const d = new Date(`${monday}T00:00:00`)
  if (Number.isNaN(d.getTime())) return monday
  return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })
}

/** Every night in a week, keyed by its Monday, so a series can be built in one pass. */
function byWeek(stats: readonly DayStats[]): Map<string, DayStats[]> {
  const out = new Map<string, DayStats[]>()
  for (const day of stats) {
    const monday = weekStart(day.date)
    const found = out.get(monday)
    if (found) found.push(day)
    else out.set(monday, [day])
  }
  return out
}

/**
 * Whether a week is finished, AT THE LEVEL BEING PLOTTED.
 *
 * A week is partial while today is still inside it — that much is true of
 * everything. The second cause is the subtle one: a night captured at some
 * levels and not others. The department section sits at the top of the roll and
 * the item list runs to another frame, so a night can carry departments and no
 * items. Its takings are complete, its categories are complete, and its item
 * lines are missing — and flagging all three as partial would be as wrong as
 * flagging none.
 *
 * So the caller says what "captured" means for the series it is building.
 */
function isPartial(
  monday: string,
  today: string,
  nights: readonly DayStats[],
  captured: (day: DayStats) => boolean,
): boolean {
  if (addDays(monday, 6) >= today) return true
  return nights.some((d) => d.hasZRead && !captured(d))
}

interface Totals {
  pence: number
  qtyMilli: number
}

/**
 * Build one series from a per-night extractor, over a fixed run of weeks.
 *
 * `captured` says whether a night carries this level of detail at all, which is
 * a different question from whether this particular line sold. It sets both the
 * rate's denominator and whether a week counts as whole.
 */
function series(
  key: string,
  label: string,
  weeks: readonly string[],
  nightsByWeek: ReadonlyMap<string, DayStats[]>,
  today: string,
  captured: (day: DayStats) => boolean,
  pick: (day: DayStats) => Totals | null,
): WeekSeries {
  const buckets: WeekBucket[] = weeks.map((monday) => {
    const nights = nightsByWeek.get(monday) ?? []
    let pence = 0
    let qtyMilli = 0
    for (const night of nights) {
      const got = pick(night)
      if (!got) continue
      pence += got.pence
      qtyMilli += got.qtyMilli
    }
    return {
      start: monday,
      end: addDays(monday, 6),
      nights: nights.filter((d) => d.takingsPence !== null).length,
      rollNights: nights.filter(captured).length,
      pence,
      qtyMilli,
      partial: isPartial(monday, today, nights, captured),
    }
  })

  // The last two FINISHED weeks. A part-finished week is always lower than a
  // whole one, so comparing it would report a collapse every Tuesday.
  const finished = buckets.filter((b) => !b.partial)
  const latest = finished[finished.length - 1]
  const before = finished[finished.length - 2]
  let changeBp: number | null = null
  if (
    latest &&
    before &&
    latest.rollNights >= MIN_NIGHTS_FOR_CHANGE &&
    before.rollNights >= MIN_NIGHTS_FOR_CHANGE
  ) {
    const now = latest.pence / latest.rollNights
    const then = before.pence / before.rollNights
    if (then > 0) changeBp = Math.round(((now - then) / then) * 10000)
  }

  // Weeks before the records start are not weeks the pub took nothing. A pub
  // three weeks in would otherwise open on nine weeks of floor, which makes the
  // chart unreadable and says something untrue. Only LEADING empties go: a
  // genuinely quiet week in the middle is a real zero and stays.
  let from = 0
  while (from < buckets.length && buckets[from]!.nights === 0) from++
  const kept = from >= buckets.length ? [] : buckets.slice(from)

  return {
    key,
    label,
    buckets: kept,
    totalPence: kept.reduce((a, b) => a + b.pence, 0),
    totalQtyMilli: kept.reduce((a, b) => a + b.qtyMilli, 0),
    changeBp,
  }
}

/**
 * Every department the till has printed, week by week.
 *
 * In registry order rather than by size, so a chart's legend and colours never
 * reshuffle between visits — a category that had a quiet week must not change
 * colour because of it.
 */
export function categoryWeeks(
  stats: readonly DayStats[],
  today: string,
  weekCount = 12,
): WeekSeries[] {
  const weeks = weeksBack(today, weekCount)
  const earliest = weeks[0] ?? today
  const inSpan = stats.filter((d) => d.date >= earliest)
  const nightsByWeek = byWeek(inSpan)

  const seen = new Map<string, string>()
  for (const day of inSpan) for (const d of day.departments) if (!seen.has(d.code)) seen.set(d.code, d.label)

  return sortByRegistry([...seen.entries()].map(([code, label]) => ({ code, label }))).map(({ code, label }) =>
    series(
      code,
      departmentLabel(code, label),
      weeks,
      nightsByWeek,
      today,
      // Captured means the department section was read, not that this
      // department sold: a quiet Tuesday for the wine is a zero, not a gap.
      (day) => day.departments.length > 0,
      (day) => {
        const line = day.departments.find((d) => d.code === code)
        return line ? { pence: line.pence, qtyMilli: line.qtyMilli } : null
      },
    ),
  )
}

/** The pub as a whole, week by week — the line everything else is read against. */
export function takingsWeeks(stats: readonly DayStats[], today: string, weekCount = 12): WeekSeries {
  const weeks = weeksBack(today, weekCount)
  const earliest = weeks[0] ?? today
  const nightsByWeek = byWeek(stats.filter((d) => d.date >= earliest))
  // A takings figure is all this level needs. A night read for its totals alone
  // is a complete night here, whatever else the photographs missed.
  return series(
    'all',
    'Takings',
    weeks,
    nightsByWeek,
    today,
    (day) => day.takingsPence !== null,
    (day) => (day.takingsPence === null ? null : { pence: day.takingsPence, qtyMilli: day.guestCount ?? 0 }),
  )
}

/** One line off the till, week by week. Matched on code first, printed name second. */
export function itemWeeks(
  stats: readonly DayStats[],
  today: string,
  code: string,
  name: string,
  weekCount = 12,
): WeekSeries {
  const weeks = weeksBack(today, weekCount)
  const earliest = weeks[0] ?? today
  const nightsByWeek = byWeek(stats.filter((d) => d.date >= earliest))
  const wantCode = code.trim().toUpperCase()
  const wantName = normalise(name)
  return series(
    wantCode,
    name,
    weeks,
    nightsByWeek,
    today,
    (day) => day.items.length > 0,
    (day) => {
      const line = day.items.find(
        (i) => (wantCode !== '' && i.code.trim().toUpperCase() === wantCode) || normalise(i.name) === wantName,
      )
      return line ? { pence: line.pence, qtyMilli: line.qtyMilli } : null
    },
  )
}


// ---------------------------------------------------------------------------
// Rising and falling.
//
// A dashboard total answers "how did we do". It cannot answer "what changed",
// and what changed is the question that leads to a decision: take the line off,
// move the price, order more of it.
//
// Measured over the last four nights-with-a-roll-worth of trade against the
// four before, by rate per night rather than by total, so a fortnight shut does
// not read as the beer dying. Both halves need enough nights before anything is
// claimed, and a line that was never going anywhere is not news for moving a
// few pence.
// ---------------------------------------------------------------------------

export type MoverKind = 'category' | 'item'

export interface Mover {
  kind: MoverKind
  key: string
  label: string
  /** Change in the per-roll-night rate, in basis points. */
  changeBp: number
  /** What it does now, per night with a roll. */
  nowPencePerNight: number
  beforePencePerNight: number
  nowQtyMilliPerNight: number
  beforeQtyMilliPerNight: number
  /** Nights behind each half, so a reader can weigh it. */
  nights: number
  beforeNights: number
  /** Takings across the recent half, so the big movers can be put first. */
  recentPence: number
}

/**
 * Below this a night, a line is too small for its swing to be worth a decision.
 *
 * Per night rather than per window, so the floor means the same thing whether
 * four weeks or twelve are being compared. Ten pounds a night is well under one
 * per cent of a night's trade here: a pickled egg going from one to four is a
 * tripling and is not news.
 */
export const MOVER_FLOOR_PENCE_PER_NIGHT = 1000

interface Half {
  pence: number
  qtyMilli: number
}

function rateOf(nights: number, half: Half): { pence: number; qtyMilli: number } {
  if (nights <= 0) return { pence: 0, qtyMilli: 0 }
  return { pence: half.pence / nights, qtyMilli: half.qtyMilli / nights }
}

/**
 * What is growing and what is dying, biggest first.
 *
 * `nightsEachSide` is in nights WITH AN ITEM LIST, not calendar nights: four
 * weeks of a six-night pub is twenty-four, and a roll skipped here and there
 * should shorten the window rather than dilute it.
 */
export function movers(
  stats: readonly DayStats[],
  nightsEachSide = 24,
): { rising: Mover[]; falling: Mover[] } {
  const sorted = [...stats].sort((a, b) => a.date.localeCompare(b.date))

  const gather = (
    nights: readonly DayStats[],
    lines: (day: DayStats) => ReadonlyArray<{ code: string; name?: string; label?: string; pence: number; qtyMilli: number }>,
  ) => {
    const acc = new Map<string, { label: string; pence: number; qtyMilli: number }>()
    for (const day of nights) {
      for (const line of lines(day)) {
        const label = line.label ?? line.name ?? line.code
        const found = acc.get(line.code)
        if (found) {
          found.pence += line.pence
          found.qtyMilli += line.qtyMilli
          found.label = label
        } else {
          acc.set(line.code, { label, pence: line.pence, qtyMilli: line.qtyMilli })
        }
      }
    }
    return acc
  }

  const out: Mover[] = []
  const compare = (
    kind: MoverKind,
    lines: (day: DayStats) => ReadonlyArray<{ code: string; name?: string; label?: string; pence: number; qtyMilli: number }>,
  ) => {
    // Each kind counts its own nights. The department section sits at the top
    // of the roll and the item list runs to another frame, so a pub that
    // photographs only the first part has categories and no items — and would
    // otherwise be told nothing at all about either.
    const captured = sorted.filter((d) => lines(d).length > 0)
    const recent = captured.slice(-nightsEachSide)
    const before = captured.slice(-2 * nightsEachSide, -nightsEachSide)
    if (recent.length < MIN_NIGHTS_FOR_TREND || before.length < MIN_NIGHTS_FOR_TREND) return

    const now = gather(recent, lines)
    const then = gather(before, lines)
    // The union of both halves: a line that has stopped selling entirely is the
    // most important mover there is, and it appears in only one of them.
    for (const key of new Set([...now.keys(), ...then.keys()])) {
      const a = now.get(key) ?? { label: then.get(key)?.label ?? key, pence: 0, qtyMilli: 0 }
      const b = then.get(key) ?? { label: a.label, pence: 0, qtyMilli: 0 }
      const nowRate = rateOf(recent.length, a)
      const beforeRate = rateOf(before.length, b)
      // Nothing to divide by, so nothing to claim: a line that did not exist
      // before is new rather than up by an infinite percentage.
      if (beforeRate.pence <= 0) continue
      if (Math.max(nowRate.pence, beforeRate.pence) < MOVER_FLOOR_PENCE_PER_NIGHT) continue
      const changeBp = Math.round(((nowRate.pence - beforeRate.pence) / beforeRate.pence) * 10000)
      if (Math.abs(changeBp) < MOVER_BP) continue
      out.push({
        kind,
        key,
        // Said the way a person says it. A department arrives as the till
        // prints it — all capitals — and "WINE" in a sentence reads as
        // shouting rather than as a category.
        label: kind === 'category' ? departmentLabel(key, a.label) : a.label,
        changeBp,
        nowPencePerNight: Math.round(nowRate.pence),
        beforePencePerNight: Math.round(beforeRate.pence),
        nowQtyMilliPerNight: Math.round(nowRate.qtyMilli),
        beforeQtyMilliPerNight: Math.round(beforeRate.qtyMilli),
        nights: recent.length,
        beforeNights: before.length,
        recentPence: a.pence,
      })
    }
  }

  compare('category', (day) => day.departments)
  compare('item', (day) => day.items)

  // Sorted by how far each moved, then by size, so the top of each list is
  // both the biggest swing and worth caring about.
  const bySwing = (a: Mover, b: Mover) => Math.abs(b.changeBp) - Math.abs(a.changeBp) || b.recentPence - a.recentPence
  return {
    rising: out.filter((m) => m.changeBp > 0).sort(bySwing),
    falling: out.filter((m) => m.changeBp < 0).sort(bySwing),
  }
}
