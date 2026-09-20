// ---------------------------------------------------------------------------
// Getting a phone photograph into a state something can read.
//
// A modern phone shoots 4000px and several megabytes. Sent whole that is slow
// on pub wifi, costs tokens for nothing, and can exceed the request limit
// outright. It is also, for a till roll, mostly bar towel.
// ---------------------------------------------------------------------------

/** The long edge Anthropic recommends; beyond it, cost rises and accuracy does not. */
const VISION_MAX_EDGE = 1568

/** The on-device scanner wants more pixels per character, not fewer. */
const DEVICE_MAX_EDGE = 2200

/** Small enough to keep hundreds of nights in the browser without thinking about it. */
const THUMB_MAX_EDGE = 900

async function draw(
  file: Blob,
  maxEdge: number,
): Promise<{ canvas: HTMLCanvasElement; ctx: CanvasRenderingContext2D }> {
  const bitmap = await createImageBitmap(file)
  const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height))
  const width = Math.max(1, Math.round(bitmap.width * scale))
  const height = Math.max(1, Math.round(bitmap.height * scale))

  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  if (!ctx) throw new Error('This browser would not open that photograph.')
  ctx.drawImage(bitmap, 0, 0, width, height)
  bitmap.close?.()
  return { canvas, ctx }
}

/**
 * How many slices a photograph of this shape needs.
 *
 * A till roll is a ribbon, and a photograph of one is tall and thin. Squashing
 * the long edge down to 1568 to fit the vision limit throws away most of the
 * height, and the first thing to go is the smallest print on the roll — the
 * item list. The department block is printed large and survives it, which is
 * why a shrunken photograph reads as a roll with categories and no drinks on
 * it, rather than as a photograph that failed.
 *
 * So a tall photograph is cut into overlapping bands instead, each sent at a
 * size the small print survives. Three requests for a long roll rather than
 * one, at a fraction of a penny each, to read the part of the receipt the whole
 * cellar hangs off.
 */
export function slicesFor(width: number, height: number, _maxEdge = VISION_MAX_EDGE): number {
  if (width <= 0 || height <= 0) return 1
  const ratio = Math.max(width, height) / Math.min(width, height)
  // A squarish photograph loses little by being sent whole, and an extra request
  // for a few per cent more resolution is not worth making. Past about five to
  // four it is, and an ordinary portrait snap of a receipt is four to three.
  if (ratio < 1.25) return 1
  return Math.max(2, Math.min(MAX_SLICES, Math.ceil(ratio)))
}

/** Beyond this a photograph is of something other than one till roll. */
const MAX_SLICES = 4

/** How much of each band repeats the one before, so no line falls down a join. */
const OVERLAP = 0.12

/**
 * The photograph, ready to send: one image, or a tall one cut into bands.
 *
 * Each band is scaled so its own long edge is at most the vision limit, which
 * for a tall photograph means the characters arrive several times the size they
 * would whole.
 */
export async function prepareForVision(file: Blob): Promise<Array<{ data: string; mediaType: string }>> {
  const bitmap = await createImageBitmap(file)
  const { width, height } = bitmap
  const slices = slicesFor(width, height)

  if (slices === 1) {
    bitmap.close?.()
    const { canvas } = await draw(file, VISION_MAX_EDGE)
    const dataUrl = canvas.toDataURL('image/jpeg', 0.9)
    return [{ data: dataUrl.slice(dataUrl.indexOf(',') + 1), mediaType: 'image/jpeg' }]
  }

  // Bands run down the long side, whichever way up the photograph is.
  const tall = height >= width
  const along = tall ? height : width
  const across = tall ? width : height
  const band = Math.ceil(along / slices)
  const bleed = Math.round(band * OVERLAP)

  const out: Array<{ data: string; mediaType: string }> = []
  for (let i = 0; i < slices; i += 1) {
    const from = Math.max(0, i * band - bleed)
    const to = Math.min(along, (i + 1) * band + bleed)
    const cutAlong = to - from
    const scale = Math.min(1, VISION_MAX_EDGE / Math.max(across, cutAlong))
    const w = Math.max(1, Math.round((tall ? across : cutAlong) * scale))
    const h = Math.max(1, Math.round((tall ? cutAlong : across) * scale))

    const canvas = document.createElement('canvas')
    canvas.width = w
    canvas.height = h
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('This browser would not open that photograph.')
    ctx.drawImage(
      bitmap,
      tall ? 0 : from, tall ? from : 0,
      tall ? across : cutAlong, tall ? cutAlong : across,
      0, 0, w, h,
    )
    const dataUrl = canvas.toDataURL('image/jpeg', 0.9)
    out.push({ data: dataUrl.slice(dataUrl.indexOf(',') + 1), mediaType: 'image/jpeg' })
  }
  bitmap.close?.()
  return out
}

