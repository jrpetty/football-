// ---------------------------------------------------------------------------
// Source adapters.
//
// Two feeds, chosen to sidestep a decade of schema drift rather than fight it:
//
//   openfootball  -> match results 2014-15 onward. Club names appear directly,
//                    so no id mapping is needed and the long goals history is
//                    reliable all the way back.
//   FPL mirror    -> player data and team xG. Only used from 2022-23, which is
//                    exactly when the feed gained `team`, `position` and the
//                    expected-goals columns. Before that the per-gameweek rows
//                    cannot even be attributed to a club without a teams.csv
//                    that does not exist for those seasons.
//
// Splitting them this way means neither source is asked for something it
// cannot supply cleanly.
// ---------------------------------------------------------------------------

import { fetchText } from '../lib/http.ts'
import { parseCsv, num, bool, type CsvRecord } from '../lib/csv.ts'
import { parseFixtureStats } from '../lib/pyrepr.ts'
import { normalizeTeam, tryNormalizeTeam } from '../../src/config/teams.ts'
import type { NormMatch, NormPlayer, NormPlayerGw, NormSeason, PositionCode } from './types.ts'

const FPL_MIRROR = 'https://raw.githubusercontent.com/vaastav/Fantasy-Premier-League/master/data'
const OPENFOOTBALL = 'https://raw.githubusercontent.com/openfootball/football.json/master'

/** Seasons whose FPL feed carries team attribution and expected goals. */
export const XG_ERA_START = '2022-23'

/** Historical results are immutable; the live season needs a short TTL. */
function ttlFor(season: string, currentSeason: string): number {
  return season === currentSeason ? 30 * 60 * 1000 : Infinity
}

// --- openfootball -----------------------------------------------------------

interface OfMatch {
  round?: string
  date: string
  time?: string
  team1: string
  team2: string
  score?: { ft?: [number, number]; ht?: [number, number] }
}

/** Match results for one season. Returns [] when the season has no file yet. */
export async function fetchOpenFootballSeason(
  season: string,
  currentSeason: string,
  division: '1' | '2' = '1',
): Promise<NormMatch[]> {
  const url = `${OPENFOOTBALL}/${season}/en.${division}.json`
  const text = await fetchText(url, { ttlMs: ttlFor(season, currentSeason) })
  if (text === null) return []
  const parsed = JSON.parse(text) as { matches?: OfMatch[] }
  const out: NormMatch[] = []
  for (const m of parsed.matches ?? []) {
    // Championship files carry clubs we deliberately do not model; skip rather
    // than throw, but a missing Premier League club is a real error.
    const home = division === '1' ? normalizeTeam(m.team1) : tryNormalizeTeam(m.team1)
    const away = division === '1' ? normalizeTeam(m.team2) : tryNormalizeTeam(m.team2)
    if (!home || !away) continue
    const ft = m.score?.ft
    const roundMatch = /(\d+)/.exec(m.round ?? '')
    out.push({
      season,
      round: roundMatch ? Number(roundMatch[1]) : 0,
      date: new Date(`${m.date}T${m.time ?? '15:00'}:00Z`).getTime(),
      home,
      away,
      homeGoals: ft ? ft[0] : null,
      awayGoals: ft ? ft[1] : null,
      homeXg: null,
      awayXg: null,
      finished: Boolean(ft),
    })
  }
  return out
}

// --- FPL mirror -------------------------------------------------------------

const POSITION_BY_ELEMENT_TYPE: Record<number, PositionCode> = {
  1: 'GK',
  2: 'DEF',
  3: 'MID',
  4: 'FWD',
  // Managers became a selectable "position" in 2025-26; they never play, and
  // treating them as outfielders would pollute every per-90 rate.
  5: 'MID',
}

