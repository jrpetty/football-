// Reading a football game's radar: the small pitch map that shows every
// player as a coloured dot and the ball. Only the screen's pixels are read.
//
// The radar box (aligned by the player, like the minimap) is searched for
// the pitch outline (long bright lines); inside it, compact blobs in a team
// colour are players (two dots that overlap are split by their size), and a
// small bright blob that survives an erosion (which wipes out the thin pitch
// lines) is the ball. Each dot is placed on the pitch in metres: x along the
// pitch's length as the radar draws it (0 = its left end), y across (0 = its
// top). Which team is which comes from the colours the player set in the
// game (user and opponent radar colours), or, when left at the default,
// from two colour groups the tracker tells apart over time.

import type { NormRect } from '../types.ts'

export const PITCH_LENGTH = 105
export const PITCH_WIDTH = 68

/** The colours a game lets the player pick for each team's dots, roughly as drawn. */
export const RADAR_COLOURS = {
  red: [225, 45, 45],
  blue: [45, 115, 235],
  green: [45, 200, 80],
  purple: [150, 70, 215],
  orange: [245, 145, 35],
  yellow: [240, 220, 40],
  white: [240, 240, 240],
} as const satisfies Record<string, readonly [number, number, number]>
export type RadarColour = keyof typeof RADAR_COLOURS

export interface RadarDot {
  /** Pitch metres. */
  readonly x: number
  readonly y: number
  /** A team label: the player's ("ours") and the opponent's ("theirs") when the colours are known, else colour group "A" or "B". */
  readonly team: 'ours' | 'theirs' | 'A' | 'B'
  readonly rgb: readonly [number, number, number]
}

/** The ball, pitch metres, and the team whose colour is right round it (the carrier's dot), if any. */
export interface RadarBall {
  readonly x: number
  readonly y: number
  readonly on: RadarDot['team'] | null
}

export interface RadarReading {
  /** Enough of a radar seen (the radar is hidden during replays and cut-scenes). */
  readonly ok: boolean
  readonly dots: readonly RadarDot[]
  /** The ball, pitch metres: the likeliest candidate, and every candidate (the tracker picks by where it was). */
  readonly ball: RadarBall | null
  readonly balls: readonly RadarBall[]
  /** The colour groups' mean colours (colours not given), for the tracker to keep A and B apart. */
  readonly groups: readonly (readonly [number, number, number])[]
  /** Where the pitch was found in the box, 0..1 of the box, and whether its outline was seen (else the box is taken as the pitch). */
  readonly pitch: { readonly x0: number; readonly y0: number; readonly x1: number; readonly y1: number; readonly outline?: boolean }
  /** The box's size in pixels, and its bright pixels (for the tracker to learn the lines drawn every frame). */
  readonly size?: { readonly w: number; readonly h: number }
  readonly white?: Uint8Array | null
}

export interface RadarOptions {
  /** The player's team colour on the radar, and the opponent's (unset: told apart by colour groups). */
  readonly ours?: RadarColour | null
  readonly theirs?: RadarColour | null
  /** Pixels of the box that are bright on (nearly) every frame: the pitch lines, never the ball. */
  readonly lines?: Uint8Array | null
}

const NONE: RadarReading = { ok: false, dots: [], ball: null, balls: [], groups: [], pitch: { x0: 0, y0: 0, x1: 1, y1: 1 } }

interface Blob {
  n: number
  sx: number
  sy: number
  r: number
  g: number
  b: number
  x0: number
  y0: number
  x1: number
  y1: number
  px: number[]
}

