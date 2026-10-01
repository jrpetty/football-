// Boot, loading screen, title, scene switching (camp, city map, supply runs),
// the simulation loop, hotkeys, settings and saving.
import { Pipeline } from './render/pipeline.js'
import { initView, view } from './render/view.js'
import { pregenerate } from './render/texgen.js'
import { initAudio, sfx, setSound } from './core/audio.js'
import { S, newGame, hasSave, load, save, day, log, wipeSave, buildCost, newStation, pay, canAfford, completeGoal } from './game/state.js'
import { econTick, initSchedules, autoResolveRaid, scheduleRaid } from './game/economy.js'
import * as belts from './game/belts.js'
import { STATIONS, GAME_MIN_PER_SEC } from './game/data.js'
import { BaseScene } from './scenes/base.js'
import { CityMap } from './scenes/citymap.js'
import { Mission } from './scenes/mission.js'
import { UI } from './ui/ui.js'
import { genCity } from './world/city.js'
import { portrait } from './ui/portrait.js'
import { bus, h } from './core/util.js'

const GRASS = { low: 0, medium: 18000, high: 40000, ultra: 75000 }

class Game {
  constructor() {
    this.pipe = null
    this.scene = null
    this.base = null
    this.map = null
    this.mission = null
    this.ui = null
    this.running = false
    this.saveT = 20
    this.last = performance.now()
  }
  grassCount() {
    return GRASS[S?.settings?.quality || 'high'] ?? 40000
  }
  portrait(s) {
    return portrait(s, this.pipe.renderer)
  }
  // ---------------------------------------------------------------- boot
  async boot() {
    const canvas = document.getElementById('c')
    this.pipe = new Pipeline(canvas)
    initView(canvas)
    this.resize()
    window.addEventListener('resize', () => this.resize())
    const hud = document.getElementById('hud')
    const fill = h('i')
    const note = h('span', 'Preparing textures…')
    const loading = h('div.loading', h('div.ld-card', h('div.logo.big', 'HOLDOUT'), h('div.ld-bar', fill), note, h('p.ld-tip', 'Tip: give every survivor a job that suits them. A farmer farms half again as fast.')))
    hud.append(loading)
    await new Promise((r) => setTimeout(r, 30))
    await pregenerate((f, name) => {
      fill.style.width = `${Math.round(f * 70)}%`
      note.textContent = `Preparing ${name}…`
    })
    note.textContent = 'Building the camp…'
    fill.style.width = '80%'
    await new Promise((r) => setTimeout(r, 30))
    const saved = hasSave()
    if (saved) {
      load(saved)
      this.catchUp()
    } else {
      newGame()
      initSchedules()
    }
    this.makeCity()
    this.applySettings(true)
    this.base = new BaseScene(this)
    this.scene = this.base
    this.base.enter()
    view.input.handler = null
    fill.style.width = '100%'
    await new Promise((r) => setTimeout(r, 60))
    loading.remove()
    this.title(saved)
    view.input.onKey = (e) => {
      if (this.onKey(e)) e._handled = true
    }
    requestAnimationFrame((t) => this.frame(t))
    const unlock = () => initAudio()
    window.addEventListener('pointerdown', unlock)
    window.addEventListener('keydown', unlock)
    window.addEventListener('beforeunload', () => this.running && save())
    document.addEventListener('visibilitychange', () => document.hidden && this.running && save())
  }
  // The city is the same for the whole camp: its locations seed radio events.
  makeCity() {
    this.city = genCity(S.seed)
    S.cityLocs = this.city.locs.map((l) => ({ id: l.id, type: l.type, level: l.level, name: l.name }))
  }
  // Let camp time pass in one go (travel to and from a run).
  fastForward(minutes) {
    let left = minutes / GAME_MIN_PER_SEC
    while (left > 0 && !S.over) {
      const step = Math.min(1, left)
      econTick(step)
      left -= step
    }
  }
  resize() {
    const w = window.innerWidth
    const hh = window.innerHeight
    this.pipe.resize(w, hh)
    view.camera.aspect = w / hh
    view.camera.updateProjectionMatrix()
  }
  applySettings(first = false) {
    const st = S.settings
    const q = st.quality || 'high'
    const tilt = st.tilt !== false
    const changedQ = q !== this.pipe.quality || tilt !== this.pipe.tiltShift
    this.pipe.tiltShift = tilt
    if (changedQ || first) this.pipe.setQuality(q)
    this.resize()
    view.input.edgePan = st.edgePan !== false
    setSound(st.sound !== false)
    if (this.base && !first) {
      this.base.atmo.setShadowSize(this.pipe.Q.shadow)
      this.base.world.grassCount = this.grassCount()
      this.base.world.rebuildGrass()
    }
  }
  // ---------------------------------------------------------------- title
  title(saved) {
    const el = h(
      'div.title',
      h(
        'div.tcard',
        h('div.logo.huge', 'HOLDOUT'),
        h('p.tag', 'The city fell weeks ago. A handful of you made it to an old lumber yard on the edge of town. Keep them alive.'),
        h(
          'ul.tfeat',
          h('li', h('b', 'Build the camp. '), 'Twenty-five stations, each with three levels: farms and filters, forges and chemistry labs, gunsmiths and tailors, generators and turrets.'),
          h('li', h('b', 'Run the benches. '), 'Queue orders, keep stock topped up, fit mods, repair worn gear, automate with modules and power.'),
          h('li', h('b', 'Scavenge the city. '), 'Pick a building on the map and a squad, then search it room by room. Stay together: loners get swarmed.'),
          h('li', h('b', 'Push the walls out. '), 'Clear the woods, the wrecking lot and the freight yard to grow. And hold the wall when the horde comes.'),
        ),
        h(
          'div.tbtns',
          saved ? h('button.btn.go.big', { onclick: () => this.start(false) }, `Continue · Day ${day()}`) : h('button.btn.go.big', { onclick: () => this.start(false) }, 'Start'),
          saved ? h('button.btn.big.ghost', { onclick: () => this.confirmNew() }, 'New camp') : null,
          h('button.btn.big.ghost', { onclick: () => this.ui?.openSettings() ?? this.quickSettings() }, 'Settings'),
        ),
        h('p.fine', 'Best on a PC with a mouse. Progress saves in this browser.'),
      ),
    )
    document.getElementById('hud').append(el)
    this.titleEl = el
    view.rig.jump(56, 58, 54)
  }
  quickSettings() {
    this.ui = new UI(this)
    this.ui.showCamp(false)
    this.ui.openSettings()
  }
  confirmNew() {
    if (confirm('Start a new camp? Your current camp will be lost.')) this.newGame()
  }
  start() {
    initAudio()
    sfx('click')
    this.titleEl?.remove()
    this.titleEl = null
    if (!this.ui) this.ui = new UI(this)
    this.ui.showCamp(true)
    view.input.handler = this.base
    this.running = true
    this.bindEvents()
    save()
    if (S.time < 9 * 60 && day() === 1) setTimeout(() => this.ui.toast('Click a station to see its workers, or press B to build.'), 1200)
  }
  newGame() {
    wipeSave()
    location.reload()
  }
  // Time passes while the game is closed (capped at an hour of real time).
  catchUp() {
    const away = Math.min(3600, (Date.now() - (S.saved || Date.now())) / 1000)
    if (away < 30) return
    const before = { ...S.res }
    for (let i = 0; i < Math.floor(away); i++) econTick(1, { offline: true })
    const diff = Object.entries(S.res).map(([k, v]) => [k, Math.round(v - before[k])]).filter(([, v]) => v !== 0)
    log(`While you were away (${Math.round(away / 60)} min): ${diff.slice(0, 8).map(([k, v]) => `${v > 0 ? '+' : ''}${v} ${k}`).join(', ') || 'nothing changed'}.`, 'story')
  }
  bindEvents() {
    if (this.bound) return
    this.bound = true
    bus.on('recruit', () => {
      sfx('radio')
      if (this.scene === this.base && !S.raid) this.ui.toast('Someone is waiting at the gate.', 'story')
    })
    bus.on('raidWarn', (intel) => {
      sfx('alarm')
      this.ui.toast(`${intel.name} within the hour! Get your defenders home.`, 'bad')
    })
    bus.on('raidStart', (R) => {
      if (this.scene === this.base && !this.map?.isOpen) {
        this.ui.closePanel()
        this.ui.closeModal()
        this.base.startRaid(R)
        S.speed = Math.max(1, S.speed || 1)
      } else {
        // the squad is out: the camp fights on its own
        autoResolveRaid(R)
        scheduleRaid()
      }
    })
    bus.on('raidResolved', (r) => {
      const rep = { ...r, won: r.ratio >= 0.55, killed: Math.round(r.count * Math.min(1, r.ratio)) }
      if (this.scene !== this.base) {
        this.mission?.toast?.(`A horde of ${r.count} hit the camp while you were out!`, 'bad')
        this.pendingRaidReport = rep
      } else this.ui.raidReport(rep)
    })
    bus.on('gameover', () => {
      this.running = false
      this.ui.gameOver()
    })
    bus.on('death', (s) => this.ui.toast(`${s.first} is dead.`, 'bad'))
  }
  // ---------------------------------------------------------------- input
  onKey(e) {
    if (this.titleEl) return false
    if (this.ui?.onKey(e)) return true
    const k = e.key
    if (this.scene === this.base && !S.raid && !this.base.placing) {
      if (k === ' ') {
        e.preventDefault()
        this.setSpeed(S.speed ? 0 : this.lastSpeed || 1)
        return true
      }
      if (k === '1' || k === '2' || k === '3') {
        this.setSpeed({ 1: 1, 2: 2, 3: 4 }[k])
        return true
      }
    }
    return false
  }
  setSpeed(v) {
    if (S.raid && v > 1) v = 1
    if (v) this.lastSpeed = v
    S.speed = v
    sfx('click')
    this.ui?.updateTop()
  }
  // ---------------------------------------------------------------- camp actions
  placeStation(type, x, z, rot) {
    const cost = buildCost(type)
    if (!canAfford(cost)) {
      this.ui.toast('Not enough resources', 'bad')
      sfx('error')
      return false
    }
    pay(cost)
    newStation(type, x, z, rot)
    sfx('build')
    this.ui.toast(`${STATIONS[type].name}: construction started`)
    return true
  }
  // ---------------------------------------------------------------- scenes
  openMap() {
    if (S.raid) return this.ui.toast('Not while the camp is under attack!', 'bad')
    if (this.mapLoading) return
    this.base.cancelPlacing()
    this.ui.closePanel()
    this.ui.toggleBuild(false)
    const go = () => {
      this.ui.showCamp(false)
      this.baseCam = view.rig.save()
      this.map.open()
      this.scene = this.map
      view.input.handler = this.map
    }
    if (this.map) return go()
    // first visit: build the city behind a short loading card
    this.mapLoading = true
    const card = h('div.loading.soft', h('div.ld-card', h('div.logo', 'ASHFORD'), h('span', 'Surveying the city…')))
    document.getElementById('hud').append(card)
    setTimeout(() => {
      try {
        this.map = new CityMap(this)
      } finally {
        card.remove()
        this.mapLoading = false
      }
      go()
    }, 60)
  }
  closeMap() {
    this.map.close()
    this.scene = this.base
    view.rig.restore(this.baseCam)
    this.base.enter()
    view.input.handler = this.base
    this.ui.showCamp(true)
  }
  startMission(loc, ids, loadout = {}) {
    this.map.close()
    this.ui.showCamp(false)
    this.fastForward(loadout.travel || 0)
    this.mission = new Mission(this, loc, ids, loadout)
    this.scene = this.mission
    view.input.handler = this.mission
    completeGoal('firstRun')
    save()
  }
  endMission(report) {
    const m = this.mission
    setTimeout(() => {
      m.dispose()
      this.mission = null
      this.fastForward(m.loadout?.travel || 0)
      this.scene = this.base
      if (this.baseCam) view.rig.restore(this.baseCam)
      this.base.enter()
      this.base.fence.openGate(8)
      view.input.handler = this.base
      this.ui.showCamp(true)
      this.ui.missionReport(report)
      if (this.pendingRaidReport) {
        this.ui.raidReport(this.pendingRaidReport)
        this.pendingRaidReport = null
      }
      bus.emit('change')
      save()
    }, report.result === 'extracted' ? 400 : 1600)
  }
  // ---------------------------------------------------------------- loop
  frame(t) {
    requestAnimationFrame((tt) => this.frame(tt))
    const dt = Math.max(0, Math.min(0.1, (t - this.last) / 1000))
    this.last = t
    const inBase = this.scene === this.base
    // On a run the camp clock slows so a run costs hours, not half a day.
    const speed = this.running ? (inBase ? (S.raid ? 1 : S.speed ?? 1) : this.mission ? 0.35 : S.speed ?? 1) : 1
    const paused = this.mission?.paused || (inBase && speed === 0)
    const simDt = dt * (speed || 0)
    if (this.running && !paused && simDt > 0) {
      let left = simDt
      while (left > 0) {
        const step = Math.min(0.25, left)
        econTick(step)
        left -= step
      }
      this.saveT -= dt
      if (this.saveT <= 0) {
        this.saveT = 20
        save()
      }
    }
    if (!this.titleEl) view.input.update(dt)
    else view.rig.yawGoal += dt * 0.04
    view.rig.update(dt)
    const scene = this.scene
    if (scene) {
      scene.update(dt, this.titleEl ? dt : simDt)
      if (scene.render) scene.render(dt)
      else this.pipe.render(scene.scene, view.camera, dt)
      view.labels.update(dt, view.camera, scene.scene)
    }
    this.ui?.update(dt)
  }
}

const game = new Game()
window.__holdout = game
window.__view = view
Object.defineProperty(window, '__S', { get: () => S })
window.__belts = belts
game.boot()
