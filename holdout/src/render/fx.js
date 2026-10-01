// Visual effects: soft particles (fire, smoke, embers, sparks, dust), debris,
// blood decals, tracers, muzzle flashes, explosions, flames and rain.
import * as THREE from 'three'

// ---------------------------------------------------------------- shared textures
function radialTex(stops, size = 64) {
  const c = document.createElement('canvas')
  c.width = c.height = size
  const g = c.getContext('2d')
  const gr = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2)
  for (const [o, col] of stops) gr.addColorStop(o, col)
  g.fillStyle = gr
  g.fillRect(0, 0, size, size)
  const t = new THREE.CanvasTexture(c)
  return t
}
let SOFT = null
let PUFF = null
function softTex() {
  if (!SOFT) SOFT = radialTex([[0, 'rgba(255,255,255,1)'], [0.35, 'rgba(255,255,255,0.55)'], [1, 'rgba(255,255,255,0)']])
  return SOFT
}
function puffTex() {
  if (PUFF) return PUFF
  const s = 128
  const c = document.createElement('canvas')
  c.width = c.height = s
  const g = c.getContext('2d')
  for (let i = 0; i < 14; i++) {
    const x = s / 2 + (Math.random() - 0.5) * s * 0.4
    const y = s / 2 + (Math.random() - 0.5) * s * 0.4
    const r = s * (0.18 + Math.random() * 0.2)
    const gr = g.createRadialGradient(x, y, 0, x, y, r)
    gr.addColorStop(0, 'rgba(255,255,255,0.35)')
    gr.addColorStop(1, 'rgba(255,255,255,0)')
    g.fillStyle = gr
    g.fillRect(0, 0, s, s)
  }
  PUFF = new THREE.CanvasTexture(c)
  return PUFF
}
let BLOOD = null
function bloodTex() {
  if (BLOOD) return BLOOD
  const s = 128
  const c = document.createElement('canvas')
  c.width = c.height = s
  const g = c.getContext('2d')
  g.fillStyle = '#fff'
  for (let i = 0; i < 16; i++) {
    const r = 5 + Math.random() * 22
    const a = Math.random() * Math.PI * 2
    const d = Math.random() * 28
    g.beginPath()
    g.arc(64 + Math.cos(a) * d, 64 + Math.sin(a) * d, r, 0, Math.PI * 2)
    g.fill()
  }
  for (let i = 0; i < 26; i++) {
    const a = Math.random() * Math.PI * 2
    const d = 36 + Math.random() * 24
    g.beginPath()
    g.arc(64 + Math.cos(a) * d, 64 + Math.sin(a) * d, 1 + Math.random() * 3, 0, Math.PI * 2)
    g.fill()
  }
  BLOOD = new THREE.CanvasTexture(c)
  return BLOOD
}
let SCORCH = null
function scorchTex() {
  if (!SCORCH) SCORCH = radialTex([[0, 'rgba(255,255,255,0.95)'], [0.5, 'rgba(255,255,255,0.6)'], [1, 'rgba(255,255,255,0)']], 128)
  return SCORCH
}
let RING = null
export function ringTex() {
  if (RING) return RING
  const c = document.createElement('canvas')
  c.width = c.height = 128
  const g = c.getContext('2d')
  g.strokeStyle = '#fff'
  g.lineWidth = 6
  g.beginPath()
  g.arc(64, 64, 56, 0, Math.PI * 2)
  g.stroke()
  g.globalAlpha = 0.5
  g.lineWidth = 2
  g.beginPath()
  g.arc(64, 64, 46, 0, Math.PI * 2)
  g.stroke()
  RING = new THREE.CanvasTexture(c)
  return RING
}