/** Reads the radar in `region` (fractions of the frame) of an RGBA frame. */
export function readRadar(frame: { readonly width: number; readonly height: number; readonly data: Uint8ClampedArray }, region: NormRect, opts: RadarOptions = {}): RadarReading {
  const X0 = Math.max(0, Math.round(region.x * frame.width))
  const Y0 = Math.max(0, Math.round(region.y * frame.height))
  const W = Math.min(frame.width - X0, Math.round(region.w * frame.width))
  const H = Math.min(frame.height - Y0, Math.round(region.h * frame.height))
  if (W < 40 || H < 24) return NONE
  const d = frame.data
  const at = (x: number, y: number) => ((Y0 + y) * frame.width + X0 + x) * 4
  // Pixel classes: bright and grey (lines, the ball), and coloured (dots).
  const white = new Uint8Array(W * H)
  const colour = new Uint8Array(W * H)
  for (let y = 0; y < H; y++)
    for (let x = 0; x < W; x++) {
      const o = at(x, y)
      const r = d[o]
      const g = d[o + 1]
      const b = d[o + 2]
      const max = Math.max(r, g, b)
      const min = Math.min(r, g, b)
      const i = y * W + x
      if (min >= 170 && max - min <= 60) white[i] = 1
      else if (max >= 90 && (max - min) / max >= 0.35) colour[i] = 1
    }
  const pitch = findPitch(white, W, H)
  const px0 = pitch.x0 * W
  const py0 = pitch.y0 * H
  const pw = (pitch.x1 - pitch.x0) * W
  const ph = (pitch.y1 - pitch.y0) * H
  const toPitch = (x: number, y: number) => ({ x: ((x + 0.5 - px0) / pw) * PITCH_LENGTH, y: ((y + 0.5 - py0) / ph) * PITCH_WIDTH })
  // Dots may sit on a touchline, half outside the drawn pitch.
  const pad = Math.max(2, Math.round(pw * 0.02))
  const inside = (x: number, y: number) => x >= px0 - pad && x < px0 + pw + pad && y >= py0 - pad && y < py0 + ph + pad
  // Each dot pixel gets its team (the two colours given, else two colour groups found on this
  // frame), so touching dots of the two teams stay apart.
  const known = opts.ours && opts.theirs && opts.ours !== opts.theirs ? ([RADAR_COLOURS[opts.ours], RADAR_COLOURS[opts.theirs]] as const) : null
  const whiteTeam = !!known && (opts.ours === 'white' || opts.theirs === 'white')
  let centres: readonly (readonly [number, number, number])[]
  if (known) centres = known
  else {
    // The two colour groups of the dot-sized blobs (specks of the picture behind are left out).
    const minDot = Math.max(3, Math.PI * (0.011 * pw) ** 2)
    const cols = components(colour, W, H, (o) => rgbAt(d, o), at)
      .filter((b) => b.n >= minDot && inside(b.sx / b.n, b.sy / b.n) && b.n / ((b.x1 - b.x0 + 1) * (b.y1 - b.y0 + 1)) >= 0.35)
      .map((b) => [b.r / b.n, b.g / b.n, b.b / b.n])
    if (cols.length < 8) return { ...NONE, pitch }
    centres = twoGroups(cols).centres
  }
  const label = new Uint8Array(W * H)
  for (let i = 0; i < W * H; i++) {
    if (!colour[i] && !(whiteTeam && white[i])) continue
    const c = rgbAt(d, at(i % W, (i / W) | 0))
    const da = colourDistance(c, centres[0])
    const db = colourDistance(c, centres[1])
    if (Math.min(da, db) <= 0.55) label[i] = da <= db ? 1 : 2
  }
  const blobs: (Blob & { team: number })[] = []
  for (const k of [1, 2]) {
    const mask = new Uint8Array(W * H)
    for (let i = 0; i < W * H; i++) mask[i] = label[i] === k ? 1 : 0
    for (const b of components(mask, W, H, (o) => rgbAt(d, o), at)) if (inside(b.sx / b.n, b.sy / b.n)) blobs.push({ ...b, team: k })
  }
  if (blobs.length < 6) return { ...NONE, pitch }
  // The size of one dot: the median of the blobs big enough to be one (radars draw dots about 2.5
  // to 4% of the pitch's length across; specks of the picture behind a translucent radar are smaller).
  const minDot = Math.max(3, Math.PI * (0.011 * pw) ** 2)
  const areas = blobs.filter((b) => b.n >= minDot).map((b) => b.n).sort((a, b) => a - b)
  const unit = Math.max(minDot, areas[areas.length >> 1] ?? minDot)
  const dots: RadarDot[] = []
  const name = (k: number): RadarDot['team'] => (known ? (k === 1 ? 'ours' : 'theirs') : k === 1 ? 'A' : 'B')
  const ballLike: { x: number; y: number; n: number }[] = []
  // The ball: a small bright blob whose middle has bright neighbours all round (the pitch lines,
  // one or two pixels wide, do not), smaller than a dot, and not part of the lines drawn every
  // frame (`opts.lines`, when the tracker has learnt them).
  const core = new Uint8Array(W * H)
  for (let y = 1; y < H - 1; y++)
    for (let x = 1; x < W - 1; x++) {
      const i = y * W + x
      if (!white[i] || (opts.lines && opts.lines.length === W * H && opts.lines[i])) continue
      let n = 0
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) if ((dx || dy) && white[i + dy * W + dx]) n++
      if (n >= 6) core[i] = 1
    }
  const balls: { x: number; y: number; n: number; px: number; py: number }[] = []
  for (const c of components(core, W, H, (o) => rgbAt(d, o), at)) {
    if (!inside(c.sx / c.n, c.sy / c.n) || c.n > unit * 0.6) continue
    balls.push({ ...toPitch(c.sx / c.n, c.sy / c.n), n: c.n, px: c.sx / c.n, py: c.sy / c.n })
  }
  // The ball is drawn at the carrier's feet, over their dot: what is left of a dot beside the
  // ball still counts, and the colour round the ball says which team has it.
  const dotR = Math.sqrt(unit / Math.PI)
  const nearBall = (x: number, y: number) => balls.some((c) => Math.hypot(c.px - x, c.py - y) <= dotR * 2.2)

  for (const b of blobs) {
    const bw = b.x1 - b.x0 + 1
    const bh = b.y1 - b.y0 + 1
    // Touching dots of one team: by area, or by length (two that overlap a lot are longer than one).
    const across = 2 * Math.sqrt(unit / Math.PI)
    const k = Math.max(1, Math.min(4, Math.round(b.n / unit), Math.max(bw, bh) > 1.5 * across ? 2 : 1))
    // Lines and text: long and thin, or sparse.
    if (Math.max(bw, bh) > 3.2 * Math.sqrt(unit) * k || b.n / (bw * bh) < 0.3) continue
    if (b.n < unit * 0.45 && !(b.n >= unit * 0.12 && nearBall(b.sx / b.n, b.sy / b.n))) {
      // A white team: its small blobs may be the ball.
      if (whiteTeam && RADAR_COLOURS[b.team === 1 ? opts.ours! : opts.theirs!] === RADAR_COLOURS.white) ballLike.push({ ...toPitch(b.sx / b.n, b.sy / b.n), n: b.n })
      continue
    }
    const rgb: [number, number, number] = [b.r / b.n, b.g / b.n, b.b / b.n]
    if (k === 1) dots.push({ ...toPitch(b.sx / b.n, b.sy / b.n), team: name(b.team), rgb })
    else for (const c of kmeansPixels(b.px, W, k)) dots.push({ ...toPitch(c[0], c[1]), team: name(b.team), rgb })
  }
  // With no history, the candidate most like a ball (the most solid one).
  for (const b of ballLike) balls.push({ ...b, px: NaN, py: NaN })
  const ballOn = (c: { px: number; py: number }): RadarDot['team'] | null => {
    if (!Number.isFinite(c.px)) return null
    const r = Math.ceil(dotR * 1.8)
    let n1 = 0
    let n2 = 0
    for (let y = Math.max(0, Math.round(c.py) - r); y <= Math.min(H - 1, Math.round(c.py) + r); y++)
      for (let x = Math.max(0, Math.round(c.px) - r); x <= Math.min(W - 1, Math.round(c.px) + r); x++) {
        if (Math.hypot(x - c.px, y - c.py) > r) continue
        const l = label[y * W + x]
        if (l === 1) n1++
        else if (l === 2) n2++
      }
    return Math.max(n1, n2) >= 3 && Math.max(n1, n2) >= 2 * Math.min(n1, n2) ? name(n1 >= n2 ? 1 : 2) : null
  }
  const ball = balls.length ? [...balls].sort((a, b) => b.n - a.n)[0] : null
  // A radar: its outline seen, a dozen or more dots, and no more of a team than a team has (a
  // busy picture behind a hidden radar makes many blobs, and no outline).
  const perTeam = (k: RadarDot['team']) => dots.filter((d) => d.team === k).length
  const ok = !!pitch.outline && dots.length >= 12 && Math.max(perTeam(name(1)), perTeam(name(2))) <= 14
  return {
    ok,
    dots: ok ? dots : [],
    ball: ok && ball ? { x: ball.x, y: ball.y, on: ballOn(ball) } : null,
    balls: ok ? balls.map((b) => ({ x: b.x, y: b.y, on: ballOn(b) })) : [],
    groups: centres,
    pitch,
    size: { w: W, h: H },
    white: ok ? white : null,
  }
}

