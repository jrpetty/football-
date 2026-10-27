// Pattern memory: outcome counts per (episode type, visible response). This is
// the only thing the coach "learns", and the only part that can outlive a
// session — and only if the player opts in. It holds counts, a last-seen date,
// and the player's own verdicts (dismissed / marked wrong); never frames,
// timelines, or anything that could reconstruct a match.
//
// Long-term evidence is segmented by game mode (ranked habits are not mixed
// with practice), gated by the pack's major version (a new patch starts
// fresh), and decays with a 30-day half-life so old habits fade.

import { majorVersion, type GameProfile } from './profile/schema.ts'
import type { Episode, OutcomeCounts } from './types.ts'

export const SNAPSHOT_SCHEMA_VERSION = 2
/** Per-episode history cap; older evidence is scaled down so recent play dominates. */
export const HISTORY_CAP = 200
export const HALF_LIFE_DAYS = 30
export const UNSPECIFIED_MODE = 'unspecified'

export type PatternVerdict = 'dismissed' | 'disputed'

export interface StoredCounts extends OutcomeCounts {
  readonly lastObservedAt: string
}

export interface PatternSnapshot {
  readonly schemaVersion: typeof SNAPSHOT_SCHEMA_VERSION
  readonly profileId: string
  readonly profileVersion: string
  readonly updatedAt: string
  readonly sessions: number
  /** mode → episode → response → counts. */
  readonly segments: Readonly<Record<string, Readonly<Record<string, Readonly<Record<string, StoredCounts>>>>>>
  /** The player's verdicts on patterns, by episode id. */
  readonly verdicts: Readonly<Record<string, { readonly verdict: PatternVerdict; readonly at: string }>>
}

type CountTable = Map<string, Map<string, OutcomeCounts & { lastObservedAt?: string }>>

export class PatternMemory {
  private readonly session: CountTable = new Map()
  private readonly history: CountTable = new Map()
  private readonly snapshotIn: PatternSnapshot | null
  private readonly mode: string
  private readonly now: Date
  /** Why history was not loaded, when it was offered but rejected. */
  readonly historyNotice: string | null
  private readonly profile: Pick<GameProfile, 'id' | 'version'>

  constructor(profile: Pick<GameProfile, 'id' | 'version'>, snapshot: PatternSnapshot | null = null, mode: string | null = null, now = new Date()) {
    this.profile = profile
    this.mode = mode ?? UNSPECIFIED_MODE
    this.now = now
    this.snapshotIn = snapshot
    this.historyNotice = snapshot ? this.loadHistory(snapshot) : null
  }

  record(ep: Episode): void {
    if (ep.excluded) return
    const c = cell(this.session, ep.episodeId, ep.response)
    c.n++
    if (ep.outcome === 'negative') c.negative++
    if (ep.outcome === 'positive') c.positive++
  }

  /** Combined (decayed) history + session counts for one episode type. */
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

  verdict(episodeId: string): PatternVerdict | null {
    return this.snapshotIn?.verdicts[episodeId]?.verdict ?? null
  }

  /**
   * History merged with this session, ready to persist (only when the player
   * opted in). Other modes' segments and the player's verdicts carry over
   * untouched; the stored history is undecayed counts with last-seen dates.
   */
  snapshot(now: Date): PatternSnapshot {
    const prev = this.snapshotIn && this.historyNotice === null ? this.snapshotIn : null
    const segments: Record<string, Record<string, Record<string, StoredCounts>>> = structuredClone(
      (prev?.segments ?? {}) as Record<string, Record<string, Record<string, StoredCounts>>>,
    )
    const mine = (segments[this.mode] ??= {})
    for (const [episodeId, responses] of this.session) {
      const ep = (mine[episodeId] ??= {})
      for (const [response, c] of responses) {
        const o = ep[response] ?? { n: 0, negative: 0, positive: 0, lastObservedAt: now.toISOString() }
        ep[response] = { n: o.n + c.n, negative: o.negative + c.negative, positive: o.positive + c.positive, lastObservedAt: now.toISOString() }
      }
      const total = Object.values(ep).reduce((s, c) => s + c.n, 0)
      if (total > HISTORY_CAP) {
        const scale = HISTORY_CAP / total
        for (const [r, c] of Object.entries(ep)) {
          const n = Math.max(1, Math.round(c.n * scale))
          ep[r] = { n, negative: Math.min(n, Math.round(c.negative * scale)), positive: Math.min(n, Math.round(c.positive * scale)), lastObservedAt: c.lastObservedAt }
        }
      }
    }
    return {
      schemaVersion: SNAPSHOT_SCHEMA_VERSION,
      profileId: this.profile.id,
      profileVersion: this.profile.version,
      updatedAt: now.toISOString(),
      sessions: (prev?.sessions ?? 0) + 1,
      segments,
      verdicts: prev?.verdicts ?? {},
    }
  }

  private loadHistory(s: PatternSnapshot): string | null {
    if (s.profileId !== this.profile.id) return 'Saved tendencies were for a different pack and were not loaded.'
    if (majorVersion(s.profileVersion) !== majorVersion(this.profile.version)) {
      return `Saved tendencies came from pack v${s.profileVersion}; this is v${this.profile.version} (likely a new game patch), so they were not mixed in.`
    }
    for (const [episodeId, responses] of Object.entries(s.segments[this.mode] ?? {})) {
      for (const [response, c] of Object.entries(responses)) {
        const weight = decayWeight(c.lastObservedAt, this.now)
        const n = Math.round(c.n * weight)
        if (n === 0) continue
        const target = cell(this.history, episodeId, response)
        target.n += n
        target.negative += Math.min(n, Math.round(c.negative * weight))
        target.positive += Math.min(n, Math.round(c.positive * weight))
      }
    }
    return null
  }
}

