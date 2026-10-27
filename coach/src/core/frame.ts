// Pixel utilities shared by the detectors. Pure functions over RGBA buffers.

import type { Frame, HsvRange, NormRect } from './types.ts'

export interface PixelRect {
  readonly x0: number
  readonly y0: number
  /** Exclusive. */
  readonly x1: number
  /** Exclusive. */
  readonly y1: number
}

/** Map a normalized rect onto a frame, clamped to its bounds. Never empty for a valid rect. */
export function toPixelRect(frame: Pick<Frame, 'width' | 'height'>, r: NormRect): PixelRect {
  const x0 = clampInt(Math.floor(r.x * frame.width), 0, frame.width - 1)
  const y0 = clampInt(Math.floor(r.y * frame.height), 0, frame.height - 1)
  const x1 = clampInt(Math.ceil((r.x + r.w) * frame.width), x0 + 1, frame.width)
  const y1 = clampInt(Math.ceil((r.y + r.h) * frame.height), y0 + 1, frame.height)
  return { x0, y0, x1, y1 }
}

function clampInt(v: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, v))
}

export interface Hsv {
  /** 0..360 */
  h: number
  /** 0..1 */
  s: number
  /** 0..1 */
  v: number
}

export function rgbToHsv(r: number, g: number, b: number, out: Hsv = { h: 0, s: 0, v: 0 }): Hsv {
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const d = max - min
  let h = 0
  if (d !== 0) {
    if (max === r) h = ((g - b) / d) % 6
    else if (max === g) h = (b - r) / d + 2
    else h = (r - g) / d + 4
    h *= 60
    if (h < 0) h += 360
  }
  out.h = h
  out.s = max === 0 ? 0 : d / max
  out.v = max / 255
  return out
}

export function inHsvRange(c: Hsv, range: HsvRange): boolean {
  if (c.s < range.s[0] || c.s > range.s[1]) return false
  if (c.v < range.v[0] || c.v > range.v[1]) return false
  const [h0, h1] = range.h
  return h0 <= h1 ? c.h >= h0 && c.h <= h1 : c.h >= h0 || c.h <= h1
}

/** Mean luminance (0..255) of every pixel in a rect, on an N×M grid of cells. */
export function grayscaleGrid(frame: Frame, rect: PixelRect, cols: number, rows: number): Float32Array {
  const out = new Float32Array(cols * rows)
  const counts = new Uint32Array(cols * rows)
  const w = rect.x1 - rect.x0
  const h = rect.y1 - rect.y0
  for (let y = rect.y0; y < rect.y1; y++) {
    const cy = Math.min(rows - 1, Math.floor(((y - rect.y0) * rows) / h))
    for (let x = rect.x0; x < rect.x1; x++) {
      const cx = Math.min(cols - 1, Math.floor(((x - rect.x0) * cols) / w))
      const i = (y * frame.width + x) * 4
      const lum = 0.299 * frame.data[i] + 0.587 * frame.data[i + 1] + 0.114 * frame.data[i + 2]
      out[cy * cols + cx] += lum
      counts[cy * cols + cx] += 1
    }
  }
  for (let k = 0; k < out.length; k++) out[k] = counts[k] ? out[k] / counts[k] : 0
  return out
}
