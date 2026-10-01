// Conveyor belts. A belt carries one resource from a station (or a Storage
// Depot) to another station (or a depot) along a route over open ground.
// Items ride it at the tier's speed, at least `gap` metres apart, so a belt
// moves speed / gap items a second. What arrives fills the station's input
// buffer; what a belted station makes waits in its output buffer for the
// next free spot on the belt. Stations without belts haul from storage.
import { STATIONS, BELTS, BELT_STACK, BELT_BONUS, ALT_RECIPES, SEC_PER_DAY, SIGNAL } from './data.js'
import { S, bounds, gateTiles, stationSize, pay, gain, capOf, linkCost, removeLink, isUnlocked, researchDone } from './state.js'
import { bus, uid } from '../core/util.js'

export const BELT_Y = 2.45 // deck height of the lowest belts: survivors walk underneath
export const BELT_DY = 0.62 // each crossing layer runs this much higher
export const stackOf = (k) => BELT_STACK[k] || 1
export const beltSpeed = (tier) => BELTS[tier].speed * (researchDone('logistics') ? 1.25 : 1)
export const linkRate = (tier) => beltSpeed(tier) / BELTS[tier].gap // items a second
export const linkPerDay = (tier, k) => linkRate(tier) * stackOf(k) * SEC_PER_DAY
export const isDepot = (st) => st?.type === 'storage'
const byId = (id) => S.stations.find((s) => s.id === id)
export const linksOf = (st) => (S.links || []).filter((l) => l.from === st.id || l.to === st.id)

// ---------------------------------------------------------------- what goes in and out
function recipesOf(st) {
  const D = STATIONS[st.type]
  const out = []
  if (D.recipe) out.push(D.recipe)
  if (D.recipes) out.push(...Object.values(D.recipes))
  for (const a of Object.values(st.alts || {})) if (ALT_RECIPES[a]) out.push(ALT_RECIPES[a].recipe)
  return out
}
// Resources a station can take off a belt, in recipe order.
const SIGNAL_INPUTS = [...new Set(SIGNAL.flatMap((p) => Object.keys(p.cost)))]
export function inputsOf(st) {
  if (st.type === 'generator') return ['fuel']
  if (st.type === 'boiler') return [...STATIONS.boiler.fuels]
  if (st.type === 'mast') return SIGNAL_INPUTS
  const set = new Set()
  for (const r of recipesOf(st)) for (const [k, v] of Object.entries(r.in || {})) if (v > 0) set.add(k)
  return [...set]
}
// Resources a station can put on a belt.
export function outputsOf(st) {
  const D = STATIONS[st.type]
  const set = new Set(Object.keys(D.passive || {}))
  for (const r of recipesOf(st)) for (const k of Object.keys(r.out || {})) set.add(k)
  return [...set]
}
export const takesIn = (st, k) => (isDepot(st) ? k !== 'cash' : inputsOf(st).includes(k))
export const givesOut = (st, k) => (isDepot(st) ? k !== 'cash' : outputsOf(st).includes(k))
// Input buffers hold about four batches; output buffers hold a few.
export function inCap(st, k) {
  if (isDepot(st)) return Infinity
  if (st.type === 'generator') return 10
  if (st.type === 'boiler') return 16
  if (st.type === 'mast') return 40 * stackOf(k)
  let m = 0
  for (const r of recipesOf(st)) m = Math.max(m, r.in?.[k] || 0)
  return Math.max(3 * stackOf(k), Math.ceil(m * 4))
}
export const outCap = (k, v = 1) => Math.max(6 * stackOf(k), v * 3)

export const feeds = (st, k) => (S.links || []).some((l) => l.to === st.id && l.res === k)
export const belted = (st, k) => (S.links || []).some((l) => l.from === st.id && l.res === k)
// A belted product is made on demand when a belt takes it to another station;
// if every belt for it ends at a depot, the station's stock target still applies.
export const pulled = (st, k) => (S.links || []).some((l) => l.from === st.id && l.res === k && !isDepot(byId(l.to)))

// Belting saves the hauling: +15% when every input arrives by belt, and
// +15% when everything made leaves by belt.
export function beltBonus(st, R) {
  if (!R || !S.links?.length) return 1
  const ins = Object.keys(R.in || {}).filter((k) => R.in[k] > 0)
  const outs = Object.keys(R.out || {})
  let b = 1
  if (ins.length && ins.every((k) => feeds(st, k))) b += BELT_BONUS
  if (outs.length && outs.every((k) => belted(st, k))) b += BELT_BONUS
  return b
}

