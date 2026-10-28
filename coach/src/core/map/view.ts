// What the map views draw, from a stitched map and the matches played on it:
// the largest piece as an image, the pins (deaths, kills, killers), the paths
// life by life, enemy pings and deaths as heat, callouts where they were
// read, and a height for every pixel for the 3D view (from the minimap's own
// light and dark shapes, raised where the player was seen upstairs or on a
// roof). Points recorded on pieces that were joined later are moved onto the
// piece they joined.
//
// With the map's official overhead map, everything is drawn on it instead:
// each point goes through its piece's placement on the official map (found
// and checked while playing, or afterwards); points on pieces not placed are
// left out and counted, never guessed.

import { CELL, fromBase64, type StoredMosaic } from './minimap.ts'
import type { MapPin, MatchPins } from './locator.ts'
import { KIND_INDOOR, KIND_OUT, KIND_SOLID, KIND_WALL, toOfficial, usable, type Alignment } from './official.ts'

export interface MapImage {
  readonly piece: number
  readonly x0: number
  readonly y0: number
  readonly w: number
  readonly h: number
  /** Luminance 0..255. */
  readonly lum: Uint8Array
  /** 0 where nothing was seen. */
  readonly weight: Uint8Array
}

const decoded = new WeakMap<StoredMosaic, MapImage | null>()

/** The largest piece of a stitched map, decoded (once per stored map). */
export function mainImage(m: StoredMosaic): MapImage | null {
  if (decoded.has(m)) return decoded.get(m)!
  const p = [...m.pieces].sort((a, b) => b.w * b.h - a.w * a.h)[0]
  const img = p ? { piece: p.id, x0: p.x0, y0: p.y0, w: p.w, h: p.h, lum: fromBase64(p.mean), weight: fromBase64(p.weight) } : null
  decoded.set(m, img)
  return img
}

/** Where a point recorded on a piece lies now (pieces joined since are followed). */
export function resolvePoint(m: StoredMosaic, piece: number, x: number, y: number): { piece: number; x: number; y: number } {
  let p = piece
  let X = x
  let Y = y
  for (let guard = 0; guard < 64; guard++) {
    const j = m.joined[String(p)]
    if (!j) break
    p = j[0]
    X += j[1]
    Y += j[2]
  }
  return { piece: p, x: X, y: Y }
}

export interface PlacedPin extends MapPin {
  /** Which of the given matches (0 = the first given). */
  readonly match: number
  /** Image coordinates (0..w, 0..h). */
  readonly px: number
  readonly py: number
  readonly killerPx?: { readonly x: number; readonly y: number } | null
}

/** An official overhead map, as the views need it (see official.worker.ts). */
export interface OfficialBase {
  readonly id: string
  readonly srcW: number
  readonly srcH: number
  /** Working size, and working pixels per image pixel: the layers' coordinates. */
  readonly w: number
  readonly h: number
  readonly k: number
  readonly kind: Uint8Array
}

export interface MapLayers {
  /** The stitched piece drawn; with an official map, a stand-in of its size (in bounds = seen). */
  readonly image: MapImage
  /** Drawn on the official overhead map (coordinates are its working pixels). */
  readonly official?: { readonly base: OfficialBase; readonly placed: number; readonly unplaced: number } | null
  readonly pins: readonly PlacedPin[]
  /** Paths in image coordinates: one per life per match, with times (s). */
  readonly paths: readonly { readonly match: number; readonly life: number; readonly points: readonly (readonly [number, number, number])[] }[]
  /** Enemy pings per cell, in image coordinates (cell centre) with counts. */
  readonly enemies: readonly { readonly x: number; readonly y: number; readonly n: number }[]
  /** Callouts where they were read, in image coordinates. */
  readonly callouts: readonly { readonly name: string; readonly x: number; readonly y: number; readonly n: number }[]
  /** Heights seen per cell (image coordinates): -1 underground, 0 ground, 1 upper, 2 roof (the most seen). */
  readonly heights: readonly { readonly x: number; readonly y: number; readonly level: number }[]
  /** How many matches had something on this piece. */
  readonly matches: number
}

/**
 * A piece's placement on an official map, from a stored stitched map: its own, or one of a piece
 * joined into it. None when the placements were made on another picture.
 */
export function storedAlignment(m: StoredMosaic, piece: number, officialId: string): Alignment | null {
  if (m.alignedTo?.id !== officialId) return null
  const own = m.aligned?.[String(piece)]
  if (usable(own)) return own
  for (const [id, a] of Object.entries(m.aligned ?? {})) {
    if (!usable(a)) continue
    const r = resolvePoint(m, Number(id), 0, 0)
    if (r.piece === piece && Number(id) !== piece) return { ...a, tx: a.tx - a.s * r.x, ty: a.ty - a.s * r.y }
  }
  return null
}

