// Domain rules: club identity, disciplinary thresholds, availability, parsing.
// Several of these encode bugs that actually occurred during development.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { normalizeTeam, tryNormalizeTeam, canonicalizeName, clubName, CLUBS } from '../src/config/teams.ts'
import { nextThreshold, cardStatus, bookingProbability, redCardBan, CARD_THRESHOLDS } from '../src/core/suspensions.ts'
import { teamAvailability, playProbability, absenceReason, type PlayerRates } from '../src/core/availability.ts'
import { restProfile } from '../src/core/congestion.ts'
import { promotedPrior, championshipTable, MEASURED_PROMOTED_PRIOR } from '../src/core/promoted.ts'
import { parseCsv, parseCsvRows, num, bool } from '../scripts/lib/csv.ts'
import { parsePyRepr, parseFixtureStats } from '../scripts/lib/pyrepr.ts'
import { playerProps } from '../src/core/playerProps.ts'

// --- Club identity -----------------------------------------------------------

test('the same club resolves to one code across every source spelling', () => {
  // This is the bug that split clubs in two and degraded an early backtest.
  const city = ['Manchester City', 'Manchester City FC', 'Man City', 'MCI']
  const codes = new Set(city.map(normalizeTeam))
  assert.equal(codes.size, 1, `expected one code, got ${[...codes].join(', ')}`)
  assert.equal([...codes][0], 'MCI')

  assert.equal(normalizeTeam('Tottenham Hotspur FC'), normalizeTeam('Spurs'))
  assert.equal(normalizeTeam('Brighton & Hove Albion'), normalizeTeam('Brighton and Hove Albion FC'))
  assert.equal(normalizeTeam("Nott'm Forest"), normalizeTeam('Nottingham Forest FC'))
  assert.equal(normalizeTeam('AFC Bournemouth'), normalizeTeam('Bournemouth'))
  assert.equal(normalizeTeam('Wolverhampton Wanderers FC'), normalizeTeam('Wolves'))
  assert.equal(normalizeTeam('Sunderland AFC'), normalizeTeam('Sunderland'))
  assert.equal(normalizeTeam('Hull City AFC'), normalizeTeam('Hull City'))
})

test('canonicalising strips only the noise that varies between sources', () => {
  assert.equal(canonicalizeName('Fulham FC'), 'fulham')
  assert.equal(canonicalizeName('Brighton & Hove Albion'), 'brighton and hove albion')
  assert.equal(canonicalizeName("Nott'm Forest"), "nott'm forest")
})

test('an unknown club fails loudly rather than being invented', () => {
  assert.throws(() => normalizeTeam('Real Madrid'), /Unknown club/)
  assert.equal(tryNormalizeTeam('Real Madrid'), null)
})

test('club codes are unique and every alias resolves back to its own club', () => {
  const codes = CLUBS.map((c) => c.code)
  assert.equal(new Set(codes).size, codes.length, 'duplicate club code')
  for (const c of CLUBS) {
    assert.equal(normalizeTeam(c.name), c.code)
    for (const alias of c.aliases) assert.equal(normalizeTeam(alias), c.code, `alias "${alias}"`)
  }
})

test('clubName round-trips through a code', () => {
  assert.equal(clubName(normalizeTeam('Manchester United FC')), 'Man Utd')
})

// --- Suspensions -------------------------------------------------------------

test('booking thresholds fire at the right counts', () => {
  assert.equal(nextThreshold(0, 1)?.yellows, 5)
  assert.equal(nextThreshold(4, 10)?.yellows, 5)
  // At five the first ban is served; the next risk is ten, not another five.
  assert.equal(nextThreshold(5, 10)?.yellows, 10)
  assert.equal(nextThreshold(9, 20)?.yellows, 10)
  assert.equal(nextThreshold(10, 20)?.yellows, 15)
  assert.equal(nextThreshold(15, 35)?.yellows, 20)
})

