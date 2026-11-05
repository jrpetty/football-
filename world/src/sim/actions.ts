// How plan steps become physical work. Each action resolves its target in the
// world (nearest tree, a free building site, a person by name…), walks there
// with A*, then works over time. Failures are remembered by the agent so the
// mind can learn from them ("I couldn't find clay nearby — need to explore").

import { B, BIOMES, F, FEATURES, ITEMS, FOOD_ITEMS, TREE_FEATURES, DEPOSITS, invAdd, invCount, invTotal, ALL_ITEMS, type Item, type Inventory } from './data.ts'
import { BUILDINGS, BUILDING_TYPES, RECIPES, TOOL_MULT, TOOL_NAMES, type BuildingType, type Recipe } from './buildings.ts'
import { TECHS, TECH_BY_ID, availableTechs, techResearchBonus, techWorkBonus, geographyAffinity, techFx } from './tech.ts'
import { clamp } from './rng.ts'
import { HOUR, hourOf, isNight } from './time.ts'
import type { Agent, PlanStep, Task, SkillName, ActionKind } from './types.ts'
import type { World } from './world.ts'
import { CARRY_CAP } from './world.ts'
import { militaryTier, propose, type Proposal } from './diplomacy.ts'

type Result = 'continue' | 'done' | 'fail'

interface Handler {
  init(w: World, a: Agent, t: Task): boolean
  arrive?(w: World, a: Agent, t: Task): boolean
  work(w: World, a: Agent, t: Task, dtH: number): Result
}

// ------------------------------------------------------------------ public API

export function startTask(w: World, a: Agent, step: PlanStep, fromPlan: boolean): void {
  const action = normalizeAction(step.action)
  const t: Task = {
    action, target: String(step.target ?? ''), amount: Number(step.amount) || 0, text: String(step.text ?? ''),
    phase: 'init', tx: -1, ty: -1, path: null, pathIdx: 0, progress: 0, need: 1, ref: 0, label: '', fails: 0, started: w.time, fromPlan,
  }
  a.task = t
  const h = HANDLERS[action]
  let ok = false
  try {
    ok = h ? h.init(w, a, t) : false
  } catch {
    ok = false
  }
  if (!ok) {
    if (a.task === t) a.task = null
  } else if (t.phase === 'init') {
    t.phase = 'work'
  }
}

export function updateTask(w: World, a: Agent, dtH: number): void {
  const t = a.task
  if (!t) return
  const h = HANDLERS[t.action]
  if (!h) { a.task = null; return }
  if (t.phase === 'travel') {
    if (w.time - t.started > 22 * HOUR) {
      fail(w, a, `I gave up on ${t.label.toLowerCase() || t.action} — it was taking far too long to get there.`)
      return
    }
    const arrived = moveAlong(w, a, t, dtH)
    if (arrived === null) return // still walking
    if (!arrived) {
      if (t.fails++ < 2 && t.tx >= 0) {
        const p = w.path(a, t.tx, t.ty, true)
        if (p) { t.path = p; t.pathIdx = 0; return }
      }
      fail(w, a, `I couldn't find a way to reach my destination for ${t.label.toLowerCase() || t.action}.`)
      return
    }
    t.phase = 'work'
    a.moving = false
    if (h.arrive && !h.arrive(w, a, t)) {
      if (a.task === t) a.task = null
      return
    }
  }
  if (t.phase === 'work' && a.task === t) {
    a.moving = false
    const r = h.work(w, a, t, dtH)
    if (r !== 'continue' && a.task === t) a.task = null
  }
}

export function cancelTask(_w: World, a: Agent) {
  a.task = null
  a.moving = false
}

// ------------------------------------------------------------------ movement

function speedOf(w: World, a: Agent): number {
  let s = 10
  const age = w.age(a)
  if (age < 10 || age > 60) s *= 0.75
  if (a.energy < 15) s *= 0.65
  if (a.health < 40) s *= 0.7
  if (isNight(w.time)) s *= 0.85
  if (w.weather === 'storm' || w.weather === 'snow') s *= 0.85
  if (age >= 14) s *= techFx(w.techs(a.tribeId), 'travel')
  return s
}

/** Returns true on arrival, false if blocked, null if still walking. */
function moveAlong(w: World, a: Agent, t: Task, dtH: number): boolean | null {
  const p = t.path
  if (!p || t.pathIdx >= p.length) return true
  let budget = speedOf(w, a) * dtH
  a.moving = true
  while (budget > 0 && t.pathIdx < p.length) {
    const ti = p[t.pathIdx]
    const c = w.cost(ti, w.canSail(a.tribeId))
    if (c === Infinity && t.pathIdx < p.length - 1) return false
    const tx = (ti % w.W) + 0.5, ty = Math.floor(ti / w.W) + 0.5
    const dx = tx - a.x, dy = ty - a.y
    const d = Math.hypot(dx, dy)
    const step = budget / Math.max(0.2, c === Infinity ? 1 : c)
    if (dx !== 0) a.dir = dx < 0 ? -1 : 1
    if (d <= step) {
      a.x = tx; a.y = ty
      budget -= d * Math.max(0.2, c === Infinity ? 1 : c)
      t.pathIdx++
      if ((t.pathIdx & 1) === 0) w.revealAround(a.tribeId, a.x, a.y, 9)
    } else {
      a.x += (dx / d) * step
      a.y += (dy / d) * step
      budget = 0
    }
  }
  return t.pathIdx >= p.length ? true : null
}

function travel(w: World, a: Agent, t: Task, tx: number, ty: number, adjacent: boolean): boolean {
  t.tx = tx
  t.ty = ty
  const p = w.path(a, tx, ty, adjacent)
  if (!p) return false
  t.path = p
  t.pathIdx = 0
  t.phase = p.length ? 'travel' : 'work'
  return true
}

// ------------------------------------------------------------------ helpers

function fail(w: World, a: Agent, why: string) {
  w.remember(a, why, 4)
  a.task = null
  a.moving = false
  if (a.plan.length && /couldn't|can't|cannot|no |need|lack/i.test(why)) {
    // plans built on a failed premise usually need rethinking
    a.urgentThink = true
  }
}

function skillUp(a: Agent, s: SkillName, dtH: number) {
  a.skills[s] = clamp(a.skills[s] + dtH * 0.6 * (1 - a.skills[s] / 100), 0, 100)
}

function workMult(w: World, a: Agent, skill: SkillName, toolWeight = 1): number {
  const tool = 1 + (TOOL_MULT[a.toolTier] - 1) * toolWeight
  const sk = 0.65 + (a.skills[skill] / 100) * 0.8
  let m = tool * sk * techWorkBonus(w.techs(a.tribeId))
  if (w.hasPower(a.tribeId)) m *= 1.4
  if (a.energy < 20) m *= 0.7
  if (a.sick > 0) m *= 0.6
  return m
}

function wearTools(w: World, a: Agent, dtH: number) {
  if (a.toolTier <= 0) return
  a.toolWear += dtH * (3.2 - a.toolTier * 0.4)
  if (a.toolWear >= 100) {
    a.toolWear = 0
    a.toolTier = Math.max(0, a.toolTier - 1)
    w.remember(a, `My ${TOOL_NAMES[a.toolTier + 1]} wore out. I should craft new tools.`, 5)
  }
}

const norm = (s: string) => s.toLowerCase().trim().replace(/[\s-]+/g, '_').replace(/[^a-z_0-9]/g, '')

const ACTION_ALIASES: Record<string, ActionKind> = {
  collect: 'gather', chop: 'gather', mine: 'gather', survey: 'prospect', prospecting: 'prospect', negotiate: 'diplomacy', envoy: 'diplomacy', treaty: 'diplomacy', declare_war: 'diplomacy', dig: 'gather', drill: 'gather', harvest: 'farm', plant: 'farm', forage: 'gather', cut: 'gather',
  construct: 'build', make: 'craft', smelt: 'craft', forge: 'craft', study: 'research', invent: 'research',
  deposit: 'store', withdraw: 'take', say: 'talk', speak: 'talk', chat: 'talk', gift: 'give', propose: 'court',
  romance: 'court', woo: 'court', travel: 'explore', scout: 'explore', home: 'go_home', return_home: 'go_home',
  found: 'found_tribe', join: 'join_tribe', law: 'decree', announce: 'decree', pray: 'celebrate', feast: 'celebrate',
  ritual: 'celebrate', idle: 'rest', wait: 'rest', relax: 'rest', fight: 'attack', walk: 'wander', move: 'wander', move_to: 'wander',
  go_to: 'wander', cure: 'heal', tend: 'heal', record: 'write', exchange: 'trade', barter: 'trade',
}

export function normalizeAction(s: string): ActionKind {
  const n = norm(String(s ?? ''))
  if (n in HANDLERS) return n as ActionKind
  return ACTION_ALIASES[n] ?? 'rest'
}

const ITEM_ALIASES: Record<string, Item> = {
  log: 'wood', logs: 'wood', timber: 'wood', lumber: 'planks', tree: 'wood', trees: 'wood', branches: 'wood',
  rock: 'stone', rocks: 'stone', stones: 'stone', flint: 'stone', food: 'berries', berry: 'berries', fruit: 'berries',
  reeds: 'fiber', reed: 'fiber', flax: 'fiber', fibre: 'fiber', plant_fiber: 'fiber', herbs: 'fiber', thatch: 'fiber',
  ore: 'iron_ore', iron: 'iron_ore', ironore: 'iron_ore', copper_ore: 'copper', tin_ore: 'tin', gold_ore: 'gold',
  wheat: 'grain', crops: 'grain', meat: 'meat', game: 'meat', fish: 'fish', hides: 'hide', leather: 'hide', pelts: 'hide',
  brick: 'bricks', plank: 'planks', pot: 'pottery', pots: 'pottery', petroleum: 'oil', crude_oil: 'oil', coal_ore: 'coal',
}

export function normalizeItem(s: string, forGather = false): Item | null {
  let n = norm(s)
  if (!n) return null
  if (forGather && n === 'iron') return 'iron_ore'
  if ((ALL_ITEMS as string[]).includes(n)) return n as Item
  if (ITEM_ALIASES[n]) return ITEM_ALIASES[n]
  if (n.endsWith('s') && (ALL_ITEMS as string[]).includes(n.slice(0, -1))) return n.slice(0, -1) as Item
  n = n.replace(/^(some|more|the)_/, '')
  for (const it of ALL_ITEMS) if (n.includes(it)) return it
  for (const k in ITEM_ALIASES) if (n.includes(k)) return ITEM_ALIASES[k]
  return null
}

export function normalizeBuilding(s: string): BuildingType | null {
  const n = norm(s)
  if ((BUILDING_TYPES as string[]).includes(n)) return n as BuildingType
  const alias: Record<string, BuildingType> = {
    fire: 'campfire', fire_pit: 'campfire', shelter: 'hut', home: 'hut', house: 'house', stone_house: 'house', storage: 'stockpile',
    store: 'stockpile', storehouse: 'granary', field: 'farm', fields: 'farm', crop_field: 'farm', farm_field: 'farm', wheat_field: 'farm',
    pen: 'pasture', corral: 'pasture', school: 'library', shrine: 'temple', church: 'temple', altar: 'temple', forge: 'smithy',
    blacksmith: 'smithy', quarry: 'mine', harbor: 'dock', harbour: 'dock', pier: 'dock', town_center: 'town_hall', townhall: 'town_hall',
    city_hall: 'town_hall', tower: 'watchtower', wall: 'watchtower', windmill: 'mill', watermill: 'mill', college: 'university',
    clinic: 'hospital', power_station: 'power_plant', laboratory: 'lab', statue: 'monument', obelisk: 'monument', rocket: 'launch_pad',
    spaceport: 'launch_pad', apartments: 'apartment', tent: 'lean_to', leanto: 'lean_to', granary: 'granary', pottery_kiln: 'kiln', oven: 'kiln',
  }
  if (alias[n]) return alias[n]
  for (const b of BUILDING_TYPES) if (n.includes(b)) return b
  for (const k in alias) if (n.includes(k)) return alias[k]
  return null
}

