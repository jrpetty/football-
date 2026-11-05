// The 3D view of the world (WebGL via three.js). A drop-in replacement for the
// 2D canvas renderer with the same camera, selection and minimap interface.
//
//  • terrain, water, roads and borders ........ terrain.ts
//  • trees, rocks, grass (instanced) ........... flora.ts
//  • buildings by type and era ................. buildings3d.ts
//  • people and animals (jointed rigs) ......... character.ts
//  • particles, weather, clouds, birds ......... fx.ts
//
// The camera orbits a ground target: drag to pan, wheel to zoom, right-drag
// or Q/E to turn. A sun crosses the sky with real shadows, the light warms at
// dawn and dusk, night falls blue with glowing windows and firelight, and an
// HTML-canvas overlay draws names, speech bubbles and feelings above heads.

import * as THREE from 'three'
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js'
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js'
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass.js'
import { OutputPass } from 'three/examples/jsm/postprocessing/OutputPass.js'
import { B, F } from '../sim/data.ts'
import { BUILDINGS } from '../sim/buildings.ts'
import { eraOf } from '../sim/tech.ts'
import { daylight, hourOf, isNight } from '../sim/time.ts'
import { clamp, smoothstep } from '../sim/rng.ts'
import type { World } from '../sim/world.ts'
import type { Agent } from '../sim/types.ts'
import { Renderer, TP, type Overlay, type Selection } from '../render/renderer.ts'
import { Terrain } from './terrain.ts'
import { Flora, wind, treeSpot, solidAt } from './flora.ts'
import { Building3D, glowMat } from './buildings3d.ts'
import { Person3D, Animal3D } from './character.ts'
import { Particles, PK, Weather, Clouds, Birds } from './fx.ts'
import { status, militaryTier } from '../sim/diplomacy.ts'
import { hash2 } from '../sim/rng.ts'

interface Body { key: number; x: number; y: number; r: number; moving: boolean; sx?: number; sy?: number }
const hash01 = (tx: number, ty: number) => hash2(tx, ty, 41)
/** work that happens inside a building (people doing anything else are pushed out of walls instead) */
const INSIDE_WORK = new Set(['craft', 'research', 'write', 'sleep', 'rest', 'store', 'take', 'gather', 'heal', 'teach'])
/** structures without an inside */
const OPEN_AIR = new Set(['campfire', 'stockpile', 'well', 'watchtower', 'monument', 'walls', 'market', 'dock', 'lean_to', 'solar_farm', 'wind_farm', 'evaporation_ponds', 'oil_well', 'offshore_rig', 'launch_pad', 'radio_tower', 'fish_farm', 'harbour'])

const FOV = 40

export function webglAvailable(): boolean {
  try {
    const c = document.createElement('canvas')
    return !!c.getContext('webgl2')
  } catch {
    return false
  }
}

export class Renderer3D {
  canvas: HTMLCanvasElement
  world: World
  cam = { x: 0, y: 0, zoom: 2.3 }
  target: { x: number; y: number; zoom: number } | null = null
  dpr = 1
  vw = 0
  vh = 0
  selection: Selection = { kind: null, id: 0 }
  hover: { x: number; y: number } | null = null
  follow = false
  cinematic = false
  overlay: Overlay = 'territory'
  showNames = true
  yaw = 0
  private yawTarget: number | null = null
  private drift = Math.random() * 1000
  private quality: 'high' | 'low'

  private gl: THREE.WebGLRenderer
  private scene = new THREE.Scene()
  private camera: THREE.PerspectiveCamera
  private composer: EffectComposer | null = null
  private bloom: UnrealBloomPass | null = null
  private over: HTMLCanvasElement
  private octx: CanvasRenderingContext2D
  private mini: Renderer

  private terrain!: Terrain
  private flora!: Flora
  private worldGroup = new THREE.Group()
  private people = new Map<number, Person3D>()
  private animals = new Map<number, Animal3D>()
  private buildings = new Map<number, Building3D>()
  private boats = new Map<number, THREE.Group>()
  /** how far each body is drawn from where the simulation puts it, so none overlap */
  private offsets = new Map<number, { x: number; y: number }>()
  private padVersion = -1
  private camLift = 0
  private padT = -1e9
  private particles = new Particles()
  private weather = new Weather()
  private clouds!: Clouds
  private birds = new Birds()
  private ring: THREE.Mesh

  private sun: THREE.DirectionalLight
  private hemi: THREE.HemisphereLight
  private lamps: THREE.PointLight[] = []
  private fog: THREE.Fog
  private sky = new THREE.Color('#a9c9e6')
  private pmrem: THREE.PMREMGenerator
  private skyScene = new THREE.Scene()
  private skyMat: THREE.ShaderMaterial
  private envRT: THREE.WebGLRenderTarget | null = null
  private envT = -1e9
  private envKey = ''

  private groundY = 0
  private lastT = 0
  private frame = 0
  private flash = 0
  private roadsDirty = false
  private roadsT = 0
  private overlayKey = ''
  private overlayT = 0
  private keyHandler: (e: KeyboardEvent) => void
  private eraCache = new Map<number, number>()
  private raycaster = new THREE.Raycaster()
  private tmp = new THREE.Vector3()

