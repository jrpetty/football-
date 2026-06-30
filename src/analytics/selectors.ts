// ---------------------------------------------------------------------------
// Pure analytics functions over AppData. No React here — just data → numbers.
// Pages and charts consume these so the maths lives in exactly one place.
// ---------------------------------------------------------------------------
import type {
  AppData, Match, Player, PlayerMatchStat, PositionGroup, VideoClip, VideoTag,
} from '../types'
import { resultOf } from '../utils/format'

export interface TeamSeasonStats {
  played: number
  wins: number
  draws: number
  losses: number
  goalsFor: number
  goalsAgainst: number
  goalDiff: number
  points: number
  ppg: number
  cleanSheets: number
  failedToScore: number
  avgPossession: number
  xgFor: number
  xgAgainst: number
  winRate: number
}

export function teamSeasonStats(matches: Match[]): TeamSeasonStats {
  const played = matches.length
  let wins = 0, draws = 0, losses = 0, gf = 0, ga = 0, cs = 0, fts = 0
  let poss = 0, xgF = 0, xgA = 0
  for (const m of matches) {
    const r = resultOf(m.goalsFor, m.goalsAgainst)
    if (r === 'W') wins++
    else if (r === 'D') draws++
    else losses++
    gf += m.goalsFor
    ga += m.goalsAgainst
    if (m.goalsAgainst === 0) cs++
    if (m.goalsFor === 0) fts++
    poss += m.possession
    xgF += m.xgFor
    xgA += m.xgAgainst
  }
  const points = wins * 3 + draws
  return {
    played, wins, draws, losses,
    goalsFor: gf, goalsAgainst: ga, goalDiff: gf - ga,
    points,
    ppg: played ? points / played : 0,
    cleanSheets: cs,
    failedToScore: fts,
    avgPossession: played ? poss / played : 0,
    xgFor: Math.round(xgF * 10) / 10,
    xgAgainst: Math.round(xgA * 10) / 10,
    winRate: played ? (wins / played) * 100 : 0,
  }
}

/** Chronologically-sorted matches (oldest first). */
export function chronological(matches: Match[]): Match[] {
  return [...matches].sort((a, b) => a.date.localeCompare(b.date))
}

/** Most-recent-first. */
export function recent(matches: Match[]): Match[] {
  return [...matches].sort((a, b) => b.date.localeCompare(a.date))
}

/** Form over the last n matches, oldest→newest, as result letters. */
export function form(matches: Match[], n = 5): ('W' | 'D' | 'L')[] {
  return chronological(matches)
    .slice(-n)
    .map((m) => resultOf(m.goalsFor, m.goalsAgainst))
}

export interface PointsPoint {
  matchId: string
  label: string
  index: number
  points: number
  cumulativePoints: number
  goalsFor: number
  goalsAgainst: number
  xgFor: number
  xgAgainst: number
}

export function pointsTimeline(matches: Match[]): PointsPoint[] {
  const chron = chronological(matches)
  let cum = 0
  return chron.map((m, i) => {
    const r = resultOf(m.goalsFor, m.goalsAgainst)
    const p = r === 'W' ? 3 : r === 'D' ? 1 : 0
    cum += p
    return {
      matchId: m.id,
      label: m.opponent,
      index: i,
      points: p,
      cumulativePoints: cum,
      goalsFor: m.goalsFor,
      goalsAgainst: m.goalsAgainst,
      xgFor: m.xgFor,
      xgAgainst: m.xgAgainst,
    }
  })
}

// ---------------------------------------------------------------------------
// Player aggregates
// ---------------------------------------------------------------------------
export interface PlayerAggregate {
  player: Player
  appearances: number
  starts: number
  minutes: number
  goals: number
  assists: number
  goalContributions: number
  shots: number
  shotsOnTarget: number
  keyPasses: number
  passes: number
  passesCompleted: number
  passAccuracy: number
  tackles: number
  interceptions: number
  duelsWon: number
  duelsTotal: number
  duelWinRate: number
  distanceKm: number
  xg: number
  yellowCards: number
  redCards: number
  avgRating: number
  goalsPer90: number
  assistsPer90: number
  contributionsPer90: number
}