export function normalizeTech(s: string): string | null {
  const n = norm(s)
  if (!n) return null
  if (TECH_BY_ID[n]) return n
  const byName = TECHS.find((t) => norm(t.name) === n)
  if (byName) return byName.id
  const partial = TECHS.find((t) => norm(t.name).includes(n) || n.includes(t.id))
  return partial ? partial.id : null
}

function findRecipe(w: World, a: Agent, target: string): Recipe | null {
  const n = norm(target)
  const known = w.techs(a.tribeId)
  const direct = RECIPES.find((r) => r.id === n || norm(r.name) === n)
  if (direct) return direct
  // by output item ("tools" → best tool recipe we can do)
  const item = n.includes('tool') ? 'tools' : normalizeItem(target)
  if (!item) return null
  const cands = RECIPES.filter((r) => r.out === item && (!r.tech || known.has(r.tech)))
  if (!cands.length) return RECIPES.find((r) => r.out === item) ?? null
  cands.sort((x, y) => (y.tier ?? 0) - (x.tier ?? 0))
  const affordable = cands.find((r) => w.missing(a, r.inputs).length === 0)
  return affordable ?? cands[0]
}

function nearHub(w: World, a: Agent): boolean {
  const h = w.tribeHub(a.tribeId)
  return Math.hypot(h.x + 0.5 - a.x, h.y + 0.5 - a.y) < 2.6
}

function hubTravel(w: World, a: Agent, t: Task): boolean {
  const h = w.tribeHub(a.tribeId)
  if (Math.hypot(h.x + 0.5 - a.x, h.y + 0.5 - a.y) < 2.2) return true
  return travel(w, a, t, h.x, h.y, true)
}

function itemSourceTile(w: World, a: Agent, item: Item, radius: number, nearX = a.x, nearY = a.y): number {
  const known = w.techs(a.tribeId)
  if (item === 'sand') {
    return w.nearestTile(nearX, nearY, radius, (i) => (w.biome[i] === B.BEACH || w.biome[i] === B.DESERT) && !w.occ[i] && w.isExplored(a.tribeId, i))
  }
  return w.nearestTile(nearX, nearY, radius, (i) => {
    const f = w.feature[i]
    if (!f || w.amount[i] === 0) return false
    const def = FEATURES[f]
    if (def.item !== item) return false
    if (def.tech && !known.has(def.tech)) return false
    if (w.occ[i]) {
      const b = w.buildingById.get(w.occ[i] - 1)
      if (b && b.type !== 'mine') return false
    }
    return w.isExplored(a.tribeId, i)
  })
}

/** Tasks that draw from an underground deposit through a mine, well, rig or ponds. */
const DEP_TASK = new WeakSet<Task>()
const EXTRACTORS = new Set<BuildingType>(['mine', 'oil_well', 'offshore_rig', 'evaporation_ponds'])

/** The nearest of the tribe's extraction sites with reachable reserves of an item. */
function depositSource(w: World, a: Agent, item: Item): number {
  let best = -1, bd = 1e9
  for (const b of w.buildings) {
    if (!b.done || b.tribeId !== a.tribeId || !EXTRACTORS.has(b.type)) continue
    if (w.reservesNear(a.tribeId, b.x, b.y, item) <= 0) continue
    const d = Math.hypot(b.x - a.x, b.y - a.y)
    if (d < bd) { bd = d; best = w.idx(b.x, b.y) }
  }
  return best
}

/**
 * How an instinct-driven person should get a mineral: work an existing mine
 * or well, dig a surface outcrop, build an extraction site over a known body,
 * or go prospecting for one.
 */
export function oreStep(w: World, a: Agent, item: Item): { action: 'gather' | 'build' | 'prospect'; target: string } | null {
  if (depositSource(w, a, item) >= 0) return { action: 'gather', target: item }
  if (itemSourceTile(w, a, item, 48) >= 0) return { action: 'gather', target: item }
  // one working per ore body is plenty; and a people only runs so many at once
  const own = w.buildings.filter((b) => b.tribeId === a.tribeId && EXTRACTORS.has(b.type))
  const era = Math.max(0, ...[...w.techs(a.tribeId)].map((id) => TECH_BY_ID[id]?.era ?? 0))
  for (const i of w.knownList(a.tribeId)) {
    if (DEPOSITS[w.deposit[i]]?.item !== item || !w.depAmt[i]) continue
    const site = w.extractable(a.tribeId, i)
    if (!site) continue
    const same = own.filter((b) => b.type === site)
    // finish what's started; otherwise one working per body, and only so many of each kind
    if (same.some((b) => !b.done)) return { action: 'build', target: site }
    if (same.length >= (site === 'mine' ? 3 + era : 3)) continue
    const x = i % w.W, y = Math.floor(i / w.W)
    if (own.some((b) => Math.abs(b.x - x) + Math.abs(b.y - y) < 8)) continue
    return { action: 'build', target: site }
  }
  if (w.hasTech(a.tribeId, 'mining')) return { action: 'prospect', target: item }
  return null
}

/** Why an ore can't be had yet, in a person's words. */
function oreAdvice(w: World, a: Agent, item: Item): string | null {
  const dep = DEPOSITS.find((d) => d.item === item && d.id)
  if (!dep) return null
  let known = 0, reachable = 0, kind = ''
  for (const i of w.knownList(a.tribeId)) {
    if (DEPOSITS[w.deposit[i]]?.item !== item || !w.depAmt[i]) continue
    known++
    const e = w.extractable(a.tribeId, i)
    if (e) { reachable++; kind = e }
  }
  const site = kind.replace('_', ' ')
  if (reachable) return `We know where ${item.replace('_', ' ')} lies underground — we need to build a ${site} over it.`
  if (known) return dep.via === 'well' ? 'We know where oil lies, but we cannot drill for it yet.' : `We know of ${item.replace('_', ' ')} deep underground, but cannot dig that deep yet.`
  return w.hasTech(a.tribeId, 'prospecting') ? `I couldn't find ${item.replace('_', ' ')} at the surface. Someone should go prospecting.` : null
}

/** Move a warrior to where their campaign needs them: the muster point, the enemy's hearth, or home to defend it. */
function warMove(w: World, a: Agent, t: Task, c: { phase: string; kind: string; side: number; enemy: number; tx: number; ty: number }): boolean {
  const home = w.tribeHub(a.tribeId)
  const enemy = w.tribeById(c.enemy)?.name ?? 'enemy'
  const toEnemy = c.kind !== 'defend' && (c.phase === 'march' || c.phase === 'battle')
  const x = toEnemy ? c.tx : home.x, y = toEnemy ? c.ty : home.y
  t.label = c.kind === 'defend' ? `Defending against the ${enemy}` : c.phase === 'muster' ? 'Mustering for war' : `Marching on the ${enemy}`
  // spread out a little, like a real band
  const jx = Math.round((Math.random() - 0.5) * 4), jy = Math.round((Math.random() - 0.5) * 4)
  return travel(w, a, t, Math.max(0, Math.min(w.W - 1, x + jx)), Math.max(0, Math.min(w.H - 1, y + jy)), true) || true
}

function personTarget(w: World, a: Agent, t: Task): Agent | undefined {
  return w.findAgentByName(t.target, a)
}

function deposit(w: World, a: Agent): number {
  const tr = w.tribeById(a.tribeId)
  if (!tr) return 0
  const cap = w.storageCap(tr.id)
  let room = cap - w.storageUsed(tr)
  let moved = 0
  let foodKept = 0
  for (const k of Object.keys(a.inv) as Item[]) {
    let n = invCount(a.inv, k)
    if (ITEMS[k].food && foodKept < 3) {
      const keep = Math.min(n, 3 - foodKept)
      foodKept += keep
      n -= keep
    }
    const m = Math.min(n, room)
    if (m <= 0) continue
    invAdd(a.inv, k, -m)
    invAdd(tr.storage, k, m)
    room -= m
    moved += m
  }
  return moved
}

function eatFrom(a: Agent, inv: Inventory): boolean {
  let best: Item | null = null
  for (const f of FOOD_ITEMS) if (invCount(inv, f) > 0 && (!best || (ITEMS[f].food ?? 0) > (ITEMS[best].food ?? 0))) best = f
  if (!best) return false
  invAdd(inv, best, -1)
  a.hunger = Math.max(0, a.hunger - (ITEMS[best].food ?? 10))
  return true
}

const DIRS: Record<string, [number, number]> = {
  north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0], northeast: [0.7, -0.7], northwest: [-0.7, -0.7],
  southeast: [0.7, 0.7], southwest: [-0.7, 0.7], n: [0, -1], s: [0, 1], e: [1, 0], w: [-1, 0], ne: [0.7, -0.7], nw: [-0.7, -0.7], se: [0.7, 0.7], sw: [-0.7, 0.7],
}

function describeArea(w: World, a: Agent, x: number, y: number, r: number): string {
  const counts = new Map<string, number>()
  const biomes = new Map<string, number>()
  for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) {
    const xx = Math.floor(x) + dx, yy = Math.floor(y) + dy
    if (!w.inBounds(xx, yy)) continue
    const i = w.idx(xx, yy)
    const f = w.feature[i]
    if (f && FEATURES[f].item && f !== F.BUSH && !TREE_FEATURES.has(f) && f !== F.FIBER) counts.set(FEATURES[f].name, (counts.get(FEATURES[f].name) ?? 0) + 1)
    const bn = BIOMES[w.biome[i]].name
    biomes.set(bn, (biomes.get(bn) ?? 0) + 1)
  }
  const topB = [...biomes.entries()].sort((p, q) => q[1] - p[1]).slice(0, 2).map((e) => e[0].toLowerCase())
  const feats = [...counts.keys()].slice(0, 5).map((s) => s.toLowerCase())
  const others = w.tribes.filter((t) => t.alive && t.id !== a.tribeId && w.buildings.some((b) => b.tribeId === t.id && Math.abs(b.x - x) < r && Math.abs(b.y - y) < r))
  let s = `${topB.join(' and ')}`
  if (feats.length) s += `, with ${feats.join(', ')}`
  if (others.length) s += `. I saw the settlement of the ${others.map((t) => t.name).join(' and ')}`
  return s
}

// ------------------------------------------------------------------ handlers