function rgbAt(d: Uint8ClampedArray, o: number): [number, number, number] {
  return [d[o], d[o + 1], d[o + 2]]
}

/** The pitch outline in the box: the outermost long bright rows and columns (else the whole box, a little inset). */
function findPitch(white: Uint8Array, W: number, H: number): RadarReading['pitch'] {
  const rows = new Array<number>(H).fill(0)
  const cols = new Array<number>(W).fill(0)
  for (let y = 0; y < H; y++)
    for (let x = 0; x < W; x++)
      if (white[y * W + x]) {
        rows[y]++
        cols[x]++
      }
  const firstRow = (from: number, to: number, step: number) => {
    for (let y = from; step > 0 ? y < to : y > to; y += step) if (rows[y] >= W * 0.45) return y
    return -1
  }
  const firstCol = (from: number, to: number, step: number) => {
    for (let x = from; step > 0 ? x < to : x > to; x += step) if (cols[x] >= H * 0.45) return x
    return -1
  }
  const top = firstRow(0, Math.round(H * 0.3), 1)
  const bottom = firstRow(H - 1, Math.round(H * 0.7), -1)
  const left = firstCol(0, Math.round(W * 0.3), 1)
  const right = firstCol(W - 1, Math.round(W * 0.7), -1)
  if (top < 0 || bottom < 0 || left < 0 || right < 0 || bottom - top < H * 0.4 || right - left < W * 0.4) return { x0: 0.03, y0: 0.04, x1: 0.97, y1: 0.96, outline: false }
  // The middle of each line.
  return { x0: (left + 0.5) / W, y0: (top + 0.5) / H, x1: (right + 0.5) / W, y1: (bottom + 0.5) / H, outline: true }
}

