// End to end on the pixel path: synthetic HUD pixels → engine → spoken/queued
// messages, checked against the simulator's ground truth and the policy registry.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { CoachingMode, TimelineEntry, Utterance } from '../src/core/types.ts'
import { DEFAULT_SETTINGS } from '../src/core/settings.ts'
import { renderHud, simulateFrames, type GroundTruth } from '../src/sim/syntheticArena.ts'
import { arena, engineFor, settings } from './helpers.ts'

function run(mode: CoachingMode, profileModeId: string, seed = 7, rounds = 12, opts: { tournament?: boolean } = {}) {
  const engine = engineFor(arena, settings({ mode, profileModeId }), opts)
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
  assert.equal(counted.filter((e) => e.response === 'healed').length, truth.lowHealth.filter((l) => l.healed).length)
})

test('the coach learns the real pattern and states it with honest strength', () => {
  const { engine } = run('silent', 'ranked')
  const low = engine.insights().find((i) => i.insight.episodeId === 'low-health')!
  assert.equal(low.insight.worse.response, 'none')
  assert.equal(low.insight.better.response, 'healed')
  assert.ok(['moderate', 'strong'].includes(low.insight.grade))
})

test('the coach does not invent a strong pattern the simulator does not contain', () => {
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

test('ranked (round notes approved, no live) never speaks live tips but gives reflective notes', () => {
  for (const mode of ['competitive-safe', 'training', 'post-round'] as const) {
    const { utterances, entries } = run(mode, 'ranked')
    assert.ok(utterances.every((u) => u.category !== 'training' && u.category !== 'live_tactical'), mode)
    assert.ok(utterances.some((u) => u.category === 'reflective'), `${mode} still gives round notes`)
    const live = messages(entries).filter((m) => m.message.category === 'training')
    assert.ok(live.every((m) => m.decision.decision === 'queued'))
  }
  const { entries } = run('training', 'ranked')
  assert.ok(entries.some((e) => e.type === 'notice' && /Live tips are off/.test(e.text)))
})

test('an unreviewed multiplayer mode is post-match only: nothing during play or between rounds', () => {
  const { utterances, entries } = run('training', 'casual')
  assert.deepEqual([...new Set(utterances.map((u) => u.category))], ['post_event'])
  const roundNotes = messages(entries).filter((m) => m.message.category === 'reflective')
  assert.ok(roundNotes.every((m) => m.decision.decision === 'queued'))
})

test('tournament mode: nothing is ever spoken', () => {
  const { utterances, entries } = run('training', 'practice', 7, 12, { tournament: true })
  assert.equal(utterances.length, 0)
  assert.ok(entries.some((e) => e.type === 'notice' && /Tournament/.test(e.text)))
})

test('training in single-player practice speaks a live tip only once evidence is moderate', () => {
  const { entries, utterances } = run('training', 'practice')
  const live = messages(entries).filter((m) => m.message.category === 'training')
  const spoken = live.filter((m) => m.decision.decision === 'spoken')
  assert.ok(spoken.length >= 1, 'at least one live tip')
  assert.ok(spoken.every((m) => m.message.grade === 'moderate' || m.message.grade === 'strong'))
  assert.ok(live.some((m) => /evidence is weak/.test(m.decision.reasons.join(' '))), 'early tips held back')
  const times = utterances.filter((u) => u.category === 'training').map((u) => u.expiresAt!)
  for (let i = 1; i < times.length; i++) assert.ok(times[i] - times[i - 1] >= DEFAULT_SETTINGS.cooldownSec * 1000)
})

test('post-match mode says nothing until the match ends, then gives one summary', () => {
  const { utterances } = run('post-match', 'ranked')
  assert.equal(utterances.length, 1)
  assert.equal(utterances[0].category, 'post_event')
  assert.match(utterances[0].text, /^Match summary\. You won \d+ of 12 rounds/)
})

test('every message decision carries a reason and the policy status', () => {
  const { entries } = run('training', 'practice')
  for (const m of messages(entries)) {
    assert.ok(m.decision.reasons.length > 0, m.message.text)
    assert.equal(m.decision.policyStatus, 'approved_for_scope')
    assert.ok(m.message.evidenceSummary.length > 0)
  }
})

test('an unreadable HUD is reported and stops inference', () => {
  const engine = engineFor(arena, settings())
  const blank = renderHud({ health: null, abilityReady: null, roundLive: false, eliminated: false, banner: null }, 256, 144, 0)
  const out: TimelineEntry[] = []
  for (let t = 0; t <= 4000; t += 200) out.push(...engine.processFrame({ ...blank, timestamp: t }).entries)
  assert.ok(out.some((e) => e.type === 'notice' && /Cannot read the HUD/.test(e.text)))
  assert.equal(engine.status.hudReadable, false)
})

test('a black capture marks the state stale with a reason', () => {
  const engine = engineFor(arena, settings())
  const black = { width: 256, height: 144, data: new Uint8ClampedArray(256 * 144 * 4).fill(0), timestamp: 0 }
  const out: TimelineEntry[] = []
  for (let t = 0; t <= 2000; t += 200) out.push(...engine.processFrame({ ...black, data: new Uint8ClampedArray(black.data), timestamp: t }).entries)
  assert.ok(out.some((e) => e.type === 'notice' && /capture is black/.test(e.text)))
  assert.equal(engine.status.visible.stale, true)
})

test('a wrong aspect ratio is flagged', () => {
  const engine = engineFor(arena, settings())
  const f = renderHud({ health: 1, abilityReady: true, roundLive: true, eliminated: false, banner: null }, 200, 150, 0)
  const out = engine.processFrame(f)
  assert.ok(out.entries.some((e) => e.type === 'notice' && /expects 1\.78:1/.test(e.text)))
})

test('manual boundaries and stop produce a summary without fabricating a result', () => {
  const engine = engineFor(arena, settings({ mode: 'post-match' }))
  const gen = simulateFrames({ rounds: 2, seed: 3, fps: 5 })
  for (let r = gen.next(); !r.done && r.value.timestamp < 40_000; r = gen.next()) engine.processFrame(r.value)
  const out = engine.manualBoundary('match-end', 'unknown', 40_000)
  const summary = out.entries.find((e) => e.type === 'message' && e.message.category === 'post_event')
  assert.ok(summary && summary.type === 'message')
  assert.equal(summary.decision.decision, 'spoken')
  assert.ok(out.entries.some((e) => e.type === 'event' && e.event.manual))
  assert.equal(out.matches.length, 1)
  assert.equal(out.matches[0].endedManually, true)
  assert.equal(engine.stop(41_000).utterances.length, 0)
})

test('long-term tendencies load only when the player opted in', () => {
  const first = run('silent', 'ranked')
  const snap = first.engine.snapshot(new Date())
  assert.equal(engineFor(arena, settings({ saveTendencies: false }), { history: snap }).insights().length, 0)
  assert.ok(engineFor(arena, settings({ saveTendencies: true }), { history: snap }).insights().some((i) => i.insight.usesHistory))
})
