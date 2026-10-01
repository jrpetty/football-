// HTML interface: top bar, horde timer, panels for stations, survivors,
// building, armory, market and goals, plus all the modals.
import {
  RES, RES_KEYS, STOCK_KEYS, SKILLS, SKILL_KEYS, SKILL_MAX, xpForLevel, OCCUPATIONS, TRAITS, ITEMS, RARITY, STATIONS,
  STATION_CATS, FENCE, RECIPES, GOALS, HORDES, itemStatLine, DAY_MIN, GAME_MIN_PER_SEC, LOCATIONS,
} from './data.js'
import {
  S, day, hour, clockStr, gameDur, getS, survivorStats, survivorLevel, workEff, bestFor, equipped, equip, unequip, itemOf, ownerOf,
  storageCap, bedCount, canAfford, pay, gain, stationSize, workersOf, slots, assign, power, stationRate, isAutomated, kitchenSaving,
  upgradeCost, startUpgrade, demolish, recipesFor, queueCraft, cancelCraft, fenceMax, repairCost, repairFence, upgradeFence, raidIntel,
  acceptRecruit, declineRecruit, claimGoal, countType, reqMet, maxLevelOf, itemSellPrice, resSellPrice, addItem, removeItem, save, log,
  constructSpeed, powerNeed, killSurvivor, scheduleRecruit,
} from './state.js'
import { icon } from './icons.js'
import { sfx, setSound, soundOn } from './audio.js'
import { setQuality, gfx } from './gfx.js'
import { bus, h, fmt, clamp } from './util.js'

const SEC_PER_DAY = DAY_MIN / GAME_MIN_PER_SEC
const pct = (v) => `${Math.round(v * 100)}%`
const resChip = (k, v, need = null) => {
  const short = need != null && (S.res[k] || 0) < need
  return h('span.chip' + (short ? '.short' : ''), { style: { '--c': RES[k].color }, title: RES[k].name, html: icon(k) }, h('b', fmt(v)))
}
const costChips = (cost) => h('span.chips', Object.entries(cost).map(([k, v]) => resChip(k, v, v)))
const rarityTag = (id) => h('span.rar', { style: { '--c': RARITY[ITEMS[id].rarity].color } }, RARITY[ITEMS[id].rarity].name)

export class UI {
  constructor(game) {
    this.game = game
    this.root = document.getElementById('hud')
    this.panelKey = null
    this.panelFn = null
    this.tick = 0
    this.build()
    let pending = false
    const refresh = () => {
      if (pending) return
      pending = true
      requestAnimationFrame(() => {
        pending = false
        this.refreshPanel()
      })
    }
    for (const ev of ['change', 'stations', 'fence', 'crafted', 'built', 'recruitJoined', 'newDay', 'levelup', 'death']) bus.on(ev, refresh)
    bus.on('log', (e) => this.feedPush(e))
    bus.on('goal', (g) => {
      this.toast(`Goal complete: ${g.text}`, 'good')
      sfx('complete')
      this.renderTracker()
    })
    bus.on('levelup', (s, k) => {
      if (this.game.scene === this.game.base) sfx('levelup', 400)
    })
  }

  // ---------------------------------------------------------------- shell
  build() {
    const R = this.root
    R.innerHTML = ''
    this.top = h('header.topbar')
    this.brand = h('div.brand', h('span.logo', 'HOLDOUT'), (this.clockEl = h('div.clock')))
    this.hordeEl = h('button.horde', { onclick: () => this.openHordeInfo() })
    this.resEl = h('div.res')
    this.speedEl = h('div.speed')
    this.top.append(this.brand, this.hordeEl, this.resEl, this.speedEl)
    this.nav = h('nav.nav')
    const navBtn = (id, label, fn, cls = '') => {
      const b = h('button.navbtn' + cls, { onclick: () => (sfx('click'), fn()), title: label, html: icon(id) }, h('span', label))
      b.dataset.id = id
      return b
    }
    this.nav.append(
      navBtn('map', 'Scavenge', () => this.game.openMap(), '.primary'),
      navBtn('build', 'Build', () => this.toggle('build', () => this.renderBuild())),
      navBtn('people', 'Survivors', () => this.toggle('people', () => this.renderSurvivors())),
      navBtn('armory', 'Armory', () => this.toggle('armory', () => this.renderArmory())),
      navBtn('market', 'Market', () => this.toggle('market', () => this.renderMarket())),
      navBtn('goals', 'Goals', () => this.toggle('goals', () => this.renderGoals())),
      navBtn('menu', 'Menu', () => this.openMenu()),
    )
    this.panel = h('aside.panel', { hidden: true })
    this.feed = h('div.feed')
    this.toasts = h('div.toasts')
    this.modalRoot = h('div.modals')
    this.gateBadge = h('button.gatebadge', { hidden: true, onclick: () => this.showRecruit() }, h('span', { html: icon('gate') }), h('b', 'Someone is at the gate'))
    this.tracker = h('button.tracker', { onclick: () => this.toggle('goals', () => this.renderGoals()) })
    this.placeBar = h('div.placebar', { hidden: true })
    this.raidBar = h('div.raidbar', { hidden: true })
    this.campUI = h('div.campui', this.top, this.nav, this.panel, this.feed, this.gateBadge, this.tracker, this.placeBar, this.raidBar)
    R.append(this.campUI, this.toasts, this.modalRoot)
    this.renderTracker()
  }
  showCamp(v) {
    this.campUI.hidden = !v
    if (!v) this.closePanel()
  }
  update(dt) {
    this.tick -= dt
    if (this.tick > 0) return
    this.tick = 0.2
    this.renderTop()
    this.panelLive?.()
    this.gateBadge.hidden = !S.recruit.pending || !!S.raid
    if (S.raid) this.updateRaidBar()
  }

