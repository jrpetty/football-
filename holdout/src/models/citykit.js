// Map-scale models for the city map: low-poly trees, vehicles and street
// furniture built for instancing by the thousand, building helpers (windows,
// hipped roofs, rooftop clutter, fire escapes, water tanks) and a shared sign
// atlas so every shop front gets its own painted sign in one draw call.
import * as THREE from 'three'
import { Builder } from './kit.js'
import { mat } from '../render/materials.js'
import { foliageMaterial } from '../render/terrain.js'
import { FOLIAGE_KEYS } from '../render/materials.js'

const TAU = Math.PI * 2

// ---------------------------------------------------------------- instancing
// Instanced copies of a template; parts whose material key is listed in
// `tint` take a per-instance colour (vertex colour × instance colour).
export class InstSet {
  constructor(scene, template, max, { tint = [], shadow = true, wind = false } = {}) {
    this.scene = scene
    this.max = max
    this.parts = []
    template.updateMatrixWorld(true)
    template.traverse((o) => {
      if (!o.isMesh) return
      let m = o.material
      const key = m.userData?.key
      if (wind && FOLIAGE_KEYS.has(key)) m = foliageMaterial(key)
      const g = o.geometry.clone()
      g.applyMatrix4(o.matrixWorld)
      const im = new THREE.InstancedMesh(g, m, max)
      im.count = 0
      im.castShadow = shadow && o.castShadow
      im.receiveShadow = true
      im.userData.tinted = tint.includes(key) || tint.includes('*')
      if (im.userData.tinted) im.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(max * 3).fill(1), 3)
      scene.add(im)
      this.parts.push(im)
    })
  }
  // list: [{ x, y, z, ry, rx, rz, s, sy, c }]
  set(list) {
    const m = new THREE.Matrix4()
    const q = new THREE.Quaternion()
    const sc = new THREE.Vector3()
    const p = new THREE.Vector3()
    const e = new THREE.Euler()
    const col = new THREE.Color()
    const n = Math.min(this.max, list.length)
    for (let i = 0; i < n; i++) {
      const it = list[i]
      e.set(it.rx || 0, it.ry || 0, it.rz || 0, 'YXZ')
      q.setFromEuler(e)
      const s = it.s ?? 1
      sc.set(s * (it.sx ?? 1), s * (it.sy ?? 1), s * (it.sz ?? 1))
      m.compose(p.set(it.x, it.y || 0, it.z), q, sc)
      if (it.c) col.set(it.c)
      else col.setRGB(1, 1, 1)
      for (const im of this.parts) {
        im.setMatrixAt(i, m)
        if (im.userData.tinted) im.setColorAt(i, col)
      }
    }
    for (const im of this.parts) {
      im.count = n
      im.instanceMatrix.needsUpdate = true
      if (im.instanceColor) im.instanceColor.needsUpdate = true
      im.computeBoundingSphere()
    }
    return this
  }
  dispose() {
    for (const im of this.parts) {
      this.scene.remove(im)
      im.geometry.dispose()
      im.dispose?.()
    }
  }
}

// World position of a point in the builder's current local space.
const _v = new THREE.Vector3()
const _q = new THREE.Quaternion()
const _s = new THREE.Vector3()
const _e = new THREE.Euler()
export function worldOf(b, x, y, z) {
  _v.set(x, y, z).applyMatrix4(b.top)
  return { x: _v.x, y: _v.y, z: _v.z }
}
export function yawOf(b) {
  b.top.decompose(_v, _q, _s)
  _e.setFromQuaternion(_q, 'YXZ')
  return _e.y
}
// Collects instanced props (in world space) while buildings are assembled.
export class PropList {
  constructor() {
    this.by = {}
  }
  add(kind, x, z, o = {}) {
    ;(this.by[kind] ||= []).push({ x, z, ...o })
  }
  // from builder-local coordinates
  put(b, kind, lx, lz, o = {}) {
    const w = worldOf(b, lx, o.y || 0, lz)
    this.add(kind, w.x, w.z, { ...o, y: w.y, ry: (o.ry || 0) + yawOf(b) })
  }
}

// ---------------------------------------------------------------- palettes
export const LEAF = ['#5f8238', '#56783a', '#6d8a40', '#4a6b32', '#7a9046', '#62803c', '#86904a', '#5a7434']
export const LEAF_AUTUMN = ['#b0823a', '#a65e2c', '#c09a40', '#94742e', '#b8703a']
export const NEEDLE = ['#3e5c36', '#45633a', '#38543a', '#4c6a3e']
export const FOREST = ['#3f5a2c', '#4a6432', '#3a5430', '#52683a', '#44582e', '#4e5e30']
export const FOREST_FIR = ['#2e4a30', '#34502e', '#2a4430', '#3a5636']
export const CARS = ['#6e3430', '#3a4e62', '#c8c4bc', '#3a3e42', '#5a6650', '#9a958e', '#a88a48', '#4e3a4a', '#2a3440', '#7a5a3a', '#d8d4cc', '#8a2e2a', '#2e4a3a']
export const HOUSE_WALLS = ['#d6c8a8', '#a8b6ba', '#c9ad8c', '#9daa8a', '#d8d0bc', '#bba9a0', '#8e9eac', '#e0d8c4', '#c4b8a0', '#b4a48a']
export const HOUSE_ROOFS = ['#4a4644', '#5a4a40', '#6a3a32', '#3e4446', '#4e5248', '#5e5650', '#38393c']
export const BRICKS = ['#ffffff', '#e8d8d0', '#d0c0b8', '#f0e0c8', '#c8b0a0']
export const PLASTER = ['#d8ccb4', '#c8bca8', '#b8b0a0', '#d0c4b0', '#a8a49c', '#c8b49c', '#bcc4c0']