test('a threshold whose deadline has passed can no longer bite', () => {
  // Five bookings only carries a ban before the club's 19th league match.
  assert.equal(nextThreshold(4, 19)?.yellows, 10)
  assert.equal(nextThreshold(4, 25)?.yellows, 10)
  // Ten only counts before the 32nd.
  assert.equal(nextThreshold(9, 32)?.yellows, 15)
  // Fifteen and twenty have no deadline.
  assert.equal(nextThreshold(14, 37)?.yellows, 15)
  assert.equal(nextThreshold(20, 37), null)
})

test('ban lengths follow the published thresholds', () => {
  assert.deepEqual(
    CARD_THRESHOLDS.map((t) => [t.yellows, t.banMatches]),
    [[5, 1], [10, 2], [15, 3], [20, 4]],
  )
})

test('a player one booking away is flagged, and the ban risk is the booking risk', () => {
  const s = cardStatus({
    playerId: 1, name: 'Test', team: 'ARS',
    yellows: 4, reds: 0, clubMatchesPlayed: 12,
    yellowPer90: 0.4, expectedMinutes: 90,
  })
  assert.equal(s.yellowsToBan, 1)
  assert.equal(s.onBrink, true)
  assert.equal(s.banProbability, s.bookingProbability)
  assert.ok(s.bookingProbability > 0.3 && s.bookingProbability < 0.35)
})

test('a player two bookings away carries no immediate ban risk', () => {
  const s = cardStatus({
    playerId: 2, name: 'Test', team: 'ARS',
    yellows: 3, reds: 0, clubMatchesPlayed: 12,
    yellowPer90: 0.4, expectedMinutes: 90,
  })
  assert.equal(s.onBrink, false)
  assert.equal(s.banProbability, 0)
  assert.ok(s.bookingProbability > 0, 'still able to be booked, just not banned')
})

test('a season starts with everyone clear', () => {
  // The bug this guards: pre-season data carries last season's totals, which
  // once had half a squad reading as one booking from a ban in gameweek one.
  const s = cardStatus({
    playerId: 3, name: 'Test', team: 'ARS',
    yellows: 0, reds: 0, clubMatchesPlayed: 0,
    yellowPer90: 0.3, expectedMinutes: 90,
  })
  assert.equal(s.onBrink, false)
  assert.equal(s.yellowsToBan, 5)
  assert.equal(s.banProbability, 0)
})

test('booking probability scales with minutes on the pitch', () => {
  const full = bookingProbability(0.4, 90)
  const cameo = bookingProbability(0.4, 20)
  assert.ok(full > cameo)
  assert.equal(bookingProbability(0.4, 0), 0)
  assert.equal(bookingProbability(0, 90), 0)
})

test('an unrecorded red card assumes the one-match minimum and says so', () => {
  const unknown = redCardBan()
  assert.equal(unknown.matches, 1)
  assert.equal(unknown.assumed, true)
  assert.match(unknown.note, /not recorded/i)
  assert.equal(redCardBan('violent-conduct').matches, 3)
  assert.equal(redCardBan('violent-conduct').assumed, false)
  assert.equal(redCardBan('second-yellow').matches, 1)
})

// --- Availability ------------------------------------------------------------

function player(over: Partial<PlayerRates> = {}): PlayerRates {
  return {
    id: 1, name: 'P', fullName: 'P Player', team: 'ARS', position: 'MID',
    minutes: 2000, xgi90: 0.4, xgc90: 1.1, saves90: 0, yellow90: 0.2, red90: 0.01,
    status: 'a', chanceNextRound: null, news: '', cost: 7, minuteShare: 0.8, joinedAt: null,
    ...over,
  }
}

