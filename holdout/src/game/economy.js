// The camp simulation: consumption, production chains, crafting orders,
// repairs, power and automation, construction, expansions, morale, weather,
// recruits, distress calls, hordes and the black market.
import {
  RES, STOCK_KEYS, AMMO_KEYS, ITEMS, QUALITY, MODS, STATIONS, FENCE, RECIPES, EXPANSIONS, HORDES, OCCUPATIONS, LOCATIONS,
  GAME_MIN_PER_SEC, DAY_MIN, SEC_PER_DAY, RARITY,
} from './data.js'
import {
  S, day, hour, bounds, log, gain, pay, canAfford, capOf, stationSize, workersOf, workEff, gainXP, completeGoal,
  survivorStats, itemOf, addItem, repairCost, orderSpec, rollQuality, rebuildFence, fenceMax, makeSurvivor, killSurvivor,
  bedCount, countType, maxLevelOf, addMoraleEvent, itemName, moraleMult, available, getS,
} from './state.js'
import { bus, pick, rint, rand, chance, clamp, weighted } from '../core/util.js'

// ---------------------------------------------------------------- power
export function powerNeed(st) {
  if (st.building || st.level < 1) return 0
  const d = STATIONS[st.type]
  if (d.auto && st.level >= d.auto && st.module && st.autoOn !== false) return d.autoPower
  if (st.type === 'turret' || st.type === 'floodlight') return st.autoOn === false ? 0 : d.power
  return 0
}
export function solarOutput() {
  const h = hour()
  if (h < 6 || h > 19.5) return 0
  const sunK = Math.sin(((h - 6) / 13.5) * Math.PI)
  const w = { clear: 1, hazy: 0.8, overcast: 0.45, rain: 0.3, fog: 0.4 }[S.weather.type] ?? 1
  return sunK * w
}
export function powerInfo() {
  let supply = 0
  let gen = 0
  for (const st of S.stations) {
    if (st.building || st.level < 1) continue
    if (st.type === 'generator') {
      const op = workersOf(st).filter((s) => s.status === 'ok')[0]
      let p = STATIONS.generator.power[st.level - 1]
      if (op) p *= 1 + (OCCUPATIONS[op.occ].fx.station?.generator || 0) + 0.03 * op.skills.tech
      if (S.res.fuel > 0.05) {
        supply += p
        gen += p
      }
    }
    if (st.type === 'solar') supply += STATIONS.solar.solar[st.level - 1] * solarOutput()
  }
  let demand = 0
  let left = supply
  const powered = new Set()
  for (const st of S.stations) {
    const need = powerNeed(st)
    if (!need) continue
    demand += need
    if (left >= need) {
      left -= need
      powered.add(st.id)
    }
  }
  return { supply: Math.round(supply * 10) / 10, gen, demand, used: supply - left, powered }
}
let pinfoCache = null
export const power = () => pinfoCache || powerInfo()

export function stationRate(st, pinfo) {
  if (st.building || st.level < 1) return 0
  const d = STATIONS[st.type]
  let r = 0
  for (const s of workersOf(st)) if (s.status === 'ok') r += workEff(s, st.type)
  if (isAutomated(st, pinfo)) r += d.autoRate
  return r
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
  return { food: food * (1 - kitchenSaving()), water }
}

