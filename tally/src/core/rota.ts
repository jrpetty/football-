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

import { addDays, dateKey, fromDateKey } from './date.ts'

/** Minutes in a day, for shifts that finish after midnight. */
const DAY_MINUTES = 24 * 60

/** Below this many nights either side, a comparison is noise and is withheld. */
export const MIN_NIGHTS_FOR_COMPARISON = 5

export interface Person {
  id: string
  name: string
  /** Palette slot, fixed at creation so a person's colour never moves. */
  slot: number
  /** The shift they usually work, so putting them on a day is one tap. */
  defaultStartMin: number
  defaultEndMin: number
  /** Optional: without it, no labour cost is shown rather than a wrong one. */
  ratePencePerHour?: number
  archived?: boolean
}

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

/** A shift at the person's usual hours. */
export function shiftFor(person: Person, date: string): Shift {
  return {
    id: shiftId(date, person.id),
    date,
    personId: person.id,
    startMin: person.defaultStartMin,
    endMin: person.defaultEndMin,
  }
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
      }
    })
    .sort((a, b) => b.nightsOn - a.nightsOn || a.name.localeCompare(b.name))
}

/** Labour as a share of takings, in basis points. The publican's ratio. */
export function labourShareBp(costPence: number, takingsPence: number): number | null {
  if (takingsPence <= 0) return null
  return Math.round((costPence / takingsPence) * 10000)
}
