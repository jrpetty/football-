// The interface: top bar (time, horde timer, resources, power, morale, crew,
// speed), the left nav, a right-hand detail drawer, the bottom build dock,
// toasts, tooltips, modals, the raid HUD and reports.
import { RES, TOP_BAR, STATIONS, STATION_CATS, AMMO_KEYS, FENCE, EXPANSIONS, HORDES, OCCUPATIONS, SEC_PER_HOUR, GAME_MIN_PER_SEC, MILESTONES, SEASON_DAYS } from '../game/data.js'
import { NET, S, day, hour, clockStr, gameDur, capOf, bedCount, buildCost, reqMet, canAfford, countType, maxLevelOf, expansionAvailable, expansionCost, fenceUpgradeCost, getS, survivorStats, unlockedBy, msDone, season, seasonDay, year } from '../game/state.js'
import { raidIntel, campFlow, power, moraleFactors } from '../game/economy.js'
import { WEATHER } from '../render/sky.js'
import { sfx } from '../core/audio.js'
import { bus, h, fmt, fmtTime, clamp } from '../core/util.js'
import { icon } from './icons.js'
import { costList, resIcon, bar, plural } from './common.js'

const resChipSigned = (k, v) => h('span.ci', { style: { '--c': RES[k].color } }, h('i.ic', { html: resIcon(k) }), `${v > 0 ? '+' : '−'}${fmt(Math.abs(v))}`)
import { renderStation } from './stationpanel.js'
import { renderSurvivor, renderCrew, renderItems } from './crewpanel.js'
import { renderProgress } from './progresspanel.js'
import { manualModal } from './manual.js'
import { motorPool } from './motorpool.js'
import { renderJournal } from './journal.js'
import { storyBadge } from '../game/story.js'
import { renderPlayers, feedChat, netChip, updateNetChip } from './netui.js'
import { volumeControl } from './volume.js'
import { callName, fullName } from '../game/deeds.js'
import { fullscreenButton } from './fullscreen.js'
import { victoryModal, renderMarket, renderLog, renderFence, renderExpansion, renderProduction, renderPower, renderMorale, renderSettings, recruitModal, raidReportModal, missionReportModal, gameOverModal, menuModal, hordeInfo } from './camppanels.js'

const NAV = [
  { id: 'build', label: 'Build', key: 'B', icon: 'build', primary: true },
  { id: 'crew', label: 'Crew', key: 'C', icon: 'people' },
  { id: 'items', label: 'Items', key: 'I', icon: 'items' },
  { id: 'trade', label: 'Trade', key: 'T', icon: 'market' },
  { id: 'camp', label: 'Camp', key: 'P', icon: 'production' },
  { id: 'map', label: 'Map', key: 'M', icon: 'map' },
  { id: 'progress', label: 'Progress', key: 'G', icon: 'goals' },
  { id: 'journal', label: 'Journal', key: 'J', icon: 'book' },
  { id: 'log', label: 'Log', key: 'L', icon: 'log' },
]

export class UI {
  constructor(game) {
    this.game = game
    this.root = document.getElementById('hud')
    this.panelKey = null
    this.panelFn = null
    this.panelT = 0
    this.topT = 0
    this.feedItems = []
    this.buildCat = 'living'
    this.build()
    this.offs = []
    const on = (ev, fn) => this.offs.push(bus.on(ev, fn))
    on('log', (e) => this.feedPush(e))
    on('goal', (g) => this.toast(`Goal complete: ${g.text}`, 'good'))
    on('levelup', (s, sk) => this.toast(`${s.first} reached level ${s.skills[sk]}`, 'good'))
    on('perkReady', (s) => this.toast(`${s.first} can choose a perk`, 'good'))
    on('infected', (s) => this.toast(`${s.first} is infected`, 'bad'))
    on('turned', (s) => this.toast(`${s.first} turned. The others had to put them down.`, 'bad'))
    on('season', (Z) => this.toast(`${Z.name} has come`, 'story'))
    on('crafted', (st, r, it) => {
      if (it && it.q >= 2) sfx('rare')
    })
    on('broken', (it) => this.toast('An item broke from wear. Repair it at a bench.', 'bad'))
    on('event', (ev) => this.toast(ev.kind === 'distress' ? 'Radio: a distress call. Check the map.' : 'A supply drop came down. Check the map.', 'story'))
    on('weather', () => (this.topT = 0))
    on('newDay', (d) => this.toast(`Day ${d}`, 'story'))
  }
  dispose() {
    for (const off of this.offs) off()
    this.root.innerHTML = ''
  }
  get pipe() {
    return this.game.pipe
  }