// Idle survivors (no job) help build, or forage when nothing is going up.
export const FORAGE = { wood: 6, scrap: 4 }
export function constructSpeed() {
  let sp = 0.35
  for (const s of S.survivors) {
    if (s.status !== 'ok' || s.job) continue
    sp += 0.3 * (0.62 + 0.08 * s.skills.build) * (1 + (OCCUPATIONS[s.occ].fx.construct || 0))
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
  if (d.passive) for (const [k, arr] of Object.entries(d.passive)) add(k, arr[st.level - 1] * (S.weather.type === 'rain' ? 2 : 1))
  if (d.recipe && rate > 0) {
    const tm = Array.isArray(d.recipe.time) ? d.recipe.time[st.level - 1] : d.recipe.time
    const cyc = (rate * SEC_PER_DAY) / tm
    for (const [k, v] of Object.entries(d.recipe.out)) add(k, v * cyc)
    for (const [k, v] of Object.entries(d.recipe.in)) add(k, -v * cyc)
  }
  if (d.modes && rate > 0) {
    const m = d.modes[st.curMode || 'pammo']
    const cyc = (rate * SEC_PER_DAY) / m.time[st.level - 1]
    for (const [k, v] of Object.entries(m.out)) add(k, v * cyc)
    for (const [k, v] of Object.entries(m.in)) add(k, -v * cyc)
  }
  if (st.type === 'kitchen' && st.active) add('wood', -d.burn.wood)
  if (st.type === 'generator' && pinfo.used > 0 && pinfo.gen > 0) add('fuel', -(SEC_PER_DAY / d.burn[st.level - 1]) * Math.min(1, pinfo.used / Math.max(pinfo.supply, 0.01)))
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
  total.food = (total.food || 0) - n.food
  total.water = (total.water || 0) - n.water
  return total
}

// ---------------------------------------------------------------- econ tick
export function econTick(dt, opts = {}) {
  if (!S || S.over) return
  const prevDay = day()
  const prevHour = hour()
  S.time += dt * GAME_MIN_PER_SEC
  const pinfo = (pinfoCache = powerInfo())

  // ---- weather changes at dawn and sometimes midday
  if (S.time >= S.weather.until) rollWeather()

  // ---- food & water
  const needs = dailyNeeds()
  S.res.food = Math.max(0, S.res.food - (needs.food / SEC_PER_DAY) * dt)
  S.res.water = Math.max(0, S.res.water - (needs.water / SEC_PER_DAY) * dt)
  const hungry = S.survivors.length > 0 && (S.res.food <= 0.01 || S.res.water <= 0.01)
  if (hungry && !S.hungry) log(S.res.water <= 0.01 ? 'Out of water! Everyone is weakening.' : 'Out of food! Everyone is weakening.', 'bad')
  S.hungry = hungry

  // ---- generator fuel
  if (pinfo.gen > 0 && pinfo.used > 0) {
    const load = Math.min(1, pinfo.used / Math.max(pinfo.supply, 0.01))
    for (const st of S.stations) {
      if (st.type !== 'generator' || st.level < 1 || st.building) continue
      S.res.fuel = Math.max(0, S.res.fuel - (dt / STATIONS.generator.burn[st.level - 1]) * load)
    }
  }

  // ---- construction, wall upgrades, expansions
  const cs = constructSpeed()
  for (const st of S.stations) {
    if (!st.building) continue
    st.building.left -= dt * cs
    if (st.building.left <= 0) {
      st.level = st.building.to
      st.building = null
      bus.emit('built', st)
      log(`${STATIONS[st.type].name} ${st.level > 1 ? `upgraded to level ${st.level}` : 'built'}.`, 'good')
      for (const s of S.survivors) if (s.status === 'ok' && !s.job) gainXP(s, 'build', 5)
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
  for (const st of S.stations) {
    st.active = false
    st.stalled = null
    if (st.building || st.level < 1) continue
    const d = STATIONS[st.type]
    const rate = stationRate(st, pinfo)
    if (isAutomated(st, pinfo)) anyAuto = true
    if (d.passive) {
      const mult = S.weather.type === 'rain' ? 2 : 1
      for (const [k, arr] of Object.entries(d.passive)) gain({ [k]: (arr[st.level - 1] * mult / SEC_PER_DAY) * dt })
      st.active = true
    }
    if (st.type === 'solar') st.active = solarOutput() > 0.05
    for (const s of workersOf(st)) if (s.status === 'ok' && d.skill) gainXP(s, d.skill, 0.2 * dt)
    if (d.recipe) tickProcessor(st, d.recipe, rate, dt)
    else if (d.modes) tickAmmo(st, rate, dt)
    else if (st.type === 'infirmary') tickInfirmary(st, rate, dt)
    else if (d.queue) tickBench(st, rate, dt)
    if (st.type === 'training') tickTraining(st, dt)
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
    if (st.type === 'generator') st.active = pinfo.gen > 0 && pinfo.used > 0
    if (st.type === 'radio') st.active = workersOf(st).some((s) => s.status === 'ok')
    if (st.type === 'watchtower') st.active = workersOf(st).some((s) => s.status === 'ok')
    if (d.workers[st.level - 1] && !workersOf(st).length && !isAutomated(st, pinfo) && !['generator', 'watchtower', 'radio', 'training'].includes(st.type)) st.stalled = st.stalled || 'No workers'
  }
  if (anyAuto) completeGoal('automate')

  // ---- recovery
  for (const s of S.survivors) {
    if (s.status === 'mission') continue
    const st = survivorStats(s)
    if (S.hungry) s.hp = Math.max(1, s.hp - dt * 0.1)
    else if (s.status === 'ok') s.hp = Math.min(st.maxHp, s.hp + dt * 0.1)
    else if (s.status === 'injured') {
      s.hp = Math.min(st.maxHp, s.hp + dt * 0.035)
      if (s.hp >= st.maxHp * 0.7) {
        s.status = 'ok'
        log(`${s.first} has recovered.`, 'good')
        bus.emit('change')
      }
    }
  }

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
  S.events = S.events.filter((e) => e.expires > S.time)
  if (S.time >= (S.nextEvent || 0)) spawnEvent()

  // ---- hordes
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

  // ---- day rollover
  if (day() !== prevDay) {
    restockMarket()
    for (const k of Object.keys(S.looted)) if (S.looted[k] <= day()) delete S.looted[k]
    log(`Day ${day()} begins. ${S.survivors.length} survivors in camp.`, 'story')
    if (S.morale < 18) desertion()
    bus.emit('newDay', day())
  }
  if (Math.floor(prevHour) !== Math.floor(hour())) checkGoals()
}

// Continuous processors: farm, filter, lumber, scrap, forge, still.
function tickProcessor(st, R, rate, dt) {
  if (rate <= 0) return
  const tm = Array.isArray(R.time) ? R.time[st.level - 1] : R.time
  // Respect the stock limit (Forge/Still) so inputs aren't burned for nothing.
  const outKey = Object.keys(R.out)[0]
  if (st.limit != null && S.res[outKey] >= st.limit) {
    st.stalled = `Stopped: ${RES[outKey].name.toLowerCase()} at limit (${st.limit})`
    return
  }
  st.active = true
  st.progress += (dt * rate) / tm
  let guard = 0
  while (st.progress >= 1 && guard++ < 20) {
    if (!canAfford(R.in)) {
      st.stalled = 'Missing ' + Object.keys(R.in).filter((k) => S.res[k] < R.in[k]).map((k) => RES[k].name.toLowerCase()).join(', ')
      st.progress = 1
      st.active = false
      break
    }
    if (Object.keys(R.out).every((k) => S.res[k] >= capOf(k) - 0.5)) {
      st.stalled = 'Storage full'
      st.progress = 1
      st.active = false
      break
    }
    for (const [k, v] of Object.entries(R.in)) S.res[k] -= v
    gain(R.out)
    if (R.bonus) for (const [k, p] of Object.entries(R.bonus)) if (chance(p)) gain({ [k]: 1 })
    st.progress -= 1
    bus.emit('produced', st, R.out)
  }
}

// Ammo Press: produces the chosen calibre, or the one furthest below its target
// that it has the materials for.
function pickAmmoMode(st) {
  const M = STATIONS.ammo.modes
  if (st.mode && st.mode !== 'auto') return S.res[st.mode] < (st.targets?.[st.mode] ?? 1e9) ? st.mode : null
  const want = ['pammo', 'rammo', 'shells']
    .filter((k) => (st.targets?.[k] ?? 100) > 0 && S.res[k] < (st.targets?.[k] ?? 100))
    .sort((a, b) => S.res[a] / (st.targets?.[a] ?? 100) - S.res[b] / (st.targets?.[b] ?? 100))
  if (!want.length) return null
  return want.find((k) => canAfford(M[k].in)) || want[0]
}
function tickAmmo(st, rate, dt) {
  if (rate <= 0) return
  const mode = pickAmmoMode(st)
  st.curMode = mode
  if (!mode) {
    st.stalled = 'All ammo at target'
    return
  }
  const M = STATIONS.ammo.modes[mode]
  st.active = true
  st.progress += (dt * rate) / M.time[st.level - 1]
  if (st.progress >= 1) {
    if (!canAfford(M.in)) {
      st.stalled = 'Missing ' + Object.keys(M.in).filter((k) => S.res[k] < M.in[k]).map((k) => RES[k].name.toLowerCase()).join(', ')
      st.progress = 1
      st.active = false
      return
    }
    for (const [k, v] of Object.entries(M.in)) S.res[k] -= v
    gain(M.out)
    st.progress -= 1
    bus.emit('produced', st, M.out)
  }
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
    if (spec && canAfford(spec.cost)) return o
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
    pay(spec.cost)
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
  const items = S.items.filter((it) => ITEMS[it.id].repair === st.type && it.cond < 60 && !st.orders.some((o) => o.item === it.uid))
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
    if (r.item) {
      const q = rollQuality(st)
      const it = addItem(r.item, { q })
      log(`${STATIONS[st.type].name}: made ${itemName(it)}.`, q >= 2 ? 'good' : '')
      if (q === 3) completeGoal('master')
      bus.emit('crafted', st, r, it)
    } else {
      gain(r.out)
      bus.emit('crafted', st, r)
    }
    S.stats.crafted++
    completeGoal('craft')
    for (const s of ws) gainXP(s, STATIONS[st.type].skill || 'craft', 6)
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
  for (const m of medics) healPower += workEff(m, 'infirmary') * (OCCUPATIONS[m.occ].fx.healMult || 1)
  const patients = S.survivors.filter((s) => s.status === 'injured' || (s.status === 'ok' && s.hp < survivorStats(s).maxHp * 0.6)).slice(0, d.beds[st.level - 1])
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
  if (S.res.food <= 0.01) f.push({ text: 'No food', v: -25 })
  if (S.res.water <= 0.01) f.push({ text: 'No water', v: -25 })
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
  const r = weighted([
    { t: 'clear', w: 45 },
    { t: 'hazy', w: 18 },
    { t: 'overcast', w: 16 },
    { t: 'rain', w: 15 },
    { t: 'fog', w: 6 },
  ]).t
  S.weather = { type: r, until: S.time + rand(6, 16) * 60 }
  if (r !== 'clear' && r !== 'hazy') log(r === 'rain' ? 'It starts to rain. The collectors fill faster.' : r === 'fog' ? 'Fog rolls in. Zombies can\'t see far, and neither can you.' : 'Clouds roll over.', '')
  bus.emit('weather', r)
}

// ---------------------------------------------------------------- recruits & events
export function scheduleRecruit() {
  let mult = 1
  const radio = S.stations.filter((s) => s.type === 'radio' && s.level > 0 && !s.building)
  for (const st of radio) mult = Math.min(mult, STATIONS.radio.recruit[st.level - 1])
  const op = radio.flatMap((st) => workersOf(st)).find((s) => s.status === 'ok')
  if (op) mult *= 1 / (1 + 0.25 * workEff(op, 'radio'))
  S.recruit.next = S.time + rand(9, 15) * 60 * mult
}
function recruitQuality() {
  return Math.min(4, Math.floor(day() / 6) + maxLevelOf('radio'))
}
// Distress calls and supply drops appear on the city map for a while.
function spawnEvent() {
  const radio = maxLevelOf('radio')
  S.nextEvent = S.time + rand(20, 34) * 60 * (radio ? 0.75 - radio * 0.1 : 1)
  if (!S.cityLocs?.length) return
  const free = S.cityLocs.filter((l) => !S.looted[l.id] && !S.events.some((e) => e.locId === l.id) && l.level <= Math.min(5, 2 + Math.floor(day() / 3)))
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
export function scheduleRaid(first = false) {
  const d = day()
  let sizeIdx
  if (first || d < 4) sizeIdx = 0
  else if (d < 8) sizeIdx = chance(0.6) ? 0 : 1
  else if (d < 14) sizeIdx = chance(0.55) ? 1 : 2
  else if (d < 22) sizeIdx = chance(0.6) ? 2 : 3
  else sizeIdx = 3
  const H = HORDES[sizeIdx]
  const camp = Object.values(S.expansions).filter((v) => v === 'done').length + Math.floor(S.survivors.length / 4)
  const count = rint(H.min, H.max) + Math.floor(d / 4) + camp
  const at = first ? 22 * 60 : S.time + rand(20, 32) * 60
  S.nextRaid = { at, size: sizeIdx, count, warned: false }
}
export function raidIntel() {
  const r = S.nextRaid
  if (!r) return null
  const towers = countType('watchtower')
  const H = HORDES[r.size]
  return { in: r.at - S.time, name: towers ? H.name : 'Unknown horde', count: towers ? r.count : null, size: r.size, known: !!towers }
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
  const pinfo = powerInfo()
  for (const st of S.stations) if (st.type === 'turret' && pinfo.powered.has(st.id) && S.res.pammo > 5) def += STATIONS.turret.dmg[st.level - 1] / STATIONS.turret.rate[st.level - 1]
  const fence = FENCE[S.fence.level].hp * 0.08
  const horde = R.count * (10 + day() * 0.6)
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
      const l = Math.floor(S.res[k] * rand(0.2, 0.45))
      S.res[k] -= l
      report.lost[k] = l
    }
    if (chance(0.5) && defenders.length) {
      const v = pick(defenders)
      killSurvivor(v, 'Killed defending the camp')
      report.dead.push(v.first)
    }
    addMoraleEvent('The horde broke through', -18, 2)
  } else addMoraleEvent('Held the wall', 8, 1)
  S.stats.raids++
  S.stats.kills += Math.round(R.count * clamp(ratio, 0.3, 1))
  completeGoal('surviveHorde')
  log(`${offline ? 'While you were away, a' : 'A'} horde of ${R.count} hit the camp. ${report.injured.length ? report.injured.length + ' injured.' : 'No one was hurt.'}${report.dead.length ? ' ' + report.dead.join(', ') + ' did not make it.' : ''}`, report.dead.length ? 'bad' : '')
  bus.emit('raidResolved', report)
  return report
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
  const bundles = [['food', 30], ['water', 30], ['wood', 40], ['scrap', 40], ['metal', 25], ['cloth', 25], ['parts', 6], ['electronics', 4], ['chemicals', 6], ['gunpowder', 10], ['fuel', 15], ['pammo', 60], ['rammo', 30], ['shells', 20], ['meds', 4], ['medkit', 1], ['molotov', 2], ['module', 1]]
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
