// Game state: survivors, items (quality, condition, mods), stations and their
// orders, the camp's shape and expansions, saving and loading.
import {
  RES, RES_KEYS, STOCK_KEYS, SKILLS, SKILL_KEYS, SKILL_MAX, xpForLevel, OCCUPATIONS, OCC_KEYS, TRAITS, TRAIT_KEYS,
  FIRST_NAMES, FEMALE_NAMES, LAST_NAMES, ITEMS, QUALITY, MODS, STATIONS, FENCE, RECIPES, REPAIR, EXPANSIONS, EXPANSION_DEPTH,
  GOALS, DAY_MIN, UTILITIES,
} from './data.js'
import { bus, uid, pick, rint, rand, chance, clamp, store, weighted } from '../core/util.js'
import { SKIN_TONES, HAIR_COLORS } from '../models/character.js'

export const SAVE_KEY = 'holdout.save.v2'
export let S = null
export const setState = (s) => (S = s)

// ---------------------------------------------------------------- camp geometry
export const BASE = { W: 112, H: 112, START: [40, 72] }
// Current fence rectangle (fence tiles on the boundary, inclusive).
export function bounds(s = S) {
  const e = s.expansions || {}
  const d = EXPANSION_DEPTH
  const n = (id) => (e[id] === 'done' ? 1 : 0)
  return {
    x0: BASE.START[0] - d * (n('w1') + n('w2')),
    x1: BASE.START[1] + d * (n('e1') + n('e2')),
    z0: BASE.START[0] - d * (n('n1') + n('n2')),
    z1: BASE.START[1] + d * (n('s1') + n('s2')),
  }
}
export function gateTiles(b = bounds()) {
  const cx = Math.floor((b.x0 + b.x1) / 2)
  return [cx - 1, cx, cx + 1]
}
export function fenceTiles(b = bounds()) {
  const out = []
  for (let x = b.x0; x <= b.x1; x++) out.push([x, b.z0])
  for (let z = b.z0 + 1; z <= b.z1; z++) out.push([b.x1, z])
  for (let x = b.x1 - 1; x >= b.x0; x--) out.push([x, b.z1])
  for (let z = b.z1 - 1; z > b.z0; z--) out.push([b.x0, z])
  return out
}
export const perimeter = (b = bounds()) => 2 * (b.x1 - b.x0 + b.z1 - b.z0)
// The land an expansion adds, as a tile rectangle (exclusive of fences).
export function expansionRect(id, s = S) {
  const X = EXPANSIONS.find((e) => e.id === id)
  const b = bounds(s)
  const d = EXPANSION_DEPTH
  if (X.side === 'n') return { x0: b.x0, x1: b.x1, z0: b.z0 - d, z1: b.z0 }
  if (X.side === 's') return { x0: b.x0, x1: b.x1, z0: b.z1, z1: b.z1 + d }
  if (X.side === 'w') return { x0: b.x0 - d, x1: b.x0, z0: b.z0, z1: b.z1 }
  return { x0: b.x1, x1: b.x1 + d, z0: b.z0, z1: b.z1 }
}
export function expansionAvailable(id) {
  const X = EXPANSIONS.find((e) => e.id === id)
  if (S.expansions[id]) return false
  if (X.ring === 2 && S.expansions[X.side + '1'] !== 'done') return false
  return true
}

