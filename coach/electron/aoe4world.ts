// AoE4World, after a match, when the player asked for it (Settings in
// History → "Check against the real game"): the player's recent games, then the
// summary of the one the coach watched. Only aoe4world.com, only https, only
// public match data (the player's match history must be public there), never
// during play. Nothing about the player is sent beyond their AoE4World profile
// number, which is in the address.

import { parseGames, parseSummary, pickGame, type SummaryPlayer } from '../src/core/truth/aoe4world.ts'
import { factsFromSummary, parseGameList, type GameFacts, type HistoryGame } from '../src/core/aoe4/history.ts'

const BASE = 'https://aoe4world.com'
const TIMEOUT_MS = 20_000
const MAX_BYTES = 8 * 1024 * 1024
// AoE4World turns away clients whose name looks like a crawler ("fetch", "bot"…).
const HEADERS = { 'User-Agent': 'SightlineCoach/0.1 (desktop coaching app)', Accept: 'application/json' }

export type SummaryResult = { ok: true; gameId: number; player: SummaryPlayer } | { ok: false; error: string }

async function getJson(path: string): Promise<{ status: number; json: unknown }> {
  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), TIMEOUT_MS)
  try {
    const res = await fetch(`${BASE}${path}`, { headers: HEADERS, signal: ctrl.signal, redirect: 'follow' })
    if (!res.url.startsWith(`${BASE}/`)) return { status: 0, json: null }
    const text = await res.text()
    if (text.length > MAX_BYTES) return { status: 0, json: null }
    let json: unknown = null
    try {
      json = JSON.parse(text)
    } catch {
      json = null
    }
    return { status: res.status, json }
  } finally {
    clearTimeout(t)
  }
}

const statusError = (status: number) =>
  status === 429
    ? 'AoE4World is busy. Try again in a minute.'
    : status === 401 || status === 403 || status === 404
      ? 'AoE4World has no summary for that game: your match history may not be public on AoE4World, or the summary is not ready yet (it can take a few minutes after the game).'
      : `AoE4World did not answer (${status || 'no connection'}).`

/** The summary of the player's game that ended closest to `endedAt`. */
export async function aoe4worldSummary(profileId: unknown, endedAt: unknown, durationSec: unknown): Promise<SummaryResult> {
  if (typeof profileId !== 'number' || !Number.isInteger(profileId) || profileId <= 0 || profileId > 1e10) return { ok: false, error: 'Set your AoE4World profile first.' }
  const end = typeof endedAt === 'string' ? new Date(endedAt) : null
  if (!end || Number.isNaN(end.getTime())) return { ok: false, error: 'Unknown match time.' }
  try {
    const list = await getJson(`/api/v0/players/${profileId}/games?limit=20`)
    if (list.status !== 200) return { ok: false, error: statusError(list.status) }
    const gameId = pickGame(parseGames(list.json), end, typeof durationSec === 'number' ? durationSec : 0)
    if (gameId === null) return { ok: false, error: 'No game on AoE4World ended near this match (only Ranked and Quick Match games are listed there).' }
    const sum = await getJson(`/players/${profileId}/games/${gameId}/summary?camelize=true`)
    if (sum.status !== 200) return { ok: false, error: statusError(sum.status) }
    const summary = parseSummary(sum.json)
    if (!summary) return { ok: false, error: "AoE4World's summary was not in the expected format, so it was not used." }
    const player = summary.players.find((p) => p.profileId === profileId)
    if (!player) return { ok: false, error: 'You are not in that game on AoE4World.' }
    return { ok: true, gameId, player }
  } catch (e) {
    return { ok: false, error: e instanceof Error && e.name === 'AbortError' ? 'AoE4World took too long to answer.' : 'Could not reach AoE4World.' }
  }
}

export interface FoundPlayer {
  readonly profileId: number
  readonly name: string
  readonly country: string | null
}

/** Players by name, to find one's own profile number. */
export async function aoe4worldSearch(query: unknown): Promise<{ ok: true; players: FoundPlayer[] } | { ok: false; error: string }> {
  if (typeof query !== 'string' || query.trim().length < 3 || query.length > 40) return { ok: false, error: 'Type at least 3 letters of your in-game name.' }
  try {
    const r = await getJson(`/api/v0/players/search?query=${encodeURIComponent(query.trim())}`)
    if (r.status !== 200) return { ok: false, error: statusError(r.status) }
    const players = (r.json as { players?: unknown } | null)?.players
    if (!Array.isArray(players)) return { ok: true, players: [] }
    return {
      ok: true,
      players: players
        .flatMap((p: Record<string, unknown>) =>
          p && typeof p.profile_id === 'number' && typeof p.name === 'string' ? [{ profileId: p.profile_id, name: p.name.slice(0, 60), country: typeof p.country === 'string' ? p.country.slice(0, 4) : null }] : [],
        )
        .slice(0, 10),
    }
  } catch {
    return { ok: false, error: 'Could not reach AoE4World.' }
  }
}

