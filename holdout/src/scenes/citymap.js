// The city map: the whole town as a living 3D model at true scale, lit by
// the camp's clock. Streets with markings and crossings, a river with
// embankments and bridges, the rail yard, the highway jammed with the
// evacuation that never got out, burning blocks, farms and forest around it.
// Pick a location (its pin shows the danger level), pick a squad, roll out.
import * as THREE from 'three'
import { view, screenRay } from '../render/view.js'
import { Atmosphere, nightFactor } from '../render/sky.js'
import { FX, makeFlame, tickFlames, ringTex } from '../render/fx.js'
import { Splat, Terrain, wind } from '../render/terrain.js'
import { mat, setNightGlow } from '../render/materials.js'
import { texSet } from '../render/texgen.js'
import { Builder, seeded } from '../models/kit.js'
import { InstSet, PropList, mapTreeModel, mapVehicleModel, mapPropModel, CARS, LEAF, NEEDLE, FOREST, FOREST_FIR, signPlane, addSign } from '../models/citykit.js'
import * as CB from '../models/citybuildings.js'
import { locationModel } from '../models/citylandmarks.js'
import { gableRoof } from '../models/parts.js'
import { SIDEWALK, RIVER_W, HIGHWAY_Z, HIGHWAY_W, FACE_ROT, route } from '../world/city.js'
import { S, hour } from '../game/state.js'
import { LEVEL_COLORS } from '../game/data.js'
import { MapPanel } from '../ui/mappanel.js'
import { clamp, smooth } from '../core/util.js'

const TAU = Math.PI * 2
const WATER_Y = -2.0
const CHUNK = 320
const pick = (r, a) => a[Math.floor(r() * a.length)]

// Vehicle and prop templates for instancing: key -> [factory, tint keys]
const VEH = ['sedan', 'hatch', 'suv', 'pickup', 'van', 'truck', 'semi', 'bus', 'schoolbus', 'police', 'ambulance', 'firetruck', 'humvee', 'mtruck', 'heli']
const PROPS = ['bale', 'booth', 'lamp', 'signal', 'hydrant', 'bench', 'trash', 'mailbox', 'busstop', 'pole', 'poleT', 'barrier', 'sandbags', 'hesco', 'policeline', 'container', 'dumpster', 'pallets', 'tires', 'rubble', 'tent', 'tomb', 'cross', 'billboard', 'swing', 'boat', 'boxcar', 'tanker', 'flatcar', 'loco', 'tie', 'crane', 'pylon', 'car-pile']
const TINTED = { container: ['corrugated'], dumpster: ['paint'], mailbox: ['paint'], tent: ['canvas'], boxcar: ['corrugated'], tanker: ['paint'], flatcar: ['corrugated'], loco: ['paint'] }

export class CityMap {
  constructor(game) {
    this.game = game
    this.city = game.city
    this.scene = new THREE.Scene()
    this.isOpen = false
    this.t = 0
    this.sel = null
    this.hover = null
    this.locs = new Map()
    this.chunks = new Map()
    this.P = new PropList()
    this.wires = []
    this.lightPool = []
    this.build()
  }

  // ---------------------------------------------------------------- build
  chunk(x, z) {
    const k = Math.floor(x / CHUNK) + ',' + Math.floor(z / CHUNK)
    let b = this.chunks.get(k)
    if (!b) {
      b = new Builder()
      this.chunks.set(k, b)
    }
    return b
  }
  build() {
    const c = this.city
    const Q = this.game.pipe.Q
    this.atmo = new Atmosphere(this.scene, this.game.pipe.renderer, { shadowSize: Math.max(2048, Q.shadow), shadowRange: [90, 820], sunDist: 1100, shadowFar: 2600 })
    this.atmo.sun.shadow.bias = -0.0006
    this.atmo.sun.shadow.normalBias = 0.5
    this.fx = new FX(this.scene)
    this.cx = c.W / 2
    this.cz = c.H / 2 + 60
    const T = []
    let t0 = performance.now()
    const lap = (name) => {
      const t = performance.now()
      T.push(`${name} ${Math.round(t - t0)}`)
      t0 = t
    }
    this.buildGround()
    lap('ground')
    this.buildWater()
    this.buildRoads()
    this.buildHighway()
    this.buildRail()
    this.buildBridges()
    lap('streets')
    this.buildLots()
    lap('lots')
    this.buildStreetLife()
    this.buildCountryside()
    this.buildCamp()
    lap('life')
    this.splat.upload()
    for (const [, b] of this.chunks) {
      const g = b.build({ index: false })
      g.traverse((o) => o.isMesh && (o.matrixAutoUpdate = false))
      this.scene.add(g)
    }
    this.chunks.clear()
    lap('merge')
    this.buildInstances()
    this.buildWires()
    this.buildMarkers()
    this.buildFires()
    lap('inst')
    console.info('city map build', T.join(' · '))
  }

