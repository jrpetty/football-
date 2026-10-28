// The map, stitched from the player's own minimap.
//
// With a fixed (north-up) minimap, every frame's minimap is a window onto the
// same overhead map, centred on the player; as they move, it slides. Each new
// window is matched against what has been stitched so far (normalised cross-
// correlation), which gives the player's position on the map to about a
// pixel, and is then painted in, so the map grows as they play and is kept
// for the next match on it. After a respawn the window is found again
// anywhere on the known map; a spawn on unexplored ground starts a new piece,
// joined to the rest as soon as the player walks onto known ground.
//
// Coloured icons (enemy pings, teammates, objectives) and the player's own
// arrow are left out of the stitching. Red enemy pings are kept as sightings,
// placed on the map. Only the screen's pixels are read.

import type { Frame } from '../types.ts'
import { alignSteps, anchorWindow, locateWindow, usable, type Alignment, type OfficialMap, type PieceImage } from './official.ts'

/** Working size of one minimap window, px. Map coordinates are in these pixels. */
export const MM = 96
const HALF = MM / 2
/** Side of one piece of stitched map, px. */
export const CANVAS = 1024
/** Weight at which a painted pixel stops averaging (so it can still change). */
const WEIGHT_CAP = 24
/** How much match a pixel away from the predicted spot costs while following (see searchNear). */
const PRIOR = 0.006
/** The player's arrow, left out: radius around the centre, px. */
const ARROW_R = 8

export interface MinimapRegion {
  readonly x: number
  readonly y: number
  readonly w: number
  readonly h: number
  /** A round minimap: only the inscribed circle is map. */
  readonly round?: boolean
}

/** One minimap window, ready to match. */
export interface MinimapWindow {
  /** Luminance 0..1, MM×MM. */
  readonly lum: Float32Array
  /** 1 where the pixel is map (not an icon, the arrow or the frame). */
  readonly mask: Uint8Array
  readonly valid: number
  /** Standard deviation of the map pixels' luminance. */
  readonly contrast: number
  /** Red enemy pings, as offsets from the player, px. */
  readonly enemies: readonly { readonly dx: number; readonly dy: number }[]
  /** Bright, uncoloured pixels at the very centre: the player's arrow (a minimap always has one there). */
  readonly marker: number
}

/** Bright centre pixels needed to take a window for a minimap (the arrow). */
export const MIN_MARKER = 6

// --- Reading a window from a frame --------------------------------------------------------------

/** Cuts the minimap out of a frame and prepares it (box-averaged down to MM×MM). */
export function readWindow(frame: Frame, region: MinimapRegion): MinimapWindow {
  const fw = frame.width
  const fh = frame.height
  const sx0 = Math.max(0, Math.round(region.x * fw))
  const sy0 = Math.max(0, Math.round(region.y * fh))
  const sw = Math.max(1, Math.min(fw - sx0, Math.round(region.w * fw)))
  const sh = Math.max(1, Math.min(fh - sy0, Math.round(region.h * fh)))
  const rgb = new Float32Array(MM * MM * 3)
  const d = frame.data
  for (let i = 0; i < MM; i++) {
    const y0 = sy0 + Math.floor((i * sh) / MM)
    const y1 = Math.max(y0 + 1, sy0 + Math.floor(((i + 1) * sh) / MM))
    for (let j = 0; j < MM; j++) {
      const x0 = sx0 + Math.floor((j * sw) / MM)
      const x1 = Math.max(x0 + 1, sx0 + Math.floor(((j + 1) * sw) / MM))
      let r = 0
      let g = 0
      let b = 0
      let n = 0
      for (let y = y0; y < y1; y++) {
        let o = (y * fw + x0) * 4
        for (let x = x0; x < x1; x++, o += 4) {
          r += d[o]
          g += d[o + 1]
          b += d[o + 2]
          n++
        }
      }
      const p = (i * MM + j) * 3
      rgb[p] = r / n
      rgb[p + 1] = g / n
      rgb[p + 2] = b / n
    }
  }
  return prepareWindow(rgb, !!region.round)
}

/** Builds the window from MM×MM RGB values (0..255). */
export function prepareWindow(rgb: Float32Array, round: boolean): MinimapWindow {
  const lum = new Float32Array(MM * MM)
  // Pixels in an icon's colour (enemy red, teammate blue, objective yellow, green); only small,
  // compact blobs of them are icons. Walls, hatching and textures in the same colours are map.
  const tinted = new Uint8Array(MM * MM)
  const red = new Uint8Array(MM * MM)
  for (let p = 0; p < MM * MM; p++) {
    const r = rgb[p * 3]
    const g = rgb[p * 3 + 1]
    const b = rgb[p * 3 + 2]
    lum[p] = (0.299 * r + 0.587 * g + 0.114 * b) / 255
    const max = Math.max(r, g, b)
    const min = Math.min(r, g, b)
    if (max <= 70 || (max - min) / max <= 0.38) continue
    const isRed = r >= 130 && g < 0.5 * r && b < 0.5 * r
    const isBlue = b >= 110 && b > 1.35 * r && b - r > 60
    const isYellow = r >= 150 && g >= 130 && b < 0.55 * g
    const isGreen = g >= 120 && g > 1.4 * r && g > 1.2 * b
    if (isRed || isBlue || isYellow || isGreen) tinted[p] = 1
    if (isRed) red[p] = 1
  }
  const icon = smallBlobs(tinted, 80, 14)
  const mask = new Uint8Array(MM * MM)
  const rr = (HALF - 3) * (HALF - 3)
  let valid = 0
  let s = 0
  let ss = 0
  for (let i = 0; i < MM; i++) {
    for (let j = 0; j < MM; j++) {
      const dy = i - HALF + 0.5
      const dx = j - HALF + 0.5
      if (i < 3 || j < 3 || i >= MM - 3 || j >= MM - 3) continue
      if (round && dx * dx + dy * dy > rr) continue
      if (dx * dx + dy * dy <= ARROW_R * ARROW_R) continue
      // Icons and a pixel around them.
      let near = 0
      for (let a = -1; a <= 1 && !near; a++) for (let c = -1; c <= 1 && !near; c++) near = icon[(i + a) * MM + j + c]
      if (near) continue
      const p = i * MM + j
      mask[p] = 1
      valid++
      s += lum[p]
      ss += lum[p] * lum[p]
    }
  }
  const mean = valid ? s / valid : 0
  const contrast = valid ? Math.sqrt(Math.max(0, ss / valid - mean * mean)) : 0
  let marker = 0
  const mr = ARROW_R - 2
  for (let i = HALF - mr; i < HALF + mr; i++) {
    for (let j = HALF - mr; j < HALF + mr; j++) {
      const dy = i - HALF + 0.5
      const dx = j - HALF + 0.5
      if (dx * dx + dy * dy > mr * mr) continue
      const p = i * MM + j
      if (lum[p] >= 0.7 && !tinted[p]) marker++
    }
  }
  return { lum, mask, valid, contrast, enemies: blobs(red), marker }
}

