// First person: walk in one survivor's boots. The camera sits at their eyes,
// the mouse turns their head (pointer lock), WASD walks them through the
// same grid the AI walks, and their own arms hold the weapon they carry:
// guns fire where the crosshair is, melee weapons swing at what is in front.
// The scene in play (a run, the camp) is the "host": it moves the body with
// collision, resolves shots and swings, and says what is under the
// crosshair (E to search, open, climb...). Everything here is presentation
// and input; the host owns the rules.
import * as THREE from 'three'
import { view } from './view.js'
import { VMLayer } from './vmlayer.js'
import { viewModel } from '../models/viewmodel.js'
import { clamp, lerp, h } from '../core/util.js'
import { sfx } from '../core/audio.js'
import { INDOOR } from './materials.js'
import { icon } from '../ui/icons.js'

const KEY = 'holdout.fp'
// third: over the shoulder instead of behind the eyes
export const fpPrefs = { sens: 1, fov: 74, invert: false, bob: true, third: false }
try {
  Object.assign(fpPrefs, JSON.parse(localStorage.getItem(KEY) || '{}'))
} catch {}
export function saveFpPrefs() {
  try {
    localStorage.setItem(KEY, JSON.stringify(fpPrefs))
  } catch {}
}

const UP = new THREE.Vector3(0, 1, 0)
const _e = new THREE.Euler(0, 0, 0, 'YXZ')
const _v = new THREE.Vector3()
const _d = new THREE.Vector3()
const _f = new THREE.Vector3()
const _p = new THREE.Vector3()
const _q = new THREE.Vector3()
const _eye = new THREE.Vector3()
const ease = (t) => t * t * (3 - 2 * t)
// turn angle a toward b by at most step radians, the short way round
const turnTo = (a, b, step) => {
  const d = Math.atan2(Math.sin(b - a), Math.cos(b - a))
  return a + clamp(d, -step, step)
}

// a soft star for the muzzle flash, drawn once
let flashTex = null
function flashTexture() {
  if (flashTex) return flashTex
  const c = document.createElement('canvas')
  c.width = c.height = 128
  const g = c.getContext('2d')
  const r = g.createRadialGradient(64, 64, 0, 64, 64, 64)
  r.addColorStop(0, 'rgba(255,250,220,1)')
  r.addColorStop(0.18, 'rgba(255,214,120,0.95)')
  r.addColorStop(0.5, 'rgba(255,140,40,0.35)')
  r.addColorStop(1, 'rgba(255,90,10,0)')
  g.fillStyle = r
  g.fillRect(0, 0, 128, 128)
  g.globalCompositeOperation = 'lighter'
  g.fillStyle = 'rgba(255,220,150,0.8)'
  for (let i = 0; i < 6; i++) {
    g.save()
    g.translate(64, 64)
    g.rotate((i / 6) * Math.PI * 2 + 0.3)
    g.beginPath()
    g.moveTo(0, -5)
    g.lineTo(62, 0)
    g.lineTo(0, 5)
    g.fill()
    g.restore()
  }
  flashTex = new THREE.CanvasTexture(c)
  flashTex.colorSpace = THREE.SRGBColorSpace
  return flashTex
}

export class FirstPerson {
  constructor(game) {
    this.game = game
    this.active = false
    this.host = null
    this.yaw = 0
    this.pitch = 0
    this.vx = 0
    this.vz = 0
    this.bobPh = 0
    this.bobAmt = 0
    this.stepT = 0
    this.layer = new VMLayer()
    this.vm = null
    this.vmKey = ''
    this.locked = false
    this.trigger = false
    this.aimHeld = false
    this.ads = 0
    this.kick = 0
    this.recoil = 0
    this.swingT = -1
    this.swingDur = 0.5
    this.swingSide = 1
    this.throwT = -1
    this.equip = 0
    this.swayX = 0
    this.swayY = 0
    this.hurtV = 0
    this.lastHp = null
    this.sprint = 0
    this.cool = 0
    this.flashT = 0
    this.shake = 0
    this.lookT = 0
    this.look = null
    this.hitT = 0
    this.dom = null
    this.saved = null
    // no pointer lock (a touch screen, or a page that may not take the
    // mouse): drag to look; on a touch screen a thumb stick walks and
    // buttons fire, aim and use
    this.free = false
    this.touchUI = false
    this.drags = new Map()
    this.stick = null
    this.tmx = 0
    this.tmz = 0
    this.trun = false
    this.aimToggle = false
    this.lockPromise = false
    this.noteT = 0
    this.padKey = ''
    // third person: how far the camera has gone over the shoulder (0..1),
    // how far back it sits, whether the body is drawn, the body's heading
    this.thirdK = 0
    this.camDist = 2.3
    this.bodyOn = null
    this.bodyFor = null
    this.bodyH = null
    this.lastAtk = 0
    // muzzle flash: a cross of two glowing cards at the muzzle
    const fm = new THREE.MeshBasicMaterial({ map: flashTexture(), transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false, color: new THREE.Color(3, 2.6, 2) })
    this.flashMesh = new THREE.Group()
    for (let i = 0; i < 2; i++) {
      const m = new THREE.Mesh(new THREE.PlaneGeometry(0.16, 0.16), fm)
      m.rotation.y = i * Math.PI * 0.5
      this.flashMesh.add(m)
    }
    const face = new THREE.Mesh(new THREE.PlaneGeometry(0.12, 0.12), fm)
    face.rotation.x = 0
    this.flashMesh.add(face)
    this.flashMesh.visible = false
    this.layer.root.add(this.flashMesh)
    // spent brass (and red shotgun shells) flicking out of the gun
    const brass = new THREE.MeshStandardMaterial({ color: '#c9a24a', metalness: 0.85, roughness: 0.32 })
    const shell = new THREE.MeshStandardMaterial({ color: '#a8261e', metalness: 0.1, roughness: 0.5 })
    this.casings = []
    for (let i = 0; i < 8; i++) {
      const m = new THREE.Mesh(new THREE.CylinderGeometry(0.0045, 0.0045, 0.019, 8), brass)
      m.visible = false
      m.userData = { v: new THREE.Vector3(), spin: new THREE.Vector3(), t: 0, brass, shell }
      this.layer.root.add(m)
      this.casings.push(m)
    }
    document.addEventListener('pointerlockchange', () => this.onLockChange())
    // browsers whose request hands back no promise say no with this event
    document.addEventListener('pointerlockerror', () => !this.lockPromise && this.lockError())
    document.addEventListener('mousemove', (e) => this.onMouse(e))
    document.addEventListener('mousedown', (e) => this.onDown(e), true)
    document.addEventListener('mouseup', (e) => this.onUp(e), true)
    document.addEventListener('contextmenu', (e) => this.active && (this.locked || this.free) && e.preventDefault())
    window.addEventListener('pointermove', (e) => this.onPMove(e))
    window.addEventListener('pointerup', (e) => this.onPUp(e))
    window.addEventListener('pointercancel', (e) => this.onPUp(e, true))
  }

