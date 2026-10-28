// The official overhead map (a game's own tactical map image) and how the
// player's stitched minimap lines up on it.
//
// Analysis: the playable area (inside the bright boundary line, found by
// flooding in from the image's edges), and inside it the walls and outlines
// (bright cool lines), indoor floors (the lighter fills), solid structures
// (the warm hatching) and open ground. From that the overhead view dims what
// is out of bounds and the 3D view raises walls and solid structures.
//
// Alignment: the stitched minimap and the official map are the same place
// drawn at different scales. The best scale and position are found by
// normalised cross-correlation, coarse to fine: every plausible scale on a
// small copy of both, then the strongest candidates refined at each level.
// The result maps stitched-map pixels to official-image pixels (X = s·x + tx,
// Y = s·y + ty), with its match score and how much of it falls in bounds.
// Once one piece of a map is aligned the scale is known, and other pieces
// only need a position.
//
// Ambiguity is refused rather than guessed: a placement must beat the best
// one elsewhere by a clear margin (two identical wings tie), and a single
// window placed on its own is only shown once the next frames bear it out.
// While playing, each frame is pinned down on the official map near where
// its placement predicts (the stitched map drifts a little over a long walk;
// the official map does not).

import { MM } from './minimap.ts'

export const KIND_OUT = 0
export const KIND_FLOOR = 1
export const KIND_INDOOR = 2
export const KIND_WALL = 3
export const KIND_SOLID = 4

interface Level {
  readonly w: number
  readonly h: number
  readonly v: Float32Array
}

export interface OfficialMap {
  /** A fingerprint of the picture (placements made on another picture do not carry over). */
  readonly id: string
  /** The image as given. */
  readonly srcW: number
  readonly srcH: number
  /** Working size, and working pixels per image pixel. */
  readonly w: number
  readonly h: number
  readonly k: number
  /** Luminance 0..1 at working size. */
  readonly lum: Float32Array
  /** What each working pixel is (KIND_*). */
  readonly kind: Uint8Array
  /** The playable area's bounding box, working pixels. */
  readonly box: { readonly x0: number; readonly y0: number; readonly x1: number; readonly y1: number }
  /** Share of the image in bounds. */
  readonly playable: number
  /** How much this looks like a tactical map (0..1): a bounded area with line work on a dark surround. */
  readonly score: number
  /** Luminance at working size, /2, /4, /8 (for alignment). */
  readonly levels: readonly Level[]
}

