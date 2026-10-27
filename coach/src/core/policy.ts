// The policy and coaching-mode governor. Every candidate message passes
// through here, and every outcome — spoken, queued, text-only or suppressed —
// is recorded with its reasons so the review screen can explain why the coach
// did or did not speak. Silence is a first-class, frequent result.

import { gradeAtLeast } from './evidence.ts'
import { countWords, WORD_LIMITS } from './language.ts'
import { modeAllowsLiveCoaching, type ProfileMode } from './profile/schema.ts'
import { MODE_INFO, type CoachSettings } from './settings.ts'
import type {
  CandidateMessage,
  CoachingMode,
  DecisionRecord,
  DeliveryDecision,
  EvidenceGrade,
  Utterance,
  Verbosity,
} from './types.ts'

export const VERBOSITY_RULES: Record<
  Verbosity,
  { minConfidence: number; maxPerMinute: number; reviewLines: number; minReviewGrade: EvidenceGrade; minLiveGrade: EvidenceGrade }
> = {
  minimal: { minConfidence: 0.8, maxPerMinute: 2, reviewLines: 1, minReviewGrade: 'moderate', minLiveGrade: 'strong' },
  balanced: { minConfidence: 0.7, maxPerMinute: 3, reviewLines: 1, minReviewGrade: 'moderate', minLiveGrade: 'moderate' },
  detailed: { minConfidence: 0.6, maxPerMinute: 4, reviewLines: 2, minReviewGrade: 'weak', minLiveGrade: 'moderate' },
}

/** A reflective message nobody heard within this long is no longer worth saying. */
const REFLECTIVE_TTL_MS = 20_000
/** The same reflective topic is not spoken again within this window. */
const REFLECTIVE_REPEAT_MS = 5 * 60_000

export interface EffectiveMode {
  readonly mode: CoachingMode
  /** Set when the player's chosen mode was downgraded, explaining why. */
  readonly downgradeReason: string | null
}

/** Training mode only stays live where the profile documents that live coaching is allowed. */
export function effectiveMode(chosen: CoachingMode, profileMode: ProfileMode | null): EffectiveMode {
  if (chosen !== 'training' || modeAllowsLiveCoaching(profileMode)) return { mode: chosen, downgradeReason: null }
  let why: string
  if (!profileMode) why = 'no game mode is selected, so it is treated as competitive'
  else if (profileMode.liveCoaching.status === 'prohibited') why = `the profile marks "${profileMode.label}" as prohibiting live coaching`
  else why = `"${profileMode.label}" is ${profileMode.competitive ? 'competitive' : 'multiplayer'} and has no documented publisher approval for live coaching`
  return { mode: 'competitive-safe', downgradeReason: `Live tips are off: ${why}. Running competitive-safe instead.` }
}

export interface Decision {
  readonly record: DecisionRecord
  readonly utterance: Utterance | null
}

export class CoachingGovernor {
  private settings: CoachSettings
  private profileMode: ProfileMode | null
  private spokenAt: number[] = []
  private lastSpokenAt = -Infinity
  private readonly topicSpokenAt = new Map<string, number>()
  /** Topics held back from live delivery, for the next reflective message. */
  private readonly queued = new Set<string>()
  /** Spoken reflective utterances that should be cancelled if a new round starts first. */
  private pendingReflective: { id: string; expiresAt: number }[] = []

  constructor(settings: CoachSettings, profileMode: ProfileMode | null) {
    this.settings = settings
    this.profileMode = profileMode
  }

  configure(settings: CoachSettings, profileMode: ProfileMode | null): void {
    this.settings = settings
    this.profileMode = profileMode
  }

  get mode(): EffectiveMode {
    return effectiveMode(this.settings.mode, this.profileMode)
  }

  get rules() {
    return VERBOSITY_RULES[this.settings.verbosity]
  }

  /** Topics deferred from live delivery since the last reflective message. */
  takeQueuedTopics(): string[] {
    const topics = [...this.queued]
    this.queued.clear()
    return topics
  }

  /**
   * A new round visibly started. Outside training mode, reflective speech
   * that has not finished yet is cancelled: talking into a live round is the
   * thing competitive-safe exists to prevent.
   */
  onRoundStart(now: number): string[] {
    if (this.mode.mode === 'training') return []
    const cancel = this.pendingReflective.filter((p) => p.expiresAt > now).map((p) => p.id)
    this.pendingReflective = []
    return cancel
  }

