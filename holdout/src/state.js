// Game state, survivor generation, derived stats and the camp economy.
import {
  RES, STOCK_KEYS, SKILLS, SKILL_KEYS, SKILL_MAX, xpForLevel, OCCUPATIONS, OCC_KEYS, TRAITS, TRAIT_KEYS,
  FIRST_NAMES, LAST_NAMES, ITEMS, STATIONS, FENCE, RECIPES, HORDES, GOALS, GAME_MIN_PER_SEC, DAY_MIN, LOCATIONS,
} from './data.js'
import { bus, uid, pick, rint, rand, chance, clamp, store, weighted } from './util.js'
import { randomLook } from './models.js'

export const SAVE_KEY = 'holdout.save.v1'
export let S = null // the live state
export const setState = (s) => (S = s)

// ---------------------------------------------------------------- base layout
export const BASE = { W: 48, H: 48, F0: 8, F1: 40, GATE: [23, 24, 25] }
// Perimeter tiles in a fixed order; fence hp is stored per index.
export const FENCE_TILES = (() => {
  const out = []
  const { F0, F1 } = BASE
  for (let x = F0; x <= F1; x++) out.push([x, F0])
  for (let z = F0 + 1; z <= F1; z++) out.push([F1, z])
  for (let x = F1 - 1; x >= F0; x--) out.push([x, F1])
  for (let z = F1 - 1; z > F0; z--) out.push([F0, z])
  return out
})()
export const isGate = (x, z) => z === BASE.F1 && BASE.GATE.includes(x)

// ---------------------------------------------------------------- clock
export const now = () => S.time
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
export function makeSurvivor(opts = {}) {
  const occ = opts.occ || pick(OCC_KEYS)
  const quality = opts.quality ?? 0 // 0..3 extra skill points spread around
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
  const traits = []
  const goods = TRAIT_KEYS.filter((t) => TRAITS[t].good)
  const bads = TRAIT_KEYS.filter((t) => !TRAITS[t].good)
  traits.push(pick(goods))
  if (chance(0.45)) traits.push(pick(bads))
  else if (chance(0.3)) {
    const t = pick(goods.filter((g) => g !== traits[0]))
    traits.push(t)
  }
  const first = opts.first || pick(FIRST_NAMES)
  const s = {
    id: uid('s'),
    name: `${first} ${opts.last || pick(LAST_NAMES)}`,
    first,
    occ,
    traits,
    skills,
    xp,
    look: randomLook(),
    hp: 100,
    status: 'ok',
    job: null,
    equip: { weapon: null, armor: null, gear: null },
    kills: 0,
    runs: 0,
    joined: S ? day() : 1,
    age: rint(19, 58),
  }
  s.hp = survivorStats(s).maxHp
  return s
}

export const getS = (id) => S.survivors.find((s) => s.id === id)
export const alive = () => S.survivors
export const inCamp = () => S.survivors.filter((s) => s.status === 'ok' || s.status === 'injured')
export const available = () => S.survivors.filter((s) => s.status === 'ok')
export const survivorLevel = (s) => Math.max(1, Math.round(SKILL_KEYS.reduce((a, k) => a + s.skills[k], 0) / 2.4))

function fxSum(s, key) {
  let v = OCCUPATIONS[s.occ].fx[key] || 0
  for (const t of s.traits) v += TRAITS[t].fx[key] || 0
  return v
}
export const itemOf = (uidv) => (uidv ? S.items.find((i) => i.uid === uidv) : null)
export const equipped = (s, slot) => {
  const it = itemOf(s.equip[slot])
  return it ? it.id : null
}