/** Analyses an official overhead image (RGBA bytes). */
export function analyzeOfficial(rgba: Uint8ClampedArray | Uint8Array, srcW: number, srcH: number, maxSide = 1024): OfficialMap {
  const k = Math.min(1, maxSide / Math.max(srcW, srcH))
  const w = Math.max(1, Math.round(srcW * k))
  const h = Math.max(1, Math.round(srcH * k))
  const R = new Float32Array(w * h)
  const G = new Float32Array(w * h)
  const B = new Float32Array(w * h)
  for (let y = 0; y < h; y++) {
    const sy0 = Math.floor(y / k)
    const sy1 = Math.max(sy0 + 1, Math.min(srcH, Math.floor((y + 1) / k)))
    for (let x = 0; x < w; x++) {
      const sx0 = Math.floor(x / k)
      const sx1 = Math.max(sx0 + 1, Math.min(srcW, Math.floor((x + 1) / k)))
      let r = 0
      let g = 0
      let b = 0
      let n = 0
      for (let sy = sy0; sy < sy1; sy++) {
        let o = (sy * srcW + sx0) * 4
        for (let sx = sx0; sx < sx1; sx++, o += 4) {
          r += rgba[o]
          g += rgba[o + 1]
          b += rgba[o + 2]
          n++
        }
      }
      const i = y * w + x
      R[i] = r / n
      G[i] = g / n
      B[i] = b / n
    }
  }
  const lum = new Float32Array(w * h)
  const line = new Uint8Array(w * h)
  const warm = new Uint8Array(w * h)
  for (let i = 0; i < w * h; i++) {
    lum[i] = (0.299 * R[i] + 0.587 * G[i] + 0.114 * B[i]) / 255
    if (lum[i] >= 0.47 && B[i] >= R[i] * 0.85) line[i] = 1
    if (R[i] > B[i] * 1.12 && R[i] >= 45) warm[i] = 1
  }
  // The outside: flooded in from the edges, stopped by the (thickened) lines. A boundary with a
  // gap would let the flood in, so the lines are thickened until the result is plausible.
  // A leak shows as an "inside" that is mostly the lines themselves.
  let outside: Uint8Array = new Uint8Array(w * h)
  let inFrac = 0
  let bounded = false
  for (const d of [1, 2, 3, 4, 5, 6]) {
    const barrier = dilate(line, w, h, d)
    outside = flood(barrier, w, h)
    let inside = 0
    let thick = 0
    for (let i = 0; i < w * h; i++)
      if (!outside[i]) {
        inside++
        if (barrier[i]) thick++
      }
    inFrac = inside / (w * h)
    if (inFrac >= 0.03 && inFrac <= 0.92 && thick / inside <= 0.45) {
      bounded = true
      break
    }
  }
  // Else by colour: out of bounds is drawn warm (orange) or near black; the playable area is cool blue-grey.
  if (!bounded) {
    const cool = new Uint8Array(w * h)
    for (let i = 0; i < w * h; i++) cool[i] = line[i] || (B[i] > R[i] * 1.25 && B[i] >= 30) ? 1 : 0
    for (const d of [1, 2, 3]) {
      const barrier = dilate(cool, w, h, d)
      outside = flood(barrier, w, h)
      let inside = 0
      let thick = 0
      for (let i = 0; i < w * h; i++)
        if (!outside[i]) {
          inside++
          if (line[i]) thick++
        }
      inFrac = inside / (w * h)
      if (inFrac >= 0.03 && inFrac <= 0.92 && thick / inside <= 0.45) {
        bounded = true
        break
      }
    }
  }
  if (!bounded) outside.fill(0)
  else outside = keepLargest(outside, w, h)
  // Inside: walls, solid (hatched) structures, indoor floors, open ground.
  const warmSum = boxSum(warm, w, h, 2)
  const kind = new Uint8Array(w * h)
  let x0 = w
  let y0 = h
  let x1 = -1
  let y1 = -1
  let lines = 0
  let inside = 0
  let darkOut = 0
  let warmOut = 0
  let litIn = 0
  let outN = 0
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x
      if (outside[i]) {
        darkOut += lum[i]
        if (warm[i]) warmOut++
        outN++
        continue
      }
      litIn += lum[i]
      inside++
      if (x < x0) x0 = x
      if (x > x1) x1 = x
      if (y < y0) y0 = y
      if (y > y1) y1 = y
      if (line[i]) {
        kind[i] = KIND_WALL
        lines++
      } else if (warmSum[i] >= 7) kind[i] = KIND_SOLID
      else kind[i] = lum[i] >= 0.15 ? KIND_INDOOR : KIND_FLOOR
    }
  }
  const lineFrac = inside ? lines / inside : 0
  // Out of bounds is drawn darker than the playable area, or in warm texture.
  const surround = outN && (darkOut / outN < (inside ? litIn / inside : 1) + 0.05 || warmOut / outN > 0.25) ? 1 : 0
  const score = ((bounded && inFrac >= 0.05 && inFrac <= 0.85 ? 1 : 0) + Math.min(1, lineFrac * 10) + surround) / 3
  const levels: Level[] = [{ w, h, v: lum }]
  for (let l = 1; l <= 3; l++) levels.push(half(levels[l - 1]))
  return {
    id: fingerprint(srcW, srcH, levels[3]),
    srcW,
    srcH,
    w,
    h,
    k,
    lum,
    kind,
    box: x1 >= 0 ? { x0, y0, x1, y1 } : { x0: 0, y0: 0, x1: w - 1, y1: h - 1 },
    playable: inside / (w * h),
    score,
    levels,
  }
}

