// Predicted line-ups, and the two data bugs that made the first attempt at
// them produce nonsense.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { predictLineup } from '../src/core/lineup.ts'
import type { PlayerRates } from '../src/core/availability.ts'
import { buildPlayerRates } from '../scripts/build-model.ts'
import type { NormPlayer, NormPlayerGw } from '../scripts/sources/types.ts'

function rate(over: Partial<PlayerRates> = {}): PlayerRates {
  return {
    id: 1, name: 'P', fullName: 'P Player', team: 'ARS', position: 'MID',
    minutes: 2000, xgi90: 0.3, xgc90: 1.1, saves90: 0, yellow90: 0.2, red90: 0.01,
    status: 'a', chanceNextRound: null, news: '', cost: 6, minuteShare: 0.7, joinedAt: null,
    ...over,
  }
}

/** A believable 20-man squad with a realistic positional spread. */
function squad(over: (i: number) => Partial<PlayerRates> = () => ({})): PlayerRates[] {
  const shape: PlayerRates['position'][] = [
    'GK', 'GK',
    'DEF', 'DEF', 'DEF', 'DEF', 'DEF', 'DEF',
    'MID', 'MID', 'MID', 'MID', 'MID', 'MID',
    'FWD', 'FWD', 'FWD', 'FWD',
  ]
  return shape.map((position, i) =>
    rate({
      id: i + 1,
      name: `${position}${i + 1}`,
      position,
      // Descending minute share so the first of each line is the clear starter.
      minuteShare: Math.max(0.15, 0.95 - i * 0.04),
      minutes: 2000 - i * 60,
      ...over(i),
    }),
  )
}

test('a predicted eleven has exactly eleven players and exactly one keeper', () => {
  const l = predictLineup(squad())
  assert.equal(l.starters.length, 11)
  assert.equal(l.starters.filter((s) => s.position === 'GK').length, 1)
})

test('the eleven is a plausible shape, not whoever scored highest', () => {
  const l = predictLineup(squad())
  const count = (p: string): number => l.starters.filter((s) => s.position === p).length
  // Minute share knows nothing about shape, so the selection has to impose one.
  assert.ok(count('DEF') >= 3 && count('DEF') <= 5, `defenders: ${count('DEF')}`)
  assert.ok(count('MID') >= 2 && count('MID') <= 5, `midfielders: ${count('MID')}`)
  assert.ok(count('FWD') >= 1, `forwards: ${count('FWD')}`)
  assert.match(l.formation, /^\d-\d-\d$/)
  // The outfield lines must add up to ten.
  const [d, m, f] = l.formation.split('-').map(Number)
  assert.equal(d! + m! + f!, 10)
})

test('the team sheet reads back to front', () => {
  const l = predictLineup(squad())
  const order = { GK: 0, DEF: 1, MID: 2, FWD: 3 } as const
  const seq = l.starters.map((s) => order[s.position])
  assert.deepEqual(seq, [...seq].sort((a, b) => a - b), 'keeper first, forwards last')
})

test('an injured regular is left out and named as absent', () => {
  const s = squad((i) => (i === 2 ? { status: 'i', news: 'Hamstring', minuteShare: 0.95 } : {}))
  const l = predictLineup(s)
  assert.ok(!l.starters.some((x) => x.id === 3), 'an injured player cannot start')
  assert.ok(l.absent.some((x) => x.id === 3), 'and should be named as missing')
})

test('a doubtful player can still start, but with lower confidence', () => {
  const healthy = predictLineup(squad())
  const doubtful = predictLineup(squad((i) => (i === 8 ? { status: 'd', chanceNextRound: 50 } : {})))
  assert.ok(doubtful.confidence < healthy.confidence)
})

test('a promoted club with no minutes still gets a keeper and an honest label', () => {
  // This is the real Coventry case: no top-flight record anywhere in the feed.
  // The first version produced an eleven with no goalkeeper at all.
  const s = squad(() => ({ minutes: 0, minuteShare: 0 })).map((p, i) =>
    ({ ...p, cost: 4 + (i % 7) * 0.5 }),
  )
  const l = predictLineup(s)
  assert.equal(l.basis, 'price', 'with no minutes, selection falls back to squad value')
  assert.equal(l.starters.length, 11)
  assert.equal(l.starters.filter((x) => x.position === 'GK').length, 1, 'a side must have a keeper')
  assert.ok(l.confidence < 0.6, 'a price-based guess must not claim high confidence')
})

test('a squad too thin to name an eleven says so rather than inventing one', () => {
  const l = predictLineup([rate({ id: 1 }), rate({ id: 2 })])
  assert.equal(l.formation, 'unknown')
  assert.ok(l.starters.length < 11)
  assert.equal(l.confidence, 0)
})

// --- The data bugs behind the first attempt ---------------------------------

function player(over: Partial<NormPlayer> = {}): NormPlayer {
  return {
    id: 1, name: 'Target', fullName: 'Target Player', team: 'ARS', position: 'MID',
    minutes: 3420, starts: 38, goals: 10, assists: 5, yellowCards: 4, redCards: 0,
    saves: 0, cleanSheets: 10, xg: 9, xa: 4, xgConceded: 30,
    status: 'a', news: '', chanceNextRound: null, cost: 8, joinedAt: null,
    ...over,
  }
}