  constructor(canvas: HTMLCanvasElement, world: World) {
    this.canvas = canvas
    this.world = world
    this.quality = (localStorage.getItem('genesis.quality') as 'high' | 'low' | null) ?? 'high'
    this.gl = new THREE.WebGLRenderer({ canvas, antialias: this.quality === 'low', powerPreference: 'high-performance', alpha: false })
    this.gl.outputColorSpace = THREE.SRGBColorSpace
    this.gl.toneMapping = THREE.ACESFilmicToneMapping
    this.gl.toneMappingExposure = 1.05
    this.gl.shadowMap.enabled = true
    this.gl.shadowMap.type = THREE.PCFSoftShadowMap
    this.camera = new THREE.PerspectiveCamera(FOV, 1, 0.1, 1200)
    this.pmrem = new THREE.PMREMGenerator(this.gl)

    // the overlay canvas sits just above the WebGL canvas
    this.over = document.createElement('canvas')
    this.over.className = 'map-overlay'
    canvas.insertAdjacentElement('afterend', this.over)
    this.octx = this.over.getContext('2d')!
    this.mini = new Renderer(document.createElement('canvas'), world)

    // lights
    this.hemi = new THREE.HemisphereLight('#cfe3ff', '#5a4a36', 0.6)
    this.sun = new THREE.DirectionalLight('#fff4e6', 2.6)
    this.sun.castShadow = true
    const sm = this.quality === 'high' ? 2048 : 1024
    this.sun.shadow.mapSize.set(sm, sm)
    this.sun.shadow.bias = -0.0005
    this.sun.shadow.normalBias = 0.03
    this.sun.shadow.camera.near = 1
    this.sun.shadow.camera.far = 260
    this.scene.add(this.hemi, this.sun, this.sun.target)
    for (let k = 0; k < 6; k++) {
      const l = new THREE.PointLight('#ffb060', 0, 6, 2)
      this.lamps.push(l)
      this.scene.add(l)
    }
    this.fog = new THREE.Fog(this.sky, 40, 200)
    this.scene.fog = this.fog
    this.scene.background = this.sky

    // a gradient sky only for reflections (the camera looks down at the land)
    this.skyMat = new THREE.ShaderMaterial({
      side: THREE.BackSide,
      depthWrite: false,
      uniforms: { uTop: { value: new THREE.Color('#4a7fc0') }, uHor: { value: new THREE.Color('#cfe0f0') }, uSun: { value: new THREE.Vector3(0, 1, 0) }, uSunCol: { value: new THREE.Color('#fff') } },
      vertexShader: 'varying vec3 vD; void main(){ vD = normalize(position); gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }',
      fragmentShader: `uniform vec3 uTop; uniform vec3 uHor; uniform vec3 uSun; uniform vec3 uSunCol; varying vec3 vD;
        void main(){ float h = clamp(vD.y, -0.2, 1.0); vec3 c = mix(uHor, uTop, pow(max(h,0.0), 0.6)); if (h < 0.0) c = mix(uHor, uHor*0.5, -h*5.0);
        float s = max(dot(normalize(vD), normalize(uSun)), 0.0); c += uSunCol * (pow(s, 600.0) * 30.0 + pow(s, 12.0) * 0.35); gl_FragColor = vec4(c, 1.0); }`,
    })
    this.skyScene.add(new THREE.Mesh(new THREE.SphereGeometry(100, 32, 16), this.skyMat))

    this.ring = new THREE.Mesh(new THREE.RingGeometry(0.42, 0.52, 40).rotateX(-Math.PI / 2), new THREE.MeshBasicMaterial({ color: '#ffffff', transparent: true, opacity: 0.9, depthWrite: false }))
    this.ring.renderOrder = 4
    this.ring.visible = false

    this.scene.add(this.worldGroup, this.particles.points, this.weather.group, this.birds.group, this.ring)
    this.setupWorld(world)

    if (this.quality === 'high') {
      const rt = new THREE.WebGLRenderTarget(1, 1, { type: THREE.HalfFloatType, samples: 4 })
      this.composer = new EffectComposer(this.gl, rt)
      this.composer.addPass(new RenderPass(this.scene, this.camera))
      this.bloom = new UnrealBloomPass(new THREE.Vector2(256, 256), 0.32, 0.5, 0.92)
      this.composer.addPass(this.bloom)
      this.composer.addPass(new OutputPass())
    }

    this.keyHandler = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return
      if (e.key === 'q' || e.key === 'Q') this.yawTarget = (this.yawTarget ?? this.yaw) + Math.PI / 4
      if (e.key === 'e' || e.key === 'E') this.yawTarget = (this.yawTarget ?? this.yaw) - Math.PI / 4
    }
    window.addEventListener('keydown', this.keyHandler)
    canvas.addEventListener('contextmenu', (e) => e.preventDefault())
  }

  private setupWorld(w: World) {
    // tear down
    this.worldGroup.clear()
    for (const p of this.people.values()) p.dispose()
    this.people.clear()
    this.animals.clear()
    for (const b of this.buildings.values()) b.dispose()
    this.buildings.clear()
    this.boats.clear()
    this.terrain?.dispose()
    this.flora?.dispose()
    // build
    this.terrain = new Terrain(w)
    this.flora = new Flora(w, this.terrain.heights)
    this.clouds = new Clouds(w.W, w.H)
    this.worldGroup.add(this.terrain.group, this.flora.group, this.clouds.group)
    // the first few chunks of forest right away, the rest stream in
    const t = w.tribes.find((x) => x.alive)
    this.cam.x = t ? t.center.x + 0.5 : w.W / 2
    this.cam.y = t ? t.center.y + 0.5 : w.H / 2
    this.flora.update(4, this.cam.x, this.cam.y)
    w.dirtyChunks.clear()
    this.overlayKey = ''
    this.groundY = this.terrain.heights.surface(this.cam.x, this.cam.y)
    this.roadsDirty = true
  }

  setWorld(w: World) {
    this.world = w
    this.mini.setWorld(w)
    this.selection = { kind: null, id: 0 }
    this.eraCache.clear()
    this.setupWorld(w)
  }

  resize(w: number, h: number, dpr: number) {
    this.vw = w
    this.vh = h
    this.dpr = this.quality === 'low' ? 1 : Math.min(dpr, 1.75)
    this.gl.setPixelRatio(this.dpr)
    this.gl.setSize(w, h, false)
    this.composer?.setPixelRatio(this.dpr)
    this.composer?.setSize(w, h)
    this.bloom?.resolution.set(w / 2, h / 2)
    this.camera.aspect = w / Math.max(1, h)
    this.camera.updateProjectionMatrix()
    const odpr = Math.min(2, window.devicePixelRatio || 1)
    this.over.width = Math.round(w * odpr)
    this.over.height = Math.round(h * odpr)
    this.particles.uniforms.uScale.value = (h * this.dpr) / (2 * Math.tan((FOV * Math.PI) / 360))
  }

  // ---------------------------------------------------------------- camera

  private get dist(): number {
    return 30 / this.cam.zoom
  }
  private get pitch(): number {
    const base = 0.46 + 0.64 * smoothstep(6, 110, this.dist)
    return this.cinematic ? base - 0.12 : base
  }
  private placeCamera() {
    const d = this.dist, p = this.pitch
    const gx = this.cam.x, gz = this.cam.y
    const cx = gx + Math.sin(this.yaw) * Math.cos(p) * d
    const cz = gz + Math.cos(this.yaw) * Math.cos(p) * d
    let cy = this.groundY + Math.sin(p) * d
    // never dip into a hillside, and never let a ridge stand between the eye and
    // what it is looking at: rise until the line of sight clears the land
    const hs = this.terrain.heights
    let need = hs.surface(cx, cz) + 1.2
    for (let k = 1; k <= 12; k++) {
      const f = k / 12
      if (f < 0.15) continue
      // the line of sight must pass a little above the land (the margin grows toward the eye)
      const h = hs.surface(gx + (cx - gx) * f, gz + (cz - gz) * f) + 0.35 * f
      need = Math.max(need, this.groundY + (h - this.groundY) / f)
    }
    // ease the lift so the camera glides over hills instead of jumping
    const lift = Math.max(0, need - cy)
    this.camLift += (lift - this.camLift) * (lift > this.camLift ? 0.35 : 0.08)
    cy += Math.max(this.camLift, lift * 0.85)
    this.camera.position.set(cx, cy, cz)
    this.camera.lookAt(gx, this.groundY, gz)
    this.camera.near = Math.max(0.1, d * 0.02)
    this.camera.far = d * 6 + 300
    this.camera.updateProjectionMatrix()
    this.camera.updateMatrixWorld()
  }

  screenToWorld(sx: number, sy: number): { x: number; y: number } {
    const ndc = new THREE.Vector2((sx / Math.max(1, this.vw)) * 2 - 1, -(sy / Math.max(1, this.vh)) * 2 + 1)
    this.raycaster.setFromCamera(ndc, this.camera)
    const o = this.raycaster.ray.origin, d = this.raycaster.ray.direction
    const hs = this.terrain.heights
    const step = Math.max(0.2, this.dist * 0.015)
    let prev = 0
    for (let t = 0; t < this.dist * 8 + 100; t += step) {
      const x = o.x + d.x * t, y = o.y + d.y * t, z = o.z + d.z * t
      if (y <= hs.surface(x, z)) {
        // refine
        let lo = prev, hi = t
        for (let k = 0; k < 10; k++) {
          const m = (lo + hi) / 2
          if (o.y + d.y * m <= hs.surface(o.x + d.x * m, o.z + d.z * m)) hi = m
          else lo = m
        }
        return { x: o.x + d.x * hi, y: o.z + d.z * hi }
      }
      prev = t
    }
    const t = d.y < 0 ? -o.y / d.y : 0
    return { x: o.x + d.x * t, y: o.z + d.z * t }
  }
  worldToScreen(x: number, y: number, h?: number): { x: number; y: number; behind: boolean } {
    const v = this.tmp.set(x, h ?? this.terrain.heights.surface(x, y), y).project(this.camera)
    return { x: (v.x * 0.5 + 0.5) * this.vw, y: (-v.y * 0.5 + 0.5) * this.vh, behind: v.z > 1 }
  }
  private project(v: THREE.Vector3): { x: number; y: number; behind: boolean } {
    const p = this.tmp.copy(v).project(this.camera)
    return { x: (p.x * 0.5 + 0.5) * this.vw, y: (-p.y * 0.5 + 0.5) * this.vh, behind: p.z > 1 }
  }

  zoomAt(sx: number, sy: number, factor: number) {
    this.placeCamera()
    const before = this.screenToWorld(sx, sy)
    this.cam.zoom = clamp(this.cam.zoom * factor, 0.3, 7)
    this.placeCamera()
    const after = this.screenToWorld(sx, sy)
    this.cam.x = clamp(this.cam.x + before.x - after.x, 0, this.world.W)
    this.cam.y = clamp(this.cam.y + before.y - after.y, 0, this.world.H)
    this.target = null
  }
  pan(dx: number, dy: number) {
    const k = (2 * this.dist * Math.tan((FOV * Math.PI) / 360)) / Math.max(1, this.vh)
    const sp = Math.sin(this.pitch)
    const rx = Math.cos(this.yaw), rz = -Math.sin(this.yaw)
    const fx = -Math.sin(this.yaw), fz = -Math.cos(this.yaw)
    this.cam.x -= rx * dx * k
    this.cam.y -= rz * dx * k
    this.cam.x += fx * dy * k / sp
    this.cam.y += fz * dy * k / sp
    this.cam.x = clamp(this.cam.x, 0, this.world.W)
    this.cam.y = clamp(this.cam.y, 0, this.world.H)
    this.follow = false
    this.target = null
  }
  rotate(dx: number) {
    this.yaw -= dx * 0.006
    this.yawTarget = null
  }
  flyTo(x: number, y: number, zoom?: number) {
    this.target = { x, y, zoom: zoom ?? Math.max(this.cam.zoom, 2.4) }
  }

  pick(sx: number, sy: number): Selection {
    const w = this.world
    let best: Agent | null = null
    let bd = 34
    const v = new THREE.Vector3()
    for (const a of w.agents) {
      if (!a.alive) continue
      const p = this.people.get(a.id)
      if (!p || !p.root.visible) continue
      v.copy(p.root.position)
      v.y += p.root.scale.y * 0.35
      const s = this.project(v)
      if (s.behind) continue
      const d = Math.hypot(s.x - sx, s.y - sy)
      if (d < bd) { bd = d; best = a }
    }
    if (best) return { kind: 'agent', id: best.id }
    const p = this.screenToWorld(sx, sy)
    const tx = Math.floor(p.x), ty = Math.floor(p.y)
    if (!w.inBounds(tx, ty)) return { kind: null, id: 0 }
    const o = w.occ[w.idx(tx, ty)]
    if (o) return { kind: 'building', id: o - 1 }
    return { kind: 'tile', id: w.idx(tx, ty) }
  }

  selectedTribeId(): number {
    const s = this.selection
    const w = this.world
    if (s.kind === 'agent') return w.agentById.get(s.id)?.tribeId ?? 0
    if (s.kind === 'building') return w.buildingById.get(s.id)?.tribeId ?? 0
    if (s.kind === 'tribe') return s.id
    return w.tribes[0]?.id ?? 0
  }

  /**
   * Indoors: asleep at home, or at work inside a building (a smith at the
   * forge, a miner underground). Their bodies are inside walls, so they are not drawn.
   */
  private indoors(w: World, a: Agent): boolean {
    const tk = a.task
    if (a.moving || !tk || tk.phase === 'travel') {
      // a child at home after dark
      if (!a.moving && w.age(a) < 14 && a.homeId && isNight(w.time)) {
        const h = w.buildingById.get(a.homeId)
        if (h && h.done && Math.hypot(h.x + 0.5 - a.x, h.y + 0.5 - a.y) < 2.4) return true
      }
      return false
    }
    if (tk.action === 'sleep' && a.homeId) {
      const h = w.buildingById.get(a.homeId)
      if (h && h.done && Math.hypot(h.x + BUILDINGS[h.type].size / 2 - a.x, h.y + BUILDINGS[h.type].size / 2 - a.y) < 2.4) return true
    }
    // at work inside a building with walls and a door: they are in there
    if (!INSIDE_WORK.has(tk.action)) return false
    const tx = Math.floor(a.x), ty = Math.floor(a.y)
    const o = w.occ[ty * w.W + tx]
    if (o) {
      const b = w.buildingById.get(o - 1)
      if (b && b.done && !BUILDINGS[b.type].walkable && !OPEN_AIR.has(b.type)) return true
    }
    return false
  }

  /**
   * Keep every visible body out of walls, tree trunks and each other. Each
   * body keeps a small, persistent offset from its simulated spot: crowds part
   * gently instead of jittering, and the offset relaxes away once there's room.
   */
  private unclip(w: World, bodies: Body[], dt: number) {
    const relax = Math.min(1, dt * 0.7)
    for (const b of bodies) {
      let o = this.offsets.get(b.key)
      if (!o) { o = { x: 0, y: 0 }; this.offsets.set(b.key, o) }
      o.x *= 1 - relax; o.y *= 1 - relax
      b.sx = b.x; b.sy = b.y
      b.x += o.x; b.y += o.y
    }
    // bodies part: a coarse grid of half-tile cells
    const grid = new Map<number, Body[]>()
    for (const b of bodies) {
      const k = Math.floor(b.x * 2) * 4096 + Math.floor(b.y * 2)
      const c = grid.get(k)
      if (c) c.push(b); else grid.set(k, [b])
    }
    const push = Math.min(1, dt * 8)
    for (const b of bodies) {
      const gx = Math.floor(b.x * 2), gy = Math.floor(b.y * 2)
      for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
        const c = grid.get((gx + dx) * 4096 + gy + dy)
        if (!c) continue
        for (const q of c) {
          if (q.key <= b.key) continue
          let ex = q.x - b.x, ey = q.y - b.y
          const min = b.r + q.r
          const dd = ex * ex + ey * ey
          if (dd >= min * min) continue
          let dist = Math.sqrt(dd)
          if (dist < 1e-4) { const ang = ((b.key * 7919 + q.key * 104729) % 628) / 100; ex = Math.cos(ang); ey = Math.sin(ang); dist = 1 } else { ex /= dist; ey /= dist; dist = (min - dist) }
          const m = dist * 0.5 * push
          // the one standing still gives way to the one walking past
          const wb = b.moving && !q.moving ? 0.3 : !b.moving && q.moving ? 0.7 : 0.5
          b.x -= ex * m * wb * 2; b.y -= ey * m * wb * 2
          q.x += ex * m * (1 - wb) * 2; q.y += ey * m * (1 - wb) * 2
        }
      }
    }
    // walls and trunks are hard: project out of them
    for (const b of bodies) {
      for (let pass = 0; pass < 2; pass++) {
        const tx0 = Math.floor(b.x), ty0 = Math.floor(b.y)
        for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
          const tx = tx0 + dx, ty = ty0 + dy
          if (tx < 0 || ty < 0 || tx >= w.W || ty >= w.H) continue
          const i = ty * w.W + tx
          if (solidAt(w, tx, ty)) {
            const bd = w.buildingById.get(w.occ[i] - 1)!
            const S = BUILDINGS[bd.type].size, m = 0.06
            const x0 = bd.x + m - b.r, x1 = bd.x + S - m + b.r, y0 = bd.y + m - b.r, y1 = bd.y + S - m + b.r
            if (b.x > x0 && b.x < x1 && b.y > y0 && b.y < y1) {
              const l = b.x - x0, r = x1 - b.x, u = b.y - y0, dn = y1 - b.y
              const mn = Math.min(l, r, u, dn)
              // never push someone into the sea or another wall: try the nearest free side
              if (mn === l) b.x = x0; else if (mn === r) b.x = x1; else if (mn === u) b.y = y0; else b.y = y1
            }
          }
          const f = w.feature[i]
          if (f >= F.TREE && f <= F.JUNGLE) {
            for (let k = 0; k < 2; k++) {
              if (k === 1 && hash01(tx, ty) <= 0.72) continue
              const sp = treeSpot(w, tx, ty, k)
              const R = 0.075 * sp.s + b.r
              const ex = b.x - sp.x, ey = b.y - sp.z
              const dd = ex * ex + ey * ey
              if (dd < R * R) {
                const dist = Math.sqrt(dd) || 1e-3
                b.x = sp.x + (ex / dist) * R; b.y = sp.z + (ey / dist) * R
              }
            }
          }
        }
      }
      // remember the offset for next frame (a body that jumped far starts afresh)
      const o = this.offsets.get(b.key)!
      o.x = b.x - b.sx!; o.y = b.y - b.sy!
      const L = Math.hypot(o.x, o.y)
      if (L > 0.8) { o.x *= 0.8 / L; o.y *= 0.8 / L }
    }
  }

  private eraOfTribe(id: number): number {
    let e = this.eraCache.get(id)
    if (e === undefined) {
      e = eraOf(this.world.techs(id))
      this.eraCache.set(id, e)
    }
    return e
  }

  // ---------------------------------------------------------------- frame

  render(now: number) {
    const w = this.world
    const dt = this.lastT ? Math.min(0.1, (now - this.lastT) / 1000) : 0.016
    this.lastT = now
    this.frame++
    const t = now / 1000
    if (this.frame % 30 === 0) this.eraCache.clear()

    // --- camera motion
    if (this.cinematic) {
      this.drift += dt * 0.05
      const tribe = w.tribes.find((x) => x.alive)
      const cx = tribe ? tribe.center.x : w.W / 2, cy = tribe ? tribe.center.y : w.H / 2
      const tx = cx + Math.cos(this.drift) * 10, ty = cy + Math.sin(this.drift * 1.3) * 6
      this.cam.x += (tx - this.cam.x) * Math.min(1, dt * 0.5)
      this.cam.y += (ty - this.cam.y) * Math.min(1, dt * 0.5)
      this.cam.zoom += (1.5 - this.cam.zoom) * Math.min(1, dt * 0.5)
      this.yaw += dt * 0.035
    } else if (this.follow && this.selection.kind === 'agent') {
      const a = w.agentById.get(this.selection.id)
      if (a && a.alive) { this.cam.x += (a.x - this.cam.x) * Math.min(1, dt * 4); this.cam.y += (a.y - this.cam.y) * Math.min(1, dt * 4) }
    } else if (this.target) {
      const k = Math.min(1, dt * 4)
      this.cam.x += (this.target.x - this.cam.x) * k
      this.cam.y += (this.target.y - this.cam.y) * k
      this.cam.zoom += (this.target.zoom - this.cam.zoom) * k
      if (Math.abs(this.target.x - this.cam.x) < 0.05 && Math.abs(this.target.y - this.cam.y) < 0.05 && Math.abs(this.target.zoom - this.cam.zoom) < 0.02) this.target = null
    }
    if (this.yawTarget !== null) {
      this.yaw += (this.yawTarget - this.yaw) * Math.min(1, dt * 6)
      if (Math.abs(this.yawTarget - this.yaw) < 0.002) { this.yaw = this.yawTarget; this.yawTarget = null }
    }
    const gTarget = Math.max(0, this.terrain.heights.surface(this.cam.x, this.cam.y))
    this.groundY += (gTarget - this.groundY) * Math.min(1, dt * 3)
    this.placeCamera()
    const d = this.dist
    const view = d * 2.2 + 18 // radius of interest around the target
    const cx = this.cam.x, cz = this.cam.y

    // --- world changes
    if (w.dirtyChunks.size) {
      for (const k of w.dirtyChunks) this.flora.markDirty32(k)
      w.dirtyChunks.clear()
      this.roadsDirty = true
    }
    this.flora.update(1, cx, cz)
    this.flora.setDetail(d < 42)
    let maxEra = 0
    for (const tr of w.tribes) if (tr.alive) maxEra = Math.max(maxEra, this.eraOfTribe(tr.id))
    if (this.roadsDirty && now - this.roadsT > 1200) {
      this.roadsDirty = false
      this.roadsT = now
      this.terrain.buildRoads(maxEra)
    }
    this.updateOverlay(now)

    // --- light and air
    const h = hourOf(w.time)
    const day = daylight(w.time)
    const night = clamp((0.62 - day) / 0.5, 0, 1)
    this.updateSky(h, day, night, d, w.weather, t)
    this.terrain.update(t, w.season)
    // new buildings level their ground (at most twice a second)
    if (w.buildVersion !== this.padVersion && performance.now() - this.padT > 500) {
      this.padVersion = w.buildVersion
      this.padT = performance.now()
      for (const key of this.terrain.levelPads(w)) this.flora.markDirty32(key)
    }
    wind.uTime.value = t
    wind.uCam.value.copy(this.camera.position)
    wind.uLook.value.set(this.cam.x, this.groundY + 0.2, this.cam.y)
    wind.uCut.value = this.dist < 26 ? clamp(this.dist * 0.14, 0.55, 2.4) : 0
    wind.uWind.value = w.weather === 'storm' ? 3 : w.weather === 'rain' || w.weather === 'snow' ? 1.7 : 1

    // --- buildings
    const seen = new Set<number>()
    const lights: [number, number, number, number, number, number][] = [] // x y z radius warmth dist²
    const v = new THREE.Vector3()
    for (const b of w.buildings) {
      if (b.type === 'road') continue
      seen.add(b.id)
      let m = this.buildings.get(b.id)
      if (!m) { m = new Building3D(b); this.buildings.set(b.id, m); this.worldGroup.add(m.root) }
      m.b = b
      const tr = w.tribeById(b.tribeId)
      const size = BUILDINGS[b.type].size
      const near = Math.hypot(b.x + size / 2 - cx, b.y + size / 2 - cz) < view
      m.root.visible = near || d > 50
      if (!m.root.visible) continue
      m.sync(this.eraOfTribe(b.tribeId), tr?.color ?? '#cccccc', this.buildingGround(b.x, b.y, size, b.type), this.dockDir(b.x, b.y, size))
      m.update(t, night, b.crop)
      const info = m.info
      if (!info || !b.done || !near) continue
      if (info.light && night > 0.05) {
        v.set(info.light[0], info.light[1], info.light[2])
        m.root.localToWorld(v)
        lights.push([v.x, v.y, v.z, info.light[3], info.light[4], (v.x - cx) ** 2 + (v.z - cz) ** 2])
      }
      for (const s of info.smoke) {
        const kind = s[3]
        const rate = kind === 1 ? 6 : kind === 2 ? 3 : (b.type === 'hut' || b.type === 'house') ? (w.season >= 2 || h > 17 || h < 8 ? 1.2 : 0.25) : 1.6
        if (Math.random() < rate * dt) {
          v.set(s[0], s[1], s[2])
          m.root.localToWorld(v)
          if (kind === 1) this.particles.emit(PK.EMBER, v.x, v.y, v.z, (Math.random() - 0.5) * 0.3, 0.7 + Math.random() * 0.5, (Math.random() - 0.5) * 0.3, 1 + Math.random())
          else this.particles.emit(kind === 2 ? PK.STEAM : PK.SMOKE, v.x, v.y, v.z, 0.05 + Math.random() * 0.1, 0.45 + Math.random() * 0.2, (Math.random() - 0.5) * 0.1, 3.5 + Math.random() * 2)
        }
      }
      if (b.hp < 60 && b.type !== 'farm' && b.type !== 'pasture' && Math.random() < dt * 10) {
        // a building burning after a raid
        v.set(0.3 + Math.random() * 0.5 * size, (info.height ?? 0.8) * 0.8, 0.3 + Math.random() * 0.5 * size)
        m.root.localToWorld(v)
        this.particles.emit(Math.random() < 0.5 ? PK.EMBER : PK.SMOKE, v.x, v.y, v.z, (Math.random() - 0.5) * 0.2, 0.6 + Math.random() * 0.5, (Math.random() - 0.5) * 0.2, Math.random() < 0.5 ? 1.2 : 4)
        if (lights.length < 12) lights.push([v.x, v.y, v.z, 3, 1, (v.x - cx) ** 2 + (v.z - cz) ** 2])
      }
      if (b.type === 'campfire' && Math.random() < dt * 1.2) {
        v.set(0.5, 0.4, 0.5)
        m.root.localToWorld(v)
        this.particles.emit(PK.SMOKE, v.x, v.y, v.z, 0.05, 0.5, 0, 3)
      }
      if (m.rocket && maxEra >= 9) m.rocket.position.y = 0.1 + Math.max(0, Math.sin(t * 0.05 + b.id) - 0.96) * 300
    }
    for (const [id, m] of this.buildings) if (!seen.has(id)) { this.worldGroup.remove(m.root); m.dispose(); this.buildings.delete(id) }
    glowMat.emissiveIntensity = night * 2.6
    // nearest lamps light the night
    lights.sort((a, b) => a[5] - b[5])
    this.lamps.forEach((l, k) => {
      const L = lights[k]
      if (!L || night < 0.05) { l.intensity = 0; return }
      const fl = L[4] > 0.9 ? 0.85 + Math.sin(t * 11 + k) * 0.1 + Math.sin(t * 23 + k * 3) * 0.05 : 1
      l.position.set(L[0], L[1] + 0.3, L[2])
      l.color.set(L[4] > 0.5 ? '#ff9a4a' : L[4] > 0.25 ? '#ffc98a' : '#d8e6ff')
      l.distance = L[3] * 1.6
      l.intensity = night * fl * (0.9 + L[3] * 0.7)
    })

    // --- people
    const alive = new Set<number>()
    const hs = this.terrain.heights
    const bodies: Body[] = []
    for (const a of w.agents) {
      if (!a.alive) continue
      alive.add(a.id)
      let p = this.people.get(a.id)
      if (!p) { p = new Person3D(a.id); this.people.set(a.id, p); this.worldGroup.add(p.root) }
      const inView = Math.hypot(a.x - cx, a.y - cz) < view && d < 95
      const indoors = inView && this.indoors(w, a)
      p.root.visible = inView && !indoors
      if (!p.root.visible) { const boat = this.boats.get(a.id); if (boat) boat.visible = false; continue }
      bodies.push({ key: a.id, x: a.x, y: a.y, r: w.age(a) < 14 ? 0.06 : 0.085, moving: a.moving })
    }
    // --- animals join the same crowd: nothing walks through anything else
    const live = new Set<number>()
    for (const an of w.animals) {
      if (!an.alive) continue
      live.add(an.id)
      let m = this.animals.get(an.id)
      if (!m) { m = new Animal3D(an); this.animals.set(an.id, m); this.worldGroup.add(m.root) }
      m.an = an
      const inView = Math.hypot(an.x - cx, an.y - cz) < view && d < 80
      m.root.visible = inView
      if (inView) bodies.push({ key: -an.id - 1, x: an.x, y: an.y, r: an.kind === 'rabbit' ? 0.05 : an.kind === 'boar' ? 0.1 : 0.12, moving: true })
    }
    this.unclip(w, bodies, dt)
    const at = new Map<number, Body>()
    for (const b of bodies) at.set(b.key, b)
    for (const a of w.agents) {
      if (!a.alive) continue
      const bd = at.get(a.id)
      if (!bd) continue
      const p = this.people.get(a.id)!
      const boat = this.boats.get(a.id)
      const x = bd.x, z = bd.y
      const ti = Math.floor(z) * w.W + Math.floor(x)
      const bio = w.biome[Math.floor(a.y) * w.W + Math.floor(a.x)]
      const sea = bio === B.DEEP_OCEAN || bio === B.OCEAN || bio === B.SHALLOWS || bio === B.LAKE
      const lv = hs.level[ti]
      let y = hs.at(x, z)
      if (sea && !Number.isNaN(lv)) y = lv + 0.08
      const tr = w.tribeById(a.tribeId)
      const camDist = this.camera.position.distanceTo(p.root.position)
      p.update(a, x, y, z, t, this.eraOfTribe(a.tribeId), tr?.color ?? '#cccccc', tr?.leaderId === a.id, w.age(a), camDist < 24)
      if (sea) {
        let bm = boat
        const tier = boatTier(w.techs(a.tribeId))
        if (bm && bm.userData.tier !== tier) { this.worldGroup.remove(bm); bm = undefined }
        if (!bm) { bm = makeBoat(tier); this.boats.set(a.id, bm); this.worldGroup.add(bm) }
        bm.visible = true
        bm.position.set(x, (Number.isNaN(lv) ? y : lv) + Math.sin(t * 1.6 + a.id) * 0.03, z)
        bm.rotation.y = p.heading
        bm.rotation.z = Math.sin(t * 1.2 + a.id) * 0.05
      } else if (boat) boat.visible = false
      if (p.impact && Math.random() < 0.35) {
        p.toolTip(v)
        const item = a.task?.target ?? ''
        const kind = item === 'wood' ? PK.CHIP : a.task?.action === 'build' || a.task?.action === 'craft' ? PK.DUST : PK.SPARK
        this.particles.burst(kind, v.x, v.y, v.z, 3, 0.8)
      }
    }
    for (const [id, p] of this.people) if (!alive.has(id)) {
      this.worldGroup.remove(p.root); p.dispose(); this.people.delete(id)
      this.offsets.delete(id)
      const bm = this.boats.get(id)
      if (bm) { this.worldGroup.remove(bm); this.boats.delete(id) }
    }
    for (const an of w.animals) {
      const bd = at.get(-an.id - 1)
      const m = this.animals.get(an.id)
      if (bd && m) m.update(bd.x, hs.at(bd.x, bd.y), bd.y, t)
    }
    for (const [id, m] of this.animals) if (!live.has(id)) { this.worldGroup.remove(m.root); this.animals.delete(id); this.offsets.delete(-id - 1) }

    // --- the clash of battle: sparks for blades, smoke and flashes for guns
    for (const c of w.dip.campaigns) {
      if (c.phase !== 'battle') continue
      const guns = militaryTier(w.techs(c.side)).weapon === 'rifle'
      for (const id of c.units) {
        const p = this.people.get(id)
        if (!p || !p.root.visible || Math.random() > dt * (guns ? 3 : 2.2)) continue
        p.headTop(v)
        v.y -= p.root.scale.y * 0.25
        if (guns) {
          this.particles.burst(PK.SPARK, v.x, v.y, v.z, 2, 0.3)
          this.particles.emit(PK.SMOKE, v.x, v.y, v.z, (Math.random() - 0.5) * 0.3, 0.2, (Math.random() - 0.5) * 0.3, 2.5)
        } else this.particles.burst(Math.random() < 0.7 ? PK.SPARK : PK.DUST, v.x, v.y, v.z, 3, 0.6)
      }
    }

    // --- selection ring
    const sel = this.selection
    const sa = sel.kind === 'agent' ? this.people.get(sel.id) : undefined
    this.ring.visible = !!sa && sa.root.visible
    if (sa && this.ring.visible) {
      this.ring.position.copy(sa.root.position)
      this.ring.position.y += 0.05
      const pulse = 1 + Math.sin(t * 4) * 0.06
      this.ring.scale.setScalar(pulse * sa.root.scale.x * 0.8)
      const a = w.agentById.get(sel.id)
      ;(this.ring.material as THREE.MeshBasicMaterial).color.set(a ? w.tribeById(a.tribeId)?.color ?? '#ffffff' : '#ffffff')
    }

    // --- ambient life and air
    const season = w.season
    if (season === 2 && d < 60 && Math.random() < dt * 5) {
      const x = cx + (Math.random() - 0.5) * d * 1.6, z = cz + (Math.random() - 0.5) * d * 1.2
      const f = w.feature[clamp(Math.floor(z), 0, w.H - 1) * w.W + clamp(Math.floor(x), 0, w.W - 1)]
      if (f === F.TREE || f === F.JUNGLE) this.particles.emit(PK.LEAF, x, hs.at(x, z) + 1.4, z, 0.3, -0.3, 0.1, 4)
    }
    if (season === 1 && night > 0.6 && d < 50 && this.particles.count < 900 && Math.random() < dt * 12) {
      const x = cx + (Math.random() - 0.5) * d * 1.6, z = cz + (Math.random() - 0.5) * d * 1.2
      const ti = clamp(Math.floor(z), 0, w.H - 1) * w.W + clamp(Math.floor(x), 0, w.W - 1)
      const b = w.biome[ti]
      if (b === B.GRASSLAND || b === B.FOREST || b === B.MARSH || b === B.RAINFOREST) this.particles.emit(PK.FIREFLY, x, hs.at(x, z) + 0.3 + Math.random() * 0.6, z, 0, 0, 0, 5 + Math.random() * 4)
    }
    this.particles.update(dt, t, wind.uWind.value, night)
    this.weather.update(dt, t, w.weather, cx, this.groundY, cz, Math.min(60, d * 1.8 + 10))
    this.clouds.update(dt, w.weather, this.sky, this.camera.position.y)
    this.birds.update(dt, t, cx, cz, d * 1.4 + 10, day, season)

    // --- draw
    if (this.composer) this.composer.render()
    else this.gl.render(this.scene, this.camera)
    this.drawOverlay(now, night)
  }

  private buildingGround(x: number, y: number, s: number, type: string): number {
    const hs = this.terrain.heights
    let sum = 0, mx = -1e9
    for (const [dx, dy] of [[0.2, 0.2], [s - 0.2, 0.2], [0.2, s - 0.2], [s - 0.2, s - 0.2], [s / 2, s / 2]]) {
      const g = type === 'dock' || type === 'offshore_rig' || type === 'fish_farm' ? hs.surface(x + dx, y + dy) : hs.at(x + dx, y + dy)
      sum += g
      mx = Math.max(mx, g)
    }
    if (type === 'offshore_rig') return Math.max(0, mx)
    // sit on the average ground; the plinth buries the high side and fills the low
    return type === 'farm' || type === 'pasture' ? sum / 5 : Math.max(sum / 5, mx - 0.3) - 0.02
  }
  private dockDir(x: number, y: number, s = 1): number {
    const w = this.world
    const water = (xx: number, yy: number) => w.inBounds(xx, yy) && (w.biome[w.idx(xx, yy)] <= B.RIVER)
    const side = (dx: number, dy: number) => { let n = 0; for (let k = 0; k < s; k++) n += water(dx === 0 ? x + k : dx > 0 ? x + s : x - 1, dy === 0 ? y + k : dy > 0 ? y + s : y - 1) ? 1 : 0; return n }
    const opts: [number, number][] = [[side(0, 1), 0], [side(1, 0), Math.PI / 2], [side(0, -1), Math.PI], [side(-1, 0), -Math.PI / 2]]
    opts.sort((a, b) => b[0] - a[0])
    return opts[0][0] ? opts[0][1] : 0
  }

  private updateOverlay(now: number) {
    const w = this.world
    const sel = this.selectedTribeId()
    const key = `${this.overlay}|${this.overlay === 'explored' ? sel : 0}`
    const stale = this.overlay === 'territory' ? w.territoryDirty && now - this.overlayT > 1000 : this.overlay !== 'none' && now - this.overlayT > 2500
    if (key !== this.overlayKey || stale) {
      this.overlayKey = key
      this.overlayT = now
      this.terrain.setOverlay(this.overlay, sel)
      if (this.overlay === 'territory') w.territoryDirty = false
    }
    // borders fade as the camera comes down to the ground
    this.terrain.uniforms.uStrength.value = clamp(0.35 + (this.dist - 10) / 40, 0.35, 1)
  }

  private updateSky(h: number, day: number, night: number, d: number, weather: string, t: number) {
    const ang = ((h - 6) / 12) * Math.PI
    const el = Math.sin(ang)
    const sunI = 3.4 * smoothstep(-0.04, 0.32, el)
    const moonI = 0.55 * smoothstep(0.02, -0.3, el)
    const golden = clamp(1 - Math.abs(el - 0.08) / 0.3, 0, 1)
    const over = weather === 'storm' ? 0.3 : weather === 'rain' || weather === 'snow' ? 0.45 : weather === 'cloudy' ? 0.6 : weather === 'fog' ? 0.55 : 1
    const dir = new THREE.Vector3()
    if (sunI >= moonI) {
      dir.set(Math.cos(ang), Math.max(0.12, el), 0.42).normalize()
      this.sun.color.set('#ffd2a0').lerp(new THREE.Color('#fff6ea'), smoothstep(0.08, 0.55, el))
      this.sun.intensity = sunI * over
    } else {
      dir.set(-Math.cos(ang) * 0.6, 0.85, -0.35).normalize()
      this.sun.color.set('#9fb4ff')
      this.sun.intensity = moonI * (0.5 + over * 0.5)
    }
    // the light frames the area around the camera target
    const S = clamp(d * 0.95, 12, 70)
    const sc = this.sun.shadow.camera
    if (sc.right !== S) { sc.left = -S; sc.right = S; sc.top = S; sc.bottom = -S; sc.updateProjectionMatrix() }
    // snap to shadow texels so edges don't crawl as the camera pans
    const texel = (2 * S) / this.sun.shadow.mapSize.x
    const tx = Math.round(this.cam.x / texel) * texel, tz = Math.round(this.cam.y / texel) * texel
    this.sun.target.position.set(tx, this.groundY, tz)
    this.sun.position.set(tx + dir.x * 120, this.groundY + dir.y * 120, tz + dir.z * 120)

    const skyDay = new THREE.Color('#a9c9e6'), skyGold = new THREE.Color('#e9a878'), skyNight = new THREE.Color('#0c1630')
    this.sky.copy(skyDay).lerp(skyGold, golden * 0.75).lerp(skyNight, night)
    if (over < 1) this.sky.lerp(new THREE.Color(night > 0.5 ? '#141a24' : '#8f98a4'), (1 - over) * 0.8)
    if (weather === 'fog') this.sky.lerp(new THREE.Color('#c8ced4').multiplyScalar(1 - night * 0.8), 0.7)
    this.hemi.color.set('#cfe3ff').lerp(new THREE.Color('#ffc89a'), golden * 0.5).lerp(new THREE.Color('#2a3c6a'), night)
    this.hemi.groundColor.set('#5a4a36').lerp(new THREE.Color('#10141c'), night)
    this.hemi.intensity = (0.4 + day * 0.32) * (0.75 + over * 0.25)
    // lightning
    if (weather === 'storm' && Math.random() < 0.003) this.flash = 1
    this.flash = Math.max(0, this.flash - 0.04)
    this.hemi.intensity += this.flash * 3
    this.fog.color.copy(this.sky)
    const fogK = weather === 'fog' ? 0.35 : weather === 'rain' || weather === 'storm' || weather === 'snow' ? 0.7 : 1
    this.fog.near = (d * 1.6 + 12) * fogK
    this.fog.far = (d * 4.2 + 90) * fogK
    this.gl.toneMappingExposure = 1.0 + night * 0.25
    this.scene.environmentIntensity = 0.18 + day * 0.32

    // re-bake the reflection sky every few seconds when the colours change
    const key = `${this.sky.getHexString()}|${Math.round(el * 10)}`
    if (key !== this.envKey && t - this.envT > 4) {
      this.envKey = key
      this.envT = t
      const top = this.sky.clone().lerp(new THREE.Color(night > 0.5 ? '#050a18' : '#3f74b8'), 0.6)
      this.skyMat.uniforms.uTop.value.copy(top)
      this.skyMat.uniforms.uHor.value.copy(this.sky)
      this.skyMat.uniforms.uSun.value.copy(dir)
      this.skyMat.uniforms.uSunCol.value.copy(this.sun.color).multiplyScalar(sunI > moonI ? 1 : 0.15)
      const rt = this.pmrem.fromScene(this.skyScene, 0.02)
      this.envRT?.dispose()
      this.envRT = rt
      this.scene.environment = rt.texture
    }
  }

  // ---------------------------------------------------------------- overlay (labels, speech, feelings)

  private drawOverlay(now: number, night: number) {
    const w = this.world
    const ctx = this.octx
    const k = this.over.width / Math.max(1, this.vw)
    ctx.setTransform(k, 0, 0, k, 0, 0)
    ctx.clearRect(0, 0, this.vw, this.vh)
    const d = this.dist
    const v = new THREE.Vector3()

    // hover tile
    if (this.hover && d < 60) this.tileOutline(this.hover.x, this.hover.y, 1, 'rgba(255,255,255,0.55)', 1.2)
    const sel = this.selection
    if (sel.kind === 'tile') this.tileOutline(sel.id % w.W, Math.floor(sel.id / w.W), 1, '#ffffff', 2)
    if (sel.kind === 'building') {
      const b = w.buildingById.get(sel.id)
      if (b) { ctx.setLineDash([6, 4]); this.tileOutline(b.x, b.y, BUILDINGS[b.type].size, '#ffffff', 2); ctx.setLineDash([]) }
    }
    // the selected person's path
    if (sel.kind === 'agent') {
      const a = w.agentById.get(sel.id)
      const p = a?.task?.path
      if (a && a.alive && p && a.task && a.task.pathIdx < p.length) {
        ctx.strokeStyle = w.tribeById(a.tribeId)?.color ?? '#fff'
        ctx.globalAlpha = 0.9
        ctx.lineWidth = 2.5
        ctx.setLineDash([8, 6])
        ctx.lineDashOffset = -now / 40
        ctx.beginPath()
        const s0 = this.worldToScreen(a.x, a.y, this.terrain.heights.at(a.x, a.y) + 0.05)
        ctx.moveTo(s0.x, s0.y)
        for (let q = a.task.pathIdx; q < p.length; q++) {
          const px = (p[q] % w.W) + 0.5, py = Math.floor(p[q] / w.W) + 0.5
          const s = this.worldToScreen(px, py, this.terrain.heights.surface(px, py) + 0.05)
          ctx.lineTo(s.x, s.y)
        }
        ctx.stroke()
        ctx.setLineDash([])
        ctx.globalAlpha = 1
        const last = p[p.length - 1]
        this.tileOutline(last % w.W, Math.floor(last / w.W), 1, '#ffffff', 1.5)
      }
    }

    ctx.textAlign = 'center'
    ctx.textBaseline = 'alphabetic'
    // tribe banners when looking from afar
    if (d > 30) {
      ctx.font = '600 12px Inter, sans-serif'
      for (const t of w.tribes) {
        if (!t.alive) continue
        const p = this.worldToScreen(t.center.x + 0.5, t.center.y + 0.5, this.terrain.heights.surface(t.center.x + 0.5, t.center.y + 0.5) + 2.5)
        if (p.behind || p.x < -100 || p.x > this.vw + 100 || p.y < -40 || p.y > this.vh + 40) continue
        const label = `${t.emblem} ${t.name}`
        const tw = ctx.measureText(label).width + 16
        ctx.fillStyle = 'rgba(8,12,22,0.75)'
        roundRect(ctx, p.x - tw / 2, p.y - 26, tw, 20, 10)
        ctx.fill()
        ctx.fillStyle = t.color
        ctx.fillRect(p.x - tw / 2 + 8, p.y - 9, tw - 16, 1.5)
        ctx.fillStyle = '#fff'
        ctx.fillText(label, p.x, p.y - 12)
      }
    }

    const showNames = this.showNames && d < 30
    const placed: [number, number, number, number][] = []
    const free = (x: number, y: number, wd: number, h: number) => {
      for (const [px, py, pw, ph] of placed) if (x < px + pw && x + wd > px && y < py + ph && y + h > py) return false
      return true
    }
    type L = { a: Agent; x: number; y: number; z: number }
    const vis: L[] = []
    for (const a of w.agents) {
      if (!a.alive) continue
      const p = this.people.get(a.id)
      if (!p || !p.root.visible) continue
      p.headTop(v)
      const s = this.project(v)
      if (s.behind || s.x < -60 || s.x > this.vw + 60 || s.y < -60 || s.y > this.vh + 80) continue
      vis.push({ a, x: s.x, y: s.y, z: this.camera.position.distanceTo(v) })
    }
    const isSel = (a: Agent) => sel.kind === 'agent' && sel.id === a.id
    vis.sort((p, q) => {
      const sp = (isSel(p.a) ? 2 : 0) + (p.a.speech && now < p.a.speech.until ? 1 : 0)
      const sq = (isSel(q.a) ? 2 : 0) + (q.a.speech && now < q.a.speech.until ? 1 : 0)
      return sq - sp || p.z - q.z
    })
    for (const { a, x, y } of vis) {
      const selected = isSel(a)
      // a thinking mind
      if (a.aiPending) {
        const pl = 0.5 + 0.5 * Math.sin(now / 200 + a.id)
        ctx.fillStyle = `rgba(186,160,255,${0.3 * pl})`
        ctx.beginPath(); ctx.arc(x, y - 10, 11, 0, Math.PI * 2); ctx.fill()
        for (let q = 0; q < 3; q++) {
          const ph = Math.sin(now / 160 - q * 0.8)
          ctx.fillStyle = `rgba(226,214,255,${0.55 + ph * 0.4})`
          ctx.beginPath(); ctx.arc(x - 6 + q * 6, y - 10 - Math.max(0, ph) * 2, 2.2, 0, Math.PI * 2); ctx.fill()
        }
      }
      // feelings
      let icon = a.emote && now < a.emote.until ? a.emote.icon : ''
      if (!icon && a.sick > 0.5) icon = '🤒'
      if (!icon && a.hunger > 85) icon = '🍖'
      if (!icon && a.warmth < 25) icon = '🥶'
      if (icon && d < 45 && !a.aiPending) {
        const fresh = a.emote && now < a.emote.until ? Math.min(1, (a.emote.until - now) / 500) : 0.85
        const pop = a.emote && now < a.emote.until ? 1 + Math.max(0, Math.min(1, 1 - (now - a.emote.start) / 300)) * 0.4 : 1
        const fs = Math.round(clamp(260 / d, 13, 26) * pop)
        const bx = x + fs * 0.8, by = y - fs * 0.6 - Math.sin(now / 300 + a.id) * 2
        ctx.globalAlpha = fresh
        ctx.fillStyle = 'rgba(255,255,255,0.94)'
        ctx.beginPath(); ctx.arc(bx, by, fs * 0.68, 0, Math.PI * 2); ctx.fill()
        ctx.beginPath(); ctx.moveTo(bx - fs * 0.5, by + fs * 0.3); ctx.lineTo(x + 2, y + 2); ctx.lineTo(bx - fs * 0.1, by + fs * 0.6); ctx.fill()
        ctx.font = `${fs}px "Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji",sans-serif`
        ctx.textBaseline = 'middle'
        ctx.fillStyle = '#000'
        ctx.fillText(icon, bx, by + 1)
        ctx.textBaseline = 'alphabetic'
        ctx.globalAlpha = 1
      }
      const sp = a.speech
      if (sp && now < sp.until && d < 70) {
        const fade = Math.min(1, (sp.until - now) / 600)
        const box = this.bubble(x, y - 6, sp.text, fade, w.tribeById(a.tribeId)?.color ?? '#fff', selected, selected ? null : free)
        if (box) { placed.push(box); continue }
      }
      if (showNames || selected) {
        ctx.font = `${selected ? 700 : 600} 11.5px Inter, sans-serif`
        const tw = ctx.measureText(a.name).width
        const ny = y - 6
        if (!selected && !free(x - tw / 2, ny - 11, tw, 13)) continue
        placed.push([x - tw / 2, ny - 11, tw, 13])
        ctx.lineWidth = 3
        ctx.strokeStyle = 'rgba(0,0,0,0.6)'
        ctx.strokeText(a.name, x, ny)
        ctx.fillStyle = selected ? '#fff' : 'rgba(255,255,255,0.92)'
        ctx.fillText(a.name, x, ny)
      }
    }

    this.drawDiplomacy(now)

    // a soft cinematic vignette, deeper at night
    const vg = ctx.createRadialGradient(this.vw / 2, this.vh / 2, Math.min(this.vw, this.vh) * 0.4, this.vw / 2, this.vh / 2, Math.max(this.vw, this.vh) * 0.75)
    vg.addColorStop(0, 'rgba(0,0,0,0)')
    vg.addColorStop(1, `rgba(4,8,20,${0.32 + night * 0.18})`)
    ctx.fillStyle = vg
    ctx.fillRect(0, 0, this.vw, this.vh)
    if (this.flash > 0.5) {
      ctx.fillStyle = `rgba(230,236,255,${(this.flash - 0.5) * 0.5})`
      ctx.fillRect(0, 0, this.vw, this.vh)
    }
  }

  /** Lines of alliance, trade, tribute and war between peoples; arrows for armies on the move. */
  private drawDiplomacy(now: number) {
    const w = this.world
    const ctx = this.octx
    const hs = this.terrain.heights
    const live = w.tribes.filter((t) => t.alive)
    const at = (x: number, y: number, lift = 2.2) => this.worldToScreen(x + 0.5, y + 0.5, hs.surface(x + 0.5, y + 0.5) + lift)
    if (this.overlay === 'diplomacy') {
      const style: Record<string, [string, number[], number]> = {
        war: ['#ff3b3b', [10, 7], 4], alliance: ['#4ade80', [], 3.5], trade: ['#fbbf24', [4, 6], 2.5], tribute: ['#c084fc', [2, 5], 2.5],
        friendly: ['rgba(134,239,172,0.6)', [2, 8], 1.5], tense: ['rgba(251,146,60,0.6)', [2, 8], 1.5], hostile: ['rgba(248,113,113,0.8)', [2, 6], 2],
      }
      for (const a of live) for (const b of live) {
        if (a.id >= b.id) continue
        const st = status(w, a.id, b.id)
        const s = style[st]
        if (!s) continue
        const p = at(a.center.x, a.center.y), q = at(b.center.x, b.center.y)
        if (p.behind || q.behind) continue
        ctx.strokeStyle = s[0]
        ctx.lineWidth = s[2]
        ctx.setLineDash(s[1])
        ctx.lineDashOffset = st === 'war' ? -now / 30 : 0
        ctx.beginPath()
        const mx = (p.x + q.x) / 2, my = Math.min(p.y, q.y) - Math.abs(p.x - q.x) * 0.15 - 30
        ctx.moveTo(p.x, p.y); ctx.quadraticCurveTo(mx, my, q.x, q.y); ctx.stroke()
        ctx.setLineDash([])
        const icon = { war: '⚔️', alliance: '🤝', trade: '🐫', tribute: '💰', friendly: '🙂', tense: '😠', hostile: '😡' }[st as 'war']
        ctx.font = '18px "Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji",sans-serif'
        ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
        ctx.fillStyle = 'rgba(8,12,22,0.8)'
        ctx.beginPath(); ctx.arc((p.x + 2 * mx + q.x) / 4, (p.y + 2 * my + q.y) / 4, 14, 0, Math.PI * 2); ctx.fill()
        ctx.fillText(icon, (p.x + 2 * mx + q.x) / 4, (p.y + 2 * my + q.y) / 4 + 1)
        ctx.textBaseline = 'alphabetic'
      }
    }
    // armies on the march (always shown)
    for (const c of w.dip.campaigns) {
      if (c.kind === 'defend' || !c.units.length) continue
      let sx = 0, sy = 0, n = 0
      for (const id of c.units) { const a = w.agentById.get(id); if (a?.alive) { sx += a.x; sy += a.y; n++ } }
      if (!n) continue
      const p = at(sx / n - 0.5, sy / n - 0.5, 0.8), q = at(c.tx, c.ty, 0.8)
      if (p.behind || q.behind) continue
      const col = w.tribeById(c.side)?.color ?? '#ff3b3b'
      ctx.strokeStyle = col
      ctx.lineWidth = 3
      ctx.setLineDash([12, 8])
      ctx.lineDashOffset = -now / 25
      ctx.beginPath(); ctx.moveTo(p.x, p.y); ctx.lineTo(q.x, q.y); ctx.stroke()
      ctx.setLineDash([])
      const ang = Math.atan2(q.y - p.y, q.x - p.x)
      ctx.fillStyle = col
      ctx.beginPath(); ctx.moveTo(q.x, q.y); ctx.lineTo(q.x - Math.cos(ang - 0.4) * 16, q.y - Math.sin(ang - 0.4) * 16); ctx.lineTo(q.x - Math.cos(ang + 0.4) * 16, q.y - Math.sin(ang + 0.4) * 16); ctx.closePath(); ctx.fill()
      ctx.font = '700 12px Inter, sans-serif'
      ctx.textAlign = 'center'
      const label = `${c.phase === 'battle' ? '⚔️ ' : '🛡️ '}${n} ${w.tribeById(c.side)?.name ?? ''}`
      const tw = ctx.measureText(label).width + 14
      ctx.fillStyle = 'rgba(8,12,22,0.8)'
      roundRect(ctx, p.x - tw / 2, p.y - 32, tw, 20, 10); ctx.fill()
      ctx.fillStyle = '#fff'
      ctx.fillText(label, p.x, p.y - 18)
    }
  }

  private tileOutline(x: number, y: number, s: number, color: string, lw: number) {
    const ctx = this.octx
    const hs = this.terrain.heights
    const pts: [number, number][] = []
    const N = 4 * s
    const edge = (x0: number, y0: number, x1: number, y1: number) => {
      for (let k = 0; k < N; k++) { const t = k / N; const px = x0 + (x1 - x0) * t, py = y0 + (y1 - y0) * t; const p = this.worldToScreen(px, py, hs.surface(px, py) + 0.04); pts.push([p.x, p.y]) }
    }
    edge(x, y, x + s, y); edge(x + s, y, x + s, y + s); edge(x + s, y + s, x, y + s); edge(x, y + s, x, y)
    ctx.strokeStyle = color
    ctx.lineWidth = lw
    ctx.beginPath()
    pts.forEach(([px, py], k) => (k ? ctx.lineTo(px, py) : ctx.moveTo(px, py)))
    ctx.closePath()
    ctx.stroke()
  }

  private bubble(x: number, y: number, text: string, alpha: number, color: string, big: boolean, free: ((x: number, y: number, w: number, h: number) => boolean) | null): [number, number, number, number] | null {
    const ctx = this.octx
    ctx.font = `500 ${big ? 12.5 : 11.5}px Inter, sans-serif`
    const maxW = big ? 240 : 190
    const words = text.split(/\s+/)
    const lines: string[] = []
    let cur = ''
    const maxL = big ? 5 : 3
    for (const wd of words) {
      const test = cur ? cur + ' ' + wd : wd
      if (ctx.measureText(test).width > maxW && cur) { lines.push(cur); cur = wd } else cur = test
      if (lines.length >= maxL) break
    }
    if (cur && lines.length < maxL) lines.push(cur)
    else if (lines.length >= maxL) lines[lines.length - 1] += '…'
    const lh = big ? 16 : 14.5
    let wmax = 0
    for (const l of lines) wmax = Math.max(wmax, ctx.measureText(l).width)
    const bw = wmax + 18, bh = lines.length * lh + 10
    const bx = x - bw / 2, by = y - bh - 8
    if (free && !free(bx, by, bw, bh + 8)) return null
    ctx.globalAlpha = alpha
    ctx.fillStyle = 'rgba(250,250,252,0.96)'
    ctx.shadowColor = 'rgba(0,0,0,0.35)'
    ctx.shadowBlur = 10
    roundRect(ctx, bx, by, bw, bh, 9)
    ctx.fill()
    ctx.shadowBlur = 0
    ctx.beginPath(); ctx.moveTo(x - 6, by + bh - 0.5); ctx.lineTo(x, by + bh + 7); ctx.lineTo(x + 6, by + bh - 0.5); ctx.closePath(); ctx.fill()
    ctx.fillStyle = color
    ctx.fillRect(bx + 8, by + 3, bw - 16, 2)
    ctx.fillStyle = '#1c2230'
    ctx.textAlign = 'center'
    lines.forEach((l, k) => ctx.fillText(l, x, by + 8 + lh * (k + 0.78)))
    ctx.globalAlpha = 1
    return [bx, by, bw, bh + 8]
  }

  // ---------------------------------------------------------------- minimap

  renderMinimap(c: HTMLCanvasElement) {
    const m = this.mini
    m.world = this.world
    m.vw = this.vw
    m.vh = this.vh
    m.cam.x = this.cam.x
    m.cam.y = this.cam.y
    const visW = 2 * this.dist * Math.tan((FOV * Math.PI) / 360) * (this.vw / Math.max(1, this.vh)) * 1.2
    m.cam.zoom = this.vw / (TP * Math.max(1, visW))
    m.renderMinimap(c)
  }

  dispose() {
    window.removeEventListener('keydown', this.keyHandler)
    this.over.remove()
    this.terrain.dispose()
    this.flora.dispose()
    this.envRT?.dispose()
    this.pmrem.dispose()
    this.composer?.dispose()
    this.gl.dispose()
    this.gl.forceContextLoss()
  }
}

