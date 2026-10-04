// Dev harness: the first-person arms and weapon over a simple lit street,
// for screenshots while modelling. window.__show(id, look, opts) swaps them.
import * as THREE from 'three'
import { Pipeline } from '../render/pipeline.js'
import { Atmosphere, nightFactor } from '../render/sky.js'
import { initView, view } from '../render/view.js'
import { Builder } from '../models/kit.js'
import { pregenerate } from '../render/texgen.js'
import { setNightGlow, INDOOR } from '../render/materials.js'
import { viewModel } from '../models/viewmodel.js'
import { VMLayer } from '../render/vmlayer.js'
import { OUTFITS } from '../models/character.js'
import { pineModel } from '../models/nature.js'

const canvas = document.getElementById('c')
const pipe = new Pipeline(canvas)
initView(canvas)
const q = new URLSearchParams(location.search)
pipe.setQuality(q.get('q') || 'high')
pipe.resize(window.innerWidth, window.innerHeight)
const cam = view.camera
cam.fov = 70
cam.near = 0.05
cam.aspect = window.innerWidth / window.innerHeight
cam.updateProjectionMatrix()
await pregenerate()
const scene = new THREE.Scene()
const atmo = new Atmosphere(scene, pipe.renderer)
const b = new Builder()
b.box(80, 0.1, 80, { mat: 'asphalt', color: '#8a8a88', y: -0.05, ao: 0 })
for (let i = 0; i < 6; i++) b.box(6, 3 + (i % 3), 4, { mat: i % 2 ? 'brick' : 'siding', color: i % 2 ? '#a86a50' : '#c8c0a8', x: -12 + i * 6, y: 1.5 + (i % 3) / 2, z: -14 })
b.box(1.2, 1, 1.2, { mat: 'wood', color: '#8a6a4a', x: 1.5, y: 0.5, z: -4 })
scene.add(b.build())
for (let i = 0; i < 5; i++) {
  const t = pineModel(11 + i)
  t.position.set(-10 + i * 5, 0, -24)
  scene.add(t)
}
const layer = new VMLayer()
pipe.overlay = layer
pipe.firstPerson = true
layer.visible = true
let cur = null
const hour = parseFloat(q.get('h') || '15')
window.__show = (id, occ = 'drifter', skin = '#d39d76', o = {}) => {
  if (cur) layer.root.remove(cur.rig)
  const top = (OUTFITS[occ] || OUTFITS.drifter).top
  cur = viewModel(id, o.mods || [], { skin, top, gloves: o.gloves === undefined ? '#3a2e24' : o.gloves, female: !!o.female })
  if (o.ads && cur.pose.ads) cur.rig.position.set(-cur.pose.pos[0] + cur.pose.ads[0], -cur.pose.pos[1] + cur.pose.ads[1] - 0, 0)
  layer.root.add(cur.rig)
  return true
}
window.__show(q.get('w') || 'pistol')
// look at the view model from elsewhere (close-ups of the hands)
window.__vmcam = (p, t, fov = 54) => {
  layer.camera.position.set(...p)
  layer.camera.lookAt(...t)
  layer.camera.fov = fov
  layer.camera.updateProjectionMatrix()
}
cam.position.set(0, 1.62, 0)
cam.lookAt(0, 1.5, -10)
let last = performance.now()
function frame(now) {
  const dt = Math.max(0, Math.min(0.05, (now - last) / 1000))
  last = now
  const a = atmo.update(hour, new THREE.Vector3(0, 0, -10), 25, dt)
  pipe.exposure = a.exposure
  setNightGlow(nightFactor(hour))
  layer.setAspect(cam.aspect)
  layer.sync(scene, atmo, cam, 1)
  const D = window.__dbgL || {}
  if (D.noEnv) layer.scene.environment = null
  if (D.noSun) layer.sun.intensity = 0
  if (D.noHemi) layer.hemi.intensity = 0
  pipe.render(scene, cam, dt)
}
window.__frame = () => frame(performance.now())
window.__dbgLayer = layer
window.__indoor = (on) => {
  INDOOR.uIndoorK.value = on ? 1 : 0
  INDOOR.uIndoorN.value = 1
  INDOOR.uIndoorR.value[0].set(-30, -30, 30, 30)
  INDOOR.uIndoorTop.value[0] = 50
}
window.__progInfo = () => {
  const r = pipe.renderer
  const out = []
  scene.traverse((o) => {
    if (!o.isMesh || !o.material?.userData?.key) return
    const p = r.properties.get(o.material)
    const prog = p.currentProgram
    if (!prog) return
    const u = prog.getUniforms().map
    out.push({ key: o.material.userData.key, indoor: 'uIndoorK' in u, n: 'uIndoorN' in u })
  })
  return out
}
window.__ready = true
