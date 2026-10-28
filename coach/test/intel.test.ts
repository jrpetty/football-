// Scouting intel: names read from the screen, what a scouted building can make
// for the opponent's civilisation, and what beats it for the player's own.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { IntelTracker, parseNames, plural } from '../src/core/analyses/intel.ts'
import type { Roster } from '../src/core/profile/schema.ts'
import type { VisibleState } from '../src/core/types.ts'

const roster: Roster = {
  checkedAt: '2026-10-28',
  patch: 'test',
  factions: [
    { id: 'english', name: 'English', status: 'released', bonuses: [], uniqueUnits: ['longbowman'], replaces: { archer: 'longbowman' }, howToPlayAgainst: 'Raid with horsemen.' },
    { id: 'mongols', name: 'Mongols', status: 'released', bonuses: [], uniqueUnits: ['mangudai'], lacks: ['spearman'] },
    { id: 'scots', name: 'Scots', status: 'upcoming', bonuses: [], uniqueUnits: [] },
  ],
  units: [
    { id: 'villager', name: 'Villager', classes: ['economic'], building: 'town-center', counters: [], counteredBy: ['horseman'] },
    { id: 'spearman', name: 'Spearman', aliases: ['spear'], classes: ['light', 'melee', 'infantry'], building: 'barracks', counters: ['horseman', 'knight'], counteredBy: ['archer', 'man-at-arms'] },
    { id: 'man-at-arms', name: 'Man-at-Arms', aliases: ['MAA'], classes: ['heavy', 'melee', 'infantry'], building: 'barracks', counters: ['spearman'], counteredBy: ['crossbowman'] },
    { id: 'archer', name: 'Archer', classes: ['light', 'ranged', 'infantry'], building: 'archery-range', counters: ['spearman'], counteredBy: ['horseman', 'man-at-arms'] },
    { id: 'crossbowman', name: 'Crossbowman', classes: ['light', 'ranged', 'infantry'], building: 'archery-range', counters: ['heavy'], counteredBy: ['horseman'] },
    { id: 'horseman', name: 'Horseman', classes: ['light', 'melee', 'cavalry'], building: 'stable', counters: ['archer'], counteredBy: ['spearman'] },
    { id: 'knight', name: 'Knight', classes: ['heavy', 'melee', 'cavalry'], building: 'stable', counters: ['archer'], counteredBy: ['spearman', 'crossbowman'], why: 'Spearmen brace against the charge.' },
    { id: 'horse-archer', name: 'Horse Archer', classes: ['light', 'ranged', 'cavalry'], building: 'stable', counters: ['spearman'], counteredBy: ['archer'] },
    { id: 'longbowman', name: 'Longbowman', classes: ['light', 'ranged', 'infantry'], building: 'archery-range', counters: ['spearman'], counteredBy: ['horseman'], factions: ['english'] },
    { id: 'mangudai', name: 'Mangudai', classes: ['light', 'ranged', 'cavalry'], building: 'stable', counters: ['infantry'], counteredBy: ['archer'], factions: ['mongols'] },
  ],
  buildings: [
    { id: 'town-center', name: 'Town Center', produces: ['villager'] },
    { id: 'barracks', name: 'Barracks', produces: ['spearman', 'man-at-arms'] },
    { id: 'archery-range', name: 'Archery Range', produces: ['archer', 'crossbowman'] },
    { id: 'stable', name: 'Stable', produces: ['horseman', 'knight', 'horse-archer'] },
  ],
}

const spec = { factionKey: 'opponentCiv', buildingsKey: 'enemyBuildings', unitsKey: 'enemyUnits' }
const fv = (value: string) => ({ value, confidence: 0.9, observedAt: 0, source: 'vision-model' as const, conflicted: false })
const state = (values: Record<string, string>): VisibleState => ({ timestampMs: 0, phase: 'round', stale: false, staleReasons: [], activeSignals: [], values: Object.fromEntries(Object.entries(values).map(([k, v]) => [k, fv(v)])) }) as unknown as VisibleState

