// Progress: the AoE4World history (games list, per-game facts, trends, win
// rates, the weekly summary), build orders (made from a game, compared with a
// played game, called during play), practice plans, and the opponent card.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseSummary } from '../src/core/truth/aoe4world.ts'
import { addFacts, emptyHistory, factsFromSummary, isAoe4History, mergeGames, parseGameList, trends, wantSummaries, weeklySummary, winRates, type Aoe4History, type GameFacts, type HistoryGame } from '../src/core/aoe4/history.ts'
import { BuildCaller, buildFindings, buildFromGame, compareBuild, isBuildLibrary, isBuildOrder, playedFromScreen, type PlayedBuild } from '../src/core/aoe4/buildOrder.ts'
import { itemKey, itemName, nameKey } from '../src/core/aoe4/names.ts'
import { opponentCard } from '../src/core/aoe4/opponentCard.ts'
import { currentFocus, focusStatus, isPracticePlan, makePlan, measurements, practiceLine, recordGames, suggestFoci, type Measurement } from '../src/core/analyses/practice.ts'
import type { MatchRecord } from '../src/core/analyses/matchRecorder.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import type { TimelineEntry, Utterance } from '../src/core/types.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { aoe4GameReplay } from '../src/sim/visionReplay.ts'
import { engineFor, settings } from './helpers.ts'

const aoe4 = builtInProfile('aoe4')
const roster = aoe4.knowledge.roster!
const DAY = 86_400
const NOW = 1_800_000_000

/** A game summary in AoE4World's format: one player with a build, one opponent making longbows. */
function summary(over: { feudal?: number; castle?: number; barracksAt?: number; villagersEvery?: number } = {}) {
  const every = over.villagersEvery ?? 20
  const made = [0, 0, 0, 0, 0, 0, ...Array.from({ length: 50 }, (_, i) => every * (i + 1))]
  return {
    gameId: 5001,
    mapName: 'Dry Arabia',
    duration: 1300,
    startedAt: NOW,
    finishedAt: NOW + 1300,
    players: [
      {
        profileId: 111,
        name: 'Me',
        civilization: 'english',
        result: 'win',
        actions: { feudalAge: [over.feudal ?? 270], castleAge: [over.castle ?? 700] },
        buildOrder: [
          { icon: 'icons/races/common/units/scout', type: 'Unit', finished: [0], destroyed: [] },
          { icon: 'icons/races/english/units/villager', type: 'Unit', finished: made, destroyed: [400] },
          { icon: 'icons/races/common/buildings/town_centre_capitol', type: 'Building', finished: [], constructed: [0] },
          { icon: 'icons/races/common/buildings/house', type: 'Building', finished: [], constructed: [40, 150, 260] },
          { icon: 'icons/races/common/buildings/mill', type: 'Building', finished: [], constructed: [30] },
          { icon: 'icons/races/common/buildings/gold_mining_camp', type: 'Building', finished: [], constructed: [95] },
          { icon: 'icons/races/common/buildings/barracks', type: 'Building', finished: [], constructed: [over.barracksAt ?? 190, 330, 500] },
          { icon: 'icons/races/english/buildings/building_landmark_age1_council_hall', type: 'Building', finished: [], constructed: [over.feudal ?? 270] },
          { icon: 'icons/races/common/units/spearman_1', type: 'Unit', finished: [280, 300, 320], destroyed: [] },
          { icon: 'icons/races/common/upgrades/wheelbarrow', type: 'Upgrade', finished: [350] },
          { icon: 'icons/races/common/upgrades/gold_gather_1', type: 'Upgrade', finished: ['oops'] },
        ],
      },
      {
        profileId: 222,
        name: 'Them',
        civilization: 'french',
        result: 'loss',
        actions: { feudalAge: [300] },
        buildOrder: [
          { icon: 'icons/races/common/units/archer_2', type: 'Unit', finished: [400, 410, 420, 430], destroyed: [] },
          { icon: 'icons/races/common/units/villager', type: 'Unit', finished: [20, 40], destroyed: [] },
          { icon: 'icons/races/french/units/royal_knight_2', type: 'Unit', finished: [500, 520], destroyed: [] },
        ],
      },
    ],
  }
}

