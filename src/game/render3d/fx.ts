import * as THREE from 'three'

// Particles: turf thrown up by a strike, dust off a slide, sparks off the woodwork,
// the streak behind a hard-hit ball, a goal's confetti.
//
// Everything is one pool drawn as `THREE.Points` — one draw call for the lot, a
// fixed amount of memory, nothing allocated per effect. There are two pools
// because there are two blend modes: ordinary alpha for matter (turf, dust,
// paper) and additive for light (sparks, glints, the ball's trail).
//
// A particle is only ever visual. Nothing here reads from or writes to the
// simulation, and none of it changes what a shot does.

export interface Puff {
  x: number
  y: number
  z: number
  vx?: number
  vy?: number
  vz?: number
  life: number
  size: number
  // Size at the end of life. Dust grows; a spark shrinks.
  size1?: number
  r: number
  g: number
  b: number
  a?: number
  gravity?: number
  drag?: number
  square?: boolean
  // Confetti flutters: its size flickers as it tumbles edge-on.
  flutter?: boolean
}

const VERT = /* glsl */ `
  attribute float aSize;
  attribute vec4 aColor;
  attribute float aShape;
  varying vec4 vColor;
  varying float vShape;
  uniform float uScale;
  void main() {
    vColor = aColor;
    vShape = aShape;
    vec4 mv = modelViewMatrix * vec4(position, 1.0);
    gl_Position = projectionMatrix * mv;
    gl_PointSize = aSize * uScale / max(0.2, -mv.z);
  }
`

const FRAG = /* glsl */ `
  varying vec4 vColor;
  varying float vShape;
  void main() {
    vec2 d = gl_PointCoord - 0.5;
    float a;
    if (vShape > 0.5) {
      a = 1.0 - smoothstep(0.34, 0.5, max(abs(d.x), abs(d.y)));
    } else {
      a = 1.0 - smoothstep(0.18, 0.5, length(d));
    }
    gl_FragColor = vec4(vColor.rgb, vColor.a * a);
    if (gl_FragColor.a < 0.01) discard;
  }
`

class Pool {
  readonly points: THREE.Points
  private n: number
  private pos: Float32Array
  private col: Float32Array
  private size: Float32Array
  private shape: Float32Array
  // Per-particle state the GPU doesn't need.
  private vel: Float32Array
  private age: Float32Array
  private life: Float32Array
  private s0: Float32Array
  private s1: Float32Array
  private a0: Float32Array
  private grav: Float32Array
  private drag: Float32Array
  private flutter: Uint8Array
  private phase: Float32Array
  private next = 0
  private live = 0
  private material: THREE.ShaderMaterial

  constructor(n: number, additive: boolean) {
    this.n = n
    this.pos = new Float32Array(n * 3)
    this.col = new Float32Array(n * 4)
    this.size = new Float32Array(n)
    this.shape = new Float32Array(n)
    this.vel = new Float32Array(n * 3)
    this.age = new Float32Array(n).fill(1)
    this.life = new Float32Array(n).fill(1)
    this.s0 = new Float32Array(n)
    this.s1 = new Float32Array(n)
    this.a0 = new Float32Array(n)
    this.grav = new Float32Array(n)
    this.drag = new Float32Array(n)
    this.flutter = new Uint8Array(n)
    this.phase = new Float32Array(n)

    const geo = new THREE.BufferGeometry()
    geo.setAttribute('position', new THREE.BufferAttribute(this.pos, 3).setUsage(THREE.DynamicDrawUsage))
    geo.setAttribute('aColor', new THREE.BufferAttribute(this.col, 4).setUsage(THREE.DynamicDrawUsage))
    geo.setAttribute('aSize', new THREE.BufferAttribute(this.size, 1).setUsage(THREE.DynamicDrawUsage))
    geo.setAttribute('aShape', new THREE.BufferAttribute(this.shape, 1).setUsage(THREE.DynamicDrawUsage))

    this.material = new THREE.ShaderMaterial({
      uniforms: { uScale: { value: 600 } },
      vertexShader: VERT,
      fragmentShader: FRAG,
      transparent: true,
      depthWrite: false,
      blending: additive ? THREE.AdditiveBlending : THREE.NormalBlending,
    })
    this.points = new THREE.Points(geo, this.material)
    // Particles are scattered across the whole pitch; the bounding sphere of
    // whatever happens to be live is not worth recomputing.
    this.points.frustumCulled = false
    this.points.renderOrder = additive ? 12 : 11
    this.points.visible = false
  }

  setScale(v: number) {
    this.material.uniforms.uScale.value = v
  }

  get count() {
    return this.live
  }