/** The pixels of connected blobs no bigger than `maxSize` pixels and `maxSide` across. */
function smallBlobs(m: Uint8Array, maxSize: number, maxSide: number): Uint8Array {
  const out = new Uint8Array(MM * MM)
  const seen = new Uint8Array(MM * MM)
  const stack: number[] = []
  const members: number[] = []
  for (let p = 0; p < MM * MM; p++) {
    if (!m[p] || seen[p]) continue
    members.length = 0
    let x0 = MM
    let x1 = -1
    let y0 = MM
    let y1 = -1
    stack.push(p)
    seen[p] = 1
    while (stack.length) {
      const q = stack.pop()!
      members.push(q)
      const qi = (q / MM) | 0
      const qj = q % MM
      if (qj < x0) x0 = qj
      if (qj > x1) x1 = qj
      if (qi < y0) y0 = qi
      if (qi > y1) y1 = qi
      for (let a = -1; a <= 1; a++)
        for (let c = -1; c <= 1; c++) {
          const i = qi + a
          const j = qj + c
          if (i < 0 || j < 0 || i >= MM || j >= MM) continue
          const r = i * MM + j
          if (m[r] && !seen[r]) {
            seen[r] = 1
            stack.push(r)
          }
        }
    }
    if (members.length <= maxSize && x1 - x0 < maxSide && y1 - y0 < maxSide) for (const q of members) out[q] = 1
  }
  return out
}

/** Centres of red blobs (enemy pings) outside the arrow, as offsets from the centre. */
function blobs(red: Uint8Array): { dx: number; dy: number }[] {
  const seen = new Uint8Array(MM * MM)
  const out: { dx: number; dy: number }[] = []
  const stack: number[] = []
  for (let p = 0; p < MM * MM; p++) {
    if (!red[p] || seen[p]) continue
    let n = 0
    let sx = 0
    let sy = 0
    stack.push(p)
    seen[p] = 1
    while (stack.length) {
      const q = stack.pop()!
      const qi = (q / MM) | 0
      const qj = q % MM
      n++
      sx += qj
      sy += qi
      for (const [a, c] of [[-1, 0], [1, 0], [0, -1], [0, 1]] as const) {
        const i = qi + a
        const j = qj + c
        if (i < 0 || j < 0 || i >= MM || j >= MM) continue
        const r = i * MM + j
        if (red[r] && !seen[r]) {
          seen[r] = 1
          stack.push(r)
        }
      }
    }
    if (n < 2 || n > 80) continue
    const dx = sx / n - HALF + 0.5
    const dy = sy / n - HALF + 0.5
    if (dx * dx + dy * dy > (ARROW_R + 2) * (ARROW_R + 2)) out.push({ dx: Math.round(dx), dy: Math.round(dy) })
  }
  return out.slice(0, 12)
}

// --- A piece of stitched map ---------------------------------------------------------------------

export class MapPiece {
  /** Renumbered when a piece moves into another learned map (see MinimapMosaic.adopt). */
  id: number
  readonly mean = new Float32Array(CANVAS * CANVAS)
  readonly weight = new Uint8Array(CANVAS * CANVAS)
  minX = CANVAS
  minY = CANVAS
  maxX = -1
  maxY = -1
  /** Pixels painted at least once. */
  area = 0

  constructor(id: number) {
    this.id = id
  }

  get empty(): boolean {
    return this.maxX < 0
  }

  paint(w: MinimapWindow, cx: number, cy: number): void {
    const x0 = cx - HALF
    const y0 = cy - HALF
    for (let i = 0; i < MM; i++) {
      const Y = y0 + i
      if (Y < 0 || Y >= CANVAS) continue
      for (let j = 0; j < MM; j++) {
        const p = i * MM + j
        if (!w.mask[p]) continue
        const X = x0 + j
        if (X < 0 || X >= CANVAS) continue
        const o = Y * CANVAS + X
        const n = this.weight[o]
        if (n === 0) {
          this.area++
          if (X < this.minX) this.minX = X
          if (X > this.maxX) this.maxX = X
          if (Y < this.minY) this.minY = Y
          if (Y > this.maxY) this.maxY = Y
        }
        this.mean[o] += (w.lum[p] - this.mean[o]) / (n + 1)
        if (n < WEIGHT_CAP) this.weight[o] = n + 1
      }
    }
  }

  /** Paints another piece into this one, shifted by (dx, dy). */
  absorb(other: MapPiece, dx: number, dy: number): void {
    for (let Y = other.minY; Y <= other.maxY; Y++) {
      const TY = Y + dy
      if (TY < 0 || TY >= CANVAS) continue
      for (let X = other.minX; X <= other.maxX; X++) {
        const wo = other.weight[Y * CANVAS + X]
        if (!wo) continue
        const TX = X + dx
        if (TX < 0 || TX >= CANVAS) continue
        const o = TY * CANVAS + TX
        const wt = this.weight[o]
        if (wt === 0) {
          this.area++
          if (TX < this.minX) this.minX = TX
          if (TX > this.maxX) this.maxX = TX
          if (TY < this.minY) this.minY = TY
          if (TY > this.maxY) this.maxY = TY
        }
        this.mean[o] = (this.mean[o] * wt + other.mean[Y * CANVAS + X] * wo) / (wt + wo)
        this.weight[o] = Math.min(WEIGHT_CAP, wt + wo)
      }
    }
  }