/** Boats by what a people knows: dugout canoe → sailing smack → ocean ship → steam trawler → diesel trawler. */
export function boatTier(known: Set<string>): number {
  return known.has('diesel_trawlers') ? 4 : known.has('steamships') ? 3 : known.has('shipbuilding') ? 2 : known.has('sailing') ? 1 : 0
}
const boatTemplates = new Map<number, THREE.Group>()
const bm = (c: string, r = 0.8) => new THREE.MeshStandardMaterial({ color: c, roughness: r })
function hullGeo(w: number, h: number, l: number): THREE.BufferGeometry {
  const g = new THREE.SphereGeometry(0.3, 14, 7, 0, Math.PI * 2, Math.PI / 2, Math.PI / 2)
  g.scale(w, h, l)
  g.translate(0, 0.12, 0)
  return g
}
function boatTemplate(tier: number): THREE.Group {
  let g = boatTemplates.get(tier)
  if (g) return g
  g = new THREE.Group()
  const add = (geo: THREE.BufferGeometry, m: THREE.Material, x = 0, y = 0, z = 0) => { const o = new THREE.Mesh(geo, m); o.position.set(x, y, z); o.castShadow = true; g!.add(o); return o }
  if (tier === 0) {
    add(hullGeo(0.55, 0.45, 1.5), bm('#6b4a2b'))
    add(new THREE.BoxGeometry(0.02, 0.02, 0.6), bm('#8a6a44'), 0.12, 0.2, 0.1).rotation.x = 0.6
  } else if (tier === 1) {
    add(hullGeo(0.8, 0.6, 1.8), bm('#7a5530'))
    add(new THREE.CylinderGeometry(0.012, 0.015, 0.9, 6), bm('#5a3d26'), 0, 0.55, 0.05)
    const sail = add(new THREE.PlaneGeometry(0.42, 0.6), new THREE.MeshStandardMaterial({ color: '#c23b22', roughness: 0.9, side: THREE.DoubleSide }), 0.01, 0.6, -0.08)
    sail.rotation.y = Math.PI / 2 + 0.25
  } else if (tier === 2) {
    add(hullGeo(1.0, 0.8, 2.4), bm('#5a3d26'))
    add(new THREE.BoxGeometry(0.4, 0.14, 0.3), bm('#6b4a2b'), 0, 0.2, -0.5)
    for (const [z, h] of [[0.2, 1.3], [-0.25, 1.1]]) {
      add(new THREE.CylinderGeometry(0.015, 0.02, h, 6), bm('#3e2818'), 0, 0.2 + h / 2, z)
      const sail = add(new THREE.PlaneGeometry(0.55, h * 0.55), new THREE.MeshStandardMaterial({ color: '#efe7d6', roughness: 0.95, side: THREE.DoubleSide }), 0, 0.35 + h * 0.4, z)
      sail.rotation.y = Math.PI / 2
    }
  } else {
    const steel = tier === 4
    add(hullGeo(1.0, 0.85, 2.4), bm(steel ? '#b23a2e' : '#2e3a4a', 0.6))
    add(new THREE.BoxGeometry(0.42, 0.3, 0.5), bm('#f4f4f2', 0.5), 0, 0.32, -0.25)
    add(new THREE.BoxGeometry(0.36, 0.06, 0.44), bm('#3a3a40'), 0, 0.5, -0.25)
    add(new THREE.CylinderGeometry(0.05, 0.06, 0.4, 8), bm(steel ? '#f4f4f2' : '#1a1a1e'), 0, 0.55, -0.55)
    // the gantry that hauls the trawl net over the stern
    add(new THREE.BoxGeometry(0.4, 0.04, 0.04), bm('#e0a030'), 0, 0.62, 0.62)
    add(new THREE.BoxGeometry(0.03, 0.45, 0.03), bm('#e0a030'), -0.18, 0.4, 0.62)
    add(new THREE.BoxGeometry(0.03, 0.45, 0.03), bm('#e0a030'), 0.18, 0.4, 0.62)
  }
  boatTemplates.set(tier, g)
  return g
}
function makeBoat(tier: number): THREE.Group {
  const g = boatTemplate(tier).clone()
  g.userData.tier = tier
  return g
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}
