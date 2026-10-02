// The command palette (Ctrl+K, or /): type to find any station, survivor,
// item, resource, place in the city, panel or thing to build, and jump
// straight to it. Arrow keys move, Enter goes, Esc closes. With nothing
// typed it lists what you opened last.
import { RES, STOCK_KEYS, STATIONS, LOCATIONS, ITEMS, OCCUPATIONS } from '../game/data.js'
import { S, itemName, ownerOf, isUnlocked, stationSize, buildCost, canAfford } from '../game/state.js'
import { fullName } from '../game/deeds.js'
import { view } from '../render/view.js'
import { sfx } from '../core/audio.js'
import { h, fmt } from '../core/util.js'
import { icon } from './icons.js'
import { resIcon } from './common.js'

const RECENT_KEY = 'holdout.palette.recent'
const GROUP_ORDER = ['Recent', 'Go to', 'Stations', 'People', 'Resources', 'Places', 'Items', 'Build']
function recent() {
  try {
    return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]')
  } catch {
    return []
  }
}
function remember(id) {
  try {
    const r = [id, ...recent().filter((x) => x !== id)].slice(0, 8)
    localStorage.setItem(RECENT_KEY, JSON.stringify(r))
  } catch {}
}
// Point the camp camera at something.
function lookAt(ui, x, z, dist = 22) {
  if (ui.game.scene !== ui.game.base) return
  view.rig.focus(x, z, Math.min(view.rig.distGoal ?? dist, dist))
}

// Everything you can jump to.
function entries(ui) {
  const out = []
  const g = ui.game
  const add = (group, id, label, sub, ic, run, kw = '') => out.push({ group, id, label, sub, ic, run, kw: (kw + ' ' + label + ' ' + sub).toLowerCase() })
  // panels and tools
  const P = (label, sub, ic, run, kw = '') => add('Go to', 'go:' + label, label, sub, icon(ic), run, kw)
  P('Build', 'Put up a station or a belt', 'build', () => ui.toggleBuild(true), 'construct place')
  P('Crew', 'Everyone in camp and their jobs', 'people', () => ui.openCrew(), 'survivors people')
  P('Items', 'Weapons, armor and gear', 'items', () => ui.openItems(), 'inventory gear')
  P('Trade', 'The black market', 'market', () => ui.openMarket(), 'sell buy market')
  P('Camp overview', 'Stock, rates and every station', 'production', () => ui.openProduction(), 'production stock')
  P('Power', 'Where power comes from and goes', 'bolt', () => ui.openPower(), 'electricity generator')
  P('Map', 'The city: runs, scouts and the horde', 'map', () => g.openMap(), 'city run scout')
  P('Progress', 'Milestones, research and the Signal', 'goals', () => ui.openProgress(), 'milestones tiers')
  P('Journal', 'The story so far', 'book', () => ui.openJournal(), 'story')
  P('Log', 'Everything that happened', 'book', () => ui.openLog(), 'events')
  P('Horde intel', 'The next horde and the wall', 'horde', () => ui.openHorde(), 'raid attack')
  P('The wall', 'Repair and upgrade the fence', 'shield', () => ui.openFence(), 'fence palisade')
  P('Morale', 'How the camp feels and why', 'heart', () => ui.openMorale(), 'mood')
  P('Motor pool', 'Vehicles and repairs', 'truck', () => ui.openMotorPool(), 'van car')
  P('Custom alerts', 'Tell me when something happens', 'bell', () => ui.openAlerts(), 'notify rules alert')
  P('Field manual', 'How everything works', 'book', () => ui.openManual(), 'help guide')
  P('Settings', 'Graphics, sound and controls', 'settings', () => ui.openSettings(), 'options')
  // stations
  for (const st of S.stations) {
    const D = STATIONS[st.type]
    const v = g.base?.stationViews?.get(st.id)
    const status = st.building ? 'under construction' : v?.warnShow || st.stalled || (st.active ? 'working' : 'idle')
    add('Stations', 'st:' + st.id, `${D.name}${st.level > 1 ? ` L${st.level}` : ''}`, status, icon('production'), () => {
      const [w, d] = stationSize(st)
      lookAt(ui, st.x + w / 2, st.z + d / 2, 26)
      ui.openStation(st.id)
    }, D.cat)
  }
  // people
  for (const s of S.survivors) {
    const job = s.job ? S.stations.find((x) => x.id === s.job) : null
    const where = s.status === 'mission' ? 'on a run' : s.status === 'scout' ? 'scouting' : s.status === 'outpost' ? 'at an outpost' : s.status === 'injured' ? 'injured' : job ? STATIONS[job.type].name : 'no job'
    add('People', 'sv:' + s.id, fullName(s), `${OCCUPATIONS[s.occ].name} · ${where}`, icon('people'), () => {
      const w = g.base?.people?.list?.get(s.id)
      if (w) lookAt(ui, w.pos.x, w.pos.z, 18)
      ui.openSurvivor(s.id)
    }, (s.traits || []).join(' '))
  }
  // resources
  const flow = ui.flowCache || {}
  for (const k of STOCK_KEYS) {
    const r = flow[k] || 0
    add('Resources', 'res:' + k, RES[k].name, `${fmt(S.res[k] || 0)} in store${Math.abs(r) > 0.05 ? ` · ${r > 0 ? '+' : ''}${r.toFixed(1)}/day` : ''}`, resIcon(k), () => ui.openFlow(k), RES[k].short || '')
  }
  // places in the city
  for (const l of S.cityLocs || []) add('Places', 'loc:' + l.id, l.name, `${LOCATIONS[l.type]?.name || ''} · level ${l.level}`, icon('map'), () => g.openMap(l.id), l.district || '')
  // gear
  for (const it of S.items.filter((x) => !x.locker)) {
    const who = ownerOf(it.uid)
    add('Items', 'it:' + it.uid, itemName(it), who ? `${who.first} has it` : 'unused', icon('items'), () => (who ? ui.openSurvivor(who.id) : ui.openItems('free')), ITEMS[it.id].slot)
  }
  // things to build
  for (const [type, D] of Object.entries(STATIONS)) {
    if (D.fixed || !isUnlocked('station', type)) continue
    if (D.unique && S.stations.some((x) => x.type === type)) continue
    const cost = buildCost?.(type)
    add('Build', 'build:' + type, `Build ${D.name}`, cost && !canAfford(cost) ? 'not enough materials' : D.cat, icon('build'), () => {
      ui.buildCat = D.cat
      ui.toggleBuild(true)
      if (!cost || canAfford(cost)) g.base?.startPlacing(type)
    }, 'build ' + D.cat)
  }
  return out
}
// How well an entry matches what was typed (0: not at all).
function score(e, q) {
  if (!q) return 1
  const L = e.label.toLowerCase()
  let total = 0
  for (const t of q.split(/\s+/).filter(Boolean)) {
    let s = 0
    if (L.startsWith(t)) s = 100
    else if (L.split(/[\s·,()-]+/).some((w) => w.startsWith(t))) s = 70
    else if (L.includes(t)) s = 45
    else if (e.kw.includes(t)) s = 18
    else {
      // letters in order (fz → "forge")
      let i = 0
      for (const ch of L) if (ch === t[i]) i++
      s = i === t.length && t.length >= 2 ? 8 : 0
    }
    if (!s) return 0
    total += s
  }
  return total
}

