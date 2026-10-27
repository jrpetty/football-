// End to end: synthetic HUD pixels → engine → spoken/queued messages, checked
// against the simulator's ground truth.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CoachEngine } from '../src/core/engine.ts'
import { DEFAULT_SETTINGS } from '../src/core/settings.ts'
import type { CoachingMode, TimelineEntry, Utterance } from '../src/core/types.ts'
import { renderHud, simulateFrames, type GroundTruth } from '../src/sim/syntheticArena.ts'
import { arena, settings } from './helpers.ts'

function run(mode: CoachingMode, profileModeId: string, seed = 7, rounds = 12) {
  const engine = new CoachEngine(arena, settings({ mode, profileModeId }))
  const entries: TimelineEntry[] = [...engine.startNotices(0)]
  const utterances: Utterance[] = []
  const gen = simulateFrames({ rounds, seed, fps: 5 })
  let r = gen.next()
  while (!r.done) {
    const out = engine.processFrame(r.value)
    entries.push(...out.entries)
    utterances.push(...out.utterances)
    r = gen.next()
  }
  return { engine, entries, utterances, truth: r.value as GroundTruth }
}

const messages = (entries: TimelineEntry[]) => entries.flatMap((e) => (e.type === 'message' ? [e] : []))
const events = (entries: TimelineEntry[], id: string) => entries.filter((e) => e.type === 'event' && e.event.ruleId === id)

test('visible events match the simulator ground truth', () => {
  const { entries, truth } = run('silent', 'ranked')
  assert.equal(events(entries, 'health-low').length, truth.lowHealth.length)
  assert.equal(events(entries, 'eliminated').length, truth.lowHealth.filter((l) => l.eliminated).length)
  assert.equal(events(entries, 'round-start').length, truth.rounds.length)
  assert.equal(events(entries, 'round-won').length, truth.rounds.filter((x) => x.result === 'win').length)
  assert.equal(events(entries, 'round-lost').length, truth.rounds.filter((x) => x.result === 'loss').length)
  assert.equal(events(entries, 'match-end').length, 1)

  const counted = entries.flatMap((e) => (e.type === 'episode' && e.episode.episodeId === 'low-health' && !e.episode.excluded ? [e.episode] : []))
  const healedTruth = truth.lowHealth.filter((l) => l.healed).length
  assert.equal(counted.filter((e) => e.response === 'healed').length, healedTruth)
})

test('the coach learns the real pattern and states it with honest strength', () => {
  const { engine } = run('silent', 'ranked')
  const low = engine.insights().find((i) => i.insight.episodeId === 'low-health')!
  assert.equal(low.insight.worse.response, 'none')
  assert.equal(low.insight.better.response, 'healed')
  assert.ok(['moderate', 'strong'].includes(low.insight.grade))
})

test('the coach does not invent a strong pattern the simulator does not contain', () => {
  // In the simulator, using the escape ability has no effect on eliminations.
  for (const seed of [1, 2, 3, 4, 5, 6]) {
    const { engine } = run('silent', 'ranked', seed)
    const heavy = engine.insights().find((i) => i.insight.episodeId === 'heavy-hit')
    assert.notEqual(heavy?.insight.grade, 'strong', `seed ${seed}: ${heavy?.statement}`)
  }
})

test('silent tracker never produces speech', () => {
  const { utterances, entries } = run('silent', 'practice')
  assert.equal(utterances.length, 0)
  assert.ok(messages(entries).every((m) => m.decision.decision !== 'spoken'))
})

test('competitive-safe and training-in-ranked never speak live cues', () => {
  for (const mode of ['competitive-safe', 'training', 'post-round'] as const) {
    const { utterances, entries } = run(mode, 'ranked')
    assert.ok(utterances.every((u) => u.kind !== 'live-cue'), mode)
    assert.ok(utterances.length > 0, `${mode} still gives reflective feedback`)
    const queued = messages(entries).filter((m) => m.message.kind === 'live-cue')
    assert.ok(queued.every((m) => m.decision.decision === 'queued'))
  }
  const { entries } = run('training', 'ranked')
  assert.ok(entries.some((e) => e.type === 'notice' && /Live tips are off/.test(e.text)))
})

