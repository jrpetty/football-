// The AoE IV pack's civilisation plans, read against the real roster: every
// civilisation is covered from the first age to the fourth, generic sightings
// never become a read, per-civilisation unit ages hold, and what is spoken is
// fit to speak.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import aoe4Json from '../src/profiles/aoe4.json' with { type: 'json' }
import { IntelTracker } from '../src/core/analyses/intel.ts'
import type { GameProfile } from '../src/core/profile/schema.ts'
import type { Utterance, VisibleState } from '../src/core/types.ts'
import { aoe4GameReplay } from '../src/sim/visionReplay.ts'
import { engineFor, settings } from './helpers.ts'

const aoe4 = aoe4Json as unknown as GameProfile
const roster = aoe4.knowledge.roster!
const spec = aoe4.intel!
const fv = (value: string) => ({ value, confidence: 0.9, observedAt: 0, source: 'vision-model' as const, conflicted: false })
const state = (values: Record<string, string>): VisibleState => ({ timestampMs: 0, phase: 'round', stale: false, staleReasons: [], activeSignals: [], values: Object.fromEntries(Object.entries(values).map(([k, v]) => [k, fv(v)])) }) as unknown as VisibleState
const AGES = ['dark', 'feudal', 'castle', 'imperial']

test('every civilisation has plans for each age, Dark to Imperial', () => {
  assert.equal(roster.factions.length, 25)
  for (const f of roster.factions) {
    assert.deepEqual((f.agePlans ?? []).map((p) => p.age), [1, 2, 3, 4], f.id)
    for (const p of f.agePlans!) {
      assert.ok(p.theirMoves.length >= 1, `${f.id} age ${p.age}: their moves`)
      assert.ok(p.playAs.length >= 1, `${f.id} age ${p.age}: how to play it`)
      for (const m of p.theirMoves) assert.ok(m.response.length >= 1 && m.threat, `${f.id} ${m.id}`)
    }
  }
})

test('per-civilisation unit ages: HRE Men-at-Arms in Feudal, French ones from Castle', () => {
  const hre = new IntelTracker(roster, spec, 'English')
  const said = hre.update(state({ age: 'feudal', opponentAge: 'feudal', opponentCiv: 'Holy Roman Empire', enemyBuildings: 'Barracks' }), 1000)
  assert.ok(said.some((n) => n.kind === 'building' && /expect [^.]*Men-at-Arms/.test(n.text)), JSON.stringify(said))
  const fr = new IntelTracker(roster, spec, 'English')
  const said2 = fr.update(state({ age: 'feudal', opponentAge: 'feudal', opponentCiv: 'French', enemyBuildings: 'Barracks' }), 1000)
  assert.ok(!said2.some((n) => n.kind === 'building' && /expect [^.]*Men-at-Arms/.test(n.text)), JSON.stringify(said2))
  // Seeing Rus Knights in Feudal does not make the coach think they are in Castle.
  const rus = new IntelTracker(roster, spec, 'English')
  rus.update(state({ age: 'feudal', opponentCiv: 'Rus', enemyUnits: '4 Knights' }), 1000)
  assert.equal(rus.report().opponentAge?.age, 2)
})

test('generic sightings never become a read, for any civilisation at any age', () => {
  for (const f of roster.factions) {
    for (let age = 1; age <= 4; age++) {
      const t = new IntelTracker(roster, spec, 'English')
      const news = [
        ...t.update(state({ age: AGES[age - 1], opponentAge: AGES[age - 1], opponentCiv: f.name }), 1000),
        ...t.update(state({ enemyBuildings: 'Town Center, Barracks, Archery Range, Stable, Blacksmith, House', enemyUnits: '12 Villagers, 1 Scout, 6 Spearmen, 4 Archers' }), 2000),
      ]
      assert.ok(!news.some((n) => n.kind === 'read'), `${f.id} age ${age}: ${news.filter((n) => n.kind === 'read').map((n) => n.text).join(' | ')}`)
    }
  }
})

test('English in Feudal: a Council Hall and an Archery Range are read as Longbow pressure, said once and fit to speak', () => {
  const t = new IntelTracker(roster, spec, 'French')
  const first = t.update(state({ age: 'feudal', opponentAge: 'feudal', opponentCiv: 'English', enemyBuildings: 'Town Center, Barracks' }), 1000)
  assert.equal(t.report().reads.length, 0)
  assert.ok(first.some((n) => n.kind === 'plan' && /^Feudal Age as French: [A-Z]/.test(n.text)), JSON.stringify(first))
  const said = t.update(state({ enemyBuildings: 'Council Hall, Archery Range', enemyUnits: '6 Longbowmen' }), 2000)
  const read = said.find((n) => n.kind === 'read')
  assert.ok(read, JSON.stringify(said))
  assert.equal(read.id, 'longbow-pressure')
  assert.match(read.text, /^Read: .+ Seen: Council Hall, Longbowman, Archery Range, Barracks\. Timing: /)
  assert.doesNotMatch(read.text, /unverified|~|\+|\d-\d/)
  assert.ok(!t.update(state({ enemyUnits: '8 Longbowmen' }), 3000).some((n) => n.kind === 'read'), 'said once')
})

test('every plan with a landmark is read from that landmark alone (or tied with another plan that has it)', () => {
  for (const f of roster.factions) {
    for (const p of f.agePlans ?? []) {
      for (const m of p.theirMoves) {
        const l = m.signals.landmarks?.[0]
        if (!l) continue
        const t = new IntelTracker(roster, spec, 'English')
        t.update(state({ opponentAge: AGES[p.age - 1], opponentCiv: f.name, enemyBuildings: l }), 1000)
        const reads = t.report().reads
        assert.ok(reads.some((r) => r.id === m.id && r.score >= 3), `${f.id} ${m.id} from ${l}: ${JSON.stringify(reads.map((r) => [r.id, r.score]))}`)
      }
    }
  }
})

test('the AoE IV demo, played as French: the read and the player\'s plan for each age are spoken, around the other commentary', () => {
  const engine = engineFor(aoe4, settings({ profileId: 'aoe4', profileModeId: 'skirmish-vs-ai', mode: 'training', commentary: true }), { context: { civ: 'French' } })
  const spoken: Utterance[] = []
  let now = 0
  for (const b of aoe4GameReplay({ seed: 4 }).batches) spoken.push(...engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt))).utterances)
  const lines = spoken.filter((u) => u.category === 'commentary').map((u) => u.text)
  assert.ok(lines.some((t) => /^Read: Longbow pressure.* Seen: Council Hall/.test(t)), lines.join(' | '))
  assert.ok(lines.some((t) => /^Dark Age as French: /.test(t)), lines.join(' | '))
  assert.ok(lines.some((t) => /^Feudal Age as French: /.test(t)), lines.join(' | '))
  assert.ok(lines.some((t) => /^They have a Barracks: expect Spearmen or Men-at-Arms\./.test(t)), 'English get Men-at-Arms early')
  assert.ok(lines.every((t) => !/\(unverified\)|~/.test(t)), lines.join(' | '))
})