/** FNV-1a over the image's size and its smallest level, coarsely quantised (the same file gives the same id). */
function fingerprint(srcW: number, srcH: number, lv: Level): string {
  let h = 0x811c9dc5
  const add = (v: number) => {
    h ^= v & 0xff
    h = Math.imul(h, 0x01000193) >>> 0
  }
  for (const v of [srcW, srcW >> 8, srcH, srcH >> 8]) add(v)
  for (const v of [lv.w, lv.h]) add(v)
  for (let i = 0; i < lv.v.length; i++) add(Math.round(lv.v[i] * 15))
  return h.toString(16).padStart(8, '0')
}

function dilate(m: Uint8Array, w: number, h: number, r: number): Uint8Array {
  const s = boxSum(m, w, h, r)
  const out = new Uint8Array(w * h)
  for (let i = 0; i < w * h; i++) out[i] = s[i] > 0 ? 1 : 0
  return out
}

/** Sums over a (2r+1)² box, clipped at the edges. */
function boxSum(m: Uint8Array, w: number, h: number, r: number): Uint16Array {
  const rows = new Uint16Array(w * h)
  for (let y = 0; y < h; y++) {
    let acc = 0
    for (let x = -r; x < w + r; x++) {
      if (x + r < w) acc += m[y * w + x + r]
      if (x - r - 1 >= 0) acc -= m[y * w + x - r - 1]
      if (x >= 0 && x < w) rows[y * w + x] = acc
    }
  }
  const out = new Uint16Array(w * h)
  for (let x = 0; x < w; x++) {
    let acc = 0
    for (let y = -r; y < h + r; y++) {
      if (y + r < h) acc += rows[(y + r) * w + x]
      if (y - r - 1 >= 0) acc -= rows[(y - r - 1) * w + x]
      if (y >= 0 && y < h) out[y * w + x] = acc
    }
  }
  return out
}

/** Pixels reachable from the image's edges without crossing the barrier. */
function flood(barrier: Uint8Array, w: number, h: number): Uint8Array {
  const out = new Uint8Array(w * h)
  const stack: number[] = []
  const push = (i: number) => {
    if (!out[i] && !barrier[i]) {
      out[i] = 1
      stack.push(i)
    }
  }
  for (let x = 0; x < w; x++) {
    push(x)
    push((h - 1) * w + x)
  }
  for (let y = 0; y < h; y++) {
    push(y * w)
    push(y * w + w - 1)
  }
  while (stack.length) {
    const i = stack.pop()!
    const x = i % w
    const y = (i / w) | 0
    if (x > 0) push(i - 1)
    if (x < w - 1) push(i + 1)
    if (y > 0) push(i - w)
    if (y < h - 1) push(i + w)
  }
  return out
}

/** Only the largest connected in-bounds region stays in bounds (stray specks outside do not). */
function keepLargest(outside: Uint8Array, w: number, h: number): Uint8Array {
  const label = new Int32Array(w * h)
  let best = 0
  let bestSize = 0
  let next = 0
  const stack: number[] = []
  for (let i = 0; i < w * h; i++) {
    if (outside[i] || label[i]) continue
    next++
    let size = 0
    label[i] = next
    stack.push(i)
    while (stack.length) {
      const q = stack.pop()!
      size++
      const x = q % w
      const y = (q / w) | 0
      for (const n of [x > 0 ? q - 1 : -1, x < w - 1 ? q + 1 : -1, y > 0 ? q - w : -1, y < h - 1 ? q + w : -1]) {
        if (n < 0 || outside[n] || label[n]) continue
        label[n] = next
        stack.push(n)
      }
    }
    if (size > bestSize) {
      bestSize = size
      best = next
    }
  }
  const out = new Uint8Array(w * h)
  for (let i = 0; i < w * h; i++) out[i] = label[i] === best ? 0 : 1
  return out
}

