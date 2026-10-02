// The station drawer: workers, what the station is doing and producing,
// crafting orders (queue, recipes, mods, repairs), automation, upgrades.
import { RES, STATIONS, RECIPES, MODS, ITEMS, QUALITY, OCCUPATIONS, SKILLS, SKILL_KEYS, REPAIR, SEC_PER_DAY, FENCE, ALT_RECIPES, BELTS, BELT_BONUS, BELT_STACK, SIGNAL, RESEARCH, CORE_SLOTS, VEHICLES, PEN_FENCE } from '../game/data.js'
import { NET,
  S, day, workersOf, slots, assign, workEff, bestFor, upgradeCost, startUpgrade, demolish, installModule, recipesFor, modsFor, queueMax, orderRecipe,
  orderMod, orderRepair, cancelOrder, moveOrder, orderSpec, qualityOdds, itemOf, itemName, canAfford, survivorStats, capOf, bedCount, getS, ownerOf,
  repairCost, repairTime, gameDur, stationSize, signalNeed, deliverSignal, signalPhase, signalCost, signalBlocked, researchCost,
  researchLock, startResearch, cancelResearch, pickAlt, altsFor, researchDone, installCore, removeCore, coreBoost, hasFlag, msDone,
  canControl,
} from '../game/state.js'
import { recycleQueue, salvageOf, flockOf, flockMax, flockFactor, penRisk, buildPenFence, mendPenFence, mendCost, buyAnimal, animalCost, stationFlow, power, powerNeed, linkPower, isAutomated, stationRate, solarOutput, windOutput, boilerFuel, sourcePower, HAND_RATE, kitchenSaving, constructSpeed, raidIntel, activeRecipe, activeSingle, recipeUnlocked, recipeTarget } from '../game/economy.js'
import { linksOf, inputsOf, outputsOf, linkPerDay, linkState, upgradeCostOf, upgradeLink, upgradeLocked, removeLink, beltBonus, pulled, portsOf, linkAt, isDepot, nodeKind, nodeRes, insertNode, splitProblem, firstOut, setFirst, hopperHold, stackOf, NODE_RULES, beltSpeed as beltSpeedOf } from '../game/belts.js'
import { flowsNow, limitText } from '../game/rates.js'
import { slotGroups, groupFill, STOCK_GROUPS } from '../scenes/basestock.js'
import { pinButton } from './watch.js'
import { isRunning, setRunning } from './multipanel.js'
import { sfx } from '../core/audio.js'
import { bus, h, fmt, clamp } from '../core/util.js'
import { icon } from './icons.js'
import { leaderChip } from './netui.js'
import { costList, resChip, resIcon, bar, qualityTag, condBar, itemCard, seg, stepper, plural } from './common.js'

const TYPE_ICON = { hopper: 'hopper', recycler: 'recycle', coop: 'hen', goatpen: 'wool', priority: 'belt' }
const CAT_ICON = { living: 'gate', production: 'production', crafting: 'hammer', defense: 'shield', power: 'bolt', logistics: 'belt', storage: 'box' }
const tabState = {}

export function renderStation(ui, id) {
  const st = S.stations.find((s) => s.id === id)
  if (!st) return null
  const D = STATIONS[st.type]
  const pinfo = power()
  const body = []
  // the same calm reading as the label over the building: a problem once it
  // has lasted a few seconds, a pace when it's only held back by its belts
  const v = ui.game.base?.stationViews?.get(st.id)
  const warn = v ? v.warnShow : st.stalled
  const busy = v ? v.runK > 0.3 : st.active
  const status = st.building ? `Under construction` : warn ? warn : v?.pace ? v.pace : busy ? 'Working' : D.workers[Math.max(0, st.level - 1)] && !workersOf(st).length && !isAutomated(st, pinfo) ? 'Idle: no workers' : 'Ready'
  const sub = h('span', h('span.lvl', st.level ? `Level ${st.level}` : 'New'), ' · ', h('span' + (warn ? '.bad' : v?.pace ? '.warn' : busy ? '.good' : ''), status))
  body.push(h('p.desc', D.desc))
  if (st.building) body.push(constructionBlock(st))
  if (st.level > 0) {
    const ws = workerBlock(ui, st)
    if (ws) body.push(ws)
    if (D.machine) body.push(machineBlock(ui, st, pinfo))
    body.push(...effectBlock(ui, st, pinfo))
    const logi = logisticsBlock(ui, st)
    if (logi) body.push(logi)
    const chain = chainBlock(st)
    if (chain) body.push(chain)
    if (D.queue || st.type === 'infirmary') body.push(benchBlock(ui, st))
    if (D.auto) body.push(autoBlock(ui, st, pinfo))
  }
  if (st.type !== 'mast' && D.levels > 1) body.push(upgradeBlock(ui, st))
  if (!D.fixed) body.push(actionsBlock(ui, st))
  return ui.frame(D.name, sub, body, { icon: TYPE_ICON[st.type] || CAT_ICON[D.cat], extra: pinButton(ui, 'st', st.id) })
}

// ---------------------------------------------------------------- construction
function constructionBlock(st) {
  const b = st.building
  const sp = constructSpeed()
  const left = b.left / Math.max(0.01, sp)
  return h(
    'section.card',
    h('h3', st.level ? `Upgrading to level ${b.to}` : 'Construction', h('small', `${Math.round((1 - b.left / b.total) * 100)}%`)),
    bar(1 - b.left / b.total, 'build'),
    h('p.note', `About ${gameDur((left * 3))} at the current pace. Free survivors build faster: anyone without a job helps out.`),
  )
}

// ---------------------------------------------------------------- workers
function workerBlock(ui, st) {
  const D = STATIONS[st.type]
  const n = slots(st)
  if (!n) return null
  const ws = workersOf(st)
  const rows = []
  for (let i = 0; i < n; i++) {
    const s = ws[i]
    if (s) {
      const e = workEff(s, st.type)
      const perk = OCCUPATIONS[s.occ].fx.station?.[st.type]
      rows.push(
        h(
          'div.wslot.filled',
          h('img.por', { src: ui.game.portrait(s), onclick: () => ui.openSurvivor(s.id) }),
          h('div.ws-info', h('b', { onclick: () => ui.openSurvivor(s.id) }, s.name), h('span', OCCUPATIONS[s.occ].name, perk ? h('em.good', ` +${Math.round(perk * 100)}%`) : null, s.status === 'injured' ? h('em.bad', ' · injured') : null)),
          h('div.ws-eff', { 'data-tip': `Works at ${Math.round(e * 100)}% speed${D.skill ? ` (${SKILLS[D.skill].name} ${s.skills[D.skill]})` : ''}` }, `${Math.round(e * 100)}%`),
          canControl(s) ? h('button.mini', { onclick: () => (assign(s, null), sfx('click'), ui.refreshPanel()) }, 'Remove') : leaderChip(s),
        ),
      )
    } else rows.push(h('button.wslot.empty', { onclick: () => pickWorker(ui, st) }, h('i', { html: icon('plus') }), 'Assign a survivor'))
  }
  const best = bestFor(st.type)
  // one click: the free survivors best at this job fill the empty places
  const free = S.survivors.filter((s) => !s.job && s.status === 'ok' && canControl(s)).sort((a, b) => workEff(b, st.type) - workEff(a, st.type))
  const fill =
    ws.length < n && free.length
      ? h(
          'button.mini.fillbest',
          {
            'data-tip': `Assign ${free.slice(0, n - ws.length).map((s) => s.first).join(' and ')}: the free survivors best at this job`,
            onclick: () => {
              for (const s of free.slice(0, n - ws.length)) assign(s, st)
              sfx('select')
              ui.refreshPanel()
            },
          },
          'Fill with the best free',
        )
      : null
  return h('section.card', h('h3', 'Workers', h('small', `${ws.length}/${n}${D.skill ? ` · ${SKILLS[D.skill].name}` : ''}`), fill), rows, best.length ? h('p.note', 'Best: ', best.join(', ')) : null)
}
export function pickWorker(ui, st) {
  const D = STATIONS[st.type]
  const list = S.survivors.filter((s) => s.status !== 'mission' && s.status !== 'scout' && s.job !== st.id && canControl(s)).map((s) => ({ s, e: workEff(s, st.type) || 0.0001 }))
  list.sort((a, b) => b.e - a.e)
  let close
  close = ui.modal(
    h(
      'div',
      h('h2', `Assign to ${D.name}`),
      h('p.note', D.skill ? `Uses ${SKILLS[D.skill].name}. Occupation bonuses apply.` : 'Anyone can work here.'),
      h(
        'div.picklist',
        list.map(({ s, e }) => {
          const cur = s.job ? S.stations.find((x) => x.id === s.job) : null
          return h(
            'button.pickrow',
            {
              onclick: () => {
                assign(s, st)
                sfx('select')
                close()
                ui.refreshPanel()
              },
            },
            h('img.por', { src: ui.game.portrait(s) }),
            h('div.pr-main', h('b', s.name), h('span', OCCUPATIONS[s.occ].name, s.status === 'injured' ? h('em.bad', ' · injured') : null)),
            h('div.pr-job', cur ? `Now: ${STATIONS[cur.type].name}` : 'No job'),
            h('div.pr-eff' + (e >= 1.2 ? '.good' : e < 0.85 ? '.bad' : ''), `${Math.round(e * 100)}%`),
          )
        }),
      ),
    ),
    { actions: [h('button.btn.ghost', { onclick: () => close() }, 'Close')] },
  )
}