// ---------------------------------------------------------------- soft particle cloud
const PVERT = /* glsl */ `
attribute float size;
attribute vec4 pcolor;
attribute float spin;
uniform float scale;
varying vec4 vColor;
varying float vSpin;
#include <fog_pars_vertex>
void main() {
  vColor = pcolor;
  vSpin = spin;
  vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
  gl_PointSize = size * scale / -mvPosition.z;
  gl_Position = projectionMatrix * mvPosition;
  #include <fog_vertex>
}`
const PFRAG = /* glsl */ `
uniform sampler2D map;
varying vec4 vColor;
varying float vSpin;
#include <fog_pars_fragment>
void main() {
  vec2 c = gl_PointCoord - 0.5;
  float s = sin(vSpin), co = cos(vSpin);
  vec2 uv = vec2(c.x * co - c.y * s, c.x * s + c.y * co) + 0.5;
  vec4 t = texture2D(map, uv);
  gl_FragColor = vec4(vColor.rgb, vColor.a * t.a);
  if (gl_FragColor.a < 0.003) discard;
  #include <fog_fragment>
}`

class Cloud {
  constructor(scene, { max = 600, additive = false, texture }) {
    this.max = max
    this.n = 0
    const g = new THREE.BufferGeometry()
    this.pos = new Float32Array(max * 3)
    this.size = new Float32Array(max)
    this.col = new Float32Array(max * 4)
    this.spin = new Float32Array(max)
    g.setAttribute('position', new THREE.BufferAttribute(this.pos, 3).setUsage(THREE.DynamicDrawUsage))
    g.setAttribute('size', new THREE.BufferAttribute(this.size, 1).setUsage(THREE.DynamicDrawUsage))
    g.setAttribute('pcolor', new THREE.BufferAttribute(this.col, 4).setUsage(THREE.DynamicDrawUsage))
    g.setAttribute('spin', new THREE.BufferAttribute(this.spin, 1).setUsage(THREE.DynamicDrawUsage))
    g.setDrawRange(0, 0)
    this.mat = new THREE.ShaderMaterial({
      uniforms: THREE.UniformsUtils.merge([THREE.UniformsLib.fog, { map: { value: texture }, scale: { value: 600 } }]),
      vertexShader: PVERT,
      fragmentShader: PFRAG,
      transparent: true,
      depthWrite: false,
      blending: additive ? THREE.AdditiveBlending : THREE.NormalBlending,
      fog: true,
    })
    this.mat.uniforms.map.value = texture
    this.points = new THREE.Points(g, this.mat)
    this.points.frustumCulled = false
    this.points.renderOrder = additive ? 3 : 2
    scene.add(this.points)
    this.list = []
  }
  spawn(p) {
    if (this.list.length >= this.max) this.list.shift()
    this.list.push(p)
  }
  update(dt, camScale) {
    this.mat.uniforms.scale.value = camScale
    let n = 0
    for (let i = this.list.length - 1; i >= 0; i--) {
      const p = this.list[i]
      p.age += dt
      if (p.age >= p.life) {
        this.list.splice(i, 1)
        continue
      }
    }
    for (const p of this.list) {
      const t = p.age / p.life
      p.vy += (p.g ?? 0) * dt
      p.vx *= 1 - (p.drag ?? 0) * dt
      p.vz *= 1 - (p.drag ?? 0) * dt
      p.x += p.vx * dt
      p.y += p.vy * dt
      p.z += p.vz * dt
      if (p.floor && p.y < 0.02) {
        p.y = 0.02
        p.vy *= -0.2
      }
      this.pos[n * 3] = p.x
      this.pos[n * 3 + 1] = p.y
      this.pos[n * 3 + 2] = p.z
      this.size[n] = p.s0 + (p.s1 - p.s0) * t
      const fade = t < (p.fadeIn ?? 0.1) ? t / (p.fadeIn ?? 0.1) : 1 - Math.pow((t - (p.fadeIn ?? 0.1)) / (1 - (p.fadeIn ?? 0.1)), p.fadePow ?? 1.4)
      const c0 = p.c0
      const c1 = p.c1 || c0
      this.col[n * 4] = c0.r + (c1.r - c0.r) * t
      this.col[n * 4 + 1] = c0.g + (c1.g - c0.g) * t
      this.col[n * 4 + 2] = c0.b + (c1.b - c0.b) * t
      this.col[n * 4 + 3] = p.a * fade
      this.spin[n] = (p.rot || 0) + (p.vr || 0) * p.age
      n++
    }
    const g = this.points.geometry
    g.setDrawRange(0, n)
    g.attributes.position.needsUpdate = true
    g.attributes.size.needsUpdate = true
    g.attributes.pcolor.needsUpdate = true
    g.attributes.spin.needsUpdate = true
  }
}

