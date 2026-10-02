// A station as it appears in the camp: the model for its level (or a blueprint
// ghost and scaffolding while it's being built), fading roofs, animated parts,
// flames, smoke and steam, light anchors and a status label.
import * as THREE from 'three'
import { cloneMat } from '../render/materials.js'
import { stationModel, scaffold } from '../models/stations.js'
import { slotGroups, syncPiles } from './basestock.js'
import { AnimalPen } from './baseanimals.js'
import { makeFlame } from '../render/fx.js'
import { STATIONS, RES, RECIPES, MODS, ITEMS } from '../game/data.js'
import { S, stationSize, workersOf, itemOf, itemName } from '../game/state.js'
import { view } from '../render/view.js'
import { nodeKind, nodeRes, inCap } from '../game/belts.js'
import { h, clamp, rand } from '../core/util.js'

const _dir = new THREE.Vector3()
const EMPTY = { lights: [], emitters: [], flames: [], spots: [], beds: [], seats: [], anims: [], blink: [], roofs: [] }
export const BLUEPRINT = new THREE.MeshStandardMaterial({ color: '#9ad0ff', emissive: '#4aa0ff', emissiveIntensity: 0.6, transparent: true, opacity: 0.22, depthWrite: false, roughness: 0.4 })
const _v = new THREE.Vector3()

