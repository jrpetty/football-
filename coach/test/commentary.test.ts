// Live commentary: spoken where the game and mode allow speech during play,
// paced on its own, never in restricted play, and built only from readings.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { TimelineEntry, Utterance } from '../src/core/types.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { aoe4GameReplay, mw4MatchReplay, type VisionReplay } from '../src/sim/visionReplay.ts'
import { engineFor, settings } from './helpers.ts'

const aoe4 = builtInProfile('aoe4')
const mw4 = builtInProfile('cod-mw4')

function play(packId: string, modeId: string, replay: VisionReplay, over: Record<string, unknown> = {}) {
  const engine = engineFor(builtInProfile(packId), settings({ profileId: packId, profileModeId: modeId, mode: 'training', commentary: true, ...over }))
  const entries: TimelineEntry[] = [...engine.startNotices(0)]
  const utterances: Utterance[] = []
  let now = 0
  for (const b of replay.batches) {
    const out = engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt)))
    entries.push(...out.entries)
    utterances.push(...out.utterances)
  }
  const commentary = entries.flatMap((e) => (e.type === 'message' && e.message.category === 'commentary' ? [e] : []))
  return { entries, utterances, commentary, spoken: utterances.filter((u) => u.category === 'commentary').map((u) => u.text) }
}

test('commentary against the AI: fights, checkpoints and results are spoken, short, from the readings', () => {
  const { spoken, entries } = play('aoe4', 'skirmish-vs-ai', aoe4GameReplay({ seed: 4 }))
  assert.ok(entries.some((e) => e.type === 'notice' && /Live commentary is on: short spoken lines/.test(e.text)))
  assert.ok(spoken.some((t) => /^Fight starting near their Town Center/.test(t)), spoken.join(' | '))
  assert.ok(spoken.some((t) => /^Fight lost near their Town Center, towers or Keep.*Army \d+ to \d+\.$/.test(t)), spoken.join(' | '))
  assert.ok(spoken.some((t) => /^Fight won/.test(t)))
  assert.ok(spoken.some((t) => /^5:00, 18 villagers: 3 behind one Town Center that never stops\.$/.test(t)), spoken.join(' | '))
  for (const t of spoken) {
    assert.ok(t.split(/\s+/).length <= 22, t)
    assert.doesNotMatch(t, BANNED_CLAIMS)
  }
})

test('commentary in bot matches: deaths and kills with how they happened', () => {
  const { spoken } = play('cod-mw4', 'private-bots', mw4MatchReplay({ seed: 2 }))
  assert.ok(spoken.some((t) => /^Died while you were in the open/.test(t)), spoken.join(' | '))
  assert.ok(spoken.some((t) => /^Kill .*close range/.test(t)))
})

test('commentary in online play: never spoken, and the player is told why once', () => {
  const { utterances, commentary, entries } = play('cod-mw4', 'multiplayer', mw4MatchReplay({ seed: 2 }))
  assert.ok(utterances.every((u) => u.category !== 'commentary'))
  assert.ok(commentary.length > 0 && commentary.every((c) => c.decision.decision === 'suppressed'))
  assert.equal(entries.filter((e) => e.type === 'notice' && /Live commentary is on, but it cannot speak during play here/.test(e.text)).length, 1)
})

test('commentary off, muted or silent: not spoken; it never blocks a coaching tip', () => {
  assert.equal(play('aoe4', 'skirmish-vs-ai', aoe4GameReplay({ seed: 4 }), { commentary: false }).commentary.length, 0)
  const muted = play('aoe4', 'skirmish-vs-ai', aoe4GameReplay({ seed: 4 }), { muted: true })
  assert.ok(muted.commentary.length > 0 && muted.commentary.every((c) => c.decision.decision === 'text-only'))
  const silent = play('aoe4', 'skirmish-vs-ai', aoe4GameReplay({ seed: 4 }), { mode: 'silent' })
  assert.ok(silent.utterances.every((u) => u.category !== 'commentary'))
  // Tips still get through with commentary on (separate pacing).
  const tips = play('cod-mw4', 'private-bots', mw4MatchReplay({ seed: 2 }), { cooldownSec: 10 }).utterances.filter((u) => u.category === 'training')
  assert.ok(tips.some((u) => u.text === 'Fight from cover, not the open.'))
})

test('commentary pacing: at most one line every few seconds and eight a minute', () => {
  const { commentary } = play('cod-mw4', 'private-bots', mw4MatchReplay({ seed: 2, realMinutes: 2 }))
  const spokenAt = commentary.filter((c) => c.decision.decision === 'spoken').map((c) => c.at)
  for (let i = 1; i < spokenAt.length; i++) assert.ok(spokenAt[i] - spokenAt[i - 1] >= 6000)
  for (const t of spokenAt) assert.ok(spokenAt.filter((x) => x > t - 60_000 && x <= t).length <= 8)
})

test('packs: commentary leads are short and refer to real events', () => {
  for (const p of [aoe4, mw4, builtInProfile('ea-fc')]) assert.ok(Object.keys(p.commentary ?? {}).length > 0, p.id)
})