  /** Normalised cross-correlation of a window centred at (cx, cy), every `step` pixels; -1 when too little overlaps. */
  score(w: MinimapWindow, cx: number, cy: number, step: number, minOverlap: number): number {
    const x0 = cx - HALF
    const y0 = cy - HALF
    let n = 0
    let sa = 0
    let sb = 0
    let saa = 0
    let sbb = 0
    let sab = 0
    let total = 0
    for (let i = 0; i < MM; i += step) {
      const Y = y0 + i
      const inY = Y >= 0 && Y < CANVAS
      const row = Y * CANVAS
      for (let j = 0; j < MM; j += step) {
        const p = i * MM + j
        if (!w.mask[p]) continue
        total++
        if (!inY) continue
        const X = x0 + j
        if (X < 0 || X >= CANVAS) continue
        const o = row + X
        if (!this.weight[o]) continue
        const a = w.lum[p]
        const b = this.mean[o]
        n++
        sa += a
        sb += b
        saa += a * a
        sbb += b * b
        sab += a * b
      }
    }
    if (!total || n < minOverlap * total) return -1
    const va = saa - (sa * sa) / n
    const vb = sbb - (sb * sb) / n
    if (va <= 1e-6 || vb <= 1e-6) return -1
    return (sab - (sa * sb) / n) / Math.sqrt(va * vb)
  }

  /**
   * The best match within `r` px of the predicted spot (px, py): coarse every 2 px, then to the
   * pixel. Near-ties go to the prediction: along a straight wall with little else in view every
   * spot matches about as well, and the player keeps moving the way they were.
   */
  searchNear(w: MinimapWindow, px: number, py: number, r: number, minOverlap = 0.45): { x: number; y: number; ncc: number } {
    let best = { x: px, y: py, ncc: -1 }
    let bestV = -Infinity
    const rr = r * r
    for (let dy = -r; dy <= r; dy += 2) {
      for (let dx = -r; dx <= r; dx += 2) {
        if (dx * dx + dy * dy > rr) continue
        const s = this.score(w, px + dx, py + dy, 2, minOverlap)
        const v = s - PRIOR * Math.hypot(dx, dy)
        if (s > -1 && v > bestV) {
          best = { x: px + dx, y: py + dy, ncc: s }
          bestV = v
        }
      }
    }
    return this.refine(w, best, 1, minOverlap, { x: px, y: py })
  }

  private refine(w: MinimapWindow, at: { x: number; y: number; ncc: number }, r: number, minOverlap: number, prior?: { x: number; y: number }): { x: number; y: number; ncc: number } {
    const cost = (x: number, y: number) => (prior ? PRIOR * Math.hypot(x - prior.x, y - prior.y) : 0)
    let best = { ...at, ncc: this.score(w, at.x, at.y, 1, minOverlap) }
    let bestV = best.ncc - cost(at.x, at.y)
    for (let dy = -r; dy <= r; dy++) {
      for (let dx = -r; dx <= r; dx++) {
        if (!dx && !dy) continue
        const s = this.score(w, at.x + dx, at.y + dy, 1, minOverlap)
        const v = s - cost(at.x + dx, at.y + dy)
        if (v > bestV) {
          best = { x: at.x + dx, y: at.y + dy, ncc: s }
          bestV = v
        }
      }
    }
    return best
  }

  /**
   * The best match anywhere on this piece (the window may hang over its
   * edge), with the margin over the best match elsewhere: a coarse pass every
   * 4 px, then the strongest few refined.
   */
  searchAll(w: MinimapWindow, minOverlap = 0.4): { x: number; y: number; ncc: number; margin: number } | null {
    if (this.empty) return null
    const pad = Math.round(HALF * (1 - minOverlap))
    const x0 = Math.max(HALF, this.minX - pad)
    const x1 = Math.min(CANVAS - HALF, this.maxX + pad)
    const y0 = Math.max(HALF, this.minY - pad)
    const y1 = Math.min(CANVAS - HALF, this.maxY + pad)
    const top: { x: number; y: number; ncc: number }[] = []
    for (let y = y0; y <= y1; y += 4) {
      for (let x = x0; x <= x1; x += 4) {
        const s = this.score(w, x, y, 4, minOverlap)
        if (s < 0.25) continue
        top.push({ x, y, ncc: s })
      }
    }
    if (!top.length) return null
    top.sort((a, b) => b.ncc - a.ncc)
    // The strongest few, at least 10 px apart.
    const picks: { x: number; y: number; ncc: number }[] = []
    for (const t of top) {
      if (picks.every((p) => Math.hypot(p.x - t.x, p.y - t.y) > 10)) picks.push(t)
      if (picks.length >= 5) break
    }
    const refined = picks
      .map((p) => {
        let b = { ...p, ncc: -1 }
        for (let dy = -4; dy <= 4; dy += 2) for (let dx = -4; dx <= 4; dx += 2) {
          const s = this.score(w, p.x + dx, p.y + dy, 2, minOverlap)
          if (s > b.ncc) b = { x: p.x + dx, y: p.y + dy, ncc: s }
        }
        return this.refine(w, b, 1, minOverlap)
      })
      .sort((a, b) => b.ncc - a.ncc)
    const best = refined[0]
    const second = refined.find((r) => Math.hypot(r.x - best.x, r.y - best.y) > 10)
    return { ...best, margin: best.ncc - (second?.ncc ?? 0) }
  }
}

// --- Following the player -----------------------------------------------------------------------

export type TrackState = 'tracking' | 'found' | 'new' | 'lost' | 'skipped'

export interface TrackPoint {
  readonly t: number
  readonly piece: number
  readonly x: number
  readonly y: number
  readonly ncc: number
  /** The first point after a jump (respawn, spectating, a killcam). */
  readonly jump: boolean
}