// ---------------------------------------------------------------- supply chain
// What a station takes in and hands on, and which stations sit on either side
// of it, worked out from the recipe data.
const ioCache = {}
function stationIO(type) {
  if (ioCache[type]) return ioCache[type]
  const D = STATIONS[type]
  const ins = new Set()
  const outs = new Set()
  const take = (o) => Object.keys(o || {}).forEach((k) => ins.add(k))
  const give = (o) => Object.keys(o || {}).forEach((k) => outs.add(k))
  if (D.recipe) {
    take(D.recipe.in)
    give(D.recipe.out)
    give(D.recipe.bonus)
  }
  for (const m of Object.values(D.recipes || {})) {
    take(m.in)
    give(m.out)
  }
  if (D.passive) give(D.passive)
  if (D.burn && !Array.isArray(D.burn)) take(D.burn)
  if (type === 'generator') ins.add('fuel')
  if (type === 'boiler') for (const k of STATIONS.boiler.fuels) ins.add(k)
  if (type === 'turret') ins.add('pammo')
  for (const r of RECIPES) {
    if (r.station !== type) continue
    take(r.in)
    give(r.out)
  }
  take(REPAIR[type])
  for (const M of Object.values(MODS)) if (M.bench === type) take(M.cost)
  return (ioCache[type] = { ins: [...ins], outs: [...outs] })
}
const ALSO_USED = { food: 'everyone eats', water: 'everyone drinks', meds: 'healing', pammo: 'pistols, SMGs', rammo: 'rifles', shells: 'shotguns', fuel: 'the van', medkit: 'squads on runs', molotov: 'squads on runs', pipebomb: 'squads on runs', noisemaker: 'squads on runs', module: 'automating a station' }
function resTag(k) {
  return h('span.ci', { style: { '--c': RES[k].color } }, h('i.ic', { html: resIcon(k) }), RES[k].short || RES[k].name)
}
function stationNames(types, extra) {
  const built = (t) => S.stations.some((x) => x.type === t && x.level > 0)
  const list = types.sort((a, b) => built(b) - built(a))
  const shown = list.slice(0, 3).map((t) => h('span' + (built(t) ? '.have' : ''), { 'data-tip': built(t) ? 'Built' : 'Not built yet' }, STATIONS[t].name))
  const parts = [...shown]
  if (list.length > 3) parts.push(h('span', `+${list.length - 3} more`))
  if (extra) parts.push(h('span.use', extra))
  return h('span.chnames', parts)
}
function chainBlock(st) {
  const D = STATIONS[st.type]
  if (!['production', 'crafting'].includes(D.cat) && !['kitchen', 'infirmary', 'generator', 'boiler', 'turret'].includes(st.type)) return null
  const io = stationIO(st.type)
  if (!io.ins.length && !io.outs.length) return null
  const types = Object.keys(STATIONS).filter((t) => t !== st.type)
  const rows = []
  if (io.ins.length) {
    rows.push(h('div.chhead', 'Takes'))
    for (const k of io.ins) {
      const from = types.filter((t) => stationIO(t).outs.includes(k))
      rows.push(h('div.chrow', resTag(k), h('i.chdir', '←'), from.length ? stationNames(from, k === 'cloth' || k === 'electronics' || k === 'chemicals' ? 'runs' : null) : h('span.chnames', h('span.use', 'runs and the trader'))))
    }
  }
  if (io.outs.length) {
    rows.push(h('div.chhead', 'Supplies'))
    for (const k of io.outs) {
      const to = types.filter((t) => stationIO(t).ins.includes(k))
      rows.push(h('div.chrow', resTag(k), h('i.chdir', '→'), to.length || ALSO_USED[k] ? stationNames(to, ALSO_USED[k]) : h('span.chnames', h('span.use', 'the trader'))))
    }
  }
  return h('section.card.chain', h('h3', 'Supply chain', h('small', 'white: built · grey: not yet')), rows)
}