function half(l: Level): Level {
  const w = Math.max(1, l.w >> 1)
  const h = Math.max(1, l.h >> 1)
  const v = new Float32Array(w * h)
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const i = 2 * y * l.w + 2 * x
      v[y * w + x] = (l.v[i] + l.v[Math.min(i + 1, l.v.length - 1)] + l.v[Math.min(i + l.w, l.v.length - 1)] + l.v[Math.min(i + l.w + 1, l.v.length - 1)]) / 4
    }
  return { w, h, v }
}

// --- Alignment ------------------------------------------------------------------------------------

/** Stitched-map pixels → official-image pixels: X = s·x + tx, Y = s·y + ty. */
export interface Alignment {
  readonly s: number
  readonly tx: number
  readonly ty: number
  /** How well the stitched map matches the official one there (normalised cross-correlation, −1..1). */
  readonly ncc: number
  /** Share of the stitched map that lands on the official image. */
  readonly coverage: number
  /** How much better than the best match somewhere else (a symmetric map can match twice). */
  readonly margin?: number
  /** Set by the player (moved or scaled by hand). */
  readonly manual?: boolean
}

/** A piece of stitched map: luminance 0..255 and where it was seen, at its place on the canvas. */
export interface PieceImage {
  readonly x0: number
  readonly y0: number
  readonly w: number
  readonly h: number
  readonly lum: Uint8Array
  readonly weight: Uint8Array
}

/** A match good enough to draw on (else the stitched map is shown on its own). */
export const ALIGN_MIN = 0.55
/**
 * How much better than anywhere else it must be. Two identical wings score alike, and what tells
 * them apart by chance (a few pixels of the boundary at the edge of what was seen) stays under this.
 */
export const ALIGN_MARGIN = 0.06

interface Template {
  readonly w: number
  readonly h: number
  /** Offsets into a row-major image of width `stride`, and values. */
  readonly idx: Int32Array
  readonly val: Float32Array
  readonly sx: Int16Array
  readonly sy: Int16Array
}

/** The piece drawn at `scale` (target pixels per piece pixel), valid pixels only, every `step` pixels (or enough to stay within `budget` points). */
export function template(p: PieceImage, scale: number, stride: number, step = 1, budget = Infinity): Template {
  const w = Math.max(1, Math.round(p.w * scale))
  const h = Math.max(1, Math.round(p.h * scale))
  if (Number.isFinite(budget)) step = Math.max(step, Math.ceil(Math.sqrt((w * h * seenShare(p)) / budget)))
  const idx: number[] = []
  const val: number[] = []
  const sx: number[] = []
  const sy: number[] = []
  // Each target pixel takes the mean of the piece pixels it covers (or the nearest, when enlarging).
  const r = Math.max(0, Math.floor(0.5 / scale - 0.5))
  for (let y = 0; y < h; y += step) {
    const cy = Math.min(p.h - 1, Math.floor((y + 0.5) / scale))
    for (let x = 0; x < w; x += step) {
      const cx = Math.min(p.w - 1, Math.floor((x + 0.5) / scale))
      let s = 0
      let n = 0
      let seen = 0
      let all = 0
      for (let dy = -r; dy <= r; dy++)
        for (let dx = -r; dx <= r; dx++) {
          const yy = cy + dy
          const xx = cx + dx
          if (yy < 0 || xx < 0 || yy >= p.h || xx >= p.w) continue
          all++
          const i = yy * p.w + xx
          if (!p.weight[i]) continue
          seen++
          s += p.lum[i]
          n++
        }
      if (!n || seen < all * 0.6) continue
      idx.push(y * stride + x)
      val.push(s / n / 255)
      sx.push(x)
      sy.push(y)
    }
  }
  return { w, h, idx: Int32Array.from(idx), val: Float32Array.from(val), sx: Int16Array.from(sx), sy: Int16Array.from(sy) }
}

function seenShare(p: PieceImage): number {
  let n = 0
  for (let i = 0; i < p.weight.length; i += 7) if (p.weight[i]) n++
  return Math.max(0.05, n / Math.ceil(p.weight.length / 7))
}

