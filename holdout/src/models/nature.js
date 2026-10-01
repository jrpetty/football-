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
// ---------------------------------------------------------------- tree parts
// A tapered limb along a smooth curve through pts, radius r0 -> r1.
export function limb(b, pts, r0, r1, o = {}) {
  const curve = new THREE.CatmullRomCurve3(pts.map((p) => new THREE.Vector3(...p)), false, 'catmullrom', 0.4)
  const segs = o.segs ?? Math.max(3, pts.length * 3)
  const rad = o.radial ?? 7
  const frames = curve.computeFrenetFrames(segs, false)
  const pos = []
  const nrm = []
  const uv = []
  const P = new THREE.Vector3()
  for (let i = 0; i <= segs; i++) {
    const t = i / segs
    curve.getPointAt(t, P)
    const r = r0 + (r1 - r0) * Math.pow(t, 0.85)
    const N = frames.normals[i]
    const B = frames.binormals[i]
    for (let j = 0; j <= rad; j++) {
      const a = (j / rad) * TAU
      const cx = Math.cos(a)
      const cy = Math.sin(a)
      const nx = cx * N.x + cy * B.x
      const ny = cx * N.y + cy * B.y
      const nz = cx * N.z + cy * B.z
      pos.push(P.x + nx * r, P.y + ny * r, P.z + nz * r)
      nrm.push(nx, ny, nz)
      uv.push((j / rad) * Math.max(0.3, r * 6), t * curve.getLength() * 0.7)
    }
  }
  const idx = []
  for (let i = 0; i < segs; i++) for (let j = 0; j < rad; j++) {
    const a = i * (rad + 1) + j
    const c = a + rad + 1
    idx.push(a, c, a + 1, c, c + 1, a + 1)
  }
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nrm, 3))
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2))
  g.setIndex(idx)
  b.add(g, { mat: o.mat ?? 'bark', color: o.color ?? '#ffffff', ao: o.ao ?? 0.25 })
  return curve
}
// A foliage card: a quad with 0..1 UVs, centred at the origin, facing +z,
// bent slightly so it doesn't read as a flat sheet.
export function card(b, w, h, o) {
  const g = new THREE.PlaneGeometry(w, h, 2, 2)
  const p = g.attributes.position
  for (let i = 0; i < p.count; i++) {
    const x = p.getX(i) / (w / 2)
    const y = p.getY(i) / (h / 2)
    p.setZ(i, -(x * x + y * y) * (o.bend ?? 0.12) * Math.min(w, h))
  }
  g.computeVertexNormals()
  b.add(g, { ao: 0, ...o })
}
// A clump of leaf cards round a point, plus a dark inner core that fills the
// gaps so the crown reads as solid from a distance.
export function leafClump(b, x, y, z, r, rnd, o = {}) {
  const key = o.mat ?? 'leafCard'
  const n = o.n ?? 9
  const greens = o.colors ?? ['#ffffff', '#f0f4e8', '#e0e8d0', '#f8fff0']
  b.add(smoothBlob(r * 0.5, 1, 0.35, 0.8, rnd), { mat: o.coreMat ?? 'leaf', color: o.core ?? '#4a6a30', x, y, z })
  for (let i = 0; i < n; i++) {
    // spread cards over the sphere, facing outwards, tilted up a little
    const u = rnd() * 2 - 1
    const a = rnd() * TAU
    const sx = Math.sqrt(1 - u * u) * Math.cos(a)
    const sz = Math.sqrt(1 - u * u) * Math.sin(a)
    const sy = u * 0.7 + 0.2
    const d = r * (0.35 + rnd() * 0.35)
    const size = r * (1.25 + rnd() * 0.5)
    card(b, size, size, { mat: key, color: pick(rnd, greens), x: x + sx * d, y: y + sy * d, z: z + sz * d, ry: Math.atan2(sx, sz), rx: -Math.asin(Math.max(-0.9, Math.min(0.9, sy))) * 0.8 + (rnd() - 0.5) * 0.4, rz: rnd() * TAU, order: 'YXZ', jitter: 0.12 })
  }
}

