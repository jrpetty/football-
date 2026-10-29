// What really happened in an Age of Empires IV match, from AoE4World's game
// summary (built from the game's own replay summary), compared with what the
// coach read. The summary gives, for each player: when every villager was made
// and lost (so the exact villager count at any time), when each age was
// reached, and the stockpile of each resource every 20 seconds.
//
// Format: GET https://aoe4world.com/players/<profile>/games/<game>/summary?camelize=true
// (players[].buildOrder[].{icon, pbgid, finished[], destroyed[]}, players[].actions.feudalAge[],
// players[].resources.{timestamps[], food[], wood[], gold[], stone[]}). Read defensively: anything
// unexpected makes the summary unusable rather than misread.

import type { ReadingLog } from './readingLog.ts'

export interface SummaryPlayer {
  readonly profileId: number
  readonly name: string
  readonly civilization: string
  readonly result: string | null
  /** Age reached: feudal, castle, imperial → game seconds. */
  readonly ages: Readonly<Record<string, number>>
  /** Villagers made and lost (game seconds). */
  readonly villagersMade: readonly number[]
  readonly villagersLost: readonly number[]
  /** Stockpiles every 20 s. */
  readonly resources: { readonly t: readonly number[]; readonly food: readonly number[]; readonly wood: readonly number[]; readonly gold: readonly number[]; readonly stone: readonly number[] } | null
  /** What was built, trained and researched, and when (game seconds; the first 20 minutes). */
  readonly build: readonly BuildEvent[]
}

/** One thing in a real build order: a building completed, a unit trained, a technology or an age reached. */
export interface BuildEvent {
  readonly t: number
  readonly kind: 'building' | 'unit' | 'upgrade' | 'age'
  /** The game's own name for it (the last part of its icon), e.g. "barracks", "horseman_2"; ages: "feudal". */
  readonly item: string
}

/** Build events kept: the first 20 minutes, at most this many of one thing, at most this many in all. */
const BUILD_UNTIL_SEC = 1200
const BUILD_PER_ITEM = 6
const BUILD_MAX = 300

export interface GameSummary {
  readonly gameId: number
  readonly mapName: string
  readonly durationSec: number
  /** Unix seconds. */
  readonly startedAt: number
  readonly finishedAt: number | null
  readonly players: readonly SummaryPlayer[]
}

const nums = (v: unknown): number[] | null => (Array.isArray(v) && v.every((x) => typeof x === 'number' && Number.isFinite(x)) ? (v as number[]) : null)

/** A villager in the build order: its icon is a villager (every civilisation's, including variants). */
function isVillager(item: Record<string, unknown>): boolean {
  const icon = typeof item.icon === 'string' ? item.icon : ''
  const last = icon.split('/').pop() ?? ''
  return item.type === 'Unit' && /(^|_)villager$/.test(last)
}