/** A learned map as stored: the stitched pieces (cropped to what was seen) and how earlier pieces were joined. */
export interface StoredMosaic {
  readonly version: 1
  readonly map: string
  readonly updatedAt: string
  readonly pieces: readonly { readonly id: number; readonly x0: number; readonly y0: number; readonly w: number; readonly h: number; readonly mean: string; readonly weight: string }[]
  /** A piece joined into another: [into, dx, dy]. */
  readonly joined: Readonly<Record<string, readonly [number, number, number]>>
  readonly nextId: number
  /** Callouts read on screen, placed where the player stood when they were read. */
  readonly callouts: Readonly<Record<string, { readonly piece: number; readonly x: number; readonly y: number; readonly n: number }>>
  /** Heights the player was seen at, per 8-px cell ("piece:cx:cy" → underground, ground, upper, roof). */
  readonly heights: Readonly<Record<string, readonly [number, number, number, number]>>
  /** Pieces placed on the official overhead map (stitched pixels → its pixels), by piece id. */
  readonly aligned?: Readonly<Record<string, Alignment>>
  /** The size of the official image those placements refer to. */
  readonly alignedTo?: { readonly w: number; readonly h: number }
}

/** Thresholds: keep tracking; accept a re-find; join two pieces. */
const KEEP = 0.5
const FIND = 0.75
const JOIN = 0.75
/** Failed re-finds (with a readable minimap) before a new piece starts. */
const NEW_AFTER = 3
/** How far (stitched px) the official map may move a tracked spot. */
const ANCHOR_REACH = 4
/** Recent minimap views kept for points not placed yet (about 9 KB each). */
const MAX_VIEWS = 240
/** Frames a placement from one window must be borne out by before it is shown. */
const CONFIRM_FRAMES = 3
/** A piece is kept only once this much has been seen on it (a still screen never grows). */
const KEEP_AREA = MM * MM * 1.6
const MAX_TRAIL = 30_000

export const CELL = 8
const ELEV_INDEX: Readonly<Record<string, number>> = { underground: 0, ground: 1, upper: 2, roof: 3 }

export class MinimapMosaic {
  readonly map: string
  private pieces = new Map<number, MapPiece>()
  private joined = new Map<number, [number, number, number]>()
  private nextId = 1
  private cur: { piece: number; x: number; y: number; vx: number; vy: number; t: number } | null = null
  private failures = 0
  private frames = 0
  private callouts = new Map<string, { piece: number; x: number; y: number; n: number }>()
  private heights = new Map<string, [number, number, number, number]>()
  readonly trail: TrackPoint[] = []
  /** Set when a piece was added to or changed. */
  dirty = false
  last: { state: TrackState; ncc: number; at: number } = { state: 'lost', ncc: 0, at: 0 }

  /** Follow only windows with the player's arrow at the centre (not a death screen or a scene that slides like a map). */
  needMarker = true
  /** The official overhead map, when the player has it: pieces are placed on it, and re-finds are checked against it. */
  private official: OfficialMap | null = null
  private aligned = new Map<number, Alignment>()
  private alignedArea = new Map<number, number>()
  /**
   * Pieces placed from one window, not yet shown: the next frames must pin down on the official
   * map where the placement says (a boundary wall beside hatched ground can look like a hatched
   * building's wall), else the placement is dropped.
   */
  private provisional = new Map<number, { ok: number; bad: number }>()
  /** How often each placed piece's frames pin down on the official map (decaying counts). */
  private anchored = new Map<number, { ok: number; n: number }>()
  /**
   * The minimap at recent points on pieces not placed yet (in memory only): once the piece is
   * placed, each of those points is pinned down on the official map too.
   */
  private unplacedViews: { point: TrackPoint; img: PieceImage }[] = []
  private job: { piece: number; area: number; it: Generator<void, Alignment | null> } | null = null

  constructor(map: string, stored?: StoredMosaic | null) {
    this.map = map
    if (stored) this.load(stored)
  }

  /** Use the official overhead map (or stop using it). Placements made against another size are scaled. */
  setOfficial(off: OfficialMap | null, stored?: StoredMosaic | null): void {
    this.official = off
    this.job = null
    const to = stored?.alignedTo
    if (off && to && (to.w !== off.srcW || to.h !== off.srcH)) {
      const kx = off.srcW / to.w
      for (const [id, a] of this.aligned) this.aligned.set(id, { ...a, s: a.s * kx, tx: a.tx * kx, ty: a.ty * (off.srcH / to.h) })
    }
  }

  /** Follows one window. */
  track(w: MinimapWindow, t: number): { state: TrackState; point: TrackPoint | null } {
    this.frames++
    const out = this.step(w, t)
    if (this.official) this.alignWork(2)
    this.last = { state: out.state, ncc: out.point?.ncc ?? 0, at: t }
    if (out.point) {
      const prev = this.trail[this.trail.length - 1]
      if (!prev || out.point.jump || prev.piece !== out.point.piece || Math.hypot(prev.x - out.point.x, prev.y - out.point.y) >= 1 || t - prev.t >= 1000) {
        this.trail.push(out.point)
        if (this.trail.length > MAX_TRAIL) this.trail.splice(0, this.trail.length - MAX_TRAIL)
        if (this.official && !this.alignmentFor(out.point.piece)) {
          this.unplacedViews.push({ point: out.point, img: windowImage(w, Math.round(out.point.x) - HALF, Math.round(out.point.y) - HALF) })
          if (this.unplacedViews.length > MAX_VIEWS) this.unplacedViews.shift()
        }
      }
    }
    return out
  }

