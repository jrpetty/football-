// Display helpers for the UI.

import type { DeliveryDecision, EvidenceGrade, TimelineEntry } from '../core/types.ts'

/** Session-relative clock, m:ss. */
export function clock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

export function pct(v: number): string {
  return `${Math.round(v * 100)}%`
}

export const GRADE_TEXT: Record<EvidenceGrade, string> = {
  insufficient: 'Still learning',
  weak: 'Early sign',
  moderate: 'Moderate evidence',
  strong: 'Strong evidence',
}

export const DECISION_TEXT: Record<DeliveryDecision, string> = {
  spoken: 'Spoken',
  queued: 'Held for review',
  'text-only': 'Shown, not spoken',
  suppressed: 'Not said',
}

/** How the review timeline groups entries: what was seen vs inferred vs said. */
export type TimelineKind = 'observed' | 'inferred' | 'spoken' | 'held' | 'note'

export function timelineKind(e: TimelineEntry): TimelineKind {
  switch (e.type) {
    case 'event':
      return 'observed'
    case 'episode':
    case 'insight':
      return 'inferred'
    case 'message':
      return e.decision.decision === 'spoken' ? 'spoken' : 'held'
    case 'notice':
      return 'note'
  }
}

export function usd(v: number): string {
  return v < 0.01 && v > 0 ? '<$0.01' : `$${v.toFixed(2)}`
}

/** "3 s ago", "2 min ago". */
export function ago(ms: number): string {
  if (ms < 1500) return 'just now'
  if (ms < 60_000) return `${Math.round(ms / 1000)} s ago`
  return `${Math.round(ms / 60_000)} min ago`
}

/** "passAccuracyPct" → "Pass accuracy pct". */
export function labelOf(key: string): string {
  const s = key.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[-_]/g, ' ').toLowerCase()
  return s.charAt(0).toUpperCase() + s.slice(1)
}

/** Game seconds as m:ss. */
export function gameClock(sec: number): string {
  const s = Math.max(0, Math.round(sec))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

export function dateShort(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

export const WINDOW_TEXT = {
  during_play: 'During play',
  round_break: 'Round break',
  post_match: 'Match over',
  idle: 'Waiting',
} as const

export const CATEGORY_TEXT = {
  descriptive: 'Observation',
  reflective: 'Reflection',
  post_event: 'Review',
  training: 'Training tip',
  live_tactical: 'Live tip',
  commentary: 'Commentary',
} as const

/** "2.5 s", "45 s", "1 min 12 s". */
export function formatMs(ms: number): string {
  if (ms < 10_000) return `${(ms / 1000).toFixed(ms % 1000 ? 1 : 0)} s`
  const s = Math.round(ms / 1000)
  return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min${s % 60 ? ` ${s % 60} s` : ''}`
}