  // ---------------------------------------------------------------- shell
  build() {
    const R = this.root
    R.innerHTML = ''
    this.top = h('div.topbar')
    this.nav = h(
      'div.nav',
      NAV.map((n) => h('button.navbtn' + (n.primary ? '.primary' : ''), { 'data-nav': n.id, 'data-tip': `${n.label} <kbd>${n.key}</kbd>`, onclick: () => this.navClick(n.id) }, h('i', { html: icon(n.icon) }), h('span', n.label))),
      h('div.navsep'),
      h('button.navbtn.small', { 'data-tip': 'Field manual <kbd>F1</kbd>', onclick: () => this.openManual() }, h('span.qm', '?')),
      fullscreenButton(this),
      h('button.navbtn.small', { 'data-tip': 'Settings', onclick: () => this.openSettings() }, h('i', { html: icon('settings') })),
      h('button.navbtn.small', { 'data-tip': 'Menu <kbd>Esc</kbd>', onclick: () => this.openMenu() }, h('i', { html: icon('menu') })),
    )
    this.panel = h('aside.panel', { hidden: true })
    this.dock = h('div.dock', { hidden: true })
    this.feed = h('div.feed')
    this.toasts = h('div.toasts')
    this.tip = h('div.tip', { hidden: true })
    this.placebar = h('div.placebar', { hidden: true })
    this.raidbar = h('div.raidbar', { hidden: true })
    this.modalRoot = h('div.modals')
    R.append(this.top, this.nav, this.panel, this.dock, this.feed, this.toasts, this.placebar, this.raidbar, this.tip, this.modalRoot)
    // delegated tooltips for anything with data-tip
    R.addEventListener('mouseover', (e) => {
      const t = e.target.closest?.('[data-tip]')
      if (t) {
        this.elTip = t
        this.showTip(t.getAttribute('data-tip'), e.clientX, e.clientY)
      }
    })
    R.addEventListener('mousemove', (e) => {
      if (this.elTip) this.placeTip(e.clientX, e.clientY)
    })
    R.addEventListener('mouseout', (e) => {
      if (this.elTip && !this.elTip.contains(e.relatedTarget)) {
        this.elTip = null
        this.tip.hidden = true
      }
    })
    this.renderTop()
  }
  showCamp(v) {
    this.top.hidden = !v
    this.nav.hidden = !v
    this.feed.hidden = !v
    if (this.coopEl) this.coopEl.hidden = !v || !this.coopEl.children.length
    if (!v) {
      this.closePanel()
      this.dock.hidden = true
    }
  }
  navClick(id) {
    sfx('click')
    if (id === 'build') return this.toggleBuild()
    if (id === 'map') return this.game.openMap()
    const fns = { players: () => this.openPlayers(), crew: () => this.openCrew(), items: () => this.openItems(), trade: () => this.openMarket(), camp: () => this.openProduction(), progress: () => this.openProgress(), journal: () => this.openJournal(), log: () => this.openLog() }
    if (this.panelKey === id) return this.closePanel()
    fns[id]?.()
  }
  onKey(e) {
    if (e.target instanceof HTMLInputElement) return false
    const k = e.key.toLowerCase()
    if (k === 'f1') {
      e.preventDefault?.()
      this.openManual()
      return true
    }
    if (this.modalRoot.children.length) {
      if (k === 'escape') this.closeModal()
      return true
    }
    if (NET.role !== 'solo' && k === 'enter' && (this.game.scene === this.game.base || this.game.scene === this.game.map)) {
      this.openChat()
      return true
    }
    // camp hotkeys belong to the camp: runs and the city map have their own
    if (S.raid || this.game.scene !== this.game.base) return false
    if (k === 'o' && NET.role !== 'solo') {
      this.navClick('players')
      return true
    }
    const map = { b: 'build', c: 'crew', i: 'items', t: 'trade', p: 'camp', m: 'map', g: 'progress', j: 'journal', l: 'log' }
    if (map[k] && !e.ctrlKey && !e.metaKey && !this.game.base?.placing) {
      this.navClick(map[k])
      return true
    }
    if (k === 'f' && !this.game.base?.placing) {
      this.openFence()
      return true
    }
    if (k === 'h') {
      this.openHorde()
      return true
    }
    if (k === 'escape') {
      if (this.game.base?.placing) return false
      if (!this.dock.hidden) {
        this.dock.hidden = true
        return true
      }
      if (this.panelKey) {
        this.closePanel()
        return true
      }
      this.openMenu()
      return true
    }
    return false
  }