/**
 * NCC of a template placed at (ox, oy) on a level, over the part that lands on the image
 * (the minimap can show surroundings the official image does not): at least 70% must.
 */
export function nccAt(t: Template, lv: Level, ox: number, oy: number): number {
  const total = t.idx.length
  if (total < 30) return -1
  const inside = ox >= 0 && oy >= 0 && ox + t.w <= lv.w && oy + t.h <= lv.h
  let n = 0
  let sa = 0
  let sb = 0
  let saa = 0
  let sbb = 0
  let sab = 0
  const v = lv.v
  if (inside) {
    const base = oy * lv.w + ox
    for (let i = 0; i < total; i++) {
      const a = t.val[i]
      const b = v[base + t.idx[i]]
      sa += a
      sb += b
      saa += a * a
      sbb += b * b
      sab += a * b
    }
    n = total
  } else {
    for (let i = 0; i < total; i++) {
      const X = ox + t.sx[i]
      const Y = oy + t.sy[i]
      if (X < 0 || Y < 0 || X >= lv.w || Y >= lv.h) continue
      const a = t.val[i]
      const b = v[Y * lv.w + X]
      n++
      sa += a
      sb += b
      saa += a * a
      sbb += b * b
      sab += a * b
    }
    if (n < total * 0.7) return -1
  }
  const va = saa - (sa * sa) / n
  const vb = sbb - (sb * sb) / n
  if (va <= 1e-9 || vb <= 1e-9) return -1
  return (sab - (sa * sb) / n) / Math.sqrt(va * vb)
}

/**
 * The best placement of a template on a level, every `step` pixels (it may hang over each edge by
 * up to 30% of its size), and the best one clearly elsewhere: on a map with two identical wings
 * that one scores as well, and the placement is not to be trusted.
 */
function bestPlacement(t: Template, lv: Level, step: number): { best: Placement; rival: Placement | null } | null {
  const mx = Math.floor(t.w * 0.3)
  const my = Math.floor(t.h * 0.3)
  const found: Placement[] = []
  for (let oy = -my; oy + t.h - my <= lv.h; oy += step)
    for (let ox = -mx; ox + t.w - mx <= lv.w; ox += step) {
      const c = nccAt(t, lv, ox, oy)
      if (c > -1) found.push({ ox, oy, ncc: c })
    }
  if (!found.length) return null
  let best = found[0]
  for (const f of found) if (f.ncc > best.ncc) best = f
  // Clearly elsewhere: a sixth of the template away (a window holding little but a straight wall
  // matches all along it, and that is no placement either).
  const radius = Math.max(3, 0.15 * Math.min(t.w, t.h))
  let rival: Placement | null = null
  for (const f of found) if ((!rival || f.ncc > rival.ncc) && Math.hypot(f.ox - best.ox, f.oy - best.oy) > radius) rival = f
  return { best, rival }
}

interface Placement {
  ox: number
  oy: number
  ncc: number
}

interface Candidate {
  s: number
  ox: number
  oy: number
  ncc: number
}

/**
 * Aligns a piece of stitched map on the official map, a step at a time (it
 * yields between scales so a caller can keep a page responsive). `scale`:
 * the scale already found for this map (official-image pixels per stitched
 * pixel), when known.
 */
