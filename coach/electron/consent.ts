// Capture consent. The renderer cannot start a capture on its own: the player
// must pick a window or screen in the picker, which records a single-use,
// short-lived grant here. The display-media handler consumes it. A page that
// calls getDisplayMedia without a fresh, explicit choice gets nothing.

export const CONSENT_TTL_MS = 15_000

export class CaptureConsent {
  private pending: { sourceId: string; at: number } | null = null
  private readonly ttlMs: number
  private readonly now: () => number

  constructor(ttlMs = CONSENT_TTL_MS, now: () => number = Date.now) {
    this.ttlMs = ttlMs
    this.now = now
  }

  choose(sourceId: string): void {
    this.pending = { sourceId, at: this.now() }
  }

  /** The chosen source id, if a fresh choice exists. Single use. */
  take(): string | null {
    const p = this.pending
    this.pending = null
    if (!p || this.now() - p.at > this.ttlMs) return null
    return p.sourceId
  }
}

/** desktopCapturer ids look like "screen:0:0" or "window:132456:0". */
export function isSourceId(v: unknown): v is string {
  return typeof v === 'string' && /^(screen|window):[0-9]+:[0-9]+$/.test(v)
}