// ---------------------------------------------------------------- routing
const DX = [1, 0, -1, 0]
const DZ = [0, 1, 0, -1]
const TURN = 2.5 // a corner costs as much as 2.5 m of belt
const SHARE = 4 // running over another belt costs 4 m

// The tiles beside a station, each with the direction leading away from it
// and the point on the station's edge where a belt meets it.
function ringOf(st) {
  const [w, d] = stationSize(st)
  const out = []
  for (let z = st.z; z < st.z + d; z++) {
    out.push({ x: st.x + w, z, dir: 0, ex: st.x + w, ez: z + 0.5 })
    out.push({ x: st.x - 1, z, dir: 2, ex: st.x, ez: z + 0.5 })
  }
  for (let x = st.x; x < st.x + w; x++) {
    out.push({ x, z: st.z + d, dir: 1, ex: x + 0.5, ez: st.z + d })
    out.push({ x, z: st.z - 1, dir: 3, ex: x + 0.5, ez: st.z })
  }
  return out
}
// Ground a belt may cross: inside the fence and off the clutter strip, not
// over a station, the gate lane or the supply van.
function beltField(skip = null) {
  const b = bounds()
  const x0 = b.x0 + 2
  const z0 = b.z0 + 2
  const W = b.x1 - 2 - x0 + 1
  const H = b.z1 - 2 - z0 + 1
  const block = new Uint8Array(W * H)
  const mark = (x, z) => {
    x -= x0
    z -= z0
    if (x >= 0 && z >= 0 && x < W && z < H) block[z * W + x] = 1
  }
  for (const st of S.stations) {
    if (st === skip) continue
    const [w, d] = stationSize(st)
    for (let x = st.x; x < st.x + w; x++) for (let z = st.z; z < st.z + d; z++) mark(x, z)
  }
  const g = gateTiles(b)
  for (let x = g[0] - 7; x <= g[2] + 2; x++) for (let z = b.z1 - 7; z <= b.z1; z++) mark(x, z)
  return { x0, z0, W, H, block }
}
// Tiles under the gate lane and van, where no belt may run (for placement previews).
export function beltBlocked(x, z) {
  const b = bounds()
  const g = gateTiles(b)
  return x < b.x0 + 2 || z < b.z0 + 2 || x > b.x1 - 2 || z > b.z1 - 2 || (x >= g[0] - 7 && x <= g[2] + 2 && z >= b.z1 - 7)
}

