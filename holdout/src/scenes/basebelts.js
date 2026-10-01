// Conveyor belts in the camp. Each belt runs overhead on posts, with a lift at
// each end that climbs out of one station and drops into the next, and the
// goods it carries ride along it as small crates, drums, sacks, logs and
// ingots. Also the link mode for laying a new belt: valid ends light up, the
// route previews under the cursor and a click builds it.
import * as THREE from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'
import { Builder } from '../models/kit.js'
import { mat } from '../render/materials.js'
import { view, pickAt } from '../render/view.js'
import { BELTS, RES, STATIONS, SEC_PER_DAY } from '../game/data.js'
import { S, stationSize, canAfford } from '../game/state.js'
import { BELT_Y, BELT_DY, planLink, addLink, linkProblem, linkState, linkPerDay, isDepot, rerouteLinks } from '../game/belts.js'
import { sfx } from '../core/audio.js'
import { fmt } from '../core/util.js'

const LOW = 1.0 // where goods leave and enter a station
const OFF = 0.32 // lifts stand this far out from the station wall
const TEX_M = 0.4 // metres of belt per texture repeat

// How each tier looks: Mk1 is salvage on timber posts, Mk2 painted steel in
// safety yellow, Mk3 bare steel with blue rails.
const LOOK = [
  null,
  { frame: 'rust', frameC: '#6a5e52', rail: 'wood', railC: '#8a6c4a', post: 'wood', postC: '#76593c', lamp: '#ffb24a' },
  { frame: 'paint', frameC: '#3c4148', rail: 'paint', railC: '#d8a020', post: 'paint', postC: '#4a5058', lamp: '#ffd060' },
  { frame: 'steel', frameC: '#5f6b74', rail: 'paint', railC: '#3f7fc0', post: 'steel', postC: '#6a747c', lamp: '#7ad0ff' },
]
const STATE_LAMP = { moving: '#46ff7a', idle: '#ffb020', backed: '#ff3a30' }
// Which shape a resource rides in.
const SHAPE = { water: 'drum', fuel: 'drum', chemicals: 'drum', gunpowder: 'keg', rubber: 'roll', food: 'sack', cloth: 'sack', wood: 'log', metal: 'bar', steel: 'bar', wiring: 'spool' }
const SHAPES = ['crate', 'drum', 'keg', 'sack', 'log', 'bar', 'spool', 'roll']

// ---------------------------------------------------------------- shared resources
let beltTex = null
function beltTexture() {
  if (beltTex) return beltTex
  const c = document.createElement('canvas')
  c.width = 64
  c.height = 64
  const g = c.getContext('2d')
  g.fillStyle = '#2b2c2f'
  g.fillRect(0, 0, 64, 64)
  for (let i = 0; i < 420; i++) {
    g.fillStyle = `rgba(${Math.random() < 0.5 ? '255,255,255' : '0,0,0'},${Math.random() * 0.05})`
    g.fillRect(Math.random() * 64, Math.random() * 64, 1 + Math.random() * 3, 1)
  }
  // fine ribs across the belt, and one low chevron cleat per repeat
  for (let y = 2; y < 64; y += 8) {
    g.fillStyle = 'rgba(0,0,0,0.32)'
    g.fillRect(4, y, 56, 2)
    g.fillStyle = 'rgba(255,255,255,0.05)'
    g.fillRect(4, y + 2, 56, 1)
  }
  g.lineCap = 'round'
  g.strokeStyle = 'rgba(12,12,14,0.75)'
  g.lineWidth = 4
  g.beginPath()
  g.moveTo(10, 40)
  g.lineTo(32, 28)
  g.lineTo(54, 40)
  g.stroke()
  g.strokeStyle = 'rgba(255,255,255,0.09)'
  g.lineWidth = 1.5
  g.beginPath()
  g.moveTo(10, 38)
  g.lineTo(32, 26)
  g.lineTo(54, 38)
  g.stroke()
  // worn edges
  g.fillStyle = '#18191b'
  g.fillRect(0, 0, 4, 64)
  g.fillRect(60, 0, 4, 64)
  g.fillStyle = 'rgba(200,190,170,0.08)'
  g.fillRect(4, 0, 2, 64)
  g.fillRect(58, 0, 2, 64)
  const t = new THREE.CanvasTexture(c)
  t.wrapS = t.wrapT = THREE.RepeatWrapping
  t.colorSpace = THREE.SRGBColorSpace
  t.anisotropy = 4
  beltTex = t
  return t
}
const beltMats = []
function beltMat(tier) {
  if (!beltMats[tier]) {
    const t = beltTexture().clone()
    t.needsUpdate = true
    beltMats[tier] = new THREE.MeshStandardMaterial({ map: t, roughness: 0.86, metalness: 0.05 })
  }
  return beltMats[tier]
}
const GHOST_OK = new THREE.MeshStandardMaterial({ color: '#8af0b0', emissive: '#2a9a5a', emissiveIntensity: 0.7, transparent: true, opacity: 0.5, depthWrite: false })
const GHOST_BAD = new THREE.MeshStandardMaterial({ color: '#ff9a8a', emissive: '#a02a20', emissiveIntensity: 0.7, transparent: true, opacity: 0.45, depthWrite: false })