/**
 * One image, whatever shape the photograph is.
 *
 * For the card slip and the price board, which are not ribbons of paper and do
 * not carry a column of small print that has to survive.
 */
export async function prepareOneForVision(file: Blob): Promise<{ data: string; mediaType: string }> {
  const { canvas } = await draw(file, VISION_MAX_EDGE)
  const dataUrl = canvas.toDataURL('image/jpeg', 0.9)
  return { data: dataUrl.slice(dataUrl.indexOf(',') + 1), mediaType: 'image/jpeg' }
}

/**
 * Join the bands back into one transcription.
 *
 * The bands overlap on purpose, so the seam repeats lines. Left in, a repeated
 * PLU line would be counted twice and the night would read high. The longest
 * run of lines that ends one band and begins the next is the seam, and it is
 * dropped from the second.
 */
export function joinSlices(parts: readonly string[]): string {
  const clean = parts.map((p) => p.split(/\r?\n/).map((l) => l.trimEnd()))
  let out: string[] = clean[0] ?? []
  for (const next of clean.slice(1)) {
    const most = Math.min(out.length, next.length, 60)
    let seam = 0
    for (let n = most; n > 0; n -= 1) {
      const tail = out.slice(out.length - n).filter((l) => l.trim())
      const head = next.slice(0, n).filter((l) => l.trim())
      if (tail.length === 0 || tail.length !== head.length) continue
      if (tail.every((l, i) => l.trim() === head[i]?.trim())) {
        seam = n
        break
      }
    }
    out = [...out, ...next.slice(seam)]
  }
  return out.join('\n')
}

/**
 * Grey, stretched and hardened for the on-device scanner.
 *
 * Thermal paper is the difficult case: low contrast to begin with, and fading
 * as it ages. Tesseract wants black text on white, so the image is converted to
 * luminance, the actual range present is stretched back out to full black and
 * white, and the result is pushed towards a threshold. This is the single
 * biggest lever on whether the on-device path reads a faded roll at all.
 */
export async function prepareForDevice(file: Blob): Promise<Blob> {
  const { canvas, ctx } = await draw(file, DEVICE_MAX_EDGE)
  const img = ctx.getImageData(0, 0, canvas.width, canvas.height)
  const px = img.data

  const grey = new Uint8ClampedArray(px.length / 4)
  for (let i = 0, g = 0; i < px.length; i += 4, g++) {
    grey[g] = (px[i]! * 0.299 + px[i + 1]! * 0.587 + px[i + 2]! * 0.114) | 0
  }

  // Percentile rather than min/max: a single glare highlight or a dark fold
  // would otherwise define the range and flatten everything between them.
  const histogram = new Uint32Array(256)
  for (const v of grey) histogram[v]!++
  const total = grey.length
  const at = (fraction: number): number => {
    let seen = 0
    const target = total * fraction
    for (let v = 0; v < 256; v++) {
      seen += histogram[v]!
      if (seen >= target) return v
    }
    return 255
  }
  const low = at(0.02)
  const high = at(0.98)
  const span = Math.max(1, high - low)

  for (let i = 0, g = 0; i < px.length; i += 4, g++) {
    let v = ((grey[g]! - low) / span) * 255
    // A gentle S-curve: darkens ink, lifts paper, without the information loss
    // of a hard threshold on a photograph lit unevenly across the roll.
    v = v < 128 ? (v * v) / 128 : 255 - ((255 - v) * (255 - v)) / 128
    const c = v < 0 ? 0 : v > 255 ? 255 : v
    px[i] = px[i + 1] = px[i + 2] = c
    px[i + 3] = 255
  }
  ctx.putImageData(img, 0, 0)

  return await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error('Could not prepare that photograph.'))),
      'image/png',
    )
  })
}

/** A small JPEG to keep with the record, so a disputed night can be checked. */
export async function makeThumbnail(file: Blob): Promise<Blob> {
  const { canvas } = await draw(file, THUMB_MAX_EDGE)
  return await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error('Could not shrink that photograph.'))),
      'image/jpeg',
      0.7,
    )
  })
}
