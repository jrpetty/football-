// Modeling kit: compose detailed models out of many primitives, then merge
// them into one mesh per material. Parts carry their colour as vertex colours
// and get UVs in real-world metres so textures tile at a consistent scale.
import * as THREE from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'
import { mergeGeometries, mergeVertices } from 'three/addons/utils/BufferGeometryUtils.js'
import { mat, texScale, texGrain } from '../render/materials.js'

const _m = new THREE.Matrix4()
const _q = new THREE.Quaternion()
const _e = new THREE.Euler()
const _v = new THREE.Vector3()
const _s = new THREE.Vector3()
const _c = new THREE.Color()

function trs(o) {
  _e.set(o.rx || 0, o.ry || 0, o.rz || 0, o.order || 'XYZ')
  _q.setFromEuler(_e)
  _v.set(o.x || 0, o.y || 0, o.z || 0)
  const s = o.s ?? 1
  _s.set((o.sx ?? 1) * s, (o.sy ?? 1) * s, (o.sz ?? 1) * s)
  return new THREE.Matrix4().compose(_v, _q, _s)
}

// Scale box-style UVs (0..1 per face) to metres for each face. With grain,
// each face's texture u axis runs along its longer side (wood grain along boards).
function boxUV(g, w, h, d, ts, grain = false) {
  const uv = g.attributes.uv
  const n = g.attributes.normal
  for (let i = 0; i < uv.count; i++) {
    const ax = Math.abs(n.getX(i))
    const ay = Math.abs(n.getY(i))
    const az = Math.abs(n.getZ(i))
    let su, sv
    if (ax >= ay && ax >= az) {
      su = d
      sv = h
    } else if (ay >= ax && ay >= az) {
      su = w
      sv = d
    } else {
      su = w
      sv = h
    }
    if (grain && sv > su) uv.setXY(i, (uv.getY(i) * sv) / ts, (uv.getX(i) * su) / ts)
    else uv.setXY(i, (uv.getX(i) * su) / ts, (uv.getY(i) * sv) / ts)
  }
}
function scaleUV(g, su, sv) {
  const uv = g.attributes.uv
  if (!uv) return
  for (let i = 0; i < uv.count; i++) uv.setXY(i, uv.getX(i) * su, uv.getY(i) * sv)
}

const geoCache = new Map()
function cached(key, make) {
  let g = geoCache.get(key)
  if (!g) {
    g = make()
    geoCache.set(key, g)
  }
  return g
}

