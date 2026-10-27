// Match records: a compact, structured summary of one match — final score,
// result, end-screen statistics, mistakes and zone counts. This is what
// cross-match history (opt-in) is made of. No frames, no timeline.

import type { MatchStatSpec } from '../profile/schema.ts'
import type { RoundResult, VisibleState } from '../types.ts'
import type { HeatmapResult } from './heatmap.ts'
import type { MistakeTotals } from './mistakes.ts'

export const MATCH_RECORD_SCHEMA = 1

export interface MatchRecord {
  readonly schema: typeof MATCH_RECORD_SCHEMA
  readonly id: string
  readonly gameProfileId: string
  readonly profileVersion: string
  readonly modeId: string | null
  /** ISO date of the end of the match. */
  readonly endedAt: string
  readonly durationSec: number
  readonly result: RoundResult
  readonly score: { readonly player: number; readonly opponent: number } | null
  readonly rounds: { readonly won: number; readonly lost: number; readonly drawn: number; readonly unread: number }
  /** End-screen statistics that were read with enough confidence. */
  readonly stats: Readonly<Record<string, number>>
  readonly mistakes: Readonly<Record<string, { readonly count: number; readonly totalSec: number }>>
  readonly zones: Readonly<Record<string, Readonly<Record<string, number>>>>
  /** True when the match was ended by the player (stop or "match over"), not seen on screen. */
  readonly endedManually: boolean
}

const STAT_MIN_CONFIDENCE = 0.7

export class MatchRecorder {
  private readonly spec: MatchStatSpec | undefined
  private stats = new Map<string, { value: number; confidence: number }>()
  private startedAt: number | null = null

  constructor(spec: MatchStatSpec | undefined) {
    this.spec = spec
  }

  observe(state: VisibleState, now: number): void {
    this.startedAt ??= now
    if (!this.spec) return
    for (const { key } of this.spec.keys) {
      const v = state.values[key]
      if (!v || typeof v.value !== 'number' || v.conflicted || v.confidence < STAT_MIN_CONFIDENCE) continue
      const cur = this.stats.get(key)
      // Statistics only grow towards the final screen; keep the most confident latest reading.
      if (!cur || v.confidence >= cur.confidence) this.stats.set(key, { value: v.value, confidence: v.confidence })
    }
  }

  finish(input: {
    now: number
    endedAt: Date
    gameProfileId: string
    profileVersion: string
    modeId: string | null
    result: RoundResult
    score: MatchRecord['score']
    rounds: MatchRecord['rounds']
    mistakes: Record<string, MistakeTotals>
    heatmaps: readonly HeatmapResult[]
    endedManually: boolean
  }): MatchRecord {
    const record: MatchRecord = {
      schema: MATCH_RECORD_SCHEMA,
      id: `${input.gameProfileId}-${input.endedAt.getTime().toString(36)}`,
      gameProfileId: input.gameProfileId,
      profileVersion: input.profileVersion,
      modeId: input.modeId,
      endedAt: input.endedAt.toISOString(),
      durationSec: this.startedAt === null ? 0 : Math.round((input.now - this.startedAt) / 1000),
      result: input.result,
      score: input.score,
      rounds: input.rounds,
      stats: Object.fromEntries([...this.stats].map(([k, v]) => [k, v.value])),
      mistakes: Object.fromEntries(
        Object.entries(input.mistakes)
          .filter(([, t]) => t.count > 0)
          .map(([k, t]) => [k, { count: t.count, totalSec: Math.round(t.totalMs / 1000) }]),
      ),
      zones: Object.fromEntries(input.heatmaps.filter((h) => h.total > 0).map((h) => [h.spec.id, { ...h.cells }])),
      endedManually: input.endedManually,
    }
    this.stats = new Map()
    this.startedAt = null
    return record
  }
}

/** Shape check for records read back from disk. */
export function isMatchRecord(v: unknown): v is MatchRecord {
  if (typeof v !== 'object' || v === null) return false
  const o = v as Record<string, unknown>
  return (
    o.schema === MATCH_RECORD_SCHEMA &&
    typeof o.id === 'string' &&
    typeof o.gameProfileId === 'string' &&
    typeof o.endedAt === 'string' &&
    typeof o.stats === 'object' &&
    o.stats !== null &&
    Object.values(o.stats as object).every((x) => typeof x === 'number' && Number.isFinite(x))
  )
}
