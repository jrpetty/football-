// ---------------------------------------------------------------------------
// Build every artifact the site reads.
//
// Run: node scripts/build-all.ts
//
// Predictions are generated only for the next few gameweeks, not the whole
// season. Availability, form and rest are all "as of now", so a forecast for
// March made in August would be a fabrication dressed up as a prediction.
// ---------------------------------------------------------------------------

import { loadCorpus, CURRENT_SEASON } from './ingest.ts'
import { fitModel, buildPlayerRates, teamForm, DEFAULT_PARAMS } from './build-model.ts'
import { writeJson, readJson, dataPath } from './lib/fsjson.ts'
import { clubName, club } from '../src/config/teams.ts'
import { teamAvailability } from '../src/core/availability.ts'
import { restProfile } from '../src/core/congestion.ts'
import { predictFixture, type TeamContext } from '../src/core/predict.ts'
import { buildEvidence } from '../src/core/evidence.ts'
import { analyseUpset } from '../src/core/upset.ts'
import { playerProps } from '../src/core/playerProps.ts'
import { predictLineup } from '../src/core/lineup.ts'
import { diffRosters, takeSnapshot, mergeTransfers, type RosterPlayer } from '../src/core/transfers.ts'
import { deriveShapes, DEFAULT_SHAPE, type ShapeRow } from '../src/core/formation.ts'
import { cardStatus } from '../src/core/suspensions.ts'
import { outcomeOf, rps, logLoss, brier, summarize, calibration, expectedCalibrationError } from '../src/core/metrics.ts'
import { round } from '../src/core/math.ts'
import type {
  SeasonArtifact,
  GameweekArtifact,
  FixtureArtifact,
  TeamSummary,
  PlayersArtifact,
  PlayerArtifact,
  LedgerArtifact,
  LedgerEntry,
  AccuracyArtifact,
  CardWatchEntry,
  SquadsArtifact,
  RecomputeSide,
  TransfersArtifact,
} from '../src/core/schema.ts'
import { SCHEMA_VERSION } from '../src/core/schema.ts'
import type { NormMatch } from './sources/types.ts'

/** How many upcoming gameweeks get full predictions. */
const FORECAST_HORIZON = 3

function seasonTeams(matches: readonly NormMatch[], season: string): string[] {
  const s = new Set<string>()
  for (const m of matches) if (m.season === season) { s.add(m.home); s.add(m.away) }
  return [...s].sort()
}

