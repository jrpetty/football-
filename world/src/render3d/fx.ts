// Atmosphere and life in the air: a particle system (smoke, steam, embers,
// wood chips, sparks, dust, falling leaves, fireflies), rain and snow that
// fall around the camera, drifting clouds that cast shadows, and flocks of
// birds wheeling overhead.

import * as THREE from 'three'

// ---------------------------------------------------------------- particles

export const PK = { SMOKE: 0, EMBER: 1, STEAM: 2, CHIP: 3, SPARK: 4, DUST: 6, LEAF: 7, FIREFLY: 8 } as const

interface P { x: number; y: number; z: number; vx: number; vy: number; vz: number; life: number; max: number; kind: number; seed: number }

const MAX = 1400

export class Particles {
  points: THREE.Points
  private list: P[] = []
  private pos = new Float32Array(MAX * 3)
  private col = new Float32Array(MAX * 4)
  private size = new Float32Array(MAX)
  private geo = new THREE.BufferGeometry()
  uniforms = { uScale: { value: 400 } }
  constructor() {
    this.geo.setAttribute('position', new THREE.BufferAttribute(this.pos, 3).setUsage(THREE.DynamicDrawUsage))
    this.geo.setAttribute('color', new THREE.BufferAttribute(this.col, 4).setUsage(THREE.DynamicDrawUsage))
    this.geo.setAttribute('size', new THREE.BufferAttribute(this.size, 1).setUsage(THREE.DynamicDrawUsage))
    const mat = new THREE.ShaderMaterial({
      uniforms: this.uniforms,
      transparent: true,
      depthWrite: false,
      vertexShader: `
        attribute float size;
        attribute vec4 color;
        varying vec4 vC;
        uniform float uScale;
        void main() {
          vC = color;
          vec4 mv = modelViewMatrix * vec4(position, 1.0);
          gl_PointSize = size * uScale / max(0.1, -mv.z);
          gl_Position = projectionMatrix * mv;
        }`,
      fragmentShader: `
        varying vec4 vC;
        void main() {
          vec2 d = gl_PointCoord - 0.5;
          float r = length(d);
          if (r > 0.5) discard;
          float a = smoothstep(0.5, 0.1, r);
          gl_FragColor = vec4(vC.rgb, vC.a * a);
        }`,
    })
    this.points = new THREE.Points(this.geo, mat)
    this.points.frustumCulled = false
    this.points.renderOrder = 5
  }
  emit(kind: number, x: number, y: number, z: number, vx = 0, vy = 0, vz = 0, life = 1) {
    if (this.list.length >= MAX) this.list.shift()
    this.list.push({ x, y, z, vx, vy, vz, life, max: life, kind, seed: Math.random() })
  }
  burst(kind: number, x: number, y: number, z: number, n: number, spread = 1) {
    for (let k = 0; k < n; k++) this.emit(kind, x, y, z, (Math.random() - 0.5) * 1.6 * spread, 0.6 + Math.random() * 1.2, (Math.random() - 0.5) * 1.6 * spread, 0.5 + Math.random() * 0.4)
  }
  update(dt: number, t: number, wind: number, night: number) {
    const keep: P[] = []
    for (const p of this.list) {
      p.life -= dt
      if (p.life <= 0) continue
      p.x += p.vx * dt
      p.y += p.vy * dt
      p.z += p.vz * dt
      switch (p.kind) {
        case PK.SMOKE: case PK.STEAM: p.vx += wind * 0.12 * dt; p.vy *= 1 - dt * 0.15; break
        case PK.EMBER: p.vx += Math.sin(t * 5 + p.seed * 9) * dt * 0.4; break
        case PK.CHIP: case PK.SPARK: case PK.DUST: p.vy -= 5.5 * dt; break
        case PK.LEAF: p.vx += Math.sin(p.life * 3 + p.seed * 7) * dt * 0.8; p.vy = -0.35; break
        case PK.FIREFLY: p.vx = Math.sin(t * 0.7 + p.seed * 30) * 0.3; p.vz = Math.cos(t * 0.9 + p.seed * 20) * 0.3; p.vy = Math.sin(t * 1.3 + p.seed * 10) * 0.12; break
      }
      keep.push(p)
    }
    this.list = keep
    const n = keep.length
    for (let k = 0; k < n; k++) {
      const p = keep[k]
      const f = p.life / p.max // 1 → 0
      this.pos[k * 3] = p.x; this.pos[k * 3 + 1] = p.y; this.pos[k * 3 + 2] = p.z
      let r = 1, g = 1, b = 1, a = 1, s = 0.1
      switch (p.kind) {
        case PK.SMOKE: { const v = (0.42 + p.seed * 0.15) * (1 - night * 0.75); r = g = b = v; a = Math.min(1, (1 - f) * 4) * f * (0.5 - night * 0.25); s = 0.25 + (1 - f) * 0.9; break }
        case PK.STEAM: r = g = b = 0.9 * (1 - night * 0.75); a = Math.min(1, (1 - f) * 4) * f * (0.42 - night * 0.2); s = 0.35 + (1 - f) * 1.3; break
        case PK.EMBER: r = 3.2; g = 1.2 + f; b = 0.3; a = f; s = 0.05; break
        case PK.CHIP: r = 0.75; g = 0.56; b = 0.33; a = Math.min(1, f * 2); s = 0.06; break
        case PK.SPARK: r = 3.5; g = 2.4; b = 0.9; a = Math.min(1, f * 2); s = 0.045; break
        case PK.DUST: r = 0.72; g = 0.66; b = 0.56; a = Math.min(0.6, f); s = 0.12 + (1 - f) * 0.2; break
        case PK.LEAF: r = 0.85 + p.seed * 0.1; g = 0.42 + p.seed * 0.25; b = 0.12; a = Math.min(1, f * 3); s = 0.07; break
        case PK.FIREFLY: { const gl = 0.5 + 0.5 * Math.sin(t * 3 + p.seed * 40); r = 2.4 * gl; g = 3 * gl; b = 0.8 * gl; a = Math.min(1, f * 3, (1 - f) * 3) * (0.3 + gl * 0.7); s = 0.06 + gl * 0.05; break }
      }
      this.col[k * 4] = r; this.col[k * 4 + 1] = g; this.col[k * 4 + 2] = b; this.col[k * 4 + 3] = a
      this.size[k] = s
    }
    this.geo.setDrawRange(0, n)
    ;(this.geo.getAttribute('position') as THREE.BufferAttribute).needsUpdate = true
    ;(this.geo.getAttribute('color') as THREE.BufferAttribute).needsUpdate = true
    ;(this.geo.getAttribute('size') as THREE.BufferAttribute).needsUpdate = true
  }
  get count() { return this.list.length }
}

