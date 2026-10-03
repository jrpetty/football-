// The camp scene. Terrain and the world around it, the wall and gate, every
// station, the people living here, building placement, expansion plots,
// day/night lighting with a pool of real lights, weather, and horde attacks
// (raid logic lives in baseraid.js).
import * as THREE from 'three'
import { Atmosphere, nightFactor, isNight } from '../render/sky.js'
import { FX, tickFlames } from '../render/fx.js'
import { view, pickAt, groundAt } from '../render/view.js'
import { setNightGlow, WEATHER } from '../render/materials.js'
import { wind } from '../render/terrain.js'
import { Grid, BLOCK } from '../core/grid.js'
import { BaseWorld } from './baseworld.js'
import { FenceView } from './basefence.js'
import { StationView } from './basestation.js'
import { StationBatcher } from './basebatch.js'
import { CampPeople } from './basepeople.js'
import { RaidMixin } from './baseraid.js'
import { RaidNetMixin } from './raidnet.js'
import { netMarkers } from '../ui/netui.js'
import { BeltMixin, nearestPod } from './basebelts.js'
import { stationModel } from '../models/stations.js'
import { makeSurvivorCharacter } from '../world/agents.js'
import { STATIONS, RES, EXPANSIONS } from '../game/data.js'
import { NET, S, BASE, bounds, hour, stationSize, workersOf, gateTiles, expansionAvailable, expansionCost, season, leafTurn } from '../game/state.js'
import { power } from '../game/economy.js'
import { sfx, setAmbience } from '../core/audio.js'
import { bus, h, rand, clamp, fmt } from '../core/util.js'
import { icon } from '../ui/icons.js'

export const GHOST_OK = new THREE.MeshStandardMaterial({ color: '#9ae6a0', emissive: '#3a8a4a', emissiveIntensity: 0.5, transparent: true, opacity: 0.55, depthWrite: false })
export const GHOST_BAD = new THREE.MeshStandardMaterial({ color: '#ff8a8a', emissive: '#a02020', emissiveIntensity: 0.6, transparent: true, opacity: 0.5, depthWrite: false })
const LIGHTS = 8
const _tint = new THREE.Vector3(1, 1, 1)