// Shortest tidy route from the side of station `a` to the side of station
// `b`: Dijkstra over (tile, heading) so corners and shared tiles cost extra.
// Returns { pts: corner points, tiles: [x, z, x, z…], len } or null.
export function routeBelt(a, b, ignore = null) {
  if (!a || !b || a === b) return null
  const { x0, z0, W, H, block } = beltField()
  const N = W * H
  const share = new Uint8Array(N)
  for (const l of S.links || []) {
    if (l === ignore) continue
    for (let i = 0; i < l.tiles.length; i += 2) {
      const x = l.tiles[i] - x0
      const z = l.tiles[i + 1] - z0
      if (x >= 0 && z >= 0 && x < W && z < H) share[z * W + x] = Math.min(9, share[z * W + x] + 1)
    }
  }
  const free = (x, z) => x >= 0 && z >= 0 && x < W && z < H && !block[z * W + x]
  const goalDir = new Int8Array(N).fill(-1)
  const goalEdge = new Map()
  for (const r of ringOf(b)) {
    const x = r.x - x0
    const z = r.z - z0
    if (!free(x, z)) continue
    goalDir[z * W + x] = (r.dir + 2) & 3
    goalEdge.set(z * W + x, r)
  }
  if (!goalEdge.size) return null
  const dist = new Float32Array(N * 4).fill(Infinity)
  const from = new Int32Array(N * 4).fill(-1)
  const startEdge = new Map()
  const heap = new Heap()
  for (const r of ringOf(a)) {
    const x = r.x - x0
    const z = r.z - z0
    if (!free(x, z)) continue
    const t = z * W + x
    const s = t * 4 + r.dir
    const c = share[t] * SHARE
    startEdge.set(t, r)
    if (c < dist[s]) {
      dist[s] = c
      heap.push(s, c)
    }
  }
  let best = Infinity
  let bestS = -1
  while (heap.size) {
    const c = heap.topPri()
    const s = heap.pop()
    if (c > dist[s]) continue
    if (c >= best) break
    const t = s >> 2
    const d = s & 3
    if (goalDir[t] >= 0) {
      const tot = c + (goalDir[t] !== d ? TURN : 0)
      if (tot < best) {
        best = tot
        bestS = s
      }
    }
    const tx = t % W
    const tz = (t / W) | 0
    for (let nd = 0; nd < 4; nd++) {
      if (nd === ((d + 2) & 3)) continue
      const nx = tx + DX[nd]
      const nz = tz + DZ[nd]
      if (!free(nx, nz)) continue
      const nt = nz * W + nx
      const nc = c + 1 + (nd !== d ? TURN : 0) + share[nt] * SHARE
      const ns = nt * 4 + nd
      if (nc < dist[ns]) {
        dist[ns] = nc
        from[ns] = s
        heap.push(ns, nc)
      }
    }
  }
  if (bestS < 0) return null
  const path = []
  for (let s = bestS; s >= 0; s = from[s]) path.push(s >> 2)
  path.reverse()
  const se = startEdge.get(path[0])
  const ge = goalEdge.get(path[path.length - 1])
  const pts = [[se.ex, se.ez]]
  for (const t of path) pts.push([x0 + (t % W) + 0.5, z0 + ((t / W) | 0) + 0.5])
  pts.push([ge.ex, ge.ez])
  // keep only the corners
  const P = [pts[0]]
  for (let i = 1; i < pts.length - 1; i++) {
    const [ax, az] = P[P.length - 1]
    const [bx, bz] = pts[i]
    const [cx, cz] = pts[i + 1]
    if (Math.abs((bx - ax) * (cz - bz) - (bz - az) * (cx - bx)) > 1e-6) P.push(pts[i])
  }
  P.push(pts[pts.length - 1])
  let len = 0
  for (let i = 1; i < P.length; i++) len += Math.hypot(P[i][0] - P[i - 1][0], P[i][1] - P[i - 1][1])
  const tiles = []
  for (const t of path) tiles.push(x0 + (t % W), z0 + ((t / W) | 0))
  return { pts: P, tiles, len: Math.round(len * 100) / 100 }
}

// The lowest height layer that no belt sharing a tile with this one uses.
function pickLayer(tiles, ignore = null) {
  const mine = new Set()
  for (let i = 0; i < tiles.length; i += 2) mine.add(tiles[i] * 4096 + tiles[i + 1])
  const used = new Set()
  for (const l of S.links || []) {
    if (l === ignore) continue
    for (let i = 0; i < l.tiles.length; i += 2) {
      if (mine.has(l.tiles[i] * 4096 + l.tiles[i + 1])) {
        used.add(l.layer || 0)
        break
      }
    }
  }
  let L = 0
  while (used.has(L)) L++
  return L
}

// ---------------------------------------------------------------- building
// Can a belt run from a to b with resource k? Returns null, or why not.
export function linkProblem(a, b, k) {
  if (!isUnlocked('belt', 1)) return 'Belts need the Conveyors milestone'
  if (!a || !b || a === b) return 'Pick another station'
  if (isDepot(a) && isDepot(b)) return 'Depots share one store already'
  if (!givesOut(a, k)) return `${STATIONS[a.type].name} doesn't make that`
  if (!takesIn(b, k)) return `${STATIONS[b.type].name} doesn't use that`
  if ((S.links || []).some((l) => l.from === a.id && l.to === b.id && l.res === k)) return 'Already belted'
  return null
}
export function planLink(a, b, k, tier = 1) {
  const why = linkProblem(a, b, k)
  if (why) return { why }
  const r = routeBelt(a, b)
  if (!r) return { why: 'No route: buildings or the wall are in the way' }
  return { ...r, cost: linkCost(tier, r.len), layer: pickLayer(r.tiles), tier }
}
export function addLink(a, b, k, tier = 1) {
  const p = planLink(a, b, k, tier)
  if (p.why) return p.why
  if (!pay(p.cost)) return 'Not enough materials'
  const l = { id: uid('ln'), from: a.id, to: b.id, res: k, tier, layer: p.layer, pts: p.pts, tiles: p.tiles, len: p.len, items: [], flow: 0, moved: 0 }
  S.links.push(l)
  bus.emit('links')
  return l
}
export const upgradeCostOf = (l) => (l.tier < BELTS.length - 1 ? linkCost(l.tier + 1, l.len) : null)
export const upgradeLocked = (l) => !isUnlocked('belt', l.tier + 1)
export function upgradeLink(l) {
  const c = upgradeCostOf(l)
  if (!c || upgradeLocked(l) || !pay(c)) return false
  l.tier++
  bus.emit('links')
  return true
}
// After a station moves: re-route its belts. Any that can't find a way are
// taken down with a full refund. Returns how many were lost.
export function rerouteLinks(st) {
  let lost = 0
  for (const l of linksOf(st)) {
    const r = routeBelt(byId(l.from), byId(l.to), l)
    if (!r) {
      removeLink(l, 1)
      lost++
      continue
    }
    if (l.items.length) gain({ [l.res]: l.items.length * stackOf(l.res) })
    Object.assign(l, { pts: r.pts, tiles: r.tiles, len: r.len, items: [], layer: pickLayer(r.tiles, l) })
  }
  bus.emit('links')
  return lost
}
export { removeLink }

