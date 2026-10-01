// The station drawer: workers, what the station is doing and producing,
// crafting orders (queue, recipes, mods, repairs), automation, upgrades.
import { RES, STATIONS, RECIPES, MODS, ITEMS, QUALITY, OCCUPATIONS, SKILLS, SKILL_KEYS, REPAIR, SEC_PER_DAY, FENCE } from '../game/data.js'
import {
  S, workersOf, slots, assign, workEff, bestFor, upgradeCost, startUpgrade, demolish, installModule, recipesFor, modsFor, queueMax, orderRecipe,
  orderMod, orderRepair, cancelOrder, moveOrder, orderSpec, qualityOdds, itemOf, itemName, canAfford, survivorStats, capOf, bedCount, getS, ownerOf,
  repairCost, repairTime, gameDur, stationSize,
} from '../game/state.js'
import { stationFlow, power, isAutomated, stationRate, solarOutput, kitchenSaving, constructSpeed, raidIntel } from '../game/economy.js'
import { sfx } from '../core/audio.js'
import { bus, h, fmt, clamp } from '../core/util.js'
import { icon } from './icons.js'
import { costList, resChip, resIcon, bar, qualityTag, condBar, itemCard, seg, stepper, plural } from './common.js'

const CAT_ICON = { living: 'gate', production: 'production', crafting: 'hammer', defense: 'shield' }
const tabState = {}

