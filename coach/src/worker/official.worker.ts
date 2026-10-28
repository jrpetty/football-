// Official overhead map work off the page's thread (a 2048 px image takes a
// few hundred milliseconds to analyse; placing a stitched map on it about a
// second a piece): the analysis (fingerprint, how much it looks like a
// tactical map, its playable area and what each pixel is), and placing a
// saved stitched map on it after the fact.

import { analyzeOfficial, placePieces, type Alignment, type PieceImage } from '../core/map/official.ts'

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

export type OfficialRequest =
  | { readonly id: number; readonly kind: 'analyze'; readonly data: Uint8ClampedArray; readonly width: number; readonly height: number }
  | { readonly id: number; readonly kind: 'place'; readonly data: Uint8ClampedArray; readonly width: number; readonly height: number; readonly pieces: readonly (PieceImage & { readonly piece: number })[] }

export type OfficialReply =
  | { readonly id: number; readonly analysis: OverheadAnalysis }
  | { readonly id: number; readonly placed: { readonly officialId: string; readonly srcW: number; readonly srcH: number; readonly aligned: Record<string, Alignment> } }
  | { readonly id: number; readonly error: string }

const post = (msg: OfficialReply, transfer: Transferable[] = []) => (self as unknown as Worker).postMessage(msg, transfer)

self.onmessage = (e: MessageEvent<OfficialRequest>) => {
  const req = e.data
  try {
    const off = analyzeOfficial(req.data, req.width, req.height)
    if (req.kind === 'place') {
      post({ id: req.id, placed: { officialId: off.id, srcW: off.srcW, srcH: off.srcH, aligned: placePieces(off, req.pieces) } })
      return
    }
    const analysis: OverheadAnalysis = { id: off.id, score: off.score, playable: off.playable, srcW: off.srcW, srcH: off.srcH, w: off.w, h: off.h, k: off.k, kind: off.kind, box: off.box }
    post({ id: req.id, analysis }, [analysis.kind.buffer])
  } catch (err) {
    post({ id: req.id, error: err instanceof Error ? err.message : String(err) })
  }
}
