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
import { view, pickAt, groundAt } from '../render/view.js'
import { BELTS, RES, STATIONS, SEC_PER_DAY } from '../game/data.js'
import { S, stationSize, canAfford } from '../game/state.js'
import { BELT_Y, BELT_DY, planLink, addLink, linkProblem, linkState, linkPerDay, isDepot, isNode, nodeKind, nodeRes, rerouteLinks, beltSpeed, ensurePorts, portsOf, portAt, linkAt, freePods, inputsOf, outputsOf, beltBlocked } from '../game/belts.js'
import { flowsNow, limitText } from '../game/rates.js'
import { plateMat } from '../models/detail.js'
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

// ---------------------------------------------------------------- pods
// Each pod is a steel panel flush with the wall, framed in its colour, with
// a hatch where goods pass and a plate saying IN or OUT; an arrow painted on
// the ground tile in front shows the way goods go. Brighter once a belt is
// plugged in, with a dot in the colour of what it carries.
const POD_COL = { in: '#2fc49e', out: '#f0962c', io: '#5a9ae0' }
const POD_DIM = { in: '#2d5f54', out: '#7a5330', io: '#3a5675' }
const PDX = [1, 0, -1, 0]
const PDZ = [0, 1, 0, -1]
function decalTexture(two) {
  const c = document.createElement('canvas')
  c.width = c.height = 128
  const g = c.getContext('2d')
  g.fillStyle = '#ffffff'
  const arrow = (flip) => {
    g.save()
    if (flip) {
      g.translate(64, 64)
      g.rotate(Math.PI)
      g.translate(-64, -64)
    }
    g.beginPath()
    if (two) {
      g.moveTo(64, 10)
      g.lineTo(98, 44)
      g.lineTo(76, 44)
      g.lineTo(76, 62)
      g.lineTo(52, 62)
      g.lineTo(52, 44)
      g.lineTo(30, 44)
    } else {
      g.moveTo(64, 12)
      g.lineTo(108, 60)
      g.lineTo(80, 60)
      g.lineTo(80, 112)
      g.lineTo(48, 112)
      g.lineTo(48, 60)
      g.lineTo(20, 60)
    }
    g.closePath()
    g.fill()
    g.restore()
  }
  // a painted bay: a filled square, a bold border, the arrow on top
  g.globalAlpha = 0.32
  g.fillRect(4, 4, 120, 120)
  g.globalAlpha = 0.9
  g.lineWidth = 7
  g.strokeStyle = '#ffffff'
  g.strokeRect(6, 6, 116, 116)
  g.globalAlpha = 1
  arrow(false)
  if (two) arrow(true)
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  return t
}
const decalTex = {}
const decalMats = {}
function decalMat(kind) {
  if (!decalMats[kind]) {
    const tk = kind === 'io' ? 'two' : 'one'
    decalTex[tk] ??= decalTexture(tk === 'two')
    decalMats[kind] = new THREE.MeshBasicMaterial({ map: decalTex[tk], color: POD_COL[kind], transparent: true, opacity: 0.88, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -2 })
  }
  return decalMats[kind]
}
const DECAL_GEO = new THREE.PlaneGeometry(0.92, 0.92)
// a strip of light along the top of each pod, in its colour, so it reads in shade
const stripMats = {}
const stripMat = (kind, lit) => (stripMats[kind + lit] ??= new THREE.MeshStandardMaterial({ color: '#202020', emissive: lit ? POD_COL[kind] : POD_DIM[kind], emissiveIntensity: lit ? 1.6 : 0.8, roughness: 0.5 }))
DECAL_GEO.rotateX(-Math.PI / 2)
// Which way a pod works right now: a two-way hatch takes the direction of its belt.
function podKind(st, p, l = linkAt(st, p.i)) {
  if (p.kind !== 'io') return p.kind
  return l ? (l.from === st.id ? 'out' : 'in') : 'io'
}
function buildPods(st) {
  const ports = portsOf(st)
  const root = new THREE.Group()
  const B = new Builder()
  const node = isNode(st)
  for (const p of ports) {
    const l = linkAt(st, p.i)
    const kind = podKind(st, p, l)
    const ux = PDX[p.dir]
    const uz = PDZ[p.dir]
    if (!node) {
      const c = l ? POD_COL[kind] : POD_DIM[kind]
      B.at({ x: p.ex, z: p.ez, ry: Math.atan2(ux, uz) }, () => {
        B.box(0.82, 1.5, 0.05, { mat: 'steel', color: '#565d64', y: 0.75, z: 0.025 })
        for (const sx of [-1, 1]) B.box(0.09, 1.54, 0.1, { mat: 'paint', color: c, x: sx * 0.39, y: 0.77, z: 0.05 })
        B.box(0.88, 0.14, 0.11, { mat: 'paint', color: c, y: 1.5, z: 0.055 })
        B.box(0.7, 0.06, 0.04, { material: stripMat(kind, !!l), y: 1.6, z: 0.09 })
        B.box(0.52, 0.3, 0.02, { mat: 'plain', color: '#0e0e0e', y: 1.0, z: 0.06 })
        B.box(0.58, 0.04, 0.1, { mat: 'steel', color: '#8a9298', y: 0.84, z: 0.07 })
        B.plane(0.4, 0.2, { material: plateMat(kind === 'in' ? 'IN' : kind === 'out' ? 'OUT' : 'IN / OUT', c, '#101010'), y: 0.56, z: 0.056, shadow: false })
        if (l) B.cyl(0.12, 0.12, 0.04, { mat: 'paint', color: RES[l.res].color, y: 1.59, z: 0.05, seg: 14 })
      })
    }
    const m = new THREE.Mesh(DECAL_GEO, decalMat(kind))
    m.position.set(p.x + 0.5, 0.035, p.z + 0.5)
    // the arrow's tip points to -z before turning: outward for an outtake, at the wall for an intake
    m.rotation.y = kind === 'in' ? Math.atan2(ux, uz) : Math.atan2(-ux, -uz)
    m.renderOrder = 2
    root.add(m)
  }
  if (!node && ports.length) {
    const g = B.build()
    g.traverse((o) => {
      if (o.isMesh) {
        o.castShadow = true
        o.receiveShadow = true
      }
    })
    root.add(g)
  }
  root.userData.pick = { type: 'pods', st }
  return root
}
// The pod of st nearest a point in the world.
export function nearestPod(st, x, z) {
  let best = null
  let bd = Infinity
  for (const p of portsOf(st)) {
    const d = Math.hypot(p.ex - x, p.ez - z)
    if (d < bd) {
      bd = d
      best = p
    }
  }
  return bd < 1.6 ? best : null
}