// ---------------------------------------------------------------- precipitation

export class Weather {
  group = new THREE.Group()
  private rain: THREE.LineSegments
  private snow: THREE.Points
  private N = 2400
  private drops: Float32Array
  private rainPos: Float32Array
  private snowPos: Float32Array
  constructor() {
    this.drops = new Float32Array(this.N * 4)
    for (let k = 0; k < this.N; k++) {
      this.drops[k * 4] = Math.random()
      this.drops[k * 4 + 1] = Math.random()
      this.drops[k * 4 + 2] = Math.random()
      this.drops[k * 4 + 3] = 0.7 + Math.random() * 0.6
    }
    this.rainPos = new Float32Array(this.N * 6)
    const rg = new THREE.BufferGeometry()
    rg.setAttribute('position', new THREE.BufferAttribute(this.rainPos, 3).setUsage(THREE.DynamicDrawUsage))
    this.rain = new THREE.LineSegments(rg, new THREE.LineBasicMaterial({ color: '#b8cde6', transparent: true, opacity: 0.45, depthWrite: false }))
    this.rain.frustumCulled = false
    this.snowPos = new Float32Array(this.N * 3)
    const sg = new THREE.BufferGeometry()
    sg.setAttribute('position', new THREE.BufferAttribute(this.snowPos, 3).setUsage(THREE.DynamicDrawUsage))
    this.snow = new THREE.Points(sg, new THREE.PointsMaterial({ color: '#ffffff', size: 0.09, transparent: true, opacity: 0.9, depthWrite: false }))
    this.snow.frustumCulled = false
    this.group.add(this.rain, this.snow)
  }
  update(dt: number, t: number, kind: string, cx: number, cy: number, cz: number, span: number) {
    const rain = kind === 'rain' || kind === 'storm'
    const snow = kind === 'snow'
    this.rain.visible = rain
    this.snow.visible = snow
    if (!rain && !snow) return
    const n = kind === 'storm' ? this.N : rain ? Math.floor(this.N * 0.6) : Math.floor(this.N * 0.7)
    const H = span * 0.9
    const d = this.drops
    for (let k = 0; k < this.N; k++) {
      const sp = d[k * 4 + 3]
      if (rain) { d[k * 4 + 1] -= dt * (kind === 'storm' ? 1.5 : 1.1) * sp; d[k * 4] += dt * (kind === 'storm' ? 0.12 : 0.04) }
      else { d[k * 4 + 1] -= dt * 0.09 * sp; d[k * 4] += Math.sin(t + k) * dt * 0.01 }
      if (d[k * 4 + 1] < 0) { d[k * 4 + 1] += 1; d[k * 4] = Math.random(); d[k * 4 + 2] = Math.random() }
      if (d[k * 4] > 1) d[k * 4] -= 1
      const x = cx + (d[k * 4] - 0.5) * span, y = cy + d[k * 4 + 1] * H - 1, z = cz + (d[k * 4 + 2] - 0.5) * span
      if (k >= n) {
        if (rain) { this.rainPos.fill(0, k * 6, k * 6 + 6) } else { this.snowPos[k * 3 + 1] = -999 }
        continue
      }
      if (rain) {
        const L = 0.5 * sp
        this.rainPos[k * 6] = x; this.rainPos[k * 6 + 1] = y; this.rainPos[k * 6 + 2] = z
        this.rainPos[k * 6 + 3] = x - (kind === 'storm' ? 0.12 : 0.04); this.rainPos[k * 6 + 4] = y - L; this.rainPos[k * 6 + 5] = z
      } else {
        this.snowPos[k * 3] = x; this.snowPos[k * 3 + 1] = y; this.snowPos[k * 3 + 2] = z
      }
    }
    if (rain) (this.rain.geometry.getAttribute('position') as THREE.BufferAttribute).needsUpdate = true
    else (this.snow.geometry.getAttribute('position') as THREE.BufferAttribute).needsUpdate = true
  }
}