function gw(over: Partial<NormPlayerGw> = {}): NormPlayerGw {
  return {
    season: '2025-26', element: 1, round: 1, fixtureId: 1, team: 'ARS', opponent: 'CHE',
    wasHome: true, position: 'MID', minutes: 5, starts: 0, goals: 0, assists: 0, yellowCards: 0, redCards: 0,
    saves: 0, goalsConceded: 0, xg: 0, xa: 0, xgConceded: 0, date: Date.UTC(2025, 8, 1),
    ...over,
  }
}

test('player rates ignore previous seasons, because element ids are reassigned', () => {
  // The bug: FPL hands id 1 to a different player every season. Reading last
  // season's rows for this season's id gave each player a stranger's record —
  // it put the wrong eleven on the pitch entirely.
  const now = Date.UTC(2026, 7, 20)
  const impostorRows = Array.from({ length: 10 }, (_, i) =>
    gw({ season: '2025-26', element: 1, minutes: 5, round: i + 1, fixtureId: i + 1 }),
  )
  const rates = buildPlayerRates([player({ id: 1, minutes: 3420 })], impostorRows, now, '2026-27')
  assert.equal(rates.length, 1)
  // Must fall back to the snapshot's full-season minutes, not the 50 minutes
  // belonging to whoever held id 1 last season.
  assert.equal(rates[0]!.minutes, 3420)
  assert.ok(rates[0]!.minuteShare > 0.9, `share ${rates[0]!.minuteShare} — an ever-present should be ~1`)
})

test('current-season rows are used once there are enough of them', () => {
  const now = Date.UTC(2026, 9, 1)
  const rows = Array.from({ length: 6 }, (_, i) =>
    gw({
      season: '2026-27', element: 1, minutes: 90, round: i + 1, fixtureId: i + 1,
      date: Date.UTC(2026, 8, i + 1), xg: 0.5, yellowCards: 1,
    }),
  )
  const rates = buildPlayerRates([player({ id: 1, minutes: 3420, xg: 0 })], rows, now, '2026-27')
  assert.equal(rates[0]!.minutes, 540, 'six full matches this season')
  assert.ok(rates[0]!.xgi90 > 0.4, 'rates should come from live form, not the snapshot')
  assert.ok(rates[0]!.yellow90 > 0.9, 'card rate too')
})

test('the minute-share denominator is season-scoped, since fixture ids repeat', () => {
  // Fixture ids run 1-380 every season. Counting distinct ids across the whole
  // corpus inflated the denominator and crushed every minute share.
  const now = Date.UTC(2026, 9, 1)
  const thisSeason = Array.from({ length: 5 }, (_, i) =>
    gw({ season: '2026-27', element: 1, minutes: 90, fixtureId: i + 1, date: Date.UTC(2026, 8, i + 1) }),
  )
  // Same fixture ids, previous season — must not enlarge the denominator.
  const lastSeason = Array.from({ length: 5 }, (_, i) =>
    gw({ season: '2025-26', element: 99, minutes: 90, fixtureId: i + 1, date: Date.UTC(2025, 8, i + 1) }),
  )
  const rates = buildPlayerRates(
    [player({ id: 1, minutes: 0 })],
    [...thisSeason, ...lastSeason],
    now,
    '2026-27',
  )
  assert.ok(
    rates[0]!.minuteShare > 0.9,
    `an ever-present should read ~1, got ${rates[0]!.minuteShare}`,
  )
})

test('a player with no data anywhere gets zeroed rates rather than NaN', () => {
  const rates = buildPlayerRates([player({ id: 7, minutes: 0, xg: 0, xa: 0 })], [], Date.now(), '2026-27')
  const r = rates[0]!
  for (const v of [r.xgi90, r.xgc90, r.yellow90, r.red90, r.minuteShare]) {
    assert.ok(Number.isFinite(v), 'rates must never be NaN')
  }
  assert.equal(r.minuteShare, 0)
})

// --- Formations, transfers and what-if recomputation -------------------------

import { deriveShapes, shapeVariants, DEFAULT_SHAPE, type ShapeRow } from '../src/core/formation.ts'
import { recomputeFixture, applyEdits, probabilityDelta } from '../src/core/whatIf.ts'
import type { RecomputeInputs } from '../src/core/schema.ts'

/** A club's starting eleven for one match, as gameweek rows. */
function sheet(team: string, fixtureId: number, date: number, def: number, mid: number, fwd: number): ShapeRow[] {
  const rows: ShapeRow[] = [
    { team, fixtureId, season: '2025-26', date, position: 'GK', starts: 1, minutes: 90 },
  ]
  for (let i = 0; i < def; i++) rows.push({ team, fixtureId, season: '2025-26', date, position: 'DEF', starts: 1, minutes: 90 })
  for (let i = 0; i < mid; i++) rows.push({ team, fixtureId, season: '2025-26', date, position: 'MID', starts: 1, minutes: 90 })
  for (let i = 0; i < fwd; i++) rows.push({ team, fixtureId, season: '2025-26', date, position: 'FWD', starts: 1, minutes: 90 })
  // Substitutes appear in the data but did not start, so must not count.
  rows.push({ team, fixtureId, season: '2025-26', date, position: 'FWD', starts: 0, minutes: 12 })
  return rows
}

