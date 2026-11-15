// How long a trip takes: a rough time for every "where do I go" answer.
//
// The speeds are estimates, not the game's numbers. Bohemia's scripts give
// stamina and food use per second, not metres per second, and the wiki lists
// car top speeds only. So: jogging (which in DayZ 1.x costs no stamina, so you
// can keep it up) at about 16 km/h; sprinting at about 22 km/h while stamina
// lasts; swimming slowly. Cars from the wiki's top speeds (a Sarka 120 does
// about 100 km/h on flat roads), averaged well below top speed for bends,
// towns and hills, and slower off the road.
//
// Sprinting is generalised: how long you can sprint depends on stamina and
// what you carry, so "with sprints" assumes you sprint whenever stamina lets
// you and jog between, about 19 km/h on average. Heavy loads sprint less.
//
// A straight line is shorter than the way you walk or drive, so answers from
// a straight line add a detour (more by car, which keeps to the roads). On the
// map the length over the ground is used instead.

import type { Travel } from './terrain.ts'

/** Metres per second (estimates). */
export const SPEED = {
  /** Jogging: about 16 km/h, no stamina cost. */
  jog: 16 / 3.6,
  /** Sprinting: about 22 km/h, while stamina lasts. */
  sprint: 22 / 3.6,
  /** Sprinting whenever stamina lets you, jogging between: about 19 km/h on average (less when heavily loaded). */
  mixed: 19 / 3.6,
  /** Swimming: about 5 km/h. */
  swim: 5 / 3.6,
  /** A car on the road, on average (bends, towns, hills): about 65 km/h. */
  carRoad: 65 / 3.6,
  /** A car off the road (fields, tracks): about 30 km/h. */
  carOff: 30 / 3.6,
} as const

/** A straight line times this is roughly the way over the ground. */
export const DETOUR: Readonly<Record<Travel, number>> = { foot: 1.15, car: 1.4 }

/** Below this, a car is not worth mentioning. */
const CAR_FROM_M = 1500

export interface Ground {
  /** Length over the ground, in metres. */
  readonly lengthM: number
  /** Share of that on roads (0 to 1). */
  readonly road: number
  /** Share in water (0 to 1), swum on foot. */
  readonly water?: number
}

/** Seconds for a way over the ground, on foot (jogging, or with sprints) or by car. */
export function seconds(way: Ground, travel: Travel, sprints = false): number {
  const L = Math.max(0, way.lengthM)
  if (travel === 'car') {
    const road = clamp(way.road)
    return (L * road) / SPEED.carRoad + (L * (1 - road)) / SPEED.carOff
  }
  const water = clamp(way.water ?? 0)
  return (L * (1 - water)) / (sprints ? SPEED.mixed : SPEED.jog) + (L * water) / SPEED.swim
}

/** Seconds for a straight line, with the detour added. */
export function straightSeconds(m: number, travel: Travel, sprints = false): number {
  const lengthM = m * DETOUR[travel]
  // By car most of a trip of a few km is on roads; the start and end are not.
  return seconds({ lengthM, road: travel === 'car' ? 0.75 : 0 }, travel, sprints)
}

/** "about 23 min jogging (19 with sprints)": the sprint time only when it saves a minute or more. */
export function footTime(jog: number, sprint: number): string {
  const a = duration(jog)
  const b = bare(sprint)
  return b !== bare(jog) && jog - sprint >= 45 ? `${a} jogging (${/h/.test(b) ? b : b.replace(/ min$/, '')} with sprints)` : `${a} jogging`
}

/** "under a minute", "about 6 min", "about 1 h 20 min". */
export function duration(s: number): string {
  if (s < 50) return 'under a minute'
  const min = Math.max(1, Math.round(s / 60))
  if (min < 60) return `about ${min} min`
  const h = Math.floor(min / 60)
  const rest = Math.round((min % 60) / 5) * 5
  return rest ? `about ${h} h ${rest} min` : `about ${h} h`
}

/** "about 6 min" without the "about" (for lists that already say it). */
const bare = (s: number) => duration(s).replace(/^about /, '')

/**
 * The time for a straight-line distance, in words: "about 6 min jogging" and,
 * for longer trips, "about 2 min by car". Rough: the way over the ground is
 * longer than a straight line.
 */
export function tripTime(m: number): string {
  const foot = footTime(straightSeconds(m, 'foot'), straightSeconds(m, 'foot', true))
  if (m < CAR_FROM_M) return foot
  return `${foot}, ${bare(straightSeconds(m, 'car'))} by car`
}

/** The short form for a list row: "6 min on foot". */
export function tripShort(m: number): string {
  return `${bare(straightSeconds(m, 'foot'))} on foot`
}

/** The time for a way over the ground, in words: "about 9 min jogging (7 with sprints)" or "about 4 min by car". */
export function groundTime(way: Ground, travel: Travel): string {
  if (travel === 'car') return `${duration(seconds(way, 'car'))} by car`
  return footTime(seconds(way, 'foot'), seconds(way, 'foot', true))
}

/** One line on how the times are worked out, for answers and the map. */
export const TIME_NOTE = 'Times are rough: jogging about 16 km/h (no stamina cost); with sprints whenever stamina lets you, about 19 km/h (less when heavily loaded); a car about 65 km/h on roads.'

function clamp(x: number): number {
  return Number.isFinite(x) ? Math.min(1, Math.max(0, x)) : 0
}
