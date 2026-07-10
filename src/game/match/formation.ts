import { FIELD, GK } from '../config'
import type { Vec2 } from '../core/vec'
import type { Role, Team } from '../types'

export interface Slot {
  role: Role
  base: Vec2 // resting ("home") position in world metres
}

// Row templates for M outfield players: each entry is how many players sit in
// that depth band, ordered back → front. Kept compact for arena-sized sides.
const ROWS: Record<number, number[]> = {
  1: [1],
  2: [1, 1],
  3: [2, 1],
  4: [2, 1, 1],
  5: [2, 2, 1],
  6: [2, 3, 1],
  7: [3, 3, 1],
}

// Build the resting positions for one team. `size` counts everyone incl. GK.
export function formation(team: Team, size: number): Slot[] {
  const slots: Slot[] = []
  const mirror = team === 'away'
  const L = FIELD.length
  const W = FIELD.width

  // Goalkeeper.
  slots.push({
    role: 'GK',
    base: { x: mirror ? L - GK.lineDepth : GK.lineDepth, y: W / 2 },
  })

  const outfield = Math.max(1, size - 1)
  const rows = ROWS[Math.min(outfield, 7)] ?? [outfield]
  const nRows = rows.length

  // Depth fractions run from just outside the box (0.17) to just past halfway (0.6).
  for (let r = 0; r < nRows; r++) {
    const count = rows[r]
    const depthFrac = nRows === 1 ? 0.35 : 0.17 + (r / (nRows - 1)) * 0.43
    const role: Role = r === 0 ? 'DEF' : r === nRows - 1 ? 'FWD' : 'MID'
    for (let i = 0; i < count; i++) {
      const yFrac = count === 1 ? 0.5 : 0.22 + (i / (count - 1)) * 0.56
      const xFrac = mirror ? 1 - depthFrac : depthFrac
      slots.push({ role, base: { x: xFrac * L, y: yFrac * W } })
    }
  }
  return slots
}
