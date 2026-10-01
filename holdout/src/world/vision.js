// Line-of-sight vision for supply runs. Every survivor casts rays through the
// 1 m tile grid each tick: walls, neighbouring buildings, the van and tall
// shelving stop sight, windows don't. Tiles carry three values:
//   visible  - seen right now (soft falloff toward the edge of sight)
//   explored - seen before, or mapped by walking into the room
//   sensed   - felt through one wall by a scout (wall sense)
// Values ease over time and are baked into a texture four texels per tile.
// Each texel blends its nearest tile centres, but never across a wall, so
// light doesn't leak into the next room; texels inside a wall take the value
// of the room on their side. A full-screen pass (render/fow.js) shades the
// scene from that texture.
import * as THREE from 'three'

const SUB = 4
const TAU = Math.PI * 2
// Containers taller than this block sight (store aisles, wardrobes, racks).
const TALL = new Set(['fridge', 'cabinet', 'wardrobe', 'bookshelf', 'shelf', 'toolrack', 'locker', 'gunlocker', 'pallet', 'server', 'chemshelf', 'firelocker', 'shed'])

const angDiff = (a, b) => {
  let d = (a - b) % TAU
  if (d > Math.PI) d -= TAU
  if (d < -Math.PI) d += TAU
  return Math.abs(d)
}

export class Vision {
  constructor(lv) {
    const W = (this.W = lv.W)
    const H = (this.H = lv.H)
    this.lv = lv
    this.x0 = lv.x0
    this.z0 = lv.z0
    const N = W * H
    this.N = N
    this.block = new Uint8Array(N) // stops sight
    this.solid = new Uint8Array(N) // has no floor of its own: texels take a neighbour's value
    this.target = new Float32Array(N)
    this.tsense = new Float32Array(N)
    this.vis = new Float32Array(N)
    this.sense = new Float32Array(N)
    this.seen = new Uint8Array(N)
    this.mem = new Float32Array(N)
    const g = lv.grid
    const windows = new Set()
    for (const w of lv.windows) if ((w.i * 5 + w.j) % 7 !== 0) windows.add(w.j * W + w.i) // boarded ones stay shut
    for (let k = 0; k < N; k++) {
      const own = g.owner[k]
      const wall = lv.walls[k] === 1
      if (wall) {
        this.solid[k] = 1
        this.block[k] = windows.has(k) ? 0 : 1
      } else if (g.opaque[k]) {
        // neighbouring buildings, the van, trucks, the edge of the play area
        this.solid[k] = 1
        this.block[k] = 1
      } else if (own && typeof own === 'object' && own.kind && TALL.has(own.kind)) {
        this.block[k] = 1
        this.solid[k] = 1
      }
    }
    // rooms: their floor plus the ring of walls around them
    this.roomTiles = lv.rooms.map((r) => {
      const out = []
      for (let j = r.j0 - 1; j <= r.j1 + 1; j++)
        for (let i = r.i0 - 1; i <= r.i1 + 1; i++) {
          if (i < 0 || j < 0 || i >= W || j >= H) continue
          const k = j * W + i
          const inside = i >= r.i0 && i <= r.i1 && j >= r.j0 && j <= r.j1
          if ((inside && lv.roomAt[k] === r.id) || (!inside && (lv.walls[k] === 1 || lv.roomAt[k] === r.id))) out.push(k)
          else if (inside) out.push(k)
        }
      return Int32Array.from(out)
    })
    this.roomSeen = new Uint8Array(lv.rooms.length)
    this.buildWeights()
    this.data = new Uint8Array(W * SUB * H * SUB * 4)
    this.tex = new THREE.DataTexture(this.data, W * SUB, H * SUB, THREE.RGBAFormat, THREE.UnsignedByteType)
    this.tex.magFilter = THREE.LinearFilter
    this.tex.minFilter = THREE.LinearFilter
    this.tex.generateMipmaps = false
    this.tex.flipY = false
    this.tex.needsUpdate = true
    this.changed = true
  }

  // Outdoors is known from the drive in; inside the building it is not.
  revealOutdoors() {
    const lv = this.lv
    for (let k = 0; k < this.N; k++) {
      if (lv.roomAt[k] >= 0) continue
      const i = k % this.W
      const j = (k / this.W) | 0
      if (lv.buildings.some((B) => i >= B.i0 && i <= B.i1 && j >= B.j0 && j <= B.j1)) continue
      this.seen[k] = 1
      this.mem[k] = 1
    }
    // the outer walls of each building are seen from the street
    for (const B of lv.buildings) {
      for (let i = B.i0; i <= B.i1; i++)
        for (const j of [B.j0, B.j1]) this.markSeen(j * this.W + i)
      for (let j = B.j0; j <= B.j1; j++)
        for (const i of [B.i0, B.i1]) this.markSeen(j * this.W + i)
    }
  }
  markSeen(k) {
    if (k < 0 || k >= this.N) return
    this.seen[k] = 1
    this.mem[k] = 1
  }