export class Builder {
  constructor() {
    this.batches = new Map()
    this.stack = [new THREE.Matrix4()]
    this.pivots = []
  }
  get top() {
    return this.stack[this.stack.length - 1]
  }
  push(o) {
    this.stack.push(this.top.clone().multiply(trs(o)))
    return this
  }
  pop() {
    this.stack.pop()
    return this
  }
  at(o, fn) {
    this.push(o)
    fn(this)
    this.pop()
    return this
  }
  // Add a geometry (shared or fresh) with options.
  add(geo, o = {}) {
    const g = geo.index ? geo.toNonIndexed() : geo.clone()
    if (!g.attributes.uv) g.setAttribute('uv', new THREE.Float32BufferAttribute(new Float32Array(g.attributes.position.count * 2), 2))
    if (!g.attributes.normal) g.computeVertexNormals()
    const keep = o.keepColor && g.attributes.color
    for (const k of Object.keys(g.attributes)) if (!['position', 'normal', 'uv', ...(keep ? ['color'] : [])].includes(k)) g.deleteAttribute(k)
    g.applyMatrix4(this.top.clone().multiply(trs(o)))
    if (keep) {
      const material = o.material || mat(o.mat || 'plain')
      const shadow = o.shadow !== false && !material.userData.noShadow
      const key = material.uuid + (shadow ? 's' : 'n')
      if (!this.batches.has(key)) this.batches.set(key, { material, geos: [], shadow })
      this.batches.get(key).geos.push(g)
      this.last = g
      return this
    }
    // vertex colours with a gentle ground-contact darkening
    _c.set(o.color || '#ffffff')
    if (o.jitter) _c.offsetHSL(0, 0, (Math.random() - 0.5) * o.jitter)
    const pos = g.attributes.position
    const cols = new Float32Array(pos.count * 3)
    const ao = o.ao ?? 0.18
    for (let i = 0; i < pos.count; i++) {
      const y = pos.getY(i)
      const k = 1 - ao * Math.max(0, 1 - y / 0.35)
      cols[i * 3] = _c.r * k
      cols[i * 3 + 1] = _c.g * k
      cols[i * 3 + 2] = _c.b * k
    }
    g.setAttribute('color', new THREE.Float32BufferAttribute(cols, 3))
    const material = o.material || mat(o.mat || 'plain')
    const shadow = o.shadow !== false && !material.userData.noShadow
    const key = material.uuid + (shadow ? 's' : 'n')
    if (!this.batches.has(key)) this.batches.set(key, { material, geos: [], shadow })
    this.batches.get(key).geos.push(g)
    this.last = g
    this.afterAdd?.(g, o)
    return this
  }
  // ---- primitives
  box(w, h, d, o = {}) {
    const m = o.mat || 'plain'
    const ts = o.uv ?? texScale(m)
    let g
    if (o.r) g = new RoundedBoxGeometry(w, h, d, o.seg ?? 2, Math.min(o.r, w / 2 - 0.001, h / 2 - 0.001, d / 2 - 0.001))
    else g = new THREE.BoxGeometry(w, h, d)
    boxUV(g, w, h, d, ts, texGrain(m))
    return this.add(g, o)
  }
  cyl(rt, rb, h, o = {}) {
    const m = o.mat || 'plain'
    const ts = o.uv ?? texScale(m)
    const seg = o.seg ?? 12
    const g = new THREE.CylinderGeometry(rt, rb, h, seg, o.hseg ?? 1, !!o.open, o.ts ?? 0, o.tl ?? Math.PI * 2)
    scaleUV(g, (Math.PI * 2 * Math.max(rt, rb)) / ts, h / ts)
    if (texGrain(m)) {
      const uv = g.attributes.uv
      for (let i = 0; i < uv.count; i++) uv.setXY(i, uv.getY(i), uv.getX(i))
    }
    return this.add(g, o)
  }
  cone(r, h, o = {}) {
    return this.cyl(0.0001, r, h, o)
  }
  sphere(r, o = {}) {
    const ts = o.uv ?? texScale(o.mat || 'plain')
    const g = new THREE.SphereGeometry(r, o.ws ?? 14, o.hs ?? 10, o.ps ?? 0, o.pl ?? Math.PI * 2, o.ts ?? 0, o.tl ?? Math.PI)
    scaleUV(g, (Math.PI * 2 * r) / ts, (Math.PI * r) / ts)
    return this.add(g, o)
  }
  capsule(r, len, o = {}) {
    const ts = o.uv ?? texScale(o.mat || 'plain')
    const g = new THREE.CapsuleGeometry(r, len, o.cs ?? 4, o.seg ?? 10)
    scaleUV(g, (Math.PI * 2 * r) / ts, (len + r * 2) / ts)
    return this.add(g, o)
  }
  torus(R, r, o = {}) {
    const g = new THREE.TorusGeometry(R, r, o.rs ?? 8, o.ts2 ?? 20, o.arc ?? Math.PI * 2)
    return this.add(g, o)
  }
  ico(r, o = {}) {
    let g = new THREE.IcosahedronGeometry(r, o.detail ?? 1)
    if (o.noise) {
      g.deleteAttribute('normal')
      g.deleteAttribute('uv')
      g = mergeVertices(g, 1e-4)
      const p = g.attributes.position
      for (let i = 0; i < p.count; i++) {
        const k = 1 + (Math.random() - 0.5) * o.noise
        p.setXYZ(i, p.getX(i) * k, p.getY(i) * k, p.getZ(i) * k)
      }
      g.computeVertexNormals()
    }
    return this.add(g, o)
  }
  dodeca(r, o = {}) {
    return this.add(new THREE.DodecahedronGeometry(r, o.detail ?? 0), o)
  }
  // profile: [[radius, y], ...] bottom to top
  lathe(profile, o = {}) {
    const ts = o.uv ?? texScale(o.mat || 'plain')
    const pts = profile.map(([r, y]) => new THREE.Vector2(Math.max(0.0001, r), y))
    const g = new THREE.LatheGeometry(pts, o.seg ?? 16)
    let len = 0
    for (let i = 1; i < pts.length; i++) len += pts[i].distanceTo(pts[i - 1])
    const maxR = Math.max(...profile.map((p) => p[0]))
    scaleUV(g, (Math.PI * 2 * maxR) / ts, len / ts)
    return this.add(g, o)
  }
  // points: [[x,y,z], ...]
  tube(points, radius, o = {}) {
    const curve = new THREE.CatmullRomCurve3(points.map((p) => new THREE.Vector3(...p)), false, 'catmullrom', o.tension ?? 0.5)
    const g = new THREE.TubeGeometry(curve, o.tseg ?? Math.max(4, points.length * 4), radius, o.seg ?? 6, false)
    return this.add(g, o)
  }
  // shape: [[x,y], ...] in the XY plane, extruded along +Z by depth
  extrude(shape, depth, o = {}) {
    const s = new THREE.Shape(shape.map(([x, y]) => new THREE.Vector2(x, y)))
    if (o.holes) for (const hole of o.holes) s.holes.push(new THREE.Path(hole.map(([x, y]) => new THREE.Vector2(x, y))))
    const g = new THREE.ExtrudeGeometry(s, {
      depth,
      bevelEnabled: !!o.bevel,
      bevelThickness: o.bevel || 0,
      bevelSize: o.bevel || 0,
      bevelSegments: 1,
      curveSegments: o.curve ?? 8,
    })
    const ts = o.uv ?? texScale(o.mat || 'plain')
    scaleUV(g, 1 / ts, 1 / ts)
    g.translate(0, 0, -depth / 2)
    return this.add(g, o)
  }
  plane(w, h, o = {}) {
    const g = new THREE.PlaneGeometry(w, h)
    if (!o.material) scaleUV(g, w / (o.uv ?? texScale(o.mat || 'plain')), h / (o.uv ?? texScale(o.mat || 'plain')))
    return this.add(g, { ao: 0, ...o })
  }
  // Wedge (triangular prism) for roofs, ramps and blades.
  wedge(w, h, d, o = {}) {
    const g = cached('wedge', () => {
      const s = new THREE.Shape([new THREE.Vector2(-0.5, 0), new THREE.Vector2(0.5, 0), new THREE.Vector2(0, 1)])
      const e = new THREE.ExtrudeGeometry(s, { depth: 1, bevelEnabled: false })
      e.translate(0, 0, -0.5)
      return e
    })
    const c = g.clone()
    c.scale(w, h, d)
    const ts = o.uv ?? texScale(o.mat || 'plain')
    boxUV(c, w, h, d, ts)
    return this.add(c, o)
  }
  // A box spanning two points (posts, braces, rafters). w across, h up.
  beam(a, b2, w, h, o = {}) {
    const dx = b2[0] - a[0]
    const dy = b2[1] - a[1]
    const dz = b2[2] - a[2]
    const len = Math.hypot(dx, dy, dz)
    const yaw = Math.atan2(dx, dz)
    const pitch = -Math.atan2(dy, Math.hypot(dx, dz))
    this.push({ x: (a[0] + b2[0]) / 2, y: (a[1] + b2[1]) / 2, z: (a[2] + b2[2]) / 2, ry: yaw, rx: pitch, order: 'YXZ', rz: o.roll || 0 })
    if (o.round) this.cyl(w / 2, w / 2, len, { ...o, rx: Math.PI / 2, x: 0, y: 0, z: 0, ry: 0, rz: 0 })
    else this.box(w, h, len + (o.extend || 0), { ...o, x: 0, y: 0, z: 0, rx: 0, ry: 0, rz: 0 })
    this.pop()
    return this
  }
  // A rope/cable hanging between points with a catenary-like sag.
  rope(points, r, o = {}) {
    const pts = []
    const sag = o.sag ?? 0.15
    for (let i = 0; i < points.length - 1; i++) {
      const A = points[i]
      const B = points[i + 1]
      const n = o.steps ?? 8
      for (let k = i === 0 ? 0 : 1; k <= n; k++) {
        const t = k / n
        const len = Math.hypot(B[0] - A[0], B[2] - A[2])
        pts.push([A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t - Math.sin(t * Math.PI) * sag * Math.max(0.3, len / 3), A[2] + (B[2] - A[2]) * t])
      }
    }
    return this.tube(pts, r, { seg: 5, tension: 0.3, ...o })
  }
  // A cloth sheet (tarps, awnings, sails) spanning a w×d rectangle on XZ,
  // sagging in the middle. Corners at y = corners[i] (NW, NE, SE, SW).
  cloth(w, d, o = {}) {
    const sx = o.segX ?? 10
    const sz = o.segZ ?? 8
    const g = new THREE.PlaneGeometry(w, d, sx, sz)
    g.rotateX(-Math.PI / 2)
    const c = o.corners || [0, 0, 0, 0]
    const sag = o.sag ?? 0.12
    const pos = g.attributes.position
    const rnd = seeded(o.seed || 7)
    for (let i = 0; i < pos.count; i++) {
      const u = pos.getX(i) / w + 0.5
      const v = pos.getZ(i) / d + 0.5
      const y = c[0] * (1 - u) * (1 - v) + c[1] * u * (1 - v) + c[2] * u * v + c[3] * (1 - u) * v
      const edge = Math.sin(u * Math.PI) * Math.sin(v * Math.PI)
      const wr = (rnd() - 0.5) * (o.wrinkle ?? 0.02)
      pos.setY(i, y - edge * sag + wr * edge)
    }
    g.computeVertexNormals()
    const ts = o.uv ?? texScale(o.mat || 'canvas')
    scaleUV(g, w / ts, d / ts)
    return this.add(g, { ao: 0, ...o })
  }
  // A named animated part: build its geometry inside fn, attach at transform.
  pivot(name, o, fn) {
    const sub = new Builder()
    fn(sub)
    this.pivots.push({ name, matrix: this.top.clone().multiply(trs(o)), sub })
    return this
  }
  build(opts = {}) {
    const group = new THREE.Group()
    for (const { material, geos, shadow } of this.batches.values()) {
      let merged = mergeGeometries(geos, false)
      for (const g of geos) g.dispose()
      if (!merged) continue
      if (opts.index !== false && merged.attributes.position.count > 64) {
        const idx = mergeVertices(merged, 1e-4)
        merged.dispose()
        merged = idx
      }
      merged.computeBoundingSphere()
      const mesh = new THREE.Mesh(merged, material)
      mesh.castShadow = shadow && opts.shadow !== false
      mesh.receiveShadow = opts.receive !== false
      group.add(mesh)
    }
    const pv = {}
    const list = []
    for (const p of this.pivots) {
      const obj = p.sub.build(opts)
      p.matrix.decompose(obj.position, obj.quaternion, obj.scale)
      obj.name = p.name
      group.add(obj)
      pv[p.name] = obj
      list.push(obj)
      Object.assign(pv, obj.userData.pivots || {})
    }
    group.userData.pivots = pv
    group.userData.pivotList = list
    this.pivots = []
    this.batches.clear()
    return group
  }
}

// Convenience: build a model with a function.
export function model(fn, opts) {
  const b = new Builder()
  fn(b)
  return b.build(opts)
}

// Deterministic randomness per model so rebuilds look identical.
export function seeded(seed) {
  let s = seed >>> 0 || 1
  return () => {
    s = (s + 0x6d2b79f5) | 0
    let t = Math.imul(s ^ (s >>> 15), 1 | s)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
