// Frame health: is the capture actually showing the game? Black frames
// (capture blocked, minimised window, loading), frozen frames (minimised or
// stalled capture) and size changes are reported so the state can be marked
// stale instead of the coach reasoning about a picture that is not there.

import type { Frame } from './types.ts'

export const BLACK_AFTER_MS = 1500
export const FROZEN_AFTER_MS = 5000

export interface FrameHealth {
  readonly black: boolean
  readonly frozen: boolean
  readonly sizeChanged: boolean
  /** Human-readable reason the frames cannot be trusted, or null. */
  readonly problem: string | null
}

const COLS = 16
const ROWS = 9

export class FrameHealthMonitor {
  private size: { w: number; h: number } | null = null
  private lastGrid: Float32Array | null = null
  private blackSince: number | null = null
  private frozenSince: number | null = null

  check(frame: Frame): FrameHealth {
    const t = frame.timestamp
    const sizeChanged = !!this.size && (this.size.w !== frame.width || this.size.h !== frame.height)
    this.size = { w: frame.width, h: frame.height }

    const grid = sampleGrid(frame)
    let mean = 0
    for (const v of grid) mean += v
    mean /= grid.length
    let variance = 0
    for (const v of grid) variance += (v - mean) ** 2
    const isBlack = mean < 10 && Math.sqrt(variance / grid.length) < 4

    let diff = Infinity
    if (this.lastGrid && !sizeChanged) {
      diff = 0
      for (let i = 0; i < grid.length; i++) diff += Math.abs(grid[i] - this.lastGrid[i])
      diff /= grid.length
    }
    this.lastGrid = grid
    const isStill = diff < 0.5

    this.blackSince = isBlack ? (this.blackSince ?? t) : null
    this.frozenSince = isStill && !isBlack ? (this.frozenSince ?? t) : null
    const black = this.blackSince !== null && t - this.blackSince >= BLACK_AFTER_MS
    const frozen = this.frozenSince !== null && t - this.frozenSince >= FROZEN_AFTER_MS
    const problem = black
      ? 'The capture is black (blocked by the game, minimised, or loading).'
      : frozen
        ? 'The picture has not changed for 5 seconds (minimised, paused, or the capture stalled).'
        : null
    return { black, frozen, sizeChanged, problem }
  }

  reset(): void {
    this.size = null
    this.lastGrid = null
    this.blackSince = null
    this.frozenSince = null
  }
}

/** Mean luminance of a coarse 16×9 grid, sampling every 4th pixel for speed. */
export function sampleGrid(frame: Frame): Float32Array {
  const out = new Float32Array(COLS * ROWS)
  const counts = new Uint32Array(COLS * ROWS)
  for (let y = 0; y < frame.height; y += 4) {
    const cy = Math.min(ROWS - 1, Math.floor((y * ROWS) / frame.height))
    for (let x = 0; x < frame.width; x += 4) {
      const cx = Math.min(COLS - 1, Math.floor((x * COLS) / frame.width))
      const i = (y * frame.width + x) * 4
      out[cy * COLS + cx] += 0.299 * frame.data[i] + 0.587 * frame.data[i + 1] + 0.114 * frame.data[i + 2]
      counts[cy * COLS + cx]++
    }
  }
  for (let k = 0; k < out.length; k++) out[k] = counts[k] ? out[k] / counts[k] : 0
  return out
}