// Markers for link mode: a glow on each pod a belt could plug into, and a
// pin for each bend the player has set.
const HOT_MAT = new THREE.MeshBasicMaterial({ color: '#8affc0', transparent: true, opacity: 0.55, depthWrite: false, toneMapped: false })
const HOT_GEO = new THREE.RingGeometry(0.3, 0.42, 24)
HOT_GEO.rotateX(-Math.PI / 2)
const PIN_MAT = new THREE.MeshStandardMaterial({ color: '#7ae8ff', emissive: '#2ab8e0', emissiveIntensity: 1.2 })
const PIN_GEO = (() => {
  const a = new THREE.CylinderGeometry(0.05, 0.05, BELT_Y, 8)
  a.translate(0, BELT_Y / 2, 0)
  const b = new THREE.SphereGeometry(0.16, 12, 8)
  b.translate(0, BELT_Y + 0.1, 0)
  const c = new THREE.RingGeometry(0.25, 0.4, 20)
  c.rotateX(-Math.PI / 2)
  c.translate(0, 0.04, 0)
  return mergeGeometries([a.toNonIndexed(), b.toNonIndexed(), c.toNonIndexed()], false)
})()

const ALL_RES = Object.keys(RES).filter((k) => k !== 'cash')
// What a building can put on (take off) a belt; null means anything.
const sends = (st) => (isDepot(st) ? null : isNode(st) ? (nodeRes(st) ? [nodeRes(st)] : null) : outputsOf(st))
const takes = (st) => (isDepot(st) ? null : isNode(st) ? (nodeRes(st) ? [nodeRes(st)] : null) : inputsOf(st))

