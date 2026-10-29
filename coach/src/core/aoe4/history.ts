// The player's Age of Empires IV games from AoE4World: every public Ranked and
// Quick Match game, including the ones played without the coach. The games
// list gives the civilisations, map, result and rating; for recent games, the
// game's summary adds the facts (villagers at 5, 10 and 15 minutes, age-up
// times, the build, and the opponent's main units). From these: trends, win
// rates by civilisation, matchup and map, and a weekly summary.
//
// Nothing here is read from the screen: it is the game's own record, as
// AoE4World publishes it. Summaries are fetched slowly and kept, so each one is
// asked for once (AoE4World asks clients not to crawl them).

import { villagersAt, type BuildEvent, type GameSummary } from '../truth/aoe4world.ts'

export const HISTORY_SCHEMA = 1
/** Games kept (the most recent). */
export const MAX_GAMES = 400
/** Summaries fetched in one sync at most (each is one request to AoE4World). */
export const SUMMARIES_PER_SYNC = 30
/** Villager counts kept per game (game seconds). */
export const VILLAGER_TIMES = [180, 300, 480, 600, 900] as const

export interface HistoryGame {
  readonly gameId: number
  /** Unix seconds. */
  readonly startedAt: number
  readonly durationSec: number
  readonly map: string
  /** AoE4World's game kind, e.g. "rm_1v1", "qm_2v2". */
  readonly kind: string
  /** The player's civilisation, as the pack names it ("holy-roman-empire"). */
  readonly civ: string
  /** The other team's civilisations. */
  readonly opponents: readonly string[]
  readonly result: 'win' | 'loss' | null
  readonly rating: number | null
}

/** What the game's summary adds for the player. */
export interface GameFacts {
  readonly gameId: number
  /** Villagers alive at 3, 5, 8, 10 and 15 minutes (only times the game lasted). */
  readonly villagers: Readonly<Record<string, number>>
  /** feudal / castle / imperial → game seconds. */
  readonly ages: Readonly<Record<string, number>>
  /** The player's build: buildings, units and technologies with their times (first 20 minutes). */
  readonly build: readonly BuildEvent[]
  /** In a 1v1: the opponent's most-made military units (icon names), most first. */
  readonly opponentArmy: readonly { readonly item: string; readonly count: number }[]
}

export interface Aoe4History {
  readonly schema: typeof HISTORY_SCHEMA
  readonly profileId: number
  /** ISO time of the last sync with AoE4World. */
  readonly syncedAt: string | null
  readonly games: readonly HistoryGame[]
  /** gameId → facts. */
  readonly facts: Readonly<Record<string, GameFacts>>
  /** Games AoE4World had no summary for (not asked again). */
  readonly noSummary: readonly number[]
}

export const emptyHistory = (profileId: number): Aoe4History => ({ schema: HISTORY_SCHEMA, profileId, syncedAt: null, games: [], facts: {}, noSummary: [] })

const civId = (s: unknown): string => (typeof s === 'string' ? s.toLowerCase().replace(/_/g, '-').slice(0, 40) : '')

/**
 * The player's games from GET /api/v0/players/<id>/games. Both shapes AoE4World uses are read:
 * teams[][i].{profile_id, civilization, result, rating} and teams[][i].player.{…}.
 */