/** Where a stitched point lies on the official map (its working pixels), if its piece is placed. */
export function officialPoint(m: StoredMosaic, base: OfficialBase, piece: number, x: number, y: number): { x: number; y: number } | null {
  const r = resolvePoint(m, piece, x, y)
  const a = storedAlignment(m, r.piece, base.id)
  if (!a) return null
  const o = toOfficial(a, r.x, r.y)
  return { x: o.x * base.k, y: o.y * base.k }
}

/** Everything to draw for a stitched map and some matches on it (oldest first); on the official map when given. */
export function mapLayers(m: StoredMosaic, matches: readonly MatchPins[], base?: OfficialBase | null): MapLayers | null {
  const stitched = mainImage(m)
  const image = base ? officialImage(base) : stitched
  if (!image) return null
  let placed = 0
  let unplaced = 0
  // `count`: a point of the player's (pins and paths), counted as placed or not.
  const on = (piece: number, x: number, y: number, count = false) => {
    if (base) {
      const at = officialPoint(m, base, piece, x, y)
      if (count && at) placed++
      else if (count) unplaced++
      return at
    }
    const r = resolvePoint(m, piece, x, y)
    return r.piece === image.piece ? { x: r.x - image.x0, y: r.y - image.y0 } : null
  }
  const pins: PlacedPin[] = []
  const paths: { match: number; life: number; points: [number, number, number][] }[] = []
  const enemyCells = new Map<string, { x: number; y: number; n: number }>()
  let used = 0
  matches.forEach((mp, i) => {
    let any = false
    for (const p of mp.pins) {
      const at = on(p.piece, p.x, p.y, true)
      if (!at) continue
      any = true
      const k = p.killer ? on(p.killer.piece, p.killer.x, p.killer.y) : null
      pins.push({ ...p, match: i, px: at.x, py: at.y, killerPx: k })
    }
    let cur: { match: number; life: number; points: [number, number, number][] } | null = null
    for (const [piece, x, y, t, life] of mp.trail) {
      const at = on(piece, x, y, true)
      if (!at) {
        cur = null
        continue
      }
      any = true
      if (!cur || cur.life !== life) paths.push((cur = { match: i, life, points: [] }))
      cur.points.push([at.x, at.y, t])
    }
    for (const [k, n] of Object.entries(mp.enemies)) {
      const [piece, cx, cy] = k.split(':').map(Number)
      const at = on(piece, cx * CELL + CELL / 2, cy * CELL + CELL / 2)
      if (!at) continue
      any = true
      const key = `${Math.round(at.x)}:${Math.round(at.y)}`
      const e = enemyCells.get(key) ?? { x: at.x, y: at.y, n: 0 }
      e.n += n
      enemyCells.set(key, e)
    }
    if (any) used++
  })
  const callouts = Object.entries(m.callouts).flatMap(([name, c]) => {
    const at = on(c.piece, c.x, c.y)
    return at ? [{ name, ...at, n: c.n }] : []
  })
  const heights = Object.entries(m.heights).flatMap(([k, v]) => {
    const [piece, cx, cy] = k.split(':').map(Number)
    const at = on(piece, cx * CELL + CELL / 2, cy * CELL + CELL / 2)
    if (!at) return []
    const best = v.indexOf(Math.max(...v))
    return [{ ...at, level: best - 1 }]
  })
  return { image, pins, paths, enemies: [...enemyCells.values()], callouts, heights, matches: used, official: base ? { base, placed, unplaced } : null }
}

const officialImages = new WeakMap<OfficialBase, MapImage>()

/** A stand-in image of the official map's size: in bounds counts as seen. */
function officialImage(base: OfficialBase): MapImage {
  const hit = officialImages.get(base)
  if (hit) return hit
  const n = base.w * base.h
  const lum = new Uint8Array(n)
  const weight = new Uint8Array(n)
  for (let i = 0; i < n; i++) {
    const k = base.kind[i]
    weight[i] = k === KIND_OUT ? 0 : 1
    lum[i] = k === KIND_WALL ? 220 : k === KIND_SOLID ? 170 : k === KIND_INDOOR ? 90 : 40
  }
  const img = { piece: -1, x0: 0, y0: 0, w: base.w, h: base.h, lum, weight }
  officialImages.set(base, img)
  return img
}

