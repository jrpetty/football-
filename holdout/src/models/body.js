// Continuous skinned bodies. Torso, limbs, neck and head are lofted surfaces:
// stacks of rounded cross-sections, smoothly interpolated, whose skin weights
// blend across the joints. Clothes are more lofts laid over the same profile
// with a thickness, hems, folds and woven-in patterns, so a character reads
// as one figure instead of a jointed mannequin.
import * as THREE from 'three'
import { mat, texScale } from '../render/materials.js'

const TAU = Math.PI * 2

// ---------------------------------------------------------------- skeleton
// name, parent, offset (bind pose: standing, arms hanging, facing +Z)
export const BONES = [
  ['root', -1, [0, 0, 0]],
  ['hips', 0, [0, 0.98, 0]],
  ['spine', 1, [0, 0.1, 0]],
  ['chest', 2, [0, 0.2, 0]],
  ['neck', 3, [0, 0.2, 0]],
  ['head', 4, [0, 0.08, 0]],
  ['shoulderL', 3, [0.07, 0.15, 0]],
  ['upperArmL', 6, [0.12, 0, 0]],
  ['foreArmL', 7, [0, -0.28, 0]],
  ['handL', 8, [0, -0.255, 0]],
  ['shoulderR', 3, [-0.07, 0.15, 0]],
  ['upperArmR', 10, [-0.12, 0, 0]],
  ['foreArmR', 11, [0, -0.28, 0]],
  ['handR', 12, [0, -0.255, 0]],
  ['thighL', 1, [0.095, -0.05, 0]],
  ['shinL', 14, [0, -0.44, 0]],
  ['footL', 15, [0, -0.43, 0]],
  ['thighR', 1, [-0.095, -0.05, 0]],
  ['shinR', 17, [0, -0.44, 0]],
  ['footR', 18, [0, -0.43, 0]],
]
export const BONE = Object.fromEntries(BONES.map((b, i) => [b[0], i]))
export const NB = BONES.length
function bindMatrices() {
  const objs = BONES.map(([, , off]) => {
    const o = new THREE.Object3D()
    o.position.set(...off)
    return o
  })
  BONES.forEach(([, p], i) => p >= 0 && objs[p].add(objs[i]))
  objs[0].updateMatrixWorld(true)
  return objs.map((o) => o.matrixWorld.clone())
}
export const BIND = bindMatrices()

// ---------------------------------------------------------------- small maths
export const smooth = (a, b, x) => {
  const t = Math.min(1, Math.max(0, (x - a) / (b - a)))
  return t * t * (3 - 2 * t)
}
const clamp01 = (x) => (x < 0 ? 0 : x > 1 ? 1 : x)
function h3(x, y, z) {
  let h = (x * 374761393 + y * 668265263 + z * 1274126177) | 0
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296
}
// smooth 3D value noise in 0..1
export function vn3(x, y, z) {
  const xi = Math.floor(x)
  const yi = Math.floor(y)
  const zi = Math.floor(z)
  const xf = x - xi
  const yf = y - yi
  const zf = z - zi
  const u = xf * xf * (3 - 2 * xf)
  const v = yf * yf * (3 - 2 * yf)
  const w = zf * zf * (3 - 2 * zf)
  const l = (a, b, t) => a + (b - a) * t
  const c00 = l(h3(xi, yi, zi), h3(xi + 1, yi, zi), u)
  const c10 = l(h3(xi, yi + 1, zi), h3(xi + 1, yi + 1, zi), u)
  const c01 = l(h3(xi, yi, zi + 1), h3(xi + 1, yi, zi + 1), u)
  const c11 = l(h3(xi, yi + 1, zi + 1), h3(xi + 1, yi + 1, zi + 1), u)
  return l(l(c00, c10, v), l(c01, c11, v), w)
}
export const fbm3 = (x, y, z) => vn3(x, y, z) * 0.6 + vn3(x * 2.13 + 7.1, y * 2.13, z * 2.13) * 0.28 + vn3(x * 4.7, y * 4.7 + 3.3, z * 4.7) * 0.12

