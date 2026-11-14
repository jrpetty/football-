// The Chernarus survival assistant: your spot, where items spawn, your kit,
// what to do next, take me to, and routes that keep out of gas zones.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { assistantAnswer, assistantRoute, kitFrom, parseKitUpdate, parseSetSpot, takeMeTo, whatNow, CHERNARUS } from '../src/core/dayz/assistant.ts'
import { CHERNARUS_ITEMS, findItem, itemAnswer, kindAnswer, placesFor, tierAt, buildingAnswer } from '../src/core/dayz/items.ts'
import { decodeTerrain, gasMask, groundPath } from '../src/core/dayz/terrain.ts'
import { classify, answerLocally, lookupKind } from '../src/core/qa/ask.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { engineFor, policyFor, settings } from './helpers.ts'
import cher from '../src/profiles/dayz/terrain.chernarus.json' with { type: 'json' }

const dayz = builtInProfile('dayz')
const turovo = parseSetSpot("Coach, I'm at Turovo")!
const ctx = { spotX: turovo.x, spotY: turovo.y, spotLabel: turovo.label, kit: '' }

test('your spot: a town in any spelling or a grid, said the way players say it', () => {
  assert.deepEqual(turovo, { x: 13550, y: 14110, label: 'Turovo' })
  assert.equal(parseSetSpot('im at typobo')?.label, 'Turovo')
  assert.deepEqual(parseSetSpot('I am near grid 135 012'), { x: 13550, y: 14110, label: 'grid 135 012' })
  assert.equal(parseSetSpot('set my spot to Berezino')?.label, 'Berezino')
  assert.equal(parseSetSpot("I'm fine"), null)
  assert.equal(parseSetSpot('where is typobo'), null)
})

test('the loot table: hundreds of items grouped, readable names, tiers across the map', () => {
  assert.ok(CHERNARUS_ITEMS.length > 500)
  assert.equal(findItem('where do I find a canteen')?.name, 'Canteen')
  assert.equal(findItem('need bandages')?.name, 'Bandage')
  assert.equal(findItem('where can I find 7.62x39 ammo')?.name, '7.62x39mm rounds, box of 20')
  assert.equal(findItem('a map please')?.id, 'ChernarusMap')
  assert.equal(tierAt(1700, 14000), 4, 'Tisy: tier 4')
  assert.equal(tierAt(4600, 10300), 3, 'NWAF: tier 3')
  assert.equal(tierAt(10300, 2200), 1, 'Elektro: tier 1')
  // A tiered item only points to places in its tiers.
  const taloon = findItem('taloon backpack')!
  assert.ok(placesFor(taloon, turovo).every((p) => taloon.tiers.includes(p.tier)))
  const a = itemAnswer(findItem('canteen')!, turovo)
  assert.match(a, /^Canteen: spawns in hunting stands and lodges and military buildings, about \d+ on Chernarus at a time\. Nearest from Turovo: /)
  assert.match(kindAnswer('backpack', null), /^Backpacks on Chernarus, most common first: /)
  assert.match(buildingAnswer('Police'), /^Most common in police stations on Chernarus: /)
  for (const t of [a, kindAnswer('backpack', turovo)]) assert.doesNotMatch(t, BANNED_CLAIMS)
})

test('the kit: "I found a knife and rags" ticks, "I lost my backpack" unticks', () => {
  assert.deepEqual(parseKitUpdate('I found a knife and some rags'), { have: ['knife', 'bandages'], lost: [] })
  assert.deepEqual(parseKitUpdate('I lost my backpack'), { have: [], lost: ['backpack'] })
  assert.deepEqual(parseKitUpdate('got a mosin'), { have: ['gun'], lost: [] })
  assert.equal(parseKitUpdate('where is a knife'), null)
})