  emit(p: Puff) {
    const i = this.next
    this.next = (this.next + 1) % this.n
    if (this.age[i] >= this.life[i]) this.live++
    this.pos[i * 3] = p.x
    this.pos[i * 3 + 1] = p.z
    this.pos[i * 3 + 2] = p.y
    this.vel[i * 3] = p.vx ?? 0
    this.vel[i * 3 + 1] = p.vz ?? 0
    this.vel[i * 3 + 2] = p.vy ?? 0
    this.age[i] = 0
    this.life[i] = Math.max(0.05, p.life)
    this.s0[i] = p.size
    this.s1[i] = p.size1 ?? p.size
    this.a0[i] = p.a ?? 1
    this.grav[i] = p.gravity ?? 0
    this.drag[i] = p.drag ?? 0
    this.shape[i] = p.square ? 1 : 0
    this.flutter[i] = p.flutter ? 1 : 0
    this.phase[i] = Math.random() * 6.283
    this.col[i * 4] = p.r
    this.col[i * 4 + 1] = p.g
    this.col[i * 4 + 2] = p.b
    this.col[i * 4 + 3] = this.a0[i]
    this.size[i] = p.size
  }

  update(dt: number) {
    if (!this.live) {
      this.points.visible = false
      return
    }
    let live = 0
    for (let i = 0; i < this.n; i++) {
      if (this.age[i] >= this.life[i]) continue
      this.age[i] += dt
      const t = this.age[i] / this.life[i]
      if (t >= 1) {
        this.size[i] = 0
        this.col[i * 4 + 3] = 0
        continue
      }
      live++
      const k = Math.max(0, 1 - this.drag[i] * dt)
      this.vel[i * 3] *= k
      this.vel[i * 3 + 1] = this.vel[i * 3 + 1] * k - this.grav[i] * dt
      this.vel[i * 3 + 2] *= k
      this.pos[i * 3] += this.vel[i * 3] * dt
      this.pos[i * 3 + 1] += this.vel[i * 3 + 1] * dt
      this.pos[i * 3 + 2] += this.vel[i * 3 + 2] * dt
      // Matter that reaches the ground stays on it and stops.
      if (this.grav[i] > 0 && this.pos[i * 3 + 1] < 0.02) {
        this.pos[i * 3 + 1] = 0.02
        this.vel[i * 3] *= 0.3
        this.vel[i * 3 + 1] = 0
        this.vel[i * 3 + 2] *= 0.3
      }
      let s = this.s0[i] + (this.s1[i] - this.s0[i]) * t
      if (this.flutter[i]) s *= 0.35 + 0.65 * Math.abs(Math.sin(this.phase[i] + this.age[i] * 9))
      this.size[i] = s
      // Fade out over the back half of life.
      this.col[i * 4 + 3] = this.a0[i] * (t < 0.55 ? 1 : 1 - (t - 0.55) / 0.45)
    }
    this.live = live
    this.points.visible = live > 0
    const g = this.points.geometry
    ;(g.getAttribute('position') as THREE.BufferAttribute).needsUpdate = true
    ;(g.getAttribute('aColor') as THREE.BufferAttribute).needsUpdate = true
    ;(g.getAttribute('aSize') as THREE.BufferAttribute).needsUpdate = true
    ;(g.getAttribute('aShape') as THREE.BufferAttribute).needsUpdate = true
  }

  dispose() {
    this.points.geometry.dispose()
    this.material.dispose()
  }
}

export class Fx {
  // Matter (alpha-blended) and light (additive).
  readonly matter = new Pool(700, false)
  readonly light = new Pool(500, true)

  get group(): THREE.Object3D[] {
    return [this.matter.points, this.light.points]
  }

  // How many particles are alive right now — for tests, and for a quality
  // setting that wants to know whether it is being heavy.
  get live() {
    return this.matter.count + this.light.count
  }

  update(dt: number) {
    this.matter.update(dt)
    this.light.update(dt)
  }

  // `viewportH` is in device pixels; `fov` is the camera's vertical field in degrees.
  setView(viewportH: number, fov: number) {
    const scale = (viewportH * 0.5) / Math.tan((fov * Math.PI) / 360)
    this.matter.setScale(scale)
    this.light.setScale(scale)
  }

  dispose() {
    this.matter.dispose()
    this.light.dispose()
  }

  // ---- recipes ----------------------------------------------------------------

  private rnd = (a: number, b: number) => a + Math.random() * (b - a)