// ---------------------------------------------------------------- trees
export function mapTreeModel(kind) {
  const b = new Builder()
  const bark = { mat: 'bark', color: '#86786a' }
  const leaf = { mat: 'leaf', color: '#ffffff' }
  const needle = { mat: 'needle', color: '#ffffff' }
  if (kind === 'oak') {
    b.cyl(0.2, 0.34, 3.4, { ...bark, y: 1.7, seg: 7 })
    b.beam([0, 2.6, 0], [1.3, 3.9, 0.3], 0.2, 0.2, bark)
    b.beam([0, 2.8, 0], [-1.1, 4.1, -0.5], 0.18, 0.18, bark)
    b.ico(2.5, { ...leaf, y: 5.1, detail: 1, noise: 0.32, sy: 0.82 })
    b.ico(1.9, { ...leaf, x: 1.6, y: 4.4, z: 0.4, detail: 1, noise: 0.34 })
    b.ico(1.8, { ...leaf, x: -1.4, y: 4.6, z: -0.6, detail: 1, noise: 0.34 })
    b.ico(1.5, { ...leaf, x: 0.3, y: 6.0, z: -1.0, detail: 1, noise: 0.3 })
  } else if (kind === 'maple') {
    b.cyl(0.16, 0.26, 2.6, { ...bark, y: 1.3, seg: 6 })
    b.ico(1.9, { ...leaf, y: 4.0, detail: 1, noise: 0.3, sy: 1.15 })
    b.ico(1.3, { ...leaf, x: 0.9, y: 3.4, z: 0.5, detail: 1, noise: 0.3 })
    b.ico(1.2, { ...leaf, x: -0.8, y: 4.6, z: -0.3, detail: 1, noise: 0.3 })
  } else if (kind === 'poplar') {
    b.cyl(0.14, 0.22, 2.2, { ...bark, y: 1.1, seg: 6 })
    b.ico(1.3, { ...leaf, y: 5.0, detail: 1, noise: 0.24, sy: 3.0 })
  } else if (kind === 'pine') {
    b.cyl(0.14, 0.26, 2.4, { ...bark, y: 1.2, seg: 6 })
    for (let k = 0; k < 4; k++) b.cone(2.3 - k * 0.48, 2.7 - k * 0.2, { ...needle, y: 2.9 + k * 1.45, seg: 9, ry: k * 0.7 })
  } else if (kind === 'spruce') {
    b.cyl(0.14, 0.24, 2.0, { ...bark, y: 1.0, seg: 6 })
    for (let k = 0; k < 6; k++) b.cone(1.75 - k * 0.27, 1.9, { ...needle, y: 2.2 + k * 1.15, seg: 8, ry: k * 0.9 })
  } else if (kind === 'dead') {
    b.cyl(0.12, 0.3, 5.2, { ...bark, color: '#7a6e64', y: 2.6, seg: 6 })
    b.beam([0, 3.0, 0], [1.4, 4.6, 0.4], 0.12, 0.12, { ...bark, color: '#7a6e64' })
    b.beam([0, 3.6, 0], [-1.2, 5.0, -0.3], 0.1, 0.1, { ...bark, color: '#7a6e64' })
    b.beam([0, 4.2, 0], [0.3, 5.6, -1.2], 0.08, 0.08, { ...bark, color: '#7a6e64' })
    b.beam([0.7, 3.8, 0.2], [1.6, 4.2, 1.0], 0.07, 0.07, { ...bark, color: '#7a6e64' })
  } else if (kind === 'bush') {
    b.ico(1.0, { ...leaf, y: 0.6, detail: 1, noise: 0.35, sy: 0.7 })
    b.ico(0.75, { ...leaf, x: 0.8, y: 0.45, z: 0.3, detail: 1, noise: 0.35, sy: 0.75 })
    b.ico(0.7, { ...leaf, x: -0.6, y: 0.4, z: -0.4, detail: 1, noise: 0.35, sy: 0.75 })
  } else if (kind === 'clump') {
    // a mass of forest canopy for distant woods
    b.cyl(0.25, 0.4, 3.6, { ...bark, y: 1.8, seg: 5 })
    b.ico(3.6, { ...leaf, y: 6.2, detail: 1, noise: 0.34, sy: 0.8 })
    b.ico(2.6, { ...leaf, x: 2.8, y: 5.2, z: 1.2, detail: 1, noise: 0.34, sy: 0.85 })
    b.ico(2.4, { ...leaf, x: -2.5, y: 5.4, z: -1.5, detail: 1, noise: 0.34, sy: 0.85 })
  } else if (kind === 'firclump') {
    b.cyl(0.25, 0.4, 3, { ...bark, y: 1.5, seg: 5 })
    b.cone(4.2, 9, { ...needle, y: 7, seg: 7 })
    b.cone(3.0, 8, { ...needle, x: 3.4, y: 5.6, z: 1.2, seg: 6 })
    b.cone(3.2, 8.4, { ...needle, x: -2.8, y: 5.8, z: -2.0, seg: 6 })
  } else if (kind === 'hedge') {
    b.box(1, 1, 1, { ...leaf, y: 0.5, r: 0.2, seg: 2 })
  }
  return b.build()
}