/** Evidence weight after `HALF_LIFE_DAYS`-day half-life decay. */
export function decayWeight(lastObservedAt: string, now: Date): number {
  const ageDays = Math.max(0, (now.getTime() - Date.parse(lastObservedAt)) / 86_400_000)
  return Number.isFinite(ageDays) ? 0.5 ** (ageDays / HALF_LIFE_DAYS) : 0
}

function cell(table: CountTable, episodeId: string, response: string): OutcomeCounts {
  let byResponse = table.get(episodeId)
  if (!byResponse) table.set(episodeId, (byResponse = new Map()))
  let c = byResponse.get(response)
  if (!c) byResponse.set(response, (c = { n: 0, negative: 0, positive: 0 }))
  return c
}

// --- Player controls over stored patterns ----------------------------------------------

export function setVerdict(s: PatternSnapshot, episodeId: string, verdict: PatternVerdict | null, now: Date): PatternSnapshot {
  const verdicts = { ...s.verdicts }
  if (verdict) verdicts[episodeId] = { verdict, at: now.toISOString() }
  else delete verdicts[episodeId]
  return { ...s, verdicts, updatedAt: now.toISOString() }
}

/** Delete one pattern's evidence in every mode. */
export function deletePattern(s: PatternSnapshot, episodeId: string, now: Date): PatternSnapshot {
  const segments = Object.fromEntries(
    Object.entries(s.segments).map(([mode, eps]) => [mode, Object.fromEntries(Object.entries(eps).filter(([id]) => id !== episodeId))]),
  )
  const verdicts = { ...s.verdicts }
  delete verdicts[episodeId]
  return { ...s, segments, verdicts, updatedAt: now.toISOString() }
}

// --- Validation and migration (snapshots are read from disk) -----------------------------

export function isCounts(c: unknown): c is OutcomeCounts {
  if (typeof c !== 'object' || c === null) return false
  const o = c as Record<string, unknown>
  const ok = (v: unknown) => typeof v === 'number' && Number.isInteger(v) && v >= 0 && v <= 1_000_000
  return ok(o.n) && ok(o.negative) && ok(o.positive) && (o.negative as number) + (o.positive as number) <= (o.n as number)
}

function isStoredCounts(c: unknown): c is StoredCounts {
  return isCounts(c) && typeof (c as { lastObservedAt?: unknown }).lastObservedAt === 'string'
}

export function isPatternSnapshot(v: unknown): v is PatternSnapshot {
  if (typeof v !== 'object' || v === null) return false
  const o = v as Record<string, unknown>
  if (o.schemaVersion !== SNAPSHOT_SCHEMA_VERSION) return false
  if (typeof o.profileId !== 'string' || typeof o.profileVersion !== 'string' || typeof o.updatedAt !== 'string') return false
  if (typeof o.sessions !== 'number' || typeof o.segments !== 'object' || o.segments === null) return false
  if (typeof o.verdicts !== 'object' || o.verdicts === null) return false
  for (const v of Object.values(o.verdicts as Record<string, unknown>)) {
    const x = v as { verdict?: unknown } | null
    if (!x || (x.verdict !== 'dismissed' && x.verdict !== 'disputed')) return false
  }
  return Object.values(o.segments as Record<string, unknown>).every(
    (eps) =>
      typeof eps === 'object' &&
      eps !== null &&
      Object.values(eps).every((r) => typeof r === 'object' && r !== null && Object.values(r).every(isStoredCounts)),
  )
}

/** Read a stored snapshot of any known version; null if unusable. */
export function migrateSnapshot(v: unknown): PatternSnapshot | null {
  if (isPatternSnapshot(v)) return v
  const o = v as { schemaVersion?: unknown; profileId?: unknown; profileVersion?: unknown; updatedAt?: unknown; sessions?: unknown; episodes?: unknown } | null
  if (!o || o.schemaVersion !== 1 || typeof o.profileId !== 'string' || typeof o.profileVersion !== 'string') return null
  if (typeof o.episodes !== 'object' || o.episodes === null) return null
  const updatedAt = typeof o.updatedAt === 'string' ? o.updatedAt : new Date(0).toISOString()
  const eps: Record<string, Record<string, StoredCounts>> = {}
  for (const [id, responses] of Object.entries(o.episodes as Record<string, Record<string, unknown>>)) {
    for (const [r, c] of Object.entries(responses ?? {})) {
      if (!isCounts(c)) continue
      ;(eps[id] ??= {})[r] = { ...c, lastObservedAt: updatedAt }
    }
  }
  const migrated = {
    schemaVersion: SNAPSHOT_SCHEMA_VERSION,
    profileId: o.profileId,
    profileVersion: o.profileVersion,
    updatedAt,
    sessions: typeof o.sessions === 'number' ? o.sessions : 1,
    segments: { [UNSPECIFIED_MODE]: eps },
    verdicts: {},
  } satisfies PatternSnapshot
  return isPatternSnapshot(migrated) ? migrated : null
}
