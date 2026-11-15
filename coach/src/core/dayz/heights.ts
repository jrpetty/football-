// Heights for hill-aware routes and times: a 128 × 128 grid per map built from
// the heights of the buildings, trees and objects in Bohemia's Central Economy
// files (scripts/build-dayz-heights.mjs). Going uphill on foot is slower,
// gently downhill a little faster (Tobler's walking rule, scaled to jogging);
// a car loses speed up steep slopes.

import chernarus from '../../profiles/dayz/heights.chernarus.json' with { type: 'json' }
import livonia from '../../profiles/dayz/heights.livonia.json' with { type: 'json' }
import sakhal from '../../profiles/dayz/heights.sakhal.json' with { type: 'json' }
import { distance, type DayzMapId } from './places.ts'
import { tripShort, tripTime } from './travel.ts'
import { MIN_SLOPE_FACTOR, slopeFactor, type Travel } from './terrain.ts'

export interface HeightsData {
  readonly worldSize: number
  readonly size: number
  readonly b64: string
  readonly errorM?: { readonly median: number; readonly p90: number }
}

export interface Heights {
  readonly worldSize: number
  readonly size: number
  /** Metres above sea level, row by row from the north edge. */
  readonly h: Int16Array
}

const DATA: Readonly<Record<DayzMapId, HeightsData>> = { chernarus, livonia, sakhal }
const cache = new Map<DayzMapId, Heights>()

function bytes(b64: string): Uint8Array {
  if (typeof atob === 'function') {
    const s = atob(b64)
    const out = new Uint8Array(s.length)
    for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i)
    return out
  }
  return new Uint8Array(Buffer.from(b64, 'base64'))
}

export function decodeHeights(d: HeightsData): Heights {
  const b = bytes(d.b64)
  const h = new Int16Array(d.size * d.size)
  const view = new DataView(b.buffer, b.byteOffset, b.byteLength)
  for (let i = 0; i < h.length; i++) h[i] = view.getInt16(i * 2, true)
  return { worldSize: d.worldSize, size: d.size, h }
}

export function heightsFor(map: DayzMapId): Heights {
  let H = cache.get(map)
  if (!H) {
    H = decodeHeights(DATA[map])
    cache.set(map, H)
  }
  return H
}

/** How far the heights can be off (from the build's left-out points). */
export const heightError = (map: DayzMapId) => DATA[map].errorM ?? null

/** Height at a world point (x east, y north), between square centres. */
export function heightAt(H: Heights, x: number, y: number): number {
  const G = H.size
  const cell = H.worldSize / G
  const fx = Math.min(G - 1.001, Math.max(0, x / cell - 0.5))
  const fy = Math.min(G - 1.001, Math.max(0, (H.worldSize - y) / cell - 0.5))
  const x0 = Math.floor(fx)
  const y0 = Math.floor(fy)
  const tx = fx - x0
  const ty = fy - y0
  const h = H.h
  return h[y0 * G + x0] * (1 - tx) * (1 - ty) + h[y0 * G + x0 + 1] * tx * (1 - ty) + h[(y0 + 1) * G + x0] * (1 - tx) * ty + h[(y0 + 1) * G + x0 + 1] * tx * ty
}

export { MIN_SLOPE_FACTOR, slopeFactor }

export interface Climb {
  /** Metres climbed and descended along the way. */
  readonly upM: number
  readonly downM: number
  /** Time against flat ground (1 = flat; 1.3 = 30 % longer). */
  readonly factor: number
}

/** Climbing along a line of points, sampled every 60 m. */
export function climbAlong(H: Heights, points: readonly { x: number; y: number }[], travel: Travel): Climb {
  let up = 0
  let down = 0
  let len = 0
  let timed = 0
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1]
    const b = points[i]
    const d = Math.hypot(b.x - a.x, b.y - a.y)
    const steps = Math.max(1, Math.ceil(d / 60))
    let prev = heightAt(H, a.x, a.y)
    for (let k = 1; k <= steps; k++) {
      const t = k / steps
      const z = heightAt(H, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
      const dz = z - prev
      const run = d / steps
      if (dz > 0) up += dz
      else down -= dz
      len += run
      timed += run * slopeFactor(run ? dz / run : 0, travel)
      prev = z
    }
  }
  return { upM: Math.round(up), downM: Math.round(down), factor: len ? timed / len : 1 }
}

/** "uphill: about 150 m of climbing", for climbs worth mentioning. */
export function climbWords(c: { readonly upM: number; readonly downM: number }): string | null {
  if (c.upM < 60 && c.downM < 60) return null
  if (c.upM >= 60 && c.upM > c.downM * 1.5) return `mostly uphill, about ${Math.round(c.upM / 10) * 10} m of climbing`
  if (c.downM >= 60 && c.downM > c.upM * 1.5) return `mostly downhill, about ${Math.round(c.downM / 10) * 10} m down`
  return `hilly, about ${Math.round(c.upM / 10) * 10} m up and ${Math.round(c.downM / 10) * 10} m down`
}

/** Hill factors for a straight line between two points (on foot and by car). */
export function lineHills(map: DayzMapId, a: { x: number; y: number }, b: { x: number; y: number }): { foot: number; car: number; climb: Climb } {
  const H = heightsFor(map)
  const climb = climbAlong(H, [a, b], 'foot')
  return { foot: climb.factor, car: climbAlong(H, [a, b], 'car').factor, climb }
}

/** The time between two points with hills, and the climb when it is worth saying: "about 25 min jogging (21 with sprints), 9 min by car; mostly uphill, about 300 m of climbing". */
export function tripBetween(map: DayzMapId, a: { x: number; y: number }, b: { x: number; y: number }): string {
  const h = lineHills(map, a, b)
  const words = climbWords(h.climb)
  return `${tripTime(distance(a, b), h)}${words ? `; ${words}` : ''}`
}

/** The short time between two points with hills: "6 min on foot". */
export function tripShortBetween(map: DayzMapId, a: { x: number; y: number }, b: { x: number; y: number }): string {
  return tripShort(distance(a, b), lineHills(map, a, b))
}
