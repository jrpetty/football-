// Tile grid with A* pathfinding and line-of-sight. Tile (x, z) covers world
// [x, x+1) × [z, z+1); its centre is (x + 0.5, z + 0.5).
//
// Tall buildings keep each floor in its own strip of columns; links (stairs,
// a lift) join tiles on different floors, and the search measures distance
// in each floor's own frame so it heads for the stairs.

export const BLOCK = 255

export class Grid {
  constructor(w, h) {
    this.w = w
    this.h = h
    this.cost = new Uint8Array(w * h) // 0 = open, BLOCK = solid, 1..254 = extra cost
    this.opaque = new Uint8Array(w * h)
    this.owner = new Array(w * h).fill(null) // entity occupying the tile
    this.links = null // tile index -> [{ to, cost, kind, ref }]
    this.fl = null // floors: { w0, rw, off }
  }
  // Floors past column w0, each rw wide; off[k] shifts floor k back onto the
  // ground floor's columns. links: [{ a: [i, j], b: [i, j], kind, cost, off }]
  setFloors(w0, rw, off, links) {
    this.fl = { w0, rw, off }
    this.links = new Map()
    for (const L of links) {
      const a = this.i(L.a[0], L.a[1])
      const b = this.i(L.b[0], L.b[1])
      const add = (from, to) => {
        if (!this.links.has(from)) this.links.set(from, [])
        this.links.get(from).push({ to, cost: L.cost, kind: L.kind, ref: L })
      }
      add(a, b)
      add(b, a)
    }
  }
  floorOf(x) {
    const f = this.fl
    return !f || x < f.w0 ? 0 : 1 + Math.floor((x - f.w0) / f.rw)
  }
  // a column in its floor's own frame (the ground floor's columns)
  localX(x) {
    const f = this.fl
    return !f || x < f.w0 ? x : x - f.off[this.floorOf(x)]
  }
  inb(x, z) {
    return x >= 0 && z >= 0 && x < this.w && z < this.h
  }
  i(x, z) {
    return z * this.w + x
  }
  open(x, z) {
    return this.inb(x, z) && this.cost[z * this.w + x] !== BLOCK
  }
  set(x, z, cost, opaque = 0, owner = null) {
    if (!this.inb(x, z)) return
    const i = z * this.w + x
    this.cost[i] = cost
    this.opaque[i] = opaque
    this.owner[i] = owner
  }
  fillRect(x0, z0, w, d, cost, opaque = 0, owner = null) {
    for (let x = x0; x < x0 + w; x++) for (let z = z0; z < z0 + d; z++) this.set(x, z, cost, opaque, owner)
  }
  rectOpen(x0, z0, w, d) {
    for (let x = x0; x < x0 + w; x++) for (let z = z0; z < z0 + d; z++) if (!this.open(x, z) || this.owner[this.i(x, z)]) return false
    return true
  }