function emptyAgg(player: Player): PlayerAggregate {
  return {
    player, appearances: 0, starts: 0, minutes: 0, goals: 0, assists: 0,
    goalContributions: 0, shots: 0, shotsOnTarget: 0, keyPasses: 0, passes: 0,
    passesCompleted: 0, passAccuracy: 0, tackles: 0, interceptions: 0, duelsWon: 0,
    duelsTotal: 0, duelWinRate: 0, distanceKm: 0, xg: 0, yellowCards: 0, redCards: 0,
    avgRating: 0, goalsPer90: 0, assistsPer90: 0, contributionsPer90: 0,
  }
}

export function playerAggregate(data: AppData, playerId: string): PlayerAggregate {
  const player = data.players.find((p) => p.id === playerId)
  if (!player) throw new Error(`Unknown player ${playerId}`)
  const agg = emptyAgg(player)
  let ratingSum = 0
  for (const m of data.matches) {
    const s = m.playerStats.find((ps) => ps.playerId === playerId)
    if (!s) continue
    agg.appearances++
    if (m.lineup.some((l) => l.playerId === playerId && l.isStarter)) agg.starts++
    agg.minutes += s.minutes
    agg.goals += s.goals
    agg.assists += s.assists
    agg.shots += s.shots
    agg.shotsOnTarget += s.shotsOnTarget
    agg.keyPasses += s.keyPasses
    agg.passes += s.passes
    agg.passesCompleted += s.passesCompleted
    agg.tackles += s.tackles
    agg.interceptions += s.interceptions
    agg.duelsWon += s.duelsWon
    agg.duelsTotal += s.duelsTotal
    agg.distanceKm += s.distanceKm
    agg.xg += s.xg
    agg.yellowCards += s.yellowCards
    agg.redCards += s.redCards
    ratingSum += s.rating
  }
  agg.goalContributions = agg.goals + agg.assists
  agg.passAccuracy = agg.passes ? (agg.passesCompleted / agg.passes) * 100 : 0
  agg.duelWinRate = agg.duelsTotal ? (agg.duelsWon / agg.duelsTotal) * 100 : 0
  agg.avgRating = agg.appearances ? ratingSum / agg.appearances : 0
  agg.xg = Math.round(agg.xg * 10) / 10
  agg.distanceKm = Math.round(agg.distanceKm * 10) / 10
  const factor = agg.minutes ? 90 / agg.minutes : 0
  agg.goalsPer90 = agg.goals * factor
  agg.assistsPer90 = agg.assists * factor
  agg.contributionsPer90 = agg.goalContributions * factor
  return agg
}

export function allPlayerAggregates(data: AppData): PlayerAggregate[] {
  return data.players.map((p) => playerAggregate(data, p.id))
}

export interface PlayerMatchLogEntry {
  match: Match
  stat: PlayerMatchStat
}

export function playerMatchLog(data: AppData, playerId: string): PlayerMatchLogEntry[] {
  const out: PlayerMatchLogEntry[] = []
  for (const m of chronological(data.matches)) {
    const stat = m.playerStats.find((ps) => ps.playerId === playerId)
    if (stat) out.push({ match: m, stat })
  }
  return out
}

// ---------------------------------------------------------------------------
// Leaderboards
// ---------------------------------------------------------------------------
export type LeaderboardMetric =
  | 'goals' | 'assists' | 'goalContributions' | 'avgRating'
  | 'minutes' | 'tackles' | 'interceptions' | 'keyPasses' | 'xg'
  | 'distanceKm' | 'passAccuracy' | 'duelWinRate'

export function leaderboard(
  data: AppData,
  metric: LeaderboardMetric,
  limit = 5,
  minAppearances = 1,
): PlayerAggregate[] {
  return allPlayerAggregates(data)
    .filter((a) => a.appearances >= minAppearances)
    .sort((a, b) => (b[metric] as number) - (a[metric] as number))
    .slice(0, limit)
}

// ---------------------------------------------------------------------------
// Squad composition
// ---------------------------------------------------------------------------
export function squadByGroup(players: Player[]): Record<PositionGroup, Player[]> {
  const out: Record<PositionGroup, Player[]> = { GK: [], DEF: [], MID: [], FWD: [] }
  for (const p of players) out[p.positionGroup].push(p)
  for (const g of Object.keys(out) as PositionGroup[]) {
    out[g].sort((a, b) => a.number - b.number)
  }
  return out
}

