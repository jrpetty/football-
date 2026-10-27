// The visible-state fuser. It turns state observations from any recogniser
// (pixel detectors, the vision model) into one short-lived picture of what is
// visibly happening. Values expire quickly, late arrivals never overwrite newer
// readings, disagreeing sources lower confidence instead of picking a winner,
// and the whole state is marked stale (with reasons) when the picture cannot
// be trusted. Missing values stay missing: nothing is filled in.

import type { FusedValue, GamePhase, Observation, VisibleState } from './types.ts'

const PHASES: readonly GamePhase[] = ['menu', 'round', 'combat', 'round_end', 'match_end', 'unknown']
/** Two sources disagreeing within this window is a conflict, not an update. */
const CONFLICT_WINDOW_MS = 2000
const DEFAULT_TTL_MS = 5000

interface Stored extends FusedValue {
  readonly obs: Observation
}

export class VisibleStateFuser {
  private readonly values = new Map<string, Stored>()
  private readonly staleReasons = new Map<string, string>()
  private readonly ttl: (key: string) => number
  private everSeen = false

  constructor(ttl: (key: string) => number = () => DEFAULT_TTL_MS) {
    this.ttl = ttl
  }

  ingest(observations: readonly Observation[]): void {
    for (const obs of observations) {
      if (obs.kind !== 'state' || obs.value === undefined) continue
      this.everSeen = true
      const cur = this.values.get(obs.eventType)
      if (cur && obs.timestampMs < cur.observedAt) continue // a late arrival never overwrites a newer reading
      const conflicted =
        !!cur &&
        cur.value !== obs.value &&
        cur.source !== obs.source &&
        obs.timestampMs - cur.observedAt < CONFLICT_WINDOW_MS &&
        cur.confidence >= 0.5 &&
        obs.confidence >= 0.5
      this.values.set(obs.eventType, {
        value: obs.value,
        confidence: conflicted ? Math.min(cur!.confidence, obs.confidence) * 0.5 : obs.confidence,
        observedAt: obs.timestampMs,
        source: obs.source,
        conflicted,
        obs,
      })
    }
  }

  /** Mark (or clear, with null) a reason the picture cannot be trusted right now. */
  setStale(id: string, reason: string | null): void {
    if (reason) this.staleReasons.set(id, reason)
    else this.staleReasons.delete(id)
  }

  /** A fresh value for a key, or null if missing, expired, or the state is stale. */
  get(key: string, now: number): FusedValue | null {
    if (this.staleReasons.size) return null
    const v = this.values.get(key)
    return v && now - v.observedAt <= this.ttl(key) ? v : null
  }

  snapshot(now: number): VisibleState {
    const fresh: Record<string, FusedValue> = {}
    const active: Observation[] = []
    for (const [key, v] of this.values) {
      if (now - v.observedAt > this.ttl(key)) continue
      fresh[key] = { value: v.value, confidence: v.confidence, observedAt: v.observedAt, source: v.source, conflicted: v.conflicted }
      active.push(v.obs)
    }
    const reasons = [...this.staleReasons.values()]
    const keys = Object.keys(fresh)
    if (this.everSeen && keys.length === 0 && reasons.length === 0) reasons.push('no fresh observations')
    const conflicts = keys.filter((k) => fresh[k].conflicted)
    if (conflicts.length) reasons.push(`recognisers disagree about ${conflicts.join(', ')}`)
    const stale = this.staleReasons.size > 0 || (this.everSeen && keys.length === 0)
    const confidence = keys.length ? keys.reduce((s, k) => s + fresh[k].confidence, 0) / keys.length : 0

    const phaseValue = fresh.phase?.value
    const outcome = fresh.visibleOutcome?.value
    const health = fresh.visibleHealth?.value
    const objective = fresh.visibleObjective?.value
    return {
      timestampMs: now,
      phase: typeof phaseValue === 'string' && (PHASES as readonly string[]).includes(phaseValue) ? (phaseValue as GamePhase) : 'unknown',
      ...(typeof health === 'number' ? { visibleHealth: health } : {}),
      ...(typeof objective === 'string' ? { visibleObjective: objective } : {}),
      ...(outcome === 'success' || outcome === 'failure' || outcome === 'unknown' ? { visibleOutcome: outcome } : {}),
      values: stale ? {} : fresh,
      activeSignals: stale ? [] : active,
      confidence: stale ? confidence * 0.5 : confidence,
      stale,
      staleReasons: reasons,
    }
  }

  reset(): void {
    this.values.clear()
    this.everSeen = false
  }
}
