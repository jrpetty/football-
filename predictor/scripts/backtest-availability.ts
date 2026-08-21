// ---------------------------------------------------------------------------
// Does the player-availability adjustment actually help?
//
// Run: node scripts/backtest-availability.ts [--season 2025-26]
//
// Until now this was the one part of the model taking a coefficient on faith.
// The effect size was measured (teams missing two of their three top attackers
// scored 1.146 against 1.520), but it had never been walk-forward tested,
// because doing that appeared to need archived injury reports the feed does
// not carry — and reconstructing who was fit from who actually played would
// leak the result straight into the forecast.
//
// There is an honest way round it. A player who did not feature in either of
// his club's previous two matches was, in the great majority of cases, already
// known to be unavailable before the next one. That is information a forecaster
// genuinely had at kickoff. It is a proxy, not a team sheet: it will call a
// rotated squad player "absent" too. But those players carry little minute
// share, so they barely move the multiplier — what the proxy mostly detects is
// a regular who has abruptly stopped playing, which is exactly the injury
// signal the adjustment is meant to capture.
//
// Strictly no future information is used: only matches before the one being
// predicted.
// ---------------------------------------------------------------------------

import { loadCorpus } from './ingest.ts'
import { fitModel, buildPlayerRates, DEFAULT_PARAMS, type ModelParams } from './build-model.ts'
import { scoreMatrix, outcomeProbs } from '../src/core/dixonColes.ts'
import { dispersionFromUncertainty } from '../src/core/predict.ts'
import { teamAvailability, type PlayerRates } from '../src/core/availability.ts'
import { outcomeOf, summarize, type ScoredPrediction } from '../src/core/metrics.ts'
import { restProfile } from '../src/core/congestion.ts'
import type { NormMatch, NormPlayerGw } from './sources/types.ts'

const REFIT_EVERY = 10
/** A player missing this many consecutive club matches counts as unavailable. */
const ABSENCE_WINDOW = 2

function seasonTeams(matches: readonly NormMatch[], season: string): string[] {
  const s = new Set<string>()
  for (const m of matches) if (m.season === season) { s.add(m.home); s.add(m.away) }
  return [...s].sort()
}

/**
 * Which players a forecaster would have known were unavailable.
 *
 * Built only from matches strictly before `asOf`.
 */
function knownAbsences(
  rows: readonly NormPlayerGw[],
  season: string,
  team: string,
  asOf: number,
): Set<number> {
  // The club's own recent matches, most recent first.
  const clubRows = rows.filter((g) => g.season === season && g.team === team && g.date < asOf)
  const fixtures = [...new Set(clubRows.map((g) => g.fixtureId))]
    .map((id) => ({ id, date: Math.max(...clubRows.filter((g) => g.fixtureId === id).map((g) => g.date)) }))
    .sort((a, b) => b.date - a.date)
    .slice(0, ABSENCE_WINDOW)

  if (fixtures.length < ABSENCE_WINDOW) return new Set()

  // Everyone who has appeared for the club this season.
  const squad = new Set(clubRows.filter((g) => g.minutes > 0).map((g) => g.element))
  const played = new Set<number>()
  for (const f of fixtures) {
    for (const g of clubRows) {
      if (g.fixtureId === f.id && g.minutes > 0) played.add(g.element)
    }
  }
  const absent = new Set<number>()
  for (const id of squad) if (!played.has(id)) absent.add(id)
  return absent
}

/** Apply the known absences to a squad, as unavailability. */
function withAbsences(squad: readonly PlayerRates[], absent: ReadonlySet<number>): PlayerRates[] {
  return squad.map((p) => (absent.has(p.id) ? { ...p, status: 'i', chanceNextRound: 0 } : p))
}

export interface DetailedScore extends ScoredPrediction {
  /** Largest availability hit on either side, 0 = full strength. */
  absenceSeverity: number
}

