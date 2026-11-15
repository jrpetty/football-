// Crafting, car repair and base building: from Bohemia's scripts (recipes the
// game registers, what each vehicle needs) and the base-building reference.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { BUILDS, buildAnswer, buildTopic, craftAnswer, RECIPES, recipesFor, recipesUsing, VEHICLES, vehicleAnswer, vehicleIn } from '../src/core/dayz/crafting.ts'
import { localReply, runTool, COMPANION_TOOLS, type CompanionContext } from '../src/core/dayz/companion.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import { builtInProfile } from '../src/profiles/index.ts'

const dayz = builtInProfile('dayz')
const ctx: CompanionContext = { spot: null, kit: [], map: 'chernarus' }

test('the data: registered recipes, every vehicle, the base parts', () => {
  assert.ok(RECIPES.length >= 120)
  assert.ok(VEHICLES.some((v) => v.name === 'Olga 24') && VEHICLES.some((v) => v.name === 'M3S'))
  for (const id of ['fence', 'watchtower', 'flagpole']) assert.ok(BUILDS.some((b) => b.id.startsWith(id)), id)
})

test('recipes: what to make, best match first; what an item makes', () => {
  assert.equal(recipesFor('how do I make a splint')[0].name, 'Splint')
  assert.deepEqual(recipesFor('how do I make rope').map((r) => r.name).sort(), ['Rope (from guts)', 'Rope (from rags)'])
  assert.match(craftAnswer('how do I make rope')!, /^Rope \(from rags\): 6× Rags \+ 6× Rags → Rope\./)
  assert.match(craftAnswer('how do I make a stone knife')!, /^Stone knife: Small stone \+ Small stone or Large stone \(kept\) → Stone knife\./)
  assert.ok(recipesUsing('what can I make with rags').some((r) => r.name === 'Splint'))
  assert.match(craftAnswer('what can I make with rags')!, /^With that you can make: .*Splint/)
  assert.equal(craftAnswer('how do I make friends'), null)
})

test('vehicles: what each needs to start, by the names players use', () => {
  assert.equal(vehicleIn('my humvee')?.name, 'M1025 Humvee')
  assert.match(vehicleAnswer('what does the olga need')!, /^Olga 24: to start it needs Car Battery, Spark Plug, Radiator, fuel\./)
  assert.match(vehicleAnswer('what parts does the M3S need')!, /Truck Battery/)
  assert.match(vehicleAnswer('how do I fix a car')!, /^Every car needs a charged battery/)
  assert.equal(vehicleAnswer('where do I find a car battery'), null)
})

test('base building: stages, locks, raiding, territory, tips', () => {
  assert.equal(buildTopic('what do I need to build a fence'), 'build')
  assert.equal(buildTopic('how does raiding work'), 'raiding')
  assert.equal(buildTopic('how do code locks work'), 'locks')
  assert.equal(buildTopic('how does the flag pole work'), 'territory')
  assert.equal(buildTopic('where is Berezino'), null)
  assert.match(buildAnswer('what do I need to build a fence')!, /^Fence: kit rope \+ 2 short sticks.*Base: 2 wooden logs/i)
  assert.match(buildAnswer('how does raiding work')!, /^Raiding: Dismantling .* only from the inside/)
  for (const q of ['what do I need to build a fence', 'how does raiding work', 'how do code locks work', 'how does the flag pole work', 'base building tips']) assert.doesNotMatch(buildAnswer(q)!, BANNED_CLAIMS, q)
})

test('the companion: cards for recipes, vehicles and base parts; tools for Claude', () => {
  assert.equal(localReply('how do I make a splint', ctx, dayz, null, null)?.card?.type, 'recipe')
  assert.equal(localReply('what does the Olga need to run?', ctx, dayz, null, null)?.card?.type, 'vehicle')
  assert.equal(localReply('what do I need to build a watchtower', ctx, dayz, null, null)?.card?.type, 'build')
  assert.equal(localReply('how does raiding work?', ctx, dayz, null, null)?.card?.type, 'guide')
  for (const n of ['craft', 'vehicle_parts', 'base_building']) assert.ok(COMPANION_TOOLS.some((t) => t.name === n), n)
  const effects = { setSpot: () => {}, setKit: () => {} }
  assert.equal(runTool('craft', { item: 'a fireplace' }, ctx, dayz, effects).card?.type, 'recipe')
  assert.match(runTool('craft', { item: 'with rags' }, ctx, dayz, effects).text, /^With that you can make/)
  assert.equal(runTool('vehicle_parts', { vehicle: 'Sarka' }, ctx, dayz, effects).card?.type, 'vehicle')
  assert.equal(runTool('base_building', { question: 'how do code locks work' }, ctx, dayz, effects).card?.type, 'guide')
})