function components(mask: Uint8Array, W: number, H: number, rgb: (o: number) => number[], at: (x: number, y: number) => number): Blob[] {
  const seen = new Uint8Array(W * H)
  const out: Blob[] = []
  const stack: number[] = []
  for (let p = 0; p < W * H; p++) {
    if (!mask[p] || seen[p]) continue
    const b: Blob = { n: 0, sx: 0, sy: 0, r: 0, g: 0, b: 0, x0: W, y0: H, x1: 0, y1: 0, px: [] }
    stack.push(p)
    seen[p] = 1
    while (stack.length) {
      const q = stack.pop()!
      const x = q % W
      const y = (q / W) | 0
      const c = rgb(at(x, y))
      b.n++
      b.sx += x
      b.sy += y
      b.r += c[0]
      b.g += c[1]
      b.b += c[2]
      if (x < b.x0) b.x0 = x
      if (x > b.x1) b.x1 = x
      if (y < b.y0) b.y0 = y
      if (y > b.y1) b.y1 = y
      if (b.px.length < 400) b.px.push(q)
      for (let dy = -1; dy <= 1; dy++)
        for (let dx = -1; dx <= 1; dx++) {
          const xx = x + dx
          const yy = y + dy
          if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue
          const r = yy * W + xx
          if (mask[r] && !seen[r]) {
            seen[r] = 1
            stack.push(r)
          }
        }
    }
    out.push(b)
  }
  return out
}