// ---------------------------------------------------------------- vehicles
const P = { mat: 'paint', color: '#ffffff' } // tinted per instance
const GL = { mat: 'mapGlass', color: '#3a4652' }
const TY = { mat: 'rubber', color: '#1c1c1c' }
const DK = { mat: 'plain', color: '#26282a' }
function wheels(b, xs, zs, r = 0.34, w = 0.24) {
  for (const x of xs) for (const z of zs) b.cyl(r, r, w, { ...TY, x, y: r, z, rz: Math.PI / 2, seg: 9 })
}
// Body helpers in vehicle space: +z forward, x across, wheels on y = 0.
function carBody(b, L, W, H0, cabL, cabH, cabZ, opt = {}) {
  b.box(W, H0, L, { ...P, y: 0.3 + H0 / 2, r: 0.08, seg: 1 })
  b.box(W + 0.02, 0.14, L + 0.04, { ...DK, y: 0.33 })
  b.box(W - 0.12, cabH, cabL, { ...GL, y: 0.3 + H0 + cabH / 2 - 0.02, z: cabZ })
  b.box(W - 0.16, 0.08, cabL - 0.3, { ...P, y: 0.3 + H0 + cabH + 0.02, z: cabZ - 0.05 })
  if (opt.lightbar) {
    b.box(0.5, 0.1, 0.22, { mat: 'glowBlue', color: '#ffffff', x: -0.27, y: 0.3 + H0 + cabH + 0.12, z: cabZ })
    b.box(0.5, 0.1, 0.22, { mat: 'glowRed', color: '#ffffff', x: 0.27, y: 0.3 + H0 + cabH + 0.12, z: cabZ })
  }
}
export function mapVehicleModel(kind, { burnt = false } = {}) {
  const b = new Builder()
  if (kind === 'sedan' || kind === 'police') {
    carBody(b, 4.5, 1.8, 0.62, 2.1, 0.5, -0.2, { lightbar: kind === 'police' })
    wheels(b, [-0.86, 0.86], [-1.4, 1.35])
    if (kind === 'police') {
      for (const sx of [-1, 1]) b.box(0.02, 0.36, 2.0, { mat: 'plain', color: '#f0f0f0', x: sx * 0.91, y: 0.62, z: 0.1 })
    }
  } else if (kind === 'hatch') {
    carBody(b, 3.9, 1.74, 0.6, 2.0, 0.5, -0.35)
    wheels(b, [-0.84, 0.84], [-1.2, 1.2])
  } else if (kind === 'suv') {
    carBody(b, 4.8, 1.95, 0.78, 2.7, 0.6, -0.4)
    wheels(b, [-0.93, 0.93], [-1.5, 1.45], 0.4, 0.28)
    b.box(1.6, 0.06, 2.2, { ...DK, y: 2.0, z: -0.45 })
  } else if (kind === 'pickup') {
    b.box(1.95, 0.7, 5.4, { ...P, y: 0.7, r: 0.06, seg: 1 })
    b.box(1.85, 0.62, 1.9, { ...GL, y: 1.36, z: 0.55 })
    b.box(1.8, 0.08, 1.6, { ...P, y: 1.7, z: 0.5 })
    b.box(1.95, 0.5, 2.3, { ...P, y: 1.25, z: -1.5 })
    b.box(1.7, 0.45, 2.1, { mat: 'plain', color: '#2e2c2a', y: 1.3, z: -1.5 })
    b.box(1.97, 0.14, 5.44, { ...DK, y: 0.42 })
    wheels(b, [-0.94, 0.94], [-1.75, 1.6], 0.4, 0.28)
  } else if (kind === 'van') {
    b.box(2.0, 1.85, 5.2, { ...P, y: 1.3, r: 0.1, seg: 1 })
    b.box(1.9, 0.6, 0.3, { ...GL, y: 1.75, z: 2.48, rx: -0.25 })
    for (const sx of [-1, 1]) b.box(0.04, 0.5, 0.9, { ...GL, x: sx * 1.0, y: 1.8, z: 1.75 })
    b.box(2.02, 0.16, 5.24, { ...DK, y: 0.42 })
    wheels(b, [-0.96, 0.96], [-1.8, 1.7], 0.38, 0.26)
  } else if (kind === 'ambulance') {
    b.box(2.1, 2.2, 3.6, { ...P, y: 1.5, z: -0.9 })
    b.box(2.0, 1.4, 1.9, { ...P, y: 1.1, z: 1.8, r: 0.1, seg: 1 })
    b.box(1.9, 0.6, 0.2, { ...GL, y: 1.55, z: 2.7, rx: -0.3 })
    for (const sx of [-1, 1]) b.box(0.03, 0.34, 3.5, { mat: 'paint', color: '#c8302a', x: sx * 1.06, y: 1.25, z: -0.9 })
    b.box(1.2, 0.12, 0.3, { mat: 'glowRed', color: '#ffffff', y: 2.66, z: 0.8 })
    b.box(2.12, 0.16, 5.6, { ...DK, y: 0.42, z: 0 })
    wheels(b, [-1.0, 1.0], [-2.0, 1.9], 0.4, 0.28)
  } else if (kind === 'truck') {
    b.box(2.3, 2.6, 6.0, { ...P, y: 1.95, z: -1.3 })
    b.box(2.2, 1.6, 2.1, { mat: 'paint', color: '#d0ccc4', y: 1.35, z: 2.75, r: 0.1, seg: 1 })
    b.box(2.1, 0.7, 0.2, { ...GL, y: 1.75, z: 3.8, rx: -0.2 })
    b.box(2.32, 0.2, 8.4, { ...DK, y: 0.55, z: -0.2 })
    wheels(b, [-1.05, 1.05], [-3.4, -2.3, 2.9], 0.48, 0.32)
  } else if (kind === 'semi') {
    // tractor
    b.box(2.4, 2.2, 3.0, { ...P, y: 1.9, z: 6.2, r: 0.12, seg: 1 })
    b.box(2.3, 0.8, 0.2, { ...GL, y: 2.45, z: 7.72, rx: -0.15 })
    b.box(2.0, 1.0, 1.4, { ...P, y: 1.4, z: 8.2 })
    for (const sx of [-1, 1]) b.cyl(0.1, 0.1, 2.6, { mat: 'chrome', color: '#b8bcc0', x: sx * 1.05, y: 2.9, z: 4.85, seg: 6 })
    // trailer
    b.box(2.5, 2.9, 12.6, { mat: 'paint', color: '#d8d6d0', y: 2.55, z: -2.2 })
    b.box(2.52, 0.2, 13.0, { ...DK, y: 1.0, z: -2.0 })
    wheels(b, [-1.1, 1.1], [-7.6, -6.4, 4.6, 5.8, 7.6], 0.5, 0.36)
  } else if (kind === 'bus' || kind === 'schoolbus') {
    b.box(2.55, 2.75, 11.8, { ...P, y: 1.75, r: 0.12, seg: 1 })
    for (const sx of [-1, 1]) b.box(0.04, 0.85, 10.6, { ...GL, x: sx * 1.28, y: 2.25, z: -0.2 })
    b.box(2.3, 1.1, 0.06, { ...GL, y: 2.0, z: 5.92 })
    if (kind === 'schoolbus') {
      for (const sx of [-1, 1]) b.box(0.03, 0.1, 11.5, { mat: 'plain', color: '#1e1e1e', x: sx * 1.29, y: 1.45 })
      b.box(2.3, 1.0, 1.6, { ...P, y: 1.1, z: 6.6 })
    }
    b.box(2.2, 0.08, 11, { mat: 'plain', color: '#c8c8c4', y: 3.14 })
    wheels(b, [-1.12, 1.12], [-3.8, 3.6], 0.5, 0.32)
  } else if (kind === 'firetruck') {
    b.box(2.5, 2.4, 8.6, { ...P, y: 1.75, z: -0.8 })
    b.box(2.4, 1.7, 2.2, { ...P, y: 1.6, z: 4.4, r: 0.12, seg: 1 })
    b.box(2.3, 0.8, 0.2, { ...GL, y: 2.0, z: 5.5, rx: -0.2 })
    // ladder
    b.box(0.9, 0.24, 8.4, { mat: 'steel', color: '#c8ccd0', y: 3.12, z: -0.9 })
    for (let k = 0; k < 14; k++) b.box(0.86, 0.04, 0.05, { mat: 'steel', color: '#a8acb0', y: 3.26, z: -4.8 + k * 0.6 })
    for (const sx of [-1, 1]) b.box(0.03, 0.14, 8.4, { mat: 'plain', color: '#e8e4d8', x: sx * 1.26, y: 1.25, z: -0.8 })
    b.box(1.6, 0.14, 0.3, { mat: 'glowRed', color: '#ffffff', y: 2.52, z: 4.6 })
    wheels(b, [-1.12, 1.12], [-3.6, -2.4, 3.6], 0.5, 0.34)
  } else if (kind === 'humvee') {
    b.box(2.2, 0.9, 4.8, { ...P, y: 0.95, r: 0.06, seg: 1 })
    b.box(2.0, 0.65, 2.4, { ...P, y: 1.72, z: -0.4 })
    b.box(1.9, 0.5, 0.08, { ...GL, y: 1.72, z: 0.82, rx: -0.15 })
    b.cyl(0.38, 0.4, 0.3, { ...P, y: 2.2, z: -0.4, seg: 8 })
    b.box(0.08, 0.08, 1.0, { mat: 'steel', color: '#3a3c3a', y: 2.32, z: 0.2 })
    wheels(b, [-1.0, 1.0], [-1.5, 1.6], 0.46, 0.34)
  } else if (kind === 'mtruck') {
    // military cargo truck with a canvas cover
    b.box(2.4, 1.8, 2.3, { ...P, y: 1.7, z: 3.0 })
    b.box(2.2, 0.6, 0.1, { ...GL, y: 2.2, z: 4.16 })
    b.box(2.5, 0.5, 5.6, { ...P, y: 1.3, z: -1.4 })
    b.cyl(1.25, 1.25, 5.4, { mat: 'canvas', color: '#7c8158', y: 2.0, z: -1.4, rx: Math.PI / 2, seg: 10, ts: Math.PI / 2, tl: Math.PI })
    wheels(b, [-1.1, 1.1], [-3.2, -2.0, 3.1], 0.55, 0.36)
  } else if (kind === 'heli') {
    b.capsule(1.1, 4.2, { ...P, y: 1.6, rx: Math.PI / 2, seg: 10 })
    b.box(1.6, 0.9, 1.2, { ...GL, y: 1.9, z: 2.6 })
    b.box(0.5, 0.6, 6.5, { ...P, y: 2.0, z: -5.6 })
    b.box(0.1, 1.6, 1.0, { ...P, y: 2.7, z: -8.6 })
    b.cyl(0.18, 0.18, 0.6, { mat: 'steel', color: '#3a3c3a', y: 3.1, seg: 8 })
    for (let k = 0; k < 4; k++) b.box(0.4, 0.06, 7.6, { mat: 'paint', color: '#2a2c2a', y: 3.42, ry: (k * Math.PI) / 4 + 0.3, z: 0 })
    for (const sx of [-1, 1]) b.box(0.1, 0.1, 3.8, { mat: 'steel', color: '#3a3c3a', x: sx * 1.0, y: 0.08, z: 0.3 })
  }
  const g = b.build({ index: false })
  if (burnt) {
    g.traverse((o) => {
      if (!o.isMesh) return
      o.material = mat(o.material.userData?.key === 'rubber' ? 'rubber' : 'rust')
      const c = o.geometry.attributes.color
      if (c) for (let i = 0; i < c.count; i++) c.setXYZ(i, 0.24 + Math.random() * 0.06, 0.22, 0.2)
    })
  }
  return g
}

