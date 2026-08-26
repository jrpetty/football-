// ---------------------------------------------------------------------------
// One-off repair: restore the predicted scores the site actually showed.
//
// Run: node scripts/repair-ledger-scores.ts <git-ref> <gameweek> [--write]
//
// Until 26 August the ledger stored `topScorelines[0]` — the globally most
// likely scoreline — while every page displayed the most likely scoreline
// *within* the most likely outcome. Those disagree constantly, so the record
// was grading predictions that had never been shown to anyone. Gameweek one
// was sealed with the wrong figure in eight of ten entries, and it cost a real
// hit: the board said Man City 2-1, City won 2-1, and the ledger marked it
// wrong because it had stored 1-1.
//
// A sealed entry must never be revised with hindsight, and this does not: it
// reads the forecast artifact exactly as it was committed BEFORE any result
// existed, and recomputes the headline score from those sealed probabilities.
// The script refuses to run against a snapshot that already contains results,
// which is the guard that makes it safe.
//
// Recover the pre-result artifact with:
//   git show <ref>:predictor/public/data/predictions/gw<N>.json
// ---------------------------------------------------------------------------

import { execFileSync } from 'node:child_process'
import { readJson, writeJson, dataPath } from './lib/fsjson.ts'
import { headlineScore } from '../src/core/predict.ts'
import { ledgerKey } from '../src/core/ledger.ts'
import { CURRENT_SEASON } from './ingest.ts'
import type { LedgerArtifact, GameweekArtifact } from '../src/core/schema.ts'

const [ref, gwArg, ...rest] = process.argv.slice(2)
const write = rest.includes('--write')
if (!ref || !gwArg) {
  console.error('usage: node scripts/repair-ledger-scores.ts <git-ref> <gameweek> [--write]')
  process.exit(2)
}
const gameweek = Number(gwArg)

const raw = execFileSync(
  'git',
  ['show', `${ref}:predictor/public/data/predictions/gw${gameweek}.json`],
  { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, cwd: process.cwd().replace(/\/predictor$/, '') },
)
const published = JSON.parse(raw) as GameweekArtifact

// The guard that makes this safe: a snapshot carrying results is a snapshot
// taken after the fact, and nothing in it can be trusted as a forecast.
const contaminated = published.fixtures.filter((f) => f.finished || f.result)
if (contaminated.length > 0) {
  console.error(
    `${ref} gw${gameweek} already contains ${contaminated.length} result(s). ` +
      'That is not a sealed forecast — refusing to repair from it.',
  )
  process.exit(1)
}

const ledger = await readJson<LedgerArtifact>(dataPath('ledger.json'))
if (!ledger) {
  console.error('ledger.json is missing')
  process.exit(1)
}
const index = new Map(ledger.entries.map((e) => [ledgerKey(e), e]))

let changed = 0
let matched = 0
console.log(`Repairing gameweek ${gameweek} from ${ref} (${published.fixtures.length} published forecasts)\n`)
for (const f of published.fixtures) {
  const entry = index.get(
    ledgerKey({ season: CURRENT_SEASON, fixtureId: f.id ?? 0, home: f.home, away: f.away }),
  )
  if (!entry) {
    console.log(`  ${f.home} v ${f.away}: no ledger entry`)
    continue
  }
  matched++
  const shown = headlineScore(f)
  const stored = entry.predictedScore
  if (stored.home === shown.home && stored.away === shown.away) continue
  changed++
  const actual = entry.actual
  const nowRight = actual ? shown.home === actual.homeGoals && shown.away === actual.awayGoals : null
  const wasRight = actual ? actual.correctScore : null
  console.log(
    `  ${f.home} v ${f.away}: recorded ${stored.home}-${stored.away} -> shown ${shown.home}-${shown.away}` +
      (actual ? `   (actual ${actual.homeGoals}-${actual.awayGoals}${wasRight !== nowRight ? `, ${wasRight ? 'was' : 'now'} correct` : ''})` : ''),
  )
  entry.predictedScore = shown
  if (actual) actual.correctScore = shown.home === actual.homeGoals && shown.away === actual.awayGoals
}

console.log(`\n${matched} entries matched, ${changed} corrected.`)
if (!write) {
  console.log('Dry run — pass --write to save.')
} else {
  await writeJson(dataPath('ledger.json'), ledger)
  console.log('ledger.json rewritten. Re-run build-all.ts to refresh accuracy.json.')
}