export function* alignSteps(p: PieceImage, off: OfficialMap, opts: { readonly scale?: number } = {}): Generator<void, Alignment | null> {
  if (p.w < 8 || p.h < 8) return null
  const PW = off.box.x1 - off.box.x0 + 1
  // Working pixels per stitched pixel: a minimap window shows from an eighth to most of the map's width.
  const known = opts.scale ? opts.scale * off.k : null
  const sMin = known ? known / 1.04 : (0.12 * PW) / MM
  const sMax = known ? known * 1.04 : (0.8 * PW) / MM
  const ratio = known ? 1.01 : 1.045
  const scales: number[] = []
  for (let s = sMin; s <= sMax * 1.0001; s *= ratio) scales.push(s)
  // The coarsest level about 128 px across.
  const L = Math.max(0, Math.min(3, Math.round(Math.log2(Math.max(off.w, off.h) / 128))))
  const lv = off.levels[L]
  const f = 2 ** L
  const top: Candidate[] = []
  // Every scale's best and its best rival elsewhere, for the margin.
  const coarse: Candidate[] = []
  const keep = (c: Candidate, n: number) => {
    top.push(c)
    top.sort((a, b) => b.ncc - a.ncc)
    // Distinct candidates only.
    for (let i = top.length - 1; i > 0; i--)
      if (top.slice(0, i).some((q) => Math.abs(q.s / top[i].s - 1) < 0.02 && Math.hypot(q.ox - top[i].ox, q.oy - top[i].oy) < 3)) top.splice(i, 1)
    if (top.length > n) top.length = n
  }
  for (const s of scales) {
    // A sparse template is enough to find the right neighbourhood.
    const t = template(p, s / f, lv.w, 1, 700)
    if (t.w > lv.w * 1.3 || t.h > lv.h * 1.3 || t.idx.length < 30) {
      yield
      continue
    }
    const b = bestPlacement(t, lv, 1)
    if (b) {
      coarse.push({ s, ...b.best })
      keep({ s, ...b.best }, 8)
      if (b.rival) {
        coarse.push({ s, ...b.rival })
        keep({ s, ...b.rival }, 8)
      }
    }
    yield
  }
  if (!top.length) return null
  // Refine each candidate level by level: scale ±, position ±2 px at that level.
  let cands = top.map((c) => ({ ...c, ox: c.ox, oy: c.oy }))
  for (let l = L - 1; l >= 0; l--) {
    const lvl = off.levels[l]
    const fl = 2 ** l
    cands = cands.map((c) => {
      let best = { s: c.s, ox: c.ox * 2, oy: c.oy * 2, ncc: -2 }
      for (const ds of [1 / ratio ** 0.5, 1, ratio ** 0.5]) {
        const s = c.s * ds
        const t = template(p, s / fl, lvl.w, 1, 25_000)
        for (let dy = -3; dy <= 3; dy++)
          for (let dx = -3; dx <= 3; dx++) {
            const ox = c.ox * 2 + dx
            const oy = c.oy * 2 + dy
            const v = nccAt(t, lvl, ox, oy)
            if (v > best.ncc) best = { s, ox, oy, ncc: v }
          }
      }
      return best
    })
    cands.sort((a, b) => b.ncc - a.ncc)
    cands = cands.slice(0, Math.max(2, cands.length >> 1))
    yield
  }
  const b = cands[0]
  if (!b || b.ncc < -1) return null
  // The margin, compared on the coarse level where every candidate was scored alike: the winner's
  // coarse candidate against the best one somewhere else (another place or another scale).
  const bx = b.ox / f
  const by = b.oy / f
  const near = (c: Candidate) => Math.abs(c.s / b.s - 1) <= 0.05 && Math.hypot(c.ox - bx, c.oy - by) <= Math.max(3, 0.15 * Math.min(p.w, p.h) * (b.s / f))
  const own = coarse.filter(near).reduce((m, c) => Math.max(m, c.ncc), -1)
  const rival = coarse.filter((c) => !near(c)).reduce((m, c) => Math.max(m, c.ncc), -1)
  const margin = own > -1 ? own - rival : 0
  // Share of the piece that lands on the image.
  const t = template(p, b.s, off.w, 1, 25_000)
  let inb = 0
  for (let i = 0; i < t.idx.length; i++) {
    const X = b.ox + t.sx[i]
    const Y = b.oy + t.sy[i]
    if (X >= 0 && Y >= 0 && X < off.w && Y < off.h) inb++
  }
  // Working pixels → image pixels; the template's origin is the piece's corner on the canvas.
  const s = b.s / off.k
  return { s, tx: b.ox / off.k - s * p.x0, ty: b.oy / off.k - s * p.y0, ncc: matchedNcc(p, off, b), coverage: t.idx.length ? inb / t.idx.length : 0, margin }
}