// ---------------------------------------------------------------- street furniture and props
export function mapPropModel(kind) {
  const b = new Builder()
  const steel = { mat: 'paint', color: '#4a4e52' }
  if (kind === 'lamp') {
    b.cyl(0.08, 0.12, 7.2, { ...steel, y: 3.6, seg: 6 })
    b.beam([0, 7.0, 0], [0, 7.4, 1.6], 0.08, 0.08, steel)
    b.box(0.36, 0.16, 0.7, { ...steel, y: 7.36, z: 1.75 })
    b.box(0.3, 0.04, 0.6, { mat: 'glass', color: '#e8e4d8', y: 7.27, z: 1.75 })
  } else if (kind === 'signal') {
    b.cyl(0.1, 0.14, 6.2, { mat: 'paint', color: '#3a3e40', y: 3.1, seg: 6 })
    b.box(0.14, 0.16, 6.0, { mat: 'paint', color: '#3a3e40', y: 6.0, z: 3.0 })
    for (const z of [2.2, 4.6]) {
      b.box(0.36, 1.0, 0.32, { mat: 'paint', color: '#c8a020', y: 5.4, z })
      b.sphere(0.09, { mat: 'glowRed', color: '#802020', y: 5.7, z, x: 0.17, ws: 6, hs: 4 })
    }
  } else if (kind === 'hydrant') {
    b.cyl(0.13, 0.15, 0.6, { mat: 'paint', color: '#b8322a', y: 0.3, seg: 8 })
    b.sphere(0.13, { mat: 'paint', color: '#b8322a', y: 0.62, ws: 8, hs: 5 })
  } else if (kind === 'bench') {
    b.box(1.8, 0.08, 0.45, { mat: 'wood', color: '#8a6a4a', y: 0.45 })
    b.box(1.8, 0.4, 0.06, { mat: 'wood', color: '#8a6a4a', y: 0.75, z: -0.22, rx: -0.15 })
    for (const sx of [-1, 1]) b.box(0.06, 0.45, 0.45, { ...steel, x: sx * 0.8, y: 0.22 })
  } else if (kind === 'trash') {
    b.cyl(0.28, 0.25, 0.95, { mat: 'paint', color: '#3e4a3c', y: 0.48, seg: 8 })
  } else if (kind === 'mailbox') {
    b.box(0.08, 1.0, 0.08, { mat: 'wood', color: '#6a5a48', y: 0.5 })
    b.box(0.26, 0.24, 0.5, { mat: 'paint', color: '#ffffff', y: 1.1 })
  } else if (kind === 'busstop') {
    b.box(3.6, 0.08, 1.6, { mat: 'paint', color: '#4a5258', y: 2.5 })
    for (const sx of [-1, 1]) b.box(0.08, 2.5, 0.08, { ...steel, x: sx * 1.7, y: 1.25, z: -0.7 })
    b.box(3.4, 2.0, 0.04, { mat: 'glass', color: '#c8d4d8', y: 1.3, z: -0.72 })
    b.box(0.04, 2.0, 1.4, { mat: 'paint', color: '#d8d0b8', x: 1.72, y: 1.3 })
    b.box(2.4, 0.08, 0.4, { mat: 'wood', color: '#8a6a4a', y: 0.48, z: -0.45 })
  } else if (kind === 'pole') {
    // wooden utility pole with a crossarm and insulators
    b.cyl(0.13, 0.17, 9.2, { mat: 'wood', color: '#7a6450', y: 4.6, seg: 6 })
    b.box(2.0, 0.12, 0.12, { mat: 'wood', color: '#6a5444', y: 8.6 })
    for (const x of [-0.85, 0, 0.85]) b.cyl(0.05, 0.06, 0.2, { mat: 'plain', color: '#5a7a68', x, y: 8.76, seg: 5 })
  } else if (kind === 'poleT') {
    b.cyl(0.13, 0.17, 9.2, { mat: 'wood', color: '#7a6450', y: 4.6, seg: 6 })
    b.box(2.0, 0.12, 0.12, { mat: 'wood', color: '#6a5444', y: 8.6 })
    for (const x of [-0.85, 0, 0.85]) b.cyl(0.05, 0.06, 0.2, { mat: 'plain', color: '#5a7a68', x, y: 8.76, seg: 5 })
    b.cyl(0.26, 0.26, 0.8, { mat: 'paint', color: '#8a8e90', y: 7.4, z: 0.32, seg: 8 })
  } else if (kind === 'barrier') {
    // concrete jersey barrier, 3 m
    b.box(0.62, 0.3, 3.0, { mat: 'concrete', color: '#c4c0b6', y: 0.15 })
    b.box(0.3, 0.55, 3.0, { mat: 'concrete', color: '#c4c0b6', y: 0.56 })
  } else if (kind === 'sandbags') {
    for (let k = 0; k < 3; k++) for (let i = 0; i < 4; i++) b.box(0.7, 0.24, 0.42, { mat: 'canvas', color: '#a89a74', x: -1.05 + i * 0.7 + (k % 2) * 0.35, y: 0.12 + k * 0.23, r: 0.08, seg: 1 })
  } else if (kind === 'hesco') {
    b.box(1.5, 1.4, 1.5, { mat: 'canvas', color: '#b0a07a', y: 0.7 })
    b.box(1.52, 0.04, 1.52, { mat: 'steel', color: '#6a6e68', y: 1.4 })
  } else if (kind === 'policeline') {
    b.box(2.4, 0.22, 0.06, { mat: 'paint', color: '#e8e4dc', y: 0.9 })
    for (let k = 0; k < 4; k++) b.box(0.3, 0.22, 0.07, { mat: 'paint', color: '#b02a22', x: -0.9 + k * 0.6, y: 0.9 })
    for (const sx of [-1, 1]) b.box(0.06, 1.0, 0.5, { mat: 'paint', color: '#d8d4cc', x: sx * 1.0, y: 0.5 })
  } else if (kind === 'container') {
    b.box(2.44, 2.6, 12.2, { mat: 'corrugated', color: '#ffffff', y: 1.3 })
    b.box(2.46, 0.12, 12.22, { mat: 'paint', color: '#4a4a48', y: 2.62 })
  } else if (kind === 'dumpster') {
    b.box(1.8, 1.2, 1.1, { mat: 'paint', color: '#ffffff', y: 0.62 })
    b.box(1.84, 0.08, 1.14, { mat: 'paint', color: '#2a2c2a', y: 1.26, rx: 0.12 })
  } else if (kind === 'pallets') {
    for (let k = 0; k < 3; k++) b.box(1.2, 0.14, 1.0, { mat: 'wood', color: '#b89a74', y: 0.07 + k * 0.15 })
    b.box(1.1, 0.7, 0.9, { mat: 'plain', color: '#b48c5a', y: 0.8 })
  } else if (kind === 'tires') {
    for (let k = 0; k < 4; k++) b.torus(0.32, 0.12, { mat: 'rubber', color: '#202020', y: 0.12 + k * 0.22, rx: Math.PI / 2, rs: 5, ts2: 10 })
  } else if (kind === 'rubble') {
    for (let k = 0; k < 7; k++) b.dodeca(0.35 + (k % 3) * 0.2, { mat: 'concrete', color: k % 2 ? '#a8a49c' : '#8a7a6a', x: Math.cos(k * 2.1) * (0.4 + k * 0.12), y: 0.2, z: Math.sin(k * 2.1) * (0.4 + k * 0.1), rx: k, ry: k * 1.3 })
    b.box(1.6, 0.1, 0.24, { mat: 'wood', color: '#6a5a48', y: 0.45, ry: 0.6, rz: 0.2 })
  } else if (kind === 'tent') {
    b.wedge(3.2, 2.0, 4.6, { mat: 'canvas', color: '#ffffff', ry: Math.PI / 2 })
  } else if (kind === 'tomb') {
    b.box(0.6, 0.8, 0.18, { mat: 'concrete', color: '#a8a8a0', y: 0.4, r: 0.08, seg: 2 })
  } else if (kind === 'cross') {
    b.box(0.14, 1.2, 0.14, { mat: 'concrete', color: '#b0b0a8', y: 0.6 })
    b.box(0.6, 0.12, 0.14, { mat: 'concrete', color: '#b0b0a8', y: 0.95 })
  } else if (kind === 'billboard') {
    for (const sx of [-1, 1]) b.cyl(0.2, 0.22, 7, { mat: 'paint', color: '#5a5e60', x: sx * 3, y: 3.5, seg: 6 })
    b.box(9, 3.6, 0.3, { mat: 'paint', color: '#4a4e50', y: 8.2 })
  } else if (kind === 'swing') {
    for (const sx of [-1, 1]) {
      b.beam([sx * 1.6, 0, -0.9], [sx * 1.6, 2.4, 0], 0.08, 0.08, { mat: 'paint', color: '#c84a2a' })
      b.beam([sx * 1.6, 0, 0.9], [sx * 1.6, 2.4, 0], 0.08, 0.08, { mat: 'paint', color: '#c84a2a' })
    }
    b.box(3.3, 0.1, 0.1, { mat: 'paint', color: '#c84a2a', y: 2.4 })
    for (const x of [-0.6, 0.6]) b.box(0.5, 0.06, 0.24, { mat: 'rubber', color: '#2a2a2a', x, y: 0.55 })
  } else if (kind === 'boat') {
    b.box(2.2, 0.9, 6.5, { mat: 'paint', color: '#ffffff', y: 0.45, r: 0.3, seg: 2 })
    b.box(1.6, 0.9, 2.0, { mat: 'paint', color: '#e8e4dc', y: 1.3, z: -0.6 })
    b.box(1.5, 0.4, 0.06, { ...GL, y: 1.45, z: 0.42 })
  } else if (kind === 'boxcar') {
    b.box(3.0, 3.6, 15, { mat: 'corrugated', color: '#ffffff', y: 2.6 })
    b.box(2.8, 0.4, 15.4, { ...DK, y: 0.9 })
    for (const z of [-5.6, -4.4, 4.4, 5.6]) for (const x of [-0.72, 0.72]) b.cyl(0.42, 0.42, 0.14, { mat: 'steel', color: '#3a3a38', x, y: 0.45, z, rz: Math.PI / 2, seg: 8 })
  } else if (kind === 'tanker') {
    b.cyl(1.45, 1.45, 14, { mat: 'paint', color: '#ffffff', y: 2.4, rx: Math.PI / 2, seg: 12 })
    b.box(2.8, 0.4, 15.4, { ...DK, y: 0.9 })
    b.cyl(0.4, 0.4, 0.5, { mat: 'paint', color: '#ffffff', y: 3.9, seg: 8 })
    for (const z of [-5.6, -4.4, 4.4, 5.6]) for (const x of [-0.72, 0.72]) b.cyl(0.42, 0.42, 0.14, { mat: 'steel', color: '#3a3a38', x, y: 0.45, z, rz: Math.PI / 2, seg: 8 })
  } else if (kind === 'flatcar') {
    b.box(2.8, 0.4, 15.4, { ...DK, y: 0.9 })
    b.box(2.44, 2.6, 12.2, { mat: 'corrugated', color: '#ffffff', y: 2.4 })
    for (const z of [-5.6, -4.4, 4.4, 5.6]) for (const x of [-0.72, 0.72]) b.cyl(0.42, 0.42, 0.14, { mat: 'steel', color: '#3a3a38', x, y: 0.45, z, rz: Math.PI / 2, seg: 8 })
  } else if (kind === 'loco') {
    b.box(3.0, 3.8, 17, { mat: 'paint', color: '#ffffff', y: 2.8 })
    b.box(2.9, 1.0, 2.6, { ...GL, y: 4.1, z: 7.2 })
    b.box(2.8, 0.5, 17.4, { ...DK, y: 0.9 })
    for (const z of [-6, -4.6, 4.6, 6]) for (const x of [-0.72, 0.72]) b.cyl(0.46, 0.46, 0.14, { mat: 'steel', color: '#3a3a38', x, y: 0.48, z, rz: Math.PI / 2, seg: 8 })
  } else if (kind === 'tie') {
    b.box(2.6, 0.16, 0.26, { mat: 'wood', color: '#5a4a3c', y: 0.08 })
  } else if (kind === 'crane') {
    // a tower crane over a construction site
    b.box(1.6, 40, 1.6, { mat: 'paint', color: '#d8a828', y: 20 })
    b.box(1.2, 1.4, 46, { mat: 'paint', color: '#d8a828', y: 40.7, z: 12 })
    b.box(3, 3, 4, { mat: 'paint', color: '#d8d4c8', y: 38.5, z: 0 })
    b.box(2.6, 2.6, 6, { mat: 'concrete', color: '#8a8880', y: 40.5, z: -9 })
  } else if (kind === 'pylon') {
    // high-voltage lattice tower
    const legs = [
      [-3, -3],
      [3, -3],
      [3, 3],
      [-3, 3],
    ]
    for (const [x, z] of legs) b.beam([x, 0, z], [x * 0.25, 26, z * 0.25], 0.22, 0.22, { mat: 'steel', color: '#8a9094' })
    for (let k = 0; k < 6; k++) {
      const y = 3 + k * 4
      const s = 1 - (y / 26) * 0.75
      for (let e = 0; e < 4; e++) {
        const [ax, az] = legs[e]
        const [bx, bz] = legs[(e + 1) % 4]
        b.beam([ax * s, y, az * s], [bx * s, y + 4, bz * s], 0.08, 0.08, { mat: 'steel', color: '#8a9094' })
      }
    }
    b.box(12, 0.3, 0.3, { mat: 'steel', color: '#8a9094', y: 22 })
    b.box(8, 0.3, 0.3, { mat: 'steel', color: '#8a9094', y: 26 })
  } else if (kind === 'car-pile') {
    for (let k = 0; k < 4; k++) b.box(1.8, 0.7, 4.2, { mat: 'rust', color: k % 2 ? '#8a6a5a' : '#6a6e72', y: 0.35 + k * 0.72, ry: k * 0.25 - 0.3, x: (k % 2) * 0.3 })
  } else if (kind === 'bale') {
    b.cyl(0.85, 0.85, 1.3, { mat: 'canvas', color: '#d4bc84', rz: Math.PI / 2, seg: 10 })
  } else if (kind === 'booth') {
    b.box(2.4, 2.6, 2.4, { mat: 'paint', color: '#c8ccc4', y: 1.3 })
    b.box(2.5, 1.0, 2.5, { mat: 'mapGlass', color: '#4a5a64', y: 1.7 })
    b.box(2.8, 0.2, 2.8, { mat: 'paint', color: '#5a6248', y: 2.7 })
    b.box(0.15, 0.15, 6, { mat: 'paint', color: '#e8e4dc', y: 1.0, x: 1.4, z: 3.2 })
    for (let k = 0; k < 6; k++) b.box(0.16, 0.16, 0.5, { mat: 'paint', color: '#c8302a', y: 1.0, x: 1.4, z: 0.6 + k * 1.0 })
  } else if (kind === 'crashlight') {
    b.cyl(0.2, 0.3, 0.6, { mat: 'glowRed', color: '#ffffff', y: 0.3, seg: 6 })
  }
  return b.build({ index: false })
}