/** Splits a blob of k touching dots into k centres (k-means on its pixels). */
function kmeansPixels(px: readonly number[], W: number, k: number): [number, number][] {
  const pts = px.map((p) => [p % W, (p / W) | 0] as [number, number])
  // Start from the extremes along the blob's longer axis.
  const xs = pts.map((p) => p[0])
  const ys = pts.map((p) => p[1])
  const horizontal = Math.max(...xs) - Math.min(...xs) >= Math.max(...ys) - Math.min(...ys)
  const sorted = [...pts].sort((a, b) => (horizontal ? a[0] - b[0] : a[1] - b[1]))
  let centres = Array.from({ length: k }, (_, i) => sorted[Math.min(sorted.length - 1, Math.round(((i + 0.5) / k) * sorted.length))])
  for (let it = 0; it < 8; it++) {
    const sum = centres.map(() => [0, 0, 0])
    for (const p of pts) {
      let bi = 0
      let bd = Infinity
      centres.forEach((c, i) => {
        const dd = (p[0] - c[0]) ** 2 + (p[1] - c[1]) ** 2
        if (dd < bd) {
          bd = dd
          bi = i
        }
      })
      sum[bi][0] += p[0]
      sum[bi][1] += p[1]
      sum[bi][2]++
    }
    centres = sum.map((s, i) => (s[2] ? ([s[0] / s[2], s[1] / s[2]] as [number, number]) : centres[i]))
  }
  return centres
}

/** Colour distance on chromaticity and brightness, 0..~1.7. */
export function colourDistance(a: readonly number[], b: readonly number[]): number {
  const sa = a[0] + a[1] + a[2] + 1
  const sb = b[0] + b[1] + b[2] + 1
  const dc = Math.hypot(a[0] / sa - b[0] / sb, a[1] / sa - b[1] / sb, a[2] / sa - b[2] / sb) * 2
  const dv = Math.abs(Math.max(...a) - Math.max(...b)) / 255
  return dc + dv * 0.5
}

/** Two colour groups (2-means on chromaticity), with each colour's group. */
export function twoGroups(cols: readonly (readonly number[])[]): { centres: [number, number, number][]; labels: number[] } {
  if (!cols.length) return { centres: [], labels: [] }
  // Start from the two most different colours (of up to 120 spread through the list).
  const stride = Math.max(1, Math.floor(cols.length / 120))
  let a = 0
  let b = 0
  let far = -1
  for (let i = 0; i < cols.length; i += stride)
    for (let j = i + stride; j < cols.length; j += stride) {
      const dd = colourDistance(cols[i], cols[j])
      if (dd > far) {
        far = dd
        a = i
        b = j
      }
    }
  let centres: [number, number, number][] = [[...cols[a]] as [number, number, number], [...cols[b]] as [number, number, number]]
  let labels = cols.map(() => 0)
  for (let it = 0; it < 6; it++) {
    labels = cols.map((c) => (colourDistance(c, centres[0]) <= colourDistance(c, centres[1]) ? 0 : 1))
    centres = [0, 1].map((k) => {
      const m = cols.filter((_, i) => labels[i] === k)
      return m.length ? ([0, 1, 2].map((ch) => m.reduce((s, c) => s + c[ch], 0) / m.length) as [number, number, number]) : centres[k]
    })
  }
  return { centres, labels }
}