async function main(): Promise<void> {
  console.log('Loading corpus...')
  const corpus = await loadCorpus()
  const now = Date.now()

  const seasons = [...new Set(corpus.matches.map((m) => m.season))].sort()
  const prevSeason = seasons[seasons.indexOf(CURRENT_SEASON) - 1] ?? ''
  const currentTeams = seasonTeams(corpus.matches, CURRENT_SEASON)
  const previousTeams = seasonTeams(corpus.matches, prevSeason)

  console.log(`Fitting ratings as of ${new Date(now).toISOString().slice(0, 10)}...`)
  const model = fitModel(
    corpus.matches,
    corpus.championship,
    now,
    currentTeams,
    previousTeams,
    DEFAULT_PARAMS,
  )
  console.log(
    `  ${Object.keys(model.ratings.attack).length} teams rated, ` +
      `home advantage ${model.ratings.homeAdvantage.toFixed(3)}, rho ${model.ratings.rho.toFixed(4)}`,
  )
  console.log(
    `  promoted: ${[...model.promoted].join(', ')} ` +
      `(prior attack ${model.prior.attack.toFixed(3)}, defence ${model.prior.defence.toFixed(3)}, n=${model.prior.sampleSize})`,
  )

  // --- Season table and team summaries -------------------------------------
  const seasonMatches = corpus.matches.filter((m) => m.season === CURRENT_SEASON)
  const played = seasonMatches.filter((m) => m.finished)

  const rates = buildPlayerRates(corpus.players, corpus.playerGws, now, CURRENT_SEASON)

  // The shape each club actually starts, read from its recent team sheets and
  // weighted toward recent matches so a change of system shows through.
  const shapes = deriveShapes(corpus.playerGws as ShapeRow[], now)

  // Disciplinary records reset every season. The pre-season snapshot carries
  // last season's totals, so counting those would tell a fan that half of
  // Arsenal are one booking from a ban before a ball is kicked. Accumulation is
  // therefore rebuilt from this season's played matches only — which correctly
  // means everyone starts on zero.
  const seasonCards = new Map<number, { yellows: number; reds: number }>()
  for (const g of corpus.playerGws) {
    if (g.date >= now) continue
    if (g.season !== CURRENT_SEASON) continue
    const acc = seasonCards.get(g.element) ?? { yellows: 0, reds: 0 }
    acc.yellows += g.yellowCards
    acc.reds += g.redCards
    seasonCards.set(g.element, acc)
  }
  const cardsFor = (id: number): { yellows: number; reds: number } =>
    seasonCards.get(id) ?? { yellows: 0, reds: 0 }
  const ratesByTeam = new Map<string, typeof rates>()
  for (const r of rates) {
    if (!ratesByTeam.has(r.team)) ratesByTeam.set(r.team, [])
    ratesByTeam.get(r.team)!.push(r)
  }


  const teams: TeamSummary[] = currentTeams.map((code) => {
    const identity = club(code)
    const theirs = played.filter((m) => m.home === code || m.away === code)
    let won = 0, drawn = 0, lost = 0, goalsFor = 0, goalsAgainst = 0
    for (const m of theirs) {
      const isHome = m.home === code
      const gf = (isHome ? m.homeGoals : m.awayGoals) ?? 0
      const ga = (isHome ? m.awayGoals : m.homeGoals) ?? 0
      goalsFor += gf
      goalsAgainst += ga
      if (gf > ga) won++
      else if (gf === ga) drawn++
      else lost++
    }
    const form = teamForm(corpus.matches, code, now, 6, null)
    return {
      code,
      name: identity.name,
      short: identity.short,
      color: identity.color,
      promoted: model.promoted.has(code),
      played: theirs.length,
      won, drawn, lost, goalsFor, goalsAgainst,
      points: won * 3 + drawn,
      attack: round(model.ratings.attack[code] ?? 0, 4),
      defence: round(model.ratings.defence[code] ?? 0, 4),
      ratingSd: round(model.ratingSd[code] ?? 0.15, 4),
      form: form.recent,
    }
  })
  teams.sort((a, b) => b.points - a.points || b.goalsFor - b.goalsAgainst - (a.goalsFor - a.goalsAgainst))

  // --- Which gameweeks to forecast -----------------------------------------
  const gameweeks = [...new Set(seasonMatches.map((m) => m.round))].filter((g) => g > 0).sort((a, b) => a - b)
  const completed = gameweeks.filter((g) =>
    seasonMatches.filter((m) => m.round === g).every((m) => m.finished),
  )
  const upcoming = gameweeks.filter((g) => !completed.includes(g))
  const currentGameweek = upcoming[0] ?? gameweeks[gameweeks.length - 1] ?? 1
  const forecastWeeks = upcoming.slice(0, FORECAST_HORIZON)

  console.log(`Current gameweek ${currentGameweek}; forecasting ${forecastWeeks.join(', ')}`)

  // Kickoffs per team, for rest calculations.
  const kickoffsByTeam = new Map<string, number[]>()
  for (const m of corpus.matches) {
    if (!m.finished && m.date > now) continue
    for (const t of [m.home, m.away]) {
      if (!kickoffsByTeam.has(t)) kickoffsByTeam.set(t, [])
      kickoffsByTeam.get(t)!.push(m.date)
    }
  }

  // Load the existing ledger so past forecasts are never rewritten.
  const ledger = (await readJson<LedgerArtifact>(dataPath('ledger.json'))) ?? {
    schemaVersion: SCHEMA_VERSION,
    generatedAt: now,
    entries: [],
  }
  const ledgerKey = (e: { season: string; fixtureId: number }): string => `${e.season}#${e.fixtureId}`
  const ledgerIndex = new Map(ledger.entries.map((e) => [ledgerKey(e), e]))

  const side = (ctx: TeamContext, code: string): RecomputeSide => ({
    attack: round(model.ratings.attack[code] ?? 0, 4),
    defence: round(model.ratings.defence[code] ?? 0, 4),
    ratingSd: round(ctx.ratingSd, 4),
    matchesPlayed: ctx.matchesPlayed,
    promoted: ctx.promoted,
    rest: ctx.rest,
    shape: shapes.get(code) ?? DEFAULT_SHAPE,
  })

  const buildContext = (code: string, kickoff: number): TeamContext => {
    const squad = ratesByTeam.get(code) ?? []
    return {
      code,
      availability: teamAvailability(code, squad, { strength: DEFAULT_PARAMS.availabilityStrength }),
      rest: restProfile(kickoff, kickoffsByTeam.get(code) ?? []),
      ratingSd: model.ratingSd[code] ?? 0.15,
      matchesPlayed: played.filter((m) => m.home === code || m.away === code).length,
      promoted: model.promoted.has(code),
    }
  }

  // --- Per-gameweek artifacts ----------------------------------------------
  const writtenGameweeks: number[] = []
  for (const gw of [...completed, ...forecastWeeks].sort((a, b) => a - b)) {
    const fixtures = seasonMatches.filter((m) => m.round === gw).sort((a, b) => a.date - b.date)
    if (fixtures.length === 0) continue

    const artifacts: FixtureArtifact[] = []
    for (const m of fixtures) {
      const homeCtx = buildContext(m.home, m.date)
      const awayCtx = buildContext(m.away, m.date)
      const prediction = predictFixture({ home: homeCtx, away: awayCtx, kickoff: m.date, ratings: model.ratings })

      const homeName = clubName(m.home)
      const awayName = clubName(m.away)
      const homeFormV = teamForm(corpus.matches, m.home, m.date, DEFAULT_PARAMS.formWindow, 'home')
      const awayFormV = teamForm(corpus.matches, m.away, m.date, DEFAULT_PARAMS.formWindow, 'away')

      const evidenceCtx = {
        prediction,
        homeName,
        awayName,
        homeAvailability: homeCtx.availability,
        awayAvailability: awayCtx.availability,
        homeRest: homeCtx.rest,
        awayRest: awayCtx.rest,
        homeForm: homeFormV,
        awayForm: awayFormV,
        homePromoted: homeCtx.promoted,
        awayPromoted: awayCtx.promoted,
      }
      const evidence = buildEvidence(evidenceCtx)
      const upset = analyseUpset(evidenceCtx)

      const homeSquad = ratesByTeam.get(m.home) ?? []
      const awaySquad = ratesByTeam.get(m.away) ?? []
      const homePlayers = playerProps({
        squad: homeSquad,
        teamLambda: prediction.lambdaHome,
        cleanSheetProb: prediction.cleanSheetHome,
      }).slice(0, 16)
      const awayPlayers = playerProps({
        squad: awaySquad,
        teamLambda: prediction.lambdaAway,
        cleanSheetProb: prediction.cleanSheetAway,
      }).slice(0, 16)

      // Card watch: only players who can actually reach a threshold.
      const cardWatch: CardWatchEntry[] = []
      for (const [squad, code] of [[homeSquad, m.home], [awaySquad, m.away]] as const) {
        const clubPlayed = played.filter((x) => x.home === code || x.away === code).length
        for (const r of squad) {
          const cards = cardsFor(r.id)
          const yellows = cards.yellows
          if (yellows === 0) continue
          const status = cardStatus({
            playerId: r.id,
            name: r.name,
            team: code,
            yellows,
            reds: cards.reds,
            clubMatchesPlayed: clubPlayed,
            yellowPer90: r.yellow90,
            expectedMinutes: Math.min(90, r.minuteShare * 90),
          })
          if (status.yellowsToBan !== null && status.yellowsToBan <= 2 && status.nextThreshold) {
            cardWatch.push({
              playerId: r.id,
              name: r.name,
              team: code,
              yellows,
              yellowsToBan: status.yellowsToBan,
              banMatches: status.nextThreshold.banMatches,
              bookingProbability: round(status.bookingProbability, 4),
              banProbability: round(status.banProbability, 4),
            })
          }
        }
      }
      cardWatch.sort((a, b) => b.banProbability - a.banProbability || a.yellowsToBan - b.yellowsToBan)

      const fixtureArtifact: FixtureArtifact = {
        id: m.fixtureId ?? 0,
        gameweek: gw,
        kickoff: m.date,
        home: m.home,
        away: m.away,
        finished: m.finished,
        homeGoals: m.homeGoals,
        awayGoals: m.awayGoals,
        probs: prediction.probs,
        lambdaHome: prediction.lambdaHome,
        lambdaAway: prediction.lambdaAway,
        expectedGoalsHome: prediction.expectedGoalsHome,
        expectedGoalsAway: prediction.expectedGoalsAway,
        btts: prediction.btts,
        over25: prediction.over25,
        cleanSheetHome: prediction.cleanSheetHome,
        cleanSheetAway: prediction.cleanSheetAway,
        topScorelines: prediction.topScorelines,
        modalScore: prediction.modalScore,
        dispersion: prediction.dispersion,
        rho: prediction.rho,
        confidence: prediction.confidence,
        terms: prediction.terms,
        evidence,
        upset,
        homePlayers,
        awayPlayers,
        homeMissing: homeCtx.availability.missing,
        awayMissing: awayCtx.availability.missing,
        homeLineup: predictLineup(homeSquad, shapes.get(m.home) ?? DEFAULT_SHAPE, now),
        awayLineup: predictLineup(awaySquad, shapes.get(m.away) ?? DEFAULT_SHAPE, now),
        recompute: {
          homeAdvantage: round(model.ratings.homeAdvantage, 4),
          rho: round(model.ratings.rho, 4),
          availabilityStrength: DEFAULT_PARAMS.availabilityStrength,
          home: side(homeCtx, m.home),
          away: side(awayCtx, m.away),
        },
        cardWatch: cardWatch.slice(0, 8),
      }

      // The ledger holds the last forecast made before kickoff.
      //
      // Not the first: the model refits every week and gets fixed when it is
      // wrong, so freezing the opening version would score a model that no
      // longer exists. (It nearly did — every gameweek-one entry was written
      // before a player-rates bug was found, and Arsenal v Coventry sat at
      // 53.7% when the corrected model said 66.7%.) An entry stays open to
      // revision right up to kickoff and is sealed the instant the match
      // starts, which is the line that actually matters: nothing here is ever
      // revised with knowledge of a result.
      const key = `${CURRENT_SEASON}#${m.fixtureId ?? 0}`
      const kickedOff = m.date <= now
      const forecast = {
        probs: prediction.probs,
        lambdaHome: prediction.lambdaHome,
        lambdaAway: prediction.lambdaAway,
        predictedScore: {
          home: prediction.topScorelines[0]?.home ?? 0,
          away: prediction.topScorelines[0]?.away ?? 0,
        },
      }
      const existing = ledgerIndex.get(key)
      if (!existing && !kickedOff) {
        const entry: LedgerEntry = {
          season: CURRENT_SEASON,
          gameweek: gw,
          fixtureId: m.fixtureId ?? 0,
          home: m.home,
          away: m.away,
          kickoff: m.date,
          predictedAt: now,
          updatedAt: now,
          revisions: 0,
          ...forecast,
        }
        ledger.entries.push(entry)
        ledgerIndex.set(key, entry)
      } else if (existing && !kickedOff && !existing.actual) {
        const moved = Math.abs(existing.probs.home - forecast.probs.home) > 1e-6
        Object.assign(existing, forecast)
        existing.updatedAt = now
        if (moved) existing.revisions = (existing.revisions ?? 0) + 1
      }

      // Score a finished fixture against whatever was forecast beforehand.
      if (m.finished && m.homeGoals !== null && m.awayGoals !== null) {
        const recorded = ledgerIndex.get(key)
        const probs = recorded?.probs ?? prediction.probs
        const actual = outcomeOf(m.homeGoals, m.awayGoals)
        const predictedScore = recorded?.predictedScore ?? {
          home: prediction.topScorelines[0]?.home ?? 0,
          away: prediction.topScorelines[0]?.away ?? 0,
        }
        const scored = {
          homeGoals: m.homeGoals,
          awayGoals: m.awayGoals,
          outcome: actual,
          rps: round(rps(probs, actual), 5),
          logLoss: round(logLoss(probs, actual), 5),
          brier: round(brier(probs, actual), 5),
          correctOutcome:
            (probs.home >= probs.draw && probs.home >= probs.away ? 'home' : probs.draw >= probs.away ? 'draw' : 'away') === actual,
          correctScore: predictedScore.home === m.homeGoals && predictedScore.away === m.awayGoals,
        }
        if (recorded) recorded.actual = scored
        fixtureArtifact.result = {
          outcome: actual,
          correctOutcome: scored.correctOutcome,
          correctScore: scored.correctScore,
          rps: scored.rps,
          logLoss: scored.logLoss,
          brier: scored.brier,
        }
      }

      artifacts.push(fixtureArtifact)
    }

    const scoredHere = artifacts.filter((f) => f.result).map((f) => ({
      probs: f.probs,
      actual: f.result!.outcome,
    }))

    const gwArtifact: GameweekArtifact = {
      schemaVersion: SCHEMA_VERSION,
      season: CURRENT_SEASON,
      gameweek: gw,
      generatedAt: now,
      fixtures: artifacts,
      metrics: scoredHere.length > 0 ? summarize(scoredHere) : undefined,
    }
    await writeJson(dataPath('predictions', `gw${gw}.json`), gwArtifact)
    writtenGameweeks.push(gw)
  }

  // --- Players -------------------------------------------------------------
  // Each club's total attacking value, so a player's share of it can be shown.
  //
  // A share is only meaningful when there is a squad to take a share of. A
  // newly promoted club has almost nobody with Premier League minutes, so the
  // one player who does absorbs nearly all of it — Awoniyi came out at 92.5%
  // of Coventry's attacking value, which reads as "he is their whole attack"
  // when it actually means "we can barely measure this club at all". Those
  // clubs report no impact rather than a confident-looking fiction.
  const MIN_RATED_SQUAD = 8
  const teamAttackValue = new Map<string, number>()
  const teamRatedCount = new Map<string, number>()
  for (const r of rates) {
    teamAttackValue.set(r.team, (teamAttackValue.get(r.team) ?? 0) + Math.max(0, r.xgi90) * r.minuteShare)
    if (r.minutes > 0) teamRatedCount.set(r.team, (teamRatedCount.get(r.team) ?? 0) + 1)
  }

  const playerArtifacts: PlayerArtifact[] = corpus.players
    .map((p) => {
      const rate = rates.find((r) => r.id === p.id)
      const clubPlayed = played.filter((m) => m.home === p.team || m.away === p.team).length
      const seasonCard = cardsFor(p.id)
      const status = cardStatus({
        playerId: p.id,
        name: p.name,
        team: p.team,
        yellows: seasonCard.yellows,
        reds: seasonCard.reds,
        clubMatchesPlayed: clubPlayed,
        yellowPer90: rate?.yellow90 ?? 0,
        expectedMinutes: Math.min(90, (rate?.minuteShare ?? 0) * 90),
      })
      return {
        id: p.id,
        name: p.name,
        fullName: p.fullName,
        team: p.team,
        position: p.position,
        minutes: p.minutes,
        starts: p.starts,
        goals: p.goals,
        assists: p.assists,
        // Cards are this season's count (they reset each year); the output
        // stats above may still be last season's, flagged on the artifact.
        yellowCards: seasonCard.yellows,
        redCards: seasonCard.reds,
        priorYellowCards: p.yellowCards,
        priorRedCards: p.redCards,
        xg: round(p.xg, 2),
        xa: round(p.xa, 2),
        xgi90: round(rate?.xgi90 ?? 0, 3),
        yellow90: round(rate?.yellow90 ?? 0, 3),
        status: p.status,
        news: p.news,
        chanceNextRound: p.chanceNextRound,
        cost: p.cost,
        yellowsToBan: status.yellowsToBan,
        onBrink: status.onBrink,
        joinedAt: p.joinedAt,
        impact: round(
          rate &&
          (teamAttackValue.get(p.team) ?? 0) > 0 &&
          (teamRatedCount.get(p.team) ?? 0) >= MIN_RATED_SQUAD
            ? (Math.max(0, rate.xgi90) * rate.minuteShare) / teamAttackValue.get(p.team)!
            : 0,
          4,
        ),
      }
    })
    .filter((p) => p.minutes > 0 || p.status !== 'a')

  const playersArtifact: PlayersArtifact = {
    schemaVersion: SCHEMA_VERSION,
    season: CURRENT_SEASON,
    generatedAt: now,
    statsFromPriorSeason: played.length < 4,
    players: playerArtifacts,
  }
  await writeJson(dataPath('players.json'), playersArtifact)

  // Squad rates, one copy per club. Fixtures reference these rather than
  // embedding them, which keeps a gameweek file small even though every match
  // needs both full squads to be re-runnable in the browser.
  const squads: Record<string, typeof rates> = {}
  for (const code of currentTeams) squads[code] = ratesByTeam.get(code) ?? []
  const squadsArtifact: SquadsArtifact = {
    schemaVersion: SCHEMA_VERSION,
    season: CURRENT_SEASON,
    generatedAt: now,
    squads,
  }
  await writeJson(dataPath('squads.json'), squadsArtifact)

  // --- Transfers -----------------------------------------------------------
  //
  // A move is invisible in a single snapshot: the feed just reports a player at
  // a different club than last time. Diffing against the previous run turns
  // that into a record, which matters because a club that has sold its main
  // striker keeps the team rating it earned with him until results catch up.
  const rosterPlayers: RosterPlayer[] = corpus.players.map((p) => ({
    id: p.id, name: p.name, team: p.team, position: p.position, cost: p.cost, joinedAt: p.joinedAt,
  }))
  const previousTransfers = await readJson<TransfersArtifact>(dataPath('transfers.json'))
  const diff = diffRosters(previousTransfers?.roster ?? null, rosterPlayers, CURRENT_SEASON, now)
  if (diff.rejected) console.warn(`  ! squad diff skipped: ${diff.rejected}`)
  if (diff.records.length > 0) {
    console.log(`  ${diff.records.length} squad change(s): ` +
      diff.records.slice(0, 5).map((r) =>
        r.kind === 'moved' ? `${r.name} ${r.from}->${r.to}` : `${r.name} ${r.kind}`).join(', '))
  }
  const transfersArtifact: TransfersArtifact = {
    schemaVersion: SCHEMA_VERSION,
    season: CURRENT_SEASON,
    generatedAt: now,
    roster: takeSnapshot(rosterPlayers, CURRENT_SEASON, now),
    records: mergeTransfers(previousTransfers?.records ?? [], diff.records),
  }
  await writeJson(dataPath('transfers.json'), transfersArtifact)

  // Append this week's availability flags to a history file.
  //
  // The archive carries no record of who was injured *before* a past match, so
  // the availability adjustment can only be tested against a noisy proxy today
  // (see scripts/backtest-availability.ts). Recording the real flags each week
  // means that in a few months it can be tested properly — against what was
  // actually known at the time.
  interface StatusSnapshot {
    takenAt: number
    gameweek: number
    /** player id -> [status, chance-of-playing] for anyone not fully available. */
    flags: Record<number, [string, number | null]>
  }
  const history = (await readJson<{ schemaVersion: number; snapshots: StatusSnapshot[] }>(
    dataPath('status-history.json'),
  )) ?? { schemaVersion: SCHEMA_VERSION, snapshots: [] }

  const flags: Record<number, [string, number | null]> = {}
  for (const p of corpus.players) {
    if (p.status !== 'a' || p.chanceNextRound !== null) flags[p.id] = [p.status, p.chanceNextRound]
  }
  // One snapshot per gameweek; a rerun in the same week replaces it rather
  // than piling up near-identical rows.
  const existingIdx = history.snapshots.findIndex((x) => x.gameweek === currentGameweek)
  const snapshot: StatusSnapshot = { takenAt: now, gameweek: currentGameweek, flags }
  if (existingIdx >= 0) history.snapshots[existingIdx] = snapshot
  else history.snapshots.push(snapshot)
  await writeJson(dataPath('status-history.json'), history)

  // --- Accuracy ------------------------------------------------------------
  const scoredEntries = ledger.entries.filter((e) => e.actual)
  const scored = scoredEntries.map((e) => ({ probs: e.probs, actual: e.actual!.outcome }))
  // Fixed league-average probabilities, the honest "do nothing" comparison.
  const baselineProbs = { home: 0.44, draw: 0.25, away: 0.31 }
  const baseline = scoredEntries.map((e) => ({ probs: baselineProbs, actual: e.actual!.outcome }))

  const byGw = new Map<number, { probs: typeof baselineProbs; actual: 'home' | 'draw' | 'away' }[]>()
  for (const e of scoredEntries) {
    if (!byGw.has(e.gameweek)) byGw.set(e.gameweek, [])
    byGw.get(e.gameweek)!.push({ probs: e.probs, actual: e.actual!.outcome })
  }

  const bins = calibration(scored)
  const accuracy: AccuracyArtifact = {
    schemaVersion: SCHEMA_VERSION,
    generatedAt: now,
    overall: summarize(scored),
    baseline: summarize(baseline),
    byGameweek: [...byGw.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([gameweek, items]) => ({ gameweek, season: CURRENT_SEASON, metrics: summarize(items) })),
    calibration: bins,
    expectedCalibrationError: round(expectedCalibrationError(bins), 5),
    bestCalls: [...scoredEntries].sort((a, b) => a.actual!.rps - b.actual!.rps).slice(0, 5),
    worstCalls: [...scoredEntries].sort((a, b) => b.actual!.rps - a.actual!.rps).slice(0, 5),
  }
  await writeJson(dataPath('accuracy.json'), accuracy)

  ledger.generatedAt = now
  await writeJson(dataPath('ledger.json'), ledger)

  // --- Season --------------------------------------------------------------
  const notes: string[] = [
    'Line-ups are predicted from recent minutes and current availability. Confirmed team sheets are only published about an hour before kickoff and are deliberately not waited for, so a late change will not be reflected.',
    'Red-card ban lengths are inferred: the data feed records that a red card was shown but not the offence, so the one-match minimum is assumed.',
  ]
  if (played.length === 0) {
    notes.unshift(
      'The season has not started. Every rating here comes from previous seasons, with newly promoted clubs on a measured prior — so uncertainty is at its highest of the whole campaign.',
    )
  }

  const season: SeasonArtifact = {
    schemaVersion: SCHEMA_VERSION,
    season: CURRENT_SEASON,
    generatedAt: now,
    currentGameweek,
    completedGameweeks: completed,
    allGameweeks: gameweeks,
    teams,
    params: {
      xi: DEFAULT_PARAMS.xi,
      xgBlend: DEFAULT_PARAMS.xgBlend,
      ridge: DEFAULT_PARAMS.ridge,
      homeAdvantage: round(model.ratings.homeAdvantage, 4),
      rho: round(model.ratings.rho, 4),
      availabilityStrength: DEFAULT_PARAMS.availabilityStrength,
      promotedPriorAttack: round(model.prior.attack, 4),
      promotedPriorDefence: round(model.prior.defence, 4),
      promotedSampleSize: model.prior.sampleSize,
    },
    notes,
  }
  await writeJson(dataPath('season.json'), season)

  console.log(
    `\nWrote season.json, players.json (${playerArtifacts.length}), ledger.json (${ledger.entries.length}), ` +
      `accuracy.json, and ${writtenGameweeks.length} gameweek files.`,
  )
}

await main()
