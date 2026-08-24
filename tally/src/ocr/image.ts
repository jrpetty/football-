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

/** Base64 JPEG for the vision request, with the media type it needs. */
export async function prepareForVision(file: Blob): Promise<{ data: string; mediaType: string }> {
  const { canvas } = await draw(file, VISION_MAX_EDGE)
  const dataUrl = canvas.toDataURL('image/jpeg', 0.9)
  return { data: dataUrl.slice(dataUrl.indexOf(',') + 1), mediaType: 'image/jpeg' }
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
