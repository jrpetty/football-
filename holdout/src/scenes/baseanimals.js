// Livestock in the camp: hens and goats that wander their run, peck and
// graze, go in for the night, and the fence the player puts round them. The
// coop or pen model reports its run (I.pen: a rectangle in local metres) and
// its door (I.door); the animals live inside the station's model group, so
// they turn with it.
import { Builder } from '../models/kit.js'
import { shadeHex } from '../models/parts.js'
import { STATIONS, PEN_FENCE } from '../game/data.js'
import { flockOf } from '../game/economy.js'
import { rand } from '../core/util.js'

const TAU = Math.PI * 2
const cache = new Map()
const built = (key, fn) => {
  if (cache.has(key)) return cache.get(key)
  const b = new Builder()
  fn(b)
  const g = b.build()
  g.traverse((o) => {
    if (o.isMesh) o.castShadow = true
  })
  cache.set(key, g)
  return g
}
// A hen: head on a pivot that pecks.
function henModel(color) {
  return built('hen' + color, (b) => {
    b.sphere(0.13, { mat: 'cloth', color, y: 0.21, sx: 0.85, sz: 1.25, ws: 10, hs: 8 })
    b.cone(0.08, 0.16, { mat: 'cloth', color: shadeHex(color, -0.15), y: 0.32, z: -0.13, rx: -0.7, seg: 6 })
    for (const s of [-1, 1]) b.sphere(0.07, { mat: 'cloth', color: shadeHex(color, -0.08), x: s * 0.09, y: 0.22, z: -0.01, sx: 0.4, sz: 1.1, ws: 8, hs: 6 })
    for (const s of [-1, 1]) b.cyl(0.008, 0.008, 0.11, { mat: 'plain', color: '#d8a020', x: s * 0.04, y: 0.055, seg: 4 })
    b.pivot('head', { y: 0.3, z: 0.1 }, (p) => {
      p.sphere(0.065, { mat: 'cloth', color, y: 0.05, z: 0.02, ws: 8, hs: 6 })
      p.box(0.016, 0.05, 0.07, { mat: 'plain', color: '#c8201a', y: 0.12, z: 0.02 })
      p.box(0.012, 0.035, 0.025, { mat: 'plain', color: '#c8201a', y: 0.0, z: 0.07 })
      p.cone(0.017, 0.045, { mat: 'plain', color: '#d8a020', y: 0.04, z: 0.1, rx: Math.PI / 2, seg: 4 })
      for (const s of [-1, 1]) p.sphere(0.009, { mat: 'gloss', color: '#101010', x: s * 0.045, y: 0.07, z: 0.05, ws: 6, hs: 4 })
    })
  })
}
// A goat: legs, a barrel body, a head on a pivot that grazes, horns, a beard.
function goatModel(color) {
  return built('goat' + color, (b) => {
    const dark = shadeHex(color, -0.25)
    b.box(0.3, 0.3, 0.62, { mat: 'cloth', color, y: 0.58, r: 0.12, seg: 3 })
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
      b.cyl(0.035, 0.03, 0.46, { mat: 'cloth', color, x: sx * 0.1, y: 0.23, z: sz * 0.22, seg: 6 })
      b.cyl(0.032, 0.032, 0.06, { mat: 'plain', color: '#2a2420', x: sx * 0.1, y: 0.03, z: sz * 0.22, seg: 6 })
    }
    b.cone(0.04, 0.12, { mat: 'cloth', color: dark, y: 0.72, z: -0.32, rx: -2.2, seg: 5 })
    b.pivot('head', { y: 0.72, z: 0.28 }, (p) => {
      p.cyl(0.07, 0.09, 0.22, { mat: 'cloth', color, y: 0.06, z: 0.04, rx: 0.6, seg: 8 })
      p.box(0.13, 0.13, 0.24, { mat: 'cloth', color, y: 0.15, z: 0.16, r: 0.05, rx: 0.35 })
      p.box(0.09, 0.08, 0.08, { mat: 'cloth', color: shadeHex(color, 0.1), y: 0.08, z: 0.28, r: 0.03, rx: 0.35 })
      for (const s of [-1, 1]) {
        p.box(0.03, 0.05, 0.12, { mat: 'cloth', color: dark, x: s * 0.09, y: 0.2, z: 0.08, rz: s * 1.1 })
        p.cone(0.02, 0.16, { mat: 'plain', color: '#a8988a', x: s * 0.035, y: 0.3, z: 0.06, rx: -0.9, seg: 5 })
        p.sphere(0.012, { mat: 'gloss', color: '#d8b020', x: s * 0.06, y: 0.2, z: 0.2, ws: 6, hs: 4 })
      }
      p.cone(0.025, 0.09, { mat: 'cloth', color: dark, y: 0.0, z: 0.28, rx: Math.PI, seg: 5 })
    })
  })
}
const HEN_COLS = ['#e8dcc8', '#a8582a', '#3a3028', '#d8c8a0', '#8a4a2a']
const GOAT_COLS = ['#ece6da', '#8a6a4a', '#3a3430', '#c8b8a0', '#a07a52']