// --- The player's whole history, and builds from AoE4World games ------------------------------

/** Pause between summary requests: AoE4World asks clients not to crawl summaries. */
const SUMMARY_GAP_MS = 1500
const MAX_LIST_PAGES = 4
const MAX_SUMMARIES_PER_CALL = 10
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const validId = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v) && v > 0 && v < 1e10

/** The player's public games (up to 200, newest first): civilisations, map, result, rating. */
export async function aoe4worldGames(profileId: unknown): Promise<{ ok: true; games: HistoryGame[] } | { ok: false; error: string }> {
  if (!validId(profileId)) return { ok: false, error: 'Set your AoE4World profile first.' }
  const games: HistoryGame[] = []
  try {
    for (let page = 1; page <= MAX_LIST_PAGES; page++) {
      const r = await getJson(`/api/v0/players/${profileId}/games?limit=50&page=${page}`)
      if (r.status !== 200) {
        if (page === 1) return { ok: false, error: statusError(r.status) }
        break
      }
      const list = parseGameList(r.json, profileId)
      games.push(...list)
      const raw = (r.json as { games?: unknown[] } | null)?.games
      if (!Array.isArray(raw) || raw.length < 50) break
      await sleep(500)
    }
    return { ok: true, games }
  } catch (e) {
    return { ok: false, error: e instanceof Error && e.name === 'AbortError' ? 'AoE4World took too long to answer.' : 'Could not reach AoE4World.' }
  }
}

/**
 * The facts of some of the player's games, from their summaries: one request at a time, with a pause
 * between them, at most 10 per call. Stops early when AoE4World says it is busy.
 */
export async function aoe4worldFacts(profileId: unknown, gameIds: unknown): Promise<{ ok: true; facts: GameFacts[]; missing: number[]; busy: boolean } | { ok: false; error: string }> {
  if (!validId(profileId) || !Array.isArray(gameIds)) return { ok: false, error: 'Set your AoE4World profile first.' }
  const facts: GameFacts[] = []
  const missing: number[] = []
  try {
    for (const id of gameIds.filter(validId).slice(0, MAX_SUMMARIES_PER_CALL)) {
      const r = await getJson(`/players/${profileId}/games/${id}/summary?camelize=true`)
      if (r.status === 429) return { ok: true, facts, missing, busy: true }
      const summary = r.status === 200 ? parseSummary(r.json) : null
      const f = summary ? factsFromSummary(summary, profileId) : null
      if (f) facts.push(f)
      else if (r.status === 200 || r.status === 404) missing.push(id)
      await sleep(SUMMARY_GAP_MS)
    }
    return { ok: true, facts, missing, busy: false }
  } catch (e) {
    return facts.length ? { ok: true, facts, missing, busy: true } : { ok: false, error: e instanceof Error && e.name === 'AbortError' ? 'AoE4World took too long to answer.' : 'Could not reach AoE4World.' }
  }
}

export interface GamePlayerBuild {
  readonly profileId: number
  readonly name: string
  readonly civ: string
  readonly result: string | null
  readonly opponentCiv: string | null
  readonly facts: GameFacts
}

/** "https://aoe4world.com/players/123-name/games/456" (or just the two numbers) → ids. */
export function parseGameLink(link: string): { profileId: number; gameId: number } | null {
  const m = /players\/(\d{1,10})[^/]*\/games\/(\d{1,12})/.exec(link) ?? /^\s*(\d{1,10})\D+(\d{1,12})\s*$/.exec(link)
  return m ? { profileId: Number(m[1]), gameId: Number(m[2]) } : null
}

async function gameBuilds(profileId: number, gameId: number): Promise<{ ok: true; gameId: number; players: GamePlayerBuild[] } | { ok: false; error: string }> {
  const r = await getJson(`/players/${profileId}/games/${gameId}/summary?camelize=true`)
  if (r.status !== 200) return { ok: false, error: statusError(r.status) }
  const summary = parseSummary(r.json)
  if (!summary) return { ok: false, error: "AoE4World's summary was not in the expected format, so it was not used." }
  const players = summary.players.flatMap((p) => {
    const facts = factsFromSummary(summary, p.profileId)
    const others = summary.players.filter((q) => q.profileId !== p.profileId)
    return facts ? [{ profileId: p.profileId, name: p.name, civ: p.civilization.toLowerCase().replace(/_/g, '-'), result: p.result, opponentCiv: others.length === 1 ? others[0].civilization.toLowerCase().replace(/_/g, '-') : null, facts }] : []
  })
  return { ok: true, gameId, players }
}