// ---------------------------------------------------------------- what it does
function rateRow(label, flow, keys) {
  const ents = (keys || Object.keys(flow)).filter((k) => Math.abs(flow[k] || 0) >= 0.05)
  if (!ents.length) return null
  return h('div.kv', h('span', label), h('span.flows', ents.map((k) => h('span.flow' + (flow[k] > 0 ? '.up' : '.down'), resChip(k, Math.abs(flow[k]).toFixed(Math.abs(flow[k]) < 10 ? 1 : 0), flow[k] > 0 ? '+' : '−')))))
}
function effectBlock(ui, st, pinfo) {
  const D = STATIONS[st.type]
  const out = []
  const lv = st.level
  const flow = stationFlow(st, pinfo)
  const card = (title, ...kids) => h('section.card', h('h3', title), ...kids)
  if (D.livestock) out.push(livestockCard(ui, st, pinfo))
  switch (st.type) {
    case 'campfire':
      out.push(card('The fire', h('p', 'Free survivors gather here in the evening. Keeps morale up (+4).')))
      break
    case 'bunkhouse':
      out.push(card('Beds', h('div.kv', h('span', 'This bunkhouse'), h('b', plural(D.beds[lv - 1], 'bed'))), h('div.kv', h('span', 'Whole camp'), h('b', `${S.survivors.length} / ${bedCount()} in use`)), h('div.kv', h('span', 'Comfort'), h('b', `+${lv * 4} morale`))))
      break
    case 'storage':
    case 'crates':
    case 'shed':
    case 'warehouse':
      out.push(card('Capacity', h('div.kv', h('span', 'Adds'), h('b', `+${D.cap[lv - 1]} storage`)), h('div.kv', h('span', 'Basic goods now hold'), h('b', fmt(capOf('wood')))), h('div.kv', h('span', 'Camp stores'), h('b', plural(S.stations.filter((x) => isDepot(x) && x.level > 0).length, 'store'))), h('p.note', 'Every store adds to one shared camp store: goods belted into any of them can be belted out of any other. Valuable goods (parts, electronics, medicine) store less; ammunition stores more.')))
      if (lv > 0) {
        // what the pallets here show, and how full each kind is across the camp
        const mine = [...new Set(slotGroups(st))]
        const rest = STOCK_GROUPS.filter((g) => !mine.includes(g))
        const row = (g, here) => h('div.stockrow' + (here ? '.here' : ''), h('span', g.name), bar(groupFill(g), 'stock'), h('small', `${Math.round(groupFill(g) * 100)}%`))
        out.push(card('Stockpiles', h('p.note', 'The piles on the pallets grow as the camp\'s stock of each kind rises. More stores show more kinds.'), mine.map((g) => row(g, true)), rest.length ? h('details.stockmore', h('summary', 'Everything else'), rest.map((g) => row(g, false))) : null))
      }
      break
    case 'hopper': {
      const k = nodeRes(st)
      const n = stackOf(k || 'scrap')
      const held = k ? (st.buf?.out?.[k] || 0) / n : 0
      const cap = hopperHold(st)
      out.push(card('Tank', h('div.kv', h('span', 'Holds'), h('b', `${Math.floor(held)} / ${cap} loads${k && n > 1 ? ` (${n} ${RES[k].name.toLowerCase()} each)` : ''}`)), bar(held / cap, 'hop'), h('p.note', k ? `${RES[k].name}: fills when more comes in than goes out, and empties when the belt in runs dry.` : 'Empty. Belt goods in and out and it fills when more arrives than leaves.'), lv < D.levels ? h('p.note.dim', `Level ${lv + 1} holds ${D.hold[lv]} loads.`) : null))
      break
    }
    case 'kitchen': {
      const sav = kitchenSaving()
      out.push(card('Meals', h('div.kv', h('span', 'Food saved'), h('b', `${Math.round(sav * 100)}%`)), h('div.kv', h('span', 'Morale'), h('b', st.active ? `+${D.morale[lv - 1]}` : '—')), rateRow('Uses', flow), h('p.note', 'A cook stretches every ration. Chefs stretch them further and lift morale more.')))
      break
    }
    case 'infirmary': {
      const pts = (st.patients || []).map((id) => getS(id)).filter(Boolean)
      out.push(
        card(
          'Patients',
          pts.length
            ? pts.map((p) => h('div.patient', h('img.por.sm', { src: ui.game.portrait(p) }), h('b', p.first), bar(p.hp / survivorStats(p).maxHp, 'hp', `${Math.round(p.hp)} / ${survivorStats(p).maxHp}`)))
            : h('p.note', 'Nobody needs care right now. Between patients the medics work through the orders below.'),
          h('div.kv', h('span', 'Beds'), h('b', D.beds[lv - 1])),
        ),
      )
      break
    }
    case 'training': {
      const opts = [['auto', 'Weakest'], ...['melee', 'ranged', 'scavenge', 'build', 'medic'].map((k) => [k, SKILLS[k].name])]
      out.push(card('Drill', h('p.note', 'Trainees practise a skill. "Weakest" picks melee or ranged, whichever is lower.'), seg(opts, st.trainSkill || 'auto', (v) => ((st.trainSkill = v), ui.refreshPanel())), h('div.kv', h('span', 'Learning rate'), h('b', `${D.xpRate[lv - 1]}× per trainee`))))
      break
    }
    case 'radio': {
      out.push(card('Broadcasting', h('div.kv', h('span', 'Newcomers arrive'), h('b', `${Math.round((1 / D.recruit[lv - 1]) * 100)}% as often`)), h('div.kv', h('span', 'Distress calls'), h('b', 'More frequent')), h('div.kv', h('span', 'Trader stock'), h('b', `+${lv * 2} items${lv >= 2 ? ', modules' : ''}`)), h('p.note', 'An operator makes it work harder.')))
      break
    }
    case 'collector':
      out.push(card('Rainwater', rateRow('Collects', flow), h('p.note', 'Doubles in the rain. Nobody needs to work here.')))
      break
    case 'research':
      out.push(...researchBlock(ui, st))
      break
    case 'mast': {
      const P = signalPhase()
      const need = signalNeed()
      if (!P) {
        out.push(card('The Signal', h('p.good', 'All five phases are done. The mast calls the coast every night.')))
        break
      }
      const rows = Object.entries(signalCost(P)).map(([k, v]) => h('div.sig-row', resTag(k), bar((v - need[k]) / v, 'prod'), h('small', `${fmt(v - need[k])} / ${fmt(v)}`)))
      const lock = signalBlocked()
      if (lock) rows.push(h('p.note.bad', h('b', `${lock.short}. `), lock.text, ' ', h('a.link', { onclick: () => ui.openJournal() }, 'Journal')))
      out.push(
        card(
          `Phase ${S.signal.phase + 1} of ${SIGNAL.length}: ${P.name}`,
          h('p.note', P.desc),
          rows,
          h('div.kv', h('span', 'Hand over what storage has'), h('button.btn.small.go', { onclick: () => (deliverSignal() ? (sfx('build'), ui.toast('Delivered to the mast', 'good')) : (sfx('error'), ui.toast('Nothing in storage the mast still needs', 'bad')), ui.refreshPanel()) }, 'Deliver')),
          h('p.note', 'Belts into the mast deliver as they arrive. ', h('a.link', { onclick: () => ui.openProgress() }, 'All phases')),
        ),
      )
      break
    }
    case 'generator':
    case 'boiler':
      out.push(engineCard(ui, st, pinfo, flow))
      break
    case 'wind': {
      const w = windOutput()
      out.push(card('Wind', h('div.kv', h('span', 'Full output'), h('b', `${D.wind[lv - 1]} power`)), h('div.kv', h('span', 'Right now'), h('b', `${(D.wind[lv - 1] * w).toFixed(1)} power`)), bar(w, 'prod', `${Math.round(w * 100)}% wind`), h('p.note', 'Storms, rain and snow turn it hardest; fog leaves it nearly still. Spare output charges the battery banks.')))
      break
    }
    case 'battery': {
      const cap = D.store[lv - 1]
      const c = st.charge || 0
      const f = st.flow || 0
      out.push(card('Stored power', bar(c / cap, 'prod', `${fmt(c)} / ${cap}`), h('div.kv', h('span', 'Right now'), h('b' + (f > 0.05 ? '.good' : f < -0.05 ? '.bad' : ''), f > 0.05 ? `Charging · +${f.toFixed(1)}` : f < -0.05 ? `Supplying · ${(-f).toFixed(1)}` : 'Holding')), h('div.kv', h('span', 'Can supply'), h('b', `up to ${D.rate[lv - 1]} power`)), h('p.note', `Charges from spare sun and wind, never from fuel. Full, it runs ${D.rate[lv - 1]} power for ${Math.round(cap / D.rate[lv - 1])} hours.`)))
      break
    }
    case 'solar':
      out.push(card('Sunlight', h('div.kv', h('span', 'Peak output'), h('b', `${D.solar[lv - 1]} power`)), h('div.kv', h('span', 'Right now'), h('b', `${(D.solar[lv - 1] * solarOutput()).toFixed(1)} power`)), h('p.note', 'Nothing at night; clouds and rain cut it down.')))
      break
    case 'watchtower':
      out.push(card('Lookout', h('div.kv', h('span', 'Guard damage'), h('b', `+${Math.round(D.towerDmg[lv - 1] * 100)}%`)), h('div.kv', h('span', 'Horde intel'), h('b', 'Size and count')), h('p.note', 'A guard up here shoots night wanderers before they reach the wall, and fights from height during hordes.')))
      break
    case 'turret':
      out.push(card('Auto-turret', h('div.kv', h('span', 'Damage'), h('b', `${D.dmg[lv - 1]} · ${(1 / D.rate[lv - 1]).toFixed(1)} shots/s`)), h('div.kv', h('span', 'Range'), h('b', `${D.range[lv - 1]} m`)), h('div.kv', h('span', 'Uses'), h('b', `${D.power} power, 9mm ammo`)), powerToggle(ui, st, pinfo)))
      break
    case 'floodlight':
      out.push(card('Floodlight', h('p.note', 'Lights the ground outside the wall at night. Defenders nearby shoot at full accuracy in the dark.'), h('div.kv', h('span', 'Uses'), h('b', `${D.power} power at night`)), powerToggle(ui, st, pinfo)))
      break
    case 'recycler':
      out.push(recyclerCard(ui, st))
      out.push(recipesBlock(ui, st, flow))
      break
    default:
      if (D.recipes) {
        out.push(recipesBlock(ui, st, flow))
        break
      }
      if (D.recipe) {
        const R = activeSingle(st)
        const tm = Array.isArray(R.time) ? R.time[lv - 1] : R.time
        const rows = [rateRow('Per day', flow), h('div.kv', h('span', 'One batch'), h('span', costList(R.in, { small: true }), ' → ', costList(R.out, { small: true, have: false }), h('small', ` · ${tm}s of work`)))]
        if (R.bonus) rows.push(h('p.note', 'Sometimes turns up ', Object.keys(R.bonus).map((k) => RES[k].name.toLowerCase()).join(' and '), '.'))
        if (D.limit) rows.push(limitControl(ui, st, Object.keys(R.out)[0]))
        const alt = altPicker(ui, st, '_')
        if (alt) rows.push(alt)
        rows.push(bar(clamp(st.progress || 0, 0, 1), 'prod'))
        out.push(card('Production', ...rows))
      }
  }
  return out
}
// Hens or goats: how many, how they grow, and the fence that keeps the
// night off them.
function livestockCard(ui, st, pinfo) {
  const D = STATIONS[st.type]
  const goat = D.livestock === 'goat'
  const n = flockOf(st)
  const max = flockMax(st)
  const word = (k) => `${k} ${goat ? (k === 1 ? 'goat' : 'goats') : k === 1 ? 'hen' : 'hens'}`
  const F = PEN_FENCE[st.pen || 0]
  const next = PEN_FENCE[(st.pen || 0) + 1]
  const risk = day() < (st.grace || 0) ? 0 : penRisk(st, pinfo)
  const keeper = workersOf(st).some((s) => s.status === 'ok')
  const hpMax = F.hp || 0
  const hp = st.penHp ?? hpMax
  const mend = mendCost(st)
  return h(
    'section.card.flock',
    h('h3', h('span', h('i.inl', { html: icon(goat ? 'wool' : 'hen') }), goat ? 'The herd' : 'The flock'), h('small', `${word(n)} of ${max}`)),
    h('div.flockrow', Array.from({ length: max }, (_, i) => h('i.fa' + (i < n ? '.on' : ''), { html: icon(goat ? 'wool' : 'hen') }))),
    h('p.note', n >= max ? `As many as the ${goat ? 'pen' : 'coop'} can hold${st.level < D.levels ? '. Upgrade it for room for more' : ''}.` : !keeper ? `Nobody is looking after them, so ${goat ? 'no kids are born' : 'no chicks hatch'}. Assign a keeper.` : `With a keeper and the camp fed, ${goat ? 'a kid is born' : 'chicks hatch'} every few days.`),
    h('p.note', `They make ${Math.round(flockFactor(st) * 100)}% of what a full ${goat ? 'pen' : 'coop'} would.`),
    n < max ? h('button.btn.small.ghost', { disabled: !canAfford(animalCost(st)), 'data-tip': `A trader on the road sells ${goat ? 'goats' : 'hens'}: ${costTip(animalCost(st))}`, onclick: () => (buyAnimal(st) ? (sfx('coin'), ui.toast(`Another ${goat ? 'goat' : 'hen'} in the ${goat ? 'pen' : 'coop'}`, 'good')) : sfx('error'), ui.refreshPanel()) }, `Buy a ${goat ? 'goat' : 'hen'}`) : null,
    h('h4.subhead', 'Fence'),
    h('div.kv', h('span', F.name), st.pen ? h('b' + (hp <= 1 ? '.bad' : ''), `${hp} / ${hpMax}`) : h('b.bad', 'none')),
    h('div.kv', { 'data-tip': 'Watchtower guards and lit floodlights lower it too.' }, h('span', 'Chance the infected get at them tonight'), h('b' + (risk > 0.2 ? '.bad' : risk > 0.08 ? '.warn' : '.good'), `${Math.round(risk * 100)}%`)),
    h(
      'div.bactions',
      next ? h('button.btn.small' + (st.pen ? '' : '.go'), { disabled: !canAfford(next.cost), 'data-tip': `<b>${next.name}</b>${costTip(next.cost)}`, onclick: () => (buildPenFence(st) ? (sfx('build'), ui.toast(`${next.name} up`, 'good')) : sfx('error'), ui.refreshPanel()) }, st.pen ? `Upgrade to ${next.name.toLowerCase()}` : `Put up a ${next.name.toLowerCase()}`) : null,
      st.pen && hp < hpMax ? h('button.btn.small.ghost', { disabled: !canAfford(mend), 'data-tip': `Mend the fence: ${costTip(mend)}`, onclick: () => (mendPenFence(st) ? sfx('build') : sfx('error'), ui.refreshPanel()) }, 'Mend') : null,
    ),
    h('p.note.dim', 'Their noise draws the infected at night: every coop or pen adds a few to each horde.'),
  )
}
// The recycler: what it's stripping now, what's queued, standing orders.
function recyclerCard(ui, st) {
  const cur = st.gear ? itemOf(st.gear.uid) : null
  const queue = recycleQueue(st).filter((it) => it !== cur)
  const give = (it) => costList(salvageOf(it, st.level), { small: true, have: false })
  const toggle = (key, label, tip) => h('label.rtoggle', { 'data-tip': tip }, h('input', { type: 'checkbox', checked: !!st[key], onchange: (e) => ((st[key] = e.target.checked), sfx('click'), ui.refreshPanel()) }), h('span', label))
  return h(
    'section.card.recy',
    h('h3', h('span', h('i.inl', { html: icon('recycle') }), 'Recycling'), h('small', st.recycled ? `${st.recycled} stripped so far` : 'gear first, then junk')),
    cur
      ? h('div.rnow', h('div.rline', h('b', itemName(cur)), h('span.rgive', '→ ', give(cur))), bar(clamp(st.progress || 0, 0, 1), 'prod'))
      : h('p.note', queue.length ? 'Starting on the next piece.' : 'No gear waiting. Mark gear in Items with "Recycle", or set a standing order below. Meanwhile it works through junk.'),
    queue.length
      ? h(
          'div.rqueue',
          queue.slice(0, 8).map((it) =>
            h(
              'div.rrow',
              h('span', itemName(it)),
              h('span.rgive', give(it)),
              it.recycle ? h('button.mini', { 'data-tip': 'Keep it: take it off the list', onclick: () => ((it.recycle = false), sfx('click'), ui.refreshPanel()) }, 'Keep') : h('small.dim', (it.cond ?? 100) <= 0 ? 'broken' : 'crude'),
            ),
          ),
          queue.length > 8 ? h('small.dim', `and ${queue.length - 8} more`) : null,
        )
      : null,
    h(
      'div.rorders',
      toggle('autoBroken', 'Strip broken gear', 'Anything worn down to nothing that nobody is carrying goes to the recycler without asking.'),
      toggle('autoCrude', 'Strip crude spares', 'Crude-quality gear nobody is carrying goes to the recycler without asking.'),
    ),
    h('button.btn.small.ghost', { onclick: () => ui.openItems('free') }, h('i', { html: icon('items') }), ' Choose gear to recycle'),
  )
}
// Choose between a recipe and the alternates researched for it.
function altPicker(ui, st, base) {
  const known = altsFor(st.type, base)
  if (!known.length) return null
  const cur = st.alts?.[base] || ''
  const opts = [['', 'Standard'], ...known.map((a) => [a, ALT_RECIPES[a].name])]
  return h(
    'div.altpick',
    h('span', h('i.inl', { html: icon('schematic') }), 'Recipe'),
    seg(opts, cur, (v) => {
      st.alts = st.alts || {}
      if (v) st.alts[base] = v
      else delete st.alts[base]
      st.progress = 0
      ui.refreshPanel()
    }),
    cur ? h('small', ALT_RECIPES[cur].desc) : null,
  )
}
// ---------------------------------------------------------------- research desk
function researchBlock(ui, st) {
  const out = []
  const card = (title, ...kids) => h('section.card', h('h3', ...[].concat(title)), ...kids)
  const pick = S.research.pick
  if (pick) {
    out.push(
      card(
        ['Choose an alternate recipe', h('small', 'from the schematic you studied')],
        h('p.note', 'Pick one. The other two go back into the pile for a later schematic.'),
        h(
          'div.altcards',
          pick.map((a) => {
            const A = ALT_RECIPES[a]
            const D = STATIONS[A.station]
            const base = A.base === '_' ? D.recipe : D.recipes[A.base]
            const R = { ...base, ...A.recipe }
            const have = S.stations.some((x) => x.type === A.station)
            return h(
              'div.altcard',
              h('b', A.name),
              h('small', `${D.name}${have ? '' : ' (not built yet)'}`),
              h('p', A.desc),
              R.in && R.out ? h('div.ab', costList(R.in, { small: true, have: false }), ' → ', costList(R.out, { small: true, have: false })) : null,
              h('button.btn.small.go', { onclick: () => (pickAlt(a) && (sfx('complete'), ui.toast(`${A.name} unlocked at the ${D.name}`, 'good')), ui.refreshPanel()) }, 'Choose'),
            )
          }),
        ),
      ),
    )
  }
  const p = st.project
  if (p) {
    const R = RESEARCH[p.id]
    const rate = stationRate(st, power())
    out.push(card(['Researching', h('small', rate > 0 ? `${gameDur((p.left / rate) * 3)} left` : 'Needs a researcher')], h('b', R.name), bar(1 - p.left / p.total, 'prod'), h('div.kv', h('span.note', R.desc), h('button.mini', { onclick: () => (cancelResearch(st), sfx('click'), ui.refreshPanel()), 'data-tip': 'Stop and get the materials back' }, 'Cancel'))))
  }
  const cats = [...new Set(Object.values(RESEARCH).map((r) => r.cat))]
  const rows = []
  for (const c of cats) {
    rows.push(h('h4', c))
    for (const [id, R] of Object.entries(RESEARCH)) {
      if (R.cat !== c) continue
      const done = researchDone(id) && !R.repeat
      const lock = researchLock(id, st)
      const ok = !lock && !p && canAfford(researchCost(id))
      rows.push(
        h(
          'div.recipe.rs' + (done ? '.done' : lock ? '.locked' : ''),
          h('div.r-main', { 'data-tip': `<b>${R.name}</b>${R.desc}` }, h('b', R.name, done ? h('em.good', ' · done') : null), h('span.r-sub', R.desc), done ? null : h('span.r-sub', costList(researchCost(id), { small: true }), h('small', ` · ${R.time}s of work`))),
          done ? h('i.inl.good', { html: icon('check') }) : lock && lock !== 'Done' ? h('span.r-lock', h('i', { html: icon('lock') }), lock) : h('button.mini', { disabled: !ok, onclick: () => (startResearch(st, id) ? (sfx('click'), ui.toast(`Researching ${R.name}`)) : sfx('error'), ui.refreshPanel()) }, p ? 'Busy' : 'Start'),
        ),
      )
    }
  }
  out.push(card(['Projects', h('small', `${Object.keys(S.research.done).length} done · ${S.research.alts.length} alternates known`)], h('p.note', 'Schematics and specimens come from supply runs: schematics from offices, schools and labs, specimens from the special infected.'), ...rows))
  return out
}
function limitControl(ui, st, outKey) {
  const steps = [50, 100, 150, 200, 300, 400, 600, 1e9]
  const i = Math.max(0, steps.findIndex((v) => v >= (st.limit ?? 1e9)))
  return h('div.kv', h('span', `Stop at ${RES[outKey].name.toLowerCase()}`), stepper(i, 0, steps.length - 1, (j) => ((st.limit = steps[j] >= 1e9 ? null : steps[j]), ui.refreshPanel()), (j) => (steps[j] >= 1e9 ? 'Never' : steps[j])))
}
// Steam engines and generators: output, fuel, and a switch.
function engineCard(ui, st, pinfo, flow) {
  const D = STATIONS[st.type]
  const lit = pinfo.srcs.some((x) => x.st === st)
  const k = st.type === 'generator' ? 'fuel' : boilerFuel(st)
  const perDay = k ? -(flow[k] || 0) : 0
  const store = k ? (S.res[k] || 0) + (st.buf?.in?.[k] || 0) : 0
  const rows = [
    h('div.kv', h('span', 'Capacity'), h('b', `${fmt(sourcePower(st))} power${workersOf(st).length ? ` (with ${st.type === 'boiler' ? 'stoker' : 'operator'})` : ''}`)),
    h('div.kv', h('span', 'Running at'), h('b', lit ? `${Math.round(pinfo.load * 100)}% · ${fmt(st.out || 0)} power` : h('em.bad', st.powerOn === false ? 'Switched off' : st.type === 'boiler' ? 'No wood or coal' : 'No fuel'))),
    h('div.kv', h('span', 'Camp grid'), h('b', `${fmt(pinfo.used)} used of ${fmt(pinfo.supply)}`)),
    k ? h('div.kv', h('span', `Burning ${RES[k].name.toLowerCase()}`), h('b', perDay > 0.05 ? `${perDay.toFixed(1)} a day · ${store > 0 ? `${(store / perDay).toFixed(1)} days left` : 'empty!'}` : 'Idling: sun, wind or batteries cover it')) : null,
  ]
  if (st.type === 'boiler') {
    rows.push(h('div.kv', h('span', 'Fuel'), seg([['', 'Coal first'], ['wood', 'Wood first']], st.fuelPick || '', (v) => ((st.fuelPick = v || null), ui.refreshPanel()))))
    rows.push(h('p.note', 'Coal burns three times as long as wood. Belt wood or coal straight in, or it hauls from storage. It only burns as hard as the camp needs.'))
  } else rows.push(h('p.note', 'Only burns fuel for the power the camp is drawing.'))
  rows.push(h('div.kv', h('span', ''), h('button.mini', { onclick: () => ((st.powerOn = st.powerOn === false), ui.refreshPanel()) }, st.powerOn === false ? 'Start it up' : 'Shut it down')))
  return h('section.card', h('h3', 'Power'), rows)
}
// Machines run at full speed on power and are hand-cranked at half without.
function machineBlock(ui, st, pinfo) {
  const D = STATIONS[st.type]
  const working = workersOf(st).some((s) => s.status === 'ok') || isAutomated(st, pinfo)
  const on = pinfo.powered.has(st.id)
  const off = st.powerOn === false
  return h(
    'section.card.machine' + (on ? '.on' : ''),
    h('div.kv', h('span', h('i.inl', { html: icon('bolt') }), `Draws ${D.machine} power while working`), off ? h('em', 'Switched off: by hand') : !working ? h('em', 'Idle') : on ? h('em.good', 'Powered · full speed') : h('em.bad', `No power · hand-cranked at ${Math.round(HAND_RATE * 100)}%`)),
    h('div.kv', h('small.dim', pinfo.supply > 0 ? `Camp grid: ${fmt(pinfo.used)} used of ${fmt(pinfo.supply)}` : 'No power on the grid yet: build a Steam Engine (Power tab).'), h('button.mini', { onclick: () => ((st.powerOn = off ? undefined : false), ui.refreshPanel()) }, off ? 'Use power' : 'Run by hand')),
  )
}
function powerToggle(ui, st, pinfo) {
  const on = st.autoOn !== false
  return h('div.kv', h('span', pinfo.powered.has(st.id) ? h('em.good', 'Powered') : on ? h('em.bad', 'No power') : h('em', 'Switched off')), h('button.mini', { onclick: () => ((st.autoOn = !on), ui.refreshPanel()) }, on ? 'Switch off' : 'Switch on'))
}
// Multi-recipe production: Auto keeps every product near its target.
const TSTEP = { pammo: 20, rammo: 20, shells: 10, metal: 20, gunpowder: 10, chemicals: 5, parts: 5, wiring: 5, steel: 5, rubber: 5, cells: 2, circuits: 2, motors: 1, coils: 1, amps: 1, molotov: 1, pipebomb: 1 }
function recipesBlock(ui, st, flow) {
  const D = STATIONS[st.type]
  const mode = st.mode || 'auto'
  const ids = Object.keys(D.recipes)
  const outOf = (id) => Object.keys(activeRecipe(st, id).out)[0]
  const label = (id) => RES[outOf(id)].short || RES[outOf(id)].name
  const opts = [['auto', 'Auto'], ...ids.filter((id) => recipeUnlocked(st, id)).map((id) => [id, label(id)])]
  const rows = [
    h('p.note', 'Auto works on whichever product is furthest below its target and has what it needs. Pick one to make only that. Set a target to 0 to stop making it.'),
    seg(opts, mode, (v) => ((st.mode = v), ui.refreshPanel())),
  ]
  for (const id of ids) {
    const R = activeRecipe(st, id)
    const k = outOf(id)
    const lock = !recipeUnlocked(st, id)
    const step = TSTEP[k] ?? 5
    const tgt = recipeTarget(st, id)
    const alt = st.alts?.[id] ? ALT_RECIPES[st.alts[id]] : null
    rows.push(
      h(
        'div.ammorow' + (st.curMode === id && st.active ? '.on' : '') + (lock ? '.locked' : ''),
        h('span.an', { style: { '--c': RES[k].color }, 'data-tip': `<b>${RES[k].name}</b>${RES[k].desc}` }, RES[k].name, alt ? h('em.alt', ` · ${alt.name}`) : null),
        h('span.ah', fmt(S.res[k])),
        !lock && pulled(st, k)
          ? h('span.at', { 'data-tip': 'A belt takes this to another station, so it is made whenever the belt has room. Set the target to 0 to stop.' }, h('i.inl', { html: icon('belt') }), 'on demand', stepper(Math.round(tgt / step), 0, 60, (v) => ((st.targets[id] = v * step), ui.refreshPanel()), (v) => (v ? 'on' : 'off')))
          : lock
          ? h('span.at', h('i.inl', { html: icon('lock') }), `Level ${R.lvl}`)
          : h('span.at', 'target ', stepper(Math.round(tgt / step), 0, 60, (v) => ((st.targets[id] = v * step), ui.refreshPanel()), (v) => v * step)),
        h('span.ab', costList(R.in, { small: true }), ' → ', resChip(k, R.out[k]), h('small', ` ${R.time[st.level - 1]}s`)),
      ),
    )
    const picker = altPicker(ui, st, id)
    if (picker) rows.push(picker)
  }
  rows.push(rateRow('Per day now', flow))
  rows.push(bar(clamp(st.progress || 0, 0, 1), 'prod'))
  return h('section.card', h('h3', 'Production', h('small', st.curMode ? `Making ${RES[outOf(st.curMode)].name.toLowerCase()}` : st.stalled || '')), ...rows)
}