// A fence round the footprint: posts every metre, and a gate in the front.
export function penFence(w, d, tier) {
  return built(`fence${w}x${d}t${tier}`, (b) => {
    const post = tier === 2 ? { mat: 'wood', color: '#6a5440' } : { mat: 'wood', color: '#9a8466' }
    const hw = w / 2 - 0.08
    const hd = d / 2 - 0.08
    const gate = 0.9 // half-width of the gate gap in the front
    const side = (x0, z0, x1, z1, front) => {
      const len = Math.hypot(x1 - x0, z1 - z0)
      const n = Math.max(1, Math.round(len))
      const ry = Math.atan2(x1 - x0, z1 - z0)
      for (let i = 0; i <= n; i++) {
        const t = i / n
        const x = x0 + (x1 - x0) * t
        const z = z0 + (z1 - z0) * t
        if (front && Math.abs(x) < gate - 0.05) continue
        b.box(0.08, tier === 2 ? 1.25 : 1.0, 0.08, { ...post, x, y: tier === 2 ? 0.62 : 0.5, z })
      }
      // rails or planks, split round the gate on the front
      const runs = front ? [[x0, -gate], [gate, x1]] : [[0, 1]]
      for (const [a0, a1] of runs) {
        let ax, az, bx, bz
        if (front) {
          ax = a0
          bx = a1
          az = bz = z0
        } else {
          ax = x0
          az = z0
          bx = x1
          bz = z1
        }
        const L = Math.hypot(bx - ax, bz - az)
        if (L < 0.1) continue
        const cx = (ax + bx) / 2
        const cz = (az + bz) / 2
        if (tier === 2) {
          for (const y of [0.25, 0.55, 0.85]) b.box(0.035, 0.16, L, { mat: 'wood', color: shadeHex('#8a6a4a', (y - 0.5) * 0.2), x: cx, y, z: cz, ry, jitter: 0.06 })
          b.box(0.006, 1.05, L, { mat: 'steel', color: '#8a8e90', x: cx, y: 0.55, z: cz, ry, ao: 0 })
        } else {
          for (const y of [0.3, 0.75]) b.box(0.03, 0.06, L, { mat: 'wood', color: '#a89070', x: cx, y, z: cz, ry })
          const np = Math.floor(L / 0.16)
          for (let i = 0; i < np; i++) {
            const t = (i + 0.5) / np
            b.box(0.025, 0.86, 0.08, { mat: 'wood', color: shadeHex('#c8b494', (Math.sin(i * 7.3) * 0.5) * 0.12), x: ax + (bx - ax) * t, y: 0.43, z: az + (bz - az) * t, ry, jitter: 0.05 })
          }
        }
      }
    }
    side(-hw, -hd, hw, -hd, false)
    side(-hw, -hd, -hw, hd, false)
    side(hw, -hd, hw, hd, false)
    side(-hw, hd, hw, hd, true)
    // the gate, swung half open
    b.at({ x: -gate, z: hd, ry: -0.6 }, () => {
      for (const y of [0.3, 0.75]) b.box(gate * 0.95, 0.07, 0.035, { mat: 'wood', color: '#9a7a52', x: gate * 0.48, y })
      b.beam([0.05, 0.3, 0], [gate * 0.9, 0.75, 0], 0.05, 0.035, { mat: 'wood', color: '#9a7a52' })
    })
  })
}

