// ---------------------------------------------------------------------------
// Tracking squad movement between runs.
//
// A transfer is invisible in a snapshot: the feed simply reports a player at a
// different club than last time, with no event to say he moved. Diffing two
// snapshots turns that into a record — who arrived, who left, and what it
// costs the sides involved.
//
// This matters beyond bookkeeping. A club that has just sold its main striker
// carries the same team rating it earned with him, and the model has no way of
// knowing until results catch up. Naming the move at least puts that in front
// of a reader.
//
// Element ids are stable within a season but reassigned between them, so every
// snapshot carries its season and a rollover resets rather than reporting five
// hundred imaginary transfers.
// ---------------------------------------------------------------------------

export interface RosterEntry {
  team: string
  name: string
}

export interface RosterSnapshot {
  season: string
  takenAt: number
  /** Player id -> where he was. */
  byPlayer: Record<number, RosterEntry>
}

/**
 * `listed` is the important distinction.
 *
 * A player appearing in the feed for the first time is not necessarily a
 * signing — the source periodically expands its roster, and a squad player who
 * was previously unlisted shows up looking exactly like an arrival. The feed's
 * own join date tells them apart: a genuine summer signing carries a date from
 * this window, while Mudryk turning up with a join date of January 2023 has
 * plainly not just moved. Calling both "arrived" would put fiction in a ledger
 * whose only job is to be accurate.
 */
export type TransferKind = 'moved' | 'arrived' | 'listed' | 'left'

export interface TransferRecord {
  playerId: number
  name: string
  kind: TransferKind
  /** Club left, or null for an arrival from outside the league. */
  from: string | null
  /** Club joined, or null for a departure out of the league. */
  to: string | null
  /** The feed's own join date, where it states one. */
  joinedAt: string | null
  /** When this pipeline first saw the change. */
  detectedAt: number
  position: string
  cost: number
}

export interface TransfersArtifactShape {
  schemaVersion: number
  season: string
  generatedAt: number
  roster: RosterSnapshot
  /** Most recent first. */
  records: TransferRecord[]
}

/** A player as the diff needs to see him. */
export interface RosterPlayer {
  id: number
  name: string
  team: string
  position: string
  cost: number
  joinedAt: string | null
}

/**
 * A diff larger than this is a feed problem, not a transfer window.
 *
 * Even a chaotic deadline day moves a handful of Premier League players. A
 * hundred "transfers" in one run means the source changed shape or ids were
 * reassigned, and writing that into the ledger would bury every real move.
 */
export const IMPLAUSIBLE_CHANGE_COUNT = 60

/** A join date older than this means the player was already at the club. */
export const RECENT_JOIN_DAYS = 120

/** Did this player genuinely just arrive, or merely appear in the feed? */
export function classifyArrival(joinedAt: string | null, now: number): 'arrived' | 'listed' {
  if (!joinedAt) return 'listed'
  const t = Date.parse(joinedAt)
  if (!Number.isFinite(t)) return 'listed'
  return now - t <= RECENT_JOIN_DAYS * 86400000 ? 'arrived' : 'listed'
}

export interface DiffResult {
  records: TransferRecord[]
  /** Set when the diff was rejected, with the reason. */
  rejected: string | null
}

/** Compare the current roster against the last snapshot. */
export function diffRosters(
  previous: RosterSnapshot | null,
  current: readonly RosterPlayer[],
  season: string,
  now: number,
): DiffResult {
  // No prior snapshot, or a new season: establish a baseline, report nothing.
  // Ids do not survive a season change, so a diff across one is meaningless.
  if (!previous) return { records: [], rejected: null }
  if (previous.season !== season) {
    return { records: [], rejected: `season changed from ${previous.season} to ${season}` }
  }

  const records: TransferRecord[] = []
  const seen = new Set<number>()

  for (const p of current) {
    seen.add(p.id)
    const before = previous.byPlayer[p.id]
    if (!before) {
      records.push({
        playerId: p.id, name: p.name, kind: classifyArrival(p.joinedAt, now),
        from: null, to: p.team, joinedAt: p.joinedAt, detectedAt: now,
        position: p.position, cost: p.cost,
      })
    } else if (before.team !== p.team) {
      records.push({
        playerId: p.id, name: p.name, kind: 'moved',
        from: before.team, to: p.team, joinedAt: p.joinedAt, detectedAt: now,
        position: p.position, cost: p.cost,
      })
    }
  }

  for (const [idRaw, before] of Object.entries(previous.byPlayer)) {
    const id = Number(idRaw)
    if (seen.has(id)) continue
    records.push({
      playerId: id, name: before.name, kind: 'left',
      from: before.team, to: null, joinedAt: null, detectedAt: now,
      position: '', cost: 0,
    })
  }

  if (records.length > IMPLAUSIBLE_CHANGE_COUNT) {
    return {
      records: [],
      rejected: `${records.length} squad changes in one run — treating this as a feed problem rather than transfers`,
    }
  }

  return { records, rejected: null }
}

/** Build a snapshot of where everyone currently is. */
export function takeSnapshot(
  players: readonly RosterPlayer[],
  season: string,
  now: number,
): RosterSnapshot {
  const byPlayer: Record<number, RosterEntry> = {}
  for (const p of players) byPlayer[p.id] = { team: p.team, name: p.name }
  return { season, takenAt: now, byPlayer }
}

/**
 * Merge newly detected moves into the ledger.
 *
 * Keyed on player and destination so a move is not re-recorded every run while
 * it remains true, and capped so the file cannot grow without bound.
 */
export function mergeTransfers(
  existing: readonly TransferRecord[],
  fresh: readonly TransferRecord[],
  limit = 300,
): TransferRecord[] {
  const key = (r: TransferRecord): string => `${r.playerId}#${r.kind}#${r.from ?? ''}#${r.to ?? ''}`
  const seen = new Set(existing.map(key))
  const merged = [...fresh.filter((r) => !seen.has(key(r))), ...existing]
  merged.sort((a, b) => b.detectedAt - a.detectedAt)
  return merged.slice(0, limit)
}

/** Moves inside a recent window, for the UI. */
export function recentTransfers(
  records: readonly TransferRecord[],
  now: number,
  days = 60,
): TransferRecord[] {
  const cutoff = now - days * 86400000
  return records.filter((r) => r.detectedAt >= cutoff)
}