test('availability reads the feed’s status flags', () => {
  assert.equal(playProbability({ status: 'a', chanceNextRound: null }), 1)
  assert.equal(playProbability({ status: 'i', chanceNextRound: null }), 0)
  assert.equal(playProbability({ status: 's', chanceNextRound: null }), 0)
  assert.equal(playProbability({ status: 'd', chanceNextRound: null }), 0.5)
  // An explicit percentage always wins over the flag.
  assert.equal(playProbability({ status: 'd', chanceNextRound: 75 }), 0.75)
  assert.equal(absenceReason('s'), 'suspended')
  assert.equal(absenceReason('i'), 'injured')
})

test('a full-strength squad gets no adjustment', () => {
  const squad = Array.from({ length: 18 }, (_, i) => player({ id: i, name: `P${i}` }))
  const a = teamAvailability('ARS', squad)
  assert.equal(a.unknown, false)
  assert.equal(a.missing.length, 0)
  assert.ok(Math.abs(a.attackMultiplier - 1) < 1e-9)
  assert.equal(a.logAttackShift, 0)
})

test('losing the best attacker lowers the multiplier and names him', () => {
  const squad = [
    player({ id: 1, name: 'Star', xgi90: 1.2, minuteShare: 0.95, status: 'i', news: 'Hamstring' }),
    ...Array.from({ length: 17 }, (_, i) => player({ id: i + 2, name: `P${i}`, xgi90: 0.25 })),
  ]
  const a = teamAvailability('ARS', squad)
  assert.ok(a.attackMultiplier < 1, 'attack should be reduced')
  assert.ok(a.logAttackShift < 0)
  assert.equal(a.missing[0]?.name, 'Star')
  assert.ok(a.missing[0]!.attackShare > 0.1, 'his share of team value should be substantial')
})

test('a defensive absence raises the opponent’s scoring rate, not its own', () => {
  const squad = [
    player({ id: 1, name: 'CB', position: 'DEF', cost: 12, minuteShare: 0.95, status: 'i' }),
    ...Array.from({ length: 17 }, (_, i) => player({ id: i + 2, position: 'DEF', cost: 4 })),
  ]
  const a = teamAvailability('ARS', squad)
  assert.ok(a.defenceMultiplier < 1)
  // Sign convention: a weaker defence is a positive shift on the opponent.
  assert.ok(a.logDefenceShift > 0)
})

test('a squad with too little data reports unknown rather than guessing', () => {
  const a = teamAvailability('ARS', [player({ minutes: 10 })])
  assert.equal(a.unknown, true)
  assert.equal(a.attackMultiplier, 1)
})

test('multipliers stay inside their clamps even in the worst case', () => {
  const squad = Array.from({ length: 18 }, (_, i) =>
    player({ id: i, xgi90: 1.5, minuteShare: 1, status: 'i' }),
  )
  const a = teamAvailability('ARS', squad)
  assert.ok(a.attackMultiplier >= 0.72 && a.attackMultiplier <= 1.06)
})

// --- Rest --------------------------------------------------------------------

test('rest profile penalises a short turnaround and ignores a full week', () => {
  const day = 86400000
  const kickoff = 1_700_000_000_000
  const fresh = restProfile(kickoff, [kickoff - 8 * day])
  assert.equal(fresh.logShift, 0)
  assert.equal(fresh.note, null)

  const tired = restProfile(kickoff, [kickoff - 3 * day])
  assert.ok(tired.logShift < 0)
  assert.ok(tired.note?.includes('3 days'))

  // Future fixtures must never count as rest already taken.
  const ignoresFuture = restProfile(kickoff, [kickoff + 5 * day])
  assert.equal(ignoresFuture.daysRest, null)
})

test('a congested run compounds the penalty', () => {
  const day = 86400000
  const k = 1_700_000_000_000
  const congested = restProfile(k, [k - 3 * day, k - 7 * day, k - 11 * day])
  const single = restProfile(k, [k - 3 * day])
  assert.ok(congested.logShift < single.logShift)
  assert.equal(congested.matchesIn14Days, 3)
})

// --- Promoted priors ---------------------------------------------------------

