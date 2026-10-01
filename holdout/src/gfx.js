// Renderer, camera rig, input, procedural textures, shared materials,
// the HTML label layer, atmosphere (day/night) and combat effects.
import * as THREE from 'three'
import { clamp, lerp } from './util.js'

export const gfx = {
  renderer: null,
  camera: null,
  rig: null,
  input: null,
  labels: null,
  quality: 'high',
}

export function initGfx(canvas) {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' })
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.15
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFShadowMap
  gfx.renderer = renderer
  gfx.camera = new THREE.PerspectiveCamera(30, 1, 0.5, 400)
  gfx.rig = new CamRig(gfx.camera)
  gfx.input = new Input(canvas, gfx.rig)
  gfx.labels = new Labels(document.getElementById('labels'))
  setQuality(gfx.quality)
  const resize = () => {
    const w = window.innerWidth
    const h = window.innerHeight
    renderer.setSize(w, h, false)
    gfx.camera.aspect = w / h
    gfx.camera.updateProjectionMatrix()
  }
  window.addEventListener('resize', resize)
  resize()
  return gfx
}

export function setQuality(q) {
  gfx.quality = q
  const r = gfx.renderer
  if (!r) return
  const dpr = window.devicePixelRatio || 1
  r.setPixelRatio(q === 'high' ? Math.min(dpr, 2) : q === 'medium' ? Math.min(dpr, 1.5) : 1)
  r.shadowMap.enabled = q !== 'low'
  r.shadowMap.needsUpdate = true
  // Materials must recompile when shadows toggle.
  for (const m of matCache.values()) m.needsUpdate = true
}

// ---------------------------------------------------------------- camera
export class CamRig {
  constructor(camera) {
    this.camera = camera
    this.target = new THREE.Vector3(24, 0, 24)
    this.goal = this.target.clone()
    this.yaw = Math.PI / 4
    this.yawGoal = this.yaw
    this.pitch = 0.92
    this.dist = 30
    this.distGoal = 30
    this.minDist = 9
    this.maxDist = 60
    this.bounds = { x0: 0, z0: 0, x1: 48, z1: 48 }
    this.shake = 0
    this.apply()
  }
  setBounds(x0, z0, x1, z1) {
    this.bounds = { x0, z0, x1, z1 }
    this.clampGoal()
  }
  clampGoal() {
    const b = this.bounds
    this.goal.x = clamp(this.goal.x, b.x0, b.x1)
    this.goal.z = clamp(this.goal.z, b.z0, b.z1)
  }
  // Portrait screens see less of the world sideways, so pull back.
  fitDist(d) {
    const a = window.innerWidth / window.innerHeight
    return a < 1 ? d * Math.min(1.8, 1 / a) : d
  }
  jump(x, z, dist) {
    this.goal.set(x, 0, z)
    this.clampGoal()
    this.target.copy(this.goal)
    if (dist) this.dist = this.distGoal = this.fitDist(dist)
    this.apply()
  }
  focus(x, z) {
    this.goal.set(x, 0, z)
    this.clampGoal()
  }
  zoom(f) {
    this.distGoal = clamp(this.distGoal * f, this.minDist, this.fitDist(this.maxDist))
  }
  rotate(d) {
    this.yawGoal += d
  }
  update(dt) {
    const k = 1 - Math.exp(-dt * 9)
    this.target.lerp(this.goal, k)
    this.yaw = lerp(this.yaw, this.yawGoal, k)
    this.dist = lerp(this.dist, this.distGoal, k)
    this.shake = Math.max(0, this.shake - dt * 2.5)
    this.apply()
  }
  apply() {
    const c = this.camera
    const cp = Math.cos(this.pitch)
    c.position.set(
      this.target.x + this.dist * cp * Math.sin(this.yaw),
      this.target.y + this.dist * Math.sin(this.pitch),
      this.target.z + this.dist * cp * Math.cos(this.yaw),
    )
    if (this.shake > 0) {
      const s = this.shake * 0.25
      c.position.x += (Math.random() - 0.5) * s
      c.position.y += (Math.random() - 0.5) * s
    }
    c.lookAt(this.target)
    c.updateMatrixWorld()
  }
}

// ---------------------------------------------------------------- input
const _ray = new THREE.Raycaster()
const _ndc = new THREE.Vector2()
const _plane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0)