export function parseGameList(raw: unknown, profileId: number): HistoryGame[] {
  const games = (raw as { games?: unknown } | null)?.games
  if (!Array.isArray(games)) return []
  return games.flatMap((g: Record<string, unknown>) => {
    if (!g || typeof g.game_id !== 'number' || !Array.isArray(g.teams) || g.ongoing === true) return []
    const teams = (g.teams as unknown[]).map((t) => (Array.isArray(t) ? t : []).map((x) => ((x as { player?: unknown })?.player ?? x) as Record<string, unknown>))
    const mine = teams.findIndex((t) => t.some((p) => p?.profile_id === profileId))
    if (mine < 0) return []
    const me = teams[mine].find((p) => p?.profile_id === profileId)!
    const started = typeof g.started_at === 'string' ? Math.round(Date.parse(g.started_at) / 1000) : NaN
    if (!Number.isFinite(started)) return []
    const kind = typeof g.kind === 'string' ? g.kind : typeof g.leaderboard === 'string' ? g.leaderboard : ''
    return [
      {
        gameId: g.game_id,
        startedAt: started,
        durationSec: typeof g.duration === 'number' ? g.duration : 0,
        map: typeof g.map === 'string' ? g.map.slice(0, 60) : '',
        kind: kind.slice(0, 20),
        civ: civId(me.civilization),
        opponents: teams.flatMap((t, i) => (i === mine ? [] : t.map((p) => civId(p?.civilization)).filter(Boolean))),
        result: me.result === 'win' ? 'win' : me.result === 'loss' ? 'loss' : null,
        rating: typeof me.rating === 'number' ? me.rating : null,
      },
    ]
  })
}

const MILITARY_SKIP = /villager|scout|fishing|trade|monk|imam|prelate|shaman|priest|dervish|scholar|ship|boat|transport/

/** The facts of one game for the player, from its summary; null when the player is not in it. */
export function factsFromSummary(summary: GameSummary, profileId: number): GameFacts | null {
  const me = summary.players.find((p) => p.profileId === profileId)
  if (!me) return null
  const villagers: Record<string, number> = {}
  if (me.villagersMade.length) for (const sec of VILLAGER_TIMES) if (summary.durationSec >= sec) villagers[sec] = villagersAt(me, sec)
  const others = summary.players.filter((p) => p.profileId !== profileId)
  let opponentArmy: { item: string; count: number }[] = []
  if (others.length === 1) {
    const counts = new Map<string, number>()
    for (const e of others[0].build) if (e.kind === 'unit' && !MILITARY_SKIP.test(e.item)) counts.set(e.item, (counts.get(e.item) ?? 0) + 1)
    opponentArmy = [...counts].map(([item, count]) => ({ item, count })).sort((a, b) => b.count - a.count).slice(0, 3)
  }
  return { gameId: summary.gameId, villagers, ages: me.ages, build: me.build, opponentArmy }
}

/** Adds newly listed games (newest first, without repeats), keeping the most recent MAX_GAMES. */
export function mergeGames(h: Aoe4History, games: readonly HistoryGame[]): Aoe4History {
  const byId = new Map(h.games.map((g) => [g.gameId, g]))
  for (const g of games) byId.set(g.gameId, g)
  const all = [...byId.values()].sort((a, b) => b.startedAt - a.startedAt).slice(0, MAX_GAMES)
  const keep = new Set(all.map((g) => String(g.gameId)))
  return { ...h, games: all, facts: Object.fromEntries(Object.entries(h.facts).filter(([id]) => keep.has(id))) }
}

/** Games whose summary is worth fetching: the most recent ones without facts, long enough to have a build. */
export function wantSummaries(h: Aoe4History, max = SUMMARIES_PER_SYNC): number[] {
  const skip = new Set(h.noSummary)
  return h.games.filter((g) => !h.facts[g.gameId] && !skip.has(g.gameId) && g.durationSec >= 300).slice(0, max).map((g) => g.gameId)
}

export function addFacts(h: Aoe4History, facts: readonly GameFacts[], missing: readonly number[]): Aoe4History {
  return { ...h, facts: { ...h.facts, ...Object.fromEntries(facts.map((f) => [String(f.gameId), f])) }, noSummary: [...new Set([...h.noSummary, ...missing])].slice(-MAX_GAMES) }
}