  // ---------------------------------------------------------------- top bar
  renderTop() {
    const T = this.top
    T.innerHTML = ''
    const w = S.weather?.type || 'clear'
    const night = hour() >= 20.5 || hour() < 5.5
    this.clockEl = h('b')
    this.dayEl = h('span')
    this.seasonEl = h('span.season')
    const wIcon = w === 'rain' ? 'rain' : w === 'snow' ? 'snow' : w === 'fog' ? 'fog' : w === 'overcast' || w === 'hazy' ? 'cloud' : night ? 'moon' : 'sun'
    this.hordeBtn = h('button.horde', { onclick: () => this.openHorde(), 'data-tip': 'Horde intel <kbd>H</kbd>' }, h('i.ic', { html: icon('horde') }), h('div', h('small'), h('b')))
    this.resEls = {}
    const chips = h(
      'div.res',
      [...TOP_BAR.filter((k) => k !== 'cash'), 'ammo', 'cash'].map((k) => {
        const el = h('button.rchip', { onclick: () => this.openProduction(), style: { '--c': k === 'ammo' ? RES.pammo.color : RES[k].color } }, h('i.ic', { html: k === 'ammo' ? icon('ammo') : resIcon(k) }), h('b'), h('i.fill'), h('em.rate'))
        el.addEventListener('mouseenter', () => (this.hoverRes = k))
        el.addEventListener('mouseleave', () => (this.hoverRes = null))
        this.resEls[k] = el
        return el
      }),
    )
    this.pwEl = h('button.rchip.pw', { onclick: () => this.openPower() }, h('i.ic', { html: icon('bolt') }), h('b'))
    this.morEl = h('button.rchip.mor', { onclick: () => this.openMorale() }, h('i.ic', { html: icon('morale') }), h('b'))
    this.popEl = h('button.rchip.pop', { onclick: () => this.openCrew() }, h('i.ic', { html: icon('people') }), h('b'))
    const speeds = [
      [0, icon('pause'), 'Pause <kbd>Space</kbd>'],
      [1, '1×', 'Normal speed <kbd>1</kbd>'],
      [2, '2×', 'Fast <kbd>2</kbd>'],
      [4, '4×', 'Fastest <kbd>3</kbd>'],
    ]
    this.speedEl = h(
      'div.speed' + (NET.role === 'client' && !this.game.net?.isAdmin() ? '.locked' : ''),
      speeds.map(([v, label, tip]) => h('button', { 'data-v': v, 'data-tip': tip, html: label, onclick: () => this.game.setSpeed(v) })),
    )
    T.append(
      h('div.brand', h('div.logo', 'HOLDOUT'), h('div.clock', h('i.ic', { html: icon(wIcon), 'data-tip': WEATHER[w]?.name || w }), this.dayEl, this.seasonEl, this.clockEl)),
      this.hordeBtn,
      chips,
      h('div.meters', this.pwEl, this.morEl, this.popEl, NET.role !== 'solo' ? (this.netEl ??= netChip(this)) : null),
      volumeControl(this.game),
      this.speedEl,
    )
    this.weatherShown = w
    this.updateTop()
  }
  updateTop() {
    if (!S) return
    if ((S.weather?.type || 'clear') !== this.weatherShown) return this.renderTop()
    this.dayEl.textContent = `Day ${day()}`
    const Z = season()
    if (this.seasonEl.textContent !== Z.name) {
      this.seasonEl.textContent = Z.name
      this.seasonEl.style.setProperty('--c', Z.color)
    }
    this.seasonEl.setAttribute('data-tip', `<b>${Z.name}, year ${year()}</b>Day ${seasonDay()} of ${SEASON_DAYS}. ${Z.desc}${S.cold ? '<br><span class="bad">Out of wood and fuel: the camp is freezing.</span>' : ''}`)
    this.seasonEl.classList.toggle('cold', !!S.cold)
    this.clockEl.textContent = clockStr()
    const flow = this.flowCache || {}
    for (const [k, el] of Object.entries(this.resEls)) {
      let v
      let cap
      let rate
      if (k === 'ammo') {
        v = AMMO_KEYS.reduce((a, x) => a + S.res[x], 0)
        cap = AMMO_KEYS.reduce((a, x) => a + capOf(x), 0)
        rate = AMMO_KEYS.reduce((a, x) => a + (flow[x] || 0), 0)
      } else {
        v = S.res[k]
        cap = capOf(k)
        rate = flow[k] || 0
      }
      el.querySelector('b').textContent = fmt(v)
      const f = el.querySelector('.fill')
      if (cap !== Infinity) f.style.width = `${clamp(v / cap, 0, 1) * 100}%`
      else f.style.width = '0'
      el.classList.toggle('full', cap !== Infinity && v >= cap - 0.5)
      el.classList.toggle('empty', (k === 'food' || k === 'water') && v < 1)
      el.classList.toggle('low', (k === 'food' || k === 'water') && v >= 1 && rate < 0 && v / -rate < 1)
      const re = el.querySelector('.rate')
      const r = Math.round(rate)
      re.textContent = r ? (r > 0 ? '+' : '') + r : ''
      re.className = 'rate' + (r > 0 ? ' up' : r < 0 ? ' down' : '')
      el.setAttribute('data-tip', this.resTip(k, v, cap, rate))
    }
    const p = power()
    this.pwEl.querySelector('b').textContent = `${fmt(p.used)}/${fmt(p.supply)}`
    this.pwEl.classList.toggle('short', p.demand > p.supply + 0.01)
    this.pwEl.setAttribute('data-tip', `<b>Power</b>${p.supply ? `${p.supply} available, ${p.demand} wanted.` : 'No power. Build a Generator or Solar Array.'}`)
    const m = Math.round(S.morale)
    this.morEl.querySelector('b').textContent = m
    this.morEl.className = 'rchip mor' + (m < 25 ? ' bad' : m >= 65 ? ' good' : '')
    this.morEl.setAttribute('data-tip', `<b>Morale ${m}</b>${m < 25 ? 'Dangerously low: people work slower and may leave.' : m >= 65 ? 'High spirits: everyone works faster.' : 'Steady.'}`)
    const beds = bedCount()
    this.popEl.querySelector('b').textContent = `${S.survivors.length}/${beds}`
    this.popEl.setAttribute('data-tip', `<b>${plural(S.survivors.length, 'survivor')}</b>${beds} beds. Build or upgrade Bunkhouses to take in more.`)
    for (const b of this.speedEl.children) b.classList.toggle('on', +b.dataset.v === (S.speed ?? 1))
    this.updateHorde()
  }
  resTip(k, v, cap, rate) {
    if (k === 'ammo') return `<b>Ammunition</b>${AMMO_KEYS.map((a) => `${RES[a].name}: ${fmt(S.res[a])}`).join('<br>')}<br><em>${rate >= 0 ? '+' : ''}${Math.round(rate)} a day</em>`
    const R = RES[k]
    const days = rate < 0 && v > 0 ? ` · lasts ${(v / -rate).toFixed(1)} days` : ''
    return `<b>${R.name}</b>${fmt(v)}${cap !== Infinity ? ` / ${fmt(cap)}` : ''}<br><em>${rate >= 0 ? '+' : ''}${Math.round(rate)} a day${days}</em><br><small>${R.desc}</small>`
  }
  updateHorde() {
    const I = raidIntel()
    const el = this.hordeBtn
    if (S.raid) {
      el.className = 'horde now' + (S.raid.blood ? ' blood' : '')
      el.querySelector('small').textContent = S.raid.blood ? 'Blood Moon' : 'Under attack'
      el.querySelector('b').textContent = `${S.raid.count - S.raid.killed} left`
      return
    }
    if (!I) return
    const mins = I.in
    el.className = 'horde' + (mins < 60 ? ' urgent imminent' : mins < 180 ? ' urgent' : '') + (I.blood ? ' blood' : '')
    el.querySelector('small').textContent = I.known ? `${I.name} · ${I.count}` : I.blood ? 'Blood Moon rising' : 'Horde incoming'
    el.querySelector('b').textContent = gameDur(mins)
  }
  openHorde() {
    this.openPanel('horde', () => hordeInfo(this), { live: true })
  }