  // ---------------------------------------------------------------- top bar
  renderTop() {
    const hr = hour()
    const night = hr >= 20.5 || hr < 5.5
    this.clockEl.innerHTML = `<span class="ic">${icon(night ? 'moon' : 'sun')}</span><b>Day ${day()}</b><span>${clockStr()}</span>`
    // horde timer
    const intel = raidIntel()
    if (S.raid) {
      this.hordeEl.className = 'horde now'
      this.hordeEl.innerHTML = `<span class="ic">${icon('horde')}</span><div><small>Under attack</small><b>${S.raid.killed}/${S.raid.count} killed</b></div>`
    } else if (intel) {
      const urgent = intel.in < 3 * 60
      this.hordeEl.className = 'horde' + (urgent ? ' urgent' : '') + (intel.in < 60 ? ' imminent' : '')
      this.hordeEl.innerHTML = `<span class="ic">${icon('horde')}</span><div><small>${intel.name}${intel.count ? ` · ~${intel.count}` : ''}</small><b>arrives in ${gameDur(intel.in)}</b></div>`
    }
    // resources
    const cap = storageCap()
    if (!this.resChips) {
      this.resChips = {}
      this.resEl.innerHTML = ''
      for (const k of RES_KEYS) {
        const b = h('b')
        const fill = h('i.fill')
        const c = h('span.rchip', { style: { '--c': RES[k].color }, title: RES[k].name }, h('span.ic', { html: icon(k) }), b, fill)
        this.resChips[k] = { b, fill, c }
        this.resEl.append(c)
      }
      this.powerEl = h('span.rchip.pw', { title: 'Generator power used / available' })
      this.popEl = h('span.rchip.pop', { title: 'Survivors / beds' })
      this.resEl.append(this.powerEl, this.popEl)
    }
    for (const k of RES_KEYS) {
      const { b, fill, c } = this.resChips[k]
      const v = Math.floor(S.res[k])
      if (b.textContent !== fmt(v)) b.textContent = fmt(v)
      if (k !== 'cash') {
        fill.style.width = `${clamp(S.res[k] / cap, 0, 1) * 100}%`
        c.classList.toggle('full', S.res[k] >= cap - 0.5)
        c.classList.toggle('empty', S.res[k] < 1 && (k === 'food' || k === 'water'))
      }
      c.title = `${RES[k].name}${k !== 'cash' ? ` · ${v}/${cap}` : ''}`
    }
    const p = power()
    this.powerEl.innerHTML = `<span class="ic">${icon('power')}</span><b>${fmt(p.used)}/${fmt(p.supply)}</b>`
    this.powerEl.classList.toggle('short', p.demand > p.supply)
    this.popEl.innerHTML = `<span class="ic">${icon('people')}</span><b>${S.survivors.length}/${bedCount()}</b>`
    // speed
    const inCamp = this.game.scene === this.game.base
    const sp = inCamp && !S.raid ? S.speed : 1
    const key = `${sp}|${inCamp && !S.raid}`
    if (this.speedKey !== key) {
      this.speedKey = key
      this.speedEl.innerHTML = ''
      for (const v of [1, 2, 4]) {
        this.speedEl.append(h('button' + (sp === v ? '.on' : ''), { disabled: !(inCamp && !S.raid), onclick: () => ((S.speed = v), (this.speedKey = null), sfx('click')), title: `Game speed ×${v}` }, `${v}×`))
      }
    }
  }
  renderTracker() {
    const g = GOALS.find((x) => S.goals[x.id] !== 'claimed')
    const done = g && S.goals[g.id] === 'done'
    this.tracker.hidden = !g
    if (!g) return
    this.tracker.className = 'tracker' + (done ? ' done' : '')
    this.tracker.innerHTML = `<small>${done ? 'Reward ready' : 'Next goal'}</small><b>${g.text}</b>`
  }

  // ---------------------------------------------------------------- feed & toasts
  feedPush(e) {
    const el = h('div.fe', e.text)
    if (e.kind) el.classList.add(e.kind)
    this.feed.prepend(el)
    while (this.feed.children.length > 5) this.feed.lastChild.remove()
    setTimeout(() => el.classList.add('old'), 9000)
  }
  toast(msg, kind = '') {
    const t = h('div.toast', msg)
    t.className = 'toast ' + kind
    this.toasts.prepend(t)
    while (this.toasts.children.length > 4) this.toasts.lastChild.remove()
    setTimeout(() => t.classList.add('out'), 3600)
    setTimeout(() => t.remove(), 4200)
  }

  // ---------------------------------------------------------------- panels
  toggle(key, fn) {
    if (this.panelKey === key) return this.closePanel()
    this.openPanel(key, fn)
  }
  openPanel(key, fn, live = null) {
    this.panelKey = key
    this.panelFn = fn
    this.panelLiveFn = live
    this.panel.hidden = false
    this.panel.scrollTop = 0
    for (const b of this.nav.querySelectorAll('.navbtn')) b.classList.toggle('on', b.dataset.id === key)
    this.refreshPanel()
  }
  refreshPanel() {
    if (!this.panelFn || this.panel.hidden) return
    const st = this.panel.scrollTop
    this.panel.innerHTML = ''
    this.panelLive = null
    const content = this.panelFn()
    if (!content) return this.closePanel()
    this.panel.append(content)
    this.panel.scrollTop = st
  }
  closePanel() {
    this.panel.hidden = true
    this.panelKey = null
    this.panelFn = null
    this.panelLive = null
    if (this.game.base) this.game.base.selectedStation = null
    for (const b of this.nav.querySelectorAll('.navbtn')) b.classList.remove('on')
  }
  head(title, sub = '', extra = null) {
    return h('div.phead', h('div', h('h2', title), sub ? h('p.sub', sub) : null), extra, h('button.x', { onclick: () => this.closePanel(), 'aria-label': 'Close', html: icon('close') }))
  }