  // ---------------------------------------------------------------- dom
  buildDom() {
    if (this.dom) return this.dom
    const D = {}
    D.root = h('div.fp')
    D.cross = h('div.fp-cross', h('i.t'), h('i.b'), h('i.l'), h('i.r'), h('b'))
    D.hit = h('div.fp-hit', h('i'), h('i'), h('i'), h('i'))
    D.prompt = h('div.fp-prompt')
    D.prog = h('div.fp-prog', h('i'))
    D.hurt = h('div.fp-hurt')
    D.low = h('div.fp-low')
    D.scope = h('div.fp-scope')
    D.name = h('div.fp-name')
    D.hpbar = h('div.fp-hp', h('i'))
    D.weapon = h('div.fp-weapon')
    D.ammo = h('div.fp-ammo')
    D.tip = h('div.fp-tip')
    D.pause = h(
      'div.fp-pause',
      h('div.fp-pause-card', h('h3', 'In their boots'), h('p', 'Click to look around and play.'), h('div.fp-keys', ...[['WASD', 'walk'], ['Shift', 'run'], ['Mouse', 'look'], ['Left click', 'shoot or swing'], ['Right click', 'aim'], ['E', 'use what you look at'], ['T', 'first or third person'], ['` / F5 / Esc', 'top-down view']].map(([k, v]) => h('span', h('kbd', k), ' ', v))), h('div.fp-pause-btns', h('button.btn.go', { onclick: (e) => (e.stopPropagation(), this.lock()) }, 'Play'), (D.camBtn = h('button.btn', { onclick: (e) => (e.stopPropagation(), this.setThird(!fpPrefs.third)) }, fpPrefs.third ? 'First person' : 'Third person')), h('button.btn', { onclick: (e) => (e.stopPropagation(), this.game.toggleFirstPerson?.(false)) }, 'Top-down view'))),
    )
    D.note = h('div.fp-note')
    // touch: a stick that appears under the left thumb, buttons on the right
    D.stick = h('div.fp-stick', (D.knob = h('i')))
    const tb = (cls, b, ic, label) => h(`button.fp-tb.${cls}`, { 'data-fp': b, 'aria-label': label }, h('i', { html: icon(ic) }), label && h('span', label))
    D.fire = tb('fire', 'fire', 'crosshair', '')
    D.aim = tb('aim', 'aim', 'binoculars', 'Aim')
    D.use = tb('use', 'use', 'search', 'Use')
    D.keys = h('div.fp-tkeys')
    D.cam = tb('cam', 'cam', 'rotate', fpPrefs.third ? '1st' : '3rd')
    D.pad = h('div.fp-pad', D.stick, h('div.fp-stickhint'), D.fire, D.aim, D.use, h('div.fp-tcol', tb('exit', 'exit', 'eye', 'View'), D.cam, D.keys))
    D.root.append(D.hurt, D.low, D.scope, D.cross, D.hit, D.prompt, D.prog, h('div.fp-status', D.name, D.hpbar), h('div.fp-gun', D.weapon, D.ammo), D.tip, D.note, D.pad, D.pause)
    document.body.appendChild(D.root)
    view.canvas.addEventListener('pointerdown', (e) => this.onPDown(e))
    D.pad.addEventListener('pointerdown', (e) => this.onPDown(e))
    this.dom = D
    return D
  }
  // a line of help that fades by itself
  noteShow(text, secs = 5) {
    if (!this.dom) return
    this.dom.note.textContent = text
    this.noteT = secs
  }
  // behind the eyes or over the shoulder (kept between visits)
  setThird(on) {
    fpPrefs.third = !!on
    saveFpPrefs()
    if (this.dom) {
      this.dom.camBtn.textContent = on ? 'First person' : 'Third person'
      this.dom.cam.querySelector('span').textContent = on ? '1st' : '3rd'
    }
    for (const b of document.querySelectorAll('.fpbtn')) b.textContent = on ? 'Third person' : 'First person'
    if (this.active) sfx('click')
  }
  isThird() {
    return this.thirdK > 0.5
  }
  setPrompt(text) {
    if (this.dom.prompt._t === text) return
    this.dom.prompt._t = text
    this.dom.prompt.innerHTML = text || ''
    this.dom.prompt.classList.toggle('on', !!text)
  }
  hitMark(kill, head) {
    sfx(kill ? 'killmark' : 'hitmark', 30)
    const el = this.dom.hit
    el.classList.remove('on', 'kill', 'head')
    void el.offsetWidth
    el.classList.add('on')
    if (kill) el.classList.add('kill')
    if (head) el.classList.add('head')
    this.hitT = 0.2
  }