export function screenRay(sx, sy) {
  _ndc.set((sx / window.innerWidth) * 2 - 1, -(sy / window.innerHeight) * 2 + 1)
  _ray.setFromCamera(_ndc, gfx.camera)
  return _ray
}
export function groundAt(sx, sy, y = 0) {
  const r = screenRay(sx, sy)
  _plane.constant = -y
  const out = new THREE.Vector3()
  return r.ray.intersectPlane(_plane, out) ? out : null
}
// First object whose ancestor chain carries userData.pick.
export function pickAt(sx, sy, objects) {
  const r = screenRay(sx, sy)
  const hits = r.intersectObjects(objects, true)
  for (const h of hits) {
    let o = h.object
    while (o) {
      if (o.userData.pick) return { pick: o.userData.pick, point: h.point }
      o = o.parent
    }
  }
  return null
}

export class Input {
  constructor(canvas, rig) {
    this.canvas = canvas
    this.rig = rig
    this.handler = null // active scene: { onTap, onHover, onDragStart?, wantsDrag? }
    this.pointers = new Map()
    this.keys = new Set()
    this.drag = null
    this.pinch = null
    this.mouse = { x: 0, y: 0, inside: false }
    canvas.addEventListener('pointerdown', (e) => this.down(e))
    window.addEventListener('pointermove', (e) => this.move(e))
    window.addEventListener('pointerup', (e) => this.up(e))
    window.addEventListener('pointercancel', (e) => this.up(e, true))
    canvas.addEventListener('contextmenu', (e) => e.preventDefault())
    canvas.addEventListener(
      'wheel',
      (e) => {
        e.preventDefault()
        rig.zoom(Math.pow(1.0015, e.deltaY))
      },
      { passive: false },
    )
    window.addEventListener('keydown', (e) => {
      if (e.target instanceof HTMLInputElement) return
      this.keys.add(e.key.toLowerCase())
      this.handler?.onKey?.(e)
    })
    window.addEventListener('keyup', (e) => this.keys.delete(e.key.toLowerCase()))
    window.addEventListener('blur', () => this.keys.clear())
  }
  down(e) {
    this.canvas.setPointerCapture?.(e.pointerId)
    this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY, sx: e.clientX, sy: e.clientY, button: e.button, t: performance.now() })
    if (this.pointers.size === 2) {
      const [a, b] = [...this.pointers.values()]
      this.pinch = { d: Math.hypot(a.x - b.x, a.y - b.y), ang: Math.atan2(b.y - a.y, b.x - a.x), dist: this.rig.distGoal, yaw: this.rig.yawGoal }
      this.drag = null
      return
    }
    if (this.pointers.size === 1) {
      const rotate = e.button === 2 || e.button === 1
      const p0 = groundAt(e.clientX, e.clientY)
      this.drag = { rotate, p0, moved: false, x: e.clientX, y: e.clientY, button: e.button, shift: e.shiftKey, custom: false }
      if (!rotate && this.handler?.onPress?.(e.clientX, e.clientY)) this.drag.custom = true
    }
  }
  move(e) {
    this.mouse.x = e.clientX
    this.mouse.y = e.clientY
    this.mouse.inside = e.target === this.canvas
    const p = this.pointers.get(e.pointerId)
    if (!p) {
      if (e.target === this.canvas) this.handler?.onHover?.(e.clientX, e.clientY)
      return
    }
    p.x = e.clientX
    p.y = e.clientY
    if (this.pinch && this.pointers.size >= 2) {
      const [a, b] = [...this.pointers.values()]
      const d = Math.hypot(a.x - b.x, a.y - b.y)
      const ang = Math.atan2(b.y - a.y, b.x - a.x)
      this.rig.distGoal = clamp((this.pinch.dist * this.pinch.d) / Math.max(d, 1), this.rig.minDist, this.rig.maxDist)
      this.rig.yawGoal = this.pinch.yaw - (ang - this.pinch.ang)
      return
    }
    const dr = this.drag
    if (!dr) return
    const dx = e.clientX - dr.x
    const dy = e.clientY - dr.y
    if (!dr.moved && Math.hypot(e.clientX - p.sx, e.clientY - p.sy) > 7) dr.moved = true
    if (!dr.moved) return
    if (dr.custom) {
      this.handler?.onDragMove?.(e.clientX, e.clientY)
      return
    }
    if (dr.rotate) {
      this.rig.yawGoal -= dx * 0.006
      this.rig.yaw = this.rig.yawGoal
      this.rig.apply()
    } else if (dr.p0) {
      const cur = groundAt(e.clientX, e.clientY)
      if (cur) {
        this.rig.goal.x += dr.p0.x - cur.x
        this.rig.goal.z += dr.p0.z - cur.z
        this.rig.clampGoal()
        this.rig.target.copy(this.rig.goal)
        this.rig.apply()
      }
    }
    dr.x = e.clientX
    dr.y = e.clientY
  }
  up(e, cancel) {
    const p = this.pointers.get(e.pointerId)
    this.pointers.delete(e.pointerId)
    if (this.pinch) {
      if (this.pointers.size < 2) this.pinch = null
      this.drag = null
      return
    }
    const dr = this.drag
    this.drag = null
    if (!p || !dr || cancel) return
    if (dr.custom) {
      this.handler?.onRelease?.(e.clientX, e.clientY, dr.moved)
      if (dr.moved) return
    }
    if (!dr.moved) this.handler?.onTap?.(e.clientX, e.clientY, { button: dr.button, shift: e.shiftKey || e.ctrlKey || e.metaKey, touch: e.pointerType === 'touch' })
  }
  update(dt) {
    const k = this.keys
    let mx = 0
    let mz = 0
    if (k.has('w') || k.has('arrowup')) mz -= 1
    if (k.has('s') || k.has('arrowdown')) mz += 1
    if (k.has('a') || k.has('arrowleft')) mx -= 1
    if (k.has('d') || k.has('arrowright')) mx += 1
    if (mx || mz) {
      const y = this.rig.yaw
      const sp = this.rig.dist * 0.9 * dt
      const fx = -Math.sin(y)
      const fz = -Math.cos(y)
      const rx = Math.cos(y)
      const rz = -Math.sin(y)
      this.rig.goal.x += (rx * mx - fx * mz) * sp
      this.rig.goal.z += (rz * mx - fz * mz) * sp
      this.rig.clampGoal()
    }
    if (k.has('q')) this.rig.yawGoal += dt * 1.6
    if (k.has('e')) this.rig.yawGoal -= dt * 1.6
    if (k.has('=') || k.has('+')) this.rig.zoom(1 - dt)
    if (k.has('-')) this.rig.zoom(1 + dt)
  }
}

