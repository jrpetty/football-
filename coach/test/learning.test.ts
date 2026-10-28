// Learning across matches, and the player's own screen-reading settings:
// what each side was seen building and fielding (first seen, how many, how
// often), filed under what the match was; what later matches of the same kind
// are told; the per-game refresh time, detail and looks at once; the
// matchup-only reference; and thinking off for screen readings.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CloudService, visionThinkingParam, type CloudClient } from '../electron/cloud/service.ts'
import { DEFAULT_CLOUD } from '../shared/cloud.ts'
import { learnedFrom, learnedLines, nameIndex, parseList, plansSeen, ScoutRecorder, type LearnSource } from '../src/core/analyses/learning.ts'
import type { MatchRecord } from '../src/core/analyses/matchRecorder.ts'
import { rosterText } from '../src/core/knowledge/base.ts'
import { validateProfile, type GameProfile } from '../src/core/profile/schema.ts'
import { answerLocally, classify } from '../src/core/qa/ask.ts'
import { learningPrefsFor, sanitizeSettings, visionPrefsFor } from '../src/core/settings.ts'
import type { GameEvent, VisibleState } from '../src/core/types.ts'
import { VisionSampler } from '../src/core/vision/sampler.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { aoe4GameReplay, fcMatchReplay, mw4MatchReplay, type VisionReplay } from '../src/sim/visionReplay.ts'
import { engineFor, settings } from './helpers.ts'
import type { CoachEngine } from '../src/core/engine.ts'

const aoe4 = builtInProfile('aoe4')
const mw4 = builtInProfile('cod-mw4')
const fc = builtInProfile('ea-fc')

const fv = (value: string | number, observedAt: number) => ({ value, confidence: 0.9, observedAt, source: 'vision-model' as const, conflicted: false })
const visible = (values: Record<string, string | number>, at: number): VisibleState =>
  ({ timestampMs: at, phase: 'round', stale: false, staleReasons: [], activeSignals: [], values: Object.fromEntries(Object.entries(values).map(([k, v]) => [k, fv(v, at)])) }) as unknown as VisibleState

/** Plays a replay to its end and stops; returns the match records and the timeline notices. */
function play(engine: CoachEngine, replay: VisionReplay): { matches: MatchRecord[]; notices: string[]; spoken: string[] } {
  const matches: MatchRecord[] = []
  const notices: string[] = []
  const spoken: string[] = []
  let now = 0
  for (const b of replay.batches) {
    const out = engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt)))
    matches.push(...out.matches)
    for (const e of out.entries) if (e.type === 'notice') notices.push(e.text)
    spoken.push(...out.utterances.map((u) => u.text))
  }
  matches.push(...engine.stop(now + 1000).matches)
  return { matches, notices, spoken }
}

// --- Reading names --------------------------------------------------------------------------

test('lists from the screen: counts, plurals and the roster’s names', () => {
  assert.deepEqual([...parseList('2 Stable, Archery Range x3; 4 Knights and Barracks')], [
    ['Stable', 2],
    ['Archery Range', 3],
    ['Knights', 4],
    ['Barracks', 1],
  ])
  const byRoster = nameIndex(aoe4.knowledge.roster)
  assert.equal(byRoster('Longbowmen'), 'Longbowman')
  assert.equal(byRoster('men-at-arms'), 'Man-at-Arms')
  assert.equal(byRoster('council hall'), 'Council Hall')
  const plain = nameIndex(undefined)
  assert.equal(plain('knights'), 'Knight')
  assert.equal(plain('Barracks'), 'Barracks', 'one building, not a plural')
  assert.equal(plain('UAV', true), 'UAV')
})

// --- One match ------------------------------------------------------------------------------