  private step(w: MinimapWindow, t: number): { state: TrackState; point: TrackPoint | null } {
    if (w.valid < MM * MM * 0.3 || w.contrast < 0.025 || (this.needMarker && w.marker < MIN_MARKER)) return { state: 'skipped', point: null }
    if (!this.pieces.size) {
      const p = this.newPiece()
      p.paint(w, CANVAS / 2, CANVAS / 2)
      this.cur = { piece: p.id, x: CANVAS / 2, y: CANVAS / 2, vx: 0, vy: 0, t }
      return { state: 'new', point: this.point(t, 1, true) }
    }
    const c = this.cur
    if (c && t - c.t <= 3000) {
      const dt = Math.max(1, t - c.t)
      const px = Math.round(c.x + Math.max(-20, Math.min(20, c.vx * dt)))
      const py = Math.round(c.y + Math.max(-20, Math.min(20, c.vy * dt)))
      const r = Math.round(Math.min(28, 8 + (Math.hypot(c.vx, c.vy) * dt) / 2 + dt / 250))
      const piece = this.pieces.get(c.piece)!
      const m = piece.searchNear(w, px, py, r)
      if (m.ncc >= KEEP) {
        // Further than anyone moves between two frames: the view cut to somewhere else nearby (a killcam).
        const cut = Math.hypot(m.x - px, m.y - py) > 14 + dt / 100
        // On a piece placed on the official map, the spot is taken from it (no drift).
        const at = this.official && !cut ? this.anchor(w, c.piece, m.x, m.y) : null
        const x = at?.x ?? m.x
        const y = at?.y ?? m.y
        const moved = Math.round(x) !== Math.round(c.x) || Math.round(y) !== Math.round(c.y)
        if (moved || this.frames % 5 === 0) {
          piece.paint(w, Math.round(x), Math.round(y))
          this.dirty = true
        }
        this.cur = { piece: c.piece, x, y, vx: 0.6 * c.vx + (0.4 * (x - c.x)) / dt, vy: 0.6 * c.vy + (0.4 * (y - c.y)) / dt, t }
        this.failures = 0
        // With the official map, pieces are placed on it instead of joined by look (a map with two
        // identical wings would join the wrong way).
        if (!this.official && this.pieces.size > 1 && this.frames % 15 === 0) this.tryJoin(w)
        return { state: 'tracking', point: this.point(t, m.ncc, cut) }
      }
      this.cur = null
    }
    // Lost (a respawn, a killcam, a menu): look everywhere, on every other frame.
    if (this.failures % 2 === 1 && this.failures < NEW_AFTER) {
      this.failures++
      return { state: 'lost', point: null }
    }
    const found = this.findAnywhere(w, FIND)
    if (found && this.official && !this.confirmOnOfficial(w, found)) {
      this.failures++
      if (this.failures < NEW_AFTER) return { state: 'lost', point: null }
    } else if (found) {
      const piece = this.pieces.get(found.piece)!
      const at = this.official ? this.anchor(w, found.piece, found.x, found.y) : null
      const x = at?.x ?? found.x
      const y = at?.y ?? found.y
      piece.paint(w, Math.round(x), Math.round(y))
      this.dirty = true
      this.cur = { piece: found.piece, x, y, vx: 0, vy: 0, t }
      this.failures = 0
      return { state: 'found', point: this.point(t, found.ncc, true) }
    }
    this.failures++
    if (this.failures < NEW_AFTER) return { state: 'lost', point: null }
    // Unexplored ground (or a place that could be either of two): a new piece, joined to the rest
    // later, or placed straight on the official map when the window is found there for certain.
    const p = this.newPiece()
    p.paint(w, CANVAS / 2, CANVAS / 2)
    this.dirty = true
    if (this.official) {
      const s = this.knownScale()
      const at = s ? locateWindow(windowImage(w, CANVAS / 2 - HALF, CANVAS / 2 - HALF), this.official, s) : null
        if (at && at.unique && s) {
        this.aligned.set(p.id, { s, tx: at.x - s * (CANVAS / 2 - HALF), ty: at.y - s * (CANVAS / 2 - HALF), ncc: at.ncc, coverage: 1, margin: at.margin })
        this.alignedArea.set(p.id, 0)
        this.provisional.set(p.id, { ok: 0, bad: 0 })
      }
    }
    this.cur = { piece: p.id, x: CANVAS / 2, y: CANVAS / 2, vx: 0, vy: 0, t }
    this.failures = 0
    return { state: 'new', point: this.point(t, 1, true) }
  }

  /** The window's best match on any piece, if clear enough. */
  private findAnywhere(w: MinimapWindow, min: number, except?: number): { piece: number; x: number; y: number; ncc: number } | null {
    let best: { piece: number; x: number; y: number; ncc: number; margin: number } | null = null
    for (const p of [...this.pieces.values()].sort((a, b) => b.area - a.area)) {
      if (p.id === except) continue
      const m = p.searchAll(w)
      if (m && (!best || m.ncc > best.ncc)) best = { piece: p.id, ...m }
    }
    // A clear winner only: on a symmetric map the mirror-image spot can match nearly as well.
    if (!best || best.ncc < min || best.margin < 0.06) return null
    return best
  }

  /** While on a newer piece, checks whether this spot is already on another; if so, the two are joined. */
  private tryJoin(w: MinimapWindow): void {
    const c = this.cur!
    const other = this.findAnywhere(w, JOIN, c.piece)
    if (!other) return
    const a = this.pieces.get(c.piece)!
    const b = this.pieces.get(other.piece)!
    // The smaller piece goes into the larger.
    // Whole pixels (a spot pinned on the official map can fall between two).
    const cx = Math.round(c.x)
    const cy = Math.round(c.y)
    const [from, into, dx, dy] = a.area <= b.area ? [a, b, other.x - cx, other.y - cy] : [b, a, cx - other.x, cy - other.y]
    into.absorb(from, dx, dy)
    this.pieces.delete(from.id)
    this.joined.set(from.id, [into.id, dx, dy])
    if (from.id === c.piece) this.cur = { ...c, piece: into.id, x: c.x + dx, y: c.y + dy }
    this.dirty = true
  }

  private newPiece(): MapPiece {
    const p = new MapPiece(this.nextId++)
    this.pieces.set(p.id, p)
    return p
  }

  private point(t: number, ncc: number, jump: boolean): TrackPoint {
    const c = this.cur!
    return { t, piece: c.piece, x: c.x, y: c.y, ncc, jump }
  }

  /** Where a point recorded on a piece lies now that pieces may have been joined. */
  resolve(piece: number, x: number, y: number): { piece: number; x: number; y: number } {
    let p = piece
    let X = x
    let Y = y
    for (let guard = 0; guard < 64; guard++) {
      const j = this.joined.get(p)
      if (!j) break
      ;[p, X, Y] = [j[0], X + j[1], Y + j[2]]
    }
    return { piece: p, x: X, y: Y }
  }

  /** The trail point nearest a moment (within `withinMs`), resolved. */
  positionAt(t: number, withinMs = 1500): (TrackPoint & { readonly index: number }) | null {
    const tr = this.trail
    let lo = 0
    let hi = tr.length - 1
    while (lo < hi) {
      const mid = (lo + hi) >> 1
      if (tr[mid].t < t) lo = mid + 1
      else hi = mid
    }
    let best = -1
    for (const i of [lo - 1, lo]) if (i >= 0 && i < tr.length && Math.abs(tr[i].t - t) <= withinMs && (best < 0 || Math.abs(tr[i].t - t) < Math.abs(tr[best].t - t))) best = i
    if (best < 0) return null
    const p = tr[best]
    return { ...p, ...this.resolve(p.piece, p.x, p.y), index: best }
  }