// ---------------------------------------------------------------- labels
// HTML elements pinned to world positions.
const _v = new THREE.Vector3()
export class Labels {
  constructor(layer) {
    this.layer = layer
    this.items = new Set()
  }
  add(el, pos, { offsetY = 0, life = 0, rise = 0, className = '', scene = null } = {}) {
    if (className) el.classList.add(...className.split(' '))
    el.classList.add('wl')
    this.layer.appendChild(el)
    const item = { el, pos, offsetY, life, age: 0, rise, scene, hidden: false, remove: () => this.remove(item) }
    this.items.add(item)
    return item
  }
  remove(item) {
    if (!item) return
    item.el.remove()
    this.items.delete(item)
  }
  clearScene(scene) {
    for (const it of [...this.items]) if (it.scene === scene) this.remove(it)
  }
  float(scene, pos, html, cls = '') {
    const el = document.createElement('div')
    el.className = 'float ' + cls
    el.innerHTML = html
    return this.add(el, pos.clone ? pos.clone() : new THREE.Vector3(pos.x, pos.y, pos.z), { life: 1.6, rise: 1.1, scene })
  }
  update(dt, camera, activeScene) {
    const w = window.innerWidth
    const h = window.innerHeight
    for (const it of this.items) {
      if (it.life) {
        it.age += dt
        if (it.age >= it.life) {
          this.remove(it)
          continue
        }
      }
      const visible = !it.scene || it.scene === activeScene
      const p = typeof it.pos === 'function' ? it.pos() : it.pos.isObject3D ? it.pos.getWorldPosition(_v) : it.pos
      if (!visible || !p || it.hidden) {
        it.el.style.display = 'none'
        continue
      }
      _v.set(p.x, p.y + it.offsetY + (it.rise ? (it.age / it.life) * it.rise : 0), p.z)
      _v.project(camera)
      if (_v.z > 1 || _v.x < -1.2 || _v.x > 1.2 || _v.y < -1.2 || _v.y > 1.2) {
        it.el.style.display = 'none'
        continue
      }
      it.el.style.display = ''
      const x = (_v.x * 0.5 + 0.5) * w
      const y = (-_v.y * 0.5 + 0.5) * h
      it.el.style.transform = `translate3d(${x.toFixed(1)}px,${y.toFixed(1)}px,0)`
      if (it.life) it.el.style.opacity = String(Math.min(1, (1 - it.age / it.life) * 2.2))
    }
  }
}

// ---------------------------------------------------------------- textures
function canvasTex(size, draw, repeat = 1) {
  const c = document.createElement('canvas')
  c.width = c.height = size
  const g = c.getContext('2d')
  draw(g, size)
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  t.wrapS = t.wrapT = THREE.RepeatWrapping
  t.repeat.set(repeat, repeat)
  t.anisotropy = 4
  return t
}
function speckle(g, s, n, colors, rMin, rMax, alpha = 1) {
  for (let i = 0; i < n; i++) {
    g.globalAlpha = alpha * (0.3 + Math.random() * 0.7)
    g.fillStyle = colors[(Math.random() * colors.length) | 0]
    const r = rMin + Math.random() * (rMax - rMin)
    const x = Math.random() * s
    const y = Math.random() * s
    g.beginPath()
    g.arc(x, y, r, 0, Math.PI * 2)
    g.fill()
    // wrap so the texture tiles
    if (x < r) g.fillRect(x + s - r, y - r, r * 2, r * 2)
  }
  g.globalAlpha = 1
}

