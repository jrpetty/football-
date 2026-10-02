// Conveyor belts. A belt carries one resource from an outtake pod on one
// building (a station, a Storage Depot, a splitter or a merger) to an intake
// pod on another, along a route over open ground. Items ride it at the
// tier's speed, at least `gap` metres apart, so a belt moves speed / gap
// items a second. What arrives fills the building's input buffer; what a
// belted building makes waits in its output buffer for the next free spot on
// the belt. Buildings without belts haul from storage.
//
// Splitters and mergers pass goods straight through. A splitter hands each
// item to the next of its outgoing belts in turn, so two belts get half each
// and three get a third; when one is full its share goes to the others. A
// merger takes from whichever incoming belt has an item waiting.
import { STATIONS, BELTS, BELT_STACK, BELT_BONUS, SEC_PER_DAY } from './data.js'
import { S, bounds, gateTiles, stationSize, pay, gain, canAfford, capOf, linkCost, removeLink, isUnlocked, researchDone, newStation } from './state.js'
import { isDepot, isNode, nodeKind, inputsOf, outputsOf, recipesOf, portsOf, portAt, freePods, linkAt, podSends, podTakes } from './ports.js'
import { bus, uid } from '../core/util.js'

export { isDepot, isNode, nodeKind, inputsOf, outputsOf, portsOf, portAt, freePods, linkAt, removeLink }
export const BELT_Y = 2.45 // deck height of the lowest belts: survivors walk underneath
export const BELT_DY = 0.62 // each crossing layer runs this much higher
export const stackOf = (k) => BELT_STACK[k] || 1
export const beltSpeed = (tier) => BELTS[tier].speed * (researchDone('logistics') ? 1.25 : 1)
export const linkRate = (tier) => beltSpeed(tier) / BELTS[tier].gap // items a second
export const linkPerDay = (tier, k) => linkRate(tier) * stackOf(k) * SEC_PER_DAY
const byId = (id) => S.stations.find((s) => s.id === id)
export const linksOf = (st) => (S.links || []).filter((l) => l.from === st.id || l.to === st.id)