test('a club’s usual shape is read from the sides it actually started', () => {
  const now = Date.UTC(2026, 7, 20)
  const rows: ShapeRow[] = []
  for (let i = 0; i < 8; i++) rows.push(...sheet('ARS', i, now - (i + 1) * 7 * 86400000, 4, 3, 3))
  for (let i = 8; i < 10; i++) rows.push(...sheet('ARS', i, now - (i + 1) * 7 * 86400000, 3, 5, 2))
  const shape = deriveShapes(rows, now).get('ARS')!
  assert.equal(shape.label, '4-3-3')
  assert.equal(shape.def, 4)
  assert.ok(shape.share > 0.6, `share ${shape.share}`)
  assert.ok(shape.sample >= 10)
})

test('a recent change of system outweighs what a club used to do', () => {
  const now = Date.UTC(2026, 7, 20)
  const rows: ShapeRow[] = []
  // Long-ago: a back four, many times. Recently: a back three, a few times.
  for (let i = 0; i < 20; i++) rows.push(...sheet('CHE', i, now - (400 + i * 7) * 86400000, 4, 4, 2))
  for (let i = 20; i < 28; i++) rows.push(...sheet('CHE', i, now - (i - 19) * 7 * 86400000, 3, 4, 3))
  const shape = deriveShapes(rows, now).get('CHE')!
  assert.equal(shape.label, '3-4-3', 'recency weighting should surface the current system')
})

test('substitutes and malformed sheets do not distort the shape', () => {
  const now = Date.UTC(2026, 7, 20)
  const rows = [
    ...sheet('EVE', 1, now - 7 * 86400000, 4, 4, 2),
    // A nine-man sheet: incomplete data, must be discarded not counted.
    { team: 'EVE', fixtureId: 2, season: '2025-26', date: now - 14 * 86400000, position: 'DEF' as const, starts: 1, minutes: 90 },
  ]
  const shape = deriveShapes(rows, now).get('EVE')!
  assert.equal(shape.label, '4-4-2')
  assert.equal(shape.sample, 1, 'only the complete sheet counts')
})

test('shape variants agree with the headline shape', () => {
  // These are shown together; if they used different weighting the panel would
  // contradict itself.
  const now = Date.UTC(2026, 7, 20)
  const rows: ShapeRow[] = []
  for (let i = 0; i < 6; i++) rows.push(...sheet('LIV', i, now - (i + 1) * 7 * 86400000, 4, 5, 1))
  for (let i = 6; i < 8; i++) rows.push(...sheet('LIV', i, now - (i + 1) * 7 * 86400000, 3, 6, 1))
  const headline = deriveShapes(rows, now).get('LIV')!
  const variants = shapeVariants(rows, 'LIV', now)
  assert.equal(variants[0]!.label, headline.label, 'the most common variant is the headline shape')
})

test('the eleven matches the shape the club actually plays', () => {
  // The bug this replaces: a generic min/max rule produced Liverpool as 3-6-1,
  // a formation no side has ever set up in.
  const l = predictLineup(squad(), { def: 4, mid: 4, fwd: 2, label: '4-4-2', sample: 30, share: 0.7 })
  assert.equal(l.formation, '4-4-2')
  assert.equal(l.starters.filter((s) => s.position === 'DEF').length, 4)
  assert.equal(l.starters.filter((s) => s.position === 'MID').length, 4)
  assert.equal(l.starters.filter((s) => s.position === 'FWD').length, 2)
})

test('a different shape produces a different eleven from the same squad', () => {
  const s = squad()
  const flat = predictLineup(s, { def: 5, mid: 4, fwd: 1, label: '5-4-1', sample: 20, share: 0.6 })
  assert.equal(flat.formation, '5-4-1')
  assert.equal(flat.starters.filter((x) => x.position === 'DEF').length, 5)
  assert.notDeepEqual(
    flat.starters.map((x) => x.id),
    predictLineup(s, DEFAULT_SHAPE).starters.map((x) => x.id),
  )
})

test('a recent signing is flagged', () => {
  const now = Date.UTC(2026, 7, 20)
  const recent = new Date(now - 20 * 86400000).toISOString().slice(0, 10)
  const old = new Date(now - 900 * 86400000).toISOString().slice(0, 10)
  const s = squad((i) => (i === 8 ? { joinedAt: recent } : { joinedAt: old }))
  const l = predictLineup(s, DEFAULT_SHAPE, now)
  const flagged = [...l.starters, ...l.bench].filter((x) => x.newSigning)
  assert.equal(flagged.length, 1)
  assert.equal(flagged[0]!.id, 9)
})

// --- What-if ----------------------------------------------------------------

