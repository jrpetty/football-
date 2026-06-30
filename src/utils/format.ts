import type { MatchResult, PositionGroup, PositionCode, Player } from '../types'

export function fmtDate(iso: string): string {
  const d = new Date(iso + 'T00:00:00')
  return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })
}

export function fmtDateShort(iso: string): string {
  const d = new Date(iso + 'T00:00:00')
  return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short' })
}

export function pct(value: number, digits = 0): string {
  return `${value.toFixed(digits)}%`
}

export function num(value: number, digits = 0): string {
  return value.toLocaleString('en-GB', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

/** Per-90 normalisation helper. */
export function per90(total: number, minutes: number): number {
  if (minutes <= 0) return 0
  return (total / minutes) * 90
}

export function initials(name: string): string {
  return name
    .split(' ')
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()
}

export const POSITION_GROUP_LABEL: Record<PositionGroup, string> = {
  GK: 'Goalkeeper',
  DEF: 'Defender',
  MID: 'Midfielder',
  FWD: 'Forward',
}

export const POSITION_GROUP_COLOR: Record<PositionGroup, string> = {
  GK: '#eab308',
  DEF: '#3b82f6',
  MID: '#22c55e',
  FWD: '#ef4444',
}

export const POSITION_LABEL: Record<PositionCode, string> = {
  GK: 'Goalkeeper',
  RB: 'Right Back', RWB: 'Right Wing-Back', CB: 'Centre Back', LB: 'Left Back', LWB: 'Left Wing-Back',
  CDM: 'Defensive Mid', CM: 'Central Mid', CAM: 'Attacking Mid', RM: 'Right Mid', LM: 'Left Mid',
  RW: 'Right Wing', LW: 'Left Wing', CF: 'Centre Forward', ST: 'Striker',
}

export const RESULT_COLOR: Record<MatchResult, string> = {
  W: '#22c55e',
  D: '#94a3b8',
  L: '#ef4444',
}

export function resultOf(goalsFor: number, goalsAgainst: number): MatchResult {
  if (goalsFor > goalsAgainst) return 'W'
  if (goalsFor < goalsAgainst) return 'L'
  return 'D'
}

/** Colour for a rating out of 10 (red→amber→green). */
export function ratingColor(rating: number): string {
  if (rating >= 7.5) return '#22c55e'
  if (rating >= 6.8) return '#84cc16'
  if (rating >= 6.0) return '#eab308'
  if (rating >= 5.0) return '#f97316'
  return '#ef4444'
}

export function statusColor(status: Player['status']): string {
  switch (status) {
    case 'Available': return '#22c55e'
    case 'Doubtful': return '#eab308'
    case 'Suspended': return '#f97316'
    case 'Injured': return '#ef4444'
  }
}

export function genId(prefix: string): string {
  return `${prefix}_${Math.random().toString(36).slice(2, 9)}${Date.now().toString(36).slice(-4)}`
}

export function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v))
}