test('a match record: first seen on the game clock, most at once, a reading counted once, tallies and filtered events', () => {
  const r = new ScoutRecorder(aoe4)
  r.observe(visible({ gameTimeSec: 200, enemyBuildings: 'Barracks', age: 'feudal' }, 1000), 1000)
  // The same reading kept by the state for a few seconds is not a new sighting.
  r.observe(visible({ gameTimeSec: 200, enemyBuildings: 'Barracks' }, 1000), 4000)
  r.observe(visible({ gameTimeSec: 300, enemyBuildings: '2 Barracks, Archery Range', enemyUnits: '10 Longbowmen', ownArmy: '6 Horsemen' }, 101_000), 101_000)
  const s = r.finish({ opponent: 'English' })!
  assert.deepEqual(s.dims, { opponent: 'English' })
  assert.deepEqual(s.tracks['their-buildings'].Barracks, { first: 200, peak: 2, n: 2 })
  assert.equal(s.tracks['their-buildings']['Archery Range'].first, 300)
  assert.deepEqual(s.tracks['their-army'].Longbowman, { first: 300, peak: 10, n: 1 })
  assert.equal(s.tracks['your-army'].Horseman.peak, 6)
  assert.equal(s.tracks['your-age'].feudal.first, 200)
  assert.equal(r.finish({}), null, 'a new match starts empty')

  // Kills and deaths per weapon in hand; goals filtered by who scored.
  const w = new ScoutRecorder(mw4)
  const ev = (ruleId: string, attributes: Record<string, string> = {}) => ({ ruleId, attributes }) as unknown as GameEvent
  w.observe(visible({ ownWeapon: 'MCW' }, 1000), 1000)
  w.event(ev('kill'), 1500, visible({}, 1500))
  w.event(ev('kill'), 1600, visible({}, 1600))
  w.event(ev('death', { killedBy: 'smg' }), 1700, visible({}, 1700))
  w.observe(visible({ ownWeapon: 'Renetti' }, 2000), 2000)
  w.event(ev('kill'), 2100, visible({}, 2100))
  const m = w.finish({ map: 'Yard' })!
  assert.deepEqual([m.tracks['your-weapon'].MCW.good, m.tracks['your-weapon'].MCW.bad], [2, 1])
  assert.equal(m.tracks['your-weapon'].Renetti.good, 1)
  assert.equal(m.tracks['killed-by'].smg.n, 1)

  const g = new ScoutRecorder(fc)
  g.event(ev('goal', { scorer: 'opponent', buildUp: 'cross' }), 1000, visible({}, 1000))
  g.event(ev('goal', { scorer: 'player', buildUp: 'through_ball' }), 2000, visible({}, 2000))
  const f = g.finish({})!
  assert.deepEqual(Object.keys(f.tracks.conceded), ['cross'])
  assert.deepEqual(Object.keys(f.tracks.scored), ['through_ball'])
})

// --- Across matches -------------------------------------------------------------------------

test('across matches: how often, the median first-seen time, the usual order, results, and filters', () => {
  const rec = (opp: string, result: 'win' | 'loss', archeryAt: number, councilHall: boolean): LearnSource => ({
    gameProfileId: 'aoe4',
    result,
    scout: {
      dims: { opponent: opp },
      tracks: {
        'their-buildings': {
          Barracks: { first: 150, peak: 1, n: 3 },
          'Archery Range': { first: archeryAt, peak: 2, n: 4 },
          ...(councilHall ? { 'Council Hall': { first: archeryAt + 30, peak: 1, n: 1 } } : {}),
        },
        'your-army': { Horseman: { first: 400, peak: 8, n: 5 } },
      },
    },
  })
  const records = [rec('English', 'loss', 240, true), rec('English', 'loss', 260, true), rec('English', 'win', 300, false), rec('French', 'win', 500, false)]
  const l = learnedFrom(records, aoe4, { opponent: 'English' })!
  assert.equal(l.matches, 3)
  assert.equal(l.filterText, 'against English')
  assert.deepEqual([l.won, l.lost], [1, 2])
  const b = l.tracks.find((t) => t.id === 'their-buildings')!
  assert.deepEqual(b.order, [
    { name: 'Barracks', atSec: 150 },
    { name: 'Archery Range', atSec: 260 },
    { name: 'Council Hall', atSec: 280 },
  ])
  const ch = b.items.find((i) => i.name === 'Council Hall')!
  assert.deepEqual([ch.seenIn, ch.lost, ch.won], [2, 2, 0])
  const lines = learnedLines(l, 'them')
  assert.equal(lines[0], 'Their buildings in the usual order (first seen): Barracks 2:30, Archery Range 4:20, Council Hall 4:40.')
  assert.ok(lines.includes('When they had Council Hall: you lost 2 of 2.') || !b.composition)
  assert.equal(learnedFrom(records, aoe4, { opponent: 'Mongols' }), null)
  assert.equal(learnedFrom(records, aoe4)!.matches, 4, 'no filter: every game')
})