export interface FormationUse {
  formation: string
  count: number
  wins: number
  draws: number
  losses: number
  ppg: number
}

export function formationUsage(matches: Match[]): FormationUse[] {
  const map = new Map<string, FormationUse>()
  for (const m of matches) {
    const f = map.get(m.formation) ?? { formation: m.formation, count: 0, wins: 0, draws: 0, losses: 0, ppg: 0 }
    f.count++
    const r = resultOf(m.goalsFor, m.goalsAgainst)
    if (r === 'W') f.wins++
    else if (r === 'D') f.draws++
    else f.losses++
    map.set(m.formation, f)
  }
  const arr = [...map.values()]
  for (const f of arr) f.ppg = f.count ? (f.wins * 3 + f.draws) / f.count : 0
  return arr.sort((a, b) => b.count - a.count)
}

/** Goals scored bucketed into 15-minute intervals across all matches. */
export function goalsByInterval(matches: Match[]): { bucket: string; for: number; against: number }[] {
  const buckets = ['0-15', '16-30', '31-45', '46-60', '61-75', '76-90']
  const result = buckets.map((b) => ({ bucket: b, for: 0, against: 0 }))
  const idx = (minute: number) => Math.min(5, Math.floor((minute - 1) / 15))
  for (const m of matches) {
    for (const e of m.events) {
      if (e.type === 'goal') result[idx(e.minute)].for++
    }
    // approximate goals against spread evenly is not stored per-minute; skip.
  }
  return result
}

/** Aggregate touch heatmap samples for a player across all matches. */
export function playerTouches(data: AppData, playerId: string): { x: number; y: number }[] {
  const out: { x: number; y: number }[] = []
  for (const m of data.matches) {
    const s = m.playerStats.find((ps) => ps.playerId === playerId)
    if (s) out.push(...s.touches)
  }
  return out
}

/** Average rating per appearance over time (chronological) for a player. */
export function playerRatingTrend(data: AppData, playerId: string): { label: string; rating: number }[] {
  return playerMatchLog(data, playerId).map((e) => ({
    label: e.match.opponent,
    rating: e.stat.rating,
  }))
}

// ---------------------------------------------------------------------------
// Individual player analysis: percentile ranking vs position peers
// ---------------------------------------------------------------------------
export interface PercentileRow {
  key: string
  label: string
  /** Raw metric value. */
  value: number
  /** Pre-formatted value for display. */
  display: string
  /** 0..100 percentile within the position-peer group. */
  percentile: number
}

export interface PlayerPercentiles {
  group: PositionGroup
  /** Number of peers in the comparison set (including this player). */
  peers: number
  rows: PercentileRow[]
}

interface MetricDef {
  key: string
  label: string
  get: (a: PlayerAggregate) => number
  fmt: (v: number) => string
}

const p90 = (total: number, minutes: number) => (minutes ? (total / minutes) * 90 : 0)
const d2 = (v: number) => (Math.round(v * 100) / 100).toFixed(2)
const d1 = (v: number) => (Math.round(v * 10) / 10).toFixed(1)
const pctStr = (v: number) => `${Math.round(v)}%`

const PERCENTILE_METRICS: MetricDef[] = [
  { key: 'goalsP90', label: 'Goals /90', get: (a) => a.goalsPer90, fmt: d2 },
  { key: 'assistsP90', label: 'Assists /90', get: (a) => a.assistsPer90, fmt: d2 },
  { key: 'gaP90', label: 'G+A /90', get: (a) => a.contributionsPer90, fmt: d2 },
  { key: 'xgP90', label: 'xG /90', get: (a) => p90(a.xg, a.minutes), fmt: d2 },
  { key: 'shotsP90', label: 'Shots /90', get: (a) => p90(a.shots, a.minutes), fmt: d1 },
  { key: 'keyP90', label: 'Key passes /90', get: (a) => p90(a.keyPasses, a.minutes), fmt: d1 },
  { key: 'passAcc', label: 'Pass accuracy', get: (a) => a.passAccuracy, fmt: pctStr },
  { key: 'tackP90', label: 'Tackles /90', get: (a) => p90(a.tackles, a.minutes), fmt: d1 },
  { key: 'intP90', label: 'Interceptions /90', get: (a) => p90(a.interceptions, a.minutes), fmt: d1 },
  { key: 'duels', label: 'Duels won', get: (a) => a.duelWinRate, fmt: pctStr },
  { key: 'distP90', label: 'Distance /90 (km)', get: (a) => p90(a.distanceKm, a.minutes), fmt: d1 },
  { key: 'rating', label: 'Avg rating', get: (a) => a.avgRating, fmt: d1 },
]