  // Each texel blends up to four tile centres, skipping tiles behind a wall.
  buildWeights() {
    const W = this.W
    const H = this.H
    const TW = W * SUB
    const TH = H * SUB
    const n = TW * TH
    const I4 = (this.I4 = new Int32Array(n * 4).fill(-1))
    const W4 = (this.W4 = new Float32Array(n * 4))
    const solid = this.solid
    const inb = (i, j) => i >= 0 && j >= 0 && i < W && j < H
    const open = (i, j) => inb(i, j) && !solid[j * W + i]
    // can sight/light pass between two open tiles without crossing a wall?
    const linked = (ai, aj, bi, bj) => {
      if (!open(bi, bj)) return false
      const di = bi - ai
      const dj = bj - aj
      if (!di && !dj) return true
      if (!di || !dj) return true
      return open(ai + di, aj) || open(ai, aj + dj)
    }
    for (let tz = 0; tz < TH; tz++) {
      for (let tx = 0; tx < TW; tx++) {
        const t = tz * TW + tx
        const px = (tx + 0.5) / SUB
        const pz = (tz + 0.5) / SUB
        const hi = Math.floor(px)
        const hj = Math.floor(pz)
        const cand = []
        if (open(hi, hj)) {
          const i0 = Math.floor(px - 0.5)
          const j0 = Math.floor(pz - 0.5)
          const fx = px - 0.5 - i0
          const fz = pz - 0.5 - j0
          const corners = [
            [i0, j0, (1 - fx) * (1 - fz)],
            [i0 + 1, j0, fx * (1 - fz)],
            [i0, j0 + 1, (1 - fx) * fz],
            [i0 + 1, j0 + 1, fx * fz],
          ]
          for (const [ci, cj, w] of corners) if (w > 1e-4 && linked(hi, hj, ci, cj)) cand.push([cj * W + ci, w])
          if (!cand.length) cand.push([hj * W + hi, 1])
        } else if (inb(hi, hj)) {
          // inside a wall: take the room on this side of it
          const ox = px - (hi + 0.5)
          const oz = pz - (hj + 0.5)
          const sx = ox < 0 ? -1 : 1
          const sz = oz < 0 ? -1 : 1
          const ax = Math.abs(ox)
          const az = Math.abs(oz)
          if (open(hi + sx, hj)) cand.push([hj * W + hi + sx, ax + 0.02])
          if (open(hi, hj + sz)) cand.push([(hj + sz) * W + hi, az + 0.02])
          if (open(hi + sx, hj + sz) && (open(hi + sx, hj) || open(hi, hj + sz))) cand.push([(hj + sz) * W + hi + sx, ax * az * 0.5])
          if (!cand.length) cand.push([hj * W + hi, 1])
        }
        let tot = 0
        for (const c of cand) tot += c[1]
        cand.sort((a, b) => b[1] - a[1])
        for (let q = 0; q < Math.min(4, cand.length); q++) {
          I4[t * 4 + q] = cand[q][0]
          W4[t * 4 + q] = cand[q][1] / (tot || 1)
        }
      }
    }
  }