const INPUTS: RecomputeInputs = {
  intercept: Math.log(1.4),
  homeAdvantage: 0.25,
  homeAttackDev: 0,
  homeDefenceDev: 0,
  rho: -0.05,
  availabilityStrength: 0.3,
  home: {
    attack: 0.3, defence: 0.2, ratingSd: 0.12, matchesPlayed: 10, promoted: false,
    rest: { daysRest: 7, matchesIn14Days: 1, logShift: 0, note: null },
    shape: DEFAULT_SHAPE,
  },
  away: {
    attack: -0.1, defence: -0.05, ratingSd: 0.12, matchesPlayed: 10, promoted: false,
    rest: { daysRest: 7, matchesIn14Days: 1, logShift: 0, note: null },
    shape: DEFAULT_SHAPE,
  },
}

test('removing a player marks him unavailable rather than deleting him', () => {
  // Squad depth has to survive the edit: losing a striker from a deep squad
  // should hurt less than losing one from a thin squad, and deleting the row
  // outright would throw that information away.
  const s = squad()
  const edited = applyEdits(s, new Set([3]))
  assert.equal(edited.length, s.length, 'the squad keeps its size')
  assert.equal(edited.find((p) => p.id === 3)!.status, 'u')
  assert.equal(edited.find((p) => p.id === 4)!.status, 'a', 'others are untouched')
})

test('an edit can also force an injured player back in', () => {
  const s = squad((i) => (i === 2 ? { status: 'i' } : {}))
  const edited = applyEdits(s, new Set(), new Set([3]))
  assert.equal(edited.find((p) => p.id === 3)!.status, 'a')
})

test('with no edits, recomputation reproduces the original forecast exactly', () => {
  // This is the guarantee that makes the feature trustworthy: the browser runs
  // the same model, so an untouched squad must give an identical answer.
  const home = squad()
  const away = squad((i) => ({ id: 100 + i, name: `A${i}` }))
  const a = recomputeFixture({
    inputs: INPUTS, homeCode: 'ARS', awayCode: 'CHE', kickoff: Date.UTC(2026, 7, 21),
    homeSquad: home, awaySquad: away, removed: new Set(),
  })
  const b = recomputeFixture({
    inputs: INPUTS, homeCode: 'ARS', awayCode: 'CHE', kickoff: Date.UTC(2026, 7, 21),
    homeSquad: home, awaySquad: away, removed: new Set(),
  })
  assert.deepEqual(a.prediction.probs, b.prediction.probs)
  const d = probabilityDelta(a.prediction.probs, b.prediction.probs)
  assert.equal(d.largest, 0)
})

test('removing a key player moves the forecast against his side', () => {
  const home = squad((i) => (i === 14 ? { xgi90: 1.1, minuteShare: 0.95 } : {}))
  const away = squad((i) => ({ id: 100 + i, name: `A${i}` }))
  const before = recomputeFixture({
    inputs: INPUTS, homeCode: 'ARS', awayCode: 'CHE', kickoff: Date.UTC(2026, 7, 21),
    homeSquad: home, awaySquad: away, removed: new Set(),
  })
  const after = recomputeFixture({
    inputs: INPUTS, homeCode: 'ARS', awayCode: 'CHE', kickoff: Date.UTC(2026, 7, 21),
    homeSquad: home, awaySquad: away, removed: new Set([15]),
  })
  assert.ok(
    after.prediction.probs.home < before.prediction.probs.home,
    'losing the best attacker must lower his side’s win probability',
  )
  assert.ok(after.prediction.expectedGoalsHome < before.prediction.expectedGoalsHome)
  assert.ok(!after.homeLineup.starters.some((s) => s.id === 15), 'and he cannot still be in the side')
})

test('the eleven reshapes around a removal', () => {
  const home = squad()
  const away = squad((i) => ({ id: 100 + i, name: `A${i}` }))
  const keeper = predictLineup(home, DEFAULT_SHAPE).starters.find((s) => s.position === 'GK')!
  const after = recomputeFixture({
    inputs: INPUTS, homeCode: 'ARS', awayCode: 'CHE', kickoff: Date.UTC(2026, 7, 21),
    homeSquad: home, awaySquad: away, removed: new Set([keeper.id]),
  })
  const keepers = after.homeLineup.starters.filter((s) => s.position === 'GK')
  assert.equal(keepers.length, 1, 'the reserve keeper comes in')
  assert.notEqual(keepers[0]!.id, keeper.id)
})

test('probabilities stay a valid distribution however much is removed', () => {
  const home = squad()
  const away = squad((i) => ({ id: 100 + i, name: `A${i}` }))
  const wipeout = new Set(home.slice(0, 12).map((p) => p.id))
  const r = recomputeFixture({
    inputs: INPUTS, homeCode: 'ARS', awayCode: 'CHE', kickoff: Date.UTC(2026, 7, 21),
    homeSquad: home, awaySquad: away, removed: wipeout,
  })
  const { home: h, draw: d, away: a } = r.prediction.probs
  assert.ok(Math.abs(h + d + a - 1) < 0.002, `sums to ${h + d + a}`)
  for (const v of [h, d, a]) assert.ok(v >= 0 && v <= 1)
})

// --- The ledger's sealing rule ----------------------------------------------
//
// These encode the rule directly, because it is the one thing that makes the
// accuracy record mean anything and it is invisible in the UI.

import type { LedgerEntry } from '../src/core/schema.ts'

