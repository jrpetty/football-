// The perimeter wall: instanced segments per variant (intact, damaged, broken,
// corners), rebuilt when the camp grows or the wall is upgraded, plus the gate.
import * as THREE from 'three'
import { fenceSegment, gateModel, FENCE_VARIANTS } from '../models/fence.js'
import { S, bounds, fenceTiles, gateTiles, fenceMax } from '../game/state.js'
import { BLOCK } from '../core/grid.js'

const KINDS = ['v0', 'v1', 'v2', 'v3', 'damaged', 'broken', 'corner']

export class FenceView {
  constructor(base) {
    this.base = base
    this.group = new THREE.Group()
    base.scene.add(this.group)
    this.level = -1
    this.boundsKey = ''
    this.sets = {}
    this.gate = null
    this.gateOpen = 0
    this.gateWant = 0
  }
  // Build the instanced meshes for the current level (once per level).
  buildLevel(lv) {
    for (const set of Object.values(this.sets)) for (const im of set) {
      this.group.remove(im)
      im.geometry.dispose()
    }
    this.sets = {}
    const max = 320
    for (const k of KINDS) {
      const model = fenceSegment(lv, k, KINDS.indexOf(k) + 1)
      model.updateMatrixWorld(true)
      const list = []
      model.traverse((o) => {
        if (!o.isMesh) return
        const g = o.geometry.clone()
        g.applyMatrix4(o.matrixWorld)
        const im = new THREE.InstancedMesh(g, o.material, max)
        im.count = 0
        im.castShadow = true
        im.receiveShadow = true
        im.frustumCulled = false
        im.userData.pick = { type: 'fence' }
        this.group.add(im)
        list.push(im)
      })
      this.sets[k] = list
    }
    if (this.gate) this.group.remove(this.gate)
    this.gate = gateModel(lv)
    this.gate.traverse((o) => {
      if (o.isMesh) o.userData.pick = { type: 'gate' }
    })
    this.group.add(this.gate)
    this.level = lv
  }
  // Re-place every segment for the current shape and damage.
  refresh() {
    const lv = S.fence.level
    if (lv !== this.level) {
      this.buildLevel(lv)
      this.boundsKey = ''
    }
    const b = bounds()
    const key = `${b.x0},${b.x1},${b.z0},${b.z1}`
    const shapeChanged = key !== this.boundsKey
    this.boundsKey = key
    const tiles = fenceTiles(b)
    this.tiles = tiles
    const gates = new Set(gateTiles(b))
    const mx = fenceMax()
    const grid = this.base.grid
    // clear old fence occupancy when the shape changes
    if (shapeChanged) {
      for (let i = 0; i < grid.owner.length; i++) if (grid.owner[i] === 'fence' || grid.owner[i] === 'gate') {
        grid.cost[i] = 0
        grid.opaque[i] = 0
        grid.owner[i] = null
      }
    }
    const m = new THREE.Matrix4()
    const q = new THREE.Quaternion()
    const sc = new THREE.Vector3(1, 1, 1)
    const p = new THREE.Vector3()
    const up = new THREE.Vector3(0, 1, 0)
    const counts = Object.fromEntries(KINDS.map((k) => [k, 0]))
    const place = (kind, x, z, ry) => {
      q.setFromAxisAngle(up, ry)
      m.compose(p.set(x, 0, z), q, sc)
      for (const im of this.sets[kind]) im.setMatrixAt(counts[kind], m)
      counts[kind]++
    }
    tiles.forEach(([x, z], i) => {
      const corner = (x === b.x0 || x === b.x1) && (z === b.z0 || z === b.z1)
      const side = z === b.z0 ? 'n' : z === b.z1 ? 's' : x === b.x0 ? 'w' : 'e'
      const ry = side === 'n' ? 0 : side === 's' ? Math.PI : side === 'w' ? Math.PI / 2 : -Math.PI / 2
      const gate = side === 's' && gates.has(x)
      const hp = S.fence.hp[i] ?? mx
      const broken = hp <= 0
      if (gate) grid.set(x, z, this.gateOpen > 0.5 ? 0 : broken ? 0 : BLOCK, 0, 'gate')
      else grid.set(x, z, broken ? 0 : BLOCK, 0, 'fence')
      if (gate) return
      if (corner) {
        place(broken ? 'broken' : 'corner', x + 0.5, z + 0.5, ry)
        return
      }
      const kind = broken ? 'broken' : hp < mx * 0.5 ? 'damaged' : 'v' + (((x * 7 + z * 13) % FENCE_VARIANTS) + FENCE_VARIANTS) % FENCE_VARIANTS
      place(kind, x + 0.5, z + 0.5, ry)
    })
    for (const k of KINDS) for (const im of this.sets[k]) {
      im.count = counts[k]
      im.instanceMatrix.needsUpdate = true
    }
    const g = gateTiles(b)
    this.gate.position.set(g[1] + 0.5, 0, b.z1 + 0.5)
    this.gate.rotation.y = Math.PI
    const gi = tiles.findIndex(([x, z]) => z === b.z1 && x === g[1])
    this.gate.visible = !(gi >= 0 && (S.fence.hp[gi] ?? mx) <= 0)
  }
  tileIndexAt(x, z) {
    return (this.tiles || []).findIndex(([tx, tz]) => tx === x && tz === z)
  }
  openGate(sec = 6) {
    this.gateUntil = performance.now() + sec * 1000
  }
  update(dt) {
    const want = (this.gateUntil || 0) > performance.now() ? 1 : 0
    const prev = this.gateOpen
    this.gateOpen += (want - this.gateOpen) * Math.min(1, dt * 2.2)
    if (this.gate) {
      const pv = this.gate.userData.pivots
      if (pv.doorL) pv.doorL.rotation.y = -this.gateOpen * 1.45
      if (pv.doorR) pv.doorR.rotation.y = this.gateOpen * 1.45
    }
    if ((prev > 0.5) !== (this.gateOpen > 0.5)) {
      const b = bounds()
      for (const x of gateTiles(b)) {
        const i = this.tileIndexAt(x, b.z1)
        const broken = (S.fence.hp[i] ?? 1) <= 0
        this.base.grid.set(x, b.z1, this.gateOpen > 0.5 || broken ? 0 : BLOCK, 0, 'gate')
      }
    }
  }
}
