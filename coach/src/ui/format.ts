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
