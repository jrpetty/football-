// Station models share a few dozen materials, but every station drew each of
// its materials on its own: a big camp spent well over a thousand draw calls
// a frame (main pass plus shadows) on walls and roofs that never move. The
// batcher gathers those still parts into one BatchedMesh per material and
// shadow setting, so the whole camp's buildings draw in a few dozen calls.
// The original meshes stay where they were, hidden, so picking, bounds and
// anything that walks a model keep working.
import * as THREE from 'three'

const START_INSTANCES = 64
const START_VERTS = 32768

// non-indexed geometry gets an index of its own (a batch is all one or the other)
const indexed = new WeakMap()
function withIndex(g) {
  if (g.index) return g
  let c = indexed.get(g)
  if (!c) {
    c = new THREE.BufferGeometry()
    for (const k in g.attributes) c.setAttribute(k, g.attributes[k])
    const n = g.attributes.position.count
    const idx = n > 65535 ? new Uint32Array(n) : new Uint16Array(n)
    for (let i = 0; i < n; i++) idx[i] = i
    c.setIndex(new THREE.BufferAttribute(idx, 1))
    c.boundingBox = g.boundingBox
    c.boundingSphere = g.boundingSphere
    indexed.set(g, c)
  }
  return c
}

function attrSig(g) {
  let s = ''
  for (const k of Object.keys(g.attributes).sort()) {
    const a = g.attributes[k]
    s += `${k}:${a.itemSize}${a.normalized ? 'n' : ''},`
  }
  return s
}

// Whether a mesh can go into a batch: plain, opaque, one material, nothing
// that changes per frame.
export function batchable(o) {
  if (!o.isMesh || o.isInstancedMesh || o.isSkinnedMesh || o.isBatchedMesh) return false
  const m = o.material
  if (!m || Array.isArray(m) || m.transparent || o.userData.flame) return false
  const g = o.geometry
  if (!g?.attributes.position || Object.keys(g.morphAttributes).length || g.groups.length > 1) return false
  if (o.renderOrder || o.layers.mask !== 1) return false
  return true
}

export class StationBatcher {
  constructor(scene) {
    this.scene = scene
    this.batches = new Map()
    this.owned = new Map()
  }
  batchFor(mesh, g) {
    const key = `${mesh.material.uuid}|${mesh.castShadow ? 1 : 0}${mesh.receiveShadow ? 1 : 0}|${attrSig(g)}`
    let B = this.batches.get(key)
    if (!B) {
      const bm = new THREE.BatchedMesh(START_INSTANCES, START_VERTS, START_VERTS * 2, mesh.material)
      bm.castShadow = mesh.castShadow
      bm.receiveShadow = mesh.receiveShadow
      bm.name = 'stations:' + (mesh.material.name || mesh.material.userData.key || '')
      // picking goes through the hidden originals
      bm.raycast = () => {}
      bm.frustumCulled = false
      this.scene.add(bm)
      B = { bm, geos: new Map(), maxV: START_VERTS, maxI: START_VERTS * 2, waste: 0 }
      this.batches.set(key, B)
    }
    return B
  }
  // A geometry's slot in its batch. Station parts share their model's
  // geometry and keep their slot for the next station of that kind; geometry
  // built for one use (a station's pods) leaves the batch when it is disposed.
  geometryFor(B, src) {
    let G = B.geos.get(src)
    if (G) return G
    const g = withIndex(src)
    const nv = g.attributes.position.count
    const ni = g.index.count
    const bm = B.bm
    if ((bm.unusedVertexCount < nv || bm.unusedIndexCount < ni) && B.waste) {
      bm.optimize()
      B.waste = 0
    }
    if (bm.unusedVertexCount < nv || bm.unusedIndexCount < ni) {
      const usedV = B.maxV - bm.unusedVertexCount
      const usedI = B.maxI - bm.unusedIndexCount
      B.maxV = Math.max(B.maxV * 2, usedV + nv)
      B.maxI = Math.max(B.maxI * 2, usedI + ni)
      bm.setGeometrySize(B.maxV, B.maxI)
    }
    G = { id: bm.addGeometry(g), refs: 0, dead: false, nv }
    B.geos.set(src, G)
    const onDispose = () => {
      src.removeEventListener('dispose', onDispose)
      G.dead = true
      if (!G.refs) this.drop(B, src, G)
    }
    src.addEventListener('dispose', onDispose)
    G.off = () => src.removeEventListener('dispose', onDispose)
    return G
  }
  drop(B, src, G) {
    if (B.geos.get(src) !== G) return
    B.bm.deleteGeometry(G.id)
    B.geos.delete(src)
    B.waste += G.nv
  }
  // Take a mesh's look into its batch (at its current world transform) and
  // hide the mesh itself.
  add(mesh) {
    if (this.owned.has(mesh) || !batchable(mesh)) return false
    const B = this.batchFor(mesh, withIndex(mesh.geometry))
    const G = this.geometryFor(B, mesh.geometry)
    const bm = B.bm
    if (bm.instanceCount >= bm.maxInstanceCount) bm.setInstanceCount(bm.maxInstanceCount * 2)
    const iid = bm.addInstance(G.id)
    bm.setMatrixAt(iid, mesh.matrixWorld)
    G.refs++
    mesh.visible = false
    this.owned.set(mesh, { B, G, iid, src: mesh.geometry })
    return true
  }
  remove(mesh) {
    const o = this.owned.get(mesh)
    if (!o) return
    o.B.bm.deleteInstance(o.iid)
    this.owned.delete(mesh)
    mesh.visible = true
    o.G.refs--
    if (o.G.dead && !o.G.refs) this.drop(o.B, o.src, o.G)
  }
  setMatrix(mesh, m) {
    const o = this.owned.get(mesh)
    if (o) o.B.bm.setMatrixAt(o.iid, m)
  }
  setVisible(mesh, v) {
    const o = this.owned.get(mesh)
    if (o) o.B.bm.setVisibleAt(o.iid, v)
  }
  // How many batches and parts (for profiling).
  stats() {
    let parts = 0
    for (const B of this.batches.values()) parts += B.bm.instanceCount
    return { batches: this.batches.size, parts }
  }
  dispose() {
    for (const B of this.batches.values()) {
      for (const G of B.geos.values()) G.off()
      this.scene.remove(B.bm)
      B.bm.dispose()
    }
    this.batches.clear()
    this.owned.clear()
  }
}

// A throwaway batch per material of a model, so the shaders a batch needs can
// compile ahead of time (see warmup.js).
export function batchedCopies(model) {
  const g = new THREE.Group()
  for (const o of model.children) {
    if (!batchable(o)) continue
    const geo = withIndex(o.geometry)
    const bm = new THREE.BatchedMesh(1, geo.attributes.position.count, geo.index.count, o.material)
    bm.castShadow = o.castShadow
    bm.receiveShadow = o.receiveShadow
    bm.addInstance(bm.addGeometry(geo))
    g.add(bm)
  }
  return g
}
