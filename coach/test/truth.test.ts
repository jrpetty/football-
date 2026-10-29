// Checking the coach against the real game: AoE4World's game summary (its
// format, as published), the player's own numbers, and the reading log kept
// with each match.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { compareWithPlayer, compareWithSummary, isTruthReport, parseGames, parseSummary, pickGame, villagersAt } from '../src/core/truth/aoe4world.ts'
import { isMatchRecord, type MatchRecord } from '../src/core/analyses/matchRecorder.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { aoe4GameReplay } from '../src/sim/visionReplay.ts'
import { engineFor, settings } from './helpers.ts'

const aoe4 = builtInProfile('aoe4')

/** A summary in AoE4World's format: villagers made every 20 s from 6, two lost at 9:00; Feudal at 4:30. */
function summary(profileId = 111, made: number[] = [0, 0, 0, 0, 0, 0, ...Array.from({ length: 60 }, (_, i) => 20 * (i + 1))], lost: number[] = [540, 541]) {
  const t = Array.from({ length: 61 }, (_, i) => i * 20)
  return {
    gameId: 209050389,
    mapName: 'Dry Arabia',
    duration: 1200,
    startedAt: 1_800_000_000,
    finishedAt: 1_800_001_200,
    players: [
      {
        profileId,
        name: 'Player',
        civilization: 'english',
        result: 'win',
        actions: { feudalAge: [270], castleAge: [900] },
        resources: { timestamps: t, food: t.map((x) => 100 + x), wood: t.map(() => 200), gold: t.map(() => 100), stone: t.map(() => 0), foodPerMin: t.map(() => 0) },
        buildOrder: [
          { id: '1', icon: 'icons/races/common/units/scout', pbgid: 1, type: 'Unit', finished: [0], constructed: [], destroyed: [] },
          { id: '2', icon: 'icons/races/english/units/villager', pbgid: 2, type: 'Unit', finished: made, constructed: [], destroyed: lost },
          { id: '3', icon: 'icons/races/common/buildings/house', pbgid: 3, type: 'Building', finished: [60], constructed: [], destroyed: [] },
        ],
      },
      { profileId: 222, name: 'Them', civilization: 'french', result: 'loss', actions: {}, buildOrder: [] },
    ],
  }
}

test('AoE4World summary: villagers alive at any time, ages, stockpiles; anything malformed is refused', () => {
  const s = parseSummary(summary())!
  const me = s.players[0]
  assert.equal(villagersAt(me, 0), 6)
  assert.equal(villagersAt(me, 300), 21)
  assert.equal(villagersAt(me, 600), 34, '36 made, 2 lost at 9:00')
  assert.deepEqual(me.ages, { feudal: 270, castle: 900 })
  assert.equal(me.resources!.food[3], 160)
  assert.equal(parseSummary({ gameId: 1, players: [{ profileId: 1, buildOrder: [{ icon: 'x/villager', type: 'Unit', finished: ['soon'] }] }] }), null)
  assert.equal(parseSummary({ players: [] }), null)
  // Every civilisation's villagers count (variants too), and nothing else.
  const variants = summary(1, [0, 0])
  ;(variants.players[0].buildOrder as unknown[]).push({ id: '4', icon: 'icons/races/byzantines/units/villager', pbgid: 4, type: 'Unit', finished: [30], constructed: [], destroyed: [] })
  ;(variants.players[0].buildOrder as unknown[]).push({ id: '5', icon: 'icons/races/common/units/villager_fishing_boat', pbgid: 5, type: 'Unit', finished: [30], constructed: [], destroyed: [] })
  assert.equal(villagersAt(parseSummary(variants)!.players[0], 60), 3)
})

test('AoE4World games list: the game the coach watched is the one that ended closest to it', () => {
  const games = parseGames({
    games: [
      { game_id: 1, started_at: '2026-10-28T20:00:00Z', duration: 1500 },
      { game_id: 2, started_at: '2026-10-28T20:40:00Z', duration: 900 },
      { game_id: 3, started_at: '2026-10-28T18:00:00Z', duration: 900 },
    ],
  })
  assert.equal(pickGame(games, new Date('2026-10-28T20:55:30Z'), 880), 2)
  assert.equal(pickGame(games, new Date('2026-10-28T23:00:00Z'), 880), null, 'nothing near')
  assert.deepEqual(parseGames({ nope: 1 }), [])
})

