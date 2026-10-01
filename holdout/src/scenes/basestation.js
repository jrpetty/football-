// A station as it appears in the camp: the model for its level (or a blueprint
// ghost and scaffolding while it's being built), fading roofs, animated parts,
// flames, smoke and steam, light anchors and a status label.
import * as THREE from 'three'
import { stationModel, scaffold } from '../models/stations.js'
import { makeFlame } from '../render/fx.js'
import { STATIONS, RES, RECIPES, MODS, ITEMS } from '../game/data.js'
import { S, stationSize, workersOf, itemOf, itemName } from '../game/state.js'
import { view } from '../render/view.js'
import { h, clamp, rand } from '../core/util.js'

const EMPTY = { lights: [], emitters: [], flames: [], spots: [], beds: [], seats: [], anims: [], blink: [], roofs: [] }
const BLUEPRINT = new THREE.MeshStandardMaterial({ color: '#9ad0ff', emissive: '#4aa0ff', emissiveIntensity: 0.6, transparent: true, opacity: 0.22, depthWrite: false, roughness: 0.4 })
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
          o.material = o.material.clone()
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
    if (st.stalled && !st.building && st.stalled !== 'No workers') warn = st.stalled
    if ((hovered || selected) && !name) name = `${D.name}${st.level ? ` · L${st.level}` : ''}`
    if (this.lName.textContent !== name) this.lName.textContent = name
    this.lBar.style.display = bar == null ? 'none' : ''
    if (bar != null) this.lBar.firstChild.style.width = `${clamp(bar, 0, 1) * 100}%`
    if (this.lWarn.textContent !== warn) this.lWarn.textContent = warn
    this.label.el.className = `slabel wl-inner ${cls}${!name && !warn && bar == null ? ' empty' : ''}${selected ? ' sel' : ''}`
  }
  // ---- per-frame
  update(dt, ctx) {
    this.t += dt
    const st = this.st
    const I = this.info
    const active = !!st.active
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
    for (const f of this.flames) f.mesh.visible = f.when === 'always' || active
    // animated parts
    if (this.model) {
      for (const A of I.anims || []) {
        const p = this.pivots[A.name]
        if (!p) continue
        const base = this.baseRot[A.name]
        const on = A.when === 'always' || (A.when === 'night' ? ctx.night > 0.3 : active)
        const ax = A.axis || 'y'
        if (A.kind === 'spin') {
          if (on) p.rotation[ax] += dt * (A.speed || 1)
        } else if (A.kind === 'yaw') {
          if (on) p.rotation.y = base.r.y + Math.sin(t * (A.speed || 0.3)) * (A.swing || 1)
        } else if (A.kind === 'press') {
          p.position[ax] = base.p[ax] - (on ? Math.pow(Math.abs(Math.sin(t * (A.speed || 1) * Math.PI)), 3) * (A.amp || 0.3) : 0)
        } else if (A.kind === 'pump') {
          const target = on ? Math.sin(t * (A.speed || 3)) * (A.amp || 0.3) : 0
          p.rotation[ax] = base.r[ax] + target
        } else if (A.kind === 'slide') {
          if (on) p.position[ax] = base.p[ax] + Math.sin(t * (A.speed || 1) * Math.PI * 2) * (A.amp || 0.1)
        } else if (A.kind === 'shake') {
          if (on) {
            p.position.x = base.p.x + (Math.random() - 0.5) * (A.amp || 0.01)
            p.position.z = base.p.z + (Math.random() - 0.5) * (A.amp || 0.01)
          }
        } else if (A.kind === 'grow') {
          // crops follow the production cycle; the plot looks bare only when idle and empty
          const D = STATIONS[st.type]
          const g = st.level > 0 ? 0.55 + 0.45 * clamp(st.progress || 0, 0, 1) : 0.4
          const k = workersOf(st).length || st.module ? g : 0.75
          p.scale.setScalar(base.s.x * k)
        }
      }
    }
    // smoke, steam, sparks
    if (ctx.nearCam(this.cx, this.cz) && !st.building) this.emit(dt, ctx, I)
    this.updateLabel(ctx.hovered === st.id, ctx.selected === st.id)
  }
  emit(dt, ctx, I) {
    const fx = this.base.fx
    const active = !!this.st.active
    for (let i = 0; i < (I.emitters || []).length; i++) {
      const E = I.emitters[i]
      const on = E.when === 'always' || (E.when === 'night' ? ctx.night > 0.2 : active)
      if (!on) continue
      const rate = { campfire: 9, smoke: 2.4, chimney: 2, steam: 2.2, exhaust: 4, sparks: 3, embers: 4, mist: 6, sawdust: 6, dust: 3, weld: 5, solder: 1.2 }[E.kind] || 2
      this.emitAcc[i] = (this.emitAcc[i] || 0) + dt * rate
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
