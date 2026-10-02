// The camp simulation: consumption, production chains, crafting orders,
// repairs, power and automation, construction, expansions, morale, weather,
// recruits, distress calls, hordes and the black market.
import {
  RES, STOCK_KEYS, AMMO_KEYS, ITEMS, QUALITY, MODS, STATIONS, FENCE, RECIPES, EXPANSIONS, HORDES, OCCUPATIONS, LOCATIONS,
  GAME_MIN_PER_SEC, DAY_MIN, SEC_PER_DAY, SEC_PER_HOUR, UPKEEP, RARITY, ALT_RECIPES, INFECTION, OUTPOST, SIGNAL, BELTS,
  PEN_FENCE, PEN_RAID, ANIMAL_COST,
} from './data.js'
const SIGNAL_PHASES = SIGNAL.length
import {
  S, day, hour, bounds, log, gain, pay, canAfford, capOf, stationSize, workersOf, workEff, gainXP, completeGoal,
  survivorStats, itemOf, addItem, repairCost, orderSpec, rollQuality, rebuildFence, fenceMax, makeSurvivor, killSurvivor,
  bedCount, countType, maxLevelOf, addMoraleEvent, itemName, moraleMult, available, getS, isUnlocked, hasFlag, feedSignal,
  researchDone, finishResearch, coreBoost, treatInfection, season, seasonIdx, campTier, perkOf,
  outpostYield, abandonOutpost, addVehicle, outpostAt, ownerOf, removeItem, itemValue,
} from './state.js'
import { deed, dailyDeeds } from './deeds.js'
import { isLooted } from './state.js'
import { bus, pick, rint, rand, chance, clamp, weighted } from '../core/util.js'
import { tickLinks, beltBonus, belted, pulled, outCap } from './belts.js'
import { tickStory } from './story.js'
import { tickRecon } from './recon.js'

// ---------------------------------------------------------------- power
// One grid for the whole camp. Sources: steam engines (wood, or coal at a
// third of the rate), diesel generators (fuel), solar panels (daylight),
// wind turbines (weather) and battery banks (spare power stored earlier).
// Users: turrets and floodlights first, then machines and automated
// stations in build order. A machine without power is hand-cranked at half
// speed. Sun and wind are used first, then fuel; the banks cover the rest
// and charge from whatever sun and wind is left over.
export const HAND_RATE = 0.5
export const FUEL_K = { coal: 3, wood: 1 }
export function powerNeed(st) {
  if (st.building || st.level < 1) return 0
  const d = STATIONS[st.type]
  if (st.type === 'turret' || st.type === 'floodlight') return st.autoOn === false ? 0 : d.power
  if (d.node) return d.draw * (researchDone('efficiency') ? 0.75 : 1)
  const eff = researchDone('efficiency') ? 0.75 : 1
  let n = 0
  if (d.auto && st.level >= d.auto && st.module && st.autoOn !== false) n += d.autoPower * Math.pow(1 + coreBoost() * (st.cores || 0), 1.5) * eff
  if (d.machine && st.powerOn !== false && (n > 0 || workersOf(st).some((s) => s.status === 'ok'))) n += d.machine * eff
  return n
}
// Belts draw a little for every metre (more on the faster tiers), at least
// 0.1 each; with no power a belt stops. They come right after the defences:
// nothing on the line moves without them.
export const linkPower = (l) => Math.max(0.1, l.len * (BELTS[l.tier]?.power || 0.01)) * (researchDone('efficiency') ? 0.75 : 1)
const powerPrio = (st) => (st.type === 'turret' || st.type === 'floodlight' ? 0 : STATIONS[st.type].node ? 0.5 : STATIONS[st.type].machine ? 1 : 2)
export function solarOutput(track = 0) {
  const h = hour()
  if (h < 6 || h > 19.5) return 0
  const s = Math.sin(((h - 6) / 13.5) * Math.PI)
  // a mount that turns with the sun catches the low light at either end of the day
  const sunK = track ? Math.pow(s, 0.45) : s
  const w = { clear: 1, hazy: 0.8, overcast: 0.45, rain: 0.3, fog: 0.4, snow: 0.35 }[S.weather.type] ?? 1
  return sunK * w
}
// Wind strength 0..1: weather sets the mean, slow gusts move it about.
export function windOutput() {
  const w = { clear: 0.5, hazy: 0.42, overcast: 0.72, rain: 0.88, snow: 1, fog: 0.18 }[S.weather.type] ?? 0.5
  const g = 0.85 + 0.15 * Math.sin(S.time / 37) * Math.sin(S.time / 11 + 1.3)
  return clamp(w * g, 0, 1)
}
// What a steam engine would burn now: coal first (it lasts three times as
// long), then wood; null when it has neither.
export function boilerFuel(st) {
  const has = (k) => (st.buf?.in?.[k] || 0) > 0.05 || (S.res[k] || 0) - survivalReserve(k) > 0.05
  for (const k of STATIONS.boiler.fuels) if (has(k)) return st.fuelPick && st.fuelPick !== k && has(st.fuelPick) ? st.fuelPick : k
  return null
}
// Off-grid power. A rider on each bike makes power while they pedal.
export function pedalPower(st) {
  if (st.halt || st.building || st.level < 1) return 0
  const d = STATIONS[st.type]
  let p = 0
  for (const s of workersOf(st)) if (s.status === 'ok') p += d.pedal * Math.min(1.3, workEff(s, st.type))
  return p
}
// What the camp's animals leave behind, in loads a day (a goat four hens'
// worth), and the scraps an active cookhouse throws out.
export function dungPerDay() {
  let n = 0
  for (const st of S.stations) {
    const D = STATIONS[st.type]
    if (!D.livestock || st.building || st.level < 1) continue
    n += flockOf(st) * (D.livestock === 'goat' ? 1.2 : 0.3)
  }
  return n
}
export const scrapsPerDay = () => S.stations.filter((st) => st.type === 'kitchen' && st.level > 0 && !st.building && workersOf(st).length).length * 2
// A digester runs as hard as it is fed (eased: the gas holder fills and empties slowly).
export const digesterPower = (st) => (st.halt || st.building || st.level < 1 ? 0 : STATIONS[st.type].gas[st.level - 1] * (st.fed ?? 0))
// Digestate on the farm plots: a fed digester grows a tenth more.
export const digestate = () => S.stations.some((st) => STATIONS[st.type].gas && (st.fed ?? 0) > 0.5)
export function sourcePower(st) {
  const d = STATIONS[st.type]
  const op = workersOf(st).filter((s) => s.status === 'ok')[0]
  let p = d.power[st.level - 1]
  if (op) p *= 1 + (OCCUPATIONS[op.occ].fx.station?.[st.type] || 0) + 0.03 * op.skills.tech + (st.type === 'boiler' ? 0.15 : 0)
  return p
}
export function powerInfo() {
  let renew = 0
  let fueled = 0
  let charge = 0
  let room = 0
  let battRate = 0
  const srcs = []
  for (const st of S.stations) {
    if (st.building || st.level < 1) continue
    const d = STATIONS[st.type]
    if (st.type === 'generator' || st.type === 'boiler') {
      if (st.powerOn === false) continue
      const lit = st.type === 'generator' ? S.res.fuel > 0.05 || (st.buf?.in?.fuel || 0) > 0.05 : !!boilerFuel(st)
      if (!lit) continue
      const p = sourcePower(st)
      fueled += p
      srcs.push({ st, p })
    } else if (d.solar) renew += d.solar[st.level - 1] * solarOutput(d.track?.[st.level - 1])
    else if (st.type === 'wind') renew += d.wind[st.level - 1] * windOutput()
    else if (d.pedal) renew += pedalPower(st)
    else if (d.gas) renew += digesterPower(st)
    else if (st.type === 'battery') {
      const cap = d.store[st.level - 1]
      const c = clamp(st.charge || 0, 0, cap)
      charge += c
      room += cap - c
      if (c > 0.01) battRate += d.rate[st.level - 1]
    }
  }
  const supply = renew + fueled + battRate
  const users = []
  for (const st of S.stations) {
    const need = powerNeed(st)
    if (need > 0) users.push({ id: st.id, need, prio: powerPrio(st) })
  }
  let beltNeed = 0
  for (const l of S.links || []) {
    const need = linkPower(l)
    beltNeed += need
    users.push({ id: l.id, need, prio: 0.5 })
  }
  users.sort((a, b) => a.prio - b.prio)
  let demand = 0
  let left = supply
  const powered = new Set()
  for (const u of users) {
    demand += u.need
    if (left >= u.need - 1e-6) {
      left -= u.need
      powered.add(u.id)
    }
  }
  const used = supply - left
  const fromRenew = Math.min(used, renew)
  const fromFuel = Math.min(used - fromRenew, fueled)
  const fromBatt = Math.max(0, used - fromRenew - fromFuel)
  return { supply: Math.round(supply * 10) / 10, renew, fueled, gen: fueled, demand, used, powered, beltNeed, srcs, fromRenew, fromFuel, fromBatt, spare: renew - fromRenew, charge, room, battRate, load: fueled > 0 ? fromFuel / fueled : 0 }
}
let pinfoCache = null
export const power = () => pinfoCache || powerInfo()
// Burn fuel for the share of power that came from engines, and move charge
// in and out of the battery banks (store and rate in power for an hour).
function tickPower(pinfo, dt) {
  for (const { st, p } of pinfo.srcs) {
    const d = STATIONS[st.type]
    st.out = p * pinfo.load
    if (pinfo.load <= 0) continue
    let burn = (dt / d.burn[st.level - 1]) * pinfo.load
    const k = st.type === 'generator' ? 'fuel' : boilerFuel(st)
    if (!k) continue
    burn /= FUEL_K[k] || 1
    st.burning = k
    const b = st.buf?.in
    if (b?.[k] > 0) {
      const t = Math.min(b[k], burn)
      b[k] -= t
      burn -= t
    }
    S.res[k] = Math.max(0, (S.res[k] || 0) - burn)
  }
  const banks = S.stations.filter((st) => st.type === 'battery' && st.level > 0 && !st.building)
  if (!banks.length) return
  const hrs = dt / SEC_PER_HOUR
  let out = pinfo.fromBatt * hrs
  let into = Math.min(pinfo.spare, banks.reduce((a, st) => a + STATIONS.battery.rate[st.level - 1], 0)) * hrs * 0.9
  for (const st of banks) {
    const cap = STATIONS.battery.store[st.level - 1]
    st.charge = clamp(st.charge || 0, 0, cap)
    const take = pinfo.charge > 0 ? (out * st.charge) / pinfo.charge : 0
    const give = pinfo.room > 0 ? (into * (cap - st.charge)) / pinfo.room : 0
    st.charge = clamp(st.charge - take + give, 0, cap)
    st.flow = (give - take) / Math.max(hrs, 1e-6)
  }
}