// ---------------------------------------------------------------- building helpers
// All helpers work in a face/building-local frame: +z out of the facade.

// A row of windows along a facade of length len, for `floors` storeys.
// o: { y0, fh, ww, wh, gap, margin, sill, frame, glass, lit, skip(x, floor), boarded, rnd }
export function facadeWindows(b, len, o) {
  const ww = o.ww ?? 1.2
  const wh = o.wh ?? 1.5
  const gap = o.gap ?? 1.6
  const margin = o.margin ?? 1.2
  const n = Math.max(0, Math.floor((len - margin * 2 + gap) / (ww + gap)))
  if (!n) return
  const span = n * ww + (n - 1) * gap
  const rnd = o.rnd || Math.random
  for (let f = 0; f < (o.floors ?? 1); f++) {
    const y = (o.y0 ?? 0) + f * (o.fh ?? 3) + (o.sillH ?? 0.95) + wh / 2
    for (let k = 0; k < n; k++) {
      const x = -span / 2 + ww / 2 + k * (ww + gap) + (o.dx ?? 0)
      if (o.skip?.(x, f)) continue
      const r = rnd()
      if (o.boarded && r < o.boarded) {
        b.box(ww + 0.2, wh + 0.1, 0.08, { mat: 'planks', color: '#a8906c', x, y, z: 0.06, rz: (rnd() - 0.5) * 0.1 })
        continue
      }
      const lit = o.lit && r > 1 - o.lit
      const broken = !lit && o.broken && r < o.broken
      if (o.frame) b.box(ww + 0.18, wh + 0.18, 0.08, { mat: 'paint', color: o.frame, x, y, z: 0.02 })
      b.box(ww, wh, 0.1, { mat: lit ? 'window' : 'mapGlass', color: broken ? '#141618' : lit ? '#ffd8a0' : o.glass || '#4a5a66', x, y, z: o.frame ? 0.035 : 0.01 })
      if (o.sill) b.box(ww + 0.3, 0.1, 0.2, { mat: 'concrete', color: o.sill, x, y: y - wh / 2 - 0.05, z: 0.08 })
      if (o.shutters) for (const sx of [-1, 1]) b.box(0.42, wh, 0.06, { mat: 'paint', color: o.shutters, x: x + sx * (ww / 2 + 0.28), y, z: 0.04 })
      if (o.ac && rnd() < o.ac && f > 0) b.box(0.7, 0.45, 0.5, { mat: 'paint', color: '#c8c8c0', x, y: y - wh / 2 + 0.25, z: 0.26 })
    }
  }
}
// Run fn on each of a w×d box's four faces (front, back, right, left).
export function eachFace(b, w, d, fn) {
  b.at({ z: d / 2 }, () => fn(w, 'front'))
  b.at({ z: -d / 2, ry: Math.PI }, () => fn(w, 'back'))
  b.at({ x: w / 2, ry: Math.PI / 2 }, () => fn(d, 'right'))
  b.at({ x: -w / 2, ry: -Math.PI / 2 }, () => fn(d, 'left'))
}

