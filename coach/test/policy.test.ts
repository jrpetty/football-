// Safety-critical policy logic: the registry (fail closed) and the governor
// (category × window × status × mode). These tests are the contract that
// competitive and unknown scopes can never produce live output.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import registryJson from '../src/policy/registry.json' with { type: 'json' }
import { CoachingGovernor, effectiveMode } from '../src/core/policy/governor.ts'
import { resolvePolicy, validateRegistry, type ModeFacts, type ResolvedPolicy } from '../src/core/policy/registry.ts'
import { COACHING_MODES } from '../src/core/settings.ts'
import type { CoachingMessage, DeliveryWindow, MessageCategory } from '../src/core/types.ts'
import { settings } from './helpers.ts'

const NOW = new Date('2026-10-28T12:00:00Z')
const registry = (() => {
  const r = validateRegistry(registryJson)
  if (!r.ok) throw new Error(r.errors.join('\n'))
  return r.registry
})()

const OFFLINE: ModeFacts = { multiplayer: false, competitive: false }
const RANKED: ModeFacts = { multiplayer: true, competitive: true }
const resolve = (gameId: string | null, modeId: string | null, modeFacts: ModeFacts | null, tournament = false, now = NOW) =>
  resolvePolicy(registry, { gameId, modeId, modeFacts, tournament, now })

// --- Registry ------------------------------------------------------------------------

test('the shipped registry validates', () => {
  assert.ok(registry.entries.length >= 8)
})

test('registry validation rejects unsupported claims', () => {
  const bad = structuredClone(registryJson) as unknown as { entries: Record<string, unknown>[] }
  bad.entries[0].sources = [] // publisher basis without a source
  bad.entries[2].rationale = undefined // internal decision without rationale
  bad.entries.push({
    id: 'fake/live', publisher: 'Real Publisher', game: 'Real Game', gameIds: ['real'], modes: ['*'],
    status: 'approved_for_scope', scope: ['live'], basis: 'internal', rationale: 'we think so', sources: [],
    reviewedAt: '2026-10-01', reviewer: 'x', notes: '',
  })
  const r = validateRegistry(bad)
  assert.ok(!r.ok)
  const text = r.ok ? '' : r.errors.join('\n')
  assert.match(text, /must cite at least one source/)
  assert.match(text, /must state its rationale/)
  assert.match(text, /live approval for a real game cannot be an internal decision/)
})

test('VALORANT is post-match only in every mode, including offline practice', () => {
  for (const [mode, facts] of [['competitive', RANKED], ['range', OFFLINE], [null, null]] as const) {
    const p = resolve('valorant', mode, facts)
    assert.equal(p.status, 'post_match_only')
    assert.deepEqual([...p.scope], ['post_match'])
  }
})

test('FC: offline vs CPU allows live tips; everything else fails closed', () => {
  const offline = resolve('ea-fc', 'kickoff-vs-cpu', OFFLINE)
  assert.equal(offline.status, 'noncompetitive_only')
  assert.ok(offline.scope.has('live'))
  assert.match(offline.reasons[0], /internal policy, not publisher approval/)

  // Same mode id, but declared as multiplayer: only post-match.
  assert.ok(!resolve('ea-fc', 'kickoff-vs-cpu', { multiplayer: true, competitive: false }).scope.has('live'))

  for (const mode of ['division-rivals', 'squad-battles', 'clubs', 'something-new']) {
    const p = resolve('ea-fc', mode, RANKED)
    assert.equal(p.status, 'needs_publisher_review', mode)
    assert.ok(!p.scope.has('live') && !p.scope.has('round_break'), mode)
  }
})

test('unknown games: offline single-player gets the internal default, anything else fails closed', () => {
  const sp = resolve('some-new-game', 'story', OFFLINE)
  assert.equal(sp.status, 'noncompetitive_only')
  assert.equal(sp.entry?.id, 'default/offline-single-player')
  assert.ok(sp.scope.has('live'))

  const mp = resolve('some-new-game', 'online', { multiplayer: true, competitive: false })
  assert.equal(mp.status, 'needs_publisher_review')
  assert.deepEqual([...mp.scope], ['post_match'])

  const none = resolve(null, null, null)
  assert.deepEqual([...none.scope], ['post_match'])
})

test('a stale review is downgraded, and the tournament switch disables everything', () => {
  const later = new Date('2027-06-01T00:00:00Z')
  const stale = resolve('synthetic-arena', 'practice', OFFLINE, false, later)
  assert.equal(stale.recordedStatus, 'approved_for_scope')
  assert.equal(stale.status, 'needs_publisher_review')
  assert.match(stale.reasons[0], /last reviewed \d+ days ago/)

  const t = resolve('synthetic-arena', 'practice', OFFLINE, true)
  assert.equal(t.status, 'disabled')
  assert.equal(t.scope.size, 0)
})

// --- Governor ---------------------------------------------------------------------------

let n = 0
function msg(category: MessageCategory, at: number, over: Partial<CoachingMessage> = {}): CoachingMessage {
  return {
    id: `m${++n}`, category, text: 'Patch up first.', confidence: 0.9, grade: 'strong', evidenceSummary: 'test',
    topic: `topic-${category}`, priority: 'normal', createdAt: at, basedOnObservationAt: at, expiresAtMs: at + 2500,
    author: 'template', ...over,
  }
}

