// Boot, loading screen, title, scene switching (camp, city map, supply runs),
// the simulation loop, hotkeys, settings and saving.
import './core/threefix.js'
import { Pipeline } from './render/pipeline.js'
import { initView, view, groundAt } from './render/view.js'
import { pregenerate } from './render/texgen.js'
import { initAudio, sfx, setSound, setVolume } from './core/audio.js'
import { S, newGame, hasSave, load, save, day, hour, log, gain, workersOf, removeLink, wipeSave, buildCost, newStation, pay, canAfford, completeGoal, backupSave, vehicleOf, wearVehicle, NET, MP_SAVE_KEY, playerOf, DISTRICTS, MODES, killSurvivor, getS } from './game/state.js'
import { Session, readIntent, writeIntent, me, initMp } from './net/mp.js'
import { Coop } from './net/coop.js'
import { newCode } from './net/transport.js'
import { lobbyCard, joinError } from './ui/lobby.js'
import { Pings } from './ui/pings.js'
import { toggleFullscreen } from './ui/fullscreen.js'
import { warmUp, compileFor, preRender } from './render/warmup.js'
import { soundLog } from './world/sound.js'
import { econTick, initSchedules, autoResolveRaid, scheduleRaid, raidIntel } from './game/economy.js'
import { alerts } from './ui/brief.js'
import { wireDiaries, runStart } from './game/diary.js'
import { setCity } from './game/recon.js'
import { notify } from './ui/notify.js'
import * as belts from './game/belts.js'
import * as stateMod from './game/state.js'
import * as econMod from './game/economy.js'
import * as storyMod from './game/story.js'
import * as reconMod from './game/recon.js'
import { WEATHER as WX } from './render/materials.js'
import { STATIONS, GAME_MIN_PER_SEC, SEC_PER_DAY, RES, DAY_MIN } from './game/data.js'

const SKIP_SPEED = 12
const UNDO_MS = 10000

const OFFLINE_DIV = 15 // real seconds away per second of camp work
const OFFLINE_MAX_DAYS = 3
import { BaseScene } from './scenes/base.js'
import { CityMap } from './scenes/citymap.js'
import { Mission } from './scenes/mission.js'
import { UI } from './ui/ui.js'
import { genCity } from './world/city.js'
import { portrait } from './ui/portrait.js'
import { initFps, fpsFrame } from './ui/fps.js'
import { paceFrame } from './render/pacer.js'
import { bus, h } from './core/util.js'

const GRASS = { low: 0, medium: 18000, high: 40000, ultra: 75000 }