const facts = (over: Parameters<typeof summary>[0] = {}): GameFacts => factsFromSummary(parseSummary(summary(over))!, 111)!

test('AoE4World games list: both team shapes, civilisations as the pack names them, unfinished games skipped', () => {
  const raw = {
    games: [
      { game_id: 1, started_at: '2026-10-20T10:00:00Z', duration: 1500, map: 'Dry Arabia', kind: 'rm_1v1', teams: [[{ player: { profile_id: 111, civilization: 'holy_roman_empire', result: 'win', rating: 1210 } }], [{ player: { profile_id: 9, civilization: 'english', result: 'loss' } }]] },
      { game_id: 2, started_at: '2026-10-21T10:00:00Z', duration: 900, map: 'Altai', leaderboard: 'qm_2v2', teams: [[{ profile_id: 111, civilization: 'french', result: 'loss' }, { profile_id: 5, civilization: 'rus' }], [{ profile_id: 6, civilization: 'mongols' }, { profile_id: 7, civilization: 'order_of_the_dragon' }]] },
      { game_id: 3, started_at: '2026-10-22T10:00:00Z', duration: 0, ongoing: true, teams: [[{ profile_id: 111, civilization: 'french' }], [{ profile_id: 8, civilization: 'rus' }]] },
      { game_id: 4, started_at: '2026-10-22T10:00:00Z', teams: [[{ profile_id: 1 }], [{ profile_id: 2 }]] },
    ],
  }
  const games = parseGameList(raw, 111)
  assert.equal(games.length, 2)
  assert.deepEqual([games[0].civ, games[0].opponents, games[0].result, games[0].rating, games[0].kind], ['holy-roman-empire', ['english'], 'win', 1210, 'rm_1v1'])
  assert.deepEqual([games[1].civ, games[1].opponents, games[1].result, games[1].kind], ['french', ['mongols', 'order-of-the-dragon'], 'loss', 'qm_2v2'])
  assert.deepEqual(parseGameList({ nope: 1 }, 111), [])
})

test('a game summary gives the facts: villagers at checkpoints, ages, the build (no villagers, nothing from the start), the opponent\'s main units', () => {
  const f = facts()
  assert.equal(f.villagers[300], 6 + 15 - 0)
  assert.equal(f.villagers[600], 6 + 30 - 1)
  assert.deepEqual(f.ages, { feudal: 270, castle: 700 })
  assert.ok(!f.build.some((e) => e.item === 'villager' || e.t === 0), 'villagers and starting buildings are not build steps')
  assert.ok(f.build.some((e) => e.kind === 'upgrade' && e.item === 'wheelbarrow'))
  assert.ok(!f.build.some((e) => e.item === 'gold_gather_1'), 'malformed times are skipped')
  assert.deepEqual(f.opponentArmy.map((u) => u.item), ['archer_2', 'royal_knight_2'], 'villagers are not army')
})

function game(id: number, daysAgo: number, civ: string, opp: string, result: 'win' | 'loss', map = 'Dry Arabia'): HistoryGame {
  return { gameId: id, startedAt: NOW - daysAgo * DAY, durationSec: 1300, map, kind: 'rm_1v1', civ, opponents: [opp], result, rating: 1100 }
}

function history(): Aoe4History {
  let h = emptyHistory(111)
  const games: HistoryGame[] = []
  // The 4 weeks before: Feudal around 5:10, 30 villagers at 10:00; losing to English.
  for (let i = 0; i < 6; i++) games.push(game(100 + i, 9 + i * 4, 'french', i % 2 ? 'english' : 'rus', i % 2 ? 'loss' : 'win'))
  // This week: Feudal around 4:30, 34 at 10:00.
  for (let i = 0; i < 3; i++) games.push(game(200 + i, 1 + i, 'french', 'english', i === 0 ? 'win' : 'loss'))
  h = mergeGames(h, games)
  const f = (id: number, feudal: number, v10: number): GameFacts => ({ gameId: id, villagers: { 600: v10 }, ages: { feudal }, build: [], opponentArmy: [{ item: 'longbowman_2', count: 8 }] })
  h = addFacts(h, [...[0, 1, 2, 3, 4, 5].map((i) => f(100 + i, 310 + (i % 2) * 10, 30)), ...[0, 1, 2].map((i) => f(200 + i, 270, 34))], [])
  return h
}