const WINDOWS: DeliveryWindow[] = ['during_play', 'round_break', 'post_match', 'idle']
const CATEGORIES: MessageCategory[] = ['descriptive', 'reflective', 'post_event', 'training', 'live_tactical']
const POLICIES: [string, ResolvedPolicy][] = [
  ['practice (all)', resolve('synthetic-arena', 'practice', OFFLINE)],
  ['ranked (round+post)', resolve('synthetic-arena', 'ranked', RANKED)],
  ['valorant (post only)', resolve('valorant', 'competitive', RANKED)],
  ['unknown multiplayer', resolve('x', 'online', RANKED)],
  ['tournament', resolve('synthetic-arena', 'practice', OFFLINE, true)],
]

test('exhaustive matrix: nothing is ever spoken outside the policy scope or the mode', () => {
  for (const [name, policy] of POLICIES) {
    for (const mode of COACHING_MODES) {
      for (const window of WINDOWS) {
        for (const category of CATEGORIES) {
          for (const requested of [false, true]) {
            const g = new CoachingGovernor(settings({ mode }), policy)
            const d = g.decide(msg(category, 1000, { requestedByPlayer: requested }), 1000, window)
            const where = `${name}/${mode}/${window}/${category}/${requested ? 'asked' : 'auto'}: ${d.record.decision} (${d.record.reasons.join('; ')})`
            if (d.record.decision !== 'spoken') {
              assert.equal(d.utterance, null, where)
              continue
            }
            const scope = window === 'during_play' ? 'live' : window === 'round_break' ? 'round_break' : 'post_match'
            assert.ok(policy.scope.has(scope), `spoke outside policy scope: ${where}`)
            assert.notEqual(mode, 'silent', where)
            if (window === 'during_play') assert.equal(mode, 'training', `spoke during play outside training mode: ${where}`)
            if (category === 'training' || category === 'live_tactical') assert.equal(window, 'during_play', where)
          }
        }
      }
    }
  }
})

test('competitive-safe and restricted policies block live tips but note them for later', () => {
  const g = new CoachingGovernor(settings({ mode: 'competitive-safe' }), POLICIES[1][1])
  const d = g.decide(msg('training', 0), 0, 'during_play')
  assert.equal(d.record.decision, 'queued')
  assert.deepEqual(g.takeQueuedTopics(), ['topic-training'])

  const v = new CoachingGovernor(settings({ mode: 'training' }), POLICIES[2][1])
  const live = v.decide(msg('training', 0), 0, 'during_play')
  assert.equal(live.record.decision, 'queued')
  assert.match(live.record.reasons[0], /VALORANT: post-match review only/)
})

test('a question asked during restricted play is held until the match ends', () => {
  const g = new CoachingGovernor(settings({ mode: 'training' }), POLICIES[3][1])
  const asked = g.decide(msg('descriptive', 0, { requestedByPlayer: true, text: 'Your answer.' }), 0, 'during_play')
  assert.equal(asked.record.decision, 'queued')
  assert.match(asked.record.reasons.join(' '), /held until the match ends/)
  assert.equal(g.decide(msg('descriptive', 0, { requestedByPlayer: true, expiresAtMs: 1e9 }), 10, 'post_match').record.decision, 'spoken')
})

test('training mode downgrades with a reason when the policy has no live scope', () => {
  assert.equal(effectiveMode('training', POLICIES[0][1]).liveActive, true)
  const ranked = effectiveMode('training', POLICIES[1][1])
  assert.equal(ranked.mode, 'competitive-safe')
  assert.match(ranked.downgradeReason ?? '', /Live tips are off/)
  assert.equal(effectiveMode('training', POLICIES[2][1]).mode, 'post-match')
})

test('live tips respect evidence, confidence, expiry, cooldown, repetition and mute', () => {
  const practice = POLICIES[0][1]
  const g = () => new CoachingGovernor(settings({ mode: 'training', cooldownSec: 20 }), practice)
  const why = (d: { record: { reasons: readonly string[] } }) => d.record.reasons.join(' ')

  assert.match(why(g().decide(msg('training', 0, { grade: 'weak' }), 0, 'during_play')), /evidence is weak/)
  assert.match(why(g().decide(msg('training', 0, { confidence: 0.4 }), 0, 'during_play')), /visual confidence/)
  assert.match(why(g().decide(msg('training', 0), 3000, 'during_play')), /moment passed/)

  const gov = g()
  assert.equal(gov.decide(msg('training', 0), 0, 'during_play').record.decision, 'spoken')
  assert.match(why(gov.decide(msg('training', 5000, { topic: 'other' }), 5000, 'during_play')), /cooldown/)
  assert.match(why(gov.decide(msg('training', 30_000), 30_000, 'during_play')), /same thing recently/)

  const muted = new CoachingGovernor(settings({ mode: 'training', muted: true }), practice)
  assert.equal(muted.decide(msg('training', 0), 0, 'during_play').record.decision, 'text-only')
})

test('reflective speech still pending when the next round starts is cut off outside live play', () => {
  const g = new CoachingGovernor(settings({ mode: 'competitive-safe' }), POLICIES[1][1])
  const d = g.decide(msg('reflective', 0, { text: 'A note.', expiresAtMs: 20_000 }), 0, 'round_break')
  assert.equal(d.record.decision, 'spoken')
  assert.deepEqual(g.onRoundStart(4000), [d.record.messageId])
})

test('over-long messages are refused rather than truncated', () => {
  const g = new CoachingGovernor(settings({ mode: 'training' }), POLICIES[0][1])
  const long = 'one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen'
  assert.equal(g.decide(msg('training', 0, { text: long }), 0, 'during_play').record.decision, 'suppressed')
})