// Hipped roof over a w×d footprint at height y.
export function hipRoof(b, w, d, y, rise, o = {}) {
  const ov = o.over ?? 0.45
  const W2 = w / 2 + ov
  const D2 = d / 2 + ov
  const ridge = Math.max(0, W2 - D2)
  const top = y + rise
  const v = [
    // front slope (trapezoid)
    [-W2, y, D2], [W2, y, D2], [ridge, top, 0],
    [-W2, y, D2], [ridge, top, 0], [-ridge, top, 0],
    // back slope
    [W2, y, -D2], [-W2, y, -D2], [-ridge, top, 0],
    [W2, y, -D2], [-ridge, top, 0], [ridge, top, 0],
    // hips
    [W2, y, D2], [W2, y, -D2], [ridge, top, 0],
    [-W2, y, -D2], [-W2, y, D2], [-ridge, top, 0],
  ]
  const pos = new Float32Array(v.length * 3)
  const uv = new Float32Array(v.length * 2)
  const ts = o.uv ?? 2.5
  v.forEach((p, i) => {
    pos.set(p, i * 3)
    uv[i * 2] = (p[0] + p[2] * 0.3) / ts
    uv[i * 2 + 1] = (Math.abs(p[2]) + (p[1] - y) * 1.4) / ts
  })
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3))
  g.setAttribute('uv', new THREE.BufferAttribute(uv, 2))
  g.computeVertexNormals()
  b.add(g, { mat: o.mat || 'shingles', color: o.color || '#4a4644', ao: 0 })
  // eaves underside
  b.box(W2 * 2, 0.12, D2 * 2, { mat: 'plain', color: o.soffit || '#d8d0c0', y: y - 0.06, ao: 0 })
}