  // ---------------------------------------------------------------- lock
  lock() {
    if (!this.active || this.free) return
    const cv = view.canvas
    if (!cv.requestPointerLock) return this.lockError()
    let p = null
    try {
      p = cv.requestPointerLock({ unadjustedMovement: true })
    } catch {}
    this.lockPromise = !!p?.then
    // raw mouse input is not on offer everywhere: then the plain lock
    p?.catch?.(() => {
      let q = null
      try {
        q = cv.requestPointerLock()
      } catch {
        return this.lockError()
      }
      q?.catch?.(() => this.lockError())
    })
  }
  // The page may not take the mouse (some app views, a sandboxed frame):
  // look by dragging instead. Straight after Esc the browser refuses for a
  // moment, which is no reason to give up on it.
  lockError() {
    if (!this.active || this.locked || this.free) return
    if (performance.now() - (this.unlockedAt || -1e4) < 1500) return
    this.setFree(true)
    if (!this.touchUI) this.noteShow('This page can’t hold the mouse: drag to look, click to shoot, right-drag to aim.', 7)
  }
  setFree(on) {
    this.free = on
    this.drags.clear()
    this.stick = null
    this.tmx = this.tmz = 0
    this.trun = false
    this.trigger = false
    this.aimHeld = this.aimToggle = false
    const D = this.dom
    if (!D) return
    D.root.classList.toggle('free', on)
    D.root.classList.toggle('touch', on && this.touchUI)
    document.body.classList.toggle('fptouch', on && this.touchUI)
    document.body.classList.toggle('fpfree', on && !this.touchUI)
    D.pause.classList.remove('on')
    D.stick.classList.remove('on')
    D.fire.classList.remove('down')
    D.aim.classList.remove('down')
  }
  unlock() {
    if (document.pointerLockElement) document.exitPointerLock?.()
  }
  onLockChange() {
    const was = this.locked
    this.locked = !!document.pointerLockElement && document.pointerLockElement === view.canvas
    if (was && !this.locked) this.unlockedAt = performance.now()
    if (this.locked && this.free) this.setFree(false)
    this.trigger = false
    this.aimHeld = false
    if (this.dom) this.dom.pause.classList.toggle('on', this.active && !this.locked && !this.free && !this.uiOpen())
  }
  // turn the head by a drag or a mouse movement (pixels; k radians a pixel)
  turn(dx, dy, k) {
    k *= fpPrefs.sens * (1 - this.ads * 0.45) * (this.scoped() ? 0.4 : 1)
    dx = clamp(dx, -300, 300)
    dy = clamp(dy, -300, 300)
    this.yaw -= dx * k
    this.pitch = clamp(this.pitch - dy * k * (fpPrefs.invert ? -1 : 1), -1.45, 1.45)
    this.swayX = clamp(this.swayX + dx * 0.00012, -0.03, 0.03)
    this.swayY = clamp(this.swayY + dy * 0.00012, -0.03, 0.03)
  }
  onMouse(e) {
    if (!this.active || !this.locked) return
    this.turn(e.movementX || 0, e.movementY || 0, 0.0021)
  }
  // ---------------------------------------------------------------- touch / no lock
  onPDown(e) {
    if (!this.active) return
    const touch = e.pointerType === 'touch' || e.pointerType === 'pen'
    if (touch && !this.touchUI) {
      // a finger: the touch layout from now on
      this.touchUI = true
      this.unlock()
      this.setFree(true)
    }
    if (!this.free || this.uiOpen()) return
    e.preventDefault()
    e.stopPropagation()
    const btn = e.target.closest?.('[data-fp]')
    const b = btn?.dataset.fp
    const d = { x: e.clientX, y: e.clientY, sx: e.clientX, sy: e.clientY, moved: 0, mouse: !touch, button: e.button, b, t: performance.now() }
    if (b === 'fire') {
      this.trigger = true
      this.tryAttack()
      btn.classList.add('down')
    } else if (b === 'aim') {
      this.aimToggle = !this.aimToggle
      this.aimHeld = this.aimToggle
      btn.classList.toggle('down', this.aimToggle)
      return
    } else if (b === 'use') {
      this.use()
      return
    } else if (b === 'exit') {
      this.game.toggleFirstPerson?.(false)
      return
    } else if (b === 'cam') {
      this.setThird(!fpPrefs.third)
      return
    } else if (b) {
      this.host?.fpKey?.({ key: b, shiftKey: false, ctrlKey: false, preventDefault() {} }, this)
      this.padKey = ''
      return
    } else if (touch && e.clientX < innerWidth * 0.45 && !this.stick) {
      d.stick = true
      this.stick = d
      this.dom.stick.classList.add('on')
      this.setStick(d)
    } else if (!touch && e.button === 2) {
      this.aimHeld = true
      d.aim = true
    }
    try {
      ;(btn || view.canvas).setPointerCapture?.(e.pointerId)
    } catch {}
    this.drags.set(e.pointerId, d)
  }
  onPMove(e) {
    const d = this.drags.get(e.pointerId)
    if (!d || !this.active) return
    const dx = e.clientX - d.x
    const dy = e.clientY - d.y
    d.x = e.clientX
    d.y = e.clientY
    d.moved += Math.abs(dx) + Math.abs(dy)
    if (d.stick) return this.setStick(d)
    // a click is not a drag until it has moved a little
    if (d.mouse && d.button === 0 && d.moved < 6 && !d.hold) return
    this.turn(dx, dy, d.mouse ? 0.0036 : 0.0042)
  }
  onPUp(e, cancel) {
    const d = this.drags.get(e.pointerId)
    if (!d) return
    this.drags.delete(e.pointerId)
    if (d.stick) {
      this.stick = null
      this.tmx = this.tmz = 0
      this.trun = false
      this.dom?.stick.classList.remove('on')
      return
    }
    if (d.b === 'fire') {
      this.trigger = false
      this.dom?.fire.classList.remove('down')
      return
    }
    if (d.aim) {
      this.aimHeld = false
      return
    }
    if (d.mouse && d.button === 0) {
      if (d.hold) this.trigger = false
      else if (d.moved < 6 && !cancel) this.tryAttack()
    }
  }
  // the stick: the knob follows the thumb up to the ring; the ring follows a
  // thumb that slides well past it; pushed hard forwards, they run
  setStick(d) {
    const R = 50
    let ox = d.x - d.sx
    let oy = d.y - d.sy
    let L = Math.hypot(ox, oy)
    if (L > R * 1.6) {
      const k = 1 - (R * 1.6) / L
      d.sx += ox * k
      d.sy += oy * k
      ox = d.x - d.sx
      oy = d.y - d.sy
      L = Math.hypot(ox, oy)
    }
    const m = clamp((L - 5) / (R - 5), 0, 1)
    this.tmx = L > 0 ? (ox / L) * m : 0
    this.tmz = L > 0 ? (-oy / L) * m : 0
    this.trun = L > R * 1.2 && -oy > Math.abs(ox)
    const S = this.dom.stick
    S.style.transform = `translate(${d.sx}px, ${d.sy}px)`
    const kl = Math.min(L, R)
    this.dom.knob.style.transform = `translate(${L ? (ox / L) * kl : 0}px, ${L ? (oy / L) * kl : 0}px)`
    S.classList.toggle('run', this.trun)
  }
  // the host's own buttons (the torch, the squad...), refreshed when they change
  padKeys() {
    const list = this.host?.fpTouchKeys?.() || []
    const key = list.map((k) => k.key + (k.n ?? '') + (k.on ? '*' : '')).join('|')
    if (key === this.padKey) return
    this.padKey = key
    this.dom.keys.replaceChildren(...list.map((k) => h(`button.fp-tb.small${k.on ? '.down' : ''}`, { 'data-fp': k.key, 'aria-label': k.label }, h('i', { html: icon(k.icon) }), h('span', k.n != null ? `${k.label} ${k.n}` : k.label))))
  }
  onDown(e) {
    if (!this.active || this.free) return
    if (!this.locked) {
      if (e.target === view.canvas) {
        e.preventDefault()
        e.stopPropagation()
        this.lock()
      }
      return
    }
    e.preventDefault()
    e.stopPropagation()
    if (e.button === 0) {
      this.trigger = true
      this.tryAttack()
    } else if (e.button === 2) this.aimHeld = true
  }
  onUp(e) {
    if (!this.active) return
    if (e.button === 0) this.trigger = false
    else if (e.button === 2) this.aimHeld = false
  }
  uiOpen() {
    const ui = this.game.ui
    if (!ui) return false
    if (ui.modalRoot?.children.length) return true
    // the camp's panels only count in the camp
    if (this.game.scene !== this.game.base) return false
    return !!(ui.panelKey || (ui.dock && !ui.dock.hidden))
  }