// ---------------------------------------------------------------- belts
// Which belts leave and arrive here, how full they run, and buttons to lay
// new ones. Anything without a belt is carried to and from storage by hand.
function currentRecipe(st) {
  const D = STATIONS[st.type]
  if (D.recipe) return activeSingle(st)
  if (D.recipes && st.curMode && D.recipes[st.curMode]) return activeRecipe(st, st.curMode)
  return null
}
const POD_COL = { in: '#2fc49e', out: '#f0962c', io: '#5a9ae0' }
const byId = (id) => S.stations.find((x) => x.id === id)
const perDay = (v) => (v === Infinity ? 'plenty' : v < 10 ? v.toFixed(1) : fmt(Math.round(v)))
// a pod's name tag: IN 1, OUT 2, HATCH 3
const podDir = (st, p) => {
  if (p.kind !== 'io') return p.kind
  const l = linkAt(st, p.i)
  return l ? (l.from === st.id ? 'out' : 'in') : 'io'
}
function podTag(st, p) {
  const kind = podDir(st, p)
  // numbered among the pods working the same way: OUT 1, OUT 2, IN
  const same = portsOf(st).filter((q) => podDir(st, q) === kind)
  const n = same.indexOf(p) + 1
  return h('span.podtag', { style: { '--c': POD_COL[kind] } }, `${kind === 'in' ? 'IN' : kind === 'out' ? 'OUT' : 'HATCH'}${same.length > 1 ? ' ' + n : ''}`)
}
// One belt, seen from one of its ends.
function beltRow(ui, st, l, p) {
  const out = l.from === st.id
  const other = byId(out ? l.to : l.from)
  const r = flowsNow().links.get(l.id)
  const cap = linkPerDay(l.tier, l.res)
  const now = (l.flow || 0) * SEC_PER_DAY
  const state = linkState(l)
  const T = BELTS[l.tier]
  return h(
    'div.prow.' + state,
    p ? podTag(st, p) : h('span.podtag', '—'),
    h(
      'div.pmain',
      h('div.pline', h('i.ic', { html: resIcon(l.res), style: { color: RES[l.res].color } }), h('b', RES[l.res].name), h('span.pdir', out ? 'to' : 'from'), h('button.plink', { onclick: () => other && ui.openStation(other.id) }, other ? STATIONS[other.type].name : '?'), h('span.ltier', { style: { '--c': T.color } }, `Mk${l.tier}`)),
      h('div.pline', h('span.lbar', { 'data-tip': `${perDay(now)} a day moving now; settles at ${perDay(r?.flow ?? now)}; the belt carries up to ${perDay(cap)}` }, bar((r?.flow ?? now) / cap, 'belt' + ((r?.flow ?? now) > cap * 0.95 ? '.max' : ''))), h('small.prate', `${perDay(r?.flow ?? now)}/${perDay(cap)} a day`)),
      r ? h('small.plimit' + (r.limit === 'belt' ? '.warn' : ''), limitText(l, r)) : null,
    ),
    h('button.mini', { 'data-tip': 'This belt: rates, upgrade, splitters', onclick: () => ui.openBelt(l), html: icon('belt') }),
  )
}
// Every pod on a building: what's plugged in and how it runs, or a button to
// plug a belt into a free one. Anything not belted goes by hand.
function logisticsBlock(ui, st) {
  const ports = portsOf(st)
  if (!ports.length) return null
  const D = STATIONS[st.type]
  const depot = isDepot(st)
  const node = nodeKind(st)
  const mine = linksOf(st)
  const R = currentRecipe(st)
  const bonus = depot || node ? 1 : beltBonus(st, R)
  const rows = []
  const add = (p) => {
    const l = linkAt(st, p.i)
    if (l) return rows.push(beltRow(ui, st, l, p))
  }
  const free = { in: [], out: [], io: [] }
  for (const p of ports) {
    if (linkAt(st, p.i)) add(p)
    else free[p.kind].push(p)
  }
  // belts from before pods existed, still on their old route
  for (const l of mine.filter((x) => x.legacy)) rows.push(beltRow(ui, st, l, null))
  const plug = (dir, list, label) =>
    list.length
      ? h('div.prow.free', h('span.podtag', { style: { '--c': POD_COL[list[0].kind === 'io' ? 'io' : dir] } }, `${list.length} free`), h('span.pfree', label), h('button.mini.ladd', { 'data-tip': dir === 'out' ? 'Lay a belt from here to a station that uses it, a depot or a splitter' : 'Lay a belt bringing goods here', onclick: () => ui.game.base.startLinking(st, null, dir, { port: list[0].i, fromPanel: true }) }, h('i', { html: icon('plus') }), dir === 'out' ? 'Belt out' : 'Belt in'))
      : null
  const list = (a) => a.map((k) => RES[k].name.toLowerCase()).join(', ')
  if (node) {
    const ins = mine.filter((l) => l.to === st.id).length
    const outL = mine.filter((l) => l.from === st.id)
    const outs = outL.length
    const R = NODE_RULES[node]
    if (R.ins === 1) {
      if (!ins) rows.push(plug('in', free.io, 'Bring a belt in first'))
      if (ins && outs < R.outs) rows.push(plug('out', free.io, R.outs === 1 ? 'Send a belt out' : `${R.outs - outs} more way${R.outs - outs > 1 ? 's' : ''} out`))
    } else {
      if (!outs) rows.push(plug('out', free.io, 'Send a belt out'))
      if (ins < R.ins && free.io.length) rows.push(plug('in', free.io, `${Math.min(R.ins - ins, free.io.length)} more way${Math.min(R.ins - ins, free.io.length) > 1 ? 's' : ''} in`))
    }
    // which belt a priority splitter serves first
    if (node === 'prio' && outs) {
      const first = firstOut(st, outL)
      rows.unshift(
        h(
          'div.prio',
          h('span', 'Goes first:'),
          h(
            'div.seg',
            outL.map((l) => {
              const to = byId(l.to)
              return h('button' + (l === first ? '.on' : ''), { 'data-tip': l === first ? 'This belt gets everything it can take' : 'Make this belt first in line', onclick: () => (setFirst(st, l), sfx('click'), ui.refreshPanel()) }, l === first ? h('i.inl', { html: icon('star') }) : null, to ? STATIONS[to.type].name : '?')
            }),
          ),
        ),
      )
    }
  } else if (depot) {
    rows.push(plug('out', free.io, 'Send goods from storage'), plug('in', free.io, 'Bring goods into storage'))
  } else {
    rows.push(plug('in', free.in, `Takes ${list(inputsOf(st).slice(0, 5))}${inputsOf(st).length > 5 ? '…' : ''}`), plug('out', free.out, `Sends ${list(outputsOf(st))}`))
  }
  // what still goes by hand
  if (!depot && !node && R) {
    const hand = []
    for (const k of Object.keys(R.in || {})) if (R.in[k] > 0 && !mine.some((l) => l.to === st.id && l.res === k)) hand.push([k, 'in'])
    for (const k of Object.keys(R.out || {})) if (!mine.some((l) => l.from === st.id && l.res === k)) hand.push([k, 'out'])
    if (hand.length) rows.push(h('div.lhaul', h('span', 'By hand:'), hand.map(([k, dir]) => h('button.lchip', { style: { '--c': RES[k].color }, 'data-tip': `${RES[k].name} is ${dir === 'in' ? 'fetched from' : 'carried to'} storage by hand. Click to belt it ${dir === 'in' ? 'in' : 'away'}.`, onclick: () => ui.game.base.startLinking(st, k, dir, { fromPanel: true }) }, h('i.ic', { html: resIcon(k) }), RES[k].short || RES[k].name, h('b', dir === 'in' ? '→' : '←')))))
  }
  // the building's pace, when its belts hold it back
  const run = flowsNow().stations.get(st.id)
  let pace = null
  if (run && run.maxB > 0.01 && run.B < run.maxB * 0.97 && run.limit) {
    const pct = Math.round((run.B / run.maxB) * 100)
    const k = RES[run.limit.res].name.toLowerCase()
    pace = h('p.note.warn', run.limit.kind === 'input' ? `Runs at ${pct}% of full speed: its belts bring too little ${k}.` : `Runs at ${pct}% of full speed: its ${k} can't leave any faster.`)
  }
  const NODE_HINT = {
    split: 'Each item goes to the next belt out in turn: two belts get half each, three a third. When one is full, its share goes to the others.',
    prio: 'The belt marked first gets everything it can take. Only when it backs up do the others get anything, shared between them in turn.',
    merge: 'Takes from whichever belt has an item waiting. The belt out carries at most its tier\'s rate.',
    hopper: 'Goods wait in the tank until the belt out has room, so a burst upstream or a pause downstream doesn\'t stop the line.',
  }
  const hint = node
    ? h('p.note', NODE_HINT[node])
    : depot
      ? h('p.note', 'Storage hatches work both ways: send any goods out to a station, or take goods in.')
      : bonus > 1
        ? h('p.note.good', `Belts save the hauling: working ${Math.round((bonus - 1) * 100)}% faster.`)
        : h('p.note', `Belt every input and every output to save the hauling: +${Math.round(BELT_BONUS * 100)}% speed for each side.`)
  const carried = node && nodeRes(st) ? h('small', `carries ${RES[nodeRes(st)].name.toLowerCase()}`) : h('small', bonus > 1 ? `+${Math.round((bonus - 1) * 100)}% speed` : mine.length ? `${mine.length} connected` : `${ports.length} pod${ports.length > 1 ? 's' : ''}`)
  return h('section.card.logi', h('h3', h('span', h('i.inl', { html: icon('belt') }), node ? 'Belts' : 'Pods and belts'), carried), hint, pace, rows.filter(Boolean))
}

