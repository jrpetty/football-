// ---------------------------------------------------------------------------
// The rota.
//
// Two jobs, and they are not the same job.
//
// The first is ordinary and weekly: who is working which nights, from when
// until when. That is a thing a pub needs whether or not anybody is counting
// anything, and it is what most of this file is.
//
// The second is the reason it lives inside Tally. The till already says which
// clerk rang a sale up, but on a busy Saturday everyone rings up on the same
// clerk key, so "who rang it" is nearly useless for explaining a short drawer.
// Who was actually *behind the bar* is the thing that varies, and once the rota
// knows that, a night's variance can be set against the people who worked it.
//
// That comparison has to be made carefully or it is worse than not making it.
// Three people work most nights, so a short Saturday cannot be pinned on one of
// them. What the arithmetic below produces is a *correlation across nights* —
// how the nights someone worked compare with the nights they did not — with the
// sample size stated next to it, because five nights of anything means nothing.
// It is a place to look, never a verdict. The screen says so too.
// ---------------------------------------------------------------------------

import { addDays, dateKey, fromDateKey, weekdayOf } from './date.ts'
import { bestMatch, normalise } from './match.ts'

/** Minutes in a day, for shifts that finish after midnight. */
const DAY_MINUTES = 24 * 60

/** Below this many nights either side, a comparison is noise and is withheld. */
export const MIN_NIGHTS_FOR_COMPARISON = 5

export interface Person {
  id: string
  name: string
  /** Palette slot, fixed at creation so a person's colour never moves. */
  slot: number
  /** Optional: without it, no labour cost is shown rather than a wrong one. */
  ratePencePerHour?: number
  archived?: boolean
}

/** A start and a finish, in minutes since midnight. */
export interface Hours {
  startMin: number
  endMin: number
}

/** Six until close — where the boxes start on a rota with nothing on it yet. */
export const DEFAULT_SHIFT: Hours = { startMin: 18 * 60, endMin: 23 * 60 + 30 }

export interface Shift {
  /** `date:personId` — one shift per person per day, so re-tapping corrects it. */
  id: string
  date: string
  personId: string
  startMin: number
  endMin: number
}

export function shiftId(date: string, personId: string): string {
  return `${date}:${personId}`
}

/** One person on one night, for hours somebody chose. */
export function shiftAt(personId: string, date: string, hours: Hours): Shift {
  return { id: shiftId(date, personId), date, personId, startMin: hours.startMin, endMin: hours.endMin }
}

/**
 * The hours to start from when putting somebody on.
 *
 * Never a property of the person — people do not have "usual hours", nights
 * have hours, and the same person works six-till-close on Saturday and a
 * lunchtime on Sunday. So: what the rest of that night is already set to,
 * since a crew nearly always shares a session; failing that the last hours
 * anybody was put on for, which is what she typed most recently; failing
 * that six until close. Whatever comes out is shown in a box she can change
 * before tapping anyone, so nothing here is ever assumed silently.
 */
export function hoursFor(date: string, shifts: readonly Shift[]): Hours {
  const onTheNight = shifts.filter((s) => s.date === date)
  if (onTheNight.length > 0) {
    const first = onTheNight[0] as Shift
    return { startMin: first.startMin, endMin: first.endMin }
  }
  let latest: Shift | undefined
  for (const s of shifts) if (!latest || s.date > latest.date) latest = s
  return latest ? { startMin: latest.startMin, endMin: latest.endMin } : DEFAULT_SHIFT
}

/**
 * How long a shift runs, in minutes.
 *
 * A pub shift ends after midnight more often than not — six until half twelve
 * is 18:00 to 00:30, and subtracting those the naive way gives minus seventeen
 * and a half hours. An end at or before the start means the next day.
 */
export function shiftMinutes(shift: { startMin: number; endMin: number }): number {
  const raw = shift.endMin - shift.startMin
  return raw > 0 ? raw : raw + DAY_MINUTES
}

/** What a shift costs, when the person has a rate. Null rather than zero. */
export function shiftCostPence(shift: Shift, person: Person | undefined): number | null {
  if (!person?.ratePencePerHour) return null
  return Math.round((shiftMinutes(shift) * person.ratePencePerHour) / 60)
}