// A digester takes its share of the dung and scraps; short of them, it can
// be fed food (never the camp's last few days of it).
function tickDigester(st, d, share, dt) {
  const need = d.feed[st.level - 1]
  let got = Math.min(need, share)
  st.dung = got
  st.foodFed = 0
  if (got < need && st.useFood) {
    const want = ((need - got) / SEC_PER_DAY) * dt
    const take = Math.min(want, Math.max(0, (S.res.food || 0) - survivalReserve('food')))
    if (take > 0) {
      S.res.food -= take
      st.foodFed = dt > 0 ? (take / dt) * SEC_PER_DAY : 0
      got += st.foodFed
    }
  }
  const f = clamp(got / need, 0, 1)
  st.fed = (st.fed ?? 0) + (f - (st.fed ?? 0)) * Math.min(1, dt / 30)
  st.active = st.fed > 0.05
  if (f < 0.98) st.stalled = got < 0.05 ? 'Nothing to digest' : 'Short of dung and scraps'
}
// A solar lamp charges in the sun and burns through the dark.
export const lampDark = () => {
  const h = hour()
  return h < 6.3 || h > 19.2
}
function tickLamp(st, d, dt) {
  const L = d.lamp
  const hrs = (dt / SEC_PER_DAY) * 24
  const sun = solarOutput()
  let c = st.charge ?? 0.5
  if (sun > 0.02) c = Math.min(1, c + (sun * hrs) / L.charge)
  const dark = lampDark()
  if (dark && c > 0) c = Math.max(0, c - hrs / L.burn)
  st.charge = c
  st.lit = dark && c > 0.001
  st.active = st.lit
  if (dark && !st.lit) st.stalled = 'Battery flat'
}

export function stationRate(st, pinfo) {
  if (st.building || st.level < 1 || st.halt) return 0
  const d = STATIONS[st.type]
  let r = 0
  for (const s of workersOf(st)) if (s.status === 'ok') r += workEff(s, st.type)
  if (d.machine && !pinfo.powered.has(st.id)) r *= HAND_RATE
  if (S.disrepair) r *= UPKEEP.slow
  if (isAutomated(st, pinfo)) r += d.autoRate * (hasFlag('autoBoost') ? 1.5 : 1) * (1 + coreBoost() * (st.cores || 0))
  // animals: the more of them, the more they give
  if (d.livestock) r *= flockFactor(st)
  if (st.type === 'farm' && digestate()) r *= 1.1
  return r
}
// ---------------------------------------------------------------- livestock
export const flockMax = (st) => STATIONS[st.type].flock?.[Math.max(0, st.level - 1)] || 0
export const flockOf = (st) => st.flock ?? STATIONS[st.type].start ?? 0
export const flockFactor = (st) => (flockMax(st) ? Math.min(1, flockOf(st) / flockMax(st)) : 1)
// Food the camp can eat: fresh eggs and milk as well as stores.
export const foodStock = () => (S.res.food || 0) + (S.res.eggs || 0) + (S.res.milk || 0)
// How likely the infected are to get at a pen tonight: the fence, staffed
// watchtowers and lit floodlights all keep them off.
export function penRisk(st, pinfo = power()) {
  const F = PEN_FENCE[st.pen || 0]
  let k = PEN_RAID * F.guard
  for (const t of S.stations) {
    if (t.level < 1 || t.building) continue
    if (t.type === 'watchtower' && workersOf(t).some((s) => s.status === 'ok')) k *= 0.7
    if (t.type === 'floodlight' && pinfo.powered.has(t.id)) k *= 0.85
    // a charged solar lamp close by
    if (STATIONS[t.type].lamp && (t.lit || (t.charge ?? 0.5) > 0.3) && Math.hypot(t.x - st.x, t.z - st.z) < 12) k *= 0.85
  }
  return clamp(k, 0, 0.95)
}
const animal = (st, n) => {
  const w = STATIONS[st.type].livestock === 'goat' ? 'goat' : 'hen'
  return `${n} ${n === 1 ? w : w + 's'}`
}
// Put a fence round a pen (or a better one).
export function buildPenFence(st) {
  const n = (st.pen || 0) + 1
  const F = PEN_FENCE[n]
  if (!F || !pay(F.cost)) return false
  st.pen = n
  st.penHp = F.hp
  log(`${F.name} up round the ${STATIONS[st.type].name}.`, 'good')
  bus.emit('change')
  return true
}
export const mendCost = (st) => {
  const F = PEN_FENCE[st.pen || 0]
  return F?.cost ? Object.fromEntries(Object.entries(F.cost).map(([k, v]) => [k, Math.ceil(v / 2)])) : null
}
export function mendPenFence(st) {
  const F = PEN_FENCE[st.pen || 0]
  const c = mendCost(st)
  if (!c || (st.penHp ?? F.hp) >= F.hp || !pay(c)) return false
  st.penHp = F.hp
  bus.emit('change')
  return true
}
// Buy in another animal from a passing trader.
export const animalCost = (st) => ANIMAL_COST[STATIONS[st.type].livestock]
export function buyAnimal(st) {
  if (flockOf(st) >= flockMax(st) || !pay(animalCost(st))) return false
  st.flock = flockOf(st) + 1
  log(`A trader on the road sold the camp a ${STATIONS[st.type].livestock}: ${animal(st, st.flock)} in the ${STATIONS[st.type].name} now.`, 'good')
  bus.emit('change')
  return true
}
// The small hours: the infected come sniffing round the animals.
function penNight(pinfo) {
  for (const st of S.stations) {
    if (!STATIONS[st.type].livestock || st.level < 1 || st.building || !flockOf(st)) continue
    // a new pen gets a couple of quiet nights to put a fence up
    if (day() < (st.grace || 0)) continue
    if (Math.random() >= penRisk(st, pinfo)) continue
    const nm = STATIONS[st.type].name
    let lost = 0
    if (st.pen) {
      st.penHp = (st.penHp ?? PEN_FENCE[st.pen].hp) - rint(1, 2)
      if (st.penHp <= 0) {
        log(`The infected tore down the ${PEN_FENCE[st.pen].name.toLowerCase()} round the ${nm} in the night.`, 'bad')
        st.pen--
        st.penHp = st.pen ? PEN_FENCE[st.pen].hp : 0
        lost = 1
      } else log(`Something battered at the fence round the ${nm} in the night. It held. Mend it before it gives.`)
    } else lost = Math.min(flockOf(st), STATIONS[st.type].livestock === 'goat' ? 1 : rint(1, 2))
    if (lost) {
      st.flock = Math.max(0, flockOf(st) - lost)
      log(`The infected got into the ${nm} in the night: ${animal(st, lost)} lost.${st.pen ? '' : ' Fence it in.'}`, 'bad')
      addMoraleEvent(`Lost ${animal(st, lost)} in the night`, -3, 1)
    }
    bus.emit('penRaid', st, lost)
  }
}
// Each morning: a fed, cared-for flock grows.
function breedFlocks() {
  for (const st of S.stations) {
    if (!STATIONS[st.type].livestock || st.level < 1 || st.building) continue
    st.flock = flockOf(st)
    const keeper = workersOf(st).some((s) => s.status === 'ok')
    if (st.flock >= 2 && st.flock < flockMax(st) && keeper && foodStock() > S.survivors.length * 2 && chance(0.45)) {
      st.flock++
      log(`${STATIONS[st.type].livestock === 'goat' ? 'A kid was born in the Goat Pen' : 'Chicks hatched in the Chicken Coop'}: ${animal(st, st.flock)} now.`, 'good')
      bus.emit('flock', st)
    } else if (st.flock > flockMax(st)) st.flock = flockMax(st)
  }
}
export const isAutomated = (st, pinfo) => {
  const d = STATIONS[st.type]
  return !!(d.auto && st.level >= d.auto && st.module && st.autoOn !== false && pinfo.powered.has(st.id))
}

// ---------------------------------------------------------------- consumption
export function kitchenSaving() {
  let best = 0
  for (const st of S.stations) {
    if (st.type !== 'kitchen' || st.level < 1 || st.building || !st.active) continue
    const base = STATIONS.kitchen.saving[st.level - 1]
    let e = 0
    for (const s of workersOf(st)) if (s.status === 'ok') e += workEff(s, 'kitchen')
    best = Math.max(best, clamp(base * e, 0, 0.6))
  }
  return best
}
export function dailyNeeds() {
  let food = 0
  let water = 0
  for (const s of S.survivors) {
    food += s.traits.includes('glutton') ? 3 : 2
    water += 2.4
  }
  return { food: food * (1 - kitchenSaving()) * (hasFlag('rations') ? 0.85 : 1), water: water * season().water }
}

// Winter heat: wood per survivor a day, or coal or fuel at a third of that.
export function heatNeed() {
  const k = season().heat
  return k ? { wood: k * S.survivors.length, coal: (k / 3) * S.survivors.length, fuel: (k / 3) * S.survivors.length } : null
}