// ---------------------------------------------------------------- one belt
export function renderBelt(ui, sel) {
  const l = (S.links || []).find((x) => x.id === sel?.id)
  if (!l) return null
  const a = byId(l.from)
  const b = byId(l.to)
  const r = flowsNow().links.get(l.id)
  const T = BELTS[l.tier]
  const cap = linkPerDay(l.tier, l.res)
  const now = (l.flow || 0) * SEC_PER_DAY
  const up = upgradeCostOf(l)
  const nm = (st) => (st ? STATIONS[st.type].name : '?')
  // where a splitter or merger would go: the tile clicked, else the first
  // good spot from the middle of the belt outward
  const spotFor = (kind) => {
    const tiles = []
    for (let i = 0; i < l.tiles.length; i += 2) tiles.push([l.tiles[i], l.tiles[i + 1]])
    if (sel.tile) tiles.unshift(sel.tile)
    const mid = tiles.length >> 1
    const order = sel.tile ? tiles : tiles.map((_, i) => tiles[mid + (i % 2 ? -1 : 1) * Math.ceil(i / 2)]).filter(Boolean)
    let why = null
    for (const [x, z] of order) {
      const w = splitProblem(l, x, z, kind)
      if (!w) return { x, z }
      why ??= w
      if (sel.tile) break
    }
    return { why: why || 'No room on this belt' }
  }
  const nodeBtn = (kind, label, tip) => {
    const sp = spotFor(kind)
    return h(
      'button.btn.small' + (sp.why ? '.ghost' : ''),
      {
        disabled: !!sp.why,
        'data-tip': `<b>${label}</b>${tip}<br>${costTip(STATIONS[kind].cost[0])}${sp.why ? `<br><span class="bad">${sp.why}</span>` : ''}`,
        onclick: () => {
          const res = insertNode(l, sp.x, sp.z, kind)
          if (typeof res === 'string') return ui.toast(res, 'bad'), sfx('error')
          sfx('build')
          ui.toast(`${STATIONS[kind].name} in. Click its free side to send a belt on.`, 'good')
          ui.openStation(res.id)
        },
      },
      label,
    )
  }
  const body = [
    h(
      'section.card',
      h('h3', 'Carrying', h('small', `${Math.round(l.len)} m · ${l.items.length} on it`)),
      h('div.kv', h('span', 'Goods'), h('b.resname', h('i.ic', { html: resIcon(l.res), style: { color: RES[l.res].color } }), RES[l.res].name)),
      h('div.kv', h('span', 'From'), h('button.plink', { onclick: () => a && ui.openStation(a.id) }, nm(a))),
      h('div.kv', h('span', 'To'), h('button.plink', { onclick: () => b && ui.openStation(b.id) }, nm(b))),
    ),
    h(
      'section.card',
      h('h3', 'Rate', h('small', 'per day')),
      h('div.kv', h('span', 'Moving now'), h('b', perDay(now))),
      r ? h('div.kv', h('span', { 'data-tip': 'Worked out from what the far ends make and use, once everything settles' }, 'Settles at'), h('b', perDay(r.flow))) : null,
      h('div.kv', h('span', `Most a ${T.name} carries`), h('b', perDay(cap))),
      h('div.kv', { 'data-tip': `${T.name}s draw ${BELTS[l.tier].power} power a metre, at least 0.1 a belt. With no power the belt stops.` }, h('span', 'Power'), l.off ? h('b.bad', `${linkPower(l).toFixed(1)} · stopped, no power`) : h('b.good', `${linkPower(l).toFixed(1)} · running`)),
      bar((r?.flow ?? now) / cap, 'belt' + ((r?.flow ?? now) > cap * 0.95 ? '.max' : '')),
      r ? h('p.note' + (r.limit === 'belt' ? '.warn' : ''), limitText(l, r)) : null,
      h('p.note.dim', `${T.name}: ${beltSpeedOf(l.tier).toFixed(2)} m a second, items ${BELTS[l.tier].gap} m apart = ${(beltSpeedOf(l.tier) / BELTS[l.tier].gap).toFixed(3)} a second${(BELT_STACK[l.res] || 1) > 1 ? ` (${BELT_STACK[l.res]} ${RES[l.res].name.toLowerCase()} each)` : ''} × ${SEC_PER_DAY} seconds a day = ${perDay(cap)} a day.`),
    ),
    h(
      'section.card',
      h('h3', 'Change it'),
      h(
        'div.bactions',
        up
          ? h('button.btn.small', { disabled: !canAfford(up) || upgradeLocked(l), 'data-tip': `<b>Upgrade to ${BELTS[l.tier + 1].name}</b>${BELTS[l.tier + 1].desc}<br>${costTip(up)}${upgradeLocked(l) ? '<br><span class="bad">Needs its milestone</span>' : ''}`, onclick: () => (upgradeLink(l) ? (sfx('build'), ui.toast(`${BELTS[l.tier].name} running`, 'good')) : sfx('error'), ui.refreshPanel()) }, h('i', { html: icon('up') }), ` ${BELTS[l.tier + 1].name}`)
          : h('span.lmax', 'Fastest belt'),
        nodeBtn('splitter', 'Splitter here', 'Cut the belt and put a splitter in: then belt its free sides on to more stations.'),
        nodeBtn('priority', 'Priority here', 'Cut the belt and put a priority splitter in: this belt keeps first call on everything, and only what backs up goes out its other sides.'),
        nodeBtn('merger', 'Merger here', 'Cut the belt and put a merger in: then bring a second line of the same goods into it.'),
        nodeBtn('hopper', 'Hopper here', 'Cut the belt and put a buffer hopper in: a tank that soaks up bursts so the machines after it keep running.'),
        h('button.btn.small.ghost.danger', { 'data-tip': 'Take the belt down. Half its materials come back, and what was on it goes to storage.', onclick: () => (removeLink(l), sfx('dismantle'), ui.closePanel()) }, 'Take down'),
      ),
      sel.tile ? h('p.note.dim', 'Splitters, mergers and hoppers go where you clicked the belt.') : h('p.note.dim', 'Tip: click a belt where you want a splitter, merger or hopper to go.'),
    ),
  ]
  return ui.frame(`${T.name} · ${RES[l.res].name}`, h('span', `${nm(a)} → ${nm(b)}`), body, { icon: 'belt' })
}
const costTip = (c) => Object.entries(c).map(([k, v]) => `${v} ${RES[k].name.toLowerCase()}`).join(', ')