/**
 * Heights from the official map's drawing (for the 3D view), 0..1: walls stand up, solid
 * structures stand taller, indoor floors sit a little above the open ground, and out of bounds is
 * sunk. Where the player was seen upstairs or on a roof, the floor is raised as on the stitched map.
 */
export function officialHeights(layers: MapLayers): Float32Array {
  const base = layers.official!.base
  const { w, h, kind } = base
  const out = new Float32Array(w * h)
  for (let i = 0; i < w * h; i++) {
    const k = kind[i]
    out[i] = k === KIND_OUT ? -0.12 : k === KIND_WALL ? 0.55 : k === KIND_SOLID ? 0.85 : k === KIND_INDOOR ? 0.04 : 0
  }
  const r = Math.max(4, Math.round(CELL * 1.2))
  for (const c of layers.heights) {
    if (!c.level) continue
    const lift = c.level === 2 ? 0.8 : c.level === 1 ? 0.4 : -0.1
    for (let dy = -r; dy <= r; dy++)
      for (let dx = -r; dx <= r; dx++) {
        const x = Math.round(c.x + dx)
        const y = Math.round(c.y + dy)
        if (x < 0 || y < 0 || x >= w || y >= h) continue
        const i = y * w + x
        if (kind[i] === KIND_OUT || kind[i] === KIND_WALL) continue
        out[i] = Math.max(out[i], lift * (1 - Math.hypot(dx, dy) / (r * 1.4)))
      }
  }
  return out
}

/**
 * A height for every pixel, 0..1 (for the 3D view): the minimap's lighter
 * shapes (buildings, walls, cover, on most tactical maps) stand up, split
 * from the ground by Otsu's threshold, smoothed a little; cells where the
 * player was seen on an upper floor or a roof are raised, underground ones
 * sunk. Unseen pixels are -1.
 */
export function heightField(layers: MapLayers): Float32Array {
  const { image, heights } = layers
  const { w, h, lum, weight } = image
  // Otsu's threshold over the seen pixels.
  const hist = new Float64Array(256)
  let total = 0
  for (let i = 0; i < w * h; i++)
    if (weight[i]) {
      hist[lum[i]]++
      total++
    }
  let sum = 0
  for (let v = 0; v < 256; v++) sum += v * hist[v]
  let sumB = 0
  let wB = 0
  let best = 0
  let thr = 128
  for (let v = 0; v < 256; v++) {
    wB += hist[v]
    if (!wB) continue
    const wF = total - wB
    if (!wF) break
    sumB += v * hist[v]
    const mB = sumB / wB
    const mF = (sum - sumB) / wF
    const between = wB * wF * (mB - mF) * (mB - mF)
    if (between > best) {
      best = between
      // Halfway between the two classes' means: the middle of the gap between ground and structures.
      thr = (mB + mF) / 2
    }
  }
  const raw = new Float32Array(w * h)
  for (let i = 0; i < w * h; i++) raw[i] = weight[i] ? Math.max(0, Math.min(1, (lum[i] - thr) / 24 + 0.5)) : -1
  // Smooth 3×3 over seen pixels.
  const out = new Float32Array(w * h)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x
      if (raw[i] < 0) {
        out[i] = -1
        continue
      }
      let s = 0
      let n = 0
      for (let dy = -1; dy <= 1; dy++)
        for (let dx = -1; dx <= 1; dx++) {
          const xx = x + dx
          const yy = y + dy
          if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
          const v = raw[yy * w + xx]
          if (v < 0) continue
          s += v
          n++
        }
      out[i] = s / n
    }
  }
  for (const c of heights) {
    if (!c.level) continue
    const boost = c.level === 2 ? 0.6 : c.level === 1 ? 0.35 : -0.3
    for (let dy = -CELL; dy <= CELL; dy++)
      for (let dx = -CELL; dx <= CELL; dx++) {
        const x = Math.round(c.x + dx)
        const y = Math.round(c.y + dy)
        if (x < 0 || y < 0 || x >= w || y >= h) continue
        const i = y * w + x
        if (out[i] >= 0) out[i] = Math.max(-0.3, Math.min(1.6, out[i] + boost * (1 - Math.hypot(dx, dy) / (CELL * 1.5))))
      }
  }
  return out
}

/** Deaths close together, for labels ("3 deaths here"): clusters within `r` px, most first. */
export function deathClusters(pins: readonly PlacedPin[], r = 10): { x: number; y: number; n: number }[] {
  const out: { x: number; y: number; n: number }[] = []
  for (const p of pins) {
    if (p.kind !== 'death') continue
    const c = out.find((q) => Math.hypot(q.x - p.px, q.y - p.py) <= r)
    if (c) {
      c.x = (c.x * c.n + p.px) / (c.n + 1)
      c.y = (c.y * c.n + p.py) / (c.n + 1)
      c.n++
    } else out.push({ x: p.px, y: p.py, n: 1 })
  }
  return out.sort((a, b) => b.n - a.n)
}