/**
 * How well a placed piece matches, judged where the official map is no sharper than the minimap:
 * finer detail (hatching a pixel or two wide, thin lines) is not on the minimap at all and would
 * only count against a right placement.
 */
function matchedNcc(p: PieceImage, off: OfficialMap, b: Candidate): number {
  const l = Math.max(0, Math.min(off.levels.length - 1, Math.ceil(Math.log2(Math.max(1, b.s)))))
  if (!l) return b.ncc
  const f = 2 ** l
  const t = template(p, b.s / f, off.levels[l].w, 1, 25_000)
  return Math.max(b.ncc, nccAt(t, off.levels[l], Math.round(b.ox / f), Math.round(b.oy / f)))
}

/** Aligns a piece in one go (see alignSteps). */
export function alignPiece(p: PieceImage, off: OfficialMap, opts: { readonly scale?: number } = {}): Alignment | null {
  const it = alignSteps(p, off, opts)
  for (;;) {
    const r = it.next()
    if (r.done) return r.value
  }
}

/** Aligns without blocking a page: a step, then a pause. */
export async function alignPieceAsync(p: PieceImage, off: OfficialMap, opts: { readonly scale?: number } = {}): Promise<Alignment | null> {
  const it = alignSteps(p, off, opts)
  for (;;) {
    const r = it.next()
    if (r.done) return r.value
    await new Promise((res) => setTimeout(res, 0))
  }
}

/** A stitched-map point on the official image. */
export function toOfficial(a: Alignment, x: number, y: number): { x: number; y: number } {
  return { x: a.s * x + a.tx, y: a.s * y + a.ty }
}

/** Is an alignment good enough to draw on? */
export function usable(a: Alignment | null | undefined): a is Alignment {
  return !!a && (a.manual === true || (a.ncc >= ALIGN_MIN && a.coverage >= 0.7 && (a.margin ?? 1) >= ALIGN_MARGIN))
}

/**
 * Where a 3×3 grid cell of the pack's reference (laid over the playable
 * area's bounding box, north up) lies on the image, in image pixels.
 */
export function gridCell(off: OfficialMap, cell: string): { x: number; y: number } | null {
  const c = { nw: [0, 0], n: [1, 0], ne: [2, 0], w: [0, 1], c: [1, 1], e: [2, 1], sw: [0, 2], s: [1, 2], se: [2, 2] }[cell]
  if (!c) return null
  const b = off.box
  return { x: (b.x0 + ((c[0] + 0.5) / 3) * (b.x1 - b.x0)) / off.k, y: (b.y0 + ((c[1] + 0.5) / 3) * (b.y1 - b.y0)) / off.k }
}

/**
 * Finds a window (or a small piece) on the official map at a known scale
 * (official-image pixels per stitched pixel): the image position of its
 * top-left corner, its match, and whether that is the only good place for
 * it (on a map with two identical wings it is not, and the caller waits).
 */
