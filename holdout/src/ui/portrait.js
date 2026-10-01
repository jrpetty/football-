// Head-and-shoulders portraits rendered from each survivor's real 3D model,
// cached as images for the UI.
import * as THREE from 'three'
import { makeSurvivorCharacter, survivorLookKey } from '../world/agents.js'
import { compileFor } from '../render/warmup.js'

const cache = new Map()
let P = null
function setup(renderer) {
  const scene = new THREE.Scene()
  scene.add(new THREE.HemisphereLight('#e4e9ee', '#3a3028', 1.7))
  const key = new THREE.DirectionalLight('#fff4e6', 3.1)
  key.position.set(1.4, 2.6, 2.6)
  const rim = new THREE.DirectionalLight('#b4c8ff', 2.4)
  rim.position.set(-2.2, 2, -1.6)
  const fill = new THREE.DirectionalLight('#ffe2c4', 0.7)
  fill.position.set(-1.5, 1.2, 2)
  scene.add(key, rim, fill)
  const cam = new THREE.PerspectiveCamera(27, 1, 0.1, 20)
  // Render linear HDR and tone-map on the CPU with the same ACES curve as the
  // game view; without it skin clips to orange. Falls back to plain 8-bit.
  const hdr = renderer.extensions.has('EXT_color_buffer_float')
  const rt = new THREE.WebGLRenderTarget(384, 384, hdr ? { type: THREE.FloatType } : {})
  rt.texture.colorSpace = hdr ? THREE.LinearSRGBColorSpace : THREE.SRGBColorSpace
  const buf = hdr ? new Float32Array(384 * 384 * 4) : new Uint8Array(384 * 384 * 4)
  // plain CPU canvases: the pixels come from the CPU, and a GPU canvas would
  // have to be read back again to encode the image
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 384
  canvas.getContext('2d', { willReadFrequently: true })
  const out = document.createElement('canvas')
  out.width = out.height = 192
  out.getContext('2d', { willReadFrequently: true })
  P = { scene, cam, rt, buf, canvas, out, renderer, hdr }
}
// three.js ACESFilmicToneMapping (Hill's RRT+ODT fit), then the sRGB curve.
const EXPOSURE = 0.92 / 0.6
const fit = (v) => (v * (v + 0.0245786) - 0.000090537) / (v * (0.983729 * v + 0.432951) + 0.238081)
const srgb = (c) => {
  c = c < 0 ? 0 : c > 1 ? 1 : c
  return Math.round((c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055) * 255)
}
function toneMap(src, dst) {
  for (let i = 0; i < src.length; i += 4) {
    const r = src[i] * EXPOSURE
    const g = src[i + 1] * EXPOSURE
    const b = src[i + 2] * EXPOSURE
    const r1 = fit(0.59719 * r + 0.35458 * g + 0.04823 * b)
    const g1 = fit(0.076 * r + 0.90834 * g + 0.01566 * b)
    const b1 = fit(0.0284 * r + 0.13383 * g + 0.83777 * b)
    dst[i] = srgb(1.60475 * r1 - 0.53108 * g1 - 0.07367 * b1)
    dst[i + 1] = srgb(-0.10208 * r1 + 1.10813 * g1 - 0.00605 * b1)
    dst[i + 2] = srgb(-0.00327 * r1 - 0.07276 * g1 + 1.07602 * b1)
    dst[i + 3] = Math.round(Math.min(1, Math.max(0, src[i + 3])) * 255)
  }
}
// Drawing a portrait takes a render and a read back from the graphics card.
// Done on the spot, a panel full of new faces froze the game, so portraits are
// drawn one at a time between frames: until then a picture shows a quiet
// silhouette, and swaps to the face when it is ready.
const SIL = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 192 192"><defs><radialGradient id="g" cx="50%" cy="40%" r="62%"><stop offset="0" stop-color="#3d4237"/><stop offset="1" stop-color="#20241d"/></radialGradient></defs><circle cx="96" cy="80" r="33" fill="url(#g)"/><path d="M28 192c5-46 32-68 68-68s63 22 68 68z" fill="url(#g)"/></svg>'
const silhouette = typeof Blob !== 'undefined' ? new Blob([SIL], { type: 'image/svg+xml' }) : null
const pending = new Map() // look key -> { key, s, url }
const queue = []
let busy = false
const keyOf = (s) => survivorLookKey(s) + '|' + (s.look.hair?.style || '') + (s.look.hair?.color || '')

