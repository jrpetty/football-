// ---------------------------------------------------------------------------
// Live Fantasy Premier League API adapter.
//
// The mirror this project reads from is itself a dump of these endpoints, so
// the field names line up and both paths can feed the identical downstream
// code. Going direct just gets the data sooner — the mirror lags a gameweek
// refresh by however long its maintainer's job takes.
//
// IMPORTANT: this path could not be exercised from the development sandbox,
// whose egress policy blocks fantasy.premierleague.com. It is therefore
// treated as untrusted: `fetchLiveSeason` validates the shape of what comes
// back and returns null on anything suspicious, so the caller falls back to
// the mirror rather than publishing garbage. A quiet fallback is a far better
// failure than a confidently wrong gameweek.
// ---------------------------------------------------------------------------

import { fetchJson } from '../lib/http.ts'
import { tryNormalizeTeam } from '../../src/config/teams.ts'
import { parseFixtureStats } from '../lib/pyrepr.ts'
import type { NormMatch, NormPlayer, NormSeason, PositionCode } from './types.ts'

const API = 'https://fantasy.premierleague.com/api'

interface BootstrapTeam { id: number; name: string; short_name: string }
interface BootstrapElement {
  id: number; web_name: string; first_name: string; second_name: string
  team: number; element_type: number; minutes: number; starts: number
  goals_scored: number; assists: number; yellow_cards: number; red_cards: number
  saves: number; clean_sheets: number; status: string; news: string
  chance_of_playing_next_round: number | null; now_cost: number
  expected_goals: string | number; expected_assists: string | number
  expected_goals_conceded: string | number
}
interface Bootstrap { teams: BootstrapTeam[]; elements: BootstrapElement[] }
interface LiveFixture {
  id: number; event: number | null; kickoff_time: string | null; finished: boolean
  team_h: number; team_a: number; team_h_score: number | null; team_a_score: number | null
  stats?: unknown
}

const POSITION: Record<number, PositionCode> = { 1: 'GK', 2: 'DEF', 3: 'MID', 4: 'FWD', 5: 'MID' }

const n = (v: string | number | null | undefined): number => {
  const x = typeof v === 'string' ? Number(v) : v
  return Number.isFinite(x) ? (x as number) : 0
}

/**
 * Pull the current season live. Returns null — rather than throwing — whenever
 * the response fails a sanity check, so the caller can fall back cleanly.
 */
export async function fetchLiveSeason(season: string): Promise<NormSeason | null> {
  let bootstrap: Bootstrap | null
  let fixtures: LiveFixture[] | null
  try {
    // Short TTL: this is the whole point of going live.
    bootstrap = await fetchJson<Bootstrap>(`${API}/bootstrap-static/`, { ttlMs: 10 * 60 * 1000, retries: 2 })
    fixtures = await fetchJson<LiveFixture[]>(`${API}/fixtures/`, { ttlMs: 10 * 60 * 1000, retries: 2 })
  } catch (err) {
    console.warn(`  ! live FPL API unreachable (${String(err)}); falling back to the mirror`)
    return null
  }

  // --- Shape validation. Anything unexpected means fall back. ---
  if (!bootstrap || !Array.isArray(bootstrap.teams) || !Array.isArray(bootstrap.elements)) {
    console.warn('  ! live bootstrap had an unexpected shape; falling back to the mirror')
    return null
  }
  if (!fixtures || !Array.isArray(fixtures)) {
    console.warn('  ! live fixtures had an unexpected shape; falling back to the mirror')
    return null
  }
  if (bootstrap.teams.length !== 20) {
    console.warn(`  ! live feed reported ${bootstrap.teams.length} teams, expected 20; falling back`)
    return null
  }
  if (fixtures.length < 300) {
    console.warn(`  ! live feed reported only ${fixtures.length} fixtures; falling back`)
    return null
  }
  if (bootstrap.elements.length < 300) {
    console.warn(`  ! live feed reported only ${bootstrap.elements.length} players; falling back`)
    return null
  }

  const teamsById: Record<number, string> = {}
  for (const t of bootstrap.teams) {
    const code = tryNormalizeTeam(t.name) ?? tryNormalizeTeam(t.short_name)
    if (code) teamsById[t.id] = code
  }
  if (Object.keys(teamsById).length !== 20) {
    console.warn('  ! could not resolve all 20 clubs from the live feed; falling back')
    return null
  }

  const players: NormPlayer[] = bootstrap.elements
    .filter((e) => teamsById[e.team])
    .map((e) => ({
      id: e.id,
      name: e.web_name,
      fullName: `${e.first_name} ${e.second_name}`.trim(),
      team: teamsById[e.team]!,
      position: POSITION[e.element_type] ?? 'MID',
      minutes: n(e.minutes),
      starts: n(e.starts),
      goals: n(e.goals_scored),
      assists: n(e.assists),
      yellowCards: n(e.yellow_cards),
      redCards: n(e.red_cards),
      saves: n(e.saves),
      cleanSheets: n(e.clean_sheets),
      xg: n(e.expected_goals),
      xa: n(e.expected_assists),
      xgConceded: n(e.expected_goals_conceded),
      status: e.status ?? 'a',
      news: e.news ?? '',
      chanceNextRound: e.chance_of_playing_next_round,
      cost: n(e.now_cost) / 10,
    }))

  const matches: NormMatch[] = []
  for (const f of fixtures) {
    const home = teamsById[f.team_h]
    const away = teamsById[f.team_a]
    if (!home || !away) continue
    matches.push({
      season,
      round: f.event ?? 0,
      date: f.kickoff_time ? new Date(f.kickoff_time).getTime() : 0,
      home,
      away,
      homeGoals: f.finished ? n(f.team_h_score) : null,
      awayGoals: f.finished ? n(f.team_a_score) : null,
      // Team xG is only derivable from per-player rows, which the live
      // endpoints do not expose in aggregate; the mirror supplies it.
      homeXg: null,
      awayXg: null,
      finished: Boolean(f.finished),
      fixtureId: f.id,
    })
  }

  // Per-fixture events are present on finished fixtures and carry the cards.
  let cardEvents = 0
  for (const f of fixtures) {
    if (!f.finished || f.stats === undefined) continue
    const blocks = parseFixtureStats(typeof f.stats === 'string' ? f.stats : JSON.stringify(f.stats))
    cardEvents += blocks.filter((b) => b.identifier === 'yellow_cards').length
  }

  console.log(
    `  live FPL: ${Object.keys(teamsById).length} clubs, ${players.length} players, ` +
      `${matches.length} fixtures (${matches.filter((m) => m.finished).length} played, ${cardEvents} with card data)`,
  )

  return { season, teamsById, matches, players, playerGws: [], hasXg: false }
}