test('history: merged without repeats, summaries wanted once, checked on load', () => {
  const h = history()
  assert.equal(h.games.length, 9)
  assert.equal(mergeGames(h, [game(200, 1, 'french', 'english', 'win')]).games.length, 9)
  const more = mergeGames(h, [game(300, 0, 'french', 'rus', 'win'), { ...game(301, 0, 'french', 'rus', 'win'), durationSec: 120 }])
  assert.deepEqual(wantSummaries(more), [300], 'only games without facts, long enough to have a build')
  assert.deepEqual(wantSummaries(addFacts(more, [], [300])), [], 'a game without a summary is not asked for again')
  assert.ok(isAoe4History(h))
  assert.ok(!isAoe4History({ ...h, games: 'x' }))
})

test('trends, win rates and the weekly summary: plain comparisons with the number of games', () => {
  const h = history()
  const [vill, feudal] = trends(h)
  assert.equal(vill.points.length, 9)
  assert.equal(vill.recent, 30, 'median of the last 10 games')
  assert.equal(feudal.better, 'lower')
  const rows = winRates(h, 'opponent')
  const eng = rows.find((r) => r.key === 'english')!
  assert.deepEqual([eng.games, eng.wins], [6, 1])
  const w = weeklySummary(h, NOW, { civ: (id) => roster.factions.find((f) => f.id === id)?.name ?? id, unit: (item) => itemName('unit', item, roster) })
  assert.equal(w.games, 3)
  const text = w.lines.join(' ')
  assert.match(text, /3 games this week, 1 won \(33%, against 50% in the 4 weeks before\)/)
  assert.match(text, /Feudal Age 45 s faster than the 4 weeks before \(4:30 against 5:15; 3 games this week, 6 before\)/)
  assert.match(text, /Villagers at 10:00: 34, 4 more than the 4 weeks before/)
  assert.match(text, /Losing to English: 1 of 6 won in the last 5 weeks; in the losses they made mostly Longbowmen|Losing to English: 1 of 6 won in the last 5 weeks; in the losses they made mostly Longbowman/)
  assert.doesNotMatch(text, BANNED_CLAIMS)
  // Too few games on one side: nothing compared.
  const thin = weeklySummary(mergeGames(emptyHistory(111), [game(1, 1, 'french', 'rus', 'win')]), NOW, { civ: (x) => x, unit: (x) => x })
  assert.deepEqual(thin.lines, ['1 game this week, 1 won.'])
})

test('names: icon names become keys that match across games, and readable names from the roster', () => {
  assert.equal(itemKey('gold_mining_camp'), 'mining_camp')
  assert.equal(itemKey('building_miningcamp'), 'mining_camp')
  assert.equal(itemKey('horseman_2'), 'horseman')
  assert.equal(itemKey('gilded_manatarms_2'), 'gilded_man_at_arms')
  assert.equal(itemName('building', 'gold_mining_camp', roster), 'Mining Camp')
  assert.equal(itemName('unit', 'horseman_2', roster), 'Horseman')
  assert.equal(itemName('building', 'building_landmark_age1_council_hall', roster), 'Council Hall (landmark)')
  assert.equal(itemName('upgrade', 'wheelbarrow'), 'Wheelbarrow')
  assert.equal(itemName('age', 'feudal'), 'Feudal Age')
  assert.equal(nameKey('2 Mining Camps'), 'mining_camp')
  assert.equal(nameKey('Archery Range'), 'archery_range')
  assert.equal(nameKey('Spearmen'), 'spearman')
  assert.equal(nameKey('Barracks'), 'barracks')
  assert.equal(nameKey('4 Men-at-Arms'), 'man_at_arms')
  assert.equal(nameKey('Archers'), 'archer')
})