export function portrait(s, renderer) {
  if (!renderer) return ''
  const key = keyOf(s)
  const done = cache.get(key)
  if (done) return done
  let p = pending.get(key)
  if (!p) {
    p = { key, s, url: URL.createObjectURL(silhouette) }
    pending.set(key, p)
    queue.push(p)
    // not now: the panel asking for it opens first
    if (!busy) later(() => pump(renderer))
  }
  return p.url
}
// when the browser has a moment to spare
const later = (fn) => (window.requestIdleCallback ? requestIdleCallback(fn, { timeout: 400 }) : setTimeout(fn, 16))
function pump(renderer) {
  if (busy || !queue.length) return
  busy = true
  const p = queue.shift()
  draw(p.s, renderer)
    .then(
      (url) => {
        cache.set(p.key, url)
        for (const img of document.querySelectorAll('img')) if (img.src === p.url) img.src = url
        setTimeout(() => URL.revokeObjectURL(p.url), 30000)
      },
      // a face that cannot be drawn keeps its silhouette
      () => cache.set(p.key, p.url),
    )
    .finally(() => {
      pending.delete(p.key)
      // the next one a frame or so later
      busy = false
      if (queue.length) later(() => pump(renderer))
    })
}
async function draw(s, renderer) {
  if (!P) setup(renderer)
  const ch = makeSurvivorCharacter(s)
  for (let i = 0; i < 6; i++) ch.update(0.2, 'idle', { snap: true })
  ch.root.rotation.y = -0.35
  P.scene.add(ch.root)
  // a face can wear materials not drawn yet: compile them first, off the frame
  await compileFor(renderer, ch.root, P.cam, P.scene)
  const hgt = s.look.height || 1
  P.cam.position.set(0.32, 1.69 * hgt, 1.13)
  P.cam.lookAt(0, 1.585 * hgt, 0)
  P.cam.updateMatrixWorld()
  const r = renderer
  const prevRT = r.getRenderTarget()
  const prevClear = r.getClearColor(new THREE.Color())
  const prevAlpha = r.getClearAlpha()
  r.setRenderTarget(P.rt)
  r.setClearColor(0x000000, 0)
  r.clear()
  r.render(P.scene, P.cam)
  r.setRenderTarget(prevRT)
  r.setClearColor(prevClear, prevAlpha)
  // read back without stalling the frame where the renderer can
  let read = null
  try {
    if (r.readRenderTargetPixelsAsync) read = r.readRenderTargetPixelsAsync(P.rt, 0, 0, 384, 384, P.buf)
  } catch {
    read = null
  }
  if (!read) r.readRenderTargetPixels(P.rt, 0, 0, 384, 384, P.buf)
  P.scene.remove(ch.root)
  ch.dispose()
  if (read) await read
  // flip vertically into a canvas, then downscale for smooth edges
  const g = P.canvas.getContext('2d')
  const img = g.createImageData(384, 384)
  let px = P.buf
  if (P.hdr) {
    px = P.px || (P.px = new Uint8ClampedArray(384 * 384 * 4))
    toneMap(P.buf, px)
  }
  for (let y = 0; y < 384; y++) img.data.set(px.subarray((383 - y) * 384 * 4, (384 - y) * 384 * 4), y * 384 * 4)
  g.putImageData(img, 0, 0)
  const o = P.out.getContext('2d')
  o.clearRect(0, 0, 192, 192)
  o.imageSmoothingQuality = 'high'
  o.drawImage(P.canvas, 0, 0, 192, 192)
  const blob = await new Promise((res) => P.out.toBlob(res, 'image/png'))
  if (!blob) throw new Error('no portrait')
  return URL.createObjectURL(blob)
}
export function forgetPortrait(s) {
  const k0 = survivorLookKey(s)
  for (const [k, url] of [...cache.entries()]) {
    if (!k.startsWith(k0)) continue
    cache.delete(k)
    setTimeout(() => URL.revokeObjectURL(url), 30000)
  }
}
