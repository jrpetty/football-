// Dev harness: one or more models in a row on a neutral pad, lit like the
// game, for close-up screenshots while modelling.
//   ?m=van,car:sedan,station:generator:3,furn:fridge,tree:pine&gap=7&h=10
import * as THREE from 'three'
import { Pipeline } from '../render/pipeline.js'
import { Atmosphere, nightFactor } from '../render/sky.js'
import { initView, view } from '../render/view.js'
import { Builder } from '../models/kit.js'
import { pregenerate } from '../render/texgen.js'
import { setNightGlow } from '../render/materials.js'
import { makeFlame, tickFlames } from '../render/fx.js'
import { stationModel } from '../models/stations.js'
import { vanModel, carModel, pickupModel, busModel, containerModel, forkliftModel } from '../models/vehicles.js'
import { containerModel as furnModel, decorModel, openPivots } from '../models/furniture.js'
import { pineModel, broadleafModel, deadTreeModel, bushModel, boulderModel } from '../models/nature.js'
import { weaponModel } from '../models/weapons.js'

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

const MAKERS = {
  van: () => vanModel({}),
  car: (k, s) => carModel({ kind: k, seed: +(s || 3) }),
  wreck: (k, s) => carModel({ kind: k, seed: +(s || 3), wreck: 0.8 }),
  pickup: () => pickupModel({}),
  bus: () => busModel({}),
  container: () => containerModel({}),
  forklift: () => forkliftModel(),
  station: (t, l) => stationModel(t, +(l || 1)),
  furn: (k, s) => furnModel(k, +(s || 1)),
  decor: (k, s) => decorModel(k, +(s || 1)),
  tree: (k, s) => ({ pine: pineModel, broad: broadleafModel, dead: deadTreeModel, bush: bushModel, rock: boulderModel })[k](+(s || 1)),
  weapon: (id) => weaponModel(id),
}
const list = (q.get('m') || 'van').split(',')
const gap = parseFloat(q.get('gap') || '7')
const ground = new Builder()
const span = list.length * gap
ground.box(span + 30, 0.1, 30, { mat: q.get('ground') || 'concrete', color: '#c8c4bc', y: -0.05, x: ((list.length - 1) * gap) / 2, ao: 0 })
scene.add(ground.build())
const models = []
list.forEach((spec, i) => {
  const [kind, a, c] = spec.split(':')
  const g = MAKERS[kind](a, c)
  g.position.set(i * gap, 0, 0)
  if (q.get('open')) openPivots(g, 1)
  scene.add(g)
  models.push(g)
  const I = g.userData.info || {}
  for (const f of I.flames || []) {
    for (let k = 0; k < 2; k++) {
      const fl = makeFlame(f.w, f.h, 3)
      fl.position.set(i * gap + f.x, f.y, f.z)
      fl.rotation.y = (k * Math.PI) / 2
      scene.add(fl)
    }
  }
})
const hour = parseFloat(q.get('h') || '10')
view.rig.minDist = 1
view.rig.maxDist = 200
view.rig.setBounds(-100, -100, 300, 300)
view.rig.jump(parseFloat(q.get('x') || String(((list.length - 1) * gap) / 2)), 0, parseFloat(q.get('d') || '12'))
view.rig.yaw = view.rig.yawGoal = parseFloat(q.get('yaw') || String(Math.PI / 5))
let last = performance.now()
let t = 0
function frame(now) {
  const dt = Math.max(0, Math.min(0.05, (now - last) / 1000))
  last = now
  t += dt
  view.rig.update(dt)
  const a = atmo.update(hour, view.rig.target, view.rig.dist, dt)
  pipe.exposure = a.exposure
  setNightGlow(nightFactor(hour))
  tickFlames(t)
  pipe.render(scene, view.camera, dt)
  requestAnimationFrame(frame)
}
requestAnimationFrame(frame)
window.__ready = true
window.__cam = (x, z, d, yaw, pitch) => {
  view.rig.jump(x, z, d)
  if (yaw != null) view.rig.yaw = view.rig.yawGoal = yaw
}
