// The policy and coaching-mode governor. Every candidate message passes
// through here and leaves with exactly one decision (spoken, queued,
// text-only, suppressed) and the reasons for it. The publisher policy (via the
// registry) sets the outer limit; the player's mode can only narrow it.
// Nothing a vision model says can widen what is allowed.

import { gradeAtLeast } from '../evidence.ts'
import { countWords, WORD_LIMITS } from '../language.ts'
import { MODE_INFO, type CoachSettings } from '../settings.ts'
import type {
  CoachingMessage,
  CoachingMode,
  DecisionRecord,
  DeliveryDecision,
  DeliveryWindow,
  EvidenceGrade,
  Utterance,
  Verbosity,
} from '../types.ts'
import type { PolicyScope, ResolvedPolicy } from './registry.ts'

export const VERBOSITY_RULES: Record<
  Verbosity,
  { minConfidence: number; maxPerMinute: number; reviewLines: number; minReviewGrade: EvidenceGrade; minLiveGrade: EvidenceGrade }
> = {
  minimal: { minConfidence: 0.8, maxPerMinute: 2, reviewLines: 1, minReviewGrade: 'moderate', minLiveGrade: 'strong' },
  balanced: { minConfidence: 0.7, maxPerMinute: 3, reviewLines: 1, minReviewGrade: 'moderate', minLiveGrade: 'moderate' },
  detailed: { minConfidence: 0.6, maxPerMinute: 4, reviewLines: 2, minReviewGrade: 'weak', minLiveGrade: 'moderate' },
}

/** The same reflective topic is not spoken again within this window. */
const REFLECTIVE_REPEAT_MS = 5 * 60_000
const LIVE_CATEGORIES = new Set(['training', 'live_tactical'])
/** Commentary pacing: a line at most every few seconds, and not the same line twice in quick succession. */
export const COMMENTARY_GAP_MS = 6_000
/** Build-order calls: at most one line this often. */
export const BUILD_GAP_MS = 4_000
const COMMENTARY_REPEAT_MS = 30_000
const COMMENTARY_PER_MINUTE = 8

export function windowScope(w: DeliveryWindow): PolicyScope {
  return w === 'during_play' ? 'live' : w === 'round_break' ? 'round_break' : 'post_match'
}

export interface EffectiveMode {
  readonly mode: CoachingMode
  /** Whether live tips can be spoken at all in this game, mode and policy. */
  readonly liveActive: boolean
  /** Set when the player's choice is narrowed by policy, explaining why. */
  readonly downgradeReason: string | null
}

export function effectiveMode(chosen: CoachingMode, policy: ResolvedPolicy): EffectiveMode {
  const liveScope = policy.scope.has('live')
  if (chosen !== 'training') return { mode: chosen, liveActive: false, downgradeReason: null }
  if (liveScope) return { mode: 'training', liveActive: true, downgradeReason: null }
  return {
    mode: policy.scope.has('round_break') ? 'competitive-safe' : 'post-match',
    liveActive: false,
    downgradeReason: `Live tips are off: ${policy.reasons[0]}`,
  }
}

export interface Decision {
  readonly record: DecisionRecord
  readonly utterance: Utterance | null
}

export class CoachingGovernor {
  private settings: CoachSettings
  private policy: ResolvedPolicy
  private spokenAt: number[] = []
  private lastSpokenAt = -Infinity
  private readonly topicSpokenAt = new Map<string, number>()
  private readonly queued = new Set<string>()
  private pendingReflective: { id: string; expiresAt: number }[] = []
  private lastCommentaryAt = -Infinity
  private lastBuildAt = -Infinity
  private commentaryAt: number[] = []

  constructor(settings: CoachSettings, policy: ResolvedPolicy) {
    this.settings = settings
    this.policy = policy
  }

  configure(settings: CoachSettings, policy: ResolvedPolicy): void {
    this.settings = settings
    this.policy = policy
  }

  get mode(): EffectiveMode {
    return effectiveMode(this.settings.mode, this.policy)
  }

  get currentPolicy(): ResolvedPolicy {
    return this.policy
  }

  get rules() {
    return VERBOSITY_RULES[this.settings.verbosity]
  }

  /** Topics held back from live delivery since the last reflective message. */
  takeQueuedTopics(): string[] {
    const topics = [...this.queued]
    this.queued.clear()
    return topics
  }

  /** A new round visibly started: reflective speech that has not finished is cut off (outside live-allowed play). */
  onRoundStart(now: number): string[] {
    if (this.mode.liveActive) return []
    const cancel = this.pendingReflective.filter((p) => p.expiresAt > now).map((p) => p.id)
    this.pendingReflective = []
    return cancel
  }