// Small cargo shapes, bottom at y = 0, white so instance colours tint them.
let cargoGeos = null
function cargo() {
  if (cargoGeos) return cargoGeos
  const col = (g, c) => {
    const n = g.attributes.position.count
    const a = new Float32Array(n * 3)
    const cc = new THREE.Color(c)
    for (let i = 0; i < n; i++) a.set([cc.r, cc.g, cc.b], i * 3)
    g.setAttribute('color', new THREE.BufferAttribute(a, 3))
    for (const k of Object.keys(g.attributes)) if (!['position', 'normal', 'color'].includes(k)) g.deleteAttribute(k)
    return g.index ? g.toNonIndexed() : g
  }
  const box = (w, h, d, x, y, z, c, r = 0) => {
    const g = r ? new RoundedBoxGeometry(w, h, d, 2, r) : new THREE.BoxGeometry(w, h, d)
    g.translate(x, y, z)
    return col(g, c)
  }
  const cyl = (rt, rb, h, x, y, z, c, seg = 12, rotZ = 0) => {
    const g = new THREE.CylinderGeometry(rt, rb, h, seg)
    if (rotZ) g.rotateZ(rotZ)
    g.translate(x, y, z)
    return col(g, c)
  }
  const merge = (list) => mergeGeometries(list, false)
  const H = Math.PI / 2
  cargoGeos = {
    crate: merge([box(0.38, 0.27, 0.38, 0, 0.135, 0, '#ffffff', 0.02), box(0.4, 0.28, 0.06, 0, 0.14, -0.1, '#9a9a9a'), box(0.4, 0.28, 0.06, 0, 0.14, 0.1, '#9a9a9a'), box(0.3, 0.012, 0.3, 0, 0.276, 0, '#d0d0d0')]),
    drum: merge([cyl(0.15, 0.15, 0.36, 0, 0.18, 0, '#ffffff', 14), cyl(0.158, 0.158, 0.025, 0, 0.1, 0, '#8a8a8a', 14), cyl(0.158, 0.158, 0.025, 0, 0.26, 0, '#8a8a8a', 14), cyl(0.13, 0.13, 0.012, 0, 0.365, 0, '#c8c8c8', 14)]),
    keg: merge([cyl(0.13, 0.15, 0.15, 0, 0.075, 0, '#a07a50', 12), cyl(0.15, 0.13, 0.15, 0, 0.225, 0, '#a07a50', 12), cyl(0.155, 0.155, 0.02, 0, 0.15, 0, '#ffffff', 12), cyl(0.12, 0.12, 0.01, 0, 0.3, 0, '#ffffff', 12)]),
    sack: (() => {
      const g = new THREE.SphereGeometry(0.2, 12, 8)
      const p = g.attributes.position
      for (let i = 0; i < p.count; i++) {
        const y = p.getY(i)
        p.setXYZ(i, p.getX(i) * 1.05, y * 0.55 + (y > 0 ? 0.02 : 0) * Math.sin(p.getX(i) * 20), p.getZ(i) * 0.85)
      }
      g.computeVertexNormals()
      g.translate(0, 0.11, 0)
      return merge([col(g, '#ffffff'), box(0.1, 0.05, 0.06, 0.17, 0.12, 0, '#b0b0b0')])
    })(),
    log: merge([cyl(0.075, 0.075, 0.52, 0, 0.075, -0.07, '#ffffff', 9, H), cyl(0.07, 0.07, 0.5, 0.02, 0.075, 0.075, '#e8e8e8', 9, H), cyl(0.068, 0.068, 0.48, 0, 0.2, 0, '#f4f4f4', 9, H)]),
    bar: merge([box(0.32, 0.075, 0.11, 0, 0.0375, -0.065, '#ffffff', 0.01), box(0.32, 0.075, 0.11, 0, 0.0375, 0.065, '#f0f0f0', 0.01), box(0.11, 0.075, 0.32, 0, 0.1125, 0, '#e0e0e0', 0.01)]),
    spool: merge([cyl(0.15, 0.15, 0.03, 0, 0.015, 0, '#7a6a5a', 14), cyl(0.12, 0.12, 0.2, 0, 0.13, 0, '#ffffff', 14), cyl(0.15, 0.15, 0.03, 0, 0.245, 0, '#7a6a5a', 14)]),
    roll: merge([cyl(0.13, 0.13, 0.3, 0, 0.13, 0, '#ffffff', 14, H), cyl(0.05, 0.05, 0.31, 0, 0.13, 0, '#8a8a8a', 8, H)]),
  }
  return cargoGeos
}

