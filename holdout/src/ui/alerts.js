// Custom alerts: rules like "tell me when metal passes 200", "when anyone is
// hurt" or "when the forge stalls". Each rule is checked every second and
// fires once when it comes true (a toast you can click through, a chime, a
// desktop notification if the tab is in the background, and the game can
// pause for it); it re-arms once it stops being true. Kept per camp in this
// browser, so each player has their own.
import { RES, STOCK_KEYS, STATIONS } from '../game/data.js'
import { S, gameDur, capOf } from '../game/state.js'
import { raidIntel, power } from '../game/economy.js'
import { sfx } from '../core/audio.js'
import { h, fmt, uid } from '../core/util.js'
import { icon } from './icons.js'
import { notify } from './notify.js'

const key = () => `holdout.alerts.${S?.seed ?? 0}`
let rules = null
let rulesKey = null
export function alertRules() {
  if (rules && rulesKey === key()) return rules
  rulesKey = key()
  try {
    rules = JSON.parse(localStorage.getItem(rulesKey) || '[]')
  } catch {
    rules = []
  }
  if (!Array.isArray(rules)) rules = []
  return rules
}
function save() {
  try {
    localStorage.setItem(key(), JSON.stringify(rules.map(({ armed, ...r }) => r)))
  } catch {}
}
export function addRule(r) {
  alertRules().push({ id: uid('al'), on: true, pause: false, ...r })
  save()
}
export function removeRule(id) {
  rules = alertRules().filter((r) => r.id !== id)
  save()
}
const stName = (id) => {
  if (id === 'any') return 'any station'
  const st = S.stations.find((x) => x.id === id)
  return st ? `the ${STATIONS[st.type].name}` : 'a station that is gone'
}
// The rule in words.
export function ruleText(r) {
  switch (r.kind) {
    case 'res':
      return `${RES[r.res]?.name || r.res} ${r.op === 'above' ? 'passes' : 'drops below'} ${fmt(r.n)}`
    case 'full':
      return `${RES[r.res]?.name || r.res} storage is full`
    case 'hurt':
      return 'anyone is hurt'
    case 'infected':
      return 'anyone is infected'
    case 'stall':
      return `${stName(r.st)} stalls`
    case 'idle':
      return 'anyone has no job'
    case 'horde':
      return `a horde is under ${r.n} hours away`
    case 'power':
      return 'power runs short'
    case 'built':
      return 'a building is finished'
    default:
      return '?'
  }
}
// Is it true right now? Returns a short message when it is.
function check(ui, r) {
  switch (r.kind) {
    case 'res': {
      const v = S.res[r.res] || 0
      return (r.op === 'above' ? v >= r.n : v < r.n) ? `${RES[r.res].name} is ${r.op === 'above' ? 'up to' : 'down to'} ${fmt(v)}` : null
    }
    case 'full': {
      const cap = capOf(r.res)
      return (S.res[r.res] || 0) >= cap - 0.5 ? `${RES[r.res].name} storage is full` : null
    }
    case 'hurt': {
      const s = S.survivors.find((x) => x.status === 'injured')
      return s ? `${s.first} is hurt` : null
    }
    case 'infected': {
      const s = S.survivors.find((x) => (x.infection || 0) > 0)
      return s ? `${s.first} is infected` : null
    }
    case 'stall': {
      const list = r.st === 'any' ? S.stations : S.stations.filter((x) => x.id === r.st)
      for (const st of list) {
        const v = ui.game.base?.stationViews?.get(st.id)
        const w = v ? v.warnShow : st.stalled
        if (w && w !== 'No workers' && !st.building) return `${STATIONS[st.type].name}: ${w}`
      }
      return null
    }
    case 'idle': {
      const n = S.survivors.filter((s) => s.status === 'ok' && !s.job).length
      return n ? `${n} ${n === 1 ? 'person has' : 'people have'} no job` : null
    }
    case 'horde': {
      const I = raidIntel()
      return I && I.in < r.n * 60 ? `A horde in ${gameDur(I.in)}` : null
    }
    case 'power': {
      const P = power()
      return P.demand > P.supply + 0.05 ? `Power short: ${P.demand.toFixed(1)} wanted, ${P.supply.toFixed(1)} made` : null
    }
    case 'built': {
      const n = S.stations.filter((x) => !x.building && x.level > 0).length
      const was = r.seen ?? n
      r.seen = n
      return n > was ? 'A building is finished' : null
    }
  }
  return null
}
// Run every rule (once a second).
export function tickAlerts(ui) {
  if (!S || S.over) return
  for (const r of alertRules()) {
    if (!r.on) continue
    const msg = check(ui, r)
    if (msg && !r.armed) {
      r.armed = true
      fire(ui, r, msg)
    } else if (!msg) r.armed = false
  }
}
function fire(ui, r, msg) {
  sfx('alarm', 400)
  ui.toast(h('span.alerttoast', h('i', { html: icon('bell') }), msg), 'alert', { label: 'Show', fn: () => openFor(ui, r) })
  notify('Holdout', msg, 'alert' + r.id)
  if (r.pause && ui.game.scene === ui.game.base && S.speed) {
    ui.game.setSpeed?.(0)
    ui.toast('Paused for your alert', 'story')
  }
}
function openFor(ui, r) {
  if (r.kind === 'res' || r.kind === 'full') return ui.openFlow(r.res)
  if (r.kind === 'stall') {
    const st = r.st === 'any' ? S.stations.find((x) => ui.game.base?.stationViews?.get(x.id)?.warnShow) : S.stations.find((x) => x.id === r.st)
    if (st) return ui.openStation(st.id)
  }
  if (r.kind === 'hurt' || r.kind === 'infected' || r.kind === 'idle') return ui.openCrew()
  if (r.kind === 'horde') return ui.openHorde()
  if (r.kind === 'power') return ui.openPower()
  ui.openAlerts()
}