test('what now: water first, then houses, medical, a weapon; less as the kit fills', () => {
  const fresh = whatNow(turovo, new Set())
  assert.deepEqual(fresh.steps.map((s) => s.kind), ['water', 'houses', 'medical', 'police', 'military'])
  assert.equal(fresh.route.length, fresh.steps.length + 1, 'drawn from your spot through each step')
  assert.match(fresh.text, /^From Turovo: 1\. Drink at the water pump/)
  const armedNeedsAmmo = whatNow(turovo, kitFrom('water,knife,fire,clothes,backpack,food,map,bandages,medicine,gun'))
  assert.deepEqual(armedNeedsAmmo.steps.map((s) => s.kind), ['military'])
  const all = whatNow(turovo, kitFrom('water,knife,fire,clothes,backpack,food,map,bandages,medicine,gun,ammo'))
  assert.equal(all.steps.length, 0)
  assert.match(all.text, /you have the essentials/)
  assert.doesNotMatch(fresh.text, BANNED_CLAIMS)
})

test('take me to: the nearest of a kind, a town, or a place where an item spawns; nothing without a spot', () => {
  const h = takeMeTo('take me to the nearest hospital', turovo)!
  assert.match(h.text, /^Hospital in Novodmitrovsk, 2\.\d km west from Turovo: about \d+ min jogging, \d+ min by car\.$/)
  assert.equal(h.route.length, 2)
  assert.match(takeMeTo('take me to Berezino', turovo)!.text, /^Berezino: 4\.\d km south from Turovo, about \d+ min jogging, \d+ min by car\.$/)
  assert.match(takeMeTo('take me to a place with morphine', turovo)!.text, /^Morphine: spawns in medical buildings/)
  assert.match(assistantAnswer('take me to the nearest water', {})!, /^Where are you\?/)
  assert.match(assistantAnswer('what now?', {})!, /^Tell me where you are first/)
  assert.equal(assistantRoute('where do I find a gas mask', turovo, new Set())!.route.length, 2)
})

test('through the coach: the assistant answers on this computer, as look-ups allowed online', () => {
  const report = engineFor(dayz, settings({ profileId: 'dayz', profileModeId: 'official' })).report()
  const withSpot = { ...report, context: { ...report.context, ...ctx } }
  for (const [q, re, kind] of [
    ['what now?', /^From Turovo: 1\. /, 'places'],
    ['take me to the nearest police', /^Police station/, 'places'],
    ['where do I find a compass', /^Compass: spawns in/, 'items'],
    ['what spawns in police stations', /^Most common in police stations/, 'items'],
  ] as const) {
    const intent = classify(q, dayz)
    assert.equal(intent.kind, 'assistant', q)
    assert.match(answerLocally(intent, dayz, withSpot, [])!, re)
    assert.equal(lookupKind(intent, dayz, withSpot.context), kind, q)
  }
  assert.equal(classify('where is typobo', dayz).kind, 'place')
  assert.equal(classify('where can I find an M4', dayz).kind, 'gun')
  assert.ok(policyFor(dayz, 'official').lookups.has('items'))
})

test('routes keep out of gas zones: round them on foot, never through by car', () => {
  const t = decodeTerrain(cher)
  const zone = CHERNARUS.world.contaminated.reduce((a, b) => (b.r > a.r ? b : a))
  const mask = gasMask(t, [zone])
  // Across the zone, from one side to the other.
  const a = { x: zone.x - zone.r - 400, y: zone.y }
  const b = { x: zone.x + zone.r + 400, y: zone.y }
  const plain = groundPath(t, a, b, 'foot')!
  const safe = groundPath(t, a, b, 'foot', mask)!
  const inside = (p: { x: number; y: number }) => Math.hypot(p.x - zone.x, p.y - zone.y) < zone.r - 30
  assert.ok(plain.points.length >= 2)
  assert.ok(!safe.points.some(inside), 'the safe path does not cross the zone')
  assert.ok(safe.lengthM >= plain.lengthM)
})