export function openPalette(ui) {
  if (ui.paletteEl) return ui.paletteEl.querySelector('input').focus()
  const all = entries(ui)
  const input = h('input.pal-in', { placeholder: 'Find a station, survivor, resource, item, place…', spellcheck: false, autocomplete: 'off' })
  const listEl = h('div.pal-list')
  const close = () => {
    ui.paletteEl?.remove()
    ui.paletteEl = null
  }
  let shown = []
  let sel = 0
  const go = (e) => {
    if (!e) return
    remember(e.id)
    close()
    sfx('click')
    // from the city map, anything in camp means going back there first
    if (ui.game.scene === ui.game.map && e.group !== 'Places' && e.id !== 'go:Map') ui.game.closeMap()
    e.run()
  }
  const render = () => {
    const q = input.value.trim().toLowerCase()
    let res
    if (!q) {
      const rec = recent()
      const byId = new Map(all.map((e) => [e.id, e]))
      res = [...rec.map((id) => byId.get(id)).filter(Boolean).map((e) => ({ ...e, group: 'Recent' })), ...all.filter((e) => e.group === 'Go to')]
    } else {
      res = all
        .map((e) => ({ e, s: score(e, q) }))
        .filter((x) => x.s > 0)
        .sort((a, b) => b.s - a.s || GROUP_ORDER.indexOf(a.e.group) - GROUP_ORDER.indexOf(b.e.group))
        .slice(0, 40)
        .map((x) => x.e)
      // group the best matches, groups in the order their best match came
      const order = []
      for (const e of res) if (!order.includes(e.group)) order.push(e.group)
      res = order.flatMap((g) => res.filter((e) => e.group === g))
    }
    shown = res
    sel = Math.min(sel, Math.max(0, res.length - 1))
    listEl.innerHTML = ''
    let last = null
    res.forEach((e, i) => {
      if (e.group !== last) {
        last = e.group
        listEl.append(h('div.pal-group', e.group))
      }
      listEl.append(h('button.pal-row' + (i === sel ? '.sel' : ''), { onclick: () => go(e), onmousemove: () => i !== sel && ((sel = i), mark()) }, h('i.pal-ic', { html: e.ic }), h('b', e.label), h('small', e.sub)))
    })
    if (!res.length) listEl.append(h('div.pal-empty', 'Nothing matches.'))
  }
  const mark = () => {
    const rows = listEl.querySelectorAll('.pal-row')
    rows.forEach((r, i) => r.classList.toggle('sel', i === sel))
    rows[sel]?.scrollIntoView({ block: 'nearest' })
  }
  input.addEventListener('input', () => {
    sel = 0
    render()
  })
  input.addEventListener('keydown', (e) => {
    e.stopPropagation()
    if (e.key === 'Escape') return close()
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      sel = Math.min(shown.length - 1, sel + 1)
      mark()
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      sel = Math.max(0, sel - 1)
      mark()
    } else if (e.key === 'Enter') go(shown[sel])
  })
  const box = h('div.pal-box', h('div.pal-top', h('i', { html: icon('search') }), input, h('kbd', 'Esc')), listEl, h('div.pal-foot', h('span', h('kbd', '↑'), h('kbd', '↓'), ' move'), h('span', h('kbd', 'Enter'), ' open'), h('span', h('kbd', 'Ctrl'), '+', h('kbd', 'K'), ' anywhere in camp')))
  const el = h('div.palette', { onclick: (e) => e.target === el && close() }, box)
  ui.root.append(el)
  ui.paletteEl = el
  render()
  setTimeout(() => input.focus(), 0)
}