export function locateWindow(p: PieceImage, off: OfficialMap, scale: number): { x: number; y: number; ncc: number; margin: number; unique: boolean } | null {
  const sW = scale * off.k
  // A level where the window is 32–48 pixels across.
  const L = Math.max(0, Math.min(3, Math.floor(Math.log2((p.w * sW) / 32))))
  const lv = off.levels[L]
  const f = 2 ** L
  const t = template(p, sW / f, lv.w, 1, 900)
  if (t.idx.length < 30) return null
  const mx = Math.floor(t.w * 0.3)
  const my = Math.floor(t.h * 0.3)
  const cols = lv.w - t.w + 2 * mx + 1
  const rows = lv.h - t.h + 2 * my + 1
  if (cols <= 0 || rows <= 0) return null
  const scores = new Float32Array(cols * rows)
  let bi = -1
  for (let r = 0; r < rows; r++)
    for (let c = 0; c < cols; c++) {
      const v = nccAt(t, lv, c - mx, r - my)
      scores[r * cols + c] = v
      if (bi < 0 || v > scores[bi]) bi = r * cols + c
    }
  const bc = bi % cols
  const br = (bi / cols) | 0
  const radius = Math.max(3, 0.15 * Math.min(t.w, t.h))
  let rival = -1
  for (let r = 0; r < rows; r++)
    for (let c = 0; c < cols; c++) if (Math.hypot(c - bc, r - br) > radius && scores[r * cols + c] > rival) rival = scores[r * cols + c]
  const coarse = scores[bi]
  // Refine down to the working level.
  let ox = bc - mx
  let oy = br - my
  let ncc = coarse
  for (let l = L - 1; l >= 0; l--) {
    const lvl = off.levels[l]
    const tt = template(p, sW / 2 ** l, lvl.w, 1, 6000)
    let best = { ox: ox * 2, oy: oy * 2, ncc: -2 }
    for (let dy = -2; dy <= 2; dy++)
      for (let dx = -2; dx <= 2; dx++) {
        const v = nccAt(tt, lvl, ox * 2 + dx, oy * 2 + dy)
        if (v > best.ncc) best = { ox: ox * 2 + dx, oy: oy * 2 + dy, ncc: v }
      }
    ;({ ox, oy, ncc } = best)
  }
  const margin = coarse - rival
  return { x: ox / off.k, y: oy / off.k, ncc, margin, unique: coarse >= 0.45 && margin >= 0.06 }
}

/**
 * Pins a window down on the official map near where its placement predicts
 * (image px of its top-left, within `radius` image px): the stitched map
 * drifts a little over a long walk, the official map does not. Null when the
 * window does not match there clearly (the peak on the edge of the search,
 * or too weak a match).
 */
export function anchorWindow(p: PieceImage, off: OfficialMap, scale: number, X: number, Y: number, radius: number): { x: number; y: number; ncc: number } | null {
  const lv = off.levels[0]
  const t = template(p, scale * off.k, lv.w, 1, 1200)
  if (t.idx.length < 30) return null
  const R = Math.max(2, Math.ceil(radius * off.k))
  const cx = Math.round(X * off.k)
  const cy = Math.round(Y * off.k)
  const side = 2 * R + 1
  const v = new Float32Array(side * side).fill(NaN)
  const at = (dx: number, dy: number) => {
    const i = (dy + R) * side + dx + R
    if (Number.isNaN(v[i])) v[i] = nccAt(t, lv, cx + dx, cy + dy)
    return v[i]
  }
  // Every other pixel, then to the pixel around the best.
  let bx = 0
  let by = 0
  for (let dy = -R; dy <= R; dy += 2) for (let dx = -R; dx <= R; dx += 2) if (at(dx, dy) > at(bx, by)) [bx, by] = [dx, dy]
  const [gx, gy] = [bx, by]
  for (let dy = gy - 1; dy <= gy + 1; dy++)
    for (let dx = gx - 1; dx <= gx + 1; dx++) if (Math.abs(dx) <= R && Math.abs(dy) <= R && at(dx, dy) > at(bx, by)) [bx, by] = [dx, dy]
  const best = at(bx, by)
  if (Math.abs(bx) >= R - 1 || Math.abs(by) >= R - 1 || best < ANCHOR_MIN) return null
  // To a fraction of a pixel: a parabola through the peak and its neighbours.
  const sub = (a: number, b: number, c: number) => (a - 2 * b + c < 0 ? Math.max(-0.5, Math.min(0.5, (a - c) / (2 * (a - 2 * b + c)))) : 0)
  const fx = bx + sub(at(bx - 1, by), best, at(bx + 1, by))
  const fy = by + sub(at(bx, by - 1), best, at(bx, by + 1))
  return { x: (cx + fx) / off.k, y: (cy + fy) / off.k, ncc: best }
}

/** The weakest match a window is pinned down with (see anchorWindow). */
export const ANCHOR_MIN = 0.5
