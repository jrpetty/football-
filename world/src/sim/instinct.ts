// Instinct: a utility-based fallback brain. It keeps people alive and busy
// whenever a Claude mind isn't steering them — no API key, between thoughts,
// over budget, or at hyper speed. It's sensible but unimaginative: the AI
// minds are what give the world personality, culture and ambition.

import { DEPOSITS, FOOD_ITEMS, invCount, invTotal, type Item } from './data.ts'
import { BUILDINGS, RECIPES, type BuildingType } from './buildings.ts'
import { oreStep } from './actions.ts'
import { enemies, met } from './diplomacy.ts'
import { availableTechs, eraOf } from './tech.ts'
import { isNight, hourOf } from './time.ts'
import type { Agent, PlanStep } from './types.ts'
import type { World } from './world.ts'
import { CARRY_CAP } from './world.ts'

const step = (action: PlanStep['action'], target = '', amount = 0, text = ''): PlanStep => ({ action, target, amount, text })

export function instinctStep(w: World, a: Agent): PlanStep {
  const t = w.tribeById(a.tribeId)
  const known = w.techs(a.tribeId)
  const r = Math.random()

  // --- body first
  if (a.thirst > 55) return step('drink')
  if (a.hunger > 55) return step('eat')
  if ((isNight(w.time) && a.energy < 60) || a.energy < 25) return step('sleep')
  if (a.sick > 0.5 && (invCount(a.inv, 'medicine') > 0 || (t && invCount(t.storage, 'medicine') > 0))) return step('heal', 'me')
  if (isNight(w.time) && hourOf(w.time) > 21) return step('sleep')

  // --- empty the pack when it's heavy
  if (invTotal(a.inv) > CARRY_CAP * 0.7 && w.storageUsed(t ?? ({ storage: {} } as never)) < w.storageCap(a.tribeId) - 5) return step('store')
  if (!t) return step('gather', 'berries', 8)

  const stock = (i: Item) => invCount(t.storage, i) + invCount(a.inv, i)
  let food = 0
  for (const f of FOOD_ITEMS) food += stock(f)
  const members = w.members(t.id).length
  const has = (b: BuildingType, includeSites = true) => w.buildings.some((x) => x.tribeId === t.id && x.type === b && (includeSites || x.done))
  const count = (b: BuildingType) => w.buildings.filter((x) => x.tribeId === t.id && x.type === b).length
  const can = (b: BuildingType) => known.has(BUILDINGS[b].tech ?? '') || !BUILDINGS[b].tech
  const afford = (b: BuildingType) => w.missing(a, BUILDINGS[b].cost).length === 0
  const site = w.buildings.find((x) => x.tribeId === t.id && !x.done && x.type !== 'road')
  const housing = w.tribeBuildings(t.id).reduce((s, b) => s + (BUILDINGS[b.type].housing ?? 0), 0)

  // help finish what's been started
  if (site && r < 0.55) return step('build', site.type)

  // --- the communal essentials, in rough order of need
  if (food < members * 4 && r < 0.75) return foodStep(w, a)
  // families: unpartnered adults of an age look for a partner among people they like
  const age = w.age(a)
  if (!a.partnerId && age >= 16 && age <= 48 && Math.random() < 0.12) {
    const cands = w.living.filter((o) => o !== a && !o.partnerId && w.isAdult(o) && Math.abs(w.age(o) - age) < 15 && Math.hypot(o.x - a.x, o.y - a.y) < 40)
    cands.sort((p, q) => (a.relations[q.id]?.affinity ?? 0) - (a.relations[p.id]?.affinity ?? 0))
    const c = cands[0]
    if (c) {
      const rel = w.relation(a, c)
      if (rel.affinity > 28 && rel.familiarity > 20) return step('court', c.name)
      return step('talk', c.name)
    }
  }
  if (can('campfire') && !has('campfire')) return afford('campfire') ? step('build', 'campfire') : step('gather', 'wood', 8)
  const stores = count('stockpile') + count('granary') + count('warehouse')
  if (w.storageUsed(t) > w.storageCap(t.id) * 0.8 && !site && stores < 2 + members / 5) {
    const store: BuildingType = can('warehouse') && afford('warehouse') ? 'warehouse' : can('granary') && afford('granary') ? 'granary' : 'stockpile'
    if (afford(store)) return step('build', store)
  }
  if (!a.homeId && housing < members) {
    const home: BuildingType = can('house') && afford('house') ? 'house' : can('hut') ? 'hut' : 'lean_to'
    if (afford(home)) return step('build', home)
    const miss = w.missing(a, BUILDINGS[home].cost)
    return gatherFor(w, a, miss)
  }
  if (a.toolTier === 0 && known.has('stone_tools')) {
    const rec = RECIPES.find((x) => x.id === 'stone_tools')!
    if (w.missing(a, rec.inputs).length === 0) return step('craft', 'stone_tools')
    return gatherFor(w, a, w.missing(a, rec.inputs))
  }
  if (a.clothing < 25 && w.season >= 2 && known.has('clothing') && stock('hide') >= 2) return step('craft', 'clothes')

  // research is how a people advance; the curious and clever do more of it
  // foragers rarely sit and study; settled, literate societies do so far more
  const era = eraOf(known)
  const researchWeight = Math.min(0.45, (0.04 + a.traits.intelligence * 0.1 + a.traits.openness * 0.06) * (1 + era * 0.35) * (known.has('writing') ? 1.3 : 1))
  if (availableTechs(known).length && r < researchWeight) return step('research')

  // build the next useful thing the tribe lacks
  // threatened peoples fortify and train
  const threat = enemies(w, t.id).length > 0 || w.tribes.some((o) => o.alive && o.id !== t.id && met(w, t.id, o.id) && w.tribeRelation(t.id, o.id) < -35)
  const wishlist: BuildingType[] = [
    ...(threat ? (['barracks', 'walls'] as BuildingType[]) : []),
    'stockpile', 'farm', 'workshop', 'kiln', 'granary', 'well', 'farm', 'pasture', 'smithy', 'library', 'temple',
    'market', 'town_hall', 'mill', 'dock', 'farm', 'house', 'university', 'hospital', 'watchtower', 'factory',
    known.has('nuclear_power') ? 'nuclear_plant' : known.has('solar_power') ? 'solar_farm' : known.has('wind_power') ? 'wind_farm' : 'power_plant',
    'refinery', 'harbour', 'radio_tower', 'lab', 'apartment', 'airport', 'fish_farm', 'data_center', 'skyscraper', 'launch_pad',
  ]
  // prospectors go looking when a people knows mining but has found little underground
  const fed = food > members * 12
  if (fed && known.has('mining') && Math.random() < (known.has('geology') ? 0.03 : 0.015) + a.traits.openness * 0.02) {
    // what this people's knowledge makes them need, but haven't found
    const need: Item[] = ['copper', 'tin', 'iron_ore', 'coal']
    if (known.has('oil_drilling')) need.push('oil')
    if (known.has('aluminium_smelting')) need.push('bauxite')
    if (known.has('nuclear_power')) need.push('uranium')
    if (known.has('lithium_batteries')) need.push('lithium')
    const have = new Set<string>()
    for (const i of w.knownList(t.id)) have.add(DEPOSITS[w.deposit[i]]?.item ?? '')
    const missing = need.filter((x) => !have.has(x))
    if (missing.length) return step('prospect', missing[Math.floor(Math.random() * missing.length)], 3)
    if (w.knownList(t.id).length < 30 + era * 15) return step('prospect', '', 3)
  }
  // a known body nobody is working yet: sink a mine, drill a well
  if (fed && known.has('mining') && Math.random() < 0.07) {
    for (const item of ['copper', 'tin', 'iron_ore', 'coal', 'gold', 'oil', 'bauxite', 'uranium', 'lithium'] as Item[]) {
      if (stock(item) > 25) continue
      const o = oreStep(w, a, item)
      if (o?.action === 'build') {
        const bt = o.target as BuildingType
        if (afford(bt)) return step('build', bt)
        return gatherFor(w, a, w.missing(a, BUILDINGS[bt].cost))
      }
    }
  }
  const wants: BuildingType[] = []
  for (const b of wishlist) {
    if (!can(b) || wants.includes(b)) continue
    const n = count(b)
    const want = b === 'farm' ? Math.ceil(members / 2.5) : b === 'house' ? Math.ceil(members / 4) : b === 'pasture' ? Math.ceil(members / 8) : b === 'apartment' ? Math.floor(members / 20) : b === 'library' || b === 'workshop' ? Math.ceil(members / 15) : 1
    if (n < want) wants.push(b)
  }
  if (wants.length && Math.random() < 0.7) {
    const ready = wants.find((b) => afford(b))
    if (ready) return step('build', ready)
    // work toward the first two wishes
    const goal = wants[Math.random() < 0.6 ? 0 : Math.min(1, wants.length - 1)]
    return gatherFor(w, a, w.missing(a, BUILDINGS[goal].cost))
  }

  // a fishing fleet goes out whenever it can
  if (known.has('fishing') && w.tribeBuildings(t.id).some((b) => b.type === 'dock' || b.type === 'harbour') && Math.random() < 0.12) return step('fish', '', 14)
  // farm fields need hands
  if (has('farm', false) && w.season <= 2 && food < members * 40 && Math.random() < 0.35) return step('farm', '', 3)

  // craft upgrades
  if (a.toolTier < 2 && known.has('bronze_working') && stock('bronze') >= 2) return step('craft', 'bronze_tools')
  if (a.toolTier < 3 && known.has('iron_working') && stock('iron') >= 2) return step('craft', 'iron_tools')
  const refine: [string, Item, number][] = [['planks', 'wood', 12], ['bricks', 'clay', 8], ['paper', 'fiber', 6], ['bronze', 'copper', 4], ['iron', 'iron_ore', 4], ['glass', 'sand', 4], ['concrete', 'stone', 10], ['steel', 'iron', 4], ['machinery', 'steel', 4], ['fuel', 'oil', 4], ['plastic', 'oil', 4], ['aluminium', 'bauxite', 8], ['batteries', 'lithium', 2], ['electronics', 'copper', 4], ['pottery', 'clay', 6]]
  for (const [rec, inp, min] of refine) {
    const re = RECIPES.find((x) => x.id === rec)!
    if (!re.tech || !known.has(re.tech)) continue
    if (stock(inp) < min || stock(re.out as Item) > 20) continue
    if (re.station && !w.nearestBuilding(a, re.station, true, 60)) continue
    if (w.missing(a, re.inputs).length === 0 && Math.random() < 0.4) return step('craft', rec, 2)
  }

  // socialise, explore, or stock up
  if (a.social < 35 && Math.random() < 0.6) {
    const friend = w.living.filter((o) => o !== a && w.isAdult(o) && Math.hypot(o.x - a.x, o.y - a.y) < 25).sort((p, q) => (a.relations[q.id]?.affinity ?? 0) - (a.relations[p.id]?.affinity ?? 0))[0]
    if (friend) {
      const rel = w.relation(a, friend)
      if (!a.partnerId && !friend.partnerId && rel.affinity > 35 && rel.familiarity > 30 && Math.random() < 0.3) return step('court', friend.name)
      return step('talk', friend.name)
    }
  }
  if (w.season !== 3 && Math.random() < 0.06 + a.traits.openness * 0.06) return step('explore', ['north', 'south', 'east', 'west'][Math.floor(Math.random() * 4)])
  if (Math.random() < 0.08 && a.mood < 50 && w.tribeBuildings(t.id).length > 2) return step('celebrate')
  const basics: Item[] = ['wood', 'stone', 'fiber', 'clay']
  if (known.has('mining')) basics.push('copper', 'tin', 'iron_ore', 'coal')
  if (known.has('glassmaking') || known.has('engineering')) basics.push('sand')
  if (known.has('oil_drilling')) basics.push('oil')
  if (known.has('aluminium_smelting')) basics.push('bauxite')
  if (known.has('nuclear_power')) basics.push('uranium')
  if (known.has('lithium_batteries')) basics.push('lithium')
  basics.sort((x, y) => stock(x) - stock(y))
  if (Math.random() < 0.4 && food < members * 25) return foodStep(w, a)
  const pick = basics[0]
  if (ORES.includes(pick)) return resolve(w, a, pick, 10, 0) ?? step('gather', 'stone', 10)
  return step('gather', pick, 10)
}

