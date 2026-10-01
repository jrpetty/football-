// The motor pool: every vehicle the camp owns, what it costs to run and to
// mend, the dead van's repair list, and what distance does to a trip.
import { RES, VEHICLES, VAN_REPAIR } from '../game/data.js'
import { S, travelCost, vehicleProblem, vehicleRepairCost, repairVehicle, canAfford, countType } from '../game/state.js'
import { sfx } from '../core/audio.js'
import { h, fmt } from '../core/util.js'
import { icon } from './icons.js'
import { costList, bar, resIcon } from './common.js'

const SAMPLES = [
  ['Next door', 0.25],
  ['Four streets over', 0.7],
  ['Across town', 1.2],
  ['Out on the highway', 2.5],
]

export function motorPool(ui, refresh) {
  const vs = S.vehicles || []
  const ready = vs.filter((v) => !vehicleProblem(v)).length
  const body = []
  body.push(
    h('p.desc', 'Every survivor on a run carries food and water for the trip, and the further out the place, the more they need: a lot more on foot. Vehicles get there faster on a fraction of the provisions, burn fuel by the kilometre and wear down a little every trip.'),
  )
  body.push(costTable())
  for (const v of vs) body.push(vehicleCard(ui, v, refresh))
  if (!vs.some((v) => v.kind === 'bikes'))
    body.push(h('section.card.veh-hint', h('h3', h('i.inl', { html: icon('hammer') }), 'Bicycles'), h('p.note', `Four salvaged bikes from the Workbench: metal bars, parts, cloth and two tyres. Twice as fast as walking on ${Math.round(VEHICLES.bikes.prov * 100)}% of the provisions, though nothing gets stashed.${countType('workbench') ? '' : ' Build a Workbench first.'}`)))
  body.push(h('section.card.veh-hint', h('h3', h('i.inl', { html: icon('truck') }), 'Finding more'), h('p.note', 'Now and then a car out on a run still works: houses, garages and gas stations are the best bets. Search the building for its keys, or let a mechanic, ex-con or engineer hotwire it, and it drives home with the squad. Strip wrecks for tyres and batteries. The armoured truck comes with the Convoys milestone.')))
  return [h('div.mp-head', h('i', { html: icon('truck') }), h('h2', 'Motor pool'), h('small', `${ready} of ${vs.length} ready to go`)), ...body]
}

// Provisions per person for sample distances, on foot, by bike and driving.
function costTable() {
  const kinds = [['foot', 'On foot'], ['bikes', 'Bicycles'], ['van', 'Vehicle']]
  return h(
    'section.card',
    h('h3', 'What distance costs', h('small', 'food and water per person, there and back')),
    h(
      'table.vtable',
      h('tr', h('th', ''), kinds.map(([, n]) => h('th', n))),
      SAMPLES.map(([label, km]) =>
        h(
          'tr',
          h('td', h('b', label), h('small', ` ${km} km`)),
          kinds.map(([k]) => {
            const c = travelCost(km, k, 1)
            return h('td', `${c.food} / ${c.water}`, c.fuel ? h('small.dim', ` +${c.fuel} fuel`) : null)
          }),
        ),
      ),
    ),
  )
}

function vehicleCard(ui, v, refresh) {
  const V = VEHICLES[v.kind]
  const why = vehicleProblem(v)
  const cost = vehicleRepairCost(v)
  const status = v.out ? ['Out on a run', ''] : v.broken ? ['Dead', '.bad'] : why ? ['Needs repair', '.bad'] : ['Ready', '.good']
  const stats = h(
    'div.vstats',
    h('span', h('small', 'Provisions'), h('b', `${Math.round(V.prov * 100)}%`)),
    h('span', h('small', 'Fuel'), h('b', V.fuelKm ? `${V.fuelKm} / km` : 'none')),
    h('span', h('small', 'Stash'), h('b', V.stash === Infinity ? 'Everything' : V.stash ? `${V.stash}` : 'None')),
    h('span', h('small', 'Speed'), h('b', V.speed >= 1 ? 'Full' : `${Math.round(V.speed * 100)}%`)),
  )
  const kids = [h('h3', h('span', v.name), h('small' + status[1], status[0])), h('p.note', V.desc), stats]
  if (v.broken && v.kind === 'van') {
    kids.push(
      h('p.note', 'The engine turns over and dies, two tyres are flat and the other two are bald. It needs a battery that holds a charge, four tyres, parts, bolts and a little fuel. Strip abandoned cars on runs: they give up tyres and now and then a battery. Garages and gas stations keep both.'),
      h(
        'div.vchk',
        Object.entries(VAN_REPAIR).map(([k, n]) => {
          const have = S.res[k] || 0
          return h('div.vrow' + (have >= n ? '.have' : ''), h('i.ic', { style: { color: RES[k].color }, html: resIcon(k) }), h('span', RES[k].name), h('b', `${fmt(Math.min(have, n))} / ${n}`), h('i.tick', { html: icon(have >= n ? 'check' : 'close') }))
        }),
      ),
      h('div.kv', h('span', ''), h('button.btn.go', { disabled: !canAfford(VAN_REPAIR), onclick: () => (repairVehicle(v) ? (sfx('complete'), ui.toast('The van runs again!', 'good')) : sfx('error'), refresh()) }, h('span', { html: icon('wrench') }), ' Get it running')),
    )
  } else {
    kids.push(h('div.kv', h('span', 'Condition'), bar(v.cond / 100, v.cond < 30 ? 'hp.low' : 'hp', `${Math.round(v.cond)}%`)))
    if (cost)
      kids.push(h('div.kv', costList(cost, { small: true }), h('button.btn.small' + (why ? '.go' : ''), { disabled: !canAfford(cost) || v.out, onclick: () => (repairVehicle(v) ? sfx('build') : sfx('error'), refresh()) }, 'Repair')))
  }
  return h('section.card.vehicle' + (why && !v.out ? '.down' : ''), kids)
}