test('the coach against the real game: checkpoints, age-ups and every logged reading', () => {
  // Play the demo game; its scripted villagers are the truth.
  const replay = aoe4GameReplay({ seed: 4 })
  const engine = engineFor(aoe4, settings({ profileId: 'aoe4', profileModeId: 'skirmish-vs-ai', mode: 'post-match' }))
  let now = 0
  const records: MatchRecord[] = []
  for (const b of replay.batches) records.push(...engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt))).matches)
  const record = records[0]
  assert.ok(isMatchRecord(record))
  assert.ok(record.readings && record.readings.t.length > 20, 'a reading every 20 s of game time')
  assert.deepEqual(record.readings!.changes.map(([, k, v]) => `${k}=${v}`), ['age=dark', 'age=feudal'])
  // The real game as AoE4World would describe it (the demo's script: on the curve to 4:00, a 150 s gap, then 3 a minute).
  const made = [0, 0, 0, 0, 0, 0, ...Array.from({ length: 12 }, (_, i) => 20 * (i + 1)), ...Array.from({ length: 40 }, (_, i) => 390 + 20 * (i + 1))]
  const truth = parseSummary(summary(111, made, []))!
  const report = compareWithSummary(record, truth.players[0], truth.gameId, new Date('2026-10-29T10:00:00Z'))
  assert.ok(isTruthReport(report))
  const at = (what: string) => report.lines.find((l) => l.what === what)!
  assert.deepEqual([at('Villagers at 10:00').coach, at('Villagers at 10:00').real, at('Villagers at 10:00').ok], [28, 28, true])
  assert.equal(at('Feudal Age reached').ok, true)
  assert.equal(at('Castle Age reached').coach, null, 'the coach never saw the Castle Age')
  const [right, compared] = report.readings.villagers
  assert.ok(compared > 20 && right / compared > 0.95, `${right}/${compared}`)
  // The demo's food is random: the food readings mostly do not match this made-up summary, and that shows.
  assert.ok(report.readings.food[0] < report.readings.food[1])
})

test("the player's own numbers against the coach's checkpoints", () => {
  const r = compareWithPlayer({ benchmarks: { 'villager-curve': [{ atSec: 300, actual: 18 }, { atSec: 600, actual: 27 }] } }, { 300: 18, 600: 31, 900: 44 })
  assert.deepEqual(
    r.lines.map((l) => [l.what, l.coach, l.real, l.ok]),
    [
      ['Villagers at 5:00', 18, 18, true],
      ['Villagers at 10:00', 27, 31, false],
      ['Villagers at 15:00', null, 44, null],
    ],
  )
})

test('screen readings against the game data: agreed, missed, and moments of change skipped', async () => {
  const { SourceAgreement } = await import('../src/core/truth/agreement.ts')
  const a = new SourceAgreement()
  const o = (source: 'game-api' | 'vision-model' | 'pixel', key: string, value: number, at: number) =>
    a.record({ id: `${source}${at}`, timestampMs: at, gameProfileId: 'cs2', sourceRegion: 'x', kind: 'state', eventType: key, value, confidence: 0.9, freshnessMs: 0, evidence: [], source })
  o('game-api', 'kills', 3, 1000)
  o('vision-model', 'kills', 3, 5000)
  o('vision-model', 'kills', 2, 9000)
  o('game-api', 'kills', 4, 10_000)
  o('vision-model', 'kills', 3, 10_500) // the game data was changing: not compared
  o('vision-model', 'kills', 4, 14_000)
  o('pixel', 'money', 800, 2000)
  o('vision-model', 'money', 800, 6000)
  const rows = a.results()
  assert.deepEqual(rows.find((r) => r.key === 'kills'), { key: 'kills', against: 'game data', agreed: 2, compared: 3, lastMiss: ['2', '3'] })
  assert.deepEqual(rows.find((r) => r.key === 'money'), { key: 'money', against: 'this PC', agreed: 1, compared: 1, lastMiss: null })
})

