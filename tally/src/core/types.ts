// ---------------------------------------------------------------------------
// The shape of a night.
// ---------------------------------------------------------------------------

/** Where a figure came from. Recorded so a number's provenance is never lost. */
export type CaptureSource = 'manual' | 'vision' | 'device'

/** How sure the reader was. Only ever set by an OCR engine. */
export type CaptureConfidence = 'high' | 'medium' | 'low'

/**
 * One figure that came off a receipt.
 *
 * `edited` is the interesting field. An OCR'd number she corrected by hand is
 * a different thing from one she accepted, and knowing which is which is what
 * will eventually say whether the scanning is worth keeping.
 */
export interface Capture {
  pence: number | null
  source: CaptureSource
  edited: boolean
  confidence?: CaptureConfidence
  /** Key of the stored photograph, when one was kept. */
  photoId?: string
  /** Anything the reader wanted to flag — glare, a torn roll, a partial slip. */
  notes?: string
}

export function emptyCapture(): Capture {
  return { pence: null, source: 'manual', edited: false }
}

export interface DayRecord {
  /** `YYYY-MM-DD` of the trading day. One record per day; the date is the key. */
  date: string
  till: Capture
  card: Capture
  /** The drawer count. Always typed — no receipt exists for it. */
  cashPence: number | null
  note: string
  createdAt: number
  updatedAt: number
}

export function emptyDay(date: string, now = Date.now()): DayRecord {
  return {
    date,
    till: emptyCapture(),
    card: emptyCapture(),
    cashPence: null,
    note: '',
    createdAt: now,
    updatedAt: now,
  }
}