  // ---------------------------------------------------------------- tooltips
  showTip(html, x, y) {
    if (!html) {
      this.tip.hidden = true
      return
    }
    this.tip.innerHTML = html
    this.tip.hidden = false
    this.placeTip(x, y)
  }
  placeTip(x, y) {
    const t = this.tip
    const w = t.offsetWidth
    const hh = t.offsetHeight
    let tx = x + 16
    let ty = y + 18
    if (tx + w > window.innerWidth - 8) tx = x - w - 12
    if (ty + hh > window.innerHeight - 8) ty = y - hh - 12
    t.style.transform = `translate(${Math.max(6, tx)}px, ${Math.max(6, ty)}px)`
  }
  hoverTip(html, x, y) {
    if (this.elTip) return
    this.showTip(html, x, y)
  }

  // ---------------------------------------------------------------- feed & toasts
  feedPush(e) {
    if (!e.text) return
    const el = h('div.fe.' + (e.kind || 'plain'), h('span.t', clockStr()), h('span', e.text))
    this.feed.prepend(el)
    while (this.feed.children.length > 6) this.feed.lastChild.remove()
    setTimeout(() => el.classList.add('old'), 9000)
    setTimeout(() => el.remove(), 20000)
  }
  toast(msg, kind = '') {
    const el = h('div.toast' + (kind ? '.' + kind : ''), msg)
    this.toasts.append(el)
    while (this.toasts.children.length > 4) this.toasts.firstChild.remove()
    setTimeout(() => el.classList.add('out'), 3200)
    setTimeout(() => el.remove(), 3700)
  }

  // ---------------------------------------------------------------- drawer panel
  openPanel(key, fn, { live = false, wide = false, xwide = false } = {}) {
    this.panelKey = key
    this.panelFn = fn
    this.panelLive = live
    this.panel.hidden = false
    this.panel.classList.toggle('wide', !!wide)
    this.panel.classList.toggle('xwide', !!xwide)
    for (const b of this.nav.querySelectorAll('[data-nav]')) b.classList.toggle('on', b.dataset.nav === key)
    this.refreshPanel(true)
  }
  refreshPanel(force = false) {
    if (!this.panelFn) return
    const scroll = this.panel.querySelector('.pbody')?.scrollTop || 0
    const content = this.panelFn()
    if (!content) return this.closePanel()
    this.panel.innerHTML = ''
    this.panel.append(content)
    const body = this.panel.querySelector('.pbody')
    if (body && !force) body.scrollTop = scroll
  }
  closePanel() {
    this.panelKey = null
    this.panelFn = null
    this.panel.hidden = true
    this.panel.innerHTML = ''
    for (const b of this.nav.querySelectorAll('[data-nav]')) b.classList.remove('on')
    if (this.game.base) {
      this.game.base.select(null)
      this.game.base.selectedPerson = null
    }
  }
  // Standard drawer layout: header (title, subtitle, close) + scrolling body.
  frame(title, sub, body, { icon: ic = null, extra = null, tabs = null } = {}) {
    return h(
      'div.pframe',
      h('header.phead', ic ? h('i.pic', { html: icon(ic) }) : null, h('div.ptitle', h('h2', title), sub ? h('div.psub', sub) : null), extra, h('button.x', { onclick: () => this.closePanel(), 'data-tip': 'Close <kbd>Esc</kbd>', html: icon('close') })),
      tabs,
      h('div.pbody', body),
    )
  }

