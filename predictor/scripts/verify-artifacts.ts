// ---------------------------------------------------------------------------
// Artifact gate.
//
// Run: node scripts/verify-artifacts.ts
//
// The weekly job runs this before committing. Publishing a broken or
// implausible artifact is worse than publishing nothing, because the site
// gives no hint that the numbers stopped meaning anything — so anything that
// fails here stops the pipeline.
// ---------------------------------------------------------------------------

import { readdir } from 'node:fs/promises'
import { readJson, dataPath } from './lib/fsjson.ts'
import { SCHEMA_VERSION } from '../src/core/schema.ts'
import type { SeasonArtifact, GameweekArtifact, PlayersArtifact, AccuracyArtifact, LedgerArtifact } from '../src/core/schema.ts'

const problems: string[] = []
const warnings: string[] = []

function check(condition: boolean, message: string): void {
  if (!condition) problems.push(message)
}
function warn(condition: boolean, message: string): void {
  if (!condition) warnings.push(message)
}

const near = (a: number, b: number, eps: number): boolean => Math.abs(a - b) <= eps

async function main(): Promise<void> {
  // --- season.json ---------------------------------------------------------
  const season = await readJson<SeasonArtifact>(dataPath('season.json'))
  check(season !== null, 'season.json is missing')
  if (!season) return finish()

  check(season.schemaVersion === SCHEMA_VERSION, `season.json schema ${season.schemaVersion} != ${SCHEMA_VERSION}`)
  check(season.teams.length === 20, `expected 20 clubs, found ${season.teams.length}`)
  check(new Set(season.teams.map((t) => t.code)).size === season.teams.length, 'duplicate club in the table')
  check(season.currentGameweek >= 1 && season.currentGameweek <= 38, `currentGameweek ${season.currentGameweek} out of range`)
  check(season.allGameweeks.length > 0, 'no gameweeks listed')

  // Ratings are log-scale and centred; anything wild means the fit diverged.
  for (const t of season.teams) {
    check(Number.isFinite(t.attack) && Math.abs(t.attack) < 2, `${t.code} attack rating ${t.attack} is implausible`)
    check(Number.isFinite(t.defence) && Math.abs(t.defence) < 2, `${t.code} defence rating ${t.defence} is implausible`)
    check(t.ratingSd > 0 && t.ratingSd < 1, `${t.code} rating sd ${t.ratingSd} is implausible`)
  }
  const meanAttack = season.teams.reduce((s, t) => s + t.attack, 0) / season.teams.length
  warn(Math.abs(meanAttack) < 0.35, `mean attack rating ${meanAttack.toFixed(3)} is far from centred`)

  const homeAdv = season.params['homeAdvantage'] ?? 0
  check(homeAdv > 0 && homeAdv < 0.8, `home advantage ${homeAdv} is outside anything believable`)

  // --- gameweek files ------------------------------------------------------
  const files = (await readdir(dataPath('predictions')).catch(() => [])).filter((f) => f.endsWith('.json'))
  check(files.length > 0, 'no gameweek prediction files were written')

  let fixtureCount = 0
  for (const file of files.sort()) {
    const gw = await readJson<GameweekArtifact>(dataPath('predictions', file))
    if (!gw) {
      problems.push(`${file} could not be read`)
      continue
    }
    check(gw.schemaVersion === SCHEMA_VERSION, `${file} schema ${gw.schemaVersion} != ${SCHEMA_VERSION}`)
    check(gw.fixtures.length > 0, `${file} has no fixtures`)

    for (const f of gw.fixtures) {
      fixtureCount++
      const label = `${file} ${f.home}v${f.away}`

      // Probabilities must be a distribution.
      const total = f.probs.home + f.probs.draw + f.probs.away
      check(near(total, 1, 0.002), `${label}: 1X2 probabilities sum to ${total.toFixed(4)}`)
      for (const [k, v] of Object.entries(f.probs)) {
        check(v >= 0 && v <= 1, `${label}: ${k} probability ${v} out of range`)
      }
      for (const [k, v] of [['btts', f.btts], ['over25', f.over25], ['cleanSheetHome', f.cleanSheetHome], ['cleanSheetAway', f.cleanSheetAway]] as const) {
        check(v >= 0 && v <= 1, `${label}: ${k} ${v} out of range`)
      }

      // Scoring rates must be sane; a diverged fit shows up here first.
      check(f.lambdaHome > 0 && f.lambdaHome < 6, `${label}: lambdaHome ${f.lambdaHome} implausible`)
      check(f.lambdaAway > 0 && f.lambdaAway < 6, `${label}: lambdaAway ${f.lambdaAway} implausible`)
      check(f.home !== f.away, `${label}: a club cannot play itself`)
      check(f.kickoff > 0, `${label}: missing kickoff time`)

      // The headline scoreline the site shows must agree with the outcome the
      // model favours — the two contradicting each other was a real bug.
      const favouredHome = f.probs.home >= f.probs.draw && f.probs.home >= f.probs.away
      const favouredAway = f.probs.away >= f.probs.draw && f.probs.away > f.probs.home
      if (favouredHome) check(f.modalScore.home.home > f.modalScore.home.away, `${label}: home modal score is not a home win`)
      if (favouredAway) check(f.modalScore.away.away > f.modalScore.away.home, `${label}: away modal score is not an away win`)
      check(f.modalScore.draw.home === f.modalScore.draw.away, `${label}: draw modal score is not level`)

      // Top scorelines must descend.
      for (let i = 1; i < f.topScorelines.length; i++) {
        check(
          f.topScorelines[i - 1]!.prob >= f.topScorelines[i]!.prob,
          `${label}: scorelines are not sorted by probability`,
        )
      }

      // Reasoning must exist — a fixture with no evidence means the generator
      // silently produced nothing.
      check(f.evidence.length > 0, `${label}: no evidence was generated`)
      check(typeof f.upset.summary === 'string' && f.upset.summary.length > 20, `${label}: upset summary is empty`)

      // Player probabilities must be probabilities.
      for (const p of [...f.homePlayers, ...f.awayPlayers]) {
        for (const [k, v] of [['goal', p.goal], ['assist', p.assist], ['yellow', p.yellow], ['red', p.red]] as const) {
          check(v >= 0 && v <= 1, `${label}: ${p.name} ${k} ${v} out of range`)
        }
        check(p.brace <= p.goal + 1e-9, `${label}: ${p.name} brace exceeds goal probability`)
      }

      // Card watch must only list players who can actually be banned.
      for (const c of f.cardWatch) {
        check(c.yellowsToBan >= 1, `${label}: ${c.name} listed with yellowsToBan ${c.yellowsToBan}`)
        check(c.yellows > 0, `${label}: ${c.name} on the card watch with no bookings`)
      }
    }
  }

  // --- players.json --------------------------------------------------------
  const players = await readJson<PlayersArtifact>(dataPath('players.json'))
  check(players !== null, 'players.json is missing')
  if (players) {
    check(players.players.length > 100, `only ${players.players.length} players — the feed looks truncated`)
    for (const p of players.players.slice(0, 2000)) {
      check(p.yellowCards >= 0 && p.yellowCards < 40, `${p.name}: implausible yellow count ${p.yellowCards}`)
      check(p.minutes >= 0, `${p.name}: negative minutes`)
    }
    // Suspension counters reset each season, so nobody can be on the brink
    // before any match has been played.
    if (season.teams.every((t) => t.played === 0)) {
      const brink = players.players.filter((p) => p.onBrink).length
      check(brink === 0, `${brink} players flagged one booking from a ban before the season started`)
    }
  }

  // --- ledger and accuracy -------------------------------------------------
  const ledger = await readJson<LedgerArtifact>(dataPath('ledger.json'))
  const accuracy = await readJson<AccuracyArtifact>(dataPath('accuracy.json'))
  check(ledger !== null, 'ledger.json is missing')
  check(accuracy !== null, 'accuracy.json is missing')

  if (ledger) {
    const seen = new Set<string>()
    for (const e of ledger.entries) {
      const key = `${e.season}#${e.fixtureId}`
      check(!seen.has(key), `ledger has a duplicate entry for ${key}`)
      seen.add(key)
      // The whole point of the ledger is that forecasts predate kickoff.
      check(e.predictedAt <= e.kickoff, `ledger entry ${key} was recorded after kickoff`)
      const t = e.probs.home + e.probs.draw + e.probs.away
      check(near(t, 1, 0.002), `ledger entry ${key} probabilities sum to ${t.toFixed(4)}`)
    }
  }

  if (accuracy && accuracy.overall.n > 0) {
    check(accuracy.overall.rps >= 0 && accuracy.overall.rps <= 1, `overall RPS ${accuracy.overall.rps} out of range`)
    check(accuracy.overall.accuracy >= 0 && accuracy.overall.accuracy <= 1, 'accuracy out of range')
    warn(
      accuracy.overall.rps <= accuracy.baseline.rps,
      `the model (RPS ${accuracy.overall.rps.toFixed(4)}) is behind the fixed baseline ` +
        `(${accuracy.baseline.rps.toFixed(4)}) — worth investigating, not necessarily broken`,
    )
  }

  console.log(`Checked ${files.length} gameweek files, ${fixtureCount} fixtures, ${players?.players.length ?? 0} players.`)
  finish()
}

function finish(): void {
  for (const w of warnings) console.warn(`  warning: ${w}`)
  if (problems.length > 0) {
    console.error(`\n${problems.length} problem${problems.length === 1 ? '' : 's'} found:`)
    for (const p of problems.slice(0, 40)) console.error(`  - ${p}`)
    if (problems.length > 40) console.error(`  ...and ${problems.length - 40} more`)
    process.exit(1)
  }
  console.log(warnings.length > 0 ? `\nArtifacts OK (${warnings.length} warning(s)).` : '\nArtifacts OK.')
}

await main()
