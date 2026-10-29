// Reading the HUD's numbers on this computer. A game draws its resource,
// population and clock numbers in one font at fixed places, so once the shape
// of each digit is known they can be read from the pixels, every second, for
// free. The shapes are learned from the player's own screen: each time the
// cloud model reads a number, the digits in that box are labelled with it.
// Nothing here is trusted until it agrees with the model (src/core/hud/reader.ts).
//
// A number's box is cut into glyphs by the gaps between columns of ink, and
// each glyph is scaled by the height of the line (so a comma stays small and
// low) into a 12 × 12 grid of ink coverage.

import type { Frame, NormRect } from '../types.ts'

export const GRID = 12
const CELLS = GRID * GRID

export interface Glyph {
  /** Ink coverage, GRID × GRID, 0..1, row by row. */
  readonly v: Float32Array
  /** Width over line height (a "1" is narrow). */
  readonly aspect: number
}

/** The glyphs of one number box, left to right; null when the box holds nothing readable. */
export function segment(frame: Frame, box: NormRect): Glyph[] | null {
  const x0 = Math.max(0, Math.floor(box.x * frame.width))
  const y0 = Math.max(0, Math.floor(box.y * frame.height))
  const x1 = Math.min(frame.width, Math.ceil((box.x + box.w) * frame.width))
  const y1 = Math.min(frame.height, Math.ceil((box.y + box.h) * frame.height))
  const w = x1 - x0
  const h = y1 - y0
  if (w < 4 || h < 5) return null
  const lum = new Float32Array(w * h)
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const i = ((y0 + y) * frame.width + x0 + x) * 4
      lum[y * w + x] = 0.299 * frame.data[i] + 0.587 * frame.data[i + 1] + 0.114 * frame.data[i + 2]
    }
  const t = otsu(lum)
  if (t === null) return null
  // The background is whatever fills the edge of the box.
  let bright = 0
  let edge = 0
  for (let x = 0; x < w; x++) for (const y of [0, h - 1]) (edge++, lum[y * w + x] > t && bright++)
  for (let y = 1; y < h - 1; y++) for (const x of [0, w - 1]) (edge++, lum[y * w + x] > t && bright++)
  const inkBright = bright < edge / 2
  // Ink strength 0..1, keeping the soft edges a game's text is drawn with: 0 at the background's
  // typical level, 1 at the ink's.
  const bgVals: number[] = []
  const inkVals: number[] = []
  for (const l of lum) (l > t === inkBright ? inkVals : bgVals).push(l)
  if (!inkVals.length || !bgVals.length) return null
  bgVals.sort((p, q) => p - q)
  inkVals.sort((p, q) => p - q)
  const bg = bgVals[Math.floor(bgVals.length / 2)]
  const fg = inkBright ? inkVals[Math.floor(inkVals.length * 0.9)] : inkVals[Math.floor(inkVals.length * 0.1)]
  if (Math.abs(fg - bg) < 30) return null
  const ink = new Float32Array(w * h)
  for (let i = 0; i < ink.length; i++) ink[i] = Math.min(1, Math.max(0, (lum[i] - bg) / (fg - bg)))

  // Glyphs are the connected shapes of strong ink (a leaning "/" stays apart from the digits it
  // overlaps; the faint edges where two glyphs nearly touch do not join them). Shapes stacked
  // over each other (the dots of ":", a digit broken by thin strokes) are one glyph.
  const label = new Int32Array(w * h).fill(-1)
  const comps: { x0: number; x1: number; y0: number; y1: number; n: number }[] = []
  for (let i = 0; i < w * h; i++) {
    if (ink[i] <= 0.5 || label[i] >= 0) continue
    const id = comps.length
    const c = { x0: w, x1: 0, y0: h, y1: 0, n: 0 }
    const stack = [i]
    label[i] = id
    while (stack.length) {
      const j = stack.pop()!
      const x = j % w
      const y = (j - x) / w
      c.x0 = Math.min(c.x0, x)
      c.x1 = Math.max(c.x1, x + 1)
      c.y0 = Math.min(c.y0, y)
      c.y1 = Math.max(c.y1, y + 1)
      c.n++
      for (let dy = -1; dy <= 1; dy++)
        for (let dx = -1; dx <= 1; dx++) {
          const xx = x + dx
          const yy = y + dy
          if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
          const k = yy * w + xx
          if (ink[k] > 0.5 && label[k] < 0) {
            label[k] = id
            stack.push(k)
          }
        }
    }
    comps.push(c)
  }
  // Specks of noise are dropped.
  const maxN = Math.max(0, ...comps.map((c) => c.n))
  let parts = comps.map((c, id) => ({ ...c, ids: [id] })).filter((c) => c.n >= Math.max(2, maxN * 0.04))
  if (!parts.length || parts.length > 24) return null
  parts.sort((p, q) => p.x0 + p.x1 - (q.x0 + q.x1))
  // Merge shapes that overlap most of the narrower one's width.
  const merged: typeof parts = []
  for (const c of parts) {
    const last = merged[merged.length - 1]
    const overlap = last ? Math.min(last.x1, c.x1) - Math.max(last.x0, c.x0) : 0
    if (last && overlap > 0.6 * Math.min(last.x1 - last.x0, c.x1 - c.x0)) {
      merged[merged.length - 1] = { x0: Math.min(last.x0, c.x0), x1: Math.max(last.x1, c.x1), y0: Math.min(last.y0, c.y0), y1: Math.max(last.y1, c.y1), n: last.n + c.n, ids: [...last.ids, ...c.ids] }
    } else merged.push(c)
  }
  parts = merged
  if (parts.length > 16) return null
  const lineTop = Math.min(...parts.map((c) => c.y0))
  const lineH = Math.max(...parts.map((c) => c.y1)) - lineTop
  if (lineH < 4) return null
  // Glyphs that still touch: a shape much wider than a typical full-height glyph is cut at its
  // faintest columns.
  const tall = parts
    .filter((c) => c.y1 - c.y0 > lineH * 0.6)
    .map((c) => c.x1 - c.x0)
    .sort((p, q) => p - q)
  const unit = tall.length >= 3 ? tall[Math.floor(tall.length / 2)] : 0
  type Piece = { x0: number; x1: number; ids: number[] }
  const pieces: Piece[] = parts.flatMap((c) => {
    const n = unit > 0 && c.x1 - c.x0 > unit * 1.6 ? Math.round((c.x1 - c.x0) / unit) : 1
    if (n < 2) return [{ x0: c.x0, x1: c.x1, ids: c.ids }]
    const profile = new Float32Array(w)
    for (let y = 0; y < h; y++) for (let x = c.x0; x < c.x1; x++) if (c.ids.includes(label[y * w + x])) profile[x] += ink[y * w + x]
    const cuts = [c.x0]
    for (let k = 1; k < n; k++) {
      const aim = c.x0 + ((c.x1 - c.x0) * k) / n
      let best = Math.round(aim)
      for (let x = Math.max(cuts[k - 1] + 1, Math.round(aim - unit * 0.35)); x <= Math.min(c.x1 - 1, Math.round(aim + unit * 0.35)); x++) if (profile[x] < profile[best]) best = x
      cuts.push(best)
    }
    cuts.push(c.x1)
    return cuts.slice(0, -1).map((x0, k) => ({ x0, x1: cuts[k + 1], ids: c.ids }))
  })
  // Each glyph keeps its own strong ink and the soft edge around it (not a neighbour's).
  const owner = (x: number, y: number, ids: number[]): boolean => {
    const l = label[y * w + x]
    if (l >= 0) return ids.includes(l)
    for (let dy = -1; dy <= 1; dy++)
      for (let dx = -1; dx <= 1; dx++) {
        const xx = x + dx
        const yy = y + dy
        if (xx >= 0 && yy >= 0 && xx < w && yy < h && ids.includes(label[yy * w + xx])) return true
      }
    return false
  }
  return pieces
    .map((p) => {
      const own = new Float32Array(w * h)
      for (let y = 0; y < h; y++) for (let x = Math.max(0, p.x0 - 1); x < Math.min(w, p.x1 + 1); x++) if (owner(x, y, p.ids)) own[y * w + x] = ink[y * w + x]
      return glyph(own, w, h, p.x0, p.x1, lineTop, lineH)
    })
    .filter((g): g is Glyph => g !== null)
}

