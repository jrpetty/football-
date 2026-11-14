// Routes that follow the ground: the cheapest path across a terrain grid read
// from Bohemia's top-down map (scripts/build-dayz-terrain.py): road, open
// ground (fields, meadows, snow), forest, water. On foot a path takes roads
// where they help, crosses fields freely, goes through forest when that saves
// enough, and swims only when there is no other way; by car it keeps to roads
// and open ground and never crosses water. A* over a 1024 × 1024 grid, eight
// directions.

export const ROAD = 1
export const OPEN = 2
export const FOREST = 3
export const WATER = 4

export type Travel = 'foot' | 'car'

/** Time to cross a cell, relative to a road; Infinity: never. */
const COSTS: Readonly<Record<Travel, readonly number[]>> = {
  //          ?    road  open  forest water
  foot: [Infinity, 1, 1.25, 1.7, 14],
  car: [Infinity, 1, 3.5, Infinity, Infinity],
}

export interface TerrainData {
  readonly worldSize: number
  readonly size: number
  readonly rle: string
}

export interface Terrain {
  readonly worldSize: number
  readonly size: number
  /** Cell classes, row by row from the north edge. */
  readonly cells: Uint8Array
}

function fromBase64(b64: string): Uint8Array {
  if (typeof atob === 'function') {
    const s = atob(b64)
    const out = new Uint8Array(s.length)
    for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i)
    return out
  }
  return new Uint8Array(Buffer.from(b64, 'base64'))
}

export function decodeTerrain(d: TerrainData): Terrain {
  const runs = fromBase64(d.rle)
  const cells = new Uint8Array(d.size * d.size)
  let k = 0
  for (let i = 0; i + 1 < runs.length; i += 2) {
    cells.fill(runs[i], k, k + runs[i + 1])
    k += runs[i + 1]
  }
  if (k !== cells.length) throw new Error(`terrain: ${k} cells decoded, ${cells.length} expected`)
  return { worldSize: d.worldSize, size: d.size, cells }
}

/** Cells inside gas zones (1), for routes that keep out of them: on foot only as a last resort, by car never. */
export function gasMask(t: Terrain, zones: readonly { readonly x: number; readonly y: number; readonly r: number }[]): Uint8Array {
  const mask = new Uint8Array(t.size * t.size)
  const cell = t.worldSize / t.size
  for (const z of zones) {
    const r = Math.max(z.r, cell)
    const { cx, cy } = cellAt(t, z)
    const rc = Math.ceil(r / cell)
    for (let dy = -rc; dy <= rc; dy++)
      for (let dx = -rc; dx <= rc; dx++) {
        const x = cx + dx
        const y = cy + dy
        if (x < 0 || y < 0 || x >= t.size || y >= t.size || Math.hypot(dx, dy) * cell > r) continue
        mask[y * t.size + x] = 1
      }
  }
  return mask
}

/** The cell a world point falls in (x east, y north from the south-west corner). */
export function cellAt(t: Terrain, p: { x: number; y: number }): { cx: number; cy: number } {
  const k = t.size / t.worldSize
  return { cx: clamp(Math.floor(p.x * k), 0, t.size - 1), cy: clamp(Math.floor((t.worldSize - p.y) * k), 0, t.size - 1) }
}

export function classAt(t: Terrain, p: { x: number; y: number }): number {
  const { cx, cy } = cellAt(t, p)
  return t.cells[cy * t.size + cx]
}

const clamp = (v: number, a: number, b: number) => Math.max(a, Math.min(b, v))

export interface GroundPath {
  /** Simplified points, world metres, from the start to the end. */
  readonly points: readonly { readonly x: number; readonly y: number }[]
  /** Length along the path, metres. */
  readonly lengthM: number
  /** Shares of the length on each kind of ground. */
  readonly road: number
  readonly open: number
  readonly forest: number
  readonly water: number
}

/** The nearest cell that can be crossed (a point in the sea, or a car start in a forest): searched outwards. */
function nearestPassable(t: Terrain, cx: number, cy: number, cost: readonly number[]): number {
  const n = t.size
  if (Number.isFinite(cost[t.cells[cy * n + cx]])) return cy * n + cx
  for (let r = 1; r < 60; r++)
    for (let dy = -r; dy <= r; dy++)
      for (let dx = -r; dx <= r; dx++) {
        if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue
        const x = cx + dx
        const y = cy + dy
        if (x < 0 || y < 0 || x >= n || y >= n) continue
        if (Number.isFinite(cost[t.cells[y * n + x]])) return y * n + x
      }
  return -1
}

/**
 * The cheapest path between two points over the ground, or null when there is none (by car to an island).
 * Diagonal steps cost √2; each step costs the mean of the two cells it joins.
 */