test('training mode in single-player practice speaks a live cue only once evidence is moderate', () => {
  const { entries, utterances } = run('training', 'practice')
  const live = messages(entries).filter((m) => m.message.kind === 'live-cue')
  const spoken = live.filter((m) => m.decision.decision === 'spoken')
  assert.ok(spoken.length >= 1, 'at least one live cue')
  assert.ok(spoken.every((m) => m.message.grade === 'moderate' || m.message.grade === 'strong'))
  assert.ok(live.some((m) => /evidence is weak/.test(m.decision.reasons.join(' '))), 'early cues held back')
  // Spoken live cues are brief and rate-limited.
  const times = utterances.filter((u) => u.kind === 'live-cue').map((u) => u.expiresAt!)
  for (let i = 1; i < times.length; i++) assert.ok(times[i] - times[i - 1] >= DEFAULT_SETTINGS.cooldownSec * 1000)
})

test('post-match mode says nothing until the match ends, then gives one summary', () => {
  const { utterances } = run('post-match', 'ranked')
  assert.equal(utterances.length, 1)
  assert.equal(utterances[0].kind, 'match-summary')
  assert.match(utterances[0].text, /^Match summary\. You won \d+ of 12 rounds/)
})

test('every message decision carries a reason', () => {
  const { entries } = run('training', 'practice')
  for (const m of messages(entries)) assert.ok(m.decision.reasons.length > 0, m.message.text)
})

test('an unreadable HUD is reported and stops inference', () => {
  const engine = new CoachEngine(arena, settings())
  const blank = renderHud({ health: null, abilityReady: null, roundLive: false, eliminated: false, banner: null }, 256, 144, 0)
  const out: TimelineEntry[] = []
  for (let t = 0; t <= 4000; t += 200) out.push(...engine.processFrame({ ...blank, timestamp: t }).entries)
  assert.ok(out.some((e) => e.type === 'notice' && /Cannot read the HUD/.test(e.text)))
  assert.equal(engine.status.hudReadable, false)
})

test('a wrong aspect ratio is flagged', () => {
  const engine = new CoachEngine(arena, settings())
  const f = renderHud({ health: 1, abilityReady: true, roundLive: true, eliminated: false, banner: null }, 200, 150, 0)
  const out = engine.processFrame(f)
  assert.ok(out.entries.some((e) => e.type === 'notice' && /expects 1\.78:1/.test(e.text)))
})

test('manual boundaries and stop produce a summary without fabricating a result', () => {
  const engine = new CoachEngine(arena, settings({ mode: 'post-match' }))
  const gen = simulateFrames({ rounds: 2, seed: 3, fps: 5 })
  // Feed only the first ~40s (no match-end banner yet), then end the match by hand.
  for (let r = gen.next(); !r.done && r.value.timestamp < 40_000; r = gen.next()) engine.processFrame(r.value)
  const out = engine.manualBoundary('match-end', 'unknown', 40_000)
  const summary = out.entries.find((e) => e.type === 'message' && e.message.kind === 'match-summary')
  assert.ok(summary && summary.type === 'message')
  assert.equal(summary.decision.decision, 'spoken')
  assert.ok(out.entries.some((e) => e.type === 'event' && e.event.manual))
  // Nothing left to summarise after the summary.
  assert.equal(engine.stop(41_000).utterances.length, 0)
})

test('long-term tendencies load only when the player opted in', () => {
  const first = run('silent', 'ranked')
  const snap = first.engine.snapshot(new Date())
  const optedOut = new CoachEngine(arena, settings({ saveTendencies: false }), snap)
  assert.equal(optedOut.insights().length, 0)
  const optedIn = new CoachEngine(arena, settings({ saveTendencies: true }), snap)
  assert.ok(optedIn.insights().some((i) => i.insight.usesHistory))
})