  /** Where the player is now (resolved), if tracking. */
  here(): { piece: number; x: number; y: number; t: number } | null {
    return this.cur ? { ...this.resolve(this.cur.piece, this.cur.x, this.cur.y), t: this.cur.t } : null
  }

  /** Forget the current position (a death, a menu): the next window is found anew. */
  lose(): void {
    this.cur = null
  }

  /** A callout read on screen while the player stood here. */
  learnCallout(name: string, at: { piece: number; x: number; y: number }): void {
    const c = this.callouts.get(name)
    if (!c) {
      this.callouts.set(name, { ...at, n: 1 })
    } else {
      const r = this.resolve(c.piece, c.x, c.y)
      if (r.piece !== at.piece) return
      const n = Math.min(c.n + 1, 50)
      this.callouts.set(name, { piece: r.piece, x: r.x + (at.x - r.x) / n, y: r.y + (at.y - r.y) / n, n })
    }
    this.dirty = true
  }

  /** A height read on screen while the player stood here. */
  learnHeight(elevation: string, at: { piece: number; x: number; y: number }): void {
    const k = ELEV_INDEX[elevation]
    if (k === undefined) return
    const key = `${at.piece}:${Math.floor(at.x / CELL)}:${Math.floor(at.y / CELL)}`
    const h = this.heights.get(key) ?? [0, 0, 0, 0]
    h[k] = Math.min(9999, h[k] + 1)
    this.heights.set(key, h)
    this.dirty = true
  }

  /**
   * Takes over what was stitched before the map's name was known: its pieces
   * (renumbered), its trail and where the player is. They join this map's
   * pieces as soon as the player is on ground seen before.
   */
  adopt(other: MinimapMosaic): void {
    const ids = new Map<number, number>()
    for (const [id, p] of other.pieces) {
      const nid = this.nextId++
      ids.set(id, nid)
      p.id = nid
      this.pieces.set(nid, p)
    }
    const map = (id: number) => ids.get(id) ?? id
    for (const [id, j] of other.joined) this.joined.set(map(id), [map(j[0]), j[1], j[2]])
    for (const pt of other.trail) this.trail.push({ ...pt, piece: map(pt.piece) })
    for (const [n, c] of other.callouts) if (!this.callouts.has(n)) this.callouts.set(n, { ...c, piece: map(c.piece) })
    for (const [k, v] of other.heights) {
      const [id, cx, cy] = k.split(':')
      this.heights.set(`${map(Number(id))}:${cx}:${cy}`, v)
    }
    for (const [id, a] of other.aligned) if (!other.provisional.has(id)) this.aligned.set(map(id), a)
    if (other.cur) this.cur = { ...other.cur, piece: map(other.cur.piece) }
    this.dirty = true
    other.pieces = new Map()
    other.cur = null
  }

  /**
   * A re-find on a piece placed on the official map: the window must also be on the official map
   * where the placement puts it, and nowhere else nearly as well. On a piece not placed yet, the
   * window must at least be unique on the official map.
   */
  private confirmOnOfficial(w: MinimapWindow, found: { piece: number; x: number; y: number }): boolean {
    const off = this.official!
    const s = this.knownScale()
    if (!s) return true
    const at = locateWindow(windowImage(w, found.x - HALF, found.y - HALF), off, s)
    if (!at || !at.unique) return false
    const a = this.placement(found.piece)
    if (!a) return true
    const X = a.s * (found.x - HALF) + a.tx
    const Y = a.s * (found.y - HALF) + a.ty
    // The unique official spot must be where this piece's placement says (within a few minimap pixels).
    return Math.hypot(at.x - X, at.y - Y) <= 6 * s
  }

  /**
   * Where a window matched at (x, y) on a piece really is, from the official map: the piece's
   * placement predicts the spot, and the official map pins it down within a few pixels.
   */
  private anchor(w: MinimapWindow, piece: number, x: number, y: number): { x: number; y: number } | null {
    const a = this.placement(piece)
    if (!a) return null
    const X = a.s * (x - HALF) + a.tx
    const Y = a.s * (y - HALF) + a.ty
    const at = anchorWindow(windowImage(w, x - HALF, y - HALF), this.official!, a.s, X, Y, ANCHOR_REACH * a.s)
    const rate = this.anchored.get(piece) ?? { ok: 0, n: 0 }
    this.anchored.set(piece, { ok: rate.ok * 0.95 + (at ? 1 : 0), n: rate.n * 0.95 + 1 })
    const check = this.provisional.get(piece)
    if (check) {
      if (at) check.ok++
      else check.bad++
      if (check.ok >= CONFIRM_FRAMES) {
        this.provisional.delete(piece)
        this.pinDown(piece)
      }
      else if (check.bad >= 2) {
        this.unplace(piece)
        return null
      }
    }
    if (!at) return null
    return { x: (at.x - a.tx) / a.s + HALF, y: (at.y - a.ty) / a.s + HALF }
  }

  /** Pins down on the official map the points recorded on a piece before it was placed. */
  private pinDown(piece: number): void {
    const a = this.alignmentFor(piece)
    const mine = this.unplacedViews.filter((v) => v.point.piece === piece)
    if (!a || !mine.length) return
    this.unplacedViews = this.unplacedViews.filter((v) => v.point.piece !== piece)
    const moved = new Map<TrackPoint, TrackPoint>()
    for (const v of mine) {
      const X = a.s * (v.point.x - HALF) + a.tx
      const Y = a.s * (v.point.y - HALF) + a.ty
      // Further than while following: the piece may have drifted a little before it was placed.
      const at = anchorWindow(v.img, this.official!, a.s, X, Y, 1.5 * ANCHOR_REACH * a.s)
      if (at) moved.set(v.point, { ...v.point, x: v.point.x + (at.x - X) / a.s, y: v.point.y + (at.y - Y) / a.s })
    }
    const oldest = mine[0].point.t
    for (let i = this.trail.length - 1; i >= 0 && this.trail[i].t >= oldest; i--) {
      const m = moved.get(this.trail[i])
      if (m) this.trail[i] = m
    }
    this.dirty = true
  }

