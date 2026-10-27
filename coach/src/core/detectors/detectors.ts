// Detectors turn the pixels inside one HUD region into a single reading with a
// confidence. They are deliberately simple, deterministic and explainable:
// colour-run bar measurement, colour coverage, and grayscale template
// correlation. Each one reports low confidence rather than guessing when the
// region does not look like what the profile describes.

import { grayscaleGrid, inHsvRange, rgbToHsv, toPixelRect, type Hsv } from '../frame.ts'
import type { BarSignalSpec, CoverageSignalSpec, SignalSpec, SignatureSignalSpec } from '../profile/schema.ts'
import type { Frame, Reading } from '../types.ts'

export interface Detector {
  readonly spec: SignalSpec
  read(frame: Frame): Reading
}

export function createDetector(spec: SignalSpec): Detector {
  switch (spec.kind) {
    case 'bar':
      return { spec, read: (f) => readBar(f, spec) }
    case 'coverage':
      return { spec, read: (f) => readCoverage(f, spec) }
    case 'signature':
      return { spec, read: (f) => readSignature(f, spec) }
  }
}

/**
 * Bar fill: classify every pixel in the region as fill, track (the bar's empty
 * part) or other. Scanlines and end columns that are mostly "other" are the
 * region's margin — profile rectangles are never pixel-perfect — and are
 * trimmed. A real bar is then one contiguous run of fill from its anchored
 * end, so the fill ratio is that run's length. Confidence falls when fill
 * appears *beyond* the run (text, particles, another UI over the bar), when
 * scanlines disagree, or when the unfilled part does not look like the track.
 */
export function readBar(frame: Frame, spec: BarSignalSpec): Reading {
  const r = toPixelRect(frame, spec.region)
  const horizontal = spec.axis === 'horizontal'
  const length = horizontal ? r.x1 - r.x0 : r.y1 - r.y0
  const breadth = horizontal ? r.y1 - r.y0 : r.x1 - r.x0
  const FILL = 1
  const TRACK = 2
  const cls = new Uint8Array(length * breadth)
  const hsv: Hsv = { h: 0, s: 0, v: 0 }

  for (let a = 0; a < length; a++) {
    // Position 0 is the anchored end: left (horizontal) or bottom (vertical) for 'start'.
    const along = spec.fillFrom === 'start' ? a : length - 1 - a
    for (let b = 0; b < breadth; b++) {
      const x = horizontal ? r.x0 + along : r.x0 + b
      const y = horizontal ? r.y0 + b : r.y1 - 1 - along
      const i = (y * frame.width + x) * 4
      rgbToHsv(frame.data[i], frame.data[i + 1], frame.data[i + 2], hsv)
      if (inHsvRange(hsv, spec.fill)) cls[a * breadth + b] = FILL
      else if (spec.track && inHsvRange(hsv, spec.track)) cls[a * breadth + b] = TRACK
    }
  }

  // Scanlines that belong to the bar. Without a track colour every scanline counts.
  const rows: number[] = []
  for (let b = 0; b < breadth; b++) {
    let barLike = 0
    for (let a = 0; a < length; a++) if (cls[a * breadth + b]) barLike++
    if (!spec.track || barLike / length >= 0.5) rows.push(b)
  }
  if (rows.length === 0) return { kind: 'number', value: 0, confidence: 0 }

  const fillFrac = new Float32Array(length)
  const trackFrac = new Float32Array(length)
  for (let a = 0; a < length; a++) {
    let f = 0
    let t = 0
    for (const b of rows) {
      const c = cls[a * breadth + b]
      if (c === FILL) f++
      else if (c === TRACK) t++
    }
    fillFrac[a] = f / rows.length
    trackFrac[a] = t / rows.length
  }

  // Trim margin columns (neither fill nor track) at both ends, up to 8% each.
  const margin = Math.max(1, Math.floor(length * 0.08))
  const isOther = (a: number) => fillFrac[a] < 0.5 && (!spec.track || trackFrac[a] < 0.5)
  let start = 0
  while (start < margin && isOther(start)) start++
  let end = length
  while (length - end < margin && end > start + 1 && isOther(end - 1)) end--
  const span = end - start

  let agreement = 0
  for (let a = start; a < end; a++) agreement += Math.abs(fillFrac[a] - 0.5) * 2 // 1 when scanlines agree
  agreement /= span

  let run = 0
  while (run < span && fillFrac[start + run] >= 0.5) run++
  let stray = 0
  let track = 0
  for (let a = start + run; a < end; a++) {
    if (fillFrac[a] >= 0.5) stray++
    if (trackFrac[a] >= 0.5) track++
  }
  const rest = span - run
  const strayFrac = rest > 0 ? stray / rest : 0

  let confidence = clamp01(agreement * (1 - strayFrac * 2)) * Math.min(1, (rows.length / breadth) * 2)
  if (spec.track) {
    // The unfilled part must look like the bar's empty track; otherwise the
    // bar is probably not on screen at all (menu, death cam, another UI).
    confidence *= rest > 0 ? track / rest : 1
  } else if (run === 0) {
    // Without a track colour an empty bar is indistinguishable from a missing
    // one, so never report "0%" with confidence.
    confidence = Math.min(confidence, 0.4)
  }
  return { kind: 'number', value: run / span, confidence }
}