  // Nearest open tile to (x, z), searched in growing rings.
  nearestOpen(x, z, maxR = 12, pred = null) {
    x = Math.floor(x)
    z = Math.floor(z)
    if (this.open(x, z) && (!pred || pred(x, z))) return { x, z }
    for (let r = 1; r <= maxR; r++) {
      let best = null
      let bd = 1e9
      for (let dx = -r; dx <= r; dx++) {
        for (let dz = -r; dz <= r; dz++) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) !== r) continue
          const nx = x + dx
          const nz = z + dz
          if (!this.open(nx, nz) || (pred && !pred(nx, nz))) continue
          const d = dx * dx + dz * dz
          if (d < bd) {
            bd = d
            best = { x: nx, z: nz }
          }
        }
      }
      if (best) return best
    }
    return null
  }

  // A* over 8-connected tiles without cutting blocked corners.
  // Returns world-space waypoints (tile centres), excluding the start tile.
  // Waypoints reached through a link carry { link: kind, from: {x, z} }.
  // opts.lift lets the search ride a running lift (people do, the dead don't).
  path(sx, sz, tx, tz, maxIter = 6000, opts = null) {
    sx = Math.floor(sx)
    sz = Math.floor(sz)
    tx = Math.floor(tx)
    tz = Math.floor(tz)
    if (!this.open(tx, tz)) {
      const n = this.nearestOpen(tx, tz, 6)
      if (!n) return null
      tx = n.x
      tz = n.z
    }
    if (!this.open(sx, sz)) {
      const n = this.nearestOpen(sx, sz, 3)
      if (!n) return null
      sx = n.x
      sz = n.z
    }
    if (sx === tx && sz === tz) return [{ x: tx + 0.5, z: tz + 0.5 }]
    const W = this.w
    const N = this.w * this.h
    if (!this._g || this._g.length !== N) {
      this._g = new Float32Array(N)
      this._from = new Int32Array(N)
      this._stamp = new Uint32Array(N)
      this._closed = new Uint32Array(N)
      this._run = 0
    }
    const g = this._g
    const from = this._from
    const stamp = this._stamp
    const closed = this._closed
    const run = ++this._run
    const heap = new Heap()
    const start = sz * W + sx
    const goal = tz * W + tx
    g[start] = 0
    stamp[start] = run
    from[start] = -1
    const fl = this.fl
    const tf = fl ? this.floorOf(tx) : 0
    const tlx = fl ? this.localX(tx) : tx
    const hfn = fl
      ? (x, z) => {
          const f = this.floorOf(x)
          const dx = Math.abs((f ? x - fl.off[f] : x) - tlx)
          const dz = Math.abs(z - tz)
          return dx + dz + (Math.SQRT2 - 2) * Math.min(dx, dz) + Math.abs(f - tf) * 1.2
        }
      : (x, z) => {
          const dx = Math.abs(x - tx)
          const dz = Math.abs(z - tz)
          return dx + dz + (Math.SQRT2 - 2) * Math.min(dx, dz)
        }
    if (fl && maxIter === 6000) maxIter = 14000
    const links = this.links
    const lift = !!opts?.lift
    heap.push(start, hfn(sx, sz))
    let iter = 0
    let bestNode = start
    let bestH = hfn(sx, sz)
    while (heap.size && iter++ < maxIter) {
      const cur = heap.pop()
      if (cur === goal) return this._build(from, cur)
      if (closed[cur] === run) continue
      closed[cur] = run
      const cx = cur % W
      const cz = (cur / W) | 0
      for (let dz = -1; dz <= 1; dz++) {
        for (let dx = -1; dx <= 1; dx++) {
          if (!dx && !dz) continue
          const nx = cx + dx
          const nz = cz + dz
          if (!this.open(nx, nz)) continue
          if (dx && dz && (!this.open(cx + dx, cz) || !this.open(cx, cz + dz))) continue
          const ni = nz * W + nx
          if (closed[ni] === run) continue
          const step = (dx && dz ? Math.SQRT2 : 1) + this.cost[ni] * 0.1
          const ng = g[cur] + step
          if (stamp[ni] !== run || ng < g[ni]) {
            stamp[ni] = run
            g[ni] = ng
            from[ni] = cur
            const hh = hfn(nx, nz)
            if (hh < bestH) {
              bestH = hh
              bestNode = ni
            }
            heap.push(ni, ng + hh)
          }
        }
      }
      const L = links && links.get(cur)
      if (L)
        for (const e of L) {
          if (e.ref.off || (e.kind === 'lift' && !lift)) continue
          const ni = e.to
          if (closed[ni] === run || this.cost[ni] === BLOCK) continue
          const ng = g[cur] + e.cost
          if (stamp[ni] !== run || ng < g[ni]) {
            stamp[ni] = run
            g[ni] = ng
            from[ni] = cur
            const hh = hfn(ni % W, (ni / W) | 0)
            if (hh < bestH) {
              bestH = hh
              bestNode = ni
            }
            heap.push(ni, ng + hh)
          }
        }
    }
    // Unreachable: walk as close as possible.
    return bestNode !== start ? this._build(from, bestNode) : null
  }
  _build(from, node) {
    const W = this.w
    const out = []
    while (node !== -1 && from[node] !== -1) {
      const p = { x: (node % W) + 0.5, z: ((node / W) | 0) + 0.5 }
      const prev = from[node]
      const px = prev % W
      const pz = (prev / W) | 0
      if (Math.abs(px + 0.5 - p.x) > 1.5 || Math.abs(pz + 0.5 - p.z) > 1.5) {
        // a jump between floors
        const e = this.links?.get(prev)?.find((q) => q.to === node)
        p.link = e?.kind || 'stairs'
        p.from = { x: px + 0.5, z: pz + 0.5 }
      }
      out.push(p)
      node = prev
    }
    out.reverse()
    if (!out.some((p) => p.link)) return this.smooth(out)
    // smooth each floor's stretch on its own; keep both ends of every jump
    const res = []
    let seg = []
    for (const p of out) {
      if (p.link) {
        res.push(...this.smooth(seg))
        seg = []
        res.push(p)
      } else seg.push(p)
    }
    res.push(...this.smooth(seg))
    return res
  }
  // Drop waypoints that have a clear straight walk between them.
  smooth(pts) {
    if (pts.length < 3) return pts
    const out = [pts[0]]
    let anchor = pts[0]
    for (let i = 1; i < pts.length - 1; i++) {
      if (!this.walkLine(anchor.x, anchor.z, pts[i + 1].x, pts[i + 1].z)) {
        out.push(pts[i])
        anchor = pts[i]
      }
    }
    out.push(pts[pts.length - 1])
    return out
  }
  // Straight walk check using a fat line (agent radius ~0.3).
  walkLine(ax, az, bx, bz) {
    const d = Math.hypot(bx - ax, bz - az)
    const steps = Math.ceil(d / 0.25)
    const nx = -(bz - az) / (d || 1)
    const nz = (bx - ax) / (d || 1)
    for (let s = 0; s <= steps; s++) {
      const t = s / steps
      const x = ax + (bx - ax) * t
      const z = az + (bz - az) * t
      for (const o of [-0.3, 0, 0.3]) {
        if (!this.open(Math.floor(x + nx * o), Math.floor(z + nz * o))) return false
      }
    }
    return true
  }
  // Line of sight through non-opaque tiles.
  los(ax, az, bx, bz) {
    const d = Math.hypot(bx - ax, bz - az)
    const steps = Math.ceil(d / 0.3)
    for (let s = 1; s < steps; s++) {
      const t = s / steps
      const x = Math.floor(ax + (bx - ax) * t)
      const z = Math.floor(az + (bz - az) * t)
      if (this.inb(x, z) && this.opaque[z * this.w + x]) return false
    }
    return true
  }
}