const texCache = {}
export function tex(name) {
  if (texCache[name]) return texCache[name]
  let t
  switch (name) {
    case 'dirt':
      t = canvasTex(512, (g, s) => {
        g.fillStyle = '#8a7b5f'
        g.fillRect(0, 0, s, s)
        speckle(g, s, 260, ['#968766', '#7d6f55', '#76694f', '#a0906e'], 10, 40, 0.35)
        speckle(g, s, 1800, ['#665a46', '#a89a78', '#73664f'], 0.6, 2.2, 0.8)
      })
      break
    case 'grass':
      t = canvasTex(512, (g, s) => {
        g.fillStyle = '#5f6a43'
        g.fillRect(0, 0, s, s)
        speckle(g, s, 240, ['#5f6a42', '#4c5536', '#6b6f45', '#5a5a3a'], 12, 46, 0.4)
        speckle(g, s, 2400, ['#3f4a2c', '#727a4c', '#4f5a33', '#7b7550'], 0.6, 2, 0.7)
      })
      break
    case 'asphalt':
      t = canvasTex(512, (g, s) => {
        g.fillStyle = '#3b3d3f'
        g.fillRect(0, 0, s, s)
        speckle(g, s, 200, ['#35373a', '#414447', '#303235'], 10, 36, 0.4)
        speckle(g, s, 3000, ['#2a2b2d', '#4c4f52', '#56585a'], 0.5, 1.6, 0.8)
        g.strokeStyle = 'rgba(20,20,20,0.6)'
        g.lineWidth = 1.5
        for (let i = 0; i < 6; i++) {
          g.beginPath()
          let x = Math.random() * s
          let y = Math.random() * s
          g.moveTo(x, y)
          for (let j = 0; j < 6; j++) {
            x += (Math.random() - 0.5) * 60
            y += (Math.random() - 0.5) * 60
            g.lineTo(x, y)
          }
          g.stroke()
        }
      })
      break
    case 'wood':
      t = canvasTex(256, (g, s) => {
        const cols = ['#8a6844', '#7f5f3d', '#93704a', '#7a5a3a']
        const pw = s / 8
        for (let i = 0; i < 8; i++) {
          g.fillStyle = cols[i % cols.length]
          g.fillRect(0, i * pw, s, pw)
          g.fillStyle = 'rgba(0,0,0,0.35)'
          g.fillRect(0, i * pw, s, 1.5)
          const off = Math.random() * s
          g.fillRect(off, i * pw, 1.5, pw)
          g.globalAlpha = 0.15
          for (let k = 0; k < 10; k++) {
            g.fillStyle = Math.random() < 0.5 ? '#5a3f26' : '#a8835a'
            g.fillRect(Math.random() * s, i * pw + Math.random() * pw, 20 + Math.random() * 40, 1)
          }
          g.globalAlpha = 1
        }
      })
      break
    case 'tile':
      t = canvasTex(256, (g, s) => {
        const n = 4
        const ts = s / n
        for (let i = 0; i < n; i++)
          for (let j = 0; j < n; j++) {
            g.fillStyle = (i + j) % 2 ? '#c9c3b4' : '#b9b3a3'
            g.fillRect(i * ts, j * ts, ts, ts)
          }
        g.strokeStyle = 'rgba(60,55,45,0.5)'
        g.lineWidth = 2
        for (let i = 0; i <= n; i++) {
          g.beginPath()
          g.moveTo(i * ts, 0)
          g.lineTo(i * ts, s)
          g.moveTo(0, i * ts)
          g.lineTo(s, i * ts)
          g.stroke()
        }
        speckle(g, s, 40, ['#8a7a5a', '#6a5a40'], 4, 18, 0.12)
      })
      break
    case 'concrete':
      t = canvasTex(256, (g, s) => {
        g.fillStyle = '#8d8a83'
        g.fillRect(0, 0, s, s)
        speckle(g, s, 80, ['#7f7c75', '#999690', '#85827b'], 8, 30, 0.35)
        speckle(g, s, 900, ['#6d6a64', '#a3a09a'], 0.5, 1.4, 0.7)
        g.strokeStyle = 'rgba(40,40,40,0.35)'
        g.lineWidth = 1.5
        g.strokeRect(0, 0, s, s)
      })
      break
    case 'canvas':
      t = canvasTex(128, (g, s) => {
        g.fillStyle = '#fff'
        g.fillRect(0, 0, s, s)
        g.globalAlpha = 0.08
        g.fillStyle = '#000'
        for (let i = 0; i < s; i += 3) g.fillRect(i, 0, 1, s)
        for (let i = 0; i < s; i += 3) g.fillRect(0, i, s, 1)
        g.globalAlpha = 1
        speckle(g, s, 20, ['#998', '#776'], 3, 12, 0.12)
      })
      break
    case 'chain':
      t = canvasTex(64, (g, s) => {
        g.clearRect(0, 0, s, s)
        g.strokeStyle = '#c9ccce'
        g.lineWidth = 3
        g.beginPath()
        g.moveTo(0, 0)
        g.lineTo(s, s)
        g.moveTo(s, 0)
        g.lineTo(0, s)
        g.stroke()
      })
      break
    case 'blood':
      t = canvasTex(128, (g, s) => {
        g.clearRect(0, 0, s, s)
        g.fillStyle = '#fff'
        for (let i = 0; i < 14; i++) {
          const r = 6 + Math.random() * 22
          const a = Math.random() * Math.PI * 2
          const d = Math.random() * 30
          g.beginPath()
          g.arc(64 + Math.cos(a) * d, 64 + Math.sin(a) * d, r, 0, Math.PI * 2)
          g.fill()
        }
        for (let i = 0; i < 20; i++) {
          const a = Math.random() * Math.PI * 2
          const d = 40 + Math.random() * 20
          g.beginPath()
          g.arc(64 + Math.cos(a) * d, 64 + Math.sin(a) * d, 1 + Math.random() * 3, 0, Math.PI * 2)
          g.fill()
        }
      })
      t.wrapS = t.wrapT = THREE.ClampToEdgeWrapping
      break
    case 'glow':
      t = canvasTex(128, (g, s) => {
        const gr = g.createRadialGradient(64, 64, 0, 64, 64, 64)
        gr.addColorStop(0, 'rgba(255,255,255,1)')
        gr.addColorStop(0.25, 'rgba(255,255,255,0.6)')
        gr.addColorStop(1, 'rgba(255,255,255,0)')
        g.fillStyle = gr
        g.fillRect(0, 0, s, s)
      })
      t.wrapS = t.wrapT = THREE.ClampToEdgeWrapping
      break
    case 'ring':
      t = canvasTex(128, (g, s) => {
        g.clearRect(0, 0, s, s)
        g.strokeStyle = '#fff'
        g.lineWidth = 7
        g.beginPath()
        g.arc(64, 64, 54, 0, Math.PI * 2)
        g.stroke()
        g.lineWidth = 2
        g.globalAlpha = 0.5
        g.beginPath()
        g.arc(64, 64, 44, 0, Math.PI * 2)
        g.stroke()
      })
      t.wrapS = t.wrapT = THREE.ClampToEdgeWrapping
      break
    case 'strip':
      // soft-edged worn ground, used for footpaths
      t = canvasTex(128, (g, s) => {
        g.clearRect(0, 0, s, s)
        const gr = g.createLinearGradient(0, 0, s, 0)
        gr.addColorStop(0, 'rgba(255,255,255,0)')
        gr.addColorStop(0.3, 'rgba(255,255,255,0.85)')
        gr.addColorStop(0.7, 'rgba(255,255,255,0.85)')
        gr.addColorStop(1, 'rgba(255,255,255,0)')
        g.fillStyle = gr
        g.fillRect(0, 0, s, s)
        g.globalCompositeOperation = 'destination-out'
        speckle(g, s, 120, ['#000'], 2, 7, 0.35)
        g.globalCompositeOperation = 'source-over'
      })
      t.wrapS = THREE.ClampToEdgeWrapping
      break
    case 'shadow':
      t = canvasTex(64, (g, s) => {
        const gr = g.createRadialGradient(32, 32, 0, 32, 32, 32)
        gr.addColorStop(0, 'rgba(0,0,0,0.55)')
        gr.addColorStop(1, 'rgba(0,0,0,0)')
        g.fillStyle = gr
        g.fillRect(0, 0, s, s)
      })
      t.wrapS = t.wrapT = THREE.ClampToEdgeWrapping
      break
    case 'crate':
      t = canvasTex(128, (g, s) => {
        g.fillStyle = '#fff'
        g.fillRect(0, 0, s, s)
        g.fillStyle = 'rgba(0,0,0,0.25)'
        for (let i = 0; i < 4; i++) g.fillRect(0, (i * s) / 4, s, 2)
        g.lineWidth = 10
        g.strokeStyle = 'rgba(0,0,0,0.12)'
        g.strokeRect(5, 5, s - 10, s - 10)
        g.beginPath()
        g.moveTo(8, 8)
        g.lineTo(s - 8, s - 8)
        g.stroke()
      })
      break
    case 'corrugated':
      t = canvasTex(128, (g, s) => {
        for (let i = 0; i < s; i += 8) {
          const gr = g.createLinearGradient(i, 0, i + 8, 0)
          gr.addColorStop(0, '#d8d8d8')
          gr.addColorStop(0.5, '#ffffff')
          gr.addColorStop(1, '#b0b0b0')
          g.fillStyle = gr
          g.fillRect(i, 0, 8, s)
        }
        speckle(g, s, 60, ['#8a5a3a', '#6a4a30'], 2, 9, 0.25)
      })
      break
  }
  texCache[name] = t
  return t
}

