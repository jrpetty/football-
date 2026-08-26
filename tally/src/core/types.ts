// ---------------------------------------------------------------------------
// The shape of a night.
// ---------------------------------------------------------------------------

import type { ZRead } from './zread.ts'

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
  /**
   * The takings cash: what was in the drawer LESS the float left behind.
   *
   * This is the figure that reconciles against the till, and it always has
   * been — the float below is recorded beside it rather than folded into it,
   * so nothing downstream had to change when floats arrived.
   */
  cashPence: number | null
  /**
   * The change left in the drawer for tomorrow, already excluded from
   * `cashPence`. Absent or zero means the drawer was emptied.
   *
   * Kept because a night has to be readable back as it was counted — and
   * because a float that quietly grows is itself worth being able to see.
   */
  floatPence?: number
  note: string
  /**
   * The full Z read, when the roll was captured rather than just its total.
   *
   * Optional by design. A night entered in a hurry with three typed figures is
   * still a complete, useful record — the department detail is what makes the
   * dashboard possible, not what makes the night valid.
   */
  zRead?: ZRead
  /** Photographs of the roll, which usually takes several. */
  zPhotoIds?: string[]
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
