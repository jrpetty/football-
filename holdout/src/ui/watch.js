// The watch list: stations, survivors and resources pinned to a strip on the
// left of the screen with their numbers live (stock and rate, how hard a
// station is working, how someone is doing). Pin from any station, survivor
// sheet or resource panel; click a chip to open it, × to unpin. Kept per camp
// in this browser, so each player in a shared camp keeps their own.
import { RES, STATIONS } from '../game/data.js'
import { S, capOf, survivorStats, workersOf } from '../game/state.js'
import { stationFlow, power } from '../game/economy.js'
import { h, fmt } from '../core/util.js'
import { icon } from './icons.js'
import { resIcon } from './common.js'

const MAX = 10
const key = () => `holdout.watch.${S?.seed ?? 0}`
let list = null
let listKey = null
function load() {
  if (list && listKey === key()) return list
  listKey = key()
  try {
    list = JSON.parse(localStorage.getItem(listKey) || '[]')
  } catch {
    list = []
  }
  if (!Array.isArray(list)) list = []
  return list
}
function save() {
  try {
    localStorage.setItem(key(), JSON.stringify(list))
  } catch {}
}
export const watchList = () => load().filter((w) => exists(w))
export const isWatched = (kind, id) => load().some((w) => w.kind === kind && w.id === id)
export function toggleWatch(kind, id) {
  const L = load()
  const i = L.findIndex((w) => w.kind === kind && w.id === id)
  if (i >= 0) L.splice(i, 1)
  else {
    L.push({ kind, id })
    if (L.length > MAX) L.shift()
  }
  save()
  dirty = true
  return i < 0
}
const exists = (w) => (w.kind === 'res' ? !!RES[w.id] : w.kind === 'st' ? S.stations.some((x) => x.id === w.id) : S.survivors.some((x) => x.id === w.id))

// A pin button for a panel header.
export function pinButton(ui, kind, id) {
  const on = isWatched(kind, id)
  return h('button.x.pinbtn' + (on ? '.on' : ''), { 'data-tip': on ? 'Unpin from the watch list' : 'Pin to the watch list: its numbers stay on screen', onclick: () => (toggleWatch(kind, id), ui.refreshPanel(), ui.updateWatch?.(true)), html: icon('pin') })
}

// ---------------------------------------------------------------- the strip
let dirty = true
const per = (v) => (Math.abs(v) < 10 ? v.toFixed(1) : Math.round(v).toString())
function resChip(ui, k, flow) {
  const v = S.res[k] || 0
  const cap = capOf(k)
  const r = flow?.[k] || 0
  return {
    key: 'res:' + k,
    cls: (v < cap * 0.08 && r < 0 ? 'bad' : v >= cap - 0.5 ? 'full' : '') + ' res',
    icon: resIcon(k),
    color: RES[k].color,
    name: RES[k].short || RES[k].name,
    value: fmt(v),
    sub: Math.abs(r) < 0.05 ? 'steady' : `${r > 0 ? '+' : ''}${per(r)}/day`,
    subCls: r > 0.05 ? 'up' : r < -0.05 ? 'down' : '',
    fill: v / cap,
    open: () => ui.openFlow(k),
  }
}
function stChip(ui, st, pinfo) {
  const D = STATIONS[st.type]
  const v = ui.game.base?.stationViews?.get(st.id)
  const f = stationFlow(st, pinfo)
  const main = Object.entries(f).filter(([, x]) => x > 0.05).sort((a, b) => b[1] - a[1])[0]
  const warn = v?.warnShow || (st.building ? 'Building' : null)
  const busy = v ? Math.round((v.duty ?? (st.active ? 1 : 0)) * 100) : st.active ? 100 : 0
  return {
    key: 'st:' + st.id,
    cls: (warn ? 'bad' : busy > 10 ? 'busy' : 'idle') + ' st',
    icon: icon('production'),
    color: '#eaa53c',
    name: `${D.name}${st.level > 1 ? ` L${st.level}` : ''}`,
    value: warn ? '!' : `${busy}%`,
    sub: warn || (main ? `+${per(main[1])} ${RES[main[0]].short || RES[main[0]].name.toLowerCase()}/day` : workersOf(st).length ? 'working' : 'idle'),
    subCls: warn ? 'down' : '',
    fill: busy / 100,
    open: () => ui.openStation(st.id),
  }
}
function svChip(ui, s) {
  const st = survivorStats(s)
  const job = s.job ? S.stations.find((x) => x.id === s.job) : null
  const where = s.status === 'mission' ? 'On a run' : s.status === 'scout' ? 'Scouting' : s.status === 'outpost' ? 'Outpost' : s.status === 'injured' ? 'Injured' : job ? STATIONS[job.type].name : 'No job'
  return {
    key: 'sv:' + s.id,
    cls: (s.infection > 0 || s.status === 'injured' ? 'bad' : '') + ' sv',
    icon: icon('people'),
    color: '#8ccf72',
    name: s.first,
    value: `${Math.round(s.hp)}`,
    sub: s.infection > 0 ? `infected ${Math.round(s.infection)}%` : where,
    subCls: s.infection > 0 ? 'down' : '',
    fill: s.hp / st.maxHp,
    open: () => ui.openSurvivor(s.id),
  }
}
// Build or refresh the strip (called a few times a second).
export function renderWatch(ui, el) {
  const L = watchList()
  el.hidden = !L.length
  if (!L.length) {
    el.innerHTML = ''
    return
  }
  const pinfo = power()
  const flow = ui.flowCache
  const chips = L.map((w) => {
    if (w.kind === 'res') return resChip(ui, w.id, flow)
    if (w.kind === 'st') return stChip(ui, S.stations.find((x) => x.id === w.id), pinfo)
    return svChip(ui, S.survivors.find((x) => x.id === w.id))
  })
  const sig = chips.map((c) => c.key).join('|')
  if (dirty || el.dataset.sig !== sig) {
    dirty = false
    el.dataset.sig = sig
    el.innerHTML = ''
    el.append(h('div.wl-head', h('i', { html: icon('pin') }), 'Watching'))
    for (const c of chips) {
      const kind = c.key.split(':')[0]
      const id = c.key.slice(kind.length + 1)
      el.append(
        h(
          'button.wchip',
          { 'data-k': c.key, onclick: c.open },
          h('i.wic', { html: c.icon, style: { color: c.color } }),
          h('span.wn'),
          h('b.wv'),
          h('small.ws'),
          h('i.wf', h('i')),
          h('span.wx', { 'data-tip': 'Unpin', onclick: (e) => (e.stopPropagation(), toggleWatch(kind === 'res' ? 'res' : kind, id), renderWatch(ui, el)), html: icon('close') }),
        ),
      )
    }
  }
  // live numbers
  for (const c of chips) {
    const b = el.querySelector(`[data-k="${c.key}"]`)
    if (!b) continue
    b.className = 'wchip ' + c.cls
    b.querySelector('.wn').textContent = c.name
    b.querySelector('.wv').textContent = c.value
    const s = b.querySelector('.ws')
    s.textContent = c.sub
    s.className = 'ws ' + (c.subCls || '')
    b.querySelector('.wf > i').style.width = `${Math.max(0, Math.min(1, c.fill || 0)) * 100}%`
  }
}