// ---------------------------------------------------------------- geometry
// The belt's centre line in 3D, from wall to wall: out of the source at
// table height, up its lift, along the run, down the far lift and in.
function beltPath(l) {
  const P = l.pts
  const y = BELT_Y + (l.layer || 0) * BELT_DY
  const n = P.length
  const u0 = dir(P[0], P[1])
  const u1 = dir(P[n - 2], P[n - 1])
  const a = [P[0][0] + u0[0] * OFF, P[0][1] + u0[1] * OFF]
  const b = [P[n - 1][0] - u1[0] * OFF, P[n - 1][1] - u1[1] * OFF]
  const Q = [[P[0][0], LOW, P[0][1]], [a[0], LOW, a[1]], [a[0], y, a[1]]]
  for (let i = 1; i < n - 1; i++) Q.push([P[i][0], y, P[i][1]])
  Q.push([b[0], y, b[1]], [b[0], LOW, b[1]], [P[n - 1][0], LOW, P[n - 1][1]])
  // cumulative lengths and headings
  const cum = [0]
  const yaw = []
  for (let i = 1; i < Q.length; i++) {
    const dx = Q[i][0] - Q[i - 1][0]
    const dz = Q[i][2] - Q[i - 1][2]
    cum.push(cum[i - 1] + Math.hypot(dx, Q[i][1] - Q[i - 1][1], dz))
    yaw.push(Math.abs(dx) + Math.abs(dz) > 1e-4 ? Math.atan2(dx, dz) : null)
  }
  // vertical stretches take the heading of the run next to them
  for (let i = 0; i < yaw.length; i++) if (yaw[i] == null) yaw[i] = i < 2 ? Math.atan2(u0[0], u0[1]) : Math.atan2(u1[0], u1[1])
  return { Q, cum, yaw, y, a, b, u0, u1 }
}
function dir(p, q) {
  const dx = q[0] - p[0]
  const dz = q[1] - p[1]
  const d = Math.hypot(dx, dz) || 1
  return [dx / d, dz / d]
}