export function parseSummary(raw: unknown): GameSummary | null {
  const o = raw as Record<string, unknown> | null
  if (!o || typeof o !== 'object' || !Array.isArray(o.players) || typeof o.gameId !== 'number') return null
  const players: SummaryPlayer[] = []
  for (const p of o.players as Record<string, unknown>[]) {
    if (!p || typeof p !== 'object' || typeof p.profileId !== 'number' || !Array.isArray(p.buildOrder)) return null
    const made: number[] = []
    const lost: number[] = []
    for (const item of p.buildOrder as Record<string, unknown>[]) {
      if (!item || typeof item !== 'object' || !isVillager(item)) continue
      const f = nums(item.finished)
      const d = nums(item.destroyed ?? [])
      if (!f || !d) return null
      made.push(...f)
      lost.push(...d)
    }
    const actions = (p.actions && typeof p.actions === 'object' ? p.actions : {}) as Record<string, unknown>
    const ages: Record<string, number> = {}
    for (const [age, key] of [
      ['feudal', 'feudalAge'],
      ['castle', 'castleAge'],
      ['imperial', 'imperialAge'],
    ] as const) {
      const t = nums(actions[key])
      if (t?.length) ages[age] = t[0]
    }
    // The build order: buildings by when they were completed, units by when they were trained (villagers too).
    const build: BuildEvent[] = []
    for (const item of p.buildOrder as Record<string, unknown>[]) {
      // Villagers are counted separately (villagersMade).
      if (!item || typeof item !== 'object' || typeof item.icon !== 'string' || isVillager(item)) continue
      const slug = (item.icon.split('/').pop() ?? '').slice(0, 60)
      const kind = item.type === 'Building' ? 'building' : item.type === 'Unit' ? 'unit' : item.type === 'Upgrade' ? 'upgrade' : null
      if (!kind || !slug) continue
      const times = nums(kind === 'building' ? (Array.isArray(item.constructed) && item.constructed.length ? item.constructed : item.finished) : item.finished)
      if (!times) continue
      // Buildings and units there from the start (time 0) are not part of the build.
      for (const t of times.filter((x) => x > 0 && x <= BUILD_UNTIL_SEC).slice(0, BUILD_PER_ITEM)) build.push({ t, kind, item: slug })
    }
    for (const [age, sec] of Object.entries(ages)) if (sec <= BUILD_UNTIL_SEC) build.push({ t: sec, kind: 'age', item: age })
    build.sort((a, b) => a.t - b.t || a.item.localeCompare(b.item))
    const r = p.resources as Record<string, unknown> | undefined
    const t = nums(r?.timestamps)
    const series = t && (['food', 'wood', 'gold', 'stone'] as const).map((k) => nums(r?.[k]))
    const resources =
      t && series && series.every((s) => s && s.length === t.length) ? { t, food: series[0]!, wood: series[1]!, gold: series[2]!, stone: series[3]! } : null
    players.push({
      profileId: p.profileId as number,
      name: typeof p.name === 'string' ? p.name.slice(0, 60) : '',
      civilization: typeof p.civilization === 'string' ? p.civilization : '',
      result: typeof p.result === 'string' ? p.result : null,
      ages,
      villagersMade: made.sort((a, b) => a - b),
      villagersLost: lost.sort((a, b) => a - b),
      resources,
      build: build.slice(0, BUILD_MAX),
    })
  }
  return {
    gameId: o.gameId,
    mapName: typeof o.mapName === 'string' ? o.mapName : '',
    durationSec: typeof o.duration === 'number' ? o.duration : 0,
    startedAt: typeof o.startedAt === 'number' ? o.startedAt : 0,
    finishedAt: typeof o.finishedAt === 'number' ? o.finishedAt : null,
    players,
  }
}

/** Villagers alive at a game time: made by then, minus lost by then. */
export function villagersAt(p: SummaryPlayer, sec: number): number {
  const count = (xs: readonly number[]) => xs.filter((x) => x <= sec).length
  return count(p.villagersMade) - count(p.villagersLost)
}

/**
 * Which of a player's recent games is the one the coach watched: the one that finished closest
 * to the coach's end of match (within 20 minutes), with a similar length when both are known.
 */
export function pickGame(games: readonly { gameId: number; finishedAt: number | null; durationSec: number }[], endedAt: Date, durationSec: number): number | null {
  const end = endedAt.getTime() / 1000
  let best: { id: number; score: number } | null = null
  for (const g of games) {
    if (g.finishedAt === null) continue
    const gap = Math.abs(g.finishedAt - end)
    if (gap > 20 * 60) continue
    const score = gap + (durationSec > 120 && g.durationSec ? Math.abs(g.durationSec - durationSec) / 2 : 0)
    if (!best || score < best.score) best = { id: g.gameId, score }
  }
  return best?.id ?? null
}

/** A player's recent games from GET /api/v0/players/<id>/games (only what pickGame needs). */
export function parseGames(raw: unknown): { gameId: number; finishedAt: number | null; durationSec: number }[] {
  const games = (raw as { games?: unknown } | null)?.games
  if (!Array.isArray(games)) return []
  return games.flatMap((g: Record<string, unknown>) => {
    if (!g || typeof g.game_id !== 'number') return []
    const updated = typeof g.updated_at === 'string' ? Date.parse(g.updated_at) / 1000 : NaN
    const started = typeof g.started_at === 'string' ? Date.parse(g.started_at) / 1000 : NaN
    const duration = typeof g.duration === 'number' ? g.duration : 0
    const finishedAt = Number.isFinite(started) && duration ? started + duration : Number.isFinite(updated) ? updated : null
    return [{ gameId: g.game_id, finishedAt, durationSec: duration }]
  })
}

// --- The coach against the real game -----------------------------------------------------------

export interface TruthLine {
  /** What was compared, e.g. "Villagers at 10:00". */
  readonly what: string
  readonly coach: number | string | null
  readonly real: number | string
  /** Close enough (villagers within 1, a resource within 10% or 30, an age-up within 30 s). */
  readonly ok: boolean | null
}