  // ---------------------------------------------------------------- station
  openStation(id) {
    this.game.base.selectedStation = id
    this.openPanel('station:' + id, () => this.renderStation(id))
  }
  renderStation(id) {
    const st = S.stations.find((s) => s.id === id)
    if (!st) return null
    const d = STATIONS[st.type]
    const lv = st.level
    const pinfo = power()
    const frag = h('div.pbody')
    frag.append(this.head(d.name, lv ? `Level ${lv} of ${d.levels}` : 'Under construction', h('span.cat', STATION_CATS.find((c) => c.id === d.cat).name)))
    frag.append(h('p.desc', d.desc))

    if (st.building) {
      const bar = h('i')
      const eta = h('small')
      frag.append(h('div.box', h('div.row', h('b', st.building.to > 1 ? `Upgrading to level ${st.building.to}` : 'Being built'), eta), h('div.bar.big', bar), h('p.note', 'Survivors without a job help with construction. Carpenters and construction workers build faster.')))
      this.panelLive = () => {
        bar.style.width = pct(1 - st.building.left / st.building.total)
        eta.textContent = `${Math.ceil(st.building.left / constructSpeed())}s left`
      }
      this.panelLive()
      if (!lv) return frag
    }

    // status line
    const status = h('div.status')
    const rate = stationRate(st, pinfo)
    const auto = isAutomated(st, pinfo)
    const statusRender = () => {
      status.innerHTML = ''
      const lines = this.stationEffect(st, pinfo)
      for (const l of lines) status.append(h('div.sline', l))
      if (auto) status.append(h('div.sline.auto', h('span', { html: icon('power') }), `Automated · draws ${d.autoPower} power`))
      if (st.stalled) status.append(h('div.sline.warn', st.stalled))
    }
    statusRender()
    frag.append(status)

    // workers
    const n = slots(st)
    if (n > 0) {
      const ws = workersOf(st)
      const label = st.type === 'watchtower' ? 'Guards' : st.type === 'training' ? 'Trainees' : st.type === 'infirmary' ? 'Medics' : st.type === 'generator' ? 'Operator' : 'Workers'
      const best = bestFor(st.type)
      const list = h('div.slots')
      for (let i = 0; i < n; i++) {
        const s = ws[i]
        if (s) {
          const eff = workEff(s, st.type)
          list.append(
            h('div.slot.filled', h('img', { src: this.game.portrait(s), alt: '' }), h('div', h('b', { onclick: () => this.openSurvivor(s.id) }, s.first), h('small', `${OCCUPATIONS[s.occ].name} · ${d.skill ? `${SKILLS[d.skill].name} ${s.skills[d.skill]}` : ''}`)), h('span.eff', { title: 'Work efficiency' }, pct(eff)), h('button.mini', { onclick: () => (assign(s, null), sfx('click')) }, 'Remove')),
          )
        } else {
          list.append(h('button.slot.empty', { onclick: () => this.pickWorker(st) }, h('span', '+'), h('b', `Assign ${label.toLowerCase().replace(/s$/, '')}`)))
        }
      }
      frag.append(h('h3', label, h('small', best.length ? `Best: ${best.join(', ')}` : d.skill ? `Skill: ${SKILLS[d.skill].name}` : '')), list)
    }

    // automation
    if (d.auto) {
      const can = lv >= d.auto
      frag.append(
        h(
          'div.box.autobox' + (can ? '' : '.locked'),
          h('div.row', h('b', h('span', { html: icon('power') }), 'Automation'), can ? h('label.switch', h('input', { type: 'checkbox', checked: st.autoOn !== false, onchange: (e) => ((st.autoOn = e.target.checked), bus.emit('change')) }), h('i')) : h('small', `Unlocks at level ${d.auto}`)),
          h('p.note', can ? (pinfo.powered.has(st.id) ? `Runs without workers at ${d.autoRate}× a worker's output. Workers still add on top.` : pinfo.supply <= 0 ? 'No power. Build a Generator and keep it fuelled.' : 'Not enough generator power for this station.') : `At level ${d.auto} this station can run on generator power (${d.autoPower} power) with no one assigned.`),
        ),
      )
    }

    // crafting
    if (d.queue) frag.append(this.craftSection(st))
    if (st.type === 'training') {
      const sel = h('select#train-skill', { onchange: (e) => ((st.trainSkill = e.target.value), bus.emit('change')) })
      for (const [v, l] of [['auto', 'Weakest combat skill'], ['melee', 'Melee'], ['ranged', 'Ranged'], ['scavenge', 'Scavenging'], ['medic', 'Medicine']]) sel.append(h('option', { value: v, selected: st.trainSkill === v }, l))
      frag.append(h('div.box', h('div.row', h('b', 'Drill'), sel)))
    }
    if (st.type === 'infirmary' && st.patients?.length) {
      frag.append(h('h3', 'Patients'), h('div.plist', st.patients.map((pid) => getS(pid)).filter(Boolean).map((p) => this.personRow(p))))
    }

    // upgrade
    const cost = upgradeCost(st)
    if (cost && !st.building) {
      const next = lv + 1
      const benefits = this.upgradeBenefits(st, next)
      frag.append(
        h(
          'div.box.upg',
          h('div.row', h('b', `Upgrade to level ${next}`), h('small', `${d.time[lv]}s build`)),
          benefits.length ? h('ul.ben', benefits.map((b) => h('li', b))) : null,
          h('div.row', costChips(cost), h('button.btn.go', { disabled: !canAfford(cost), onclick: () => (startUpgrade(st) ? sfx('build') : sfx('error')) }, 'Upgrade')),
        ),
      )
    } else if (!cost) frag.append(h('p.note.center', 'Fully upgraded.'))

    if (!d.fixed) {
      frag.append(
        h('div.danger-row', h('button.btn.ghost.small', { onclick: () => this.confirm(`Demolish the ${d.name}?`, 'You get half of the materials back. Workers become idle.', 'Demolish', () => (demolish(st), this.closePanel())) }, 'Demolish')),
      )
    }
    const prev = this.panelLive
    this.panelLive = () => {
      prev?.()
      statusRender()
      this.craftLive?.()
    }
    return frag
  }
  stationEffect(st, pinfo) {
    const d = STATIONS[st.type]
    const lv = st.level
    const rate = stationRate(st, pinfo)
    const out = []
    if (d.recipe && st.type !== 'infirmary') {
      const tm = Array.isArray(d.recipe.time) ? d.recipe.time[lv - 1] : d.recipe.time
      const cycles = (rate * SEC_PER_DAY) / tm
      const outs = Object.entries(d.recipe.out).map(([k, v]) => `${(v * cycles).toFixed(1)} ${RES[k].name.toLowerCase()}`)
      const ins = Object.entries(d.recipe.in).map(([k, v]) => `${(v * cycles).toFixed(1)} ${RES[k].name.toLowerCase()}`)
      out.push(rate > 0 ? `Makes ${outs.join(', ')} per day${ins.length ? ` from ${ins.join(', ')}` : ''}` : 'Idle: nobody is working here')
    }
    if (d.passive) out.push(`Collects ${d.passive.water[lv - 1]} water per day on its own`)
    if (st.type === 'bunkhouse') out.push(`${d.beds[lv - 1]} beds · camp total ${bedCount()} for ${S.survivors.length} survivors`)
    if (st.type === 'storage') out.push(`+${d.cap[lv - 1]} storage for every resource · camp cap ${storageCap()}`)
    if (st.type === 'kitchen') out.push(`The camp eats ${pct(kitchenSaving())} less food`)
    if (st.type === 'infirmary') out.push(`${d.beds[lv - 1]} beds · heals ${d.heal[lv - 1]}× · makes meds when there are no patients`)
    if (st.type === 'training') out.push(`${d.xpRate[lv - 1]} XP per second for each trainee`)
    if (st.type === 'radio') {
      const next = S.recruit.pending ? 'Someone is waiting at the gate' : `Next contact in about ${gameDur(Math.max(0, S.recruit.next - S.time))}`
      out.push(`Newcomers arrive ${Math.round((1 / d.recruit[lv - 1] - 1) * 100)}% more often and bring better skills. ${next}`)
    }
    if (st.type === 'generator') {
      const burn = d.burn[lv - 1]
      out.push(`${d.power[lv - 1]} power (more with an operator) · burns 1 fuel every ${burn}s at full load`)
      out.push(`Camp power: ${pinfo.used.toFixed(1)} used of ${pinfo.supply} · demand ${pinfo.demand}`)
      if (S.res.fuel < 1) out.push('Out of fuel: build a Biofuel Still or loot gas stations')
    }
    if (st.type === 'watchtower') out.push(`Guards deal +${pct(d.towerDmg[lv - 1])} damage in horde attacks and reveal the size of incoming hordes`)
    if (st.type === 'turret') out.push(`${d.dmg[lv - 1]} damage · ${d.range[lv - 1]} m range · ${pinfo.powered.has(st.id) ? 'powered' : 'NO POWER'} · uses camp ammo`)
    if (st.type === 'floodlight') out.push(pinfo.powered.has(st.id) ? 'Powered · defenders within 12 m shoot at full accuracy at night' : 'No power')
    if (st.type === 'campfire') out.push('Survivors gather here when they have no job. At night everyone sits around the fire.')
    return out
  }
  upgradeBenefits(st, next) {
    const d = STATIONS[st.type]
    const b = []
    if (d.workers[next - 1] > d.workers[next - 2]) b.push(`${d.workers[next - 1]} job slots`)
    if (d.recipe?.time && Array.isArray(d.recipe.time)) b.push(`${Math.round((d.recipe.time[next - 2] / d.recipe.time[next - 1] - 1) * 100)}% faster`)
    if (d.auto === next) b.push(`Automation: runs on ${d.autoPower} generator power`)
    if (d.beds) b.push(`${d.beds[next - 1]} beds`)
    if (d.cap) b.push(`+${d.cap[next - 1]} storage`)
    if (d.passive) b.push(`${d.passive.water[next - 1]} water per day`)
    if (d.queue) b.push(`Queue of ${d.queue[next - 1]}, unlocks level ${next} recipes`)
    if (d.power && st.type === 'generator') b.push(`${d.power[next - 1]} power`)
    if (d.towerDmg) b.push(`+${pct(d.towerDmg[next - 1])} guard damage`)
    if (d.saving) b.push(`Up to ${pct(d.saving[next - 1])} less food eaten`)
    if (d.heal) b.push(`Heals ${d.heal[next - 1]}×`)
    if (d.xpRate) b.push(`${d.xpRate[next - 1]} XP/s`)
    if (d.recruit) b.push(`Recruits ${Math.round((1 / d.recruit[next - 1] - 1) * 100)}% more often`)
    if (st.type === 'turret') b.push(`${d.dmg[next - 1]} damage, ${d.range[next - 1]} m range`)
    return b
  }
  craftSection(st) {
    const d = STATIONS[st.type]
    const wrap = h('div')
    const qmax = d.queue[st.level - 1]
    const q = h('div.queue')
    const bars = []
    st.queue.forEach((it, i) => {
      const r = RECIPES.find((x) => x.id === it.id)
      const bar = h('i')
      bars.push({ bar, it })
      q.append(h('div.qitem', h('b', r.item ? ITEMS[r.item].name : `${Object.values(r.out)[0]} ${RES[Object.keys(r.out)[0]].name}`), h('div.bar', bar), h('button.mini', { onclick: () => cancelCraft(st, i) }, 'Cancel')))
    })
    for (let i = st.queue.length; i < qmax; i++) q.append(h('div.qitem.empty', 'Empty slot'))
    this.craftLive = () => {
      for (const { bar, it } of bars) bar.style.width = pct(1 - it.left / (it.total || 60))
    }
    this.craftLive()
    wrap.append(h('h3', 'Crafting queue', h('small', `${st.queue.length}/${qmax}`)), q)
    const list = h('div.recipes')
    for (const r of recipesFor(st.type)) {
      const locked = r.lvl > st.level
      const name = r.item ? ITEMS[r.item].name : `${Object.values(r.out)[0]}× ${RES[Object.keys(r.out)[0]].name}`
      list.append(
        h(
          'div.recipe' + (locked ? '.locked' : ''),
          h('div.rinfo', h('b', { style: r.item ? { color: RARITY[ITEMS[r.item].rarity].color } : null }, name), h('small', r.item ? itemStatLine(r.item) : 'Crafting material'), costChips(r.in)),
          h(
            'button.btn.small',
            {
              disabled: locked || !canAfford(r.in) || st.queue.length >= qmax,
              onclick: () => {
                const err = queueCraft(st, r)
                if (err) {
                  this.toast(err, 'bad')
                  sfx('error')
                } else sfx('build')
              },
            },
            locked ? `Level ${r.lvl}` : `Craft · ${r.time}s`,
          ),
        ),
      )
    }
    wrap.append(h('h3', 'Recipes'), list)
    return wrap
  }
  pickWorker(st) {
    const d = STATIONS[st.type]
    const cands = S.survivors.filter((s) => s.status !== 'mission')
    cands.sort((a, b) => workEff(b, st.type) - workEff(a, st.type))
    this.picker(
      `Assign to ${d.name}`,
      cands.map((s) => {
        const job = S.stations.find((x) => x.id === s.job)
        return {
          img: this.game.portrait(s),
          label: s.name,
          sub: `${OCCUPATIONS[s.occ].name}${d.skill ? ` · ${SKILLS[d.skill].name} ${s.skills[d.skill]}` : ''} · ${job ? `now: ${STATIONS[job.type].name}` : s.status === 'injured' ? 'injured' : 'idle'}`,
          right: pct(workEff({ ...s, status: 'ok' }, st.type)),
          good: OCCUPATIONS[s.occ].stations?.includes(st.type),
          disabled: job?.id === st.id,
          onPick: () => {
            assign(s, st)
            sfx('select')
          },
        }
      }),
    )
  }

