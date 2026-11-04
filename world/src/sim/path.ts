// Grid A* with 8-way movement, terrain costs, roads and (optionally) boats.
// Scratch buffers are reused between calls so pathfinding is allocation-free.

export interface PathGrid {
  W: number
  H: number
  /** movement cost of entering a tile; Infinity = blocked. */
  cost(i: number, sail: number): number
}

let cap = 0
let g: Float32Array
let came: Int32Array
let stamp: Uint32Array
let closed: Uint32Array
let curStamp = 1
let heapIdx: Int32Array
let heapF: Float32Array
let heapSize = 0

function ensure(n: number) {
  if (cap >= n) return
  cap = n
  g = new Float32Array(n)
  came = new Int32Array(n)
  stamp = new Uint32Array(n)
  closed = new Uint32Array(n)
  heapIdx = new Int32Array(n * 2)
  heapF = new Float32Array(n * 2)
}

function push(i: number, f: number) {
  let k = heapSize++
  heapIdx[k] = i
  heapF[k] = f
  while (k > 0) {
    const p = (k - 1) >> 1
    if (heapF[p] <= heapF[k]) break
    const ti = heapIdx[p], tf = heapF[p]
    heapIdx[p] = heapIdx[k]; heapF[p] = heapF[k]
    heapIdx[k] = ti; heapF[k] = tf
    k = p
  }
}
function pop(): number {
  const top = heapIdx[0]
  heapSize--
  if (heapSize > 0) {
    heapIdx[0] = heapIdx[heapSize]
    heapF[0] = heapF[heapSize]
    let k = 0
    for (;;) {
      const l = k * 2 + 1, r = l + 1
      let m = k
      if (l < heapSize && heapF[l] < heapF[m]) m = l
      if (r < heapSize && heapF[r] < heapF[m]) m = r
      if (m === k) break
      const ti = heapIdx[m], tf = heapF[m]
      heapIdx[m] = heapIdx[k]; heapF[m] = heapF[k]
      heapIdx[k] = ti; heapF[k] = tf
      k = m
    }
  }
  return top
}

const DX = [1, -1, 0, 0, 1, -1, 1, -1]
const DY = [0, 0, 1, -1, 1, -1, -1, 1]
const DC = [1, 1, 1, 1, 1.414, 1.414, 1.414, 1.414]

/**
 * Find a path from (sx,sy) to (tx,ty). With `adjacent`, any tile within one
 * step of the target counts as arrival (for targets that block movement).
 * Returns tile indices from the first step to the goal, or null.
 */
export function findPath(
  grid: PathGrid,
  sx: number, sy: number, tx: number, ty: number,
  adjacent: boolean, canSail: number, maxNodes = 24000,
): Int32Array | null {
  const { W, H } = grid
  const N = W * H
  ensure(N)
  curStamp++
  if (curStamp > 0xfffffff0) { stamp.fill(0); closed.fill(0); curStamp = 1 }
  heapSize = 0
  const start = sy * W + sx
  const goal = ty * W + tx
  const isGoal = (i: number) => {
    if (i === goal) return true
    if (!adjacent) return false
    const x = i % W, y = (i / W) | 0
    return Math.abs(x - tx) <= 1 && Math.abs(y - ty) <= 1
  }
  if (isGoal(start)) return new Int32Array(0)
  g[start] = 0
  stamp[start] = curStamp
  came[start] = -1
  push(start, heur(sx, sy, tx, ty))
  let expanded = 0
  while (heapSize > 0) {
    const cur = pop()
    if (closed[cur] === curStamp) continue
    closed[cur] = curStamp
    if (isGoal(cur)) return reconstruct(cur, start)
    if (++expanded > maxNodes) return null
    const cx = cur % W, cy = (cur / W) | 0
    for (let d = 0; d < 8; d++) {
      const nx = cx + DX[d], ny = cy + DY[d]
      if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue
      const ni = ny * W + nx
      if (closed[ni] === curStamp) continue
      let c = grid.cost(ni, canSail)
      if (c === Infinity) {
        // allow stepping onto a blocked goal only when not using adjacency
        if (!(ni === goal && !adjacent)) continue
        c = 1
      }
      if (d >= 4) {
        // no corner cutting through blocked tiles
        if (grid.cost(cy * W + nx, canSail) === Infinity || grid.cost(ny * W + cx, canSail) === Infinity) continue
      }
      const ng = g[cur] + c * DC[d]
      if (stamp[ni] !== curStamp || ng < g[ni]) {
        stamp[ni] = curStamp
        g[ni] = ng
        came[ni] = cur
        push(ni, ng + heur(nx, ny, tx, ty))
      }
    }
  }
  return null
}

function heur(x: number, y: number, tx: number, ty: number) {
  const dx = Math.abs(x - tx), dy = Math.abs(y - ty)
  // octile distance × cheapest terrain (roads) to stay admissible-ish while fast
  return (Math.max(dx, dy) + 0.414 * Math.min(dx, dy)) * 0.9
}

function reconstruct(end: number, start: number): Int32Array {
  const out: number[] = []
  let c = end
  while (c !== start && c >= 0) {
    out.push(c)
    c = came[c]
  }
  out.reverse()
  return Int32Array.from(out)
}