export function foodStep(w: World, a: Agent): PlanStep {
  const known = w.techs(a.tribeId)
  const t = w.tribeById(a.tribeId)
  const farms = t ? w.tribeBuildings(t.id, 'farm') : []
  const ripe = farms.some((f) => f.planted && f.crop >= 1) || (w.season <= 1 && farms.some((f) => !f.planted))
  if (ripe) return step('farm', '', 3)
  const r = Math.random()
  const fleet = t ? w.tribeBuildings(t.id).filter((b) => b.type === 'dock' || b.type === 'harbour').length : 0
  if (known.has('fishing') && r < (fleet ? 0.6 : 0.4)) return step('fish', '', 8 + fleet * 4)
  if (known.has('hunting') && r < 0.65 && w.animals.length) return step('hunt')
  return step('gather', 'berries', 10)
}

/**
 * Work backwards from what's missing: gather raw materials, craft
 * intermediates, or build the workshop a recipe needs — recursing through
 * production chains (ore → metal → machinery …) a few levels deep.
 */
export function gatherFor(w: World, a: Agent, missing: string[]): PlanStep {
  for (const m of missing) {
    const [nStr, item] = m.split(' ')
    const s = resolve(w, a, item as Item, Math.max(2, +nStr), 0)
    if (s) return s
  }
  return step('gather', 'wood', 10)
}

