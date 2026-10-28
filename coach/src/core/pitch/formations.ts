// The formation a team's average positions make, by matching the ten
// outfield players against the shapes FC teams use (4-4-2 to 5-2-1-2). Each
// shape is laid out in the team's own box: depth from the deepest outfield
// line (0) to the most advanced (1), width from one touchline-most player (0)
// to the other (1). The average positions are scaled into the same box, so a
// deep, compact block and a stretched one read as the same shape, and each
// player is matched to a slot with the least total distance. Wingers who drop
// in line with the midfield read as the midfield (a 4-3-3 defending as a
// 4-5-1 is a 4-5-1 on the radar).

import { assign } from './assign.ts'

type P = readonly [number, number]

const back4: P[] = [[0, 0.04], [0, 0.35], [0, 0.65], [0, 0.96]]
const back3: P[] = [[0, 0.22], [0, 0.5], [0, 0.78]]
const back5: P[] = [[0.08, 0], [0, 0.26], [0, 0.5], [0, 0.74], [0.08, 1]]
const row = (d: number, ws: readonly number[]): P[] => ws.map((w) => [d, w] as P)

/** Outfield slots per shape (depth, width), back to front. */
export const FORMATION_SHAPES: Readonly<Record<string, readonly P[]>> = {
  '4-4-2': [...back4, ...row(0.5, [0.04, 0.35, 0.65, 0.96]), ...row(1, [0.38, 0.62])],
  '4-4-1-1': [...back4, ...row(0.45, [0.04, 0.35, 0.65, 0.96]), [0.75, 0.5], [1, 0.5]],
  '4-3-3': [...back4, ...row(0.42, [0.27, 0.5, 0.73]), [0.92, 0.06], [1, 0.5], [0.92, 0.94]],
  '4-3-1-2': [...back4, ...row(0.38, [0.24, 0.5, 0.76]), [0.68, 0.5], ...row(1, [0.38, 0.62])],
  '4-1-2-1-2': [...back4, [0.28, 0.5], ...row(0.48, [0.28, 0.72]), [0.72, 0.5], ...row(1, [0.38, 0.62])],
  '4-2-3-1': [...back4, ...row(0.33, [0.37, 0.63]), [0.68, 0.08], [0.7, 0.5], [0.68, 0.92], [1, 0.5]],
  '4-2-1-3': [...back4, ...row(0.33, [0.37, 0.63]), [0.62, 0.5], [0.95, 0.06], [1, 0.5], [0.95, 0.94]],
  '4-5-1': [...back4, ...row(0.48, [0.04, 0.3, 0.5, 0.7, 0.96]), [1, 0.5]],
  '4-1-4-1': [...back4, [0.28, 0.5], ...row(0.58, [0.05, 0.36, 0.64, 0.95]), [1, 0.5]],
  '4-2-2-2': [...back4, ...row(0.33, [0.36, 0.64]), ...row(0.68, [0.15, 0.85]), ...row(1, [0.38, 0.62])],
  '4-3-2-1': [...back4, ...row(0.38, [0.24, 0.5, 0.76]), ...row(0.72, [0.33, 0.67]), [1, 0.5]],
  '3-5-2': [...back3, [0.45, 0], [0.4, 0.3], [0.4, 0.5], [0.4, 0.7], [0.45, 1], ...row(1, [0.38, 0.62])],
  '3-4-3': [...back3, ...row(0.42, [0.02, 0.38, 0.62, 0.98]), [0.95, 0.1], [1, 0.5], [0.95, 0.9]],
  '3-4-1-2': [...back3, ...row(0.4, [0.02, 0.38, 0.62, 0.98]), [0.7, 0.5], ...row(1, [0.38, 0.62])],
  '5-3-2': [...back5, ...row(0.45, [0.25, 0.5, 0.75]), ...row(1, [0.38, 0.62])],
  '5-2-1-2': [...back5, ...row(0.4, [0.36, 0.64]), [0.68, 0.5], ...row(1, [0.38, 0.62])],
  '5-4-1': [...back5, ...row(0.5, [0.05, 0.36, 0.64, 0.95]), [1, 0.5]],
  '5-2-3': [...back5, ...row(0.42, [0.36, 0.64]), [0.95, 0.1], [1, 0.5], [0.95, 0.9]],
}

export interface FormationFit {
  readonly formation: string
  /** Mean squared distance per player in the unit box (0 is a perfect fit). */
  readonly cost: number
  /** The next best shape and its cost. */
  readonly next: string
  readonly nextCost: number
}

/** The best-fitting shapes for ten average outfield positions (own goal at x = 0). */
export function fitFormation(slots: readonly (readonly [number, number])[]): FormationFit | null {
  if (slots.length !== 10) return null
  const xs = slots.map((p) => p[0])
  const ys = slots.map((p) => p[1])
  const x0 = Math.min(...xs)
  const y0 = Math.min(...ys)
  const dx = Math.max(...xs) - x0
  const dy = Math.max(...ys) - y0
  if (dx < 5 || dy < 5) return null
  const unit = slots.map((p) => [(p[0] - x0) / dx, (p[1] - y0) / dy] as const)
  const fits = Object.entries(FORMATION_SHAPES).map(([formation, shape]) => {
    const cost = unit.map((u) => shape.map((s) => (u[0] - s[0]) ** 2 + (u[1] - s[1]) ** 2))
    const pick = assign(cost)
    return { formation, cost: pick.reduce((sum, j, i) => sum + cost[i][j], 0) / 10 }
  })
  fits.sort((a, b) => a.cost - b.cost)
  return { formation: fits[0].formation, cost: fits[0].cost, next: fits[1].formation, nextCost: fits[1].cost }
}

/** Fits worse than this are not a recognisable shape (a broken, scattered team). */
export const FORMATION_MAX_COST = 0.03

/** The formation the average positions make, or '' when no shape fits. */
export function formationOf(slots: readonly (readonly [number, number])[]): string {
  const f = fitFormation(slots)
  return f && f.cost <= FORMATION_MAX_COST ? f.formation : ''
}

/** "4-2-3-1 (Wide)" or "5-3-2 / 5-2-1-2" → the shapes it names. */
export function shapesIn(label: string): string[] {
  return [...label.matchAll(/\d(?:-\d){2,4}/g)].map((m) => m[0])
}