test('a build from a game: ages (with the landmark that made them), first buildings, the second barracks, first units and technologies, villagers', () => {
  const f = facts()
  const b = buildFromGame({ events: f.build, villagers: f.villagers, ages: f.ages }, { civ: 'english', name: 'Test', source: 'mine', roster })
  const names = b.steps.map((s) => `${s.atSec}:${s.kind === 'villagers' ? `${s.count} villagers` : s.name}${s.count > 1 && s.kind !== 'villagers' ? ` #${s.count}` : ''}`)
  assert.ok(names.includes('270:Feudal Age (Council Hall)'), names.join(' | '))
  assert.ok(!names.some((n) => /Council Hall \(landmark\)/.test(n)), 'the age-up landmark is the age step')
  assert.ok(!names.some((n) => /House/.test(n)), 'houses are not steps')
  assert.ok(names.includes('190:Barracks') && names.includes('330:Barracks #2') && !names.includes('500:Barracks #3'))
  assert.ok(names.includes('280:Spearman') && !names.includes('300:Spearman'))
  assert.ok(names.includes('350:Wheelbarrow'))
  assert.ok(names.includes('300:21 villagers'))
  assert.ok(b.steps.every((s, i) => i === 0 || s.atSec >= b.steps[i - 1].atSec))
  assert.ok(isBuildOrder(b))
  assert.ok(isBuildLibrary({ schema: 1, builds: [b], active: { english: b.id } }))
  assert.ok(!isBuildOrder({ ...b, steps: [{ atSec: -5, kind: 'x' }] }))
})

test('a played game against a build: late, early, missing and short of villagers; on time when it was followed', () => {
  const f = facts()
  const plan = buildFromGame({ events: f.build, villagers: f.villagers, ages: f.ages }, { civ: 'english', name: 'Plan', source: 'mine', roster })
  const same = compareBuild(plan, { events: f.build, villagers: f.villagers, ages: f.ages })
  assert.ok(same.every((c) => c.status === 'on time'))
  assert.deepEqual(buildFindings(same), ['The build was followed: every step within 20 seconds, or close to it.'])
  const slow = facts({ feudal: 315, barracksAt: 260, villagersEvery: 26 })
  const played: PlayedBuild = { events: slow.build.filter((e) => e.item !== 'wheelbarrow'), villagers: slow.villagers, ages: slow.ages }
  const checks = compareBuild(plan, played, { lastSec: 1300 })
  const feudal = checks.find((c) => c.step.kind === 'age' && c.step.key === 'feudal')!
  assert.deepEqual([feudal.status, feudal.deltaSec], ['late', 45])
  assert.equal(checks.find((c) => c.step.name === 'Wheelbarrow')!.status, 'missing')
  const lines = buildFindings(checks)
  assert.match(lines[0], /^Feudal Age \(Council Hall\) 0:45 late \(5:15; the build 4:30\)\.$/)
  assert.ok(lines.some((l) => /^Barracks 1:10 late \(4:20; the build 3:10\)\.$/.test(l)), lines.join(' | '))
  assert.ok(lines.some((l) => /^Villagers at (5:00|8:00|10:00): \d+, the build has \d+\.$/.test(l)), lines.join(' | '))
  for (const l of lines) assert.doesNotMatch(l, BANNED_CLAIMS)
})

test('a captured match (no AoE4World) against a build: first seen on screen, ages read, villagers at checkpoints', () => {
  const rec = {
    scout: { tracks: { 'your-buildings': { Barracks: { first: 200 }, 'Mining Camp': { first: 120 } }, 'your-army': { Spearmen: { first: 300 } }, 'your-age': { Feudal: { first: 290 } } } },
    benchmarks: { 'villager-curve': [{ atSec: 300, actual: 18 }] },
    readings: { changes: [[285, 'age', 'feudal']] as [number, string, string][] },
  }
  const p = playedFromScreen(rec)
  assert.equal(p.ages.feudal, 285, 'the reading log comes first')
  assert.deepEqual(p.villagers, { 300: 18 })
  assert.ok(p.events.some((e) => e.item === 'barracks' && e.t === 200))
  assert.ok(p.events.some((e) => e.item === 'mining_camp'))
  assert.ok(p.events.some((e) => e.kind === 'unit' && itemKey(e.item) === 'spearman'))
})

test('the build trainer: each step called once, a little early, close steps together, long-past steps skipped, then the end', () => {
  const f = facts()
  const plan = buildFromGame({ events: f.build, villagers: f.villagers, ages: f.ages }, { civ: 'english', name: 'Plan', source: 'mine', roster })
  const c = new BuildCaller(plan)
  const calls: string[] = []
  for (let t = 0; t <= 900; t += 2) for (const x of c.tick(t, 17)) calls.push(`${t}|${x.text}`)
  assert.ok(calls.some((x) => /^\d+\|Build: Mill at 0:30/.test(x)), calls.join('\n'))
  const feudalCall = calls.find((x) => /Feudal Age \(Council Hall\) by 4:30/.test(x))!
  assert.ok(Number(feudalCall.split('|')[0]) >= 270 - 8 && Number(feudalCall.split('|')[0]) <= 270)
  assert.match(calls.find((x) => /21 villagers by 5:00/.test(x))!, /\(17 now\)/)
  assert.equal(calls.filter((x) => /Mill at 0:30/.test(x)).length, 1)
  assert.match(calls.at(-1)!, /End of the build/)
  for (const x of calls) assert.ok(x.split('|')[1].split(/\s+/).length <= 30, x)
  // Joining late: steps long past are not called.
  const late = new BuildCaller(plan)
  assert.ok(!late.tick(400, null).some((x) => /Mill/.test(x.text)))
})

function record(id: string, endedAt: string, v10: number | null, mistakes: Record<string, number>, feudalSec?: number): MatchRecord {
  return {
    schema: 1,
    id,
    gameProfileId: 'aoe4',
    profileVersion: aoe4.version,
    modeId: 'skirmish-vs-ai',
    endedAt,
    durationSec: 1300,
    result: 'win',
    score: null,
    rounds: { won: 1, lost: 0, drawn: 0, unread: 0 },
    stats: {},
    mistakes: Object.fromEntries(Object.entries(mistakes).map(([k, n]) => [k, { count: n, totalSec: n * 20 }])),
    zones: {},
    endedManually: false,
    ...(v10 !== null ? { benchmarks: { 'villager-curve': [{ atSec: 600, actual: v10, expected: 36 }] } } : {}),
    ...(feudalSec ? { readings: { t: [], values: {}, changes: [[feudalSec, 'age', 'feudal']] } } : {}),
  } as MatchRecord
}

test('practice: weaknesses from recent games with targets from your own numbers; games count for the current focus in order', () => {
  const recs = [1, 2, 3, 4].map((i) => record(`m${i}`, `2026-10-2${i}T12:00:00Z`, 28 + (i % 2), { housed: 2, 'idle-villagers': 0 }, 345))
  const ms = measurements(recs, null)
  const foci = suggestFoci(ms, aoe4, 'english')
  const ids = foci.map((f) => f.id)
  assert.ok(ids.includes('villagers-10') && ids.includes('mistake:housed') && ids.includes('feudal'), ids.join(', '))
  assert.ok(!ids.includes('mistake:idle-villagers'), 'not a weakness here')
  const v = foci.find((f) => f.id === 'villagers-10')!
  assert.deepEqual([v.baseline, v.target, v.better], [29, 33, 'higher'])
  assert.match(v.why, /Your usual is 29 at 10:00 \(last 4 games\); one Town Center that never stops has 36\./)
  const feudal = foci.find((f) => f.id === 'feudal')!
  assert.deepEqual([feudal.baseline, feudal.target], [345, 315])
  // Gilded Villagers are slower: the Order of the Dragon's curve sets the bar.
  assert.ok(!suggestFoci(measurements([1, 2, 3].map((i) => record(`d${i}`, `2026-10-2${i}T12:00:00Z`, 30, {}, 290)), null), aoe4, 'order-of-the-dragon').some((f) => f.id === 'villagers-10'))

  let plan = makePlan('aoe4', [v, feudal], new Date('2026-10-27T00:00:00Z'))
  assert.ok(isPracticePlan(plan))
  assert.equal(practiceLine(plan), 'Practice: villagers at 10:00. Target 33 villagers. Game 1 of 3.')
  const later: Measurement[] = [
    { key: 'match:a', at: '2026-10-26T12:00:00Z', values: { 'villagers-10': 40 } },
    { key: 'match:b', at: '2026-10-27T12:00:00Z', values: { 'villagers-10': 31, feudal: 330 } },
    { key: 'aoe4world:1', at: '2026-10-28T12:00:00Z', values: { 'villagers-10': 34 } },
    { key: 'match:c', at: '2026-10-28T13:00:00Z', values: { feudal: 300 } },
    { key: 'match:d', at: '2026-10-28T14:00:00Z', values: { 'villagers-10': 35, feudal: 310 } },
    { key: 'match:e', at: '2026-10-28T15:00:00Z', values: { feudal: 305 } },
  ]
  plan = recordGames(plan, later)
  assert.deepEqual(plan.foci[0].results.map((r) => r.key), ['match:b', 'aoe4world:1', 'match:d'], 'games before the plan do not count; one focus at a time')
  assert.deepEqual(plan.foci[1].results.map((r) => r.key), ['match:e'])
  assert.deepEqual(recordGames(plan, later), plan, 'each game counted once')
  const s = focusStatus(plan.foci[0])
  assert.deepEqual([s.done, s.after, s.improved, s.reachedTarget], [true, 34, true, true])
  assert.match(s.line, /^3 of 3 games: 34 villagers against 29 villagers before \(target 33 villagers\)\. Target reached\.$/)
  assert.equal(currentFocus(plan)?.id, 'feudal')
  assert.match(practiceLine(plan)!, /^Practice: feudal age time\. Target 5:15\. Game 2 of 3\.$/)
})

test('practice measurements: a game the coach watched and checked on AoE4World counts once', () => {
  const rec = { ...record('m1', '2026-10-28T12:00:00Z', 30, {}), truth: { source: 'aoe4world', checkedAt: 'x', gameId: 77, lines: [{ what: 'Villagers at 10:00', coach: 30, real: 32, ok: false }, { what: 'Feudal Age reached', coach: null, real: '4:40', ok: null }], readings: {} } } as MatchRecord
  const h = addFacts(mergeGames(emptyHistory(1), [game(77, 1, 'english', 'french', 'win'), game(78, 2, 'english', 'french', 'win')]), [{ gameId: 77, villagers: { 600: 32 }, ages: {}, build: [], opponentArmy: [] }, { gameId: 78, villagers: { 600: 33 }, ages: { feudal: 260 }, build: [], opponentArmy: [] }], [])
  const ms = measurements([rec], h)
  assert.equal(ms.length, 2)
  assert.deepEqual(ms.find((m) => m.key === 'match:m1')!.values, { 'villagers-10': 32, feudal: 280 }, 'the real numbers from the check')
})

test('opponent card: their spikes, plans and unique units with what beats them for your civilisation, and your plan', () => {
  const card = opponentCard(roster, 'english', 'french', 2)!
  assert.equal(card.name, 'English')
  assert.ok(card.uniques.some((u) => u.name === 'Longbowman' && u.beatenBy.length > 0), JSON.stringify(card.uniques))
  assert.ok(!card.uniques.flatMap((u) => u.beatenBy).includes('Archer'), 'the French have Arbalétriers etc., never a unit they lack')
  assert.ok(card.ages.find((a) => a.age === 2)!.plans.length > 0)
  assert.equal(card.yourPlan?.civ, 'French')
  assert.equal(card.yourPlan?.age, 2)
  assert.equal(opponentCard(roster, 'nobody', null), null)
})

function play(modeId: string, opts: { build?: ReturnType<typeof buildFromGame>; practice?: string; civ?: string }) {
  const s = settings({ profileId: 'aoe4', profileModeId: modeId, mode: 'training', commentary: false, context: { aoe4: { civ: opts.civ ?? 'English' } } })
  const engine = engineFor(aoe4, s, { build: opts.build ?? null, practice: opts.practice ?? null, context: { civ: opts.civ ?? 'English', feudalTargetSec: 330, castleTargetSec: 840 } })
  const entries: TimelineEntry[] = [...engine.startNotices(0)]
  const utterances: Utterance[] = []
  let now = 0
  for (const b of aoe4GameReplay({ seed: 4 }).batches) {
    const out = engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt)))
    entries.push(...out.entries)
    utterances.push(...out.utterances)
  }
  return { entries, utterances, engine }
}