  // Turf thrown up where a boot meets the ground. `power` is 0–1; the flick of
  // the foot decides how much comes up, and how far.
  turf(x: number, y: number, dirx: number, diry: number, power: number) {
    const n = 3 + Math.round(power * 9)
    for (let i = 0; i < n; i++) {
      const sp = this.rnd(0.8, 2.2 + power * 4.2)
      const ang = Math.atan2(diry, dirx) + this.rnd(-1.1, 1.1)
      const dark = Math.random() < 0.5
      this.matter.emit({
        x, y, z: 0.05,
        vx: Math.cos(ang) * sp * 0.6 - dirx * 0.4,
        vy: Math.sin(ang) * sp * 0.6 - diry * 0.4,
        vz: this.rnd(1.2, 2.6 + power * 3),
        life: this.rnd(0.5, 0.95),
        size: this.rnd(0.05, 0.11),
        r: dark ? 0.16 : 0.2, g: dark ? 0.32 : 0.5, b: dark ? 0.1 : 0.14,
        gravity: 12, drag: 0.5,
        square: true,
      })
    }
  }

  // A soft puff of dust: a slide, a landing, a heavy touch.
  dust(x: number, y: number, amount: number, vx = 0, vy = 0) {
    const n = 2 + Math.round(amount * 6)
    for (let i = 0; i < n; i++) {
      this.matter.emit({
        x: x + this.rnd(-0.15, 0.15), y: y + this.rnd(-0.15, 0.15), z: 0.06,
        vx: vx * 0.15 + this.rnd(-0.7, 0.7), vy: vy * 0.15 + this.rnd(-0.7, 0.7), vz: this.rnd(0.2, 0.9),
        life: this.rnd(0.45, 0.85),
        size: this.rnd(0.22, 0.4) * (0.6 + amount * 0.6),
        size1: this.rnd(0.7, 1.2) * (0.6 + amount * 0.6),
        r: 0.55, g: 0.55, b: 0.46, a: 0.34 * (0.5 + amount * 0.5),
        drag: 2.6,
      })
    }
  }

  // The scuff of a boot at a sprint: one small, quick puff.
  footDust(x: number, y: number, vx: number, vy: number) {
    this.matter.emit({
      x: x + this.rnd(-0.12, 0.12), y: y + this.rnd(-0.12, 0.12), z: 0.05,
      vx: -vx * 0.08 + this.rnd(-0.3, 0.3), vy: -vy * 0.08 + this.rnd(-0.3, 0.3), vz: this.rnd(0.25, 0.6),
      life: this.rnd(0.35, 0.6),
      size: this.rnd(0.12, 0.2), size1: this.rnd(0.35, 0.55),
      r: 0.5, g: 0.55, b: 0.42, a: 0.24,
      drag: 3,
    })
  }

  // Sparks: the woodwork, a hard strike.
  sparks(x: number, y: number, z: number, amount: number) {
    const n = 6 + Math.round(amount * 12)
    for (let i = 0; i < n; i++) {
      const a = this.rnd(0, Math.PI * 2)
      const sp = this.rnd(1.5, 4 + amount * 5)
      this.light.emit({
        x, y, z,
        vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, vz: this.rnd(-1, 3.4),
        life: this.rnd(0.22, 0.5),
        size: this.rnd(0.06, 0.13), size1: 0.01,
        r: 1, g: 0.86, b: 0.5,
        gravity: 8, drag: 1.2,
      })
    }
    this.light.emit({ x, y, z, life: 0.16, size: 0.35 + amount * 0.3, size1: 0.8 + amount * 0.6, r: 1, g: 0.95, b: 0.8, a: 0.55 })
  }

  // A flash of light at a point, no motion.
  flash(x: number, y: number, z: number, size: number, life = 0.18, r = 1, g = 1, b = 1) {
    this.light.emit({ x, y, z, life, size, size1: size * 1.5, r, g, b, a: 0.6 })
  }

  // The streak behind a fast ball.
  trail(x: number, y: number, z: number, speed: number) {
    const k = Math.min(1, (speed - 10) / 22)
    this.light.emit({
      x, y, z,
      life: 0.22 + k * 0.16,
      size: 0.13 + k * 0.1, size1: 0.02,
      r: 0.85, g: 0.95, b: 1, a: 0.28 + k * 0.4,
    })
  }

  // A goal: paper cannon.
  confetti(x: number, y: number, z: number, dirx: number, diry: number, colours: [number, number, number][]) {
    for (let i = 0; i < 120; i++) {
      const c = colours[(Math.random() * colours.length) | 0]
      const a = Math.atan2(diry, dirx) + this.rnd(-0.9, 0.9)
      const sp = this.rnd(2, 9)
      this.matter.emit({
        x: x + this.rnd(-2, 2), y: y + this.rnd(-2.6, 2.6), z: z + this.rnd(0, 0.8),
        vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, vz: this.rnd(5, 12),
        life: this.rnd(2.0, 3.4),
        size: this.rnd(0.16, 0.28),
        r: c[0], g: c[1], b: c[2],
        gravity: 3.2, drag: 1.1, square: true, flutter: true,
      })
    }
  }
}