// Upkeep a day: survivors wear through clothes, bedding and bandages;
// stations need patching, bolts once upgraded, spare parts if they are
// machines or power plants. Short on any of it, the camp falls into
// disrepair: everything works 15% slower and morale sags until restocked.
export function upkeepNeeds() {
  const out = {}
  const add = (o, k = 1) => {
    for (const [r, v] of Object.entries(o)) out[r] = (out[r] || 0) + v * k
  }
  add(UPKEEP.person, S.survivors.filter((s) => s.status !== 'outpost').length)
  for (const st of S.stations) {
    const d = STATIONS[st.type]
    if (st.level < 1 || d.fixed || d.node || st.type === 'mast') continue
    add(UPKEEP.station, st.level)
    if (st.level >= 2) add(UPKEEP.upgraded, st.level - 1)
    if (d.machine || d.power) add(UPKEEP.machine, st.level)
  }
  return out
}

// Idle survivors (no job) help build, or forage when nothing is going up.
export const FORAGE = { wood: 6, scrap: 4 }
export function constructSpeed() {
  let sp = 0.35
  for (const s of S.survivors) {
    if (s.status !== 'ok' || s.job) continue
    sp += 0.3 * (0.62 + 0.08 * s.skills.build) * (1 + (OCCUPATIONS[s.occ].fx.construct || 0) + (s.perks || []).reduce((a, p) => a + (perkOf(p)?.fx.construct || 0), 0))
  }
  return Math.min(3.2, sp * moraleMult())
}

// ---------------------------------------------------------------- production rates (for UI)
// Per in-game day, accounting for workers and automation right now.
export function stationFlow(st, pinfo = power()) {
  const d = STATIONS[st.type]
  const rate = stationRate(st, pinfo)
  const flow = {}
  const add = (k, v) => (flow[k] = (flow[k] || 0) + v)
  if (st.building || st.level < 1) return flow
  if (d.passive) for (const [k, arr] of Object.entries(d.passive)) add(k, arr[st.level - 1] * passiveMult(st))
  if (d.recipe && rate > 0) {
    const R = activeSingle(st)
    const tm = Array.isArray(R.time) ? R.time[st.level - 1] : R.time
    const cyc = (rate * beltBonus(st, R) * seasonMult(st) * SEC_PER_DAY) / tm
    for (const [k, v] of Object.entries(R.out)) add(k, v * cyc)
    for (const [k, v] of Object.entries(R.in)) add(k, -v * cyc)
  }
  if (d.recipes && rate > 0 && st.curMode && d.recipes[st.curMode] && st.active) {
    const m = activeRecipe(st, st.curMode)
    const cyc = (rate * beltBonus(st, m) * SEC_PER_DAY) / m.time[st.level - 1]
    for (const [k, v] of Object.entries(m.out)) add(k, v * cyc)
    for (const [k, v] of Object.entries(m.in)) add(k, -v * cyc)
  }
  if (st.type === 'kitchen' && st.active) add('wood', -d.burn.wood)
  if ((st.type === 'generator' || st.type === 'boiler') && pinfo.load > 0 && pinfo.srcs.some((x) => x.st === st)) {
    const k = st.type === 'generator' ? 'fuel' : boilerFuel(st)
    if (k) add(k, -(SEC_PER_DAY / d.burn[st.level - 1]) * pinfo.load / (FUEL_K[k] || 1))
  }
  return flow
}
export function campFlow() {
  const pinfo = power()
  const total = {}
  for (const st of S.stations) for (const [k, v] of Object.entries(stationFlow(st, pinfo))) total[k] = (total[k] || 0) + v
  if (!S.expanding && !S.fence.building && !S.stations.some((st) => st.building)) {
    const idle = S.survivors.filter((s) => s.status === 'ok' && !s.job).length
    for (const [k, v] of Object.entries(FORAGE)) if (idle) total[k] = (total[k] || 0) + v * idle
  }
  const n = dailyNeeds()
  // fresh eggs and milk are eaten first
  let need = n.food
  for (const k of ['eggs', 'milk']) {
    const t = Math.min(Math.max(0, total[k] || 0), need)
    if (t > 0) total[k] -= t
    need -= t
  }
  total.food = (total.food || 0) - need
  total.water = (total.water || 0) - n.water
  for (const [k, v] of Object.entries(upkeepNeeds())) total[k] = (total[k] || 0) - v
  const heat = heatNeed()
  if (heat) {
    if (S.res.wood > 1) total.wood = (total.wood || 0) - heat.wood
    else if (S.res.coal > 1) total.coal = (total.coal || 0) - heat.coal
    else total.fuel = (total.fuel || 0) - heat.fuel
  }
  return total
}
// Where one resource comes from and goes, per day: every station that makes
// or uses it, the foragers, mouths to feed, upkeep, winter heat and the
// outposts' convoys. { sources: [{ key, name, v, st? }], sinks: [...] }.
export function resourceFlow(k) {
  const pinfo = power()
  const sources = []
  const sinks = []
  const add = (list, key, name, v, extra = {}) => {
    if (Math.abs(v) < 0.02) return
    const o = list.find((x) => x.key === key)
    if (o) o.v += v
    else list.push({ key, name, v, ...extra })
  }
  for (const st of S.stations) {
    const f = stationFlow(st, pinfo)[k] || 0
    const name = STATIONS[st.type].name
    if (f > 0) add(sources, st.id, name, f, { st })
    else if (f < 0) add(sinks, st.id, name, -f, { st })
  }
  if (!S.expanding && !S.fence.building && !S.stations.some((st) => st.building)) {
    const idle = S.survivors.filter((s) => s.status === 'ok' && !s.job).length
    if (idle && FORAGE[k]) add(sources, 'forage', `Foraging (${idle} without a job)`, FORAGE[k] * idle, { kind: 'people' })
  }
  for (const o of S.outposts || []) {
    const y = outpostYield(o)[k]
    if (y) add(sources, 'post' + o.locId, `${o.name} outpost`, y, { kind: 'outpost' })
  }
  const n = dailyNeeds()
  if (k === 'food' || k === 'eggs' || k === 'milk') {
    // the camp eats eggs and milk first, then the stores
    let need = n.food
    for (const f of ['eggs', 'milk']) {
      let made = 0
      for (const st of S.stations) made += Math.max(0, stationFlow(st, pinfo)[f] || 0)
      const t = Math.min(made, need)
      if (f === k) add(sinks, 'eat', `Eaten (${S.survivors.length} people)`, t, { kind: 'people' })
      need -= t
    }
    if (k === 'food') add(sinks, 'eat', `Eaten (${S.survivors.length} people)`, need, { kind: 'people' })
  }
  if (k === 'water') add(sinks, 'drink', `Drunk (${S.survivors.length} people)`, n.water, { kind: 'people' })
  const up = upkeepNeeds()[k]
  if (up) add(sinks, 'upkeep', 'Upkeep: wear and tear', up, { kind: 'upkeep' })
  const heat = heatNeed()
  if (heat) {
    const fuelK = S.res.wood > 1 ? 'wood' : S.res.coal > 1 ? 'coal' : 'fuel'
    if (fuelK === k) add(sinks, 'heat', 'Winter heat', heat[k], { kind: 'heat' })
  }
  sources.sort((a, b) => b.v - a.v)
  sinks.sort((a, b) => b.v - a.v)
  const made = sources.reduce((a, x) => a + x.v, 0)
  const used = sinks.reduce((a, x) => a + x.v, 0)
  return { k, sources, sinks, made, used, net: made - used, stock: S.res[k] || 0, cap: capOf(k) }
}
export const passiveMult = (st) => (S.weather.type === 'rain' ? 2 : S.weather.type === 'snow' ? 0.6 : 1) * (st.type === 'collector' ? season().collector : 1)
export const seasonMult = (st) => (st.type === 'farm' ? season().farm : 1)