// Build a belt's mesh. `lower` holds tiles where a lower belt runs (no post
// may stand there). `ghost` swaps every material for a preview one.
function buildBelt(l, lower, ghost = null) {
  const B = new Builder()
  const L = LOOK[l.tier]
  const path = beltPath(l)
  const { y, a, b, u0, u1 } = path
  const P = l.pts
  const n = P.length
  const m = (k) => ghost || mat(k)
  const F = { mat: L.frame, color: L.frameC, material: ghost || undefined }
  const R = { mat: L.rail, color: L.railC, material: ghost || undefined }
  const Po = { mat: L.post, color: L.postC, material: ghost || undefined }
  // the run: points from lift to lift
  const run = [a, ...P.slice(1, n - 1), b]
  const isCorner = (i) => i > 0 && i < run.length - 1
  let v0 = 0
  for (let i = 0; i < run.length - 1; i++) {
    const p = run[i]
    const q = run[i + 1]
    const [ux, uz] = dir(p, q)
    const len = Math.hypot(q[0] - p[0], q[1] - p[1])
    const t0 = i === 0 ? 0.2 : 0.35
    const t1 = i === run.length - 2 ? 0.2 : 0.35
    const s = len - t0 - t1
    const cx = p[0] + ux * (t0 + s / 2)
    const cz = p[1] + uz * (t0 + s / 2)
    const ry = Math.atan2(ux, uz)
    if (s > 0.01) {
      B.at({ x: cx, y, z: cz, ry }, () => {
        B.box(0.7, 0.12, s, { ...F, y: -0.09 })
        B.box(0.06, 0.15, s, { ...R, x: 0.33, y: 0.03 })
        B.box(0.06, 0.15, s, { ...R, x: -0.33, y: 0.03 })
        // a lip of steel under the deck edges
        B.box(0.04, 0.1, s, { ...F, x: 0.3, y: -0.19 })
        B.box(0.04, 0.1, s, { ...F, x: -0.3, y: -0.19 })
      })
      // the belt itself, its texture running with travel
      const g = new THREE.PlaneGeometry(0.58, s)
      g.rotateX(-Math.PI / 2)
      const uv = g.attributes.uv
      const vs = (v0 + t0) / TEX_M
      for (let k = 0; k < uv.count; k++) uv.setY(k, vs + uv.getY(k) * (s / TEX_M))
      B.add(g, { material: ghost || beltMat(l.tier), x: cx, y: y + 0.001, z: cz, ry: ry + Math.PI, ao: 0, shadow: false })
    }
    v0 += len
    // rollers where each stretch ends
    for (const end of [t0, len - t1]) B.cyl(0.07, 0.07, 0.62, { ...F, x: p[0] + ux * end, y: y - 0.07, z: p[1] + uz * end, rz: Math.PI / 2, ry, order: 'YXZ' })
  }
  // corner turntables
  for (let i = 1; i < run.length - 1; i++) {
    const [ax, az] = dir(run[i - 1], run[i])
    const [bx, bz] = dir(run[i], run[i + 1])
    const c = run[i]
    B.box(0.7, 0.12, 0.7, { ...F, x: c[0], y: y - 0.09, z: c[1] })
    B.cyl(0.29, 0.29, 0.02, { material: ghost || mat('rubber'), color: '#2a2b2e', x: c[0], y: y + 0.005, z: c[1], shadow: false })
    // rails on the two closed sides: ahead of the incoming run, behind the outgoing one
    for (const [sx, sz] of [[ax, az], [-bx, -bz]]) B.box(sx ? 0.06 : 0.76, 0.15, sz ? 0.06 : 0.76, { ...R, x: c[0] + sx * 0.35, y: y + 0.03, z: c[1] + sz * 0.35 })
  }
  // posts: at the corners and every few metres between, never on a lower belt
  const posts = []
  for (let i = 0; i < run.length - 1; i++) {
    const p = run[i]
    const q = run[i + 1]
    const len = Math.hypot(q[0] - p[0], q[1] - p[1])
    const k = Math.max(1, Math.round(len / 3))
    for (let j = i === 0 ? 1 : 0; j < k; j++) {
      const t = j / k
      if ((i === 0 && t * len < 0.9) || (i === run.length - 2 && (1 - t) * len < 0.9 && j > 0)) continue
      posts.push({ x: p[0] + (q[0] - p[0]) * t, z: p[1] + (q[1] - p[1]) * t, ry: Math.atan2(q[0] - p[0], q[1] - p[1]) })
    }
  }
  for (const p of posts) {
    if (lower?.has(Math.floor(p.x) * 4096 + Math.floor(p.z))) continue
    const top = y - 0.2
    B.at({ x: p.x, z: p.z, ry: p.ry }, () => {
      B.box(0.42, 0.08, 0.42, { mat: 'concrete', color: '#8c8a84', material: ghost || undefined, y: 0.04 })
      B.box(0.13, top - 0.08, 0.13, { ...Po, y: 0.08 + (top - 0.08) / 2 })
      B.box(0.9, 0.09, 0.14, { ...Po, y: top - 0.04 })
      B.beam([0, top - 0.55, 0], [0.34, top - 0.08, 0], 0.06, 0.06, Po)
      B.beam([0, top - 0.55, 0], [-0.34, top - 0.08, 0], 0.06, 0.06, Po)
    })
  }
  // the lifts
  const lift = (c, u, out) => {
    const ry = Math.atan2(u[0], u[1])
    const hgt = y + 0.42
    const back = out ? -1 : 1 // the station side
    B.at({ x: c[0], z: c[1], ry }, () => {
      B.box(0.56, 0.07, 0.5, { mat: 'concrete', color: '#8c8a84', material: ghost || undefined, y: 0.035 })
      // four corner posts and braces: an open tower you can see the goods climb
      for (const sx of [-0.27, 0.27]) {
        for (const sz of [-0.17, 0.17]) B.box(0.07, hgt, 0.07, { ...Po, x: sx, y: hgt / 2, z: sz })
        for (const yy of [0.35, hgt * 0.5, hgt - 0.3]) B.box(0.05, 0.05, 0.36, { ...F, x: sx, y: yy })
        B.beam([sx, 0.4, -0.17], [sx, hgt * 0.5 - 0.05, 0.17], 0.035, 0.035, F)
      }
      for (const yy of [0.35, hgt - 0.3]) B.box(0.58, 0.05, 0.05, { ...F, y: yy, z: 0.17 * back })
      // the lift belt runs up a plate on the station side
      B.box(0.5, hgt - 0.55, 0.03, { ...F, y: 0.45 + (hgt - 0.55) / 2, z: 0.14 * back })
      B.box(0.42, hgt - 0.65, 0.012, { material: ghost || beltMat(l.tier), y: 0.5 + (hgt - 0.65) / 2, z: 0.12 * back, shadow: false })
      // head housing with a painted band
      B.box(0.7, 0.18, 0.5, { ...F, y: hgt + 0.03 })
      B.box(0.72, 0.06, 0.52, { ...R, y: hgt + 0.14 })
      B.box(0.6, 0.04, 0.42, { ...F, y: hgt + 0.19 })
      // the tray between the station wall and the lift
      B.box(0.5, 0.05, OFF + 0.12, { ...F, y: LOW - 0.04, z: back * (OFF + 0.12) / 2 })
      B.box(0.04, 0.12, OFF + 0.12, { ...R, x: 0.25, y: LOW, z: back * (OFF + 0.12) / 2 })
      B.box(0.04, 0.12, OFF + 0.12, { ...R, x: -0.25, y: LOW, z: back * (OFF + 0.12) / 2 })
    })
  }
  lift(a, u0, true)
  lift(b, u1, false)
  const g = B.build()
  g.traverse((o) => {
    if (o.isMesh) {
      o.castShadow = !ghost
      o.receiveShadow = !ghost
    }
  })
  // status lamps live outside the merge so their colour can change
  if (!ghost) {
    const lm = new THREE.MeshStandardMaterial({ color: '#202020', emissive: STATE_LAMP.idle, emissiveIntensity: 2.2, roughness: 0.4 })
    for (const [c, u] of [[a, u0], [b, u1]]) {
      const lamp = new THREE.Mesh(LAMP_GEO, lm)
      lamp.position.set(c[0] - u[1] * 0.22, y + 0.69, c[1] + u[0] * 0.22)
      g.add(lamp)
    }
    g.userData.lamp = lm
  }
  g.userData.path = path
  return g
}
const LAMP_GEO = new THREE.BoxGeometry(0.1, 0.1, 0.1)

