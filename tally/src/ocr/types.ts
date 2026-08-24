import type { TotalCandidate, ReceiptKind } from './extractTotal.ts'

export type EngineId = 'vision' | 'device'

export interface ScanRequest {
  file: Blob
  kind: ReceiptKind
  signal?: AbortSignal
}

export interface ScanResult {
  engine: EngineId
  /** The figure to put in the editable field, or null if nothing was found. */
  pence: number | null
  confidence: 'high' | 'medium' | 'low'
  /** Other figures the receipt offered, for a "did you mean" list. */
  candidates: TotalCandidate[]
  /** Anything worth telling her — glare, a torn roll, an unlabelled guess. */
  notes: string
  /** Raw text, when the engine produced any. Shown on request, never trusted. */
  rawText?: string
}

export type { TotalCandidate, ReceiptKind }
