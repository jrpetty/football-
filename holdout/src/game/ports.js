// Intake and outtake pods. Every building that makes or uses goods has fixed
// ports a belt plugs into: intakes down its left side, outtakes down its
// right (the front, where people work, stays clear). A Storage Depot has
// two-way hatches on every side, and a splitter or merger one on each of its
// four sides. One belt per pod.
//
// A pod is the tile just outside the wall, the direction leading away from
// the building, and the point on the wall where goods go in or come out.
import { STATIONS, RECIPES, ALT_RECIPES, SIGNAL } from './data.js'
import { S, stationSize } from './state.js'

// Anything with `depot` (the Storage Depot, crate stacks, sheds, the
// warehouse) holds the camp's one shared store and swaps goods both ways.
export const isDepot = (st) => !!(st && STATIONS[st.type]?.depot)
export const nodeKind = (st) => (st ? STATIONS[st.type]?.node || null : null) // 'split' | 'prio' | 'merge' | 'hopper'
export const isNode = (st) => !!nodeKind(st)
// How many belts may run into and out of each kind of node.
export const NODE_RULES = {
  split: { ins: 1, outs: 3 },
  prio: { ins: 1, outs: 3 },
  merge: { ins: 3, outs: 1 },
  hopper: { ins: 1, outs: 1 },
}
// Splitters of either kind hand goods out over several belts.
export const splits = (st) => {
  const k = nodeKind(st)
  return k === 'split' || k === 'prio'
}

// ---------------------------------------------------------------- what goes in and out
function recipesOf(st) {
  const D = STATIONS[st.type]
  const out = []
  if (D.recipe) out.push(D.recipe)
  if (D.recipes) out.push(...Object.values(D.recipes))
  for (const a of Object.values(st.alts || {})) if (ALT_RECIPES[a]) out.push(ALT_RECIPES[a].recipe)
  // crafting benches: their resource recipes (gear and tools leave by hand)
  if (D.queue) for (const r of RECIPES) if (r.station === st.type && (r.lvl || 1) <= Math.max(1, st.level)) out.push(r)
  return out
}
export { recipesOf }
const SIGNAL_INPUTS = [...new Set(SIGNAL.flatMap((p) => Object.keys(p.cost)))]
// Resources a station can take off a belt, in recipe order.
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
// The most different inputs (outputs) any one recipe uses at once.
function widest(st, key) {
  let m = 0
  for (const r of recipesOf(st)) m = Math.max(m, Object.keys(r[key] || {}).filter((k) => r[key][k] > 0).length)
  return m
}

// ---------------------------------------------------------------- where the pods are
// World directions: 0 east (+x), 1 south (+z), 2 west (-x), 3 north (-z).
// A building turned (rot 1) has its left side to the south, its right to
// the north and its front to the east.
const SIDE = [
  { left: 2, right: 0, back: 3, front: 1 },
  { left: 1, right: 3, back: 2, front: 0 },
]
// The tiles along one side of a footprint, in order of the world axis.
function sideTiles(st, dir) {
  const [w, d] = stationSize(st)
  const out = []
  if (dir === 0 || dir === 2) for (let z = st.z; z < st.z + d; z++) out.push(dir === 0 ? { x: st.x + w, z, ex: st.x + w, ez: z + 0.5 } : { x: st.x - 1, z, ex: st.x, ez: z + 0.5 })
  else for (let x = st.x; x < st.x + w; x++) out.push(dir === 1 ? { x, z: st.z + d, ex: x + 0.5, ez: st.z + d } : { x, z: st.z - 1, ex: x + 0.5, ez: st.z })
  return out
}
// n pods spread evenly along a side
function spread(tiles, n) {
  const out = []
  const L = tiles.length
  for (let k = 0; k < n; k++) out.push(tiles[Math.min(L - 1, Math.floor(((k + 0.5) * L) / n))])
  return [...new Set(out)]
}
// How many pods of each kind a building gets.
export function podCounts(st) {
  if (isNode(st)) return { io: 4 }
  if (isDepot(st)) return { io: 6 }
  const ins = inputsOf(st).length
  const outs = outputsOf(st).length
  const [w, d] = STATIONS[st.type].size
  // left and right sides run along the depth
  return {
    in: ins ? Math.min(d, ins, Math.max(widest(st, 'in'), Math.min(ins, 4)), 4) : 0,
    out: outs ? Math.min(d, outs, 2) : 0,
  }
}
// All of a building's pods: { i, kind: 'in' | 'out' | 'io', dir, x, z (the
// tile outside), ex, ez (the point on the wall) }. Cached per footprint.
const cache = new Map()
export function portsOf(st) {
  if (!st) return []
  const key = `${st.id}|${st.type}|${st.x},${st.z}|${st.rot ? 1 : 0}|${st.level}|${Object.values(st.alts || {}).join(',')}`
  const hit = cache.get(st.id)
  if (hit && hit.key === key) return hit.ports
  const n = podCounts(st)
  const S1 = SIDE[st.rot ? 1 : 0]
  const out = []
  const add = (t, kind, dir) => out.push({ i: out.length, kind, dir, x: t.x, z: t.z, ex: t.ex, ez: t.ez })
  if (n.io) {
    if (isNode(st)) for (const dir of [0, 1, 2, 3]) add(sideTiles(st, dir)[0], 'io', dir)
    else
      for (const dir of [3, 0, 1, 2]) {
        const tiles = sideTiles(st, dir)
        for (const t of spread(tiles, tiles.length >= 5 ? 2 : 1)) add(t, 'io', dir)
      }
  } else {
    if (n.in) for (const t of spread(sideTiles(st, S1.left), n.in)) add(t, 'in', S1.left)
    if (n.out) for (const t of spread(sideTiles(st, S1.right), n.out)) add(t, 'out', S1.right)
  }
  cache.set(st.id, { key, ports: out })
  return out
}
export const portAt = (st, i) => portsOf(st)[i] || null
// Can goods leave (enter) through this pod?
export const podSends = (p) => p && (p.kind === 'out' || p.kind === 'io')
export const podTakes = (p) => p && (p.kind === 'in' || p.kind === 'io')
// The belt plugged into a pod, if any.
export function linkAt(st, i, ignore = null) {
  for (const l of S.links || []) {
    if (l === ignore) continue
    if ((l.from === st.id && l.fromPort === i) || (l.to === st.id && l.toPort === i)) return l
  }
  return null
}
// Free pods for a belt leaving (dir 'out') or entering ('in') a building.
export function freePods(st, dir, ignore = null) {
  return portsOf(st).filter((p) => (dir === 'out' ? podSends(p) : podTakes(p)) && !linkAt(st, p.i, ignore))
}
