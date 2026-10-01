// Head-and-shoulders portraits rendered from each survivor's real 3D model,
// cached as images for the UI.
import * as THREE from 'three'
import { makeSurvivorCharacter, survivorLookKey } from '../world/agents.js'

const cache = new Map()
let P = null
function setup(renderer) {
  const scene = new THREE.Scene()
  scene.add(new THREE.HemisphereLight('#dfe6ee', '#3a3028', 1.9))
  const key = new THREE.DirectionalLight('#fff0dc', 3.4)
  key.position.set(1.4, 2.6, 2.6)
  const rim = new THREE.DirectionalLight('#a8c0ff', 2.6)
  rim.position.set(-2.2, 2, -1.6)
  const fill = new THREE.DirectionalLight('#ffd8b0', 0.8)
  fill.position.set(-1.5, 1.2, 2)
  scene.add(key, rim, fill)
  const cam = new THREE.PerspectiveCamera(27, 1, 0.1, 20)
  const rt = new THREE.WebGLRenderTarget(384, 384)
  rt.texture.colorSpace = THREE.SRGBColorSpace
  const buf = new Uint8Array(384 * 384 * 4)
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 384
  const out = document.createElement('canvas')
  out.width = out.height = 192
  P = { scene, cam, rt, buf, canvas, out, renderer }
}
export function portrait(s, renderer) {
  if (!renderer) return ''
  const key = survivorLookKey(s) + '|' + (s.look.hair?.style || '') + (s.look.hair?.color || '')
  if (cache.has(key)) return cache.get(key)
  if (!P) setup(renderer)
  const ch = makeSurvivorCharacter(s)
  for (let i = 0; i < 6; i++) ch.update(0.2, 'idle', { snap: true })
  ch.root.rotation.y = -0.35
  P.scene.add(ch.root)
  const hgt = s.look.height || 1
  P.cam.position.set(0.3, 1.7 * hgt, 1.05)
  P.cam.lookAt(0, 1.6 * hgt, 0)
  P.cam.updateMatrixWorld()
  const r = renderer
  const prevRT = r.getRenderTarget()
  const prevClear = r.getClearColor(new THREE.Color())
  const prevAlpha = r.getClearAlpha()
  r.setRenderTarget(P.rt)
  r.setClearColor(0x000000, 0)
  r.clear()
  r.render(P.scene, P.cam)
  r.readRenderTargetPixels(P.rt, 0, 0, 384, 384, P.buf)
  r.setRenderTarget(prevRT)
  r.setClearColor(prevClear, prevAlpha)
  P.scene.remove(ch.root)
  ch.dispose()
  // flip vertically into a canvas, then downscale for smooth edges
  const g = P.canvas.getContext('2d')
  const img = g.createImageData(384, 384)
  for (let y = 0; y < 384; y++) img.data.set(P.buf.subarray((383 - y) * 384 * 4, (384 - y) * 384 * 4), y * 384 * 4)
  g.putImageData(img, 0, 0)
  const o = P.out.getContext('2d')
  o.clearRect(0, 0, 192, 192)
  o.imageSmoothingQuality = 'high'
  o.drawImage(P.canvas, 0, 0, 192, 192)
  const url = P.out.toDataURL('image/png')
  cache.set(key, url)
  return url
}
export function forgetPortrait(s) {
  for (const k of [...cache.keys()]) if (k.startsWith(survivorLookKey(s))) cache.delete(k)
}