// ---------------------------------------------------------------- crafting benches
function benchBlock(ui, st) {
  const tabs = [['orders', `Orders ${st.orders.filter((o) => !o.maint).length}/${queueMax(st)}`], ['recipes', 'Recipes']]
  if (modsFor(st.type).length) tabs.push(['mods', 'Mods'])
  if (REPAIR[st.type]) tabs.push(['repairs', 'Repairs'])
  const cur = tabState[st.id] && tabs.some(([t]) => t === tabState[st.id]) ? tabState[st.id] : st.orders.length ? 'orders' : 'recipes'
  const content = cur === 'orders' ? ordersTab(ui, st) : cur === 'recipes' ? recipesTab(ui, st) : cur === 'mods' ? modsTab(ui, st) : repairsTab(ui, st)
  return h('section.card.bench', h('div.tabs', tabs.map(([t, label]) => h('button' + (t === cur ? '.on' : ''), { onclick: () => ((tabState[st.id] = t), ui.refreshPanel()) }, label))), content)
}
function oddsLine(st) {
  const o = qualityOdds(st)
  return h('div.odds', { 'data-tip': 'Chance of each quality for items made here. Better crafters, occupation perks (Gunsmith, Tailor, Mechanic), Steady Hands and bench level all help.' }, ['crude', 'standard', 'fine', 'master'].map((k, i) => h('span', { style: { '--q': QUALITY[i].color, flex: Math.max(0.02, o[k]) } }, `${QUALITY[i].name} ${Math.round(o[k] * 100)}%`)))
}
function orderName(o) {
  if (o.kind === 'recipe') {
    const r = RECIPES.find((x) => x.id === o.recipe)
    return r.vehicle ? VEHICLES[r.vehicle].name : r.item ? ITEMS[r.item].name : `${Object.values(r.out)[0]} ${RES[Object.keys(r.out)[0]].name}`
  }
  const it = itemOf(o.item)
  if (o.kind === 'mod') return `${MODS[o.mod].name} → ${it ? itemName(it) : '?'}`
  return `${o.maint ? 'Maintenance' : 'Repair'}: ${it ? itemName(it) : '?'}`
}
function ordersTab(ui, st) {
  const rate = stationRate(st, power())
  const rows = st.orders.map((o, i) => {
    const spec = orderSpec(o)
    const working = st.working === o.id && o.total
    const frac = working ? 1 - o.left / o.total : 0
    const waiting = !o.paid && spec && !canAfford(spec.cost)
    const stocked = o.kind === 'recipe' && o.keep != null && (() => {
      const r = RECIPES.find((x) => x.id === o.recipe)
      return r.out && S.res[Object.keys(r.out)[0]] >= o.keep
    })()
    return h(
      'div.order' + (working ? '.on' : '') + (waiting ? '.wait' : '') + (stocked ? '.stocked' : ''),
      h('div.o-main', h('b', orderName(o)), h('span.o-sub', o.kind === 'recipe' ? (o.keep != null ? `Keep ${o.keep} in stock` : o.repeat > 1 ? `${o.repeat} to go` : '1 to go') : '', waiting ? h('em.bad', ' · waiting for materials') : stocked ? h('em', ' · stocked') : null), spec ? costList(spec.cost, { small: true, have: !o.paid }) : null, working ? bar(frac, 'prod') : null),
      o.maint
        ? null
        : h(
            'div.o-btns',
            h('button.mini', { disabled: i === 0, onclick: () => (moveOrder(st, o.id, -1), ui.refreshPanel()), html: icon('up') }),
            h('button.mini', { disabled: i === st.orders.length - 1, onclick: () => (moveOrder(st, o.id, 1), ui.refreshPanel()), html: icon('down') }),
            h('button.mini', { onclick: () => (cancelOrder(st, o.id), sfx('click'), ui.refreshPanel()), html: icon('close'), 'data-tip': o.paid ? 'Cancel (materials are refunded)' : 'Cancel' }),
          ),
    )
  })
  return h(
    'div',
    rows.length ? rows : h('p.note', 'Nothing queued. Pick something from the Recipes tab.'),
    rate <= 0 ? h('p.note.bad', 'Nobody is working here, so nothing gets made. Assign a worker or automate it.') : h('p.note', `Working at ${Math.round(rate * 100)}% speed.`),
    REPAIR[st.type] ? h('label.check', h('input', { type: 'checkbox', checked: !!st.maintain, onchange: (e) => ((st.maintain = e.target.checked), ui.refreshPanel()) }), ' Between orders, repair equipment that drops below 60%') : null,
    oddsLine(st),
  )
}
function recipesTab(ui, st) {
  const list = recipesFor(st.type)
  const rate = stationRate(st, power())
  const cats = [...new Set(list.map((r) => r.cat))]
  const full = st.orders.filter((o) => !o.maint).length >= queueMax(st)
  return h(
    'div',
    cats.map((c) =>
      h(
        'div.rgroup',
        h('h4', c),
        list
          .filter((r) => r.cat === c)
          .map((r) => {
            const rlock = r.research && !researchDone(r.research)
            const locked = r.lvl > st.level || rlock
            const outName = r.vehicle ? VEHICLES[r.vehicle].name : r.item ? ITEMS[r.item].name : RES[Object.keys(r.out)[0]].name
            const outN = r.out ? Object.values(r.out)[0] : 1
            const tip = r.vehicle ? `<b>${outName}</b>${r.desc || VEHICLES[r.vehicle].desc}` : r.item ? `<b>${ITEMS[r.item].name}</b>${ITEMS[r.item].desc || ''}<br><em>${r.item ? itemStatLineSafe(r.item) : ''}</em>` : `<b>${outName}</b>${RES[Object.keys(r.out)[0]].desc}`
            const add = (repeat, keep = null) => {
              const err = orderRecipe(st, r.id, repeat, keep)
              if (err) {
                ui.toast(err, 'bad')
                sfx('error')
              } else {
                sfx('click')
                tabState[st.id] = 'orders'
              }
              ui.refreshPanel()
            }
            return h(
              'div.recipe' + (locked ? '.locked' : ''),
              h('div.r-main', { 'data-tip': tip }, h('b', outN > 1 ? `${outN}× ${outName}` : outName), h('span.r-sub', costList(r.in, { small: true }), h('small', rate > 0.01 ? ` · ${Math.round(r.time / rate)}s each` : ` · ${r.time}s of work`))),
              locked
                ? h('span.r-lock', h('i', { html: icon('lock') }), rlock ? `Research: ${RESEARCH[r.research].name}` : `Level ${r.lvl}`)
                : h(
                    'div.r-btns',
                    h('button.mini', { disabled: full, onclick: () => add(1) }, '×1'),
                    h('button.mini', { disabled: full, onclick: () => add(5) }, '×5'),
                    r.out ? h('button.mini', { disabled: full, onclick: () => add(1, defaultKeep(r)), 'data-tip': `Keep making it until you have ${defaultKeep(r)}, then wait` }, h('i', { html: icon('repeat') }), ` ${defaultKeep(r)}`) : null,
                  ),
            )
          }),
      ),
    ),
    full ? h('p.note.bad', `The queue is full (${queueMax(st)}). Upgrade for more slots.`) : null,
    list.some((r) => r.item) ? oddsLine(st) : null,
  )
}
function itemStatLineSafe(id) {
  const it = ITEMS[id]
  if (it.slot === 'weapon') return `${it.dmg} dmg · ${it.range} m${it.ammo ? ' · ' + RES[it.ammo].short : ''}`
  if (it.slot === 'armor') return `+${it.hp} health · ${Math.round(it.dr * 100)}% damage reduction`
  return it.desc || ''
}
function defaultKeep(r) {
  const k = Object.keys(r.out)[0]
  return { parts: 30, gunpowder: 40, chemicals: 20, meds: 10, medkit: 3, molotov: 4, pipebomb: 2, noisemaker: 3, module: 1 }[k] ?? 20
}
function modsTab(ui, st) {
  const mods = modsFor(st.type)
  return h(
    'div',
    h('p.note', 'One mod per item. Fitting takes the item out of use until it is done.'),
    mods.map(([id, M]) => {
      const locked = M.lvl > st.level
      const eligible = S.items.filter((it) => (!it.locker || it.locker === NET.pid) && (ITEMS[it.id].mods === M.type || (M.type === 'gun' && ITEMS[it.id].mods === 'gun')) && !(it.mods || []).length && !st.orders.some((o) => o.item === it.uid))
      return h(
        'div.recipe' + (locked ? '.locked' : ''),
        h('div.r-main', h('b', M.name), h('span.r-sub', M.desc, ' ', costList(M.cost, { small: true }))),
        locked ? h('span.r-lock', h('i', { html: icon('lock') }), `Level ${M.lvl}`) : h('button.mini', { disabled: !eligible.length, onclick: () => pickModTarget(ui, st, id, eligible) }, eligible.length ? 'Fit to…' : 'No items'),
      )
    }),
  )
}
function pickModTarget(ui, st, modId, items) {
  let close
  close = ui.modal(
    h(
      'div',
      h('h2', `Fit ${MODS[modId].name}`),
      h(
        'div.igrid',
        items.map((it) =>
          itemCard(it, {
            onclick: () => {
              const err = orderMod(st, modId, it.uid)
              if (err) ui.toast(err, 'bad')
              else {
                sfx('click')
                tabState[st.id] = 'orders'
              }
              close()
              ui.refreshPanel()
            },
          }),
        ),
      ),
    ),
    { actions: [h('button.btn.ghost', { onclick: () => close() }, 'Cancel')] },
  )
}
function repairsTab(ui, st) {
  const items = S.items.filter((it) => (!it.locker || it.locker === NET.pid) && ITEMS[it.id].repair === st.type && (it.cond ?? 100) < 100).sort((a, b) => a.cond - b.cond)
  if (!items.length) return h('p.note', 'Everything this bench looks after is in perfect condition.')
  return h(
    'div',
    items.map((it) => {
      const queued = st.orders.some((o) => o.item === it.uid)
      return h(
        'div.recipe',
        h('div.r-main', h('b', itemName(it), ' ', h('small', `${Math.round(it.cond)}%`)), h('span.r-sub', costList(repairCost(it), { small: true }), h('small', ` · ${repairTime(it)}s`)), condBar(it)),
        h('button.mini', { disabled: queued, onclick: () => (orderRepair(st, it.uid), sfx('click'), ui.refreshPanel()) }, queued ? 'Queued' : 'Repair'),
      )
    }),
  )
}

