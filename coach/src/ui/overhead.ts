// Official overhead map images in the page: decoding what the player imports
// or downloads, re-encoding it as PNG for keeping, telling which map a file is
// from its name, and analysing it (in a worker) to check it is a tactical map.

import type { GameMap } from '../core/profile/schema.ts'
import type { OverheadAnalysis } from '../worker/official.worker.ts'

export type { OverheadAnalysis }

/** An image as RGBA. */
export interface DecodedImage {
  readonly data: Uint8ClampedArray
  readonly width: number
  readonly height: number
}

/** Larger images are scaled down to this side (the analysis works at 1024 px; the view needs no more). */
const MAX_SIDE = 4096
/** How much an image must look like a tactical map to be kept (the real maps score 0.88–1, a screenshot 0.33). */
export const MIN_OVERHEAD_SCORE = 0.8

function canvas(w: number, h: number): OffscreenCanvas | HTMLCanvasElement {
  if (typeof OffscreenCanvas !== 'undefined') return new OffscreenCanvas(w, h)
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  return c
}

/** Decodes PNG, JPEG or WebP bytes (or a file). */
export async function decodeImage(src: Blob | Uint8Array): Promise<DecodedImage> {
  const blob = src instanceof Blob ? src : new Blob([src as Uint8Array<ArrayBuffer>])
  const bmp = await createImageBitmap(blob)
  try {
    const f = Math.min(1, MAX_SIDE / Math.max(bmp.width, bmp.height))
    const w = Math.max(1, Math.round(bmp.width * f))
    const h = Math.max(1, Math.round(bmp.height * f))
    const c = canvas(w, h)
    const ctx = c.getContext('2d', { willReadFrequently: true }) as OffscreenCanvasRenderingContext2D | CanvasRenderingContext2D | null
    if (!ctx) throw new Error('No 2D canvas.')
    ctx.drawImage(bmp, 0, 0, w, h)
    return { data: ctx.getImageData(0, 0, w, h).data, width: w, height: h }
  } finally {
    bmp.close()
  }
}

/** Re-encodes an image as PNG (what is kept on disk, whatever was imported). */
export async function encodePng(img: DecodedImage): Promise<Uint8Array> {
  const c = canvas(img.width, img.height)
  const ctx = c.getContext('2d') as OffscreenCanvasRenderingContext2D | CanvasRenderingContext2D | null
  if (!ctx) throw new Error('No 2D canvas.')
  ctx.putImageData(new ImageData(new Uint8ClampedArray(img.data), img.width, img.height), 0, 0)
  const blob = c instanceof HTMLCanvasElement ? await new Promise<Blob | null>((res) => c.toBlob(res, 'image/png')) : await c.convertToBlob({ type: 'image/png' })
  if (!blob) throw new Error('Could not encode the image.')
  return new Uint8Array(await blob.arrayBuffer())
}

const slug = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()

/** Which map a file is, from its name ("cachette.webp", "MW4 Reactor U92 tac map.png"); the longest name that fits wins. */
export function mapForFile(fileName: string, maps: readonly GameMap[]): string | null {
  const name = ` ${slug(fileName.replace(/\.[a-z0-9]+$/i, ''))} `
  let best: { id: string; len: number } | null = null
  for (const m of maps)
    for (const n of [m.id, m.name, ...(m.aliases ?? [])]) {
      const k = slug(n)
      if (k.length >= 3 && name.includes(` ${k} `) && (!best || k.length > best.len)) best = { id: m.id, len: k.length }
    }
  return best?.id ?? null
}

let worker: Worker | null = null
let nextId = 1
const waiting = new Map<number, { resolve: (a: OverheadAnalysis) => void; reject: (e: Error) => void }>()

/** Analyses an image in a worker. The image's pixels are copied (the caller keeps them). */
export function analyzeOverhead(img: DecodedImage): Promise<OverheadAnalysis> {
  if (!worker) {
    worker = new Worker(new URL('../worker/official.worker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = (e: MessageEvent<{ id: number; analysis?: OverheadAnalysis; error?: string }>) => {
      const w = waiting.get(e.data.id)
      waiting.delete(e.data.id)
      if (!w) return
      if (e.data.analysis) w.resolve(e.data.analysis)
      else w.reject(new Error(e.data.error ?? 'Analysis failed.'))
    }
    worker.onerror = (e) => {
      for (const w of waiting.values()) w.reject(new Error(e.message || 'Analysis failed.'))
      waiting.clear()
      worker?.terminate()
      worker = null
    }
  }
  const id = nextId++
  const data = new Uint8ClampedArray(img.data)
  return new Promise((resolve, reject) => {
    waiting.set(id, { resolve, reject })
    worker!.postMessage({ id, data, width: img.width, height: img.height }, [data.buffer])
  })
}
