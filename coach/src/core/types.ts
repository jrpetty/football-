// The engine's domain model. Everything here describes what was *visible*:
// pixel measurements, the states inferred from them, and the events and
// patterns built on top. Nothing in this file can represent hidden game state.

/** An RGBA frame (4 bytes per pixel, row-major). Volatile: never persisted. */
export interface Frame {
  readonly width: number
  readonly height: number
  readonly data: Uint8ClampedArray
  /** Milliseconds on a monotonic clock (capture time, not processing time). */
  readonly timestamp: number
}

/** A rectangle in normalized coordinates (0..1) relative to the captured image. */
export interface NormRect {
  readonly x: number
  readonly y: number
  readonly w: number
  readonly h: number
}

/** Inclusive HSV range. Hue in degrees; a range with h[0] > h[1] wraps through 0. */
export interface HsvRange {
  readonly h: readonly [number, number]
  readonly s: readonly [number, number]
  readonly v: readonly [number, number]
}

/** A single detector reading for one frame. */
export type Reading =
  | { readonly kind: 'number'; readonly value: number; readonly confidence: number }
  | { readonly kind: 'boolean'; readonly value: boolean; readonly score: number; readonly confidence: number }

/** A tracked, smoothed signal. `known: false` means the HUD element could not be read. */
export type SignalState =
  | {
      readonly id: string
      readonly known: true
      readonly kind: 'number'
      readonly value: number
      readonly confidence: number
      readonly observedAt: number
    }
  | {
      readonly id: string
      readonly known: true
      readonly kind: 'boolean'
      readonly value: boolean
      readonly confidence: number
      readonly observedAt: number
    }
  | { readonly id: string; readonly known: false; readonly lastKnownAt: number | null }

export type EventCategory = 'context' | 'action' | 'outcome' | 'boundary'
export type BoundaryKind = 'round-start' | 'round-end' | 'match-end'
export type RoundResult = 'win' | 'loss' | 'draw' | 'unknown'

/** Something that visibly happened, derived from signal transitions. */
export interface GameEvent {
  readonly uid: string
  readonly ruleId: string
  readonly label: string
  readonly category: EventCategory
  readonly at: number
  readonly confidence: number
  /** Human-readable description of the pixel evidence, e.g. "health bar 71% → 24% in 0.8s". */
  readonly evidence: string
  readonly boundary?: BoundaryKind
  readonly result?: RoundResult
  /** True when the event came from the player pressing a button in this app, not from pixels. */
  readonly manual?: boolean
}

export interface OutcomeCounts {
  n: number
  negative: number
  positive: number
}

/** The key used for "the player did none of the listed responses". */
export const NO_RESPONSE = 'none'

export type EpisodeExclusion = 'truncated-by-boundary' | 'low-visibility' | 'session-ended'

/** One observed trigger → visible response → visible outcome sequence. */
export interface Episode {
  readonly uid: string
  readonly episodeId: string
  readonly triggerUid: string
  readonly startedAt: number
  readonly endedAt: number
  readonly response: string
  readonly outcome: 'negative' | 'positive' | 'neutral'
  readonly outcomeEventUid: string | null
  readonly confidence: number
  readonly excluded: EpisodeExclusion | null
}

export type EvidenceGrade = 'insufficient' | 'weak' | 'moderate' | 'strong'

/** A statistical association between a visible response and a visible outcome. */
export interface Insight {
  readonly key: string
  readonly episodeId: string
  /** The response that went worse, and the one that went better. */
  readonly worse: { readonly response: string; readonly counts: OutcomeCounts }
  readonly better: { readonly response: string; readonly counts: OutcomeCounts }
  readonly grade: EvidenceGrade
  /** Posterior probability that `worse` really has the higher negative-outcome rate. */
  readonly probability: number
  /** True when long-term history (not just this session) contributed. */
  readonly usesHistory: boolean
}

export type CoachingMode = 'silent' | 'post-round' | 'post-match' | 'training' | 'competitive-safe'
export type CoachTone = 'gentle' | 'direct' | 'analytical' | 'high-energy'
export type Verbosity = 'minimal' | 'balanced' | 'detailed'

export type MessageKind = 'live-cue' | 'round-review' | 'match-summary'

/** A message the planner would like to deliver. The governor decides whether it may. */
export interface CandidateMessage {
  readonly id: string
  readonly kind: MessageKind
  readonly topic: string
  readonly text: string
  readonly createdAt: number
  /** For live cues: the time of the visible event it reacts to. */
  readonly basedOnEventAt: number | null
  readonly confidence: number
  readonly grade: EvidenceGrade
  readonly priority: number
}

export type DeliveryDecision = 'spoken' | 'queued' | 'text-only' | 'suppressed'

export interface DecisionRecord {
  readonly messageId: string
  readonly decision: DeliveryDecision
  readonly reasons: readonly string[]
  readonly at: number
}

/** A request to the speech engine. Dropped if not started before `expiresAt`. */
export interface Utterance {
  readonly id: string
  readonly text: string
  readonly kind: MessageKind
  readonly expiresAt: number | null
}

export type TimelineEntry =
  | { readonly type: 'event'; readonly at: number; readonly event: GameEvent }
  | { readonly type: 'episode'; readonly at: number; readonly episode: Episode; readonly summary: string }
  | { readonly type: 'insight'; readonly at: number; readonly insight: Insight; readonly statement: string }
  | {
      readonly type: 'message'
      readonly at: number
      readonly message: CandidateMessage
      readonly decision: DecisionRecord
    }
  | { readonly type: 'notice'; readonly at: number; readonly level: 'info' | 'warning'; readonly text: string }