  // ---------------------------------------------------------------- routing
  openStation(id) {
    const st = S.stations.find((s) => s.id === id)
    if (!st) return
    this.game.base?.select(id)
    this.openPanel('station:' + id, () => renderStation(this, id), { live: true })
  }
  openSurvivor(id) {
    if (this.game.base) this.game.base.selectedPerson = id
    this.openPanel('survivor:' + id, () => renderSurvivor(this, id), { live: true })
  }
  openCrew() {
    this.openPanel('crew', () => renderCrew(this), { live: true, wide: true })
  }
  openItems() {
    this.openPanel('items', () => renderItems(this), { wide: true })
  }
  openMarket() {
    this.openPanel('trade', () => renderMarket(this), { wide: true })
  }
  openProgress() {
    this.openPanel('progress', () => renderProgress(this), { wide: true, live: true })
  }
  openLog() {
    this.openPanel('log', () => renderLog(this), { live: true })
  }
  openFence() {
    this.openPanel('fence', () => renderFence(this), { live: true })
  }
  openExpansion(id) {
    this.openPanel('exp:' + id, () => renderExpansion(this, id), { live: true })
  }
  openProduction() {
    this.openPanel('camp', () => renderProduction(this), { live: true, wide: true, xwide: true })
  }
  openPower() {
    this.openPanel('power', () => renderPower(this), { live: true })
  }
  openMorale() {
    this.openPanel('morale', () => renderMorale(this), { live: true })
  }
  openSettings() {
    this.modal(renderSettings(this), { small: true })
  }
  // ---------------------------------------------------------------- multiplayer
  netReady() {
    if (this.nav.querySelector('[data-nav=players]')) return
    const b = h('button.navbtn', { 'data-nav': 'players', 'data-tip': 'Players <kbd>O</kbd>', onclick: () => this.navClick('players') }, h('i', { html: icon('people') }), h('span', 'Players'))
    this.nav.querySelector('.navsep').before(b)
    this.chatIn = h('input.inp', { placeholder: 'Say something… (Enter to send, Esc to close)', maxLength: 240 })
    this.chatbar = h('div.chatbar', { hidden: true }, this.chatIn)
    this.chatIn.addEventListener('keydown', (e) => {
      e.stopPropagation()
      if (e.key === 'Enter') {
        const t = this.chatIn.value.trim()
        if (t) this.game.net?.say(NET.pid, t)
        this.closeChat()
      } else if (e.key === 'Escape') this.closeChat()
    })
    this.chatIn.addEventListener('blur', () => setTimeout(() => this.closeChat(), 100))
    this.root.append(this.chatbar)
    this.renderTop()
    for (const m of (this.game.net?.chat || []).slice(-4)) feedChat(this.feed, m)
    this.coopChanged()
  }
  // Invitations to friends' runs, and the run you joined, at the bottom right.
  coopChanged() {
    const coop = this.game.coop
    if (!coop) return
    if (!this.coopEl) {
      this.coopEl = h('div.coopcards')
      this.root.append(this.coopEl)
    }
    const cards = []
    for (const run of coop.runs.values()) {
      if (run.leader === NET.pid) continue
      const P = S.mp.players[run.leader]
      const joined = coop.joined === run.id
      const going = Object.entries(run.roster)
        .map(([pid, ids]) => `${pid === NET.pid ? 'you' : S.mp.players[pid]?.name || '?'}: ${ids.map((id) => S.survivors.find((x) => x.id === id)?.first).filter(Boolean).join(', ')}`)
        .join(' · ')
      cards.push(
        h(
          'div.coopinv' + (joined ? '.joined' : ''),
          { style: { '--c': P?.color || '#888' } },
          h('div.ci-head', h('i', { html: icon('people') }), h('b', `${P?.name || 'Someone'} → ${run.locName}`), h('small', `L${run.level}`), h('button.x', { onclick: () => coop.dismiss(run.id), html: icon('close'), 'data-tip': 'Not this time' })),
          h('small.ci-going', going),
          joined
            ? h('div.ci-row', h('span.good', 'You are going. Waiting for them to set out.'), h('button.btn.small.ghost', { onclick: () => coop.leave(run.id) }, 'Leave'))
            : h('div.ci-row', h('span.dim', 'Bring survivors you lead.'), h('button.btn.small.go', { onclick: () => this.coopPick(run) }, 'Join')),
        ),
      )
    }
    this.coopEl.replaceChildren(...cards)
    this.coopEl.hidden = !cards.length || this.top.hidden
  }
  coopPick(run) {
    const coop = this.game.coop
    const av = coop.available()
    const room = Math.min(4, 6 - coop.total(run))
    const pick = new Set()
    let close
    const body = h('div.picklist')
    const draw = () => {
      body.replaceChildren(
        ...av.map((s) =>
          h(
            'button.pickrow' + (pick.has(s.id) ? '.on' : ''),
            { onclick: () => (pick.has(s.id) ? pick.delete(s.id) : pick.size < room && pick.add(s.id), sfx('click'), draw()) },
            h('img.por', { src: this.game.portrait(s) }),
            h('div.pr-main', h('b', s.name), h('span', `${OCCUPATIONS[s.occ].name} · ${survivorStats(s).weapon.name}`)),
            h('div.pr-job', pick.has(s.id) ? 'Going' : ''),
          ),
        ),
      )
    }
    draw()
    close = this.modal(
      h('div', h('h2', `Join the run to ${run.locName}`), h('p.note', av.length ? `Pick up to ${room} of the survivors you lead.` : 'You have nobody free to send: injured, sick and busy survivors stay home.'), body),
      {
        actions: [
          h('button.btn.ghost', { onclick: () => close() }, 'Cancel'),
          h('button.btn.go', { onclick: () => (pick.size ? (coop.join(run.id, [...pick]), close()) : sfx('error')) }, 'Join the run'),
        ],
      },
    )
  }
  openChat() {
    if (!this.chatbar) return
    this.chatbar.hidden = false
    this.feed.classList.add('chatting')
    this.chatIn.value = ''
    setTimeout(() => this.chatIn.focus(), 0)
  }
  closeChat() {
    if (!this.chatbar || this.chatbar.hidden) return
    this.chatbar.hidden = true
    this.feed.classList.remove('chatting')
    this.chatIn.blur()
  }
  netChat(m) {
    feedChat(this.feed, m)
    if (m.pid && m.pid !== NET.pid) sfx('click')
    if (this.panelKey === 'players') this.refreshPanel(true)
  }
  openPlayers() {
    this.openPanel('players', () => renderPlayers(this), { live: true, wide: true })
  }
  netProblem(code) {
    const why = { not_permitted: 'Hosting here needs permission to send to the artifact’s live room: edit access, or contributor access if the owner opened it up. You can still join a friend’s camp, or host from the standalone file.', 'code-taken': 'Another camp is already using this code (or the last session has not timed out yet).', offline: 'The connection service could not be reached. Check your internet connection.', timeout: 'The connection service did not answer in time.' }[code] || `Something went wrong (${code}).`
    let close
    close = this.modal(h('div', h('h2', 'Friends can’t reach your camp yet'), h('p', why), h('p.note', 'You can keep playing: the camp runs and saves as normal.')), {
      small: true,
      actions: [h('button.btn.ghost', { onclick: () => close() }, 'Play on'), h('button.btn.go', { onclick: () => location.reload() }, 'Try again')],
    })
  }
  netGone(why) {
    this.closePanel()
    this.modal(h('div', h('h2', 'Disconnected'), h('p', why), h('p.note', 'Nothing is lost: the host keeps the camp. Join again when they are back.')), {
      small: true,
      locked: true,
      actions: [h('button.btn.ghost', { onclick: () => this.game.leaveNet() }, 'Title screen'), h('button.btn.go', { onclick: () => location.reload() }, 'Rejoin')],
    })
  }
  openJournal() {
    this.openPanel('journal', () => renderJournal(this), { live: true, wide: true })
  }
  openMotorPool() {
    const wrap = h('div.motorpool')
    const draw = () => {
      wrap.replaceChildren(...motorPool(this, draw))
    }
    draw()
    this.modal(wrap, {})
  }
  openManual(page) {
    if (this.modalRoot.querySelector('.manual')) return this.closeModal()
    const M = this.game.mission
    if (M && this.game.scene === M && !M.paused && !M.coop) ((M.paused = true), M.renderPause?.())
    this.modal(manualModal(this, page), { xl: true })
  }
  openMenu() {
    this.modal(menuModal(this), { small: true })
  }
  showRecruit() {
    if (!S.recruit.pending) return
    this.modal(recruitModal(this), { small: false })
  }
  raidReport(r) {
    this.modal(raidReportModal(this, r), { small: true })
  }
  // What the crew got done while the game was closed.
  awayModal(r) {
    const hrs = r.away / 3600
    const gains = r.diff.filter(([, v]) => v > 0).slice(0, 10)
    const used = r.diff.filter(([, v]) => v < 0).slice(0, 6)
    let close
    close = this.modal(
      h(
        'div.away',
        h('h2', 'While you were away'),
        h('p.note', `${hrs >= 1 ? `${hrs.toFixed(1)} hours` : `${Math.round(r.away / 60)} minutes`} away: the crew kept working for about ${r.days >= 1 ? `${r.days.toFixed(1)} days` : `${Math.round(r.days * 24)} hours`} of camp time. The clock waited for you, so no horde came.`),
        gains.length ? h('div.aw-row', h('small', 'Made'), h('div', gains.map(([k, v]) => resChipSigned(k, v)))) : null,
        used.length ? h('div.aw-row', h('small', 'Used'), h('div', used.map(([k, v]) => resChipSigned(k, v)))) : null,
        !gains.length && !used.length ? h('p', 'Not much changed.') : null,
      ),
      { small: true, actions: [h('button.btn.go', { onclick: () => close() }, 'Back to it')] },
    )
  }
  // A player back in a shared camp: what happened while they were gone.
  sinceModal(from) {
    const mins = S.time - from
    const lines = S.log.filter((e) => e.t > from && e.kind).slice(0, 12)
    const mine = S.survivors.filter((s) => S.mp?.owner?.[s.id] === NET.pid)
    const hurt = mine.filter((s) => s.status === 'injured' || s.infection > 0)
    let close
    close = this.modal(
      h(
        'div.away',
        h('h2', 'Since you were last here'),
        h('p.note', `${mins >= 1440 ? `${(mins / 1440).toFixed(1)} days` : `${Math.round(mins / 60)} hours`} of camp time went by. It is day ${day()} now${mine.length ? `, and you lead ${plural(mine.length, 'survivor')}` : ''}.${hurt.length ? ` ${hurt.map((s) => s.first).join(', ')} ${hurt.length === 1 ? 'needs' : 'need'} looking after.` : ''}`),
        lines.length ? h('div.log.since', lines.map((e) => h('div.le.' + e.kind, h('span.t', `D${Math.floor(e.t / 1440) + 1} ${clockStr(e.t)}`), h('span', e.text)))) : h('p', 'A quiet stretch: nothing worth telling.'),
      ),
      { small: true, actions: [h('button.btn.go', { onclick: () => close() }, 'Back to it')] },
    )
  }
  missionReport(r) {
    this.modal(missionReportModal(this, r))
  }
  victory(held) {
    let close
    close = this.modal(victoryModal(this, held, () => close()), { locked: true })
  }
  gameOver() {
    this.modal(gameOverModal(this), { small: true, locked: true })
  }

