// Round and match boundaries from the visible game phase (vision packs).
// A phase change counts only when it is confident or seen twice in a row, so
// one misread frame cannot end a match.

import type { BoundaryKind, GameEvent, GamePhase, RoundResult, VisibleState } from '../types.ts'

const CONFIDENT = 0.85
const MIN_CONFIDENCE = 0.6
const PLAY: ReadonlySet<GamePhase> = new Set(['round', 'combat'])

export class PhaseBoundaryDetector {
  private stable: GamePhase = 'unknown'
  private candidate: { phase: GamePhase; reads: number } | null = null
  private seq = 0

  update(state: VisibleState): GameEvent[] {
    const p = state.values.phase
    if (!p || typeof p.value !== 'string' || p.confidence < MIN_CONFIDENCE) return []
    const phase = state.phase
    if (phase === 'unknown' || phase === this.stable) {
      this.candidate = null
      return []
    }
    if (this.candidate?.phase === phase) this.candidate.reads++
    else this.candidate = { phase, reads: 1 }
    if (this.candidate.reads < 2 && p.confidence < CONFIDENT) return []
    this.candidate = null

    const from = this.stable
    this.stable = phase
    const at = p.observedAt
    if (PLAY.has(phase) && !PLAY.has(from)) return [this.boundary('round-start', at, p.confidence, `phase ${from} → ${phase}`)]
    if (phase === 'round_end' && PLAY.has(from)) {
      return [this.boundary('round-end', at, p.confidence, `phase ${from} → round end`, result(state))]
    }
    if (phase === 'match_end') return [this.boundary('match-end', at, p.confidence, `phase ${from} → match end`, result(state))]
    return []
  }

  get phase(): GamePhase {
    return this.stable
  }

  private boundary(kind: BoundaryKind, at: number, confidence: number, evidence: string, res: RoundResult = 'unknown'): GameEvent {
    const label = kind === 'round-start' ? 'Round / half started' : kind === 'round-end' ? 'Round / half ended' : 'Match ended'
    return {
      uid: `phase-${kind}#${++this.seq}`,
      ruleId: `phase-${kind}`,
      label,
      category: 'boundary',
      at,
      confidence,
      evidence,
      boundary: kind,
      result: res,
      source: 'derived',
    }
  }
}

function result(state: VisibleState): RoundResult {
  return state.visibleOutcome === 'success' ? 'win' : state.visibleOutcome === 'failure' ? 'loss' : 'unknown'
}