// ---------------------------------------------------------------- automation
function autoBlock(ui, st, pinfo) {
  const D = STATIONS[st.type]
  const auto = isAutomated(st, pinfo)
  const rows = []
  if (st.level < D.auto) rows.push(h('p.note', `At level ${D.auto} this station can run itself with an Automation Module and ${D.autoPower} power.`))
  else if (!st.module) {
    rows.push(h('p.note', `Install an Automation Module and it works on its own at ${Math.round(D.autoRate * 100)}% speed, on ${D.autoPower} power. Workers still add their speed on top.`))
    rows.push(h('div.kv', h('span', `You have ${S.res.module} module${S.res.module === 1 ? '' : 's'}`), h('button.btn.small', { disabled: S.res.module < 1, onclick: () => (installModule(st) ? (sfx('build'), ui.toast('Automation module installed', 'good')) : null, ui.refreshPanel()) }, 'Install')))
    if (S.res.module < 1) rows.push(h('p.note', 'Modules are made at the Electronics Bench (level 2) or bought from the trader once your radio reaches level 2.'))
  } else {
    rows.push(h('div.kv', h('span', auto ? h('em.good', `Running automatically · ${Math.round(D.autoRate * 100)}% speed`) : st.autoOn === false ? h('em', 'Automation switched off') : h('em.bad', `Not enough power (needs ${D.autoPower})`)), h('button.mini', { onclick: () => ((st.autoOn = st.autoOn === false), ui.refreshPanel()) }, st.autoOn === false ? 'Switch on' : 'Switch off')))
    rows.push(h('div.kv', h('span', 'Power'), h('b', `${fmt(pinfo.used)} used of ${fmt(pinfo.supply)}`)))
    if (hasFlag('cores')) {
      const n = st.cores || 0
      rows.push(
        h(
          'div.cores',
          h('span', { 'data-tip': `Each power core adds ${Math.round(coreBoost() * 100)}% to automated speed. Power draw grows faster than speed.` }, h('i.inl', { html: icon('core') }), 'Power cores'),
          h('span.slots', Array.from({ length: CORE_SLOTS }, (_, i) => h('i.slot' + (i < n ? '.on' : '')))),
          h('button.mini', { disabled: n >= CORE_SLOTS || S.res.core < 1, onclick: () => (installCore(st) ? sfx('build') : sfx('error'), ui.refreshPanel()), 'data-tip': `Fit a core (you have ${Math.floor(S.res.core)})` }, h('i', { html: icon('plus') })),
          h('button.mini', { disabled: !n, onclick: () => (removeCore(st) ? sfx('click') : null, ui.refreshPanel()), 'data-tip': 'Take a core out' }, h('i', { html: icon('minus') })),
        ),
      )
      if (n) rows.push(h('p.note', `Overclocked: automation at ${Math.round(D.autoRate * (hasFlag('autoBoost') ? 1.5 : 1) * (1 + coreBoost() * n) * 100)}% speed, drawing ${fmt(powerNeed(st))} power.`))
    }
  }
  return h('section.card', h('h3', h('span', h('i.inl', { html: icon('module') }), 'Automation'), h('small', st.module ? 'Module installed' : '')), rows)
}