export class BaseScene {
  constructor(game) {
    this.game = game
    this.scene = new THREE.Scene()
    this.atmo = new Atmosphere(this.scene, game.pipe.renderer, { shadowSize: game.pipe.Q.shadow })
    this.fx = new FX(this.scene)
    this.grid = new Grid(BASE.W, BASE.H)
    this.world = new BaseWorld(this, { seed: S.seed % 100000, grass: game.grassCount() })
    this.fence = new FenceView(this)
    this.stationViews = new Map()
    this.batcher = new StationBatcher(this.scene)
    this.people = new CampPeople(this)
    this.squad = []
    this.zombies = []
    this.wanderers = []
    this.placing = null
    this.hovered = null
    this.selected = null
    this.selectedPerson = null
    this.t = 0
    this.time = 0
    this.mode = 'base'
    // dynamic lights
    this.lights = []
    for (let i = 0; i < LIGHTS; i++) {
      const l = new THREE.PointLight('#ffb060', 0, 10, 1.8)
      l.position.set(0, -20, 0)
      this.scene.add(l)
      this.lights.push(l)
    }
    this.spots = []
    for (let i = 0; i < 4; i++) {
      const s = new THREE.SpotLight('#f0f4ff', 0, 30, 0.6, 0.5, 1.4)
      s.position.set(0, -20, 0)
      this.scene.add(s, s.target)
      this.spots.push(s)
    }
    this.cursor = this.makeCursor()
    this.plotLabels = new Map()
    this.offs = []
    const on = (ev, fn) => this.offs.push(bus.on(ev, fn))
    on('stations', () => {
      this.syncStations()
      this.syncBelts?.()
    })
    on('built', (st) => {
      this.syncStations()
      const v = this.stationViews.get(st.id)
      if (v && this.active) {
        this.fx.dust(new THREE.Vector3(v.cx, 0.4, v.cz), 14, '#b8a888')
        this.fx.burst(new THREE.Vector3(v.cx, 1.2, v.cz), '#c8b88a', 18, 3, 1, 3)
        sfx('complete')
      }
      this.repaint()
    })
    on('fence', () => this.fence.refresh())
    on('change', () => this.people.sync())
    on('nickname', () => this.people.sync())
    on('recruitJoined', () => {
      this.fence.openGate(8)
      this.people.sync()
    })
    on('produced', (st, out) => this.showProduce(st, out))
    on('expanded', () => this.onExpanded())
    on('expansions', () => this.world.refresh(true))
    on('links', () => this.syncBelts())
    on('vehicles', () => this.world.buildVehicles())
    this.syncStations()
    this.initBelts()
    this.fence.refresh()
    this.repaint()
    this.world.refresh(true)
    this.world.rebuildGrass()
    this.world.buildDecor()
    this.people.sync()
  }
  dispose() {
    for (const off of this.offs) off()
    this.disposeBelts()
    view.labels.clearScene(this.scene)
    this.atmo.dispose()
    this.batcher.dispose()
    this.game.pipe.forget(this.scene)
  }
  get active() {
    return this.game.scene === this
  }
  get rightClickCommands() {
    return !!S.raid
  }
  enter() {
    const b = bounds()
    view.rig.minDist = 9
    view.rig.maxDist = 78
    view.rig.setBounds(b.x0 - 14, b.z0 - 14, b.x1 + 14, b.z1 + 18)
    if (!this.entered) {
      const fire = S.stations.find((s) => s.type === 'campfire')
      view.rig.jump(fire ? fire.x + 2 : 56, fire ? fire.z + 3 : 58, 42)
    }
    this.entered = true
    this.snowInit = false
    this.leafInit = false
    this.syncStations()
    this.people.sync()
    this.fence.refresh()
  }
  repaint() {
    this.world.repaint()
  }
  onExpanded() {
    this.fence.refresh()
    this.repaint()
    this.world.refresh(true)
    this.world.rebuildGrass()
    this.clearDecorGrid()
    this.world.buildDecor()
    const b = bounds()
    view.rig.setBounds(b.x0 - 14, b.z0 - 14, b.x1 + 14, b.z1 + 18)
    sfx('complete')
  }

  clearDecorGrid() {
    const g = this.grid
    for (let i = 0; i < g.owner.length; i++) if (g.owner[i] === 'decor') {
      g.cost[i] = 0
      g.opaque[i] = 0
      g.owner[i] = null
    }
  }

  // ---------------------------------------------------------------- stations
  syncStations() {
    const seen = new Set()
    // clear station tiles, then re-block current footprints
    const g = this.grid
    for (let i = 0; i < g.owner.length; i++) {
      const o = g.owner[i]
      if (o && typeof o === 'object' && o.type) {
        g.cost[i] = 0
        g.opaque[i] = 0
        g.owner[i] = null
      }
    }
    for (const st of S.stations) {
      seen.add(st.id)
      let v = this.stationViews.get(st.id)
      if (!v) {
        v = new StationView(this, st)
        this.stationViews.set(st.id, v)
      } else {
        v.st = st
        v.refresh()
      }
      const [w, d] = stationSize(st)
      g.fillRect(st.x, st.z, w, d, BLOCK, 0, st)
    }
    for (const [id, v] of this.stationViews) {
      if (!seen.has(id)) {
        v.dispose()
        this.stationViews.delete(id)
      }
    }
  }
  showProduce(st, out) {
    if (!this.active || view.rig.dist > 48) return
    const v = this.stationViews.get(st.id)
    if (!v) return
    const [k, n] = Object.entries(out)[0]
    view.labels.float(this.scene, new THREE.Vector3(v.cx, 2.4, v.cz), `<i class="ri" style="--c:${RES[k].color}">${icon(k === 'pammo' || k === 'rammo' || k === 'shells' ? 'ammo' : k) || ''}</i>+${n} ${RES[k].short || RES[k].name}`, 'res')
  }