test('AoE IV, three games against English: the second game is told what the first showed, and the plans say how often they were met', () => {
  const history: MatchRecord[] = []
  const runs = [4, 5, 6].map((seed) => {
    const engine = engineFor(aoe4, settings({ profileId: 'aoe4', profileModeId: 'skirmish-vs-ai', mode: 'training', commentary: true }), {
      context: { civ: 'French' },
      matchHistory: [...history],
    })
    const r = play(engine, aoe4GameReplay({ seed }))
    // Each game ends at its own time (the test clock is fixed).
    history.push(...r.matches.map((m) => ({ ...m, id: `${m.id}-${seed}` })))
    return { ...r, report: engine.report() }
  })
  assert.ok(history.every((m) => m.scout?.dims.opponent === 'English' && m.scout.dims.civ === 'French'))
  assert.ok(!runs[0].notices.some((n) => /^Your last/.test(n)), 'nothing to recall the first time')
  const recall = runs[2].notices.find((n) => /^Your last/.test(n))
  assert.match(recall ?? '', /^Your last 2 games against English: Their buildings in the usual order \(first seen\): /)
  assert.match(recall ?? '', /Council Hall/)
  assert.ok(runs[2].spoken.some((t) => /^Your last 2 games against English/.test(t)), 'spoken with the commentary')
  // The reference plans, linked to what this player meets.
  const report = runs[2].report
  assert.equal(report.learned?.matches, 3, 'this game too, once it ended')
  const longbows = report.intel?.reads.find((r) => /Longbow/.test(r.name))
  assert.deepEqual(longbows?.past, { seen: 2, of: 2 })
  // Asked out loud.
  assert.equal(classify('what do they usually build?', aoe4).kind, 'learned')
  assert.equal(classify("what's my usual build order?", aoe4).kind, 'learned')
  assert.equal(classify('what are they making?', aoe4).kind, 'intel', 'what they make now is still intel')
  assert.match(answerLocally(classify('what do they usually do?', aoe4), aoe4, report, history.slice(0, 2))!, /^From your last 3 games against English \(as first seen on your screen\): /)
})

test('in ranked play the recall waits for Insights, like the rest of the scouting', () => {
  const first = play(engineFor(aoe4, settings({ profileId: 'aoe4', profileModeId: 'skirmish-vs-ai', mode: 'training' })), aoe4GameReplay({ seed: 4 })).matches
  const ranked = engineFor(aoe4, settings({ profileId: 'aoe4', profileModeId: 'ranked-1v1', mode: 'training', commentary: true }), { matchHistory: first })
  const r = play(ranked, aoe4GameReplay({ seed: 5 }))
  assert.ok(!r.notices.some((n) => /^Your last/.test(n)))
  assert.ok(!r.spoken.some((t) => /^Your last/.test(t)))
  assert.equal(r.matches[0].scout?.dims.opponent, 'English', 'still learned from, for after the match')
})

test('MW4 and FC learn too: what kills you on a map, your weapons, how they score against you', () => {
  const mw: MatchRecord[] = []
  for (const seed of [4, 5]) mw.push(...play(engineFor(mw4, settings({ profileId: 'cod-mw4', profileModeId: mw4.modes.find((m) => !m.multiplayer)?.id ?? null, mode: 'training' }), { matchHistory: [...mw] }), mw4MatchReplay({ seed })).matches)
  const lm = learnedFrom(mw, mw4, { map: 'Synthetic Yard' })!
  const lines = [...learnedLines(lm, 'them'), ...learnedLines(lm, 'you')]
  assert.ok(lines.some((l) => /^What killed you: assault rifle \d+/.test(l)), lines.join(' | '))
  assert.ok(lines.some((l) => /^Your weapons \(kills–deaths\): MCW \d+–\d+/.test(l)), lines.join(' | '))
  const fcs: MatchRecord[] = []
  for (const seed of [4, 5]) fcs.push(...play(engineFor(fc, settings({ profileId: 'ea-fc', profileModeId: 'kickoff-vs-cpu', mode: 'training' }), { matchHistory: [...fcs] }), fcMatchReplay({ seed })).matches)
  const lf = learnedFrom(fcs, fc, { opponent: 'Northbridge FC' })!
  assert.equal(lf.matches, 2)
  assert.match(learnedLines(lf, 'them').join(' '), /How they scored: cutback \d+/)
  assert.match(learnedLines(lf, 'them').join(' '), /Their formations: mostly 4-2-3-1 \(2 of 2\)/)
})

test('a learning spec must point at what the pack reads', () => {
  const bad = structuredClone(aoe4) as unknown as { learning: { tracks: { key: string }[]; dimensions: { key: string }[] } }
  bad.learning.tracks[0].key = 'nope'
  bad.learning.dimensions[1].key = 'nope'
  const r = validateProfile(bad)
  assert.ok(!r.ok)
  assert.ok(r.errors.some((e) => /learning\.tracks\[0\]\.key: unknown state key "nope"/.test(e)))
  assert.ok(r.errors.some((e) => /learning\.dimensions\[1\]\.key: unknown player context "nope"/.test(e)))
})