  private unplace(piece: number): void {
    this.aligned.delete(piece)
    this.alignedArea.delete(piece)
    this.provisional.delete(piece)
    this.anchored.delete(piece)
    this.dirty = true
  }

  /** Advances the placement of pieces on the official map by a few steps (it is spread over frames). */
  private alignWork(steps: number): void {
    const off = this.official!
    if (!this.job) {
      const need = [...this.pieces.values()]
        .filter((p) => p.area >= MM * MM * 1.2)
        .filter((p) => {
          const a = this.aligned.get(p.id)
          if (!a) return true
          // A placement the frames keep pinning down is kept: fitting the grown piece again would
          // move every point already placed by it.
          const rate = this.anchored.get(p.id)
          const holding = !!rate && rate.n >= 8 && rate.ok >= rate.n * 0.5
          return !a.manual && !holding && p.area >= (this.alignedArea.get(p.id) ?? 0) * 1.4
        })
        .sort((a, b) => (b.id === this.cur?.piece ? 1 : 0) - (a.id === this.cur?.piece ? 1 : 0) || b.area - a.area)[0]
      if (!need) return
      this.job = { piece: need.id, area: need.area, it: alignSteps(pieceImage(need), off, { scale: this.knownScale() ?? undefined }) }
    }
    for (let i = 0; i < steps; i++) {
      const r = this.job.it.next()
      if (!r.done) continue
      const piece = this.job.piece
      // A worse fit never replaces a good one, but the whole piece outranks the one window it was
      // first placed by, and a window placement the whole piece does not bear out is dropped.
      const prev = this.aligned.get(piece)
      if (usable(r.value) && (!usable(prev) || !this.alignedArea.get(piece) || r.value.ncc >= prev.ncc - 0.05)) {
        this.aligned.set(piece, r.value)
        this.provisional.delete(piece)
        this.anchored.delete(piece)
        this.pinDown(piece)
      } else if (this.provisional.has(piece)) this.unplace(piece)
      if (this.aligned.has(piece)) this.alignedArea.set(piece, this.job.area)
      this.dirty = true
      this.job = null
      return
    }
  }

  /** The scale found so far (official pixels per stitched pixel), the middle of the placements. */
  knownScale(): number | null {
    const ss = [...this.aligned].filter(([id, a]) => usable(a) && !this.provisional.has(id)).map(([, a]) => a.s).sort((a, b) => a - b)
    return ss.length ? ss[ss.length >> 1] : null
  }

  /** The placement on the official map of a piece (or of a piece joined into it), once it is certain. */
  alignmentFor(piece: number): Alignment | null {
    return this.placement(piece, true)
  }

  private placement(piece: number, certain = false): Alignment | null {
    const a = this.aligned.get(piece)
    if (usable(a)) return certain && this.provisional.has(piece) ? null : a
    for (const [id] of this.joined) {
      if (this.resolve(id, 0, 0).piece !== piece) continue
      const b = this.aligned.get(id)
      if (!usable(b) || (certain && this.provisional.has(id))) continue
      const r = this.resolve(id, 0, 0)
      // x_id = x_piece − (r.x − 0): the joined piece's origin lies at r on this one.
      return { ...b, tx: b.tx - b.s * r.x, ty: b.ty - b.s * r.y }
    }
    return null
  }

  /** Where a callout was read, resolved, if it has been. */
  calloutAt(name: string): { piece: number; x: number; y: number; n: number } | null {
    const c = this.callouts.get(name)
    return c ? { ...this.resolve(c.piece, c.x, c.y), n: c.n } : null
  }

  /** The largest piece's size, px² (what has been seen of this map). */
  get seenArea(): number {
    return Math.max(0, ...[...this.pieces.values()].map((p) => p.area))
  }

  get pieceCount(): number {
    return [...this.pieces.values()].filter((p) => p.area >= KEEP_AREA).length
  }

  /** The pieces worth keeping (a still screen or a glimpse is not), cropped to what was seen. */
  save(now = new Date()): StoredMosaic {
    const pieces = [...this.pieces.values()]
      .filter((p) => p.area >= KEEP_AREA)
      .map((p) => {
        const w = p.maxX - p.minX + 1
        const h = p.maxY - p.minY + 1
        const mean = new Uint8Array(w * h)
        const weight = new Uint8Array(w * h)
        for (let y = 0; y < h; y++) {
          for (let x = 0; x < w; x++) {
            const o = (p.minY + y) * CANVAS + p.minX + x
            weight[y * w + x] = p.weight[o]
            mean[y * w + x] = Math.round(Math.max(0, Math.min(1, p.mean[o])) * 255)
          }
        }
        return { id: p.id, x0: p.minX, y0: p.minY, w, h, mean: toBase64(mean), weight: toBase64(weight) }
      })
    const kept = new Set(pieces.map((p) => p.id))
    const resolvedKept = (id: number) => kept.has(this.resolve(id, 0, 0).piece)
    return {
      version: 1,
      map: this.map,
      updatedAt: now.toISOString(),
      pieces,
      joined: Object.fromEntries([...this.joined].filter(([id]) => resolvedKept(id)).map(([id, j]) => [String(id), j])),
      nextId: this.nextId,
      callouts: Object.fromEntries([...this.callouts].filter(([, c]) => resolvedKept(c.piece)).map(([n, c]) => [n, { ...this.resolve(c.piece, c.x, c.y), n: c.n }])),
      heights: Object.fromEntries(
        [...this.heights].filter(([k]) => resolvedKept(Number(k.split(':')[0]))).map(([k, v]) => {
          const [id, cx, cy] = k.split(':').map(Number)
          const r = this.resolve(id, cx * CELL, cy * CELL)
          return [`${r.piece}:${Math.floor(r.x / CELL)}:${Math.floor(r.y / CELL)}`, v] as const
        }),
      ),
      ...(this.aligned.size
        ? {
            aligned: Object.fromEntries([...this.aligned].filter(([id]) => !this.provisional.has(id) && (kept.has(id) || resolvedKept(id))).map(([id, a]) => [String(id), a])),
            ...(this.official ? { alignedTo: { w: this.official.srcW, h: this.official.srcH } } : this.alignedTo ? { alignedTo: this.alignedTo } : {}),
          }
        : {}),
    }
  }