/** A glyph's ink, scaled by the line's height into the grid (bilinear, so half-pixel shifts blur rather than jump). */
function glyph(ink: Float32Array, w: number, h: number, a: number, b: number, top: number, lineH: number): Glyph | null {
  let mass = 0
  for (let y = top; y < top + lineH && y < h; y++) for (let x = a; x < b; x++) mass += ink[y * w + x]
  if (mass < 1.5) return null
  const v = new Float32Array(CELLS)
  const px = lineH / GRID
  const left = (a + b) / 2 - (GRID / 2) * px
  const at = (x: number, y: number) => {
    const xi = Math.floor(x)
    const yi = Math.floor(y)
    const fx = x - xi
    const fy = y - yi
    const get = (xx: number, yy: number) => (xx >= a - 1 && xx <= b && xx >= 0 && xx < w && yy >= 0 && yy < h ? ink[yy * w + xx] : 0)
    return get(xi, yi) * (1 - fx) * (1 - fy) + get(xi + 1, yi) * fx * (1 - fy) + get(xi, yi + 1) * (1 - fx) * fy + get(xi + 1, yi + 1) * fx * fy
  }
  for (let gy = 0; gy < GRID; gy++)
    for (let gx = 0; gx < GRID; gx++) {
      let sum = 0
      for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) sum += at(left + (gx + (j + 0.5) / 3) * px - 0.5, top + (gy + (i + 0.5) / 3) * px - 0.5)
      v[gy * GRID + gx] = sum / 9
    }
  return { v, aspect: (b - a) / lineH }
}