  // ---------------------------------------------------------------- build dock
  toggleBuild(force) {
    const show = force ?? this.dock.hidden
    this.dock.hidden = !show
    for (const b of this.nav.querySelectorAll('[data-nav="build"]')) b.classList.toggle('on', show)
    if (show) this.renderBuild()
  }
  renderBuild() {
    const D = this.dock
    D.innerHTML = ''
    const cats = [...STATION_CATS, { id: 'walls', name: 'Walls & Land' }]
    const tabs = h(
      'div.dtabs',
      cats.map((c) => h('button' + (c.id === this.buildCat ? '.on' : ''), { onclick: () => ((this.buildCat = c.id), this.renderBuild()) }, c.name)),
      h('span.dhint', 'Click a card, then click the ground. ', h('kbd', 'R'), ' rotates, ', h('kbd', 'Shift'), ' places several, ', h('kbd', 'Esc'), ' cancels.'),
      h('button.x', { onclick: () => this.toggleBuild(false), html: icon('close') }),
    )
    let cards
    if (this.buildCat === 'walls') cards = this.landCards()
    else
      cards = Object.entries(STATIONS)
        .filter(([, d]) => d.cat === this.buildCat && !d.fixed)
        .map(([type, d]) => {
          const cost = buildCost(type)
          const ok = canAfford(cost)
          const req = reqMet(type)
          const have = countType(type, 0)
          const msId = unlockedBy('station', type)
          const reqTxt = msId && !msDone(msId)
            ? `Milestone: ${MILESTONES[msId].name} (tier ${MILESTONES[msId].tier})`
            : d.unique && have
              ? 'Only one per camp'
              : d.req ? Object.entries(d.req).map(([t, l]) => `${STATIONS[t].name} L${l}`).join(', ') : ''
          return h(
            'button.bcard' + (!req ? '.locked' : !ok ? '.poor' : ''),
            {
              onclick: () => {
                if (!req) return this.toast(reqTxt.startsWith('Milestone') || reqTxt.startsWith('Only') ? reqTxt : `Needs ${reqTxt}`, 'bad'), sfx('error')
                this.game.base.startPlacing(type)
                sfx('click')
              },
              'data-tip': `<b>${d.name}</b>${d.desc}${d.workers[0] ? `<br><em>${d.workers[0]} worker${d.workers[0] > 1 ? 's' : ''} at level 1</em>` : ''}${reqTxt && !req ? `<br><span class="bad">${reqTxt.startsWith('Milestone') || reqTxt.startsWith('Only') ? reqTxt : `Requires ${reqTxt}`}</span>` : ''}`,
            },
            h('div.bc-name', d.name, have ? h('span.have', `×${have}`) : null),
            h('div.bc-size', type === 'mast' ? `${d.size[0]}×${d.size[1]} · 5 phases` : `${d.size[0]}×${d.size[1]} · ${d.levels} level${d.levels > 1 ? 's' : ''}`),
            req ? costList(cost, { small: true }) : h('div.bc-lock', h('i', { html: icon('lock') }), reqTxt),
          )
        })
    D.append(tabs, h('div.dcards', cards))
  }
  landCards() {
    const out = []
    const next = FENCE[S.fence.level + 1]
    if (next) {
      const cost = fenceUpgradeCost()
      const fm = unlockedBy('fence', S.fence.level + 1)
      const flock = fm && !msDone(fm)
      out.push(
        h(
          'button.bcard.wide' + (flock ? '.locked' : !canAfford(cost) ? '.poor' : ''),
          { onclick: () => this.openFence(), 'data-tip': `<b>${next.name}</b>${next.hp} strength per section (now ${FENCE[S.fence.level].hp}).${flock ? `<br><span class="bad">Milestone: ${MILESTONES[fm].name} (tier ${MILESTONES[fm].tier})</span>` : ''}` },
          h('div.bc-name', `Upgrade wall: ${next.name}`),
          h('div.bc-size', S.fence.building ? 'Under construction' : `${FENCE[S.fence.level].name} → ${next.name}`),
          flock ? h('div.bc-lock', h('i', { html: icon('lock') }), `Milestone: ${MILESTONES[fm].name}`) : costList(cost, { small: true }),
        ),
      )
    }
    for (const X of EXPANSIONS) {
      const avail = expansionAvailable(X.id)
      const status = S.expansions[X.id]
      if (status === 'done') continue
      const cost = expansionCost(X.id)
      const xm = unlockedBy('exp', X.id)
      const xlock = xm && !msDone(xm) ? `Milestone: ${MILESTONES[xm].name}` : null
      out.push(
        h(
          'button.bcard.wide' + (!avail ? '.locked' : !canAfford(cost) ? '.poor' : ''),
          { onclick: () => this.openExpansion(X.id), 'data-tip': `<b>${X.name}</b>${X.desc}${xlock ? `<br><span class="bad">${xlock} (tier ${MILESTONES[xm].tier})</span>` : ''}` },
          h('div.bc-name', X.name, h('span.have', X.side.toUpperCase() + X.ring)),
          h('div.bc-size', status === 'building' ? 'Clearing now' : avail ? 'Expand the camp' : xlock ? 'Not yet surveyed' : X.ring === 2 ? 'Expand this side once first' : 'Unavailable'),
          avail ? costList(cost, { small: true }) : h('div.bc-lock', h('i', { html: icon('lock') }), xlock || 'Locked'),
        ),
      )
    }
    return out
  }