/** Fraction of the region's pixels within a colour range. */
export function coverageFraction(frame: Frame, spec: Pick<CoverageSignalSpec, 'region' | 'color'>): number {
  const r = toPixelRect(frame, spec.region)
  const hsv: Hsv = { h: 0, s: 0, v: 0 }
  let match = 0
  for (let y = r.y0; y < r.y1; y++) {
    for (let x = r.x0; x < r.x1; x++) {
      const i = (y * frame.width + x) * 4
      rgbToHsv(frame.data[i], frame.data[i + 1], frame.data[i + 2], hsv)
      if (inHsvRange(hsv, spec.color)) match++
    }
  }
  return match / ((r.x1 - r.x0) * (r.y1 - r.y0))
}

/**
 * Colour coverage as a boolean. The score is the matching fraction;
 * confidence reflects distance from the decision band between `off` and `on`:
 * a reading sitting inside the band is ambiguous.
 */
export function readCoverage(frame: Frame, spec: CoverageSignalSpec): Reading {
  const score = coverageFraction(frame, spec)
  return { kind: 'boolean', value: score >= spec.on, score, confidence: bandConfidence(score, spec.off, spec.on) }
}

/** Pearson correlation between the region (downsampled) and a stored grayscale template. */
export function readSignature(frame: Frame, spec: SignatureSignalSpec): Reading {
  const { cols, rows, values } = spec.template
  const grid = grayscaleGrid(frame, toPixelRect(frame, spec.region), cols, rows)
  const score = correlation(grid, values)
  return { kind: 'boolean', value: score >= spec.on, score, confidence: bandConfidence(score, spec.off, spec.on) }
}

export function correlation(a: ArrayLike<number>, b: ArrayLike<number>): number {
  const n = Math.min(a.length, b.length)
  let ma = 0
  let mb = 0
  for (let i = 0; i < n; i++) {
    ma += a[i]
    mb += b[i]
  }
  ma /= n
  mb /= n
  let num = 0
  let da = 0
  let db = 0
  for (let i = 0; i < n; i++) {
    const x = a[i] - ma
    const y = b[i] - mb
    num += x * y
    da += x * x
    db += y * y
  }
  // A flat region (e.g. a black loading screen) has no structure to compare.
  if (da < 1e-6 || db < 1e-6) return 0
  return num / Math.sqrt(da * db)
}

/** 0 inside the hysteresis band [off, on], rising to 1 a band-width outside it. */
function bandConfidence(score: number, off: number, on: number): number {
  const width = Math.max(on - off, 1e-3)
  if (score >= on) return clamp01(0.5 + (score - on) / width)
  if (score <= off) return clamp01(0.5 + (off - score) / width)
  return 0.25
}

function clamp01(v: number): number {
  return Math.min(1, Math.max(0, v))
}
