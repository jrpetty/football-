// ---------------------------------------------------------------------------
// Ingest: raw feeds -> one normalised match/player corpus in .cache.
//
// Run: node scripts/ingest.ts
//
// The corpus is the single input to ratings, predictions and the backtest, so
// every source quirk is resolved here and nowhere downstream.
// ---------------------------------------------------------------------------

import { fetchFplSeason, fetchOpenFootballSeason, XG_ERA_START } from './sources/mirror.ts'
import { fetchLiveSeason } from './sources/live.ts'
import { writeJson, cachePath } from './lib/fsjson.ts'
import type { NormMatch, NormPlayer, NormPlayerGw } from './sources/types.ts'

/** Every season the corpus covers, oldest first. */
export const SEASONS = [
  '2014-15', '2015-16', '2016-17', '2017-18', '2018-19', '2019-20', '2020-21',
  '2021-22', '2022-23', '2023-24', '2024-25', '2025-26', '2026-27',
]

export const CURRENT_SEASON = '2026-27'

export interface Corpus {
  currentSeason: string
  generatedAt: number
  matches: NormMatch[]
  /** Current-season player aggregates (includes live injury status). */
  players: NormPlayer[]
  /** Per-gameweek player lines across the xG era. */
  playerGws: NormPlayerGw[]
  /** Previous-season player aggregates, for cold-start player rates. */
  priorPlayers: NormPlayer[]
  /** Championship results, used to build promoted-side priors. */
  championship: NormMatch[]
}

function key(m: NormMatch): string {
  return `${m.season}|${m.home}|${m.away}`
}

export type SourceMode = 'auto' | 'live' | 'mirror'

/**
 * Overlay live data onto the mirror's richer per-player detail.
 *
 * The mirror carries expected goals and per-gameweek rows the live endpoints
 * do not expose; the live feed carries fresher results and today's injury
 * flags. Taking the best of each means a Tuesday run reflects the weekend even
 * if the mirror has not refreshed yet, without losing xG.
 */
function overlayLive(mirror: Awaited<ReturnType<typeof fetchFplSeason>>, live: Awaited<ReturnType<typeof fetchLiveSeason>>) {
  if (!mirror) return live
  if (!live) return mirror

  const liveByFixture = new Map(live.matches.map((m) => [m.fixtureId ?? -1, m]))
  const matches = mirror.matches.map((m) => {
    const hit = liveByFixture.get(m.fixtureId ?? -1)
    if (!hit || !hit.finished) return m
    // Live wins on results; the mirror keeps supplying xG.
    return { ...m, homeGoals: hit.homeGoals, awayGoals: hit.awayGoals, finished: true }
  })

  // Availability is the most perishable field on the whole feed, so the live
  // view of it always wins.
  const liveById = new Map(live.players.map((p) => [p.id, p]))
  const players = mirror.players.map((p) => {
    const hit = liveById.get(p.id)
    return hit ? { ...hit, xg: p.xg || hit.xg, xa: p.xa || hit.xa } : p
  })
  // Players the mirror has not seen yet (new signings) still need to appear.
  for (const p of live.players) if (!mirror.players.some((m) => m.id === p.id)) players.push(p)

  return { ...mirror, matches, players }
}

export async function ingest(source: SourceMode = 'auto'): Promise<Corpus> {
  const matches: NormMatch[] = []
  const playerGws: NormPlayerGw[] = []
  let players: NormPlayer[] = []
  let priorPlayers: NormPlayer[] = []
  const championship: NormMatch[] = []

  for (const season of SEASONS) {
    const isXgEra = season >= XG_ERA_START
    const of = await fetchOpenFootballSeason(season, CURRENT_SEASON, '1')
    let fpl = isXgEra ? await fetchFplSeason(season, CURRENT_SEASON) : null

    // Only the current season is worth fetching live; earlier ones are settled.
    if (season === CURRENT_SEASON && source !== 'mirror') {
      const live = await fetchLiveSeason(season)
      if (live) fpl = overlayLive(fpl, live)
      else if (source === 'live') {
        throw new Error('live source was requested but the FPL API did not return usable data')
      }
    }

    // openfootball is the spine for played seasons; the FPL feed supplies xG
    // and is the only source for a season that has not started yet.
    let seasonMatches: NormMatch[] = of
    if (fpl) {
      const byKey = new Map(fpl.matches.map((m) => [key(m), m]))
      if (of.length > 0) {
        seasonMatches = of.map((m) => {
          const hit = byKey.get(key(m))
          if (!hit) return m
          // openfootball can lag the end of a season (its 2025-26 file was
          // missing 27 results the FPL feed already had). Take the result from
          // whichever source has one, so late-season matches are not silently
          // dropped from the training set.
          const haveResult = m.homeGoals !== null
          return {
            ...m,
            homeGoals: haveResult ? m.homeGoals : hit.homeGoals,
            awayGoals: haveResult ? m.awayGoals : hit.awayGoals,
            finished: m.finished || hit.finished,
            homeXg: hit.homeXg,
            awayXg: hit.awayXg,
            fixtureId: hit.fixtureId,
            round: hit.round || m.round,
          }
        })
      } else {
        seasonMatches = fpl.matches
      }
      playerGws.push(...fpl.playerGws)
      if (season === CURRENT_SEASON) players = fpl.players
      else priorPlayers = fpl.players
    }
    matches.push(...seasonMatches)

    const champ = await fetchOpenFootballSeason(season, CURRENT_SEASON, '2')
    championship.push(...champ)

    const played = seasonMatches.filter((m) => m.finished).length
    const withXg = seasonMatches.filter((m) => m.homeXg !== null).length
    console.log(
      `  ${season}: ${String(seasonMatches.length).padStart(3)} matches ` +
        `(${String(played).padStart(3)} played, ${String(withXg).padStart(3)} with xG), ` +
        `${String(champ.length).padStart(3)} championship`,
    )
  }

  matches.sort((a, b) => a.date - b.date)
  playerGws.sort((a, b) => a.date - b.date)

  return {
    currentSeason: CURRENT_SEASON,
    generatedAt: Date.now(),
    matches,
    players,
    playerGws,
    priorPlayers,
    championship,
  }
}

/** Load the cached corpus, ingesting first if it is missing. */
export async function loadCorpus(): Promise<Corpus> {
  const { readJson } = await import('./lib/fsjson.ts')
  const cached = await readJson<Corpus>(cachePath('corpus.json'))
  if (cached) return cached
  const corpus = await ingest()
  await writeJson(cachePath('corpus.json'), corpus)
  return corpus
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop() ?? '')
if (isMain) {
  const arg = process.argv.indexOf('--source')
  const source = (arg >= 0 ? process.argv[arg + 1] : 'auto') as SourceMode
  console.log(`Ingesting Premier League data (source: ${source})...`)
  const corpus = await ingest(source)
  await writeJson(cachePath('corpus.json'), corpus)
  const played = corpus.matches.filter((m) => m.finished).length
  console.log(
    `\nCorpus: ${corpus.matches.length} matches (${played} played), ` +
      `${corpus.players.length} current players, ${corpus.playerGws.length} player-gameweeks, ` +
      `${corpus.championship.length} championship matches`,
  )
}