/** The rule as implemented by the build: revise until kickoff, then seal. */
function applyForecast(
  entry: LedgerEntry | undefined,
  forecast: { probs: { home: number; draw: number; away: number } },
  now: number,
  kickoff: number,
): { entry: LedgerEntry | undefined; changed: boolean } {
  const kickedOff = kickoff <= now
  if (!entry && !kickedOff) {
    return {
      entry: {
        season: '2026-27', gameweek: 1, fixtureId: 1, home: 'ARS', away: 'CHE',
        kickoff, predictedAt: now, updatedAt: now, revisions: 0,
        probs: forecast.probs, lambdaHome: 1.5, lambdaAway: 1.1,
        predictedScore: { home: 1, away: 1 },
      },
      changed: true,
    }
  }
  if (entry && !kickedOff && !entry.actual) {
    const moved = Math.abs(entry.probs.home - forecast.probs.home) > 1e-6
    entry.probs = forecast.probs
    entry.updatedAt = now
    if (moved) entry.revisions = (entry.revisions ?? 0) + 1
    return { entry, changed: moved }
  }
  return { entry, changed: false }
}

test('a forecast can be revised while the match is still to come', () => {
  // The model refits weekly and gets fixed when it is wrong. Freezing the
  // first version would score a model that no longer exists — which nearly
  // happened: every gameweek-one entry was written before a player-rates bug
  // was found, holding Arsenal at 53.7% when the fix said 66.7%.
  const kickoff = Date.UTC(2026, 7, 21, 19)
  const first = applyForecast(undefined, { probs: { home: 0.537, draw: 0.28, away: 0.183 } }, kickoff - 86400000, kickoff)
  const revised = applyForecast(first.entry, { probs: { home: 0.667, draw: 0.22, away: 0.113 } }, kickoff - 3600000, kickoff)
  assert.equal(revised.entry!.probs.home, 0.667)
  assert.equal(revised.entry!.revisions, 1, 'and the revision is recorded, not hidden')
})

test('a forecast is sealed the moment the match kicks off', () => {
  const kickoff = Date.UTC(2026, 7, 21, 19)
  const before = applyForecast(undefined, { probs: { home: 0.6, draw: 0.25, away: 0.15 } }, kickoff - 3600000, kickoff)
  // One second after kickoff, nothing may change it again.
  const after = applyForecast(before.entry, { probs: { home: 0.99, draw: 0.005, away: 0.005 } }, kickoff + 1000, kickoff)
  assert.equal(after.entry!.probs.home, 0.6, 'a forecast must never move once the match has started')
  assert.equal(after.changed, false)
})

test('a scored entry is never rewritten, even before a later kickoff', () => {
  const kickoff = Date.UTC(2026, 7, 21, 19)
  const entry = applyForecast(undefined, { probs: { home: 0.6, draw: 0.25, away: 0.15 } }, kickoff - 86400000, kickoff).entry!
  entry.actual = {
    homeGoals: 2, awayGoals: 1, outcome: 'home', rps: 0.1, logLoss: 0.5,
    brier: 0.3, correctOutcome: true, correctScore: false,
  }
  const attempt = applyForecast(entry, { probs: { home: 0.95, draw: 0.03, away: 0.02 } }, kickoff - 3600000, kickoff)
  assert.equal(attempt.entry!.probs.home, 0.6, 'a result already in the ledger freezes the forecast')
})

test('no entry is created for a match that has already started', () => {
  const kickoff = Date.UTC(2026, 7, 21, 19)
  const r = applyForecast(undefined, { probs: { home: 0.6, draw: 0.25, away: 0.15 } }, kickoff + 60000, kickoff)
  assert.equal(r.entry, undefined, 'a forecast made after kickoff is not a forecast')
})

// --- Transfer detection ------------------------------------------------------
//
// A move is invisible in one snapshot — the feed reports where a player is,
// never that he moved — so this is entirely a diffing problem, and the ways it
// goes wrong are all about identity.

import {
  diffRosters, takeSnapshot, mergeTransfers, recentTransfers, classifyArrival,
  IMPLAUSIBLE_CHANGE_COUNT, type RosterPlayer,
} from '../src/core/transfers.ts'

function roster(over: Partial<RosterPlayer> = {}): RosterPlayer {
  return { id: 1, name: 'Player', team: 'ARS', position: 'MID', cost: 6, joinedAt: null, ...over }
}

const NOW = Date.UTC(2026, 7, 25)

test('a club change is detected as a move', () => {
  const before = takeSnapshot([roster({ id: 1, name: 'Haaland', team: 'BOU' })], '2026-27', NOW - 86400000)
  const after = [roster({ id: 1, name: 'Haaland', team: 'MCI', joinedAt: '2026-08-24' })]
  const { records, rejected } = diffRosters(before, after, '2026-27', NOW)
  assert.equal(rejected, null)
  assert.equal(records.length, 1)
  assert.equal(records[0]!.kind, 'moved')
  assert.equal(records[0]!.from, 'BOU')
  assert.equal(records[0]!.to, 'MCI')
  assert.equal(records[0]!.joinedAt, '2026-08-24')
})