// One station's animals and fence.
export class AnimalPen {
  constructor(view) {
    this.view = view
    this.animals = []
    this.fence = null
    this.fenceKey = ''
  }
  // keep up with the flock size and fence tier (the model may have been rebuilt)
  sync() {
    const v = this.view
    const st = v.st
    const D = STATIONS[st.type]
    const I = v.info
    const model = v.model
    if (!model || !I.pen) return
    if (this.model !== model) {
      this.model = model
      this.animals = []
      this.fence = null
      this.fenceKey = ''
    }
    // fence
    const tier = st.pen || 0
    const fk = `${tier}`
    if (fk !== this.fenceKey) {
      if (this.fence) model.remove(this.fence)
      this.fence = null
      this.fenceKey = fk
      if (tier > 0) {
        const [w, d] = D.size
        this.fence = penFence(w, d, tier).clone()
        model.add(this.fence)
      }
    }
    // animals
    const n = Math.min(12, flockOf(st))
    const goat = D.livestock === 'goat'
    while (this.animals.length < n) {
      const i = this.animals.length
      const g = (goat ? goatModel(GOAT_COLS[(i * 3 + st.id.length) % GOAT_COLS.length]) : henModel(HEN_COLS[(i * 7 + st.id.length) % HEN_COLS.length])).clone()
      const head = g.getObjectByName('head')
      const p = I.pen
      const a = { g, head, x: rand(p.x0, p.x1), z: rand(p.z0, p.z1), tx: 0, tz: 0, wait: rand(0, 3), yaw: rand(0, TAU), bob: rand(0, TAU), inside: false }
      a.tx = a.x
      a.tz = a.z
      g.position.set(a.x, 0, a.z)
      g.traverse((o) => {
        if (o.isMesh) o.userData.pick = { type: 'station', st }
      })
      model.add(g)
      this.animals.push(a)
    }
    while (this.animals.length > n) {
      const a = this.animals.pop()
      model.remove(a.g)
    }
  }
  update(dt, ctx) {
    const v = this.view
    const I = v.info
    if (!I.pen || !this.animals.length) return
    const goat = STATIONS[v.st.type].livestock === 'goat'
    const night = ctx.night > 0.55
    const speed = goat ? 0.45 : 0.6
    const p = I.pen
    for (const a of this.animals) {
      // at night they go in and settle
      if (night && I.door) {
        a.tx = I.door.x + Math.sin(a.bob) * 0.15
        a.tz = I.door.z
      }
      const dx = a.tx - a.x
      const dz = a.tz - a.z
      const dist = Math.hypot(dx, dz)
      if (dist > 0.05) {
        const step = Math.min(dist, speed * dt)
        a.x += (dx / dist) * step
        a.z += (dz / dist) * step
        const want = Math.atan2(dx, dz)
        let d = ((want - a.yaw + Math.PI) % TAU + TAU) % TAU - Math.PI
        a.yaw += d * Math.min(1, dt * 6)
        a.walk = (a.walk || 0) + dt * (goat ? 7 : 12)
        a.g.position.y = Math.abs(Math.sin(a.walk)) * (goat ? 0.025 : 0.02)
        if (a.head) a.head.rotation.x = 0
      } else {
        a.g.position.y = 0
        a.wait -= dt
        // pecking or grazing while they stand
        a.bob += dt * (goat ? 1.4 : 5)
        if (a.head) a.head.rotation.x = goat ? 0.5 + Math.max(0, Math.sin(a.bob)) * 0.5 : Math.max(0, Math.sin(a.bob * 1.3)) ** 6 * 1.1
        if (a.wait <= 0 && !night) {
          a.wait = rand(1.5, 6)
          a.tx = rand(p.x0, p.x1)
          a.tz = rand(p.z0, p.z1)
        }
      }
      // indoors at night: out of sight
      const home = night && I.door && dist < 0.12
      a.g.visible = !home
      a.g.position.x = a.x
      a.g.position.z = a.z
      a.g.rotation.y = a.yaw
    }
  }
}
