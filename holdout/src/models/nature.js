// Trees, bushes, rocks, flowers and ground clutter. Each builder returns a
// Group made by the modeling kit; the terrain instances them in bulk.
import * as THREE from 'three'
import { Builder, seeded } from './kit.js'
import { mergeVertices } from 'three/addons/utils/BufferGeometryUtils.js'

const TAU = Math.PI * 2
const pick = (r, a) => a[Math.floor(r() * a.length)]

// A cone with its vertices jittered so foliage layers look ragged.
function raggedCone(b, r, h, o, rnd) {
  const seg = o.seg ?? 12
  const g = new THREE.CylinderGeometry(0.02, r, h, seg, 3, true)
  const p = g.attributes.position
  for (let i = 0; i < p.count; i++) {
    const x = p.getX(i)
    const y = p.getY(i)
    const z = p.getZ(i)
    const k = (y + h / 2) / h
    const a = Math.atan2(z, x)
    // lobed silhouette with drooping branch tips along the lower rim
    const lobe = 1 + Math.sin(a * 5 + (o.ry || 0) * 3) * 0.12 + (rnd() - 0.5) * 0.18 * (1 - k)
    const droop = k < 0.05 ? -h * (0.12 + rnd() * 0.12) : 0
    p.setXYZ(i, x * lobe, y + droop, z * lobe)
  }
  g.computeVertexNormals()
  // normals point more upward/outward for softer shading
  const nrm = g.attributes.normal
  for (let i = 0; i < nrm.count; i++) {
    const nx = nrm.getX(i)
    const ny = nrm.getY(i) + 0.6
    const nz = nrm.getZ(i)
    const l = Math.hypot(nx, ny, nz)
    nrm.setXYZ(i, nx / l, ny / l, nz / l)
  }
  b.add(g, { ...o, ry: o.ry || 0 })
  // underside skirt so the cone isn't hollow from low angles
  const sk = new THREE.CircleGeometry(r * 0.95, seg)
  sk.rotateX(Math.PI / 2)
  b.add(sk, { ...o, y: (o.y || 0) - h / 2 - 0.02, color: shadeLeaf(o.color) })
}
function shadeLeaf(hex) {
  const c = new THREE.Color(hex)
  c.multiplyScalar(0.6)
  return '#' + c.getHexString()
}
// Smooth lumpy sphere: weld the icosphere first so displacement can't crack it.
export function smoothBlob(r, detail, noise, squash, rnd) {
  let g = new THREE.IcosahedronGeometry(r, detail)
  g.deleteAttribute('normal')
  g.deleteAttribute('uv')
  g = mergeVertices(g, 1e-4)
  const p = g.attributes.position
  for (let i = 0; i < p.count; i++) {
    const k = 1 + (rnd() - 0.5) * noise
    p.setXYZ(i, p.getX(i) * k, p.getY(i) * k * squash, p.getZ(i) * k)
  }
  g.computeVertexNormals()
  // spherical UVs so the leaf texture wraps the blob
  const uv = new Float32Array(p.count * 2)
  for (let i = 0; i < p.count; i++) {
    const x = p.getX(i)
    const y = p.getY(i)
    const z = p.getZ(i)
    uv[i * 2] = (Math.atan2(z, x) / (Math.PI * 2) + 0.5) * r * 6
    uv[i * 2 + 1] = (y / r) * r * 2
  }
  g.setAttribute('uv', new THREE.BufferAttribute(uv, 2))
  return g
}
function blob(b, r, o, rnd) {
  b.add(smoothBlob(r, o.detail ?? 2, o.noise ?? 0.3, o.squash ?? 0.85, rnd), o)
}
export function pineModel(seed = 1, o = {}) {
  const rnd = seeded(seed)
  const b = new Builder()
  const h = o.h ?? 7 + rnd() * 3
  const tr = 0.16 + rnd() * 0.06
  b.cyl(tr * 0.35, tr, h * 0.78, { mat: 'bark', color: '#ffffff', y: (h * 0.78) / 2, seg: 8 })
  for (let i = 0; i < 4; i++) {
    const a = rnd() * TAU
    b.beam([0, 0.1, 0], [Math.cos(a) * 0.45, 0, Math.sin(a) * 0.45], 0.14, 0.1, { mat: 'bark', color: '#ffffff' })
  }
  const layers = 6 + Math.floor(rnd() * 3)
  const greens = o.dead ? ['#7a6a46', '#6a5a3a'] : ['#4a6c34', '#557a3a', '#42622e', '#5e823e', '#4e7034']
  for (let i = 0; i < layers; i++) {
    const t = i / (layers - 1)
    const y = h * (0.22 + t * 0.66)
    const r = (1 - t) * (h * 0.27) + 0.35
    raggedCone(b, r, h * 0.26, { mat: 'needle', color: pick(rnd, greens), y, ry: rnd() * TAU, jitter: 0.08 }, rnd)
  }
  raggedCone(b, 0.45, h * 0.2, { mat: 'needle', color: pick(rnd, greens), y: h * 0.93 }, rnd)
  return b.build()
}
export function broadleafModel(seed = 1, o = {}) {
  const rnd = seeded(seed)
  const b = new Builder()
  const h = o.h ?? 5 + rnd() * 2.5
  const lean = (rnd() - 0.5) * 0.15
  b.cyl(0.13, 0.22, h * 0.55, { mat: 'bark', color: '#e8dcd0', y: (h * 0.55) / 2, rz: lean, seg: 9 })
  const greens = o.autumn ? ['#b8702e', '#d0922e', '#9a5826', '#c48a36'] : ['#5a8236', '#669040', '#4e7630', '#74a046', '#5e883a']
  const branches = 4 + Math.floor(rnd() * 3)
  for (let i = 0; i < branches; i++) {
    const a = (i / branches) * TAU + rnd() * 0.6
    const len = 1.2 + rnd() * 1.2
    const y0 = h * (0.42 + rnd() * 0.15)
    const end = [Math.cos(a) * len, y0 + 0.8 + rnd() * 1.0, Math.sin(a) * len]
    b.beam([0, y0, 0], end, 0.11, 0.11, { mat: 'bark', color: '#e8dcd0', round: true })
    blob(b, 1.0 + rnd() * 0.6, { mat: 'leaf', color: pick(rnd, greens), x: end[0], y: end[1] + 0.3, z: end[2], jitter: 0.06 }, rnd)
  }
  for (let i = 0; i < 4; i++) {
    const a = rnd() * TAU
    const d = rnd() * 0.9
    blob(b, 1.2 + rnd() * 0.7, { mat: 'leaf', color: pick(rnd, greens), x: Math.cos(a) * d, y: h * 0.8 + rnd() * 1.2, z: Math.sin(a) * d, jitter: 0.06 }, rnd)
  }
  return b.build()
}
export function deadTreeModel(seed = 1) {
  const rnd = seeded(seed)
  const b = new Builder()
  const h = 4.5 + rnd() * 2.5
  b.cyl(0.08, 0.2, h, { mat: 'bark', color: '#c8bcb0', y: h / 2, seg: 8, rz: (rnd() - 0.5) * 0.1 })
  for (let i = 0; i < 6; i++) {
    const a = rnd() * TAU
    const y0 = h * (0.35 + rnd() * 0.5)
    const len = 0.8 + rnd() * 1.4
    const end = [Math.cos(a) * len, y0 + 0.5 + rnd() * 1.2, Math.sin(a) * len]
    b.beam([0, y0, 0], end, 0.07, 0.07, { mat: 'bark', color: '#c8bcb0', round: true })
    b.beam(end, [end[0] * 1.3 + (rnd() - 0.5) * 0.5, end[1] + 0.4 + rnd() * 0.4, end[2] * 1.3], 0.035, 0.035, { mat: 'bark', color: '#c8bcb0', round: true })
  }
  return b.build()
}
export function bushModel(seed = 1, o = {}) {
  const rnd = seeded(seed)
  const b = new Builder()
  const greens = o.colors ?? ['#4f6e32', '#5a7a38', '#46642c', '#6a8840', '#5e7a3a']
  const n = 3 + Math.floor(rnd() * 3)
  const s = o.s ?? 1
  for (let i = 0; i < n; i++) {
    const a = rnd() * TAU
    const d = rnd() * 0.45 * s
    blob(b, (0.45 + rnd() * 0.35) * s, { mat: 'leaf', color: pick(rnd, greens), x: Math.cos(a) * d, y: (0.35 + rnd() * 0.25) * s, z: Math.sin(a) * d, squash: 0.75 }, rnd)
  }
  if (o.berries) for (let i = 0; i < 10; i++) b.sphere(0.04, { mat: 'gloss', color: o.berries, x: (rnd() - 0.5) * 0.9, y: 0.4 + rnd() * 0.4, z: (rnd() - 0.5) * 0.9, ws: 6, hs: 4 })
  return b.build()
}
export function boulderModel(seed = 1, o = {}) {
  const rnd = seeded(seed)
  const b = new Builder()
  const s = o.s ?? 1
  const tone = pick(rnd, ['#9a948a', '#8a8478', '#a39c90', '#7e786e'])
  let g = new THREE.DodecahedronGeometry(s, 1)
  g.deleteAttribute('normal')
  g.deleteAttribute('uv')
  g = mergeVertices(g, 1e-4)
  const p = g.attributes.position
  for (let i = 0; i < p.count; i++) {
    const k = 1 + (rnd() - 0.5) * 0.25
    p.setXYZ(i, p.getX(i) * k * (1 + rnd() * 0.1), Math.max(-s * 0.25, p.getY(i) * k * 0.62), p.getZ(i) * k)
  }
  g = g.toNonIndexed()
  g.computeVertexNormals()
  b.add(g, { mat: 'concrete', color: tone, y: s * 0.2, ry: rnd() * TAU })
  // moss on top
  if (o.moss !== false) blob(b, s * 0.55, { mat: 'leaf', color: '#5a6e34', y: s * 0.48, squash: 0.25, noise: 0.5 }, rnd)
  if (rnd() < 0.6) {
    const g2 = new THREE.DodecahedronGeometry(s * 0.45, 0)
    b.add(g2, { mat: 'concrete', color: tone, x: s * 0.9, y: s * 0.12, z: s * 0.3, sy: 0.6, ry: rnd() * 3 })
  }
  return b.build()
}
export function stumpModel(seed = 1) {
  const rnd = seeded(seed)
  const b = new Builder()
  const r = 0.22 + rnd() * 0.12
  const h = 0.25 + rnd() * 0.3
  b.cyl(r, r * 1.2, h, { mat: 'bark', color: '#ffffff', y: h / 2, seg: 10 })
  b.cyl(r * 0.92, r * 0.92, 0.015, { mat: 'wood', color: '#e8d0a0', y: h + 0.003, seg: 10 })
  for (let i = 0; i < 4; i++) {
    const a = (i / 4) * TAU + rnd()
    b.box(0.09, 0.1, r * 1.1, { mat: 'bark', color: '#ffffff', x: Math.cos(a) * r, y: 0.04, z: Math.sin(a) * r, ry: -a + Math.PI / 2, rx: 0.4 })
  }
  return b.build()
}
export function fallenLogModel(seed = 1) {
  const rnd = seeded(seed)
  const b = new Builder()
  const len = 3 + rnd() * 2.5
  const r = 0.18 + rnd() * 0.1
  b.cyl(r * 0.9, r, len, { mat: 'bark', color: '#e8e0d8', y: r * 0.9, rz: Math.PI / 2, seg: 10 })
  b.cyl(r * 0.85, r * 0.85, 0.015, { mat: 'wood', color: '#c8a878', x: len / 2 + 0.005, y: r * 0.9, rz: Math.PI / 2, seg: 10 })
  for (let i = 0; i < 3; i++) blob(b, 0.18, { mat: 'leaf', color: '#5a6a30', x: (rnd() - 0.5) * len, y: r * 1.7, z: (rnd() - 0.5) * 0.2, squash: 0.4 }, rnd)
  return b.build()
}
export function flowersModel(seed = 1, o = {}) {
  const rnd = seeded(seed)
  const b = new Builder()
  const cols = o.colors ?? ['#e8d040', '#f0f0e8', '#c84a8a', '#8a6ad8', '#e87a3a']
  const col = pick(rnd, cols)
  for (let i = 0; i < 7; i++) {
    const x = (rnd() - 0.5) * 0.5
    const z = (rnd() - 0.5) * 0.5
    const h = 0.25 + rnd() * 0.25
    b.cyl(0.006, 0.006, h, { mat: 'leaf', color: '#4a7a30', x, y: h / 2, z, seg: 3, shadow: false })
    b.sphere(0.035, { mat: 'leaf', color: col, x, y: h, z, sy: 0.5, ws: 6, hs: 3, shadow: false })
  }
  return b.build()
}
export function reedsModel(seed = 1) {
  const rnd = seeded(seed)
  const b = new Builder()
  for (let i = 0; i < 12; i++) {
    const a = rnd() * TAU
    const d = rnd() * 0.4
    const h = 0.7 + rnd() * 0.7
    b.beam([Math.cos(a) * d, 0, Math.sin(a) * d], [Math.cos(a) * (d + 0.15), h, Math.sin(a) * (d + 0.15)], 0.02, 0.02, { mat: 'leaf', color: pick(rnd, ['#6a7a3a', '#8a8a4a', '#5a6a30']), shadow: false })
    if (rnd() < 0.4) b.capsule(0.025, 0.12, { mat: 'leaf', color: '#5a3a22', x: Math.cos(a) * (d + 0.15), y: h, z: Math.sin(a) * (d + 0.15), seg: 5, shadow: false })
  }
  return b.build()
}

