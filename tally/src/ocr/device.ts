// ---------------------------------------------------------------------------
// Reading a receipt on the phone itself.
//
// The offline half of the OCR decision. Tesseract runs as WebAssembly in the
// browser: no key, no account, no per-scan cost, and nothing leaves the
// premises. What it does not do well is thermal receipt paper — see the
// comparison in the README — so it is the fallback rather than the default,
// and its confidence is reported honestly enough that the confirm screen
// treats its answer as a suggestion to check rather than a figure to accept.
//
// The engine and its language data are fetched on first use and then cached by
// the service worker, so the first scan needs a connection and later ones do
// not.
// ---------------------------------------------------------------------------

import type { ScanRequest, ScanResult } from './types.ts'
import { extractTotals } from './extractTotal.ts'
import { prepareForDevice } from './image.ts'

type Worker = {
  recognize: (image: Blob) => Promise<{ data: { text: string; confidence: number } }>
  setParameters: (p: Record<string, unknown>) => Promise<unknown>
  terminate: () => Promise<unknown>
}

let workerPromise: Promise<Worker> | null = null

/**
 * One worker, kept alive between scans.
 *
 * Starting it means downloading and compiling several megabytes of WASM; doing
 * that twice a night, once per receipt, would be the slowest part of the whole
 * app.
 */
async function getWorker(): Promise<Worker> {
  if (!workerPromise) {
    workerPromise = (async () => {
      const { createWorker } = await import('tesseract.js')
      const worker = (await createWorker('eng')) as unknown as Worker
      await worker.setParameters({
        // A receipt is one narrow column of text in varying sizes, which is
        // exactly what this mode is for. The default assumes a page.
        tessedit_pageseg_mode: '4',
        // Keeps the scanner from reporting whole paragraphs of confident
        // nonsense from the background of the photograph.
        preserve_interword_spaces: '1',
      })
      return worker
    })().catch((err) => {
      workerPromise = null
      throw err
    })
  }
  return workerPromise
}

/** Release the engine — worth doing when she leaves the scanning screen. */
export async function releaseDeviceEngine(): Promise<void> {
  const p = workerPromise
  workerPromise = null
  if (!p) return
  try {
    await (await p).terminate()
  } catch {
    /* already gone */
  }
}

/** Tesseract's own 0–100 score, mapped conservatively. */
function gradeConfidence(score: number, guessed: boolean): 'high' | 'medium' | 'low' {
  if (guessed) return 'low'
  if (score >= 85) return 'medium'
  return 'low'
}

export async function scanWithDevice(req: ScanRequest): Promise<ScanResult> {
  const prepared = await prepareForDevice(req.file)
  const worker = await getWorker()
  const { data } = await worker.recognize(prepared)

  if (req.signal?.aborted) throw new Error('ABORTED')

  const candidates = extractTotals(data.text ?? '', req.kind)
  const best = candidates[0]

  if (!best) {
    return {
      engine: 'device',
      pence: null,
      confidence: 'low',
      candidates: [],
      notes: 'Nothing on that photograph read as a total. Type the figure in, or try Claude.',
      rawText: data.text,
    }
  }

  return {
    engine: 'device',
    pence: best.pence,
    confidence: gradeConfidence(data.confidence ?? 0, best.guessed),
    candidates,
    notes: best.guessed
      ? 'No total was labelled on that scan — this is the largest amount found, so please check it.'
      : '',
    rawText: data.text,
  }
}