test('an arrival and a departure are distinguished from a move', () => {
  const before = takeSnapshot(
    [roster({ id: 1, name: 'Stays' }), roster({ id: 2, name: 'Leaves', team: 'CHE' })],
    '2026-27', NOW - 86400000,
  )
  const after = [
    roster({ id: 1, name: 'Stays' }),
    // A recent join date is what makes this a signing rather than a listing.
    roster({ id: 3, name: 'Arrives', team: 'LIV', joinedAt: '2026-08-20' }),
  ]
  const { records } = diffRosters(before, after, '2026-27', NOW)
  const byKind = Object.fromEntries(records.map((r) => [r.name, r]))
  assert.equal(byKind['Arrives']!.kind, 'arrived')
  assert.equal(byKind['Arrives']!.from, null)
  assert.equal(byKind['Leaves']!.kind, 'left')
  assert.equal(byKind['Leaves']!.to, null)
  assert.ok(!byKind['Stays'], 'an unchanged player is not a transfer')
})

test('a season rollover reports nothing, because ids are reassigned', () => {
  // Element ids do not survive a season change, so diffing across one would
  // report every player in the league as having moved.
  const before = takeSnapshot([roster({ id: 1, team: 'ARS' })], '2025-26', NOW - 86400000)
  const after = [roster({ id: 1, team: 'CHE' })]
  const { records, rejected } = diffRosters(before, after, '2026-27', NOW)
  assert.equal(records.length, 0)
  assert.match(rejected ?? '', /season changed/)
})

test('the first run establishes a baseline instead of inventing transfers', () => {
  const { records, rejected } = diffRosters(null, [roster()], '2026-27', NOW)
  assert.equal(records.length, 0)
  assert.equal(rejected, null)
})

test('an implausible number of changes is treated as a feed problem', () => {
  // Even a chaotic deadline day moves a handful of players. A hundred at once
  // means the source changed shape, and writing that in would bury real moves.
  const before = takeSnapshot(
    Array.from({ length: IMPLAUSIBLE_CHANGE_COUNT + 5 }, (_, i) => roster({ id: i, team: 'ARS' })),
    '2026-27', NOW - 86400000,
  )
  const after = Array.from({ length: IMPLAUSIBLE_CHANGE_COUNT + 5 }, (_, i) => roster({ id: i, team: 'CHE' }))
  const { records, rejected } = diffRosters(before, after, '2026-27', NOW)
  assert.equal(records.length, 0)
  assert.match(rejected ?? '', /feed problem/)
})

test('a move is not re-recorded on every subsequent run', () => {
  const move = {
    playerId: 1, name: 'Haaland', kind: 'moved' as const, from: 'BOU', to: 'MCI',
    joinedAt: '2026-08-24', detectedAt: NOW, position: 'FWD', cost: 15,
  }
  const once = mergeTransfers([], [move])
  const twice = mergeTransfers(once, [{ ...move, detectedAt: NOW + 86400000 }])
  assert.equal(twice.length, 1, 'the same move must not accumulate day after day')
})

test('the ledger stays bounded and newest-first', () => {
  const many = Array.from({ length: 400 }, (_, i) => ({
    playerId: i, name: `P${i}`, kind: 'moved' as const, from: 'ARS', to: 'CHE',
    joinedAt: null, detectedAt: NOW + i, position: 'MID', cost: 5,
  }))
  const merged = mergeTransfers([], many, 300)
  assert.equal(merged.length, 300)
  assert.ok(merged[0]!.detectedAt > merged[299]!.detectedAt, 'newest first')
})

test('recent transfers respect their window', () => {
  const records = [
    { playerId: 1, name: 'New', kind: 'moved' as const, from: 'ARS', to: 'CHE', joinedAt: null, detectedAt: NOW - 5 * 86400000, position: 'MID', cost: 5 },
    { playerId: 2, name: 'Old', kind: 'moved' as const, from: 'ARS', to: 'CHE', joinedAt: null, detectedAt: NOW - 200 * 86400000, position: 'MID', cost: 5 },
  ]
  const recent = recentTransfers(records, NOW, 60)
  assert.equal(recent.length, 1)
  assert.equal(recent[0]!.name, 'New')
})

test('a player appearing in the feed is not assumed to have signed', () => {
  // The source periodically expands its roster, and a squad player who was
  // previously unlisted looks exactly like an arrival. Mudryk turning up with
  // a join date of January 2023 has plainly not just moved.
  assert.equal(classifyArrival('2026-08-08', NOW), 'arrived')
  assert.equal(classifyArrival('2023-01-15', NOW), 'listed')
  assert.equal(classifyArrival(null, NOW), 'listed')
  assert.equal(classifyArrival('not a date', NOW), 'listed')
})

test('arrivals are classified when the diff runs, not afterwards', () => {
  const before = takeSnapshot([roster({ id: 1, name: 'Existing' })], '2026-27', NOW - 86400000)
  const after = [
    roster({ id: 1, name: 'Existing' }),
    roster({ id: 2, name: 'Signing', team: 'CHE', joinedAt: '2026-08-20' }),
    roster({ id: 3, name: 'Reappeared', team: 'CHE', joinedAt: '2023-01-15' }),
  ]
  const { records } = diffRosters(before, after, '2026-27', NOW)
  const byName = Object.fromEntries(records.map((r) => [r.name, r.kind]))
  assert.equal(byName['Signing'], 'arrived')
  assert.equal(byName['Reappeared'], 'listed')
})

