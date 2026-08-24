// ---------------------------------------------------------------------------
// Choosing a reader, and failing usefully when none is available.
//
// Every path through this file ends somewhere she can still finish the night:
// with a figure to check, or with an empty box to type into. Nothing here is
// allowed to become a dead end, because the paper process it replaces never
// had one.
// ---------------------------------------------------------------------------

import type { ScanRequest, ScanResult } from './types.ts'
import { effectiveEngine, loadSettings } from '../storage/settings.ts'
import { scanWithVision } from './vision.ts'
import { scanWithDevice } from './device.ts'

export * from './types.ts'
export { extractTotals, bestTotal } from './extractTotal.ts'
export { releaseDeviceEngine } from './device.ts'
export { makeThumbnail } from './image.ts'

/** Raised when no reader can run, so the caller can go straight to manual entry. */
export class NoEngineError extends Error {
  constructor(public readonly reason: string) {
    super(reason)
    this.name = 'NoEngineError'
  }
}

export async function scanReceipt(req: ScanRequest): Promise<ScanResult> {
  const settings = loadSettings()
  const engine = effectiveEngine(settings)

  if (engine === 'off') {
    if (settings.engine === 'off') throw new NoEngineError('Scanning is switched off.')
    if (!settings.apiKey.trim()) throw new NoEngineError('Add an API key in Settings to scan with Claude.')
    throw new NoEngineError('No connection — type the figure in, or switch to on-device scanning.')
  }

  if (engine === 'device') return await scanWithDevice(req)

  try {
    return await scanWithVision(req)
  } catch (err) {
    if (req.signal?.aborted) throw err
    // A pub's wifi drops. Rather than making her start again, fall back to the
    // scanner that needs no connection and say plainly that it happened.
    try {
      const result = await scanWithDevice(req)
      return {
        ...result,
        notes: [`Claude could not be reached, so this was read on the phone instead.`, result.notes]
          .filter(Boolean)
          .join(' '),
      }
    } catch {
      throw err
    }
  }
}

/** Turn any failure into a sentence worth reading at midnight. */
export function describeScanError(err: unknown): string {
  if (err instanceof NoEngineError) return err.reason
  const msg = err instanceof Error ? err.message : String(err)
  if (msg === 'NO_KEY') return 'Add an Anthropic API key in Settings to scan with Claude.'
  if (msg === 'NO_ANSWER') return 'Claude did not return a reading. Type the figure in.'
  if (msg === 'ABORTED' || /abort/i.test(msg)) return 'Cancelled.'
  if (/401|authentication|invalid x-api-key/i.test(msg)) return 'That API key was rejected. Check it in Settings.'
  if (/403|permission/i.test(msg)) return 'That key cannot use this model. Try another in Settings.'
  if (/429|rate.?limit/i.test(msg)) return 'Rate limited — wait a moment and try again.'
  if (/credit|billing|quota/i.test(msg)) return 'There is a billing problem on that Anthropic account.'
  if (/CORS|Failed to fetch|NetworkError|ERR_INTERNET/i.test(msg)) return 'Could not reach the internet. Type the figure in, or switch to on-device scanning.'
  return `Could not read that photograph: ${msg}`
}