test('build trainer in a game: calls spoken against the AI, the practice focus once; nothing during ranked play', () => {
  const f = facts()
  const plan = buildFromGame({ events: f.build, villagers: f.villagers, ages: f.ages }, { civ: 'english', name: 'Plan', source: 'mine', roster })
  const vsAi = play('skirmish-vs-ai', { build: plan, practice: 'Practice: villagers at 10:00. Target 33 villagers. Game 1 of 3.' })
  const builds = vsAi.utterances.filter((u) => u.category === 'build').map((u) => u.text)
  assert.equal(builds[0], 'Practice: villagers at 10:00. Target 33 villagers. Game 1 of 3.')
  assert.ok(builds.some((t) => /^Build: .*Feudal Age \(Council Hall\) by 4:30/.test(t)), builds.join(' | '))
  assert.equal(new Set(builds).size, builds.length, 'nothing said twice')
  const ranked = play('ranked-1v1', { build: plan, practice: 'Practice: x.' })
  assert.ok(ranked.utterances.every((u) => u.category !== 'build'))
  const held = ranked.entries.filter((e) => e.type === 'message' && e.message.category === 'build')
  assert.ok(held.length > 0 && held.every((e) => e.type === 'message' && e.decision.decision === 'suppressed'))
})

test('the civilisation chosen on the Coach screen (its name) selects its own villager curve', () => {
  const dragon = play('skirmish-vs-ai', { civ: 'Order of the Dragon' })
  const spec = dragon.engine.report().benchmarks[0]?.spec
  assert.equal(spec?.expected.perMinute, 2.6)
  const horde = play('skirmish-vs-ai', { civ: 'Golden Horde' })
  assert.ok(horde.entries.some((e) => e.type === 'notice' && /Villagers against the clock: not compared\. Golden Horde villagers take 37 seconds/.test(e.text)))
})

