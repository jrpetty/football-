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
    id: 1, name: 'P', team: 'ARS', position: 'MID',
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
  homeAdvantage: 0.25,
  rho: -0.05,
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
