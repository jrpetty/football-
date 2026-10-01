// Camera rig, mouse/keyboard/touch input, world-space picking and the HTML
// label layer pinned to world positions.
import * as THREE from 'three'
import { clamp, lerp } from '../core/util.js'

export const view = { camera: null, rig: null, input: null, labels: null, canvas: null }

export function initView(canvas) {
  view.canvas = canvas
  view.camera = new THREE.PerspectiveCamera(32, 1, 0.5, 600)
  view.rig = new CamRig(view.camera)
  view.input = new Input(canvas, view.rig)
  view.labels = new Labels(document.getElementById('labels'))
  return view
}

// ---------------------------------------------------------------- camera
export class CamRig {
  constructor(camera) {
    this.camera = camera
    this.target = new THREE.Vector3(48, 0, 48)
    this.goal = this.target.clone()
    this.yaw = Math.PI / 4
    this.yawGoal = this.yaw
    this.pitch = 0.9
    this.pitchGoal = 0.9
    this.dist = 34
    this.distGoal = 34
    this.minDist = 8
    this.maxDist = 75
    // pitch when zoomed in, pitch when zoomed out, zoom span between them
    this.pitchCfg = [0.6, 0.86, 30]
    this.bounds = { x0: 0, z0: 0, x1: 96, z1: 96 }
    this.shake = 0
    this.follow = null
    this.apply()
  }
  // Remember and restore the framing when switching scenes.
  save() {
    return { x: this.goal.x, z: this.goal.z, dist: this.distGoal, yaw: this.yawGoal, min: this.minDist, max: this.maxDist, pc: [...this.pitchCfg], b: { ...this.bounds }, far: this.camera.far }
  }
  restore(st) {
    if (!st) return
    this.minDist = st.min
    this.maxDist = st.max
    this.pitchCfg = st.pc
    this.bounds = st.b
    this.camera.far = st.far
    this.camera.updateProjectionMatrix()
    this.goal.set(st.x, 0, st.z)
    this.target.copy(this.goal)
    this.dist = this.distGoal = st.dist
    this.yaw = this.yawGoal = st.yaw
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
  focus(x, z, dist) {
    this.goal.set(x, 0, z)
    this.clampGoal()
    if (dist) this.distGoal = this.fitDist(dist)
  }
  zoom(f) {
    this.distGoal = clamp(this.distGoal * f, this.minDist, this.fitDist(this.maxDist))
  }
  update(dt) {
    if (this.follow) {
      const p = typeof this.follow === 'function' ? this.follow() : this.follow
      if (p) {
        this.goal.x = p.x
        this.goal.z = p.z
        this.clampGoal()
      }
    }
    const k = 1 - Math.exp(-dt * 9)
    this.target.lerp(this.goal, k)
    this.yaw = lerp(this.yaw, this.yawGoal, k)
    this.dist = lerp(this.dist, this.distGoal, k)
    // zoomed in, tilt the camera towards the horizon a little for drama
    const pc = this.pitchCfg
    const zt = clamp((this.dist - this.minDist) / pc[2], 0, 1)
    this.pitchGoal = lerp(pc[0], pc[1], zt)
    this.pitch = lerp(this.pitch, this.pitchGoal, k)
    this.shake = Math.max(0, this.shake - dt * 2.5)
    this.apply()
  }
  apply() {
    const c = this.camera
    const cp = Math.cos(this.pitch)
    c.position.set(this.target.x + this.dist * cp * Math.sin(this.yaw), this.target.y + this.dist * Math.sin(this.pitch), this.target.z + this.dist * cp * Math.cos(this.yaw))
    if (this.shake > 0) {
      const s = this.shake * 0.3
      c.position.x += (Math.random() - 0.5) * s
      c.position.y += (Math.random() - 0.5) * s
    }
    c.lookAt(this.target.x, this.target.y + 0.5, this.target.z)
    c.updateMatrixWorld()
  }
}

// ---------------------------------------------------------------- picking
const _ray = new THREE.Raycaster()
const _ndc = new THREE.Vector2()
const _plane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0)
export function screenRay(sx, sy) {
  const r = view.canvas.getBoundingClientRect()
  _ndc.set(((sx - r.left) / r.width) * 2 - 1, -((sy - r.top) / r.height) * 2 + 1)
  _ray.setFromCamera(_ndc, view.camera)
  return _ray
}
export function groundAt(sx, sy, y = 0) {
  const r = screenRay(sx, sy)
  _plane.constant = -y
  const out = new THREE.Vector3()
  return r.ray.intersectPlane(_plane, out) ? out : null
}
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
export function toScreen(p) {
  const v = new THREE.Vector3(p.x, p.y, p.z).project(view.camera)
  return { x: (v.x * 0.5 + 0.5) * window.innerWidth, y: (-v.y * 0.5 + 0.5) * window.innerHeight, behind: v.z > 1 }
}