  decide(msg: CoachingMessage, now: number, window: DeliveryWindow): Decision {
    const reasons: string[] = []
    const result = (decision: DeliveryDecision, utterance: Utterance | null = null): Decision => ({
      record: { messageId: msg.id, decision, reasons, at: now, window, policyStatus: this.policy.status },
      utterance,
    })
    const live = LIVE_CATEGORIES.has(msg.category)
    const rules = this.rules
    const mode = this.settings.mode

    // 1. Shape.
    const limit = msg.requestedByPlayer ? WORD_LIMITS.answer : WORD_LIMITS[msg.category]
    if (countWords(msg.text) > limit) {
      reasons.push(`over the ${limit}-word limit for this kind of message`)
      return result('suppressed')
    }
    if (now > msg.expiresAtMs) {
      reasons.push(`the moment passed (${((now - msg.expiresAtMs) / 1000).toFixed(1)}s after its expiry)`)
      return result('suppressed')
    }

    // 2. Publisher policy for this window. Fails closed.
    const scope = windowScope(window)
    if (this.policy.status === 'disabled') {
      reasons.push(this.policy.reasons[0])
      return result('suppressed')
    }
    if (msg.category === 'commentary') return this.decideCommentary(msg, now, window, reasons, result)
    if (msg.category === 'build') return this.decideBuild(msg, now, window, reasons, result)
    if (!this.policy.scope.has(scope)) {
      reasons.push(`${this.policy.reasons[0]} (${scopeText(scope)} is not allowed)`)
      if (live && !msg.requestedByPlayer) {
        // A training tip is not worth repeating later, but its topic is worth reviewing.
        this.queued.add(msg.topic)
        reasons.push('noted for the post-match review instead')
        return result('queued')
      }
      reasons.push('held until the match ends')
      return result('queued')
    }

    // 3. Category must fit the window.
    if (live && window !== 'during_play') {
      reasons.push('live tips only make sense during play')
      return result('suppressed')
    }
    if (!live && window === 'during_play' && !msg.requestedByPlayer && msg.category !== 'descriptive') {
      reasons.push('reflective notes wait for a break in play')
      return result('queued')
    }

    // 4. The player's mode can only narrow policy.
    if (mode === 'silent') {
      reasons.push('silent tracker mode never speaks')
      return result('text-only')
    }
    if (window === 'during_play' && mode !== 'training') {
      reasons.push(`${MODE_INFO[mode].label} never speaks during play`)
      if (live) this.queued.add(msg.topic)
      return result(live ? 'queued' : 'text-only')
    }
    if (window === 'round_break' && mode === 'post-match' && !msg.requestedByPlayer) {
      reasons.push('post-match mode saves round notes for the match summary')
      return result('text-only')
    }

    // 5. Evidence quality (not applied to answers the player asked for).
    if (live && !msg.requestedByPlayer) {
      if (!gradeAtLeast(msg.grade, rules.minLiveGrade)) {
        reasons.push(`evidence is ${msg.grade}; live tips need ${rules.minLiveGrade} or better`)
        return result('suppressed')
      }
      if (msg.confidence < rules.minConfidence) {
        reasons.push(`visual confidence ${pct(msg.confidence)} is below ${pct(rules.minConfidence)}`)
        return result('suppressed')
      }
    }

    // 6. Delivery pacing.
    if (this.settings.muted) {
      reasons.push('muted')
      return result('text-only')
    }
    if (!msg.requestedByPlayer) {
      const gate = live ? this.liveGate(now, msg.topic) : this.reflectiveGate(now, msg)
      if (gate) {
        reasons.push(gate)
        return result(live ? 'suppressed' : 'text-only')
      }
    }

    reasons.push(deliveryReason(msg, window))
    const utterance = this.speak(msg, now)
    if (!live && window === 'round_break') this.pendingReflective.push({ id: msg.id, expiresAt: msg.expiresAtMs })
    return result('spoken', utterance)
  }