  // ---------------------------------------------------------------- survivors
  openSurvivor(id) {
    this.openPanel('surv:' + id, () => this.renderSurvivor(id))
  }
  personRow(s, onclick = null) {
    const st = S.stations.find((x) => x.id === s.job)
    const statusTxt = s.status === 'mission' ? 'On a run' : s.status === 'injured' ? 'Injured' : st ? STATIONS[st.type].name : 'Idle'
    const top = SKILL_KEYS.slice().sort((a, b) => s.skills[b] - s.skills[a]).slice(0, 2)
    const ms = survivorStats(s).maxHp
    return h(
      'button.prow' + (s.status === 'injured' ? '.hurt' : s.status === 'mission' ? '.away' : ''),
      { onclick: onclick || (() => this.openSurvivor(s.id)) },
      h('img', { src: this.game.portrait(s), alt: '' }),
      h('div.pinfo', h('b', s.name), h('small', `${OCCUPATIONS[s.occ].name} · Lv ${survivorLevel(s)}`), h('div.hpbar', h('i', { style: { width: pct(clamp(s.hp / ms, 0, 1)) } }))),
      h('div.pside', h('span.pstat', statusTxt), h('small', top.map((k) => `${SKILLS[k].short} ${s.skills[k]}`).join(' · '))),
    )
  }
  renderSurvivors() {
    const frag = h('div.pbody')
    const idle = S.survivors.filter((s) => s.status === 'ok' && !s.job).length
    frag.append(this.head('Survivors', `${S.survivors.length} in camp · ${bedCount()} beds · ${idle} without a job`))
    const sorted = S.survivors.slice().sort((a, b) => (a.status === 'mission') - (b.status === 'mission') || a.name.localeCompare(b.name))
    frag.append(h('div.plist', sorted.map((s) => this.personRow(s))))
    if (idle) frag.append(h('p.note', 'Idle survivors help build and upgrade stations. Everyone else keeps their station running.'))
    if (S.stats.memorial.length) {
      frag.append(h('h3', 'Memorial', h('small', `${S.stats.memorial.length} lost`)))
      frag.append(h('div.memorial', S.stats.memorial.map((m) => h('div.mem', h('span', { html: icon('skull') }), h('div', h('b', m.name), h('small', `${m.occ} · Day ${m.day} · ${m.cause}`))))))
    }
    return frag
  }
  renderSurvivor(id) {
    const s = getS(id)
    if (!s) return null
    const st = survivorStats(s)
    const occ = OCCUPATIONS[s.occ]
    const frag = h('div.pbody')
    const statusTxt = s.status === 'mission' ? 'On a supply run' : s.status === 'injured' ? 'Injured' : 'In camp'
    frag.append(this.head(s.name, `${occ.name} · age ${s.age} · joined day ${s.joined}`))
    const hpBar = h('i', { style: { width: pct(clamp(s.hp / st.maxHp, 0, 1)) } })
    frag.append(
      h(
        'div.shero',
        h('img.big', { src: this.game.portrait(s), alt: '' }),
        h('div', h('div.row', h('span.lvl', `Level ${survivorLevel(s)}`), h('span.pstat' + (s.status === 'injured' ? '.bad' : ''), statusTxt)), h('div.hpbar.big', hpBar), h('small', `${Math.ceil(s.hp)}/${st.maxHp} health · ${s.kills} kills · ${s.runs} runs`), h('p.perk', h('b', 'Perk: '), occ.perk)),
      ),
    )
    this.panelLive = () => (hpBar.style.width = pct(clamp(s.hp / survivorStats(s).maxHp, 0, 1)))
    frag.append(h('div.traits', s.traits.map((t) => h('span.trait' + (TRAITS[t].good ? '.good' : '.bad'), { title: TRAITS[t].desc }, TRAITS[t].name, h('small', TRAITS[t].desc)))))
    // skills
    const sk = h('div.skills')
    for (const k of SKILL_KEYS) {
      const lv = s.skills[k]
      const need = xpForLevel(lv)
      sk.append(h('div.skill', { title: SKILLS[k].desc }, h('span', SKILLS[k].name), h('div.pips', Array.from({ length: SKILL_MAX }, (_, i) => h('i' + (i < lv ? '.on' : '')))), h('b', lv), h('div.xp', h('i', { style: { width: pct(lv >= SKILL_MAX ? 1 : s.xp[k] / need) } }))))
    }
    frag.append(h('h3', 'Skills', h('small', 'Improve with use')), sk)
    // equipment
    const eq = h('div.equip')
    for (const slot of ['weapon', 'armor', 'gear']) {
      const iid = equipped(s, slot)
      eq.append(
        h(
          'button.eslot',
          { onclick: () => this.pickItem(s, slot), disabled: s.status === 'mission' },
          h('small', slot[0].toUpperCase() + slot.slice(1)),
          iid ? h('b', { style: { color: RARITY[ITEMS[iid].rarity].color } }, ITEMS[iid].name) : h('b.dim', slot === 'weapon' ? 'Fists' : 'None'),
          h('span', iid ? itemStatLine(iid) : slot === 'weapon' ? itemStatLine('fists') : 'Tap to equip'),
        ),
      )
    }
    frag.append(h('h3', 'Equipment'), eq)
    const stats = [
      ['Damage', `${st.dmg.toFixed(0)} per hit`],
      ['Attack', `${(st.dmg / st.rate).toFixed(0)} dps · ${st.range.toFixed(1)} m`],
      st.gun ? ['Accuracy', pct(st.acc)] : ['Style', 'Melee'],
      ['Armor', pct(st.dr)],
      ['Speed', `${st.speed.toFixed(1)} m/s`],
      ['Search', `${pct(st.search)} speed`],
      ['Loot', `+${pct(st.loot - 1)}`],
      ['Dismantle', `${pct(st.dismantle)} speed`],
    ]
    frag.append(h('div.statgrid', stats.map(([a, b]) => h('div', h('small', a), h('b', b)))))
    // job
    if (s.status !== 'mission') {
      const job = S.stations.find((x) => x.id === s.job)
      const built = S.stations.filter((x) => x.level > 0 && slots(x) > 0)
      const ranked = [...new Set(built.map((x) => x.type))].map((t) => ({ t, e: workEff({ ...s, status: 'ok' }, t) })).sort((a, b) => b.e - a.e).slice(0, 3)
      frag.append(
        h('h3', 'Job'),
        h('div.box', h('div.row', h('b', job ? STATIONS[job.type].name : 'No job'), h('button.btn.small', { onclick: () => this.pickJob(s) }, job ? 'Change job' : 'Give a job')), ranked.length ? h('p.note', `Best at: ${ranked.map((r) => `${STATIONS[r.t].name} ${pct(r.e)}`).join(' · ')}`) : null),
      )
    }
    // actions
    const acts = h('div.acts')
    if (s.hp < st.maxHp && s.status !== 'mission') {
      acts.append(
        h('button.btn.small', {
          disabled: S.res.meds < 1,
          onclick: () => {
            if (!pay({ meds: 1 })) return
            s.hp = Math.min(st.maxHp, s.hp + 45)
            if (s.status === 'injured' && s.hp >= st.maxHp * 0.7) s.status = 'ok'
            sfx('loot')
            bus.emit('change')
          },
        }, 'Treat with 1 meds (+45 health)'),
      )
    }
    if (S.survivors.length > 1 && s.status !== 'mission') {
      acts.append(h('button.btn.ghost.small', { onclick: () => this.confirm(`Exile ${s.first}?`, 'They leave the camp for good, taking nothing with them.', 'Exile', () => this.exile(s)) }, 'Exile from camp'))
    }
    frag.append(acts)
    return frag
  }
  exile(s) {
    for (const k of Object.keys(s.equip)) s.equip[k] = null
    S.survivors = S.survivors.filter((x) => x !== s)
    log(`${s.name} was exiled from the camp.`, 'bad')
    this.closePanel()
    bus.emit('change')
  }
  pickJob(s) {
    const built = S.stations.filter((x) => x.level > 0 && slots(x) > 0)
    const opts = [{ label: 'No job', sub: 'Helps with construction and rests by the fire', onPick: () => assign(s, null) }]
    built
      .map((st) => ({ st, e: workEff({ ...s, status: 'ok' }, st.type) }))
      .sort((a, b) => b.e - a.e)
      .forEach(({ st, e }) => {
        const n = workersOf(st).length
        const full = n >= slots(st)
        opts.push({
          label: STATIONS[st.type].name + (st.level > 1 ? ` · L${st.level}` : ''),
          sub: `${n}/${slots(st)} workers${full ? ' · full' : ''}`,
          right: pct(e),
          good: OCCUPATIONS[s.occ].stations?.includes(st.type),
          disabled: (full && s.job !== st.id) || s.job === st.id,
          onPick: () => {
            assign(s, st)
            sfx('select')
          },
        })
      })
    this.picker(`Job for ${s.first}`, opts)
  }
  pickItem(s, slot) {
    const items = S.items.filter((i) => ITEMS[i.id].slot === slot)
    items.sort((a, b) => ITEMS[b.id].value - ITEMS[a.id].value)
    const opts = []
    if (s.equip[slot]) opts.push({ label: 'Unequip', sub: 'Put it back in the armory', onPick: () => unequip(s, slot) })
    for (const it of items) {
      const o = ownerOf(it.uid)
      opts.push({
        label: ITEMS[it.id].name,
        labelColor: RARITY[ITEMS[it.id].rarity].color,
        sub: `${itemStatLine(it.id)}${o ? ` · used by ${o.first}` : ''}`,
        disabled: o === s || (o && o.status === 'mission'),
        onPick: () => {
          equip(s, it.uid)
          sfx('select')
        },
      })
    }
    if (!items.length) opts.push({ label: `No ${slot} in the armory`, sub: 'Loot, craft or buy one', disabled: true })
    this.picker(`${slot[0].toUpperCase() + slot.slice(1)} for ${s.first}`, opts)
  }
  picker(title, opts) {
    const list = h('div.plist')
    let close
    for (const o of opts) {
      list.append(
        h(
          'button.prow.pick' + (o.good ? '.good' : ''),
          {
            disabled: !!o.disabled,
            onclick: () => {
              close()
              o.onPick?.()
            },
          },
          o.img ? h('img', { src: o.img, alt: '' }) : null,
          h('div.pinfo', h('b', { style: o.labelColor ? { color: o.labelColor } : null }, o.label), o.sub ? h('small', o.sub) : null),
          o.right ? h('span.eff', o.right) : null,
        ),
      )
    }
    close = this.modal(h('div', h('h2', title), list), { small: true })
  }