class Game {
  constructor() {
    this.pipe = null
    this.pings = new Pings()
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
    wireDiaries()
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
    // multiplayer: the title screen asked to host or join (kept across reloads)
    const intent = readIntent()
    let saved = null
    if (intent?.mode === 'join') {
      note.textContent = `Joining camp ${intent.code}…`
      try {
        const who = me()
        this.net = new Session(this, 'client', intent.code, who)
        await this.net.join()
      } catch (e) {
        this.net?.close()
        this.net = null
        NET.role = 'solo'
        writeIntent(null)
        this.joinFailed = joinError(e.message)
      }
    }
    if (this.net) {
      saved = S
    } else if (intent?.mode === 'host') {
      NET.role = 'host'
      NET.pid = me().pid
      saved = intent.fresh ? null : hasSave(MP_SAVE_KEY)
      if (saved) {
        load(saved)
        this.catchUp()
      } else {
        newGame()
        initSchedules()
        S.mode = intent.gameMode === 'once' ? 'once' : 'restock'
        // keep the graphics settings from single player
        const solo = hasSave()
        if (solo?.settings) S.settings = { ...S.settings, ...solo.settings }
      }
      if (!S.mp) initMp(newCode(), { ...me(), name: intent.name || me().name })
    } else {
      saved = hasSave()
      if (saved) {
        load(saved)
        this.catchUp()
      } else {
        newGame()
        initSchedules()
      }
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
    if (intent && (this.net || NET.role === 'host')) this.startNet(intent)
    else this.title(saved)
    // compile the shaders the camp will need while nothing is happening
    setTimeout(() => warmUp(this), 2500)
    view.input.onKey = (e) => {
      if (this.onKey(e)) e._handled = true
    }
    view.input.onPing = (x, y) => this.ping(x, y)
    // right-click belongs to the game everywhere, not just on the 3D view:
    // never the browser's menu (text fields keep theirs, for paste)
    window.addEventListener('contextmenu', (e) => {
      if (!e.target.closest?.('input, textarea, [contenteditable="true"]')) e.preventDefault()
    })
    initFps()
    const loop = (t) => {
      requestAnimationFrame(loop)
      if (paceFrame(t)) this.frame(t)
    }
    requestAnimationFrame(loop)
    this.startTicker()
    const unlock = () => initAudio()
    window.addEventListener('pointerdown', unlock)
    window.addEventListener('keydown', unlock)
    window.addEventListener('beforeunload', () => this.running && save())
    document.addEventListener('visibilitychange', () => document.hidden && this.running && save())
  }
  // The city is the same for the whole camp: its locations seed radio events.
  makeCity() {
    this.city = genCity(S.seed)
    setCity(this.city)
    S.cityLocs = this.city.locs.map((l) => ({ id: l.id, type: l.type, level: l.level, name: l.name, district: l.lot?.district || 'residential' }))
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
    // the interface size, held down so the side bar still fits the window
    const want = S?.settings?.uiScale || 1
    const ui = Math.min(want, Math.max(0.9, (hh - 20) / 740))
    document.documentElement.style.setProperty('--ui', ui.toFixed(3))
    // the side bar is tall: it grows only while it stays clear of the top bar
    document.documentElement.style.setProperty('--navui', Math.min(ui, Math.max(1, (hh - 150) / 730)).toFixed(3))
  }
  applySettings(first = false) {
    const st = S.settings
    // a guest's settings are their own, kept apart from the shared camp
    if (NET.role === 'client') {
      try {
        localStorage.setItem('holdout.mpsettings', JSON.stringify(st))
      } catch {}
    }
    const q = st.quality || 'high'
    const tilt = st.tilt !== false
    this.pipe.setAutoRes(st.autoRes !== false)
    const changedQ = q !== this.pipe.quality || tilt !== this.pipe.tiltShift
    this.pipe.tiltShift = tilt
    if (changedQ || first) this.pipe.setQuality(q)
    this.resize()
    view.input.edgePan = st.edgePan !== false
    setSound(st.sound !== false)
    setVolume(st.volume ?? 0.8)
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
          h('li', h('b', 'Build a machine. '), 'Belt forges, labs and machine shops into production lines, from scrap to steel to circuits. Unlock tiers, study schematics, overclock with power cores.'),
          h('li', h('b', 'See only what they see. '), 'Runs are dark until your people walk in. Scouts sense through walls; the nearsighted miss what is coming.'),
          h('li', h('b', 'Survive the seasons. '), 'Bites infect, winters freeze, Blood Moons bring the dead in force. Hordes grow with everything you build.'),
          h('li', h('b', 'Call the coast. '), 'Rebuild the old broadcast mast in five phases, then hold the last night. A campaign for weeks, not hours.'),
        ),
        h(
          'div.tbtns',
          saved ? h('button.btn.go.big', { onclick: () => this.start(false) }, `Continue · Day ${day()}`) : h('button.btn.go.big', { onclick: () => this.chooseMode() }, 'Start'),
          saved ? h('button.btn.big.ghost', { onclick: () => this.confirmNew() }, 'New camp') : null,
          h('button.btn.big.ghost', { onclick: () => this.showLobby() }, 'Multiplayer'),
          h('button.btn.big.ghost', { onclick: () => this.ui?.openSettings() ?? this.quickSettings() }, 'Settings'),
        ),
        h('p.fine', 'Best on a PC with a mouse. Progress saves in this browser.'),
      ),
    )
    document.getElementById('hud').append(el)
    this.titleEl = el
    view.rig.jump(56, 58, 54)
    if (this.joinFailed) this.showLobby(this.joinFailed)
    else if (/^#join[A-Za-z0-9]{5}$/.test(location.hash)) this.showLobby(null, location.hash.slice(5).toUpperCase())
  }
  // A new camp: which kind of city is it?
  chooseMode() {
    sfx('click')
    const card = this.titleEl.querySelector('.tcard')
    card.hidden = true
    let pick = 'restock'
    const opts = Object.entries(MODES).map(([k, m]) =>
      h(
        'button.modeopt' + (k === pick ? '.on' : ''),
        {
          onclick: (e) => {
            pick = k
            sfx('select')
            for (const b of box.querySelectorAll('.modeopt')) b.classList.toggle('on', b === e.currentTarget)
          },
        },
        h('b', m.name),
        h('small', m.short),
        h('p', m.desc),
      ),
    )
    const box = h(
      'div.tcard.modecard',
      h('div.logo.big', 'HOLDOUT'),
      h('h2', 'What is left of the city?'),
      h('p.tag', 'Both play the whole story: the Signal, the hordes, the city to take back. The difference is what the city still holds.'),
      h('div.modeopts', opts),
      h(
        'div.tbtns',
        h('button.btn.go.big', { onclick: () => ((S.mode = pick), box.remove(), this.start(false)) }, 'Start'),
        h('button.btn.big.ghost', { onclick: () => (box.remove(), (card.hidden = false)) }, 'Back'),
      ),
    )
    this.titleEl.append(box)
  }
  // The multiplayer card, in place of the title card.
  showLobby(error = null, code = '') {
    const card = this.titleEl.querySelector('.tcard')
    card.hidden = true
    const lob = lobbyCard(this, { error, code, back: () => (lob.remove(), (card.hidden = false)) })
    this.titleEl.append(lob)
  }
  // Hosting or joining: straight into the camp, then open the connection.
  startNet(intent) {
    this.start()
    this.coop = new Coop(this)
    if (NET.role === 'host') {
      this.net = new Session(this, 'host', S.mp.code, { ...me(), name: S.mp.players[me().pid]?.name || intent.name || me().name })
      if (!S.mp.players[NET.pid]) initMp(S.mp.code, me())
      this.net.host().then(
        () => this.ui.toast(`Hosting camp ${S.mp.code}. Friends join with this code.`, 'good'),
        (e) => this.ui.netProblem(e.message),
      )
    } else {
      this.ui.toast(S.mp.server ? `Welcome to ${S.mp.name || 'the camp'}` : `Joined ${S.mp.players[S.mp.host]?.name || 'the host'}'s camp`, 'good')
      // back after a while: what happened since
      const left = S.mp.players[NET.pid]?.left
      if (left != null && S.time - left >= 6 * 60) setTimeout(() => this.ui.sinceModal(left), 900)
    }
    this.ui.netReady()
  }
  // Leave a multiplayer camp for the title screen (the host saves first).
  leaveNet() {
    if (NET.role === 'host') save()
    this.net?.close()
    writeIntent(null)
    setTimeout(() => location.reload(), 250)
  }
  // A friend's co-op run sets out with some of this player's survivors:
  // build the same street and follow the leader's game.
  startCoopRemote(run) {
    if (this.mission || this.starting) return this.coop?.leave(run.id)
    const loc = this.city.locs.find((l) => l.id === run.locId)
    if (!loc) return
    if (this.scene === this.map) {
      this.map.close()
      this.scene = this.base
    }
    if (this.scene === this.base) this.baseCam = view.rig.save()
    this.base.cancelPlacing?.()
    this.ui.closePanel()
    this.ui.closeModal()
    this.ui.toggleBuild(false)
    this.ui.showCamp(false)
    const ids = Object.values(run.roster).flat()
    const lo = { ...run.loadout }
    if (lo.stash === -1) lo.stash = Infinity
    const card = this.missionCard(loc)
    setTimeout(() => {
      this.starting = false
      // the run may have ended while the card was going up
      if (this.coop.active?.id !== run.id) return this.missionFailed(card, null, 'The run was over before your squad got there.')
      try {
        this.mission = new Mission(this, loc, ids, { ...lo, coop: { run, role: 'joiner', seed: run.seed } })
      } catch (e) {
        return this.missionFailed(card, e)
      }
      this.enterMission(this.mission, card)
      this.coop.flushPending(this.mission)
    }, 40)
  }
  endCoopRemote(report) {
    const m = this.mission
    if (!m?.remote || m.ending) return
    m.ending = true
    m.over = true
    setTimeout(() => {
      m.dispose()
      this.mission = null
      this.scene = this.base
      if (this.baseCam) view.rig.restore(this.baseCam)
      this.base.enter()
      view.input.handler = this.base
      this.ui.showCamp(true)
      if (report?.title) this.ui.missionReport({ loot: {}, items: [], lost: [], injured: [], ...report })
      bus.emit('change')
    }, 600)
  }
  // ---------------------------------------------------------------- pings
  // Mark a spot for friends: in camp everyone sees it, on a co-op run the
  // friends on that run. Returns false when there is nobody to show.
  ping(sx = view.input.mouse.x, sy = view.input.mouse.y) {
    if (NET.role === 'solo' || !this.net || !this.running) return false
    const m = this.scene === this.mission && this.mission?.coop && !this.mission.over ? this.mission : null
    if (this.scene !== this.base && !m) return false
    const p = groundAt(sx, sy)
    if (!p) return false
    const now = performance.now()
    if (now - (this.lastPing || 0) < 600) return true
    this.lastPing = now
    const d = { k: 'ping', x: Math.round(p.x * 10) / 10, z: Math.round(p.z * 10) / 10, w: m ? m.coop.run.id : 'camp' }
    this.net.relay(m ? m.coopPeers() : '*', d)
    this.showPing(NET.pid, d)
    return true
  }
  showPing(pid, d) {
    if (!Number.isFinite(d.x) || !Number.isFinite(d.z)) return
    const m = this.mission
    const inCamp = d.w === 'camp' && this.scene === this.base && !this.titleEl
    const onRun = m && this.scene === m && m.coop?.run.id === d.w
    if (!inCamp && !onRun) return
    const P = playerOf(pid)
    this.pings.add(inCamp ? this.base.scene : m.scene, d.x, inCamp ? 0 : m.floorY(d.x, d.z), d.z, P?.color || '#e8dcc0', pid === NET.pid ? 'You' : P?.name || 'Someone')
    sfx('ping', 120)
  }
  // An always-on camp hands this player a horde to fight live for everyone.
  captainRaid(R) {
    // the host's word arrives before its diff saying so: trust the message
    if (this.scene !== this.base || this.mission || this.mapLoading || this.base.mode === 'raid') return false
    this.base.cancelPlacing()
    this.ui.closePanel()
    this.ui.closeModal()
    this.net.captain = true
    this.base.startRaid(R)
    S.raid.captain = NET.pid
    this.ui.toast('The horde is here and you have the defence. Everyone is watching.', 'bad')
    return true
  }
  netGone(why) {
    if (this.goneShown) return
    this.goneShown = true
    this.running = false
    this.ui?.netGone(why)
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
    if (this.awayReport) {
      const r = this.awayReport
      this.awayReport = null
      setTimeout(() => this.ui.awayModal(r), 600)
    }
  }
  newGame() {
    wipeSave()
    writeIntent(null)
    location.reload()
  }
  // While the game is closed the crew keeps working, at a fraction of the
  // pace: two hours away is a day's production, up to three days. The camp
  // clock stands still, so no horde ever hits an empty camp.
  catchUp() {
    const away = (Date.now() - (S.saved || Date.now())) / 1000
    if (away < 120) return
    const sim = Math.min(away / OFFLINE_DIV, OFFLINE_MAX_DAYS * SEC_PER_DAY)
    const before = { ...S.res }
    const keep = { time: S.time, weather: { ...S.weather } }
    for (let left = sim; left > 0; left -= 1) econTick(Math.min(1, left), { offline: true })
    S.time = keep.time
    S.weather = keep.weather
    const diff = Object.entries(S.res).map(([k, v]) => [k, v - (before[k] || 0)]).filter(([, v]) => Math.abs(v) >= 1).sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]))
    const hrs = away / 3600
    this.awayReport = { away, days: sim / SEC_PER_DAY, diff }
    log(`While you were away (${hrs >= 1 ? `${hrs.toFixed(1)} h` : `${Math.round(away / 60)} min`}) the crew kept working: ${diff.slice(0, 6).map(([k, v]) => `${v > 0 ? '+' : ''}${Math.round(v)} ${RES[k].name.toLowerCase()}`).join(', ') || 'nothing much changed'}.`, 'story')
  }
  bindEvents() {
    if (this.bound) return
    this.bound = true
    bus.on('recruit', () => {
      sfx('radio')
      if (this.scene === this.base && !S.raid) this.ui.toast('Someone is waiting at the gate.', 'story')
      notify('Someone is at the gate', 'A survivor wants to join the camp.')
    })
    bus.on('raidWarn', (intel) => {
      sfx('alarm')
      this.ui.toast(`${intel.name} within the hour! Get your defenders home.`, 'bad')
      notify('The horde is coming', `${intel.name} within the hour. Get your defenders home.`)
    })
    bus.on('raidStart', (R) => {
      notify('The horde is here', `${R.count} infected at the wall.`)
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
    // liberating the city: districts and milestones, and the end of it all
    bus.on('district', (d) => this.ui.toast(`${DISTRICTS[d] || 'A district'} is clear. People came out of hiding with supplies.`, 'good'))
    bus.on('liberation', (m) => {
      if (m.at < 1) return this.ui.toast(`Liberation: ${m.name}. ${m.desc}`, 'good')
      sfx('complete')
      save()
      backupSave()
      setTimeout(() => this.ui.cityVictory(), 900)
    })
    bus.on('victory', ({ held }) => {
      sfx('complete')
      save()
      backupSave()
      setTimeout(() => this.ui.victory(held), 900)
    })
    bus.on('signalPhase', (p) => {
      if (p >= 5) this.ui.toast('The Signal reaches the coast. Every dead thing in the city heard it too. Hold one more night.', 'bad')
      else this.ui.toast(`The Signal: phase ${p} complete`, 'good')
    })
    bus.on('gameover', () => {
      this.running = false
      this.ui.gameOver()
    })
    bus.on('death', (s) => this.ui.toast(`${s.first} is dead.`, 'bad'))
    bus.on('newDay', () => {
      save()
      backupSave()
    })
  }
  // ---------------------------------------------------------------- input
  onKey(e) {
    if (e.key === 'Enter' && e.altKey) {
      e.preventDefault()
      toggleFullscreen(this.ui)
      return true
    }
    if (this.titleEl) return false
    if (this.ui?.onKey(e)) return true
    const k = e.key
    if (k.toLowerCase() === 'q' && !e.ctrlKey && !e.metaKey && this.ping()) return true
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
      if (k === '4') {
        this.skipAhead()
        return true
      }
      if ((k === 'z' || k === 'Z') && (e.ctrlKey || e.metaKey) && this.lastPlaced) {
        e.preventDefault?.()
        this.undoPlace()
        return true
      }
    }
    return false
  }
  setSpeed(v) {
    this.skip = null
    if (NET.role === 'client') {
      if (!this.net?.isAdmin()) return this.ui?.toast('The camp’s admin sets the pace.', '')
      this.net.setSpeed(v)
      sfx('click')
      return
    }
    if (S.raid && v > 1) v = 1
    if (v) this.lastSpeed = v
    S.speed = v
    sfx('click')
    this.ui?.updateTop()
  }
  // Skip ahead: the camp runs fast until the build is done, or to dawn (or
  // dusk); a horde getting close, someone at the gate, a call from the city
  // or a new problem stops it early.
  skipAhead() {
    if (NET.role === 'client') return this.ui?.toast('Only the host can skip ahead.', '')
    if (this.skip) return this.stopSkip('')
    if (!S || S.raid || this.scene !== this.base || S.over) return
    const I = raidIntel()
    if (I && I.in < 90) return this.ui?.toast('The horde is too close to skip ahead.', 'bad')
    const hr = hour()
    const building = this.isBuilding()
    const night = hr >= 18 || hr < 6
    const d0 = Math.floor(S.time / DAY_MIN) * DAY_MIN
    this.skip = {
      building,
      until: building ? null : d0 + (hr < 6 ? 6 * 60 : hr >= 18 ? DAY_MIN + 6 * 60 : 18 * 60),
      why: night ? 'dawn' : 'dusk',
      prev: S.speed || 1,
      bad: alerts(this.ui).filter((a) => a.sev === 'bad').map((a) => a.key),
      evs: this.liveEvents(),
      gate: !!S.recruit?.pending,
      cap: S.time + DAY_MIN,
      t: 0,
    }
    S.speed = SKIP_SPEED
    sfx('click')
    this.ui?.toast(building ? 'Skipping ahead until the build is done' : `Skipping ahead to ${this.skip.why}`, '')
    this.ui?.updateTop()
  }
  isBuilding() {
    return S.stations.some((st) => st.building) || !!S.fence.building || !!S.expanding
  }
  liveEvents() {
    return (S.events || []).filter((e) => e.expires > S.time).map((e) => e.locId + ':' + e.kind)
  }
  stopSkip(msg, kind = '') {
    if (!this.skip) return
    if (S.speed === SKIP_SPEED) S.speed = this.skip.prev
    this.skip = null
    if (msg) this.ui?.toast(msg, kind)
    if (msg && document.hidden) notify('Holdout', msg)
    this.ui?.updateTop()
  }
  checkSkip(dt) {
    const k = this.skip
    if (!k || (k.t -= dt) > 0) return
    k.t = 0.2
    if (S.speed !== SKIP_SPEED) return (this.skip = null)
    if (S.raid || S.over || this.scene !== this.base) return this.stopSkip('')
    if (this.ui.modalRoot.children.length) return this.stopSkip('')
    const I = raidIntel()
    if (I && I.in < 60) return this.stopSkip(`Stopped: ${I.known ? I.name : 'the horde'} is an hour out.`, 'bad')
    if (S.recruit?.pending && !k.gate) return this.stopSkip('Stopped: someone is at the gate.', 'good')
    if (this.liveEvents().some((e) => !k.evs.includes(e))) return this.stopSkip('Stopped: a call came in from the city.', '')
    const fresh = alerts(this.ui).find((a) => a.sev === 'bad' && !k.bad.includes(a.key))
    if (fresh) return this.stopSkip(`Stopped: ${fresh.text}.`, 'bad')
    if (k.building && !this.isBuilding()) return this.stopSkip('Skipped ahead: the build is done.', 'good')
    if (k.until != null && S.time >= k.until) return this.stopSkip(`Skipped ahead to ${k.why}.`, 'good')
    if (S.time >= k.cap) return this.stopSkip('Skipped a whole day.', '')
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
    const st = newStation(type, x, z, rot)
    sfx('build')
    this.lastPlaced = { id: st.id, cost, at: performance.now() }
    this.ui.toast(`${STATIONS[type].name}: construction started`, '', { label: 'Undo', fn: () => this.undoPlace() })
    return true
  }
  // Changed your mind: a station placed in the last few seconds comes back
  // down with everything it cost.
  undoPlace() {
    const L = this.lastPlaced
    this.lastPlaced = null
    const st = L && S.stations.find((x) => x.id === L.id)
    if (!st || st.level > 0 || !st.building || performance.now() - L.at > UNDO_MS) return
    for (const s of workersOf(st)) s.job = null
    for (const l of (S.links || []).filter((x) => x.from === st.id || x.to === st.id)) removeLink(l, 1)
    S.stations = S.stations.filter((x) => x !== st)
    gain(L.cost)
    bus.emit('stations')
    bus.emit('change')
    sfx('click')
    this.ui.toast(`${STATIONS[st.type].name} taken down, cost returned`)
  }
  // ---------------------------------------------------------------- scenes
  openMap(locId) {
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
      const loc = locId && this.city.locs.find((l) => l.id === locId)
      if (loc) this.map.select(loc)
    }
    if (this.map) return go()
    // first visit: build the city behind a short loading card
    this.mapLoading = true
    const card = h('div.loading.soft', h('div.ld-card', h('div.logo', 'ASHFORD'), h('span', 'Surveying the city…')))
    document.getElementById('hud').append(card)
    setTimeout(() => {
      let ok = false
      try {
        this.map = new CityMap(this)
        ok = true
      } finally {
        if (!ok) {
          card.remove()
          this.mapLoading = false
        }
      }
      // its shaders and geometry get ready behind the card too
      const r = this.pipe.renderer
      const show = () => {
        if (!this.mapLoading) return
        preRender(r, this.map.scene, view.camera)
        card.remove()
        this.mapLoading = false
        go()
      }
      compileFor(r, this.map.scene, view.camera).then(show)
      setTimeout(show, 6000)
    }, 60)
  }
  closeMap() {
    this.map?.close()
    this.scene = this.base
    view.rig.restore(this.baseCam)
    this.base.enter()
    view.input.handler = this.base
    this.ui.showCamp(true)
  }
  startMission(loc, ids, loadout = {}) {
    if (!loadout.coop && this.coop?.joined) this.coop.leave()
    this.map?.close()
    this.ui.showCamp(false)
    // alone, the trip there passes in a blink; with friends, time is shared
    if (NET.role === 'solo') this.fastForward(loadout.travel || 0)
    // the card goes up first and the street is built behind it
    const card = this.missionCard(loc)
    setTimeout(() => {
      this.starting = false
      try {
        runStart(ids.map((id) => getS(id)))
        this.mission = new Mission(this, loc, ids, loadout)
      } catch (e) {
        this.missionFailed(card, e)
        return
      }
      const run = loadout.coop?.run
      if (run) this.net?.say(null, `${Object.keys(run.roster).map((p) => S.mp.players[p]?.name || 'Someone').join(' and ')} set out together for ${loc.name} with ${ids.length} survivors.`)
      else if (NET.role !== 'solo') this.net?.say(null, `${this.net.name} set out for ${loc.name} with ${ids.length} ${ids.length === 1 ? 'survivor' : 'survivors'}.`)
      this.enterMission(this.mission, card)
      completeGoal('firstRun')
      save()
    }, 40)
  }
  missionCard(loc) {
    this.scene = null
    view.input.handler = null
    this.starting = true
    const card = h('div.loading.soft', h('div.ld-card', h('div.logo', loc?.name || 'The city'), h('span', 'Heading in…')))
    document.getElementById('hud').append(card)
    return card
  }
  // a street that could not be built: back to camp rather than stuck
  missionFailed(card, e, why = 'The squad could not get there. Try again.') {
    if (e) console.error('holdout: the run could not start', e)
    card?.remove()
    this.starting = false
    this.mission = null
    this.scene = this.base
    if (this.baseCam) view.rig.restore(this.baseCam)
    this.base.enter()
    view.input.handler = this.base
    this.ui.showCamp(true)
    this.ui.toast(why, 'bad')
  }
  // The street is built; its shaders compile in the background behind a
  // short card (a street has dozens, and compiling them in the first frame
  // froze the game), then the run begins.
  enterMission(m, card = this.missionCard(m.loc)) {
    this.starting = false
    let done = false
    const go = () => {
      if (done) return
      done = true
      card.remove()
      if (this.mission !== m || m.over) return
      this.scene = m
      view.input.handler = m
    }
    // one still update first: the street sets up its torches and the sky's
    // light on its first frame, and shaders depend on both
    try {
      m.update(0)
    } catch {}
    compileFor(this.pipe.renderer, m.scene, view.camera).then(() => {
      if (done || this.mission !== m) return
      preRender(this.pipe.renderer, m.scene, view.camera)
      // let the card paint over that before the street appears
      setTimeout(go, 30)
    })
    // never wait on it for long
    setTimeout(go, 6000)
  }
  endMission(report) {
    const m = this.mission
    // there and back again
    if (S.stats && m.loadout?.km) S.stats.km = (S.stats.km || 0) + m.loadout.km * 2
    // the vehicle comes home a little more worn
    const v = vehicleOf(m.loadout?.vehId)
    if (v) {
      v.out = false
      wearVehicle(v, m.loadout.km || 1)
      bus.emit('vehicles')
    }
    setTimeout(() => {
      m.dispose()
      this.mission = null
      if (NET.role === 'solo') this.fastForward(m.loadout?.travel || 0)
      else this.net?.say(null, `${this.net.name}'s squad ${report.result === 'extracted' ? 'made it home from' : report.result === 'wiped' ? 'was lost at' : 'came back from'} ${m.loc?.name || 'the city'}.`)
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
  // A hidden tab gets no animation frames. With friends connected the camp
  // must not stop, so a tiny worker clock keeps the simulation and the
  // connection going (without drawing) while the tab is in the background.
  startTicker() {
    try {
      const src = URL.createObjectURL(new Blob(['setInterval(() => postMessage(0), 100)'], { type: 'text/javascript' }))
      this.ticker = new Worker(src)
      this.ticker.onmessage = () => {
        if (document.hidden && this.net && this.running) this.frame(performance.now(), true)
      }
    } catch {
      this.ticker = null
    }
  }
  frame(t, hidden = false) {
    const dt = Math.max(0, Math.min(hidden ? 1 : 0.1, (t - this.last) / 1000))
    if (!hidden) fpsFrame(t - this.last)
    this.last = t
    const inBase = this.scene === this.base
    // On a run the camp clock slows so a run costs hours, not half a day.
    // With friends the camp keeps one pace for everyone, set by the host.
    const mp = NET.role !== 'solo'
    const speed = this.running ? (mp ? (S.raid ? 1 : S.speed ?? 1) : inBase ? (S.raid ? 1 : S.speed ?? 1) : this.mission ? 0.35 : S.speed ?? 1) : 1
    const paused = mp ? !speed : this.mission?.paused || (inBase && speed === 0)
    const simDt = dt * (speed || 0)
    if (this.running && !paused && simDt > 0 && NET.role === 'client') this.net.guestTick(simDt)
    else if (this.running && !paused && simDt > 0) {
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
    this.net?.update(dt)
    if (this.skip) this.checkSkip(dt)
    if (hidden) {
      // a horde fight still plays out (and streams to friends) unseen, and
      // so does a run this player leads for friends
      if (S.raid && this.scene === this.base) this.base.update(dt, simDt)
      else if (this.mission?.coop && !this.mission.remote && this.scene === this.mission) this.mission.update(Math.min(dt, 0.1))
      return
    }
    if (this.ending) {
      // the camera drifts slowly round what is left
      view.rig.follow = null
      view.rig.yawGoal += dt * 0.05
      view.rig.distGoal = Math.min(view.rig.fitDist(view.rig.maxDist) * 0.8, view.rig.distGoal + dt * 1.5)
    } else if (!this.titleEl) view.input.update(dt)
    else view.rig.yawGoal += dt * 0.04
    view.rig.update(dt)
    const scene = this.scene
    if (scene) {
      scene.update(dt, this.titleEl ? dt : simDt)
      if (scene.render) scene.render(dt)
      else this.pipe.render(scene.scene, view.camera, dt)
      this.pings.update(dt, scene.scene)
      view.labels.update(dt, view.camera, scene.scene)
      this.pipe.adapt(dt)
    }
    this.ui?.update(dt)
  }
}

const game = new Game()
window.__holdout = game
window.__view = view
// handles for tests and profiling
window.__dbg = { compileFor, soundLog, killSurvivor }
Object.defineProperty(window, '__S', { get: () => S })
window.__belts = belts
window.__state = stateMod
window.__econ = econMod
window.__story = storyMod
window.__recon = reconMod
window.__weather = WX
game.boot()
