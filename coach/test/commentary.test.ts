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

// --- Saying something: status lines, the age, mistakes, and why it is quiet -------------------

test('commentary against the AI never goes quiet for long: a status line from the readings, and the age-up', () => {
  const { spoken } = play('aoe4', 'skirmish-vs-ai', aoe4GameReplay({ seed: 4, realMinutes: 16, intervalMs: 3000 }), { mode: 'competitive-safe' })
  const status = spoken.filter((t) => /^\d+:\d\d, population \d+ of \d+/.test(t))
  assert.ok(status.length >= 4, spoken.join('\n'))
  // Only readings that were on screen, in the pack's order.
  assert.match(status[0], /^\d+:\d\d, population \d+ of \d+, Dark Age, food \d+, wood \d+, gold \d+\.$/)
  assert.ok(spoken.some((t) => /^You reached the Feudal Age at 4:\d\d\.$/.test(t)), 'the age-up is said')
  for (const t of spoken) {
    assert.doesNotMatch(t, BANNED_CLAIMS)
    assert.ok(t.split(/\s+/).length <= 22, t)
  }
})

test('commentary: a status line waits for a quiet spell and leaves out what is not on screen', async () => {
  const { statusLine } = await import('../src/core/commentary.ts')
  const parts = aoe4.commentaryStatus!.parts
  const read = (vals: Record<string, string | number>) => (k: string) => vals[k] ?? null
  assert.equal(statusLine(parts, 2, read({ gameTimeSec: 605, population: 31, populationCap: 40, age: 'feudal' })), '10:05, population 31 of 40, Feudal Age.')
  assert.equal(statusLine(parts, 2, read({ gameTimeSec: 605 })), null, 'too little on screen')
  assert.equal(statusLine(parts, 2, read({ age: 'castle', food: 1200, wood: 40, gold: 900 })), 'Castle Age, food 1,200, wood 40, gold 900.')
})

test('commentary in competitive-safe mode says what it sees of mistakes; training mode leaves that to the tip', async () => {
  const { aoe2GameReplay } = await import('../src/sim/visionReplay.ts')
  const safe = play('aoe2-de', 'skirmish-vs-ai', aoe2GameReplay({ seed: 5 }), { mode: 'competitive-safe' })
  assert.ok(safe.spoken.some((t) => /^Housed at \d+ of \d+\.$/.test(t)), safe.spoken.join('\n'))
  const training = play('aoe2-de', 'skirmish-vs-ai', aoe2GameReplay({ seed: 5 }), { mode: 'training' })
  assert.ok(!training.spoken.some((t) => t.startsWith('Housed at')))
  assert.ok(training.utterances.some((u) => u.category === 'training' && /housed/i.test(u.text)))
})

test('commentary at capture start says whether it can talk here, and where it can', async () => {
  const { commentaryStartLine } = await import('../src/core/commentary.ts')
  const { policyFor } = await import('./helpers.ts')
  const s = (profileModeId: string | null, over: Record<string, unknown> = {}) => settings({ profileId: 'aoe4', profileModeId, commentary: true, ...over })
  const live = ['Campaign / Historical Battles', 'Skirmish vs AI']
  assert.equal(commentaryStartLine(aoe4, s('skirmish-vs-ai'), policyFor(aoe4, 'skirmish-vs-ai'), live), 'Commentary on. I will call out what I see.')
  assert.equal(
    commentaryStartLine(aoe4, s('ranked-1v1'), policyFor(aoe4, 'ranked-1v1'), live),
    'Commentary is on, but in Ranked 1v1 I stay quiet until the match ends, then you get the review. I can talk during Campaign / Historical Battles and Skirmish vs AI.',
  )
  assert.match(commentaryStartLine(aoe4, s(null), policyFor(aoe4, null), live), /^Commentary is on, but the game mode is not set, so I stay quiet during play\. Pick the mode on the Coach screen\./)
  assert.match(commentaryStartLine(aoe4, s('skirmish-vs-ai', { mode: 'silent' }), policyFor(aoe4, 'skirmish-vs-ai'), live), /silent tracker mode/)
  assert.match(commentaryStartLine(aoe4, s('skirmish-vs-ai', { tournament: true }), policyFor(aoe4, 'skirmish-vs-ai', { tournament: true }), live), /tournament mode keeps me silent/)
  for (const m of [null, 'ranked-1v1', 'skirmish-vs-ai']) assert.doesNotMatch(commentaryStartLine(aoe4, s(m), policyFor(aoe4, m), live), BANNED_CLAIMS)
})

test('commentary in ranked play: still never spoken, status lines and the age-up included', () => {
  const { utterances, commentary } = play('aoe4', 'ranked-1v1', aoe4GameReplay({ seed: 4, realMinutes: 16, intervalMs: 3000 }), { mode: 'competitive-safe' })
  assert.ok(utterances.every((u) => u.category !== 'commentary'))
  assert.ok(commentary.length > 0 && commentary.every((c) => c.decision.decision === 'suppressed'))
})

test('packs: readings in commentary lines must exist', async () => {
  const { validateProfile } = await import('../src/core/profile/schema.ts')
  const bad = JSON.parse(JSON.stringify(aoe4))
  bad.commentaryStatus.parts = ['{population} of {popCap}']
  bad.commentaryChanges = [{ key: 'era', text: '{era|minutes}' }]
  const r = validateProfile(bad)
  assert.ok(!r.ok)
  const errors = (r as { errors: string[] }).errors.join('\n')
  assert.match(errors, /commentaryStatus\.parts\[0\]: unknown reading "popCap"/)
  assert.match(errors, /commentaryChanges\[0\]\.key: unknown state key "era"/)
  assert.match(errors, /unknown format "minutes"/)
})