// --- Reading a team sheet ----------------------------------------------------
//
// The feed's short forms are chosen for a fantasy game, not for matching what
// a broadcaster prints. These are the real cases.

import { matchName, matchLineup, normalizeName, type MatchCandidate } from '../src/core/lineupMatch.ts'
import { confirmedShift, applyConfirmedLineup, BENCH_MINUTE_SHARE } from '../src/core/whatIf.ts'

const LIVERPOOL: MatchCandidate[] = [
  { id: 1, name: 'A.Becker', fullName: 'Alisson Becker', position: 'GK' },
  { id: 2, name: 'Virgil', fullName: 'Virgil van Dijk', position: 'DEF' },
  { id: 3, name: 'Kerkez', fullName: 'Milos Kerkez', position: 'DEF' },
  { id: 4, name: 'Mac Allister', fullName: 'Alexis Mac Allister', position: 'MID' },
  { id: 5, name: 'Szoboszlai', fullName: 'Dominik Szoboszlai', position: 'MID' },
  { id: 6, name: 'Gakpo', fullName: 'Cody Gakpo', position: 'MID' },
]

test('a broadcast name reaches the right player despite the feed short form', () => {
  // Neither string contains the other in either of these.
  assert.equal(matchName('Van Dijk', LIVERPOOL).player?.id, 2)
  assert.equal(matchName('Alisson', LIVERPOOL).player?.id, 1)
  // And the straightforward ones still work.
  assert.equal(matchName('Kerkez', LIVERPOOL).player?.id, 3)
  assert.equal(matchName('Mac Allister', LIVERPOOL).player?.id, 4)
})

test('initials, accents and punctuation do not defeat a match', () => {
  const squad: MatchCandidate[] = [
    { id: 1, name: 'B.Fernandes', fullName: 'Bruno Borges Fernandes', position: 'MID' },
    { id: 2, name: 'Nørgaard', fullName: 'Christian Nørgaard', position: 'MID' },
    { id: 3, name: 'Matheus N.', fullName: 'Matheus Nunes', position: 'MID' },
  ]
  assert.equal(matchName('Bruno Fernandes', squad).player?.id, 1)
  assert.equal(matchName('B. Fernandes', squad).player?.id, 1)
  assert.equal(matchName('Norgaard', squad).player?.id, 2)
  assert.equal(matchName('Matheus Nunes', squad).player?.id, 3)
})

test('normalising strips what varies between a graphic and a feed', () => {
  assert.equal(normalizeName('Nørgaard'), 'norgaard')
  assert.equal(normalizeName('B.Fernandes'), 'b fernandes')
  assert.equal(normalizeName("O'Reilly"), 'o reilly')
  assert.equal(normalizeName('  Van   Dijk  '), 'van dijk')
  // Never throws on whatever a vision model returned.
  assert.equal(normalizeName(null), '')
  assert.equal(normalizeName(undefined), '')
})

test('two players who cannot be told apart are reported, not guessed', () => {
  // Guessing here puts the wrong player in an eleven and moves a forecast with
  // nothing on screen to say so.
  const squad: MatchCandidate[] = [
    { id: 1, name: 'Neves', fullName: 'Lucas Neves', position: 'MID' },
    { id: 2, name: 'Neves', fullName: 'Ruben Neves', position: 'MID' },
  ]
  const m = matchName('Neves', squad)
  assert.equal(m.player, null)
  assert.equal(m.quality, 'ambiguous')
  assert.equal(m.alternatives.length, 2)
  // A first name resolves it.
  assert.equal(matchName('Ruben Neves', squad).player?.id, 2)
})

test('a name that is not in the squad is left unresolved rather than forced', () => {
  const m = matchName('Mbappé', LIVERPOOL)
  assert.equal(m.player, null)
  assert.equal(m.quality, 'none')
})

test('two printed names cannot collapse onto the same player', () => {
  // Without claiming, a near-miss could take a player already matched exactly,
  // silently producing a ten-man side.
  const result = matchLineup(['Virgil', 'Van Dijk', 'Gakpo'], LIVERPOOL)
  const ids = result.matched.map((m) => m.player.id)
  assert.equal(new Set(ids).size, ids.length, 'no player is used twice')
  assert.equal(result.matched.length + result.unresolved.length, 3)
})

test('a whole printed eleven resolves, reported in the order given', () => {
  const printed = ['Alisson', 'Van Dijk', 'Kerkez', 'Szoboszlai', 'Mac Allister', 'Gakpo']
  const result = matchLineup(printed, LIVERPOOL)
  assert.equal(result.matched.length, 6)
  assert.equal(result.unresolved.length, 0)
  assert.deepEqual(result.matched.map((m) => m.query), printed)
})

// --- Applying a confirmed sheet ---------------------------------------------

