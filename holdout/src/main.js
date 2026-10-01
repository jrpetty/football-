// Boot, title screen, scene switching and the main loop.
import { initGfx, gfx, setQuality } from './gfx.js'
import { initAudio, sfx, setSound } from './audio.js'
import { S, newGame, hasSave, load, save, econTick, autoResolveRaid, scheduleRaid, placeStation, canAfford, pay, day, log, setState, wipeSave } from './state.js'
import { STATIONS } from './data.js'
import { Base } from './base.js'
import { Mission } from './mission.js'
import { WorldMap } from './worldmap.js'
import { UI } from './ui.js'
import { portrait } from './portrait.js'
import { bus, h } from './util.js'

class Game {
  constructor() {
    this.scene = null
    this.base = null
    this.mission = null
    this.ui = null
    this.map = new WorldMap(this)
    this.saveT = 20
    this.last = performance.now()
    this.running = false
  }
  portrait(s) {
    return portrait(s)
  }
  boot() {
    initGfx(document.getElementById('c'))
    this.title()
    requestAnimationFrame((t) => this.frame(t))
    const unlock = () => initAudio()
    window.addEventListener('pointerdown', unlock)
    window.addEventListener('keydown', unlock)
    window.addEventListener('beforeunload', () => this.running && save())
    document.addEventListener('visibilitychange', () => document.hidden && this.running && save())
  }
  // ---------------------------------------------------------------- title
  title() {
    const saved = hasSave()
    const el = h(
      'div.title',
      h(
        'div.tcard',
        h('h1', 'HOLDOUT'),
        h('p.tag', 'The city fell eleven days ago. Four of you made it to an old lumber yard on the edge of town. Keep them alive.'),
        h(
          'ul.tfeat',
          h('li', h('b', 'Run the camp. '), 'Give every survivor a job. A farmer farms better, a gunsmith builds better guns, a doctor heals faster.'),
          h('li', h('b', 'Scavenge the city. '), 'Pick a building, pick a squad, then search it room by room. Isolated survivors get swarmed.'),
          h('li', h('b', 'Hold the wall. '), 'A horde is always on its way. The timer is at the top of the screen.'),
          h('li', h('b', 'Automate. '), 'Generators, sprinklers, sawmills and presses keep the camp running while your best fighters are out.'),
        ),
        h(
          'div.tbtns',
          saved ? h('button.btn.go.big', { onclick: () => this.start(saved) }, `Continue · Day ${Math.floor(saved.time / 1440) + 1}`) : null,
          h('button.btn.big' + (saved ? '.ghost' : '.go'), { onclick: () => this.start(null) }, saved ? 'New camp' : 'Start a new camp'),
        ),
        h('p.fine', 'Progress saves in this browser. Drag to pan, scroll or pinch to zoom, right-drag or two fingers to rotate.'),
      ),
    )
    document.getElementById('hud').append(el)
    this.titleEl = el
    // A quiet backdrop camp behind the title.
    newGame()
    this.base = new Base(this)
    this.scene = this.base
    this.base.enter()
    gfx.rig.jump(24, 24, 44)
  }
  start(data) {
    initAudio()
    sfx('click')
    this.titleEl?.remove()
    if (this.base) this.disposeBase()
    if (data) {
      load(data)
      this.catchUp()
    } else {
      wipeSave()
      newGame()
    }
    setQuality(S.settings.quality || 'high')
    setSound(S.settings.sound !== false)
    this.ui = new UI(this)
    this.base = new Base(this)
    this.scene = this.base
    this.base.enter()
    gfx.input.handler = this.base
    this.running = true
    this.bindEvents()
    save()
    if (!data) {
      setTimeout(() => {
        this.ui.toast('Tip: tap a station to see its workers, or the Build button to add more.', '')
      }, 1500)
    }
  }
  disposeBase() {
    this.base.dispose()
    this.base = null
  }
  // Time passes while the game is closed (capped at an hour of real time).
  catchUp() {
    const away = Math.min(3600, (Date.now() - (S.saved || Date.now())) / 1000)
    if (away < 30) return
    const before = { ...S.res }
    const steps = Math.floor(away)
    for (let i = 0; i < steps; i++) econTick(1, { offline: true })
    const diff = Object.entries(S.res).map(([k, v]) => [k, Math.round(v - before[k])]).filter(([, v]) => v !== 0)
    log(`While you were away (${Math.round(away / 60)} min): ${diff.map(([k, v]) => `${v > 0 ? '+' : ''}${v} ${k}`).join(', ') || 'nothing changed'}.`, 'story')
  }
  bindEvents() {
    if (this.bound) return
    this.bound = true
    bus.on('recruit', () => {
      sfx('radio')
      if (this.scene === this.base && !S.raid) this.ui.showRecruit()
    })
    bus.on('raidWarn', (intel) => {
      sfx('alarm')
      this.ui.toast(`${intel.name} in under an hour! Get your defenders home.`, 'bad')
    })
    bus.on('raidStart', (R) => {
      if (this.scene === this.base) {
        if (this.map.isOpen) this.closeMap()
        this.ui.closePanel()
        this.base.startRaid(R)
      } else {
        // The squad is out: the camp fights on its own.
        autoResolveRaid(R)
        scheduleRaid()
      }
    })
    bus.on('raidResolved', (r) => {
      const rep = { ...r, won: r.ratio >= 0.55, killed: Math.round(r.count * Math.min(1, r.ratio)) }
      if (this.scene !== this.base) {
        this.mission?.toast(`A horde of ${r.count} hit the camp while you were out!`, 'bad')
        this.pendingRaidReport = rep
      } else this.ui.raidReport(rep)
    })
    bus.on('gameover', () => {
      this.running = false
      this.ui.gameOver()
    })
    bus.on('death', (s) => this.ui.toast(`${s.first} is dead.`, 'bad'))
  }
  // ---------------------------------------------------------------- flow
  placeStation(type, x, z, rot) {
    const d = STATIONS[type]
    if (!canAfford(d.cost[0])) {
      this.ui.toast('Not enough resources', 'bad')
      sfx('error')
      return false
    }
    pay(d.cost[0])
    placeStation(type, x, z, rot)
    sfx('build')
    this.ui.toast(`${d.name} under construction`, '')
    return true
  }
  openMap() {
    if (S.raid) {
      this.ui.toast('Not while the camp is under attack!', 'bad')
      return
    }
    this.base.cancelPlacing()
    this.ui.closePanel()
    this.ui.hoverTip(null)
    document.body.style.cursor = ''
    this.ui.showCamp(false)
    this.map.open()
    gfx.input.handler = null
  }
  closeMap() {
    this.map.close()
    this.ui.showCamp(true)
    gfx.input.handler = this.base
  }
  startMission(loc, ids) {
    this.map.close()
    this.ui.showCamp(false)
    this.mission = new Mission(this, loc, ids)
    this.scene = this.mission
    gfx.input.handler = this.mission
    save()
  }
  endMission(report) {
    const m = this.mission
    setTimeout(() => {
      m.dispose()
      this.mission = null
      this.scene = this.base
      this.base.enter()
      gfx.rig.jump(24.5, 36, 30)
      gfx.input.handler = this.base
      this.ui.showCamp(true)
      this.ui.missionReport(report)
      if (this.pendingRaidReport) {
        this.ui.raidReport(this.pendingRaidReport, true)
        this.pendingRaidReport = null
      }
      bus.emit('change')
      save()
    }, report.result === 'extracted' ? 350 : 1600)
  }
  newGame() {
    wipeSave()
    location.reload()
  }
  // ---------------------------------------------------------------- loop
  frame(t) {
    requestAnimationFrame((tt) => this.frame(tt))
    const dt = Math.min(0.1, (t - this.last) / 1000)
    this.last = t
    const inBase = this.scene === this.base
    // On a run the camp clock slows down, so a run costs hours rather than half a day.
    const speed = this.running && inBase && !S.raid && !this.map.isOpen ? S.speed || 1 : this.mission ? 0.35 : 1
    const paused = this.mission?.paused
    const simDt = dt * speed
    if (this.running && !paused) {
      // Run the economy in small steps so ×4 stays stable.
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
    } else if (!this.running && this.base && !this.titleEl?.isConnected) {
      // game over: keep the world animating
    }
    if (!this.titleEl?.isConnected && this.running) gfx.input.update(dt)
    else if (this.titleEl?.isConnected) gfx.rig.yawGoal += dt * 0.05
    gfx.rig.update(dt)
    if (this.map.isOpen) {
      this.ui?.update(dt)
      return // the map covers the whole screen
    }
    const scene = this.scene
    if (scene) {
      scene.update(dt, simDt)
      gfx.renderer.render(scene.scene, gfx.camera)
      gfx.labels.update(dt, gfx.camera, scene.scene)
    }
    this.ui?.update(dt)
  }
}

const game = new Game()
window.__holdout = game
Object.defineProperty(window, '__S', { get: () => S })
game.boot()
