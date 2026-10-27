// The coaching-mode governor: who may speak, when, and why not.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CoachingGovernor, effectiveMode } from '../src/core/policy.ts'
import type { CandidateMessage, CoachingMode, EvidenceGrade, MessageKind } from '../src/core/types.ts'
import { arena, settings } from './helpers.ts'

const practice = arena.modes.find((m) => m.id === 'practice')!
const ranked = arena.modes.find((m) => m.id === 'ranked')!
const casual = arena.modes.find((m) => m.id === 'casual')!

let n = 0
function msg(kind: MessageKind, at: number, over: Partial<CandidateMessage> = {}): CandidateMessage {
  return {
    id: `m${++n}`, kind, topic: kind === 'live-cue' ? 'cue:x' : `topic-${n}`, text: 'Patch up first.', createdAt: at,
    basedOnEventAt: kind === 'live-cue' ? at : null, confidence: 0.9, grade: 'strong' as EvidenceGrade, priority: 1, ...over,
  }
}

test('training mode is downgraded to competitive-safe unless the game mode allows live coaching', () => {
  assert.equal(effectiveMode('training', practice).mode, 'training')
  for (const mode of [ranked, casual, null]) {
    const e = effectiveMode('training', mode)
    assert.equal(e.mode, 'competitive-safe')
    assert.match(e.downgradeReason ?? '', /Live tips are off/)
  }
  // Other modes are never upgraded or changed.
  for (const m of ['silent', 'post-round', 'post-match', 'competitive-safe'] as CoachingMode[]) {
    assert.equal(effectiveMode(m, practice).mode, m)
  }
})

test('live cues are only ever spoken in effective training mode', () => {
  for (const mode of ['silent', 'post-round', 'post-match', 'competitive-safe', 'training'] as CoachingMode[]) {
    for (const pm of [practice, ranked]) {
      const g = new CoachingGovernor(settings({ mode }), pm)
      const d = g.decide(msg('live-cue', 1000), 1000, { maxAgeMs: 2000 })
      const live = mode === 'training' && pm === practice
      assert.equal(d.record.decision === 'spoken', live, `${mode}/${pm.id}: ${d.record.decision} ${d.record.reasons}`)
      if (!live) assert.equal(d.utterance, null)
      if (!live && mode !== 'silent') assert.equal(d.record.decision, 'queued', 'deferred to reflective review')
    }
  }
})

test('live cues respect evidence, confidence, freshness, cooldown, repetition and mute', () => {
  const g = () => new CoachingGovernor(settings({ mode: 'training', cooldownSec: 20 }), practice)
  const reason = (d: ReturnType<CoachingGovernor['decide']>) => d.record.reasons.join(' ')

  assert.match(reason(g().decide(msg('live-cue', 0, { grade: 'weak' }), 0, { maxAgeMs: 2000 })), /evidence is weak/)
  assert.match(reason(g().decide(msg('live-cue', 0, { confidence: 0.4 }), 0, { maxAgeMs: 2000 })), /visual confidence/)
  const stale = g().decide(msg('live-cue', 0), 3000, { maxAgeMs: 2000 })
  assert.equal(stale.record.decision, 'suppressed')
  assert.match(reason(stale), /moment passed/)

  const gov = g()
  assert.equal(gov.decide(msg('live-cue', 0), 0, { maxAgeMs: 2000 }).record.decision, 'spoken')
  const tooSoon = gov.decide(msg('live-cue', 5000, { topic: 'cue:other' }), 5000, { maxAgeMs: 2000 })
  assert.match(reason(tooSoon), /cooldown/)
  const repeat = gov.decide(msg('live-cue', 30_000), 30_000, { maxAgeMs: 2000 })
  assert.match(reason(repeat), /same thing recently/)
  assert.equal(gov.decide(msg('live-cue', 31_000, { topic: 'cue:new' }), 31_000, { maxAgeMs: 2000 }).record.decision, 'spoken')

  const muted = new CoachingGovernor(settings({ mode: 'training', muted: true }), practice)
  const m = muted.decide(msg('live-cue', 0), 0, { maxAgeMs: 2000 })
  assert.equal(m.record.decision, 'text-only')
  assert.equal(m.utterance, null)
})

test('spoken live cues expire with the moment they refer to', () => {
  const g = new CoachingGovernor(settings({ mode: 'training' }), practice)
  const d = g.decide(msg('live-cue', 1000), 1200, { maxAgeMs: 2000 })
  assert.equal(d.utterance?.expiresAt, 3000)
})

test('reflective messages follow the mode: silent never speaks, post-match holds round notes', () => {
  const decide = (mode: CoachingMode, kind: MessageKind) =>
    new CoachingGovernor(settings({ mode }), ranked).decide(msg(kind, 0, { text: 'Round lost. A note.' }), 0).record.decision
  assert.equal(decide('silent', 'round-review'), 'text-only')
  assert.equal(decide('silent', 'match-summary'), 'text-only')
  assert.equal(decide('post-match', 'round-review'), 'text-only')
  assert.equal(decide('post-match', 'match-summary'), 'spoken')
  assert.equal(decide('post-round', 'round-review'), 'spoken')
  assert.equal(decide('competitive-safe', 'round-review'), 'spoken')
})

test('reflective speech still pending when the next round starts is cancelled outside training', () => {
  const g = new CoachingGovernor(settings({ mode: 'competitive-safe' }), ranked)
  const d = g.decide(msg('round-review', 0, { text: 'A note.' }), 0)
  assert.deepEqual(g.onRoundStart(4000), [d.record.messageId])
  assert.deepEqual(g.onRoundStart(5000), [], 'only cancelled once')

  const t = new CoachingGovernor(settings({ mode: 'training' }), practice)
  t.decide(msg('round-review', 0, { text: 'A note.' }), 0)
  assert.deepEqual(t.onRoundStart(4000), [])
})

test('over-long messages are refused rather than truncated mid-thought', () => {
  const g = new CoachingGovernor(settings({ mode: 'training' }), practice)
  const d = g.decide(msg('live-cue', 0, { text: 'one two three four five six seven eight nine ten eleven twelve thirteen' }), 0, { maxAgeMs: 2000 })
  assert.equal(d.record.decision, 'suppressed')
})