// --- Screen-reading settings --------------------------------------------------------------------

test('refresh time, detail and looks at once: the player’s choice per game, within limits, over the pack’s defaults', () => {
  const s = sanitizeSettings({ vision: { 'cod-mw4': { intervalMs: 250, maxWidth: 1600, maxInFlight: 9 }, aoe4: { maxWidth: 999 }, 'BAD ID': { intervalMs: 2000 } } })
  assert.deepEqual(s.vision, { 'cod-mw4': { intervalMs: 1000, maxWidth: 1600, maxInFlight: 4 } })
  assert.deepEqual(visionPrefsFor(mw4, s), { intervalMs: 1000, maxWidth: 1600, maxInFlight: 4, custom: true })
  // Defaults: the pack's, with enough looks at once for its interval.
  assert.deepEqual(visionPrefsFor(aoe4, s), { intervalMs: 3000, maxWidth: 1280, maxInFlight: 2, custom: false })
  assert.equal(visionPrefsFor(mw4, sanitizeSettings({ vision: { 'cod-mw4': { intervalMs: 1000 } } })).maxInFlight, 3, 'auto: about one reply time')

  // A new refresh time applies from the next frame.
  const sampler = new VisionSampler({ intervalMs: 3000, minChange: 0, maxSkipMs: 12_000, maxInFlight: 1 })
  const grid = new Float32Array(4)
  assert.ok(sampler.shouldSample(0, grid))
  sampler.sending(0, grid)
  sampler.done(500, true)
  assert.equal(sampler.shouldSample(1500, grid), false)
  sampler.reconfigure({ intervalMs: 1000, maxInFlight: 3 })
  assert.ok(sampler.shouldSample(1500, grid))
})