  /**
   * Live commentary: the player turned it on, so where the game and mode
   * allow speech during play it speaks, on its own pace (it never delays a
   * coaching tip, and it is dropped rather than read late). Elsewhere it is
   * not delivered at all: commentary after the fact is not commentary.
   */
  private decideCommentary(msg: CoachingMessage, now: number, window: DeliveryWindow, reasons: string[], result: (d: DeliveryDecision, u?: Utterance | null) => Decision): Decision {
    if (!this.settings.commentary) {
      reasons.push('live commentary is off')
      return result('suppressed')
    }
    if (window !== 'during_play') {
      reasons.push('commentary is only for live play')
      return result('suppressed')
    }
    if (!this.policy.scope.has('live')) {
      reasons.push(`${this.policy.reasons[0]} (speaking during play is not allowed)`)
      return result('suppressed')
    }
    // Paced the same whether it is spoken or only shown (muted), so a muted coach does not repeat itself on screen.
    const since = now - this.lastCommentaryAt
    if (since < COMMENTARY_GAP_MS) {
      reasons.push(`commentary paced: last line ${(since / 1000).toFixed(0)}s ago`)
      return result('suppressed')
    }
    const last = this.topicSpokenAt.get(msg.topic)
    if (last !== undefined && now - last < COMMENTARY_REPEAT_MS) {
      reasons.push('said the same thing recently')
      return result('suppressed')
    }
    if (this.commentaryAt.filter((t) => now - t < 60_000).length >= COMMENTARY_PER_MINUTE) {
      reasons.push(`already ${COMMENTARY_PER_MINUTE} commentary lines in the last minute`)
      return result('suppressed')
    }
    this.lastCommentaryAt = now
    this.commentaryAt = [...this.commentaryAt.filter((t) => now - t < 60_000), now]
    this.topicSpokenAt.set(msg.topic, now)
    if (this.settings.mode === 'silent') {
      reasons.push('silent tracker mode never speaks')
      return result('text-only')
    }
    if (this.settings.muted) {
      reasons.push('muted')
      return result('text-only')
    }
    reasons.push('live commentary is on and allowed here')
    return result('spoken', { id: msg.id, text: msg.text, category: msg.category, priority: msg.priority, expiresAt: msg.expiresAtMs })
  }

  /**
   * Build-order calls (and the practice focus): the player chose to train with them, so where the game
   * and mode allow speech during play they are spoken, paced; elsewhere they are not delivered at all.
   */
  private decideBuild(msg: CoachingMessage, now: number, window: DeliveryWindow, reasons: string[], result: (d: DeliveryDecision, u?: Utterance | null) => Decision): Decision {
    if (window !== 'during_play') {
      reasons.push('build calls are only for live play')
      return result('suppressed')
    }
    if (!this.policy.scope.has('live')) {
      reasons.push(`${this.policy.reasons[0]} (speaking during play is not allowed)`)
      return result('suppressed')
    }
    const since = now - this.lastBuildAt
    if (since < BUILD_GAP_MS) {
      reasons.push(`build calls paced: last ${(since / 1000).toFixed(0)}s ago`)
      return result('suppressed')
    }
    this.lastBuildAt = now
    if (this.settings.mode === 'silent') {
      reasons.push('silent tracker mode never speaks')
      return result('text-only')
    }
    if (this.settings.muted) {
      reasons.push('muted')
      return result('text-only')
    }
    reasons.push('the build trainer is on and live coaching is allowed here')
    return result('spoken', { id: msg.id, text: msg.text, category: msg.category, priority: msg.priority, expiresAt: msg.expiresAtMs })
  }

  private liveGate(now: number, topic: string): string | null {
    const since = now - this.lastSpokenAt
    if (since < this.settings.cooldownSec * 1000) {
      return `cooldown: last spoke ${(since / 1000).toFixed(0)}s ago (minimum ${this.settings.cooldownSec}s)`
    }
    const last = this.topicSpokenAt.get(topic)
    if (last !== undefined && now - last < Math.max(90_000, this.settings.cooldownSec * 4000)) return 'said the same thing recently'
    return this.rateGate(now)
  }

  private reflectiveGate(now: number, msg: CoachingMessage): string | null {
    if (msg.category === 'post_event') return null
    const last = this.topicSpokenAt.get(msg.topic)
    if (last !== undefined && now - last < REFLECTIVE_REPEAT_MS) return 'the same note was spoken recently'
    return this.rateGate(now)
  }

  private rateGate(now: number): string | null {
    const recent = this.spokenAt.filter((t) => now - t < 60_000).length
    return recent >= this.rules.maxPerMinute ? `already spoke ${recent} times in the last minute` : null
  }

  private speak(msg: CoachingMessage, now: number): Utterance {
    this.lastSpokenAt = now
    this.spokenAt = [...this.spokenAt.filter((t) => now - t < 60_000), now]
    this.topicSpokenAt.set(msg.topic, now)
    return { id: msg.id, text: msg.text, category: msg.category, priority: msg.priority, expiresAt: msg.expiresAtMs }
  }
}

function deliveryReason(msg: CoachingMessage, window: DeliveryWindow): string {
  if (msg.requestedByPlayer) return 'you asked'
  switch (window) {
    case 'during_play':
      return `training tip allowed here; ${msg.grade} evidence`
    case 'round_break':
      return 'the round visibly ended'
    case 'post_match':
      return 'the match ended'
    case 'idle':
      return 'capture has stopped'
  }
}

function scopeText(s: PolicyScope): string {
  return s === 'live' ? 'speaking during play' : s === 'round_break' ? 'speaking between rounds' : 'post-match review'
}

function pct(v: number): string {
  return `${Math.round(v * 100)}%`
}