  // ---------------------------------------------------------------- placement bar
  showPlaceBar(name, move) {
    const P = this.placebar
    P.hidden = false
    P.innerHTML = ''
    this.dock.classList.add('placing')
    P.append(
      h('b', move ? `Moving ${name}` : `Placing ${name}`),
      h('span.pb-ok'),
      h('button.btn.small', { onclick: () => this.game.base.rotatePlacing() }, h('i', { html: icon('rotate') }), 'Rotate', h('kbd', 'R')),
      h('button.btn.small.ghost', { onclick: () => this.game.base.cancelPlacing() }, 'Cancel', h('kbd', 'Esc')),
    )
  }
  placeOk(ok) {
    const el = this.placebar.querySelector('.pb-ok')
    if (el) {
      el.textContent = ok ? 'Click to place' : 'Blocked here'
      el.className = 'pb-ok ' + (ok ? 'good' : 'bad')
    }
  }
  hidePlaceBar() {
    this.placebar.hidden = true
    this.dock.classList.remove('placing')
    if (!this.dock.hidden) this.renderBuild()
  }
  // Laying a belt: what is being connected and how to finish.
  showLinkBar(title, hint) {
    const P = this.placebar
    this.closePanel()
    P.hidden = false
    P.innerHTML = ''
    P.classList.add('linkbar')
    P.append(
      h('i.pb-ic', { html: icon('belt') }),
      h('b', title),
      h('span.pb-ok.good', hint),
      h('button.btn.small.ghost', { onclick: () => this.game.base.cancelLinking(true) }, 'Cancel', h('kbd', 'Esc')),
    )
  }
  hideLinkBar() {
    this.placebar.hidden = true
    this.placebar.classList.remove('linkbar')
  }

