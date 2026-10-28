// A map's real layout (the pack's geometry from the game's nav mesh) drawn
// from above in the app's own style: walls traced round the walkable floor,
// floors shaded by height, the other level faint underneath (Nuke). The
// drawing stands in for an overhead map the player has not imported: the map
// views, lineups and the 3D model use it, analysed like an imported picture.
// Loaded per map on demand.

import { floorColour, hasLowerLevel, heightGrid, isMapLayout, layoutPolygons, type LayoutLevel, type MapLayout } from '../core/map/layout.ts'
import { analyzeOverhead, type DecodedImage, type OverheadAnalysis } from './overhead.ts'

const FILES = import.meta.glob('../profiles/layouts/*/*.json', { import: 'default' }) as Record<string, () => Promise<unknown>>
const path = (pack: string, map: string) => `../profiles/layouts/${pack}/${map}.json`

/** Whether the pack ships the real layout of this map. */
export function hasLayout(pack: string, map: string | null | undefined): boolean {
  return !!map && path(pack, map) in FILES
}

const layouts = new Map<string, Promise<MapLayout | null>>()

export function loadLayout(pack: string, map: string): Promise<MapLayout | null> {
  const k = path(pack, map)
  let p = layouts.get(k)
  if (!p) {
    const load = FILES[k]
    p = load ? load().then((v) => (isMapLayout(v) ? v : null)).catch(() => null) : Promise.resolve(null)
    layouts.set(k, p)
  }
  return p
}

const WALL = '#c9d4e2'
const OTHER_LEVEL = 'rgba(120, 140, 160, 0.16)'

/** The layout drawn from above on a canvas `side` pixels wide (transparent off the floor). */
export function drawLayout(l: MapLayout, level: LayoutLevel = 'upper', side = l.size): HTMLCanvasElement {
  const c = document.createElement('canvas')
  c.width = c.height = side
  const ctx = c.getContext('2d')!
  const f = side / l.size
  const two = hasLowerLevel(l)
  const mine = layoutPolygons(l, two ? level : undefined)
  const path2d = (pts: readonly (readonly [number, number])[]) => {
    const p = new Path2D()
    pts.forEach(([x, y], i) => (i ? p.lineTo(x * f, y * f) : p.moveTo(x * f, y * f)))
    p.closePath()
    return p
  }
  ctx.lineJoin = 'round'
  // The other level, faint, so stairs and drops read in context.
  if (two) {
    ctx.fillStyle = OTHER_LEVEL
    for (const p of layoutPolygons(l, level === 'upper' ? 'lower' : 'upper')) ctx.fill(path2d(p.pts))
  }
  const paths = mine.map((p) => ({ p, d: path2d(p.pts) }))
  // Walls: every floor stroked wide, then the floors filled over it, so only the outer edge
  // (and the edge of every box and pillar) is left showing.
  ctx.strokeStyle = WALL
  ctx.lineWidth = 3.2 * f
  for (const { d } of paths) ctx.stroke(d)
  const [lo, hi] = l.z
  const span = Math.max(1, hi - lo)
  // Lowest first, so a higher floor covers the edge of a lower one.
  for (const { p, d } of [...paths].sort((a, b) => a.p.z - b.p.z)) {
    const [r, g, b] = floorColour((p.z - lo) / span)
    ctx.fillStyle = `rgb(${r}, ${g}, ${b})`
    ctx.strokeStyle = ctx.fillStyle
    ctx.lineWidth = 1.1 * f
    ctx.fill(d)
    ctx.stroke(d)
  }
  // Steps: an edge between floors a stair or more apart, drawn as a thin dark line.
  ctx.strokeStyle = 'rgba(8, 12, 18, 0.35)'
  ctx.lineWidth = 0.9 * f
  for (const { p, d } of paths) if (Math.max(...p.zs) - Math.min(...p.zs) > 24) ctx.stroke(d)
  return c
}

export interface LayoutPicture {
  readonly url: string
  readonly analysis: OverheadAnalysis
  readonly layout: MapLayout
  readonly level: LayoutLevel
  /** The drawing at the overview's size, as analysed (the demo draws its radar from it). */
  readonly image: DecodedImage
}

const pictures = new Map<string, Promise<LayoutPicture | null>>()

/** The layout as a picture for the map views (drawn at twice the overview's size for zooming), with its analysis. */
export function layoutPicture(pack: string, map: string, level: LayoutLevel = 'upper'): Promise<LayoutPicture | null> {
  const k = `${pack}/${map}/${level}`
  let p = pictures.get(k)
  if (!p) {
    p = (async () => {
      const l = await loadLayout(pack, map)
      if (!l) return null
      const big = drawLayout(l, level, l.size * 2)
      const blob = await new Promise<Blob | null>((resolve) => big.toBlob(resolve, 'image/png'))
      if (!blob) return null
      const small = document.createElement('canvas')
      small.width = small.height = l.size
      const sctx = small.getContext('2d', { willReadFrequently: true })!
      sctx.drawImage(big, 0, 0, l.size, l.size)
      const image: DecodedImage = { data: sctx.getImageData(0, 0, l.size, l.size).data, width: l.size, height: l.size }
      const analysis = await analyzeOverhead(image)
      return { url: URL.createObjectURL(blob), analysis, layout: l, level, image }
    })().catch((err: unknown) => {
      console.error(`Could not draw the layout of ${map}:`, err)
      pictures.delete(k)
      return null
    })
    pictures.set(k, p)
  }
  return p
}

/**
 * Heights for the 3D model on the views' working grid (w × h cells, k working pixels per overview
 * pixel): floors at their real heights (lifted a little for legibility), a wall standing round
 * every floor, and NaN (cut away) elsewhere.
 */
export function layoutTerrain(l: MapLayout, w: number, h: number, level: LayoutLevel = 'upper'): Float32Array {
  const grid = heightGrid(l, w, h, hasLowerLevel(l) ? level : undefined)
  const k = w / l.size
  const lift = 1.35
  const px = (z: number) => ((z - l.z[0]) / l.unitsPerPx) * k * lift
  const wall = (128 / l.unitsPerPx) * k
  const out = new Float32Array(w * h).fill(Number.NaN)
  for (let i = 0; i < w * h; i++) if (!Number.isNaN(grid[i])) out[i] = px(grid[i])
  // Walls: off-floor cells next to a floor stand above the highest floor beside them.
  const r = Math.max(1, Math.round(k * 2))
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const i = y * w + x
      if (!Number.isNaN(grid[i])) continue
      let top = -Infinity
      for (let yy = Math.max(0, y - r); yy <= Math.min(h - 1, y + r); yy++)
        for (let xx = Math.max(0, x - r); xx <= Math.min(w - 1, x + r); xx++) {
          const v = grid[yy * w + xx]
          if (!Number.isNaN(v) && v > top) top = v
        }
      if (top > -Infinity) out[i] = px(top) + wall
    }
  return out
}
