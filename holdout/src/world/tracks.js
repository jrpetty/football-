// Tracks in the snow. In winter everyone walking outdoors on a run leaves
// prints behind: boots for the squad, bare shuffling feet for the infected.
// They fade over a few minutes (faster while snow is falling). The infected's
// prints show where they went even when they are out of sight; the squad's
// own prints are a trail, and an infected that stumbles on a fresh one
// follows it back the way it came: to the van.
import * as THREE from 'three'

const MAX = 900 // prints of each kind
const STRIDE = { s: 0.72, z: 0.95 }

function printTex(kind) {
  const c = document.createElement('canvas')
  c.width = 64
  c.height = 128
  const g = c.getContext('2d')
  g.fillStyle = '#fff'
  if (kind === 's') {
    // a boot sole: toe and heel, tread bars
    g.beginPath()
    g.ellipse(32, 40, 20, 34, 0, 0, Math.PI * 2)
    g.fill()
    g.beginPath()
    g.ellipse(32, 100, 16, 20, 0, 0, Math.PI * 2)
    g.fill()
    g.globalCompositeOperation = 'destination-out'
    for (let y = 14; y < 72; y += 9) g.fillRect(14, y, 36, 3)
    for (let y = 88; y < 116; y += 8) g.fillRect(18, y, 28, 3)
  } else {
    // a bare, dragging foot: toes, a smeared sole
    g.beginPath()
    g.ellipse(32, 64, 15, 42, 0.08, 0, Math.PI * 2)
    g.fill()
    for (let k = 0; k < 5; k++) {
      g.beginPath()
      g.arc(20 + k * 6, 16 - Math.abs(k - 2) * 2, 4, 0, Math.PI * 2)
      g.fill()
    }
    g.globalAlpha = 0.5
    g.fillRect(24, 100, 16, 28)
  }
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  return t
}

class PrintSet {
  constructor(scene, kind) {
    const geo = new THREE.PlaneGeometry(kind === 's' ? 0.19 : 0.17, 0.38)
    geo.rotateX(-Math.PI / 2)
    this.fade = new Float32Array(MAX)
    geo.setAttribute('aFade', new THREE.InstancedBufferAttribute(this.fade, 1))
    const m = new THREE.MeshStandardMaterial({ map: printTex(kind), color: kind === 's' ? '#4a5466' : '#56604c', transparent: true, depthWrite: false, roughness: 1, polygonOffset: true, polygonOffsetFactor: -3 })
    m.onBeforeCompile = (sh) => {
      sh.vertexShader = sh.vertexShader.replace('#include <common>', '#include <common>\nattribute float aFade;\nvarying float vFade;').replace('#include <begin_vertex>', '#include <begin_vertex>\nvFade = aFade;')
      sh.fragmentShader = sh.fragmentShader.replace('#include <common>', '#include <common>\nvarying float vFade;').replace('#include <map_fragment>', '#include <map_fragment>\ndiffuseColor.a *= vFade * 0.8;')
    }
    m.customProgramCacheKey = () => 'tracks'
    this.mesh = new THREE.InstancedMesh(geo, m, MAX)
    this.mesh.frustumCulled = false
    this.mesh.renderOrder = 1
    this.mesh.count = 0
    this.born = new Float32Array(MAX)
    this.head = 0
    this.n = 0
    scene.add(this.mesh)
    this._m = new THREE.Matrix4()
    this._q = new THREE.Quaternion()
    this._p = new THREE.Vector3()
    this._s = new THREE.Vector3(1, 1, 1)
    this._up = new THREE.Vector3(0, 1, 0)
  }
  add(x, y, z, heading, t) {
    const k = this.head
    this.head = (this.head + 1) % MAX
    this.n = Math.min(MAX, this.n + 1)
    this._q.setFromAxisAngle(this._up, heading)
    this._p.set(x, y, z)
    this._m.compose(this._p, this._q, this._s)
    this.mesh.setMatrixAt(k, this._m)
    this.mesh.instanceMatrix.needsUpdate = true
    this.mesh.count = this.n
    this.born[k] = t
    this.fade[k] = 1
    this.mesh.geometry.attributes.aFade.needsUpdate = true
  }
  refresh(t, life) {
    for (let k = 0; k < this.n; k++) this.fade[k] = Math.max(0, 1 - (t - this.born[k]) / life)
    this.mesh.geometry.attributes.aFade.needsUpdate = true
  }
  dispose() {
    this.mesh.parent?.remove(this.mesh)
    this.mesh.geometry.dispose()
    this.mesh.material.map?.dispose()
    this.mesh.material.dispose()
  }
}

export class Tracks {
  constructor(scene) {
    this.sets = { s: new PrintSet(scene, 's'), z: new PrintSet(scene, 'z') }
    this.t = 0
    this.refreshT = 0
    // the squad's trails, in sim space: per survivor, oldest first
    this.trails = new Map()
    this.cells = new Map() // 2 m buckets of trail points, for zombies to find
  }
  get life() {
    return this.snowing ? 80 : 170
  }
  // call for anyone walking outdoors; p is where they are drawn
  step(a, simX, simZ, p, kind) {
    const st = (a._trk ||= { x: simX, z: simZ, d: 0, side: 1 })
    const d = Math.hypot(simX - st.x, simZ - st.z)
    st.x = simX
    st.z = simZ
    if (d > 3) return // a jump (the stairs, a teleport): no print
    st.d += d
    if (st.d < STRIDE[kind]) return
    st.d = 0
    st.side = -st.side
    const h = a.heading
    const ox = Math.cos(h) * 0.13 * st.side
    const oz = -Math.sin(h) * 0.13 * st.side
    this.sets[kind].add(p.x + ox, p.y + 0.02, p.z + oz, h + Math.PI, this.t)
    if (kind === 's') this.addTrail(a, simX, simZ)
  }
  addTrail(a, x, z) {
    let tr = this.trails.get(a)
    if (!tr) this.trails.set(a, (tr = []))
    const pt = { x, z, t: this.t, tr, i: tr.length }
    tr.push(pt)
    const key = Math.floor(x / 2) + ',' + Math.floor(z / 2)
    let c = this.cells.get(key)
    if (!c) this.cells.set(key, (c = []))
    c.push(pt)
  }
  // a fresh trail point near (x, z), if any
  trailNear(x, z, r = 1.6) {
    const life = this.life
    let best = null
    let bd = r
    const cx = Math.floor(x / 2)
    const cz = Math.floor(z / 2)
    for (let i = cx - 1; i <= cx + 1; i++)
      for (let j = cz - 1; j <= cz + 1; j++) {
        const c = this.cells.get(i + ',' + j)
        if (!c) continue
        for (const p of c) {
          if (this.t - p.t > life * 0.8) continue
          const d = Math.hypot(p.x - x, p.z - z)
          if (d < bd) {
            bd = d
            best = p
          }
        }
      }
    return best
  }
  // the next point along a trail, back the way it came (about 6 m on)
  trailBack(pt) {
    const tr = pt.tr
    let i = pt.i
    let walked = 0
    while (i > 0 && walked < 6) {
      walked += Math.hypot(tr[i].x - tr[i - 1].x, tr[i].z - tr[i - 1].z)
      i--
    }
    return i < pt.i ? tr[i] : null
  }
  update(dt, snowing) {
    this.snowing = snowing
    this.t += dt
    this.refreshT -= dt
    if (this.refreshT <= 0) {
      this.refreshT = 0.5
      for (const s of Object.values(this.sets)) s.refresh(this.t, this.life)
    }
  }
  dispose() {
    for (const s of Object.values(this.sets)) s.dispose()
  }
}
