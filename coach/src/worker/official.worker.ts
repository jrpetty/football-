// Analyses an official overhead map image off the page's thread (a 2048 px
// image takes a few hundred milliseconds): its fingerprint, how much it looks
// like a tactical map, its playable area and what each pixel is.

import { analyzeOfficial } from '../core/map/official.ts'

export interface OverheadAnalysis {
  readonly id: string
  readonly score: number
  readonly playable: number
  readonly srcW: number
  readonly srcH: number
  readonly w: number
  readonly h: number
  readonly k: number
  readonly kind: Uint8Array
  readonly box: { readonly x0: number; readonly y0: number; readonly x1: number; readonly y1: number }
}

self.onmessage = (e: MessageEvent<{ id: number; data: Uint8ClampedArray; width: number; height: number }>) => {
  const { id, data, width, height } = e.data
  try {
    const off = analyzeOfficial(data, width, height)
    const out: OverheadAnalysis = { id: off.id, score: off.score, playable: off.playable, srcW: off.srcW, srcH: off.srcH, w: off.w, h: off.h, k: off.k, kind: off.kind, box: off.box }
    ;(self as unknown as Worker).postMessage({ id, analysis: out }, [out.kind.buffer])
  } catch (err) {
    ;(self as unknown as Worker).postMessage({ id, error: err instanceof Error ? err.message : String(err) })
  }
}