// ---------------------------------------------------------------- input
export class Input {
  constructor(canvas, rig) {
    this.canvas = canvas
    this.rig = rig
    this.handler = null
    this.pointers = new Map()
    this.keys = new Set()
    this.drag = null
    this.pinch = null
    this.mouse = { x: 0, y: 0, inside: false }
    this.edgePan = true
    canvas.addEventListener('pointerdown', (e) => this.down(e))
    window.addEventListener('pointermove', (e) => this.move(e))
    window.addEventListener('pointerup', (e) => this.up(e))
    window.addEventListener('pointercancel', (e) => this.up(e, true))
    canvas.addEventListener('contextmenu', (e) => e.preventDefault())
    canvas.addEventListener('pointerleave', () => (this.mouse.inside = false))
    canvas.addEventListener('dblclick', (e) => this.handler?.onDouble?.(e.clientX, e.clientY))
    canvas.addEventListener(
      'wheel',
      (e) => {
        e.preventDefault()
        rig.zoom(Math.pow(1.0013, e.deltaY))
      },
      { passive: false },
    )
    window.addEventListener('keydown', (e) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement || e.target instanceof HTMLTextAreaElement) return
      this.keys.add(e.key.toLowerCase())
      this.onKey?.(e)
      this.handler?.onKey?.(e)
    })
    window.addEventListener('keyup', (e) => this.keys.delete(e.key.toLowerCase()))
    window.addEventListener('blur', () => this.keys.clear())
  }
  down(e) {
    this.canvas.setPointerCapture?.(e.pointerId)
    this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY, sx: e.clientX, sy: e.clientY, button: e.button })
    if (this.pointers.size === 2) {
      const [a, b] = [...this.pointers.values()]
      this.pinch = { d: Math.hypot(a.x - b.x, a.y - b.y), ang: Math.atan2(b.y - a.y, b.x - a.x), dist: this.rig.distGoal, yaw: this.rig.yawGoal }
      this.drag = null
      return
    }
    if (this.pointers.size === 1) {
      const rotate = e.button === 1 || (e.button === 2 && !this.handler?.rightClickCommands)
      const right = e.button === 2
      this.drag = { rotate, right, p0: groundAt(e.clientX, e.clientY), moved: false, x: e.clientX, y: e.clientY, button: e.button, custom: false }
      if (!rotate && !right && this.handler?.onPress?.(e.clientX, e.clientY, e)) this.drag.custom = true
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
      this.rig.distGoal = clamp((this.pinch.dist * this.pinch.d) / Math.max(d, 1), this.rig.minDist, this.rig.fitDist(this.rig.maxDist))
      this.rig.yawGoal = this.pinch.yaw - (ang - this.pinch.ang)
      return
    }
    const dr = this.drag
    if (!dr) return
    const dx = e.clientX - dr.x
    if (!dr.moved && Math.hypot(e.clientX - p.sx, e.clientY - p.sy) > 6) dr.moved = true
    if (!dr.moved) return
    if (dr.custom) {
      this.handler?.onDragMove?.(e.clientX, e.clientY)
      return
    }
    if (dr.rotate || dr.right) {
      // right-drag (or middle-drag) rotates
      this.rig.yawGoal -= dx * 0.006
      this.rig.yaw = this.rig.yawGoal
      this.rig.apply()
      dr.rotated = true
    } else if (dr.p0) {
      const cur = groundAt(e.clientX, e.clientY)
      if (cur) {
        this.rig.follow = null
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
    if (!dr.moved) this.handler?.onTap?.(e.clientX, e.clientY, { button: dr.button, shift: e.shiftKey, ctrl: e.ctrlKey || e.metaKey, touch: e.pointerType === 'touch' })
  }
  update(dt) {
    const k = this.keys
    let mx = 0
    let mz = 0
    if (k.has('w') || k.has('arrowup')) mz -= 1
    if (k.has('s') || k.has('arrowdown')) mz += 1
    if (k.has('a') || k.has('arrowleft')) mx -= 1
    if (k.has('d') || k.has('arrowright')) mx += 1
    // edge scrolling with the mouse (PC)
    if (this.edgePan && this.mouse.inside && !this.drag && document.hasFocus()) {
      const m = 6
      if (this.mouse.x <= m) mx -= 1
      if (this.mouse.x >= window.innerWidth - m) mx += 1
      if (this.mouse.y <= m) mz -= 1
      if (this.mouse.y >= window.innerHeight - m) mz += 1
    }
    if (mx || mz) {
      this.rig.follow = null
      const y = this.rig.yaw
      const sp = this.rig.dist * (k.has('shift') ? 1.8 : 0.95) * dt
      const fx = -Math.sin(y)
      const fz = -Math.cos(y)
      const rx = Math.cos(y)
      const rz = -Math.sin(y)
      this.rig.goal.x += (rx * mx - fx * mz) * sp
      this.rig.goal.z += (rz * mx - fz * mz) * sp
      this.rig.clampGoal()
    }
    if (k.has('q')) this.rig.yawGoal += dt * 1.7
    if (k.has('e')) this.rig.yawGoal -= dt * 1.7
    if (k.has('=') || k.has('+')) this.rig.zoom(1 - dt * 1.2)
    if (k.has('-')) this.rig.zoom(1 + dt * 1.2)
  }
}