  // ---------------------------------------------------------------- build
  renderBuild() {
    const frag = h('div.pbody')
    frag.append(this.head('Build', 'Pick a station, then tap the ground inside the fence to place it.'))
    const tabs = h('div.tabs')
    const body = h('div')
    const show = (cat) => {
      this.buildTab = cat
      for (const b of tabs.children) b.classList.toggle('on', b.dataset.cat === cat)
      body.innerHTML = ''
      if (cat === 'defense') body.append(this.fenceCard())
      for (const [type, d] of Object.entries(STATIONS)) {
        if (d.cat !== cat || d.fixed) continue
        const cost = d.cost[0]
        const n = countType(type, 0)
        const req = reqMet(type)
        const reqText = d.req ? Object.entries(d.req).map(([t, l]) => `${STATIONS[t].name} L${l}`).join(', ') : ''
        const best = bestFor(type)
        body.append(
          h(
            'button.bcard' + (req ? '' : '.locked'),
            {
              disabled: !req,
              onclick: () => {
                if (!canAfford(cost)) {
                  this.toast('Not enough resources', 'bad')
                  sfx('error')
                  return
                }
                this.closePanel()
                this.game.base.startPlacing(type)
              },
            },
            h('div.bc-top', h('b', d.name), h('small', `${d.size[0]}×${d.size[1]}${n ? ` · built ${n}` : ''}`)),
            h('p', d.desc),
            h('div.bc-foot', costChips(cost), !req ? h('small.req', `Needs ${reqText}`) : best.length ? h('small', `Best staff: ${best.join(', ')}`) : null),
          ),
        )
      }
    }
    for (const c of STATION_CATS) {
      const b = h('button', { onclick: () => show(c.id) }, c.name)
      b.dataset.cat = c.id
      tabs.append(b)
    }
    frag.append(tabs, body)
    show(this.buildTab || 'production')
    return frag
  }
  fenceCard() {
    const lv = S.fence.level
    const F = FENCE[lv]
    const next = FENCE[lv + 1]
    const hpAvg = S.fence.hp.reduce((a, b) => a + b, 0) / S.fence.hp.length
    const broken = S.fence.hp.filter((x) => x <= 0).length
    const rc = repairCost()
    const box = h(
      'div.box.fence',
      h('div.row', h('b', `Perimeter: ${F.name}`), h('small', `${Math.round((hpAvg / F.hp) * 100)}% intact${broken ? ` · ${broken} breaches` : ''}`)),
      h('div.bar.big', h('i', { style: { width: pct(hpAvg / F.hp) } })),
      rc.missing > 0 ? h('div.row', costChips(rc.cost), h('button.btn.small', { disabled: !canAfford(rc.cost), onclick: () => (repairFence() ? sfx('build') : sfx('error')) }, 'Repair all')) : null,
      S.fence.building ? h('p.note', 'Upgrade under way…') : next ? h('div.row', h('div', h('small', `Upgrade to ${next.name} · ${next.hp} strength per section`), costChips(next.cost)), h('button.btn.small.go', { disabled: !canAfford(next.cost), onclick: () => (upgradeFence() ? sfx('build') : sfx('error')) }, 'Upgrade')) : h('p.note', 'Strongest wall available.'),
    )
    return box
  }
  openFence() {
    this.openPanel('fence', () => {
      const f = h('div.pbody')
      f.append(this.head('Perimeter', 'The wall hordes have to chew through before reaching anyone.'))
      f.append(this.fenceCard())
      f.append(h('p.note', 'Hordes pick a side and batter the nearest sections. Broken sections let zombies straight in. Watchtowers, turrets and floodlights help hold the line.'))
      return f
    })
  }
  showPlaceBar(name) {
    this.placeBar.hidden = false
    this.placeBar.innerHTML = ''
    this.placeOkEl = h('span.pstate')
    this.placeBar.append(
      h('div', h('small', 'Placing'), h('b', name), this.placeOkEl),
      h('button.btn.ghost.small', { onclick: () => this.game.base.rotatePlacing(), html: icon('rotate') + '<span>Rotate</span>' }),
      h('button.btn.ghost.small', { onclick: () => this.game.base.cancelPlacing() }, 'Cancel'),
      h('button.btn.go.small', { onclick: () => this.game.base.confirmPlacing() }, 'Place'),
    )
  }
  placeOk(ok) {
    if (this.placeOkEl) {
      this.placeOkEl.textContent = ok ? 'Good spot' : 'Blocked here'
      this.placeOkEl.className = 'pstate ' + (ok ? 'ok' : 'bad')
    }
  }
  hidePlaceBar() {
    this.placeBar.hidden = true
  }