  private alignedTo: { w: number; h: number } | null = null

  /** Places a piece on the official map by hand (the overhead view's Adjust). */
  setAlignment(piece: number, a: Alignment): void {
    this.aligned.set(piece, a)
    this.provisional.delete(piece)
    this.anchored.delete(piece)
    this.dirty = true
  }

  private load(s: StoredMosaic): void {
    for (const sp of s.pieces) {
      const p = new MapPiece(sp.id)
      const mean = fromBase64(sp.mean)
      const weight = fromBase64(sp.weight)
      for (let y = 0; y < sp.h; y++) {
        for (let x = 0; x < sp.w; x++) {
          const wt = weight[y * sp.w + x]
          if (!wt) continue
          const X = sp.x0 + x
          const Y = sp.y0 + y
          if (X < 0 || Y < 0 || X >= CANVAS || Y >= CANVAS) continue
          const o = Y * CANVAS + X
          p.weight[o] = Math.min(WEIGHT_CAP, wt)
          p.mean[o] = mean[y * sp.w + x] / 255
          p.area++
          if (X < p.minX) p.minX = X
          if (X > p.maxX) p.maxX = X
          if (Y < p.minY) p.minY = Y
          if (Y > p.maxY) p.maxY = Y
        }
      }
      this.pieces.set(p.id, p)
    }
    for (const [id, j] of Object.entries(s.joined)) this.joined.set(Number(id), [j[0], j[1], j[2]])
    this.nextId = Math.max(s.nextId, ...[...this.pieces.keys()].map((k) => k + 1), 1)
    for (const [n, c] of Object.entries(s.callouts)) this.callouts.set(n, { ...c })
    for (const [k, v] of Object.entries(s.heights)) this.heights.set(k, [v[0], v[1], v[2], v[3]])
    for (const [id, a] of Object.entries(s.aligned ?? {})) this.aligned.set(Number(id), a)
    this.alignedTo = s.alignedTo ?? null
  }
}

/** A piece as an image for placing it on the official map. */
function pieceImage(p: MapPiece): PieceImage {
  const w = p.maxX - p.minX + 1
  const h = p.maxY - p.minY + 1
  const lum = new Uint8Array(w * h)
  const weight = new Uint8Array(w * h)
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const o = (p.minY + y) * CANVAS + p.minX + x
      weight[y * w + x] = p.weight[o]
      lum[y * w + x] = Math.round(Math.max(0, Math.min(1, p.mean[o])) * 255)
    }
  return { x0: p.minX, y0: p.minY, w, h, lum, weight }
}

/** One window as an image, its top-left at (x0, y0) on its piece. */
function windowImage(w: MinimapWindow, x0: number, y0: number): PieceImage {
  const lum = new Uint8Array(MM * MM)
  for (let i = 0; i < MM * MM; i++) lum[i] = Math.round(Math.max(0, Math.min(1, w.lum[i])) * 255)
  return { x0, y0, w: MM, h: MM, lum, weight: w.mask }
}

// --- Encoding ----------------------------------------------------------------------------------

export function toBase64(bytes: Uint8Array): string {
  let s = ''
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return btoa(s)
}

export function fromBase64(b64: string): Uint8Array {
  const s = atob(b64)
  const out = new Uint8Array(s.length)
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i)
  return out
}

/** Checks a stored mosaic's shape (else null). */
export function cleanMosaic(raw: unknown): StoredMosaic | null {
  if (!raw || typeof raw !== 'object') return null
  const s = raw as Partial<StoredMosaic>
  if (s.version !== 1 || typeof s.map !== 'string' || !Array.isArray(s.pieces) || typeof s.nextId !== 'number') return null
  const okInt = (v: unknown, max = CANVAS) => Number.isInteger(v) && (v as number) >= 0 && (v as number) <= max
  for (const p of s.pieces) {
    if (!p || !okInt(p.id, 1e6) || !okInt(p.x0) || !okInt(p.y0) || !okInt(p.w) || !okInt(p.h) || typeof p.mean !== 'string' || typeof p.weight !== 'string') return null
    if (p.mean.length > ((p.w * p.h + 2) / 3) * 4 + 4) return null
  }
  return {
    version: 1,
    map: s.map.slice(0, 80),
    updatedAt: typeof s.updatedAt === 'string' ? s.updatedAt : new Date(0).toISOString(),
    pieces: s.pieces,
    joined: s.joined && typeof s.joined === 'object' ? s.joined : {},
    nextId: s.nextId,
    callouts: s.callouts && typeof s.callouts === 'object' ? s.callouts : {},
    heights: s.heights && typeof s.heights === 'object' ? s.heights : {},
    ...cleanAligned(s),
  }
}

function cleanAligned(s: Partial<StoredMosaic>): Pick<StoredMosaic, 'aligned' | 'alignedTo'> {
  const fin = (v: unknown) => typeof v === 'number' && Number.isFinite(v)
  const aligned: Record<string, Alignment> = {}
  for (const [id, a] of Object.entries(s.aligned && typeof s.aligned === 'object' ? s.aligned : {})) {
    if (!/^\d{1,6}$/.test(id) || !a || typeof a !== 'object') continue
    const o = a as unknown as Record<string, unknown>
    if (![o.s, o.tx, o.ty, o.ncc, o.coverage].every(fin) || (o.s as number) <= 0) continue
    aligned[id] = { s: o.s as number, tx: o.tx as number, ty: o.ty as number, ncc: o.ncc as number, coverage: o.coverage as number, ...(fin(o.margin) ? { margin: o.margin as number } : {}), ...(o.manual === true ? { manual: true } : {}) }
  }
  const to = s.alignedTo
  const alignedTo = to && fin(to.w) && fin(to.h) && to.w > 0 && to.h > 0 ? { w: to.w, h: to.h } : undefined
  return Object.keys(aligned).length ? { aligned, ...(alignedTo ? { alignedTo } : {}) } : {}
}