// ---------------------------------------------------------------- flames
// Animated flame on a camera-facing quad: noise-distorted teardrop shape.
const FVERT = /* glsl */ `
varying vec2 vUv;
uniform float seed;
void main() {
  vUv = uv;
  vec4 mv = modelViewMatrix * vec4(0.0, 0.0, 0.0, 1.0);
  vec2 scale = vec2(length(modelMatrix[0].xyz), length(modelMatrix[1].xyz));
  mv.xy += (position.xy) * scale;
  gl_Position = projectionMatrix * mv;
}`
const FFRAG = /* glsl */ `
varying vec2 vUv;
uniform float time;
uniform float seed;
uniform float intensity;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
  vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
}
void main() {
  vec2 uv = vUv;
  float t = time * 2.2 + seed * 10.0;
  float n = noise(vec2(uv.x * 4.0, uv.y * 3.0 - t * 1.6)) * 0.6 + noise(vec2(uv.x * 9.0, uv.y * 7.0 - t * 3.0)) * 0.4;
  float x = (uv.x - 0.5) * 2.0;
  float w = (1.0 - uv.y) * 0.9 + 0.12;
  float shape = 1.0 - smoothstep(w * 0.55, w, abs(x + (n - 0.5) * 0.5 * uv.y));
  shape *= smoothstep(0.0, 0.12, uv.y) * (1.0 - smoothstep(0.55 + n * 0.35, 1.0, uv.y));
  float core = shape * (1.0 - uv.y);
  vec3 col = mix(vec3(1.0, 0.25, 0.04), vec3(1.0, 0.62, 0.18), core);
  col = mix(col, vec3(1.0, 0.95, 0.7), smoothstep(0.55, 1.0, core));
  float a = shape * (0.55 + n * 0.45);
  if (a < 0.01) discard;
  gl_FragColor = vec4(col * intensity, a);
}`
const flameMats = []
export function makeFlame(w = 0.6, h = 1, intensity = 3) {
  const m = new THREE.ShaderMaterial({
    uniforms: { time: { value: 0 }, seed: { value: Math.random() }, intensity: { value: intensity } },
    vertexShader: FVERT,
    fragmentShader: FFRAG,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending,
  })
  flameMats.push(m)
  const g = new THREE.PlaneGeometry(1, 1)
  g.translate(0, 0.5, 0)
  const mesh = new THREE.Mesh(g, m)
  mesh.scale.set(w, h, 1)
  mesh.renderOrder = 4
  mesh.frustumCulled = false
  mesh.userData.flame = true
  return mesh
}
export function tickFlames(t) {
  for (const m of flameMats) m.uniforms.time.value = t
}