export function isAoe4History(v: unknown): v is Aoe4History {
  const o = v as Aoe4History | null
  return (
    !!o &&
    o.schema === HISTORY_SCHEMA &&
    typeof o.profileId === 'number' &&
    Array.isArray(o.games) &&
    o.games.length <= MAX_GAMES &&
    o.games.every((g) => g && typeof g.gameId === 'number' && typeof g.startedAt === 'number' && typeof g.civ === 'string' && Array.isArray(g.opponents)) &&
    typeof o.facts === 'object' &&
    o.facts !== null &&
    Object.values(o.facts).every((f) => f && typeof f.gameId === 'number' && typeof f.villagers === 'object' && typeof f.ages === 'object' && Array.isArray(f.build) && f.build.length <= 400) &&
    Array.isArray(o.noSummary)
  )
}

// --- What the history says ----------------------------------------------------------------

const median = (xs: readonly number[]): number | null => {
  if (!xs.length) return null
  const s = [...xs].sort((a, b) => a - b)
  const m = Math.floor(s.length / 2)
  return s.length % 2 ? s[m] : Math.round((s[m - 1] + s[m]) / 2)
}

export const clock = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.round(sec % 60)).padStart(2, '0')}`

export interface TrendPoint {
  readonly gameId: number
  readonly startedAt: number
  readonly value: number
  readonly civ: string
  readonly result: 'win' | 'loss' | null
}

export interface Trend {
  readonly id: 'villagers-10' | 'feudal' | 'castle'
  readonly label: string
  readonly unit: 'villagers' | 'time'
  /** Lower is better for age-up times. */
  readonly better: 'higher' | 'lower'
  /** Oldest first. */
  readonly points: readonly TrendPoint[]
  /** Median of the last 10, and of the 10 before. */
  readonly recent: number | null
  readonly before: number | null
}

/** Villagers at 10:00 and age-up times, game by game (games with a summary), optionally for one civilisation. */
export function trends(h: Aoe4History, civ: string | null = null): Trend[] {
  const games = [...h.games].reverse().filter((g) => !civ || g.civ === civ)
  const series = (pick: (f: GameFacts) => number | undefined): TrendPoint[] =>
    games.flatMap((g) => {
      const f = h.facts[g.gameId]
      const v = f ? pick(f) : undefined
      return typeof v === 'number' ? [{ gameId: g.gameId, startedAt: g.startedAt, value: v, civ: g.civ, result: g.result }] : []
    })
  const make = (id: Trend['id'], label: string, unit: Trend['unit'], better: Trend['better'], points: TrendPoint[]): Trend => ({
    id,
    label,
    unit,
    better,
    points,
    recent: median(points.slice(-10).map((p) => p.value)),
    before: points.length > 10 ? median(points.slice(-20, -10).map((p) => p.value)) : null,
  })
  return [
    make('villagers-10', 'Villagers at 10:00', 'villagers', 'higher', series((f) => f.villagers[600])),
    make('feudal', 'Feudal Age reached', 'time', 'lower', series((f) => f.ages.feudal)),
    make('castle', 'Castle Age reached', 'time', 'lower', series((f) => f.ages.castle)),
  ]
}

export interface WinRateRow {
  readonly key: string
  readonly games: number
  readonly wins: number
}

/** Wins and games by your civilisation, the opponent's civilisation (1v1) or the map. */
export function winRates(h: Aoe4History, by: 'civ' | 'opponent' | 'map', sinceUnix = 0): WinRateRow[] {
  const rows = new Map<string, { games: number; wins: number }>()
  for (const g of h.games) {
    if (g.startedAt < sinceUnix || !g.result) continue
    const keys = by === 'civ' ? [g.civ] : by === 'map' ? [g.map] : g.opponents.length === 1 ? g.opponents : []
    for (const k of keys.filter(Boolean)) {
      const r = rows.get(k) ?? { games: 0, wins: 0 }
      r.games++
      if (g.result === 'win') r.wins++
      rows.set(k, r)
    }
  }
  return [...rows].map(([key, r]) => ({ key, ...r })).sort((a, b) => b.games - a.games || a.key.localeCompare(b.key))
}

const DAY = 86_400

export interface WeeklySummary {
  /** Games in the last 7 days. */
  readonly games: number
  readonly wins: number
  readonly lines: readonly string[]
}

/**
 * The last 7 days against the 4 weeks before them. Each line is said only with enough games on both
 * sides (2 or more), and as a plain comparison of medians, with the number of games.
 */
export function weeklySummary(h: Aoe4History, nowUnix: number, names: { civ: (id: string) => string; unit: (item: string) => string }): WeeklySummary {
  const week = h.games.filter((g) => g.startedAt >= nowUnix - 7 * DAY)
  const before = h.games.filter((g) => g.startedAt < nowUnix - 7 * DAY && g.startedAt >= nowUnix - 35 * DAY)
  const lines: string[] = []
  const decided = (gs: readonly HistoryGame[]) => gs.filter((g) => g.result)
  const wins = week.filter((g) => g.result === 'win').length
  if (week.length) {
    const w = decided(week)
    const b = decided(before)
    const rate = (gs: readonly HistoryGame[]) => Math.round((gs.filter((g) => g.result === 'win').length / gs.length) * 100)
    lines.push(`${week.length} ${week.length === 1 ? 'game' : 'games'} this week, ${wins} won${w.length && b.length >= 3 ? ` (${rate(w)}%, against ${rate(b)}% in the 4 weeks before)` : ''}.`)
  }
  const factsOf = (gs: readonly HistoryGame[]) => gs.flatMap((g) => (h.facts[g.gameId] ? [h.facts[g.gameId]] : []))
  const fw = factsOf(week)
  const fb = factsOf(before)
  const compare = (label: string, pick: (f: GameFacts) => number | undefined, unit: 'time' | 'villagers') => {
    const a = fw.map(pick).filter((x): x is number => typeof x === 'number')
    const b = fb.map(pick).filter((x): x is number => typeof x === 'number')
    if (a.length < 2 || b.length < 2) return
    const ma = median(a)!
    const mb = median(b)!
    const n = `${a.length} games this week, ${b.length} before`
    if (unit === 'time') {
      const d = ma - mb
      if (Math.abs(d) < 10) lines.push(`${label} about the same as the 4 weeks before (${clock(ma)}; ${n}).`)
      else lines.push(`${label} ${Math.abs(d)} s ${d < 0 ? 'faster' : 'slower'} than the 4 weeks before (${clock(ma)} against ${clock(mb)}; ${n}).`)
    } else {
      const d = ma - mb
      if (Math.abs(d) < 2) lines.push(`${label}: ${ma}, about the same as the 4 weeks before (${n}).`)
      else lines.push(`${label}: ${ma}, ${Math.abs(d)} ${d > 0 ? 'more' : 'fewer'} than the 4 weeks before (${mb}; ${n}).`)
    }
  }
  compare('Feudal Age', (f) => f.ages.feudal, 'time')
  compare('Castle Age', (f) => f.ages.castle, 'time')
  compare('Villagers at 10:00', (f) => f.villagers[600], 'villagers')
  // The matchup that goes worst over the 5 weeks (3 or more games, won less than 40%), with what they made.
  const recent = [...week, ...before]
  const rows = winRates({ ...h, games: recent }, 'opponent')
  const worst = rows.filter((r) => r.games >= 3 && r.wins / r.games < 0.4).sort((a, b) => a.wins / a.games - b.wins / b.games)[0]
  if (worst) {
    const army = new Map<string, number>()
    for (const g of recent) {
      if (g.opponents.length !== 1 || g.opponents[0] !== worst.key || g.result !== 'loss') continue
      for (const u of h.facts[g.gameId]?.opponentArmy ?? []) army.set(u.item, (army.get(u.item) ?? 0) + u.count)
    }
    const top = [...army].sort((a, b) => b[1] - a[1]).slice(0, 2).map(([item]) => names.unit(item))
    lines.push(`Losing to ${names.civ(worst.key)}: ${worst.wins} of ${worst.games} won in the last 5 weeks${top.length ? `; in the losses they made mostly ${top.join(' and ')}` : ''}.`)
  }
  return { games: week.length, wins, lines }
}