  // ---------------------------------------------------------------- armory
  renderArmory() {
    const frag = h('div.pbody')
    frag.append(this.head('Armory', `${S.items.length} items · craft more at the Workbench, Weapons and Clothing stations`))
    const tabs = h('div.tabs')
    const body = h('div.items')
    const show = (slot) => {
      this.armTab = slot
      for (const b of tabs.children) b.classList.toggle('on', b.dataset.slot === slot)
      body.innerHTML = ''
      const items = S.items.filter((i) => slot === 'all' || ITEMS[i.id].slot === slot).sort((a, b) => ITEMS[b.id].value - ITEMS[a.id].value)
      if (!items.length) body.append(h('p.note.center', 'Nothing here yet.'))
      for (const it of items) {
        const o = ownerOf(it.uid)
        const I = ITEMS[it.id]
        body.append(
          h(
            'div.item',
            h('div.iinfo', h('b', { style: { color: RARITY[I.rarity].color } }, I.name), rarityTag(it.id), h('small', itemStatLine(it.id)), h('small.owner', o ? `Used by ${o.first}` : 'In storage')),
            h(
              'div.iacts',
              h('button.btn.small', { disabled: o?.status === 'mission', onclick: () => this.giveItem(it) }, o ? 'Reassign' : 'Equip'),
              h(
                'button.btn.ghost.small',
                {
                  disabled: !!o,
                  onclick: () => {
                    removeItem(it.uid)
                    gain({ cash: itemSellPrice(it.id) })
                    sfx('coin')
                    bus.emit('change')
                  },
                },
                `Sell $${itemSellPrice(it.id)}`,
              ),
            ),
          ),
        )
      }
    }
    for (const [k, l] of [['all', 'All'], ['weapon', 'Weapons'], ['armor', 'Armor'], ['gear', 'Gear']]) {
      const b = h('button', { onclick: () => show(k) }, l)
      b.dataset.slot = k
      tabs.append(b)
    }
    frag.append(tabs, body)
    show(this.armTab || 'all')
    return frag
  }
  giveItem(it) {
    const I = ITEMS[it.id]
    const cands = S.survivors.filter((s) => s.status !== 'mission')
    this.picker(
      `Give ${I.name} to…`,
      cands.map((s) => {
        const cur = equipped(s, I.slot)
        return { img: this.game.portrait(s), label: s.name, sub: `${OCCUPATIONS[s.occ].name} · has ${cur ? ITEMS[cur].name : 'nothing'} · ${I.slot === 'weapon' ? (I.kind === 'gun' ? `Ranged ${s.skills.ranged}` : `Melee ${s.skills.melee}`) : ''}`, onPick: () => (equip(s, it.uid), sfx('select')) }
      }),
    )
  }