// Flat roof: membrane, parapet, and the clutter that sells a rooftop.
export function flatRoof(b, w, d, y, o = {}) {
  const rnd = o.rnd || Math.random
  const par = o.parapet ?? 0.6
  b.box(w - 0.2, 0.1, d - 0.2, { mat: 'roofTar', color: o.roof || '#5a5754', y: y + 0.05, ao: 0 })
  if (par > 0) {
    const pc = o.parColor || '#a8a49c'
    const pm = o.parMat || 'concrete'
    b.box(w, par, 0.3, { mat: pm, color: pc, y: y + par / 2, z: d / 2 - 0.15 })
    b.box(w, par, 0.3, { mat: pm, color: pc, y: y + par / 2, z: -d / 2 + 0.15 })
    b.box(0.3, par, d - 0.6, { mat: pm, color: pc, y: y + par / 2, x: w / 2 - 0.15 })
    b.box(0.3, par, d - 0.6, { mat: pm, color: pc, y: y + par / 2, x: -w / 2 + 0.15 })
    b.box(w + 0.1, 0.08, 0.42, { mat: 'concrete', color: '#c8c4bc', y: y + par + 0.04, z: d / 2 - 0.15 })
  }
  const area = w * d
  const n = o.clutter ?? Math.min(9, Math.floor(area / 90) + 1)
  for (let k = 0; k < n; k++) {
    const x = (rnd() - 0.5) * (w - 4)
    const z = (rnd() - 0.5) * (d - 4)
    const r = rnd()
    if (r < 0.45) {
      // AC unit with fan grille
      b.box(1.6, 1.0, 1.2, { mat: 'paint', color: '#b8bcbc', x, y: y + 0.6, z })
      b.cyl(0.42, 0.42, 0.04, { mat: 'paint', color: '#3a3e40', x, y: y + 1.12, z, seg: 10 })
    } else if (r < 0.62) {
      b.cyl(0.18, 0.18, 1.0, { mat: 'steel', color: '#9a9ea2', x, y: y + 0.5, z, seg: 6 })
      b.cone(0.3, 0.25, { mat: 'steel', color: '#9a9ea2', x, y: y + 1.1, z, seg: 6 })
    } else if (r < 0.75) {
      b.box(1.4, 0.7, 1.4, { mat: 'paint', color: '#8a8e90', x, y: y + 0.45, z })
    } else if (r < 0.85) {
      // satellite dish
      b.cyl(0.05, 0.05, 0.8, { mat: 'steel', color: '#8a8e90', x, y: y + 0.4, z, seg: 5 })
      b.sphere(0.5, { mat: 'paint', color: '#d8d8d0', x, y: y + 0.9, z, ps: 0, pl: TAU, ts: 0, tl: 1.0, rx: -1.0, ws: 10, hs: 4 })
    } else if (r < 0.92) {
      // skylight
      b.box(2.2, 0.35, 1.4, { mat: 'mapGlass', color: '#6a7a84', x, y: y + 0.25, z })
    } else {
      // tarp-covered junk / survivor camp on the roof
      b.box(1.8, 0.5, 1.4, { mat: 'canvas', color: '#3f74b0', x, y: y + 0.3, z, ry: rnd() })
    }
  }
  if (o.hatch !== false) b.box(1.8, 2.4, 2.4, { mat: o.parMat || 'concrete', color: o.parColor || '#a8a49c', x: w / 2 - 2.2, y: y + 1.2, z: -d / 2 + 2.4 })
  if (o.tank) waterTank(b, -w / 2 + 3, y, -d / 2 + 3)
}
// Classic wooden rooftop water tank on a steel stand.
export function waterTank(b, x, y, z) {
  for (const [dx, dz] of [
    [-0.9, -0.9],
    [0.9, -0.9],
    [0.9, 0.9],
    [-0.9, 0.9],
  ])
    b.box(0.14, 2.2, 0.14, { mat: 'steel', color: '#4a4e50', x: x + dx, y: y + 1.1, z: z + dz })
  b.cyl(1.4, 1.4, 2.8, { mat: 'wood', color: '#8a6e54', x, y: y + 3.6, z, seg: 12 })
  b.cone(1.55, 1.1, { mat: 'wood', color: '#6a5444', x, y: y + 5.55, z, seg: 12 })
  for (const k of [0.5, 1.6, 2.6]) b.cyl(1.42, 1.42, 0.06, { mat: 'steel', color: '#3a3a38', x, y: y + 2.2 + k, z, seg: 12 })
}
// Iron fire escape zig-zagging up a facade (face-local, +z out).
export function fireEscape(b, x, floors, fh, y0, w = 3.2) {
  const iron = { mat: 'paint', color: '#2e3032' }
  for (let f = 1; f < floors; f++) {
    const y = y0 + f * fh
    b.box(w, 0.08, 1.2, { ...iron, x, y, z: 0.6 })
    b.box(w, 0.9, 0.04, { ...iron, x, y: y + 0.45, z: 1.2 })
    for (const sx of [-1, 1]) b.box(0.04, 0.9, 1.2, { ...iron, x: x + (sx * w) / 2, y: y + 0.45, z: 0.6 })
    // stair to the next landing
    if (f < floors - 1) b.beam([x - w / 2 + 0.4, y + 0.05, 0.6], [x + w / 2 - 0.4, y + fh - 0.05, 0.6], 0.7, 0.06, iron)
  }
}

