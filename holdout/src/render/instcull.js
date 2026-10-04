// Trees, rocks, flowers and grass are instanced: one draw call puts thousands
// of copies in the camp. But the card draws every copy, on screen or not, in
// the picture and again in the shadow map: a camp frame came to some 11
// million triangles, most of them far off screen or in the next field. This
// keeps each kind's instances in coarse grid cells and, whenever the camera
// (or the sun) has moved enough to matter, packs only the cells in view into
// the instance buffers. Still one draw call per part; far fewer triangles.
import * as THREE from 'three'

const CELL = 24
// cells are tested a little oversize, so a camera that has moved a few metres
// since the last pack still finds everything it should
const MARGIN = 9

// The camera's and the sun's shadow box's view volumes, and where they were
// when last captured.
export class CullView {
  constructor() {
    this.frustum = new THREE.Frustum()
    this.shadow = new THREE.Frustum()
    this.hasShadow = false
    this.focus = new THREE.Vector3(1e9, 0, 0)
    this.cam = new THREE.Vector3(1e9, 0, 0)
    this.quat = new THREE.Quaternion()
    this.sun = new THREE.Vector3(1e9, 0, 0)
    this.aspect = 0
    this.fov = 0
    this._m = new THREE.Matrix4()
    this._v = new THREE.Vector3()
    // first person turns all the time: the cone packed is this many degrees
    // wider than the view, and only a bigger turn packs again
    this.wide = 0
    this._cam = new THREE.PerspectiveCamera()
  }
  // Has the view moved far enough since the last capture to matter?
  moved(camera, sun, focus) {
    camera.getWorldPosition(this._v)
    if (this._v.distanceToSquared(this.cam) > 9) return true
    if (focus.distanceToSquared(this.focus) > 9) return true
    // about 1.2 degrees of turn (about 6 when packing a wider cone)
    if (1 - Math.abs(camera.quaternion.dot(this.quat)) > (this.wide ? 1.4e-3 : 7e-5)) return true
    if (camera.aspect !== this.aspect || camera.fov !== this.fov) return true
    if (sun && sun.position.distanceToSquared(this.sun) > 4) return true
    return false
  }
  capture(camera, sun, focus) {
    camera.updateMatrixWorld()
    let proj = camera.projectionMatrix
    if (this.wide && camera.isPerspectiveCamera) {
      const c = this._cam
      c.fov = Math.min(170, camera.fov + this.wide)
      c.aspect = camera.aspect
      c.near = camera.near
      c.far = camera.far
      c.updateProjectionMatrix()
      // wider sideways as well: the aspect grows with the extra degrees
      proj = c.projectionMatrix
    }
    this.frustum.setFromProjectionMatrix(this._m.multiplyMatrices(proj, camera.matrixWorldInverse))
    camera.getWorldPosition(this.cam)
    this.quat.copy(camera.quaternion)
    this.aspect = camera.aspect
    this.fov = camera.fov
    this.focus.copy(focus)
    this.hasShadow = false
    if (sun?.castShadow) {
      sun.updateMatrixWorld()
      sun.target.updateMatrixWorld()
      sun.shadow.updateMatrices(sun)
      const sc = sun.shadow.camera
      this.shadow.setFromProjectionMatrix(this._m.multiplyMatrices(sc.projectionMatrix, sc.matrixWorldInverse))
      this.sun.copy(sun.position)
      this.hasShadow = true
    }
  }
}

