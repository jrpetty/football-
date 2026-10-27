// Goals from the scoreboard. A score change only counts when it is a single
// goal (+1 on one side), read consistently (twice, or once with very high
// confidence). Misreads such as a jump of three or a decrease are ignored;
// a persistent new reading quietly becomes the new baseline. Vision sightings
// of a goal (with zone and build-up) are fused onto the scoreboard goal they
// belong to; if the scoreboard cannot be read at all, a confident sighting
// stands on its own.

import type { ScoreRule } from '../profile/schema.ts'
import type { GameEvent, VisibleState } from '../types.ts'

const MIN_CONFIDENCE = 0.6
const SINGLE_READ_CONFIDENCE = 0.9
const RESYNC_AFTER = 3

export interface ScoreUpdate {
  readonly events: GameEvent[]
  readonly notices: string[]
  /** Scoreboard goals that a held sighting has just enriched: uid → zone/attributes. */
  readonly amendments: { uid: string; zone?: string; attributes: Record<string, string | number | boolean> }[]
}

interface Sighting {
  readonly ev: GameEvent
  readonly scorer: 'player' | 'opponent'
}

export class ScoreTracker {
  private readonly rule: ScoreRule
  private side: 'home' | 'away'
  private baseline: { home: number; away: number } | null = null
  private pending: { home: number; away: number; reads: number } | null = null
  private seq = 0
  private readonly recentGoals: GameEvent[] = []
  private heldSightings: Sighting[] = []
  private everReadable = false

  constructor(rule: ScoreRule, side: 'home' | 'away') {
    this.rule = rule
    this.side = side
  }

  setSide(side: 'home' | 'away'): void {
    this.side = side
  }

  get score(): { player: number; opponent: number } | null {
    if (!this.baseline) return null
    return this.side === 'home'
      ? { player: this.baseline.home, opponent: this.baseline.away }
      : { player: this.baseline.away, opponent: this.baseline.home }
  }

  newMatch(): void {
    this.baseline = null
    this.pending = null
    this.recentGoals.length = 0
    this.heldSightings = []
  }

  update(state: VisibleState, now: number): ScoreUpdate {
    const out: ScoreUpdate = { events: [], notices: [], amendments: [] }
    const h = state.values[this.rule.homeKey]
    const a = state.values[this.rule.awayKey]
    if (h && a && typeof h.value === 'number' && typeof a.value === 'number' && !h.conflicted && !a.conflicted) {
      const confidence = Math.min(h.confidence, a.confidence)
      if (confidence >= MIN_CONFIDENCE) {
        this.everReadable = true
        this.read({ home: h.value, away: a.value }, confidence, Math.max(h.observedAt, a.observedAt), out)
      }
    }
    // Sightings that waited for a scoreboard goal and never got one.
    const window = this.rule.confirmWindowMs ?? 45_000
    const expired = this.heldSightings.filter((s) => now - s.ev.at > window)
    this.heldSightings = this.heldSightings.filter((s) => now - s.ev.at <= window)
    for (const s of expired) {
      if (!this.everReadable && s.ev.confidence >= 0.75) out.events.push(this.goal(s.scorer, s.ev.at, s.ev.confidence, `seen on screen (scoreboard unreadable): ${s.ev.evidence}`, s.ev))
    }
    return out
  }

  /** A vision sighting of a goal: attach it to a scoreboard goal, or hold it until one arrives. */
  sighting(ev: GameEvent): ScoreUpdate['amendments'] {
    const scorer = ev.attributes?.scorer
    if (scorer !== 'player' && scorer !== 'opponent') return []
    const target = scorer === 'player' ? this.rule.forEvent : this.rule.againstEvent
    const window = this.rule.confirmWindowMs ?? 45_000
    const match = this.recentGoals.find((g) => g.ruleId === target && Math.abs(g.at - ev.at) <= window && !g.zone)
    if (match) {
      this.recentGoals.splice(this.recentGoals.indexOf(match), 1, { ...match, zone: ev.zone })
      return [{ uid: match.uid, zone: ev.zone, attributes: { ...(ev.attributes ?? {}) } }]
    }
    this.heldSightings.push({ ev, scorer })
    return []
  }

  private read(cur: { home: number; away: number }, confidence: number, at: number, out: ScoreUpdate): void {
    if (!this.baseline) {
      if (this.confirm(cur, confidence)) this.baseline = cur
      return
    }
    const dh = cur.home - this.baseline.home
    const da = cur.away - this.baseline.away
    if (dh === 0 && da === 0) {
      this.pending = null
      return
    }
    const single = (dh === 1 && da === 0) || (dh === 0 && da === 1)
    if (!this.confirm(cur, confidence, single ? 1 : RESYNC_AFTER - 1)) return
    const before = this.baseline
    this.baseline = cur
    if (!single) {
      out.notices.push(`The scoreboard read ${cur.home}–${cur.away} after ${before.home}–${before.away}; treated as a correction, no goal counted.`)
      return
    }
    const homeScored = dh === 1
    const scorer = (homeScored && this.side === 'home') || (!homeScored && this.side === 'away') ? 'player' : 'opponent'
    const ev = this.goal(scorer, at, confidence, `scoreboard ${before.home}–${before.away} → ${cur.home}–${cur.away}`)
    // A sighting may already be waiting for this goal.
    const held = this.heldSightings.find((s) => s.scorer === scorer && Math.abs(s.ev.at - at) <= (this.rule.confirmWindowMs ?? 45_000))
    if (held) {
      this.heldSightings.splice(this.heldSightings.indexOf(held), 1)
      out.events.push({ ...ev, zone: held.ev.zone, attributes: { ...(held.ev.attributes ?? {}) } })
      this.recentGoals.push({ ...ev, zone: held.ev.zone })
    } else {
      out.events.push(ev)
      this.recentGoals.push(ev)
    }
    while (this.recentGoals.length > 20) this.recentGoals.shift()
  }

  /** True once a reading is consistent enough to trust. */
  private confirm(cur: { home: number; away: number }, confidence: number, needed = 1): boolean {
    if (confidence >= SINGLE_READ_CONFIDENCE && needed <= 1) {
      this.pending = null
      return true
    }
    if (this.pending && this.pending.home === cur.home && this.pending.away === cur.away) this.pending.reads++
    else this.pending = { ...cur, reads: 1 }
    if (this.pending.reads > needed) {
      this.pending = null
      return true
    }
    return false
  }

  private goal(scorer: 'player' | 'opponent', at: number, confidence: number, evidence: string, from?: GameEvent): GameEvent {
    const forPlayer = scorer === 'player'
    return {
      uid: `${forPlayer ? this.rule.forEvent : this.rule.againstEvent}#${++this.seq}`,
      ruleId: forPlayer ? this.rule.forEvent : this.rule.againstEvent,
      label: forPlayer ? 'Goal scored' : 'Goal conceded',
      category: 'outcome',
      at,
      confidence,
      evidence,
      source: 'derived',
      ...(from?.zone ? { zone: from.zone } : {}),
      ...(from?.attributes ? { attributes: from.attributes } : {}),
    }
  }
}
