// The visible-state tracker turns per-frame detector readings into stable
// signals: numeric bars are median-smoothed, boolean detectors get hysteresis,
// and a signal that cannot be read confidently becomes *unknown* after a short
// grace period instead of silently keeping (or zeroing) its last value.

import { createDetector, type Detector } from './detectors/detectors.ts'
import type { SignalSpec } from './profile/schema.ts'
import type { Frame, SignalState } from './types.ts'

/** A confident reading may be missing for this long before the signal reads as unknown. */
export const STALE_AFTER_MS = 750

interface Track {
  readonly detector: Detector
  readonly minConfidence: number
  readonly smoothing: number
  /** Recent confident numeric readings, for the median filter. */
  window: number[]
  state: SignalState
  /** Boolean hysteresis memory, independent of known/unknown. */
  lastBool: boolean | null
}

export class VisibleStateTracker {
  private readonly tracks: Track[]

  constructor(specs: readonly SignalSpec[]) {
    this.tracks = specs.map((spec) => ({
      detector: createDetector(spec),
      minConfidence: spec.minConfidence ?? 0.5,
      smoothing: spec.kind === 'bar' ? (spec.smoothing ?? 3) : 1,
      window: [],
      state: { id: spec.id, known: false, lastKnownAt: null },
      lastBool: null,
    }))
  }

  update(frame: Frame): ReadonlyMap<string, SignalState> {
    const t = frame.timestamp
    for (const track of this.tracks) {
      const reading = track.detector.read(frame)
      const spec = track.detector.spec
      if (reading.confidence < track.minConfidence) {
        track.state = this.decay(track.state, t)
        continue
      }
      if (reading.kind === 'number') {
        track.window.push(reading.value)
        if (track.window.length > track.smoothing) track.window.shift()
        track.state = {
          id: spec.id,
          known: true,
          kind: 'number',
          value: median(track.window),
          confidence: reading.confidence,
          observedAt: t,
        }
      } else {
        // Hysteresis: once on, stay on until the score drops to the "off" level.
        const off = spec.kind === 'bar' ? 0 : spec.off
        const value = track.lastBool ? reading.score > off : reading.value
        track.lastBool = value
        track.state = { id: spec.id, known: true, kind: 'boolean', value, confidence: reading.confidence, observedAt: t }
      }
    }
    return this.snapshot()
  }

  snapshot(): ReadonlyMap<string, SignalState> {
    return new Map(this.tracks.map((tr) => [tr.state.id, tr.state]))
  }

  /** Forget smoothing history, e.g. after capture restarts on a different target. */
  reset(): void {
    for (const track of this.tracks) {
      track.window = []
      track.lastBool = null
      track.state = { id: track.state.id, known: false, lastKnownAt: null }
    }
  }

  private decay(state: SignalState, t: number): SignalState {
    if (!state.known) return state
    if (t - state.observedAt <= STALE_AFTER_MS) return state
    return { id: state.id, known: false, lastKnownAt: state.observedAt }
  }
}

function median(values: readonly number[]): number {
  const s = [...values].sort((a, b) => a - b)
  const m = s.length >> 1
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2
}