// One set of instances (matrices, maybe colours) drawn by one or more
// InstancedMesh parts that share them.
export class CulledSet {
  // meshes: the parts. thin: { near, span, min } drops a random share of the
  // instances in far cells (they are stored in random order): from `near`
  // metres out the share kept falls over `span` metres to at least `min`.
  // lod: { meshes, dist } draws instances farther than dist from the camera
  // with a second, cheaper set of parts instead (same instances; such a set
  // carries no colours).
  constructor(meshes, { casts = false, thin = null, max = 0, colors = false, lod = null } = {}) {
    this.meshes = meshes
    this.casts = casts
    this.thin = thin
    this.lod = lod && lod.meshes.length ? { meshes: lod.meshes, d2: lod.dist * lod.dist } : null
    this.matAttr1 = null
    this.n = 0
    this.cells = []
    this.smats = null
    this.scols = null
    // the parts share their instance buffers: one pack and one upload per set
    const cap = max || meshes[0]?.instanceMatrix.count || 0
    this.matAttr = new THREE.InstancedBufferAttribute(new Float32Array(cap * 16), 16)
    this.matAttr.setUsage(THREE.DynamicDrawUsage)
    this.colAttr = null
    if (this.lod) {
      this.matAttr1 = new THREE.InstancedBufferAttribute(new Float32Array(cap * 16), 16)
      this.matAttr1.setUsage(THREE.DynamicDrawUsage)
      for (const m of this.lod.meshes) m.instanceMatrix = this.matAttr1
    }
    for (const m of meshes) {
      m.instanceMatrix = this.matAttr
      if (colors || m.instanceColor) {
        this.colAttr ??= new THREE.InstancedBufferAttribute(new Float32Array(cap * 3), 3)
        this.colAttr.setUsage(THREE.DynamicDrawUsage)
        m.instanceColor = this.colAttr
      }
    }
  }
  // mats: n 4x4 matrices (world), cols: n rgb triples or null, rad: each
  // instance's bounding radius in metres (a number or an array of n)
  set(mats, cols, rad) {
    const n = (this.n = Math.floor(mats.length / 16))
    const cap = this.matAttr.count
    if (n > cap) throw new Error(`CulledSet: ${n} instances for room for ${cap}`)
    const ra = (i) => (typeof rad === 'number' ? rad : rad[i])
    let x0 = Infinity
    let z0 = Infinity
    let x1 = -Infinity
    let z1 = -Infinity
    for (let i = 0; i < n; i++) {
      const x = mats[i * 16 + 12]
      const z = mats[i * 16 + 14]
      if (x < x0) x0 = x
      if (x > x1) x1 = x
      if (z < z0) z0 = z
      if (z > z1) z1 = z
    }
    if (!n) {
      x0 = z0 = 0
      x1 = z1 = 1
    }
    const gw = Math.max(1, Math.floor((x1 - x0) / CELL) + 1)
    const gh = Math.max(1, Math.floor((z1 - z0) / CELL) + 1)
    const cellOf = new Int32Array(n)
    const counts = new Int32Array(gw * gh)
    for (let i = 0; i < n; i++) {
      const c = Math.floor((mats[i * 16 + 14] - z0) / CELL) * gw + Math.floor((mats[i * 16 + 12] - x0) / CELL)
      cellOf[i] = c
      counts[c]++
    }
    // counting sort by cell: every cell's instances end up contiguous
    const starts = new Int32Array(gw * gh + 1)
    for (let c = 0; c < gw * gh; c++) starts[c + 1] = starts[c] + counts[c]
    const fill = starts.slice(0, gw * gh)
    this.smats = new Float32Array(n * 16)
    this.scols = cols ? new Float32Array(n * 3) : null
    const boxes = new Array(gw * gh)
    for (let i = 0; i < n; i++) {
      const c = cellOf[i]
      const j = fill[c]++
      this.smats.set(mats.subarray(i * 16, i * 16 + 16), j * 16)
      if (cols) this.scols.set(cols.subarray(i * 3, i * 3 + 3), j * 3)
      const x = mats[i * 16 + 12]
      const y = mats[i * 16 + 13]
      const z = mats[i * 16 + 14]
      const r = ra(i)
      const b = (boxes[c] ??= new THREE.Box3(new THREE.Vector3(Infinity, Infinity, Infinity), new THREE.Vector3(-Infinity, -Infinity, -Infinity)))
      b.min.set(Math.min(b.min.x, x - r), Math.min(b.min.y, y - r), Math.min(b.min.z, z - r))
      b.max.set(Math.max(b.max.x, x + r), Math.max(b.max.y, y + r), Math.max(b.max.z, z + r))
    }
    this.cells = []
    for (let c = 0; c < gw * gh; c++) {
      if (!counts[c]) continue
      const box = boxes[c].expandByScalar(MARGIN)
      this.cells.push({ start: starts[c], count: counts[c], box, mx: (box.min.x + box.max.x) / 2, mz: (box.min.z + box.max.z) / 2 })
    }
    // until the first pack, everything is drawn
    this.pack(null, null)
  }
  // Pack the cells in view (all of them with no view) into the buffers.
  pack(view, focus) {
    const A = this.matAttr.array
    const C = this.colAttr?.array
    const S = this.smats
    const T = this.thin
    const L = view ? this.lod : null
    const A1 = L ? this.matAttr1.array : null
    let off = 0
    let off1 = 0
    for (const c of this.cells) {
      if (view) {
        if (!(view.frustum.intersectsBox(c.box) || (this.casts && view.hasShadow && view.shadow.intersectsBox(c.box)))) continue
      }
      let k = c.count
      if (T && focus) {
        const d = Math.hypot(c.mx - focus.x, c.mz - focus.z)
        k = Math.max(1, Math.ceil(k * Math.min(1, Math.max(T.min, 1 - (d - T.near) / T.span))))
      }
      if (L) {
        // each instance to the full model or the cheap one by its own distance
        const cam = view.cam
        for (let i = c.start; i < c.start + k; i++) {
          const o = i * 16
          const dx = S[o + 12] - cam.x
          const dy = S[o + 13] - cam.y
          const dz = S[o + 14] - cam.z
          if (dx * dx + dy * dy + dz * dz < L.d2) {
            const w = off++ * 16
            for (let q = 0; q < 16; q++) A[w + q] = S[o + q]
          } else {
            const w = off1++ * 16
            for (let q = 0; q < 16; q++) A1[w + q] = S[o + q]
          }
        }
        continue
      }
      A.set(S.subarray(c.start * 16, (c.start + k) * 16), off * 16)
      if (C) C.set(this.scols.subarray(c.start * 3, (c.start + k) * 3), off * 3)
      off += k
    }
    for (const m of this.meshes) m.count = off
    this.matAttr.clearUpdateRanges()
    this.matAttr.addUpdateRange(0, Math.max(1, off * 16))
    this.matAttr.needsUpdate = true
    if (this.lod) {
      for (const m of this.lod.meshes) m.count = off1
      this.matAttr1.clearUpdateRanges()
      this.matAttr1.addUpdateRange(0, Math.max(1, off1 * 16))
      this.matAttr1.needsUpdate = true
    }
    if (this.colAttr) {
      this.colAttr.clearUpdateRanges()
      this.colAttr.addUpdateRange(0, Math.max(1, off * 3))
      this.colAttr.needsUpdate = true
    }
    this.drawn = off + off1
    this.drawnFar = off1
  }
}