  // ---------------------------------------------------------------- casting
  // observers: [{ x, z, heading, sight, back, cone, pierce, torch: { r, half } }]
  update(observers) {
    this.target.fill(0)
    this.tsense.fill(0)
    for (const o of observers) this.castFrom(o)
    const lv = this.lv
    for (const o of observers) {
      // walking into a room maps all of it
      const i = Math.floor(o.x - this.x0)
      const j = Math.floor(o.z - this.z0)
      if (i < 0 || j < 0 || i >= this.W || j >= this.H) continue
      const r = lv.roomAt[j * this.W + i]
      if (r >= 0 && !this.roomSeen[r]) {
        this.roomSeen[r] = 1
        for (const k of this.roomTiles[r]) this.seen[k] = 1
        this.newRoom = lv.rooms[r]
      }
    }
    const T = this.target
    const S = this.tsense
    for (let k = 0; k < this.N; k++) if (T[k] > 0.3 || S[k] > 0.3) this.seen[k] = 1
  }
  castFrom(o) {
    const ox = o.x - this.x0
    const oz = o.z - this.z0
    const maxR = Math.max(o.sight, o.torch ? o.torch.r : 0, o.pierce || 0)
    const n = Math.max(96, Math.ceil(TAU * maxR * 1.8))
    const cone = o.cone ?? 1.22
    const back = o.back ?? 0.62
    for (let k = 0; k < n; k++) {
      const a = (k / n) * TAU
      const d = angDiff(a, o.heading)
      // full sight ahead, shorter behind, blended over ~20 degrees
      const blend = d <= cone ? 1 : d >= cone + 0.35 ? 0 : 1 - (d - cone) / 0.35
      let R = o.sight * (back + (1 - back) * blend)
      if (o.torch && d < o.torch.half) R = Math.max(R, o.torch.r * (1 - 0.35 * (d / o.torch.half) ** 2))
      this.ray(ox, oz, Math.sin(a), Math.cos(a), R, o.pierce || 0)
    }
    // the tile they stand on
    const i = Math.floor(ox)
    const j = Math.floor(oz)
    if (i >= 0 && j >= 0 && i < this.W && j < this.H) this.target[j * this.W + i] = 1
  }
  ray(ox, oz, dx, dz, R, pierce) {
    const W = this.W
    const H = this.H
    const block = this.block
    const T = this.target
    const S = this.tsense
    let i = Math.floor(ox)
    let j = Math.floor(oz)
    const si = dx > 0 ? 1 : -1
    const sj = dz > 0 ? 1 : -1
    const tdx = Math.abs(dx) < 1e-9 ? 1e9 : Math.abs(1 / dx)
    const tdz = Math.abs(dz) < 1e-9 ? 1e9 : Math.abs(1 / dz)
    let tmx = dx > 0 ? (i + 1 - ox) * tdx : (ox - i) * tdx
    let tmz = dz > 0 ? (j + 1 - oz) * tdz : (oz - j) * tdz
    const fade = R * 0.7
    const reach = Math.max(R, pierce)
    let sensing = false
    let t = 0
    for (let guard = 0; guard < 256 && t <= reach; guard++) {
      if (i < 0 || j < 0 || i >= W || j >= H) return
      const k = j * W + i
      const cx = i + 0.5 - ox
      const cz = j + 0.5 - oz
      const dist = Math.sqrt(cx * cx + cz * cz)
      if (!sensing) {
        if (t > R) return
        const v = dist <= fade ? 1 : dist >= R ? 0 : 1 - (dist - fade) / (R - fade)
        if (v > T[k]) T[k] = v
        if (block[k]) {
          if (pierce <= 0 || t > pierce) return
          sensing = true
        }
      } else {
        if (t > pierce) return
        const v = Math.min(1, (pierce - dist) / 1.6 + 0.15)
        if (v > S[k]) S[k] = v
        if (block[k]) return
      }
      // step to the next tile; at an exact corner don't slip between two blockers
      if (Math.abs(tmx - tmz) < 1e-6) {
        if (block[j * W + i + si] && block[(j + sj) * W + i]) return
        t = tmx
        tmx += tdx
        tmz += tdz
        i += si
        j += sj
      } else if (tmx < tmz) {
        t = tmx
        tmx += tdx
        i += si
      } else {
        t = tmz
        tmz += tdz
        j += sj
      }
    }
  }

