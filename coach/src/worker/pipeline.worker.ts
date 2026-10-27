/// <reference lib="webworker" />
// The analysis worker. Decodes captured frames on an OffscreenCanvas, runs the
// engine, and posts back structured results. With cloud vision on, it also
// encodes the occasional sampled frame — downscaled, or only the pack's
// declared crops — as a JPEG for the main process.

import type { GameProfile } from '../core/profile/schema.ts'
import type { Frame } from '../core/types.ts'
import { PipelineHost } from './host.ts'
import type { ToWorker } from './protocol.ts'

declare const self: DedicatedWorkerGlobalScope

let canvas: OffscreenCanvas | null = null
let ctx: OffscreenCanvasRenderingContext2D | null = null

async function encodeJpeg(frame: Frame, profile: GameProfile): Promise<ArrayBuffer> {
  const v = profile.vision!
  const source = new OffscreenCanvas(frame.width, frame.height)
  source.getContext('2d')!.putImageData(new ImageData(new Uint8ClampedArray(frame.data), frame.width, frame.height), 0, 0)
  // Either the whole frame, or the pack's crops stacked top to bottom.
  const parts = v.crops?.length
    ? v.crops.map((c) => ({ sx: c.region.x * frame.width, sy: c.region.y * frame.height, sw: c.region.w * frame.width, sh: c.region.h * frame.height }))
    : [{ sx: 0, sy: 0, sw: frame.width, sh: frame.height }]
  const width = Math.min(v.maxWidth, Math.max(...parts.map((p) => p.sw)))
  const heights = parts.map((p) => Math.round((p.sh * width) / p.sw))
  const out = new OffscreenCanvas(width, heights.reduce((a, b) => a + b, 0))
  const octx = out.getContext('2d')!
  let y = 0
  parts.forEach((p, i) => {
    octx.drawImage(source, p.sx, p.sy, p.sw, p.sh, 0, y, width, heights[i])
    y += heights[i]
  })
  const blob = await out.convertToBlob({ type: 'image/jpeg', quality: 0.75 })
  return blob.arrayBuffer()
}

const host = new PipelineHost({
  post: (msg, transfer = []) => self.postMessage(msg, transfer),
  schedule: (fn) => setTimeout(fn, 0),
  now: () => performance.now(),
  encodeJpeg,
  decode(bitmap, timestamp) {
    if (!canvas || canvas.width !== bitmap.width || canvas.height !== bitmap.height) {
      canvas = new OffscreenCanvas(bitmap.width, bitmap.height)
      ctx = canvas.getContext('2d', { willReadFrequently: true })
    }
    if (!ctx) throw new Error('2D canvas is unavailable in the analysis worker')
    ctx.drawImage(bitmap, 0, 0)
    const image = ctx.getImageData(0, 0, bitmap.width, bitmap.height)
    return { width: image.width, height: image.height, data: image.data, timestamp }
  },
})

self.onmessage = (e: MessageEvent<ToWorker>) => host.handle(e.data)
