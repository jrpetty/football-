// Event rules: declarative, per-profile descriptions of which visible signal
// transitions count as events. Rules only ever fire on *transitions they saw*:
// a value that is already low when capture starts is not "health dropped low",
// and nothing fires while a signal is unknown.

import type { EventRuleSpec, SignalSpec } from './profile/schema.ts'
import type { GameEvent, SignalState } from './types.ts'

const DEFAULT_COOLDOWN_MS = 500
/** A transition observed across a gap longer than this is reported with reduced confidence. */
const GAP_PENALTY_AFTER_MS = 2000

type KnownNumber = Extract<SignalState, { known: true; kind: 'number' }>
type KnownBool = Extract<SignalState, { known: true; kind: 'boolean' }>

interface RuleState {
  lastFiredAt: number
  /** For change rules: recent (t, value) samples. */
  history: { t: number; v: number }[]
  /** For cross rules: whether the rule may fire. */
  armed: boolean
  /** For edge rules. */
  lastBool: boolean | null
  pendingSince: number | null
  /** Last time the signal was known, to detect gaps. */
  lastSeenAt: number | null
}

export class EventDetector {
  private readonly rules: readonly EventRuleSpec[]
  private readonly labels: ReadonlyMap<string, string>
  private readonly states = new Map<string, RuleState>()
  private seq = 0

  constructor(rules: readonly EventRuleSpec[], signals: readonly SignalSpec[]) {
    this.rules = rules
    this.labels = new Map(signals.map((s) => [s.id, s.label]))
    this.reset()
  }

  reset(): void {
    for (const r of this.rules) {
      this.states.set(r.id, {
        lastFiredAt: -Infinity,
        history: [],
        armed: false,
        lastBool: null,
        pendingSince: null,
        lastSeenAt: null,
      })
    }
  }

  update(signals: ReadonlyMap<string, SignalState>, t: number): GameEvent[] {
    const out: GameEvent[] = []
    for (const rule of this.rules) {
      const sig = signals.get(rule.signal)
      const st = this.states.get(rule.id)!
      if (!sig || !sig.known || sig.observedAt !== t) continue // only act on fresh readings
      const gap = st.lastSeenAt === null ? 0 : t - st.lastSeenAt
      st.lastSeenAt = t
      const fired = this.evaluate(rule, sig, st, t)
      if (!fired || t - st.lastFiredAt < (rule.cooldownMs ?? DEFAULT_COOLDOWN_MS)) continue
      st.lastFiredAt = t
      const gapPenalty = gap > GAP_PENALTY_AFTER_MS ? 0.6 : 1
      out.push({
        uid: `${rule.id}#${++this.seq}`,
        ruleId: rule.id,
        label: rule.label,
        category: rule.category,
        at: t,
        confidence: sig.confidence * gapPenalty,
        evidence:
          fired + (gapPenalty < 1 ? ` (after ${(gap / 1000).toFixed(1)}s where it could not be read)` : ''),
        ...(rule.boundary ? { boundary: rule.boundary, result: rule.result ?? 'unknown' } : {}),
      })
    }
    return out
  }

  /** Returns a human-readable evidence string when the rule fires, else null. */
  private evaluate(rule: EventRuleSpec, sig: SignalState, st: RuleState, t: number): string | null {
    const label = this.labels.get(rule.signal) ?? rule.signal
    switch (rule.type) {
      case 'drop':
      case 'rise': {
        if (!isNumber(sig)) return null
        st.history.push({ t, v: sig.value })
        while (st.history.length && t - st.history[0].t > rule.withinMs) st.history.shift()
        const ref =
          rule.type === 'drop'
            ? st.history.reduce((a, b) => (b.v > a.v ? b : a))
            : st.history.reduce((a, b) => (b.v < a.v ? b : a))
        const change = rule.type === 'drop' ? ref.v - sig.value : sig.value - ref.v
        if (change < rule.delta) return null
        st.history = [{ t, v: sig.value }]
        return `${label} ${pct(ref.v)} → ${pct(sig.value)} in ${((t - ref.t) / 1000).toFixed(1)}s`
      }
      case 'below':
      case 'above': {
        if (!isNumber(sig)) return null
        const v = sig.value
        if (rule.type === 'below') {
          if (v >= rule.rearm) st.armed = true
          if (st.armed && v < rule.value) {
            st.armed = false
            return `${label} fell below ${pct(rule.value)} (now ${pct(v)})`
          }
        } else {
          if (v <= rule.rearm) st.armed = true
          if (st.armed && v > rule.value) {
            st.armed = false
            return `${label} rose above ${pct(rule.value)} (now ${pct(v)})`
          }
        }
        return null
      }
      case 'becomes-true':
      case 'becomes-false': {
        if (!isBool(sig)) return null
        const target = rule.type === 'becomes-true'
        const prev = st.lastBool
        if (prev === null) {
          st.lastBool = sig.value // first sighting establishes state; it is not a transition
          return null
        }
        if (sig.value !== target) {
          st.lastBool = sig.value
          st.pendingSince = null
          return null
        }
        if (prev === target) return null
        st.pendingSince ??= t
        if (t - st.pendingSince < (rule.minHoldMs ?? 0)) return null
        st.lastBool = target
        st.pendingSince = null
        return `${label} ${target ? 'appeared' : 'disappeared'}`
      }
    }
  }

  /** Approximate count of events emitted so far (used to create unique ids). */
  get emitted(): number {
    return this.seq
  }
}

function isNumber(s: SignalState): s is KnownNumber {
  return s.known && s.kind === 'number'
}

function isBool(s: SignalState): s is KnownBool {
  return s.known && s.kind === 'boolean'
}

function pct(v: number): string {
  return `${Math.round(v * 100)}%`
}