test('intel: names with counts, plurals and aliases; longer names are not read twice', () => {
  const t = new IntelTracker(roster, spec, null)
  const units = (t as unknown as { unitMatchers: Parameters<typeof parseNames>[1] }).unitMatchers
  const found = parseNames('12 Spearmen, Men-at-Arms x4, 3 horse archers, Archer (2), MAA', units)
  assert.deepEqual(Object.fromEntries(found), { spearman: 12, 'man-at-arms': 5, 'horse-archer': 3, archer: 2 })
  assert.equal(plural('Man-at-Arms'), 'Men-at-Arms')
  assert.equal(plural('Spearman'), 'Spearmen')
  assert.equal(plural('Knight'), 'Knights')
})

test('intel: a scouted building means the units it can make for that civilisation (unique replacements, missing units)', () => {
  const t = new IntelTracker(roster, spec, null)
  const english = t.findFaction('english')!
  const mongols = t.findFaction('MONGOLS')!
  assert.ok(t.producible('archery-range', english).includes('longbowman'))
  assert.ok(!t.producible('archery-range', english).includes('archer'), 'Longbowmen replace Archers')
  assert.ok(t.producible('stable', mongols).includes('mangudai'))
  assert.ok(!t.producible('barracks', mongols).includes('spearman'), 'Mongols lack Spearmen here')
  assert.ok(!t.producible('stable', null).includes('mangudai'), 'unique units need the civilisation')
})

test('intel: news lines once each, counters for the player, and the report', () => {
  const t = new IntelTracker(roster, spec, 'english')
  const news1 = t.update(state({ opponentCiv: 'Mongols', enemyBuildings: '2 Stables, Barracks' }), 1000)
  assert.deepEqual(news1.map((n) => n.kind).sort(), ['building', 'building', 'faction'])
  assert.equal(news1.find((n) => n.kind === 'faction')!.text, 'Opponent: Mongols. Watch for Mangudai.')
  assert.match(news1.find((n) => n.id === 'stable')!.text, /^They have a Stable: expect Horsemen, Knights or Horse Archers\. .*Spearmen.* answer them\.$/)
  assert.equal(news1.find((n) => n.id === 'barracks')!.text, 'They have a Barracks: expect Men-at-Arms. Crossbowmen answer them.', 'Mongols have no Spearmen here')
  const news2 = t.update(state({ opponentCiv: 'Mongols', enemyBuildings: 'Stable', enemyUnits: '8 Knights, 1 Mangudai' }), 5000)
  assert.deepEqual(news2.map((n) => n.text), ["They're making Knights. Spearmen and Crossbowmen beat them."])
  assert.equal(t.update(state({ enemyUnits: '9 Knights' }), 9000).length, 0, 'said once')
  const r = t.report()
  assert.equal(r.faction?.name, 'Mongols')
  assert.deepEqual(r.buildings.map((b) => [b.name, b.count]), [['Stable', 2], ['Barracks', 1]])
  assert.deepEqual(r.units[0], { id: 'knight', name: 'Knight', count: 9, firstAt: 5000 })
  assert.equal(r.counters[0].against, 'Knight')
  assert.deepEqual(r.counters[0].build, ['Spearman', 'Crossbowman'])
  assert.equal(r.counters[0].why, 'Spearmen brace against the charge.')
  assert.ok(r.likely.some((l) => l.name === 'Horseman' && l.from === 'Stable'))
})

test('intel: the player civilisation shapes the answer (unique replacements); upcoming civs are flagged', () => {
  const t = new IntelTracker(roster, spec, 'English')
  assert.deepEqual(t.answersTo('horseman'), ['spearman'])
  assert.deepEqual(t.answersTo('spearman'), ['longbowman', 'man-at-arms'], 'English answer Spearmen with Longbowmen, not Archers')
  t.update(state({ opponentCiv: 'Scots' }), 0)
  assert.equal(t.report().faction?.status, 'upcoming')
  t.newMatch()
  assert.equal(t.report().faction?.name, 'Scots', 'the last match stays visible until new intel arrives')
})