  // ---------------------------------------------------------------- easing + texture
  ease(dt) {
    const up = 1 - Math.exp(-dt * 10)
    const down = 1 - Math.exp(-dt * 3.2)
    const memUp = 1 - Math.exp(-dt * 2.4)
    const V = this.vis
    const T = this.target
    const Sv = this.sense
    const St = this.tsense
    const M = this.mem
    const seen = this.seen
    const W = this.W
    // the box of tiles that changed this frame: only that part gets re-baked
    let i0 = 1e9
    let i1 = -1
    let j0 = 1e9
    let j1 = -1
    for (let k = 0; k < this.N; k++) {
      let ch = false
      const v = V[k]
      const t = T[k]
      if (v !== t) {
        const nv = v + (t - v) * (t > v ? up : down)
        V[k] = Math.abs(nv - t) < 0.004 ? t : nv
        ch = true
      }
      const s = Sv[k]
      const st = St[k]
      if (s !== st) {
        const ns = s + (st - s) * (st > s ? up : down)
        Sv[k] = Math.abs(ns - st) < 0.004 ? st : ns
        ch = true
      }
      if (seen[k] && M[k] < 1) {
        M[k] = M[k] > 0.995 ? 1 : Math.min(1, M[k] + (1 - M[k]) * memUp + 0.002)
        ch = true
      }
      if (ch) {
        const i = k % W
        const j = (k - i) / W
        if (i < i0) i0 = i
        if (i > i1) i1 = i
        if (j < j0) j0 = j
        if (j > j1) j1 = j
      }
    }
    if (i1 >= 0) this.markDirty(i0, j0, i1, j1)
  }
  markDirty(i0, j0, i1, j1) {
    const d = this.dirty
    if (!d) this.dirty = { i0, j0, i1, j1 }
    else {
      d.i0 = Math.min(d.i0, i0)
      d.j0 = Math.min(d.j0, j0)
      d.i1 = Math.max(d.i1, i1)
      d.j1 = Math.max(d.j1, j1)
    }
    this.changed = true
  }
  bake() {
    if (!this.changed) return
    this.changed = false
    const D = this.data
    const I4 = this.I4
    const W4 = this.W4
    const V = this.vis
    const M = this.mem
    const Sv = this.sense
    const TW = this.W * SUB
    const TH = this.H * SUB
    // texels blend neighbouring tiles, so widen the changed box by one tile
    const d = this.dirty || { i0: 0, j0: 0, i1: this.W - 1, j1: this.H - 1 }
    this.dirty = null
    const x0 = Math.max(0, (d.i0 - 1) * SUB)
    const x1 = Math.min(TW, (d.i1 + 2) * SUB)
    const y0 = Math.max(0, (d.j0 - 1) * SUB)
    const y1 = Math.min(TH, (d.j1 + 2) * SUB)
    for (let ty = y0; ty < y1; ty++)
    for (let t = ty * TW + x0, te = ty * TW + x1; t < te; t++) {
      const b = t * 4
      let v = 0
      let m = 0
      let s = 0
      for (let q = 0; q < 4; q++) {
        const k = I4[b + q]
        if (k < 0) break
        const w = W4[b + q]
        v += V[k] * w
        m += M[k] * w
        s += Sv[k] * w
      }
      D[b] = v * 255
      D[b + 1] = m * 255
      D[b + 2] = s * 255
    }
    this.tex.needsUpdate = true
  }

  // ---------------------------------------------------------------- queries (lot-local world coords)
  idx(x, z) {
    const i = Math.floor(x - this.x0)
    const j = Math.floor(z - this.z0)
    if (i < 0 || j < 0 || i >= this.W || j >= this.H) return -1
    return j * this.W + i
  }
  visibleAt(x, z) {
    const k = this.idx(x, z)
    return k < 0 ? 0 : this.vis[k]
  }
  seesNow(x, z) {
    const k = this.idx(x, z)
    return k < 0 ? 0 : this.target[k]
  }
  sensedAt(x, z) {
    const k = this.idx(x, z)
    return k < 0 ? 0 : this.sense[k]
  }
  exploredAt(x, z) {
    const k = this.idx(x, z)
    return k < 0 ? 1 : this.seen[k]
  }
  exploredFrac() {
    let n = 0
    let s = 0
    for (let k = 0; k < this.N; k++) {
      if (this.lv.roomAt[k] < 0) continue
      n++
      if (this.seen[k]) s++
    }
    return n ? s / n : 1
  }

  // ---------------------------------------------------------------- memory between visits
  save() {
    const bytes = new Uint8Array(Math.ceil(this.N / 8))
    for (let k = 0; k < this.N; k++) if (this.seen[k] && this.lv.roomAt[k] >= 0) bytes[k >> 3] |= 1 << (k & 7)
    let s = ''
    for (const b of bytes) s += String.fromCharCode(b)
    return { w: this.W, h: this.H, bits: btoa(s) }
  }
  load(d) {
    if (!d || d.w !== this.W || d.h !== this.H) return
    const s = atob(d.bits)
    for (let k = 0; k < this.N; k++) {
      if ((s.charCodeAt(k >> 3) >> (k & 7)) & 1) {
        this.seen[k] = 1
        this.mem[k] = 1
      }
    }
    // rooms already fully known don't need their reveal again
    this.roomTiles.forEach((tiles, r) => {
      let all = true
      for (const k of tiles) if (this.lv.roomAt[k] === r && !this.seen[k]) all = false
      if (all) this.roomSeen[r] = 1
    })
    // walls around known floor
    for (let k = 0; k < this.N; k++) {
      if (!this.seen[k] || this.lv.roomAt[k] < 0) continue
      const i = k % this.W
      for (const n of [k - 1, k + 1, k - this.W, k + this.W]) if (n >= 0 && n < this.N && this.solid[n] && Math.abs((n % this.W) - i) <= 1) this.markSeen(n)
    }
    this.changed = true
  }
  dispose() {
    this.tex.dispose()
  }
}