// ---------------------------------------------------------------- the mixin
const _m = new THREE.Matrix4()
const _q = new THREE.Quaternion()
const _p = new THREE.Vector3()
const _s = new THREE.Vector3(1, 1, 1)
const _c = new THREE.Color()
const _up = new THREE.Vector3(0, 1, 0)
const lerpAngle = (a, b, t) => {
  let d = ((b - a + Math.PI) % (Math.PI * 2)) - Math.PI
  if (d < -Math.PI) d += Math.PI * 2
  return a + d * t
}

export const BeltMixin = {
  initBelts() {
    this.beltViews = new Map()
    this.podViews = new Map()
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
    if (!this.beltViews) return
    ensurePorts()
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
    this.syncPods()
  },
  syncPods() {
    const live = new Set()
    for (const st of S.stations) {
      const ports = portsOf(st)
      if (!ports.length) continue
      live.add(st.id)
      const key = `${st.x},${st.z},${st.rot ? 1 : 0}|` + ports.map((p) => `${p.kind}${p.x},${p.z}${podKind(st, p)}${linkAt(st, p.i)?.res || ''}`).join(';')
      const v = this.podViews.get(st.id)
      if (v && v.key === key) continue
      if (v) this.disposePods(v)
      const group = buildPods(st)
      this.scene.add(group)
      this.podViews.set(st.id, { key, group, st })
    }
    for (const [id, v] of this.podViews) {
      if (live.has(id)) continue
      this.disposePods(v)
      this.podViews.delete(id)
    }
  },
  disposePods(v) {
    this.scene.remove(v.group)
    v.group.traverse((o) => o.isMesh && o.geometry !== DECAL_GEO && o.geometry.dispose())
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
    for (const v of this.podViews.values()) this.disposePods(v)
    this.podViews.clear()
    for (const m of Object.values(this.cargo)) this.scene.remove(m)
    this.cancelLinking()
    this.cancelBeltTool()
  },
  podGroups() {
    return [...(this.podViews?.values() || [])].map((v) => v.group)
  },
  // Belt tiles under a footprint (belts attached to a station being moved
  // re-route themselves; others are routed round a new building).
  beltsUnder(x, z, w, d, move = null) {
    const out = new Set()
    for (let i = x; i < x + w; i++) {
      for (let j = z; j < z + d; j++) {
        for (const l of this.beltTiles.get(i * 4096 + j) || []) if (!move || (l.from !== move.id && l.to !== move.id)) out.add(l)
      }
    }
    return [...out]
  },
  beltBlocks(x, z, w, d, move = null) {
    return this.beltsUnder(x, z, w, d, move).length > 0
  },
  afterMove(st) {
    const lost = rerouteLinks(st)
    if (lost) this.game.ui?.toast(`${lost} belt${lost > 1 ? 's' : ''} had no way through and came down (fully refunded).`, 'bad')
  },
  // A new building went down across belts: they find a way round it.
  rerouteAround(list) {
    if (!list.length) return
    const lost = rerouteLinks(null, list)
    this.game.ui?.toast(lost ? `${lost} belt${lost > 1 ? 's' : ''} had no way round and came down (fully refunded).` : `${list.length} belt${list.length > 1 ? 's' : ''} re-routed round it.`, lost ? 'bad' : '')
  },
  // ---- per frame
  updateBelts(dt, simDt) {
    for (let t = 1; t < BELTS.length; t++) if (beltMats[t]) beltMats[t].map.offset.y -= (beltSpeed(t) * simDt) / TEX_M
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
    const R = 0.35 // goods ease round a corner over this far either side of it
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
      const total = cum[cum.length - 1]
      const scale = total / Math.max(0.01, l.len)
      let seg = cum.length - 2
      for (const d of l.items) {
        const s = Math.min(total, d * scale)
        while (seg > 0 && cum[seg] > s) seg--
        const a = Q[seg]
        const b = Q[seg + 1]
        const t = (s - cum[seg]) / Math.max(1e-6, cum[seg + 1] - cum[seg])
        _p.set(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t + 0.005, a[2] + (b[2] - a[2]) * t)
        // turn smoothly through corners instead of snapping
        let y = yaw[seg]
        const toEnd = cum[seg + 1] - s
        const fromStart = s - cum[seg]
        if (toEnd < R && seg + 1 < yaw.length) y = lerpAngle(y, yaw[seg + 1], 0.5 * (1 - toEnd / R))
        else if (fromStart < R && seg > 0) y = lerpAngle(y, yaw[seg - 1], 0.5 * (1 - fromStart / R))
        _q.setFromAxisAngle(_up, y)
        // goods grow out of the outtake hatch and shrink into the intake
        const k = Math.min(1, s / 0.45, (total - s) / 0.45)
        _s.setScalar(0.3 + 0.7 * Math.max(0, k))
        _m.compose(_p, _q, _s)
        const i = counts[shape]++
        mesh.setMatrixAt(i, _m)
        mesh.setColorAt(i, _c)
      }
    }
    _s.set(1, 1, 1)
    for (const k of SHAPES) {
      const mesh = this.cargo[k]
      mesh.count = counts[k]
      if (counts[k]) {
        mesh.instanceMatrix.needsUpdate = true
        mesh.instanceColor.needsUpdate = true
      }
    }
    // link mode: pulse the outlines and the free pods
    const k = 0.6 + 0.4 * Math.sin(this.t * 5)
    if (this.linking) for (const r of this.linking.rings) for (const m of r.userData.mats) m.opacity = (m.userData.base ??= m.opacity) * (r.userData.hot ? 1 : k)
    if (this.linking || this.beltTool) HOT_MAT.opacity = 0.35 + 0.35 * k
  },
  beltTip(l) {
    const from = S.stations.find((s) => s.id === l.from)
    const to = S.stations.find((s) => s.id === l.to)
    const st = linkState(l)
    const r = flowsNow().links.get(l.id)
    const cap = linkPerDay(l.tier, l.res)
    return `<b>${BELTS[l.tier].name} · ${RES[l.res].name}</b><span>${STATIONS[from?.type]?.name || '?'} → ${STATIONS[to?.type]?.name || '?'} · ${Math.round(l.len)} m</span><span>Moving ${fmt(l.flow * SEC_PER_DAY)} a day now${r ? `, settles at ${fmt(r.flow)}` : ''} (carries up to ${fmt(cap)})${st === 'backed' ? ' · <em class="bad">backed up</em>' : ''}</span>${r ? `<small>${limitText(l, r)}</small>` : ''}<small>Click for the belt</small>`
  },
  podTip(st, p) {
    const l = linkAt(st, p.i)
    const kind = podKind(st, p, l)
    const nm = STATIONS[st.type].name
    const what = kind === 'in' ? 'Intake' : kind === 'out' ? 'Outtake' : 'Hatch'
    const list = (a) => (a ? a.map((k) => RES[k].name.toLowerCase()).join(', ') : 'anything')
    let body
    if (l) {
      const other = S.stations.find((x) => x.id === (l.from === st.id ? l.to : l.from))
      const r = flowsNow().links.get(l.id)
      body = `<span>${RES[l.res].name} ${l.from === st.id ? 'to' : 'from'} the ${STATIONS[other?.type]?.name || '?'}: ${fmt(l.flow * SEC_PER_DAY)} a day now${r ? `, settles at ${fmt(r.flow)}` : ''}</span>${r ? `<small>${limitText(l, r)}</small>` : ''}<small>Click for the belt</small>`
    } else if (isNode(st)) body = `<span>${nodeKind(st) === 'split' ? 'Splitter' : 'Merger'} side: free</span><small>Click to lay a belt from here</small>`
    else if (kind === 'in') body = `<span>Takes ${list(takes(st))}</span><small>Free · click to bring goods in by belt</small>`
    else if (kind === 'out') body = `<span>Sends ${list(sends(st))}</span><small>Free · click to lay a belt from here</small>`
    else body = `<span>Any goods in or out</span><small>Free · click to lay a belt from here</small>`
    return `<b>${what} · ${nm}</b>${body}`
  },
  podHit(x, y) {
    const hit = pickAt(x, y, this.podGroups())
    if (hit?.pick?.type !== 'pods') return null
    const p = nearestPod(hit.pick.st, hit.point.x, hit.point.z)
    return p ? { st: hit.pick.st, p } : null
  },

  // ---- the belt tool: pick any pod to start from
  startBeltTool() {
    this.cancelPlacing?.()
    this.cancelLinking()
    this.cancelBeltTool()
    const marks = []
    for (const st of S.stations) {
      for (const p of portsOf(st)) {
        if (linkAt(st, p.i)) continue
        const m = new THREE.Mesh(HOT_GEO, HOT_MAT)
        m.position.set(p.x + 0.5, 0.06, p.z + 0.5)
        this.scene.add(m)
        marks.push(m)
      }
    }
    this.beltTool = { marks }
    this.game.ui?.showLinkBar('Lay a belt', marks.length ? 'Click an orange outtake to send goods, or a teal intake to bring them in' : 'Nothing in camp has a free pod yet', () => this.cancelBeltTool())
    sfx('click')
  },
  cancelBeltTool() {
    const T = this.beltTool
    if (!T) return
    for (const m of T.marks) this.scene.remove(m)
    this.beltTool = null
    this.game.ui?.hideLinkBar()
  },
  // Start a belt from a pod (or open the belt already plugged into it).
  startFromPod(st, p) {
    const l = linkAt(st, p.i)
    if (l) {
      this.cancelBeltTool()
      this.game.ui?.openBelt(l)
      return
    }
    let dir = p.kind === 'in' ? 'in' : 'out'
    if (nodeKind(st) === 'split') dir = S.links.some((x) => x.to === st.id) ? 'out' : 'in'
    if (nodeKind(st) === 'merge') dir = S.links.some((x) => x.from === st.id) ? 'in' : 'out'
    this.startLinking(st, null, dir, { port: p.i })
  },

  // ---- link mode
  // st: the building the belt starts (dir 'out') or ends (dir 'in') at;
  // res: what it carries, or null to work it out from the far end;
  // opts.port: the pod to use; opts.fromPanel: reopen st's panel after.
  startLinking(st, res = null, dir = 'out', opts = {}) {
    this.cancelPlacing?.()
    this.cancelLinking()
    this.cancelBeltTool()
    const mine = dir === 'out' ? sends(st) : takes(st)
    const L = (this.linking = { st, dir, port: opts.port ?? null, fromPanel: !!opts.fromPanel, res, choices: mine && mine.length > 1 ? mine : null, via: [], viaMarks: [], plans: new Map(), hover: null, ghost: null, rings: [], hot: [], ringOf: new Map(), ok: new Set() })
    if (!res && mine?.length === 1) L.res = mine[0]
    this.refreshLinkTargets()
    sfx('click')
  },
  // What a belt from a to b would carry: the chosen resource, else the best
  // one both ends deal in (what the far end's recipe needs first).
  linkRes(a, b) {
    const L = this.linking
    const A = sends(a)
    const B2 = takes(b)
    let list = (A || ALL_RES).filter((k) => !B2 || B2.includes(k))
    if (L.res) return list.includes(L.res) ? L.res : null
    if (!list.length) return null
    // what each end is working with right now
    const now = (s, side) => {
      const D = STATIONS[s.type]
      const R = D.recipe || (D.recipes && (D.recipes[s.curMode] || D.recipes[s.mode])) || null
      return R ? Object.keys(R[side] || {}) : []
    }
    const makes = now(a, 'out')
    const needs = now(b, 'in')
    // already carried between this pair: least wanted
    const dup = (k) => (S.links || []).some((l) => l.from === a.id && l.to === b.id && l.res === k)
    const score = (k) => (needs.includes(k) ? 4 : 0) + (makes.includes(k) ? 2 : 0) + ((S.res[k] || 0) > 0 ? 1 : 0) - (dup(k) ? 8 : 0)
    list.sort((p, q) => score(q) - score(p))
    return list[0] || null
  },
  linkEnds(other) {
    const L = this.linking
    return L.dir === 'out' ? [L.st, other] : [other, L.st]
  },
  linkOpts(other, port) {
    const L = this.linking
    return L.dir === 'out' ? { fromPort: L.port, toPort: port, via: L.via } : { fromPort: port, toPort: L.port, via: [...L.via].reverse() }
  },
  // Light up every building (and pod) this belt could plug into.
  refreshLinkTargets() {
    const L = this.linking
    for (const r of L.rings) this.scene.remove(r)
    for (const m of L.hot) this.scene.remove(m)
    L.rings = []
    L.hot = []
    L.ringOf.clear()
    L.ok.clear()
    for (const x of S.stations) {
      if (x === L.st) continue
      const [a, b] = this.linkEnds(x)
      const k = this.linkRes(a, b)
      if (!k || linkProblem(a, b, k, this.linkOpts(x, null))) continue
      L.ok.add(x.id)
      const ring = footRing(x, isDepot(x) ? '#7ab8ff' : isNode(x) ? '#ffd27a' : '#4ae0b0')
      L.rings.push(ring)
      L.ringOf.set(x.id, ring)
      this.scene.add(ring)
      for (const p of freePods(x, L.dir === 'out' ? 'in' : 'out')) {
        const m = new THREE.Mesh(HOT_GEO, HOT_MAT)
        m.position.set(p.x + 0.5, 0.06, p.z + 0.5)
        this.scene.add(m)
        L.hot.push(m)
      }
    }
    const src = footRing(L.st, '#ffb040')
    src.userData.hot = true
    L.rings.push(src)
    this.scene.add(src)
    if (L.port != null) {
      const p = portAt(L.st, L.port)
      const m = new THREE.Mesh(HOT_GEO, PIN_MAT)
      m.position.set(p.x + 0.5, 0.07, p.z + 0.5)
      this.scene.add(m)
      L.hot.push(m)
    }
    this.showLinkHelp()
  },
  showLinkHelp() {
    const L = this.linking
    const nm = STATIONS[L.st.type].name
    const what = L.res ? RES[L.res].name.toLowerCase() : 'goods'
    const title = L.dir === 'out' ? `Belt ${what} from the ${nm}` : `Belt ${what} into the ${nm}`
    const hint = !L.ok.size ? 'Nothing in camp can connect' : `${L.dir === 'out' ? 'Click where it goes' : 'Click where it comes from'} · click the ground to add a bend${L.via.length ? ` (${L.via.length}) · right-click removes one` : ''} · Shift keeps going`
    const chips = L.choices ? L.choices.map((k) => ({ k, name: RES[k].short || RES[k].name, color: RES[k].color, on: L.res === k })) : null
    this.game.ui?.showLinkBar(title, hint, () => this.cancelLinking(L.fromPanel), chips, (k) => {
      L.res = L.res === k ? null : k
      L.plans.clear()
      this.clearLinkGhost()
      L.hover = null
      this.refreshLinkTargets()
    })
  },
  cancelLinking(reopen = false) {
    const L = this.linking
    if (!L) return
    for (const r of L.rings) {
      this.scene.remove(r)
      r.traverse((o) => o.isMesh && (o.geometry.dispose(), o.material.dispose()))
    }
    for (const m of [...L.hot, ...L.viaMarks]) this.scene.remove(m)
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
  // What's under the cursor in link mode: a building, and the pod if one.
  linkTarget(x, y) {
    const ph = this.podHit(x, y)
    if (ph) return { st: ph.st, port: ph.p.i }
    const hit = pickAt(x, y, [...this.stationViews.values()].map((v) => v.group))
    const st = hit?.pick?.type === 'station' ? hit.pick.st : null
    return st ? { st, port: null } : null
  },
  planFor(t) {
    const L = this.linking
    const [a, b] = this.linkEnds(t.st)
    const k = this.linkRes(a, b)
    if (!k) return { why: `${STATIONS[t.st.type].name}: nothing to carry between these` }
    let port = t.port
    // a pod of the wrong kind, or taken: let the router pick
    const p = port != null ? portAt(t.st, port) : null
    if (p && (linkAt(t.st, p.i) || (L.dir === 'out' ? p.kind === 'out' : p.kind === 'in'))) port = null
    const key = `${t.st.id}|${port}|${k}|${L.via.join(';')}`
    let plan = L.plans.get(key)
    if (!plan) {
      plan = { ...planLink(a, b, k, 1, this.linkOpts(t.st, port)), res: k, port }
      L.plans.set(key, plan)
    }
    return plan
  },
  onLinkHover(x, y) {
    const L = this.linking
    const t = this.linkTarget(x, y)
    const id = t && t.st !== L.st ? `${t.st.id}|${t.port}` : null
    if (id !== L.hover) {
      L.hover = id
      this.clearLinkGhost()
      for (const r of L.rings) r.userData.hot = r === L.rings[L.rings.length - 1]
      L.tip = null
      if (id) {
        const plan = this.planFor(t)
        const nm = STATIONS[t.st.type].name
        if (plan.why) L.tip = `<b>${nm}</b><span class="bad">${plan.why}</span>`
        else {
          const afford = canAfford(plan.cost)
          const lower = new Set()
          for (let i = 0; i < plan.tiles.length; i += 2) {
            const k = plan.tiles[i] * 4096 + plan.tiles[i + 1]
            if ((this.beltTiles.get(k) || []).some((o) => (o.layer || 0) < plan.layer)) lower.add(k)
          }
          L.ghost = buildBelt({ ...plan, tier: 1 }, lower, afford ? GHOST_OK : GHOST_BAD)
          this.scene.add(L.ghost)
          const ring = L.ringOf.get(t.st.id)
          if (ring) ring.userData.hot = true
          const cost = Object.entries(plan.cost).map(([k, v]) => `${(S.res[k] || 0) >= v ? '' : '<i class="bad">'}${v} ${RES[k].short || RES[k].name.toLowerCase()}${(S.res[k] || 0) >= v ? '' : '</i>'}`).join(' · ')
          L.tip = `<b>${RES[plan.res].name} ${L.dir === 'out' ? 'to' : 'from'} the ${nm}</b><span>${BELTS[1].name} · ${Math.round(plan.len)} m · carries up to ${fmt(linkPerDay(1, plan.res))} a day</span><span>${cost}</span><span>${afford ? 'Click to build' : 'Not enough materials'}</span>`
        }
      }
      document.body.style.cursor = id ? (L.ok.has(t.st.id) ? 'pointer' : 'not-allowed') : 'crosshair'
    }
    this.game.ui?.hoverTip(L.tip, x, y)
  },
  onLinkTap(x, y, e) {
    const L = this.linking
    if (e.button === 2) {
      if (L.via.length) return this.popBend()
      return this.cancelLinking(L.fromPanel)
    }
    const t = this.linkTarget(x, y)
    if (!t || t.st === L.st) return this.addBend(x, y)
    const plan = this.planFor(t)
    if (plan.why) {
      this.game.ui?.toast(plan.why, 'bad')
      sfx('error')
      return
    }
    const [a, b] = this.linkEnds(t.st)
    const r = addLink(a, b, plan.res, 1, { ...this.linkOpts(t.st, plan.port), fromPort: L.dir === 'out' ? L.port : plan.port ?? undefined, toPort: L.dir === 'out' ? plan.port ?? undefined : L.port })
    if (typeof r === 'string') {
      this.game.ui?.toast(r, 'bad')
      sfx('error')
      return
    }
    sfx('build')
    this.game.ui?.toast(`Belt built: ${RES[plan.res].name.toLowerCase()} from the ${STATIONS[a.type].name} to the ${STATIONS[b.type].name}.`, 'good')
    // Shift: lay another from the same building
    if (e.shiftKey) {
      const st = L.st
      const free = freePods(st, L.dir)
      if (free.length) return this.startLinking(st, L.res, L.dir, { port: L.port != null ? free[0].i : null, fromPanel: L.fromPanel })
    }
    this.cancelLinking(L.fromPanel)
  },
  addBend(x, y) {
    const L = this.linking
    const g = groundAt(x, y)
    if (!g) return
    const tx = Math.floor(g.x)
    const tz = Math.floor(g.z)
    const inside = S.stations.some((s) => {
      const [w, d] = stationSize(s)
      return tx >= s.x && tx < s.x + w && tz >= s.z && tz < s.z + d
    })
    if (inside || beltBlocked(tx, tz)) {
      this.game.ui?.toast('A belt can\'t bend there', 'bad')
      sfx('error')
      return
    }
    L.via.push([tx, tz])
    const m = new THREE.Mesh(PIN_GEO, PIN_MAT)
    m.position.set(tx + 0.5, 0, tz + 0.5)
    this.scene.add(m)
    L.viaMarks.push(m)
    L.plans.clear()
    L.hover = undefined
    this.clearLinkGhost()
    this.showLinkHelp()
    sfx('click')
  },
  popBend() {
    const L = this.linking
    L.via.pop()
    const m = L.viaMarks.pop()
    if (m) this.scene.remove(m)
    L.plans.clear()
    L.hover = undefined
    this.clearLinkGhost()
    this.showLinkHelp()
    sfx('click')
  },
}