const WIND = ['north', 'north-east', 'east', 'south-east', 'south', 'south-west', 'west', 'north-west'] as const

/** The compass direction from one point to another (the minimap is north-up). */
export function bearing(from: { readonly x: number; readonly y: number }, to: { readonly x: number; readonly y: number }): (typeof WIND)[number] {
  const a = Math.atan2(to.x - from.x, -(to.y - from.y))
  return WIND[(Math.round(a / (Math.PI / 4)) + 8) % 8]
}

/** The callout read nearest a point, if one is close (image coordinates). */
export function nearestCallout(layers: MapLayers, x: number, y: number, within = 45): string | null {
  let best: { name: string; d: number } | null = null
  for (const c of layers.callouts) {
    const d = Math.hypot(c.x - x, c.y - y)
    if (d <= within && (!best || d < best.d)) best = { name: c.name, d }
  }
  return best?.name ?? null
}

/**
 * What the pins say, most useful first: the spot where the player dies most
 * and the direction the shots come from there; where kills cluster; where
 * enemy pings show up most; deaths soon after the life began.
 */
export function mapInsights(layers: MapLayers, focus: number | null = null, max = 5): string[] {
  const pins = layers.pins.filter((p) => focus === null || p.match === focus)
  const deaths = pins.filter((p) => p.kind === 'death')
  const out: string[] = []
  const near = (x: number, y: number) => {
    const c = nearestCallout(layers, x, y)
    return c ? ` near ${c}` : ''
  }
  const spots = deathClusters(deaths, 14)
  const top = spots[0]
  if (top && top.n >= 2) {
    const here = deaths.filter((d) => Math.hypot(d.px - top.x, d.py - top.y) <= 14)
    const dirs = new Map<string, number>()
    for (const d of here) {
      if (!d.killerPx) continue
      const b = bearing({ x: d.px, y: d.py }, d.killerPx)
      dirs.set(b, (dirs.get(b) ?? 0) + 1)
    }
    const [dir, n] = [...dirs].sort((a, b) => b[1] - a[1])[0] ?? [null, 0]
    const spots2 = [...new Set(here.map((d) => d.killer?.spot).filter((s): s is string => !!s))]
    out.push(
      `Most deaths in one spot: ${top.n} of ${deaths.length} within a few steps of each other${near(top.x, top.y)}` +
        (dir && n >= 2 ? `, ${n} of them shot from the ${dir}${spots2.length === 1 ? ` (${spots2[0]})` : ''}. Pre-aim or smoke the ${dir} before you cross it.` : '.'),
    )
  }
  const second = spots[1]
  if (second && second.n >= 2) {
    const where = near(second.x, second.y)
    out.push(top && where && where === near(top.x, top.y) ? `Another spot close by: ${second.n} deaths.` : `Another spot: ${second.n} deaths${where}.`)
  }
  const kills = pins.filter((p) => p.kind === 'kill')
  const kspots = deathClusters(kills.map((k) => ({ ...k, kind: 'death' as const })), 16)
  if (kspots[0] && kspots[0].n >= 3) out.push(`Your kills cluster${near(kspots[0].x, kspots[0].y) || ' in one area'} (${kspots[0].n} of ${kills.length}): a position that works for you.`)
  if (focus === null || layers.matches <= 1) {
    const e = [...layers.enemies].sort((a, b) => b.n - a.n)
    const hot = e[0]
    if (hot && hot.n >= 3) out.push(`Enemy pings show up most${near(hot.x, hot.y) || ' in one spot'} (${hot.n} times on your minimap).`)
  }
  // Deaths within 15 s of spawning and close to the spawn: its exits are watched.
  const early = deaths.flatMap((d) => {
    const start = layers.paths.find((p) => p.match === d.match && p.life === d.life)?.points[0]
    return start && d.t - start[2] <= 15 && Math.hypot(d.px - start[0], d.py - start[1]) <= 60 ? [{ d, start }] : []
  })
  if (early.length >= 2) {
    const where = near(early[0].start[0], early[0].start[1])
    out.push(`${early.length} deaths came within 15 s of spawning, close to the spawn${where ? ` (${where.slice(6)})` : ''}: its exits are watched. Wait a moment, check the exits or leave another way.`)
  }
  return out.slice(0, max)
}