/** Otsu's threshold, or null for a flat box (nothing drawn). */
function otsu(lum: Float32Array): number | null {
  const hist = new Float64Array(256)
  for (const l of lum) hist[Math.min(255, Math.max(0, Math.round(l)))]++
  let min = 255
  let max = 0
  for (let i = 0; i < 256; i++)
    if (hist[i]) {
      min = Math.min(min, i)
      max = Math.max(max, i)
    }
  if (max - min < 40) return null
  const total = lum.length
  let sum = 0
  for (let i = 0; i < 256; i++) sum += i * hist[i]
  let sumB = 0
  let wB = 0
  let best = -1
  let t = 0
  for (let i = 0; i < 256; i++) {
    wB += hist[i]
    if (!wB) continue
    const wF = total - wB
    if (!wF) break
    sumB += i * hist[i]
    const mB = sumB / wB
    const mF = (sum - sumB) / wF
    const between = wB * wF * (mB - mF) ** 2
    if (between > best) {
      best = between
      t = i
    }
  }
  return t + 0.5
}

/** How different two glyphs look (0 the same): the best match within a one-cell shift, so a glyph drawn a pixel off still matches. */
export function distance(a: Glyph, b: Glyph): number {
  let best = Infinity
  for (let dy = -1; dy <= 1; dy++)
    for (let dx = -1; dx <= 1; dx++) {
      let d = 0
      for (let y = 0; y < GRID; y++) {
        const yy = y + dy
        for (let x = 0; x < GRID; x++) {
          const xx = x + dx
          const bv = yy >= 0 && yy < GRID && xx >= 0 && xx < GRID ? b.v[yy * GRID + xx] : 0
          d += Math.abs(a.v[y * GRID + x] - bv)
        }
        if (d >= best * 24) break
      }
      best = Math.min(best, d / 24)
    }
  return best + Math.abs(a.aspect - b.aspect)
}

/** Samples kept for each character. */
const PER_CHAR = 8
/** A sample is used once the model has labelled that shape the same way this many times (one misread is not learned). */
export const CONFIRMATIONS = 2
/** Glyphs closer than this are the same shape (one sample, counted again). */
const SAME_SHAPE = 0.3
/** A glyph further than this from every sample is not read. */
export const MAX_DISTANCE = 0.65
/** The best character must beat the next by this much. */
export const MIN_MARGIN = 0.12

export interface GlyphBookData {
  readonly v: 1
  /** Character → samples, each GRID² coverage values (0..255), the aspect × 100, then how often it was labelled so. */
  readonly chars: Readonly<Record<string, readonly (readonly number[])[]>>
}

type Sample = Glyph & { seen: number }

/** A stored book: well formed and small (the shapes of at most 24 characters). */
export function isGlyphBookData(v: unknown): v is GlyphBookData {
  const o = v as GlyphBookData | null
  if (!o || o.v !== 1 || typeof o.chars !== 'object' || o.chars === null || Array.isArray(o.chars)) return false
  const entries = Object.entries(o.chars)
  return entries.length <= 24 && entries.every(([ch, list]) => ch.length === 1 && Array.isArray(list) && list.length <= 16 && list.every((x) => Array.isArray(x) && x.length === CELLS + 2 && x.every((n) => typeof n === 'number' && Number.isFinite(n))))
}

/** The digit shapes learned from one player's screen. */
export class GlyphBook {
  private readonly samples = new Map<string, Sample[]>()

