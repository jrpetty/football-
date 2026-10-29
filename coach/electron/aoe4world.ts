// AoE4World, after a match, when the player asked for it (Settings in
// History → "Check against the real game"): the player's recent games, then the
// summary of the one the coach watched. Only aoe4world.com, only https, only
// public match data (the player's match history must be public there), never
// during play. Nothing about the player is sent beyond their AoE4World profile
// number, which is in the address.

import { parseGames, parseSummary, pickGame, type SummaryPlayer } from '../src/core/truth/aoe4world.ts'

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