// ---------------------------------------------------------------- materials
const matCache = new Map()
// Shared MeshStandardMaterial per colour/option combination.
export function M(color, o = {}) {
  const key = color + JSON.stringify(o)
  let m = matCache.get(key)
  if (m) return m
  const params = { color, roughness: o.rough ?? 0.85, metalness: o.metal ?? 0, flatShading: o.flat ?? false }
  if (o.map) params.map = tex(o.map)
  if (o.emissive) {
    params.emissive = new THREE.Color(o.emissive)
    params.emissiveIntensity = o.ei ?? 1
  }
  if (o.transparent) {
    params.transparent = true
    params.opacity = o.opacity ?? 1
    params.depthWrite = o.depthWrite ?? false
  }
  if (o.alphaTest) {
    params.alphaTest = o.alphaTest
    params.side = THREE.DoubleSide
  }
  if (o.side) params.side = o.side
  m = new THREE.MeshStandardMaterial(params)
  matCache.set(key, m)
  return m
}
export function basicMat(color, o = {}) {
  const key = 'basic' + color + JSON.stringify(o)
  let m = matCache.get(key)
  if (m) return m
  m = new THREE.MeshBasicMaterial({
    color,
    map: o.map ? tex(o.map) : null,
    transparent: true,
    opacity: o.opacity ?? 1,
    depthWrite: false,
    blending: o.add ? THREE.AdditiveBlending : THREE.NormalBlending,
    side: THREE.DoubleSide,
  })
  matCache.set(key, m)
  return m
}