// ---------------------------------------------------------------- clouds

export class Clouds {
  group = new THREE.Group()
  private list: { g: THREE.Group; s: number }[] = []
  private mat = new THREE.MeshStandardMaterial({ color: '#ffffff', roughness: 1, transparent: true, opacity: 0.88, flatShading: true })
  private W: number
  private H: number
  constructor(W: number, H: number) {
    this.W = W
    this.H = H
    const geo = new THREE.IcosahedronGeometry(1, 1)
    for (let k = 0; k < 16; k++) {
      const g = new THREE.Group()
      const n = 4 + Math.floor(Math.random() * 5)
      for (let q = 0; q < n; q++) {
        const m = new THREE.Mesh(geo, this.mat)
        const r = 1.6 + Math.random() * 2.2
        m.scale.set(r * 1.3, r * 0.6, r)
        m.position.set((q - n / 2) * 1.9 + Math.random(), Math.random() * 0.8, (Math.random() - 0.5) * 2.6)
        m.castShadow = true
        g.add(m)
      }
      g.position.set(Math.random() * W, 17 + Math.random() * 5, Math.random() * H)
      this.group.add(g)
      this.list.push({ g, s: 0.4 + Math.random() * 0.6 })
    }
  }
  update(dt: number, kind: string, sky: THREE.Color, camY: number) {
    const cover = kind === 'storm' ? 16 : kind === 'rain' || kind === 'snow' ? 13 : kind === 'cloudy' ? 11 : kind === 'fog' ? 4 : 5
    this.mat.color.copy(sky).lerp(new THREE.Color('#ffffff'), kind === 'storm' || kind === 'rain' ? 0.25 : 0.7)
    if (kind === 'storm') this.mat.color.multiplyScalar(0.55)
    // fade clouds out when the camera dips below them
    this.mat.opacity = Math.min(0.88, Math.max(0, (camY - 20) / 14)) * 0.88
    this.list.forEach((c, k) => {
      c.g.visible = k < cover
      c.g.position.x += dt * c.s * 0.45
      if (c.g.position.x > this.W + 40) { c.g.position.x = -40; c.g.position.z = Math.random() * this.H }
    })
  }
}

