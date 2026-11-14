// The DayZ map view's arithmetic, kept out of the component so it can be
// tested: where the view looks (centre and span in world metres), zooming
// about a point, the SVG viewBox, which labels show at a zoom, reading a
// position the player types (a grid reference or a place), and what is at a
// clicked point.
//
// World coordinates: metres from the south-west corner, x east, y north.
// SVG coordinates: x east, y down from the north edge (svgY = worldSize - y).

import { findPlace, featureLine, gridRef, neighbours, region, km, type DayzMap, type DayzMapId, type DayzPlace } from './places.ts'

export interface MapViewState {
  /** Centre, world metres. */
  readonly cx: number
  readonly cy: number
  /** Metres shown from top to bottom of the view. */
  readonly span: number
}

export const MIN_SPAN = 250

export function fullView(map: DayzMap): MapViewState {
  return { cx: map.worldSize / 2, cy: map.worldSize / 2, span: map.worldSize }
}

/** Keep the view on the map and between the closest zoom and the whole map. */
export function clampView(map: DayzMap, v: MapViewState): MapViewState {
  const span = Math.min(map.worldSize * 1.1, Math.max(MIN_SPAN, v.span))
  const clamp = (c: number) => Math.min(map.worldSize, Math.max(0, c))
  return { cx: clamp(v.cx), cy: clamp(v.cy), span }
}

/** Zoom by a factor (>1 closer) keeping the world point under the cursor where it is. */
export function zoomAt(map: DayzMap, v: MapViewState, factor: number, at: { x: number; y: number } | null = null): MapViewState {
  const span = Math.min(map.worldSize * 1.1, Math.max(MIN_SPAN, v.span / factor))
  if (!at) return clampView(map, { ...v, span })
  const k = span / v.span
  return clampView(map, { cx: at.x + (v.cx - at.x) * k, cy: at.y + (v.cy - at.y) * k, span })
}

/** Centre on a point at a span (a town: about 2 km across). */
export function focusOn(map: DayzMap, at: { x: number; y: number }, span = 2000): MapViewState {
  return clampView(map, { cx: at.x, cy: at.y, span })
}

/** The SVG viewBox for a view in a box of this width/height ratio. */
export function viewBox(map: DayzMap, v: MapViewState, aspect: number): { x: number; y: number; w: number; h: number } {
  const h = v.span
  const w = v.span * aspect
  return { x: v.cx - w / 2, y: map.worldSize - v.cy - h / 2, w, h }
}

/** The world point under a pixel of the view's box. */
export function pointAt(map: DayzMap, v: MapViewState, box: { width: number; height: number }, px: number, py: number): { x: number; y: number } {
  const vb = viewBox(map, v, box.width / box.height)
  const sx = vb.x + (px / box.width) * vb.w
  const sy = vb.y + (py / box.height) * vb.h
  return { x: sx, y: map.worldSize - sy }
}

/** Places named at this zoom: cities, towns and airfields always, military bases once zoomed in a little, villages at 5 km, the rest at 1.8 km. */
export function labelled(place: DayzPlace, span: number, worldSize: number): boolean {
  switch (place.kind) {
    case 'city':
    case 'town':
    case 'airfield':
      return true
    case 'military':
      return span <= worldSize * 0.7
    case 'village':
      return span <= 5000
    default:
      return span <= 1800
  }
}

/**
 * A position the player types: a grid reference as players share it ("135
 * 012", "135012": 100 m squares; "13 01": 1 km squares), or a place name
 * (any spelling the coach understands). The centre of the square, or the place.
 */
export function parsePosition(map: DayzMap, text: string): { x: number; y: number; label: string } | null {
  const t = text.trim()
  const six = t.match(/^(\d{3})\s*[ ,/-]?\s*(\d{3})$/)
  const four = t.match(/^(\d{2})\s*[ ,/-]?\s*(\d{2})$/)
  const square = six ? { e: +six[1], s: +six[2], size: 100 } : four ? { e: +four[1], s: +four[2], size: 1000 } : null
  if (square) {
    const x = square.e * square.size + square.size / 2
    const y = map.worldSize - (square.s * square.size + square.size / 2)
    if (x < 0 || x > map.worldSize || y < 0 || y > map.worldSize) return null
    return { x, y, label: `grid ${gridRef(map, x, y)}` }
  }
  const p = findPlace(t, map.id)
  return p && p.map.id === map.id ? { x: p.place.x, y: p.place.y, label: p.place.name } : null
}

/** What is at a point: its grid, the region, the nearest town and water. */
export function pointInfo(map: DayzMap, at: { x: number; y: number }): { grid: string; line: string } {
  const near = neighbours(map, at, 1)[0]
  const water = featureLine(map, at, 'water')
  const parts = [`Grid ${gridRef(map, at.x, at.y)}, ${region(map, at.x, at.y)} of ${map.name}`]
  if (near) parts.push(near.m < 300 ? `in ${near.place.name}` : `${km(near.m)} ${opposite(near.dir)} of ${near.place.name}`)
  if (water) parts.push(water)
  return { grid: gridRef(map, at.x, at.y), line: `${parts.map((t) => t.charAt(0).toUpperCase() + t.slice(1)).join('. ')}.` }
}

const OPPOSITE: Readonly<Record<string, string>> = { north: 'south', south: 'north', east: 'west', west: 'east', 'north-east': 'south-west', 'south-west': 'north-east', 'north-west': 'south-east', 'south-east': 'north-west' }
/** neighbours() gives the direction from here to the town; the point is the other way from the town. */
const opposite = (dir: string) => OPPOSITE[dir] ?? dir

/** Grid lines every kilometre, labelled as players count them (eastings from the west, northings from the north). */
export function gridLines(map: DayzMap): { readonly at: number; readonly label: string }[] {
  const out: { at: number; label: string }[] = []
  for (let m = 0; m <= map.worldSize; m += 1000) out.push({ at: m, label: String(m / 100).padStart(3, '0') })
  return out
}

export function mapById(maps: readonly DayzMap[], id: DayzMapId): DayzMap {
  return maps.find((m) => m.id === id) ?? maps[0]
}
