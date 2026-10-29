// Automatic snapshots: which events make one, the tags in the pack's words, and finding the frame.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { autoMomentFor, RecentFrames } from '../src/core/autoMoments.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import type { GameEvent } from '../src/core/types.ts'

const ev = (ruleId: string, attributes: Record<string, string> = {}, extra: Partial<GameEvent> = {}): GameEvent => ({ uid: 'e1', ruleId, label: ruleId === 'death' ? 'Death' : 'Fight lost', category: 'outcome', at: 1000, confidence: 0.8, evidence: 'x', attributes, ...extra })

test('a death in Call of Duty and a lost fight in AoE IV make a key moment, with what was read in the pack\'s words', () => {
  const cod = builtInProfile('cod-mw4')
  const m = autoMomentFor(cod, ev('death', { exposure: 'in_open', movement: 'unknown' }))!
  assert.equal(m.event, 'death')
  assert.ok(m.tags.includes('Cover when you died: In the open'), m.tags.join(' | '))
  assert.ok(!m.tags.some((t) => /unknown/.test(t)), 'unread circumstances are left out')
  assert.equal(autoMomentFor(cod, ev('kill')), null, 'a kill is not a key moment')
  assert.equal(autoMomentFor(cod, ev('death', {}, { manual: true })), null)
  const aoe4 = builtInProfile('aoe4')
  const f = autoMomentFor(aoe4, ev('fight_lost', { location: 'near_enemy_defenses' }))!
  assert.ok(f.tags.includes('Where you fought: Near their defences'), f.tags.join(' | '))
  assert.equal(autoMomentFor(aoe4, ev('fight_won')), null)
})

test('the frame an event was read from: the closest within 1.5 s, used once; old frames drop out', () => {
  const r = new RecentFrames<string>(3)
  r.keep(1000, 'a')
  r.keep(4000, 'b')
  r.keep(7000, 'c')
  assert.equal(r.take(4200), 'b')
  assert.equal(r.take(4200), null, 'used once')
  assert.equal(r.keep(10_000, 'd'), null)
  assert.equal(r.keep(13_000, 'e'), 'a', 'the oldest drops out')
  assert.equal(r.take(20_000), null)
  assert.deepEqual(r.clear(), ['c', 'd', 'e'])
})