  // ---------------------------------------------------------------- raid HUD
  raidHud(on) {
    this.raidbar.hidden = !on
    this.nav.classList.toggle('dim', on)
    if (on) {
      this.closePanel()
      this.dock.hidden = true
      this.updateRaidBar(true)
    }
  }
  updateRaidBar(rebuild = false) {
    const base = this.game.base
    if (!S.raid || !base) return
    const R = this.raidbar
    if (rebuild || !R.querySelector('.rb-squad') || R.querySelector('.rb-squad').children.length !== base.squad.length) {
      R.innerHTML = ''
      R.append(
        h('div.rb-head', h('i', { html: icon('horde') }), h('b.rb-left'), h('span.rb-ammo')),
        h(
          'div.rb-squad',
          base.squad.map((a, i) =>
            h('button.rb-def', { onclick: () => base.selectDefender(a), 'data-tip': `${fullName(a.data)}<br><em>${a.st.weapon.name}</em>` }, h('img', { src: this.game.portrait(a.data) }), h('span', h('kbd', i + 1), callName(a.data)), h('div.bar.hp', h('i'))),
          ),
        ),
        h('div.rb-hint', 'Select a defender, then right-click to move or attack. Click a downed friend with someone selected to revive them.'),
      )
    }
    R.querySelector('.rb-left').textContent = `Horde · ${Math.max(0, S.raid.count - S.raid.killed)} left`
    R.querySelector('.rb-ammo').innerHTML = AMMO_KEYS.map((k) => `<span style="--c:${RES[k].color}">${RES[k].short} ${fmt(S.res[k])}</span>`).join('')
    base.squad.forEach((a, i) => {
      const el = R.querySelector('.rb-squad').children[i]
      if (!el) return
      el.classList.toggle('sel', base.selectedDef === a)
      el.classList.toggle('down', a.downed)
      el.querySelector('.bar i').style.width = `${clamp(a.hp / a.maxHp, 0, 1) * 100}%`
    })
  }

  // ---------------------------------------------------------------- modals
  modal(content, { small = false, xl = false, actions = null, onClose = null, locked = false } = {}) {
    const close = () => {
      wrap.remove()
      onClose?.()
    }
    const card = h('div.modal' + (small ? '.small' : '') + (xl ? '.xl' : ''), content, actions ? h('div.mactions', actions) : null)
    const wrap = h('div.mwrap', { onclick: (e) => !locked && e.target === wrap && close() }, card)
    wrap._close = close
    this.modalRoot.append(wrap)
    return close
  }
  closeModal() {
    const last = this.modalRoot.lastChild
    last?._close?.()
  }
  confirm(title, text, yes, fn, { danger = false } = {}) {
    let close
    close = this.modal(h('div', h('h2', title), h('p', text)), {
      small: true,
      actions: [h('button.btn.ghost', { onclick: () => close() }, 'Cancel'), h('button.btn' + (danger ? '.danger' : '.go'), { onclick: () => (close(), fn()) }, yes)],
    })
  }

  // ---------------------------------------------------------------- loop
  update(dt) {
    this.topT -= dt
    if (this.topT <= 0) {
      this.topT = 0.25
      this.flowT = (this.flowT ?? 0) - 0.25
      if (this.flowT <= 0) {
        this.flowT = 1
        this.flowCache = campFlow()
        this.nav.querySelector('[data-nav=journal]')?.classList.toggle('badge', storyBadge() > 0)
      }
      this.updateTop()
      if (this.netEl) updateNetChip(this.netEl, this.game.net)
    }
    if (this.panelLive && this.panelFn) {
      this.panelT -= dt
      if (this.panelT <= 0 && !this.panel.matches(':hover:active') && !this.panel.querySelector('input:focus, select:focus')) {
        this.panelT = 1
        this.refreshPanel()
      }
    }
    if (S.raid) {
      this.rbT = (this.rbT ?? 0) - dt
      if (this.rbT <= 0) {
        this.rbT = 0.2
        this.updateRaidBar()
      }
    }
  }
}