const HANDLERS: Record<ActionKind, Handler> = {
  gather: {
    init(w, a, t) {
      let item = normalizeItem(t.target, true)
      if (!item) item = 'wood'
      if (item === 'meat' || item === 'hide') { t.action = 'hunt'; return HANDLERS.hunt.init(w, a, t) }
      if (item === 'fish') { t.action = 'fish'; return HANDLERS.fish.init(w, a, t) }
      if (item === 'grain') { t.action = 'farm'; return HANDLERS.farm.init(w, a, t) }
      t.target = item
      const room = CARRY_CAP - invTotal(a.inv)
      if (room <= 0) { fail(w, a, 'My pack is full; I should store things first.'); return false }
      t.amount = clamp(t.amount || 10, 1, room)
      const failKey = `${a.tribeId}|gather|${item}|${Math.floor(a.x / 16)},${Math.floor(a.y / 16)}`
      // a mine, well or rig the tribe already runs beats scratching at outcrops
      const dep = depositSource(w, a, item)
      if (dep >= 0) {
        DEP_TASK.add(t)
        t.ref = dep
        t.need = 0
        const b = w.buildingById.get(w.occ[dep] - 1)
        t.label = `${b?.type === 'oil_well' || b?.type === 'offshore_rig' ? 'Pumping' : b?.type === 'evaporation_ponds' ? 'Harvesting' : 'Mining'} ${item.replace('_', ' ')}`
        return travel(w, a, t, dep % w.W, Math.floor(dep / w.W), true)
      }
      const src = w.failed(failKey) ? -1 : itemSourceTile(w, a, item, 48)
      if (src < 0) {
        w.markFailed(failKey, 8)
        const f = FEATURES.find((d) => d.item === item)
        const advice = oreAdvice(w, a, item)
        if (f?.tech && !w.hasTech(a.tribeId, f.tech)) fail(w, a, `We can't harvest ${item} yet — it needs ${TECH_BY_ID[f.tech]?.name ?? f.tech}.`)
        else if (advice) fail(w, a, advice)
        else fail(w, a, `I couldn't find any ${item.replace('_', ' ')} in the lands we know. We need to explore further.`)
        return false
      }
      t.ref = src
      t.need = 0
      t.label = `Gathering ${item.replace('_', ' ')}`
      return travel(w, a, t, src % w.W, Math.floor(src / w.W), true)
    },
    work(w, a, t, dtH) {
      const item = t.target as Item
      const i = t.ref
      if (DEP_TASK.has(t)) {
        const b = w.buildingById.get(w.occ[i] - 1)
        if (!b) return t.need > 0 ? 'done' : 'fail'
        const base = b.type === 'offshore_rig' ? 7 : b.type === 'oil_well' ? 4 : b.type === 'evaporation_ponds' ? 2.5 : 2.2
        const known = w.techs(a.tribeId)
        const rate = base * workMult(w, a, 'gathering', b.type === 'mine' ? 1 : 0.3) * techFx(known, 'mine')
        t.progress += rate * dtH
        w.practice(a, `gather:${item}`, dtH)
        skillUp(a, 'gathering', dtH)
        if (b.type === 'mine') wearTools(w, a, dtH)
        // mines are dangerous places
        if (b.type === 'mine' && Math.random() < dtH * 0.0025 / techFx(known, 'health')) {
          a.injured = Math.max(a.injured, 1.2)
          w.remember(a, 'A roof fall in the mine caught me. I am hurt.', 6)
          w.log('event', `A rock fall in the ${item.replace('_', ' ')} mine injures ${a.name}.`, '⚠️', a.id, a.tribeId)
          return 'done'
        }
        while (t.progress >= 1) {
          t.progress -= 1
          if (!w.extractNear(a.tribeId, b.x, b.y, item)) {
            w.remember(a, `The ${item.replace('_', ' ')} at our ${BUILDINGS[b.type].name.toLowerCase()} has run out.`, 4)
            return t.need > 0 ? 'done' : 'fail'
          }
          invAdd(a.inv, item, 1)
          a.stats.gathered++
          t.need++
          if (t.need >= t.amount || invTotal(a.inv) >= CARRY_CAP) {
            w.remember(a, `I brought up ${t.need} ${item.replace('_', ' ')} from our ${BUILDINGS[b.type].name.toLowerCase()}.`, 1)
            return 'done'
          }
        }
        return 'continue'
      }
      const isSand = item === 'sand'
      if (!isSand && (w.amount[i] === 0 || FEATURES[w.feature[i]].item !== item)) {
        const next = itemSourceTile(w, a, item, 10)
        if (next < 0 || t.need >= t.amount) return t.need > 0 ? 'done' : 'fail'
        t.ref = next
        travel(w, a, t, next % w.W, Math.floor(next / w.W), true)
        return 'continue'
      }
      const def = isSand ? { hoursPerUnit: 0.25 } : FEATURES[w.feature[i]]
      let rate = (1 / def.hoursPerUnit) * workMult(w, a, 'gathering')
      if (!isSand && w.feature[i] >= F.ROCK && w.feature[i] <= F.GOLD) {
        const x = i % w.W, y = Math.floor(i / w.W)
        if (w.buildings.some((b) => b.done && b.type === 'mine' && b.tribeId === a.tribeId && Math.abs(b.x - x) <= 4 && Math.abs(b.y - y) <= 4)) rate *= 2
        rate *= techFx(w.techs(a.tribeId), 'mine')
      }
      if (item === 'berries' && w.hasEvent('drought')) rate *= 0.5
      if (item === 'fiber' && w.hasTech(a.tribeId, 'cordage')) rate *= 1.3
      t.progress += rate * dtH
      w.practice(a, `gather:${item}`, dtH)
      skillUp(a, 'gathering', dtH)
      wearTools(w, a, dtH)
      while (t.progress >= 1) {
        t.progress -= 1
        invAdd(a.inv, item, 1)
        a.stats.gathered++
        t.need++
        if (!isSand) {
          w.amount[i]--
          if (w.amount[i] === 0) {
            const f = w.feature[i]
            if (TREE_FEATURES.has(f)) {
              w.feature[i] = F.SAPLING
              w.regrow.push({ i, t: w.time + (4 + Math.random() * 4) * 24 * HOUR, f })
            } else if (f === F.BUSH || f === F.FIBER) {
              // bushes and reeds regrow; nothing grows back in winter
              const wait = (1.5 + Math.random() * 1.5) * 24 * HOUR
              const springDelay = w.season === 3 ? 3 * 24 * HOUR : 0
              w.regrow.push({ i, t: w.time + wait + springDelay, f })
            } else {
              w.feature[i] = F.NONE
            }
            w.markTile(i)
          }
        }
        if (t.need >= t.amount || invTotal(a.inv) >= CARRY_CAP) {
          w.remember(a, `I gathered ${t.need} ${item.replace('_', ' ')}.`, 1)
          return 'done'
        }
        if (!isSand && w.amount[i] === 0) return 'continue'
      }
      return 'continue'
    },
  },

  hunt: {
    init(w, a, t) {
      let best = null, bd = 40
      for (const an of w.animals) {
        if (!an.alive) continue
        const d = Math.hypot(an.x - a.x, an.y - a.y)
        if (d < bd) { bd = d; best = an }
      }
      if (!best) { fail(w, a, 'I found no game to hunt nearby.'); return false }
      t.ref = best.id
      t.label = `Hunting ${best.kind}`
      t.need = 1.2
      return travel(w, a, t, Math.floor(best.x), Math.floor(best.y), true)
    },
    arrive(w, a, t) {
      const an = w.animals.find((x) => x.id === t.ref)
      if (!an || !an.alive) { fail(w, a, 'The animal I was tracking is gone.'); return false }
      if (Math.hypot(an.x - a.x, an.y - a.y) > 2.5) {
        if (t.fails++ > 4) { fail(w, a, `The ${an.kind} kept slipping away from me.`); return false }
        travel(w, a, t, Math.floor(an.x), Math.floor(an.y), true)
        return true
      }
      return true
    },
    work(w, a, t, dtH) {
      t.progress += dtH
      skillUp(a, 'hunting', dtH)
      w.practice(a, 'hunt', dtH)
      if (t.progress < t.need) return 'continue'
      const an = w.animals.find((x) => x.id === t.ref)
      if (!an || !an.alive) return 'fail'
      const p = Math.min(0.95, ((w.hasTech(a.tribeId, 'hunting') ? 0.7 : 0.35) + a.traits.strength * 0.15 + a.skills.hunting / 400 + (a.toolTier ? 0.1 : 0)) * techFx(w.techs(a.tribeId), 'hunt'))
      if (Math.random() < p) {
        an.alive = false
        const meat = an.kind === 'rabbit' ? 3 : an.kind === 'boar' ? 10 : 8
        invAdd(a.inv, 'meat', meat)
        invAdd(a.inv, 'hide', an.kind === 'rabbit' ? 1 : 2)
        a.stats.gathered += meat
        w.remember(a, `I hunted a ${an.kind} and brought back ${meat} meat.`, 3)
        if (an.kind === 'boar' && Math.random() < 0.15) { a.injured = 1.5; w.remember(a, 'The boar gored me before it fell. I am wounded.', 6) }
        return 'done'
      }
      w.remember(a, `My hunt failed — the ${an.kind} escaped.`, 2)
      an.tx = clamp(Math.floor(an.x) + (an.x > a.x ? 6 : -6), 0, w.W - 1)
      an.ty = clamp(Math.floor(an.y) + (an.y > a.y ? 6 : -6), 0, w.H - 1)
      return 'done'
    },
  },

  fish: {
    init(w, a, t) {
      t.amount = clamp(t.amount || 6, 1, CARRY_CAP - invTotal(a.inv))
      if (t.amount <= 0) return false
      t.need = 0
      const sail = w.canSail(a.tribeId)
      // with boats, go out to where the fish are; without, fish from the bank
      // canoes are worth the paddle only when the home shore is fished out; sailing boats and trawlers always go out
      const shoreRich = w.fishNear(a.x, a.y, 4) > 81 * 25 * 0.5
      if (sail >= 1 && w.hasTech(a.tribeId, 'fishing') && (sail >= 2 || !shoreRich)) {
        const range = [0, 10, 30, 55][sail] + (w.hasTech(a.tribeId, 'steamships') ? 20 : 0)
        const ok = (b: number) => b === B.LAKE || b === B.SHALLOWS || (sail >= 2 && b === B.OCEAN) || (sail >= 3 && b === B.DEEP_OCEAN)
        let best = -1, bs = -1e9
        const x0 = Math.floor(a.x), y0 = Math.floor(a.y)
        for (let k = 0; k < 140; k++) {
          const ang = Math.random() * Math.PI * 2, r = 2 + Math.random() * range
          const x = Math.round(x0 + Math.cos(ang) * r), y = Math.round(y0 + Math.sin(ang) * r)
          if (!w.inBounds(x, y)) continue
          const i = w.idx(x, y)
          if (!ok(w.biome[i]) || !w.isExplored(a.tribeId, i) || w.occ[i]) continue
          const sc = w.fishNear(x, y, 1) - r * 1.2
          if (sc > bs) { bs = sc; best = i }
        }
        if (best >= 0 && w.fishNear(best % w.W, Math.floor(best / w.W), 1) > 20) {
          t.ref = 1
          t.label = sail >= 3 && w.hasTech(a.tribeId, 'steamships') ? 'Trawling' : sail >= 2 ? 'Fishing at sea' : 'Fishing from a canoe'
          if (travel(w, a, t, best % w.W, Math.floor(best / w.W), false)) return true
        }
      }
      const fishy = (i: number) => w.fishCap[i] >= 20 && w.fish[i] > 3
      const spot = w.nearestTile(a.x, a.y, 45, (i) => {
        if (!w.walkableTile(i) || w.biome[i] === B.RIVER || !w.isExplored(a.tribeId, i)) return false
        const x = i % w.W, y = Math.floor(i / w.W)
        for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
          if (w.inBounds(x + dx, y + dy) && fishy(w.idx(x + dx, y + dy))) return true
        }
        return false
      })
      if (spot < 0) { fail(w, a, 'There is no water with fish in it near here.'); return false }
      t.ref = 0
      t.label = 'Fishing'
      return travel(w, a, t, spot % w.W, Math.floor(spot / w.W), false)
    },
    work(w, a, t, dtH) {
      const known = w.techs(a.tribeId)
      let rate = (known.has('fishing') ? 1.7 : 0.5) * workMult(w, a, 'hunting', 0.4) * techFx(known, 'fish')
      const sail = w.canSail(a.tribeId)
      if (t.ref === 1) rate *= [1, 1.2, 1.6, 2.1][sail]
      else if (w.nearestBuilding(a, ['harbour'], true, 8)) rate *= 2
      else if (w.nearestBuilding(a, ['dock'], true, 8)) rate *= 1.5
      // the catch depends on how many fish are really there
      let cap = 0, have = 0
      const x0 = Math.floor(a.x), y0 = Math.floor(a.y)
      for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) {
        if (!w.inBounds(x0 + dx, y0 + dy)) continue
        const i = w.idx(x0 + dx, y0 + dy)
        cap += w.fishCap[i]
        have += w.fish[i]
      }
      const density = cap > 0 ? have / cap : 0
      rate *= 0.3 + density * 0.8
      t.progress += rate * dtH
      skillUp(a, 'hunting', dtH * 0.5)
      w.practice(a, 'fish', dtH)
      // trawl nets take much more than they keep
      const waste = known.has('diesel_trawlers') && t.ref === 1 ? 1.8 : known.has('steamships') && t.ref === 1 ? 1.4 : 1
      while (t.progress >= 1) {
        t.progress -= 1
        const got = w.takeFish(a.x, a.y, waste, 2)
        if (got < 0.5) {
          w.remember(a, 'The fish are gone from these waters. We have taken too many.', 5)
          return t.need > 0 ? 'done' : 'fail'
        }
        invAdd(a.inv, 'fish', 1)
        a.stats.gathered++
        t.need++
        if (t.need >= t.amount) {
          w.remember(a, `${t.ref === 1 ? 'Out on the water ' : ''}I caught ${t.need} fish${density < 0.35 ? ' — far fewer than there used to be' : ''}.`, 1)
          return 'done'
        }
      }
      if (w.time - t.started > 6 * HOUR) return 'done'
      return 'continue'
    },
  },

  prospect: {
    init(w, a, t) {
      if (!w.hasTech(a.tribeId, 'mining') && !w.hasTech(a.tribeId, 'prospecting')) { fail(w, a, 'We do not know what ore looks like yet — we need Mining first.'); return false }
      t.amount = clamp(t.amount || 3, 1, 6)
      t.need = 0
      const d = DIRS[norm(t.target)] ?? null
      const want = d ? null : normalizeItem(t.target, true)
      const deep = w.hasTech(a.tribeId, 'geology')
      // where each mineral forms
      const likes = (b: number, i: number): number => {
        switch (want) {
          case 'oil': return (b === B.DESERT || b === B.MARSH || b === B.GRASSLAND || b === B.SAVANNA || b === B.TUNDRA) && w.elev[i] < 0.35 ? 8 : 0
          case 'coal': return (b === B.FOREST || b === B.MARSH || b === B.GRASSLAND || b === B.TAIGA) && w.elev[i] < 0.42 ? 8 : 0
          case 'bauxite': return w.temp[i] > 0.62 && w.moist[i] > 0.5 ? 8 : 0
          case 'lithium': return b === B.DESERT ? 8 : 0
          case 'iron_ore': return b === B.HILLS ? 8 : 0
          case 'copper': case 'gold': case 'tin': case 'uranium': return b === B.MOUNTAIN || b === B.HILLS ? 8 : 0
          default: return 0
        }
      }
      // prospectors head for hills and mountains — and, once they know geology, the sedimentary lowlands too
      let best = -1, bs = -1e9
      for (let k = 0; k < 90; k++) {
        const ang = d ? Math.atan2(d[1], d[0]) + (Math.random() - 0.5) * 1.1 : Math.random() * Math.PI * 2
        const dist = 5 + Math.random() * (want ? 48 : 32)
        const x = clamp(Math.round(a.x + Math.cos(ang) * dist), 1, w.W - 2), y = clamp(Math.round(a.y + Math.sin(ang) * dist), 1, w.H - 2)
        const i = w.idx(x, y)
        if (!w.walkableTile(i) || !w.isExplored(a.tribeId, i)) continue
        const key = `${a.tribeId}|prospect|${x >> 3},${y >> 3}`
        if (w.failed(key)) continue
        const b = w.biome[i]
        let sc = -dist * 0.15 + Math.random() * 3
        if (want) sc += likes(b, i)
        else {
          if (b === B.HILLS || b === B.MOUNTAIN) sc += 6
          if (deep && (b === B.DESERT || b === B.MARSH || b === B.GRASSLAND || b === B.TUNDRA)) sc += 3
        }
        if (w.feature[i] >= F.COPPER && w.feature[i] <= F.OIL) sc += 8
        if (sc > bs) { bs = sc; best = i }
      }
      if (best < 0) { fail(w, a, 'Every likely place nearby has already been searched.'); return false }
      t.ref = best
      t.label = `${w.hasTech(a.tribeId, 'seismic_survey') ? 'Running a seismic survey' : deep ? 'Mapping the geology' : 'Prospecting'}${want ? ` for ${want.replace('_', ' ')}` : ''}`
      return travel(w, a, t, best % w.W, Math.floor(best / w.W), false)
    },
    work(w, a, t, dtH) {
      t.progress += dtH
      w.practice(a, 'prospect', dtH)
      skillUp(a, 'gathering', dtH * 0.5)
      w.revealAround(a.tribeId, a.x, a.y, 6)
      const found = w.prospect(a, dtH)
      for (const f of found) {
        w.remember(a, `Prospecting, I found ${f}.`, 7)
        w.emote(a, '⛏️')
        w.log('event', `${a.name} finds ${f}.`, '⛏️', a.id, a.tribeId)
        t.need++
      }
      if (t.progress < t.amount) return 'continue'
      w.markFailed(`${a.tribeId}|prospect|${Math.floor(a.x) >> 3},${Math.floor(a.y) >> 3}`, 24 * 6)
      if (!t.need) w.remember(a, 'I searched the ground for signs of ore and found nothing worth digging.', 2)
      return 'done'
    },
  },

  farm: {
    init(w, a, t) {
      const s = w.season
      const farms = w.tribeBuildings(a.tribeId).filter((b) => b.type === 'farm' || b.type === 'pasture')
      if (!farms.length) {
        fail(w, a, w.hasTech(a.tribeId, 'agriculture') ? 'We have no farm fields yet — someone should build one.' : "We don't know agriculture yet.")
        return false
      }
      const score = (b: (typeof farms)[0]) => {
        const d = Math.hypot(b.x - a.x, b.y - a.y)
        if (b.type === 'pasture') return b.crop >= 3 ? 40 - d * 0.5 : -1e9
        if (b.planted && b.crop >= 1) return 100 - d * 0.5
        if (!b.planted && s <= 1) return 60 - d * 0.5
        if (b.planted && b.crop < 1 && s <= 2) return 20 - d * 0.5 - b.crop * 10
        return -1e9
      }
      farms.sort((x, y) => score(y) - score(x))
      const f = farms[0]
      if (score(f) < -1e8) {
        fail(w, a, s === 3 ? 'Nothing grows in winter; the fields must wait for spring.' : 'The fields need no work right now.')
        return false
      }
      t.ref = f.id
      t.amount = t.amount || 3
      t.label = f.type === 'pasture' ? 'Herding' : f.planted && f.crop >= 1 ? 'Harvesting' : f.planted ? 'Tending crops' : 'Planting'
      return travel(w, a, t, f.x, f.y, false)
    },
    work(w, a, t, dtH) {
      const b = w.buildingById.get(t.ref)
      if (!b || !b.done) return 'fail'
      t.progress += dtH * workMult(w, a, 'farming', 0.5)
      skillUp(a, 'farming', dtH)
      w.practice(a, b.type === 'pasture' ? 'farm:pasture' : 'farm', dtH)
      const i = w.idx(b.x, b.y)
      if (b.type === 'pasture') {
        if (t.progress < 1.5) return 'continue'
        b.crop -= 2
        invAdd(a.inv, 'meat', 4)
        invAdd(a.inv, 'hide', 1)
        a.stats.gathered += 5
      } else if (b.planted && b.crop >= 1) {
        if (t.progress < 1.5) return 'continue'
        let y = 6 + w.fertility[i] * 9
        if (w.tribeBuildings(a.tribeId, 'mill').length) y *= 1.5
        y *= techFx(w.techs(a.tribeId), 'farm')
        if (w.hasEvent('drought')) y *= 0.5
        if (w.hasEvent('bounty')) y *= 1.3
        const n = Math.round(y)
        invAdd(a.inv, 'grain', n)
        a.stats.gathered += n
        b.planted = false
        b.crop = 0
        w.remember(a, `I harvested ${n} grain.`, 2)
      } else if (!b.planted) {
        if (t.progress < 0.8) return 'continue'
        b.planted = true
        b.crop = 0.05
      } else {
        if (t.progress < 1) return 'continue'
        b.crop = Math.min(1, b.crop + 0.1)
      }
      w.markTile(i)
      t.amount--
      if (t.amount > 0 && invTotal(a.inv) < CARRY_CAP) {
        const step = { action: 'farm' as const, target: '', amount: t.amount, text: '' }
        a.task = null
        startTask(w, a, step, t.fromPlan)
        return 'continue'
      }
      return 'done'
    },
  },

  eat: {
    init(w, a, t) {
      t.label = 'Eating'
      t.need = 0.3
      if (FOOD_ITEMS.some((f) => invCount(a.inv, f) > 0)) return true
      const tr = w.tribeById(a.tribeId)
      if (tr && FOOD_ITEMS.some((f) => invCount(tr.storage, f) > 0)) {
        t.ref = 1 // eat from stores
        return hubTravel(w, a, t)
      }
      // nothing stored: forage something now
      a.task = null
      startTask(w, a, { action: 'gather', target: 'berries', amount: 6, text: 'survival' }, false)
      if (!a.task) startTask(w, a, { action: 'fish', target: '', amount: 4, text: 'survival' }, false)
      if (!a.task) startTask(w, a, { action: 'hunt', target: '', amount: 1, text: 'survival' }, false)
      return !!a.task
    },
    work(w, a, t, dtH) {
      t.progress += dtH
      if (t.progress < t.need) return 'continue'
      const tr = w.tribeById(a.tribeId)
      let ate = 0
      while (a.hunger > 18 && ate < 6) {
        if (eatFrom(a, a.inv)) { ate++; continue }
        if (tr && (t.ref === 1 || nearHub(w, a)) && eatFrom(a, tr.storage)) { ate++; continue }
        break
      }
      if (w.warmSourceNear(a.x, a.y) && w.hasTech(a.tribeId, 'cooking')) a.hunger = Math.max(0, a.hunger - 8) // cooked food is more nourishing
      w.practice(a, 'eat', 0.3)
      if (!ate) { w.remember(a, 'I was hungry but had nothing to eat.', 4); return 'fail' }
      return 'done'
    },
  },

  drink: {
    init(w, a, t) {
      t.label = 'Drinking'
      t.need = 0.2
      const well = w.nearestBuilding(a, ['well'], false, 40)
      const fresh = (i: number) => {
        const b = w.biome[i]
        return b === B.RIVER || b === B.LAKE || b === B.MARSH
      }
      let water = w.nearestTile(a.x, a.y, 70, (i) => fresh(i) && (w.isExplored(a.tribeId, i) || Math.hypot((i % w.W) - a.x, Math.floor(i / w.W) - a.y) < 14))
      // desperate: follow instinct (and the lie of the land) toward any fresh water at all
      if (water < 0 && a.thirst > 70) water = w.nearestTile(a.x, a.y, 140, fresh)
      let tx = -1, ty = -1
      if (water >= 0) { tx = water % w.W; ty = Math.floor(water / w.W) }
      if (well && (tx < 0 || Math.hypot(well.x - a.x, well.y - a.y) < Math.hypot(tx - a.x, ty - a.y))) { tx = well.x; ty = well.y }
      if (tx < 0) { fail(w, a, "I can't find fresh water anywhere I know. I must explore to find a river or lake."); return false }
      return travel(w, a, t, tx, ty, true)
    },
    work(w, a, t, dtH) {
      t.progress += dtH
      if (t.progress < t.need) return 'continue'
      a.thirst = 0
      w.practice(a, 'drink', 0.2)
      return 'done'
    },
  },

  sleep: {
    init(w, a, t) {
      t.label = 'Sleeping'
      t.need = 11
      // soldiers on campaign sleep where they are, under the stars
      if (w.dip.campaigns.some((c) => c.units.includes(a.id))) { t.label = 'Sleeping in camp'; return true }
      const home = a.homeId ? w.buildingById.get(a.homeId) : undefined
      if (home) return travel(w, a, t, home.x, home.y, true) || true
      const fire = w.nearestBuilding(a, ['campfire'], true, 20)
      if (fire) return travel(w, a, t, fire.x, fire.y, true) || true
      return true
    },
    work(w, a, t, dtH) {
      t.progress += dtH
      const h = hourOf(w.time)
      const day = h > 6 && h < 20
      if (a.energy >= 99 || (day && a.energy > 75) || t.progress > t.need) { w.practice(a, 'sleep', 0.5); return 'done' }
      if (a.thirst > 85 || a.hunger > 88) return 'done'
      return 'continue'
    },
  },

  rest: {
    init(_w, _a, t) {
      t.label = t.text === 'pondering' ? 'Thinking…' : t.text === 'playing' ? 'Playing' : 'Resting'
      t.need = clamp(t.amount || 1, 0.25, 4)
      return true
    },
    work(_w, a, t, dtH) {
      t.progress += dtH
      a.energy = clamp(a.energy + dtH * 3, 0, 100)
      return t.progress >= t.need ? 'done' : 'continue'
    },
  },

  build: {
    init(w, a, t) {
      const type = normalizeBuilding(t.target)
      // help finish an existing site of this type (or any, if unspecified)
      const sites = w.buildings.filter((b) => !b.done && b.tribeId === a.tribeId && (!type || b.type === type))
      sites.sort((x, y) => Math.hypot(x.x - a.x, x.y - a.y) - Math.hypot(y.x - a.x, y.y - a.y))
      if (sites.length && type !== 'road') {
        const s = sites[0]
        t.ref = s.id
        t.target = s.type
        t.label = `Building ${BUILDINGS[s.type].name.toLowerCase()}`
        return travel(w, a, t, s.x, s.y, true)
      }
      if (!type) { fail(w, a, `I don't know how to build "${t.target}".`); return false }
      const def = BUILDINGS[type]
      t.target = type
      if (!w.hasTech(a.tribeId, def.tech)) {
        fail(w, a, `We can't build a ${def.name.toLowerCase()} yet — it needs ${TECH_BY_ID[def.tech!]?.name ?? def.tech}.`)
        return false
      }
      if (type === 'road') return initRoad(w, a, t)
      const miss = w.missing(a, def.cost)
      if (miss.length) {
        fail(w, a, `I couldn't build a ${def.name.toLowerCase()}: we still need ${miss.join(', ')}.`)
        return false
      }
      let site = w.findSite(a, type)
      if (type === 'campfire' && t.text === 'warmth') {
        const i = w.nearestTile(a.x, a.y, 3, (j) => w.canPlace('campfire', j % w.W, Math.floor(j / w.W)))
        if (i >= 0) site = { x: i % w.W, y: Math.floor(i / w.W) }
      }
      if (!site) { fail(w, a, `I couldn't find a suitable place for a ${def.name.toLowerCase()}.`); return false }
      w.payCost(a, def.cost)
      const b = w.placeBuilding(type, site.x, site.y, a.tribeId, a.id)
      t.ref = b.id
      t.label = `Building ${def.name.toLowerCase()}`
      if (!travel(w, a, t, site.x, site.y, true)) {
        // can't reach: refund and remove
        w.destroyBuilding(b, 'was abandoned')
        for (const k in def.cost) invAdd(w.tribeById(a.tribeId)!.storage, k as Item, def.cost[k as Item] ?? 0)
        fail(w, a, `I couldn't reach the site for the ${def.name.toLowerCase()}.`)
        return false
      }
      return true
    },
    work(w, a, t, dtH) {
      if (t.target === 'road') return workRoad(w, a, t, dtH)
      const b = w.buildingById.get(t.ref)
      if (!b) return 'fail'
      if (b.done) return 'done'
      const def = BUILDINGS[b.type]
      b.progress += dtH * workMult(w, a, 'building', 0.6) * techFx(w.techs(a.tribeId), 'build')
      skillUp(a, 'building', dtH)
      w.practice(a, `build:${b.type}`, dtH)
      wearTools(w, a, dtH * 0.5)
      if (b.progress >= def.hours) {
        w.completeBuilding(b, a)
        a.stats.built++
        w.remember(a, `I finished building a ${def.name.toLowerCase()}.`, def.size === 2 ? 7 : 4)
        return 'done'
      }
      return 'continue'
    },
  },

  craft: {
    init(w, a, t) {
      const r = findRecipe(w, a, t.target)
      if (!r) { fail(w, a, `I don't know how to make "${t.target}".`); return false }
      if (r.tech && !w.hasTech(a.tribeId, r.tech)) { fail(w, a, `We can't make ${r.name.toLowerCase()} yet — it needs ${TECH_BY_ID[r.tech]?.name ?? r.tech}.`); return false }
      const miss = w.missing(a, r.inputs)
      if (miss.length) { fail(w, a, `I couldn't make ${r.name.toLowerCase()}: missing ${miss.join(', ')}.`); return false }
      t.target = r.id
      t.amount = clamp(t.amount || 1, 1, 5)
      t.need = r.hours
      t.label = `Crafting ${r.name.toLowerCase()}`
      if (r.station) {
        const st = w.nearestBuilding(a, r.station, true, 60)
        if (!st) { fail(w, a, `To make ${r.name.toLowerCase()} I need a ${r.station.map((s) => BUILDINGS[s].name.toLowerCase()).join(' or ')}.`); return false }
        t.ref = st.id
        return travel(w, a, t, st.x, st.y, true)
      }
      return true
    },
    arrive(w, a, t) {
      const r = RECIPES.find((x) => x.id === t.target)!
      if (!w.payCost(a, r.inputs)) { fail(w, a, `The materials for ${r.name.toLowerCase()} were gone when I arrived.`); return false }
      t.ref = -1 // paid marker
      return true
    },
    work(w, a, t, dtH) {
      const r = RECIPES.find((x) => x.id === t.target)!
      if (t.ref !== -1) {
        if (!w.payCost(a, r.inputs)) return 'fail'
        t.ref = -1
      }
      t.progress += dtH * workMult(w, a, 'crafting', 0.4) * techFx(w.techs(a.tribeId), 'craft')
      skillUp(a, 'crafting', dtH)
      w.practice(a, `craft:${r.id}`, dtH)
      if (t.progress < t.need) return 'continue'
      t.progress = 0
      a.stats.crafted++
      if (r.out === 'tools') {
        if ((r.tier ?? 1) >= a.toolTier) { a.toolTier = r.tier ?? 1; a.toolWear = 0 }
        w.remember(a, `I crafted ${r.name.toLowerCase()}.`, 3)
      } else if (r.out) {
        invAdd(a.inv, r.out, r.outN)
        if (r.out === 'clothes' && a.clothing < 40) { invAdd(a.inv, 'clothes', -1); a.clothing = 100 }
      }
      t.amount--
      if (t.amount > 0 && w.payCost(a, r.inputs)) return 'continue'
      if (r.out && r.out !== 'tools') w.remember(a, `I made ${r.name.toLowerCase()}.`, 2)
      return 'done'
    },
  },

  research: {
    init(w, a, t) {
      const tr = w.tribeById(a.tribeId)
      if (!tr) return false
      const want = t.target ? normalizeTech(t.target) : null
      const av = availableTechs(w.techs(tr.id))
      if (!av.length) { fail(w, a, 'There is nothing left for us to discover right now.'); return false }
      if (want && av.some((x) => x.id === want)) {
        w.focusResearch(tr, want)
      } else if (want && !w.techs(tr.id).has(want) && TECH_BY_ID[want]) {
        const def = TECH_BY_ID[want]
        const missing = def.req.filter((r) => !w.techs(tr.id).has(r)).map((r) => TECH_BY_ID[r]?.name ?? r)
        w.remember(a, `${def.name} must wait — first we need ${missing.join(' and ')}.`, 3)
      }
      if (!tr.research) w.focusResearch(tr, w.pickResearch(tr) ?? av[0].id)
      t.need = clamp(t.amount || 3, 1, 6)
      t.label = `Researching ${TECH_BY_ID[(tr.research as { tech: string } | null)?.tech ?? '']?.name ?? ''}`
      const place = w.nearestBuilding(a, ['data_center', 'lab', 'university', 'library'], true, 50) ?? w.nearestBuilding(a, ['campfire', 'temple'], true, 25)
      if (place) { t.ref = place.id; return travel(w, a, t, place.x, place.y, true) || true }
      return true
    },
    work(w, a, t, dtH) {
      const tr = w.tribeById(a.tribeId)
      if (!tr) return 'fail'
      if (!tr.research) {
        const next = w.pickResearch(tr)
        if (!next) return 'done'
        w.focusResearch(tr, next)
      }
      let mult = 1
      const place = t.ref ? w.buildingById.get(t.ref) : undefined
      if (place && Math.hypot(place.x - a.x, place.y - a.y) < 3) mult = BUILDINGS[place.type].research ?? 1
      if (w.hasPower(a.tribeId)) mult *= 1.2
      const rs = tr.research as { tech: string; progress: number } | null
      if (!rs) return 'done'
      const def = TECH_BY_ID[rs.tech]
      const geo = def ? Math.sqrt(geographyAffinity(def.id, w.landscape(tr))) : 1
      // without writing there is no systematic study: ideas must be held in memory
      const literate = w.techs(tr.id).has('writing') ? 1 : 0.5
      const pts = dtH * 1.2 * (0.55 + a.traits.intelligence * 0.9) * (0.8 + a.skills.research / 100) * mult * geo * literate * techResearchBonus(w.techs(tr.id))
      rs.progress += pts
      a.stats.research += pts
      skillUp(a, 'research', dtH)
      t.progress += dtH
      if (def && w.techProgress(tr, def.id) >= def.cost) w.learnTech(tr, def.id, a, 'research')
      return t.progress >= t.need ? 'done' : 'continue'
    },
  },

  store: {
    init(w, a, t) {
      t.label = 'Storing goods'
      t.need = 0.25
      if (invTotal(a.inv) === 0) return false
      return hubTravel(w, a, t)
    },
    work(w, a, t, dtH) {
      t.progress += dtH
      if (t.progress < t.need) return 'continue'
      const n = deposit(w, a)
      if (n === 0 && invTotal(a.inv) > 3) {
        w.remember(a, 'Our stores are full — we need another stockpile or granary.', 5)
        // leave the bulkiest raw material behind rather than carry it forever
        let big: Item | null = null
        for (const k of Object.keys(a.inv) as Item[]) if (!ITEMS[k].food && (!big || invCount(a.inv, k) > invCount(a.inv, big))) big = k
        if (big) invAdd(a.inv, big, -Math.ceil(invCount(a.inv, big) / 2))
      }
      return 'done'
    },
  },

  take: {
    init(w, a, t) {
      const item = normalizeItem(t.target)
      if (!item) return false
      t.target = item
      t.amount = clamp(t.amount || 5, 1, CARRY_CAP - invTotal(a.inv))
      t.label = `Fetching ${item}`
      t.need = 0.2
      const tr = w.tribeById(a.tribeId)
      if (!tr || invCount(tr.storage, item) <= 0) { fail(w, a, `Our stores have no ${item}.`); return false }
      return hubTravel(w, a, t)
    },
    work(w, a, t, dtH) {
      t.progress += dtH
      if (t.progress < t.need) return 'continue'
      const tr = w.tribeById(a.tribeId)
      if (!tr) return 'fail'
      const n = Math.min(t.amount, Math.floor(invCount(tr.storage, t.target as Item)))
      invAdd(tr.storage, t.target as Item, -n)
      invAdd(a.inv, t.target as Item, n)
      return 'done'
    },
  },

  talk: {
    init(w, a, t) {
      t.label = 'Talking'
      const tg = personTarget(w, a, t)
      if (!tg || /^(everyone|all|tribe|group)$/i.test(t.target)) {
        if (t.text) w.say(a, t.text, null)
        return false
      }
      t.ref = tg.id
      t.label = `Talking to ${tg.name}`
      if (Math.hypot(tg.x - a.x, tg.y - a.y) > 70) { fail(w, a, `${tg.name} is too far away to talk to.`); return false }
      if (Math.hypot(tg.x - a.x, tg.y - a.y) < 2.5) return true
      return travel(w, a, t, Math.floor(tg.x), Math.floor(tg.y), true)
    },
    arrive(w, a, t) {
      const tg = w.agentById.get(t.ref)
      if (!tg || !tg.alive) return false
      if (Math.hypot(tg.x - a.x, tg.y - a.y) > 3 && t.fails++ < 3) {
        travel(w, a, t, Math.floor(tg.x), Math.floor(tg.y), true)
      }
      return true
    },
    work(w, a, t) {
      const tg = w.agentById.get(t.ref)
      if (!tg || !tg.alive) return 'fail'
      if (t.phase === 'travel') return 'continue'
      const text = t.text || smallTalk(w, a, tg)
      w.say(a, text, tg)
      w.practice(a, 'talk', 0.3)
      w.remember(a, `I told ${tg.name}: "${text.slice(0, 120)}"`, 2)
      w.remember(tg, `${a.name} told me: "${text.slice(0, 160)}"`, 4)
      return 'done'
    },
  },

  give: {
    init(w, a, t) {
      const tg = personTarget(w, a, t)
      const item = normalizeItem(t.text || '') ?? normalizeItem(t.target.split(' ').slice(1).join(' '))
      if (!tg) { fail(w, a, `I couldn't find ${t.target} to give them something.`); return false }
      const it = item ?? (Object.keys(a.inv)[0] as Item | undefined)
      if (!it || invCount(a.inv, it) <= 0) { fail(w, a, `I had nothing to give ${tg.name}.`); return false }
      t.ref = tg.id
      t.text = it
      t.amount = clamp(t.amount || 3, 1, invCount(a.inv, it))
      t.label = `Giving ${it} to ${tg.name}`
      return travel(w, a, t, Math.floor(tg.x), Math.floor(tg.y), true)
    },
    work(w, a, t) {
      const tg = w.agentById.get(t.ref)
      if (!tg || !tg.alive) return 'fail'
      const it = t.text as Item
      const n = Math.min(t.amount, invCount(a.inv, it))
      if (n <= 0) return 'fail'
      invAdd(a.inv, it, -n)
      invAdd(tg.inv, it, n)
      w.bump(tg, a, 6 + n, 4)
      w.bump(a, tg, 2, 2)
      w.remember(tg, `${a.name} gave me ${n} ${it}. That was kind.`, 5)
      w.emote(tg, '😊')
      w.remember(a, `I gave ${n} ${it} to ${tg.name}.`, 3)
      if (tg.tribeId !== a.tribeId) w.shiftTribeRelation(a.tribeId, tg.tribeId, 4)
      return 'done'
    },
  },

  teach: {
    init(w, a, t) {
      const tg = personTarget(w, a, t)
      if (!tg) { fail(w, a, `I couldn't find ${t.target} to teach.`); return false }
      t.ref = tg.id
      t.need = 1
      t.label = `Teaching ${tg.name}`
      return travel(w, a, t, Math.floor(tg.x), Math.floor(tg.y), true)
    },
    work(w, a, t, dtH) {
      const tg = w.agentById.get(t.ref)
      if (!tg || !tg.alive) return 'fail'
      t.progress += dtH
      if (t.progress < t.need) return 'continue'
      let best: SkillName = 'gathering'
      for (const s of Object.keys(a.skills) as SkillName[]) if (a.skills[s] - tg.skills[s] > a.skills[best] - tg.skills[best]) best = s
      tg.skills[best] = clamp(tg.skills[best] + 5 + a.skills.social / 25, 0, 100)
      w.practice(a, 'teach', 1)
      w.emote(a, '📚')
      skillUp(a, 'social', 1)
      w.bump(tg, a, 5, 5)
      w.bump(a, tg, 3, 5)
      w.remember(tg, `${a.name} taught me about ${best}.`, 4)
      if (tg.tribeId !== a.tribeId) {
        const mine = w.techs(a.tribeId), theirs = w.techs(tg.tribeId)
        const share = [...mine].filter((x) => !theirs.has(x) && TECH_BY_ID[x]?.req.every((r) => theirs.has(r)))
        const other = w.tribeById(tg.tribeId)
        if (share.length && other) w.learnTech(other, share[Math.floor(Math.random() * share.length)], a, 'diffusion')
        w.shiftTribeRelation(a.tribeId, tg.tribeId, 6)
      }
      return 'done'
    },
  },

  court: {
    init(w, a, t) {
      const tg = personTarget(w, a, t)
      if (!tg) { fail(w, a, `I couldn't find ${t.target}.`); return false }
      if (!w.isAdult(tg)) { fail(w, a, `${tg.name} is only a child.`); return false }
      t.ref = tg.id
      t.need = 0.6
      t.label = `Courting ${tg.name}`
      return travel(w, a, t, Math.floor(tg.x), Math.floor(tg.y), true)
    },
    work(w, a, t, dtH) {
      const tg = w.agentById.get(t.ref)
      if (!tg || !tg.alive) return 'fail'
      t.progress += dtH
      if (t.progress < t.need) return 'continue'
      if (a.partnerId === tg.id) { w.bump(tg, a, 4, 3); w.say(a, t.text || `I'm glad we share our life, ${tg.name}.`, tg); return 'done' }
      if (t.text) w.say(a, t.text, tg)
      const r = w.relation(tg, a)
      const free = !a.partnerId && !tg.partnerId
      const threshold = 25 + Math.random() * 25 - tg.traits.agreeableness * 10
      if (free && r.affinity >= threshold && r.familiarity > 15) {
        a.partnerId = tg.id
        tg.partnerId = a.id
        w.bump(tg, a, 15, 10)
        w.bump(a, tg, 15, 10)
        w.log('social', `${a.name} and ${tg.name} became partners. 💞`, '💞', a.id, a.tribeId)
        w.emote(a, '💞', 7000)
        w.emote(tg, '💞', 7000)
        w.remember(a, `${tg.name} and I are now partners.`, 9)
        w.remember(tg, `${a.name} and I are now partners.`, 9)
        if (tg.tribeId !== a.tribeId) {
          // one moves to the other's tribe: the one with the smaller tribe moves
          const ta = w.members(a.tribeId).length, tb = w.members(tg.tribeId).length
          const mover = ta < tb ? a : tg
          const dest = w.tribeById(mover === a ? tg.tribeId : a.tribeId)
          if (dest) w.joinTribe(mover, dest)
          w.shiftTribeRelation(a.tribeId, tg.tribeId, 15)
        }
        // share a home
        if (!a.homeId && tg.homeId) a.homeId = tg.homeId
        else if (!tg.homeId && a.homeId) tg.homeId = a.homeId
      } else {
        w.bump(tg, a, r.affinity > 0 ? 3 : -3, 4)
        w.emote(a, '💔')
        w.remember(a, free ? `${tg.name} wasn't ready to be my partner. Maybe if we grow closer.` : `${tg.name} is already spoken for — or I am.`, 4)
        w.remember(tg, `${a.name} tried to court me.`, 4)
      }
      return 'done'
    },
  },

  explore: {
    init(w, a, t) {
      const d = DIRS[norm(t.target)] ?? null
      t.label = `Exploring ${d ? norm(t.target) : 'the wilds'}`
      const ex = w.explored.get(a.tribeId)
      for (let k = 0; k < 14; k++) {
        const ang = d ? Math.atan2(d[1], d[0]) + (Math.random() - 0.5) * 0.9 : Math.random() * Math.PI * 2
        const dist = 18 + Math.random() * 20
        const x = clamp(Math.round(a.x + Math.cos(ang) * dist), 1, w.W - 2)
        const y = clamp(Math.round(a.y + Math.sin(ang) * dist), 1, w.H - 2)
        const i = w.idx(x, y)
        if (!w.walkableTile(i)) continue
        if (k < 8 && ex && ex[i] && !d) continue // prefer unexplored ground
        if (travel(w, a, t, x, y, false)) return true
      }
      fail(w, a, 'I tried to explore but found no passable way.')
      return false
    },
    work(w, a) {
      w.revealAround(a.tribeId, a.x, a.y, 12)
      w.practice(a, 'explore', 2)
      w.emote(a, '🧭')
      const desc = describeArea(w, a, a.x, a.y, 10)
      const tr = w.tribeById(a.tribeId)
      const hub = tr ? tr.center : { x: a.x, y: a.y }
      const dx = a.x - hub.x, dy = a.y - hub.y
      const dir = `${dy < -5 ? 'north' : dy > 5 ? 'south' : ''}${dx > 5 ? 'east' : dx < -5 ? 'west' : ''}` || 'near home'
      const dist = Math.round(Math.hypot(dx, dy))
      w.remember(a, `Exploring ${dist} paces ${dir} of home I found ${desc}.`, 5)
      return 'done'
    },
  },

  go_home: {
    init(w, a, t) {
      t.label = 'Going home'
      const home = a.homeId ? w.buildingById.get(a.homeId) : undefined
      const h = home ? { x: home.x, y: home.y } : w.tribeHub(a.tribeId)
      return travel(w, a, t, h.x, h.y, true)
    },
    work() {
      return 'done'
    },
  },

  found_tribe: {
    init(w, a, t) {
      if (!w.isAdult(a)) return false
      const name = (t.target || t.text || '').replace(/^the\s+/i, '').trim().slice(0, 28)
      w.foundTribe(a, name)
      return false
    },
    work() {
      return 'done'
    },
  },

  join_tribe: {
    init(w, a, t) {
      const tr = w.findTribeByName(t.target || t.text)
      if (!tr || tr.id === a.tribeId) { fail(w, a, `I couldn't find a tribe called "${t.target}".`); return false }
      t.ref = tr.id
      t.label = `Seeking to join the ${tr.name}`
      const h = w.tribeHub(tr.id)
      return travel(w, a, t, h.x, h.y, true)
    },
    work(w, a, t) {
      const tr = w.tribeById(t.ref)
      if (!tr || !tr.alive) return 'fail'
      const mem = w.members(tr.id)
      const leader = w.agentById.get(tr.leaderId)
      const avg = mem.reduce((s, m) => s + (m.relations[a.id]?.affinity ?? 0), 0) / Math.max(1, mem.length)
      const lead = leader ? leader.relations[a.id]?.affinity ?? 0 : 0
      if (lead >= -10 && avg >= -5 && w.tribeRelation(tr.id, a.tribeId) > -40) {
        w.joinTribe(a, tr)
        w.remember(a, `The ${tr.name} welcomed me as one of their own.`, 8)
      } else {
        w.remember(a, `The ${tr.name} turned me away.`, 7)
        w.bump(a, leader ?? a, -10, 2)
      }
      return 'done'
    },
  },

  decree: {
    init(w, a, t) {
      const tr = w.tribeById(a.tribeId)
      const text = (t.text || t.target).trim().slice(0, 200)
      if (!tr || !text) return false
      if (tr.leaderId !== a.id) {
        const leader = w.agentById.get(tr.leaderId)
        w.remember(a, `Only our leader ${leader?.name ?? ''} can make decrees; I can only suggest it to them.`, 3)
        if (leader && leader.alive) startTask(w, a, { action: 'talk', target: leader.name, amount: 0, text: `I propose: ${text}` }, false)
        return !!a.task
      }
      const kind = norm(t.target)
      if (kind === 'name' || kind === 'rename') {
        const old = tr.name
        tr.name = t.text.replace(/^the\s+/i, '').trim().slice(0, 28) || tr.name
        w.log('tribe', `${a.name} renamed the ${old}: they are now the ${tr.name}.`, '🏷️', a.id, tr.id)
      } else if (kind === 'values') {
        tr.values = text
        w.log('tribe', `${a.name} declared the values of the ${tr.name}: “${text}”`, '📣', a.id, tr.id)
      } else if (kind === 'beliefs' || kind === 'belief' || kind === 'religion') {
        tr.beliefs = text
        w.log('tribe', `The ${tr.name} embrace a belief: “${text}”`, '🌟', a.id, tr.id)
      } else {
        w.practice(a, 'decree', 1)
        tr.laws.push(text)
        if (tr.laws.length > 6) tr.laws.shift()
        w.log('tribe', `${a.name} decreed a law for the ${tr.name}: “${text}”`, '📜', a.id, tr.id)
      }
      for (const m of w.members(tr.id)) if (m !== a) w.remember(m, `Our leader ${a.name} decreed: "${text}"`, 6)
      return false
    },
    work() {
      return 'done'
    },
  },

  write: {
    init(w, a, t) {
      if (!w.hasTech(a.tribeId, 'writing')) { fail(w, a, "We haven't invented writing yet, so I can only tell stories aloud."); if (t.text) w.say(a, t.text, null); return false }
      t.need = 2
      t.label = 'Writing'
      const lib = w.nearestBuilding(a, ['library', 'university'], true, 50)
      if (lib) return travel(w, a, t, lib.x, lib.y, true) || true
      return true
    },
    work(w, a, t, dtH) {
      t.progress += dtH
      if (t.progress < t.need) return 'continue'
      const tr = w.tribeById(a.tribeId)
      if (!tr) return 'fail'
      const title = (t.target || 'Untitled').slice(0, 60)
      tr.works.push({ t: w.time, author: a.name, title, text: (t.text || '').slice(0, 1200) })
      if (tr.works.length > 40) tr.works.shift()
      w.log('ai', `${a.name} wrote “${title}”.`, '📜', a.id, tr.id)
      w.practice(a, 'write', 2)
      w.emote(a, '📜')
      w.remember(a, `I wrote a work called "${title}".`, 6)
      skillUp(a, 'research', 1)
      return 'done'
    },
  },

  trade: {
    init(w, a, t) {
      const tg = personTarget(w, a, t)
      if (!tg) { fail(w, a, `I couldn't find ${t.target} to trade with.`); return false }
      const m = /(\d+)\s*([a-z_ ]+?)\s+for\s+(\d+)\s*([a-z_ ]+)/i.exec(t.text)
      if (!m) { fail(w, a, 'My trade offer made no sense.'); return false }
      t.ref = tg.id
      t.label = `Trading with ${tg.name}`
      return travel(w, a, t, Math.floor(tg.x), Math.floor(tg.y), true)
    },
    work(w, a, t) {
      const tg = w.agentById.get(t.ref)
      if (!tg || !tg.alive) return 'fail'
      const m = /(\d+)\s*([a-z_ ]+?)\s+for\s+(\d+)\s*([a-z_ ]+)/i.exec(t.text)!
      const giveN = +m[1], giveI = normalizeItem(m[2]), wantN = +m[3], wantI = normalizeItem(m[4])
      if (!giveI || !wantI) return 'fail'
      const ta = w.tribeById(tg.tribeId)
      const theyHave = invCount(tg.inv, wantI) + (ta ? invCount(ta.storage, wantI) : 0)
      if (invCount(a.inv, giveI) < giveN) { w.remember(a, `I didn't have the ${giveI} I offered.`, 3); return 'fail' }
      const fair = giveN >= wantN * 0.7
      const aff = tg.relations[a.id]?.affinity ?? 0
      w.say(a, `Would you trade ${wantN} ${wantI} for ${giveN} ${giveI}?`, tg)
      if (theyHave >= wantN && (fair || aff > 40)) {
        invAdd(a.inv, giveI, -giveN)
        invAdd(tg.inv, giveI, giveN)
        const fromInv = Math.min(wantN, invCount(tg.inv, wantI))
        invAdd(tg.inv, wantI, -fromInv)
        if (wantN - fromInv > 0 && ta) invAdd(ta.storage, wantI, -(wantN - fromInv))
        invAdd(a.inv, wantI, wantN)
        w.bump(tg, a, 4, 4)
        w.bump(a, tg, 4, 4)
        w.practice(a, 'trade', 0.6)
        w.emote(a, '⚖️')
        w.log('social', `${a.name} traded ${giveN} ${giveI} to ${tg.name} for ${wantN} ${wantI}.`, '⚖️', a.id, a.tribeId)
        w.remember(a, `I traded ${giveN} ${giveI} with ${tg.name} for ${wantN} ${wantI}.`, 4)
        w.remember(tg, `I traded with ${a.name}: got ${giveN} ${giveI}, gave ${wantN} ${wantI}.`, 4)
        if (tg.tribeId !== a.tribeId) w.shiftTribeRelation(a.tribeId, tg.tribeId, 5)
      } else {
        w.remember(a, `${tg.name} refused my trade.`, 3)
      }
      return 'done'
    },
  },

  celebrate: {
    init(w, a, t) {
      t.need = 2
      t.label = 'Celebrating'
      const place = w.nearestBuilding(a, ['temple', 'monument', 'market', 'campfire', 'town_hall'], true, 40)
      if (place) { t.ref = place.id; return travel(w, a, t, place.x, place.y, true) || true }
      return true
    },
    work(w, a, t, dtH) {
      t.progress += dtH
      if (t.progress < t.need) return 'continue'
      const near = w.living.filter((o) => Math.hypot(o.x - a.x, o.y - a.y) < 7)
      const tr = w.tribeById(a.tribeId)
      let feast = false
      if (tr) {
        let food = 0
        for (const f of FOOD_ITEMS) food += invCount(tr.storage, f)
        if (food > 15) {
          let n = Math.min(8, near.length * 2)
          for (const f of FOOD_ITEMS) { const m = Math.min(n, invCount(tr.storage, f)); invAdd(tr.storage, f, -m); n -= m }
          feast = true
        }
      }
      w.practice(a, 'celebrate', 2)
      for (const o of near) {
        w.emote(o, feast ? '🍖' : '🎉', 5000)
        o.mood = clamp(o.mood + (feast ? 12 : 7), 0, 100)
        o.social = clamp(o.social + 25, 0, 100)
        if (feast) o.hunger = Math.max(0, o.hunger - 25)
        if (o !== a) { w.bump(o, a, 3, 3); w.remember(o, `${a.name} led ${feast ? 'a feast' : 'a celebration'}${t.text ? `: ${t.text}` : ''}.`, 4) }
      }
      if (near.length >= 3) w.log('social', `${a.name} gathered ${near.length} people for ${feast ? 'a feast' : 'a celebration'}${t.text ? ` — ${t.text.slice(0, 80)}` : ''}.`, feast ? '🍖' : '🎉', a.id, a.tribeId)
      return 'done'
    },
  },

  heal: {
    init(w, a, t) {
      const tg = t.target && !/^(me|self|myself)$/i.test(t.target) ? personTarget(w, a, t) : a
      if (!tg) { fail(w, a, `I couldn't find ${t.target} to heal.`); return false }
      const tr = w.tribeById(a.tribeId)
      const hasMed = invCount(a.inv, 'medicine') > 0 || (tr && invCount(tr.storage, 'medicine') > 0)
      if (!hasMed) { fail(w, a, 'I have no medicine to heal with. Medicine is made from fibre and berries once we know herbalism.'); return false }
      t.ref = tg.id
      t.need = 0.5
      t.label = tg === a ? 'Treating myself' : `Healing ${tg.name}`
      if (tg === a) return true
      return travel(w, a, t, Math.floor(tg.x), Math.floor(tg.y), true)
    },
    work(w, a, t, dtH) {
      const tg = w.agentById.get(t.ref)
      if (!tg || !tg.alive) return 'fail'
      t.progress += dtH
      if (t.progress < t.need) return 'continue'
      const tr = w.tribeById(a.tribeId)
      if (invCount(a.inv, 'medicine') > 0) invAdd(a.inv, 'medicine', -1)
      else if (tr) invAdd(tr.storage, 'medicine', -1)
      tg.sick = 0
      tg.injured = 0
      w.practice(a, 'heal', 0.5)
      w.emote(a, '🌿')
      tg.health = clamp(tg.health + 25, 0, 100)
      if (tg !== a) {
        w.bump(tg, a, 12, 5)
        w.remember(tg, `${a.name} healed me with medicine.`, 6)
        w.log('social', `${a.name} nursed ${tg.name} back to health.`, '💊', a.id, a.tribeId)
      }
      return 'done'
    },
  },

  attack: {
    init(w, a, t) {
      const tg = personTarget(w, a, t)
      if (!tg) { fail(w, a, `I couldn't find ${t.target}.`); return false }
      t.ref = tg.id
      t.label = `Confronting ${tg.name}`
      return travel(w, a, t, Math.floor(tg.x), Math.floor(tg.y), true)
    },
    work(w, a, t) {
      const tg = w.agentById.get(t.ref)
      if (!tg || !tg.alive) return 'fail'
      const pa = a.traits.strength + a.toolTier * 0.15 + Math.random() * 0.6
      const pb = tg.traits.strength + tg.toolTier * 0.15 + Math.random() * 0.6
      const loser = pa >= pb ? tg : a
      const winner = loser === a ? tg : a
      loser.health -= 25 + Math.random() * 35
      loser.injured = 2
      winner.health -= Math.random() * 15
      w.bump(tg, a, -45, 5)
      w.bump(a, tg, -20, 5)
      if (tg.tribeId !== a.tribeId) w.shiftTribeRelation(a.tribeId, tg.tribeId, -30)
      w.log('event', `${a.name} attacked ${tg.name}${t.text ? ` (“${t.text.slice(0, 60)}”)` : ''}. ${winner.name} prevailed.`, '⚔️', a.id, a.tribeId)
      w.remember(tg, `${a.name} attacked me!`, 9)
      w.emote(a, '⚔️', 5000)
      w.emote(tg, '😠', 6000)
      w.remember(a, `I fought ${tg.name}. ${winner === a ? 'I won.' : 'I lost.'}`, 8)
      tg.urgentThink = true
      for (const o of w.living) if (o !== a && o !== tg && Math.hypot(o.x - a.x, o.y - a.y) < 8) { w.bump(o, a, -15, 2); w.remember(o, `I saw ${a.name} attack ${tg.name}.`, 6) }
      if (loser.health <= 0) w.kill(loser, `wounds from a fight with ${winner.name}`)
      return 'done'
    },
  },

  war: {
    init(w, a, t) {
      const c = w.dip.campaigns.find((x) => x.units.includes(a.id))
      if (!c) return false
      t.text = militaryTier(w.techs(a.tribeId)).weapon
      // the strongest of the column carries the people's banner
      if (c.kind !== 'defend' && c.units[0] === a.id) t.text = 'flag'
      return warMove(w, a, t, c)
    },
    work(w, a, t) {
      const c = w.dip.campaigns.find((x) => x.units.includes(a.id))
      if (!c) return 'done'
      const enemy = w.tribeById(c.enemy)?.name ?? 'enemy'
      if (c.phase === 'battle') {
        t.label = `Fighting the ${enemy}`
        // keep close to the fighting
        const tx = c.kind === 'defend' ? w.tribeHub(a.tribeId).x : c.tx, ty = c.kind === 'defend' ? w.tribeHub(a.tribeId).y : c.ty
        if (Math.hypot(a.x - tx, a.y - ty) > 6) warMove(w, a, t, c)
        return 'continue'
      }
      if (Math.hypot(a.x - t.tx, a.y - t.ty) > 3 || (c.phase === 'march' && t.label.startsWith('Mustering'))) warMove(w, a, t, c)
      return 'continue'
    },
  },

  diplomacy: {
    init(_w, _a, t) {
      t.label = 'Sending envoys'
      return true
    },
    work(w, a, t) {
      const tr = w.tribeById(a.tribeId)
      const other = w.findTribeByName(t.target)
      if (!tr || !other) { fail(w, a, `There is no people called ${t.target}.`); return 'fail' }
      const words = t.text.trim()
      const first = norm(words.split(/\s+/)[0] ?? '')
      const kinds: Record<string, Proposal> = {
        alliance: 'alliance', ally: 'alliance', trade: 'trade', peace: 'peace', truce: 'peace', war: 'war', attack: 'war', raid: 'war',
        tribute: 'tribute', demand: 'tribute', gift: 'gift', gifts: 'gift', break: 'break', end: 'break',
      }
      const kind = kinds[first] ?? (/(alliance|ally)/i.test(words) ? 'alliance' : /peace|truce/i.test(words) ? 'peace' : /war|attack|raid/i.test(words) ? 'war' : /tribute/i.test(words) ? 'tribute' : /trade/i.test(words) ? 'trade' : 'gift')
      if (kind !== 'gift' && tr.leaderId !== a.id) { fail(w, a, `Only our leader can make ${kind} with another people.`); return 'fail' }
      const r = propose(w, tr.id, other.id, kind, a, words.replace(/^\w+\s*[:,-]?\s*/, ''))
      w.remember(a, r.text, 7)
      w.emote(a, kind === 'war' ? '⚔️' : r.ok ? '🤝' : '✋', 5000)
      return 'done'
    },
  },

  wander: {
    init(w, a, t) {
      t.label = 'Wandering'
      let tx: number, ty: number
      const m = /(-?\d+)\s*,\s*(-?\d+)/.exec(t.target)
      if (m) { tx = clamp(+m[1], 0, w.W - 1); ty = clamp(+m[2], 0, w.H - 1) }
      else { tx = clamp(Math.floor(a.x) + Math.floor(Math.random() * 13) - 6, 0, w.W - 1); ty = clamp(Math.floor(a.y) + Math.floor(Math.random() * 13) - 6, 0, w.H - 1) }
      if (!w.walkableTile(w.idx(tx, ty))) return travel(w, a, t, tx, ty, true)
      return travel(w, a, t, tx, ty, false)
    },
    work() {
      return 'done'
    },
  },

  follow: {
    init(w, a, t) {
      const tg = personTarget(w, a, t)
      if (!tg) return false
      t.label = `Following ${tg.name}`
      return travel(w, a, t, Math.floor(tg.x), Math.floor(tg.y), true)
    },
    work() {
      return 'done'
    },
  },
}