// ---------------------------------------------------------------- what goes in and out
// What a splitter or merger carries: whatever is on the belts touching it.
export function nodeRes(st, ignore = null) {
  for (const l of S.links || []) if (l !== ignore && (l.from === st.id || l.to === st.id)) return l.res
  return null
}
export const takesIn = (st, k) => k !== 'cash' && (isDepot(st) || (isNode(st) ? (nodeRes(st) ?? k) === k : inputsOf(st).includes(k)))
export const givesOut = (st, k) => k !== 'cash' && (isDepot(st) || (isNode(st) ? (nodeRes(st) ?? k) === k : outputsOf(st).includes(k)))
// Input buffers hold about four batches; output buffers hold a few; a
// splitter or merger holds two items in passing.
export function inCap(st, k) {
  if (isDepot(st)) return Infinity
  if (isNode(st)) return 2 * stackOf(k)
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
const POD = 6 // running over someone else's pod costs 6 m: keep them clear

// Ground a belt may cross: inside the fence and off the clutter strip, not
// over a building, the gate lane or the supply van. Also where the pods
// are, so routes can keep out of their way.
function beltField() {
  const b = bounds()
  const x0 = b.x0 + 2
  const z0 = b.z0 + 2
  const W = b.x1 - 2 - x0 + 1
  const H = b.z1 - 2 - z0 + 1
  const block = new Uint8Array(W * H)
  const pods = new Uint8Array(W * H)
  const idx = (x, z) => (x - x0 >= 0 && z - z0 >= 0 && x - x0 < W && z - z0 < H ? (z - z0) * W + (x - x0) : -1)
  for (const st of S.stations) {
    const [w, d] = stationSize(st)
    for (let x = st.x; x < st.x + w; x++)
      for (let z = st.z; z < st.z + d; z++) {
        const i = idx(x, z)
        if (i >= 0) block[i] = 1
      }
    for (const p of portsOf(st)) {
      const i = idx(p.x, p.z)
      if (i >= 0) pods[i] = 1
    }
  }
  const g = gateTiles(b)
  for (let x = g[0] - 7; x <= g[2] + 2; x++)
    for (let z = b.z1 - 7; z <= b.z1; z++) {
      const i = idx(x, z)
      if (i >= 0) block[i] = 1
    }
  return { x0, z0, W, H, block, pods, idx }
}
// Tiles under the gate lane and van, where no belt may run (for placement previews).
export function beltBlocked(x, z) {
  const b = bounds()
  const g = gateTiles(b)
  return x < b.x0 + 2 || z < b.z0 + 2 || x > b.x1 - 2 || z > b.z1 - 2 || (x >= g[0] - 7 && x <= g[2] + 2 && z >= b.z1 - 7)
}

// Dijkstra over (tile, heading): corners and shared tiles cost extra.
// starts: [{ t, d, c, meta }]; goals: Map(tile -> { need: heading or -1, meta }).
function search(F, share, starts, goals, own) {
  const { W, H, block, pods } = F
  const N = W * H
  const free = (x, z) => x >= 0 && z >= 0 && x < W && z < H && !block[z * W + x]
  const dist = new Float32Array(N * 4).fill(Infinity)
  const from = new Int32Array(N * 4).fill(-1)
  const heap = new Heap()
  const startOf = new Map()
  for (const s0 of starts) {
    const s = s0.t * 4 + s0.d
    if (s0.c < dist[s]) {
      dist[s] = s0.c
      heap.push(s, s0.c)
      startOf.set(s, s0)
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
    const g = goals.get(t)
    if (g) {
      const tot = c + (g.need >= 0 && g.need !== d ? TURN : 0)
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
      const nc = c + 1 + (nd !== d ? TURN : 0) + share[nt] * SHARE + (pods[nt] && !own.has(nt) ? POD : 0)
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
  let s = bestS
  for (; from[s] >= 0; s = from[s]) path.push(s >> 2)
  path.push(s >> 2)
  path.reverse()
  return { path, start: startOf.get(s), goal: goals.get(bestS >> 2), endDir: bestS & 3 }
}

// The tidiest route from an outtake pod of `a` to an intake pod of `b`,
// through any bends the player pinned (`via`, tiles in order). Pods default
// to every free one of the right kind. Returns { pts: corner points,
// tiles: [x, z, x, z…], len, fromPort, toPort } or null.
export function routeBelt(a, b, opts = {}) {
  if (!a || !b || a === b) return null
  const { ignore = null, via = [] } = opts
  const F = beltField()
  const { x0, z0, W, H, idx } = F
  const share = new Uint8Array(W * H)
  for (const l of S.links || []) {
    if (l === ignore) continue
    for (let i = 0; i < l.tiles.length; i += 2) {
      const t = idx(l.tiles[i], l.tiles[i + 1])
      if (t >= 0) share[t] = Math.min(9, share[t] + 1)
    }
  }
  const fromPods = (opts.fromPorts || freePods(a, 'out', ignore)).filter(Boolean)
  const toPods = (opts.toPorts || freePods(b, 'in', ignore)).filter(Boolean)
  if (!fromPods.length || !toPods.length) return null
  const own = new Set()
  const starts = []
  for (const p of fromPods) {
    const t = idx(p.x, p.z)
    if (t < 0 || F.block[t]) continue
    own.add(t)
    starts.push({ t, d: p.dir, c: share[t] * SHARE, meta: p })
  }
  const goals = new Map()
  for (const p of toPods) {
    const t = idx(p.x, p.z)
    if (t < 0 || F.block[t]) continue
    own.add(t)
    goals.set(t, { need: (p.dir + 2) & 3, meta: p })
  }
  if (!starts.length || !goals.size) return null
  // leg by leg through the pinned bends
  const legs = []
  let cur = starts
  for (const [vx, vz] of via) {
    const t = idx(vx, vz)
    if (t < 0 || F.block[t]) return null
    const r = search(F, share, cur, new Map([[t, { need: -1, meta: null }]]), own)
    if (!r) return null
    legs.push(r)
    cur = [{ t, d: r.endDir, c: 0, meta: null }]
  }
  const last = search(F, share, cur, goals, own)
  if (!last) return null
  legs.push(last)
  const path = []
  for (const [i, r] of legs.entries()) path.push(...(i ? r.path.slice(1) : r.path))
  const se = legs[0].start.meta
  const ge = last.goal.meta
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
  return { pts: P, tiles, len: Math.round(len * 100) / 100, fromPort: se.i, toPort: ge.i }
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
const nameOf = (st) => STATIONS[st.type].name
// Can a belt run from a to b with resource k? Returns null, or why not.
// opts.fromPort / opts.toPort pin the pods; opts.ignore is a belt being replaced.
export function linkProblem(a, b, k, opts = {}) {
  if (!isUnlocked('belt', 1)) return 'Belts need the Conveyors milestone'
  if (!a || !b || a === b) return 'Pick another building'
  if (isDepot(a) && isDepot(b)) return 'Depots share one store already'
  const ig = opts.ignore || null
  if (isNode(a) && (nodeRes(a, ig) ?? k) !== k) return `This ${nameOf(a).toLowerCase()} carries ${nodeRes(a, ig)}`
  if (isNode(b) && (nodeRes(b, ig) ?? k) !== k) return `That ${nameOf(b).toLowerCase()} carries ${nodeRes(b, ig)}`
  if (!isNode(a) && !givesOut(a, k)) return `${nameOf(a)} doesn't make that`
  if (!isNode(b) && !takesIn(b, k)) return `${nameOf(b)} doesn't use that`
  if (nodeKind(b) === 'split' && (S.links || []).some((l) => l !== ig && l.to === b.id)) return 'A splitter takes one belt in'
  if (nodeKind(a) === 'merge' && (S.links || []).some((l) => l !== ig && l.from === a.id)) return 'A merger sends one belt out'
  if (opts.fromPort != null) {
    const p = portAt(a, opts.fromPort)
    if (!podSends(p)) return 'That pod takes goods in'
    if (linkAt(a, p.i, ig)) return 'That pod is in use'
  } else if (!freePods(a, 'out', ig).length) return `No free outtake on the ${nameOf(a)}`
  if (opts.toPort != null) {
    const p = portAt(b, opts.toPort)
    if (!podTakes(p)) return 'That pod sends goods out'
    if (linkAt(b, p.i, ig)) return 'That pod is in use'
  } else if (!freePods(b, 'in', ig).length) return `No free intake on the ${nameOf(b)}`
  return null
}
const podsFor = (st, i) => (i == null ? undefined : [portAt(st, i)])
export function planLink(a, b, k, tier = 1, opts = {}) {
  const why = linkProblem(a, b, k, opts)
  if (why) return { why }
  const r = routeBelt(a, b, { ignore: opts.ignore, via: opts.via || [], fromPorts: podsFor(a, opts.fromPort), toPorts: podsFor(b, opts.toPort) })
  if (!r) return { why: opts.via?.length ? 'No route through those bends' : 'No route: buildings or the wall are in the way' }
  return { ...r, cost: linkCost(tier, r.len), layer: pickLayer(r.tiles, opts.ignore), tier, via: opts.via || [] }
}
export function addLink(a, b, k, tier = 1, opts = {}) {
  const p = planLink(a, b, k, tier, opts)
  if (p.why) return p.why
  if (!pay(p.cost)) return 'Not enough materials'
  const l = { id: uid('ln'), from: a.id, to: b.id, res: k, tier, layer: p.layer, pts: p.pts, tiles: p.tiles, len: p.len, fromPort: p.fromPort, toPort: p.toPort, via: p.via.length ? p.via : undefined, items: [], flow: 0, moved: 0 }
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
// Lay a belt again on a new route (same pods if they still work).
function reroute(l, keepVia = true) {
  const a = byId(l.from)
  const b = byId(l.to)
  if (!a || !b) return false
  const pa = portAt(a, l.fromPort)
  const pb = portAt(b, l.toPort)
  const fromPorts = podSends(pa) && !linkAt(a, pa.i, l) ? [pa] : freePods(a, 'out', l)
  const toPorts = podTakes(pb) && !linkAt(b, pb.i, l) ? [pb] : freePods(b, 'in', l)
  let viaKept = keepVia && !!l.via?.length
  let r = routeBelt(a, b, { ignore: l, via: viaKept ? l.via : [], fromPorts, toPorts })
  if (!r && viaKept) {
    viaKept = false
    r = routeBelt(a, b, { ignore: l, fromPorts, toPorts })
  }
  if (!r) r = routeBelt(a, b, { ignore: l })
  if (!r) return false
  if (l.items.length) gain({ [l.res]: l.items.length * stackOf(l.res) })
  Object.assign(l, { pts: r.pts, tiles: r.tiles, len: r.len, fromPort: r.fromPort, toPort: r.toPort, items: [], layer: pickLayer(r.tiles, l) })
  if (!viaKept) delete l.via
  return true
}
// After a building moves (or one is put down across a belt): re-route its
// belts. Any that can't find a way are taken down with a full refund.
// Returns how many were lost.
export function rerouteLinks(st, list = null) {
  let lost = 0
  for (const l of list || linksOf(st)) {
    if (reroute(l)) continue
    removeLink(l, 1)
    lost++
  }
  bus.emit('links')
  return lost
}
// Belts laid before pods existed (or whose pod moved when a building was
// upgraded): plug each into the free pod nearest its end and route it again
// from there. A belt with no free pod left keeps its old route. Returns
// whether anything changed.
export function ensurePorts() {
  let changed = false
  for (const l of S.links || []) {
    if (l.legacy) continue
    const a = byId(l.from)
    const b = byId(l.to)
    if (!a || !b) continue
    const okA = podSends(portAt(a, l.fromPort)) && !linkAt(a, l.fromPort, l)
    const okB = podTakes(portAt(b, l.toPort)) && !linkAt(b, l.toPort, l)
    if (okA && okB) continue
    const near = (st, dir, pt) => {
      const pods = freePods(st, dir, l)
      pods.sort((p, q) => Math.hypot(p.ex - pt[0], p.ez - pt[1]) - Math.hypot(q.ex - pt[0], q.ez - pt[1]))
      return pods[0] || null
    }
    const pa = okA ? portAt(a, l.fromPort) : near(a, 'out', l.pts[0])
    const pb = okB ? portAt(b, l.toPort) : near(b, 'in', l.pts[l.pts.length - 1])
    changed = true
    if (!pa || !pb) {
      l.legacy = true
      continue
    }
    l.fromPort = pa.i
    l.toPort = pb.i
    const r = routeBelt(a, b, { ignore: l, fromPorts: [pa], toPorts: [pb] })
    if (r) Object.assign(l, { pts: r.pts, tiles: r.tiles, len: r.len, layer: pickLayer(r.tiles, l) })
  }
  return changed
}

// ---------------------------------------------------------------- splitters on a belt
// Can a splitter go on tile (x, z) of belt l? Returns null or why not.
export function splitProblem(l, x, z, kind = 'splitter') {
  if (!isUnlocked('station', kind)) return `${STATIONS[kind].name}s need the Conveyors milestone`
  let on = false
  for (let i = 0; i < l.tiles.length; i += 2) if (l.tiles[i] === x && l.tiles[i + 1] === z) on = true
  if (!on) return 'Pick a spot on the belt'
  if (beltBlocked(x, z)) return 'Too close to the gate'
  for (const st of S.stations) {
    const [w, d] = stationSize(st)
    if (x >= st.x - 1 && x <= st.x + w && z >= st.z - 1 && z <= st.z + d) return 'Too close to a building'
  }
  for (const o of S.links || []) {
    if (o === l) continue
    for (let i = 0; i < o.tiles.length; i += 2) if (o.tiles[i] === x && o.tiles[i + 1] === z) return 'Another belt runs over that spot'
  }
  if (!canAfford(STATIONS[kind].cost[0])) return 'Not enough materials'
  return null
}
// Cut belt l at (x, z) and put a splitter (or merger) there: the two halves
// keep the belt's tier, the old belt comes back in full.
export function insertNode(l, x, z, kind = 'splitter') {
  const why = splitProblem(l, x, z, kind)
  if (why) return why
  const a = byId(l.from)
  const b = byId(l.to)
  pay(STATIONS[kind].cost[0])
  const node = newStation(kind, x, z, 0, 1)
  const fromPort = l.fromPort
  const toPort = l.toPort
  const res = l.res
  const tier = l.tier
  removeLink(l, 1)
  const back = () => {
    S.stations = S.stations.filter((s) => s !== node)
    gain(STATIONS[kind].cost[0])
    bus.emit('stations')
  }
  const one = addLink(a, node, res, tier, { fromPort })
  if (typeof one === 'string') {
    back()
    return one
  }
  const two = addLink(node, b, res, tier, { toPort })
  if (typeof two === 'string') {
    removeLink(one, 1)
    back()
    return two
  }
  bus.emit('stations')
  return node
}

// ---------------------------------------------------------------- moving items
let rr = 0
let clock = 0
// a splitter's outgoing belts in a fixed order (by pod), refreshed each tick
const outsOf = new Map()
export function tickLinks(dt, pinfo = null) {
  const L = S.links
  if (!L?.length || dt <= 0) return
  clock += dt
  // no power, no movement: a belt (or a splitter or merger) without it stops
  const on = (id) => !pinfo || pinfo.powered.has(id)
  for (const l of L) l.off = !on(l.id)
  for (const st of S.stations) if (isNode(st)) st.off = !on(st.id)
  outsOf.clear()
  for (const l of L) {
    const src = byId(l.from)
    if (nodeKind(src) !== 'split') continue
    if (!outsOf.has(src.id)) outsOf.set(src.id, [])
    outsOf.get(src.id).push(l)
  }
  for (const list of outsOf.values()) list.sort((p, q) => (p.fromPort ?? 0) - (q.fromPort ?? 0))
  // a different belt goes first each tick, so belts sharing a source split it evenly
  rr = (rr + 1) % L.length
  for (let j = 0; j < L.length; j++) {
    const l = L[(j + rr) % L.length]
    const src = byId(l.from)
    const dst = byId(l.to)
    if (!src || !dst) continue
    if (l.off) {
      l.flow = (l.flow || 0) * Math.max(0, 1 - dt / 25)
      continue
    }
    const T = { gap: BELTS[l.tier].gap, speed: beltSpeed(l.tier) }
    const n = stackOf(l.res)
    const h = (T.gap * 0.8) / T.speed
    const before = l.moved
    for (let left = dt; left > 1e-6; left -= h) stepLink(l, T, src, dst, n, Math.min(left, h))
    l.flow = (l.flow || 0) + ((l.moved - before) / dt - (l.flow || 0)) * Math.min(1, dt / 25)
  }
  // splitters and mergers look busy while goods pass through
  for (const st of S.stations) {
    if (!isNode(st)) continue
    st.active = !st.off && clock - (st.passAt ?? -99) < 3
    if (st.off) st.stalled = 'No power'
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
  st.buf = st.buf || { in: {}, out: {} }
  if (st.off) return false
  // goods pass straight through a splitter or merger
  const b = isNode(st) ? st.buf.out : st.buf.in
  if ((b[k] || 0) + n > inCap(st, k) + 1e-6) return false
  b[k] = (b[k] || 0) + n
  if (isNode(st)) st.passAt = clock
  return true
}
const hasRoom = (l) => !l.items.length || l.items[l.items.length - 1] >= BELTS[l.tier].gap
function load(src, dst, l, n) {
  const k = l.res
  if (isDepot(src)) {
    if (S.res[k] < n) return false
    // only send what the far end has room for
    if (!isDepot(dst) && !isNode(dst) && (dst.buf?.in?.[k] || 0) + (l.items.length + 1) * n > inCap(dst, k)) return false
    S.res[k] -= n
    return true
  }
  const o = (src.buf = src.buf || { in: {}, out: {} }).out
  if (src.off || (o[k] || 0) < n - 1e-6) return false
  // a splitter: each item to the next belt in turn, unless that one is full
  const outs = outsOf.get(src.id)
  if (outs && outs.length > 1) {
    const turn = outs[(src.turn || 0) % outs.length]
    if (turn !== l && hasRoom(turn)) return false
    src.turn = (outs.indexOf(l) + 1) % outs.length
  }
  o[k] -= n
  return true
}
// How a belt is doing, for labels and panels.
export function linkState(l) {
  if (l.off) return 'off'
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