// A glowing outline around a station footprint (link mode).
function footRing(st, color) {
  const [w, d] = stationSize(st)
  const g = new THREE.Group()
  const m = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.85, depthWrite: false, toneMapped: false })
  const pad = 0.3
  const W = w + pad * 2
  const D = d + pad * 2
  const t = 0.14
  for (const [x, z, sx, sz] of [[0, -D / 2, W, t], [0, D / 2, W, t], [-W / 2, 0, t, D], [W / 2, 0, t, D]]) {
    const b = new THREE.Mesh(new THREE.PlaneGeometry(sx, sz), m)
    b.rotation.x = -Math.PI / 2
    b.position.set(x, 0.07, z)
    g.add(b)
  }
  const fill = new THREE.Mesh(new THREE.PlaneGeometry(W, D), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.1, depthWrite: false, toneMapped: false }))
  fill.rotation.x = -Math.PI / 2
  fill.position.y = 0.06
  g.add(fill)
  g.position.set(st.x + w / 2, 0, st.z + d / 2)
  g.userData.mats = [m, fill.material]
  return g
}

// ---------------------------------------------------------------- the mixin
const _m = new THREE.Matrix4()
const _q = new THREE.Quaternion()
const _p = new THREE.Vector3()
const _s = new THREE.Vector3(1, 1, 1)
const _c = new THREE.Color()
const _up = new THREE.Vector3(0, 1, 0)