// ---------------------------------------------------------------- sign atlas
const SHOP_SIGNS = [
  ['BAKERY', '#7a3a2a', '#f4e4c8'],
  ['LAUNDROMAT', '#2a4a6a', '#e8f0f4'],
  ['PAWN', '#c8a030', '#2a2420'],
  ['LIQUOR', '#8a2a2a', '#f8e8c8'],
  ['BOOKS', '#2e4a3a', '#e8e0c8'],
  ['BARBER', '#e8e4dc', '#a8302a'],
  ['TACOS', '#d86a2a', '#fff4d8'],
  ['PIZZA', '#2a5a2a', '#f8f0d8'],
  ['VIDEO', '#3a2a5a', '#f0d850'],
  ['THRIFT', '#4a6a7a', '#f4f0e0'],
  ['FLORIST', '#6a8a4a', '#fffcf0'],
  ['DELI', '#c8b890', '#3a2a20'],
  ['NAILS', '#d88aa8', '#ffffff'],
  ['DONUTS', '#e8a8c0', '#5a2a3a'],
  ['PHONES', '#1e2a3a', '#58c8f0'],
  ['BANK', '#2a3a4a', '#e0d4b0'],
  ['TAILOR', '#5a4a3a', '#f0e4c8'],
  ['SHOES', '#1e1e1e', '#e8e0d0'],
  ['RECORDS', '#c84a2a', '#1e1e1e'],
  ['TOYS', '#2a6ab0', '#f8d838'],
  ['CAFE', '#4a3a2a', '#f0e0c0'],
  ['BAR & GRILL', '#2a2a2a', '#e8b048'],
  ['NOODLES', '#a82a2a', '#f8e8a8'],
  ['OPTICIAN', '#e8ecec', '#2a4a6a'],
  ['DENTIST', '#e8f0f0', '#2a7a8a'],
  ['INSURANCE', '#2a3a5a', '#e8e8e8'],
  ['TATTOO', '#1a1a1a', '#d84a4a'],
  ['MOTEL', '#2a6a6a', '#f8e0a0'],
  ['GYM', '#c83a2a', '#ffffff'],
  ['ARCADE', '#2a1a4a', '#f058c0'],
  ['CHECKS CASHED', '#e8c838', '#2a2a2a'],
  ['DRY CLEANERS', '#3a6a8a', '#ffffff'],
]
let ATLAS = null
export function signAtlas() {
  if (ATLAS) return ATLAS
  const c = document.createElement('canvas')
  c.width = 1024
  c.height = 1024
  const g = c.getContext('2d')
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  t.anisotropy = 8
  const m = new THREE.MeshStandardMaterial({ map: t, roughness: 0.6, metalness: 0.05, vertexColors: true })
  m.userData.key = 'signAtlas'
  ATLAS = { canvas: c, g, tex: t, material: m, names: new Map(), next: 0 }
  for (const [name, bg, fg] of SHOP_SIGNS) addSign(name, bg, fg)
  return ATLAS
}
// Cells are 256×64 px: 4 across, 16 down.
export function addSign(name, bg = '#2a2e2a', fg = '#e8e0c8') {
  const A = ATLAS || signAtlas()
  const k = name + '|' + bg
  if (A.names.has(k)) return A.names.get(k)
  if (A.next >= 64) return 0
  const i = A.next++
  const x = (i % 4) * 256
  const y = Math.floor(i / 4) * 64
  const g = A.g
  g.fillStyle = bg
  g.fillRect(x, y, 256, 64)
  g.strokeStyle = 'rgba(255,255,255,0.18)'
  g.lineWidth = 3
  g.strokeRect(x + 4, y + 4, 248, 56)
  g.fillStyle = fg
  g.textAlign = 'center'
  g.textBaseline = 'middle'
  let size = 40
  g.font = `700 ${size}px "Saira Condensed", "Arial Narrow", sans-serif`
  while (g.measureText(name).width > 230 && size > 16) {
    size -= 2
    g.font = `700 ${size}px "Saira Condensed", "Arial Narrow", sans-serif`
  }
  g.fillText(name, x + 128, y + 34)
  // grime
  for (let n = 0; n < 40; n++) {
    g.fillStyle = `rgba(20,14,8,${Math.random() * 0.2})`
    g.fillRect(x + Math.random() * 256, y + Math.random() * 64, Math.random() * 20 + 2, Math.random() * 4 + 1)
  }
  A.tex.needsUpdate = true
  A.names.set(k, i)
  return i
}
export const shopSignCount = () => SHOP_SIGNS.length
// A sign plane (w×h, facing +z) showing atlas cell i.
export function signPlane(b, i, w, h, o = {}) {
  const A = signAtlas()
  const g = new THREE.PlaneGeometry(w, h)
  const u0 = (i % 4) / 4
  const v1 = 1 - Math.floor(i / 4) / 16
  const v0 = v1 - 1 / 16
  const uv = g.attributes.uv
  for (let k = 0; k < uv.count; k++) uv.setXY(k, u0 + uv.getX(k) * 0.25, v0 + uv.getY(k) * (v1 - v0))
  b.add(g, { ...o, material: A.material, ao: 0, shadow: false })
}