export class StationView {
  constructor(base, st) {
    this.base = base
    this.st = st
    this.group = new THREE.Group()
    base.scene.add(this.group)
    this.key = ''
    this.model = null
    this.ghost = null
    this.scaf = null
    this.flames = []
    this.roofMats = []
    this.roofK = 1
    this.emitAcc = {}
    this.phase = {}
    this.t = rand(0, 10)
    this.makeLabel()
    this.refresh()
  }
  get info() {
    return this.model?.userData.info || this.ghost?.userData.info || EMPTY
  }
  // Rebuild visuals when the level, rotation or construction state changes.
  refresh() {
    const st = this.st
    const key = `${st.level}|${st.building?.to || 0}|${st.rot}|${st.x},${st.z}`
    if (key === this.key) return
    this.key = key
    for (const o of [this.model, this.ghost, this.scaf]) if (o) this.group.remove(o)
    for (const f of this.flames) f.mesh.parent?.remove(f.mesh)
    this.flames = []
    this.model = this.ghost = this.scaf = null
    this.roofMats = []
    this.tints = null
    this.piles = null
    this.stockT = 0
    this.penT = 0
    const [w, d] = stationSize(st)
    const [w0, d0] = STATIONS[st.type].size
    this.rotY = st.rot ? Math.PI / 2 : 0
    this.cx = st.x + w / 2
    this.cz = st.z + d / 2
    this.group.position.set(this.cx, 0, this.cz)
    if (st.level > 0) {
      this.model = stationModel(st.type, st.level)
      this.model.rotation.y = this.rotY
      this.group.add(this.model)
    }
    if (st.building) {
      if (st.level === 0) {
        this.ghost = stationModel(st.type, st.building.to)
        this.ghost.traverse((o) => {
          if (o.isMesh) {
            o.material = BLUEPRINT
            o.castShadow = false
            o.receiveShadow = false
          }
        })
        this.ghost.rotation.y = this.rotY
        this.group.add(this.ghost)
      }
      this.scaf = scaffold(w0, d0)
      this.scaf.rotation.y = this.rotY
      this.group.add(this.scaf)
    }
    // roofs get their own fadeable materials
    if (this.model) {
      const I = this.model.userData.info
      for (const name of I.roofs || []) {
        const p = this.model.userData.pivots[name]
        if (!p) continue
        p.traverse((o) => {
          if (!o.isMesh) return
          o.material = cloneMat(o.material)
          o.material.transparent = true
          o.material.depthWrite = true
          this.roofMats.push({ m: o.material, mesh: o })
        })
      }
      // flames (two crossed sheets each)
      for (const f of I.flames || []) {
        for (let k = 0; k < 2; k++) {
          const fl = makeFlame(f.w, f.h, 3.2)
          fl.position.set(f.x, f.y, f.z)
          fl.rotation.y = (k * Math.PI) / 2 + 0.4
          this.model.add(fl)
          this.flames.push({ mesh: fl, when: f.when })
        }
      }
      // floodlight / turret / searchlight handles
      this.pivots = this.model.userData.pivots
      this.baseRot = {}
      for (const [n, p] of Object.entries(this.pivots)) this.baseRot[n] = { r: p.rotation.clone(), p: p.position.clone(), s: p.scale.clone() }
    } else {
      this.pivots = {}
      this.baseRot = {}
    }
    this.group.traverse((o) => {
      if (o.isMesh && !o.userData.flame) o.userData.pick = { type: 'station', st }
    })
    this.label.offsetY = this.labelHeight()
  }
  labelHeight() {
    const t = this.st.type
    if (t === 'radio') return this.st.level >= 2 ? 6 : 4.2
    if (t === 'watchtower') return [5.8, 7.6, 9.6][Math.max(0, this.st.level - 1)]
    if (t === 'campfire') return 2.2
    if (t === 'floodlight') return 4.2
    if (t === 'bunkhouse' && this.st.level === 3) return 7
    return 3.2
  }
  // ---- coordinates
  toWorld(lx, lz, ly = 0) {
    const c = Math.cos(this.rotY)
    const s = Math.sin(this.rotY)
    return { x: this.cx + lx * c + lz * s, y: ly, z: this.cz - lx * s + lz * c }
  }
  spotWorld(i) {
    const sp = this.info.spots
    if (!sp?.length) {
      const f = this.frontWorld()
      return { x: f.x + (i - 0.5) * 0.9, z: f.z, face: f.face, anim: 'hammer' }
    }
    const s = sp[Math.min(i, sp.length - 1)]
    const off = i >= sp.length ? (i - sp.length + 1) * 0.7 : 0
    const p = this.toWorld(s.x + off, s.z, s.y || 0)
    const out = { x: p.x, z: p.z, y: s.tower ? s.y : s.sit ? Math.max(0, s.sit - 0.46) : s.y || 0, face: (s.face || 0) + this.rotY, anim: s.anim, sit: !!s.sit, tower: !!s.tower, tool: s.tool, train: s.train }
    if (s.tower) {
      // walk to the foot of the tower, then climb
      const f = this.toWorld(s.x * 0.3, STATIONS[this.st.type].size[1] / 2 + 0.7)
      out.ax = f.x
      out.az = f.z
    }
    return out
  }
  bedWorld(i) {
    const b = this.info.beds?.[i]
    if (!b) return null
    const p = this.toWorld(b.x, b.z)
    return { x: p.x, z: p.z, y: b.y, face: (b.face || 0) + this.rotY + Math.PI }
  }
  seatWorld(i) {
    const s = this.info.seats?.[i]
    if (!s) return null
    const p = this.toWorld(s.x, s.z)
    return { x: p.x, z: p.z, y: s.y || 0, face: (s.face || 0) + this.rotY }
  }
  frontWorld() {
    const d = STATIONS[this.st.type].size[1]
    const p = this.toWorld(0, d / 2 + 0.7)
    return { x: p.x, z: p.z, face: this.rotY + Math.PI }
  }
  // Light anchors in world space with their on/off state.
  lightAnchors(night, out) {
    const I = this.info
    if (!this.model || this.st.building) return
    for (const L of I.lights || []) {
      const on = L.when === 'always' || (L.when === 'night' ? night > 0.25 : L.when === 'active' ? this.st.active : true)
      if (!on) continue
      const p = this.toWorld(L.x, L.z, L.y)
      out.push({ x: p.x, y: p.y, z: p.z, color: L.color, intensity: L.intensity * (L.when === 'night' ? night : 1) * (L.when === 'always' ? 0.35 + night * 0.65 : 1), dist: L.dist, flicker: L.flicker, key: this.st.id + L.x + L.z })
    }
  }
  // ---- labels
  makeLabel() {
    const el = h('div.slabel', h('span.sl-name'), h('div.sl-bar', h('i')), h('span.sl-warn'))
    this.label = view.labels.add(el, () => _v.set(this.cx, 0, this.cz), { offsetY: 3, scene: this.base.scene, maxDist: 90 })
    this.lName = el.querySelector('.sl-name')
    this.lBar = el.querySelector('.sl-bar')
    this.lWarn = el.querySelector('.sl-warn')
    el.addEventListener('click', () => this.base.game.ui?.openStation(this.st.id))
  }
  updateLabel(hovered, selected) {
    const st = this.st
    const D = STATIONS[st.type]
    let name = ''
    let bar = null
    let warn = ''
    let cls = ''
    if (st.building) {
      name = `${D.name}${st.building.to > 1 ? ` → L${st.building.to}` : ''}`
      bar = 1 - st.building.left / st.building.total
      cls = 'build'
    } else if (st.working) {
      const o = st.orders.find((x) => x.id === st.working)
      if (o && o.total) {
        bar = 1 - o.left / o.total
        name = orderLabel(o)
        cls = 'craft'
      }
    } else if (D.recipe && st.active && (hovered || selected)) {
      bar = clamp(st.progress, 0, 1)
      name = D.name
    }
    // a problem shows once it has lasted a few seconds; a machine that is
    // only paced by its belts says how fast it runs instead of blinking
    if (!st.building && this.warnShow && this.warnShow !== 'No workers') warn = this.warnShow
    else if (!st.building && this.pace) {
      warn = this.pace
      cls = cls || 'paced'
    }
    if ((hovered || selected) && !name) name = `${D.name}${st.level ? ` · L${st.level}` : ''}`
    if (this.lName.textContent !== name) this.lName.textContent = name
    this.lBar.style.display = bar == null ? 'none' : ''
    if (bar != null) this.lBar.firstChild.style.width = `${clamp(bar, 0, 1) * 100}%`
    if (this.lWarn.textContent !== warn) this.lWarn.textContent = warn
    this.label.el.className = `slabel wl-inner ${cls}${!name && !warn && bar == null ? ' empty' : ''}${selected ? ' sel' : ''}`
  }
  makeSelRing() {
    const [w, d] = stationSize(this.st)
    const mat = new THREE.MeshBasicMaterial({ color: '#f2c230', transparent: true, opacity: 0.85, depthWrite: false })
    const g = new THREE.Group()
    const t = 0.12
    for (const [x, z, sx, sz] of [[0, -d / 2 - 0.3, w + 0.6, t], [0, d / 2 + 0.3, w + 0.6, t], [-w / 2 - 0.3, 0, t, d + 0.6], [w / 2 + 0.3, 0, t, d + 0.6]]) {
      const m = new THREE.Mesh(new THREE.BoxGeometry(sx, 0.04, sz), mat)
      m.position.set(x, 0.05, z)
      g.add(m)
    }
    g.renderOrder = 3
    this.group.add(g)
    this.selRing = g
  }
  // ---- per-frame
  update(dt, ctx) {
    this.t += dt
    const st = this.st
    const I = this.info
    // Machines follow camp time: they stop when the game is paused and speed
    // up with it (to a point, so nothing strobes). They spin up quickly and
    // run down slowly, so a machine paced by a belt keeps a steady look
    // instead of flicking on and off with every item.
    const sdt = Math.min(ctx.simDt ?? dt, dt * 6)
    const on1 = st.active ? 1 : 0
    this.runK = this.runK ?? on1
    this.runK += (on1 - this.runK) * Math.min(1, sdt * (on1 > this.runK ? 2.5 : 0.45))
    this.duty = this.duty ?? on1
    this.duty += (on1 - this.duty) * Math.min(1, sdt / 20)
    // the warning: shown once the same problem has lasted 4 seconds
    const w = st.stalled || null
    if (w !== this.warnNow) {
      this.warnNow = w
      this.warnT = 0
    } else this.warnT = (this.warnT || 0) + sdt
    const pacedBy = w && (w === 'Output belt backed up' || w.startsWith('Missing '))
    if (pacedBy) this.paceWhy = w
    const busy = this.duty > 0.12
    this.warnShow = w && this.warnT > 4 && !(pacedBy && busy) ? w : null
    this.pace = !st.building && busy && this.duty < 0.92 && this.paceWhy ? `${this.paceWhy === 'Output belt backed up' ? 'Paced by its belt' : this.paceWhy.replace('Missing ', 'Waiting on ')} · ${Math.round(this.duty * 100)}%` : null
    if (!busy || this.duty >= 0.92) this.paceWhy = null
    const active = this.runK > 0.05
    const run = this.runK
    const t = this.t
    // roofs fade when you look closely, hover or select
    const near = ctx.camDist < 26 && Math.hypot(ctx.focus.x - this.cx, ctx.focus.z - this.cz) < 9
    const want = ctx.hovered === st.id || ctx.selected === st.id || near ? 0.16 : 1
    this.roofK += (want - this.roofK) * Math.min(1, dt * 6)
    for (const r of this.roofMats) {
      r.m.opacity = this.roofK
      r.m.depthWrite = this.roofK > 0.9
      r.mesh.castShadow = this.roofK > 0.5
      r.mesh.visible = this.roofK > 0.03
    }
    // flames
    for (const f of this.flames) f.mesh.visible = f.when === 'always' || run > 0.3
    // animated parts
    if (this.model) {
      for (const A of I.anims || []) {
        const p = this.pivots[A.name]
        if (!p) continue
        const base = this.baseRot[A.name]
        // how hard this part works: 'active' parts follow the eased run
        // level; the rest are on or off, in camp time all the same
        const k = A.when === 'always' ? 1 : A.when === 'night' ? (ctx.night > 0.3 ? 1 : 0) : run
        const on = k > 0.02
        const ax = A.axis || 'y'
        const ph = (this.phase[A.name] = (this.phase[A.name] || 0) + sdt * k)
        if (A.kind === 'spin') {
          if (on) p.rotation[ax] += sdt * (A.speed || 1) * k
        } else if (A.kind === 'yaw') {
          if (on) p.rotation.y = base.r.y + Math.sin(ph * (A.speed || 0.3)) * (A.swing || 1)
        } else if (A.kind === 'press') {
          p.position[ax] = base.p[ax] - Math.pow(Math.abs(Math.sin(ph * (A.speed || 1) * Math.PI)), 3) * (A.amp || 0.3) * k
        } else if (A.kind === 'pump') {
          p.rotation[ax] = base.r[ax] + Math.sin(ph * (A.speed || 3)) * (A.amp || 0.3) * k
        } else if (A.kind === 'conveyor') {
          // items ride along the belt's own x axis, wrapping every gap
          if (on) p.position.copy(base.p).addScaledVector(_dir.set(1, 0, 0).applyQuaternion(p.quaternion), (ph * (A.speed || 0.3)) % (A.amp || 0.5))
        } else if (A.kind === 'slide') {
          p.position[ax] = base.p[ax] + Math.sin(ph * (A.speed || 1) * Math.PI * 2) * (A.amp || 0.1) * k
        } else if (A.kind === 'shake') {
          if (on && sdt > 0) {
            p.position.x = base.p.x + (Math.random() - 0.5) * (A.amp || 0.01)
            p.position.z = base.p.z + (Math.random() - 0.5) * (A.amp || 0.01)
          }
        } else if (A.kind === 'fill' || A.kind === 'fillY') {
          // a gauge or a heap of goods following how full the station is
          const F = this.fillNow(dt)
          if (A.kind === 'fill') p.scale[ax] = Math.max(0.002, F.k)
          else {
            p.position.y = base.p.y + F.k * (A.amp || 1)
            p.visible = F.k > 0.01
          }
          if (A.tint) this.tint(A.name, p, F.color)
        } else if (A.kind === 'grow') {
          // crops follow the production cycle; the plot looks bare only when idle and empty
          const D = STATIONS[st.type]
          const g = st.level > 0 ? 0.55 + 0.45 * clamp(st.progress || 0, 0, 1) : 0.4
          const k = workersOf(st).length || st.module ? g : 0.75
          p.scale.setScalar(base.s.x * k)
        }
      }
    }
    // hens and goats wander their run, and go in at night
    if (I.pen && this.model) {
      this.animals ??= new AnimalPen(this)
      this.penT = (this.penT ?? 0) - dt
      if (this.penT <= 0) {
        this.penT = 1
        this.animals.sync()
      }
      this.animals.update(sdt, ctx)
    }
    // a store's stockpiles follow the camp's stock
    if (I.stock?.length && this.model) {
      this.stockT = (this.stockT ?? 0) - dt
      if (this.stockT <= 0) {
        this.stockT = 1
        syncPiles(this, I.stock, slotGroups(st))
      }
    }
    // smoke, steam, sparks
    if (ctx.nearCam(this.cx, this.cz) && !st.building) this.emit(sdt, ctx, I, run)
    const picked = ctx.selected === st.id || !!ctx.multi?.has(st.id)
    this.updateLabel(ctx.hovered === st.id, picked)
    // a gold outline on the ground round everything in a multi-selection
    const ring = !!ctx.multi?.has(st.id)
    if (ring && !this.selRing) this.makeSelRing()
    if (this.selRing) this.selRing.visible = ring
  }
  // How full a hopper's tank is (eased, once a frame), and the colour of
  // what's in it.
  fillNow(dt) {
    if (this.fillFrame === this.t) return this.fillState
    this.fillFrame = this.t
    const st = this.st
    let f = 0
    let color = '#8a8478'
    if (nodeKind(st) === 'hopper') {
      const k = nodeRes(st)
      if (k) {
        f = clamp((st.buf?.out?.[k] || 0) / Math.max(1, inCap(st, k)), 0, 1)
        color = RES[k].color
      }
    }
    const S0 = (this.fillState ??= { k: f, color })
    S0.k += (f - S0.k) * Math.min(1, dt * 2.5)
    S0.color = color
    return S0
  }
  // Give a pivot its own materials (once) and colour them.
  tint(name, p, color) {
    this.tints ??= {}
    let T = this.tints[name]
    if (!T) {
      T = this.tints[name] = { mats: [], color: null }
      p.traverse((o) => {
        if (!o.isMesh) return
        o.material = cloneMat(o.material)
        T.mats.push(o.material)
      })
    }
    if (T.color === color) return
    T.color = color
    for (const m of T.mats) {
      m.color.set(color)
      if (m.emissive && m.emissiveIntensity > 0) m.emissive.set(color)
    }
  }
  emit(dt, ctx, I, run = 1) {
    const fx = this.base.fx
    for (let i = 0; i < (I.emitters || []).length; i++) {
      const E = I.emitters[i]
      const k = E.when === 'always' ? 1 : E.when === 'night' ? (ctx.night > 0.2 ? 1 : 0) : run
      if (k < 0.05) continue
      const rate = { campfire: 9, smoke: 2.4, chimney: 2, steam: 2.2, exhaust: 4, sparks: 3, embers: 4, mist: 6, sawdust: 6, dust: 3, weld: 5, solder: 1.2 }[E.kind] || 2
      this.emitAcc[i] = (this.emitAcc[i] || 0) + dt * rate * k
      while (this.emitAcc[i] >= 1) {
        this.emitAcc[i] -= 1
        const p = this.toWorld(E.x, E.z, E.y)
        const v = new THREE.Vector3(p.x, p.y, p.z)
        switch (E.kind) {
          case 'campfire':
            fx.ember(v, 0.4)
            if (Math.random() < 0.35) fx.smoke(v.clone().setY(p.y + 1.0), { size: 0.8, life: 4, color: '#5a5450', a: 0.22, vy: 1 })
            break
          case 'smoke':
          case 'chimney':
            fx.smoke(v, { size: E.kind === 'chimney' ? 0.6 : 0.7, life: 4.5, color: '#6a6660', a: 0.3, vy: 0.9, spread: 0.15 })
            break
          case 'exhaust':
            fx.smoke(v, { size: 0.35, life: 1.8, color: '#4a4846', a: 0.35, vy: 0.7, spread: 0.08 })
            break
          case 'steam':
            fx.smoke(v, { size: 0.45, life: 2.2, color: '#e8e8e8', a: 0.22, vy: 1.1, spread: 0.12 })
            break
          case 'sparks':
          case 'weld':
            fx.sparks(v, E.kind === 'weld' ? 3 : 4, E.kind === 'weld' ? '#a8d8ff' : '#ffc860')
            break
          case 'embers':
            fx.ember(v, 0.3)
            break
          case 'mist':
            fx.smoke(v, { size: 0.5, life: 1.2, color: '#d8e8f0', a: 0.12, vy: -0.2, spread: 1.2 })
            break
          case 'sawdust':
            fx.dust(v, 1, '#e8c890')
            break
          case 'dust':
            fx.dust(v, 1, '#a89a80')
            break
          case 'solder':
            fx.smoke(v, { size: 0.12, life: 1.5, color: '#d8d8d8', a: 0.3, vy: 0.4, spread: 0.02 })
            break
        }
      }
    }
  }
  dispose() {
    this.base.scene.remove(this.group)
    this.label.remove()
  }
}

function orderLabel(o) {
  if (o.kind === 'recipe') {
    const r = RECIPES.find((x) => x.id === o.recipe)
    if (!r) return 'Crafting'
    return 'Making ' + (r.item ? ITEMS[r.item].name : RES[Object.keys(r.out)[0]].name)
  }
  if (o.kind === 'mod') return `Fitting ${MODS[o.mod]?.name || 'mod'}`
  return o.maint ? 'Maintenance' : 'Repairing'
}