test('a confirmed sheet promotes the eleven and benches the rest', () => {
  const s = squad()
  const starting = new Set(s.slice(0, 11).map((p) => p.id))
  const applied = applyConfirmedLineup(s, starting)
  for (const p of applied) {
    if (starting.has(p.id)) assert.equal(p.minuteShare, 1, `${p.name} starts`)
    else assert.ok(p.minuteShare <= BENCH_MINUTE_SHARE, `${p.name} is on the bench`)
  }
})

test('a substitute is not written off entirely', () => {
  // Zeroing the bench would overstate every team sheet: substitutes come on
  // and score.
  assert.ok(BENCH_MINUTE_SHARE > 0 && BENCH_MINUTE_SHARE < 0.3)
})

test('an empty sheet changes nothing', () => {
  const s = squad()
  const applied = applyConfirmedLineup(s, new Set())
  assert.deepEqual(applied.map((p) => p.minuteShare), s.map((p) => p.minuteShare))
})

test('a strong eleven lifts a side and a rotated one lowers it', () => {
  // This is the bug the first attempt had: teamAvailability derives its
  // baseline from the squad handed to it, so scaling everyone's minutes moved
  // numerator and denominator together and changed nothing at all. A confirmed
  // sheet has to be measured against what was *expected*.
  // Every player in the default squad has the same involvement rate, so which
  // eleven starts would not change the total at all — the test has to give
  // them different quality to mean anything.
  const s = squad((i) => ({ xgi90: 0.9 - i * 0.045 }))
  const best = [...s].sort((a, b) => b.xgi90 * b.minuteShare - a.xgi90 * a.minuteShare).slice(0, 11)
  const worst = [...s].sort((a, b) => a.xgi90 * a.minuteShare - b.xgi90 * b.minuteShare).slice(0, 11)

  const strong = confirmedShift(s, applyConfirmedLineup(s, new Set(best.map((p) => p.id))), 0.3)
  const rotated = confirmedShift(s, applyConfirmedLineup(s, new Set(worst.map((p) => p.id))), 0.3)

  assert.ok(strong.attack > rotated.attack, 'the stronger eleven must rate higher')
  assert.ok(rotated.attack < 0, 'a heavily rotated side is a downgrade on expectation')
})

test('the confirmed shift stays bounded however odd the sheet', () => {
  const s = squad()
  const oneMan = confirmedShift(s, applyConfirmedLineup(s, new Set([s[0]!.id])), 1)
  assert.ok(Math.abs(oneMan.attack) <= Math.abs(Math.log(0.7)) + 1e-9, 'clamped')
  assert.ok(Number.isFinite(oneMan.defence))
})

test('no confirmed sheet leaves the forecast exactly as published', () => {
  const home = squad()
  const away = squad((i) => ({ id: 100 + i, name: `A${i}` }))
  const base = recomputeFixture({
    inputs: INPUTS, homeCode: 'ARS', awayCode: 'CHE', kickoff: Date.UTC(2026, 7, 21),
    homeSquad: home, awaySquad: away, removed: new Set(),
  })
  const withNull = recomputeFixture({
    inputs: INPUTS, homeCode: 'ARS', awayCode: 'CHE', kickoff: Date.UTC(2026, 7, 21),
    homeSquad: home, awaySquad: away, removed: new Set(), confirmed: null,
  })
  assert.deepEqual(base.prediction.probs, withNull.prediction.probs)
})

// --- Ledger identity and scoring eligibility ---------------------------------
//
// Both of these went wrong in this repo, and both fail quietly and in the
// flattering direction, which is the worst combination for a record whose only
// purpose is to be honest about how the model is doing.

import { ledgerKey, isScorable } from '../src/core/ledger.ts'

test('fixtures without a feed id do not collapse onto one ledger key', () => {
  const season = '2026-27'
  // No feed id: this is the case where the results source is carrying the
  // season alone. Previously every such fixture keyed as "2026-27#0".
  const a = ledgerKey({ season, fixtureId: 0, home: 'ARS', away: 'COV' })
  const b = ledgerKey({ season, fixtureId: 0, home: 'LIV', away: 'HUL' })
  assert.notEqual(a, b, 'two different fixtures must never share a ledger key')

  // The reverse fixture later in the season is a different match too.
  assert.notEqual(a, ledgerKey({ season, fixtureId: 0, home: 'COV', away: 'ARS' }))

  // A feed id still wins when there is one, and is stable across runs.
  assert.equal(
    ledgerKey({ season, fixtureId: 12, home: 'ARS', away: 'COV' }),
    ledgerKey({ season, fixtureId: 12, home: 'ARS', away: 'COV' }),
  )
  // The same fixture number in a different season is a different match.
  assert.notEqual(
    ledgerKey({ season: '2025-26', fixtureId: 12, home: 'ARS', away: 'COV' }),
    ledgerKey({ season: '2026-27', fixtureId: 12, home: 'ARS', away: 'COV' }),
  )
})

test('a finished fixture with no sealed forecast is left out of the record', () => {
  // The whole point: there is no "score it against what we think now" path.
  // That model has already been refitted on this very result.
  assert.equal(isScorable(undefined), false)
  assert.equal(isScorable(null), false)
  assert.equal(isScorable({}), false, 'an entry with no stored probabilities is not a forecast')
  assert.equal(isScorable({ probs: { home: 0.6, draw: 0.25, away: 0.15 } }), true)
})