// Shared geometry cache so thousands of boxes share buffers.
const geoCache = new Map()
export function G(kind, ...a) {
  const key = kind + a.join(',')
  let g = geoCache.get(key)
  if (g) return g
  switch (kind) {
    case 'box':
      g = new THREE.BoxGeometry(...a)
      break
    case 'cyl':
      g = new THREE.CylinderGeometry(...a)
      break
    case 'sphere':
      g = new THREE.SphereGeometry(...a)
      break
    case 'cone':
      g = new THREE.ConeGeometry(...a)
      break
    case 'capsule':
      g = new THREE.CapsuleGeometry(...a)
      break
    case 'ico':
      g = new THREE.IcosahedronGeometry(...a)
      break
    case 'dodeca':
      g = new THREE.DodecahedronGeometry(...a)
      break
    case 'torus':
      g = new THREE.TorusGeometry(...a)
      break
    case 'plane':
      g = new THREE.PlaneGeometry(...a)
      break
    case 'circle':
      g = new THREE.CircleGeometry(...a)
      break
  }
  geoCache.set(key, g)
  return g
}

// ---------------------------------------------------------------- atmosphere
// Colour keys through the day. hour → [sky, fog, sunColor, sunInt, hemiSky, hemiGround, hemiInt]
const KEYS = [
  [0, '#101a2e', '#121c30', '#8aa0d8', 1.0, '#4a5f90', '#1c1e26', 0.95],
  [4.5, '#162036', '#18223a', '#90a4d8', 1.0, '#4e6494', '#1e2028', 0.98],
  [5.8, '#c98a6a', '#b88468', '#ffb27a', 1.5, '#c0a890', '#463a2c', 0.85],
  [7, '#b3c0c6', '#aeb6b8', '#fff0d8', 2.9, '#d6dee2', '#5a5040', 1.15],
  [13, '#b0c0ca', '#b0b8ba', '#fff6e8', 3.2, '#dce4e8', '#5e5444', 1.2],
  [17.5, '#c0ac94', '#b6a08c', '#ffd6a0', 2.7, '#d4c4ae', '#54473a', 1.05],
  [19.5, '#b0664a', '#8e5a4a', '#ff8a50', 1.4, '#9a7a76', '#362a24', 0.75],
  [21, '#20233c', '#1f2338', '#8a94cc', 1.0, '#4a5288', '#1c1c24', 0.95],
  [24, '#101a2e', '#121c30', '#8aa0d8', 1.0, '#4a5f90', '#1c1e26', 0.95],
]
const _c1 = new THREE.Color()
const _c2 = new THREE.Color()
function keyAt(hour) {
  let i = 0
  while (i < KEYS.length - 2 && KEYS[i + 1][0] <= hour) i++
  const a = KEYS[i]
  const b = KEYS[i + 1]
  const t = clamp((hour - a[0]) / (b[0] - a[0]), 0, 1)
  const col = (j) => _c1.set(a[j]).lerp(_c2.set(b[j]), t).clone()
  return { sky: col(1), fog: col(2), sun: col(3), sunI: lerp(a[4], b[4], t), hs: col(5), hg: col(6), hi: lerp(a[7], b[7], t) }
}
export const isNight = (hour) => hour >= 20.5 || hour < 5.5
export const nightFactor = (hour) => {
  if (hour >= 21 || hour < 4.5) return 1
  if (hour >= 19 && hour < 21) return (hour - 19) / 2
  if (hour >= 4.5 && hour < 6.5) return 1 - (hour - 4.5) / 2
  return 0
}

