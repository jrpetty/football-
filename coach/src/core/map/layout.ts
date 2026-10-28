// A map's real layout, from the game's own nav mesh (Counter-Strike 2: the
// walkable floor the game uses for bots), as polygons in the overview's
// pixels with heights. Used to draw the map from above in our own style and
// in 3D at its real heights, with no game art. Pure: the drawing itself is in
// the UI (src/ui/layouts.ts).

export interface MapLayout {
  readonly v: 1
  readonly map: string
  readonly name: string
  /** Where the geometry came from (shown with the map). */
  readonly source: string
  /** The overview's size in pixels (square). */
  readonly size: number
  /** World units per overview pixel. */
  readonly unitsPerPx: number
  /** Lowest and highest floor, in world units. */
  readonly z: readonly [number, number]
  /** Floors below this height are the lower level (Nuke). */
  readonly lowerBelowZ?: number
  readonly areas: number
  /** x, y in quarter pixels and z in world units, three numbers a vertex. */
  readonly verts: readonly number[]
  /** For each polygon: its corner count, then that many vertex indices. */
  readonly polys: readonly number[]
  /** Each polygon's level (0 upper, 1 lower), when the map has two. */
  readonly levels?: readonly number[]
}

export type LayoutLevel = 'upper' | 'lower'

export interface LayoutPolygon {
  /** Corners in overview pixels. */
  readonly pts: readonly (readonly [number, number])[]
  /** Corner heights (world units). */
  readonly zs: readonly number[]
  /** Mean height. */
  readonly z: number
  readonly level: LayoutLevel
}

export function isMapLayout(v: unknown): v is MapLayout {
  const o = v as MapLayout | null
  if (!o || o.v !== 1 || typeof o.map !== 'string' || typeof o.size !== 'number' || !Array.isArray(o.verts) || !Array.isArray(o.polys)) return false
  if (o.verts.length % 3 !== 0 || !Array.isArray(o.z) || o.z.length !== 2) return false
  const nv = o.verts.length / 3
  let i = 0
  let n = 0
  while (i < o.polys.length) {
    const k = o.polys[i]
    if (!Number.isInteger(k) || k < 3 || i + k >= o.polys.length) return false
    for (let j = 1; j <= k; j++) if (!(o.polys[i + j] >= 0 && o.polys[i + j] < nv)) return false
    i += k + 1
    n++
  }
  return !o.levels || o.levels.length === n
}

/** Every floor polygon, optionally of one level only. */
export function layoutPolygons(l: MapLayout, level?: LayoutLevel): LayoutPolygon[] {
  const out: LayoutPolygon[] = []
  let i = 0
  let n = 0
  while (i < l.polys.length) {
    const k = l.polys[i]
    const pts: [number, number][] = []
    const zs: number[] = []
    for (let j = 1; j <= k; j++) {
      const v = l.polys[i + j] * 3
      pts.push([l.verts[v] / 4, l.verts[v + 1] / 4])
      zs.push(l.verts[v + 2])
    }
    const lv: LayoutLevel = l.levels?.[n] === 1 ? 'lower' : 'upper'
    if (!level || level === lv) out.push({ pts, zs, z: zs.reduce((s, z) => s + z, 0) / zs.length, level: lv })
    i += k + 1
    n++
  }
  return out
}

/** Whether the map has a lower level drawn on its own (Nuke). */
export function hasLowerLevel(l: MapLayout): boolean {
  return !!l.levels?.some((x) => x === 1)
}

function inside(pts: readonly (readonly [number, number])[], x: number, y: number): boolean {
  let c = false
  for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
    const [xi, yi] = pts[i]
    const [xj, yj] = pts[j]
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) c = !c
  }
  return c
}

/** The floor heights under an overview pixel (all levels), highest first; empty off the floor. */
export function floorAt(polys: readonly LayoutPolygon[], x: number, y: number): number[] {
  return polys
    .filter((p) => inside(p.pts, x, y))
    .map((p) => p.z)
    .sort((a, b) => b - a)
}

/** The distance in overview pixels from a point to the nearest floor (0 on the floor). */
export function distanceToFloor(polys: readonly LayoutPolygon[], x: number, y: number): number {
  let best = Infinity
  for (const p of polys) {
    if (inside(p.pts, x, y)) return 0
    for (let i = 0, j = p.pts.length - 1; i < p.pts.length; j = i++) {
      const [ax, ay] = p.pts[j]
      const [bx, by] = p.pts[i]
      const dx = bx - ax
      const dy = by - ay
      const t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy || 1)))
      best = Math.min(best, Math.hypot(ax + t * dx - x, ay + t * dy - y))
    }
  }
  return best
}

/**
 * Floor heights on a grid (w × h cells over the overview): the highest floor of the level in each
 * cell, NaN where there is none. For the 3D view.
 */
export function heightGrid(l: MapLayout, w: number, h: number, level?: LayoutLevel): Float32Array {
  const out = new Float32Array(w * h).fill(Number.NaN)
  const sx = w / l.size
  const sy = h / l.size
  for (const p of layoutPolygons(l, level)) {
    let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity
    for (const [x, y] of p.pts) {
      x0 = Math.min(x0, x * sx)
      x1 = Math.max(x1, x * sx)
      y0 = Math.min(y0, y * sy)
      y1 = Math.max(y1, y * sy)
    }
    const scaled = p.pts.map(([x, y]) => [x * sx, y * sy] as const)
    for (let cy = Math.max(0, Math.floor(y0)); cy <= Math.min(h - 1, Math.ceil(y1)); cy++)
      for (let cx = Math.max(0, Math.floor(x0)); cx <= Math.min(w - 1, Math.ceil(x1)); cx++) {
        if (!inside(scaled, cx + 0.5, cy + 0.5)) continue
        const i = cy * w + cx
        if (!(out[i] >= p.z)) out[i] = p.z
      }
  }
  return out
}

/** Floor colour for a height share t (0 lowest, 1 highest): deep slate up to pale stone. */
export function floorColour(t: number): [number, number, number] {
  const c = Math.max(0, Math.min(1, t))
  const lo = [38, 52, 66]
  const mid = [74, 96, 112]
  const hi = [158, 170, 168]
  const [a, b, u] = c < 0.55 ? [lo, mid, c / 0.55] : [mid, hi, (c - 0.55) / 0.45]
  return [0, 1, 2].map((k) => Math.round(a[k] + (b[k] - a[k]) * u)) as [number, number, number]
}
