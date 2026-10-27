/// <reference lib="webworker" />
// The analysis worker. Decodes captured frames at analysis resolution on an
// OffscreenCanvas, runs the engine, and posts back structured results only.

import { PipelineHost } from './host.ts'
import type { ToWorker } from './protocol.ts'

declare const self: DedicatedWorkerGlobalScope

let canvas: OffscreenCanvas | null = null
let ctx: OffscreenCanvasRenderingContext2D | null = null

const host = new PipelineHost({
  post: (msg) => self.postMessage(msg),
  schedule: (fn) => setTimeout(fn, 0),
  now: () => performance.now(),
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
