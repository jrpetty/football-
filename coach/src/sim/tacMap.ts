// A synthetic overhead map ("tac-map") and the minimap a game would draw from
// it, for the minimap stitching tests and the Modern Warfare 4 demo: streets,
// buildings with walls, containers and cover, drawn in a minimap's muted
// blue-greys, plus the player's arrow, teammates, enemy pings and a frame.
// Not a real map: real maps come from the player's own minimap.

import { mulberry32 } from './syntheticArena.ts'

export interface TacMap {
  readonly size: number
  /** Luminance 0..1, size×size. */
  readonly lum: Float32Array
}

export function tacMap(seed: number, size = 720): TacMap {
  const rnd = mulberry32(seed)
  const lum = new Float32Array(size * size)
  // Ground with a little low-frequency variation.
  const phase = [rnd() * 6, rnd() * 6, rnd() * 6]
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      lum[y * size + x] = 0.17 + 0.025 * Math.sin(x / 37 + phase[0]) * Math.cos(y / 41 + phase[1]) + 0.015 * Math.sin((x + y) / 23 + phase[2])
    }
  }
  const rect = (x0: number, y0: number, w: number, h: number, v: number) => {
    for (let y = Math.max(0, Math.round(y0)); y < Math.min(size, Math.round(y0 + h)); y++)
      for (let x = Math.max(0, Math.round(x0)); x < Math.min(size, Math.round(x0 + w)); x++) lum[y * size + x] = v
  }
  // Streets: a few wide lanes across the map.
  for (let i = 0; i < 5; i++) {
    const horizontal = i % 2 === 0
    const at = 60 + rnd() * (size - 120)
    const width = 14 + rnd() * 14
    if (horizontal) rect(0, at, size, width, 0.25)
    else rect(at, 0, width, size, 0.25)
  }
  // Buildings: filled blocks with lighter walls and some inner walls.
  for (let i = 0; i < 55; i++) {
    const w = 26 + rnd() * 70
    const h = 26 + rnd() * 70
    const x = rnd() * (size - w)
    const y = rnd() * (size - h)
    const fill = 0.36 + rnd() * 0.12
    rect(x, y, w, h, 0.62)
    rect(x + 2, y + 2, w - 4, h - 4, fill)
    if (rnd() < 0.6) rect(x + w / 2, y + 2, 2, h * (0.4 + rnd() * 0.5), 0.55)
    if (rnd() < 0.5) rect(x + 2, y + h / 2, w * (0.3 + rnd() * 0.5), 2, 0.55)
    // A doorway.
    rect(x + w * (0.2 + rnd() * 0.5), y + h - 2, 8, 2, fill)
  }
  // Containers and cover.
  for (let i = 0; i < 90; i++) {
    const long = rnd() < 0.5
    rect(rnd() * (size - 20), rnd() * (size - 20), long ? 16 : 6, long ? 6 : 16, 0.47 + rnd() * 0.08)
  }
  for (let i = 0; i < 160; i++) rect(rnd() * (size - 4), rnd() * (size - 4), 3, 3, 0.3 + rnd() * 0.2)
  return { size, lum }
}

function sample(m: TacMap, x: number, y: number): number {
  if (x < 0 || y < 0 || x >= m.size - 1 || y >= m.size - 1) return 0.07
  const x0 = Math.floor(x)
  const y0 = Math.floor(y)
  const fx = x - x0
  const fy = y - y0
  const i = y0 * m.size + x0
  return (m.lum[i] * (1 - fx) + m.lum[i + 1] * fx) * (1 - fy) + (m.lum[i + m.size] * (1 - fx) + m.lum[i + m.size + 1] * fx) * fy
}

export interface MinimapDraw {
  /** Map pixels per minimap pixel. */
  readonly scale: number
  /** Heading in degrees, 0 = north, clockwise (the arrow). */
  readonly heading?: number
  readonly enemies?: readonly { readonly x: number; readonly y: number }[]
  readonly friends?: readonly { readonly x: number; readonly y: number }[]
  readonly round?: boolean
  /** Small per-pixel noise (compression, film grain), 0..1. */
  readonly noise?: number
  readonly seed?: number
}

/** Draws a px×px minimap centred on (cx, cy) into `out` (RGBA, `stride` pixels wide) at (ox, oy). */
export function drawMinimap(m: TacMap, cx: number, cy: number, px: number, d: MinimapDraw, out: Uint8ClampedArray, stride: number, ox: number, oy: number): void {
  const rnd = mulberry32(d.seed ?? 1)
  const half = px / 2
  const set = (x: number, y: number, r: number, g: number, b: number) => {
    if (x < 0 || y < 0 || x >= px || y >= px) return
    const o = ((oy + y) * stride + ox + x) * 4
    out[o] = r
    out[o + 1] = g
    out[o + 2] = b
    out[o + 3] = 255
  }
  for (let y = 0; y < px; y++) {
    for (let x = 0; x < px; x++) {
      const dx = x - half + 0.5
      const dy = y - half + 0.5
      if (d.round && dx * dx + dy * dy > half * half) continue
      let v = sample(m, cx + dx * d.scale, cy + dy * d.scale)
      if (d.noise) v += (rnd() - 0.5) * d.noise
      v = Math.max(0, Math.min(1, v))
      set(x, y, 38 + v * 170, 46 + v * 182, 58 + v * 190)
    }
  }
  // A frame.
  for (let i = 0; i < px; i++)
    for (const k of [0, 1, px - 2, px - 1]) {
      if (!d.round) {
        set(i, k, 150, 158, 168)
        set(k, i, 150, 158, 168)
      }
    }
  const dot = (wx: number, wy: number, r: number, g: number, b: number) => {
    const sx = (wx - cx) / d.scale + half
    const sy = (wy - cy) / d.scale + half
    for (let y = -2; y <= 2; y++) for (let x = -2; x <= 2; x++) if (x * x + y * y <= 5) set(Math.round(sx + x), Math.round(sy + y), r, g, b)
  }
  for (const f of d.friends ?? []) dot(f.x, f.y, 70, 190, 255)
  for (const e of d.enemies ?? []) dot(e.x, e.y, 235, 45, 45)
  // The player's arrow at the centre.
  const a = ((d.heading ?? 0) * Math.PI) / 180
  for (let y = -6; y <= 6; y++) {
    for (let x = -6; x <= 6; x++) {
      // Rotate into the arrow's frame: tip at -y.
      const u = x * Math.cos(a) + y * Math.sin(a)
      const v = -x * Math.sin(a) + y * Math.cos(a)
      if (v >= -5 && v <= 4 && Math.abs(u) <= (v + 5) * 0.45) set(Math.round(half + x), Math.round(half + y), 245, 240, 215)
    }
  }
}