export interface TruthReport {
  readonly source: 'aoe4world' | 'player'
  readonly checkedAt: string
  readonly gameId?: number
  /** Checkpoint by checkpoint (the numbers the coach spoke about). */
  readonly lines: readonly TruthLine[]
  /** Every logged reading against the truth at that time: key → [right, compared]. */
  readonly readings: Readonly<Record<string, readonly [number, number]>>
}

const clock = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.round(sec % 60)).padStart(2, '0')}`

/** Compare what the coach read in a match with the summary of the real game. */
export function compareWithSummary(
  coach: { readonly benchmarks?: Readonly<Record<string, readonly { readonly atSec: number; readonly actual: number }[]>>; readonly readings?: ReadingLog },
  player: SummaryPlayer,
  gameId: number,
  now = new Date(),
): TruthReport {
  const lines: TruthLine[] = []
  const villagerPoints = Object.values(coach.benchmarks ?? {}).flat()
  for (const p of villagerPoints) {
    const real = villagersAt(player, p.atSec)
    lines.push({ what: `Villagers at ${clock(p.atSec)}`, coach: p.actual, real, ok: Math.abs(p.actual - real) <= 1 })
  }
  // The coach's spoken checkpoints it had no agreed reading for, with the real numbers.
  for (const sec of [300, 600, 900, 1200]) {
    if (villagerPoints.some((p) => p.atSec === sec)) continue
    if (player.villagersMade.length && (player.resources?.t.at(-1) ?? Infinity) >= sec) lines.push({ what: `Villagers at ${clock(sec)}`, coach: null, real: villagersAt(player, sec), ok: null })
  }
  const changes = coach.readings?.changes ?? []
  for (const [age, sec] of Object.entries(player.ages)) {
    const seen = changes.find(([, k, v]) => k === 'age' && v === age)
    lines.push({ what: `${age[0].toUpperCase()}${age.slice(1)} Age reached`, coach: seen ? clock(seen[0]) : null, real: clock(sec), ok: seen ? Math.abs(seen[0] - sec) <= 30 : null })
  }
  const readings: Record<string, [number, number]> = {}
  const log = coach.readings
  if (log) {
    log.t.forEach((t, i) => {
      const check = (key: string, real: number | undefined, ok: (a: number, b: number) => boolean) => {
        const v = log.values[key]?.[i]
        if (v === null || v === undefined || real === undefined) return
        const r = (readings[key] ??= [0, 0])
        r[1]++
        if (ok(v, real)) r[0]++
      }
      check('villagers', player.villagersMade.length ? villagersAt(player, t) : undefined, (a, b) => Math.abs(a - b) <= 1)
      const res = player.resources
      const j = res ? res.t.indexOf(t) : -1
      if (res && j >= 0) for (const k of ['food', 'wood', 'gold', 'stone'] as const) check(k, res[k][j], (a, b) => Math.abs(a - b) <= Math.max(30, b * 0.1))
    })
  }
  return { source: 'aoe4world', checkedAt: now.toISOString(), gameId, lines, readings }
}

/** The player's own numbers (from the post-game statistics) against the coach's checkpoints. */
export function compareWithPlayer(coach: { readonly benchmarks?: Readonly<Record<string, readonly { readonly atSec: number; readonly actual: number }[]>> }, real: Readonly<Record<number, number>>, now = new Date()): TruthReport {
  const points = Object.values(coach.benchmarks ?? {}).flat()
  const lines: TruthLine[] = Object.entries(real).map(([sec, value]) => {
    const p = points.find((x) => x.atSec === Number(sec))
    return { what: `Villagers at ${clock(Number(sec))}`, coach: p?.actual ?? null, real: value, ok: p ? Math.abs(p.actual - value) <= 1 : null }
  })
  return { source: 'player', checkedAt: now.toISOString(), lines, readings: {} }
}

export function isTruthReport(v: unknown): v is TruthReport {
  const o = v as TruthReport | null
  return (
    !!o &&
    (o.source === 'aoe4world' || o.source === 'player') &&
    typeof o.checkedAt === 'string' &&
    Array.isArray(o.lines) &&
    o.lines.length <= 40 &&
    o.lines.every((l) => l && typeof l.what === 'string' && l.what.length <= 60 && (l.ok === null || typeof l.ok === 'boolean')) &&
    typeof o.readings === 'object' &&
    o.readings !== null
  )
}
