// Mass actions: Shift-click stations in the camp (or "Select all" from a
// station's panel) and work on all of them at once: upgrade them, pause or
// resume them, switch their automation, fill their empty jobs with the best
// free hands, and set production targets for every one that makes the same
// thing.
import { STATIONS, RES } from '../game/data.js'
import { S, upgradeCost, startUpgrade, canAfford, workersOf, slots, assign, workEff, canControl, canWorkAt } from '../game/state.js'
import { isAutomated, power } from '../game/economy.js'
import { sfx } from '../core/audio.js'
import { h } from '../core/util.js'
import { icon } from './icons.js'
import { costList, stepper, plural } from './common.js'

const POWERED = new Set(['generator', 'boiler', 'turret', 'floodlight'])
// Is a station running (not paused by the player, or switched off)?
export const isRunning = (st) => (POWERED.has(st.type) ? st.powerOn !== false : !st.halt)
export function setRunning(st, on) {
  if (POWERED.has(st.type)) st.powerOn = on
  else st.halt = !on
}
const sumCosts = (list) => {
  const out = {}
  for (const c of list) for (const [k, v] of Object.entries(c || {})) out[k] = (out[k] || 0) + v
  return out
}

export function renderMulti(ui) {
  const base = ui.game.base
  const sel = [...(base?.multi || [])].map((id) => S.stations.find((x) => x.id === id)).filter(Boolean)
  if (sel.length < 2) return null
  const pinfo = power()
  const types = [...new Set(sel.map((st) => st.type))]
  const refresh = () => (sfx('click'), ui.refreshPanel())
  // upgrades: everything that can go up a level, cheapest first
  const up = sel.filter((st) => !st.building && st.level > 0 && upgradeCost(st)).sort((a, b) => Object.values(upgradeCost(a)).reduce((x, y) => x + y, 0) - Object.values(upgradeCost(b)).reduce((x, y) => x + y, 0))
  const upCost = sumCosts(up.map(upgradeCost))
  const upgradeAll = () => {
    let n = 0
    for (const st of up) if (startUpgrade(st)) n++
    sfx(n ? 'build' : 'error')
    ui.toast(n ? `${plural(n, 'upgrade')} started${n < up.length ? `; not enough for the other ${up.length - n}` : ''}` : 'Not enough materials for any of them', n ? 'good' : 'bad')
    ui.refreshPanel()
  }
  const running = sel.filter(isRunning).length
  const autoable = sel.filter((st) => st.module && STATIONS[st.type].auto && st.level >= STATIONS[st.type].auto)
  const autoOn = autoable.filter((st) => st.autoOn !== false).length
  // empty jobs and who could fill them
  // (stations that run themselves don't need hands)
  const needHands = sel.filter((st) => !st.building && st.level > 0 && !isAutomated(st, pinfo))
  const emptyJobs = needHands.reduce((a, st) => a + Math.max(0, slots(st) - workersOf(st).length), 0)
  const fillJobs = () => {
    let n = 0
    for (const st of needHands) {
      while (workersOf(st).length < slots(st)) {
        const free = S.survivors.filter((s) => !s.job && s.status === 'ok' && canControl(s) && canWorkAt(s, st.type)).sort((a, b) => workEff(b, st.type) - workEff(a, st.type))
        if (!free.length || !assign(free[0], st)) break
        n++
      }
    }
    sfx(n ? 'select' : 'error')
    ui.toast(n ? `${plural(n, 'person', 'people')} put to work` : 'Nobody is free', n ? 'good' : 'bad')
    ui.refreshPanel()
  }
  // shared targets: every product any of them makes, set for all that make it
  const products = {}
  for (const st of sel) for (const id of Object.keys(STATIONS[st.type].targets || {})) (products[id] ||= []).push(st)
  const targetRows = Object.entries(products).map(([id, list]) => {
    const D = STATIONS[list[0].type]
    const R = D.recipes?.[id]
    const out = R ? Object.keys(R.out)[0] : id
    const vals = list.map((st) => st.targets?.[id] ?? D.targets[id])
    const same = vals.every((v) => v === vals[0])
    const step = out && ['pammo', 'rammo', 'shells', 'bolts'].includes(out) ? 20 : 5
    return h(
      'div.mrow',
      h('span', RES[out]?.name || id, h('small', ` · ${plural(list.length, 'station')}`)),
      same ? null : h('small.dim', 'mixed'),
      stepper(Math.min(60, Math.round((same ? vals[0] : Math.max(...vals)) / step)), 0, 60, (v) => {
        for (const st of list) st.targets = { ...(st.targets || STATIONS[st.type].targets), [id]: v * step }
        ui.refreshPanel()
      }, (v) => v * step),
    )
  })
  const list = sel.map((st) => {
    const D = STATIONS[st.type]
    const v = base?.stationViews?.get(st.id)
    const status = st.building ? 'building' : !isRunning(st) ? 'paused' : v?.warnShow || (st.active ? 'working' : 'idle')
    return h(
      'div.mrow.st',
      h('button.plink', { onclick: () => ui.openStation(st.id) }, `${D.name}${st.level ? ` L${st.level}` : ''}`),
      h('small' + (!isRunning(st) || v?.warnShow ? '.bad' : st.active ? '.good' : ''), status),
      h('small.dim', `${workersOf(st).length}/${slots(st)}${isAutomated(st, pinfo) ? ' · auto' : ''}`),
      h('button.mini', { 'data-tip': 'Take it out of the selection', onclick: () => (base.multi.delete(st.id), ui.refreshPanel()), html: icon('close') }),
    )
  })
  return ui.frame(
    `${sel.length} stations`,
    h('span', types.length === 1 ? `All ${STATIONS[types[0]].name}s` : `${types.length} kinds`, ' · ', h('a.link', { onclick: () => (base.multi.clear(), ui.closePanel()) }, 'clear')),
    [
      h('section.card', h('h3', 'Selected', h('small', 'Shift-click stations to add or remove')), list),
      h(
        'section.card',
        h('h3', 'All at once'),
        h(
          'div.bactions',
          h('button.btn.small' + (running ? '' : '.go'), { onclick: () => (sel.forEach((st) => setRunning(st, running < sel.length)), refresh()) }, h('i', { html: icon(running < sel.length ? 'play' : 'pause') }), running < sel.length ? ` Resume all (${sel.length - running} paused)` : ' Pause all'),
          autoable.length ? h('button.btn.small.ghost', { onclick: () => (autoable.forEach((st) => (st.autoOn = autoOn < autoable.length)), refresh()) }, h('i', { html: icon('module') }), autoOn < autoable.length ? ` Automation on (${autoable.length})` : ` Automation off (${autoable.length})`) : null,
          emptyJobs ? h('button.btn.small.ghost', { onclick: fillJobs }, h('i', { html: icon('people') }), ` Fill ${plural(emptyJobs, 'empty job')}`) : null,
        ),
        up.length
          ? h('div.mup', h('div', h('b', `Upgrade ${plural(up.length, 'station')}`), costList(upCost)), h('button.btn.small.go', { disabled: !up.some((st) => canAfford(upgradeCost(st))), onclick: upgradeAll }, h('i', { html: icon('up') }), canAfford(upCost) ? ' Upgrade all' : ' Upgrade what we can'))
          : h('p.note.dim', 'Nothing here can be upgraded right now.'),
      ),
      targetRows.length ? h('section.card', h('h3', 'Targets', h('small', 'keep this much in store')), targetRows) : null,
    ],
    { icon: 'select' },
  )
}
