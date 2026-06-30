import type { FormationPreset, PositionCode } from '../types'

// Pitch coordinates: x = 0 (left touchline) .. 100 (right touchline),
// y = 0 (own goal line) .. 100 (opposition goal line).
// The keeper sits near y=6, strikers near y=90.

export const FORMATIONS: FormationPreset[] = [
  {
    name: '4-3-3',
    description: 'Balanced and modern: width from wingers, control through a midfield three.',
    slots: [
      { position: 'GK', x: 50, y: 6 },
      { position: 'LB', x: 16, y: 28 },
      { position: 'CB', x: 38, y: 22 },
      { position: 'CB', x: 62, y: 22 },
      { position: 'RB', x: 84, y: 28 },
      { position: 'CDM', x: 50, y: 42 },
      { position: 'CM', x: 32, y: 54 },
      { position: 'CM', x: 68, y: 54 },
      { position: 'LW', x: 18, y: 78 },
      { position: 'ST', x: 50, y: 86 },
      { position: 'RW', x: 82, y: 78 },
    ],
  },
  {
    name: '4-4-2',
    description: 'Classic two banks of four with a strike partnership.',
    slots: [
      { position: 'GK', x: 50, y: 6 },
      { position: 'LB', x: 16, y: 26 },
      { position: 'CB', x: 38, y: 22 },
      { position: 'CB', x: 62, y: 22 },
      { position: 'RB', x: 84, y: 26 },
      { position: 'LM', x: 18, y: 54 },
      { position: 'CM', x: 40, y: 50 },
      { position: 'CM', x: 60, y: 50 },
      { position: 'RM', x: 82, y: 54 },
      { position: 'ST', x: 40, y: 84 },
      { position: 'ST', x: 60, y: 84 },
    ],
  },
  {
    name: '4-2-3-1',
    description: 'Double pivot for security, a creative ten behind a lone striker.',
    slots: [
      { position: 'GK', x: 50, y: 6 },
      { position: 'LB', x: 16, y: 26 },
      { position: 'CB', x: 38, y: 22 },
      { position: 'CB', x: 62, y: 22 },
      { position: 'RB', x: 84, y: 26 },
      { position: 'CDM', x: 38, y: 42 },
      { position: 'CDM', x: 62, y: 42 },
      { position: 'LW', x: 18, y: 66 },
      { position: 'CAM', x: 50, y: 64 },
      { position: 'RW', x: 82, y: 66 },
      { position: 'ST', x: 50, y: 86 },
    ],
  },
  {
    name: '3-5-2',
    description: 'Three at the back with wing-backs providing all the width.',
    slots: [
      { position: 'GK', x: 50, y: 6 },
      { position: 'CB', x: 30, y: 22 },
      { position: 'CB', x: 50, y: 20 },
      { position: 'CB', x: 70, y: 22 },
      { position: 'LWB', x: 12, y: 50 },
      { position: 'CM', x: 36, y: 48 },
      { position: 'CDM', x: 50, y: 42 },
      { position: 'CM', x: 64, y: 48 },
      { position: 'RWB', x: 88, y: 50 },
      { position: 'ST', x: 40, y: 84 },
      { position: 'ST', x: 60, y: 84 },
    ],
  },
  {
    name: '4-1-4-1',
    description: 'A protected back line with a single pivot and a packed midfield.',
    slots: [
      { position: 'GK', x: 50, y: 6 },
      { position: 'LB', x: 16, y: 26 },
      { position: 'CB', x: 38, y: 22 },
      { position: 'CB', x: 62, y: 22 },
      { position: 'RB', x: 84, y: 26 },
      { position: 'CDM', x: 50, y: 40 },
      { position: 'LM', x: 18, y: 60 },
      { position: 'CM', x: 40, y: 58 },
      { position: 'CM', x: 60, y: 58 },
      { position: 'RM', x: 82, y: 60 },
      { position: 'ST', x: 50, y: 86 },
    ],
  },
  {
    name: '3-4-3',
    description: 'Aggressive front three backed by a four-man midfield and back three.',
    slots: [
      { position: 'GK', x: 50, y: 6 },
      { position: 'CB', x: 30, y: 22 },
      { position: 'CB', x: 50, y: 20 },
      { position: 'CB', x: 70, y: 22 },
      { position: 'LM', x: 14, y: 52 },
      { position: 'CM', x: 40, y: 48 },
      { position: 'CM', x: 60, y: 48 },
      { position: 'RM', x: 86, y: 52 },
      { position: 'LW', x: 22, y: 80 },
      { position: 'ST', x: 50, y: 86 },
      { position: 'RW', x: 78, y: 80 },
    ],
  },
]

export const FORMATION_NAMES = FORMATIONS.map((f) => f.name)

export function getFormation(name: string): FormationPreset | undefined {
  return FORMATIONS.find((f) => f.name === name)
}

/** Map a detailed position code to its broad group. */
export function positionGroupOf(code: PositionCode): 'GK' | 'DEF' | 'MID' | 'FWD' {
  if (code === 'GK') return 'GK'
  if (['RB', 'RWB', 'CB', 'LB', 'LWB'].includes(code)) return 'DEF'
  if (['CDM', 'CM', 'CAM', 'RM', 'LM'].includes(code)) return 'MID'
  return 'FWD'
}