// ---------------------------------------------------------------- upgrade
function upgradeBlock(ui, st) {
  const D = STATIONS[st.type]
  if (st.level >= D.levels) return h('section.card', h('h3', 'Upgrades'), h('p.note', 'Fully upgraded.'))
  if (st.building) return h('div')
  const cost = upgradeCost(st)
  const next = st.level + 1
  const ben = benefits(st, next)
  return h(
    'section.card',
    h('h3', `Upgrade to level ${next}`, h('small', `${D.time[st.level]}s of building`)),
    ben.length ? h('ul.ben', ben.map((b) => h('li', b))) : null,
    h('div.kv', costList(cost), h('button.btn.go.small', { disabled: !canAfford(cost), onclick: () => (startUpgrade(st) ? (sfx('build'), ui.toast(`${D.name} upgrade started`)) : sfx('error'), ui.refreshPanel()) }, 'Upgrade')),
  )
}
function benefits(st, next) {
  const D = STATIONS[st.type]
  const i = next - 1
  const out = []
  if (D.workers[i] > D.workers[i - 1]) out.push(`${D.workers[i]} worker slots`)
  if (D.recipe?.time && Array.isArray(D.recipe.time)) out.push(`Each batch ${Math.round((1 - D.recipe.time[i] / D.recipe.time[i - 1]) * 100)}% faster`)
  if (D.recipes) {
    out.push('Works faster')
    const fresh = Object.entries(D.recipes).filter(([, r]) => (r.lvl || 1) === next).map(([, r]) => RES[Object.keys(r.out)[0]].name)
    if (fresh.length) out.push(`Can make ${fresh.join(', ')}`)
  }
  if (D.passive) for (const [k, arr] of Object.entries(D.passive)) out.push(`${arr[i]} ${RES[k].name.toLowerCase()} a day`)
  if (D.beds) out.push(`${D.beds[i]} beds`)
  if (D.cap) out.push(`+${D.cap[i]} storage`)
  if (D.queue) out.push(`${D.queue[i]} order slots`)
  if (D.saving) out.push(`Saves ${Math.round(D.saving[i] * 100)}% of food`)
  if (D.heal) out.push('Heals faster')
  if (D.xpRate) out.push(`Trains ${D.xpRate[i]}× faster`)
  if (D.power) out.push(Array.isArray(D.power) ? `${D.power[i]} power` : '')
  if (D.solar) out.push(`${D.solar[i]} peak power`)
  if (D.wind) out.push(`${D.wind[i]} power in full wind`)
  if (D.store) out.push(`stores ${D.store[i]}, supplies ${D.rate[i]}`)
  if (D.towerDmg) out.push(`+${Math.round(D.towerDmg[i] * 100)}% guard damage`)
  if (D.dmg) out.push(`${D.dmg[i]} damage, ${D.range[i]} m range`)
  if (D.recruit) out.push('More newcomers and better trade')
  if (D.auto === next) out.push('Can be automated with a module')
  const unlocks = RECIPES.filter((r) => r.station === st.type && r.lvl === next).map((r) => (r.item ? ITEMS[r.item].name : RES[Object.keys(r.out)[0]].name))
  const mods = Object.values(MODS).filter((m) => m.bench === st.type && m.lvl === next).map((m) => m.name)
  if (unlocks.length || mods.length) out.push(`Unlocks ${[...unlocks, ...mods].join(', ')}`)
  return out.filter(Boolean)
}

// ---------------------------------------------------------------- actions
function actionsBlock(ui, st) {
  const D = STATIONS[st.type]
  const same = S.stations.filter((x) => x.type === st.type)
  const pausable = !D.node && !D.depot && (D.recipe || D.recipes || D.queue || D.passive || D.livestock || ['generator', 'boiler', 'turret', 'floodlight', 'research', 'training'].includes(st.type))
  const on = isRunning(st)
  return h(
    'div.pactions',
    pausable && st.level > 0 ? h('button.btn.small' + (on ? '.ghost' : '.go'), { 'data-tip': on ? 'Stop it working for now (its workers stay assigned)' : 'Start it working again', onclick: () => (setRunning(st, !on), sfx('click'), ui.refreshPanel()) }, h('i', { html: icon(on ? 'pause' : 'play') }), on ? 'Pause' : 'Resume') : null,
    same.length > 1 ? h('button.btn.small.ghost', { 'data-tip': `Select every ${D.name} to work on them together (or Shift-click stations)`, onclick: () => ((ui.game.base.multi = new Set(same.map((x) => x.id))), ui.openMulti(), sfx('select')) }, h('i', { html: icon('select') }), `All ${same.length}`) : null,
    h('button.btn.small.ghost', { onclick: () => (ui.closePanel(), ui.game.base.startPlacing(st.type, st)) }, h('i', { html: icon('move') }), 'Move'),
    h(
      'button.btn.small.ghost.danger',
      {
        onclick: () =>
          ui.confirm(`Demolish the ${D.name}?`, 'You get half of what it cost back. Workers are unassigned.', 'Demolish', () => {
            demolish(st)
            sfx('dismantle')
            ui.closePanel()
          }, { danger: true }),
      },
      h('i', { html: icon('trash') }),
      'Demolish',
    ),
  )
}