/** The builds of the players in one AoE4World game, from its link. */
export async function aoe4worldGameBuild(link: unknown): Promise<{ ok: true; gameId: number; players: GamePlayerBuild[] } | { ok: false; error: string }> {
  const ids = typeof link === 'string' && link.length < 300 ? parseGameLink(link) : null
  if (!ids) return { ok: false, error: 'Paste the link of a game on AoE4World (…/players/…/games/…).' }
  try {
    return await gameBuilds(ids.profileId, ids.gameId)
  } catch {
    return { ok: false, error: 'Could not reach AoE4World.' }
  }
}

/**
 * A build to suggest for a civilisation: from AoE4World's list of recent Ranked 1v1 games (at most
 * three pages), the highest-rated win with that civilisation that lasted 12 minutes or more; then that
 * one game's summary.
 */
export async function aoe4worldSuggestBuild(civ: unknown): Promise<{ ok: true; gameId: number; rating: number | null; player: GamePlayerBuild } | { ok: false; error: string }> {
  if (typeof civ !== 'string' || !/^[a-z-]{2,40}$/.test(civ)) return { ok: false, error: 'Choose your civilisation first.' }
  const want = civ.replace(/-/g, '_')
  type Pick = { profileId: number; gameId: number; rating: number }
  let best = null as Pick | null
  try {
    for (let page = 1; page <= 3; page++) {
      const r = await getJson(`/api/v0/games?leaderboard=rm_1v1&order=started_at&page=${page}`)
      if (r.status !== 200) {
        if (page === 1) return { ok: false, error: statusError(r.status) }
        break
      }
      const games = (r.json as { games?: unknown[] } | null)?.games
      if (!Array.isArray(games)) break
      for (const g of games as Record<string, unknown>[]) {
        if (!g || typeof g.game_id !== 'number' || g.ongoing === true || typeof g.duration !== 'number' || g.duration < 720 || !Array.isArray(g.teams)) continue
        for (const t of g.teams as unknown[]) {
          for (const x of Array.isArray(t) ? t : []) {
            const p = ((x as { player?: unknown })?.player ?? x) as Record<string, unknown>
            if (p?.civilization !== want || p.result !== 'win' || !validId(p.profile_id)) continue
            const rating = typeof p.rating === 'number' ? p.rating : 0
            if (!best || rating > best.rating) best = { profileId: p.profile_id, gameId: g.game_id, rating } satisfies Pick
          }
        }
      }
      if ((best as Pick | null) && (best as Pick).rating >= 1400) break
      await sleep(500)
    }
    const pick = best as Pick | null
    if (!pick) return { ok: false, error: 'No recent Ranked win with that civilisation was found on AoE4World. Paste a game link instead.' }
    await sleep(500)
    const b = await gameBuilds(pick.profileId, pick.gameId)
    if (!b.ok) return b
    const player = b.players.find((p) => p.profileId === pick.profileId)
    if (!player) return { ok: false, error: 'That game had no build for the player.' }
    return { ok: true, gameId: pick.gameId, rating: pick.rating || null, player }
  } catch {
    return { ok: false, error: 'Could not reach AoE4World.' }
  }
}

/**
 * An opponent's public record, looked up by the player before a match: their last 50 games, and the
 * summaries of their last 3 one-against-one games (one request every 1.5 s).
 */
export async function aoe4worldOpponent(profileId: unknown): Promise<{ ok: true; games: HistoryGame[]; facts: GameFacts[] } | { ok: false; error: string }> {
  if (!validId(profileId)) return { ok: false, error: 'Choose the player first.' }
  try {
    const r = await getJson(`/api/v0/players/${profileId}/games?limit=50`)
    if (r.status !== 200) return { ok: false, error: statusError(r.status) }
    const games = parseGameList(r.json, profileId)
    const facts: GameFacts[] = []
    for (const g of games.filter((x) => x.opponents.length === 1 && x.durationSec >= 300).slice(0, 3)) {
      await sleep(SUMMARY_GAP_MS)
      const s = await getJson(`/players/${profileId}/games/${g.gameId}/summary?camelize=true`)
      if (s.status === 429) break
      const summary = s.status === 200 ? parseSummary(s.json) : null
      const f = summary ? factsFromSummary(summary, profileId) : null
      if (f) facts.push(f)
    }
    return { ok: true, games, facts }
  } catch {
    return { ok: false, error: 'Could not reach AoE4World.' }
  }
}