export const BeltMixin = {
  initBelts() {
    this.beltViews = new Map()
    this.beltTiles = new Map() // tile key -> link ids running over it
    this.cargo = {}
    const geos = cargo()
    const cm = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.72, metalness: 0.08 })
    for (const k of SHAPES) {
      const mesh = new THREE.InstancedMesh(geos[k], cm, 64)
      mesh.count = 0
      mesh.castShadow = true
      mesh.receiveShadow = true
      mesh.frustumCulled = false
      mesh.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(64 * 3), 3)
      this.scene.add(mesh)
      this.cargo[k] = mesh
    }
    this.syncBelts()
  },
  syncBelts() {
    const live = new Set()
    this.beltTiles.clear()
    for (const l of S.links || []) {
      live.add(l.id)
      for (let i = 0; i < l.tiles.length; i += 2) {
        const key = l.tiles[i] * 4096 + l.tiles[i + 1]
        if (!this.beltTiles.has(key)) this.beltTiles.set(key, [])
        this.beltTiles.get(key).push(l)
      }
    }
    for (const l of S.links || []) {
      const key = `${l.tier}|${l.layer}|${l.pts.join(';')}`
      let v = this.beltViews.get(l.id)
      if (v && v.key === key) {
        v.link = l
        continue
      }
      if (v) this.disposeBeltView(v)
      const lower = new Set()
      for (let i = 0; i < l.tiles.length; i += 2) {
        const k = l.tiles[i] * 4096 + l.tiles[i + 1]
        if ((this.beltTiles.get(k) || []).some((o) => o !== l && (o.layer || 0) < (l.layer || 0))) lower.add(k)
      }
      const g = buildBelt(l, lower)
      g.userData.pick = { type: 'belt', link: l }
      this.scene.add(g)
      v = { link: l, key, group: g, path: g.userData.path, lamp: g.userData.lamp, state: null }
      this.beltViews.set(l.id, v)
    }
    for (const [id, v] of this.beltViews) {
      if (!live.has(id)) {
        this.disposeBeltView(v)
        this.beltViews.delete(id)
      }
    }
  },
  disposeBeltView(v) {
    this.scene.remove(v.group)
    v.group.traverse((o) => {
      if (o.isMesh && o.geometry !== LAMP_GEO) o.geometry.dispose()
    })
    v.lamp?.dispose()
  },
  disposeBelts() {
    for (const v of this.beltViews.values()) this.disposeBeltView(v)
    this.beltViews.clear()
    for (const m of Object.values(this.cargo)) this.scene.remove(m)
    this.cancelLinking()
  },
  // Belt tiles under a footprint that would block placing a station there
  // (belts attached to a station being moved will re-route themselves).
  beltBlocks(x, z, w, d, move = null) {
    for (let i = x; i < x + w; i++) {
      for (let j = z; j < z + d; j++) {
        const ls = this.beltTiles.get(i * 4096 + j)
        if (ls && ls.some((l) => !move || (l.from !== move.id && l.to !== move.id))) return true
      }
    }
    return false
  },
  afterMove(st) {
    const lost = rerouteLinks(st)
    if (lost) this.game.ui?.toast(`${lost} belt${lost > 1 ? 's' : ''} had no way through and came down (fully refunded).`, 'bad')
  },
  // ---- per frame
  updateBelts(dt, simDt) {
    for (let t = 1; t < BELTS.length; t++) if (beltMats[t]) beltMats[t].map.offset.y -= (BELTS[t].speed * simDt) / TEX_M
    const counts = {}
    for (const k of SHAPES) counts[k] = 0
    const grow = (k, need) => {
      const old = this.cargo[k]
      if (need <= old.instanceMatrix.count) return old
      const cap = Math.ceil(need * 1.5)
      const mesh = new THREE.InstancedMesh(old.geometry, old.material, cap)
      mesh.castShadow = mesh.receiveShadow = true
      mesh.frustumCulled = false
      mesh.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(cap * 3), 3)
      this.scene.remove(old)
      old.dispose()
      this.scene.add(mesh)
      this.cargo[k] = mesh
      return mesh
    }
    for (const v of this.beltViews.values()) {
      const l = v.link
      const st = linkState(l)
      if (st !== v.state && v.lamp) {
        v.state = st
        v.lamp.emissive.set(STATE_LAMP[st])
      }
      if (!l.items.length) continue
      const shape = SHAPE[l.res] || 'crate'
      const mesh = grow(shape, counts[shape] + l.items.length)
      _c.set(RES[l.res].color).multiplyScalar(0.82)
      const { Q, cum, yaw } = v.path
      const scale = cum[cum.length - 1] / Math.max(0.01, l.len)
      let seg = cum.length - 2
      for (const d of l.items) {
        const s = Math.min(cum[cum.length - 1], d * scale)
        while (seg > 0 && cum[seg] > s) seg--
        const a = Q[seg]
        const b = Q[seg + 1]
        const t = (s - cum[seg]) / Math.max(1e-6, cum[seg + 1] - cum[seg])
        _p.set(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t + 0.005, a[2] + (b[2] - a[2]) * t)
        _q.setFromAxisAngle(_up, yaw[seg])
        _m.compose(_p, _q, _s)
        const i = counts[shape]++
        mesh.setMatrixAt(i, _m)
        mesh.setColorAt(i, _c)
      }
    }
    for (const k of SHAPES) {
      const mesh = this.cargo[k]
      mesh.count = counts[k]
      if (counts[k]) {
        mesh.instanceMatrix.needsUpdate = true
        mesh.instanceColor.needsUpdate = true
      }
    }
    // link mode: pulse the outlines
    if (this.linking) {
      const k = 0.6 + 0.4 * Math.sin(this.t * 5)
      for (const r of this.linking.rings) for (const m of r.userData.mats) m.opacity = (m.userData.base ??= m.opacity) * (r.userData.hot ? 1 : k)
    }
  },
  beltTip(l) {
    const from = S.stations.find((s) => s.id === l.from)
    const to = S.stations.find((s) => s.id === l.to)
    const st = linkState(l)
    const cap = linkPerDay(l.tier, l.res)
    return `<b>${BELTS[l.tier].name} · ${RES[l.res].name}</b><span>${STATIONS[from?.type]?.name || '?'} → ${STATIONS[to?.type]?.name || '?'} · ${Math.round(l.len)} m</span><span>${l.items.length} on the belt · ${fmt(l.flow * SEC_PER_DAY)} of ${fmt(cap)} a day${st === 'backed' ? ' · <em class="bad">backed up</em>' : st === 'idle' ? ' · idle' : ''}</span>`
  },

  // ---- link mode
  startLinking(st, res, dir = 'out') {
    this.cancelPlacing()
    this.cancelLinking()
    const ok = S.stations.filter((x) => x !== st && !(dir === 'out' ? linkProblem(st, x, res) : linkProblem(x, st, res)))
    const rings = ok.map((x) => footRing(x, isDepot(x) ? '#7ab8ff' : '#4ae0b0'))
    const src = footRing(st, '#ffb040')
    src.userData.hot = true
    rings.push(src)
    for (const r of rings) this.scene.add(r)
    this.linking = { st, res, dir, ok: new Set(ok.map((x) => x.id)), plans: new Map(), hoverId: undefined, ghost: null, rings, ringOf: new Map(ok.map((x, i) => [x.id, rings[i]])) }
    const what = RES[res].name.toLowerCase()
    this.game.ui?.showLinkBar(dir === 'out' ? `Belt ${what} from the ${STATIONS[st.type].name}` : `Belt ${what} into the ${STATIONS[st.type].name}`, ok.length ? (dir === 'out' ? 'Click a station that uses it, or a Storage Depot' : 'Click a station that makes it, or a Storage Depot') : 'Nothing in camp can connect yet')
    sfx('click')
  },
  cancelLinking(reopen = false) {
    const L = this.linking
    if (!L) return
    for (const r of L.rings) {
      this.scene.remove(r)
      r.traverse((o) => o.isMesh && (o.geometry.dispose(), o.material.dispose()))
    }
    this.clearLinkGhost()
    this.linking = null
    this.game.ui?.hideLinkBar()
    this.game.ui?.hoverTip(null)
    document.body.style.cursor = ''
    if (reopen) this.game.ui?.openStation(L.st.id)
  },
  clearLinkGhost() {
    const L = this.linking
    if (!L?.ghost) return
    this.scene.remove(L.ghost)
    L.ghost.traverse((o) => o.isMesh && o.geometry !== LAMP_GEO && o.geometry.dispose())
    L.ghost = null
  },
  linkEnds(other) {
    const L = this.linking
    return L.dir === 'out' ? [L.st, other] : [other, L.st]
  },
  onLinkHover(x, y) {
    const L = this.linking
    const hit = pickAt(x, y, [...this.stationViews.values()].map((v) => v.group))
    const st = hit?.pick?.type === 'station' ? hit.pick.st : null
    const id = st ? st.id : null
    if (id !== L.hoverId) {
      L.hoverId = id
      this.clearLinkGhost()
      for (const r of L.rings) r.userData.hot = r === L.rings[L.rings.length - 1]
      L.tip = null
      if (st && st !== L.st) {
        const [a, b] = this.linkEnds(st)
        let plan = L.plans.get(id)
        if (!plan) {
          plan = planLink(a, b, L.res, 1)
          L.plans.set(id, plan)
        }
        if (plan.why) L.tip = `<b>${STATIONS[st.type].name}</b><span class="bad">${plan.why}</span>`
        else {
          const afford = canAfford(plan.cost)
          const lower = new Set()
          for (let i = 0; i < plan.tiles.length; i += 2) {
            const k = plan.tiles[i] * 4096 + plan.tiles[i + 1]
            if ((this.beltTiles.get(k) || []).some((o) => (o.layer || 0) < plan.layer)) lower.add(k)
          }
          L.ghost = buildBelt({ ...plan, tier: 1, res: L.res }, lower, afford ? GHOST_OK : GHOST_BAD)
          this.scene.add(L.ghost)
          const ring = L.ringOf.get(id)
          if (ring) ring.userData.hot = true
          const cost = Object.entries(plan.cost).map(([k, v]) => `<span class="${(S.res[k] || 0) >= v ? '' : 'bad'}">${v} ${RES[k].short || RES[k].name.toLowerCase()}</span>`).join(' · ')
          L.tip = `<b>${BELTS[1].name} to ${STATIONS[b === L.st ? a.type : b.type].name}</b><span>${Math.round(plan.len)} m · ${cost}</span><span>${afford ? 'Click to build' : 'Not enough materials'}</span>`
        }
      }
      document.body.style.cursor = st && L.ok.has(id) ? 'pointer' : st ? 'not-allowed' : ''
    }
    this.game.ui?.hoverTip(L.tip, x, y)
  },
  onLinkTap(x, y, e) {
    const L = this.linking
    if (e.button === 2) return this.cancelLinking(true)
    const hit = pickAt(x, y, [...this.stationViews.values()].map((v) => v.group))
    const st = hit?.pick?.type === 'station' ? hit.pick.st : null
    if (!st || st === L.st) return
    if (!L.ok.has(st.id)) {
      const [a, b] = this.linkEnds(st)
      this.game.ui?.toast(linkProblem(a, b, L.res) || 'Can\'t connect there', 'bad')
      sfx('error')
      return
    }
    const [a, b] = this.linkEnds(st)
    const r = addLink(a, b, L.res, 1)
    if (typeof r === 'string') {
      this.game.ui?.toast(r, 'bad')
      sfx('error')
      return
    }
    sfx('build')
    this.game.ui?.toast(`Belt built: ${RES[L.res].name.toLowerCase()} from the ${STATIONS[a.type].name} to the ${STATIONS[b.type].name}.`, 'good')
    this.cancelLinking(true)
  },
}