export function renderStation(ui, id) {
  const st = S.stations.find((s) => s.id === id)
  if (!st) return null
  const D = STATIONS[st.type]
  const pinfo = power()
  const body = []
  const status = st.building ? `Under construction` : st.stalled ? st.stalled : st.active ? 'Working' : D.workers[Math.max(0, st.level - 1)] && !workersOf(st).length && !isAutomated(st, pinfo) ? 'Idle: no workers' : 'Ready'
  const sub = h('span', h('span.lvl', st.level ? `Level ${st.level}` : 'New'), ' · ', h('span' + (st.stalled ? '.bad' : st.active ? '.good' : ''), status))
  body.push(h('p.desc', D.desc))
  if (st.building) body.push(constructionBlock(st))
  if (st.level > 0) {
    const ws = workerBlock(ui, st)
    if (ws) body.push(ws)
    body.push(...effectBlock(ui, st, pinfo))
    const chain = chainBlock(st)
    if (chain) body.push(chain)
    if (D.queue || st.type === 'infirmary') body.push(benchBlock(ui, st))
    if (D.auto) body.push(autoBlock(ui, st, pinfo))
  }
  body.push(upgradeBlock(ui, st))
  if (!D.fixed) body.push(actionsBlock(ui, st))
  return ui.frame(D.name, sub, body, { icon: CAT_ICON[D.cat] })
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
          h('button.mini', { onclick: () => (assign(s, null), sfx('click'), ui.refreshPanel()) }, 'Remove'),
        ),
      )
    } else rows.push(h('button.wslot.empty', { onclick: () => pickWorker(ui, st) }, h('i', { html: icon('plus') }), 'Assign a survivor'))
  }
  const best = bestFor(st.type)
  return h('section.card', h('h3', 'Workers', h('small', `${ws.length}/${n}${D.skill ? ` · ${SKILLS[D.skill].name}` : ''}`)), rows, best.length ? h('p.note', 'Best: ', best.join(', ')) : null)
}
export function pickWorker(ui, st) {
  const D = STATIONS[st.type]
  const list = S.survivors.filter((s) => s.status !== 'mission' && s.job !== st.id).map((s) => ({ s, e: workEff(s, st.type) || 0.0001 }))
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
  for (const m of Object.values(D.modes || {})) {
    take(m.in)
    give(m.out)
  }
  if (D.passive) give(D.passive)
  if (D.burn && !Array.isArray(D.burn)) take(D.burn)
  if (type === 'generator') ins.add('fuel')
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
  if (!['production', 'crafting'].includes(D.cat) && !['kitchen', 'infirmary', 'generator', 'turret'].includes(st.type)) return null
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
  switch (st.type) {
    case 'campfire':
      out.push(card('The fire', h('p', 'Free survivors gather here in the evening. Keeps morale up (+4).')))
      break
    case 'bunkhouse':
      out.push(card('Beds', h('div.kv', h('span', 'This bunkhouse'), h('b', plural(D.beds[lv - 1], 'bed'))), h('div.kv', h('span', 'Whole camp'), h('b', `${S.survivors.length} / ${bedCount()} in use`)), h('div.kv', h('span', 'Comfort'), h('b', `+${lv * 4} morale`))))
      break
    case 'storage':
      out.push(card('Capacity', h('div.kv', h('span', 'Adds'), h('b', `+${D.cap[lv - 1]} storage`)), h('div.kv', h('span', 'Basic goods now hold'), h('b', fmt(capOf('wood')))), h('p.note', 'Valuable goods (parts, electronics, medicine) store less; ammunition stores more.')))
      break
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
    case 'generator': {
      const fuelDay = -(flow.fuel || 0)
      out.push(card('Power', h('div.kv', h('span', 'Output'), h('b', `${D.power[lv - 1]} power${workersOf(st).length ? ' + operator' : ''}`)), h('div.kv', h('span', 'Camp use'), h('b', `${fmt(pinfo.used)} / ${fmt(pinfo.supply)}`)), h('div.kv', h('span', 'Fuel'), h('b', fuelDay ? `${fuelDay.toFixed(1)} a day · ${S.res.fuel > 0 ? `${(S.res.fuel / fuelDay).toFixed(1)} days left` : 'empty!'}` : 'Idle: nothing needs power')), h('p.note', 'Only burns fuel for what is switched on.')))
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
    case 'ammo':
      out.push(ammoBlock(ui, st, flow))
      break
    default:
      if (D.recipe) {
        const R = D.recipe
        const tm = Array.isArray(R.time) ? R.time[lv - 1] : R.time
        const rows = [rateRow('Per day', flow), h('div.kv', h('span', 'One batch'), h('span', costList(R.in, { small: true }), ' → ', costList(R.out, { small: true, have: false }), h('small', ` · ${tm}s of work`)))]
        if (R.bonus) rows.push(h('p.note', 'Sometimes turns up ', Object.keys(R.bonus).map((k) => RES[k].name.toLowerCase()).join(' and '), '.'))
        if (D.limit) rows.push(limitControl(ui, st, Object.keys(R.out)[0]))
        rows.push(bar(clamp(st.progress || 0, 0, 1), 'prod'))
        out.push(card('Production', ...rows))
      }
  }
  return out
}
function limitControl(ui, st, outKey) {
  const steps = [50, 100, 150, 200, 300, 400, 600, 1e9]
  const i = Math.max(0, steps.findIndex((v) => v >= (st.limit ?? 1e9)))
  return h('div.kv', h('span', `Stop at ${RES[outKey].name.toLowerCase()}`), stepper(i, 0, steps.length - 1, (j) => ((st.limit = steps[j] >= 1e9 ? null : steps[j]), ui.refreshPanel()), (j) => (steps[j] >= 1e9 ? 'Never' : steps[j])))
}
function powerToggle(ui, st, pinfo) {
  const on = st.autoOn !== false
  return h('div.kv', h('span', pinfo.powered.has(st.id) ? h('em.good', 'Powered') : on ? h('em.bad', 'No power') : h('em', 'Switched off')), h('button.mini', { onclick: () => ((st.autoOn = !on), ui.refreshPanel()) }, on ? 'Switch off' : 'Switch on'))
}
function ammoBlock(ui, st, flow) {
  const D = STATIONS.ammo
  const lv = st.level
  const mode = st.mode || 'auto'
  const rows = [
    h('p.note', 'Pick a calibre, or leave it on Auto to keep the lowest one topped up to its target.'),
    seg([['auto', 'Auto'], ['pammo', '9mm'], ['rammo', '7.62'], ['shells', '12ga']], mode, (v) => ((st.mode = v), ui.refreshPanel())),
  ]
  for (const k of ['pammo', 'rammo', 'shells']) {
    const M = D.modes[k]
    const tgt = st.targets?.[k] ?? 100
    rows.push(
      h(
        'div.ammorow' + (st.curMode === k && st.active ? '.on' : ''),
        h('span.an', { style: { '--c': RES[k].color } }, RES[k].name),
        h('span.ah', fmt(S.res[k])),
        h('span.at', 'target ', stepper(Math.round(tgt / 20), 0, 60, (v) => ((st.targets[k] = v * 20), ui.refreshPanel()), (v) => v * 20)),
        h('span.ab', costList(M.in, { small: true }), ' → ', resChip(k, M.out[k]), h('small', ` ${M.time[lv - 1]}s`)),
      ),
    )
  }
  rows.push(rateRow('Per day now', flow))
  rows.push(bar(clamp(st.progress || 0, 0, 1), 'prod'))
  return h('section.card', h('h3', 'Press'), ...rows)
}

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
    return r.item ? ITEMS[r.item].name : `${Object.values(r.out)[0]} ${RES[Object.keys(r.out)[0]].name}`
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
            const locked = r.lvl > st.level
            const outName = r.item ? ITEMS[r.item].name : RES[Object.keys(r.out)[0]].name
            const outN = r.out ? Object.values(r.out)[0] : 1
            const tip = r.item ? `<b>${ITEMS[r.item].name}</b>${ITEMS[r.item].desc || ''}<br><em>${r.item ? itemStatLineSafe(r.item) : ''}</em>` : `<b>${outName}</b>${RES[Object.keys(r.out)[0]].desc}`
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
                ? h('span.r-lock', h('i', { html: icon('lock') }), `Level ${r.lvl}`)
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
      const eligible = S.items.filter((it) => (ITEMS[it.id].mods === M.type || (M.type === 'gun' && ITEMS[it.id].mods === 'gun')) && !(it.mods || []).length && !st.orders.some((o) => o.item === it.uid))
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
  const items = S.items.filter((it) => ITEMS[it.id].repair === st.type && (it.cond ?? 100) < 100).sort((a, b) => a.cond - b.cond)
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
  if (D.modes) out.push('Presses ammo faster')
  if (D.passive) for (const [k, arr] of Object.entries(D.passive)) out.push(`${arr[i]} ${RES[k].name.toLowerCase()} a day`)
  if (D.beds) out.push(`${D.beds[i]} beds`)
  if (D.cap) out.push(`+${D.cap[i]} storage`)
  if (D.queue) out.push(`${D.queue[i]} order slots`)
  if (D.saving) out.push(`Saves ${Math.round(D.saving[i] * 100)}% of food`)
  if (D.heal) out.push('Heals faster')
  if (D.xpRate) out.push(`Trains ${D.xpRate[i]}× faster`)
  if (D.power) out.push(Array.isArray(D.power) ? `${D.power[i]} power` : '')
  if (D.solar) out.push(`${D.solar[i]} peak power`)
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
  return h(
    'div.pactions',
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