// ---------------------------------------------------------------- labels
const _v = new THREE.Vector3()
export class Labels {
  constructor(layer) {
    this.layer = layer
    this.items = new Set()
  }
  add(el, pos, { offsetY = 0, life = 0, rise = 0, scene = null, maxDist = 0 } = {}) {
    el.classList.add('wl-inner')
    const wrap = document.createElement('div')
    wrap.className = 'wl'
    wrap.appendChild(el)
    this.layer.appendChild(wrap)
    const item = { el, wrap, pos, offsetY, life, age: 0, rise, scene, hidden: false, maxDist, remove: () => this.remove(item) }
    this.items.add(item)
    return item
  }
  remove(item) {
    if (!item) return
    item.wrap.remove()
    this.items.delete(item)
  }
  clearScene(scene) {
    for (const it of [...this.items]) if (it.scene === scene) this.remove(it)
  }
  float(scene, pos, html, cls = '') {
    const el = document.createElement('div')
    el.className = 'float ' + cls
    el.innerHTML = html
    return this.add(el, new THREE.Vector3(pos.x, pos.y, pos.z), { life: 1.7, rise: 1.2, scene })
  }
  update(dt, camera, activeScene) {
    const w = window.innerWidth
    const h = window.innerHeight
    const cam = camera.position
    for (const it of this.items) {
      if (it.life) {
        it.age += dt
        if (it.age >= it.life) {
          this.remove(it)
          continue
        }
      }
      const visible = !it.scene || it.scene === activeScene
      const p = typeof it.pos === 'function' ? it.pos() : it.pos?.isObject3D ? it.pos.getWorldPosition(_v) : it.pos
      if (!visible || !p || it.hidden) {
        if (it.wrap.style.display !== 'none') it.wrap.style.display = 'none'
        continue
      }
      if (it.maxDist && cam.distanceTo(p) > it.maxDist) {
        if (it.wrap.style.display !== 'none') it.wrap.style.display = 'none'
        continue
      }
      _v.set(p.x, p.y + it.offsetY + (it.rise ? (it.age / it.life) * it.rise : 0), p.z)
      _v.project(camera)
      if (_v.z > 1 || _v.x < -1.15 || _v.x > 1.15 || _v.y < -1.15 || _v.y > 1.15) {
        if (it.wrap.style.display !== 'none') it.wrap.style.display = 'none'
        continue
      }
      if (it.wrap.style.display === 'none') it.wrap.style.display = ''
      const x = (_v.x * 0.5 + 0.5) * w
      const y = (-_v.y * 0.5 + 0.5) * h
      it.wrap.style.transform = `translate3d(${x.toFixed(1)}px,${y.toFixed(1)}px,0)`
      if (it.life) it.wrap.style.opacity = String(Math.min(1, (1 - it.age / it.life) * 2.2))
    }
  }
}
