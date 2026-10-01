// Dev harness: every station at one level in a grid, with workers at their spots.
import * as THREE from 'three'
import { Pipeline } from '../render/pipeline.js'
import { Atmosphere } from '../render/sky.js'
import { initView, view } from '../render/view.js'
import { Builder } from '../models/kit.js'
import { pregenerate } from '../render/texgen.js'
import { setNightGlow } from '../render/materials.js'
import { makeFlame, tickFlames, FX } from '../render/fx.js'
import { stationModel, scaffold } from '../models/stations.js'
import { STATIONS } from '../game/data.js'
import { Character, OUTFITS, SKIN_TONES, HAIR_COLORS } from '../models/character.js'
import { nightFactor } from '../render/sky.js'

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
const fx = new FX(scene)
fx.setViewport(window.innerHeight, view.camera.fov)
const level = parseInt(q.get('level') || '1', 10)
let types = (q.get('types') || Object.keys(STATIONS).join(',')).split(',')
const levelsQ = q.get('levels') ? q.get('levels').split(',').map(Number) : null
const pairs = []
for (const t of types) for (const l of levelsQ || [null]) pairs.push([t, l])
types = pairs.map((p) => p[0])
const cols = parseInt(q.get('cols') || '5', 10)
const cell = parseFloat(q.get('cell') || '9')
const gb = new Builder()
gb.box(cols * cell + 20, 0.1, Math.ceil(types.length / cols) * cell + 20, { mat: 'dirt', color: '#ffffff', y: -0.05, ao: 0, x: (cols * cell) / 2 - cell / 2, z: (Math.ceil(types.length / cols) * cell) / 2 - cell / 2 })
scene.add(gb.build())
const anims = []
const chars = []
const occs = Object.keys(OUTFITS)
types.forEach((type, i) => {
  const lv = Math.min(pairs[i][1] ?? level, STATIONS[type].levels)
  const m = q.get('scaffold') ? scaffold(...STATIONS[type].size) : stationModel(type, lv)
  const x = (i % cols) * cell
  const z = Math.floor(i / cols) * cell
  m.position.set(x, 0, z)
  scene.add(m)
  if (q.get('roofs') === '0') for (const [n, p] of Object.entries(m.userData.pivots || {})) if (n.startsWith('roof')) p.visible = false
  const I = m.userData.info || {}
  for (const f of I.flames || []) {
    for (let k = 0; k < 2; k++) {
      const fl = makeFlame(f.w, f.h, 3)
      fl.position.set(x + f.x, f.y, z + f.z)
      fl.rotation.y = k * Math.PI / 2
      scene.add(fl)
    }
  }
  if (q.get('lights')) for (const L of (I.lights || []).slice(0, 1)) {
    const pl = new THREE.PointLight(L.color, L.intensity * 2, L.dist, 1.8)
    pl.position.set(x + L.x, L.y, z + L.z)
    scene.add(pl)
  }
  for (const a of I.anims || []) {
    const p = m.userData.pivots[a.name]
    if (p) anims.push({ a, p, base: p.position.clone(), rot: p.rotation.clone() })
  }
  if (q.get('workers') !== '0') {
    ;(I.spots || []).forEach((s, k) => {
      const ch = new Character({ skin: SKIN_TONES[(i + k) % 7], hair: { style: 'short', color: HAIR_COLORS[(i * 3 + k) % 9] }, build: 1, outfit: OUTFITS[occs[(i * 2 + k) % occs.length]], seed: i * 7 + k })
      ch.root.position.set(x + s.x, s.y || 0, z + s.z)
      ch.root.rotation.y = s.face || 0
      ch.anim = s.sit ? 'sit' : s.anim === 'carry' ? 'carry' : s.anim
      scene.add(ch.root)
      chars.push(ch)
    })
  }
  const lab = document.createElement('div')
  lab.textContent = `${STATIONS[type].name} L${lv}`
  lab.style.cssText = 'position:absolute;color:#fff;font:600 12px sans-serif;text-shadow:0 1px 2px #000;pointer-events:none'
  document.body.appendChild(lab)
  anims.push({ label: lab, pos: new THREE.Vector3(x, 0, z + STATIONS[type].size[1] / 2 + 0.8) })
})
const hour = parseFloat(q.get('h') || '15')
const fxx = parseFloat(q.get('x') || String(((cols - 1) * cell) / 2))
const fzz = parseFloat(q.get('z') || String(((Math.ceil(types.length / cols) - 1) * cell) / 2))
view.rig.minDist = 2
view.rig.maxDist = 200
view.rig.setBounds(-100, -100, 200, 200)
view.rig.jump(fxx, fzz, parseFloat(q.get('d') || '40'))
view.rig.yaw = view.rig.yawGoal = parseFloat(q.get('yaw') || String(Math.PI / 4))
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
  for (const e of anims) {
    if (e.label) {
      const v = e.pos.clone().project(view.camera)
      e.label.style.left = ((v.x * 0.5 + 0.5) * innerWidth - 40) + 'px'
      e.label.style.top = ((-v.y * 0.5 + 0.5) * innerHeight) + 'px'
      continue
    }
    const { a: A, p } = e
    if (A.kind === 'spin') p.rotation[A.axis || 'y'] = t * (A.speed || 1)
    else if (A.kind === 'yaw') p.rotation.y = Math.sin(t * (A.speed || 0.3)) * (A.swing || 1)
    else if (A.kind === 'press') p.position[A.axis || 'y'] = e.base[A.axis || 'y'] - Math.abs(Math.sin(t * (A.speed || 1))) * (A.amp || 0.3)
    else if (A.kind === 'pump') p.rotation[A.axis || 'z'] = Math.sin(t * (A.speed || 3)) * (A.amp || 0.3)
    else if (A.kind === 'slide') p.position[A.axis || 'x'] = e.base[A.axis || 'x'] + Math.sin(t * (A.speed || 1)) * (A.amp || 0.1)
    else if (A.kind === 'conveyor') p.position.copy(e.base).addScaledVector(new THREE.Vector3(1, 0, 0).applyQuaternion(p.quaternion), (t * (A.speed || 0.3)) % (A.amp || 0.5))
    else if (A.kind === 'grow') p.scale.setScalar(0.5 + 0.5 * ((t * 0.1) % 1))
  }
  for (const ch of chars) ch.update(dt, null, { speed: 0 })
  fx.update(dt)
  pipe.render(scene, view.camera, dt)
  requestAnimationFrame(frame)
}
requestAnimationFrame(frame)
window.__ready = true
window.__cam = (x, z, d, yaw) => {
  view.rig.jump(x, z, d)
  if (yaw != null) view.rig.yaw = view.rig.yawGoal = yaw
}
