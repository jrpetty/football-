// Dev harness: the camp scene without the UI, for screenshots and debugging.
import * as THREE from 'three'
import { Pipeline } from '../render/pipeline.js'
import { initView, view } from '../render/view.js'
import { pregenerate } from '../render/texgen.js'
import { newGame, S, newStation, assign, startExpansion, rebuildFence } from '../game/state.js'
import { econTick, initSchedules } from '../game/economy.js'
import { BaseScene } from '../scenes/base.js'
import { makeSurvivor } from '../game/state.js'

const canvas = document.getElementById('c')
const pipe = new Pipeline(canvas)
initView(canvas)
const q = new URLSearchParams(location.search)
pipe.setQuality(q.get('q') || 'high')
pipe.exposureMul = parseFloat(q.get('exp') || '1')
pipe.resize(window.innerWidth, window.innerHeight)
view.camera.aspect = window.innerWidth / window.innerHeight
view.camera.updateProjectionMatrix()
await pregenerate()
newGame()
initSchedules()
S.time = (parseFloat(q.get('h') || '10')) * 60
if (q.get('rain')) S.weather = { type: 'rain', until: 1e9 }
const preset = q.get('preset') || 'start'
if (preset !== 'start') {
  for (let i = 0; i < 6; i++) S.survivors.push(makeSurvivor({}))
  const lv = parseInt(q.get('lv') || '2', 10)
  const add = (t, x, z, l = lv, rot = 0) => newStation(t, x, z, rot, Math.min(l, 3))
  add('kitchen', 61, 51)
  add('infirmary', 44, 50)
  add('lumber', 44, 63, lv)
  add('forge', 52, 63)
  add('filter', 66, 61)
  add('watchtower', 67, 66)
  add('generator', 59, 63)
  add('radio', 51, 49, lv)
  const ch = add('chemlab', 60, 67)
  ch.building = { to: 1, left: 30, total: 60 }
  ch.level = 0
  S.stations.find((s) => s.type === 'bunkhouse').level = Math.min(lv, 3)
  S.stations.find((s) => s.type === 'farm').level = Math.min(lv, 3)
  S.stations.find((s) => s.type === 'workbench').level = Math.min(lv, 3)
  S.stations.find((s) => s.type === 'storage').level = Math.min(lv, 3)
  S.fence.level = parseInt(q.get('fence') || '1', 10)
  rebuildFence()
  const jobs = ['lumber', 'forge', 'filter', 'kitchen', 'watchtower', 'workbench', 'radio']
  S.survivors.forEach((s, i) => {
    const st = S.stations.find((x) => x.type === jobs[i % jobs.length])
    if (i > 0 && st) assign(s, st)
  })
  S.res.wood = 400
  S.res.cash = 2000
  S.res.metal = 300
  if (q.get('expand')) startExpansion(q.get('expand'))
}
const game = {
  pipe,
  scene: null,
  ui: null,
  grassCount: () => parseInt(q.get('grass') || '30000', 10),
  placeStation: () => false,
}
const base = new BaseScene(game)
game.scene = base
base.enter()
view.input.handler = base
view.rig.jump(parseFloat(q.get('x') || '56'), parseFloat(q.get('z') || '58'), parseFloat(q.get('d') || '44'))
if (q.get('yaw')) view.rig.yaw = view.rig.yawGoal = parseFloat(q.get('yaw'))
let last = performance.now()
const speed = parseFloat(q.get('speed') || '0')
function frame(now) {
  const dt = Math.max(0, Math.min(0.1, (now - last) / 1000))
  last = now
  if (speed) econTick(dt * speed)
  view.input.update(dt)
  view.rig.update(dt)
  base.update(dt, dt * (speed || 1))
  pipe.render(base.scene, view.camera, dt)
  view.labels.update(dt, view.camera, base.scene)
  requestAnimationFrame(frame)
}
requestAnimationFrame(frame)
window.__ready = true
window.__perf = () => ({ calls: pipe.renderer.info.render.calls, tris: pipe.renderer.info.render.triangles, geos: pipe.renderer.info.memory.geometries })
window.__S = S
window.__base = base
window.__cam = (x, z, d, yaw) => {
  view.rig.jump(x, z, d)
  if (yaw != null) view.rig.yaw = view.rig.yawGoal = yaw
}