  // ---------------------------------------------------------------- market
  renderMarket() {
    const frag = h('div.pbody')
    frag.append(this.head('Black Market', `A trader on the radio. Stock changes every dawn. Cash: $${fmt(S.res.cash)}`))
    const tabs = h('div.tabs')
    const body = h('div')
    const show = (tab) => {
      this.mTab = tab
      for (const b of tabs.children) b.classList.toggle('on', b.dataset.t === tab)
      body.innerHTML = ''
      if (tab === 'buy') {
        const grid = h('div.mgrid')
        S.market.stock.forEach((e, i) => {
          const sold = e.sold
          const name = e.kind === 'item' ? ITEMS[e.id].name : `${e.qty} ${RES[e.res].name}`
          grid.append(
            h(
              'div.mcard' + (sold ? '.sold' : ''),
              e.kind === 'item' ? rarityTag(e.id) : h('span.chip', { style: { '--c': RES[e.res].color }, html: icon(e.res) }),
              h('b', { style: e.kind === 'item' ? { color: RARITY[ITEMS[e.id].rarity].color } : null }, name),
              h('small', e.kind === 'item' ? itemStatLine(e.id) : 'Bundle'),
              h(
                'button.btn.small' + (sold ? '.ghost' : '.go'),
                {
                  disabled: sold || S.res.cash < e.price,
                  onclick: () => {
                    if (!pay({ cash: e.price })) return
                    if (e.kind === 'item') addItem(e.id)
                    else gain({ [e.res]: e.qty })
                    e.sold = true
                    sfx('coin')
                    this.toast(`Bought ${name}`, 'good')
                    bus.emit('change')
                  },
                },
                sold ? 'Sold' : `$${e.price}`,
              ),
            ),
          )
        })
        body.append(grid)
      } else {
        const rows = h('div.sellrows')
        for (const k of STOCK_KEYS) {
          const have = Math.floor(S.res[k])
          const mk = (q) =>
            h('button.btn.small.ghost', {
              disabled: have < q,
              onclick: () => {
                S.res[k] -= q
                gain({ cash: resSellPrice(k, q) })
                sfx('coin')
                bus.emit('change')
              },
            }, `Sell ${q} · $${resSellPrice(k, q)}`)
          rows.append(h('div.sellrow', h('span.chip', { style: { '--c': RES[k].color }, html: icon(k) }, h('b', fmt(have))), h('span', RES[k].name), mk(10), mk(50)))
        }
        body.append(rows, h('p.note', 'Spare weapons and gear sell from the Armory. A Store Clerk in camp gets better prices.'))
      }
    }
    for (const [k, l] of [['buy', 'Buy'], ['sell', 'Sell']]) {
      const b = h('button', { onclick: () => show(k) }, l)
      b.dataset.t = k
      tabs.append(b)
    }
    frag.append(tabs, body)
    show(this.mTab || 'buy')
    return frag
  }

  // ---------------------------------------------------------------- goals
  renderGoals() {
    const frag = h('div.pbody')
    const done = GOALS.filter((g) => S.goals[g.id] === 'claimed').length
    frag.append(this.head('Goals', `${done}/${GOALS.length} complete · Day ${day()} · ${S.stats.kills} zombies killed`))
    const list = h('div.goals')
    for (const g of GOALS) {
      const st = S.goals[g.id]
      list.append(
        h(
          'div.goal' + (st === 'claimed' ? '.claimed' : st === 'done' ? '.done' : ''),
          h('span.gi', { html: st === 'claimed' ? icon('check') : '' }),
          h('div', h('b', g.text), costChips(g.reward)),
          st === 'done' ? h('button.btn.go.small', { onclick: () => (claimGoal(g.id), sfx('coin'), this.renderTracker()) }, 'Claim') : null,
        ),
      )
    }
    frag.append(list)
    const stats = S.stats
    frag.append(h('div.statgrid', [['Days survived', day() - 1], ['Supply runs', stats.runs], ['Hordes repelled', stats.raids], ['Recruited', stats.recruited], ['Zombies killed', stats.kills], ['Lost', stats.deaths]].map(([a, b]) => h('div', h('small', a), h('b', b)))))
    return frag
  }

  // ---------------------------------------------------------------- horde info
  openHordeInfo() {
    const intel = raidIntel()
    if (!intel || S.raid) return
    const def = S.survivors.filter((s) => s.status === 'ok')
    const guns = def.filter((s) => survivorStats(s).gun).length
    const towers = countType('watchtower')
    const turrets = countType('turret')
    this.modal(
      h(
        'div',
        h('h2', intel.name),
        h('p', `Arrives in ${gameDur(intel.in)} (day ${Math.floor(S.nextRaid.at / DAY_MIN) + 1}, ${clockStr(S.nextRaid.at)}).${intel.known ? ` About ${intel.count} zombies.` : ' Build a Watchtower to see how big it is.'}`),
        h('div.statgrid', [['Defenders in camp', def.length], ['With guns', guns], ['Ammo', Math.floor(S.res.ammo)], ['Watchtowers', towers], ['Turrets', turrets], ['Wall', `${FENCE[S.fence.level].name}`]].map(([a, b]) => h('div', h('small', a), h('b', b)))),
        h('p.note', 'Survivors away on a run can\'t defend the camp. If a horde hits while you are out, the camp fights it without you.'),
      ),
      { small: true },
    )
  }

  // ---------------------------------------------------------------- raid HUD
  raidHud(on) {
    if (on && this.recruitOpen) this.closeRecruit?.()
    this.raidBar.hidden = !on
    this.nav.classList.toggle('dim', on)
    if (on) {
      this.closePanel()
      this.updateRaidBar(true)
    }
  }
  updateRaidBar(rebuild) {
    const R = S.raid
    const base = this.game.base
    if (!R) return
    if (rebuild || !this.raidCards || this.raidCards.size !== base.squad.length) {
      this.raidBar.innerHTML = ''
      this.raidTitle = h('div.rtitle')
      this.raidCards = new Map()
      const cards = h('div.squad')
      base.squad.forEach((a, i) => {
        const bar = h('i')
        const c = h('button.scard', { onclick: () => base.selectDefender(a) }, h('span.key', String(i + 1)), h('img', { src: this.game.portrait(a.data), alt: '' }), h('div.sc-info', h('b', a.data.first), h('small', a.tower ? 'Watchtower' : survivorStats(a.data).weapon.name), h('div.hpbar', bar)))
        this.raidCards.set(a, { c, bar })
        cards.append(c)
      })
      this.raidBar.append(this.raidTitle, cards, h('p.rhelp', 'Defenders fight on their own. Tap one, then tap the ground to reposition or a zombie to focus it.'))
    }
    const broken = S.fence.hp.filter((x) => x <= 0).length
    this.raidTitle.innerHTML = `<b>HORDE ATTACK</b><span>${R.killed}/${R.count} killed</span><span>${broken ? `${broken} breaches` : 'Wall holding'}</span><span>Ammo ${Math.floor(S.res.ammo)}</span>`
    for (const [a, { c, bar }] of this.raidCards) {
      bar.style.width = pct(clamp(a.hp / a.maxHp, 0, 1))
      c.classList.toggle('down', a.downed)
      c.classList.toggle('sel', a.selected)
    }
  }
  raidReport(r, whileAway = false) {
    const body = h(
      'div',
      whileAway ? h('small.eyebrow', 'While the squad was out') : null,
      h('h2', r.won ? 'Horde repelled' : 'The camp was overrun'),
      h('p', r.won ? `${r.killed} of ${r.count} zombies put down.` : `The horde broke through. ${Object.entries(r.lost).filter(([, v]) => v > 0).map(([k, v]) => `${v} ${RES[k].name.toLowerCase()}`).join(', ')} lost.`),
      r.injured.length ? h('p.bad', `Injured: ${r.injured.join(', ')}`) : null,
      r.dead.length ? h('p.bad', `Killed: ${r.dead.join(', ')}`) : null,
      h('p.note', 'Check the perimeter and repair any breaches before the next horde.'),
    )
    this.modal(body, { small: true, actions: [{ label: 'Check the wall', fn: () => this.openFence() }, { label: 'Continue', go: true }] })
  }