test('promoted prior only uses the signal the data supports', () => {
  const dominant = { team: 'COV', played: 46, goalsFor: 100, goalsAgainst: 40, points: 100 }
  const scraper = { team: 'HUL', played: 46, goalsFor: 60, goalsAgainst: 58, points: 70 }
  const a = promotedPrior(dominant)
  const b = promotedPrior(scraper)
  assert.ok(a.attack > b.attack, 'a dominant promotion should carry a better attack prior')
  // Championship defensive record does not predict Premier League conceding
  // (r = 0.05), so it must not move the defence prior at all.
  assert.equal(a.defence, MEASURED_PROMOTED_PRIOR.defence)
  assert.equal(b.defence, MEASURED_PROMOTED_PRIOR.defence)
  // And the adjustment stays modest — most of the prior is the cohort average.
  assert.ok(Math.abs(a.attack - MEASURED_PROMOTED_PRIOR.attack) < 0.12)
})

test('a club with no Championship record falls back to the cohort prior', () => {
  assert.deepEqual(promotedPrior(null), MEASURED_PROMOTED_PRIOR)
  assert.deepEqual(promotedPrior({ team: 'X', played: 3, goalsFor: 5, goalsAgainst: 2, points: 7 }), MEASURED_PROMOTED_PRIOR)
})

test('championship table accumulates points correctly', () => {
  const matches = [
    { season: 'S', home: 'COV', away: 'HUL', homeGoals: 2, awayGoals: 0, finished: true },
    { season: 'S', home: 'HUL', away: 'COV', homeGoals: 1, awayGoals: 1, finished: true },
    { season: 'OTHER', home: 'COV', away: 'HUL', homeGoals: 5, awayGoals: 0, finished: true },
    { season: 'S', home: 'COV', away: 'HUL', homeGoals: null, awayGoals: null, finished: false },
  ]
  const t = championshipTable(matches, 'S')
  assert.equal(t.get('COV')!.points, 4)
  assert.equal(t.get('HUL')!.points, 1)
  assert.equal(t.get('COV')!.played, 2, 'other seasons and unplayed matches must be excluded')
  assert.equal(t.get('COV')!.goalsFor, 3)
})

// --- Parsing -----------------------------------------------------------------

test('CSV parsing survives quoted fields, embedded commas and quotes', () => {
  const text = 'a,b,c\n1,"hello, world",3\n4,"say ""hi""",6\n'
  const rows = parseCsv(text)
  assert.equal(rows.length, 2)
  assert.equal(rows[0]!['b'], 'hello, world')
  assert.equal(rows[1]!['b'], 'say "hi"')
})

test('CSV parsing strips a byte-order mark from the first header', () => {
  const rows = parseCsv('﻿id,name\n1,Test\n')
  assert.equal(rows[0]!['id'], '1')
})

test('CSV parsing handles quoted headers and CRLF', () => {
  // The 2016-17 exports quote every header and use Windows line endings.
  const rows = parseCsv('"name","goals"\r\n"Smith",3\r\n')
  assert.equal(rows[0]!['name'], 'Smith')
  assert.equal(rows[0]!['goals'], '3')
})

test('CSV helpers coerce safely', () => {
  const rec = { a: '5', b: '', c: 'nonsense', d: 'True' }
  assert.equal(num(rec, 'a'), 5)
  assert.equal(num(rec, 'b', 7), 7)
  assert.equal(num(rec, 'c', 2), 2)
  assert.equal(num(rec, 'missing', 9), 9)
  assert.equal(bool(rec, 'd'), true)
  assert.equal(bool(rec, 'a'), false)
})

test('a trailing newline does not produce a phantom record', () => {
  assert.equal(parseCsv('a,b\n1,2\n').length, 1)
  assert.equal(parseCsvRows('a,b\n1,2\n').length, 2)
})