export class Atmosphere {
  constructor(scene) {
    this.scene = scene
    this.hemi = new THREE.HemisphereLight('#cfd8de', '#51493a', 0.8)
    scene.add(this.hemi)
    this.sun = new THREE.DirectionalLight('#fff6e8', 2.6)
    this.sun.castShadow = true
    const s = this.sun.shadow
    s.mapSize.set(2048, 2048)
    s.bias = -0.0005
    s.normalBias = 0.03
    s.radius = 2
    s.camera.near = 1
    s.camera.far = 140
    scene.add(this.sun)
    scene.add(this.sun.target)
    scene.fog = new THREE.Fog('#a9b1b3', 40, 110)
    scene.background = new THREE.Color('#a8b8c2')
  }
  set(hour, focus, dist = 30) {
    const k = keyAt(hour)
    this.scene.background.copy(k.sky)
    this.scene.fog.color.copy(k.fog)
    this.scene.fog.near = dist * 1.1
    this.scene.fog.far = dist * 3.4
    this.hemi.color.copy(k.hs)
    this.hemi.groundColor.copy(k.hg)
    this.hemi.intensity = k.hi
    this.sun.color.copy(k.sun)
    this.sun.intensity = k.sunI
    // Sun arcs east→west during the day; the moon light swings the other way at night.
    const day = hour >= 5.5 && hour <= 20.5
    const t = day ? (hour - 5.5) / 15 : ((hour + 24 - 20.5) % 24) / 9
    const az = lerp(-1.1, 1.9, t)
    const el = day ? 0.35 + Math.sin(t * Math.PI) * 0.75 : 0.9
    const dir = new THREE.Vector3(Math.cos(az) * Math.cos(el), Math.sin(el), Math.sin(az) * Math.cos(el))
    const ext = clamp(dist * 0.9, 14, 46)
    const sc = this.sun.shadow.camera
    if (sc.right !== ext) {
      sc.left = -ext
      sc.right = ext
      sc.top = ext
      sc.bottom = -ext
      sc.updateProjectionMatrix()
    }
    // Snap to shadow texels to stop shimmering while panning.
    const texel = (ext * 2) / this.sun.shadow.mapSize.x
    const fx = Math.round(focus.x / texel) * texel
    const fz = Math.round(focus.z / texel) * texel
    this.sun.target.position.set(fx, 0, fz)
    this.sun.position.set(fx + dir.x * 60, dir.y * 60, fz + dir.z * 60)
  }
}

