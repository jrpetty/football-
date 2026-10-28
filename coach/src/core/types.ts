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

export type EventCategory = 'context' | 'action' | 'outcome' | 'boundary' | 'mistake'
export type BoundaryKind = 'round-start' | 'round-end' | 'match-end'
export type RoundResult = 'win' | 'loss' | 'draw' | 'unknown'

// --- Observations and visible state ---------------------------------------------

/** Where an observation came from. A model's description of a frame is still only an observation. */
export type ObservationSource = 'pixel' | 'vision-model' | 'user' | 'derived'

/**
 * One structured detection: a state reading ("population = 25") or an event
 * ("goal conceded"). Never an unquestioned game fact: it carries confidence,
 * freshness and the evidence it rests on.
 */
export interface Observation {
  readonly id: string
  /** Capture time of the frame it came from (monotonic ms), not arrival time. */
  readonly timestampMs: number
  readonly gameProfileId: string
  /** The HUD region, crop, or "full-frame" it was read from. */
  readonly sourceRegion: string
  readonly kind: 'state' | 'event'
  /** State key (e.g. "population") or event type (e.g. "goal_conceded"). */
  readonly eventType: string
  readonly value?: string | number | boolean
  /** A pack-defined zone id, for spatial analyses. Approximate by nature. */
  readonly zone?: string
  readonly attributes?: Readonly<Record<string, string | number | boolean>>
  /** 0–1. */
  readonly confidence: number
  /** How old the observation was when it reached the engine (recognition latency). */
  readonly freshnessMs: number
  /** Recogniser names and visible cues, e.g. ["vision-model", "GOAL banner visible"]. */
  readonly evidence: readonly string[]
  readonly source: ObservationSource
}

export type GamePhase = 'menu' | 'round' | 'combat' | 'round_end' | 'match_end' | 'unknown'

/** A fused state value: the latest confident reading of one key, with its age. */
export interface FusedValue {
  readonly value: string | number | boolean
  readonly confidence: number
  readonly observedAt: number
  readonly source: ObservationSource
  /** Set when recent readings from different sources disagreed. */
  readonly conflicted: boolean
}

/** The short-lived, fused picture of what is visibly happening right now. */
export interface VisibleState {
  readonly timestampMs: number
  readonly phase: GamePhase
  readonly visibleHealth?: number
  readonly visibleObjective?: string
  readonly visibleOutcome?: 'success' | 'failure' | 'unknown'
  /** Fresh, non-expired state readings by key. Expired keys are absent, not zero. */
  readonly values: Readonly<Record<string, FusedValue>>
  readonly activeSignals: readonly Observation[]
  readonly confidence: number
  readonly stale: boolean
  readonly staleReasons: readonly string[]
}

/** Something that visibly happened, derived from signal transitions or event observations. */
export interface GameEvent {
  readonly uid: string
  readonly ruleId: string
  readonly label: string
  readonly category: EventCategory
  readonly at: number
  readonly confidence: number
  /** Human-readable description of the evidence, e.g. "health bar 71% → 24% in 0.8s". */
  readonly evidence: string
  readonly boundary?: BoundaryKind
  readonly result?: RoundResult
  readonly zone?: string
  readonly attributes?: Readonly<Record<string, string | number | boolean>>
  readonly source?: ObservationSource
  /** True when the event came from the player pressing a button in this app, not from the screen. */
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

/** A pattern as stored and shown to the player (long-term memory is opt-in). */
export interface PatternRecord {
  readonly id: string
  readonly gameProfileId: string
  readonly mode: string
  readonly contextSignature: string
  readonly visibleSequence: readonly string[]
  readonly observedOutcome: 'success' | 'failure' | 'mixed' | 'unknown'
  readonly supportingInstances: number
  readonly counterInstances: number
  readonly confidence: number
  readonly grade: EvidenceGrade
  readonly lastObservedAt: string
  readonly explanation: string
  readonly status: 'active' | 'dismissed' | 'disputed'
}

// --- Policy and coaching messages --------------------------------------------------

export type PolicyStatus =
  | 'approved_for_scope'
  | 'post_match_only'
  | 'noncompetitive_only'
  | 'needs_publisher_review'
  | 'disabled'

/** When a message would reach the player. */
export type DeliveryWindow = 'during_play' | 'round_break' | 'post_match' | 'idle'

export type MessageCategory = 'descriptive' | 'reflective' | 'post_event' | 'training' | 'live_tactical' | 'commentary'

export type CoachingMode = 'silent' | 'post-round' | 'post-match' | 'training' | 'competitive-safe'
export type CoachTone = 'gentle' | 'direct' | 'analytical' | 'high-energy'
export type Verbosity = 'minimal' | 'balanced' | 'detailed'

/** A message the planner would like to deliver. The governor decides whether it may. */
export interface CoachingMessage {
  readonly id: string
  readonly category: MessageCategory
  readonly text: string
  /** 0–1: visual confidence of the evidence behind it. */
  readonly confidence: number
  readonly grade: EvidenceGrade
  /** One line: what the message rests on, e.g. "5 of 8 low-health moments without healing ended in elimination". */
  readonly evidenceSummary: string
  /** Deduplication key. */
  readonly topic: string
  readonly priority: 'low' | 'normal' | 'high'
  readonly createdAt: number
  /** The visible moment it reacts to (live tips), or null. */
  readonly basedOnObservationAt: number | null
  /** Do not deliver after this time (source clock). */
  readonly expiresAtMs: number
  /** True for answers to the player's own questions. */
  readonly requestedByPlayer?: boolean
  /** "template" text is built from counts; "ai" text was phrased by a language model from the evidence. */
  readonly author: 'template' | 'ai'
}

export type DeliveryDecision = 'spoken' | 'queued' | 'text-only' | 'suppressed'

export interface DecisionRecord {
  readonly messageId: string
  readonly decision: DeliveryDecision
  /** Why it was (or was not) spoken; the first reason is the deciding one. */
  readonly reasons: readonly string[]
  readonly at: number
  readonly window: DeliveryWindow
  readonly policyStatus: PolicyStatus
}

/** A request to the speech engine. Dropped if not started before `expiresAt`. */
export interface Utterance {
  readonly id: string
  readonly text: string
  readonly category: MessageCategory
  readonly priority: CoachingMessage['priority']
  readonly expiresAt: number | null
}

export type TimelineEntry =
  | { readonly type: 'event'; readonly at: number; readonly event: GameEvent }
  | { readonly type: 'episode'; readonly at: number; readonly episode: Episode; readonly summary: string }
  | { readonly type: 'insight'; readonly at: number; readonly insight: Insight; readonly statement: string }
  | {
      readonly type: 'message'
      readonly at: number
      readonly message: CoachingMessage
      readonly decision: DecisionRecord
    }
  | { readonly type: 'notice'; readonly at: number; readonly level: 'info' | 'warning'; readonly text: string }
