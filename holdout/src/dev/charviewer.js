// Dev harness: renders characters with the new pipeline for screenshots.
import * as THREE from 'three'
import { Pipeline } from '../render/pipeline.js'
import { Atmosphere } from '../render/sky.js'
import { initView, view } from '../render/view.js'
import { Character, OUTFITS, SKIN_TONES, HAIR_COLORS, zombieOutfit, rngFrom } from '../models/character.js'
import { weaponModel, holdStyle } from '../models/weapons.js'
import { Builder } from '../models/kit.js'
import { pregenerate } from '../render/texgen.js'

const canvas = document.getElementById('c')
const pipe = new Pipeline(canvas)
initView(canvas)
const q = new URLSearchParams(location.search)
pipe.setQuality(q.get('q') || 'high')
pipe.resize(window.innerWidth, window.innerHeight)
view.camera.aspect = window.innerWidth / window.innerHeight
view.camera.updateProjectionMatrix()
await pregenerate()
const scene = new THREE.Scene()
const atmo = new Atmosphere(scene, pipe.renderer)
const b = new Builder()
b.box(30, 0.1, 30, { mat: q.get('plainground') ? 'plain' : 'dirt', color: q.get('plainground') ? '#8a7a60' : '#ffffff', y: -0.05, ao: 0 })
b.box(4, 0.02, 4, { mat: 'planks', color: '#ffffff', y: 0.01, x: 3, z: -2, ao: 0 })
b.box(2, 1, 1, { mat: 'wood', color: '#c8a070', x: -3, y: 0.5, z: -3, r: 0.03 })
b.cyl(0.4, 0.4, 1, { mat: 'rust', color: '#ffffff', x: -5, y: 0.5, z: -2 })
b.box(2, 1.2, 0.1, { mat: 'brick', color: '#ffffff', x: 5, y: 0.6, z: -4 })
b.box(2, 1.2, 0.1, { mat: 'corrugated', color: '#c0c4c8', x: 2, y: 0.6, z: -4.5 })
const ground = b.build()
scene.add(ground)
const chars = []
const occs = Object.keys(OUTFITS)
const anims = ['idle', 'walk', 'run', 'aim', 'swing', 'search', 'hammer', 'sit', 'lookout', 'hoe', 'type', 'wave']
const weapons = ['pistol', 'rifle', 'ar', 'shotgun', 'bat', 'axe', 'katana', 'machete', 'smg', 'revolver', 'crossbow', 'nailbat']
const mode = q.get('mode') || 'outfits'
const r = rngFrom(7)
if (mode === 'outfits') {
  occs.forEach((occ, i) => {
    const ch = new Character({ skin: SKIN_TONES[i % SKIN_TONES.length], hair: { style: ['short', 'long', 'ponytail', 'buzz', 'curly', 'side', 'bun', 'bald'][i % 8], color: HAIR_COLORS[i % HAIR_COLORS.length] }, face: { beard: i % 5 === 0 ? 'beard' : i % 7 === 0 ? 'mustache' : null }, build: 0.95 + (i % 3) * 0.08, female: i % 2 === 1, outfit: OUTFITS[occ], seed: i + 1, pack: occ === 'student' ? null : i % 6 === 0 ? 'small' : null })
    ch.root.position.set((i % 8) * 1.3 - 4.5, 0, Math.floor(i / 8) * 1.6 - 1.5)
    scene.add(ch.root)
    ch.anim = 'idle'
    chars.push(ch)
  })
} else if (mode === 'anims') {
  anims.forEach((a, i) => {
    const ch = new Character({ skin: SKIN_TONES[i % 6], hair: { style: 'short', color: HAIR_COLORS[i % 6] }, build: 1, outfit: OUTFITS[occs[i * 2 % occs.length]], seed: i + 3 })
    const wid = weapons[i % weapons.length]
    const kind = ['pistol', 'rifle', 'ar', 'shotgun', 'smg', 'revolver', 'crossbow'].includes(wid) ? 'gun' : 'melee'
    ch.setWeapon(weaponModel(wid, i % 3 === 0 ? ['suppressor'] : i % 3 === 1 ? ['scope'] : []), holdStyle(wid, kind))
    ch.root.position.set((i % 6) * 1.6 - 4, 0, Math.floor(i / 6) * 2 - 1)
    scene.add(ch.root)
    ch.anim = a
    chars.push(ch)
  })
} else {
  const kinds = ['walker', 'walker', 'runner', 'brute', 'crawler', 'walker', 'walker', 'walker']
  const themes = [null, 'hospital', null, null, null, 'police', 'riot', 'military', 'worker', 'hospital', null, 'chef']
  themes.forEach((th, i) => {
    const kind = kinds[i % kinds.length]
    const ch = new Character({ skin: ['#8f9a80', '#7d8a74', '#9a9e8a', '#76826e'][i % 4], hair: { style: ['short', 'long', 'bald', 'buzz'][i % 4], color: '#3a3028' }, build: kind === 'brute' ? 1.35 : kind === 'runner' ? 0.88 : 1, outfit: zombieOutfit(th, rngFrom(i + 11)), seed: i + 20, zombie: { kind } })
    ch.root.position.set((i % 6) * 1.5 - 4, 0, Math.floor(i / 6) * 2 - 1)
    if (kind === 'brute') ch.root.scale.setScalar(1.25)
    scene.add(ch.root)
    ch.anim = kind === 'crawler' ? 'zcrawl' : ['zidle', 'zwalk', 'zattack', 'zrun'][i % 4]
    if (kind === 'crawler') ch.mesh.rotation.x = Math.PI / 2 * 0.95, ch.mesh.position.y = 0.25
    chars.push(ch)
  })
}
if (mode === 'portraits') {
  // head-and-shoulders renders like the UI portraits, in a grid
  const ps = new THREE.Scene()
  ps.add(new THREE.HemisphereLight('#dfe6ee', '#3a3028', 1.9))
  const key = new THREE.DirectionalLight('#fff0dc', 3.4)
  key.position.set(1.4, 2.6, 2.6)
  const rim = new THREE.DirectionalLight('#a8c0ff', 2.6)
  rim.position.set(-2.2, 2, -1.6)
  const fill = new THREE.DirectionalLight('#ffd8b0', 0.8)
  fill.position.set(-1.5, 1.2, 2)
  ps.add(key, rim, fill)
  const cam = new THREE.PerspectiveCamera(27, 1, 0.1, 20)
  const R = pipe.renderer
  const grid = document.createElement('canvas')
  const N = 256
  const cols = 8
  const list = occs.slice(0, 16)
  grid.width = cols * N
  grid.height = Math.ceil(list.length / cols) * N
  grid.style.cssText = 'position:fixed;left:0;top:0;width:100%;z-index:5;background:#223'
  document.body.appendChild(grid)
  const g2 = grid.getContext('2d')
  const rt = new THREE.WebGLRenderTarget(N, N)
  rt.texture.colorSpace = THREE.SRGBColorSpace
  const buf = new Uint8Array(N * N * 4)
  const zmb = q.get('z')
  list.forEach((occ, i) => {
    const spec = zmb
      ? { skin: ['#8f9a80', '#7d8a74', '#9a9e8a', '#76826e'][i % 4], hair: { style: ['short', 'long', 'bald', 'buzz', 'curly', 'side'][i % 6], color: '#3a3028' }, build: 1, female: i % 3 === 1, outfit: zombieOutfit([null, 'hospital', 'police', 'worker', 'military', 'chef'][i % 6], rngFrom(i + 11)), seed: i + 20, zombie: { kind: 'walker' } }
      : { skin: SKIN_TONES[i % SKIN_TONES.length], hair: { style: ['short', 'long', 'ponytail', 'buzz', 'curly', 'side', 'bun', 'bald', 'mohawk'][i % 9], color: HAIR_COLORS[i % HAIR_COLORS.length] }, face: { beard: [null, 'beard', null, 'mustache', 'goatee', null][i % 6] }, build: 0.95 + (i % 3) * 0.08, female: i % 2 === 1, outfit: OUTFITS[occ], seed: i + 1 }
    const ch = new Character(spec)
    for (let k = 0; k < 6; k++) ch.update(0.2, 'idle', { snap: true })
    ch.root.rotation.y = -0.35
    ps.add(ch.root)
    cam.position.set(0.3, 1.7, 1.05)
    cam.lookAt(0, 1.6, 0)
    R.setRenderTarget(rt)
    R.setClearColor(0x2a3040, 1)
    R.clear()
    R.render(ps, cam)
    R.readRenderTargetPixels(rt, 0, 0, N, N, buf)
    R.setRenderTarget(null)
    ps.remove(ch.root)
    const img = g2.createImageData(N, N)
    for (let y = 0; y < N; y++) img.data.set(buf.subarray((N - 1 - y) * N * 4, (N - y) * N * 4), y * N * 4)
    g2.putImageData(img, (i % cols) * N, Math.floor(i / cols) * N)
  })
}
const hour = parseFloat(q.get('h') || '15')
view.rig.minDist = 0.6
view.rig.jump(0, 0, parseFloat(q.get('d') || '9'))
view.rig.yaw = view.rig.yawGoal = parseFloat(q.get('yaw') || '0.5')
let last = performance.now()
function frame(t) {
  const dt = Math.max(0, Math.min(0.05, (t - last) / 1000))
  last = t
  view.rig.update(dt)
  const a = atmo.update(hour, view.rig.target, view.rig.dist, dt)
  pipe.exposure = a.exposure * (parseFloat(q.get('exp') || '1'))
  if (q.get('noenv')) scene.environment = null
  if (q.get('noshadow')) atmo.sun.castShadow = false
  if (q.get('dbg') && !window.__dbg) {
    window.__dbg = 1
    const px = new Float32Array(4)
    console.log('sun', atmo.sun.intensity.toFixed(2), atmo.sun.position.toArray().map((v) => v.toFixed(1)).join(','), 'hemi', atmo.hemi.intensity.toFixed(2), 'env', scene.environmentIntensity, 'exp', a.exposure)
  }
  for (const ch of chars) ch.update(dt, null, { speed: ch.anim === 'walk' || ch.anim === 'zwalk' ? 1.4 : ch.anim === 'run' || ch.anim === 'zrun' ? 3.6 : ch.anim === 'zcrawl' ? 0.6 : 0, swing: (t / 1000) % 1 })
  pipe.render(scene, view.camera, dt)
  requestAnimationFrame(frame)
}
requestAnimationFrame(frame)
window.__ready = true
window.__cam = (x, z, d, yaw) => {
  view.rig.jump(x, z, d)
  if (yaw != null) view.rig.yaw = view.rig.yawGoal = yaw
}