  // terrain: flat city, a carved river channel, rolling country around
  heightAt(x, z) {
    const c = this.city
    const rz = c.river.z(x)
    const dr = Math.abs(z - rz)
    const half = RIVER_W / 2
    let h = 0
    if (dr < half + 7) h = -4.4 * smooth(clamp((half + 7 - dr) / 11, 0, 1))
    const dx = Math.max(-70 - x, 0, x - (c.W + 70))
    const dz = Math.max(-150 - z, 0, z - (c.H + 50))
    const d = Math.hypot(dx, dz)
    if (d > 0) {
      let flat = 0
      flat = Math.max(flat, 1 - clamp((Math.abs(z - HIGHWAY_Z) - 24) / 60, 0, 1))
      flat = Math.max(flat, 1 - clamp((Math.abs(x - c.rail.x) - 14) / 50, 0, 1))
      flat = Math.max(flat, 1 - clamp((Math.hypot(x - c.camp.x, z - c.camp.z) - 70) / 90, 0, 1))
      flat = Math.max(flat, 1 - clamp((Math.abs(x - c.xs[1]) - 10) / 40, 0, 1) * (z > c.H ? 1 : 0))
      flat = Math.max(flat, 1 - clamp((dr - half - 10) / 60, 0, 1))
      const hills = (Math.sin(x * 0.0041 + 1.3) * Math.cos(z * 0.0052 - 0.4) * 0.5 + 0.5) * 30 + Math.sin(x * 0.011 + z * 0.007) * 6 + Math.sin(z * 0.019 - x * 0.004) * 3
      h += smooth(clamp(d / 260, 0, 1)) * hills * (1 - flat)
    }
    return h
  }
  forestAt(x, z) {
    return Math.sin(x * 0.0071 + 2.1) * Math.cos(z * 0.0063 + 0.7) * 0.6 + Math.sin(x * 0.019 - z * 0.013) * 0.3 + Math.sin(z * 0.031 + x * 0.004) * 0.15
  }
  inCity(x, z, m = 0) {
    return x > -40 - m && x < this.city.W + 40 + m && z > -60 - m && z < this.city.H + 30 + m
  }
  buildGround() {
    const c = this.city
    const size = 4400
    this.splat = new Splat(this.cx - size / 2, this.cz - size / 2, size, 4.4)
    const sp = this.splat
    // patchy, overgrown yards and verges in town
    for (const blk of c.blocks) {
      if (blk.district !== 'residential' && blk.district !== 'park') continue
      for (let k = 0; k < Math.floor(((blk.x1 - blk.x0) * (blk.z1 - blk.z0)) / 600); k++) {
        const x = blk.x0 + Math.random() * (blk.x1 - blk.x0)
        const z = blk.z0 + Math.random() * (blk.z1 - blk.z0)
        sp.circle(x, z, 3 + Math.random() * 7, Math.random() < 0.6 ? 2 : 0, 0.5 + Math.random() * 0.3, 4, 2)
      }
    }
    // rail corridor gravel
    sp.rect(c.rail.x - 6, -1500, c.rail.x + 6, c.H + 1500, 1, 1, 2, 0.5)
    sp.rect(c.rail.x - c.rail.yard.half, c.rail.yard.z0, c.rail.x + c.rail.yard.half, c.rail.yard.z1, 1, 1, 3, 0.6)
    // camp clearing and its track
    sp.circle(c.camp.x, c.camp.z, 52, 0, 1, 8, 2)
    sp.rect(c.camp.gate.x, c.camp.z - 4, c.xs[1] + 4, c.camp.z + 4, 0, 1, 2, 0.6)
    sp.rect(c.xs[1] - 4, c.H - 40, c.xs[1] + 4, c.camp.z, 0, 0.9, 2, 0.6)
    this.terrain = new Terrain(this.scene, { cx: this.cx, cz: this.cz, size, segs: 440, splat: sp, height: (x, z) => this.heightAt(x, z) })
    // late-summer lawns gone to seed, not golf greens
    const tu = this.terrain.material.userData.uniforms
    tu.uLush.value.set('#a9b48c')
    tu.uDry.value.set('#c4b48a')
    this.terrain.mesh.matrixAutoUpdate = false
  }
  buildWater() {
    const c = this.city
    const pts = []
    for (let x = c.river.x0; x <= c.river.x1; x += 8) pts.push(x)
    const pos = []
    const uv = []
    const idx = []
    const hw = RIVER_W / 2 + 10
    pts.forEach((x, k) => {
      const rz = c.river.z(x)
      pos.push(x, WATER_Y, rz - hw, x, WATER_Y, rz + hw)
      uv.push(x / 30, 0, x / 30, (hw * 2) / 30)
      if (k > 0) {
        const a = (k - 1) * 2
        idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2)
      }
    })
    const g = new THREE.BufferGeometry()
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2))
    g.setIndex(idx)
    g.computeVertexNormals()
    const n = texSet('noise').normalMap.clone()
    n.wrapS = n.wrapT = THREE.RepeatWrapping
    n.needsUpdate = true
    this.waterNormal = n
    const m = new THREE.MeshStandardMaterial({ color: '#2e4a50', roughness: 0.07, metalness: 0.25, normalMap: n, normalScale: new THREE.Vector2(0.5, 0.5), transparent: true, opacity: 0.94 })
    const water = new THREE.Mesh(g, m)
    water.receiveShadow = true
    water.renderOrder = 1
    this.scene.add(water)
    // concrete embankments through town, with railings on top
    const x0 = c.xs[0] - 40
    const x1 = c.W + 40
    for (const side of [-1, 1]) {
      const wpos = []
      const widx = []
      const wuv = []
      let k = 0
      for (let x = x0; x <= x1; x += 6, k++) {
        const z = c.river.z(x) + side * (RIVER_W / 2 + 1)
        wpos.push(x, -3.2, z, x, 0.35, z)
        wuv.push(x / 3, 0, x / 3, 1.2)
        if (k > 0) {
          const a = (k - 1) * 2
          if (side < 0) widx.push(a, a + 2, a + 1, a + 1, a + 2, a + 3)
          else widx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2)
        }
      }
      const wg = new THREE.BufferGeometry()
      wg.setAttribute('position', new THREE.Float32BufferAttribute(wpos, 3))
      wg.setAttribute('uv', new THREE.Float32BufferAttribute(wuv, 2))
      wg.setIndex(widx)
      wg.computeVertexNormals()
      const cols = new Float32Array(wpos.length).fill(0.78)
      wg.setAttribute('color', new THREE.Float32BufferAttribute(cols, 3))
      const wall = new THREE.Mesh(wg, mat('concrete'))
      wall.receiveShadow = true
      this.scene.add(wall)
      for (let x = x0; x < x1; x += 12) {
        const za = c.river.z(x) + side * (RIVER_W / 2 + 1.4)
        const zb = c.river.z(x + 12) + side * (RIVER_W / 2 + 1.4)
        const b = this.chunk(x, za)
        b.beam([x, 1.3, za], [x + 12, 1.3, zb], 0.08, 0.08, { mat: 'paint', color: '#3a3e40' })
        b.box(0.1, 1.0, 0.1, { mat: 'paint', color: '#3a3e40', x, y: 0.85, z: za })
        // promenade
        b.beam([x, 0.04, za + side * 3.4], [x + 12, 0.04, zb + side * 3.4], 6.4, 0.08, { mat: 'pavers', color: '#c0b8a8', shadow: false, extend: 0.3 })
      }
    }
  }

  // ---------------------------------------------------------------- streets
  buildRoads() {
    const c = this.city
    const R = seeded(c.seed + 101)
    const asphalt = { mat: 'road', color: '#b8b6b0', shadow: false, ao: 0 }
    const paint = (color) => ({ mat: 'plain', color, shadow: false, ao: 0 })
    const white = paint('#bdb9ae')
    const yellow = paint('#b8963a')
    const vW = (i) => (c.avX(i) ? 20 : 13)
    const hW = (j) => (c.avZ(j) ? 20 : 13)
    const nodeOn = (i, j) => {
      const e = [c.hE[j]?.[i - 1], c.hE[j]?.[i], c.vE[i]?.[j - 1], c.vE[i]?.[j]].filter((x) => x && x.on)
      return e.length
    }
    // intersections
    for (let j = 0; j < c.NZ; j++)
      for (let i = 0; i < c.NX; i++) {
        if (!nodeOn(i, j)) continue
        const w = i === c.iR ? 13 : vW(i)
        this.chunk(c.xs[i], c.zs[j]).box(w, 0.12, hW(j), { ...asphalt, x: c.xs[i], y: 0, z: c.zs[j] })
      }
    const cracks = (b, x0, z0, x1, z1, horiz) => {
      const len = horiz ? x1 - x0 : z1 - z0
      const n = Math.floor(len / 22)
      for (let k = 0; k < n; k++) {
        const t = R()
        const x = horiz ? x0 + t * len : x0 + (x1 - x0) * R()
        const z = horiz ? z0 + (z1 - z0) * R() : z0 + t * len
        const r = R()
        if (r < 0.5) b.box(0.6 + R() * 2.4, 0.02, 0.6 + R() * 1.8, { mat: 'road', color: '#7a7874', x, y: 0.07, z, ry: R() * TAU, shadow: false, ao: 0 })
        else if (r < 0.62) b.cyl(0.45, 0.45, 0.02, { mat: 'metal', color: '#4a4a48', x, y: 0.07, z, seg: 10, shadow: false })
        else if (r < 0.8) b.box(0.12, 0.02, 3 + R() * 6, { mat: 'road', color: '#4a4846', x, y: 0.07, z, ry: R() * TAU, shadow: false, ao: 0 })
      }
    }
    // a straight road piece between two intersections, with its markings
    const piece = (horiz, a0, a1, at, w, avenue, iA, iB, opts = {}) => {
      const mid = (a0 + a1) / 2
      const len = a1 - a0
      const b = horiz ? this.chunk(mid, at) : this.chunk(at, mid)
      const r0 = a0 + (iA ?? 0)
      const r1 = a1 - (iB ?? 0)
      if (r1 - r0 > 0.1) {
        if (horiz) b.box(r1 - r0, 0.12, w, { ...asphalt, x: (r0 + r1) / 2, y: 0, z: at })
        else b.box(w, 0.12, r1 - r0, { ...asphalt, x: at, y: 0, z: (r0 + r1) / 2 })
      }
      // markings stop short of the junctions
      const s0 = a0 + (iA ?? 0) + 2.5
      const s1 = a1 - (iB ?? 0) - 2.5
      if (s1 - s0 < 4) return
      const L = s1 - s0
      const C = (s0 + s1) / 2
      const line = (off, col, dashed) => {
        if (!dashed) {
          if (horiz) b.box(L, 0.02, 0.14, { ...col, x: C, y: 0.075, z: at + off })
          else b.box(0.14, 0.02, L, { ...col, x: at + off, y: 0.075, z: C })
          return
        }
        for (let p = s0; p < s1 - 3; p += 9) {
          if (horiz) b.box(3, 0.02, 0.13, { ...col, x: p + 1.5, y: 0.075, z: at + off })
          else b.box(0.13, 0.02, 3, { ...col, x: at + off, y: 0.075, z: p + 1.5 })
        }
      }
      if (avenue) {
        line(-0.2, yellow, false)
        line(0.2, yellow, false)
        line(-w / 4, white, true)
        line(w / 4, white, true)
      } else line(0, opts.downtown ? white : yellow, true)
      // crosswalks and stop lines at both ends
      if (avenue || opts.downtown) {
        for (const [p, dir] of [
          [a0 + (iA ?? 0) + 1.6, 1],
          [a1 - (iB ?? 0) - 1.6, -1],
        ]) {
          for (let q = -w / 2 + 1; q < w / 2 - 0.6; q += 1.1) {
            if (horiz) b.box(2.6, 0.02, 0.55, { ...white, x: p, y: 0.075, z: at + q })
            else b.box(0.55, 0.02, 2.6, { ...white, x: at + q, y: 0.075, z: p })
          }
          if (horiz) b.box(0.3, 0.02, w / 2 - 0.6, { ...white, x: p + dir * 2.2, y: 0.075, z: at + (dir > 0 ? w / 4 : -w / 4) })
          else b.box(w / 2 - 0.6, 0.02, 0.3, { ...white, x: at + (dir > 0 ? -w / 4 : w / 4), y: 0.075, z: p + dir * 2.2 })
        }
      }
      if (horiz) cracks(b, a0, at - w / 2 + 1, a1, at + w / 2 - 1, true)
      else cracks(b, at - w / 2 + 1, a0, at + w / 2 - 1, a1, false)
    }
    const dtNear = (x, z) => Math.hypot(x - c.downtown.x, z - c.downtown.z) < 330
    for (const row of c.hE)
      for (const e of row) {
        if (!e.on) continue
        piece(true, e.x0, e.x1, e.z, e.w, e.kind === 'avenue', (e.i === c.iR ? 13 : vW(e.i)) / 2, (e.i + 1 === c.iR ? 13 : vW(e.i + 1)) / 2, { downtown: dtNear((e.x0 + e.x1) / 2, e.z) })
      }
    for (const col of c.vE)
      for (const e of col) {
        if (e.on && !e.bridge) piece(false, e.z0, e.z1, e.x, e.w, e.kind === 'avenue', hW(e.j) / 2, hW(e.j + 1) / 2, { downtown: dtNear(e.x, (e.z0 + e.z1) / 2) })
        if (e.bridge) {
          piece(false, e.z0, e.bridge.z0, e.x, e.w, e.kind === 'avenue', hW(e.j) / 2, 0)
          piece(false, e.bridge.z1, e.z1, e.x, e.w, e.kind === 'avenue', 0, hW(e.j + 1) / 2)
        }
        if (e.stubs) {
          for (const [a, bnd] of e.stubs) {
            piece(false, a, bnd, e.x, e.w, false, a === e.z0 ? hW(e.j) / 2 : 0, a === e.z0 ? 0 : hW(e.j + 1) / 2)
            // dead end barriers at the bank
            const zEnd = a === e.z0 ? bnd - 1 : a + 1
            for (let q = -e.w / 2 + 1.5; q < e.w / 2 - 1; q += 3.1) this.P.add('barrier', e.x + q, zEnd, { ry: Math.PI / 2 })
          }
        }
      }
    // sidewalks
    for (const blk of c.blocks) {
      const walk = (x0, z0, x1, z1) => {
        const b = this.chunk((x0 + x1) / 2, (z0 + z1) / 2)
        b.box(x1 - x0, 0.2, z1 - z0, { mat: blk.district === 'downtown' ? 'pavers' : 'concrete', color: blk.district === 'downtown' ? '#b0a89a' : '#a8a49c', x: (x0 + x1) / 2, y: 0.06, z: (z0 + z1) / 2, shadow: false, ao: 0 })
      }
      const W = blk.walk
      if (W.n) walk(blk.ox0, blk.oz0, blk.ox1, blk.oz0 + SIDEWALK)
      if (W.s) walk(blk.ox0, blk.oz1 - SIDEWALK, blk.ox1, blk.oz1)
      if (W.w) walk(blk.ox0, blk.oz0, blk.ox0 + SIDEWALK, blk.oz1)
      if (W.e) walk(blk.ox1 - SIDEWALK, blk.oz0, blk.ox1, blk.oz1)
      // paved lots for commerce and industry
      if (blk.district === 'commercial' || blk.district === 'downtown') this.chunk(blk.cx, blk.cz).box(blk.x1 - blk.x0, 0.06, blk.z1 - blk.z0, { mat: 'concrete', color: '#9c988f', x: blk.cx, y: 0.02, z: blk.cz, shadow: false, ao: 0 })
      if (blk.district === 'industrial') this.chunk(blk.cx, blk.cz).box(blk.x1 - blk.x0, 0.06, blk.z1 - blk.z0, { mat: 'concrete', color: '#9e9a92', x: blk.cx, y: 0.02, z: blk.cz, shadow: false, ao: 0 })
    }
    // the dirt track home
    const tb = this.chunk(c.xs[1], c.H + 60)
    tb.box(7, 0.1, c.camp.z - c.zs[c.NZ - 1] - 6, { mat: 'dirt', color: '#d8c8a8', x: c.xs[1], y: 0, z: (c.camp.z + c.zs[c.NZ - 1]) / 2, shadow: false, ao: 0 })
    tb.box(Math.abs(c.xs[1] - c.camp.gate.x) + 4, 0.1, 7, { mat: 'dirt', color: '#d8c8a8', x: (c.xs[1] + c.camp.gate.x) / 2, y: 0, z: c.camp.z, shadow: false, ao: 0 })
  }
  buildHighway() {
    const c = this.city
    const R = seeded(c.seed + 77)
    const x0 = c.highway.x0
    const x1 = c.highway.x1
    const z = HIGHWAY_Z
    const w = HIGHWAY_W
    for (let x = x0; x < x1; x += 200) {
      const len = Math.min(200, x1 - x)
      const b = this.chunk(x + len / 2, z)
      b.box(len, 0.12, w, { mat: 'road', color: '#a8a6a0', x: x + len / 2, y: 0.0, z, shadow: false, ao: 0 })
      b.box(len, 0.85, 0.5, { mat: 'concrete', color: '#c4c0b6', x: x + len / 2, y: 0.45, z })
      for (const s of [-1, 1]) {
        b.box(len, 0.02, 0.18, { mat: 'plain', color: '#dcd8cc', x: x + len / 2, y: 0.07, z: z + s * (w / 2 - 1.2), shadow: false })
        b.box(len, 0.3, 0.12, { mat: 'steel', color: '#a8acb0', x: x + len / 2, y: 0.75, z: z + s * (w / 2 + 0.4) })
        for (let p = x; p < x + len - 4; p += 4) b.box(0.1, 0.75, 0.1, { mat: 'steel', color: '#8a8e90', x: p, y: 0.38, z: z + s * (w / 2 + 0.4) })
        for (let p = x; p < x + len - 3; p += 12) b.box(3, 0.02, 0.15, { mat: 'plain', color: '#dcd8cc', x: p + 1.5, y: 0.07, z: z + s * (w / 4 + 0.2), shadow: false })
      }
    }
    // avenue ramps
    for (let i = 0; i < c.NX; i++) {
      if (!c.avX(i)) continue
      const z0 = z + w / 2 + 0.4
      const z1 = c.zs[0] - 10
      this.chunk(c.xs[i], (z0 + z1) / 2).box(20, 0.12, z1 - z0, { mat: 'road', color: '#b8b6b0', x: c.xs[i], y: 0, z: (z0 + z1) / 2, shadow: false, ao: 0 })
    }
    // overhead sign gantry
    for (const gx of [c.xs[1] - 80, c.W * 0.6]) {
      const b = this.chunk(gx, z)
      for (const s of [-1, 1]) b.box(0.5, 7.5, 0.5, { mat: 'steel', color: '#8a8e90', x: gx, y: 3.75, z: z + s * (w / 2 + 1) })
      b.box(0.6, 0.6, w + 3, { mat: 'steel', color: '#8a8e90', x: gx, y: 7.4, z })
      for (const s of [-1, 1]) {
        b.box(0.2, 2.6, 7, { mat: 'paint', color: '#2a6a3a', x: gx, y: 6.0, z: z + s * 5.5 })
        b.at({ x: gx + 0.12, z: z + s * 5.5, ry: Math.PI / 2 }, () => signPlane(b, addSign(s < 0 ? 'ASHFORD  EXIT 12' : 'CITY CENTRE', '#2a6a3a', '#ffffff'), 6.4, 1.6, { y: 6.1 }))
      }
    }
    // the jam: the evacuation stalled at the checkpoint
    for (let x = c.W - 520; x < c.highway.exit - 30; x += 6.2 + R() * 2) {
      for (const lane of [1, 2]) {
        if (R() < 0.18) continue
        const zz = z + (lane === 1 ? 3.5 : 9) + (R() - 0.5) * 0.8
        const k = R()
        const kind = k < 0.07 ? 'semi' : k < 0.12 ? 'bus' : k < 0.2 ? 'truck' : pick(R, ['sedan', 'sedan', 'hatch', 'suv', 'suv', 'pickup', 'van'])
        const burnt = R() < 0.08
        this.P.add(burnt ? 'car-burnt' : 'car-' + kind, x, zz, { ry: Math.PI / 2 + (R() - 0.5) * 0.25, c: pick(R, CARS) })
        if (kind === 'semi' || kind === 'bus') x += 10
      }
    }
    // the checkpoint: barriers across every lane, a booth and wire
    const cx = c.highway.exit - 64
    for (let zz = z - w / 2 + 1.5; zz < z + w / 2; zz += 3.1) if (Math.abs(zz - z) > 2) this.P.add(R() < 0.7 ? 'barrier' : 'hesco', cx + (R() - 0.5) * 0.6, zz, { ry: 0 })
    this.P.add('booth', cx + 6, z - 4, { ry: Math.PI / 2 })
    for (let zz = z - w / 2; zz < z + w / 2; zz += 2.4) this.P.add('sandbags', cx - 4, zz, { ry: Math.PI / 2 })
    for (let x = c.xs[0]; x < c.W - 520; x += 30 + R() * 60) this.P.add(R() < 0.2 ? 'car-burnt' : 'car-' + pick(R, ['sedan', 'suv', 'hatch', 'pickup']), x, z + (R() < 0.5 ? -1 : 1) * (3 + R() * 7), { ry: (R() < 0.5 ? 1 : -1) * Math.PI / 2 + (R() - 0.5) * 0.6, c: pick(R, CARS) })
    // billboards along the highway
    for (let k = 0; k < 5; k++) {
      const bx = -300 + k * ((c.W + 600) / 5) + R() * 80
      const bz = z - w / 2 - 14
      this.P.add('billboard', bx, bz, { ry: 0 })
      this.chunk(bx, bz).at({ x: bx, z: bz + 0.17 }, (b) => signPlane(b, addSign(pick(R, ['STAY INDOORS', 'EVACUATE NORTH', 'ASHFORD: A FINE PLACE', 'GRAND GROCER', 'JESUS SAVES', 'MERCY MEDICAL']), pick(R, ['#c8302a', '#2a4a7a', '#e8c040', '#2a6a3a']), '#ffffff'), 8.6, 3.2, { y: 8.2 }))
    }
  }
  buildRail() {
    const c = this.city
    const R = seeded(c.seed + 55)
    const rx = c.rail.x
    const tracks = (x, z0, z1) => {
      for (let z = z0; z < z1; z += 220) {
        const len = Math.min(220, z1 - z)
        const b = this.chunk(x, z + len / 2)
        b.box(4.2, 0.3, len, { mat: 'gravel', color: '#8a847a', x, y: 0.0, z: z + len / 2, shadow: false })
        for (const s of [-0.72, 0.72]) b.box(0.12, 0.18, len, { mat: 'steel', color: '#8a8480', x: x + s, y: 0.3, z: z + len / 2 })
      }
      for (let z = z0; z < z1; z += 0.9) this.P.add('tie', x, z, { y: 0.1, ry: 0 })
    }
    const outside = (z) => Math.abs(z - c.river.z(rx)) < RIVER_W / 2 + 20
    for (const off of [-2.4, 2.4]) {
      let z = -1400
      while (z < c.H + 1400) {
        let e = z + 40
        while (e < c.H + 1400 && !outside(e)) e += 40
        if (e > z + 40) tracks(rx + off, z, e - 40)
        z = e
        while (z < c.H + 1400 && outside(z)) z += 40
      }
    }
    // the yard: six parallel tracks with freight left standing
    const Y = c.rail.yard
    for (let k = 0; k < 6; k++) {
      const tx = rx - Y.half + 6 + k * ((Y.half * 2 - 12) / 5)
      if (Math.abs(tx - rx) < 4) continue
      tracks(tx, Y.z0, Y.z1)
      let z = Y.z0 + 8
      const train = R() < 0.75
      while (train && z < Y.z1 - 20) {
        const kind = z === Y.z0 + 8 && R() < 0.5 ? 'loco' : pick(R, ['boxcar', 'boxcar', 'tanker', 'flatcar'])
        const derail = R() < 0.04
        this.P.add(kind, tx, z + 8, { y: 0.2, ry: derail ? 0.4 : 0, rz: derail ? 0.5 : 0, c: kind === 'tanker' ? pick(R, ['#2a2c2e', '#c8c4bc', '#3a4a5a']) : kind === 'loco' ? pick(R, ['#2a4a8a', '#c8302a', '#e8a020']) : pick(R, ['#8a3a2a', '#6a4a3a', '#3a5a6a', '#8a7a5a', '#4a6a4a']) })
        z += kind === 'loco' ? 18 : 16
        if (R() < 0.12) z += 20
      }
    }
    // signal box and lamp masts
    const b = this.chunk(rx, (Y.z0 + Y.z1) / 2)
    b.box(6, 7, 5, { mat: 'brick', color: '#e8d8d0', x: rx + Y.half - 4, y: 3.5, z: Y.z0 + 10 })
    gableRoof(b, { w: 6, d: 5, y: 7, rise: 1.6, mat: 'shingles', color: '#4a4644', x: rx + Y.half - 4, z: Y.z0 + 10, gableMat: 'brick', gableColor: '#e8d8d0' })
    for (let z = Y.z0 + 20; z < Y.z1; z += 60) this.P.add('lamp', rx, z, { ry: R() * TAU, s: 1.6 })
  }
  buildBridges() {
    const c = this.city
    for (const br of c.bridges) {
      const b = this.chunk(br.x, (br.z0 + br.z1) / 2)
      const len = br.z1 - br.z0
      const zc = (br.z0 + br.z1) / 2
      if (br.kind === 'road') {
        b.box(br.w, 1.4, len, { mat: 'concrete', color: '#b8b4ac', x: br.x, y: -0.65, z: zc })
        b.box(br.w - 2 * SIDEWALK, 0.06, len, { mat: 'road', color: '#b8b6b0', x: br.x, y: 0.07, z: zc, shadow: false, ao: 0 })
        for (const s of [-1, 1]) {
          b.box(SIDEWALK, 0.22, len, { mat: 'concrete', color: '#c4c0b8', x: br.x + s * (br.w / 2 - SIDEWALK / 2), y: 0.11, z: zc })
          b.box(0.4, 1.1, len, { mat: 'concrete', color: '#c8c4bc', x: br.x + s * (br.w / 2 - 0.2), y: 0.75, z: zc })
          for (let z = br.z0 + 6; z < br.z1 - 4; z += 18) this.P.add('lamp', br.x + s * (br.w / 2 - 0.6), z, { ry: s > 0 ? -Math.PI / 2 : Math.PI / 2 })
        }
        for (let z = br.z0 + 3; z < br.z1; z += 1.2) b.box(0.02, 0.02, 0.6, { mat: 'plain', color: '#d0a830', x: br.x, y: 0.11, z, shadow: false })
        // girders and piers
        for (const s of [-0.3, 0.3]) b.box(1.0, 1.8, len, { mat: 'steel', color: '#5a6a72', x: br.x + s * br.w, y: -2.1, z: zc })
        for (const pz of [zc - len / 6, zc + len / 6]) {
          b.box(br.w * 0.8, 4.8, 3, { mat: 'concrete', color: '#a8a49c', x: br.x, y: -4.2, z: pz })
          b.box(br.w * 0.85, 0.6, 3.6, { mat: 'concrete', color: '#b8b4ac', x: br.x, y: -1.8, z: pz })
        }
      } else {
        // steel truss rail bridge
        b.box(br.w, 1.0, len, { mat: 'steel', color: '#5a5e60', x: br.x, y: -0.4, z: zc })
        for (const off of [-2.4, 2.4]) for (const s of [-0.72, 0.72]) b.box(0.12, 0.18, len, { mat: 'steel', color: '#8a8480', x: br.x + off + s, y: 0.2, z: zc })
        for (let z = br.z0; z < br.z1; z += 1) b.box(2.6, 0.12, 0.24, { mat: 'wood', color: '#5a4a3c', x: br.x - 2.4, y: 0.12, z })
        for (let z = br.z0; z < br.z1; z += 1) b.box(2.6, 0.12, 0.24, { mat: 'wood', color: '#5a4a3c', x: br.x + 2.4, y: 0.12, z })
        const H = 9
        const nb = Math.round(len / 9)
        const step = len / nb
        for (const s of [-1, 1]) {
          const x = br.x + s * (br.w / 2)
          b.box(0.6, 0.6, len, { mat: 'paint', color: '#4a5a62', x, y: H, z: zc })
          for (let k = 0; k <= nb; k++) {
            const z = br.z0 + k * step
            b.box(0.5, H, 0.5, { mat: 'paint', color: '#4a5a62', x, y: H / 2, z })
            if (k < nb) b.beam([x, 0.2, k % 2 ? z : z + step], [x, H, k % 2 ? z + step : z], 0.4, 0.4, { mat: 'paint', color: '#4a5a62' })
          }
        }
        for (let k = 0; k <= nb; k += 2) b.box(br.w, 0.4, 0.4, { mat: 'paint', color: '#4a5a62', x: br.x, y: H, z: br.z0 + k * step })
        for (const pz of [zc - len / 4, zc + len / 4]) b.box(br.w * 0.8, 4.6, 3, { mat: 'concrete', color: '#a8a49c', x: br.x, y: -3.2, z: pz })
      }
    }
  }

  // ---------------------------------------------------------------- parcels
  buildLots() {
    const c = this.city
    const P = this.P
    for (const lot of c.lots) {
      if (lot.taken) continue
      const r = seeded(lot.seed)
      if (lot.loc) {
        const b = new Builder()
        b.at({ x: lot.cx, z: lot.cz, ry: FACE_ROT[lot.face] }, () => locationModel(b, lot.loc, r, P))
        const g = b.build({ index: false })
        g.userData.pick = lot.loc
        this.scene.add(g)
        this.locs.set(lot.loc.id, { loc: lot.loc, group: g })
        continue
      }
      if (!lot.bld) continue
      const b = this.chunk(lot.cx, lot.cz)
      b.at({ x: lot.cx, z: lot.cz, ry: FACE_ROT[lot.face] }, () => CB.fillerLot(b, lot, r, this.P))
      this.paintLot(lot)
    }
  }
  paintLot(lot) {
    const k = lot.bld.kind
    const sp = this.splat
    if (k === 'empty') sp.rect(lot.x0 + 1, lot.z0 + 1, lot.x1 - 1, lot.z1 - 1, 0, 0.85, 3, 1.5)
    else if (lot.bld.state === 'burnt') sp.rect(lot.x0 + 2, lot.z0 + 2, lot.x1 - 2, lot.z1 - 2, 2, 0.7, 3, 1.5)
    else if (k === 'yard' || k === 'tanks') sp.rect(lot.x0, lot.z0, lot.x1, lot.z1, 1, 1, 2, 1)
  }
  // ---------------------------------------------------------------- street life
  buildStreetLife() {
    const c = this.city
    const R = seeded(c.seed + 303)
    const P = this.P
    const poles = []
    const nearNode = (x, z) => {
      for (const xx of c.xs) if (Math.abs(x - xx) < 14) for (const zz of c.zs) if (Math.abs(z - zz) < 14) return true
      return false
    }
    for (const blk of c.blocks) {
      const d = blk.district
      const W = blk.walk
      // sides: [x0,z0,x1,z1, outward normal x,z, road width]
      const sides = []
      if (W.n) sides.push([blk.ox0, blk.oz0 + 0.6, blk.ox1, blk.oz0 + 0.6, 0, -1, blk.roads.n])
      if (W.s) sides.push([blk.ox0, blk.oz1 - 0.6, blk.ox1, blk.oz1 - 0.6, 0, 1, blk.roads.s])
      if (W.w) sides.push([blk.ox0 + 0.6, blk.oz0, blk.ox0 + 0.6, blk.oz1, -1, 0, blk.roads.w])
      if (W.e) sides.push([blk.ox1 - 0.6, blk.oz0, blk.ox1 - 0.6, blk.oz1, 1, 0, blk.roads.e])
      for (const [x0, z0, x1, z1, nx, nz, rw] of sides) {
        const len = Math.hypot(x1 - x0, z1 - z0)
        const dx = (x1 - x0) / len
        const dz = (z1 - z0) / len
        const face = Math.atan2(nx, nz)
        const avenue = rw >= 20
        const linePoles = []
        for (let s = 8; s < len - 8; s += 1) {
          const x = x0 + dx * s
          const z = z0 + dz * s
          if (nearNode(x, z)) continue
          // street trees
          if ((d === 'residential' || d === 'commercial' || d === 'park') && s % 11 === 0 && R() < (d === 'commercial' ? 0.45 : 0.7)) {
            const kind = pick(R, ['maple', 'maple', 'oak', 'poplar'])
            P.add('tree-' + kind, x - nx * 1.2, z - nz * 1.2, { ry: R() * TAU, s: 0.6 + R() * 0.35, c: R() < 0.1 ? pick(R, ['#b0823a', '#a65e2c']) : pick(R, LEAF) })
          }
          if (d === 'downtown' && s % 15 === 0 && R() < 0.4) P.add('tree-maple', x - nx * 1.4, z - nz * 1.4, { ry: R() * TAU, s: 0.55, c: pick(R, LEAF) })
          // lighting
          if ((d !== 'residential' || avenue) && s % 30 === 4) P.add('lamp', x - nx * 0.4, z - nz * 0.4, { ry: face })
          if (d === 'residential' && !avenue && (nz < 0 || nx < 0) && s % 34 === 2) {
            P.add(R() < 0.2 ? 'poleT' : 'pole', x - nx * 0.3, z - nz * 0.3, { ry: Math.atan2(dx, dz) })
            linePoles.push([x - nx * 0.3, z - nz * 0.3])
          }
          if (s % 87 === 40) P.add('hydrant', x - nx * 0.6, z - nz * 0.6, {})
          if ((d === 'commercial' || d === 'downtown') && s % 23 === 11 && R() < 0.5) P.add(R() < 0.5 ? 'bench' : 'trash', x - nx * 1.6, z - nz * 1.6, { ry: face + Math.PI })
          if (avenue && s % 160 === 70) P.add('busstop', x - nx * 1.8, z - nz * 1.8, { ry: face + Math.PI })
          // parked and abandoned cars in the curb lane
          if (s % 7 === 0) {
            const p = d === 'residential' ? 0.24 : d === 'industrial' ? 0.16 : 0.38
            if (R() < p) {
              const cx = x + nx * 2.4
              const cz = z + nz * 2.4
              const kind = d === 'industrial' && R() < 0.4 ? pick(R, ['truck', 'van', 'pickup']) : pick(R, ['sedan', 'sedan', 'hatch', 'suv', 'pickup', 'van'])
              const burnt = R() < 0.07
              P.add(burnt ? 'car-burnt' : 'car-' + kind, cx, cz, { ry: Math.atan2(dx, dz) + (nx + nz > 0 ? Math.PI : 0) + (R() - 0.5) * 0.08, c: pick(R, CARS) })
            }
          }
        }
        if (linePoles.length > 1) poles.push(linePoles)
      }
    }
    this.wires = poles
    // abandoned traffic: a few crashes in the middle of junctions near downtown
    for (let j = 0; j < c.NZ; j++)
      for (let i = 0; i < c.NX; i++) {
        const x = c.xs[i]
        const z = c.zs[j]
        const near = Math.hypot(x - c.downtown.x, z - c.downtown.z) < 420
        if (R() < (near ? 0.35 : 0.1)) {
          for (let k = 0; k < 2 + Math.floor(R() * 3); k++) P.add(R() < 0.25 ? 'car-burnt' : 'car-' + pick(R, ['sedan', 'suv', 'hatch', 'van', 'police']), x + (R() - 0.5) * 12, z + (R() - 0.5) * 12, { ry: R() * TAU, c: pick(R, CARS) })
          if (R() < 0.15) P.add('car-bus', x + (R() - 0.5) * 8, z + (R() - 0.5) * 8, { ry: R() * TAU, rz: R() < 0.3 ? Math.PI / 2 : 0, y: 0, c: '#d8d4cc' })
        }
        if (c.avX(i) && c.avZ(j) && i !== c.iR) {
          for (const [sx, sz, ry] of [
            [-1, -1, 0],
            [1, 1, Math.PI],
          ])
            P.add('signal', x + sx * 12, z + sz * 12, { ry: ry + (sx > 0 ? -Math.PI / 2 : Math.PI / 2) })
        }
        // police roadblocks ring downtown
        if (near && R() < 0.12) {
          for (let k = -2; k <= 2; k++) P.add(R() < 0.5 ? 'barrier' : 'policeline', x + k * 3.1, z + 16, { ry: Math.PI / 2 })
          P.add('car-police', x + 5, z + 20, { ry: 0.8, c: '#e8e8e4' })
        }
      }
    // evacuation queue jammed on the avenues into downtown
    for (const col of c.vE)
      for (const e of col) {
        if (!e.on || e.kind !== 'avenue') continue
        const mz = (e.z0 + e.z1) / 2
        if (Math.hypot(e.x - c.downtown.x, mz - c.downtown.z) > 380 || R() < 0.4) continue
        for (let z = e.z0 + 16; z < e.z1 - 16; z += 6.4 + R() * 1.5) {
          if (e.bridge && z > e.bridge.z0 - 4 && z < e.bridge.z1 + 4) continue
          for (const lane of [-1, 1]) if (R() < 0.6) P.add(R() < 0.07 ? 'car-burnt' : 'car-' + pick(R, ['sedan', 'sedan', 'suv', 'hatch', 'van', 'pickup']), e.x + lane * 7.2 + (R() - 0.5) * 0.6, z, { ry: (lane < 0 ? 0 : Math.PI) + (R() - 0.5) * 0.3, c: pick(R, CARS) })
        }
      }
  }

  // ---------------------------------------------------------------- country
  buildCountryside() {
    const c = this.city
    const R = seeded(c.seed + 909)
    const P = this.P
    const sp = this.splat
    const reach = 1250
    const X0 = -reach
    const X1 = c.W + reach
    const Z0 = -reach * 0.8
    const Z1 = c.H + reach
    const clear = (x, z, m = 0) => {
      if (this.inCity(x, z, 25 + m)) return false
      if (Math.abs(z - HIGHWAY_Z) < 26 + m || Math.abs(x - c.rail.x) < 16 + m) return false
      if (Math.abs(z - c.river.z(x)) < RIVER_W / 2 + 8 + m) return false
      if (Math.hypot(x - c.camp.x, z - c.camp.z) < 60 + m) return false
      if (Math.abs(x - c.xs[1]) < 9 + m && z > c.H - 10 && z < c.camp.z) return false
      if (Math.abs(z - c.camp.z) < 8 + m && x > c.camp.x && x < c.xs[1] + 10) return false
      return true
    }
    // woods hug the camp on its west and south sides, as in the camp view
    const campWoods = (x, z) => {
      const dx = x - c.camp.x
      const dz = z - c.camp.z
      const d = Math.hypot(dx, dz)
      return d > 70 && d < 420 && (dx < -40 || dz > 50) ? 0.6 : 0
    }
    const landuse = (x, z) => Math.max(this.forestAt(x, z), campWoods(x, z))
    // patchwork farmland on a coarse grid, fields turned this way and that
    const CELL = 124
    for (let ci = Math.floor(X0 / CELL); ci < X1 / CELL; ci++) {
      for (let cj = Math.floor(Z0 / CELL); cj < Z1 / CELL; cj++) {
        const fx0 = ci * CELL + 4
        const fz0 = cj * CELL + 4
        const fx1 = fx0 + CELL - 8
        const fz1 = fz0 + CELL - 8
        const mx = (fx0 + fx1) / 2
        const mz = (fz0 + fz1) / 2
        if (!clear(mx, mz, 50) || landuse(mx, mz) > 0.2 || R() < 0.25) continue
        const kind = R()
        const rot = R() < 0.5
        const pitch = kind < 0.4 ? 5.5 : 7
        // stripes: plowed earth or crop rows
        for (let j = Math.max(0, Math.floor((fz0 - sp.z0) / sp.res)); j < Math.min(sp.n, (fz1 - sp.z0) / sp.res); j++) {
          const z = sp.z0 + (j + 0.5) * sp.res
          for (let i = Math.max(0, Math.floor((fx0 - sp.x0) / sp.res)); i < Math.min(sp.n, (fx1 - sp.x0) / sp.res); i++) {
            const x = sp.x0 + (i + 0.5) * sp.res
            const q = ((rot ? x : z) % pitch + pitch) % pitch
            if (kind < 0.4) sp.apply(j * sp.n + i, 0, 1, q < pitch * 0.55 ? 0.95 : 0.55)
            else if (kind < 0.7) sp.apply(j * sp.n + i, 2, 1, q < pitch * 0.5 ? 0.75 : 0.15)
            else if (kind < 0.85) sp.apply(j * sp.n + i, 0, 1, 0.35 + (q < pitch * 0.4 ? 0.3 : 0))
          }
        }
        // hedgerows and the odd line of trees along the edges
        const edges = [
          [fx0, fz0, fx1, fz0],
          [fx1, fz0, fx1, fz1],
          [fx0, fz1, fx1, fz1],
          [fx0, fz0, fx0, fz1],
        ]
        for (const [ax, az, bx, bz] of edges) {
          if (R() < 0.35) continue
          const len = Math.hypot(bx - ax, bz - az)
          const treeLine = R() < 0.25
          for (let t = 0; t < len; t += treeLine ? 11 : 5) {
            if (R() < 0.12) continue
            const x = ax + ((bx - ax) * t) / len + (R() - 0.5) * 1.5
            const z = az + ((bz - az) * t) / len + (R() - 0.5) * 1.5
            if (!clear(x, z)) continue
            const y = this.heightAt(x, z)
            if (treeLine) P.add('tree-' + pick(R, ['oak', 'poplar', 'maple']), x, z, { y: y - 0.2, ry: R() * TAU, s: 0.9 + R() * 0.5, c: pick(R, LEAF) })
            else P.add('bush', x, z, { y, ry: R() * TAU, s: 0.9 + R() * 0.7, c: pick(R, LEAF) })
          }
        }
        if (kind > 0.85 && R() < 0.8)
          for (let k = 0; k < 10; k++) {
            const x = fx0 + 8 + R() * (fx1 - fx0 - 16)
            const z = fz0 + 8 + R() * (fz1 - fz0 - 16)
            P.add('bale', x, z, { y: this.heightAt(x, z) + 0.9, ry: R() * TAU })
          }
      }
    }
    // woods: masses of canopy, single trees on the margins and in meadows
    for (let x = X0; x < X1; x += 15) {
      for (let z = Z0; z < Z1; z += 15) {
        const jx = x + (R() - 0.5) * 12
        const jz = z + (R() - 0.5) * 12
        if (!clear(jx, jz)) continue
        const f = landuse(jx, jz)
        const y = this.heightAt(jx, jz)
        if (f > 0.34) {
          const fir = this.forestAt(jx * 1.3 + 500, jz * 1.3) > 0.1 || f > 0.66
          P.add(fir ? 'firclump' : 'clump', jx, jz, { y: y - 0.4, ry: R() * TAU, s: 0.75 + R() * 0.5, c: fir ? pick(R, FOREST_FIR) : R() < 0.08 ? pick(R, ['#8a6a30', '#7a5a2a', '#9a7a3a']) : pick(R, FOREST) })
          sp.circle(jx, jz, 11, 3, 1, 6, 3)
        } else if (f > 0.22 || R() < 0.05) {
          const kind = pick(R, ['oak', 'oak', 'maple', 'pine', 'poplar', 'spruce', 'dead'])
          P.add('tree-' + kind, jx, jz, { y: y - 0.2, ry: R() * TAU, s: 0.9 + R() * 0.8, c: kind === 'pine' || kind === 'spruce' ? pick(R, NEEDLE) : pick(R, LEAF) })
        }
      }
    }
    // trees along the camp track
    for (let z = c.H + 20; z < c.camp.z - 10; z += 13) for (const s of [-1, 1]) if (R() < 0.7) P.add('tree-' + pick(R, ['poplar', 'oak']), c.xs[1] + s * (8 + R() * 3), z, { ry: R() * TAU, s: 0.9 + R() * 0.3, c: pick(R, LEAF) })
    // farmsteads south and west of the camp
    const farms = [
      [c.camp.x - 380, c.camp.z + 160],
      [c.camp.x + 420, c.camp.z + 230],
      [-360, c.H * 0.7],
      [c.W + 380, c.H + 180],
    ]
    for (const [fx, fz] of farms) {
      const b = this.chunk(fx, fz)
      const y = this.heightAt(fx, fz)
      b.at({ x: fx, y, z: fz, ry: R() * TAU }, () => {
        // red barn, silo and a farmhouse
        b.box(14, 7, 22, { mat: 'planks', color: '#a8443a', y: 3.5 })
        gableRoof(b, { w: 22, d: 14, y: 7, rise: 4.5, mat: 'roofmetal', color: '#6a6e70', ry: Math.PI / 2, gableMat: 'planks', gableColor: '#a8443a', over: 0.5 })
        b.box(0.2, 4.4, 5, { mat: 'planks', color: '#e8e4dc', x: 7.05, y: 2.2 })
        b.cyl(3, 3, 15, { mat: 'metal', color: '#c8ccd0', x: -11, y: 7.5, seg: 14 })
        b.sphere(3, { mat: 'metal', color: '#b8bcc0', x: -11, y: 15, seg: 14, ws: 14, hs: 6, tl: Math.PI / 2 })
        b.at({ x: 18, z: 8 }, () => CB.house(b, { w: 10, d: 8, floors: 2, wall: '#e8e4dc', roof: '#4a4644', roofType: 'gable', state: R() < 0.4 ? 'boarded' : 'ok', chimney: 1, porch: true }, R, P))
      })
      for (let k = 0; k < 12; k++) {
        const a = R() * TAU
        const rr = 30 + R() * 60
        const hx = fx + Math.cos(a) * rr
        const hz = fz + Math.sin(a) * rr
        b.cyl(0.9, 0.9, 1.4, { mat: 'canvas', color: '#d8c48a', x: hx, y: this.heightAt(hx, hz) + 0.9, z: hz, rz: Math.PI / 2, ry: R() * TAU, seg: 10 })
      }
    }
    // power line marching into town
    const pyl = []
    for (let x = -1350; x < c.xs[0] - 60; x += 210) {
      const z = c.H * 0.78 + Math.sin(x * 0.002) * 40
      if (Math.abs(z - c.river.z(x)) < RIVER_W / 2 + 14) continue
      P.add('pylon', x, z, { y: this.heightAt(x, z), ry: Math.PI / 2 })
      pyl.push([x, z, this.heightAt(x, z)])
    }
    this.pylons = pyl
    // water tower on the ridge
    const wx = c.W + 260
    const wz = c.H * 0.45
    const wy = this.heightAt(wx, wz)
    const b = this.chunk(wx, wz)
    b.at({ x: wx, y: wy, z: wz }, () => {
      for (let k = 0; k < 4; k++) b.beam([Math.cos((k / 4) * TAU) * 6, 0, Math.sin((k / 4) * TAU) * 6], [Math.cos((k / 4) * TAU) * 3, 26, Math.sin((k / 4) * TAU) * 3], 0.6, 0.6, { mat: 'paint', color: '#b8c4c8' })
      b.cyl(0.8, 0.8, 26, { mat: 'paint', color: '#b8c4c8', y: 13, seg: 8 })
      b.sphere(8, { mat: 'paint', color: '#c8d4d8', y: 32, ws: 18, hs: 12 })
      b.torus(8.4, 0.3, { mat: 'paint', color: '#8a9498', y: 30, rx: Math.PI / 2, rs: 4, ts2: 24 })
      signPlane(b, addSign('ASHFORD', '#c8d4d8', '#2a4a6a'), 10, 2.6, { y: 33, z: 8.1 })
    })
    // a few boats on the river
    for (let k = 0; k < 5; k++) {
      const x = -200 + R() * (c.W + 400)
      P.add('boat', x, c.river.z(x) + (R() - 0.5) * 20, { y: WATER_Y - 0.4, ry: R() * TAU, rz: R() < 0.3 ? 0.6 : 0, c: pick(R, ['#e8e4dc', '#c8302a', '#2a4a7a']) })
    }
  }

  // ---------------------------------------------------------------- the camp
  buildCamp() {
    const c = this.city
    const R = seeded(c.seed + 4)
    const P = this.P
    const b = this.chunk(c.camp.x, c.camp.z)
    const cx = c.camp.x
    const cz = c.camp.z
    const W = 66
    const D = 58
    // scrap fence of corrugated panels and posts
    const panel = (ax, az, bx, bz) => {
      const len = Math.hypot(bx - ax, bz - az)
      const n = Math.ceil(len / 2.4)
      for (let k = 0; k < n; k++) {
        const t0 = k / n
        const t1 = (k + 1) / n
        b.beam([ax + (bx - ax) * t0, 1.2, az + (bz - az) * t0], [ax + (bx - ax) * t1, 1.2 + (R() - 0.5) * 0.2, az + (bz - az) * t1], 0.1, 2.3 + R() * 0.4, { mat: 'corrugated', color: pick(R, ['#a8a49a', '#8a7a6a', '#9aa0a4', '#b07a5a']) })
        b.box(0.18, 2.8, 0.18, { mat: 'wood', color: '#7a6450', x: ax + (bx - ax) * t0, y: 1.4, z: az + (bz - az) * t0 })
      }
    }
    panel(cx - W / 2, cz - D / 2, cx + W / 2, cz - D / 2)
    panel(cx - W / 2, cz + D / 2, cx + W / 2, cz + D / 2)
    panel(cx - W / 2, cz - D / 2, cx - W / 2, cz + D / 2)
    panel(cx + W / 2, cz - D / 2, cx + W / 2, cz - 5)
    panel(cx + W / 2, cz + 5, cx + W / 2, cz + D / 2)
    // gate and sign
    for (const s of [-1, 1]) b.box(0.4, 4.6, 0.4, { mat: 'wood', color: '#6a5444', x: cx + W / 2, y: 2.3, z: cz + s * 5 })
    b.box(0.3, 1.2, 10.4, { mat: 'planks', color: '#a8906c', x: cx + W / 2, y: 4.4, z: cz })
    b.at({ x: cx + W / 2 + 0.2, z: cz, ry: Math.PI / 2 }, () => signPlane(b, addSign('HOLDOUT', '#2a2620', '#e8b048'), 8, 1.0, { y: 4.4 }))
    // tents, shacks, the fire and a watchtower
    const tents = [
      [-18, -14],
      [-8, -16],
      [-20, 4],
      [10, -15],
    ]
    for (const [tx, tz] of tents) P.add('tent', cx + tx, cz + tz, { ry: R() * 0.4, c: pick(R, ['#7c8158', '#d8c49c', '#4f6e4a', '#3f74b0']) })
    for (const [sx, sz, w, d] of [
      [16, 12, 9, 7],
      [-16, 16, 7, 6],
    ]) {
      b.box(w, 3, d, { mat: 'planks', color: '#a89070', x: cx + sx, y: 1.5, z: cz + sz })
      gableRoof(b, { w, d, y: 3, rise: 1.2, mat: 'corrugated', color: '#8a8a7e', x: cx + sx, z: cz + sz, gableMat: 'planks', gableColor: '#a89070' })
    }
    b.cyl(1.6, 1.8, 0.4, { mat: 'concrete', color: '#6a6660', x: cx, y: 0.2, z: cz, seg: 10 })
    this.campFire = { x: cx, z: cz }
    for (const [dx, dz] of [
      [-1.2, -1.2],
      [1.2, -1.2],
      [1.2, 1.2],
      [-1.2, 1.2],
    ])
      b.box(0.25, 7, 0.25, { mat: 'wood', color: '#7a6450', x: cx - 26 + dx, y: 3.5, z: cz + 22 + dz })
    b.box(3, 0.25, 3, { mat: 'planks', color: '#a89070', x: cx - 26, y: 7, z: cz + 22 })
    b.box(3.4, 0.15, 3.4, { mat: 'corrugated', color: '#8a8a7e', x: cx - 26, y: 9, z: cz + 22 })
    for (let k = 0; k < 4; k++) b.box(4.8, 0.5 + R() * 0.4, 1.0, { mat: 'wood', color: '#b89a74', x: cx + 20, y: 0.4 + k * 0.5, z: cz - 6 + k * 0.1, ry: 0.05 })
    P.add('car-van', cx + 22, cz + 2, { ry: Math.PI / 2, c: '#5a6a5a' })
  }

  // ---------------------------------------------------------------- instances
  buildInstances() {
    const by = this.P.by
    this.inst = []
    const make = (key, template, opts) => {
      const list = by[key]
      if (!list || !list.length) return
      const set = new InstSet(this.scene, template, list.length, opts)
      set.set(list)
      this.inst.push(set)
    }
    for (const k of ['oak', 'maple', 'poplar', 'pine', 'spruce', 'dead']) make('tree-' + k, mapTreeModel(k), { tint: ['leaf', 'needle'], wind: true })
    make('bush', mapTreeModel('bush'), { tint: ['leaf'], shadow: false })
    make('clump', mapTreeModel('clump'), { tint: ['leaf'] })
    make('firclump', mapTreeModel('firclump'), { tint: ['needle'] })
    make('hedge', mapTreeModel('hedge'), { tint: ['leaf'], shadow: false })
    for (const k of VEH) make('car-' + k, mapVehicleModel(k), { tint: ['paint'] })
    make('bus-school', mapVehicleModel('schoolbus'), { tint: ['paint'] })
    make('car-burnt', mapVehicleModel('sedan', { burnt: true }), {})
    for (const k of ['semi', 'truck', 'ambulance', 'firetruck', 'humvee', 'mtruck', 'heli']) make(k, mapVehicleModel(k), { tint: ['paint'] })
    for (const k of PROPS) make(k, mapPropModel(k), { tint: TINTED[k] || [], shadow: k !== 'tie' })
  }
  buildWires() {
    const pos = []
    const span = (a, b, ya, yb, sag) => {
      const n = 8
      for (let k = 0; k < n; k++) {
        const t0 = k / n
        const t1 = (k + 1) / n
        const y0 = ya + (yb - ya) * t0 - Math.sin(t0 * Math.PI) * sag
        const y1 = ya + (yb - ya) * t1 - Math.sin(t1 * Math.PI) * sag
        pos.push(a[0] + (b[0] - a[0]) * t0, y0, a[1] + (b[1] - a[1]) * t0, a[0] + (b[0] - a[0]) * t1, y1, a[1] + (b[1] - a[1]) * t1)
      }
    }
    for (const line of this.wires) {
      for (let k = 0; k < line.length - 1; k++) {
        const a = line[k]
        const b = line[k + 1]
        if (Math.hypot(a[0] - b[0], a[1] - b[1]) > 45) continue
        const dx = b[0] - a[0]
        const dz = b[1] - a[1]
        const l = Math.hypot(dx, dz)
        const px = -dz / l
        const pz = dx / l
        for (const off of [-0.85, 0, 0.85]) span([a[0] + px * off, a[1] + pz * off], [b[0] + px * off, b[1] + pz * off], 8.85, 8.85, 0.6)
      }
    }
    const pl = this.pylons || []
    for (let k = 0; k < pl.length - 1; k++) {
      const [ax, az, ay] = pl[k]
      const [bx, bz, by] = pl[k + 1]
      for (const off of [-6, 0, 6]) span([ax, az + off], [bx, bz + off], ay + (off ? 22 : 26), by + (off ? 22 : 26), 5)
    }
    const g = new THREE.BufferGeometry()
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
    const lines = new THREE.LineSegments(g, new THREE.LineBasicMaterial({ color: '#1e1e1c', transparent: true, opacity: 0.75 }))
    this.scene.add(lines)
  }

  // ---------------------------------------------------------------- fires
  buildFires() {
    const fires = this.P.by.fire || []
    this.fires = []
    for (const f of fires) {
      const y = f.y || 4
      const s = f.s || 1
      const grp = new THREE.Group()
      grp.position.set(f.x, y, f.z)
      for (let k = 0; k < 4; k++) {
        const fl = makeFlame(4 * s, 7 * s, 2.6)
        fl.position.set((Math.random() - 0.5) * 6 * s, 0, (Math.random() - 0.5) * 6 * s)
        grp.add(fl)
      }
      this.scene.add(grp)
      this.fires.push({ x: f.x, y, z: f.z, s, acc: 0 })
    }
    for (const sm of this.P.by.smoke || []) this.fires.push({ x: sm.x, y: sm.y || 20, z: sm.z, s: sm.s || 1, acc: 0, smokeOnly: true })
    const cf = this.campFire
    const fl = makeFlame(1.4, 2.6, 2.4)
    fl.position.set(cf.x, 0.4, cf.z)
    this.scene.add(fl)
    this.fires.push({ x: cf.x, y: 1.5, z: cf.z, s: 0.35, acc: 0, camp: true })
    for (let i = 0; i < 6; i++) {
      const l = new THREE.PointLight('#ff8a3a', 0, 120, 1.6)
      l.position.set(0, -50, 0)
      this.scene.add(l)
      this.lightPool.push(l)
    }
  }
  updateFires(dt, night) {
    const fx = this.fx
    for (const f of this.fires) {
      f.acc += dt * (f.camp ? 3 : 6 * f.s)
      while (f.acc > 1) {
        f.acc -= 1
        const s = f.s
        fx.smokeC.spawn({ x: f.x + (Math.random() - 0.5) * 6 * s, y: f.y + 4 * s, z: f.z + (Math.random() - 0.5) * 6 * s, vx: 1.8 + Math.random() * 1.2, vy: 4 + Math.random() * 3, vz: 0.6 + (Math.random() - 0.5) * 1.2, drag: 0.02, life: 18 + Math.random() * 8, age: 0, s0: 7 * s, s1: 34 * s, a: f.smokeOnly ? 0.28 : 0.42, c0: new THREE.Color(f.smokeOnly ? '#8a8682' : '#2e2a26'), c1: new THREE.Color('#8a8884'), rot: Math.random() * 6, vr: (Math.random() - 0.5) * 0.2, fadeIn: 0.06, fadePow: 1.2 })
      }
    }
    // the nearest fires light the night
    const tgt = view.rig.target
    const lit = this.fires.filter((f) => !f.smokeOnly).sort((a, b) => Math.hypot(a.x - tgt.x, a.z - tgt.z) - Math.hypot(b.x - tgt.x, b.z - tgt.z))
    this.lightPool.forEach((l, i) => {
      const f = lit[i]
      if (!f || night < 0.05) {
        l.intensity = 0
        return
      }
      l.position.set(f.x, f.y + 6 * f.s, f.z)
      l.distance = (f.camp ? 40 : 110) * Math.max(0.6, f.s)
      l.intensity = (f.camp ? 300 : 2400 * f.s) * night * (0.85 + Math.sin(this.t * 9 + i) * 0.08 + Math.sin(this.t * 5.3 + i * 2) * 0.07)
    })
  }

  // ---------------------------------------------------------------- markers
  buildMarkers() {
    const beamMat = (col) =>
      new THREE.ShaderMaterial({
        uniforms: { color: { value: new THREE.Color(col) }, t: { value: 0 } },
        vertexShader: 'varying float vy; void main(){ vy = uv.y; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }',
        fragmentShader: 'uniform vec3 color; uniform float t; varying float vy; void main(){ float a = (1.0 - vy) * 0.55 * (0.75 + 0.25 * sin(t * 3.0 + vy * 12.0)); gl_FragColor = vec4(color * 2.2, a); }',
        transparent: true,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
        side: THREE.DoubleSide,
      })
    const HEIGHT = { house: 12, apartment: 17, office: 24, diner: 9, gas: 9, store: 8, hardware: 10, pharmacy: 9, supermarket: 11, garage: 10, school: 11, hospital: 37, firestation: 18, gunstore: 8, warehouse: 13, police: 15, military: 10 }
    this.markers = []
    for (const [, L] of this.locs) {
      const loc = L.loc
      const col = LEVEL_COLORS[loc.level - 1]
      const h = (HEIGHT[loc.type] || 10) + 12
      const g = new THREE.Group()
      g.position.set(loc.x, 0, loc.z)
      const beamGeo = new THREE.CylinderGeometry(0.7, 0.7, h, 8, 1, true)
      beamGeo.translate(0, h / 2, 0)
      const beam = new THREE.Mesh(beamGeo, beamMat(col))
      beam.renderOrder = 5
      g.add(beam)
      const head = new THREE.Mesh(new THREE.OctahedronGeometry(2.6, 0), new THREE.MeshStandardMaterial({ color: col, emissive: col, emissiveIntensity: 1.6, roughness: 0.3, metalness: 0.2 }))
      head.position.y = h + 3
      head.scale.set(1, 1.5, 1)
      g.add(head)
      const ring = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ map: ringTex(), color: new THREE.Color(col).multiplyScalar(1.4), transparent: true, depthWrite: false, blending: THREE.AdditiveBlending }))
      ring.rotation.x = -Math.PI / 2
      ring.position.y = 0.4
      const rs = Math.max(loc.site.bw, loc.site.bd) * 1.25
      ring.scale.set(rs, rs, 1)
      ring.renderOrder = 4
      g.add(ring)
      this.scene.add(g)
      // label
      const el = document.createElement('div')
      el.className = 'mloc'
      el.innerHTML = `<b style="--c:${col}">${loc.level}</b><span>${loc.name}</span><i></i>`
      const label = view.labels.add(el, new THREE.Vector3(loc.x, h + 7, loc.z), { scene: this.scene })
      L.marker = { g, beam, head, ring, label, el, h, col }
      this.markers.push(L)
    }
    // the camp
    const el = document.createElement('div')
    el.className = 'mloc camp'
    el.innerHTML = '<b>★</b><span>Holdout camp</span>'
    this.campLabel = view.labels.add(el, new THREE.Vector3(this.city.camp.x, 20, this.city.camp.z), { scene: this.scene })
  }
  refreshMarkers() {
    const evs = new Map((S.events || []).map((e) => [e.locId, e]))
    for (const L of this.markers) {
      const m = L.marker
      const looted = !!S.looted[L.loc.id]
      const ev = evs.get(L.loc.id)
      const col = looted ? '#6a6e6a' : m.col
      m.head.material.color.set(col)
      m.head.material.emissive.set(col)
      m.beam.material.uniforms.color.value.set(col)
      m.ring.material.color.set(col).multiplyScalar(1.4)
      m.el.classList.toggle('looted', looted)
      m.el.classList.toggle('event', !!ev)
      m.el.querySelector('i').textContent = ev ? (ev.kind === 'distress' ? 'SOS' : 'DROP') : looted ? 'looted' : ''
      m.el.classList.toggle('sel', this.sel === L.loc)
      m.el.classList.toggle('hov', this.hover === L.loc)
    }
  }

  // ---------------------------------------------------------------- route
  showRoute(loc) {
    if (this.routeMesh) {
      this.scene.remove(this.routeMesh)
      this.routeMesh.geometry.dispose()
      this.routeMesh = null
    }
    if (!loc) return null
    const r = route(this.city, this.city.campNode, loc.node)
    if (!r) return null
    // dense polyline, then a flat ribbon with animated chevrons
    const pts = [[this.city.camp.x, this.city.camp.z], ...r.pts, [loc.x, loc.z]]
    const pos = []
    const uv = []
    const idx = []
    let acc = 0
    const W = 1.5
    for (let k = 0; k < pts.length; k++) {
      const p = pts[k]
      const a = pts[Math.max(0, k - 1)]
      const b = pts[Math.min(pts.length - 1, k + 1)]
      const dx = b[0] - a[0]
      const dz = b[1] - a[1]
      const l = Math.hypot(dx, dz) || 1
      if (k > 0) acc += Math.hypot(p[0] - pts[k - 1][0], p[1] - pts[k - 1][1])
      const nx = -dz / l
      const nz = dx / l
      const y = 0.9 + (this.heightAt(p[0], p[1]) > 0.5 ? this.heightAt(p[0], p[1]) : 0)
      pos.push(p[0] + nx * W, y, p[1] + nz * W, p[0] - nx * W, y, p[1] - nz * W)
      uv.push(acc, 0, acc, 1)
      if (k > 0) {
        const i = (k - 1) * 2
        idx.push(i, i + 1, i + 2, i + 1, i + 3, i + 2)
      }
    }
    const g = new THREE.BufferGeometry()
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2))
    g.setIndex(idx)
    if (!this.routeMat)
      this.routeMat = new THREE.ShaderMaterial({
        uniforms: { t: { value: 0 }, color: { value: new THREE.Color('#ffb040') } },
        vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }',
        fragmentShader: `uniform float t; uniform vec3 color; varying vec2 vUv;
          void main(){
            float s = fract((vUv.x - t * 22.0) / 9.0);
            float chev = smoothstep(0.0, 0.1, s) * (1.0 - smoothstep(0.3, 0.4, s));
            float edge = 1.0 - smoothstep(0.25, 0.5, abs(vUv.y - 0.5));
            float a = (0.35 + chev * 0.65) * edge;
            gl_FragColor = vec4(color * (0.9 + chev * 1.2), a * 0.85);
          }`,
        transparent: true,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
        side: THREE.DoubleSide,
      })
    this.routeMesh = new THREE.Mesh(g, this.routeMat)
    this.routeMesh.renderOrder = 6
    this.routeMesh.frustumCulled = false
    this.scene.add(this.routeMesh)
    this.routePts = pts
    return { len: r.len }
  }

  // ---------------------------------------------------------------- open / close
  open() {
    const c = this.city
    view.rig.minDist = 70
    view.rig.maxDist = 1350
    view.rig.pitchCfg = [0.66, 1.08, 800]
    view.rig.setBounds(-250, -200, c.W + 300, c.camp.z + 80)
    view.camera.far = 7000
    view.camera.near = 15
    view.camera.updateProjectionMatrix()
    if (!this.framed) {
      this.framed = true
      view.rig.jump(c.W * 0.42, c.H * 0.62, 1150)
      view.rig.yaw = view.rig.yawGoal = Math.PI * 0.15
    }
    this.isOpen = true
    this.panel = new MapPanel(this.game, this)
    this.refreshMarkers()
    if (this.sel) this.select(this.sel, true)
  }
  close() {
    this.isOpen = false
    this.panel?.destroy()
    this.panel = null
    view.camera.near = 0.5
    view.camera.updateProjectionMatrix()
    view.canvas.style.cursor = ''
    document.getElementById('labels')?.classList.remove('mapfar')
  }
  select(loc, keep = false) {
    this.sel = loc
    const info = this.showRoute(loc)
    this.routeLen = info?.len || 0
    this.refreshMarkers()
    this.panel?.render()
    if (loc && !keep) view.rig.focus(loc.x, loc.z, Math.min(view.rig.distGoal, 480))
  }

  // The van pulls out of camp and follows the route before the run starts.
  launch(loc, ids, loadout) {
    if (this.launching) return
    if (!this.van) {
      this.van = mapVehicleModel('van')
      this.van.traverse((o) => {
        if (!o.isMesh || o.material.userData?.key !== 'paint') return
        const c = o.geometry.attributes.color
        for (let i = 0; i < c.count; i++) c.setXYZ(i, c.getX(i) * 0.36, c.getY(i) * 0.42, c.getZ(i) * 0.36)
      })
      this.van.scale.setScalar(1.8)
      this.scene.add(this.van)
    }
    const pts = this.routePts || [[this.city.camp.x, this.city.camp.z], [loc.x, loc.z]]
    const cum = [0]
    for (let k = 1; k < pts.length; k++) cum.push(cum[k - 1] + Math.hypot(pts[k][0] - pts[k - 1][0], pts[k][1] - pts[k - 1][1]))
    this.launching = { loc, ids, loadout, t: 0, dur: clamp(cum[cum.length - 1] / 900, 2.2, 4.2), pts, cum }
    this.van.visible = true
    this.panel?.render()
  }
  updateLaunch(dt) {
    const L = this.launching
    if (!L) return
    L.t += dt
    const f = clamp(L.t / L.dur, 0, 1)
    const e = f < 0.5 ? 2 * f * f : 1 - Math.pow(-2 * f + 2, 2) / 2
    const total = L.cum[L.cum.length - 1]
    const s = e * total
    let k = 1
    while (k < L.cum.length - 1 && L.cum[k] < s) k++
    const a = L.pts[k - 1]
    const b = L.pts[k]
    const seg = Math.max(0.001, L.cum[k] - L.cum[k - 1])
    const t = clamp((s - L.cum[k - 1]) / seg, 0, 1)
    const x = a[0] + (b[0] - a[0]) * t
    const z = a[1] + (b[1] - a[1]) * t
    this.van.position.set(x, Math.max(0, this.heightAt(x, z)) + 0.1, z)
    this.van.rotation.y = Math.atan2(b[0] - a[0], b[1] - a[1])
    view.rig.focus(x, z, clamp(view.rig.distGoal * (1 - dt * 0.8), 260, 2000))
    if (f >= 1 && !L.done) {
      L.done = true
      setTimeout(() => {
        this.launching = null
        this.van.visible = false
        this.game.startMission(L.loc, L.ids, L.loadout)
      }, 250)
    }
  }

  // ---------------------------------------------------------------- input
  locAt(sx, sy) {
    let best = null
    let bd = 30
    for (const L of this.markers) {
      const p = new THREE.Vector3(L.loc.x, L.marker.h + 3, L.loc.z).project(view.camera)
      if (p.z > 1) continue
      const x = (p.x * 0.5 + 0.5) * window.innerWidth
      const y = (-p.y * 0.5 + 0.5) * window.innerHeight
      const d = Math.hypot(x - sx, y - sy)
      if (d < bd) {
        bd = d
        best = L.loc
      }
    }
    if (best) return best
    const ray = screenRay(sx, sy)
    const hits = ray.intersectObjects([...this.locs.values()].map((L) => L.group), true)
    for (const h of hits) {
      let o = h.object
      while (o) {
        if (o.userData.pick) return o.userData.pick
        o = o.parent
      }
    }
    return null
  }
  onTap(x, y) {
    const loc = this.locAt(x, y)
    if (loc) this.select(loc)
  }
  onDouble(x, y) {
    const loc = this.locAt(x, y)
    if (loc) view.rig.focus(loc.x, loc.z, 220)
  }
  onHover(x, y) {
    const loc = this.locAt(x, y)
    if (loc !== this.hover) {
      this.hover = loc
      this.refreshMarkers()
    }
    view.canvas.style.cursor = loc ? 'pointer' : ''
  }
  onKey(e) {
    if (e._handled) return
    if (e.key === 'Escape') {
      if (this.sel) this.select(null)
      else this.game.closeMap()
    }
  }

  // ---------------------------------------------------------------- frame
  update(dt) {
    this.t += dt
    const hr = hour()
    const night = nightFactor(hr)
    const a = this.atmo.update(hr, view.rig.target, view.rig.dist, dt)
    this.scene.fog.near = view.rig.dist * 1.35
    this.scene.fog.far = view.rig.dist * 3.4 + 700
    this.game.pipe.exposure = a.exposure
    this.game.pipe.grade(this.scene, { saturation: a.saturation * 1.05 })
    setNightGlow(night)
    tickFlames(this.t)
    wind.time.value = this.t
    this.waterNormal.offset.set(this.t * 0.012, this.t * 0.006)
    this.updateFires(dt, night)
    for (const L of this.markers) {
      const m = L.marker
      const sel = this.sel === L.loc
      const hov = this.hover === L.loc
      m.head.rotation.y += dt * (sel ? 2.4 : 0.8)
      m.head.position.y = m.h + 3 + Math.sin(this.t * 2 + L.loc.x) * 0.8
      const zoomK = clamp(view.rig.dist / 500, 0.6, 2.4)
      m.head.scale.set(zoomK * (sel || hov ? 1.4 : 1), zoomK * 1.5 * (sel || hov ? 1.4 : 1), zoomK * (sel || hov ? 1.4 : 1))
      m.beam.material.uniforms.t.value = this.t
      m.ring.material.opacity = sel ? 0.9 : hov ? 0.7 : 0.35
    }
    if (this.routeMat) this.routeMat.uniforms.t.value = this.t
    this.updateLaunch(dt)
    this.fx.setViewport(window.innerHeight, view.camera.fov)
    this.fx.update(dt)
    const far = view.rig.dist > 700
    document.getElementById('labels')?.classList.toggle('mapfar', far)
    this.panel?.update(dt)
  }
}