// ---------------------------------------------------------------- parts
const FIELDS = ['x', 'z', 'w', 'f', 'k', 'n', 'nb']
function tangents(xs, v) {
  const n = xs.length
  const d = []
  for (let i = 0; i < n - 1; i++) d.push((v[i + 1] - v[i]) / Math.max(1e-6, xs[i + 1] - xs[i]))
  const m = new Array(n)
  m[0] = d[0]
  m[n - 1] = d[n - 2]
  for (let i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2
  // Fritsch–Carlson: keep the interpolation monotone so profiles never ripple
  for (let i = 0; i < n - 1; i++) {
    if (d[i] === 0) {
      m[i] = 0
      m[i + 1] = 0
      continue
    }
    const a = m[i] / d[i]
    const b = m[i + 1] / d[i]
    const s = a * a + b * b
    if (s > 9) {
      const t = 3 / Math.sqrt(s)
      m[i] = t * a * d[i]
      m[i + 1] = t * b * d[i]
    }
  }
  return { v, m }
}

// A Part is a stack of key cross-sections along an axis (y by default; z for
// feet). Key: { y (position along the axis), x, z (centre; for a z-axis part
// z is the height), w (half width), f / k (front / back half depth — top /
// bottom on a z part), n / nb (superellipse power front / back), wt: {bone: w} }.
// Right-side limbs are built from left-side keys with mir = -1.
export class Part {
  constructor(keys, o = {}) {
    const mir = o.mir || 1
    keys = keys.map((k) => ({ x: 0, z: 0, n: 2, ...k, nb: k.nb ?? k.n ?? 2 })).map((k) => ({ ...k, x: k.x * mir, wt: mirWt(k.wt, mir) }))
    keys.sort((a, b) => a.y - b.y)
    this.keys = keys
    this.axis = o.axis || 'y'
    this.mir = mir
    this.bumps = o.bumps || []
    this.ys = keys.map((k) => k.y)
    this.T = {}
    for (const f of FIELDS) this.T[f] = tangents(this.ys, keys.map((k) => k[f]))
    this.W = keys.map((k) => Object.entries(k.wt || { root: 1 }).map(([b, w]) => [BONE[b], w]))
    this.y0 = this.ys[0]
    this.y1 = this.ys[this.ys.length - 1]
    this.shells = []
  }
  at(y, R = {}) {
    const ys = this.ys
    let i
    let t
    if (y <= ys[0]) {
      i = 0
      t = 0
    } else if (y >= ys[ys.length - 1]) {
      i = ys.length - 2
      t = 1
    } else {
      let lo = 0
      let hi = ys.length - 1
      while (hi - lo > 1) {
        const mid = (lo + hi) >> 1
        if (ys[mid] <= y) lo = mid
        else hi = mid
      }
      i = lo
      t = (y - ys[lo]) / (ys[lo + 1] - ys[lo])
    }
    const h = ys[i + 1] - ys[i]
    const t2 = t * t
    const t3 = t2 * t
    const a = 2 * t3 - 3 * t2 + 1
    const b = t3 - 2 * t2 + t
    const c = -2 * t3 + 3 * t2
    const d = t3 - t2
    for (const f of FIELDS) {
      const F = this.T[f]
      R[f] = a * F.v[i] + b * h * F.m[i] + c * F.v[i + 1] + d * h * F.m[i + 1]
    }
    R.w = Math.max(1e-4, R.w)
    R.f = Math.max(1e-4, R.f)
    R.k = Math.max(1e-4, R.k)
    R.i = i
    R.t = t
    return R
  }
  // anatomical bumps (muscle, bust, brow...) as a radial offset
  bumpAt(y, lx, lz) {
    let d = 0
    for (const b of this.bumps) {
      const dy = (y - b.y) / b.sy
      if (dy > 3 || dy < -3) continue
      const dx = ((b.sym ? Math.abs(lx) : lx) - b.x) / b.sx
      if (dx > 3 || dx < -3) continue
      const sf = b.s ? smooth(-0.15, 0.55, b.s * lz) : 1
      d += b.a * Math.exp(-(dy * dy + dx * dx)) * sf
    }
    return d
  }
  // outermost clothing offset recorded at y (for placing accessories)
  // A layer may measure what is under it with shellAt; while its own offset
  // is being worked out it is skipped, so it never counts itself (a vest
  // recorded over a shirt recursed forever on some bodies).
  shellAt(y, lz = 1) {
    let o = 0
    for (const s of this.shells) {
      if (s.busy || !(y >= s.y0 - 1e-3 && y <= s.y1 + 1e-3 && (s.side == null || s.side * lz > 0))) continue
      s.busy = true
      try {
        o = Math.max(o, s.off(y))
      } finally {
        s.busy = false
      }
    }
    return o
  }
  // A point on the outer surface at height y, lateral offset lx (mirrored
  // for right limbs) on the front (side 1) or back (-1). Returns position and
  // outward normal in bind space.
  surf(y, lx = 0, side = 1, extra = 0) {
    const p = this._pt(y, lx, side)
    const e = 0.004
    const py = this._pt(y + e, lx, side)
    const px = this._pt(y, lx + e * this.mir * (side > 0 ? 1 : 1), side)
    const ty = [py[0] - p[0], py[1] - p[1], py[2] - p[2]]
    const tx = [px[0] - p[0], px[1] - p[1], px[2] - p[2]]
    let n = [ty[1] * tx[2] - ty[2] * tx[1], ty[2] * tx[0] - ty[0] * tx[2], ty[0] * tx[1] - ty[1] * tx[0]]
    const l = Math.hypot(n[0], n[1], n[2]) || 1
    n = n.map((v) => v / l)
    if (n[2] * side < 0) n = n.map((v) => -v)
    const R = this.at(y, {})
    const lz = side
    const d = this.bumpAt(y, lx, lz) + this.shellAt(y, side) + extra
    return { p: [p[0] + n[0] * d, p[1] + n[1] * d, p[2] + n[2] * d], n, R }
  }
  _pt(y, lx, side) {
    const R = this.at(y, {})
    const ux = Math.min(1, Math.abs(lx) / R.w)
    const n = side > 0 ? R.n : R.nb
    const c = Math.pow(ux, n / 2)
    const s = Math.sqrt(Math.max(0, 1 - c * c))
    const az = Math.pow(s, 2 / n)
    const x = R.x + Math.sign(lx) * this.mir * ux * R.w
    const z = R.z + side * (side > 0 ? R.f : R.k) * az
    return this.axis === 'z' ? [x, z, y] : [x, y, z]
  }
}
// Limb keys name bones without a side ('foreArm'); resolve them for this side.
function mirWt(wt, mir) {
  if (!wt) return wt
  const o = {}
  for (const [k, v] of Object.entries(wt)) {
    let n = k
    if (BONE[k] === undefined) n = k + (mir > 0 ? 'L' : 'R')
    else if (mir < 0 && k.endsWith('L')) n = k.slice(0, -1) + 'R'
    o[n] = (o[n] || 0) + v
  }
  return o
}

// ---------------------------------------------------------------- loft sink
// Growable typed array.
class GA {
  constructor(T, n = 4096) {
    this.T = T
    this.a = new T(n)
    this.length = 0
  }
  grow(k) {
    if (this.length + k <= this.a.length) return
    const b = new this.T(Math.max(this.a.length * 2, this.length + k))
    b.set(this.a)
    this.a = b
  }
  push(...v) {
    this.grow(v.length)
    for (let i = 0; i < v.length; i++) this.a[this.length++] = v[i]
  }
  push3(x, y, z) {
    this.grow(3)
    const a = this.a
    const i = this.length
    a[i] = x
    a[i + 1] = y
    a[i + 2] = z
    this.length = i + 3
  }
  out() {
    return this.a.slice(0, this.length)
  }
}
// Collects indexed surfaces per material; flushSink turns them into geometry.
export class Sink {
  constructor() {
    this.m = new Map()
  }
  get(key) {
    let s = this.m.get(key)
    if (!s) this.m.set(key, (s = { p: new GA(Float32Array), n: new GA(Float32Array), uv: new GA(Float32Array), c: new GA(Float32Array), si: new GA(Uint16Array), sw: new GA(Float32Array), idx: new GA(Uint32Array) }))
    return s
  }
}

const _R = {}
const _acc = new Float32Array(NB)
const _c = new THREE.Color()
function top4(acc, outI, outW, o) {
  // pick the four strongest bones and normalise
  let tot = 0
  for (let k = 0; k < 4; k++) {
    let bi = 0
    let bw = -1
    for (let b = 0; b < NB; b++) if (acc[b] > bw) (bw = acc[b]), (bi = b)
    outI[o + k] = bi
    outW[o + k] = Math.max(0, bw)
    tot += Math.max(0, bw)
    acc[bi] = -1
  }
  for (let k = 0; k < 4; k++) outW[o + k] /= tot || 1
}

// Lay one surface over a part.
// L: { mat, col: hex | fn(ctx) setting ctx.c, y0 / y1: number | fn(theta),
//      th0 / th1 (open sheet; full ring when omitted), cols, dy,
//      off: number | fn(y, th, lx, lz), bump: part-bump scale (1),
//      hemLo / hemHi: { t, h } rolled hem, capLo / capHi, wfn(y, lx, lz, acc) custom weights,
//      under: inner radius of hem returns, side: record shell for front(1)/back(-1) only }
export function lay(sink, part, L, res = 1) {
  const closed = L.th0 == null
  const th0 = closed ? -Math.PI / 2 : L.th0
  const th1 = closed ? th0 + TAU : L.th1
  const cols = Math.max(5, Math.round((L.cols || 24) * res))
  const nc = closed ? cols : cols + 1
  const fy0 = typeof L.y0 === 'function' ? L.y0 : () => L.y0
  const fy1 = typeof L.y1 === 'function' ? L.y1 : () => L.y1
  const offF = typeof L.off === 'function' ? L.off : (() => { const o = L.off || 0; return () => o })()
  const TH = new Float32Array(nc)
  const Y0 = new Float32Array(nc)
  const Y1 = new Float32Array(nc)
  let span = 0
  for (let c = 0; c < nc; c++) {
    const th = th0 + ((th1 - th0) * c) / cols
    TH[c] = th
    Y0[c] = Math.max(part.y0, fy0(th))
    Y1[c] = Math.min(part.y1, fy1(th))
    if (Y1[c] < Y0[c]) Y1[c] = Y0[c]
    span = Math.max(span, Y1[c] - Y0[c])
  }
  const dy = (L.dy || 0.016) / Math.max(0.35, res)
  const nr = Math.max(2, Math.ceil(span / dy) + 1)
  const N = nr * nc
  const B0 = new Float32Array(N * 3)
  const NB0 = new Float32Array(N * 3)
  const P = new Float32Array(N * 3)
  const NR = new Float32Array(N * 3)
  const CT = new Float32Array(N * 3) // ring centres (for orienting normals)
  const YY = new Float32Array(N)
  const LX = new Float32Array(N)
  const LZ = new Float32Array(N)
  const RI = new Int16Array(N)
  const RT = new Float32Array(N)
  const zax = part.axis === 'z'
  // ---- base surface
  for (let r = 0; r < nr; r++) {
    const t = r / (nr - 1)
    for (let c = 0; c < nc; c++) {
      const i = r * nc + c
      const y = Y0[c] + (Y1[c] - Y0[c]) * t
      const R = part.at(y, _R)
      const th = TH[c]
      const cs = Math.cos(th)
      const sn = Math.sin(th)
      const fr = sn >= 0
      const e = 2 / (fr ? R.n : R.nb)
      const ax = Math.sign(cs) * Math.pow(Math.abs(cs), e)
      const az = Math.sign(sn) * Math.pow(Math.abs(sn), e)
      const a = R.x + R.w * ax
      const b = R.z + (fr ? R.f : R.k) * az
      if (zax) {
        B0[i * 3] = a
        B0[i * 3 + 1] = b
        B0[i * 3 + 2] = y
        CT[i * 3] = R.x
        CT[i * 3 + 1] = R.z
        CT[i * 3 + 2] = y
      } else {
        B0[i * 3] = a
        B0[i * 3 + 1] = y
        B0[i * 3 + 2] = b
        CT[i * 3] = R.x
        CT[i * 3 + 1] = y
        CT[i * 3 + 2] = R.z
      }
      YY[i] = y
      LX[i] = ax * R.w * part.mir
      LZ[i] = az
      RI[i] = R.i
      RT[i] = R.t
    }
  }
  gridNormals(B0, NB0, CT, nr, nc, closed, zax)
  // ---- displace: thickness, anatomy, folds, hems
  const bs = L.bump ?? 1
  for (let r = 0; r < nr; r++) {
    for (let c = 0; c < nc; c++) {
      const i = r * nc + c
      const y = YY[i]
      let d = offF(y, TH[c], LX[i], LZ[i]) + (bs ? part.bumpAt(y, LX[i], LZ[i]) * bs : 0)
      if (L.hemLo) d += L.hemLo.t * (1 - smooth(0, L.hemLo.h, y - Y0[c]))
      if (L.hemHi) d += L.hemHi.t * (1 - smooth(0, L.hemHi.h, Y1[c] - y))
      for (let k = 0; k < 3; k++) P[i * 3 + k] = B0[i * 3 + k] + NB0[i * 3 + k] * d
    }
  }
  gridNormals(P, NR, CT, nr, nc, closed, zax)
  // ---- per-vertex attributes
  const ts = texScale(L.mat || 'plain')
  const Rm = part.at((part.y0 + part.y1) / 2, {})
  const circ = Math.PI * (Rm.w + (Rm.f + Rm.k) / 2)
  const U = closed ? Math.max(1, Math.round(circ / ts)) : (circ * (th1 - th0)) / TAU / ts
  const UV = new Float32Array(N * 2)
  const COL = new Float32Array(N * 3)
  const SI = new Uint16Array(N * 4)
  const SW = new Float32Array(N * 4)
  const ctx = { y: 0, th: 0, lx: 0, lz: 0, x: 0, z: 0, c: new THREE.Color(), part }
  const fixed = typeof L.col === 'function' ? null : new THREE.Color(L.col || '#ffffff')
  for (let i = 0; i < N; i++) {
    const c = i % nc
    UV[i * 2] = ((TH[c] - th0) / (th1 - th0)) * U
    UV[i * 2 + 1] = YY[i] / ts
    if (fixed) ctx.c.copy(fixed)
    else {
      ctx.y = YY[i]
      ctx.th = TH[c]
      ctx.lx = LX[i]
      ctx.lz = LZ[i]
      ctx.x = P[i * 3]
      ctx.yy = P[i * 3 + 1]
      ctx.z = P[i * 3 + 2]
      ctx.edge = Math.min(YY[i] - Y0[c], Y1[c] - YY[i])
      L.col(ctx)
    }
    COL[i * 3] = ctx.c.r
    COL[i * 3 + 1] = ctx.c.g
    COL[i * 3 + 2] = ctx.c.b
    _acc.fill(0)
    if (L.wfn) L.wfn(YY[i], LX[i], LZ[i], _acc, P[i * 3], P[i * 3 + 1], P[i * 3 + 2])
    else {
      const ri = RI[i]
      const rt = RT[i]
      for (const [b, w] of part.W[ri]) _acc[b] += w * (1 - rt)
      for (const [b, w] of part.W[ri + 1]) _acc[b] += w * rt
    }
    top4(_acc, SI, SW, i * 4)
  }
  // ---- vertices (grid, plus a seam column so UVs wrap cleanly) and triangles
  const S = sink.get(L.mat || 'plain')
  const base = S.p.length / 3
  const pushV = (x, y, z, nx, ny, nz, i, du) => {
    S.p.push3(x, y, z)
    S.n.push3(nx, ny, nz)
    S.uv.push(UV[i * 2] + du, UV[i * 2 + 1])
    S.c.push3(COL[i * 3], COL[i * 3 + 1], COL[i * 3 + 2])
    S.si.push(SI[i * 4], SI[i * 4 + 1], SI[i * 4 + 2], SI[i * 4 + 3])
    S.sw.push(SW[i * 4], SW[i * 4 + 1], SW[i * 4 + 2], SW[i * 4 + 3])
    return S.p.length / 3 - 1
  }
  for (let i = 0; i < N; i++) pushV(P[i * 3], P[i * 3 + 1], P[i * 3 + 2], NR[i * 3], NR[i * 3 + 1], NR[i * 3 + 2], i, 0)
  const seam = closed ? S.p.length / 3 : -1
  if (closed) for (let r = 0; r < nr; r++) {
    const i = r * nc
    pushV(P[i * 3], P[i * 3 + 1], P[i * 3 + 2], NR[i * 3], NR[i * 3 + 1], NR[i * 3 + 2], i, U)
  }
  const vid = (r, c) => (closed && c === nc ? seam + r : base + r * nc + c)
  const tri = (a, b, c) => {
    // wind so each face agrees with its vertex normals
    const SP = S.p.a
    const SN = S.n.a
    const ax = SP[a * 3], ay = SP[a * 3 + 1], az = SP[a * 3 + 2]
    const ux = SP[b * 3] - ax, uy = SP[b * 3 + 1] - ay, uz = SP[b * 3 + 2] - az
    const vx = SP[c * 3] - ax, vy = SP[c * 3 + 1] - ay, vz = SP[c * 3 + 2] - az
    const fx = uy * vz - uz * vy
    const fy = uz * vx - ux * vz
    const fz = ux * vy - uy * vx
    if (fx * fx + fy * fy + fz * fz < 1e-16) return
    const sn = fx * (SN[a * 3] + SN[b * 3] + SN[c * 3]) + fy * (SN[a * 3 + 1] + SN[b * 3 + 1] + SN[c * 3 + 1]) + fz * (SN[a * 3 + 2] + SN[b * 3 + 2] + SN[c * 3 + 2])
    if (sn >= 0) S.idx.push3(a, b, c)
    else S.idx.push3(a, c, b)
  }
  const ncq = closed ? nc : nc - 1
  for (let r = 0; r < nr - 1; r++) {
    for (let c = 0; c < ncq; c++) {
      const a = vid(r, c)
      const b = vid(r, c + 1)
      const cc = vid(r + 1, c + 1)
      const d = vid(r + 1, c)
      tri(a, b, cc)
      tri(a, cc, d)
    }
  }
  // ---- hem returns: the cut edge of the cloth turning back to the body
  const ring = (r, dir) => {
    const under = L.under ?? 0.0015
    const outer = []
    const inner = []
    for (let c = 0; c <= ncq; c++) {
      const cw = closed ? c % nc : c
      const i = r * nc + cw
      const du = closed && c === nc ? U : 0
      let nx = (zax ? 0 : 0) * 0.85 + NR[i * 3] * 0.3
      let ny = (zax ? 0 : dir) * 0.85 + NR[i * 3 + 1] * 0.3
      let nz = (zax ? dir : 0) * 0.85 + NR[i * 3 + 2] * 0.3
      const l = Math.hypot(nx, ny, nz) || 1
      nx /= l
      ny /= l
      nz /= l
      const a = pushV(P[i * 3], P[i * 3 + 1], P[i * 3 + 2], nx, ny, nz, i, du)
      const b = pushV(B0[i * 3] + NB0[i * 3] * under, B0[i * 3 + 1] + NB0[i * 3 + 1] * under, B0[i * 3 + 2] + NB0[i * 3 + 2] * under, nx, ny, nz, i, du)
      // the inside of a hem is in shadow
      for (const v of [a, b]) for (let k = 0; k < 3; k++) S.c.a[v * 3 + k] *= 0.8
      outer.push(a)
      inner.push(b)
    }
    for (let c = 0; c < ncq; c++) {
      tri(outer[c], outer[c + 1], inner[c + 1])
      tri(outer[c], inner[c + 1], inner[c])
    }
  }
  if (L.hemLo) ring(0, -1)
  if (L.hemHi) ring(nr - 1, 1)
  // ---- caps
  const cap = (r, dir) => {
    let cx = 0
    let cy = 0
    let cz = 0
    for (let c = 0; c < nc; c++) {
      const i = r * nc + c
      cx += P[i * 3]
      cy += P[i * 3 + 1]
      cz += P[i * 3 + 2]
    }
    cx /= nc
    cy /= nc
    cz /= nc
    const n = zax ? [0, 0, dir] : [0, dir, 0]
    const i0 = r * nc
    const ctr = pushV(cx, cy, cz, n[0], n[1], n[2], i0, 0)
    const rim = []
    for (let c = 0; c < nc; c++) rim.push(pushV(P[(i0 + c) * 3], P[(i0 + c) * 3 + 1], P[(i0 + c) * 3 + 2], n[0], n[1], n[2], i0 + c, 0))
    for (let c = 0; c < nc; c++) tri(rim[c], rim[(c + 1) % nc], ctr)
  }
  if (closed && L.capLo) cap(0, -1)
  if (closed && L.capHi) cap(nr - 1, 1)
  // remember this layer as a shell so accessories can sit on top of it
  if (L.shell !== false) {
    let ymin = Infinity
    let ymax = -Infinity
    for (let c = 0; c < nc; c++) {
      ymin = Math.min(ymin, Y0[c])
      ymax = Math.max(ymax, Y1[c])
    }
    const th = TH[Math.floor(nc / 2)]
    part.shells.push({ y0: ymin, y1: ymax, side: L.side ?? null, off: (y) => offF(y, th, 0, 1) + (L.hemLo ? L.hemLo.t * (1 - smooth(0, L.hemLo.h, y - ymin)) : 0) })
  }
  return { nr, nc }
}

// Smooth normals from the grid by central differences, oriented outwards.
function gridNormals(P, N, CT, nr, nc, closed, zax) {
  for (let r = 0; r < nr; r++) {
    const rd = Math.max(0, r - 1)
    const ru = Math.min(nr - 1, r + 1)
    for (let c = 0; c < nc; c++) {
      const cl = closed ? (c - 1 + nc) % nc : Math.max(0, c - 1)
      const cr = closed ? (c + 1) % nc : Math.min(nc - 1, c + 1)
      const i = r * nc + c
      const a = r * nc + cr
      const b = r * nc + cl
      const u = ru * nc + c
      const d = rd * nc + c
      const tcx = P[a * 3] - P[b * 3]
      const tcy = P[a * 3 + 1] - P[b * 3 + 1]
      const tcz = P[a * 3 + 2] - P[b * 3 + 2]
      const trx = P[u * 3] - P[d * 3]
      const try_ = P[u * 3 + 1] - P[d * 3 + 1]
      const trz = P[u * 3 + 2] - P[d * 3 + 2]
      let nx = try_ * tcz - trz * tcy
      let ny = trz * tcx - trx * tcz
      let nz = trx * tcy - try_ * tcx
      const ox = P[i * 3] - CT[i * 3]
      const oy = P[i * 3 + 1] - CT[i * 3 + 1]
      const oz = P[i * 3 + 2] - CT[i * 3 + 2]
      let l = Math.hypot(nx, ny, nz)
      if (l < 1e-12) {
        // degenerate (pole of a dome): point along the axis
        const s = r === nr - 1 ? 1 : r === 0 ? -1 : 0
        nx = zax ? ox : ox
        ny = zax ? oy : s
        nz = zax ? s : oz
        l = Math.hypot(nx, ny, nz) || 1
      }
      nx /= l
      ny /= l
      nz /= l
      if (nx * ox + ny * oy + nz * oz < 0) {
        nx = -nx
        ny = -ny
        nz = -nz
      }
      N[i * 3] = nx
      N[i * 3 + 1] = ny
      N[i * 3 + 2] = nz
    }
  }
}

// Turn sink contents into BufferGeometries on a SkinBuilder's batches.
export function flushSink(sink, builder) {
  for (const [key, s] of sink.m) {
    if (!s.p.length) continue
    const g = new THREE.BufferGeometry()
    g.setAttribute('position', new THREE.BufferAttribute(s.p.out(), 3))
    g.setAttribute('normal', new THREE.BufferAttribute(s.n.out(), 3))
    g.setAttribute('uv', new THREE.BufferAttribute(s.uv.out(), 2))
    g.setAttribute('color', new THREE.BufferAttribute(s.c.out(), 3))
    g.setAttribute('skinIndex', new THREE.BufferAttribute(s.si.out(), 4))
    g.setAttribute('skinWeight', new THREE.BufferAttribute(s.sw.out(), 4))
    g.setIndex(new THREE.BufferAttribute(s.idx.out(), 1))
    const material = mat(key)
    const shadow = !material.userData.noShadow
    const k = material.uuid + (shadow ? 's' : 'n')
    if (!builder.batches.has(k)) builder.batches.set(k, { material, geos: [], shadow })
    builder.batches.get(k).geos.push(g)
  }
  sink.m.clear()
}

// ---------------------------------------------------------------- colour helpers
export const col = (hex) => new THREE.Color(hex)
export function shadeC(c, k) {
  return c.clone().multiplyScalar(k)
}
export function lerpC(out, a, b, t) {
  out.r = a.r + (b.r - a.r) * t
  out.g = a.g + (b.g - a.g) * t
  out.b = a.b + (b.b - a.b) * t
  return out
}

// ---------------------------------------------------------------- anatomy
// Proportions in metres for a ~1.77 m figure (the mesh is scaled by height).
// B = build (0.85 thin .. 1.35 brute). Female figures get a narrower waist
// and shoulders, wider hips and a bust; heavy builds get a belly.
export function anatomy(spec) {
  const B = spec.build || 1
  const fem = !!spec.female
  const Z = spec.zombie
  const LS = 1 + (B - 1) * 0.7 // limb thickness
  const sh = fem ? 0.9 : 1 // shoulders / chest width
  const ws = fem ? 0.86 : 1 // waist
  const hp = fem ? 1.09 : 1 // hips
  const fat = Math.max(0, B - 1.05)
  const T = (y, w, f, k, wt, o = {}) => ({ y, w, f, k, wt, ...o })
  const W = (w, s = 1) => w * B * s
  const D = (d, s = 1) => d * (1 + (B - 1) * 0.85) * s
  // ---- torso: crotch to the base of the skull
  const tw = {
    hips: { hips: 1 },
    lowS: { hips: 0.55, spine: 0.45 },
    spine: { spine: 1 },
    midC: { spine: 0.45, chest: 0.55 },
    chest: { chest: 1 },
    nk0: { chest: 0.55, neck: 0.45 },
    neck: { neck: 1 },
    nk1: { neck: 0.45, head: 0.55 },
    head: { head: 1 },
  }
  const torso = new Part([
    T(0.795, W(0.04, hp), 0.035, 0.04, tw.hips, { z: -0.008 }),
    T(0.812, W(0.098, hp), D(0.07), D(0.078), tw.hips, { z: -0.006 }),
    T(0.842, W(0.142, hp), D(0.087), D(0.097), tw.hips, { n: 2.2, nb: 2.3 }),
    T(0.885, W(0.161, hp), D(0.094), D(0.108), tw.hips, { n: 2.3, nb: 2.4 }),
    T(0.935, W(0.166, hp), D(0.097), D(0.106), tw.hips, { n: 2.4, nb: 2.4 }),
    T(0.985, W(0.16, (hp + ws) / 2), D(0.098, 1 + fat * 0.4), D(0.098), tw.hips, { n: 2.4, nb: 2.4 }),
    T(1.035, W(0.15, ws), D(0.098, 1 + fat * 0.9), D(0.089), tw.lowS, { n: 2.3, nb: 2.4 }),
    T(1.085, W(0.143, ws), D(0.096, 1 + fat * 1.1), D(0.084), tw.spine, { n: 2.25, nb: 2.4 }),
    T(1.145, W(0.148, (ws + sh) / 2), D(0.099, 1 + fat * 0.8), D(0.086), tw.spine, { n: 2.3, nb: 2.5 }),
    T(1.205, W(0.157, sh), D(0.104, 1 + fat * 0.4), D(0.091), tw.midC, { n: 2.4, nb: 2.6 }),
    T(1.265, W(0.166, sh), D(0.112), D(0.096), tw.chest, { n: 2.5, nb: 2.7 }),
    T(1.325, W(0.172, sh), D(0.116), D(0.1), tw.chest, { n: 2.6, nb: 2.8 }),
    T(1.375, W(0.177, sh), D(0.113), D(0.1), tw.chest, { n: 2.7, nb: 2.9 }),
    T(1.415, W(0.181, sh), D(0.104), D(0.096), tw.chest, { n: 2.8, nb: 2.9 }),
    T(1.44, W(0.173, sh), D(0.091), D(0.09), tw.chest, { n: 2.9, nb: 2.9, z: -0.004 }),
    T(1.458, W(0.156, sh), D(0.078), D(0.085), tw.chest, { n: 2.7, nb: 2.8, z: -0.007 }),
    T(1.474, W(0.13, sh), D(0.067), D(0.078), tw.nk0, { n: 2.5, nb: 2.6, z: -0.008 }),
    T(1.49, W(0.1, sh), D(0.058), D(0.071), tw.nk0, { n: 2.3, nb: 2.4, z: -0.008 }),
    T(1.506, (fem ? 0.066 : 0.074) * (1 + (B - 1) * 0.6), 0.052, 0.063, tw.nk0, { z: -0.005 }),
    T(1.524, (fem ? 0.053 : 0.06) * (1 + (B - 1) * 0.6), 0.048, 0.056, tw.neck, { z: 0.0 }),
    T(1.555, (fem ? 0.049 : 0.055) * (1 + (B - 1) * 0.6), 0.046, 0.053, tw.neck, { z: 0.005 }),
    T(1.585, (fem ? 0.045 : 0.05) * (1 + (B - 1) * 0.6), 0.044, 0.05, tw.nk1, { z: 0.008 }),
    T(1.63, 0.046, 0.04, 0.046, tw.head, { z: 0.008 }),
  ], {
    bumps: [
      // bust
      ...(fem ? [{ y: 1.302, x: 0.074, sy: 0.042, sx: 0.045, a: 0.034 * (0.85 + B * 0.15), s: 1, sym: true }, { y: 1.27, x: 0.07, sy: 0.022, sx: 0.04, a: 0.01, s: 1, sym: true }] : [{ y: 1.335, x: 0.075, sy: 0.035, sx: 0.05, a: 0.007, s: 1, sym: true }]),
      // glutes
      { y: 0.9, x: 0.07, sy: 0.05, sx: 0.06, a: fem ? 0.02 : 0.012, s: -1, sym: true },
      // shoulder blades and spine groove
      { y: 1.36, x: 0.075, sy: 0.05, sx: 0.04, a: 0.006, s: -1, sym: true },
      { y: 1.2, x: 0, sy: 0.15, sx: 0.016, a: -0.006, s: -1 },
      // collarbones and the pit of the throat
      { y: 1.466, x: 0.06, sy: 0.009, sx: 0.04, a: 0.004, s: 1, sym: true },
      { y: 1.488, x: 0, sy: 0.012, sx: 0.014, a: -0.006, s: 1 },
      // belly for heavier builds
      ...(fat > 0 ? [{ y: 1.08, x: 0, sy: 0.08, sx: 0.1, a: fat * 0.16, s: 1 }] : []),
      // Adam's apple
      ...(!fem ? [{ y: 1.54, x: 0, sy: 0.01, sx: 0.01, a: 0.005, s: 1 }] : []),
      // brute hump
      ...(Z?.kind === 'brute' ? [{ y: 1.42, x: 0, sy: 0.08, sx: 0.11, a: 0.07, s: -1 }] : []),
    ],
  })

  // ---- arms (left keys; the right arm mirrors them)
  const ax = 0.19 + Math.max(0, (B - 1) * 0.15) - (fem ? 0.006 : 0)
  const aw = (w) => w * LS * (fem ? 0.9 : 1)
  const uA = { upperArm: 1 }
  const armKeys = [
    T(0.885, aw(0.017), aw(0.022), aw(0.02), { hand: 1 }, { x: ax + 0.002 }),
    T(0.905, aw(0.021), aw(0.027), aw(0.025), { foreArm: 0.45, hand: 0.55 }, { x: ax + 0.003 }),
    T(0.93, aw(0.024), aw(0.029), aw(0.027), { foreArm: 0.85, hand: 0.15 }, { x: ax + 0.004 }),
    T(0.975, aw(0.03), aw(0.031), aw(0.029), { foreArm: 1 }, { x: ax + 0.006 }),
    T(1.04, aw(0.037), aw(0.037), aw(0.034), { foreArm: 1 }, { x: ax + 0.008 }),
    T(1.105, aw(0.042), aw(0.042), aw(0.039), { foreArm: 0.92, upperArm: 0.08 }, { x: ax + 0.009 }),
    T(1.135, aw(0.04), aw(0.04), aw(0.04), { foreArm: 0.7, upperArm: 0.3 }, { x: ax + 0.009 }),
    T(1.16, aw(0.038), aw(0.038), aw(0.043), { foreArm: 0.45, upperArm: 0.55 }, { x: ax + 0.009, z: -0.003 }),
    T(1.19, aw(0.04), aw(0.042), aw(0.042), { foreArm: 0.15, upperArm: 0.85 }, { x: ax + 0.009 }),
    T(1.24, aw(0.044), aw(0.048), aw(0.043), uA, { x: ax + 0.008 }),
    T(1.3, aw(0.047), aw(0.05), aw(0.047), uA, { x: ax + 0.007 }),
    T(1.35, aw(0.05), aw(0.051), aw(0.05), uA, { x: ax + 0.004 }),
    T(1.395, aw(0.052), aw(0.052), aw(0.052), uA, { x: ax + 0.002 }),
    T(1.425, aw(0.049), aw(0.048), aw(0.049), uA, { x: ax }),
    T(1.444, aw(0.042), aw(0.041), aw(0.043), uA, { x: ax - 0.004 }),
    T(1.456, aw(0.03), aw(0.03), aw(0.032), uA, { x: ax - 0.009 }),
    T(1.462, aw(0.008), aw(0.008), aw(0.009), uA, { x: ax - 0.012 }),
  ]
  const armBumps = [
    { y: 1.27, x: 0, sy: 0.05, sx: 0.025, a: 0.004 * LS, s: 1 }, // biceps
    { y: 1.3, x: 0, sy: 0.06, sx: 0.03, a: 0.003 * LS, s: -1 }, // triceps
    { y: 1.07, x: 0.012, sy: 0.05, sx: 0.025, a: 0.003 * LS }, // forearm
  ]
  const armL = new Part(armKeys, { bumps: armBumps })
  const armR = new Part(armKeys, { mir: -1, bumps: armBumps })

  // ---- legs
  const lx0 = 0.093 + Math.max(0, (B - 1) * 0.06) + (fem ? 0.004 : 0)
  const lw = (w) => w * LS * (fem ? 0.95 : 1)
  const thighW = (fem ? 1.06 : 1)
  const legKeys = [
    T(0.07, lw(0.024), lw(0.03), lw(0.026), { foot: 1 }, { x: lx0 + 0.002 }),
    T(0.095, lw(0.03), lw(0.032), lw(0.031), { foot: 0.5, shin: 0.5 }, { x: lx0 + 0.002 }),
    T(0.125, lw(0.031), lw(0.032), lw(0.033), { shin: 0.9, foot: 0.1 }, { x: lx0 + 0.002 }),
    T(0.17, lw(0.034), lw(0.034), lw(0.036), { shin: 1 }, { x: lx0 + 0.003 }),
    T(0.24, lw(0.04), lw(0.038), lw(0.045), { shin: 1 }, { x: lx0 + 0.004 }),
    T(0.315, lw(0.047), lw(0.042), lw(0.058), { shin: 1 }, { x: lx0 + 0.005 }),
    T(0.385, lw(0.052), lw(0.045), lw(0.063), { shin: 1 }, { x: lx0 + 0.005 }),
    T(0.445, lw(0.05), lw(0.048), lw(0.054), { shin: 0.88, thigh: 0.12 }, { x: lx0 + 0.005 }),
    T(0.49, lw(0.052), lw(0.056), lw(0.05), { shin: 0.5, thigh: 0.5 }, { x: lx0 + 0.005 }),
    T(0.535, lw(0.056), lw(0.058), lw(0.054), { shin: 0.12, thigh: 0.88 }, { x: lx0 + 0.005 }),
    T(0.6, lw(0.064 * thighW), lw(0.066), lw(0.064), { thigh: 1 }, { x: lx0 + 0.004 }),
    T(0.7, lw(0.075 * thighW), lw(0.077), lw(0.076), { thigh: 1 }, { x: lx0 + 0.003 }),
    T(0.8, lw(0.083 * thighW), lw(0.084), lw(0.085), { thigh: 1 }, { x: lx0 + 0.001 }),
    T(0.87, lw(0.084 * thighW), lw(0.085), lw(0.088), { thigh: 0.85, hips: 0.15 }, { x: lx0 - 0.001 }),
    T(0.93, lw(0.075 * thighW), lw(0.078), lw(0.082), { thigh: 0.6, hips: 0.4 }, { x: lx0 - 0.004 }),
    T(0.975, lw(0.055), lw(0.058), lw(0.06), { thigh: 0.5, hips: 0.5 }, { x: lx0 - 0.007 }),
    T(1.0, lw(0.022), lw(0.024), lw(0.025), { thigh: 0.5, hips: 0.5 }, { x: lx0 - 0.01 }),
  ]
  const legBumps = [
    { y: 0.505, x: 0, sy: 0.022, sx: 0.022, a: 0.006, s: 1 }, // kneecap
    { y: 0.36, x: -0.01, sy: 0.06, sx: 0.025, a: 0.005 * LS, s: -1 }, // calf
    { y: 0.7, x: 0.03, sy: 0.12, sx: 0.04, a: 0.004 * LS, s: 1 }, // quads
  ]
  const legL = new Part(legKeys, { bumps: legBumps })
  const legR = new Part(legKeys, { mir: -1, bumps: legBumps })
  return { torso, armL, armR, legL, legR, head: headPart(spec), B, fem, LS }
}

// ---------------------------------------------------------------- head
// The skull and face as one loft from under the chin to the crown, with the
// brow, cheekbones, eye sockets and chin modelled as bumps.
export function headPart(spec) {
  const fem = !!spec.female
  const Z = spec.zombie
  const jw = fem ? 0.93 : 1
  const hs = fem ? 1.01 : 1.045
  const wt = { head: 1 }
  const T = (y, z, w, f, k, o = {}) => ({ y: 1.56 + (y - 1.56) * hs, z: z * hs, w: w * hs, f: f * hs, k: k * hs, wt, ...o })
  const keys = [
    T(1.547, 0.07, 0.012 * jw, 0.008, 0.008),
    T(1.553, 0.066, 0.03 * jw, 0.018, 0.02),
    T(1.562, 0.056, 0.048 * jw, 0.028, 0.042, { n: 2.2 }),
    T(1.576, 0.042, 0.066 * jw, 0.04, 0.066, { n: 2.4 }),
    T(1.594, 0.03, 0.078 * jw, 0.054, 0.084, { n: 2.6 }),
    T(1.614, 0.022, 0.084 * jw, 0.066, 0.096, { n: 2.7 }),
    T(1.64, 0.014, 0.089, 0.075, 0.102, { n: 2.8, nb: 2.1 }),
    T(1.668, 0.009, 0.093, 0.08, 0.105, { n: 2.8, nb: 2.1 }),
    T(1.695, 0.006, 0.094, 0.081, 0.106, { n: 2.7, nb: 2.1 }),
    T(1.72, 0.002, 0.09, 0.073, 0.104, { n: 2.5, nb: 2.1 }),
    T(1.742, -0.002, 0.08, 0.06, 0.094, { n: 2.3 }),
    T(1.758, -0.005, 0.064, 0.046, 0.077),
    T(1.769, -0.008, 0.043, 0.03, 0.053),
    T(1.776, -0.01, 0.012, 0.008, 0.015),
  ]
  const Y = (y) => 1.56 + (y - 1.56) * hs
  const sock = Z ? -0.013 : -0.0085
  const bumps = [
    { y: Y(1.563), x: 0, sy: 0.009, sx: 0.016, a: 0.005, s: 1 }, // chin
    { y: Y(1.611), x: 0, sy: 0.013, sx: 0.024, a: 0.005, s: 1 }, // muzzle (lips sit on it)
    { y: Y(1.646), x: 0.057, sy: 0.013, sx: 0.02, a: Z ? -0.004 : 0.005, s: 1, sym: true }, // cheekbones
    { y: Y(1.622), x: 0.055, sy: 0.016, sx: 0.022, a: Z ? -0.008 : -0.001, s: 1, sym: true }, // cheeks
    { y: Y(1.671), x: 0.036, sy: 0.0105, sx: 0.017, a: sock, s: 1, sym: true }, // eye sockets
    { y: Y(1.692), x: 0.034, sy: 0.007, sx: 0.028, a: fem ? 0.003 : Z ? 0.008 : 0.006, s: 1, sym: true }, // brow ridge
    { y: Y(1.684), x: 0, sy: 0.01, sx: 0.011, a: 0.004, s: 1 }, // glabella
    { y: Y(1.7), x: 0.086, sy: 0.02, sx: 0.014, a: -0.003, s: 1, sym: true }, // temples
    { y: Y(1.69), x: 0, sy: 0.04, sx: 0.06, a: 0.007, s: -1 }, // occiput
    { y: Y(1.588), x: 0.066, sy: 0.012, sx: 0.016, a: fem ? 0.001 : 0.004, sym: true }, // jaw angle
  ]
  for (const bp of bumps) {
    bp.x *= hs
    bp.sx *= hs
  }
  const p = new Part(keys, { bumps })
  p.hs = hs
  p.Y = Y
  return p
}

// The nose: a small loft from the bridge down to the tip and nostrils.
export function nosePart(spec, head) {
  const Y = head.Y
  const hs = head.hs
  const fem = !!spec.female
  const s = (fem ? 0.9 : 1) * hs
  const wt = { head: 1 }
  const T = (y, z, w, f, k) => ({ y: Y(y), z: z * hs, w: w * s, f: f * s, k: k * s, wt })
  return new Part([
    T(1.62, 0.091, 0.004, 0.003, 0.003),
    T(1.623, 0.092, 0.011, 0.007, 0.008),
    T(1.628, 0.093, 0.0155, 0.012, 0.01),
    T(1.635, 0.094, 0.0145, 0.0165, 0.011),
    T(1.645, 0.093, 0.0115, 0.0135, 0.01),
    T(1.66, 0.091, 0.009, 0.009, 0.008),
    T(1.675, 0.088, 0.008, 0.006, 0.007),
    T(1.69, 0.084, 0.006, 0.004, 0.006),
  ])
}

// Hairline height around the head for a hair style (theta: 0 = left side,
// pi/2 = face, -pi/2 = back of the head).
export function hairline(style, fem, head) {
  const Y = head.Y
  return (th) => {
    // angle away from the face, 0 (front) .. pi (back)
    let a = Math.abs(Math.atan2(Math.cos(th), Math.sin(th)))
    const pts = style === 'long' || style === 'ponytail' || style === 'bun'
      ? [[0, 1.726], [0.55, 1.722], [0.95, 1.7], [1.25, 1.64], [1.6, 1.618], [2.1, 1.6], [Math.PI, 1.585]]
      : style === 'buzz' || style === 'mohawk'
        ? [[0, 1.73], [0.5, 1.725], [0.95, 1.706], [1.2, 1.67], [1.32, 1.645], [1.45, 1.66], [1.6, 1.675], [1.95, 1.64], [Math.PI, 1.6]]
        : [[0, fem ? 1.725 : 1.73], [0.5, 1.726], [0.95, 1.705], [1.2, 1.668], [1.32, 1.643], [1.45, 1.66], [1.62, 1.672], [1.95, 1.635], [2.4, 1.6], [Math.PI, 1.588]]
    for (let i = 1; i < pts.length; i++) {
      if (a <= pts[i][0]) {
        const t = smooth(0, 1, (a - pts[i - 1][0]) / (pts[i][0] - pts[i - 1][0]))
        return Y(pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * t)
      }
    }
    return Y(pts[pts.length - 1][1])
  }
}

// ---------------------------------------------------------------- feet
// Shoe / foot shapes as z-axis lofts. tops: [[z, top, halfWidth], ...]
export function footPart(tops, o = {}) {
  const bot = o.bot ?? 0.016
  const x = o.x ?? 0.095
  const wt = o.wt || { foot: 1 }
  const keys = tops.map(([z, top, w, xo = 0]) => ({ y: z, x: x + xo, z: (top + bot) / 2, f: (top - bot) / 2, k: (top - bot) / 2, w, n: o.n ?? 2.3, nb: o.nb ?? 5, wt }))
  return new Part(keys, { axis: 'z', mir: o.mir || 1 })
}
