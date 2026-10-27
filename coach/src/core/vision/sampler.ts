// Decides when a captured frame is worth sending to the vision recogniser.
// Cost and latency matter more than coverage: at most one request in flight,
// a minimum interval, near-identical frames skipped (paused game, menus), and
// exponential backoff after errors. The decision never depends on what the
// model said about earlier frames.

export interface SamplerConfig {
  readonly intervalMs: number
  /** Skip a frame whose grid differs from the last sent one by less than this (mean luminance). */
  readonly minChange: number
  /** But send anyway after this long, so slow changes (a clock) are still read. */
  readonly maxSkipMs: number
}

export interface SamplerStats {
  readonly sent: number
  readonly skippedUnchanged: number
  readonly errors: number
  /** Frames recognised locally as an already-known screen (no request sent). */
  readonly knownScreens: number
  readonly inFlight: boolean
  readonly backoffUntil: number
}

export class VisionSampler {
  private readonly cfg: SamplerConfig
  private lastSentAt = -Infinity
  private lastGrid: Float32Array | null = null
  private inFlight = false
  private backoffUntil = 0
  private consecutiveErrors = 0
  private sent = 0
  private skippedUnchanged = 0
  private errors = 0
  private paused = false
  private knownScreens = 0

  constructor(cfg: SamplerConfig) {
    this.cfg = cfg
  }

  /** Should this frame be sent? If true, the caller must call `sent()` and later `done()`. */
  shouldSample(now: number, grid: Float32Array): boolean {
    if (this.paused || this.inFlight || now < this.backoffUntil) return false
    const since = now - this.lastSentAt
    if (since < this.cfg.intervalMs) return false
    if (this.lastGrid && since < this.cfg.maxSkipMs && meanDiff(grid, this.lastGrid) < this.cfg.minChange) {
      this.skippedUnchanged++
      return false
    }
    return true
  }

  sending(now: number, grid: Float32Array): void {
    this.inFlight = true
    this.lastSentAt = now
    this.lastGrid = grid
    this.sent++
  }

  /** The frame was a screen already in memory: treat the interval as used, but send nothing. */
  handledLocally(now: number, grid: Float32Array): void {
    this.lastSentAt = now
    this.lastGrid = grid
    this.knownScreens++
  }

  done(now: number, ok: boolean, retryAfterMs?: number): void {
    this.inFlight = false
    if (ok) {
      this.consecutiveErrors = 0
      return
    }
    this.errors++
    this.consecutiveErrors++
    const backoff = retryAfterMs ?? Math.min(60_000, this.cfg.intervalMs * 2 ** this.consecutiveErrors)
    this.backoffUntil = now + backoff
  }

  /** Stop sampling entirely (e.g. the spend cap was reached or cloud vision was switched off). */
  setPaused(paused: boolean): void {
    this.paused = paused
  }

  get stats(): SamplerStats {
    return { sent: this.sent, skippedUnchanged: this.skippedUnchanged, errors: this.errors, knownScreens: this.knownScreens, inFlight: this.inFlight, backoffUntil: this.backoffUntil }
  }
}

function meanDiff(a: Float32Array, b: Float32Array): number {
  if (a.length !== b.length) return Infinity
  let d = 0
  for (let i = 0; i < a.length; i++) d += Math.abs(a[i] - b[i])
  return d / a.length
}