// ------------------------------------------------------------------ roads

function initRoad(w: World, a: Agent, t: Task): boolean {
  // Pave from the tribe hub toward the furthest unconnected building.
  const hub = w.tribeHub(a.tribeId)
  const blds = w.tribeBuildings(a.tribeId).filter((b) => b.type !== 'farm' && b.type !== 'pasture')
  blds.sort((x, y) => Math.hypot(y.x - hub.x, y.y - hub.y) - Math.hypot(x.x - hub.x, x.y - hub.y))
  const dest = blds.find((b) => Math.hypot(b.x - hub.x, b.y - hub.y) > 4)
  if (!dest) { fail(w, a, 'Our buildings are too close together to need roads yet.'); return false }
  const fake = { ...a, x: hub.x + 0.5, y: hub.y + 0.5 } as Agent
  const p = w.path(fake, dest.x, dest.y, true)
  if (!p) return false
  const tiles = [...p].filter((i) => !w.road[i] && !w.occ[i] && !BIOMES[w.biome[i]].water).slice(0, 14)
  if (!tiles.length) { fail(w, a, 'That road is already paved.'); return false }
  t.label = 'Paving a road'
  ;(t as Task & { tiles: number[] }).tiles = tiles
  t.need = 0
  const first = tiles[0]
  return travel(w, a, t, first % w.W, Math.floor(first / w.W), false)
}