test('CS2 demo: the screen readings are checked against the game data as the match plays', async () => {
  const { cs2MatchReplay } = await import('../src/sim/cs2Match.ts')
  const { demoMapFrom } = await import('../src/sim/visionReplay.ts')
  const cs2 = builtInProfile('cs2')
  const mirage = cs2.knowledge.maps!.find((m) => m.id === 'mirage')!
  const engine = engineFor(cs2, settings({ profileId: 'cs2', profileModeId: 'practice', mode: 'post-match' }))
  const replay = cs2MatchReplay({ seed: 3, map: demoMapFrom(mirage)!, mapId: 'de_mirage' })
  // Both feeds in time order, as the app delivers them.
  const feed = [
    ...replay.batches.map((b) => ({ at: b.arrivesAt, run: () => engine.ingestObservations(b.observations, b.arrivesAt) })),
    ...(replay.gameState ?? []).map((g) => ({ at: g.at, run: () => engine.ingestGameState(g.payload, g.at) })),
  ].sort((x, y) => x.at - y.at)
  for (const f of feed) f.run()
  const checks = engine.report().checks ?? []
  assert.ok(checks.length > 0, 'something compared')
  for (const c of checks) assert.equal(c.against, 'game data')
})

test('your usual: the median of your recent games at each checkpoint, your civilisation first, real numbers when checked', async () => {
  const { usualAt, withUsual } = await import('../src/core/analyses/baseline.ts')
  const rec = (i: number, at600: number, faction?: string, real?: number): MatchRecord => ({
    schema: 1,
    id: `m${i}`,
    gameProfileId: 'aoe4',
    profileVersion: '1',
    modeId: 'ranked-1v1',
    endedAt: new Date(2026, 9, i + 1).toISOString(),
    durationSec: 1200,
    result: 'win',
    score: null,
    rounds: { won: 0, lost: 0, drawn: 0, unread: 0 },
    stats: {},
    mistakes: {},
    zones: {},
    benchmarks: { 'villager-curve': [{ atSec: 600, actual: at600, expected: 36 }] },
    ...(faction ? { context: { playerFaction: faction } } : {}),
    ...(real !== undefined ? { truth: { source: 'aoe4world', checkedAt: '', lines: [{ what: 'Villagers at 10:00', coach: at600, real, ok: false }], readings: {} } } : {}),
    endedManually: false,
  })
  const history = [rec(1, 30, 'English'), rec(2, 32, 'French'), rec(3, 34, 'English'), rec(4, 28, 'French', 31), rec(5, 36, 'English')]
  assert.deepEqual(usualAt(history, 'villager-curve', 600, 'English'), { value: 34, games: 3, sameFaction: true })
  // Not enough French games alone: all of them (the checked one counts with its real 31).
  assert.deepEqual(usualAt(history, 'villager-curve', 600, 'French'), { value: 32, games: 5, sameFaction: false })
  assert.equal(usualAt(history.slice(0, 2), 'villager-curve', 600, null), null, 'two games are not a usual')
  // In the statement and the spoken checkpoint.
  const engine = engineFor(aoe4, settings({ profileId: 'aoe4', profileModeId: 'skirmish-vs-ai', mode: 'post-match', commentary: true }), { matchHistory: history })
  const replay = aoe4GameReplay({ seed: 4 })
  let now = 0
  const spoken: string[] = []
  for (const b of replay.batches) for (const u of engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt))).utterances) spoken.push(u.text)
  assert.ok(spoken.some((t) => /^10:00, 2[78] villagers: [89] behind one Town Center that never stops\. Your usual: 32\.$/.test(t)), spoken.join('\n'))
  const worst = withUsual(engine.report().benchmarks, history, null).find((b) => b.worst)!.worst!
  assert.match(worst.statement, /Your usual is 32 villagers \(your last 5 games\)\.$/)
})
