import { FIELD } from '../config'
import type { Vec2 } from '../core/vec'
import type { Team } from '../types'

// Geometry helpers for the pitch. The coordinate system:
//   x ∈ [0, FIELD.length]  — home defends x=0, away defends x=FIELD.length
//   y ∈ [0, FIELD.width]   — touchlines at y=0 and y=FIELD.width
// The home team attacks toward increasing x.

export const centerSpot = (): Vec2 => ({ x: FIELD.length / 2, y: FIELD.width / 2 })

// The x-coordinate of the goal line a team is defending.
export const ownGoalLineX = (team: Team): number => (team === 'home' ? 0 : FIELD.length)

// The x-coordinate of the goal a team is attacking.
export const targetGoalLineX = (team: Team): number => (team === 'home' ? FIELD.length : 0)

// Centre of the goal a team is attacking.
export const targetGoalCenter = (team: Team): Vec2 => ({
  x: targetGoalLineX(team),
  y: FIELD.width / 2,
})

export const ownGoalCenter = (team: Team): Vec2 => ({
  x: ownGoalLineX(team),
  y: FIELD.width / 2,
})

export const goalPostYs = (): [number, number] => [
  (FIELD.width - FIELD.goalWidth) / 2,
  (FIELD.width + FIELD.goalWidth) / 2,
]

// Attacking direction sign for a team (+1 home, -1 away).
export const attackDir = (team: Team): number => (team === 'home' ? 1 : -1)

export const inGoalMouthY = (y: number): boolean => {
  const [a, b] = goalPostYs()
  return y >= a && y <= b
}

export const isInsidePitch = (p: Vec2): boolean =>
  p.x >= 0 && p.x <= FIELD.length && p.y >= 0 && p.y <= FIELD.width

// Is this point inside the penalty area a team defends?
export const inOwnBox = (team: Team, p: Vec2): boolean => {
  const yMin = (FIELD.width - FIELD.boxWidth) / 2
  const yMax = (FIELD.width + FIELD.boxWidth) / 2
  if (p.y < yMin || p.y > yMax) return false
  return team === 'home' ? p.x <= FIELD.boxDepth : p.x >= FIELD.length - FIELD.boxDepth
}