// ---------------------------------------------------------------- clock
export const day = () => Math.floor(S.time / DAY_MIN) + 1
export const hour = () => (S.time % DAY_MIN) / 60
export const clockStr = (t = S.time) => {
  const m = Math.floor(t % DAY_MIN)
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`
}
export const gameDur = (mins) => {
  mins = Math.max(0, Math.round(mins))
  const h = Math.floor(mins / 60)
  const m = mins % 60
  return h > 0 ? `${h}h ${String(m).padStart(2, '0')}m` : `${m}m`
}

// ---------------------------------------------------------------- survivors
export function randomLook(female) {
  const style = female ? pick(['long', 'ponytail', 'bun', 'short', 'curly', 'side']) : pick(['short', 'side', 'buzz', 'buzz', 'curly', 'bald', 'long', 'mohawk'])
  return {
    skin: pick(SKIN_TONES),
    hair: { style, color: pick(HAIR_COLORS) },
    beard: female ? null : pick([null, null, null, 'beard', 'mustache', 'goatee']),
    build: rand(0.92, 1.1),
    height: rand(0.95, 1.05) * (female ? 0.96 : 1),
    female: !!female,
    seed: rint(1, 1e6),
  }
}
export function makeSurvivor(opts = {}) {
  const occ = opts.occ || pick(OCC_KEYS)
  const quality = opts.quality ?? 0
  const skills = {}
  const xp = {}
  for (const k of SKILL_KEYS) {
    skills[k] = 1
    xp[k] = 0
  }
  for (const [k, v] of Object.entries(OCCUPATIONS[occ].skills)) skills[k] += v
  const extra = rint(1, 3) + quality
  for (let i = 0; i < extra; i++) {
    const k = pick(SKILL_KEYS)
    skills[k] = Math.min(SKILL_MAX, skills[k] + 1)
  }
  const goods = TRAIT_KEYS.filter((t) => TRAITS[t].good)
  const bads = TRAIT_KEYS.filter((t) => !TRAITS[t].good)
  const traits = [pick(goods)]
  if (chance(0.45)) traits.push(pick(bads))
  else if (chance(0.3)) traits.push(pick(goods.filter((g) => g !== traits[0])))
  const first = opts.first || pick(FIRST_NAMES)
  const female = FEMALE_NAMES.has(first)
  const s = {
    id: uid('s'),
    name: `${first} ${opts.last || pick(LAST_NAMES)}`,
    first,
    occ,
    traits,
    skills,
    xp,
    look: randomLook(female),
    hp: 100,
    status: 'ok',
    job: null,
    equip: { weapon: null, armor: null, gear: null },
    util: null, // utility item type carried on runs
    kills: 0,
    runs: 0,
    joined: S ? day() : 1,
    age: rint(19, 58),
  }
  s.hp = survivorStats(s).maxHp
  return s
}
export const getS = (id) => S.survivors.find((s) => s.id === id)
export const available = () => S.survivors.filter((s) => s.status === 'ok')
export const survivorLevel = (s) => Math.max(1, Math.round(SKILL_KEYS.reduce((a, k) => a + s.skills[k], 0) / 2.4))

function fxSum(s, key) {
  let v = OCCUPATIONS[s.occ].fx[key] || 0
  for (const t of s.traits) v += TRAITS[t].fx[key] || 0
  return v
}
export const occFx = (s) => OCCUPATIONS[s.occ].fx

// ---------------------------------------------------------------- items
export const itemOf = (u) => (u ? S.items.find((i) => i.uid === u) : null)
export function addItem(id, o = {}) {
  const it = { uid: uid('i'), id, q: o.q ?? 1, cond: o.cond ?? 100, mods: o.mods || [] }
  S.items.push(it)
  bus.emit('items')
  return it
}
export const ownerOf = (u) => S.survivors.find((s) => Object.values(s.equip).includes(u))
export function equippedItem(s, slot) {
  const it = itemOf(s.equip[slot])
  return it || null
}
export function equipped(s, slot) {
  return equippedItem(s, slot)?.id || null
}
export function equip(s, u) {
  const it = itemOf(u)
  if (!it) return
  const slot = ITEMS[it.id].slot
  const prev = ownerOf(u)
  if (prev) prev.equip[slot] = null
  s.equip[slot] = u
  s.hp = Math.min(s.hp, survivorStats(s).maxHp)
  bus.emit('change')
}
export function unequip(s, slot) {
  s.equip[slot] = null
  s.hp = Math.min(s.hp, survivorStats(s).maxHp)
  bus.emit('change')
}
export function removeItem(u) {
  const o = ownerOf(u)
  if (o) for (const k of Object.keys(o.equip)) if (o.equip[k] === u) o.equip[k] = null
  S.items = S.items.filter((i) => i.uid !== u)
  bus.emit('items')
}
export const itemName = (it) => `${it.q !== 1 ? QUALITY[it.q].name + ' ' : ''}${ITEMS[it.id].name}`
export function itemValue(it) {
  const I = ITEMS[it.id]
  return Math.round(I.value * QUALITY[it.q ?? 1].value * (0.4 + 0.6 * (it.cond ?? 100) / 100) * (1 + (it.mods?.length || 0) * 0.25))
}
// How much a use wears an item, as percent of condition.
export function wearPerUse(it) {
  const I = ITEMS[it.id]
  if (!I.dur) return 0
  let d = I.dur * QUALITY[it.q ?? 1].dur
  for (const m of it.mods || []) if (MODS[m]?.fx.dur) d *= MODS[m].fx.dur
  return 100 / d
}
export function wear(it, uses = 1) {
  if (!it) return false
  const w = wearPerUse(it) * uses
  if (!w) return false
  const before = it.cond
  it.cond = Math.max(0, it.cond - w)
  if (before > 0 && it.cond <= 0) {
    bus.emit('broken', it)
    return true
  }
  return false
}
export function repairCost(it) {
  const I = ITEMS[it.id]
  if (!I.repair) return null
  const missing = (100 - it.cond) / 100
  const scale = Math.max(0.5, Math.sqrt(I.value / 100)) * missing
  const out = {}
  for (const [k, v] of Object.entries(REPAIR[I.repair])) {
    const n = Math.ceil(v * scale)
    if (n > 0) out[k] = n
  }
  return out
}
export const repairTime = (it) => Math.max(8, Math.round(((100 - it.cond) / 100) * 30 * Math.sqrt(ITEMS[it.id].value / 100 + 0.5)))

// Everything combat and work code needs about a survivor.
export function survivorStats(s) {
  const wIt = S ? equippedItem(s, 'weapon') : null
  const broken = wIt && wIt.cond <= 0
  const wid = wIt && !broken ? wIt.id : 'fists'
  const aIt = S ? equippedItem(s, 'armor') : null
  const A = aIt && aIt.cond > 0 ? ITEMS[aIt.id] : null
  const gIt = S ? equippedItem(s, 'gear') : null
  const Gd = gIt ? ITEMS[gIt.id] : null
  const W = ITEMS[wid]
  const wq = wIt && !broken ? QUALITY[wIt.q ?? 1] : QUALITY[1]
  const aq = aIt ? QUALITY[aIt.q ?? 1] : QUALITY[1]
  const wm = (wIt && !broken && wIt.mods) || []
  const am = (aIt && aIt.cond > 0 && aIt.mods) || []
  const mf = (mods, k, base = 1, mode = 'mul') => mods.reduce((v, m) => (MODS[m]?.fx[k] != null ? (mode === 'mul' ? v * MODS[m].fx[k] : v + MODS[m].fx[k]) : v), base)
  const sk = s.skills
  const gun = W.kind === 'gun'
  let dmg = 1 + fxSum(s, 'dmg')
  if (gun) {
    dmg += 0.06 * (sk.ranged - 1) + fxSum(s, 'gunDmg')
    if (W.pistol) dmg += fxSum(s, 'pistolDmg')
    if (W.rifle) dmg += fxSum(s, 'rifleDmg')
  } else {
    dmg += 0.08 * (sk.melee - 1) + fxSum(s, 'meleeDmg')
    if (W.axe) dmg += fxSum(s, 'axeDmg')
  }
  const armorHp = A ? A.hp * aq.mult + mf(am, 'hp', 0, 'add') : 0
  const dr = A ? clamp(A.dr * aq.mult + mf(am, 'dr', 0, 'add'), 0, 0.6) : 0
  const speed = 3.2 * (1 + fxSum(s, 'speed') + (Gd?.speed || 0) + (A?.speed || 0) + mf(am, 'speed', 0, 'add'))
  return {
    weaponId: wid,
    weaponItem: broken ? null : wIt,
    weaponBroken: !!broken,
    weapon: W,
    armorItem: aIt,
    gun,
    ammoType: gun ? W.ammo : null,
    maxHp: Math.round(100 + fxSum(s, 'hp') + armorHp + (sk.melee + sk.build) * 1.5),
    speed,
    dmg: W.dmg * dmg * wq.mult * mf(wm, 'dmg'),
    range: W.range * (gun ? 1 + 0.025 * (sk.ranged - 1) : 1) * mf(wm, 'range'),
    rate: W.rate * (gun ? 1 : 1 - 0.025 * (sk.melee - 1)) * mf(wm, 'rate'),
    acc: gun ? clamp(0.55 + 0.04 * sk.ranged + fxSum(s, 'acc') + mf(wm, 'acc', 0, 'add') + (Gd?.acc || 0), 0.3, 0.98) : 1,
    dr,
    noise: W.noise * mf(wm, 'noise'),
    noiseMult: Math.max(0.2, 1 + fxSum(s, 'noise')),
    stealth: clamp((A?.stealth || 0) + mf(am, 'stealth', 0, 'add'), 0, 0.7),
    search: 1 + 0.08 * (sk.scavenge - 1) + (Gd?.search || 0),
    loot: 1 + 0.04 * (sk.scavenge - 1) + fxSum(s, 'loot'),
    carry: Math.round(26 + sk.scavenge * 2 + fxSum(s, 'carry') + (Gd?.carry || 0)),
    utilSlots: 2 + (Gd?.util || 0),
    dismantle: 1 + 0.06 * (sk.build - 1) + fxSum(s, 'dismantle') + (Gd?.dismantle || 0),
    picklock: !!(Gd?.picklock || fxSum(s, 'picklock')),
    pry: !!W.pry,
    knock: !!W.knock,
    walkie: !!Gd?.walkie,
    nightSight: Gd?.nightSight || 0,
    revive: (OCCUPATIONS[s.occ].fx.reviveMult || 1) / (1 + 0.06 * (sk.medic - 1)),
    aura: (OCCUPATIONS[s.occ].fx.aura || 0) + (sk.medic >= 6 ? 0.4 : 0),
    carParts: fxSum(s, 'carParts'),
    fireproof: !!fxSum(s, 'fireproof'),
    xpMult: 1 + fxSum(s, 'xp'),
    pack: Gd?.pack || null,
  }
}

export function gainXP(s, skill, amt) {
  if (!s || !skill || s.skills[skill] >= SKILL_MAX) return
  s.xp[skill] += amt * (1 + fxSum(s, 'xp'))
  const need = xpForLevel(s.skills[skill])
  if (s.xp[skill] >= need) {
    s.xp[skill] -= need
    s.skills[skill]++
    bus.emit('levelup', s, skill)
    log(`${s.first} is now level ${s.skills[skill]} in ${SKILLS[skill].name}.`, 'good')
  }
}

// How good a survivor is at a station's job (≈0.7–2.2).
export function workEff(s, type) {
  const def = STATIONS[type]
  if (!def) return 0
  const sk = def.skill ? s.skills[def.skill] : 3
  let e = 0.62 + 0.08 * sk
  e *= 1 + (OCCUPATIONS[s.occ].fx.station?.[type] || 0)
  e *= 1 + fxSum(s, 'work')
  e *= moraleMult()
  if (S.hungry) e *= 0.6
  if (s.status === 'injured') e = 0
  return e
}
export const bestFor = (type) => OCC_KEYS.filter((o) => OCCUPATIONS[o].stations?.includes(type)).map((o) => OCCUPATIONS[o].name)

// ---------------------------------------------------------------- resources
export function baseCap() {
  let cap = 150
  for (const st of S.stations) if (st.type === 'storage' && st.level > 0) cap += STATIONS.storage.cap[st.level - 1]
  return cap
}
export const capOf = (k) => (k === 'cash' ? Infinity : Math.max(10, Math.round(baseCap() * (RES[k].capMul ?? 1))))
export function bedCount() {
  let n = 0
  for (const st of S.stations) if (st.type === 'bunkhouse' && st.level > 0) n += STATIONS.bunkhouse.beds[st.level - 1]
  return n
}
export const canAfford = (cost) => Object.entries(cost || {}).every(([k, v]) => (S.res[k] || 0) >= v - 1e-6)
export function pay(cost) {
  if (!canAfford(cost)) return false
  for (const [k, v] of Object.entries(cost)) S.res[k] -= v
  bus.emit('res')
  return true
}
// Add resources, clamped to storage. Returns what didn't fit.
export function gain(res, mult = 1) {
  const lost = {}
  for (const [k, v0] of Object.entries(res)) {
    const v = v0 * mult
    if (!RES[k]) continue
    const cap = capOf(k)
    const room = Math.max(0, cap - S.res[k])
    const add = Math.min(room, v)
    S.res[k] += add
    if (v - add > 0.5) lost[k] = v - add
  }
  bus.emit('res')
  return lost
}
export const ammoTotal = () => S.res.pammo + S.res.rammo + S.res.shells

// ---------------------------------------------------------------- stations
export const stationSize = (st) => {
  const [w, d] = STATIONS[st.type].size
  return st.rot ? [d, w] : [w, d]
}
export const countType = (type, minLevel = 1) => S.stations.filter((s) => s.type === type && s.level >= minLevel).length
export const maxLevelOf = (type) => S.stations.filter((s) => s.type === type).reduce((a, s) => Math.max(a, s.level), 0)
export function reqMet(type) {
  const req = STATIONS[type].req
  if (!req) return true
  return Object.entries(req).every(([t, l]) => maxLevelOf(t) >= l)
}
export const slots = (st) => (st.level > 0 ? STATIONS[st.type].workers[st.level - 1] : 0)
export const workersOf = (st) => S.survivors.filter((s) => s.job === st.id)
export function assign(s, st) {
  if (st && workersOf(st).length >= slots(st)) return false
  s.job = st ? st.id : null
  bus.emit('change')
  if (st?.type === 'farm') completeGoal('assignFarm')
  return true
}
function discount(cost) {
  // Engineers in camp shave a bit off upgrades.
  let d = 0
  for (const s of S.survivors) if (s.status === 'ok' && OCCUPATIONS[s.occ].fx.discount) d = Math.max(d, OCCUPATIONS[s.occ].fx.discount)
  if (!d) return cost
  const out = {}
  for (const [k, v] of Object.entries(cost)) out[k] = k === 'cash' ? v : Math.ceil(v * (1 - d))
  return out
}
export function upgradeCost(st) {
  const d = STATIONS[st.type]
  if (st.level >= d.levels) return null
  return discount(d.cost[st.level])
}
export const buildCost = (type) => discount(STATIONS[type].cost[0])
export function newStation(type, x, z, rot = 0, level = 0) {
  const d = STATIONS[type]
  const st = {
    id: uid('st'),
    type,
    x,
    z,
    rot,
    level,
    building: level ? null : { to: 1, left: d.time[0], total: d.time[0] },
    progress: 0,
    orders: [],
    autoOn: true,
    module: false,
    limit: d.limit ? 200 : null,
    mode: type === 'ammo' ? 'auto' : null,
    targets: type === 'ammo' ? { pammo: 300, rammo: 160, shells: 90 } : null,
    maintain: !!REPAIR[type],
    trainSkill: 'auto',
  }
  S.stations.push(st)
  bus.emit('stations')
  return st
}
export function startUpgrade(st) {
  const d = STATIONS[st.type]
  const cost = upgradeCost(st)
  if (!cost || st.building || !pay(cost)) return false
  st.building = { to: st.level + 1, left: d.time[st.level], total: d.time[st.level] }
  bus.emit('stations')
  return true
}
export function demolish(st) {
  if (STATIONS[st.type].fixed) return
  for (const s of workersOf(st)) s.job = null
  const d = STATIONS[st.type]
  const refund = {}
  for (let i = 0; i < st.level; i++) for (const [k, v] of Object.entries(d.cost[i])) refund[k] = (refund[k] || 0) + Math.floor(v / 2)
  if (st.module) refund.module = 1
  for (const o of st.orders) if (o.paid) for (const [k, v] of Object.entries(o.paid)) refund[k] = (refund[k] || 0) + v
  gain(refund)
  S.stations = S.stations.filter((x) => x !== st)
  bus.emit('stations')
}
export function installModule(st) {
  const d = STATIONS[st.type]
  if (!d.auto || st.module || st.level < d.auto) return false
  if (!pay({ module: 1 })) return false
  st.module = true
  bus.emit('change')
  return true
}

// ---------------------------------------------------------------- orders
// Order kinds: recipe (repeat N, or keep a resource stocked), mod (fit to an item), repair.
export const recipesFor = (type) => RECIPES.filter((r) => r.station === type)
export const modsFor = (type) => Object.entries(MODS).filter(([, m]) => m.bench === type)
export function queueMax(st) {
  const q = STATIONS[st.type].queue
  return q ? q[Math.max(0, st.level - 1)] : 0
}
export function addOrder(st, o) {
  if (st.orders.length >= queueMax(st)) return 'Queue full'
  st.orders.push({ id: uid('o'), left: null, total: null, paid: null, ...o })
  bus.emit('change')
  return null
}
export function orderRecipe(st, recipeId, repeat = 1, keep = null) {
  const r = RECIPES.find((x) => x.id === recipeId)
  if (!r || r.lvl > st.level) return 'Needs a higher level bench'
  return addOrder(st, { kind: 'recipe', recipe: r.id, repeat, keep })
}
export function orderMod(st, modId, itemUid) {
  const M = MODS[modId]
  const it = itemOf(itemUid)
  if (!it || !M) return 'Nothing to fit'
  if (M.lvl > st.level) return `Needs level ${M.lvl}`
  if (it.mods?.length) return 'That item already has a mod'
  if (st.orders.some((o) => o.item === itemUid)) return 'Already queued'
  return addOrder(st, { kind: 'mod', mod: modId, item: itemUid, repeat: 1 })
}
export function orderRepair(st, itemUid) {
  const it = itemOf(itemUid)
  if (!it || it.cond >= 100) return 'Nothing to repair'
  if (st.orders.some((o) => o.item === itemUid)) return 'Already queued'
  return addOrder(st, { kind: 'repair', item: itemUid, repeat: 1 })
}
export function cancelOrder(st, oid) {
  const o = st.orders.find((x) => x.id === oid)
  if (!o) return
  if (o.paid) gain(o.paid)
  st.orders = st.orders.filter((x) => x !== o)
  bus.emit('change')
}
export function moveOrder(st, oid, dir) {
  const i = st.orders.findIndex((x) => x.id === oid)
  const j = i + dir
  if (i < 0 || j < 0 || j >= st.orders.length) return
  ;[st.orders[i], st.orders[j]] = [st.orders[j], st.orders[i]]
  bus.emit('change')
}
// Inputs and duration for one unit of an order.
export function orderSpec(o) {
  if (o.kind === 'recipe') {
    const r = RECIPES.find((x) => x.id === o.recipe)
    return { cost: r.in, time: r.time, recipe: r }
  }
  if (o.kind === 'mod') {
    const M = MODS[o.mod]
    return { cost: M.cost, time: M.time }
  }
  const it = itemOf(o.item)
  if (!it) return null
  return { cost: repairCost(it) || {}, time: repairTime(it) }
}
// Chance of each quality tier for an item crafted at st by its workers.
export function qualityOdds(st) {
  const ws = workersOf(st).filter((s) => s.status === 'ok')
  let craft = 3
  let perk = 0
  for (const s of ws) {
    craft = Math.max(craft, STATIONS[st.type].skill === 'tech' ? s.skills.tech : s.skills.craft)
    perk = Math.max(perk, (OCCUPATIONS[s.occ].fx.quality?.[st.type] || 0) + (s.traits.includes('steady') ? 1 : 0))
  }
  if (!ws.length) craft = 3 // automation makes standard parts
  const lv = st.level
  let pMaster = clamp(0.012 * (craft - 3) + perk * 0.05 + (lv - 1) * 0.02, 0, 0.3)
  let pFine = clamp(0.06 + 0.045 * (craft - 1) + perk * 0.1 + (lv - 1) * 0.06, 0, 0.7 - pMaster)
  let pCrude = clamp(0.3 - 0.08 * (craft - 1) - (lv - 1) * 0.1, 0, 0.3)
  return { crude: pCrude, fine: pFine, master: pMaster, standard: Math.max(0, 1 - pCrude - pFine - pMaster) }
}
export function rollQuality(st) {
  const o = qualityOdds(st)
  const r = Math.random()
  if (r < o.master) return 3
  if (r < o.master + o.fine) return 2
  if (r < o.master + o.fine + o.crude) return 0
  return 1
}

// ---------------------------------------------------------------- fence
export const fenceMax = () => FENCE[S.fence.level].hp
export function rebuildFence(keepHp = false) {
  const tiles = fenceTiles()
  const old = S.fence.hp || []
  S.fence.hp = tiles.map((_, i) => (keepHp && old[i] != null ? old[i] : fenceMax()))
  bus.emit('fence')
}
export function repairFenceCost() {
  let missing = 0
  const mx = fenceMax()
  for (const h of S.fence.hp) missing += mx - h
  const lv = S.fence.level
  const per = lv <= 1 ? { wood: 1 / 10 } : { metal: 1 / 12, scrap: 1 / 16 }
  const cost = {}
  for (const [k, v] of Object.entries(per)) if (missing > 0) cost[k] = Math.ceil(missing * v)
  return { missing, cost }
}
export function repairFence() {
  const { missing, cost } = repairFenceCost()
  if (!missing || !pay(cost)) return false
  S.fence.hp = S.fence.hp.map(() => fenceMax())
  bus.emit('fence')
  return true
}
export function fenceUpgradeCost() {
  const next = FENCE[S.fence.level + 1]
  if (!next) return null
  const k = perimeter() / 10
  const out = {}
  for (const [r, v] of Object.entries(next.per10)) out[r] = Math.ceil(v * k)
  return discount(out)
}
export function upgradeFence() {
  const next = FENCE[S.fence.level + 1]
  const cost = fenceUpgradeCost()
  if (!next || S.fence.building || !pay(cost)) return false
  S.fence.building = { left: next.time * (perimeter() / 128), total: next.time * (perimeter() / 128) }
  bus.emit('fence')
  return true
}

// ---------------------------------------------------------------- expansions
export function expansionCost(id) {
  return discount(EXPANSIONS.find((e) => e.id === id).cost)
}
export function startExpansion(id) {
  if (!expansionAvailable(id) || S.expanding) return false
  const X = EXPANSIONS.find((e) => e.id === id)
  if (!pay(expansionCost(id))) return false
  S.expanding = { id, left: X.time, total: X.time }
  S.expansions[id] = 'building'
  bus.emit('expansions')
  return true
}

// ---------------------------------------------------------------- log/goals
export function log(text, kind = '') {
  S.log.unshift({ t: S.time, text, kind })
  if (S.log.length > 80) S.log.length = 80
  bus.emit('log', { text, kind })
}
export function completeGoal(id) {
  if (!S || S.goals[id]) return
  S.goals[id] = 'done'
  const g = GOALS.find((x) => x.id === id)
  if (g) bus.emit('goal', g)
}
export function claimGoal(id) {
  if (S.goals[id] !== 'done') return
  const g = GOALS.find((x) => x.id === id)
  gain(g.reward)
  S.goals[id] = 'claimed'
  bus.emit('change')
}

// ---------------------------------------------------------------- morale
export function moraleMult() {
  if (!S) return 1
  return 0.8 + 0.4 * clamp(S.morale ?? 50, 0, 100) / 100
}
export function addMoraleEvent(text, amount, days = 1.5) {
  S.moraleEvents.push({ text, amount, until: S.time + days * DAY_MIN, start: S.time, days })
}

// ---------------------------------------------------------------- new game
export function newGame() {
  S = {
    version: 2,
    seed: Math.floor(Math.random() * 1e9),
    time: 7.5 * 60,
    speed: 1,
    res: Object.fromEntries(RES_KEYS.map((k) => [k, 0])),
    survivors: [],
    items: [],
    stations: [],
    expansions: {},
    expanding: null,
    fence: { level: 0, hp: [], building: null },
    nextRaid: null,
    raid: null,
    recruit: { next: 0, pending: null },
    market: null,
    looted: {},
    events: [],
    goals: {},
    stats: { kills: 0, runs: 0, deaths: 0, recruited: 0, raids: 0, crafted: 0, memorial: [] },
    morale: 60,
    moraleEvents: [],
    weather: { type: 'clear', until: 0 },
    log: [],
    over: false,
    created: Date.now(),
    saved: Date.now(),
    settings: { sound: true, quality: 'high', tilt: true, edgePan: true },
  }
  Object.assign(S.res, { food: 55, water: 60, meds: 4, wood: 110, scrap: 60, metal: 30, cloth: 25, parts: 8, electronics: 4, chemicals: 4, gunpowder: 0, fuel: 14, pammo: 70, rammo: 0, shells: 12, medkit: 2, molotov: 1, cash: 150 })
  const founders = [pick(['soldier', 'police', 'firefighter', 'guard']), pick(['farmer', 'chef', 'plumber']), pick(['carpenter', 'builder', 'mechanic']), pick(['nurse', 'doctor', 'paramedic'])]
  for (const occ of founders) S.survivors.push(makeSurvivor({ occ }))
  const sv = S.survivors
  const kit = [addItem('pistol', { cond: 82 }), addItem('bat', { cond: 70 }), addItem('pipe', { cond: 88 }), addItem('jacket', { cond: 76 }), addItem('flashlight'), addItem('crowbar', { q: 0, cond: 90 })]
  sv[0].equip.weapon = kit[0].uid
  sv[0].equip.armor = kit[3].uid
  sv[1].equip.weapon = kit[1].uid
  sv[2].equip.weapon = kit[2].uid
  sv[3].equip.weapon = kit[5].uid
  sv[3].equip.gear = kit[4].uid
  sv[0].util = 'molotov'
  sv[3].util = 'medkit'
  for (const s of sv) s.hp = survivorStats(s).maxHp
  // Starting camp inside the old lumber yard (40..72). The gate is south.
  const add = (type, x, z, level = 1, rot = 0) => newStation(type, x, z, rot, level)
  add('campfire', 54, 55)
  add('bunkhouse', 43, 43)
  const farm = add('farm', 62, 43)
  add('collector', 65, 57)
  add('storage', 43, 56)
  add('workbench', 52, 44)
  sv[1].job = farm.id
  S.goals.assignFarm = 'done'
  rebuildFence()
  return S
}

// ---------------------------------------------------------------- save/load
export function save() {
  if (!S || S.over) return false
  S.saved = Date.now()
  return store.set(SAVE_KEY, JSON.stringify(S))
}
export function hasSave() {
  const raw = store.get(SAVE_KEY)
  if (!raw) return null
  try {
    const d = JSON.parse(raw)
    return d && d.version === 2 && !d.over ? d : null
  } catch {
    return null
  }
}
export function load(data) {
  S = data
  for (const s of S.survivors) if (s.status === 'mission') s.status = 'ok'
  // Missions don't survive a reload. Unfinished expansions keep building.
  S.raid = null
  S.speed = 1
  for (const k of RES_KEYS) if (S.res[k] == null) S.res[k] = 0
  return S
}
export const wipeSave = () => store.del(SAVE_KEY)

export function killSurvivor(s, cause) {
  S.stats.deaths++
  S.stats.memorial.unshift({ name: s.name, occ: OCCUPATIONS[s.occ].name, day: day(), cause, kills: s.kills })
  if (cause.includes('run') || cause.includes('Left behind')) for (const k of Object.keys(s.equip)) if (s.equip[k]) removeItem(s.equip[k])
  S.survivors = S.survivors.filter((x) => x !== s)
  addMoraleEvent(`${s.first} died`, -15, 2)
  log(`${s.name} died. ${cause}.`, 'bad')
  bus.emit('death', s)
  if (!S.survivors.length) {
    S.over = true
    wipeSave()
    bus.emit('gameover')
  }
}