// Everything the combat and work code needs about a survivor.
export function survivorStats(s) {
  const wid = (S && equipped(s, 'weapon')) || 'fists'
  const aid = S && equipped(s, 'armor')
  const gid = S && equipped(s, 'gear')
  const W = ITEMS[wid]
  const A = aid ? ITEMS[aid] : null
  const Gd = gid ? ITEMS[gid] : null
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
  return {
    weaponId: wid,
    weapon: W,
    gun,
    maxHp: Math.round(100 + fxSum(s, 'hp') + (A ? A.hp : 0) + (sk.melee + sk.build) * 1.5),
    speed: 3.1 * (1 + fxSum(s, 'speed') + (Gd?.speed || 0) + (A?.speed || 0)),
    dmg: W.dmg * dmg,
    range: W.range * (gun ? 1 + 0.025 * (sk.ranged - 1) : 1),
    rate: W.rate * (gun ? 1 : 1 - 0.025 * (sk.melee - 1)),
    acc: gun ? clamp(0.55 + 0.04 * sk.ranged + fxSum(s, 'acc'), 0.3, 0.97) : 1,
    dr: A ? A.dr : 0,
    noise: W.noise,
    noiseMult: Math.max(0.2, 1 + fxSum(s, 'noise')),
    search: 1 + 0.08 * (sk.scavenge - 1) + (Gd?.search || 0),
    loot: 1 + 0.04 * (sk.scavenge - 1) + fxSum(s, 'loot') + (Gd?.loot || 0),
    dismantle: 1 + 0.06 * (sk.build - 1) + fxSum(s, 'dismantle') + (Gd?.dismantle || 0),
    picklock: !!(Gd?.picklock || fxSum(s, 'picklock')),
    medkit: !!Gd?.medkit,
    walkie: !!Gd?.walkie,
    revive: (OCCUPATIONS[s.occ].fx.reviveMult || 1) / (1 + 0.06 * (sk.medic - 1)),
    aura: (OCCUPATIONS[s.occ].fx.aura || 0) + (sk.medic >= 6 ? 0.4 : 0),
    carParts: fxSum(s, 'carParts'),
    xpMult: 1 + fxSum(s, 'xp'),
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

// How good a survivor is at a station's job, as a multiplier (≈0.7–2.2).
export function workEff(s, type) {
  const def = STATIONS[type]
  if (!def) return 0
  const sk = def.skill ? s.skills[def.skill] : 3
  let e = 0.62 + 0.08 * sk
  e *= 1 + (OCCUPATIONS[s.occ].fx.station?.[type] || 0)
  e *= 1 + fxSum(s, 'work')
  if (S.hungry) e *= 0.6
  if (s.status === 'injured') e = 0
  return e
}
export const bestFor = (type) => OCC_KEYS.filter((o) => OCCUPATIONS[o].stations?.includes(type)).map((o) => OCCUPATIONS[o].name)

// ---------------------------------------------------------------- items
export function addItem(id) {
  const it = { uid: uid('i'), id }
  S.items.push(it)
  return it
}
export const ownerOf = (itemUid) => S.survivors.find((s) => Object.values(s.equip).includes(itemUid))
export function equip(s, itemUid) {
  const it = itemOf(itemUid)
  if (!it) return
  const slot = ITEMS[it.id].slot
  const prev = ownerOf(itemUid)
  if (prev) prev.equip[slot] = null
  s.equip[slot] = itemUid
  const st = survivorStats(s)
  s.hp = Math.min(s.hp, st.maxHp)
  bus.emit('change')
}
export function unequip(s, slot) {
  s.equip[slot] = null
  s.hp = Math.min(s.hp, survivorStats(s).maxHp)
  bus.emit('change')
}
export function removeItem(itemUid) {
  const o = ownerOf(itemUid)
  if (o) for (const k of Object.keys(o.equip)) if (o.equip[k] === itemUid) o.equip[k] = null
  S.items = S.items.filter((i) => i.uid !== itemUid)
}

// ---------------------------------------------------------------- resources
export function storageCap() {
  let cap = 150
  for (const st of S.stations) if (st.type === 'storage') cap += STATIONS.storage.cap[st.level - 1] || 0
  return cap
}
export function bedCount() {
  let n = 0
  for (const st of S.stations) if (st.type === 'bunkhouse' && st.level > 0) n += STATIONS.bunkhouse.beds[st.level - 1]
  return n
}
export const canAfford = (cost) => Object.entries(cost).every(([k, v]) => (S.res[k] || 0) >= v)
export function pay(cost) {
  if (!canAfford(cost)) return false
  for (const [k, v] of Object.entries(cost)) S.res[k] -= v
  bus.emit('res')
  return true
}
// Add resources, clamped to storage. Returns what was lost to full storage.
export function gain(res, mult = 1) {
  const cap = storageCap()
  const lost = {}
  for (const [k, v0] of Object.entries(res)) {
    const v = v0 * mult
    if (k === 'cash') {
      S.res.cash += v
      continue
    }
    const room = Math.max(0, cap - S.res[k])
    const add = Math.min(room, v)
    S.res[k] += add
    if (v - add > 0.5) lost[k] = v - add
  }
  bus.emit('res')
  return lost
}

// ---------------------------------------------------------------- stations
export const stationDef = (st) => STATIONS[st.type]
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

// Power: generators supply, automated stations/turrets/floodlights draw in list order.
export function powerInfo() {
  let supply = 0
  for (const st of S.stations) {
    if (st.type !== 'generator' || st.level < 1 || st.building) continue
    const op = workersOf(st).filter((s) => s.status === 'ok')[0]
    let p = STATIONS.generator.power[st.level - 1]
    if (op) p *= 1 + (OCCUPATIONS[op.occ].fx.station?.generator || 0) + 0.03 * op.skills.tech
    supply += p
  }
  if (S.res.fuel < 0.05) supply = 0
  let demand = 0
  const powered = new Set()
  let left = supply
  for (const st of S.stations) {
    const need = powerNeed(st)
    if (!need) continue
    demand += need
    if (left >= need) {
      left -= need
      powered.add(st.id)
    }
  }
  return { supply: Math.round(supply * 10) / 10, demand, used: supply - left, powered }
}
export function powerNeed(st) {
  if (st.building || st.level < 1) return 0
  const d = STATIONS[st.type]
  if (d.auto && st.level >= d.auto && st.autoOn !== false) return d.autoPower
  if (st.type === 'turret' || st.type === 'floodlight') return d.power
  return 0
}

// Work rate for a station: sum of worker efficiency plus automation.
export function stationRate(st, pinfo) {
  if (st.building || st.level < 1) return 0
  const d = STATIONS[st.type]
  let r = 0
  for (const s of workersOf(st)) if (s.status === 'ok') r += workEff(s, st.type)
  if (d.auto && st.level >= d.auto && st.autoOn !== false && pinfo.powered.has(st.id)) r += d.autoRate
  return r
}
export const isAutomated = (st, pinfo) => {
  const d = STATIONS[st.type]
  return !!(d.auto && st.level >= d.auto && st.autoOn !== false && pinfo.powered.has(st.id))
}

export function kitchenSaving() {
  let best = 0
  for (const st of S.stations) {
    if (st.type !== 'kitchen' || st.level < 1 || st.building) continue
    const base = STATIONS.kitchen.saving[st.level - 1]
    let e = 0
    for (const s of workersOf(st)) if (s.status === 'ok') e += workEff(s, 'kitchen')
    best = Math.max(best, clamp(base * e, 0, 0.6))
  }
  return best
}

export function constructSpeed() {
  // Idle survivors in camp pitch in on construction.
  let sp = 0.35
  for (const s of S.survivors) {
    if (s.status !== 'ok' || s.job) continue
    sp += 0.3 * (0.62 + 0.08 * s.skills.build) * (1 + (OCCUPATIONS[s.occ].fx.construct || 0))
  }
  return Math.min(3, sp)
}

export function placeStation(type, x, z, rot = 0) {
  const d = STATIONS[type]
  const st = { id: uid('st'), type, x, z, rot, level: 0, building: { to: 1, left: d.time[0], total: d.time[0] }, progress: 0, queue: [], autoOn: true, trainSkill: 'auto' }
  S.stations.push(st)
  bus.emit('stations')
  return st
}
export function upgradeCost(st) {
  const d = STATIONS[st.type]
  if (st.level >= d.levels) return null
  return d.cost[st.level]
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
  // refund half of what was spent
  const d = STATIONS[st.type]
  const refund = {}
  for (let i = 0; i < st.level; i++) for (const [k, v] of Object.entries(d.cost[i])) refund[k] = (refund[k] || 0) + Math.floor(v / 2)
  gain(refund)
  S.stations = S.stations.filter((x) => x !== st)
  bus.emit('stations')
}

// ---------------------------------------------------------------- recipes
export const recipesFor = (type) => RECIPES.filter((r) => r.station === type)
export function queueCraft(st, recipe) {
  const qmax = STATIONS[st.type].queue?.[st.level - 1] || 0
  if (st.queue.length >= qmax) return 'Queue full'
  if (recipe.lvl > st.level) return `Needs level ${recipe.lvl}`
  if (!pay(recipe.in)) return 'Not enough resources'
  st.queue.push({ id: recipe.id, left: recipe.time, total: recipe.time })
  bus.emit('change')
  return null
}
export function cancelCraft(st, i) {
  const q = st.queue[i]
  if (!q) return
  const r = RECIPES.find((x) => x.id === q.id)
  gain(r.in)
  st.queue.splice(i, 1)
  bus.emit('change')
}

// ---------------------------------------------------------------- fence
export const fenceMax = () => FENCE[S.fence.level].hp
export function fenceHp(i) {
  return S.fence.hp[i]
}
export function repairCost() {
  let missing = 0
  const mx = fenceMax()
  for (const h of S.fence.hp) missing += mx - h
  const lv = S.fence.level
  const per = lv <= 1 ? { wood: 1 / 12 } : { metal: 1 / 14, wood: 1 / 30 }
  const cost = {}
  for (const [k, v] of Object.entries(per)) if (missing > 0) cost[k] = Math.ceil(missing * v)
  return { missing, cost }
}
export function repairFence() {
  const { missing, cost } = repairCost()
  if (!missing || !pay(cost)) return false
  S.fence.hp = S.fence.hp.map(() => fenceMax())
  bus.emit('fence')
  return true
}
export function upgradeFence() {
  const next = FENCE[S.fence.level + 1]
  if (!next || S.fence.building || !pay(next.cost)) return false
  S.fence.building = { left: next.time, total: next.time }
  bus.emit('fence')
  return true
}

// ---------------------------------------------------------------- log/goals
export function log(text, kind = '') {
  S.log.unshift({ t: S.time, text, kind })
  if (S.log.length > 60) S.log.length = 60
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
function checkGoals() {
  if (S.survivors.length >= 10) completeGoal('pop10')
  if (S.survivors.length >= 20) completeGoal('pop20')
  if (day() >= 7) completeGoal('day7')
  if (day() >= 30) completeGoal('day30')
  if (S.fence.level >= 1) completeGoal('fence2')
  if (countType('filter')) completeGoal('buildFilter')
  if (countType('lumber') || countType('scrapyard')) completeGoal('buildLumber')
  if (countType('generator')) completeGoal('generator')
}

// ---------------------------------------------------------------- hordes & recruits
export function scheduleRaid(first = false) {
  const d = day()
  let sizeIdx
  if (first) sizeIdx = 0
  else if (d < 3) sizeIdx = 0
  else if (d < 6) sizeIdx = chance(0.6) ? 0 : 1
  else if (d < 12) sizeIdx = chance(0.5) ? 1 : 2
  else sizeIdx = chance(0.55) ? 2 : 3
  const H = HORDES[sizeIdx]
  const count = rint(H.min, H.max) + Math.floor(d / 3)
  const gap = first ? 0 : rand(18, 30) * 60
  const at = first ? 22 * 60 : S.time + gap
  S.nextRaid = { at, size: sizeIdx, count, warned: false }
}
export function raidIntel() {
  const towers = countType('watchtower')
  const r = S.nextRaid
  if (!r) return null
  const H = HORDES[r.size]
  return { in: r.at - S.time, name: towers ? H.name : 'Unknown horde', count: towers ? r.count : null, size: r.size, known: !!towers }
}

export function scheduleRecruit() {
  let mult = 1
  for (const st of S.stations) {
    if (st.type === 'radio' && st.level > 0 && !st.building) {
      mult = Math.min(mult, STATIONS.radio.recruit[st.level - 1])
    }
  }
  const radio = S.stations.find((s) => s.type === 'radio' && s.level > 0)
  if (radio) {
    const op = workersOf(radio).find((s) => s.status === 'ok')
    if (op) mult *= 1 / (1 + 0.25 * workEff(op, 'radio'))
  }
  S.recruit.next = S.time + rand(8, 14) * 60 * mult
}
function recruitQuality() {
  const r = maxLevelOf('radio')
  return Math.min(4, Math.floor(day() / 6) + r)
}

// ---------------------------------------------------------------- market
export function restockMarket() {
  const radio = maxLevelOf('radio')
  const pool = Object.entries(ITEMS).filter(([id]) => id !== 'fists')
  const stock = []
  const n = 6 + radio
  for (let i = 0; i < n; i++) {
    const pickFrom = pool.map(([id, it]) => ({ id, w: [6, 3.5, 1.4 + radio * 0.5, 0.4 + radio * 0.4, 0.1][RARITY_RANK[it.rarity]] }))
    const e = weighted(pickFrom)
    stock.push({ kind: 'item', id: e.id, price: Math.round(ITEMS[e.id].value * rand(1.05, 1.35)) })
  }
  const bundles = ['food', 'water', 'wood', 'metal', 'cloth', 'parts', 'fuel', 'ammo', 'meds']
  for (const r of bundles) {
    const qty = r === 'ammo' ? 50 : r === 'parts' || r === 'meds' ? 5 : 25
    stock.push({ kind: 'res', res: r, qty, price: Math.round(RES[r].sell * qty * rand(2.2, 2.8)) })
  }
  S.market = { day: day(), stock }
}
const RARITY_RANK = { common: 0, uncommon: 1, rare: 2, epic: 3, legendary: 4 }
export function sellMult() {
  let m = 1
  for (const s of S.survivors) if (s.status === 'ok' && OCCUPATIONS[s.occ].fx.market) m = Math.max(m, 1 + OCCUPATIONS[s.occ].fx.market)
  return m
}
export const itemSellPrice = (id) => Math.round(ITEMS[id].value * 0.45 * sellMult())
export const resSellPrice = (r, qty) => Math.round(RES[r].sell * qty * sellMult())

// ---------------------------------------------------------------- new game
export function newGame() {
  S = {
    version: 1,
    seed: Math.floor(Math.random() * 1e9),
    time: 7.5 * 60,
    speed: 1,
    res: { food: 45, water: 45, wood: 90, metal: 35, cloth: 20, parts: 6, fuel: 10, ammo: 60, meds: 4, cash: 150 },
    survivors: [],
    items: [],
    stations: [],
    fence: { level: 0, hp: FENCE_TILES.map(() => FENCE[0].hp), building: null },
    nextRaid: null,
    raid: null,
    recruit: { next: 0, pending: null },
    market: null,
    looted: {},
    goals: {},
    stats: { kills: 0, runs: 0, deaths: 0, recruited: 0, raids: 0, memorial: [] },
    log: [],
    over: false,
    created: Date.now(),
    saved: Date.now(),
    settings: { sound: true, quality: 'high' },
    tutorial: 0,
  }
  // Four founders with complementary jobs.
  const founders = [pick(['soldier', 'police', 'firefighter', 'guard']), pick(['farmer', 'chef', 'plumber']), pick(['carpenter', 'builder', 'mechanic']), pick(['nurse', 'doctor', 'paramedic'])]
  for (const occ of founders) S.survivors.push(makeSurvivor({ occ }))
  const sv = S.survivors
  // Starter kit
  const kit = [addItem('pistol'), addItem('bat'), addItem('pipe'), addItem('jacket'), addItem('flashlight')]
  sv[0].equip.weapon = kit[0].uid
  sv[0].equip.armor = kit[3].uid
  sv[2].equip.weapon = kit[2].uid
  sv[1].equip.weapon = kit[1].uid
  sv[3].equip.gear = kit[4].uid
  for (const s of sv) s.hp = survivorStats(s).maxHp

  const add = (type, x, z, level = 1, rot = 0) => {
    const st = { id: uid('st'), type, x, z, rot, level, building: null, progress: 0, queue: [], autoOn: true, trainSkill: 'auto' }
    S.stations.push(st)
    return st
  }
  add('campfire', 23, 23)
  add('bunkhouse', 17, 15)
  const farm = add('farm', 28, 15)
  add('collector', 32, 21)
  add('storage', 14, 22)
  add('workbench', 17, 29)
  sv[1].job = farm.id
  S.goals.assignFarm = 'done'
  scheduleRaid(true)
  S.recruit.next = S.time + 6 * 60
  restockMarket()
  log('Day 1. The four of you made it to the old lumber yard. It will have to do.', 'story')
  return S
}

// ---------------------------------------------------------------- save/load
export function save() {
  if (!S || S.over) return false
  S.saved = Date.now()
  const ok = store.set(SAVE_KEY, JSON.stringify(S))
  return ok
}
export function hasSave() {
  const raw = store.get(SAVE_KEY)
  if (!raw) return null
  try {
    const d = JSON.parse(raw)
    return d && d.version === 1 && !d.over ? d : null
  } catch {
    return null
  }
}
export function load(data) {
  S = data
  // Missions don't survive a reload: bring the squad home.
  for (const s of S.survivors) if (s.status === 'mission') s.status = 'ok'
  S.raid = null
  S.speed = 1
  return S
}
export const wipeSave = () => store.del(SAVE_KEY)

// ---------------------------------------------------------------- economy tick
let pinfoCache = null
export const power = () => pinfoCache || powerInfo()

export function econTick(dt, opts = {}) {
  if (!S || S.over) return
  const prevDay = day()
  const prevHour = hour()
  S.time += dt * GAME_MIN_PER_SEC
  const pinfo = (pinfoCache = powerInfo())
  const secPerDay = DAY_MIN / GAME_MIN_PER_SEC

  // --- consumption
  const eaters = S.survivors.length
  let foodNeed = 0
  let waterNeed = 0
  for (const s of S.survivors) {
    const glut = s.traits.includes('glutton') ? 1.5 : 1
    foodNeed += (2 * glut) / secPerDay
    waterNeed += 2.4 / secPerDay
  }
  foodNeed *= 1 - kitchenSaving()
  S.res.food = Math.max(0, S.res.food - foodNeed * dt)
  S.res.water = Math.max(0, S.res.water - waterNeed * dt)
  const hungry = eaters > 0 && (S.res.food <= 0.01 || S.res.water <= 0.01)
  if (hungry && !S.hungry) log(S.res.water <= 0.01 ? 'Out of water! Everyone is weakening.' : 'Out of food! Everyone is weakening.', 'bad')
  S.hungry = hungry

  // --- generator fuel
  if (pinfo.supply > 0 && pinfo.used > 0) {
    for (const st of S.stations) {
      if (st.type !== 'generator' || st.level < 1 || st.building) continue
      const burn = STATIONS.generator.burn[st.level - 1]
      S.res.fuel = Math.max(0, S.res.fuel - (dt / burn) * (pinfo.used / pinfo.supply))
    }
  }

  // --- construction
  const cs = constructSpeed()
  for (const st of S.stations) {
    if (!st.building) continue
    st.building.left -= dt * cs
    if (st.building.left <= 0) {
      st.level = st.building.to
      st.building = null
      bus.emit('built', st)
      log(`${STATIONS[st.type].name} ${st.level > 1 ? `upgraded to level ${st.level}` : 'built'}.`, 'good')
      for (const s of S.survivors) if (s.status === 'ok' && !s.job) gainXP(s, 'build', 4)
      if (st.type === 'generator') completeGoal('generator')
    }
  }
  if (S.fence.building) {
    S.fence.building.left -= dt * cs
    if (S.fence.building.left <= 0) {
      S.fence.level++
      S.fence.building = null
      S.fence.hp = S.fence.hp.map(() => fenceMax())
      log(`Perimeter upgraded: ${FENCE[S.fence.level].name}.`, 'good')
      bus.emit('fence')
    }
  }

  // --- stations
  let anyAuto = false
  for (const st of S.stations) {
    if (st.building || st.level < 1) continue
    const d = STATIONS[st.type]
    const rate = stationRate(st, pinfo)
    if (isAutomated(st, pinfo)) anyAuto = true
    st.active = rate > 0
    st.stalled = null
    // passive output
    if (d.passive) {
      for (const [k, arr] of Object.entries(d.passive)) gain({ [k]: (arr[st.level - 1] / secPerDay) * dt })
      st.active = true
    }
    // worker xp
    for (const s of workersOf(st)) if (s.status === 'ok' && d.skill) gainXP(s, d.skill, 0.22 * dt)
    // continuous recipes
    if (d.recipe && st.type !== 'infirmary' && rate > 0) {
      const R = d.recipe
      const tm = Array.isArray(R.time) ? R.time[st.level - 1] : R.time
      st.progress += (dt * rate) / tm
      while (st.progress >= 1) {
        if (!canAfford(R.in)) {
          st.stalled = 'Missing ' + Object.keys(R.in).filter((k) => S.res[k] < R.in[k]).map((k) => RES[k].name.toLowerCase()).join(', ')
          st.progress = 1
          break
        }
        if (Object.keys(R.out).every((k) => S.res[k] >= storageCap())) {
          st.stalled = 'Storage full'
          st.progress = 1
          break
        }
        for (const [k, v] of Object.entries(R.in)) S.res[k] -= v
        gain(R.out)
        if (R.bonus) for (const [k, p] of Object.entries(R.bonus)) if (chance(p)) gain({ [k]: 1 })
        st.progress -= 1
        bus.emit('produced', st, R.out)
      }
    }
    // crafting queues
    if (d.queue && st.queue.length) {
      if (rate > 0) {
        const q = st.queue[0]
        q.left -= dt * rate
        if (q.left <= 0) {
          const r = RECIPES.find((x) => x.id === q.id)
          st.queue.shift()
          if (r.item) {
            addItem(r.item)
            log(`${STATIONS[st.type].name} finished a ${ITEMS[r.item].name}.`, 'good')
          } else gain(r.out)
          bus.emit('crafted', st, r)
          completeGoal('craft')
          for (const s of workersOf(st)) gainXP(s, 'craft', 6)
        }
      } else st.stalled = 'Needs a worker'
    }
    if (st.type === 'infirmary') tickInfirmary(st, dt, pinfo)
    if (st.type === 'training') tickTraining(st, dt)
    if (d.workers[st.level - 1] && !workersOf(st).length && !d.passive && !isAutomated(st, pinfo) && st.type !== 'generator' && st.type !== 'watchtower' && st.type !== 'radio')
      st.stalled = st.stalled || 'No workers'
  }
  if (anyAuto) completeGoal('automate')

  // --- natural recovery
  for (const s of S.survivors) {
    if (s.status === 'mission') continue
    const st = survivorStats(s)
    if (S.hungry) s.hp = Math.max(1, s.hp - dt * 0.12)
    else if (s.status === 'ok') s.hp = Math.min(st.maxHp, s.hp + dt * 0.12)
    else if (s.status === 'injured') {
      s.hp = Math.min(st.maxHp, s.hp + dt * 0.05)
      if (s.hp >= st.maxHp * 0.7) {
        s.status = 'ok'
        log(`${s.first} has recovered.`, 'good')
        bus.emit('change')
      }
    }
  }

  // --- recruits
  if (!opts.offline) {
    const r = S.recruit
    if (r.pending && S.time > r.pending.expires) {
      log(`${r.pending.s.first} got tired of waiting at the gate and moved on.`, '')
      r.pending = null
      bus.emit('recruitGone')
      scheduleRecruit()
    }
    if (!r.pending && S.time >= r.next && !S.raid) {
      const s = makeSurvivor({ quality: recruitQuality() })
      r.pending = { s, expires: S.time + 5 * 60 }
      bus.emit('recruit', s)
      log(`A survivor is waiting at the gate: ${s.name}, ${OCCUPATIONS[s.occ].name}.`, 'story')
    }
  } else if (S.time >= S.recruit.next) scheduleRecruit()

  // --- hordes
  const R = S.nextRaid
  if (R && !S.raid) {
    if (!R.warned && R.at - S.time <= 60) {
      R.warned = true
      bus.emit('raidWarn', raidIntel())
    }
    if (S.time >= R.at) {
      if (opts.offline) {
        autoResolveRaid(R, true)
        scheduleRaid()
      } else bus.emit('raidStart', R)
    }
  }

  // --- day rollover
  if (day() !== prevDay) {
    restockMarket()
    for (const k of Object.keys(S.looted)) if (S.looted[k] <= day()) delete S.looted[k]
    log(`Day ${day()} begins. ${S.survivors.length} survivors in camp.`, 'story')
    bus.emit('newDay', day())
  }
  if (Math.floor(prevHour) !== Math.floor(hour())) checkGoals()
}

function tickInfirmary(st, dt, pinfo) {
  const d = STATIONS.infirmary
  const medics = workersOf(st).filter((s) => s.status === 'ok')
  let power = 0
  for (const m of medics) power += workEff(m, 'infirmary') * (OCCUPATIONS[m.occ].fx.healMult || 1)
  const patients = S.survivors.filter((s) => s.status === 'injured' || (s.status === 'ok' && s.hp < survivorStats(s).maxHp * 0.6)).slice(0, d.beds[st.level - 1])
  st.patients = patients.map((p) => p.id)
  if (patients.length && power > 0) {
    const per = (d.heal[st.level - 1] * power * dt) / patients.length
    for (const p of patients) p.hp = Math.min(survivorStats(p).maxHp, p.hp + per * 2.2)
    for (const m of medics) gainXP(m, 'medic', 0.35 * dt)
    st.active = true
  } else if (power > 0) {
    const R = d.recipe
    st.progress += (dt * power) / R.time
    if (st.progress >= 1) {
      if (canAfford(R.in)) {
        for (const [k, v] of Object.entries(R.in)) S.res[k] -= v
        gain(R.out)
        bus.emit('produced', st, R.out)
        st.progress = 0
      } else {
        st.progress = 1
        st.stalled = 'Idle: needs cloth + water for meds'
      }
    }
    st.active = true
  }
}

function tickTraining(st, dt) {
  const d = STATIONS.training
  const trainees = workersOf(st).filter((s) => s.status === 'ok')
  let teach = 0
  for (const s of trainees) teach = Math.max(teach, OCCUPATIONS[s.occ].fx.teach || 0)
  for (const s of trainees) {
    const skill = st.trainSkill !== 'auto' ? st.trainSkill : s.skills.melee <= s.skills.ranged ? 'melee' : 'ranged'
    gainXP(s, skill, d.xpRate[st.level - 1] * (1 + teach) * dt)
  }
  st.active = trainees.length > 0
}

// Horde hits the camp while nobody is watching (e.g. during a supply run).
export function autoResolveRaid(R, offline = false) {
  const defenders = S.survivors.filter((s) => s.status === 'ok')
  let def = 0
  for (const s of defenders) {
    const st = survivorStats(s)
    const dps = st.dmg / st.rate
    def += dps * (st.gun ? (S.res.ammo > 5 ? 1.4 : 0.3) : 0.8) * (s.hp / st.maxHp)
    if (s.job && S.stations.find((x) => x.id === s.job)?.type === 'watchtower') def += dps * 0.6
  }
  const pinfo = powerInfo()
  for (const st of S.stations) if (st.type === 'turret' && pinfo.powered.has(st.id) && S.res.ammo > 5) def += STATIONS.turret.dmg[st.level - 1] / STATIONS.turret.rate[st.level - 1]
  const fence = FENCE[S.fence.level].hp * 0.08
  const horde = R.count * (10 + day() * 0.6)
  const ratio = (def * 3 + fence) / Math.max(1, horde)
  const report = { count: R.count, injured: [], dead: [], lost: {}, fenceDmg: 0, ratio, auto: true }
  S.res.ammo = Math.max(0, S.res.ammo - Math.min(S.res.ammo, R.count * 3))
  const dmgFrac = clamp(1.3 - ratio, 0.05, 1)
  S.fence.hp = S.fence.hp.map((h) => Math.max(0, h - fenceMax() * dmgFrac * rand(0.2, 0.9)))
  report.fenceDmg = Math.round(dmgFrac * 100)
  if (ratio < 1.1) {
    for (const s of defenders) {
      if (chance(clamp(1.1 - ratio, 0, 0.8))) {
        s.status = 'injured'
        s.hp = Math.max(1, survivorStats(s).maxHp * 0.15)
        s.job = s.job // keep assignment
        report.injured.push(s.first)
      }
    }
  }
  if (ratio < 0.55) {
    for (const k of ['food', 'water', 'meds', 'ammo', 'fuel']) {
      const l = Math.floor(S.res[k] * rand(0.2, 0.45))
      S.res[k] -= l
      report.lost[k] = l
    }
    if (chance(0.5) && defenders.length) {
      const victim = pick(defenders)
      killSurvivor(victim, 'Killed defending the camp')
      report.dead.push(victim.first)
    }
  }
  S.stats.raids++
  S.stats.kills += Math.round(R.count * clamp(ratio, 0.3, 1))
  completeGoal('surviveHorde')
  log(`${offline ? 'While you were away, a' : 'A'} horde of ${R.count} hit the camp. ${report.injured.length ? report.injured.length + ' injured.' : 'No one was hurt.'}${report.dead.length ? ' ' + report.dead.join(', ') + ' did not make it.' : ''}`, report.dead.length ? 'bad' : '')
  bus.emit('raidResolved', report)
  return report
}

export function killSurvivor(s, cause) {
  S.stats.deaths++
  S.stats.memorial.unshift({ name: s.name, occ: OCCUPATIONS[s.occ].name, day: day(), cause, kills: s.kills })
  for (const k of Object.keys(s.equip)) {
    // Gear on a dead survivor is lost with them on runs; in camp it stays.
    if (cause.includes('run')) removeItem(s.equip[k])
  }
  S.survivors = S.survivors.filter((x) => x !== s)
  log(`${s.name} died. ${cause}.`, 'bad')
  bus.emit('death', s)
  if (!S.survivors.length) {
    S.over = true
    wipeSave()
    bus.emit('gameover')
  }
}

export function acceptRecruit() {
  const p = S.recruit.pending
  if (!p) return false
  if (S.survivors.length >= bedCount()) return false
  p.s.joined = day()
  S.survivors.push(p.s)
  S.recruit.pending = null
  S.stats.recruited++
  completeGoal('recruit')
  log(`${p.s.name} joined the camp.`, 'good')
  scheduleRecruit()
  bus.emit('change')
  bus.emit('recruitJoined', p.s)
  return true
}
export function declineRecruit() {
  const p = S.recruit.pending
  if (!p) return
  S.recruit.pending = null
  log(`You turned ${p.s.first} away.`, '')
  scheduleRecruit()
  bus.emit('recruitGone')
}

export function stockTotal() {
  return STOCK_KEYS.reduce((a, k) => a + S.res[k], 0)
}
export { DAY_MIN }