test('once the opponent is known, each look carries only this matchup’s roster', () => {
  const roster = aoe4.knowledge.roster!
  const full = rosterText(roster, { budget: 44_000, detail: 'vision' })
  const m = rosterText(roster, { budget: 44_000, detail: 'vision', factions: ['english', 'French'] })
  assert.ok(m.length < full.length / 2, `${m.length} vs ${full.length}`)
  assert.match(m, /^This match: English and French\./m)
  assert.match(m, /- Longbowman \(English\)/)
  assert.match(m, /- Royal Knight \(French/)
  assert.match(m, /- Spearman \[/, 'shared units stay')
  assert.doesNotMatch(m, /- Mangudai \(Mongols\)/)
  assert.match(m, /Other civilisations \(not in this match\): .*Mongols/)
  assert.equal(rosterText(roster, { budget: 44_000, detail: 'vision', factions: ['Atlanteans'] }), full, 'an unknown name changes nothing')
})

test('thinking off for screen readings: the lowest setting each model accepts, and what the session measured', async () => {
  assert.equal(visionThinkingParam('claude-opus-5-5', 'off'), null, 'Opus always thinks a little')
  assert.deepEqual(visionThinkingParam('claude-sonnet-5-5', 'off'), { type: 'between_tools' })
  assert.deepEqual(visionThinkingParam('claude-haiku-5-5', 'off'), { type: 'disabled' })
  assert.equal(visionThinkingParam('claude-sonnet-5-5', 'auto'), null)

  const calls: Record<string, unknown>[] = []
  const usage = { input_tokens: 1400, output_tokens: 420, cache_read_input_tokens: 9000, cache_creation_input_tokens: 0, output_tokens_details: { thinking_tokens: 60 } }
  const client = {
    beta: {
      messages: {
        parse: async (p: Record<string, unknown>) => {
          calls.push(p)
          return { usage, stop_reason: 'end_turn', parsed_output: { phase: 'round', phaseConfidence: 0.9, visibleOutcome: null, visibleHealth: null, state: [], events: [], unreadable: '' } }
        },
      },
    },
  } as unknown as CloudClient
  const cloud = { ...DEFAULT_CLOUD, enabled: true, visionModel: 'claude-sonnet-5-5', visionThinking: 'off' as const }
  const svc = new CloudService(() => 'sk-ant-test-0000000000000000000000', () => cloud, () => client)
  const img = { base64: 'AAAA', mediaType: 'image/jpeg' as const }
  await svc.analyzeFrame(aoe4, img, {})
  await svc.analyzeFrame(aoe4, img, {})
  assert.deepEqual(calls[0].thinking, { type: 'between_tools' })
  assert.equal(calls[0].fallbacks, undefined, 'no fallback model takes that setting')
  const v = svc.spend.vision
  assert.deepEqual([v.calls, v.input, v.cacheRead, v.output, v.thinking], [2, 2800, 18_000, 840, 120])
  assert.ok(v.usd > 0)
  svc.resetSession()
  assert.equal(svc.spend.vision.calls, 0)
})

test('the packs that learn validate, and say what they learn', () => {
  for (const p of [aoe4, mw4, fc, builtInProfile('aoe2-de')] as GameProfile[]) {
    assert.ok(validateProfile(p).ok, p.id)
    assert.ok((p.learning?.tracks.length ?? 0) >= 4, p.id)
    assert.ok(p.learning!.dimensions.some((d) => d.live), `${p.id}: something to recall live`)
  }
})

// --- The player's learning choices, your build, and the civilisation plans ------------------------

/** Three AoE IV games against English as French; the third with the given settings. */
function threeGames(learning: Record<string, unknown> = {}) {
  const history: MatchRecord[] = []
  let last: ReturnType<typeof play> & { report: ReturnType<CoachEngine['report']> } = null!
  for (const [n, seed] of [4, 5, 6].entries()) {
    const engine = engineFor(aoe4, settings({ profileId: 'aoe4', profileModeId: 'skirmish-vs-ai', mode: 'training', commentary: true, ...(n === 2 ? { learning: sanitizeSettings({ learning }).learning } : {}) }), {
      context: { civ: 'French' },
      matchHistory: [...history],
    })
    const r = play(engine, aoe4GameReplay({ seed }))
    history.push(...r.matches.map((m) => ({ ...m, id: `${m.id}-${seed}` })))
    last = { ...r, report: engine.report() }
  }
  return { history, last }
}

test('learning choices: off keeps nothing new and says nothing; recall off stays quiet; recall waits for enough games', () => {
  assert.deepEqual(sanitizeSettings({ learning: { aoe4: { enabled: false, minGames: 40 }, 'bad id': { recall: false } } }).learning, { aoe4: { enabled: false, minGames: 10 } })
  assert.deepEqual(learningPrefsFor('ea-fc', sanitizeSettings({})), { enabled: true, recall: true, minGames: 1 })

  const off = threeGames({ aoe4: { enabled: false } })
  assert.equal(off.history.at(-1)!.scout, undefined, 'nothing kept')
  assert.ok(!off.last.notices.some((n) => /^Your last/.test(n)))
  assert.equal(off.last.report.learned, null)
  assert.equal(off.last.report.intel?.reads.find((r) => /Longbow/.test(r.name))?.past ?? null, null, 'plans not linked either')

  const quiet = threeGames({ aoe4: { recall: false } })
  assert.ok(quiet.history.at(-1)!.scout, 'still learned')
  assert.ok(!quiet.last.notices.some((n) => /^Your last/.test(n)))

  assert.ok(!threeGames({ aoe4: { minGames: 3 } }).last.notices.some((n) => /^Your last/.test(n)), 'two games are not enough')
  assert.ok(threeGames({ aoe4: { minGames: 2 } }).last.notices.some((n) => /^Your last 2 games/.test(n)))
})

test('your build order: your buildings as they appear, without what you start with; your army; your civilisation’s plans you played', () => {
  const { history } = threeGames()
  const l = learnedFrom(history, aoe4, { civ: 'French' })!
  const yours = l.tracks.find((t) => t.id === 'your-buildings')!
  assert.deepEqual(
    yours.order.map((o) => o.name),
    ['Barracks', 'School of Cavalry', 'Blacksmith', 'Stable'],
    'the starting Town Center is not part of the order',
  )
  assert.match(learnedLines(l, 'you').join(' '), /^Your buildings in the usual order \(first seen\): Barracks \d:\d\d, School of Cavalry/)
  // French plans this player played (School of Cavalry is a French landmark), and English plans met.
  const played = plansSeen(aoe4, 'French', l, 'you')
  assert.ok(played.some((p) => p.seen === 3 && p.of === 3), JSON.stringify(played.slice(0, 4)))
  const met = plansSeen(aoe4, 'English', learnedFrom(history, aoe4, { opponent: 'English' })!, 'them')
  assert.ok(met.some((p) => /Longbow/.test(p.name) && p.seen === 3))
  assert.ok(met.some((p) => p.seen === 0), 'plans never met are listed as such')
  // Asked out loud.
  assert.match(answerLocally(classify("what's my usual build?", aoe4), aoe4, threeGames().last.report, history)!, /Your buildings in the usual order/)
})