// ---------------------------------------------------------------- birds

export class Birds {
  group = new THREE.Group()
  private flocks: { x: number; z: number; vx: number; vz: number; y: number; ph: number; birds: THREE.Mesh[] }[] = []
  private geo: THREE.BufferGeometry
  private mat = new THREE.MeshBasicMaterial({ color: '#1e2230', side: THREE.DoubleSide })
  constructor() {
    const g = new THREE.BufferGeometry()
    g.setAttribute('position', new THREE.Float32BufferAttribute([0, 0, 0.08, -0.22, 0.08, -0.02, 0, 0, -0.04, 0, 0, 0.08, 0, 0, -0.04, 0.22, 0.08, -0.02], 3))
    g.computeVertexNormals()
    this.geo = g
  }
  update(dt: number, t: number, cx: number, cz: number, span: number, day: number, season: number) {
    if (this.flocks.length < 2 && Math.random() < dt * 0.15 && day > 0.4 && season !== 3) {
      const a = Math.random() * Math.PI * 2
      const sp = 2.5 + Math.random() * 1.5
      const f = { x: cx - Math.cos(a) * span, z: cz - Math.sin(a) * span, vx: Math.cos(a) * sp, vz: Math.sin(a) * sp, y: 6 + Math.random() * 4, ph: Math.random() * 10, birds: [] as THREE.Mesh[] }
      const n = 4 + Math.floor(Math.random() * 7)
      for (let k = 0; k < n; k++) { const m = new THREE.Mesh(this.geo, this.mat); m.scale.setScalar(1.6); this.group.add(m); f.birds.push(m) }
      this.flocks.push(f)
    }
    this.flocks = this.flocks.filter((f) => {
      const alive = Math.hypot(f.x - cx, f.z - cz) < span * 1.6
      if (!alive) for (const b of f.birds) this.group.remove(b)
      return alive
    })
    for (const f of this.flocks) {
      f.x += f.vx * dt
      f.z += f.vz * dt
      const heading = Math.atan2(f.vx, f.vz)
      const fx = Math.sin(heading), fz = Math.cos(heading)
      f.birds.forEach((b, k) => {
        const row = Math.ceil(k / 2), side = k % 2 ? 1 : -1
        b.position.set(f.x - fx * row * 0.6 + fz * side * row * 0.45, f.y + Math.sin(t * 1.3 + k) * 0.15, f.z - fz * row * 0.6 - fx * side * row * 0.45)
        b.rotation.set(0, heading, 0)
        const flap = Math.sin(t * 9 + f.ph + k)
        b.scale.set(1.6, flap * 2.6, 1.6)
      })
    }
  }
}