// ---------------------------------------------------------------- FX manager
const _up = new THREE.Vector3(0, 1, 0)
const C = (h) => new THREE.Color(h)
export class FX {
  constructor(scene) {
    this.scene = scene
    // debris chunks
    this.maxDebris = 500
    this.debris = new THREE.InstancedMesh(new THREE.BoxGeometry(0.06, 0.06, 0.06), new THREE.MeshStandardMaterial({ roughness: 0.8 }), this.maxDebris)
    this.debris.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
    this.debris.frustumCulled = false
    this.debris.count = 0
    this.debris.castShadow = false
    scene.add(this.debris)
    this.pool = []
    this.glow = new Cloud(scene, { max: 900, additive: true, texture: softTex() })
    this.smokeC = new Cloud(scene, { max: 500, additive: false, texture: puffTex() })
    this.decals = []
    this.tracers = []
    this.rings = []
    this.emitters = []
    this.lights = []
    for (let i = 0; i < 3; i++) {
      const l = new THREE.PointLight('#ffb060', 0, 9, 2)
      l.position.set(0, -50, 0)
      scene.add(l)
      this.lights.push({ l, t: 0, dur: 0.1, peak: 0 })
    }
    this._m = new THREE.Matrix4()
    this._q = new THREE.Quaternion()
    this._s = new THREE.Vector3()
    this._p = new THREE.Vector3()
    this.camScale = 600
    this.rain = null
  }
  setViewport(heightPx, fovDeg) {
    this.camScale = heightPx / (2 * Math.tan(THREE.MathUtils.degToRad(fovDeg) / 2))
  }
  // ---- debris & blood
  burst(pos, color, n = 10, speed = 3, life = 0.8, up = 2.5, size = 1) {
    const col = color instanceof THREE.Color ? color : C(color)
    for (let i = 0; i < n; i++) {
      if (this.pool.length >= this.maxDebris) this.pool.shift()
      const a = Math.random() * Math.PI * 2
      const sp = speed * (0.3 + Math.random() * 0.7)
      this.pool.push({ x: pos.x, y: pos.y, z: pos.z, vx: Math.cos(a) * sp, vy: up * (0.4 + Math.random()), vz: Math.sin(a) * sp, life, age: 0, rot: Math.random() * 6, size: size * (0.6 + Math.random() * 0.8), color: col })
    }
  }
  blood(pos, big = false) {
    this.burst(new THREE.Vector3(pos.x, pos.y || 1, pos.z), '#5e1010', big ? 14 : 6, 2.2, 0.7, 2, 0.9)
    for (let i = 0; i < (big ? 8 : 3); i++) {
      this.glow.spawn({ x: pos.x, y: (pos.y || 1) + Math.random() * 0.3, z: pos.z, vx: (Math.random() - 0.5) * 2, vy: Math.random() * 2, vz: (Math.random() - 0.5) * 2, g: -9, life: 0.4, age: 0, s0: 0.12, s1: 0.05, a: 0, c0: C('#000') })
    }
    if (big || Math.random() < 0.5) this.decal(pos, big ? 1.2 : 0.6, '#4a0c0c', bloodTex())
  }
  decal(pos, size, color, map, opacity = 0.85) {
    let d
    if (this.decals.length > 80) d = this.decals.shift()
    else {
      d = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshStandardMaterial({ transparent: true, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -4, roughness: 0.4 }))
      d.rotation.x = -Math.PI / 2
      d.renderOrder = 1
      this.scene.add(d)
    }
    // a new shader only when the decal gains or loses a texture
    if (!d.material.map !== !map) d.material.needsUpdate = true
    d.material.map = map
    d.material.color.set(color)
    d.material.opacity = opacity
    d.position.set(pos.x + (Math.random() - 0.5) * 0.3, 0.012 + this.decals.length * 0.0001, pos.z + (Math.random() - 0.5) * 0.3)
    d.rotation.z = Math.random() * 6
    d.scale.setScalar(size * (0.7 + Math.random() * 0.6))
    this.decals.push(d)
  }
  // ---- guns
  tracer(from, to, color = '#ffd890') {
    const len = from.distanceTo(to)
    const m = new THREE.Mesh(new THREE.BoxGeometry(0.03, 0.03, 1), new THREE.MeshBasicMaterial({ color: new THREE.Color(color).multiplyScalar(4), transparent: true, opacity: 0.9, blending: THREE.AdditiveBlending, depthWrite: false }))
    m.scale.z = len
    m.position.copy(from).lerp(to, 0.5)
    m.lookAt(to)
    this.scene.add(m)
    this.tracers.push({ m, t: 0.06 })
  }
  flash(pos, color = '#ffb060', peak = 14, dur = 0.08, dist = 9) {
    const L = this.lights.reduce((a, b) => (a.t < b.t ? a : b))
    L.l.position.copy(pos)
    L.l.color.set(color)
    L.l.distance = dist
    L.peak = peak
    L.dur = dur
    L.t = dur
  }
  muzzle(pos) {
    this.glow.spawn({ x: pos.x, y: pos.y, z: pos.z, vx: 0, vy: 0, vz: 0, life: 0.07, age: 0, s0: 0.9, s1: 0.4, a: 1, c0: C('#ffd27a').multiplyScalar(3), fadeIn: 0.01 })
    for (let i = 0; i < 3; i++) this.glow.spawn({ x: pos.x, y: pos.y, z: pos.z, vx: (Math.random() - 0.5) * 3, vy: Math.random() * 2, vz: (Math.random() - 0.5) * 3, g: -6, life: 0.25, age: 0, s0: 0.08, s1: 0.03, a: 1, c0: C('#ffcf70').multiplyScalar(2) })
    this.smokeC.spawn({ x: pos.x, y: pos.y, z: pos.z, vx: (Math.random() - 0.5) * 0.4, vy: 0.5, vz: (Math.random() - 0.5) * 0.4, life: 0.9, age: 0, s0: 0.25, s1: 0.9, a: 0.25, c0: C('#b8b4ac'), rot: Math.random() * 6, vr: 0.5 })
    this.flash(pos)
  }
  // ---- fire, smoke, sparks
  smoke(pos, { size = 1, life = 3, color = '#6e6a64', a = 0.4, vy = 0.8, spread = 0.3 } = {}) {
    this.smokeC.spawn({ x: pos.x + (Math.random() - 0.5) * spread, y: pos.y, z: pos.z + (Math.random() - 0.5) * spread, vx: (Math.random() - 0.5) * 0.3 + 0.15, vy: vy * (0.7 + Math.random() * 0.6), vz: (Math.random() - 0.5) * 0.3, life: life * (0.8 + Math.random() * 0.4), age: 0, s0: size * 0.5, s1: size * 2.2, a, c0: C(color), c1: C('#9a9894'), rot: Math.random() * 6, vr: (Math.random() - 0.5) * 0.6, fadeIn: 0.15 })
  }
  ember(pos, spread = 0.3) {
    this.glow.spawn({ x: pos.x + (Math.random() - 0.5) * spread, y: pos.y, z: pos.z + (Math.random() - 0.5) * spread, vx: (Math.random() - 0.5) * 0.6, vy: 1 + Math.random() * 1.5, vz: (Math.random() - 0.5) * 0.6, g: -0.2, life: 1.2 + Math.random(), age: 0, s0: 0.07, s1: 0.02, a: 1, c0: C('#ffb050').multiplyScalar(3), c1: C('#ff4010'), fadeIn: 0.05 })
  }
  sparks(pos, n = 8, color = '#ffc860') {
    for (let i = 0; i < n; i++) {
      const a = Math.random() * Math.PI * 2
      this.glow.spawn({ x: pos.x, y: pos.y, z: pos.z, vx: Math.cos(a) * (1 + Math.random() * 3), vy: 1 + Math.random() * 3, vz: Math.sin(a) * (1 + Math.random() * 3), g: -9.8, floor: true, life: 0.4 + Math.random() * 0.4, age: 0, s0: 0.06, s1: 0.02, a: 1, c0: C(color).multiplyScalar(3), fadeIn: 0.01 })
    }
  }
  dust(pos, n = 6, color = '#a89a80') {
    for (let i = 0; i < n; i++) this.smoke(new THREE.Vector3(pos.x, pos.y ?? 0.2, pos.z), { size: 0.5, life: 1.2, color, a: 0.35, vy: 0.4, spread: 0.6 })
  }
  explosion(pos, r = 3) {
    this.flash(new THREE.Vector3(pos.x, 1.2, pos.z), '#ffa040', 60, 0.35, 16)
    for (let i = 0; i < 26; i++) {
      const a = Math.random() * Math.PI * 2
      const s = Math.random() * r * 2.5
      this.glow.spawn({ x: pos.x, y: 0.5, z: pos.z, vx: Math.cos(a) * s, vy: Math.random() * 4, vz: Math.sin(a) * s, drag: 3, life: 0.5 + Math.random() * 0.3, age: 0, s0: 1.4, s1: 0.4, a: 1, c0: C('#ffc060').multiplyScalar(4), c1: C('#ff3a10'), fadeIn: 0.02 })
    }
    for (let i = 0; i < 16; i++) this.smoke(new THREE.Vector3(pos.x, 0.6, pos.z), { size: 2.2, life: 3.5, color: '#3a3632', a: 0.55, vy: 1.6, spread: r })
    this.sparks(new THREE.Vector3(pos.x, 0.6, pos.z), 24)
    this.burst(new THREE.Vector3(pos.x, 0.5, pos.z), '#3a3530', 24, 6, 1.2, 5, 1.4)
    this.decal(pos, r * 1.5, '#0e0c0a', scorchTex(), 0.8)
  }
  ring(pos, color = '#e8c070', size = 1.2) {
    const m = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ map: ringTex(), color: new THREE.Color(color).multiplyScalar(1.6), transparent: true, depthWrite: false }))
    m.rotation.x = -Math.PI / 2
    m.position.set(pos.x, 0.05, pos.z)
    m.renderOrder = 2
    this.scene.add(m)
    this.rings.push({ m, t: 0, size })
  }
  // Persistent emitters (campfire, chimneys, exhausts). fn(fx, dt) is called each frame.
  addEmitter(fn) {
    const e = { fn, acc: 0, on: true }
    this.emitters.push(e)
    return e
  }
  removeEmitter(e) {
    this.emitters = this.emitters.filter((x) => x !== e)
  }
  // ---- rain
  setRain(intensity, focus) {
    if (intensity <= 0) {
      if (this.rain) this.rain.mesh.visible = false
      this.rainOn = 0
      return
    }
    this.rainOn = intensity
    if (!this.rain) {
      const n = 3000
      const g = new THREE.BoxGeometry(0.012, 0.6, 0.012)
      const m = new THREE.MeshBasicMaterial({ color: '#a8b8c8', transparent: true, opacity: 0.35, depthWrite: false })
      const mesh = new THREE.InstancedMesh(g, m, n)
      mesh.frustumCulled = false
      mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
      this.scene.add(mesh)
      const drops = []
      for (let i = 0; i < n; i++) drops.push({ x: (Math.random() - 0.5) * 70, y: Math.random() * 30, z: (Math.random() - 0.5) * 70, v: 14 + Math.random() * 6 })
      this.rain = { mesh, drops }
    }
    this.rain.mesh.visible = true
    this.rainFocus = focus
  }
  // Snowfall: slow flakes drifting and swaying around the camera focus.
  setSnow(intensity, focus) {
    if (intensity <= 0) {
      if (this.snow) this.snow.mesh.visible = false
      this.snowOn = 0
      return
    }
    this.snowOn = intensity
    if (!this.snow) {
      const n = 2800
      const g = new THREE.OctahedronGeometry(0.035, 0)
      const m = new THREE.MeshBasicMaterial({ color: '#f2f6ff', transparent: true, opacity: 0.9, depthWrite: false })
      const mesh = new THREE.InstancedMesh(g, m, n)
      mesh.frustumCulled = false
      mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
      this.scene.add(mesh)
      const flakes = []
      for (let i = 0; i < n; i++) flakes.push({ x: (Math.random() - 0.5) * 70, y: Math.random() * 24, z: (Math.random() - 0.5) * 70, v: 0.7 + Math.random() * 0.9, ph: Math.random() * 6.28, s: 0.6 + Math.random() * 0.9 })
      this.snow = { mesh, flakes, t: 0 }
    }
    this.snow.mesh.visible = true
    this.snowFocus = focus
  }
  update(dt) {
    if (this.snow && this.snowOn > 0) {
      const S = this.snow
      S.t += dt
      const f = this.snowFocus || { x: 0, z: 0 }
      const cnt = Math.floor(S.flakes.length * this.snowOn)
      for (let i = 0; i < cnt; i++) {
        const d = S.flakes[i]
        d.y -= d.v * dt
        d.x += Math.sin(S.t * 0.9 + d.ph) * 0.35 * dt + 0.25 * dt
        d.z += Math.cos(S.t * 0.7 + d.ph * 1.3) * 0.25 * dt
        if (d.y < 0) {
          d.y += 24
          d.x = (Math.random() - 0.5) * 70
          d.z = (Math.random() - 0.5) * 70
        }
        this._m.makeScale(d.s, d.s, d.s).setPosition(f.x + d.x, d.y, f.z + d.z)
        S.mesh.setMatrixAt(i, this._m)
      }
      S.mesh.count = cnt
      S.mesh.instanceMatrix.needsUpdate = true
    }
    // debris
    let n = 0
    for (let i = this.pool.length - 1; i >= 0; i--) {
      const p = this.pool[i]
      p.age += dt
      if (p.age >= p.life) {
        this.pool.splice(i, 1)
        continue
      }
      p.vy -= 9.8 * dt
      p.x += p.vx * dt
      p.y += p.vy * dt
      p.z += p.vz * dt
      if (p.y < 0.03) {
        p.y = 0.03
        p.vy *= -0.3
        p.vx *= 0.6
        p.vz *= 0.6
      }
      p.rot += dt * 8
      const s = p.size * (1 - p.age / p.life)
      this._q.setFromAxisAngle(_up, p.rot)
      this._s.set(s, s, s)
      this._m.compose(this._p.set(p.x, p.y, p.z), this._q, this._s)
      this.debris.setMatrixAt(n, this._m)
      this.debris.setColorAt(n, p.color)
      n++
    }
    this.debris.count = n
    this.debris.instanceMatrix.needsUpdate = true
    if (this.debris.instanceColor) this.debris.instanceColor.needsUpdate = true
    for (const e of this.emitters) if (e.on) e.fn(this, dt)
    this.glow.update(dt, this.camScale)
    this.smokeC.update(dt, this.camScale)
    for (let i = this.tracers.length - 1; i >= 0; i--) {
      const t = this.tracers[i]
      t.t -= dt
      if (t.t <= 0) {
        this.scene.remove(t.m)
        t.m.geometry.dispose()
        t.m.material.dispose()
        this.tracers.splice(i, 1)
      }
    }
    for (const L of this.lights) {
      if (L.t > 0) {
        L.t -= dt
        L.l.intensity = Math.max(0, L.peak * (L.t / L.dur))
      } else L.l.intensity = 0
    }
    for (let i = this.rings.length - 1; i >= 0; i--) {
      const r = this.rings[i]
      r.t += dt
      const k = r.t / 0.5
      r.m.scale.setScalar(r.size * (0.4 + k * 0.8))
      r.m.material.opacity = 1 - k
      if (k >= 1) {
        this.scene.remove(r.m)
        r.m.material.dispose()
        r.m.geometry.dispose()
        this.rings.splice(i, 1)
      }
    }
    if (this.rain && this.rainOn > 0) {
      const f = this.rainFocus || { x: 0, z: 0 }
      const cnt = Math.floor(this.rain.drops.length * this.rainOn)
      for (let i = 0; i < cnt; i++) {
        const d = this.rain.drops[i]
        d.y -= d.v * dt
        if (d.y < 0) {
          d.y += 30
          d.x = (Math.random() - 0.5) * 70
          d.z = (Math.random() - 0.5) * 70
        }
        this._m.makeTranslation(f.x + d.x + d.y * 0.08, d.y, f.z + d.z)
        this.rain.mesh.setMatrixAt(i, this._m)
      }
      this.rain.mesh.count = cnt
      this.rain.mesh.instanceMatrix.needsUpdate = true
    }
  }
  dispose() {
    for (const d of this.decals) d.material.dispose()
  }
}