  // ---------------------------------------------------------------- enter/exit
  enter(host) {
    if (this.active) this.exit(true)
    if (!host?.fpEnter?.()) return false
    this.host = host
    this.active = true
    view.fp = this
    const cam = view.camera
    this.saved = { fov: cam.fov, near: cam.near }
    // the body keeps the eye a hand's breadth or more from any wall, and the
    // arms draw in their own pass: a near plane this far out keeps the depth
    // precise enough that road markings don't shimmer a hundred metres off
    cam.near = 0.1
    cam.fov = fpPrefs.fov
    cam.updateProjectionMatrix()
    // face the way the top-down camera faced
    this.yaw = view.rig.yaw
    this.pitch = -0.05
    this.vx = this.vz = 0
    this.ads = 0
    this.equip = 0
    this.vmKey = ''
    this.lastHp = null
    // straight into the chosen camera, no swing over from the eyes
    this.thirdK = fpPrefs.third ? 1 : 0
    this.camDist = 2.3
    this.bodyOn = null
    this.bodyFor = null
    this.bodyH = null
    this.buildDom()
    this.dom.root.classList.add('on')
    document.body.classList.add('fpmode')
    this.game.pipe.overlay = this.layer
    this.game.pipe.firstPerson = true
    INDOOR.uFpSpec.value = 1
    this.layer.visible = true
    // a touch screen gets the stick and buttons; a mouse gets pointer lock
    const mq = (q) => {
      try {
        return matchMedia(q).matches
      } catch {
        return false
      }
    }
    if (mq('(pointer: coarse)') && !mq('(any-pointer: fine)')) this.touchUI = true
    this.free = false
    this.setFree(this.touchUI || !view.canvas.requestPointerLock)
    this.lock()
    this.onLockChange()
    if (this.touchUI) this.noteShow('Left thumb walks (push to the edge to run). Drag anywhere else to look.', 6)
    sfx('select')
    return true
  }
  // away: the scene in play changed under us, so the camera is not ours
  exit(quiet = false, away = false) {
    if (!this.active) return
    const H = this.host
    const feet = away ? null : H?.fpFeet?.()
    H?.fpExit?.()
    this.active = false
    this.host = null
    view.fp = null
    const cam = view.camera
    if (this.saved) {
      cam.fov = this.saved.fov
      cam.near = this.saved.near
      cam.updateProjectionMatrix()
    }
    this.unlock()
    this.setFree(false)
    this.game.pipe.overlay = null
    this.game.pipe.firstPerson = false
    INDOOR.uFpSpec.value = 0
    this.layer.visible = false
    if (this.vm) {
      this.layer.root.remove(this.vm.rig)
      this.vm = null
    }
    if (this.dom) {
      this.dom.root.classList.remove('on')
      this.dom.pause.classList.remove('on')
      this.setPrompt(null)
      this.noteT = 0
      this.dom.note.classList.remove('on')
    }
    document.body.classList.remove('fpmode')
    // the top-down camera picks up where the eyes were, looking the same way
    const rig = view.rig
    rig.follow = null
    rig.yaw = rig.yawGoal = this.yaw
    if (feet) rig.jump(feet.x, feet.z, Math.max(rig.minDist + 6, 22))
    if (!quiet) sfx('select')
  }

  // ---------------------------------------------------------------- keys
  onKey(e) {
    if (!this.active) return false
    const k = e.key.toLowerCase()
    if (k === 'escape') {
      // the first Esc frees the mouse (the browser does that itself, and may
      // still hand us its key); another one goes back to the view from above
      if (this.locked) this.unlock()
      else if (performance.now() - (this.unlockedAt || 0) > 400) this.game.toggleFirstPerson?.(false)
      return true
    }
    if (k === 'e' && !e.ctrlKey && !e.metaKey) {
      this.use()
      return true
    }
    if (k === 't' && !e.ctrlKey && !e.metaKey && !e.altKey) {
      if (!e.repeat) this.setThird(!fpPrefs.third)
      return true
    }
    if (this.host?.fpKey?.(e, this)) return true
    // keys that walk or turn the top-down camera do nothing here
    if (['w', 'a', 's', 'd', 'q', 'arrowup', 'arrowdown', 'arrowleft', 'arrowright', 'shift', '=', '+', '-'].includes(k)) return k === 'q' && e.repeat
    return false
  }
  use() {
    const L = this.look
    if (!L?.act) return
    L.act()
    this.lookT = 0
  }