// ---------------------------------------------------------------- the panel
let draft = { kind: 'res', res: 'metal', op: 'above', n: 200, st: 'any', hours: 2 }
const PRESETS = [
  { kind: 'res', res: 'food', op: 'below', n: 30, label: 'Food drops below 30' },
  { kind: 'res', res: 'water', op: 'below', n: 30, label: 'Water drops below 30' },
  { kind: 'res', res: 'metal', op: 'above', n: 200, label: 'Metal passes 200' },
  { kind: 'hurt', label: 'Anyone is hurt' },
  { kind: 'infected', label: 'Anyone is infected' },
  { kind: 'stall', st: 'any', label: 'Any station stalls' },
  { kind: 'horde', n: 2, label: 'A horde under 2 hours away' },
  { kind: 'power', label: 'Power runs short' },
]
export function renderAlerts(ui) {
  const R = alertRules()
  const kinds = [['res', 'A resource passes or drops below…'], ['full', 'A store is full'], ['hurt', 'Anyone is hurt'], ['infected', 'Anyone is infected'], ['stall', 'A station stalls'], ['idle', 'Anyone has no job'], ['horde', 'A horde gets close'], ['power', 'Power runs short'], ['built', 'A building is finished']]
  const sel = (opts, val, fn) => h('select.inp', { onchange: (e) => fn(e.target.value) }, opts.map(([v, l]) => h('option', { value: v, selected: v === val }, l)))
  const num = (val, fn) => h('input.inp.num', { type: 'number', min: 0, value: val, onchange: (e) => fn(+e.target.value || 0), onkeydown: (e) => e.stopPropagation() })
  const fields = []
  if (draft.kind === 'res') fields.push(sel(STOCK_KEYS.map((k) => [k, RES[k].name]), draft.res, (v) => (draft.res = v)), sel([['above', 'passes'], ['below', 'drops below']], draft.op, (v) => (draft.op = v)), num(draft.n, (v) => (draft.n = v)))
  if (draft.kind === 'full') fields.push(sel(STOCK_KEYS.map((k) => [k, RES[k].name]), draft.res, (v) => (draft.res = v)))
  if (draft.kind === 'stall') fields.push(sel([['any', 'Any station'], ...S.stations.filter((x) => STATIONS[x.type].workers?.some((w) => w) || STATIONS[x.type].recipe || STATIONS[x.type].recipes).map((x) => [x.id, `${STATIONS[x.type].name}${x.level > 1 ? ` L${x.level}` : ''}`])], draft.st, (v) => (draft.st = v)))
  if (draft.kind === 'horde') fields.push(h('span', 'under'), num(draft.n ?? 2, (v) => (draft.n = v)), h('span', 'hours away'))
  const add = () => {
    const r = { kind: draft.kind }
    if (draft.kind === 'res') Object.assign(r, { res: draft.res, op: draft.op, n: draft.n })
    if (draft.kind === 'full') r.res = draft.res
    if (draft.kind === 'stall') r.st = draft.st
    if (draft.kind === 'horde') r.n = draft.n ?? 2
    addRule(r)
    sfx('click')
    ui.refreshPanel()
  }
  return ui.frame(
    'Custom alerts',
    'Tell me when…',
    [
      h('section.card', h('h3', 'Your alerts', h('small', R.length ? `${R.filter((r) => r.on).length} on` : 'none yet')),
        R.length
          ? R.map((r) =>
              h(
                'div.alrow' + (r.on ? '' : '.off') + (r.armed ? '.firing' : ''),
                h('label.rtoggle', h('input', { type: 'checkbox', checked: r.on, onchange: (e) => ((r.on = e.target.checked), (r.armed = false), save(), ui.refreshPanel()) }), h('span', `When ${ruleText(r)}`)),
                h('label.rtoggle.small', { 'data-tip': 'Pause the game when it fires' }, h('input', { type: 'checkbox', checked: !!r.pause, onchange: (e) => ((r.pause = e.target.checked), save()) }), h('small', 'pause')),
                r.armed ? h('em.firing', 'now') : null,
                h('button.mini', { onclick: () => (removeRule(r.id), ui.refreshPanel()), html: icon('trash'), 'data-tip': 'Delete' }),
              ),
            )
          : h('p.note', 'Nothing yet. Pick one below, or build your own.'),
      ),
      h('section.card', h('h3', 'Quick add'), h('div.alpresets', PRESETS.map((p) => h('button.lchip', { onclick: () => (addRule({ ...p, label: undefined }), sfx('click'), ui.refreshPanel()) }, p.label)))),
      h('section.card', h('h3', 'Build your own'), h('div.albuild', h('span', 'When'), sel(kinds, draft.kind, (v) => ((draft.kind = v), ui.refreshPanel())), ...fields, h('button.btn.small.go', { onclick: add }, h('i', { html: icon('plus') }), ' Add'))),
      h('p.note.dim', 'Each alert fires once when it comes true and again only after it has stopped being true. With desktop notifications on (Settings), they reach you in another tab too.'),
    ],
    { icon: 'bell' },
  )
}