// ---------------------------------------------------------------- econ tick
export function econTick(dt, opts = {}) {
  if (!S || S.over) return
  const prevDay = day()
  const prevHour = hour()
  S.time += dt * GAME_MIN_PER_SEC
  const pinfo = (pinfoCache = powerInfo())

  // ---- weather changes at dawn and sometimes midday
  if (S.time >= S.weather.until && !opts.offline) rollWeather()

  // ---- food & water
  const needs = dailyNeeds()
  // fresh eggs and milk go first; then the stores
  let eat = (needs.food / SEC_PER_DAY) * dt
  for (const k of ['eggs', 'milk']) {
    const t = Math.min(S.res[k] || 0, eat)
    if (t <= 0) continue
    S.res[k] -= t
    eat -= t
    S.freshAte = (S.freshAte || 0) + t
  }
  S.res.food = Math.max(0, S.res.food - eat)
  S.res.water = Math.max(0, S.res.water - (needs.water / SEC_PER_DAY) * dt)
  const heat = heatNeed()
  let cold = false
  if (heat) {
    const w = (heat.wood / SEC_PER_DAY) * dt
    const c = (heat.coal / SEC_PER_DAY) * dt
    const f = (heat.fuel / SEC_PER_DAY) * dt
    if (S.res.wood >= w) S.res.wood -= w
    else if (S.res.coal >= c) S.res.coal -= c
    else if (S.res.fuel >= f) S.res.fuel -= f
    else cold = S.survivors.length > 0
  }
  if (cold && !S.cold) log('The camp is out of wood, coal and fuel. Everyone is freezing.', 'bad')
  S.cold = cold
  // upkeep, a little at a time
  let short = null
  for (const [k, v] of Object.entries(upkeepNeeds())) {
    const need = (v / SEC_PER_DAY) * dt
    if ((S.res[k] || 0) >= need) S.res[k] -= need
    else {
      S.res[k] = 0
      short = short || k
    }
  }
  if (short && !S.disrepair) log(`Out of ${RES[short].name.toLowerCase()} for upkeep. Things are falling apart: everyone works slower until it is restocked.`, 'bad')
  if (!short && S.disrepair) log('Upkeep is covered again. The camp is back in good repair.', 'good')
  S.disrepair = short
  const hungry = S.survivors.length > 0 && (foodStock() <= 0.01 || S.res.water <= 0.01)
  if (hungry && !S.hungry) log(S.res.water <= 0.01 ? 'Out of water! Everyone is weakening.' : 'Out of food! Everyone is weakening.', 'bad')
  S.hungry = hungry

  // ---- engines, generators and battery banks
  tickPower(pinfo, dt)

  // ---- construction, wall upgrades, expansions
  const cs = constructSpeed()
  for (const st of S.stations) {
    if (!st.building) continue
    st.building.left -= dt * cs
    if (st.building.left <= 0) {
      st.level = st.building.to
      st.building = null
      if (STATIONS[st.type].livestock && st.flock == null) {
        st.flock = STATIONS[st.type].start
        st.grace = day() + 2
      }
      bus.emit('built', st)
      S.stats.built = (S.stats.built || 0) + 1
      log(`${STATIONS[st.type].name} ${st.level > 1 ? `upgraded to level ${st.level}` : 'built'}.`, 'good')
      for (const s of S.survivors)
        if (s.status === 'ok' && !s.job) {
          gainXP(s, 'build', 5)
          deed(s, 'built')
        }
      if (st.type === 'generator') completeGoal('generator')
      if (st.type === 'forge') completeGoal('buildForge')
      if ((st.type === 'chemlab' || st.type === 'ammo') && countType('chemlab') && countType('ammo')) completeGoal('chemlab')
    }
  }
  if (S.fence.building) {
    S.fence.building.left -= dt * cs
    if (S.fence.building.left <= 0) {
      S.fence.level++
      S.fence.building = null
      rebuildFence()
      log(`Perimeter upgraded: ${FENCE[S.fence.level].name}.`, 'good')
      if (S.fence.level >= 1) completeGoal('fence2')
    }
  }
  if (S.expanding) {
    S.expanding.left -= dt * cs
    if (S.expanding.left <= 0) finishExpansion()
  }
  // With nothing to build, people without a job forage the yard for
  // deadwood and junk, so a camp can never run completely dry.
  if (!S.expanding && !S.fence.building && !S.stations.some((st) => st.building)) {
    const idle = S.survivors.filter((s) => s.status === 'ok' && !s.job).length
    if (idle) gain({ wood: (FORAGE.wood * idle * dt) / SEC_PER_DAY, scrap: (FORAGE.scrap * idle * dt) / SEC_PER_DAY })
  }

  // ---- stations
  let anyAuto = false
  // the dung and scraps, shared between the digesters
  const digs = S.stations.filter((st) => STATIONS[st.type].gas && !st.building && st.level > 0 && !st.halt)
  const feedShare = digs.length ? (dungPerDay() + scrapsPerDay()) / digs.length : 0
  for (const st of S.stations) {
    st.active = false
    st.stalled = null
    if (st.building || st.level < 1) continue
    const d = STATIONS[st.type]
    if (st.halt) {
      st.stalled = 'Paused'
      continue
    }
    const rate = stationRate(st, pinfo)
    if (isAutomated(st, pinfo)) anyAuto = true
    if (d.passive) {
      const mult = passiveMult(st)
      for (const [k, arr] of Object.entries(d.passive)) {
        const v = (arr[st.level - 1] * mult / SEC_PER_DAY) * dt
        if (belted(st, k)) st.buf.out[k] = Math.min(outCap(k), (st.buf.out[k] || 0) + v)
        else gain({ [k]: v })
      }
      st.active = true
    }
    if (d.solar) st.active = solarOutput(d.track?.[st.level - 1]) > 0.05
    if (d.pedal) st.active = pedalPower(st) > 0
    if (d.gas) tickDigester(st, d, feedShare, dt)
    if (d.lamp) tickLamp(st, d, dt)
    for (const s of workersOf(st)) if (s.status === 'ok' && d.skill) gainXP(s, d.skill, 0.2 * dt)
    if (d.recipe) tickProcessor(st, activeSingle(st), rate, dt)
    else if (st.type === 'recycler') tickRecycler(st, d, rate, dt)
    else if (d.recipes) tickMulti(st, d, rate, dt)
    else if (st.type === 'infirmary') tickInfirmary(st, rate, dt)
    else if (st.type === 'research') tickResearch(st, rate, dt)
    else if (d.queue) tickBench(st, rate, dt)
    if (st.type === 'training') tickTraining(st, dt)
    if (st.type === 'mast') {
      feedSignal(st)
      st.active = true
    }
    if (st.type === 'kitchen') {
      st.active = rate > 0
      if (st.active) {
        const need = (d.burn.wood / SEC_PER_DAY) * dt
        if (S.res.wood >= need) S.res.wood -= need
        else {
          st.active = false
          st.stalled = 'No wood for the stove'
        }
      }
    }
    if (st.type === 'generator' || st.type === 'boiler') {
      st.active = pinfo.load > 0 && pinfo.srcs.some((x) => x.st === st)
      if (!pinfo.srcs.some((x) => x.st === st)) st.stalled = st.powerOn === false ? 'Switched off' : st.type === 'generator' ? 'No fuel' : 'No wood or coal'
    }
    if (st.type === 'wind') st.active = windOutput() > 0.1
    if (st.type === 'battery') st.active = Math.abs(st.flow || 0) > 0.05
    if (st.type === 'radio') st.active = workersOf(st).some((s) => s.status === 'ok')
    if (st.type === 'watchtower') st.active = workersOf(st).some((s) => s.status === 'ok')
    if (d.workers[st.level - 1] && !workersOf(st).length && !isAutomated(st, pinfo) && !['generator', 'boiler', 'watchtower', 'radio', 'training'].includes(st.type)) st.stalled = st.stalled || 'No workers'
  }
  if (anyAuto) completeGoal('automate')
  tickLinks(dt, pinfo)

  // ---- recovery
  for (const s of S.survivors) {
    if (s.status === 'mission') continue
    const st = survivorStats(s)
    // hunger and cold wear the healthy down; the injured still mend, slowly
    if ((S.hungry || S.cold) && s.status !== 'injured') s.hp = Math.max(1, s.hp - dt * (S.hungry && S.cold ? 0.15 : 0.1))
    else if (s.status === 'ok' || s.status === 'outpost') s.hp = Math.min(st.maxHp, s.hp + dt * 0.1)
    // out scouting: no rest
    else if (s.status === 'injured') {
      s.hp = Math.min(st.maxHp, s.hp + dt * (S.hungry || S.cold ? 0.012 : 0.035))
      if (s.hp >= st.maxHp * 0.7) {
        s.status = 'ok'
        log(`${s.first} has recovered.`, 'good')
        bus.emit('change')
      }
    }
  }

  if (!opts.offline) tickInfection(dt)
  tickMorale(dt)

  // ---- recruits & distress calls
  if (!opts.offline) {
    const r = S.recruit
    if (r.pending && S.time > r.pending.expires) {
      log(`${r.pending.s.first} got tired of waiting at the gate and moved on.`)
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
  // Offline catch-up runs with the clock held (see main.js): production
  // only, no events, hordes or new days.
  if (opts.offline) return
  S.events = S.events.filter((e) => e.expires > S.time)
  if (S.time >= (S.nextEvent || 0)) spawnEvent()

  // ---- hordes
  const R = S.nextRaid
  if (R && !S.raid) {
    if (!R.warned && R.at - S.time <= 60) {
      R.warned = true
      bus.emit('raidWarn', raidIntel())
    }
    if (S.time >= R.at) bus.emit('raidStart', R)
  }

  // ---- the small hours: the infected sniff round the animals
  if (prevHour < 2 && hour() >= 2) penNight(pinfo)
  // ---- the city: the horde wanders, scouts come home, counts drift
  tickRecon(dt * GAME_MIN_PER_SEC, day() !== prevDay)

  // ---- day rollover
  if (day() !== prevDay) {
    // a camp eating fresh is a happier one
    if ((S.freshAte || 0) > S.survivors.length * 0.6) addMoraleEvent('Fresh eggs and milk', 3, 1)
    S.freshAte = 0
    breedFlocks()
    dailyDeeds()
    restockMarket()
    for (const k of Object.keys(S.looted)) if (!isLooted(k)) delete S.looted[k]
    log(`Day ${day()} begins. ${S.survivors.length} survivors in camp.`, 'story')
    if (seasonIdx() !== seasonIdx(day() - 1)) {
      const Z = season()
      log(`${Z.name} has come. ${Z.desc}`, 'story')
      bus.emit('season', Z)
    }
    if (S.morale < 18) desertion()
    tickOutposts()
    S.stats.peak = Math.max(S.stats.peak || 0, S.survivors.length)
    reinfest()
    bus.emit('newDay', day())
  }
  if (Math.floor(prevHour) !== Math.floor(hour())) {
    checkGoals()
    sampleHistory()
    tickStory()
  }
}

// ---------------------------------------------------------------- outposts
// Each morning the convoys come home with the outposts' goods. Now and then
// the dead attack one: a strong garrison holds; a weak one takes losses, and
// an outpost worn down to nothing is lost.
function tickOutposts() {
  for (const o of [...(S.outposts || [])]) {
    const crew = o.crew.map(getS).filter((s) => s && s.status === 'outpost')
    o.crew = crew.map((s) => s.id)
    if (!crew.length) {
      abandonOutpost(o, `${o.name} has nobody left to hold it. The outpost is lost.`)
      continue
    }
    const got = outpostYield(o)
    const lost = gain(got)
    const brought = Object.entries(got).filter(([k, v]) => v - (lost[k] || 0) >= 1)
    o.last = { day: day(), got: Object.fromEntries(brought.map(([k, v]) => [k, Math.round(v - (lost[k] || 0))])) }
    // attacks
    if (chance(0.07 + threatLevel() / 260)) {
      let def = 0
      for (const s of crew) {
        const st = survivorStats(s)
        def += (st.dmg / st.rate) * (st.gun ? 1.3 : 0.9) + st.maxHp * 0.12
      }
      def *= OUTPOST.defense[o.level - 1]
      const atk = 18 + threatLevel() * 4 * rand(0.6, 1.4)
      const ratio = def / atk
      if (ratio >= 1) {
        o.hp = Math.max(10, o.hp - rand(0, 12))
        log(`The dead came for ${o.name}. ${crew.map((s) => s.first).join(' and ')} held them off.`, 'good')
        for (const s of crew) gainXP(s, survivorStats(s).gun ? 'ranged' : 'melee', 20)
      } else {
        o.hp -= rand(25, 55) * (1.2 - ratio)
        const hurt = pick(crew)
        hurt.hp = Math.max(1, hurt.hp - survivorStats(hurt).maxHp * rand(0.3, 0.7))
        if (chance(0.25 * (1 - ratio))) {
          killSurvivor(hurt, `Killed holding the ${o.name} outpost`)
        } else log(`${o.name} was overrun for a while. ${hurt.first} was hurt.`, 'bad')
        if (o.hp <= 0) abandonOutpost(o, `${o.name} fell. The survivors made it home.`)
      }
      bus.emit('outposts')
    } else o.hp = Math.min(100, o.hp + 8)
  }
}

// ---------------------------------------------------------------- history
// Stock levels sampled every in-game hour for the last four days (graphs).
export const HIST_LEN = 96
function sampleHistory() {
  const H = (S.hist = S.hist || { res: {} })
  for (const k of STOCK_KEYS) {
    const a = (H.res[k] = H.res[k] || [])
    a.push(Math.round(S.res[k] * 10) / 10)
    if (a.length > HIST_LEN) a.shift()
  }
}

// Continuous processors: farm, filter, lumber, scrap, forge, still.
function tickProcessor(st, R, rate, dt) {
  if (rate <= 0) return
  const tm = Array.isArray(R.time) ? R.time[st.level - 1] : R.time
  // Respect the stock limit (the Still) so inputs aren't burned for nothing,
  // unless a belt is pulling the product to another station.
  const outKey = Object.keys(R.out)[0]
  if (st.limit != null && S.res[outKey] >= st.limit && !pulled(st, outKey)) {
    st.stalled = `Stopped: ${RES[outKey].name.toLowerCase()} at limit (${st.limit})`
    return
  }
  st.active = true
  st.progress += (dt * rate * beltBonus(st, R) * seasonMult(st)) / tm
  let guard = 0
  while (st.progress >= 1 && guard++ < 20) {
    if (!hasInputs(st, R.in)) {
      const short = Object.keys(R.in).filter((k) => stockFor(st, k) < R.in[k])
      st.stalled = short.every((k) => (S.res[k] || 0) >= R.in[k]) ? `Holding back: the camp needs its ${short.map((k) => RES[k].name.toLowerCase()).join(' and ')}` : 'Missing ' + short.map((k) => RES[k].name.toLowerCase()).join(', ')
      st.progress = 1
      st.active = false
      break
    }
    const full = outputBlocked(st, R.out)
    if (full) {
      st.stalled = full
      st.progress = 1
      st.active = false
      break
    }
    takeInputs(st, R.in)
    giveOutputs(st, R.out)
    if (R.bonus) for (const [k, p] of Object.entries(R.bonus)) if (chance(p)) gain({ [k]: 1 })
    st.progress -= 1
    bus.emit('produced', st, R.out)
  }
}

// Multi-recipe processors (Forge, Chemistry Lab, Ammo Press, Fabricator,
// Machine Shop): run the chosen recipe, or on Auto the product furthest
// below its target that there are materials for.
export const recipeTarget = (st, id) => st.targets?.[id] ?? STATIONS[st.type].targets?.[id] ?? 50
export function activeRecipe(st, id) {
  const R = STATIONS[st.type].recipes[id]
  const alt = st.alts?.[id]
  return alt && ALT_RECIPES[alt] ? { ...R, ...ALT_RECIPES[alt].recipe, lvl: R.lvl } : R
}
export function activeSingle(st) {
  const R = STATIONS[st.type].recipe
  const alt = st.alts?._
  return alt && ALT_RECIPES[alt] ? { ...R, ...ALT_RECIPES[alt].recipe } : R
}
export function recipeUnlocked(st, id) {
  const R = STATIONS[st.type].recipes[id]
  return !!R && (R.lvl || 1) <= st.level && isUnlocked('recipe', st.type + '.' + id)
}
// A product is wanted while it is below its target, or, when a belt carries
// it to another station, while that belt has room (belts pull on demand).
function pickRecipe(st, D) {
  const out1 = (r) => Object.keys(r.out)[0]
  const wanted = (id) => {
    if (!recipeUnlocked(st, id)) return false
    const R = activeRecipe(st, id)
    const k = out1(R)
    if (pulled(st, k)) return recipeTarget(st, id) > 0 && !outputBlocked(st, R.out)
    return S.res[k] < recipeTarget(st, id)
  }
  const fill = (id) => {
    const R = activeRecipe(st, id)
    const k = out1(R)
    return pulled(st, k) ? (st.buf.out[k] || 0) / outCap(k, R.out[k]) : S.res[k] / Math.max(1e-6, recipeTarget(st, id))
  }
  if (st.mode && st.mode !== 'auto') return wanted(st.mode) ? st.mode : null
  const want = Object.keys(D.recipes)
    .filter((id) => recipeTarget(st, id) > 0 && wanted(id))
    .sort((a, b) => fill(a) - fill(b))
  if (!want.length) return null
  // stay on the current product while it still has what it needs
  if (st.curMode && want.includes(st.curMode) && hasInputs(st, activeRecipe(st, st.curMode).in) && st.progress > 0.02) return st.curMode
  return want.find((id) => hasInputs(st, activeRecipe(st, id).in)) || want[0]
}
function tickMulti(st, D, rate, dt) {
  if (rate <= 0) return
  const id = pickRecipe(st, D)
  if (id !== st.curMode) {
    st.curMode = id
    st.progress = 0
  }
  if (!id) {
    const jam = Object.keys(D.recipes).some((rid) => {
      const R = activeRecipe(st, rid)
      return recipeUnlocked(st, rid) && pulled(st, Object.keys(R.out)[0]) && outputBlocked(st, R.out)
    })
    st.stalled = jam ? 'Output belt backed up' : st.mode && st.mode !== 'auto' ? 'At target' : 'Everything at target'
    return
  }
  const R = activeRecipe(st, id)
  st.active = true
  st.progress += (dt * rate * beltBonus(st, R)) / R.time[st.level - 1]
  if (st.progress >= 1) {
    if (!hasInputs(st, R.in)) {
      const short = Object.keys(R.in).filter((k) => stockFor(st, k) < R.in[k])
      st.stalled = short.every((k) => (S.res[k] || 0) >= R.in[k]) ? `Holding back: the camp needs its ${short.map((k) => RES[k].name.toLowerCase()).join(' and ')}` : 'Missing ' + short.map((k) => RES[k].name.toLowerCase()).join(', ')
      st.progress = 1
      st.active = false
      return
    }
    const full = outputBlocked(st, R.out)
    if (full) {
      st.stalled = full
      st.progress = 1
      st.active = false
      return
    }
    takeInputs(st, R.in)
    giveOutputs(st, R.out)
    st.progress -= 1
    bus.emit('produced', st, R.out)
  }
}

// ---------------------------------------------------------------- the recycler
// Gear marked for recycling (or matching the recycler's standing orders) is
// stripped first; then it works through junk like any multi-recipe station.
export function recycleQueue(st = null) {
  const auto = st || S.stations.find((x) => x.type === 'recycler' && x.level > 0)
  return S.items.filter((it) => {
    if (it.locker || ownerOf(it.uid)) return false
    if (it.recycle) return true
    if (!auto) return false
    if (auto.autoBroken && ITEMS[it.id].dur && (it.cond ?? 100) <= 0) return true
    if (auto.autoCrude && (it.q ?? 1) === 0) return true
    return false
  })
}
// What stripping an item gives back: a share of what it took to make (more
// at level 2, less when worn), plus half of any mods fitted.
const SALVAGE_FALLBACK = { melee: { metal: 8, wood: 2 }, gun: { metal: 14, parts: 6 }, armor: { cloth: 12, metal: 6 }, gear: { parts: 3, electronics: 1 } }
export function salvageOf(it, level = 1) {
  const I = ITEMS[it.id]
  const r = RECIPES.find((x) => x.item === it.id)
  const base = r ? r.in : I.slot === 'weapon' ? SALVAGE_FALLBACK[I.kind] || SALVAGE_FALLBACK.melee : SALVAGE_FALLBACK[I.slot] || SALVAGE_FALLBACK.gear
  // gear nobody can make (a katana, say) gives back by what it's worth
  const worth = r ? 1 : 1 + (I.value || 0) / 400
  const share = worth * STATIONS.recycler.salvage[Math.max(0, level - 1)] * (0.5 + (0.5 * (it.cond ?? 100)) / 100)
  const out = {}
  const add = (k, v) => {
    if (!RES[k] || v <= 0) return
    out[k] = (out[k] || 0) + v
  }
  for (const [k, v] of Object.entries(base)) add(k, Math.floor(v * share))
  for (const m of it.mods || []) for (const [k, v] of Object.entries(MODS[m]?.cost || {})) add(k, Math.floor(v * 0.5))
  if (!Object.keys(out).length) out.scrap = 2
  return out
}
// Work-seconds to strip an item: dearer gear takes longer.
export const gearTime = (it, st) => (20 + itemValue(it) / 15) * (STATIONS.recycler.gearTime[Math.max(0, (st?.level || 1) - 1)] || 1)
function tickRecycler(st, D, rate, dt) {
  if (rate <= 0) return
  let it = st.gear ? itemOf(st.gear.uid) : null
  if (!it || ownerOf(it.uid)) {
    st.gear = null
    it = recycleQueue(st)[0] || null
    if (it) {
      const t = gearTime(it, st)
      st.gear = { uid: it.uid, left: t, total: t }
    }
  }
  if (it && st.gear) {
    st.curMode = null
    st.active = true
    st.gear.left -= dt * rate
    st.progress = 1 - st.gear.left / st.gear.total
    if (st.gear.left > 0) return
    const out = salvageOf(it, st.level)
    const full = outputBlocked(st, out)
    if (full) {
      st.stalled = full
      st.gear.left = 0
      st.active = false
      return
    }
    removeItem(it.uid)
    giveOutputs(st, out)
    st.recycled = (st.recycled || 0) + 1
    log(`Recycled ${itemName(it)}: ${Object.entries(out).map(([k, v]) => `${v} ${RES[k].name.toLowerCase()}`).join(', ')}.`)
    bus.emit('recycled', it, out)
    st.gear = null
    st.progress = 0
    return
  }
  tickMulti(st, D, rate, dt)
  if (!st.curMode && !st.gear && st.stalled !== 'Output belt backed up') st.stalled = 'Nothing to recycle'
}

// ---------------------------------------------------------------- station inputs and outputs
// A station draws what it needs from its input buffer first (filled by
// belts), then hand-hauls the rest from storage. What it makes goes to its
// output buffer if a belt carries it away, otherwise straight into storage.
// What a station may take from storage. Factories leave a few days of food,
// water and (near winter) firewood for the people: a kiln or a still never
// burns the camp's last meal or its heat.
const KEEPERS = new Set(['farm', 'kitchen', 'filter', 'infirmary', 'collector'])
export function survivalReserve(k) {
  const n = S.survivors.length
  if (k === 'food') return n * 3
  if (k === 'water') return n * 3.6
  if (k === 'wood') return 20 + (season().heat || seasonIdx() === 2 ? n * 2.4 : 0)
  return 0
}
function stockFor(st, k) {
  const store = S.res[k] || 0
  const keep = st && !KEEPERS.has(st.type) ? Math.max(survivalReserve(k), STATIONS[st.type].keep?.[k] || 0) : 0
  return (st?.buf?.in?.[k] || 0) + Math.max(0, store - keep)
}
export function hasInputs(st, inp) {
  for (const [k, v] of Object.entries(inp)) if (stockFor(st, k) < v - 1e-6) return false
  return true
}
function takeInputs(st, inp) {
  for (const [k, v] of Object.entries(inp)) {
    let need = v
    const b = st.buf?.in
    if (b && b[k] > 0) {
      const t = Math.min(b[k], need)
      b[k] -= t
      need -= t
    }
    if (need > 0) S.res[k] = Math.max(0, S.res[k] - need)
  }
}
// Why a finished batch has nowhere to go, or null.
function outputBlocked(st, out) {
  let unbelted = false
  let room = false
  for (const [k, v] of Object.entries(out)) {
    if (belted(st, k)) {
      if ((st.buf?.out?.[k] || 0) + v > outCap(k, v) + 1e-6) return 'Output belt backed up'
    } else {
      unbelted = true
      if (S.res[k] < capOf(k) - 0.5) room = true
    }
  }
  return unbelted && !room ? 'Storage full' : null
}
function giveOutputs(st, out) {
  const rest = {}
  for (const [k, v] of Object.entries(out)) {
    if (belted(st, k)) {
      st.buf = st.buf || { in: {}, out: {} }
      st.buf.out[k] = (st.buf.out[k] || 0) + v
    } else rest[k] = v
  }
  if (Object.keys(rest).length) gain(rest)
}

// Crafting benches work through their order list. Resources are paid when a
// unit starts. Repeating orders keep going; "keep stocked" orders pause at target.
function nextOrder(st) {
  let stocked = 0
  for (const o of st.orders) {
    if (o.paid) return o
    if (o.kind === 'recipe' && o.keep != null) {
      const r = RECIPES.find((x) => x.id === o.recipe)
      const outK = r.out ? Object.keys(r.out)[0] : null
      if (outK && S.res[outK] >= o.keep) {
        stocked++
        continue
      }
    }
    if (o.kind !== 'recipe' && !itemOf(o.item)) continue
    const spec = orderSpec(o)
    // what's on the intake plus storage (benches don't hold back reserves)
    if (spec && Object.entries(spec.cost).every(([k, v]) => (st.buf?.in?.[k] || 0) + (S.res[k] || 0) >= v - 1e-6)) return o
  }
  st.stockedAll = stocked > 0 && stocked === st.orders.length
  return null
}
function tickBench(st, rate, dt) {
  // drop finished/invalid orders
  st.orders = st.orders.filter((o) => o.kind === 'recipe' || itemOf(o.item))
  if (rate <= 0) {
    if (st.orders.length) st.stalled = 'Needs a worker'
    return
  }
  let o = nextOrder(st)
  if (!o && st.maintain) o = maintenanceJob(st)
  if (!o) {
    if (st.orders.length) st.stalled = st.stockedAll ? 'Everything is stocked' : 'Waiting for materials'
    return
  }
  if (!o.paid) {
    const spec = orderSpec(o)
    // belted materials come out of the intake first, the rest from storage
    takeInputs(st, spec.cost)
    o.paid = { ...spec.cost }
    o.left = o.total = spec.time
  }
  st.active = true
  st.working = o.id
  o.left -= dt * rate
  if (o.left > 0) return
  completeOrder(st, o)
}
function maintenanceJob(st) {
  // Repair the most worn item this bench handles, below 60%.
  // a player's locker is theirs to look after
  const items = S.items.filter((it) => !it.locker && ITEMS[it.id].repair === st.type && it.cond < 60 && !st.orders.some((o) => o.item === it.uid))
  if (!items.length) return null
  items.sort((a, b) => a.cond - b.cond)
  const it = items[0]
  const o = { id: 'maint-' + it.uid, kind: 'repair', item: it.uid, repeat: 1, maint: true }
  const spec = orderSpec(o)
  if (!spec || !canAfford(spec.cost)) return null
  st.orders.push(o)
  return o
}
function completeOrder(st, o) {
  const ws = workersOf(st).filter((s) => s.status === 'ok')
  o.paid = null
  if (o.kind === 'recipe') {
    const r = RECIPES.find((x) => x.id === o.recipe)
    if (r.vehicle) {
      addVehicle(r.vehicle, { log: `${STATIONS[st.type].name}: four bicycles, oiled and ready.` })
      bus.emit('crafted', st, r)
    } else if (r.item) {
      const q = rollQuality(st)
      const it = addItem(r.item, { q })
      log(`${STATIONS[st.type].name}: made ${itemName(it)}.`, q >= 2 ? 'good' : '')
      if (q === 3) completeGoal('master')
      bus.emit('crafted', st, r, it)
    } else {
      // onto the outtake belt if there's room, else into storage
      const rest = {}
      for (const [k, v] of Object.entries(r.out)) {
        if (belted(st, k) && (st.buf?.out?.[k] || 0) + v <= outCap(k, v) + 1e-6) {
          st.buf = st.buf || { in: {}, out: {} }
          st.buf.out[k] = (st.buf.out[k] || 0) + v
        } else rest[k] = v
      }
      gain(rest)
      bus.emit('crafted', st, r)
    }
    S.stats.crafted++
    completeGoal('craft')
    for (const s of ws) {
      gainXP(s, STATIONS[st.type].skill || 'craft', 6)
      deed(s, 'crafted')
    }
    if (o.keep == null) {
      o.repeat = (o.repeat ?? 1) - 1
      if (o.repeat <= 0) st.orders = st.orders.filter((x) => x !== o)
    }
  } else if (o.kind === 'mod') {
    const it = itemOf(o.item)
    if (it) {
      it.mods = [...(it.mods || []), o.mod]
      log(`${MODS[o.mod].name} fitted to ${itemName(it)}.`, 'good')
      bus.emit('items')
    }
    st.orders = st.orders.filter((x) => x !== o)
    for (const s of ws) gainXP(s, 'craft', 5)
  } else {
    const it = itemOf(o.item)
    if (it) {
      it.cond = 100
      if (!o.maint) log(`${itemName(it)} repaired.`)
      bus.emit('items')
    }
    st.orders = st.orders.filter((x) => x !== o)
    for (const s of ws) gainXP(s, 'craft', 3)
  }
  st.working = null
  bus.emit('change')
}

function tickInfirmary(st, rate, dt) {
  const d = STATIONS.infirmary
  const medics = workersOf(st).filter((s) => s.status === 'ok')
  let healPower = 0
  for (const m of medics) healPower += workEff(m, 'infirmary') * (OCCUPATIONS[m.occ].fx.healMult || 1) * (1 + (m.perks || []).reduce((a, p) => a + (perkOf(p)?.fx.heal || 0), 0))
  const patients = S.survivors
    .filter((s) => s.status !== 'mission' && s.status !== 'scout' && (s.infection > 0 || s.status === 'injured' || (s.status === 'ok' && s.hp < survivorStats(s).maxHp * 0.6)))
    .sort((a, b) => (b.infection || 0) - (a.infection || 0))
    .slice(0, d.beds[st.level - 1])
  st.patients = patients.map((p) => p.id)
  if (patients.length && healPower > 0) {
    const per = (d.heal[st.level - 1] * healPower * dt * 2.2) / patients.length
    for (const p of patients) p.hp = Math.min(survivorStats(p).maxHp, p.hp + per)
    for (const m of medics) gainXP(m, 'medic', 0.35 * dt)
    st.active = true
    return
  }
  tickBench(st, rate, dt)
}

function tickResearch(st, rate, dt) {
  const p = st.project
  if (!p) {
    st.stalled = S.research.pick ? 'Choose an alternate recipe' : 'No project'
    return
  }
  if (rate <= 0) {
    st.stalled = 'Needs a researcher'
    return
  }
  st.active = true
  p.left -= dt * rate
  for (const s of workersOf(st)) if (s.status === 'ok') gainXP(s, 'tech', 0.15 * dt)
  if (p.left <= 0) finishResearch(st)
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

// ---------------------------------------------------------------- infection
// Infections climb day by day, slower for a patient at a staffed Infirmary,
// whose medics also give antivirals when the camp has them. At 100 the
// survivor turns: in camp the others have to put them down.
function tickInfection(dt) {
  const inf = S.stations.find((st) => st.type === 'infirmary' && st.level > 0 && !st.building && workersOf(st).some((w) => w.status === 'ok' && !(w.infection >= INFECTION.sick)))
  for (const s of [...S.survivors]) {
    if (!(s.infection > 0)) continue
    const cared = inf && (inf.patients || []).includes(s.id)
    if (cared && inf.autoTreat !== false && s.infection < INFECTION.cureBelow && S.res.antiviral >= 1) {
      treatInfection(s)
      continue
    }
    const before = s.infection
    s.infection += (INFECTION.perDay / SEC_PER_DAY) * dt * (cared ? INFECTION.infirmary : 1)
    if (before < INFECTION.fever && s.infection >= INFECTION.fever) log(`${s.first} is running a fever. The infection is spreading.`, 'bad')
    if (before < INFECTION.sick && s.infection >= INFECTION.sick) {
      log(`${s.first} is turning. Too sick to go out; an antiviral can only slow it now.`, 'bad')
      bus.emit('turning', s)
    }
    if (s.infection >= 100 && s.status !== 'mission') {
      killSurvivor(s, 'Turned after an infection and had to be put down')
      addMoraleEvent(`${s.first} turned`, -12, 2)
      bus.emit('turned', s)
    }
  }
}

// ---------------------------------------------------------------- expansions
function finishExpansion() {
  const X = EXPANSIONS.find((e) => e.id === S.expanding.id)
  S.expansions[X.id] = 'done'
  S.expanding = null
  rebuildFence()
  const lost = gain(X.salvage)
  log(`${X.name} cleared and fenced. Salvaged ${Object.entries(X.salvage).map(([k, v]) => `${v} ${RES[k].name.toLowerCase()}`).join(', ')}.`, 'good')
  addMoraleEvent('More room to breathe', 6, 1.5)
  completeGoal('expand')
  if (['n1', 'e1', 'w1', 's1'].every((id) => S.expansions[id] === 'done')) completeGoal('expandAll')
  bus.emit('expanded', X)
  bus.emit('expansions')
}

// ---------------------------------------------------------------- morale
export function moraleFactors() {
  const f = []
  f.push({ text: 'Baseline', v: 45 })
  if (foodStock() <= 0.01) f.push({ text: 'No food', v: -25 })
  if (S.res.water <= 0.01) f.push({ text: 'No water', v: -25 })
  if (S.cold) f.push({ text: 'Freezing', v: -15 })
  if (S.disrepair) f.push({ text: 'Camp in disrepair', v: -6 })
  else if (season().heat) f.push({ text: 'Warm through winter', v: 2 })
  const bunks = S.stations.filter((s) => s.type === 'bunkhouse' && s.level > 0)
  if (bunks.length) f.push({ text: 'Bunk comfort', v: Math.round(bunks.reduce((a, s) => a + s.level, 0) / bunks.length * 4) })
  if (S.stations.some((s) => s.type === 'campfire')) f.push({ text: 'Campfire', v: 4 })
  const kit = S.stations.filter((s) => s.type === 'kitchen' && s.active)
  if (kit.length) {
    let v = Math.max(...kit.map((s) => STATIONS.kitchen.morale[s.level - 1]))
    if (kit.some((s) => workersOf(s).some((w) => w.occ === 'chef'))) v += 4
    f.push({ text: 'Hot meals', v })
  }
  const def = Math.min(14, S.fence.level * 3 + countType('watchtower') * 2 + countType('turret') * 2)
  if (def) f.push({ text: 'Feeling safe', v: def })
  const free = bedCount() - S.survivors.length
  if (free < 0) f.push({ text: 'Overcrowded', v: free * 4 })
  const inj = S.survivors.filter((s) => s.status === 'injured').length
  if (inj) f.push({ text: `${inj} injured`, v: -Math.min(15, inj * 3) })
  for (const e of S.moraleEvents) {
    const k = clamp((e.until - S.time) / (e.days * DAY_MIN), 0, 1)
    const v = Math.round(e.amount * k)
    if (v) f.push({ text: e.text, v })
  }
  return f
}
function tickMorale(dt) {
  S.moraleEvents = S.moraleEvents.filter((e) => e.until > S.time)
  const target = clamp(moraleFactors().reduce((a, x) => a + x.v, 0), 0, 100)
  S.morale += (target - S.morale) * Math.min(1, dt * 0.05)
}
function desertion() {
  const cands = S.survivors.filter((s) => s.status === 'ok' && !s.job)
  const pool = cands.length ? cands : S.survivors.filter((s) => s.status === 'ok')
  if (pool.length <= 1 || !chance(0.35)) {
    log('Morale is dangerously low. People are talking about leaving.', 'bad')
    return
  }
  const s = pick(pool)
  for (const k of Object.keys(s.equip)) s.equip[k] = null
  S.survivors = S.survivors.filter((x) => x !== s)
  log(`${s.name} packed up and left in the night. Morale is too low.`, 'bad')
  bus.emit('change')
}

// ---------------------------------------------------------------- weather
function rollWeather() {
  const h = hour()
  const r = weighted(Object.entries(season().weather).map(([t, w]) => ({ t, w }))).t
  S.weather = { type: r, until: S.time + rand(6, 16) * 60 }
  if (r !== 'clear' && r !== 'hazy') log(r === 'rain' ? 'It starts to rain. The collectors fill faster.' : r === 'snow' ? 'Snow is falling.' : r === 'fog' ? 'Fog rolls in. Zombies can\'t see far, and neither can you.' : 'Clouds roll over.', '')
  bus.emit('weather', r)
}

// ---------------------------------------------------------------- the city
// A cleared place stays clear while an outpost holds it. Left alone, now and
// then the infected drift back in.
function reinfest() {
  if (!S.places) return
  for (const [id, P] of Object.entries(S.places)) {
    if (!P.cleared || outpostAt(+id) || !chance(0.012)) continue
    const loc = S.cityLocs.find((l) => l.id === +id)
    P.cleared = false
    P.left = 2 + (loc?.level || 1) * 2
    P.at = S.time
    log(`The infected have drifted back into ${loc?.name || 'a place you cleared'}.`, 'bad')
  }
}

// ---------------------------------------------------------------- recruits & events
export function scheduleRecruit() {
  let mult = 1
  const radio = S.stations.filter((s) => s.type === 'radio' && s.level > 0 && !s.building)
  for (const st of radio) mult = Math.min(mult, STATIONS.radio.recruit[st.level - 1])
  const op = radio.flatMap((st) => workersOf(st)).find((s) => s.status === 'ok')
  if (op) mult *= 1 / (1 + 0.25 * workEff(op, 'radio'))
  if (hasFlag('beacon')) mult *= 0.75
  // a quarter of the city cleared: word gets around
  if (S.libDone?.includes(0.25)) mult *= 0.75
  S.recruit.next = S.time + rand(9, 15) * 60 * mult
}
function recruitQuality() {
  return Math.min(5, Math.floor(day() / 6) + maxLevelOf('radio') + (hasFlag('beacon') ? 1 : 0))
}
// Distress calls and supply drops appear on the city map for a while.
function spawnEvent() {
  const radio = maxLevelOf('radio')
  S.nextEvent = S.time + rand(20, 34) * 60 * (radio ? 0.75 - radio * 0.1 : 1)
  if (!S.cityLocs?.length) return
  const free = S.cityLocs.filter((l) => !isLooted(l.id) && !S.events.some((e) => e.locId === l.id) && l.level <= Math.min(5, 2 + Math.floor(day() / 3)))
  if (!free.length) return
  const loc = pick(free)
  const kind = chance(0.65) ? 'distress' : 'airdrop'
  const ev = { id: 'ev' + Math.floor(Math.random() * 1e6), kind, locId: loc.id, expires: S.time + rand(10, 18) * 60 }
  if (kind === 'distress') ev.npc = makeSurvivor({ quality: recruitQuality() + 1 })
  S.events.push(ev)
  log(kind === 'distress' ? `Radio: someone is trapped at ${loc.name} and calling for help.` : `A supply drop came down near ${loc.name}.`, 'story')
  bus.emit('event', ev)
}

// ---------------------------------------------------------------- hordes
// How much attention the camp draws: what it has built and how many it
// shelters count for most; time survived adds a little, capped. This, not
// the calendar, decides how big hordes get.
export function threatLevel() {
  const exp = Object.values(S.expansions).filter((v) => v === 'done').length
  return campTier() * 1.3 + (S.signal?.phase || 0) + S.survivors.length / 8 + exp * 0.4 + Math.min(day(), 100) / 20
}
export const BLOOD_MOON_EVERY = 7
// Days on which the night horde is a Blood Moon.
export const isBloodMoonDay = (d) => d >= 21 && d % BLOOD_MOON_EVERY === 0
// When the Signal's last phase is done, the broadcast draws every dead thing
// in the city: one last Blood Moon horde before the evacuation convoy comes.
bus.on('signalPhase', (p) => {
  if (p >= SIGNAL_PHASES && S && !S.won) {
    S.finale = { at: S.time + 14 * 60 }
    scheduleRaid()
  }
})
export function scheduleRaid(first = false) {
  const T = threatLevel()
  if (S.finale && !S.won) {
    S.nextRaid = { at: Math.max(S.finale.at, S.time + 60), size: 3, count: 120 + Math.floor(T * 2), warned: false, blood: true, lvl: 5, finale: true }
    return
  }
  let sizeIdx
  if (first || T < 3) sizeIdx = 0
  else if (T < 6) sizeIdx = chance(0.6) ? 0 : 1
  else if (T < 10) sizeIdx = chance(0.55) ? 1 : 2
  else if (T < 15) sizeIdx = chance(0.6) ? 2 : 3
  else sizeIdx = 3
  let at = first ? 22 * 60 : S.time + rand(20, 32) * 60
  // after a breach the dead drift off sated: a day and a half to patch up
  if (!first && S.lastBreach != null && S.time - S.lastBreach < 6 * 60) at = Math.max(at, S.lastBreach + 36 * 60)
  // a Blood Moon night comes whatever else is on its way
  let blood = false
  if (!first) {
    let d = day()
    if (S.time > (d - 1) * DAY_MIN + 22 * 60) d++
    while (!isBloodMoonDay(d)) d++
    const bm = (d - 1) * DAY_MIN + 22 * 60
    // it replaces any horde due up to 20 h before it, so the two never stack
    if (bm <= at + 20 * 60) {
      at = bm
      blood = true
      sizeIdx = Math.min(3, sizeIdx + 1)
    }
  }
  const H = HORDES[sizeIdx]
  // animals draw them: each coop or pen adds a few to the horde
  const pens = S.stations.filter((x) => STATIONS[x.type].livestock && x.level > 0 && flockOf(x) > 0).length
  const count = Math.round((rint(H.min, H.max) + Math.floor(T * 1.2)) * (blood ? 1.5 : 1) * (1 + 0.08 * pens))
  const lvl = clamp(1 + Math.floor(T / 4) + (blood ? 1 : 0), 1, 5)
  S.nextRaid = { at, size: sizeIdx, count, warned: false, blood, lvl }
}
export function raidIntel() {
  const r = S.nextRaid
  if (!r) return null
  const towers = countType('watchtower')
  const H = HORDES[r.size]
  if (r.finale) return { in: r.at - S.time, name: 'The last night', count: r.count, size: 3, known: true, blood: true, finale: true, threat: threatLevel() }
  return { in: r.at - S.time, name: r.blood ? `Blood Moon: ${towers ? H.name.toLowerCase() : 'a horde'}` : towers ? H.name : 'Unknown horde', count: towers ? r.count : null, size: r.size, known: !!towers, blood: !!r.blood, threat: threatLevel() }
}
// A horde hits while nobody is watching (e.g. during a run).
export function autoResolveRaid(R, offline = false) {
  const defenders = S.survivors.filter((s) => s.status === 'ok')
  let def = 0
  for (const s of defenders) {
    const st = survivorStats(s)
    const dps = st.dmg / st.rate
    const ammoOk = !st.gun || !st.ammoType || S.res[st.ammoType] > 5
    def += dps * (st.gun ? (ammoOk ? 1.4 : 0.3) : 0.8) * (s.hp / st.maxHp)
    if (s.job && S.stations.find((x) => x.id === s.job)?.type === 'watchtower') def += dps * 0.6
  }
  // the injured still hold a gap in the wall from their beds, at a fraction
  for (const s of S.survivors.filter((x) => x.status === 'injured')) {
    const st = survivorStats(s)
    def += (st.dmg / st.rate) * (st.gun ? 0.5 : 0.3)
  }
  const pinfo = powerInfo()
  for (const st of S.stations) if (st.type === 'turret' && pinfo.powered.has(st.id) && S.res.pammo > 5) def += STATIONS.turret.dmg[st.level - 1] / STATIONS.turret.rate[st.level - 1]
  const fence = FENCE[S.fence.level].hp * 0.08
  // a zombie's weight in the fight follows its level (set by threat), not the day
  const horde = R.count * (12 + (R.lvl || clamp(1 + Math.floor(day() / 4), 1, 5)) * 6) * (R.blood ? 1.1 : 1)
  const ratio = (def * 3 + fence) / Math.max(1, horde)
  const report = { count: R.count, injured: [], dead: [], lost: {}, ratio }
  for (const k of ['pammo', 'rammo', 'shells']) S.res[k] = Math.max(0, S.res[k] - Math.min(S.res[k], R.count))
  const dmgFrac = clamp(1.3 - ratio, 0.05, 1)
  S.fence.hp = S.fence.hp.map((h) => Math.max(0, h - fenceMax() * dmgFrac * rand(0.2, 0.9)))
  if (ratio < 1.1) {
    for (const s of defenders) {
      if (chance(clamp(1.1 - ratio, 0, 0.8))) {
        s.status = 'injured'
        s.hp = Math.max(1, survivorStats(s).maxHp * 0.15)
        report.injured.push(s.first)
      }
    }
  }
  if (ratio < 0.55) {
    for (const k of ['food', 'water', 'meds', 'fuel']) {
      const l = Math.floor(S.res[k] * rand(0.15, 0.35))
      S.res[k] -= l
      report.lost[k] = l
    }
    if (chance(0.5) && defenders.length) {
      const v = pick(defenders)
      killSurvivor(v, 'Killed defending the camp')
      report.dead.push(v.first)
    }
    addMoraleEvent('The horde broke through', -18, 2)
    S.lastBreach = S.time
    S.stats.raidsLost = (S.stats.raidsLost || 0) + 1
  } else addMoraleEvent('Held the wall', 8, 1)
  S.stats.raids++
  S.stats.kills += Math.round(R.count * clamp(ratio, 0.3, 1))
  completeGoal('surviveHorde')
  if (R.finale) finishGame(ratio >= 0.55)
  log(`${offline ? 'While you were away, a' : 'A'} horde of ${R.count} hit the camp. ${report.injured.length ? report.injured.length + ' injured.' : 'No one was hurt.'}${report.dead.length ? ' ' + report.dead.join(', ') + ' did not make it.' : ''}`, report.dead.length ? 'bad' : '')
  bus.emit('raidResolved', report)
  return report
}

// The last night is over: the convoy comes at dawn.
export function finishGame(held) {
  if (S.won) return
  S.won = day()
  S.finale = null
  log(held ? 'Dawn. The convoy from the coast rolls up to the gate. You held.' : 'Dawn. The camp is in ruins, but the convoy from the coast made it. Those still standing are going home.', 'story')
  bus.emit('victory', { held })
}

// ---------------------------------------------------------------- market
const RRANK = { common: 0, uncommon: 1, rare: 2, epic: 3 }
export function restockMarket() {
  const radio = maxLevelOf('radio')
  const pool = Object.entries(ITEMS).filter(([id]) => id !== 'fists')
  const stock = []
  const n = 6 + radio * 2
  for (let i = 0; i < n; i++) {
    const e = weighted(pool.map(([id, it]) => ({ id, w: [6, 3.5, 1.4 + radio * 0.5, 0.4 + radio * 0.4][RRANK[it.rarity]] })))
    const q = chance(0.12 + radio * 0.06) ? 2 : chance(0.15) ? 0 : 1
    const base = ITEMS[e.id].value * QUALITY[q].value
    stock.push({ kind: 'item', id: e.id, q, price: Math.round(base * rand(1.05, 1.35)) })
  }
  const bundles = [['food', 30], ['water', 30], ['wood', 40], ['scrap', 40], ['metal', 25], ['coal', 20], ['plates', 12], ['bolts', 80], ['cloth', 25], ['parts', 6], ['electronics', 4], ['chemicals', 6], ['gunpowder', 10], ['fuel', 15], ['pammo', 60], ['rammo', 30], ['shells', 20], ['meds', 4], ['medkit', 1], ['molotov', 2], ['module', 1]]
  for (const [r, qty] of bundles) {
    if (r === 'module' && radio < 2) continue
    stock.push({ kind: 'res', res: r, qty, price: Math.round(RES[r].sell * qty * rand(2.2, 2.8)) })
  }
  S.market = { day: day(), stock }
}
export function sellMult() {
  let m = 1
  for (const s of S.survivors) if (s.status === 'ok' && OCCUPATIONS[s.occ].fx.market) m = Math.max(m, 1 + OCCUPATIONS[s.occ].fx.market)
  return m
}
export const buyMult = () => (sellMult() > 1 ? 0.92 : 1)
export const resSellPrice = (r, qty) => Math.round(RES[r].sell * qty * sellMult())

// ---------------------------------------------------------------- recruits
export function acceptRecruit() {
  const p = S.recruit.pending
  if (!p || S.survivors.length >= bedCount()) return false
  p.s.joined = day()
  S.survivors.push(p.s)
  S.recruit.pending = null
  S.stats.recruited++
  completeGoal('recruit')
  addMoraleEvent('A new face in camp', 4, 1)
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
  log(`You turned ${p.s.first} away.`)
  scheduleRecruit()
  bus.emit('recruitGone')
}

// ---------------------------------------------------------------- goals
function checkGoals() {
  if (S.survivors.length >= 10) completeGoal('pop10')
  if (S.survivors.length >= 20) completeGoal('pop20')
  if (day() >= 7) completeGoal('day7')
  if (day() >= 30) completeGoal('day30')
  if (countType('filter')) completeGoal('buildFilter')
  if (countType('lumber') || countType('scrapyard')) completeGoal('buildLumber')
}

// Start-of-game scheduling (after newGame()).
export function initSchedules() {
  scheduleRaid(true)
  S.recruit.next = S.time + 6 * 60
  S.nextEvent = S.time + 14 * 60
  S.weather = { type: 'clear', until: S.time + 10 * 60 }
  restockMarket()
  log('Day 1. The four of you made it to the old lumber yard. It will have to do.', 'story')
}