  // ---------------------------------------------------------------- frame
  forward(out = _d) {
    _e.set(this.pitch, this.yaw, 0, 'YXZ')
    return out.set(0, 0, -1).applyEuler(_e)
  }
  scoped() {
    return this.ads > 0.85 && !!this.vm?.pose.scope
  }
  update(dt) {
    const H = this.host
    if (!H) return
    if (!H.fpAlive()) {
      const next = H.fpNext?.()
      if (!next) {
        this.game.toggleFirstPerson?.(false)
        return
      }
    }
    const D = this.dom
    const ui = this.uiOpen()
    if (ui && this.locked) this.unlock()
    D.pause.classList.toggle('on', !this.locked && !this.free && !ui)
    const keys = view.input.keys
    const live = this.locked || !ui
    // ---- move
    let mx = 0
    let mz = 0
    if (live) {
      if (keys.has('w') || keys.has('arrowup')) mz += 1
      if (keys.has('s') || keys.has('arrowdown')) mz -= 1
      if (keys.has('d') || keys.has('arrowright')) mx += 1
      if (keys.has('a') || keys.has('arrowleft')) mx -= 1
      // the thumb stick keeps how far it is pushed
      mx += this.tmx
      mz += this.tmz
    }
    const len = Math.max(1, Math.hypot(mx, mz))
    mx /= len
    mz /= len
    const wantRun = (keys.has('shift') || (this.trun && live)) && mz > 0.3 && this.ads < 0.3 && this.swingT < 0
    // no lock, a mouse: a click held still fires on (a drag looks instead)
    if (this.free && this.drags.size) {
      const now = performance.now()
      for (const d of this.drags.values()) {
        if (d.mouse && d.button === 0 && !d.hold && d.moved < 6 && now - d.t > 220) {
          d.hold = true
          this.trigger = true
          this.tryAttack()
        }
      }
    }
    this.noteT -= dt
    D.note.classList.toggle('on', this.noteT > 0)
    this.sprint = lerp(this.sprint, wantRun && (mx || mz) ? 1 : 0, 1 - Math.exp(-dt * 8))
    const base = H.fpSpeed()
    const speed = base * (wantRun ? 1.3 : 0.78) * (1 - this.ads * 0.4)
    const sy = Math.sin(this.yaw)
    const cy = Math.cos(this.yaw)
    // forward is (-sin yaw, -cos yaw); right is (cos yaw, -sin yaw)
    const wx = (cy * mx - sy * mz) * speed
    const wz = (-sy * mx - cy * mz) * speed
    const acc = 1 - Math.exp(-dt * (mx || mz ? 11 : 14))
    this.vx += (wx - this.vx) * acc
    this.vz += (wz - this.vz) * acc
    const moved = H.fpMove(this.vx * dt, this.vz * dt)
    const sp = dt > 0 ? Math.hypot(moved.x, moved.z) / dt : 0
    if (sp < Math.hypot(this.vx, this.vz) * 0.5) {
      // into a wall: lose the speed that went nowhere
      this.vx = moved.x / Math.max(dt, 1e-3)
      this.vz = moved.z / Math.max(dt, 1e-3)
    }
    // over the shoulder the body turns to where it walks, and back to the
    // crosshair to aim, shoot or swing; behind the eyes it is the view
    this.thirdK = clamp(this.thirdK + (fpPrefs.third ? dt : -dt) * 3.5, 0, 1)
    let heading = this.yaw + Math.PI
    if (this.thirdK > 0.5) {
      const aiming = this.ads > 0.15 || this.trigger || performance.now() - this.lastAtk < 1400 || this.swingT >= 0
      if (!aiming && Math.hypot(this.vx, this.vz) > 0.5) heading = Math.atan2(this.vx, this.vz)
      this.bodyH = turnTo(this.bodyH ?? heading, heading, dt * (aiming ? 20 : 10))
      heading = this.bodyH
    } else this.bodyH = heading
    H.fpFace(heading, sp, wantRun && sp > 0.5, this.ads > 0.15 || this.trigger || performance.now() - this.lastAtk < 1400)
    this.bobAmt = lerp(this.bobAmt, clamp(sp / 4, 0, 1), 1 - Math.exp(-dt * 8))
    this.bobPh += sp * dt * (wantRun ? 1.55 : 1.85)
    this.stepT -= sp * dt
    if (this.stepT <= 0 && sp > 0.6) {
      this.stepT = wantRun ? 1.15 : 0.95
      sfx(wantRun ? 'stepRun' : 'step', 120)
      H.fpStep?.(wantRun)
    }
    // ---- camera
    const cam = view.camera
    const eye = H.fpEye(_v)
    // a scope is looked through: aiming one takes the camera to the eye
    const scopeK = this.vm?.pose.scope ? ease(this.ads) : 0
    const tk = ease(this.thirdK) * (1 - scopeK)
    const bobK = (fpPrefs.bob ? 1 : 0.35) * (1 - tk * 0.75)
    const b = this.bobAmt * bobK
    eye.y += Math.sin(this.bobPh * 2) * 0.028 * b - b * 0.01
    eye.x += Math.cos(this.yaw) * Math.sin(this.bobPh) * 0.018 * b
    eye.z += -Math.sin(this.yaw) * Math.sin(this.bobPh) * 0.018 * b
    this.shake = Math.max(0, this.shake - dt * 3)
    if (this.shake > 0) {
      eye.x += (Math.random() - 0.5) * this.shake * 0.06
      eye.y += (Math.random() - 0.5) * this.shake * 0.06
    }
    _eye.copy(eye)
    if (tk > 0.001) cam.position.lerpVectors(eye, this.shoulder(eye, dt), tk)
    else cam.position.copy(eye)
    this.recoil = Math.max(0, this.recoil - dt * (2.2 + this.recoil * 6))
    _e.set(this.pitch + this.recoil * 0.06, this.yaw, (Math.sin(this.bobPh) * 0.004 * b - this.swayX * 0.4) * (1 - tk), 'YXZ')
    cam.quaternion.setFromEuler(_e)
    // aiming down the sights narrows the view; a scope much more
    this.ads = lerp(this.ads, this.aimHeld && this.vm?.pose.kind === 'gun' && this.swingT < 0 ? 1 : 0, 1 - Math.exp(-dt * 12))
    const zoom = this.vm?.pose.scope ? lerp(1, 0.36, ease(this.ads)) : lerp(1, 0.82 - tk * 0.06, ease(this.ads))
    const fov = fpPrefs.fov * zoom * (1 + this.sprint * 0.06)
    if (Math.abs(cam.fov - fov) > 0.01) {
      cam.fov = fov
      cam.updateProjectionMatrix()
    }
    cam.updateMatrixWorld()
    // systems that follow the top-down camera's focus follow the feet
    const feet = H.fpFeet()
    view.rig.target.set(feet.x, view.rig.levelY, feet.z)
    view.rig.goal.copy(view.rig.target)
    view.rig.follow = null
    H.fpTick?.(dt)
    // ---- attack
    this.cool -= dt
    if (this.trigger && (this.locked || this.free)) this.tryAttack()
    // ---- what is under the crosshair
    this.lookT -= dt
    if (this.lookT <= 0) {
      this.lookT = 0.1
      const fw = this.forward(new THREE.Vector3())
      const L = (this.look = live ? H.fpLook(this.aimFrom(fw), fw) : null)
      // a key only for what can be done; on a touch screen the Use button lights
      const k = L ? (L.key ?? (L.act ? 'E' : '')) : ''
      this.setPrompt(L ? (k && !this.touchUI ? `<kbd>${k}</kbd> ` : '') + L.text : null)
      if (this.touchUI && this.free) {
        D.use.classList.toggle('ready', !!L?.act)
        this.padKeys()
      }
    }
    // ---- hurt
    const st = H.fpStatus()
    if (this.lastHp != null && st.hp < this.lastHp - 0.5) {
      const dmg = this.lastHp - st.hp
      this.hurtV = Math.min(1, this.hurtV + 0.35 + dmg / st.maxHp)
      this.shake = Math.min(1, this.shake + 0.5)
    }
    this.lastHp = st.hp
    this.hurtV = Math.max(0, this.hurtV - dt * 1.6)
    D.hurt.style.opacity = this.hurtV.toFixed(2)
    D.low.style.opacity = clamp(1 - st.hp / st.maxHp / 0.35, 0, 1).toFixed(2)
    // ---- hud
    if (D.name._t !== st.name) D.name.textContent = D.name._t = st.name
    D.hpbar.firstChild.style.width = `${clamp(st.hp / st.maxHp, 0, 1) * 100}%`
    D.hpbar.classList.toggle('low', st.hp < st.maxHp * 0.35)
    if (D.weapon._t !== st.weapon) D.weapon.textContent = D.weapon._t = st.weapon
    if (D.ammo._t !== st.ammo) D.ammo.innerHTML = D.ammo._t = st.ammo
    D.prog.classList.toggle('on', st.prog != null)
    if (st.prog != null) {
      D.prog.firstChild.style.width = `${clamp(st.prog, 0, 1) * 100}%`
      D.prog.dataset.label = st.progLabel || ''
    }
    if (D.tip._t !== (st.tip || '')) {
      D.tip._t = st.tip || ''
      D.tip.textContent = D.tip._t
      D.tip.classList.toggle('on', !!D.tip._t)
    }
    const spread = this.spread()
    const px = 6 + spread * 900
    D.cross.style.setProperty('--gap', `${px.toFixed(1)}px`)
    D.cross.classList.toggle('melee', this.vm?.pose.kind !== 'gun')
    D.cross.classList.toggle('hide', this.scoped() || this.sprint > 0.6)
    D.scope.classList.toggle('on', this.scoped())
    this.hitT -= dt
    // ---- the body: drawn once the camera is clear of the head
    const showBody = cam.position.distanceTo(_eye) > 0.5
    if (showBody !== this.bodyOn || st.lookKey !== this.bodyFor) {
      this.bodyOn = showBody
      this.bodyFor = st.lookKey
      H.fpSetBody?.(showBody)
    }
    // ---- the arms (behind the eyes, or coming up to a scope)
    this.layer.visible = tk < 0.5
    this.updateVM(dt, st, sp)
    this.layer.setAspect(cam.aspect)
    this.layer.sync(H.scene, H.atmo, cam, H.fpShade?.() ?? 1)
  }
  // Where the camera sits over the shoulder: behind and to the right of the
  // head, closer when aiming, pulled in front of any wall or ceiling between
  // it and the head (straight away; it eases back out once clear).
  shoulder(eye, dt) {
    const H = this.host
    const f = this.forward(_f)
    const ax = ease(this.ads)
    const rx = Math.cos(this.yaw)
    const rz = -Math.sin(this.yaw)
    const side = lerp(0.42, 0.58, ax)
    const back = lerp(2.3, 1.05, ax)
    _p.set(eye.x + rx * side, eye.y + lerp(0.2, 0.08, ax), eye.z + rz * side)
    // the shoulder point itself may be in a wall (a doorway, a corridor)
    let s = 1
    for (let k = 1; k <= 4; k++) {
      const t = k / 4
      if (this.solidNear(H, eye.x + (_p.x - eye.x) * t, eye.y + (_p.y - eye.y) * t, eye.z + (_p.z - eye.z) * t)) {
        s = ((k - 1) / 4) * 0.8
        break
      }
    }
    _p.set(eye.x + (_p.x - eye.x) * s, eye.y + (_p.y - eye.y) * s, eye.z + (_p.z - eye.z) * s)
    let free = back
    for (let t = 0.12; t <= back + 0.2; t += 0.12) {
      if (this.solidNear(H, _p.x - f.x * t, _p.y - f.y * t, _p.z - f.z * t)) {
        free = Math.max(0, t - 0.3)
        break
      }
    }
    this.camDist = free < this.camDist ? free : lerp(this.camDist, free, 1 - Math.exp(-dt * 4))
    const out = _q.set(_p.x - f.x * this.camDist, _p.y - f.y * this.camDist, _p.z - f.z * this.camDist)
    // never under the ground looking up
    const feet = H.fpFeet()
    out.y = Math.max(out.y, feet.y + 0.3)
    return out
  }
  // is there anything solid within a camera's breadth of this point?
  solidNear(H, x, y, z) {
    const r = 0.16
    return H.fpSolid(x, y, z) || H.fpSolid(x + r, y, z) || H.fpSolid(x - r, y, z) || H.fpSolid(x, y, z + r) || H.fpSolid(x, y, z - r) || H.fpSolid(x, y + r, z)
  }
  // Where a shot (or a look) starts: the eye; over the shoulder, the point on
  // the camera's ray level with the body, so the crosshair is what is hit
  // and nothing between the camera and the body gets in the way.
  aimFrom(dir) {
    const cam = view.camera
    if (this.thirdK < 0.01 || !this.host) return cam.position.clone()
    const eye = this.host.fpEye(new THREE.Vector3())
    const t = Math.max(0, eye.sub(cam.position).dot(dir))
    return cam.position.clone().addScaledVector(dir, t)
  }
  // how far shots stray (radians): steadier aimed, worse running or jumping
  spread() {
    const pose = this.vm?.pose
    if (!pose || pose.kind !== 'gun') return 0.01
    const base = this.host?.fpAccuracy?.() ?? 0.02
    const move = clamp(Math.hypot(this.vx, this.vz) / 4, 0, 1)
    return base * (1 - this.ads * 0.75) * (1 + move * 1.3 + this.sprint) + this.kick * 0.01
  }
  tryAttack() {
    const H = this.host
    if (!H || this.cool > 0 || this.equip < 0.85 || this.sprint > 0.5) return
    if (this.swingT >= 0 && this.swingT < this.swingDur * 0.75) return
    const dir = this.forward(new THREE.Vector3())
    const r = H.fpAttack(this.aimFrom(dir), dir, { spread: this.spread(), ads: this.ads > 0.6, onHit: (kill, head) => this.hitMark(kill, head), muzzle: this.muzzleWorld() })
    if (!r) return
    this.lastAtk = performance.now()
    if (r.empty) {
      this.cool = 0.35
      this.trigger = false
      sfx('dry', 200)
      return
    }
    this.cool = r.rate
    if (r.kind === 'gun') {
      this.kick = 1
      this.recoil += r.recoil ?? 0.5
      this.flashT = 0.055
      this.layer.flash.intensity = 6
      if (!r.auto) this.trigger = false
      if (r.cycle) this.cycleT = 0
      if (this.vm?.pose.eject && this.vm.weaponId !== 'crossbow') this.eject(this.vm.weaponId === 'shotgun')
    } else {
      this.swingT = 0
      this.swingDur = clamp(r.rate * 0.85, 0.32, 0.9)
      this.swingSide = -this.swingSide
    }
  }
  // where the muzzle is in the world, for the flash and the tracer
  muzzleWorld() {
    const cam = view.camera
    if (this.thirdK > 0.5 && this.host) {
      // over the shoulder: out of the gun in the body's hands, chest high
      const f = this.host.fpFeet()
      const e = this.host.fpEye(_q)
      const sy = Math.sin(this.yaw)
      const cy = Math.cos(this.yaw)
      return new THREE.Vector3(f.x - sy * 0.8 + cy * 0.14, f.y + (e.y - f.y) * 0.84, f.z - cy * 0.8 - sy * 0.14)
    }
    const m = this.vm?.muzzle
    if (!m) return cam.position.clone().addScaledVector(this.forward(new THREE.Vector3()), 0.6)
    // the overlay draws with its own field of view; near enough, map the
    // muzzle's view-space spot onto the world camera
    const p = m.clone().applyMatrix4(this.vm.rig.matrix)
    return p.applyQuaternion(cam.quaternion).add(cam.position)
  }
  throwAnim() {
    this.throwT = 0
  }
  eject(shotgun) {
    const c = this.casings.find((m) => !m.visible) || this.casings[0]
    const U = c.userData
    const V = this.vm
    c.material = shotgun ? U.shell : U.brass
    c.scale.set(shotgun ? 4.2 : 1, shotgun ? 3.2 : 1, shotgun ? 4.2 : 1)
    c.position.copy(V.pose.eject).applyMatrix4(V.rig.matrix)
    // out to the right and up, tumbling
    U.v.set(0.9 + Math.random() * 0.5, 0.9 + Math.random() * 0.4, 0.15 - Math.random() * 0.3)
    U.spin.set(Math.random() * 20, Math.random() * 20, 10 + Math.random() * 20)
    U.t = 0
    c.visible = true
  }