// ---------------------------------------------------------------- moving items
let rr = 0
export function tickLinks(dt) {
  const L = S.links
  if (!L?.length || dt <= 0) return
  // a different belt goes first each tick, so belts sharing a source split it evenly
  rr = (rr + 1) % L.length
  for (let j = 0; j < L.length; j++) {
    const l = L[(j + rr) % L.length]
    const src = byId(l.from)
    const dst = byId(l.to)
    if (!src || !dst) continue
    const T = { gap: BELTS[l.tier].gap, speed: beltSpeed(l.tier) }
    const n = stackOf(l.res)
    const h = (T.gap * 0.8) / T.speed
    const before = l.moved
    for (let left = dt; left > 1e-6; left -= h) stepLink(l, T, src, dst, n, Math.min(left, h))
    l.flow = (l.flow || 0) + ((l.moved - before) / dt - (l.flow || 0)) * Math.min(1, dt / 25)
  }
}
function stepLink(l, T, src, dst, n, dt) {
  const it = l.items
  let lim = l.len
  for (let i = 0; i < it.length; i++) {
    const d = Math.min(it[i] + T.speed * dt, lim)
    it[i] = d
    lim = d - T.gap
  }
  if (it.length && it[0] >= l.len - 1e-3) {
    if (deliver(dst, l.res, n)) {
      it.shift()
      l.moved += n
      l.jam = 0
    } else l.jam = (l.jam || 0) + dt
  }
  if (!it.length || it[it.length - 1] >= T.gap) {
    if (load(src, dst, l, n)) it.push(0)
    else if (!it.length) l.jam = 0
  }
}
function deliver(st, k, n) {
  if (isDepot(st)) {
    if (S.res[k] + n > capOf(k) + 1e-6) return false
    S.res[k] += n
    return true
  }
  const b = (st.buf = st.buf || { in: {}, out: {} }).in
  if ((b[k] || 0) + n > inCap(st, k) + 1e-6) return false
  b[k] = (b[k] || 0) + n
  return true
}
function load(src, dst, l, n) {
  const k = l.res
  if (isDepot(src)) {
    if (S.res[k] < n) return false
    // only send what the far end has room for
    if (!isDepot(dst) && (dst.buf?.in?.[k] || 0) + (l.items.length + 1) * n > inCap(dst, k)) return false
    S.res[k] -= n
    return true
  }
  const o = (src.buf = src.buf || { in: {}, out: {} }).out
  if ((o[k] || 0) < n - 1e-6) return false
  o[k] -= n
  return true
}
// How a belt is doing, for labels and panels.
export function linkState(l) {
  if ((l.jam || 0) > 1.5) return 'backed'
  if (!l.items.length) return 'idle'
  return 'moving'
}

class Heap {
  constructor() {
    this.n = []
    this.p = []
  }
  get size() {
    return this.n.length
  }
  topPri() {
    return this.p[0]
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
        const a = i * 2 + 1
        const b = a + 1
        let m = i
        if (a < n.length && p[a] < p[m]) m = a
        if (b < n.length && p[b] < p[m]) m = b
        if (m === i) break
        ;[n[m], n[i]] = [n[i], n[m]]
        ;[p[m], p[i]] = [p[i], p[m]]
        i = m
      }
    }
    return top
  }
}