  // ---------------------------------------------------------------- recruits
  showRecruit() {
    const p = S.recruit.pending
    if (!p || this.recruitOpen) return
    const s = p.s
    const occ = OCCUPATIONS[s.occ]
    const full = S.survivors.length >= bedCount()
    const sk = h('div.skills.compact')
    for (const k of SKILL_KEYS) sk.append(h('div.skill', h('span', SKILLS[k].name), h('div.pips', Array.from({ length: SKILL_MAX }, (_, i) => h('i' + (i < s.skills[k] ? '.on' : '')))), h('b', s.skills[k])))
    this.recruitOpen = true
    const close = (this.closeRecruit = this.modal(
      h(
        'div.recruit',
        h('small.eyebrow', 'At the gate'),
        h('div.shero', h('img.big', { src: this.game.portrait(s), alt: '' }), h('div', h('h2', s.name), h('p.sub', `${occ.name} · age ${s.age}`), h('p.perk', h('b', 'Perk: '), occ.perk), h('div.traits', s.traits.map((t) => h('span.trait' + (TRAITS[t].good ? '.good' : '.bad'), TRAITS[t].name, h('small', TRAITS[t].desc)))))),
        sk,
        full ? h('p.bad', `No free bed (${S.survivors.length}/${bedCount()}). Build or upgrade a Bunkhouse to take them in.`) : h('p.note', `They will eat 2 food and drink 2.4 water a day. Beds: ${S.survivors.length}/${bedCount()}.`),
      ),
      {
        onClose: () => (this.recruitOpen = false),
        actions: [
          { label: 'Turn away', fn: () => declineRecruit() },
          { label: 'Later', fn: () => {} },
          { label: full ? 'No bed' : 'Take them in', go: true, disabled: full, fn: () => (acceptRecruit() ? (sfx('complete'), this.toast(`${s.first} joined the camp`, 'good')) : null) },
        ],
      },
    ))
  }

  // ---------------------------------------------------------------- mission report
  missionReport(r) {
    const res = Object.entries(r.haul.res).filter(([, v]) => v > 0)
    const lost = Object.entries(r.lost || {}).filter(([, v]) => v > 0)
    const body = h(
      'div.report',
      h('small.eyebrow', r.loc.name),
      h('h2', r.result === 'extracted' ? 'Back at camp' : 'The run failed'),
      r.result === 'extracted'
        ? h('div', res.length ? h('div.chips.big', res.map(([k, v]) => resChip(k, v))) : h('p.note', 'No resources this time.'), r.haul.items.length ? h('div.itemlist', r.haul.items.map((id) => h('span.hitem', { style: { '--c': RARITY[ITEMS[id].rarity].color } }, ITEMS[id].name))) : null, lost.length ? h('p.bad', `Storage full: ${lost.map(([k, v]) => `${Math.round(v)} ${RES[k].name.toLowerCase()}`).join(', ')} left behind. Build a Storage Depot.`) : null)
        : h('p', 'Everything the squad carried was lost.'),
      r.injured.length ? h('p.bad', `Injured: ${r.injured.join(', ')}. They need time or an Infirmary to recover.`) : null,
      r.dead.length ? h('p.bad', `Did not make it: ${[...new Set(r.dead)].join(', ')}.`) : null,
    )
    this.modal(body, { small: true, actions: [{ label: 'Continue', go: true }] })
  }

  // ---------------------------------------------------------------- menu
  openMenu() {
    const qual = h('div.seg')
    for (const q of ['low', 'medium', 'high']) {
      qual.append(
        h('button' + (S.settings.quality === q ? '.on' : ''), {
          onclick: () => {
            S.settings.quality = q
            setQuality(q)
            for (const b of qual.children) b.classList.toggle('on', b.textContent.toLowerCase() === q)
          },
        }, q[0].toUpperCase() + q.slice(1)),
      )
    }
    const snd = h('button.btn.small', { onclick: () => ((S.settings.sound = !soundOn()), setSound(S.settings.sound), (snd.textContent = S.settings.sound ? 'Sound on' : 'Sound off')) }, soundOn() ? 'Sound on' : 'Sound off')
    let close
    close = this.modal(
      h(
        'div.menu',
        h('h2', 'Camp menu'),
        h('div.row', h('span', 'Graphics'), qual),
        h('div.row', h('span', 'Sound'), snd),
        h('div.row', h('span', 'Progress'), h('button.btn.small', { onclick: () => (save() ? this.toast('Game saved', 'good') : this.toast('Could not save in this browser', 'bad')) }, 'Save now')),
        h('h3', 'Controls'),
        h('ul.controls', h('li', h('b', 'Drag'), ' to pan · ', h('b', 'scroll / pinch'), ' to zoom · ', h('b', 'right-drag / Q E / two fingers'), ' to rotate'), h('li', h('b', 'WASD'), ' or arrows move the camera'), h('li', 'On runs: tap a survivor, then tap ground, a container or a zombie. ', h('b', '1–4'), ' select, ', h('b', 'R'), ' selects all, ', h('b', 'Space'), ' pauses.'), h('li', 'Building: ', h('b', 'R'), ' rotates, ', h('b', 'Esc'), ' cancels.')),
        h('div.row.end', h('button.btn.ghost.small', { onclick: () => (close(), this.confirm('Start a new camp?', 'Your current camp will be abandoned for good.', 'Start over', () => this.game.newGame())) }, 'Abandon camp and start over')),
      ),
      { small: true },
    )
  }

  // ---------------------------------------------------------------- modals
  modal(content, { small = false, actions = null, onClose = null, locked = false } = {}) {
    const box = h('div.modal' + (small ? '.small' : ''))
    const wrap = h('div.mwrap', box)
    const close = () => {
      wrap.classList.add('out')
      setTimeout(() => wrap.remove(), 180)
      onClose?.()
    }
    if (!locked) {
      wrap.addEventListener('pointerdown', (e) => {
        if (e.target === wrap) close()
      })
      box.append(h('button.x', { onclick: close, 'aria-label': 'Close', html: icon('close') }))
    }
    box.append(content)
    if (actions) {
      box.append(
        h(
          'div.mactions',
          actions.map((a) =>
            h('button.btn' + (a.go ? '.go' : '.ghost'), {
              disabled: !!a.disabled,
              onclick: () => {
                close()
                a.fn?.()
              },
            }, a.label),
          ),
        ),
      )
    }
    this.modalRoot.append(wrap)
    return close
  }
  confirm(title, text, yes, fn) {
    this.modal(h('div', h('h2', title), h('p', text)), { small: true, actions: [{ label: 'Cancel' }, { label: yes, go: true, fn }] })
  }
  gameOver() {
    const st = S.stats
    this.modal(
      h(
        'div.report',
        h('small.eyebrow', 'The camp has fallen'),
        h('h2', `You held out for ${day() - 1} days`),
        h('div.statgrid', [['Zombies killed', st.kills], ['Supply runs', st.runs], ['Hordes repelled', st.raids], ['Survivors recruited', st.recruited]].map(([a, b]) => h('div', h('small', a), h('b', b)))),
        h('h3', 'Remembered'),
        h('div.memorial', st.memorial.slice(0, 12).map((m) => h('div.mem', h('span', { html: icon('skull') }), h('div', h('b', m.name), h('small', `${m.occ} · Day ${m.day} · ${m.cause}`))))),
      ),
      { small: true, locked: true, actions: [{ label: 'Start a new camp', go: true, fn: () => this.game.newGame() }] },
    )
  }
}