  decide(msg: CandidateMessage, now: number, opts: { maxAgeMs?: number } = {}): Decision {
    const reasons: string[] = []
    const result = (decision: DeliveryDecision, utterance: Utterance | null = null): Decision => ({
      record: { messageId: msg.id, decision, reasons, at: now },
      utterance,
    })
    const { mode, downgradeReason } = this.mode
    const rules = this.rules

    if (countWords(msg.text) > WORD_LIMITS[msg.kind]) {
      reasons.push(`over the ${WORD_LIMITS[msg.kind]}-word limit for this kind of message`)
      return result('suppressed')
    }

    if (msg.kind === 'live-cue') {
      if (mode !== 'training') {
        reasons.push(downgradeReason ?? `live tips are not given in ${MODE_INFO[mode].label} mode`)
        if (mode === 'silent') return result('suppressed')
        this.queued.add(msg.topic)
        reasons.push('noted for the next reflective review instead')
        return result('queued')
      }
      if (!gradeAtLeast(msg.grade, rules.minLiveGrade)) {
        reasons.push(`evidence is ${msg.grade}; live tips need ${rules.minLiveGrade} or better`)
        return result('suppressed')
      }
      if (msg.confidence < rules.minConfidence) {
        reasons.push(`visual confidence ${pct(msg.confidence)} is below ${pct(rules.minConfidence)}`)
        return result('suppressed')
      }
      const age = msg.basedOnEventAt === null ? 0 : now - msg.basedOnEventAt
      if (opts.maxAgeMs !== undefined && age > opts.maxAgeMs) {
        reasons.push(`the moment passed (${(age / 1000).toFixed(1)}s old)`)
        return result('suppressed')
      }
      const gate = this.speechGate(now, msg.topic, Math.max(90_000, this.settings.cooldownSec * 4000))
      if (gate) {
        reasons.push(gate)
        return result(this.settings.muted ? 'text-only' : 'suppressed')
      }
      reasons.push(`training mode, ${msg.grade} evidence, seen ${(age / 1000).toFixed(1)}s ago`)
      const expiresAt = (msg.basedOnEventAt ?? now) + (opts.maxAgeMs ?? 2500)
      return result('spoken', this.speak(msg, now, expiresAt))
    }

    // Reflective messages: round reviews and match summaries.
    if (mode === 'silent') {
      reasons.push('silent tracker mode never speaks')
      return result('text-only')
    }
    if (msg.kind === 'round-review' && mode === 'post-match') {
      reasons.push('post-match mode saves round notes for the match summary')
      return result('text-only')
    }
    if (this.settings.muted) {
      reasons.push('muted')
      return result('text-only')
    }
    if (msg.kind === 'round-review') {
      const last = this.topicSpokenAt.get(msg.topic)
      if (last !== undefined && now - last < REFLECTIVE_REPEAT_MS) {
        reasons.push('the same note was spoken after a recent round')
        return result('text-only')
      }
      const recent = this.spokenAt.filter((t) => now - t < 60_000).length
      if (recent >= rules.maxPerMinute) {
        reasons.push(`already spoke ${recent} times in the last minute`)
        return result('text-only')
      }
    }
    reasons.push(msg.kind === 'round-review' ? 'the round visibly ended' : 'the match ended')
    const expiresAt = now + REFLECTIVE_TTL_MS
    const utterance = this.speak(msg, now, expiresAt)
    if (msg.kind === 'round-review') this.pendingReflective.push({ id: msg.id, expiresAt })
    return result('spoken', utterance)
  }

  /** Returns a reason not to speak a live message right now, or null. */
  private speechGate(now: number, topic: string, repeatMs: number): string | null {
    if (this.settings.muted) return 'muted'
    const since = now - this.lastSpokenAt
    if (since < this.settings.cooldownSec * 1000) {
      return `cooldown: last spoke ${(since / 1000).toFixed(0)}s ago (minimum ${this.settings.cooldownSec}s)`
    }
    const last = this.topicSpokenAt.get(topic)
    if (last !== undefined && now - last < repeatMs) return 'said the same thing recently'
    const recent = this.spokenAt.filter((t) => now - t < 60_000).length
    if (recent >= this.rules.maxPerMinute) return `already spoke ${recent} times in the last minute`
    return null
  }

  private speak(msg: CandidateMessage, now: number, expiresAt: number): Utterance {
    this.lastSpokenAt = now
    this.spokenAt = [...this.spokenAt.filter((t) => now - t < 60_000), now]
    this.topicSpokenAt.set(msg.topic, now)
    return { id: msg.id, text: msg.text, kind: msg.kind, expiresAt }
  }
}

function pct(v: number): string {
  return `${Math.round(v * 100)}%`
}
