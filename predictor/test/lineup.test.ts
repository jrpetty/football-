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
    status: 'a', chanceNextRound: null, news: '', cost: 6, minuteShare: 0.7,
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
    status: 'a', news: '', chanceNextRound: null, cost: 8,
    ...over,
  }
}

function gw(over: Partial<NormPlayerGw> = {}): NormPlayerGw {
  return {
    season: '2025-26', element: 1, round: 1, fixtureId: 1, team: 'ARS', opponent: 'CHE',
    wasHome: true, minutes: 5, starts: 0, goals: 0, assists: 0, yellowCards: 0, redCards: 0,
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
