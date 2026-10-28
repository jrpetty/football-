// The AoE IV roster in the pack, and scouting intel end to end on the scripted game.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { Utterance } from '../src/core/types.ts'
import { answerLocally, buildDigest, classify } from '../src/core/qa/ask.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { aoe4GameReplay } from '../src/sim/visionReplay.ts'
import { rosterText } from '../src/core/knowledge/base.ts'
import { engineFor, settings } from './helpers.ts'

const aoe4 = builtInProfile('aoe4')
const roster = aoe4.knowledge.roster!

test('roster: every released civilisation and the announced ones, each with unique units and how to play against it', () => {
  const released = roster.factions.filter((f) => f.status === 'released')
  assert.ok(released.length >= 23, `${released.length} released`)
  for (const name of ['English', 'French', 'Holy Roman Empire', 'Mongols', 'Rus', 'Chinese', 'Delhi Sultanate', 'Abbasid Dynasty', 'Malians', 'Ottomans', 'Byzantines', 'Japanese']) {
    assert.ok(released.some((f) => f.name === name), name)
  }
  const upcoming = roster.factions.filter((f) => f.status === 'upcoming').map((f) => f.name)
  assert.deepEqual(upcoming.sort(), ['Scots', 'Vikings'])
  for (const f of roster.factions) assert.ok(f.uniqueUnits.length > 0, `${f.name} has unique units`)
  assert.ok(released.filter((f) => f.howToPlayAgainst).length >= 20)
  // Counters resolve to units or classes, and every generic military unit has an answer.
  for (const u of roster.units.filter((x) => !x.factions && !x.classes.includes('workers') && x.counteredBy.length === 0)) assert.fail(`${u.name} has no counter`)
  assert.ok((roster.counterRules ?? []).length >= 25)
})

test('roster prompt text: names to report and how to tell units apart, within budget', () => {
  const vision = rosterText(roster, { budget: 36_000, detail: 'vision' })
  assert.match(vision, /- Longbowman \(English\)/)
  assert.match(vision, /Vikings \(UPCOMING, not released\)/)
  assert.ok(vision.length <= 36_000)
  assert.match(rosterText(roster, { budget: 100_000, detail: 'answer' }), /### Counter rules/)
})

test('scouting intel end to end: the opponent, their buildings and army, what beats it (for your civilisation)', async () => {
  const replay = aoe4GameReplay({ seed: 4 })
  const engine = engineFor(aoe4, settings({ profileId: 'aoe4', profileModeId: 'skirmish-vs-ai', mode: 'training', commentary: true, context: { aoe4: { civ: 'French' } } }))
  const utterances: Utterance[] = []
  let now = 0
  for (const b of replay.batches) utterances.push(...engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt))).utterances)
  const intel = engine.report().intel!
  assert.equal(intel.faction?.name, 'English')
  assert.deepEqual(intel.buildings.map((b) => [b.name, b.count]).sort(), [['Archery Range', 1], ['Barracks', 2]])
  assert.equal(intel.units[0].name, 'Longbowman')
  assert.equal(intel.units[0].count, 10)
  assert.ok(intel.counters[0].build.length > 0)
  const spoken = utterances.filter((u) => u.category === 'commentary').map((u) => u.text)
  assert.ok(spoken.includes('Opponent: English. Watch for Longbowman, King and Wynguard Footman.') || spoken.some((t) => /^Opponent: English\./.test(t)), spoken.join(' | '))
  assert.ok(spoken.some((t) => /^They have an Archery Range: expect Longbowmen/.test(t)), spoken.join(' | '))
  assert.ok(spoken.some((t) => /^They're making Longbowmen\./.test(t)), spoken.join(' | '))
  const { localReview } = await import('../src/core/review.ts')
  const review = localReview(aoe4, engine.report())!
  assert.ok(review.focus.some((f) => f.evidence === 'You mostly met Longbowmen (10 at once).' && /^Build .+ against them\.$/.test(f.fix)), JSON.stringify(review.focus))
  // Answers and Claude's digest use the same intel.
  assert.equal(classify('what are they making?', aoe4).kind, 'intel')
  assert.match(answerLocally(classify('what are they making?', aoe4), aoe4, engine.report(), [])!, /They are English\..*Longbowman \(up to 10\)/)
  const digest = JSON.parse(buildDigest({ profile: aoe4, modeLabel: null, policyLine: '', report: engine.report(), history: [], notes: null, research: null }))
  assert.equal(digest.session.scouted.faction.name, 'English')
})

test('scouting intel in ranked: kept for after the match, nothing spoken during play', () => {
  const engine = engineFor(aoe4, settings({ profileId: 'aoe4', profileModeId: 'ranked-1v1', mode: 'training', commentary: true }))
  const utterances: Utterance[] = []
  let now = 0
  for (const b of aoe4GameReplay({ seed: 4 }).batches) utterances.push(...engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt))).utterances)
  assert.ok(utterances.every((u) => u.category === 'post_event'))
  assert.equal(engine.report().intel?.faction?.name, 'English')
})