/**
 * A CSV response that is plausibly CSV.
 *
 * A source having a bad day can answer 200 with an empty body, an HTML error
 * or maintenance page, or a truncated file. Any of those parse to zero rows
 * and read downstream as "this season has no players", which is a far worse
 * outcome than a failed fetch: it is silent, it looks like data, and for the
 * archive seasons — cached indefinitely and now carried between CI runs — it
 * would persist. Rejecting it here turns it back into a retry.
 */
export function looksLikeCsv(text: string): boolean {
  const trimmed = text.trim()
  if (trimmed.length === 0) return false
  if (trimmed.startsWith('<')) return false // HTML error or maintenance page
  const firstLine = trimmed.slice(0, trimmed.indexOf('\n') + 1 || undefined)
  // A header row and at least one comma: every file this reads is multi-column.
  return firstLine.includes(',') && trimmed.includes('\n')
}

async function fetchCsv(url: string, ttlMs: number): Promise<CsvRecord[] | null> {
  const text = await fetchText(url, { ttlMs, accept: looksLikeCsv })
  return text === null ? null : parseCsv(text)
}

/**
 * Pull one season of FPL data: team map, fixtures (with team xG summed from
 * player rows), player aggregates and per-gameweek lines.
 */
export async function fetchFplSeason(season: string, currentSeason: string): Promise<NormSeason | null> {
  const ttl = ttlFor(season, currentSeason)
  const teamRows = await fetchCsv(`${FPL_MIRROR}/${season}/teams.csv`, ttl)
  if (!teamRows) return null

  const teamsById: Record<number, string> = {}
  for (const r of teamRows) {
    const id = num(r, 'id')
    const code = tryNormalizeTeam(r['name'] ?? '')
    if (id && code) teamsById[id] = code
  }

  const playerRows = (await fetchCsv(`${FPL_MIRROR}/${season}/players_raw.csv`, ttl)) ?? []
  const players: NormPlayer[] = []
  const teamByElement = new Map<number, string>()
  for (const r of playerRows) {
    const id = num(r, 'id')
    const teamCode = teamsById[num(r, 'team')]
    if (!id || !teamCode) continue
    teamByElement.set(id, teamCode)
    const chanceRaw = r['chance_of_playing_next_round']
    players.push({
      id,
      name: r['web_name'] ?? '',
      fullName: `${r['first_name'] ?? ''} ${r['second_name'] ?? ''}`.trim(),
      team: teamCode,
      position: POSITION_BY_ELEMENT_TYPE[num(r, 'element_type')] ?? 'MID',
      minutes: num(r, 'minutes'),
      starts: num(r, 'starts'),
      goals: num(r, 'goals_scored'),
      assists: num(r, 'assists'),
      yellowCards: num(r, 'yellow_cards'),
      redCards: num(r, 'red_cards'),
      saves: num(r, 'saves'),
      cleanSheets: num(r, 'clean_sheets'),
      xg: num(r, 'expected_goals'),
      xa: num(r, 'expected_assists'),
      xgConceded: num(r, 'expected_goals_conceded'),
      status: r['status'] ?? 'a',
      news: r['news'] ?? '',
      chanceNextRound: chanceRaw === undefined || chanceRaw === '' ? null : Number(chanceRaw),
      cost: num(r, 'now_cost') / 10,
      // The feed writes the string "None" rather than leaving it blank.
      joinedAt: r['team_join_date'] && r['team_join_date'] !== 'None' ? r['team_join_date'] : null,
    })
  }

  // Per-gameweek player lines. Absent for a season that has not kicked off.
  const gwRows = (await fetchCsv(`${FPL_MIRROR}/${season}/gws/merged_gw.csv`, ttl)) ?? []
  const hasXg = gwRows.length > 0 && gwRows[0]!['expected_goals'] !== undefined
  const positionByElement = new Map(players.map((p) => [p.id, p.position]))
  const playerGws: NormPlayerGw[] = []
  for (const r of gwRows) {
    const element = num(r, 'element')
    // `team` only exists from 2022-23; fall back to the season roster.
    const teamCode = r['team'] ? tryNormalizeTeam(r['team']) : (teamByElement.get(element) ?? null)
    const opponent = teamsById[num(r, 'opponent_team')]
    if (!teamCode || !opponent) continue
    playerGws.push({
      season,
      element,
      round: num(r, 'GW', num(r, 'round')),
      fixtureId: num(r, 'fixture'),
      team: teamCode,
      opponent,
      wasHome: bool(r, 'was_home'),
      // `position` only exists from 2022-23; fall back to the season roster.
      position:
        (r['position'] as PositionCode | undefined) ??
        positionByElement.get(element) ??
        'MID',
      minutes: num(r, 'minutes'),
      starts: num(r, 'starts'),
      goals: num(r, 'goals_scored'),
      assists: num(r, 'assists'),
      yellowCards: num(r, 'yellow_cards'),
      redCards: num(r, 'red_cards'),
      saves: num(r, 'saves'),
      goalsConceded: num(r, 'goals_conceded'),
      xg: num(r, 'expected_goals'),
      xa: num(r, 'expected_assists'),
      xgConceded: num(r, 'expected_goals_conceded'),
      date: new Date(r['kickoff_time'] ?? '').getTime() || 0,
    })
  }

  // Team xG per fixture, summed from the player rows on each side.
  const xgByFixture = new Map<number, { home: number; away: number }>()
  if (hasXg) {
    for (const g of playerGws) {
      let e = xgByFixture.get(g.fixtureId)
      if (!e) {
        e = { home: 0, away: 0 }
        xgByFixture.set(g.fixtureId, e)
      }
      if (g.wasHome) e.home += g.xg
      else e.away += g.xg
    }
  }

  const fixtureRows = (await fetchCsv(`${FPL_MIRROR}/${season}/fixtures.csv`, ttl)) ?? []
  const matches: NormMatch[] = []
  for (const r of fixtureRows) {
    const id = num(r, 'id')
    const home = teamsById[num(r, 'team_h')]
    const away = teamsById[num(r, 'team_a')]
    if (!home || !away) continue
    const finished = bool(r, 'finished')
    const xg = xgByFixture.get(id)
    matches.push({
      season,
      round: num(r, 'event'),
      date: new Date(r['kickoff_time'] ?? '').getTime() || 0,
      home,
      away,
      homeGoals: finished ? num(r, 'team_h_score') : null,
      awayGoals: finished ? num(r, 'team_a_score') : null,
      homeXg: finished && xg ? xg.home : null,
      awayXg: finished && xg ? xg.away : null,
      finished,
      fixtureId: id,
    })
  }

  return { season, teamsById, matches, players, playerGws, hasXg }
}

/**
 * Per-fixture card and goal attribution, read from the fixtures `stats` blob.
 *
 * The per-gameweek rows already carry card counts, but this keeps the
 * fixture-level view (who was booked in which match) that the suspension
 * tracker needs when a player's gameweek row is missing.
 */
export async function fetchFixtureEvents(
  season: string,
  currentSeason: string,
): Promise<Map<number, Map<string, Map<number, number>>>> {
  const rows = (await fetchCsv(`${FPL_MIRROR}/${season}/fixtures.csv`, ttlFor(season, currentSeason))) ?? []
  const out = new Map<number, Map<string, Map<number, number>>>()
  for (const r of rows) {
    const id = num(r, 'id')
    const blocks = parseFixtureStats(r['stats'] ?? '')
    if (blocks.length === 0) continue
    const byIdentifier = new Map<string, Map<number, number>>()
    for (const b of blocks) {
      const perPlayer = new Map<number, number>()
      for (const e of [...b.h, ...b.a]) {
        perPlayer.set(e.element, (perPlayer.get(e.element) ?? 0) + e.value)
      }
      byIdentifier.set(b.identifier, perPlayer)
    }
    out.set(id, byIdentifier)
  }
  return out
}