// ---------------------------------------------------------------- trees
// Conifer: a tapered trunk with whorls of drooping needle sprays and a slim
// dark core so the tree has body from every angle.
export function pineModel(seed = 1, o = {}) {
  const rnd = seeded(seed)
  const b = new Builder()
  const h = o.h ?? 7 + rnd() * 3
  const tr = 0.17 + rnd() * 0.06
  limb(b, [[0, -0.1, 0], [0, h * 0.4, 0], [(rnd() - 0.5) * 0.15, h * 0.98, (rnd() - 0.5) * 0.15]], tr, 0.03, { color: '#d8ccc0', radial: 8 })
  for (let i = 0; i < 4; i++) {
    const a = (i / 4) * TAU + rnd()
    limb(b, [[0, 0.35, 0], [Math.cos(a) * 0.3, 0.06, Math.sin(a) * 0.3], [Math.cos(a) * 0.55, -0.05, Math.sin(a) * 0.55]], tr * 0.55, 0.04, { color: '#c8bcb0', radial: 5 })
  }
  const dead = !!o.dead
  const core = dead ? '#5a4a32' : pick(rnd, ['#1a2a16', '#1e3018', '#182614'])
  // the core: two stacked ragged cones
  for (let i = 0; i < 3; i++) {
    const t = i / 3
    const y = h * (0.32 + t * 0.34)
    raggedCone(b, (1 - t) * h * 0.11 + 0.22, h * 0.36, { mat: 'needle', color: core, y, ry: rnd() * TAU }, rnd)
  }
  const whorls = 9 + Math.floor(rnd() * 3)
  const tints = dead ? ['#a88a58', '#b8986a'] : ['#ffffff', '#e8f0e0', '#f0f8e8', '#d8e8d0', '#f8fff0']
  for (let i = 0; i < whorls; i++) {
    const t = i / (whorls - 1)
    const y = h * (0.2 + t * 0.76)
    const reach = (1 - t) * h * 0.3 + 0.35
    const n = Math.max(4, Math.round(7 - t * 3))
    const a0 = rnd() * TAU
    for (let k = 0; k < n; k++) {
      const a = a0 + (k / n) * TAU + (rnd() - 0.5) * 0.4
      const len = reach * (0.85 + rnd() * 0.3)
      // a spray card running outward from the trunk, drooping
      b.at({ x: Math.cos(a) * len * 0.5, y: y - len * 0.12, z: Math.sin(a) * len * 0.5, ry: -a, order: 'YXZ', rz: -0.28 - rnd() * 0.15 }, () => {
        card(b, len * 1.3, len * 0.95, { mat: 'needleCard', color: pick(rnd, tints), rx: -Math.PI / 2 + 0.3, bend: 0.18, jitter: 0.1 })
        card(b, len * 1.1, len * 0.7, { mat: 'needleCard', color: pick(rnd, tints), rx: -0.9, y: -0.08, bend: 0.12, jitter: 0.1 })
      })
    }
  }
  // the leader
  b.at({ y: h * 0.96 }, () => {
    for (let k = 0; k < 3; k++) card(b, 0.5, 1.1, { mat: 'needleCard', color: '#f0f8e8', ry: (k / 3) * Math.PI, rz: Math.PI / 2, bend: 0.05 })
  })
  return b.build()
}
// Broadleaf: a flared trunk that forks into curving limbs; each limb ends in
// clumps of leaf cards around a dark core.
export function broadleafModel(seed = 1, o = {}) {
  const rnd = seeded(seed)
  const b = new Builder()
  const h = o.h ?? 5.5 + rnd() * 2.5
  const autumn = !!o.autumn
  const key = autumn ? 'leafCardAutumn' : 'leafCard'
  const core = autumn ? '#8a5a26' : '#4a6a30'
  const lean = [(rnd() - 0.5) * 0.4, (rnd() - 0.5) * 0.4]
  const fork = [lean[0], h * 0.45, lean[1]]
  const trunkR = 0.2 + rnd() * 0.06
  limb(b, [[0, -0.15, 0], [lean[0] * 0.3, h * 0.2, lean[1] * 0.3], fork], trunkR, trunkR * 0.7, { color: '#b8a898', radial: 9 })
  // root flare
  for (let i = 0; i < 5; i++) {
    const a = (i / 5) * TAU + rnd() * 0.4
    limb(b, [[0, 0.5, 0], [Math.cos(a) * 0.25, 0.12, Math.sin(a) * 0.25], [Math.cos(a) * 0.6, -0.05, Math.sin(a) * 0.6]], trunkR * 0.6, 0.04, { color: '#a89888', radial: 6 })
  }
  const limbs = 3 + Math.floor(rnd() * 3)
  const ends = []
  for (let i = 0; i < limbs; i++) {
    const a = (i / limbs) * TAU + rnd() * 0.8
    const out = 1.2 + rnd() * 1.1
    const up = h * (0.32 + rnd() * 0.2)
    const mid = [fork[0] + Math.cos(a) * out * 0.45, fork[1] + up * 0.55, fork[2] + Math.sin(a) * out * 0.45]
    const end = [fork[0] + Math.cos(a) * out, fork[1] + up, fork[2] + Math.sin(a) * out]
    limb(b, [fork, mid, end], trunkR * 0.62, 0.05, { color: '#b8a898', radial: 7 })
    ends.push(end)
    // a side branch from the middle of each limb
    const a2 = a + (rnd() < 0.5 ? -1 : 1) * (0.7 + rnd() * 0.5)
    const end2 = [mid[0] + Math.cos(a2) * 0.9, mid[1] + 0.5 + rnd() * 0.6, mid[2] + Math.sin(a2) * 0.9]
    limb(b, [mid, [(mid[0] + end2[0]) / 2, (mid[1] + end2[1]) / 2 + 0.1, (mid[2] + end2[2]) / 2], end2], 0.07, 0.03, { color: '#b8a898', radial: 5 })
    ends.push(end2)
  }
  ends.push([fork[0], fork[1] + h * 0.55, fork[2]])
  for (const e of ends) leafClump(b, e[0], e[1] + 0.2, e[2], 0.95 + rnd() * 0.5, rnd, { mat: key, core, n: 9 })
  // fill the middle of the crown
  for (let i = 0; i < 3; i++) {
    const a = rnd() * TAU
    leafClump(b, fork[0] + Math.cos(a) * 0.7, fork[1] + h * 0.33 + rnd() * 0.6, fork[2] + Math.sin(a) * 0.7, 1.1 + rnd() * 0.3, rnd, { mat: key, core, n: 7 })
  }
  return b.build()
}
export function deadTreeModel(seed = 1) {
  const rnd = seeded(seed)
  const b = new Builder()
  const h = 4.5 + rnd() * 2.5
  const top = [(rnd() - 0.5) * 0.5, h, (rnd() - 0.5) * 0.5]
  limb(b, [[0, -0.1, 0], [0.05, h * 0.5, -0.05], top], 0.22, 0.04, { color: '#b8aca0', radial: 8 })
  for (let i = 0; i < 5; i++) {
    const a = rnd() * TAU
    const y0 = h * (0.35 + rnd() * 0.45)
    const len = 0.9 + rnd() * 1.3
    const mid = [Math.cos(a) * len * 0.5, y0 + 0.35, Math.sin(a) * len * 0.5]
    const end = [Math.cos(a) * len, y0 + 0.5 + rnd() * 1.1, Math.sin(a) * len]
    limb(b, [[0, y0, 0], mid, end], 0.08, 0.015, { color: '#b8aca0', radial: 5 })
    const a2 = a + (rnd() - 0.5) * 1.5
    limb(b, [mid, [mid[0] + Math.cos(a2) * 0.6, mid[1] + 0.5 + rnd() * 0.4, mid[2] + Math.sin(a2) * 0.6]], 0.04, 0.01, { color: '#b8aca0', radial: 4 })
  }
  for (let i = 0; i < 4; i++) {
    const a = (i / 4) * TAU + rnd()
    limb(b, [[0, 0.45, 0], [Math.cos(a) * 0.3, 0.1, Math.sin(a) * 0.3], [Math.cos(a) * 0.65, -0.05, Math.sin(a) * 0.65]], 0.12, 0.03, { color: '#a89c90', radial: 5 })
  }
  return b.build()
}
export function bushModel(seed = 1, o = {}) {
  const rnd = seeded(seed)
  const b = new Builder()
  const s = o.s ?? 1
  const tints = o.colors ? o.colors.map((c) => c) : ['#ffffff', '#e8f0d8', '#d8e4c8', '#f0f8e0']
  const n = 3 + Math.floor(rnd() * 2)
  for (let i = 0; i < n; i++) {
    const a = rnd() * TAU
    const d = rnd() * 0.4 * s
    leafClump(b, Math.cos(a) * d, (0.4 + rnd() * 0.2) * s, Math.sin(a) * d, (0.45 + rnd() * 0.25) * s, rnd, { colors: tints, core: '#2e4420', n: 6 })
  }
  if (o.berries) for (let i = 0; i < 14; i++) b.sphere(0.035, { mat: 'gloss', color: o.berries, x: (rnd() - 0.5) * 0.9 * s, y: (0.35 + rnd() * 0.45) * s, z: (rnd() - 0.5) * 0.9 * s, ws: 6, hs: 4 })
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