class Heap {
  constructor() {
    this.n = []
    this.p = []
  }
  get size() {
    return this.n.length
  }
  push(node, pri) {
    const n = this.n
    const p = this.p
    n.push(node)
    p.push(pri)
    let i = n.length - 1
    while (i > 0) {
      const par = (i - 1) >> 1
      if (p[par] <= p[i]) break
      ;[n[par], n[i]] = [n[i], n[par]]
      ;[p[par], p[i]] = [p[i], p[par]]
      i = par
    }
  }
  pop() {
    const n = this.n
    const p = this.p
    const top = n[0]
    const ln = n.pop()
    const lp = p.pop()
    if (n.length) {
      n[0] = ln
      p[0] = lp
      let i = 0
      for (;;) {
        const l = i * 2 + 1
        const r = l + 1
        let m = i
        if (l < n.length && p[l] < p[m]) m = l
        if (r < n.length && p[r] < p[m]) m = r
        if (m === i) break
        ;[n[m], n[i]] = [n[i], n[m]]
        ;[p[m], p[i]] = [p[i], p[m]]
        i = m
      }
    }
    return top
  }
}

// A grid seen through a world offset: world (x, z) = tile (x - ox, z - oz).
// Lets agents walk a level built in its own frame (e.g. a lot centred on 0).
export class OffsetGrid {
  constructor(g, ox, oz) {
    this.g = g
    this.ox = ox
    this.oz = oz
    this.w = g.w
    this.h = g.h
  }
  open(x, z) {
    return this.g.open(x - this.ox, z - this.oz)
  }
  inb(x, z) {
    return this.g.inb(x - this.ox, z - this.oz)
  }
  path(sx, sz, tx, tz, maxIter, opts) {
    const p = this.g.path(sx - this.ox, sz - this.oz, tx - this.ox, tz - this.oz, maxIter, opts)
    return p ? p.map((w) => (w.link ? { x: w.x + this.ox, z: w.z + this.oz, link: w.link, from: { x: w.from.x + this.ox, z: w.from.z + this.oz } } : { x: w.x + this.ox, z: w.z + this.oz })) : null
  }
  los(ax, az, bx, bz) {
    return this.g.los(ax - this.ox, az - this.oz, bx - this.ox, bz - this.oz)
  }
  walkLine(ax, az, bx, bz) {
    return this.g.walkLine(ax - this.ox, az - this.oz, bx - this.ox, bz - this.oz)
  }
  nearestOpen(x, z, maxR, pred) {
    const n = this.g.nearestOpen(x - this.ox, z - this.oz, maxR, pred ? (i, j) => pred(i + this.ox, j + this.oz) : null)
    return n ? { x: n.x + this.ox, z: n.z + this.oz } : null
  }
}