test('Python repr parsing handles the fixture stats blob', () => {
  // Single quotes and True/False/None — JSON.parse throws on all of it.
  const raw =
    "[{'identifier': 'yellow_cards', 'a': [{'value': 1, 'element': 371}], 'h': []}, " +
    "{'identifier': 'goals_scored', 'h': [{'value': 2, 'element': 381}], 'a': []}]"
  const blocks = parseFixtureStats(raw)
  assert.equal(blocks.length, 2)
  const yellows = blocks.find((b) => b.identifier === 'yellow_cards')!
  assert.equal(yellows.a[0]!.element, 371)
  assert.equal(yellows.h.length, 0)
})

test('Python repr parsing survives apostrophes in names', () => {
  const v = parsePyRepr("{'name': 'Nott\\'m Forest', 'ok': True, 'x': None, 'n': -1.5}") as Record<string, unknown>
  assert.equal(v['name'], "Nott'm Forest")
  assert.equal(v['ok'], true)
  assert.equal(v['x'], null)
  assert.equal(v['n'], -1.5)
})

test('the stats parser accepts real JSON too, so the live API path works', () => {
  const json = JSON.stringify([{ identifier: 'red_cards', h: [{ value: 1, element: 5 }], a: [] }])
  const blocks = parseFixtureStats(json)
  assert.equal(blocks[0]!.identifier, 'red_cards')
  assert.equal(blocks[0]!.h[0]!.element, 5)
})

test('empty and malformed stats are handled without throwing', () => {
  assert.deepEqual(parseFixtureStats(''), [])
  assert.deepEqual(parseFixtureStats('[]'), [])
  assert.throws(() => parsePyRepr('{invalid'), /pyrepr/)
})

// --- Player props ------------------------------------------------------------

test('player goal chances reconcile to the team’s predicted goals', () => {
  const squad = Array.from({ length: 14 }, (_, i) =>
    player({ id: i, name: `P${i}`, xgi90: 0.3 + i * 0.05, minuteShare: 0.9 }),
  )
  const props = playerProps({ squad, teamLambda: 1.6, cleanSheetProb: 0.3 })
  assert.ok(props.length > 0)
  // Summing the implied goal rates should land near the team lambda rather
  // than the raw sum of season rates.
  const impliedGoals = props.reduce((s, p) => s + -Math.log(1 - Math.min(0.999, p.goal)), 0)
  assert.ok(impliedGoals > 0.5 && impliedGoals < 2.0, `implied ${impliedGoals}`)
  // Probabilities must be probabilities.
  for (const p of props) {
    for (const v of [p.goal, p.assist, p.yellow, p.red, p.cleanSheet, p.brace]) {
      assert.ok(v >= 0 && v <= 1, `out of range: ${v}`)
    }
    assert.ok(p.brace <= p.goal, 'a brace cannot be likelier than a goal')
  }
})

test('players who will not feature are excluded from the props', () => {
  const squad = [
    player({ id: 1, name: 'Starter', minuteShare: 0.95 }),
    player({ id: 2, name: 'Injured', minuteShare: 0.95, status: 'i' }),
  ]
  const props = playerProps({ squad, teamLambda: 1.5, cleanSheetProb: 0.3 })
  assert.ok(props.some((p) => p.name === 'Starter'))
  assert.ok(!props.some((p) => p.name === 'Injured'), 'an injured player has no expected minutes')
})

test('only keepers and defenders carry a clean-sheet probability', () => {
  const squad = [
    player({ id: 1, name: 'GK', position: 'GK', minuteShare: 1 }),
    player({ id: 2, name: 'ST', position: 'FWD', minuteShare: 1 }),
  ]
  const props = playerProps({ squad, teamLambda: 1.5, cleanSheetProb: 0.4 })
  assert.ok(props.find((p) => p.name === 'GK')!.cleanSheet > 0)
  assert.equal(props.find((p) => p.name === 'ST')!.cleanSheet, 0)
})