const RAW: Item[] = ['wood', 'stone', 'fiber', 'clay', 'sand', 'berries']
const ORES: Item[] = ['copper', 'tin', 'iron_ore', 'coal', 'gold', 'oil', 'bauxite', 'uranium', 'lithium']

export function resolve(w: World, a: Agent, item: Item, n: number, depth: number): PlanStep | null {
  const known = w.techs(a.tribeId)
  const room = Math.max(4, CARRY_CAP - invTotal(a.inv))
  if (RAW.includes(item)) return step('gather', item, Math.min(room, Math.max(4, n)))
  if (ORES.includes(item)) {
    const o = oreStep(w, a, item)
    if (!o) return null
    if (o.action === 'gather') return step('gather', item, Math.min(room, Math.max(4, n)))
    if (o.action === 'prospect') return step('prospect', o.target, 3)
    const bt = o.target as BuildingType
    if (w.buildings.some((b) => b.tribeId === a.tribeId && b.type === bt && !b.done)) return step('build', bt)
    const miss = w.missing(a, BUILDINGS[bt].cost)
    if (!miss.length) return step('build', bt)
    if (depth > 3) return null
    const [mn, mi] = miss[0].split(' ')
    return resolve(w, a, mi as Item, +mn, depth + 1)
  }
  if (item === 'hide' || item === 'meat') return step('hunt')
  if (item === 'fish') return step('fish', '', 6)
  if (item === 'grain') return step('farm', '', 3)
  if (depth > 3) return null
  const recipes = RECIPES.filter((r) => r.out === item && (!r.tech || known.has(r.tech)))
  for (const rec of recipes) {
    const miss = w.missing(a, rec.inputs)
    if (rec.station && !w.nearestBuilding(a, rec.station, true, 60)) {
      // we need the workshop first
      const st = rec.station.find((b) => !BUILDINGS[b].tech || known.has(BUILDINGS[b].tech!))
      if (!st) continue
      if (w.buildings.some((b) => b.tribeId === a.tribeId && b.type === st && !b.done)) return step('build', st)
      const stMiss = w.missing(a, BUILDINGS[st].cost)
      if (!stMiss.length) return step('build', st)
      const [sn, si] = stMiss[0].split(' ')
      return resolve(w, a, si as Item, +sn, depth + 1)
    }
    if (!miss.length) return step('craft', rec.id, Math.max(1, Math.min(4, Math.ceil(n / rec.outN))))
    const [mn, mi] = miss[0].split(' ')
    return resolve(w, a, mi as Item, +mn, depth + 1)
  }
  return null
}