// ---------------------------------------------------------------- effects
const _up = new THREE.Vector3(0, 1, 0)
export class FX {
  constructor(scene) {
    this.scene = scene
    this.max = 400
    const geo = new THREE.BoxGeometry(0.07, 0.07, 0.07)
    this.parts = new THREE.InstancedMesh(geo, new THREE.MeshStandardMaterial({ roughness: 0.8 }), this.max)
    this.parts.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
    this.parts.frustumCulled = false
    this.parts.count = 0
    scene.add(this.parts)
    this.pool = []
    this.decals = []
    this.tracers = []
    this.flashes = []
    this.rings = []
    this.lights = []
    for (let i = 0; i < 2; i++) {
      const l = new THREE.PointLight('#ffc070', 0, 7, 2)
      l.position.set(0, -50, 0)
      scene.add(l)
      this.lights.push({ l, t: 0 })
    }
    this._m = new THREE.Matrix4()
    this._q = new THREE.Quaternion()
    this._s = new THREE.Vector3()
    this._col = new THREE.Color()
  }
  burst(pos, color, n = 10, speed = 3, life = 0.8, up = 2.5, size = 1) {
    for (let i = 0; i < n; i++) {
      if (this.pool.length >= this.max) this.pool.shift()
      const a = Math.random() * Math.PI * 2
      const sp = speed * (0.3 + Math.random() * 0.7)
      this.pool.push({
        x: pos.x,
        y: pos.y,
        z: pos.z,
        vx: Math.cos(a) * sp,
        vy: up * (0.4 + Math.random()),
        vz: Math.sin(a) * sp,
        life,
        age: 0,
        rot: Math.random() * 6,
        size: size * (0.6 + Math.random() * 0.8),
        color: color instanceof THREE.Color ? color : new THREE.Color(color),
      })
    }
  }
  blood(pos, big = false) {
    this.burst(new THREE.Vector3(pos.x, pos.y ?? 1, pos.z), '#6d1515', big ? 16 : 7, 2.2, 0.7, 2, 0.9)
    if (big || Math.random() < 0.5) this.decal(pos, big ? 1.1 : 0.55)
  }
  decal(pos, size) {
    let d
    if (this.decals.length > 50) d = this.decals.shift()
    else {
      d = new THREE.Mesh(G('plane', 1, 1), new THREE.MeshBasicMaterial({ map: tex('blood'), color: '#5a0f0f', transparent: true, opacity: 0.85, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -2 }))
      d.rotation.x = -Math.PI / 2
      d.renderOrder = 1
      this.scene.add(d)
    }
    d.position.set(pos.x + (Math.random() - 0.5) * 0.3, 0.015, pos.z + (Math.random() - 0.5) * 0.3)
    d.rotation.z = Math.random() * 6
    d.scale.setScalar(size * (0.7 + Math.random() * 0.6))
    this.decals.push(d)
  }
  tracer(from, to, color = '#ffe2a0') {
    const len = from.distanceTo(to)
    const m = new THREE.Mesh(G('box', 0.035, 0.035, 1), basicMat(color, { add: true, opacity: 0.9 }))
    m.scale.z = len
    m.position.copy(from).lerp(to, 0.5)
    m.lookAt(to)
    this.scene.add(m)
    this.tracers.push({ m, t: 0.07 })
  }
  muzzle(pos) {
    const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex('glow'), color: '#ffcf7a', blending: THREE.AdditiveBlending, depthWrite: false }))
    s.position.copy(pos)
    s.scale.setScalar(0.9)
    this.scene.add(s)
    this.flashes.push({ s, t: 0.06 })
    const L = this.lights.reduce((a, b) => (a.t < b.t ? a : b))
    L.l.position.copy(pos)
    L.l.intensity = 12
    L.t = 0.07
  }
  ring(pos, color = '#e8c070', size = 1.2) {
    const m = new THREE.Mesh(G('plane', 1, 1), basicMat(color, { map: 'ring' }).clone())
    m.rotation.x = -Math.PI / 2
    m.position.set(pos.x, 0.04, pos.z)
    this.scene.add(m)
    this.rings.push({ m, t: 0, size })
  }
  update(dt) {
    const g = -9.8
    let n = 0
    for (let i = this.pool.length - 1; i >= 0; i--) {
      const p = this.pool[i]
      p.age += dt
      if (p.age >= p.life) {
        this.pool.splice(i, 1)
        continue
      }
      p.vy += g * dt
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
      this._m.compose(new THREE.Vector3(p.x, p.y, p.z), this._q, this._s)
      this.parts.setMatrixAt(n, this._m)
      this.parts.setColorAt(n, p.color)
      n++
    }
    this.parts.count = n
    this.parts.instanceMatrix.needsUpdate = true
    if (this.parts.instanceColor) this.parts.instanceColor.needsUpdate = true
    for (let i = this.tracers.length - 1; i >= 0; i--) {
      const t = this.tracers[i]
      t.t -= dt
      if (t.t <= 0) {
        this.scene.remove(t.m)
        this.tracers.splice(i, 1)
      }
    }
    for (let i = this.flashes.length - 1; i >= 0; i--) {
      const f = this.flashes[i]
      f.t -= dt
      if (f.t <= 0) {
        this.scene.remove(f.s)
        f.s.material.dispose()
        this.flashes.splice(i, 1)
      }
    }
    for (const L of this.lights) {
      if (L.t > 0) {
        L.t -= dt
        if (L.t <= 0) L.l.intensity = 0
      }
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
        this.rings.splice(i, 1)
      }
    }
  }
}

// Soft round blob under characters for contact shadow when shadows are off.
export function blobShadow(r = 0.45) {
  const m = new THREE.Mesh(G('plane', 1, 1), basicMat('#000000', { map: 'shadow', opacity: 0.8 }))
  m.rotation.x = -Math.PI / 2
  m.position.y = 0.02
  m.scale.setScalar(r * 2)
  m.renderOrder = 1
  return m
}
