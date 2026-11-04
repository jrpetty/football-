// A WebGL layer over the stage that renders the four people in 3D. It reads
// each actor's on-screen box from the DOM every frame, so walking between
// places follows the stage's own smooth transitions, and it lights the cast
// with a warm key light, a soft sky fill and a rim light in the scenario's
// accent colour.

import * as THREE from 'three'
import type { Person } from '../engine/types.ts'
import { Rig, type State } from './rig3d.ts'

export interface CastEntry {
  p: Person
  state: State
  talking: boolean
  color: string
  selected: boolean
  faceTo: number | null // person id being addressed
}

export function webgl(): boolean {
  try { return !!document.createElement('canvas').getContext('webgl2') } catch { return false }
}

export class Cast3D {
  private gl: THREE.WebGLRenderer
  private scene = new THREE.Scene()
  private camera = new THREE.OrthographicCamera(0, 1, 1, 0, -5000, 5000)
  private rigs = new Map<number, Rig>()
  private last = new Map<number, number>()
  private key: THREE.DirectionalLight
  private rim: THREE.DirectionalLight
  private hemi: THREE.HemisphereLight
  private canvas: HTMLCanvasElement
  private stage: HTMLElement
  private raf = 0
  private t0 = performance.now()
  private lastT = 0
  entries: CastEntry[] = []
  scenario = ''
  night = 0
  accent = '#ffffff'

  constructor(canvas: HTMLCanvasElement, stage: HTMLElement) {
    this.canvas = canvas
    this.stage = stage
    this.gl = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, premultipliedAlpha: true })
    this.gl.setClearColor(0x000000, 0)
    this.gl.outputColorSpace = THREE.SRGBColorSpace
    this.gl.toneMapping = THREE.ACESFilmicToneMapping
    this.gl.toneMappingExposure = 1.1
    this.hemi = new THREE.HemisphereLight('#dfe9ff', '#5a4a3a', 1.1)
    this.key = new THREE.DirectionalLight('#fff1dc', 2.6)
    this.key.position.set(-0.6, 1, 1.2)
    this.rim = new THREE.DirectionalLight('#9fd0ff', 1.6)
    this.rim.position.set(0.7, 0.8, -1)
    const front = new THREE.DirectionalLight('#ffffff', 0.5)
    front.position.set(0.3, 0.2, 1)
    this.scene.add(this.hemi, this.key, this.rim, front)
    this.camera.position.z = 1000
    const loop = () => {
      this.raf = requestAnimationFrame(loop)
      this.frame()
    }
    this.raf = requestAnimationFrame(loop)
  }

  private frame() {
    const now = performance.now()
    const t = (now - this.t0) / 1000
    const dt = this.lastT ? Math.min(0.1, t - this.lastT) : 0.016
    this.lastT = t
    const sr = this.stage.getBoundingClientRect()
    const W = Math.max(1, sr.width), H = Math.max(1, sr.height)
    const dpr = Math.min(2, window.devicePixelRatio || 1)
    if (this.canvas.width !== Math.round(W * dpr) || this.canvas.height !== Math.round(H * dpr)) {
      this.gl.setPixelRatio(dpr)
      this.gl.setSize(W, H, false)
    }
    this.camera.left = 0
    this.camera.right = W
    this.camera.top = H
    this.camera.bottom = 0
    this.camera.updateProjectionMatrix()
    // light follows the scene's time of day
    this.hemi.intensity = 1.1 - this.night * 0.55
    this.hemi.color.set('#dfe9ff').lerp(new THREE.Color('#5a6ea8'), this.night)
    this.key.intensity = 2.6 - this.night * 1.6
    this.key.color.set('#fff1dc').lerp(new THREE.Color('#9fb4ff'), this.night)
    this.rim.color.set(this.accent).lerp(new THREE.Color('#ffffff'), 0.35)

    const seen = new Set<number>()
    const xs = new Map<number, number>()
    const boxes = new Map<number, DOMRect>()
    for (const e of this.entries) {
      const el = this.stage.querySelector<HTMLElement>(`.actor[data-id="${e.p.id}"] .actor-body`)
      if (!el) continue
      const r = el.getBoundingClientRect()
      boxes.set(e.p.id, r)
      xs.set(e.p.id, r.left + r.width / 2 - sr.left)
    }
    for (const e of this.entries) {
      const r = boxes.get(e.p.id)
      if (!r) continue
      seen.add(e.p.id)
      let rig = this.rigs.get(e.p.id)
      if (!rig) { rig = new Rig(e.p.id); this.rigs.set(e.p.id, rig); this.scene.add(rig.root) }
      const x = xs.get(e.p.id)!
      const footY = r.top + r.height * 0.975 - sr.top
      const prev = this.last.get(e.p.id)
      this.last.set(e.p.id, x)
      // who to face: the person addressed, the way they're walking, or the middle of the stage
      let yaw = Math.max(-0.45, Math.min(0.45, ((W / 2 - x) / W) * 0.9))
      if (e.faceTo !== null && xs.has(e.faceTo)) yaw = Math.sign(xs.get(e.faceTo)! - x) * 0.7
      if (e.state === 'walking' && prev !== undefined && Math.abs(x - prev) > 0.05) yaw = Math.sign(x - prev) * 1.25
      rig.yaw = yaw
      rig.update(e.p, this.scenario, e.state, t, dt, e.selected ? e.color : null, e.talking)
      const scale = (r.height * 0.95) / 1.8
      rig.root.scale.setScalar(scale)
      rig.root.position.set(x, H - footY, -footY)
      rig.root.rotation.x = 0.16
    }
    for (const [id, rig] of this.rigs) if (!seen.has(id)) { this.scene.remove(rig.root); this.rigs.delete(id) }
    this.gl.render(this.scene, this.camera)
  }

  dispose() {
    cancelAnimationFrame(this.raf)
    this.gl.dispose()
  }
}