// --- weeks -------------------------------------------------------------------

/** The Monday of the week a date falls in. Pub weeks run Monday to Sunday. */
export function weekStart(key: string): string {
  const d = fromDateKey(key)
  if (Number.isNaN(d.getTime())) return key
  // getDay() is 0 for Sunday, so Sunday is six days into the week, not minus one.
  const back = (d.getDay() + 6) % 7
  return addDays(dateKey(d), -back)
}

/** The seven dates of the week beginning at `monday`. */
export function weekDays(monday: string): string[] {
  return Array.from({ length: 7 }, (_, i) => addDays(monday, i))
}

/** "18:00" from minutes since midnight. */
export function formatTime(min: number): string {
  const m = ((min % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`
}

/** Minutes since midnight from "18:00", or null if it is not a time. */
export function parseTime(text: string): number | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(text.trim())
  if (!m) return null
  const hours = Number(m[1])
  const mins = Number(m[2])
  if (hours > 23 || mins > 59) return null
  return hours * 60 + mins
}

/** "5h 30m", or "5h" on the hour — for a column of hours worked. */
export function formatHours(minutes: number): string {
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  if (h === 0) return `${m}m`
  return m === 0 ? `${h}h` : `${h}h ${m}m`
}

// --- a night's crew ----------------------------------------------------------

export interface CrewNight {
  date: string
  shifts: Shift[]
  minutes: number
  /** Null when nobody on has a rate — an unknown cost, not a free night. */
  costPence: number | null
}

/** Everyone on for one date, with the hours and what they came to. */
export function crewFor(date: string, shifts: readonly Shift[], people: readonly Person[]): CrewNight {
  const byId = new Map(people.map((p) => [p.id, p]))
  const mine = shifts.filter((s) => s.date === date)
  let minutes = 0
  let cost = 0
  let priced = false
  for (const s of mine) {
    minutes += shiftMinutes(s)
    const c = shiftCostPence(s, byId.get(s.personId))
    if (c !== null) {
      cost += c
      priced = true
    }
  }
  return { date, shifts: mine, minutes, costPence: priced ? cost : null }
}

// --- how the nights someone worked compare -----------------------------------

/** The one figure per night this comparison needs, so it stays testable. */
export interface RotaNight {
  date: string
  variancePence: number | null
  takingsPence: number | null
}

export interface CrewStat {
  personId: string
  name: string
  slot: number
  /** Nights worked in the window — all of them, whether or not they reconciled. */
  nightsOn: number
  minutes: number
  costPence: number | null
  /** Nights that reconciled and had a rota, split by whether they were on. */
  comparedOn: number
  comparedOff: number
  avgTakingsOnPence: number | null
  avgVarianceOnPence: number | null
  avgVarianceOffPence: number | null
  /** On minus off. Negative means the drawer runs shorter on their nights. */
  differencePence: number | null
  /** False when either side is too thin to mean anything. */
  meaningful: boolean
  /** Of the nights they worked that reconciled: how they came out. */
  balancedNights: number
  shortNights: number
  overNights: number
  /** The worst single night on their watch, for context on an average. */
  worstNightPence: number | null
  worstNightDate: string | null
}

/**
 * Each person's nights against everybody else's.
 *
 * The critical exclusion is nights with no rota recorded at all. Counting those
 * as nights the person "was off" would bury a real pattern under every night
 * from before the rota existed, and would do it silently. A night only enters
 * the comparison once someone has said who worked it.
 */
export function crewStats(
  nights: readonly RotaNight[],
  shifts: readonly Shift[],
  people: readonly Person[],
  tolerancePence = 0,
): CrewStat[] {
  const rotaed = new Set(shifts.map((s) => s.date))
  const onByDate = new Map<string, Set<string>>()
  for (const s of shifts) {
    const found = onByDate.get(s.date)
    if (found) found.add(s.personId)
    else onByDate.set(s.date, new Set([s.personId]))
  }

  const inWindow = new Set(nights.map((n) => n.date))
  const byPerson = new Map<string, Shift[]>()
  for (const s of shifts) {
    if (!inWindow.has(s.date)) continue
    const found = byPerson.get(s.personId)
    if (found) found.push(s)
    else byPerson.set(s.personId, [s])
  }

  // Only nights that both reconciled and have a rota can be compared.
  const comparable = nights.filter((n) => n.variancePence !== null && rotaed.has(n.date))

  const mean = (values: number[]): number | null =>
    values.length === 0 ? null : Math.round(values.reduce((a, v) => a + v, 0) / values.length)

  return people
    .filter((p) => !p.archived || byPerson.has(p.id))
    .map((person) => {
      const mine = byPerson.get(person.id) ?? []
      const minutes = mine.reduce((a, s) => a + shiftMinutes(s), 0)
      const costPence = person.ratePencePerHour
        ? Math.round((minutes * person.ratePencePerHour) / 60)
        : null

      const on: number[] = []
      const off: number[] = []
      const takingsOn: number[] = []
      for (const night of comparable) {
        const wasOn = onByDate.get(night.date)?.has(person.id) ?? false
        ;(wasOn ? on : off).push(night.variancePence as number)
        if (wasOn && night.takingsPence !== null) takingsOn.push(night.takingsPence)
      }

      // How their own nights came out, one by one — an average of −£4 reads
      // very differently if it is one bad night or twenty small ones.
      let balancedNights = 0
      let shortNights = 0
      let overNights = 0
      let worstNightPence: number | null = null
      let worstNightDate: string | null = null
      for (const night of comparable) {
        if (!(onByDate.get(night.date)?.has(person.id) ?? false)) continue
        const v = night.variancePence as number
        if (Math.abs(v) <= tolerancePence) balancedNights++
        else if (v < 0) shortNights++
        else overNights++
        if (worstNightPence === null || v < worstNightPence) {
          worstNightPence = v
          worstNightDate = night.date
        }
      }

      const avgOn = mean(on)
      const avgOff = mean(off)
      return {
        personId: person.id,
        name: person.name,
        slot: person.slot,
        nightsOn: mine.length,
        minutes,
        costPence,
        comparedOn: on.length,
        comparedOff: off.length,
        avgTakingsOnPence: mean(takingsOn),
        avgVarianceOnPence: avgOn,
        avgVarianceOffPence: avgOff,
        differencePence: avgOn !== null && avgOff !== null ? avgOn - avgOff : null,
        meaningful: on.length >= MIN_NIGHTS_FOR_COMPARISON && off.length >= MIN_NIGHTS_FOR_COMPARISON,
        balancedNights,
        shortNights,
        overNights,
        worstNightPence,
        worstNightDate,
      }
    })
    .sort((a, b) => b.nightsOn - a.nightsOn || a.name.localeCompare(b.name))
}

/** Labour as a share of takings, in basis points. The publican's ratio. */
export function labourShareBp(costPence: number, takingsPence: number): number | null {
  if (takingsPence <= 0) return null
  return Math.round((costPence / takingsPence) * 10000)
}


// --- reading the rota off a photograph ---------------------------------------

export interface ShiftProposal {
  /** The name as written on the paper. */
  written: string
  /** The day column, as written. */
  writtenDay: string
  personId?: string
  personName?: string
  date?: string
  startMin?: number
  endMin?: number
  /**
   * Whether the times came off the paper, or are only a starting point the
   * paper never gave — in which case the screen puts them in a box to change.
   */
  timesFrom: 'paper' | 'chosen' | null
  status: 'new' | 'already' | 'ambiguous' | 'unknown-person' | 'unknown-day'
  between?: string[]
}

/**
 * Work out which of the week's dates a written day column means.
 *
 * Handles the two ways a rota labels a column — by name ("Mon", "Monday") and
 * by date ("Sat 29", "29") — and refuses anything else rather than defaulting
 * to a day, since putting a shift on the wrong night is the whole risk here.
 */
export function dayToDate(written: string, days: readonly string[]): string | null {
  const text = normalise(written)
  if (!text) return null

  for (const date of days) {
    const weekday = normalise(weekdayOf(date))
    // "MON" against "MONDAY", and "MONDAY" against itself.
    if (weekday && (text === weekday || text.startsWith(weekday.slice(0, 3)))) return date
  }

  // A bare day of the month — "29", or the 29 inside "SAT 29".
  const number = /(\d{1,2})/.exec(text)
  if (number) {
    const wanted = Number(number[1])
    for (const date of days) {
      if (fromDateKey(date).getDate() === wanted) return date
    }
  }
  return null
}

/**
 * Turn a photographed rota into a proposal for the week on screen.
 *
 * Times off the paper win. Where the paper only ticks a box, the row starts at
 * whatever that night is already set to and is marked as not having come off
 * the paper, so the screen can put the hours in a box to be chosen rather than
 * crediting anybody with hours the rota never claimed.
 */
export function proposeShifts(
  scanned: ReadonlyArray<{ name: string; day: string; start: string; end: string }>,
  people: readonly Person[],
  days: readonly string[],
  existing: readonly Shift[],
): ShiftProposal[] {
  const live = people.filter((p) => !p.archived)
  const have = new Set(existing.map((s) => s.id))

  return scanned.map((row) => {
    const match = bestMatch(row.name, live, (p) => p.name)
    if (match.kind === 'unmatched') {
      return { written: row.name, writtenDay: row.day, timesFrom: null, status: 'unknown-person' as const }
    }
    if (match.kind === 'ambiguous') {
      return {
        written: row.name,
        writtenDay: row.day,
        timesFrom: null,
        status: 'ambiguous' as const,
        between: match.between.map((p) => p.name),
      }
    }

    const person = match.value
    const date = dayToDate(row.day, days)
    if (!date) {
      return {
        written: row.name,
        writtenDay: row.day,
        personId: person.id,
        personName: person.name,
        timesFrom: null,
        status: 'unknown-day' as const,
      }
    }

    const start = parseTime(row.start)
    const end = parseTime(row.end)
    const fromPaper = start !== null && end !== null
    const night = hoursFor(date, existing)
    return {
      written: row.name,
      writtenDay: row.day,
      personId: person.id,
      personName: person.name,
      date,
      startMin: start ?? night.startMin,
      endMin: end ?? night.endMin,
      timesFrom: fromPaper ? ('paper' as const) : ('chosen' as const),
      status: have.has(shiftId(date, person.id)) ? ('already' as const) : ('new' as const),
    }
  })
}

/** The shifts an accepted proposal would write. */
export function shiftsFrom(proposals: readonly ShiftProposal[]): Shift[] {
  const out: Shift[] = []
  for (const p of proposals) {
    if (!p.personId || !p.date || p.startMin === undefined || p.endMin === undefined) continue
    out.push({ id: shiftId(p.date, p.personId), date: p.date, personId: p.personId, startMin: p.startMin, endMin: p.endMin })
  }
  return out
}


/**
 * Who runs the tightest till.
 *
 * Ranked by how often their nights balance, not by the size of the variance:
 * one freak night of −£80 says less about somebody than twenty nights of −£3,
 * and a rate is comparable between someone who works twice a week and someone
 * who works five times.
 *
 * Only people with enough nights to judge are ranked at all. The rest come back
 * unranked rather than bottom, because "no record yet" is not a bad record.
 */
export interface CrewRank {
  stat: CrewStat
  /** Nights that balanced, in basis points of nights that could be judged. */
  balancedBp: number | null
  place: number | null
}

export function crewRanking(stats: readonly CrewStat[]): CrewRank[] {
  const rated = stats.map((stat) => {
    const judged = stat.balancedNights + stat.shortNights + stat.overNights
    return {
      stat,
      balancedBp: judged > 0 ? Math.round((stat.balancedNights / judged) * 10000) : null,
      judged,
    }
  })

  const rankable = rated
    .filter((r) => r.judged >= MIN_NIGHTS_FOR_COMPARISON)
    .sort((a, b) => (b.balancedBp ?? 0) - (a.balancedBp ?? 0) || b.judged - a.judged)

  const places = new Map(rankable.map((r, i) => [r.stat.personId, i + 1]))
  return rated
    .map((r) => ({ stat: r.stat, balancedBp: r.balancedBp, place: places.get(r.stat.personId) ?? null }))
    .sort((a, b) => (a.place ?? 99) - (b.place ?? 99) || b.stat.nightsOn - a.stat.nightsOn)
}