export async function run(
  season: string,
  strength: number,
  useAvailability: boolean,
  /** Apply the adjustment only when the hit exceeds this. 0 applies always. */
  threshold = 0,
): Promise<DetailedScore[]> {
  const corpus = await loadCorpus()
  const seasons = [...new Set(corpus.matches.map((m) => m.season))].sort()
  const prevSeason = seasons[seasons.indexOf(season) - 1] ?? ''
  const currentTeams = seasonTeams(corpus.matches, season)
  const previousTeams = seasonTeams(corpus.matches, prevSeason)
  const params: ModelParams = DEFAULT_PARAMS

  const test = corpus.matches
    .filter((m) => m.season === season && m.finished && m.homeGoals !== null)
    .sort((a, b) => a.date - b.date)

  const kickoffsByTeam = new Map<string, number[]>()
  for (const m of corpus.matches) {
    for (const t of [m.home, m.away]) {
      if (!kickoffsByTeam.has(t)) kickoffsByTeam.set(t, [])
      kickoffsByTeam.get(t)!.push(m.date)
    }
  }

  const scored: DetailedScore[] = []
  let model: ReturnType<typeof fitModel> | null = null
  let ratesByTeam = new Map<string, PlayerRates[]>()
  let lastFit = -1

  for (let i = 0; i < test.length; i++) {
    const m = test[i]!
    if (model === null || i - lastFit >= REFIT_EVERY) {
      model = fitModel(corpus.matches, corpus.championship, m.date, currentTeams, previousTeams, params)
      const rates = buildPlayerRates(corpus.players, corpus.playerGws, m.date, season)
      ratesByTeam = new Map()
      for (const r of rates) {
        if (!ratesByTeam.has(r.team)) ratesByTeam.set(r.team, [])
        ratesByTeam.get(r.team)!.push(r)
      }
      lastFit = i
    }

    let logHome = (model.ratings.attack[m.home] ?? 0) - (model.ratings.defence[m.away] ?? 0) + model.ratings.homeAdvantage
    let logAway = (model.ratings.attack[m.away] ?? 0) - (model.ratings.defence[m.home] ?? 0)

    logHome += restProfile(m.date, kickoffsByTeam.get(m.home) ?? []).logShift
    logAway += restProfile(m.date, kickoffsByTeam.get(m.away) ?? []).logShift

    const homeSquad = withAbsences(
      ratesByTeam.get(m.home) ?? [],
      knownAbsences(corpus.playerGws, season, m.home, m.date),
    )
    const awaySquad = withAbsences(
      ratesByTeam.get(m.away) ?? [],
      knownAbsences(corpus.playerGws, season, m.away, m.date),
    )

    // Severity is measured at a FIXED reference strength, never the one being
    // applied. Deriving it from the applied strength made the on and off runs
    // put the same match in different bands, so the sliced comparison was
    // between different sets of matches — which produced a flatly
    // contradictory read of the same data.
    const REFERENCE_STRENGTH = 1
    const absenceSeverity = Math.max(
      1 - teamAvailability(m.home, homeSquad, { strength: REFERENCE_STRENGTH }).attackMultiplier,
      1 - teamAvailability(m.away, awaySquad, { strength: REFERENCE_STRENGTH }).attackMultiplier,
      0,
    )

    if (useAvailability && absenceSeverity >= threshold) {
      const h = teamAvailability(m.home, homeSquad, { strength })
      const a = teamAvailability(m.away, awaySquad, { strength })
      logHome += h.logAttackShift + a.logDefenceShift
      logAway += a.logAttackShift + h.logDefenceShift
    }

    const sdHome = model.ratingSd[m.home] ?? 0.15
    const sdAway = model.ratingSd[m.away] ?? 0.15
    const sigma = Math.sqrt(sdHome * sdHome + sdAway * sdAway + 0.08 * 0.08)

    const matrix = scoreMatrix(
      Math.exp(logHome),
      Math.exp(logAway),
      model.ratings.rho,
      dispersionFromUncertainty(sigma),
    )
    scored.push({ probs: outcomeProbs(matrix), actual: outcomeOf(m.homeGoals!, m.awayGoals!), absenceSeverity })
  }
  return scored
}

const args = process.argv.slice(2)
const season = args.includes('--season') ? args[args.indexOf('--season') + 1]! : '2025-26'

console.log(`Availability backtest on ${season}`)
console.log("Absences inferred from who missed the club's previous two matches — known before kickoff.\n")

const off = await run(season, 0, false)
const base = summarize(off)
console.log(`  adjustment off                     RPS ${base.rps.toFixed(4)}  logloss ${base.logLoss.toFixed(4)}  acc ${(base.accuracy * 100).toFixed(1)}%  n=${base.n}`)

console.log('\nApplied to every match:')
for (const strength of [0.3, 0.7, 1.1]) {
  const s = summarize(await run(season, strength, true))
  console.log(
    `  strength ${strength.toFixed(1)}                       RPS ${s.rps.toFixed(4)}  ` +
      `${s.rps < base.rps ? 'better' : 'worse '} by ${Math.abs(s.rps - base.rps).toFixed(4)}`,
  )
}

// The league-wide figure is dominated by matches where nobody important is
// missing. The question that matters is whether it helps where it should.
console.log('\nSliced by how much of a side is actually missing:')
const onFull = await run(season, 0.7, true)
const bands: [string, number, number][] = [
  ['no real absence   (<3%)', 0, 0.03],
  ['moderate       (3-8%)', 0.03, 0.08],
  ['severe           (>8%)', 0.08, 1],
]
for (const [label, lo, hi] of bands) {
  const pick = (xs: DetailedScore[]): DetailedScore[] =>
    xs.filter((x) => x.absenceSeverity >= lo && x.absenceSeverity < hi)
  const a = summarize(pick(off))
  const b = summarize(pick(onFull))
  if (a.n === 0) continue
  const delta = b.rps - a.rps
  console.log(
    `  ${label}  n=${String(a.n).padStart(3)}   off ${a.rps.toFixed(4)}  on ${b.rps.toFixed(4)}  ` +
      `${delta < 0 ? 'BETTER' : 'worse '} by ${Math.abs(delta).toFixed(4)}`,
  )
}

console.log('\nApplied only above a severity threshold:')
for (const threshold of [0.03, 0.05, 0.08, 0.12]) {
  const s = summarize(await run(season, 0.7, true, threshold))
  const touched = onFull.filter((x) => x.absenceSeverity >= threshold).length
  console.log(
    `  threshold ${(threshold * 100).toFixed(0).padStart(2)}%  (${String(touched).padStart(3)} matches adjusted)   RPS ${s.rps.toFixed(4)}  ` +
      `${s.rps < base.rps ? 'better' : 'worse '} by ${Math.abs(s.rps - base.rps).toFixed(4)}`,
  )
}

console.log(
  '\nThe standard error on RPS at n=380 is roughly 0.008, so anything under about\n' +
    '0.004 here is indistinguishable from noise.',
)