  constructor(data?: GlyphBookData | null) {
    if (data?.v !== 1) return
    for (const [ch, list] of Object.entries(data.chars)) {
      if (ch.length !== 1 || !Array.isArray(list)) continue
      const ok = list
        .filter((x) => Array.isArray(x) && x.length === CELLS + 2 && x.every((n: unknown) => typeof n === "number"))
        .map((x) => ({ v: Float32Array.from(x.slice(0, CELLS), (n) => Math.min(1, Math.max(0, (n as number) / 255))), aspect: x[CELLS] / 100, seen: x[CELLS + 1] }))
      if (ok.length) this.samples.set(ch, ok.slice(-PER_CHAR))
    }
  }

  /** Characters with a confirmed shape. */
  get known(): string[] {
    return [...this.samples].filter(([, list]) => list.some((x) => x.seen >= CONFIRMATIONS)).map(([ch]) => ch).sort()
  }

  /**
   * Label a box's glyphs with what the model read there. Only when one of the ways of writing
   * the value has exactly as many characters as there are glyphs; true when it was used.
   */
  learn(glyphs: readonly Glyph[], renderings: readonly string[]): boolean {
    const text = renderings.find((r) => r.length === glyphs.length)
    if (!text) return false
    glyphs.forEach((g, i) => {
      const list = this.samples.get(text[i]) ?? []
      const near = list.find((x) => distance(x, g) < SAME_SHAPE)
      if (near) near.seen++
      else {
        list.push({ ...g, seen: 1 })
        // Keep the confirmed shapes first; drop the least seen.
        list.sort((p, q) => q.seen - p.seen)
        this.samples.set(text[i], list.slice(0, PER_CHAR))
      }
    })
    return true
  }

  /**
   * Read a box's glyphs, or null when any glyph is unknown or ambiguous, or when any character
   * in `needs` has no confirmed shape yet (a digit never seen would be read as the nearest known
   * one: a 9 as an 8).
   */
  read(glyphs: readonly Glyph[], needs = '0123456789'): string | null {
    if (!glyphs.length) return null
    const known = new Set(this.known)
    for (const ch of needs) if (!known.has(ch)) return null
    let out = ''
    for (const g of glyphs) {
      let best = Infinity
      let bestCh = ''
      let second = Infinity
      for (const [ch, list] of this.samples) {
        const confirmed = list.filter((x) => x.seen >= CONFIRMATIONS)
        if (!confirmed.length) continue
        const d = Math.min(...confirmed.map((x) => distance(x, g)))
        if (d < best) {
          second = best
          best = d
          bestCh = ch
        } else if (d < second) second = d
      }
      if (!bestCh || best > MAX_DISTANCE || second - best < MIN_MARGIN) return null
      out += bestCh
    }
    return out
  }

  toJSON(): GlyphBookData {
    const chars: Record<string, number[][]> = {}
    for (const [ch, list] of this.samples) chars[ch] = list.map((g) => [...Array.from(g.v, (x) => Math.round(x * 255)), Math.round(g.aspect * 100), g.seen])
    return { v: 1, chars }
  }
}

// --- How the HUD writes a value --------------------------------------------------------------

/** The ways a number may be drawn: "1200", "1,200", "1.200", "1 200". */
export function numberRenderings(n: number): string[] {
  const s = String(Math.round(Math.abs(n)))
  const grouped = (sep: string) => s.replace(/\B(?=(\d{3})+(?!\d))/g, sep)
  return [...new Set([s, grouped(','), grouped('.')])].map((x) => (n < 0 ? `-${x}` : x))
}

/** A clock in seconds as drawn: "4:05", "04:05", "1:02:03". */
export function clockRenderings(sec: number): string[] {
  const s = Math.max(0, Math.round(sec))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = String(s % 60).padStart(2, '0')
  if (h) return [`${h}:${String(m).padStart(2, '0')}:${ss}`]
  return [...new Set([`${m}:${ss}`, `${String(m).padStart(2, '0')}:${ss}`])]
}

/** Parse what was read back into a value. */
export function parseNumber(text: string): number | null {
  const t = text.replace(/[,. ]/g, '')
  return /^-?\d+$/.test(t) ? Number(t) : null
}

export function parseClock(text: string): number | null {
  const m = /^(?:(\d+):)?(\d{1,2}):(\d\d)$/.exec(text)
  if (!m) return null
  return (m[1] ? Number(m[1]) * 3600 : 0) + Number(m[2]) * 60 + Number(m[3])
}