test('upgrade timings: Wheelbarrow, Textiles and the Blacksmith by name or line and tier; against the build and the game it came from', async () => {
  const { trackedUpgrade, upgradeTimes, upgradeRows, upgradeFindings } = await import('../src/core/aoe4/upgrades.ts')
  assert.equal(trackedUpgrade('wheelbarrow'), 'wheelbarrow')
  assert.equal(trackedUpgrade('upgrade_villager_health'), 'textiles')
  assert.equal(trackedUpgrade('upgrade_melee_damage_i'), 'melee-damage-1')
  assert.equal(trackedUpgrade('ranged_armor_3'), 'ranged-armor-3')
  assert.equal(trackedUpgrade('icons/races/common/upgrades/balanced_projectiles'), 'ranged-damage-2')
  assert.equal(trackedUpgrade('gold_gather_1'), null)
  const source = upgradeTimes([
    { t: 200, kind: 'upgrade', item: 'wheelbarrow' },
    { t: 610, kind: 'upgrade', item: 'bloomery' },
    { t: 900, kind: 'upgrade', item: 'steeled_arrow' },
  ])
  assert.deepEqual(source, { wheelbarrow: 200, 'melee-damage-1': 610, 'ranged-damage-1': 900 })
  // A build made from that game keeps them, and its steps carry the tier.
  const plan = buildFromGame({ events: [{ t: 200, kind: 'upgrade', item: 'wheelbarrow' }, { t: 610, kind: 'upgrade', item: 'bloomery' }, { t: 900, kind: 'upgrade', item: 'steeled_arrow' }], villagers: {}, ages: { castle: 600 } }, { civ: 'english', name: 'x', source: 'game', roster })
  assert.deepEqual(plan.upgrades, source)
  assert.ok(isBuildOrder(plan))
  assert.ok(plan.steps.some((s) => s.key === 'melee-damage-1' && s.name === 'Bloomery'))
  const mine = upgradeTimes([{ t: 330, kind: 'upgrade', item: 'wheelbarrow' }])
  const rows = upgradeRows(mine, { wheelbarrow: 200, 'melee-damage-1': 610 }, source, 1200)
  assert.deepEqual(rows.map((r) => [r.upgrade.id, r.yours, r.build, r.source]), [
    ['wheelbarrow', 330, 200, 200],
    ['melee-damage-1', null, 610, 610],
    ['ranged-damage-1', null, null, 900],
  ])
  assert.deepEqual(upgradeFindings(rows), ['Bloomery: not researched (the build at 10:10).', 'Steeled Arrow: not researched (their game at 15:00).', 'Wheelbarrow 2:10 later than the build (5:30 against 3:20).'])
  // A shorter game: an upgrade their game got later is not listed as missing.
  assert.ok(!upgradeRows(mine, null, source, 700).some((r) => r.upgrade.id === 'ranged-damage-1'))
})