  // ---------------------------------------------------------------- arms
  updateVM(dt, st, sp) {
    const key = `${st.weaponId}|${(st.mods || []).join(',')}|${st.lookKey}`
    if (key !== this.vmKey) {
      if (this.vm) this.layer.root.remove(this.vm.rig)
      this.vm = viewModel(st.weaponId, st.mods || [], st.look)
      this.layer.root.add(this.vm.rig)
      this.vm.base = { p: this.vm.rig.position.clone(), q: this.vm.rig.quaternion.clone() }
      this.vm.adsPose = adsPose(this.vm)
      this.vmKey = key
      this.equip = 0
      this.swingT = -1
    }
    const V = this.vm
    const pose = V.pose
    const rig = V.rig
    this.equip = Math.min(1, this.equip + dt * 2.8)
    this.kick = Math.max(0, this.kick - dt * 9)
    this.swayX *= Math.exp(-dt * 9)
    this.swayY *= Math.exp(-dt * 9)
    const t = performance.now() / 1000
    const b = this.bobAmt * (fpPrefs.bob ? 1 : 0.5)
    const ads = ease(this.ads)
    // idle breathing, walk bob (a figure of eight), mouse sway
    let px = Math.cos(this.bobPh) * 0.011 * b + Math.sin(t * 1.3) * 0.0012 - this.swayX * 0.6
    let py = -Math.abs(Math.sin(this.bobPh)) * 0.012 * b + Math.sin(t * 2.1) * 0.0014 + this.swayY * 0.6
    let pz = 0
    let rx = this.swayY * 1.6
    let ry = this.swayX * 2
    let rz = -this.swayX * 1.5 + Math.cos(this.bobPh) * 0.012 * b
    // running: the weapon drops and tilts away
    const s = ease(this.sprint)
    px += s * 0.03
    py -= s * 0.05
    rx -= s * 0.32
    ry += s * (pose.kind === 'gun' ? 0.55 : 0.2)
    rz += s * 0.25
    // equipping: it comes up from below
    const eq = 1 - ease(this.equip)
    py -= eq * 0.28
    rx -= eq * 0.6
    // the shot: back into the shoulder, muzzle up
    if (pose.kind === 'gun') {
      const k = this.kick * this.kick
      pz += k * (pose.ads ? 0.045 : 0.03)
      rx += k * 0.09
      py += k * 0.006
      // a pump or a bolt worked between shots
      if (this.cycleT != null && this.cycleT < 1) {
        this.cycleT += dt / Math.max(0.3, (this.cool > 0 ? this.cool : 0.6))
        const c = Math.sin(clamp(this.cycleT, 0, 1) * Math.PI)
        py -= c * 0.022
        rz += c * 0.12
        rx -= c * 0.05
      }
    }
    // a melee swing: wind up, cut across, recover
    if (this.swingT >= 0) {
      this.swingT += dt
      const u = this.swingT / this.swingDur
      if (u >= 1) this.swingT = -1
      else {
        const side = pose.kind === 'fists' ? 0 : 1
        const wind = u < 0.3 ? ease(u / 0.3) : u < 0.55 ? 1 - ease((u - 0.3) / 0.25) : 0
        const cut = u < 0.3 ? 0 : u < 0.55 ? ease((u - 0.3) / 0.25) : 1 - ease((u - 0.55) / 0.45)
        if (side) {
          // the wind-up keeps the hands on screen; the cut sweeps across
          px += wind * 0.02 - cut * 0.22
          py += wind * 0.03 - cut * 0.05
          pz += wind * 0.05 - cut * 0.08
          rx += wind * 0.22 - cut * 0.55
          ry += -wind * 0.08 + cut * 0.6
          rz += wind * 0.22 - cut * 1.1
        } else {
          // a jab with one fist, then the other
          const jab = Math.sin(clamp(u / 0.55, 0, 1) * Math.PI)
          px += this.swingSide * jab * -0.07
          pz -= jab * 0.16
          py += jab * 0.03
        }
      }
    }
    if (this.throwT >= 0) {
      this.throwT += dt / 0.6
      const c = Math.sin(clamp(this.throwT, 0, 1) * Math.PI)
      py -= c * 0.2
      rx -= c * 0.4
      if (this.throwT >= 1) this.throwT = -1
    }
    // aiming: the sights come to the middle of the view
    const A = V.adsPose
    const P = V.base.p
    rig.position.set(lerp(P.x, A.p.x, ads) + px * (1 - ads * 0.8), lerp(P.y, A.p.y, ads) + py * (1 - ads * 0.7), lerp(P.z, A.p.z, ads) + pz)
    rig.quaternion.copy(V.base.q).slerp(A.q, ads)
    _e.set(rx * (1 - ads * 0.7), ry * (1 - ads * 0.8), rz * (1 - ads * 0.8), 'YXZ')
    rig.quaternion.multiply(new THREE.Quaternion().setFromEuler(_e))
    rig.visible = !this.scoped()
    rig.updateMatrix()
    // muzzle flash
    this.flashT -= dt
    const fl = this.flashT > 0 && V.muzzle
    this.flashMesh.visible = !!fl && !this.scoped()
    if (fl) {
      this.flashMesh.position.copy(V.muzzle).applyMatrix4(rig.matrix)
      this.flashMesh.quaternion.copy(rig.quaternion)
      this.flashMesh.rotateZ(Math.random() * Math.PI)
      const s2 = 0.8 + Math.random() * 0.6
      this.flashMesh.scale.setScalar(s2 * (pose.ads && V.weapon && st.weaponId !== 'pistol' && st.weaponId !== 'revolver' ? 1.3 : 1))
    }
    this.layer.flash.intensity = Math.max(0, this.layer.flash.intensity - dt * 120)
    if (V.muzzle) this.layer.flash.position.copy(V.muzzle).applyMatrix4(rig.matrix)
    for (const c of this.casings) {
      if (!c.visible) continue
      const U = c.userData
      U.t += dt
      U.v.y -= 9.8 * dt
      c.position.addScaledVector(U.v, dt)
      c.rotation.x += U.spin.x * dt
      c.rotation.y += U.spin.y * dt
      c.rotation.z += U.spin.z * dt
      if (U.t > 0.7) c.visible = false
    }
    // the torch, when the host says it is on: in the hand, a hand's breadth
    // from the gun, so it only warms the near side of it (the beam itself is
    // the world's light)
    this.layer.torch.intensity = st.torch ? 0.9 : 0
  }
}

// The rig pose that puts the sights on the middle of the view: the weapon
// turned straight down the view axis, its sight line at the centre.
function adsPose(V) {
  const pose = V.pose
  if (pose.kind !== 'gun' || !V.weapon) return { p: V.rig.position.clone(), q: V.rig.quaternion.clone() }
  // the weapon's own rotation, undone so it points straight ahead
  const wq = V.weapon.quaternion.clone()
  const straight = new THREE.Quaternion().setFromEuler(new THREE.Euler(0, Math.PI, 0))
  const q = straight.clone().multiply(wq.clone().invert())
  // the sight point (over the grip, at sight height), turned with the rig
  const sp = new THREE.Vector3(0, pose.sight, 0).applyQuaternion(wq).add(V.weapon.position).applyQuaternion(q)
  // the eye a hand's breadth behind a rifle's sights, an arm's length from
  // a pistol's, close to a scope
  const dist = pose.scope ? 0.16 : pose.support?.cup ? 0.42 : 0.27
  const p = new THREE.Vector3(-sp.x, -sp.y, -dist - sp.z + (pose.ads ? 0 : 0))
  return { p, q }
}