/**
 * Rank a player against same-position peers (who have played) on a basket of
 * per-90 and rate metrics. Percentile = share of peers at or below the value.
 */
export function playerPercentiles(data: AppData, playerId: string): PlayerPercentiles {
  const player = data.players.find((p) => p.id === playerId)
  if (!player) throw new Error(`Unknown player ${playerId}`)
  const peerAggs = allPlayerAggregates(data).filter(
    (a) => a.player.positionGroup === player.positionGroup && a.minutes > 0,
  )
  const me = peerAggs.find((a) => a.player.id === playerId)
  const rows: PercentileRow[] = PERCENTILE_METRICS.map((m) => {
    const mine = me ? m.get(me) : 0
    const values = peerAggs.map((a) => m.get(a))
    const atOrBelow = values.filter((v) => v <= mine + 1e-9).length
    const percentile = peerAggs.length ? (atOrBelow / peerAggs.length) * 100 : 0
    return { key: m.key, label: m.label, value: mine, display: m.fmt(mine), percentile: Math.round(percentile) }
  })
  return { group: player.positionGroup, peers: peerAggs.length, rows }
}

// ---------------------------------------------------------------------------
// Player film: every tagged video moment featuring a player
// ---------------------------------------------------------------------------
export interface PlayerMoment {
  video: VideoClip
  tag: VideoTag
}

export function playerVideoMoments(data: AppData, playerId: string): PlayerMoment[] {
  const out: PlayerMoment[] = []
  for (const v of data.videos) {
    for (const t of v.tags) {
      if (t.playerId === playerId) out.push({ video: v, tag: t })
    }
  }
  return out.sort((a, b) => a.video.title.localeCompare(b.video.title) || a.tag.time - b.tag.time)
}

// ---------------------------------------------------------------------------
// Opposition scouting
// ---------------------------------------------------------------------------

/** All opponent names we know about (matches, footage, saved reports). */
export function opponentNames(data: AppData): string[] {
  const set = new Set<string>()
  for (const m of data.matches) set.add(m.opponent)
  for (const v of data.videos) if (v.opponent) set.add(v.opponent)
  for (const s of data.scouting) set.add(s.opponent)
  return [...set].sort((a, b) => a.localeCompare(b))
}

/** Opposition-tagged video moments, optionally limited to one opponent. */
export function oppositionMoments(data: AppData, opponent?: string): PlayerMoment[] {
  const out: PlayerMoment[] = []
  for (const v of data.videos) {
    if (opponent && v.opponent !== opponent) continue
    for (const t of v.tags) {
      if (t.team === 'them') out.push({ video: v, tag: t })
    }
  }
  return out.sort((a, b) => a.tag.time - b.tag.time)
}

export interface HeadToHead {
  opponent: string
  played: number
  wins: number
  draws: number
  losses: number
  goalsFor: number
  goalsAgainst: number
  matches: Match[]
}

export function headToHead(data: AppData, opponent: string): HeadToHead {
  const ms = data.matches.filter((m) => m.opponent === opponent)
  let wins = 0, draws = 0, losses = 0, gf = 0, ga = 0
  for (const m of ms) {
    const r = resultOf(m.goalsFor, m.goalsAgainst)
    if (r === 'W') wins++
    else if (r === 'D') draws++
    else losses++
    gf += m.goalsFor
    ga += m.goalsAgainst
  }
  return { opponent, played: ms.length, wins, draws, losses, goalsFor: gf, goalsAgainst: ga, matches: recent(ms) }
}

/** Mean of the six attribute axes (an overall rating proxy). */
export function overallRating(player: Player): number {
  const a = player.attributes
  const vals = [a.pace, a.shooting, a.passing, a.dribbling, a.defending, a.physical]
  // Weight defending lightly for attackers and shooting lightly for defenders
  // would be nicer, but a simple mean keeps it transparent.
  return Math.round(vals.reduce((s, v) => s + v, 0) / vals.length)
}