// A grass tuft: a cluster of curved, tapered blades with upward normals.
export function grassTuftGeometry(seed = 1, o = {}) {
  const rnd = seeded(seed)
  const pos = []
  const col = []
  const n = o.blades ?? 9
  const H = o.h ?? 0.5
  for (let i = 0; i < n; i++) {
    const a = rnd() * TAU
    const d = rnd() * 0.12
    const bx = Math.cos(a) * d
    const bz = Math.sin(a) * d
    const h = H * (0.55 + rnd() * 0.6)
    const w = 0.035 + rnd() * 0.025
    const lean = 0.15 + rnd() * 0.35
    const dirA = a + (rnd() - 0.5) * 1.2
    const dx = Math.cos(dirA)
    const dz = Math.sin(dirA)
    const px = -dz
    const pz = dx
    const segs = 3
    let prev = null
    for (let s = 0; s <= segs; s++) {
      const t = s / segs
      const y = h * t
      const off = lean * h * t * t
      const cx = bx + dx * off
      const cz = bz + dz * off
      const ww = w * (1 - t * 0.92)
      const L = [cx - px * ww, y, cz - pz * ww]
      const R = [cx + px * ww, y, cz + pz * ww]
      const shade = 0.42 + t * 0.62
      if (prev) {
        pos.push(...prev.L, ...prev.R, ...R, ...prev.L, ...R, ...L)
        const pc = prev.c
        col.push(pc, pc, pc, pc, pc, pc, shade, shade, shade, pc, pc, pc, shade, shade, shade, shade, shade, shade)
      }
      prev = { L, R, c: shade }
    }
  }
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
  g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3))
  const nrm = new Float32Array(pos.length)
  for (let i = 0; i < nrm.length; i += 3) nrm[i + 1] = 1
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nrm, 3))
  g.computeBoundingSphere()
  return g
}