export function groundPath(t: Terrain, a: { x: number; y: number }, b: { x: number; y: number }, travel: Travel = 'foot', gas: Uint8Array | null = null): GroundPath | null {
  const cost = COSTS[travel]
  // Gas zones: eight times the time on foot (only when there is no sensible way round), closed by car.
  const gasCost = travel === 'car' ? Infinity : 8
  const n = t.size
  const ca = cellAt(t, a)
  const cb = cellAt(t, b)
  const start = nearestPassable(t, ca.cx, ca.cy, cost)
  const goal = nearestPassable(t, cb.cx, cb.cy, cost)
  if (start < 0 || goal < 0) return null
  const gx = goal % n
  const gy = (goal / n) | 0
  const g = new Float32Array(n * n).fill(Infinity)
  const from = new Int32Array(n * n).fill(-1)
  const closed = new Uint8Array(n * n)
  const heap = new MinHeap()
  const minCost = Math.min(...cost.filter(Number.isFinite))
  const h = (i: number) => {
    const dx = Math.abs((i % n) - gx)
    const dy = Math.abs(((i / n) | 0) - gy)
    return minCost * (Math.max(dx, dy) + (Math.SQRT2 - 1) * Math.min(dx, dy))
  }
  g[start] = 0
  heap.push(start, h(start))
  const DX = [1, -1, 0, 0, 1, 1, -1, -1]
  const DY = [0, 0, 1, -1, 1, -1, 1, -1]
  while (heap.size) {
    const i = heap.pop()
    if (i === goal) break
    if (closed[i]) continue
    closed[i] = 1
    const x = i % n
    const y = (i / n) | 0
    const ci = cost[t.cells[i]]
    for (let d = 0; d < 8; d++) {
      const xx = x + DX[d]
      const yy = y + DY[d]
      if (xx < 0 || yy < 0 || xx >= n || yy >= n) continue
      const j = yy * n + xx
      if (closed[j]) continue
      const cj = cost[t.cells[j]] * (gas?.[j] ? gasCost : 1)
      if (!Number.isFinite(cj)) continue
      const step = (d < 4 ? 1 : Math.SQRT2) * (ci + cj) * 0.5
      const ng = g[i] + step
      if (ng < g[j]) {
        g[j] = ng
        from[j] = i
        heap.push(j, ng + h(j))
      }
    }
  }
  if (!Number.isFinite(g[goal])) return null
  // Back from the goal: the cells, then the ground they cross.
  const cells: number[] = []
  for (let i = goal; i !== -1; i = from[i]) cells.push(i)
  cells.reverse()
  const cell = t.worldSize / n
  const centre = (i: number) => ({ x: ((i % n) + 0.5) * cell, y: t.worldSize - (((i / n) | 0) + 0.5) * cell })
  const by = [0, 0, 0, 0, 0]
  let length = 0
  for (let k = 1; k < cells.length; k++) {
    const a1 = cells[k - 1]
    const b1 = cells[k]
    const diag = a1 % n !== b1 % n && ((a1 / n) | 0) !== ((b1 / n) | 0)
    const m = (diag ? Math.SQRT2 : 1) * cell
    length += m
    by[t.cells[b1]] += m
  }
  const raw = [a, ...cells.map(centre), b]
  const total = Math.max(1, length)
  return { points: simplify(raw, cell * 0.75), lengthM: length + Math.hypot(raw[1].x - a.x, raw[1].y - a.y) + Math.hypot(b.x - raw[raw.length - 2].x, b.y - raw[raw.length - 2].y), road: by[ROAD] / total, open: by[OPEN] / total, forest: by[FOREST] / total, water: by[WATER] / total }
}

/** Ramer–Douglas–Peucker: fewer points, the same line to within eps metres. */
export function simplify(pts: readonly { x: number; y: number }[], eps: number): { x: number; y: number }[] {
  if (pts.length < 3) return [...pts]
  const keep = new Uint8Array(pts.length)
  keep[0] = 1
  keep[pts.length - 1] = 1
  const stack: [number, number][] = [[0, pts.length - 1]]
  while (stack.length) {
    const [s, e] = stack.pop()!
    const a = pts[s]
    const b = pts[e]
    const dx = b.x - a.x
    const dy = b.y - a.y
    const len = Math.hypot(dx, dy) || 1
    let best = -1
    let far = eps
    for (let i = s + 1; i < e; i++) {
      const d = Math.abs(dy * (pts[i].x - a.x) - dx * (pts[i].y - a.y)) / len
      if (d > far) {
        far = d
        best = i
      }
    }
    if (best >= 0) {
      keep[best] = 1
      stack.push([s, best], [best, e])
    }
  }
  return pts.filter((_, i) => keep[i])
}

/** A route through several points, leg by leg; null legs where there is no way. */
export function groundRoute(t: Terrain, points: readonly { x: number; y: number }[], travel: Travel = 'foot', gas: Uint8Array | null = null): { legs: (GroundPath | null)[]; lengthM: number; road: number } {
  const legs: (GroundPath | null)[] = []
  for (let i = 1; i < points.length; i++) legs.push(groundPath(t, points[i - 1], points[i], travel, gas))
  const ok = legs.filter((l): l is GroundPath => !!l)
  const lengthM = ok.reduce((s, l) => s + l.lengthM, 0)
  const road = lengthM ? ok.reduce((s, l) => s + l.road * l.lengthM, 0) / lengthM : 0
  return { legs, lengthM, road }
}

/** A binary min-heap of cell indexes by priority. */
class MinHeap {
  private ids = new Int32Array(1024)
  private keys = new Float64Array(1024)
  size = 0

  push(id: number, key: number): void {
    if (this.size === this.ids.length) {
      const ids = new Int32Array(this.size * 2)
      ids.set(this.ids)
      const keys = new Float64Array(this.size * 2)
      keys.set(this.keys)
      this.ids = ids
      this.keys = keys
    }
    let i = this.size++
    while (i > 0) {
      const p = (i - 1) >> 1
      if (this.keys[p] <= key) break
      this.ids[i] = this.ids[p]
      this.keys[i] = this.keys[p]
      i = p
    }
    this.ids[i] = id
    this.keys[i] = key
  }

  pop(): number {
    const top = this.ids[0]
    const lastId = this.ids[--this.size]
    const lastKey = this.keys[this.size]
    let i = 0
    for (;;) {
      let c = 2 * i + 1
      if (c >= this.size) break
      if (c + 1 < this.size && this.keys[c + 1] < this.keys[c]) c++
      if (this.keys[c] >= lastKey) break
      this.ids[i] = this.ids[c]
      this.keys[i] = this.keys[c]
      i = c
    }
    this.ids[i] = lastId
    this.keys[i] = lastKey
    return top
  }
}
