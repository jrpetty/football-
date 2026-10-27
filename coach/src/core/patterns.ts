// Pattern memory: outcome counts per (episode type, visible response). This is
// the only thing the coach "learns", and the only thing that can outlive a
// session — and only if the player opts in. It holds counts, never frames,
// timestamps of play, or anything that could reconstruct a match.

import { majorVersion, type GameProfile } from './profile/schema.ts'
import type { Episode, OutcomeCounts } from './types.ts'

export const SNAPSHOT_SCHEMA_VERSION = 1
/** Per-episode history cap; older evidence is scaled down so recent play dominates. */
export const HISTORY_CAP = 200

export interface PatternSnapshot {
  readonly schemaVersion: typeof SNAPSHOT_SCHEMA_VERSION
  readonly profileId: string
  readonly profileVersion: string
  readonly updatedAt: string
  readonly sessions: number
  readonly episodes: Readonly<Record<string, Readonly<Record<string, OutcomeCounts>>>>
}

type CountTable = Map<string, Map<string, OutcomeCounts>>

export class PatternMemory {
  private readonly session: CountTable = new Map()
  private readonly history: CountTable = new Map()
  private historySessions = 0
  /** Why history was not loaded, when it was offered but rejected. */
  readonly historyNotice: string | null
  private readonly profile: Pick<GameProfile, 'id' | 'version'>

  constructor(profile: Pick<GameProfile, 'id' | 'version'>, snapshot: PatternSnapshot | null = null) {
    this.profile = profile
    this.historyNotice = snapshot ? this.loadHistory(snapshot) : null
  }

  record(ep: Episode): void {
    if (ep.excluded) return
    const c = cell(this.session, ep.episodeId, ep.response)
    c.n++
    if (ep.outcome === 'negative') c.negative++
    if (ep.outcome === 'positive') c.positive++
  }

  /** Combined history + session counts for one episode type. */
  counts(episodeId: string): { counts: Map<string, OutcomeCounts>; usesHistory: boolean } {
    const out = new Map<string, OutcomeCounts>()
    let usesHistory = false
    for (const [table, isHistory] of [
      [this.history, true],
      [this.session, false],
    ] as const) {
      for (const [response, c] of table.get(episodeId) ?? []) {
        if (isHistory && c.n > 0) usesHistory = true
        const o = out.get(response) ?? { n: 0, negative: 0, positive: 0 }
        out.set(response, { n: o.n + c.n, negative: o.negative + c.negative, positive: o.positive + c.positive })
      }
    }
    return { counts: out, usesHistory }
  }

  sessionCounts(episodeId: string): Map<string, OutcomeCounts> {
    return new Map([...(this.session.get(episodeId) ?? [])].map(([k, v]) => [k, { ...v }]))
  }

  /** History merged with this session, ready to persist (only when the player opted in). */
  snapshot(now: Date): PatternSnapshot {
    const episodes: Record<string, Record<string, OutcomeCounts>> = {}
    const ids = new Set([...this.history.keys(), ...this.session.keys()])
    for (const id of ids) {
      const merged = this.counts(id).counts
      const total = [...merged.values()].reduce((s, c) => s + c.n, 0)
      const scale = total > HISTORY_CAP ? HISTORY_CAP / total : 1
      episodes[id] = {}
      for (const [response, c] of merged) {
        const n = Math.round(c.n * scale)
        if (n === 0) continue
        episodes[id][response] = {
          n,
          negative: Math.min(n, Math.round(c.negative * scale)),
          positive: Math.min(n, Math.round(c.positive * scale)),
        }
      }
    }
    return {
      schemaVersion: SNAPSHOT_SCHEMA_VERSION,
      profileId: this.profile.id,
      profileVersion: this.profile.version,
      updatedAt: now.toISOString(),
      sessions: this.historySessions + 1,
      episodes,
    }
  }

  private loadHistory(s: PatternSnapshot): string | null {
    if (s.schemaVersion !== SNAPSHOT_SCHEMA_VERSION || s.profileId !== this.profile.id) {
      return 'Saved tendencies were for a different profile and were not loaded.'
    }
    if (majorVersion(s.profileVersion) !== majorVersion(this.profile.version)) {
      return `Saved tendencies came from profile v${s.profileVersion}; this is v${this.profile.version} (likely a new game patch), so they were not mixed in.`
    }
    for (const [episodeId, responses] of Object.entries(s.episodes)) {
      for (const [response, c] of Object.entries(responses)) {
        if (!isCounts(c)) continue
        const target = cell(this.history, episodeId, response)
        target.n += c.n
        target.negative += c.negative
        target.positive += c.positive
      }
    }
    this.historySessions = s.sessions
    return null
  }
}

function cell(table: CountTable, episodeId: string, response: string): OutcomeCounts {
  let byResponse = table.get(episodeId)
  if (!byResponse) table.set(episodeId, (byResponse = new Map()))
  let c = byResponse.get(response)
  if (!c) byResponse.set(response, (c = { n: 0, negative: 0, positive: 0 }))
  return c
}

/** Snapshots are read from disk, so never trust their shape. */
export function isCounts(c: unknown): c is OutcomeCounts {
  if (typeof c !== 'object' || c === null) return false
  const o = c as Record<string, unknown>
  const ok = (v: unknown) => typeof v === 'number' && Number.isInteger(v) && v >= 0 && v <= 1_000_000
  return ok(o.n) && ok(o.negative) && ok(o.positive) && (o.negative as number) + (o.positive as number) <= (o.n as number)
}

export function isPatternSnapshot(v: unknown): v is PatternSnapshot {
  if (typeof v !== 'object' || v === null) return false
  const o = v as Record<string, unknown>
  if (o.schemaVersion !== SNAPSHOT_SCHEMA_VERSION) return false
  if (typeof o.profileId !== 'string' || typeof o.profileVersion !== 'string' || typeof o.updatedAt !== 'string') return false
  if (typeof o.sessions !== 'number' || typeof o.episodes !== 'object' || o.episodes === null) return false
  return Object.values(o.episodes as Record<string, unknown>).every(
    (r) => typeof r === 'object' && r !== null && Object.values(r).every(isCounts),
  )
}