function workRoad(w: World, a: Agent, t: Task, dtH: number): Result {
  const tiles = (t as Task & { tiles?: number[] }).tiles
  if (!tiles || !tiles.length) return 'done'
  t.progress += dtH * workMult(w, a, 'building', 0.5)
  if (t.progress < 0.4) return 'continue'
  t.progress = 0
  const i = tiles.shift()!
  if (!w.road[i] && !w.occ[i]) {
    if (!w.payCost(a, { stone: 1 })) { w.remember(a, 'I ran out of stone for the road.', 3); return 'done' }
    w.road[i] = w.hasTech(a.tribeId, 'railways') ? 2 : 1
    w.markTile(i)
    skillUp(a, 'building', 0.3)
  }
  if (!tiles.length) { w.remember(a, 'I paved a stretch of road.', 3); return 'done' }
  const n = tiles[0]
  travel(w, a, t, n % w.W, Math.floor(n / w.W), false)
  return 'continue'
}

// ------------------------------------------------------------------ chatter

const pick = <T,>(arr: T[], seed: number): T => arr[Math.abs(seed) % arr.length]

/** Instinct chatter: varied, grounded in what the speaker is living through. */
export function smallTalk(w: World, a: Agent, b: Agent): string {
  const seed = a.id * 31 + b.id * 7 + Math.floor(w.time / 53)
  const r = w.relation(a, b)
  const tr = w.tribeById(a.tribeId)
  if (a.sick > 0) return pick([`I don't feel well, ${b.name}.`, 'My head burns with fever.', 'Stay back — I think I am sick.'], seed)
  if (b.sick > 0) return pick([`You look unwell, ${b.name}. Rest, please.`, `${b.name}, let me fetch you water.`, 'You should lie down by the fire.'], seed)
  if (a.hunger > 70) return pick(['My stomach aches. Is there food in the stores?', 'We need more food before the cold comes.', `${b.name}, have you eaten today?`], seed)
  if (a.warmth < 40) return pick(['This cold cuts to the bone.', 'We need more firewood.', 'I can barely feel my fingers.'], seed)
  if (b.tribeId !== a.tribeId) {
    const other = w.tribeById(b.tribeId)
    return pick([`Greetings, friend of the ${other?.name ?? 'far lands'}.`, `What brings you to our lands, ${b.name}?`, 'Your people build differently from ours.', `Is it true the ${other?.name ?? 'others'} know things we don't?`, 'Perhaps our peoples could trade.'], seed)
  }
  const topics: string[] = []
  const s = w.season
  if (s === 3) topics.push('Winter is long this year.', 'Keep close to the fire tonight.', 'Spring cannot come soon enough.')
  if (s === 0) topics.push('Spring! The fields will want planting.', 'The bushes are budding again.', 'I love the smell of the rain in spring.')
  if (s === 1) topics.push('The sun is fierce today.', 'Long days — good for working.', "Let's gather while the weather holds.")
  if (s === 2) topics.push('Harvest time. Every hand counts.', 'The leaves are turning gold.', 'We must store food before the snow.')
  if (w.weather === 'rain' || w.weather === 'storm') topics.push('Soaked to the skin again!', 'The rain is good for the crops, at least.')
  if (a.task) topics.push(`I've been ${a.task.label.toLowerCase() || 'working'} since dawn.`)
  if (b.task?.label) topics.push(`How goes the ${b.task.label.toLowerCase().replace(/ing\b.*/, 'ing')}, ${b.name}?`)
  if (tr?.research) topics.push(`They say we're close to understanding ${TECH_BY_ID[tr.research.tech]?.name.toLowerCase() ?? 'something new'}.`)
  if (tr && w.storageUsed(tr) > w.storageCap(tr.id) * 0.85) topics.push('The stores are overflowing — we need more storage.')
  const recent = a.memories[a.memories.length - 1]
  if (recent && recent.imp >= 5) topics.push(`Did you hear? ${recent.text.replace(/^I /, 'I ')}`.slice(0, 140))
  if (a.children.length) topics.push('The children grow so fast.', 'My little ones keep me up at night.')
  if (r.affinity > 60) topics.push(`I'm always glad when our paths cross, ${b.name}.`, `You're a good friend, ${b.name}.`, 'Walk with me a while?')
  if (r.affinity < -20) topics.push(`Hm. ${b.name}.`, 'Keep to your own work.', "Don't think I've forgotten.")
  topics.push(
    'I think we could build something great here.', 'My hands ache, but it is good work.',
    `I dreamed of mountains to the ${['north', 'south', 'east', 'west'][a.id % 4]} last night.`,
    'If we learn a little more each day, imagine what our children will know.', 'Have you seen game near the river?',
    'What do you think lies beyond the hills?', 'The stars were bright last night.', 'Do you ever wonder who came before us?',
  )
  return pick(topics, seed)
}

export { DIRS }