  // ---------------------------------------------------------------- placement
  makeCursor() {
    const g = new THREE.Group()
    const tiles = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ color: '#7cc36a', transparent: true, opacity: 0.28, depthWrite: false }))
    tiles.rotation.x = -Math.PI / 2
    tiles.position.y = 0.04
    g.add(tiles)
    const edge = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(1, 0.02, 1)), new THREE.LineBasicMaterial({ color: '#c8ffb0' }))
    edge.position.y = 0.05
    g.add(edge)
    g.userData = { tiles, edge }
    g.visible = false
    this.scene.add(g)
    return g
  }
  startPlacing(type, move = null) {
    this.cancelPlacing()
    const ghost = stationModel(type, move ? move.level : 1)
    ghost.traverse((o) => {
      if (o.isMesh) {
        o.material = GHOST_OK
        o.castShadow = false
        o.receiveShadow = false
        o.userData.pick = null
      }
    })
    this.scene.add(ghost)
    const rot = move ? move.rot : 0
    this.placing = { type, rot, ghost, x: 0, z: 0, ok: false, move }
    ghost.rotation.y = rot ? Math.PI / 2 : 0
    this.cursor.visible = true
    if (move) {
      const v = this.stationViews.get(move.id)
      if (v) v.setVisible(false)
    }
    const r = view.canvas.getBoundingClientRect()
    this.updatePlacing(view.input.mouse.x || r.width / 2, view.input.mouse.y || r.height / 2)
    this.game.ui?.showPlaceBar(STATIONS[type].name, !!move)
  }
  cancelPlacing() {
    if (!this.placing) return
    if (this.placing.move) {
      const v = this.stationViews.get(this.placing.move.id)
      if (v) v.setVisible(true)
    }
    this.scene.remove(this.placing.ghost)
    this.placing = null
    this.cursor.visible = false
    this.game.ui?.hidePlaceBar()
  }
  rotatePlacing() {
    if (!this.placing) return
    this.placing.rot = this.placing.rot ? 0 : 1
    this.placing.ghost.rotation.y = this.placing.rot ? Math.PI / 2 : 0
    this.updatePlacingAt(this.placing.x, this.placing.z)
    sfx('click')
  }
  updatePlacing(sx, sy) {
    const p = groundAt(sx, sy)
    if (p) this.updatePlacingAt(p.x, p.z)
  }
  updatePlacingAt(px, pz) {
    const P = this.placing
    const [w0, d0] = STATIONS[P.type].size
    const [w, d] = P.rot ? [d0, w0] : [w0, d0]
    const x = Math.round(px - w / 2)
    const z = Math.round(pz - d / 2)
    P.x = px
    P.z = pz
    P.tx = x
    P.tz = z
    P.ok = this.canPlace(x, z, w, d, P.move)
    P.ghost.position.set(x + w / 2, 0, z + d / 2)
    const mat = P.ok ? GHOST_OK : GHOST_BAD
    if (P.mat !== mat) {
      P.mat = mat
      P.ghost.traverse((o) => o.isMesh && (o.material = mat))
    }
    const c = this.cursor
    c.position.set(x + w / 2, 0, z + d / 2)
    c.userData.tiles.scale.set(w, d, 1)
    c.userData.edge.scale.set(w, 1, d)
    c.userData.tiles.material.color.set(P.ok ? '#7cc36a' : '#d8384a')
    c.userData.edge.material.color.set(P.ok ? '#c8ffb0' : '#ff9a9a')
    this.game.ui?.placeOk(P.ok)
  }
  canPlace(x, z, w, d, move = null) {
    const b = bounds()
    if (x < b.x0 + 2 || z < b.z0 + 2 || x + w > b.x1 - 1 || z + d > b.z1 - 1) return false
    // keep a lane open from the gate
    const g = gateTiles(b)
    if (x < g[2] + 3 && x + w > g[0] - 2 && z + d > b.z1 - 5) return false
    // belts in the way are re-routed round it once it's down
    const grid = this.grid
    for (let i = x - 1; i <= x + w; i++) {
      for (let j = z - 1; j <= z + d; j++) {
        const own = grid.owner[grid.i(i, j)]
        const isMe = move && own === move
        const inner = i >= x && i < x + w && j >= z && j < z + d
        if (inner && !isMe && !grid.open(i, j)) return false
        if (!inner && own && typeof own === 'object' && own.type && !isMe) return false
      }
    }
    return true
  }
  confirmPlacing(keep = false) {
    const P = this.placing
    if (!P || !P.ok) {
      sfx('error')
      return false
    }
    const [pw, pd] = P.rot ? [STATIONS[P.type].size[1], STATIONS[P.type].size[0]] : STATIONS[P.type].size
    const under = this.beltsUnder(P.tx, P.tz, pw, pd, P.move || null)
    if (P.move) {
      const st = P.move
      st.x = P.tx
      st.z = P.tz
      st.rot = P.rot
      this.cancelPlacing()
      this.syncStations()
      this.afterMove(st)
      this.rerouteAround(under)
      this.repaint()
      sfx('build')
      bus.emit('change')
      return true
    }
    const ok = this.game.placeStation(P.type, P.tx, P.tz, P.rot)
    if (ok) {
      this.rerouteAround(under)
      this.repaint()
      if (!keep) this.cancelPlacing()
    }
    return ok
  }

  // ---------------------------------------------------------------- expansions
  updatePlots() {
    const want = new Set()
    for (const X of EXPANSIONS) {
      const building = S.expanding?.id === X.id
      if (!building && !expansionAvailable(X.id)) continue
      want.add(X.id)
      let L = this.plotLabels.get(X.id)
      if (!L) {
        const el = h('div.plot', { onclick: () => this.game.ui?.openExpansion(X.id) }, h('div.pl-name'), h('div.pl-sub'), h('div.sl-bar', h('i')))
        L = view.labels.add(el, new THREE.Vector3(), { offsetY: 0.6, scene: this.scene, maxDist: 110 })
        this.plotLabels.set(X.id, L)
      }
      const r = this.world.expansionLand(X.id)
      L.pos.set((r.x0 + r.x1) / 2, this.world.terrain.heightAt((r.x0 + r.x1) / 2, (r.z0 + r.z1) / 2), (r.z0 + r.z1) / 2)
      const el = L.el
      el.querySelector('.pl-name').textContent = building ? `Clearing ${X.name}` : `+ ${X.name}`
      const bar = el.querySelector('.sl-bar')
      if (building) {
        bar.style.display = ''
        bar.firstChild.style.width = `${(1 - S.expanding.left / S.expanding.total) * 100}%`
        el.querySelector('.pl-sub').textContent = ''
      } else {
        bar.style.display = 'none'
        const c = expansionCost(X.id)
        el.querySelector('.pl-sub').textContent = Object.entries(c).map(([k, v]) => `${fmt(v)} ${RES[k].short || RES[k].name.toLowerCase()}`).join(' · ')
      }
      el.classList.toggle('building', building)
    }
    for (const [id, L] of this.plotLabels) {
      if (!want.has(id)) {
        L.remove()
        this.plotLabels.delete(id)
      }
    }
  }

  // ---------------------------------------------------------------- the gate visitor
  updateVisitor(dt) {
    const p = S.recruit.pending
    if (p && !S.raid) {
      if (!this.visitor || this.visitor.id !== p.s.id) {
        this.removeVisitor()
        const ch = makeSurvivorCharacter(p.s)
        const g = gateTiles()
        const b = bounds()
        ch.root.position.set(g[1] + 0.5 + rand(-0.6, 0.6), 0, b.z1 + 2.8)
        ch.root.rotation.y = Math.PI
        ch.root.traverse((o) => {
          if (o.isMesh) {
            o.castShadow = true
            o.userData.pick = { type: 'visitor' }
          }
        })
        this.scene.add(ch.root)
        const el = h('div.alabel.visitor', { onclick: () => this.game.ui?.showRecruit() }, h('span.nm', 'Someone at the gate'))
        this.visitor = { id: p.s.id, ch, label: view.labels.add(el, () => ch.root.position, { offsetY: 2.3, scene: this.scene }) }
      }
      this.visitor.ch.update(dt, Math.sin(this.t * 0.4) > -0.3 ? 'wave' : 'idle', {})
    } else this.removeVisitor()
  }
  removeVisitor() {
    if (!this.visitor) return
    this.scene.remove(this.visitor.ch.root)
    this.visitor.label.remove()
    this.visitor.ch.dispose()
    this.visitor = null
  }

  // ---------------------------------------------------------------- input
  pickables() {
    const list = [...this.stationViews.values()].map((v) => v.group)
    for (const v of this.beltViews.values()) list.push(v.group)
    list.push(...this.podGroups())
    for (const w of this.people.workers) list.push(w.root)
    if (this.visitor) list.push(this.visitor.ch.root)
    if (this.world.vehGroup) list.push(...this.world.vehGroup.children)
    return list
  }
  onPress() {
    return false
  }
  onTap(x, y, e) {
    if (this.linking) return this.onLinkTap(x, y, e)
    if (this.beltTool) {
      if (e.button === 2) return this.cancelBeltTool()
      const ph = this.podHit(x, y)
      if (ph) return this.startFromPod(ph.st, ph.p)
      return
    }
    if (this.placing) {
      this.updatePlacing(x, y)
      if (e.button === 2) return this.cancelPlacing()
      if (e.touch && !this.placing.touchedOnce) {
        this.placing.touchedOnce = true
        return
      }
      this.confirmPlacing(e.shift)
      return
    }
    if (S.raid) return this.mode === 'raid' ? this.onRaidTap(x, y, e) : this.mirrorTap(x, y, e)
    if (e.button === 2) return
    const hit = pickAt(x, y, this.pickables())
    const pk = hit?.pick
    if (pk?.type === 'visitor') {
      this.game.ui?.showRecruit()
      sfx('click')
    } else if (pk?.type === 'station') {
      // Shift-click: build a selection to work on all at once
      if (e.shift || e.ctrl) {
        this.multi ??= new Set()
        if (this.selected && !this.multi.size) this.multi.add(this.selected)
        if (this.multi.has(pk.st.id)) this.multi.delete(pk.st.id)
        else this.multi.add(pk.st.id)
        this.select(null)
        sfx('select')
        if (this.multi.size >= 2) this.game.ui?.openMulti()
        else if (this.multi.size === 1) this.game.ui?.openStation([...this.multi][0])
        else this.game.ui?.closePanel()
        return
      }
      this.multi?.clear()
      this.select(pk.st.id)
      this.game.ui?.openStation(pk.st.id)
      sfx('click')
    } else if (pk?.type === 'person') {
      this.selectedPerson = pk.s.id
      this.game.ui?.openSurvivor(pk.s.id)
      sfx('click')
    } else if (pk?.type === 'vehicle') {
      this.game.ui?.openMotorPool()
      sfx('click')
    } else if (pk?.type === 'pods') {
      const p = this.podHit(x, y)
      if (p) this.startFromPod(p.st, p.p)
      else {
        this.select(pk.st.id)
        this.game.ui?.openStation(pk.st.id)
      }
      sfx('click')
    } else if (pk?.type === 'belt') {
      // a belt opens its own panel; where it was clicked is where a splitter would go
      const l = pk.link
      const tx = Math.floor(hit.point.x)
      const tz = Math.floor(hit.point.z)
      let on = false
      for (let i = 0; i < l.tiles.length; i += 2) if (l.tiles[i] === tx && l.tiles[i + 1] === tz) on = true
      this.select(null)
      this.game.ui?.openBelt(l, on ? [tx, tz] : null)
      sfx('click')
    } else {
      const p = groundAt(x, y)
      const b = bounds()
      const nearFence = p && (Math.abs(p.x - b.x0 - 0.5) < 1.2 || Math.abs(p.x - b.x1 - 0.5) < 1.2 || Math.abs(p.z - b.z0 - 0.5) < 1.2 || Math.abs(p.z - b.z1 - 0.5) < 1.2) && p.x > b.x0 - 1 && p.x < b.x1 + 2 && p.z > b.z0 - 1 && p.z < b.z1 + 2
      const plot = p && this.plotAt(p.x, p.z)
      if (nearFence) this.game.ui?.openFence()
      else if (plot) this.game.ui?.openExpansion(plot)
      else {
        this.select(null)
        this.selectedPerson = null
        this.game.ui?.closePanel()
      }
    }
  }
  plotAt(x, z) {
    for (const X of EXPANSIONS) {
      if (!expansionAvailable(X.id) && S.expanding?.id !== X.id) continue
      const r = this.world.expansionLand(X.id)
      if (x > r.x0 && x < r.x1 && z > r.z0 && z < r.z1) return X.id
    }
    return null
  }
  select(id) {
    this.selected = id
  }
  onHover(x, y) {
    if (this.placing) return this.updatePlacing(x, y)
    if (this.linking) return this.onLinkHover(x, y)
    if (this.beltTool) {
      const ph = this.podHit(x, y)
      document.body.style.cursor = ph ? 'pointer' : ''
      this.game.ui?.hoverTip(ph ? this.podTip(ph.st, ph.p) : null, x, y)
      return
    }
    const now = performance.now()
    if (now - (this.hoverT || 0) < 60) return
    this.hoverT = now
    if (S.raid) return this.mode === 'raid' ? this.onRaidHover?.(x, y) : null
    const hit = pickAt(x, y, this.pickables())
    const pk = hit?.pick
    this.hovered = pk?.type === 'station' ? pk.st.id : null
    document.body.style.cursor = pk ? 'pointer' : ''
    let tip = null
    if (pk?.type === 'station') {
      const st = pk.st
      const D = STATIONS[st.type]
      const ws = workersOf(st)
      const status = st.building ? `Under construction · ${Math.round((1 - st.building.left / st.building.total) * 100)}%` : st.stalled || (ws.length ? ws.map((s) => s.first).join(', ') : D.workers[Math.max(0, st.level - 1)] ? 'Nobody assigned' : D.desc.split('.')[0] + '.')
      tip = `<b>${D.name}${st.level ? ` <em>L${st.level}</em>` : ''}</b><span>${status}</span>`
    } else if (pk?.type === 'person') {
      const s = pk.s
      tip = `<b>${s.name}</b><span>${s.status === 'injured' ? 'Injured' : s.job ? STATIONS[S.stations.find((x) => x.id === s.job)?.type]?.name || '' : 'No job'}</span>`
    } else if (pk?.type === 'visitor') tip = '<b>Someone at the gate</b><span>Click to talk</span>'
    else if (pk?.type === 'belt') tip = this.beltTip(pk.link)
    else if (pk?.type === 'pods') {
      const p = nearestPod(pk.st, hit.point.x, hit.point.z)
      tip = p ? this.podTip(pk.st, p) : null
    }
    else if (pk?.type === 'vehicle') {
      const v = (S.vehicles || []).find((x) => x.id === pk.id)
      if (v) tip = `<b>${v.name}</b><span>${v.broken ? 'Dead: needs a battery, tyres and parts' : `Condition ${Math.round(v.cond)}%`}</span><small>Click for the motor pool</small>`
    }
    this.game.ui?.hoverTip(tip, x, y)
  }
  onKey(e) {
    if (e._handled) return
    const k = e.key.toLowerCase()
    if (this.linking) {
      if (k === 'escape') this.cancelLinking(this.linking.fromPanel)
      if (k === 'backspace' && this.linking?.via.length) this.popBend()
      return
    }
    if (this.beltTool) {
      if (k === 'escape') this.cancelBeltTool()
      return
    }
    if (this.placing) {
      if (k === 'r') this.rotatePlacing()
      if (k === 'escape') this.cancelPlacing()
      if (k === 'enter') this.confirmPlacing()
      return
    }
    if (S.raid) return this.onRaidKey?.(e)
  }

  // ---------------------------------------------------------------- lights
  updateLights(dt, night) {
    const anchors = []
    for (const v of this.stationViews.values()) v.lightAnchors(night, anchors)
    if (night > 0.25) for (const p of this.world.decorLights || []) anchors.push({ x: p.x + 0.44, y: 1.95, z: p.z, color: '#ffb060', intensity: 2 * night, dist: 7 })
    const f = view.rig.target
    for (const a of anchors) a.score = a.intensity / (1 + ((a.x - f.x) ** 2 + (a.z - f.z) ** 2) / 300)
    anchors.sort((a, b) => b.score - a.score)
    for (let i = 0; i < this.lights.length; i++) {
      const L = this.lights[i]
      const a = anchors[i]
      if (!a || a.score < 0.05) {
        L.intensity = 0
        L.position.y = -20
        continue
      }
      L.position.set(a.x, a.y, a.z)
      L.color.set(a.color)
      L.distance = a.dist * 1.2
      const fl = a.flicker ? 0.82 + Math.sin(this.t * 13 + i) * 0.08 + Math.sin(this.t * 7.7 + i * 3) * 0.1 : 1
      L.intensity = a.intensity * 2.2 * fl
    }
    // floodlights sweep the perimeter at night when powered
    const pinfo = power()
    const floods = night > 0.3 ? S.stations.filter((st) => st.type === 'floodlight' && pinfo.powered.has(st.id)) : []
    const b = bounds()
    const cx = (b.x0 + b.x1) / 2
    const cz = (b.z0 + b.z1) / 2
    this.spots.forEach((sp, i) => {
      const st = floods[i]
      if (!st) {
        sp.intensity = 0
        sp.position.y = -20
        return
      }
      const x = st.x + 0.5
      const z = st.z + 0.5
      const dx = x - cx
      const dz = z - cz
      const dl = Math.hypot(dx, dz) || 1
      sp.position.set(x, 3.6, z)
      sp.target.position.set(x + (dx / dl) * 12, 0, z + (dz / dl) * 12)
      sp.intensity = 60 * night
      const v = this.stationViews.get(st.id)
      const lamp = v?.pivots?.lamp
      if (lamp) lamp.rotation.y = Math.atan2(dx, dz)
    })
  }

  // ---------------------------------------------------------------- loop
  update(dt, simDt) {
    this.t += dt
    this.time += simDt
    const hr = hour()
    const night = nightFactor(hr)
    this.atmo.setWeather(S.weather?.type || 'clear')
    const a = this.atmo.update(hr, view.rig.target, view.rig.dist, dt)
    this.game.pipe.exposure = a.exposure
    // a Blood Moon stains the night red, from an hour before it comes
    const bm = night * (S.raid?.blood ? 1 : S.nextRaid?.blood && S.nextRaid.at - S.time < 90 ? clamp(1 - (S.nextRaid.at - S.time) / 90, 0, 1) : 0)
    this.bloodK = (this.bloodK || 0) + (bm - (this.bloodK || 0)) * Math.min(1, dt * 0.8)
    _tint.set(1 + this.bloodK * 0.32, 1 - this.bloodK * 0.22, 1 - this.bloodK * 0.26)
    this.game.pipe.grade(this.scene, { saturation: a.saturation * 1.08 * (1 + this.bloodK * 0.2), tint: _tint })
    setNightGlow(night)
    tickFlames(this.t)
    wind.time.value = this.t
    wind.strength.value = S.weather?.type === 'rain' ? 1.8 : S.weather?.type === 'overcast' ? 1.3 : 1
    const rain = S.weather?.type === 'rain' ? 1 : 0
    const snowing = S.weather?.type === 'snow' ? 1 : 0
    this.fx.setRain(rain * 0.9, view.rig.target)
    this.fx.setSnow(snowing, view.rig.target)
    // snow settles through winter and melts in spring
    const snowGoal = season().heat ? (snowing ? 1 : 0.72) : 0
    if (!this.snowInit) {
      WEATHER.uSnow.value = snowGoal
      this.snowInit = true
    }
    WEATHER.uSnow.value += (snowGoal - WEATHER.uSnow.value) * Math.min(1, simDt * 0.012)
    WEATHER.uSnowHole.value.set(0, 0, 0, 0)
    WEATHER.uAutumn.value += (leafTurn() - WEATHER.uAutumn.value) * (this.leafInit ? Math.min(1, simDt * 0.01) : 1)
    this.leafInit = true
    this.world.terrain.material.userData.uniforms.uWet.value += ((rain ? 0.85 : 0) - this.world.terrain.material.userData.uniforms.uWet.value) * Math.min(1, dt * 0.05)
    if (this.world.refresh()) this.repaintT = 0
    this.world.cull(view.camera, this.atmo.sun, view.rig.target)
    if (S.expanding) {
      this.repaintT = (this.repaintT ?? 0) - dt
      if (this.repaintT <= 0) {
        this.repaintT = 4
        this.repaint()
      }
    }
    this.fence.update(dt)
    const ctx = {
      simDt,
      camDist: view.rig.dist,
      focus: view.rig.target,
      hovered: this.hovered,
      selected: this.selected,
      multi: this.multi,
      night,
      nearCam: (x, z) => Math.hypot(x - view.rig.target.x, z - view.rig.target.z) < view.rig.dist * 1.1 + 10,
    }
    for (const v of this.stationViews.values()) {
      if (v.key !== `${v.st.level}|${v.st.building?.to || 0}|${v.st.rot}|${v.st.x},${v.st.z}`) v.refresh()
      v.update(dt, ctx)
    }
    this.updateLights(dt, night)
    this.updateBelts(dt, simDt)
    // a guest's camp mirrors the host's: raids arrive as a stream, and the
    // stragglers at the wall belong to the host's simulation
    const captain = this.game.net?.captain
    if (NET.role === 'client' && !captain) {
      if (S.raid || this.mirror) this.updateMirror(dt)
      if (!S.raid) this.people.update(dt, simDt)
    } else if (S.raid && this.mode === 'raid') {
      this.updateRaid(simDt)
      if (NET.role === 'host' || captain) this.raidStream(dt)
    } else if (S.raid) {
      // the host's horde is being fought by someone else: watch it
      this.updateMirror(dt)
    } else {
      this.people.update(dt, simDt)
      this.updateWanderers?.(simDt, night)
    }
    if (NET.role !== 'solo') netMarkers(this, dt)
    this.updateVisitor(dt)
    this.updatePlots()
    // batched station parts catch up with everything that moved them
    for (const v of this.stationViews.values()) v.syncBatch()
    this.fx.setViewport(window.innerHeight, view.camera.fov)
    // smoke, sparks and dust hang still while the camp is paused
    this.fx.update(simDt > 0 || S.raid ? dt : 0)
    setAmbience(S.raid ? 0.06 : 0.035)
  }
}
Object.assign(BaseScene.prototype, RaidMixin, RaidNetMixin, BeltMixin)
