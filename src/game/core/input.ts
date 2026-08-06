import { binds } from './bindings'
import type { Action } from './bindings'

// Raw keyboard + mouse state with edge detection. Nothing game-specific lives
// here — controllers read this and build a Command. Uses KeyboardEvent.code so
// bindings are layout-independent (WASD stays WASD on AZERTY).
//
// Readers ask by action rather than by key: `did('shield')`, not
// `isDown('KeyQ')`. The raw-code methods are still here because the rebinding
// screen needs to listen for a keypress as a keypress.

export class InputManager {
  private down = new Set<string>()
  private pressedEdge = new Set<string>()
  private releasedEdge = new Set<string>()

  mouse = { x: 0, y: 0 } // canvas-space pixels
  mouseInside = false
  mouseButtons = { left: false, right: false }
  mousePressed = { left: false, right: false }
  mouseReleased = { left: false, right: false }
  wheel = 0
  // Relative motion this frame (used by the pointer-locked 3D camera).
  movementX = 0
  movementY = 0

  private el: HTMLElement

  constructor(el: HTMLElement) {
    this.el = el
    window.addEventListener('keydown', this.onKeyDown)
    window.addEventListener('keyup', this.onKeyUp)
    el.addEventListener('mousemove', this.onMouseMove)
    el.addEventListener('mousedown', this.onMouseDown)
    window.addEventListener('mouseup', this.onMouseUp)
    el.addEventListener('mouseenter', () => (this.mouseInside = true))
    el.addEventListener('mouseleave', () => (this.mouseInside = false))
    el.addEventListener('contextmenu', (e) => e.preventDefault())
    el.addEventListener('wheel', this.onWheel, { passive: false })
    window.addEventListener('blur', () => this.clear())
  }

  dispose() {
    window.removeEventListener('keydown', this.onKeyDown)
    window.removeEventListener('keyup', this.onKeyUp)
    window.removeEventListener('mouseup', this.onMouseUp)
  }

  // Set while the rebinding screen is open: it wants the next keypress as a
  // key, not as whatever action that key currently performs.
  capture: ((code: string) => void) | null = null

  private onKeyDown = (e: KeyboardEvent) => {
    if (this.capture) {
      e.preventDefault()
      const fn = this.capture
      this.capture = null
      fn(e.code)
      return
    }
    // Keep the page from scrolling on the keys we use to play. Which keys those
    // are is now up to the player, so it is asked rather than listed.
    if (binds.gameKeys().has(e.code)) e.preventDefault()
    if (!this.down.has(e.code)) this.pressedEdge.add(e.code)
    this.down.add(e.code)
  }
  private onKeyUp = (e: KeyboardEvent) => {
    this.down.delete(e.code)
    this.releasedEdge.add(e.code)
  }
  private onMouseMove = (e: MouseEvent) => {
    const r = this.el.getBoundingClientRect()
    this.mouse.x = e.clientX - r.left
    this.mouse.y = e.clientY - r.top
    this.mouseInside = true
    this.movementX += e.movementX || 0
    this.movementY += e.movementY || 0
  }

  requestPointerLock() {
    if (document.pointerLockElement !== this.el) this.el.requestPointerLock?.()
  }
  exitPointerLock() {
    if (document.pointerLockElement === this.el) document.exitPointerLock?.()
  }
  get pointerLocked(): boolean {
    return document.pointerLockElement === this.el
  }
  private onMouseDown = (e: MouseEvent) => {
    if (e.button === 0) {
      if (!this.mouseButtons.left) this.mousePressed.left = true
      this.mouseButtons.left = true
    } else if (e.button === 2) {
      if (!this.mouseButtons.right) this.mousePressed.right = true
      this.mouseButtons.right = true
      e.preventDefault()
    }
  }
  private onMouseUp = (e: MouseEvent) => {
    if (e.button === 0) {
      if (this.mouseButtons.left) this.mouseReleased.left = true
      this.mouseButtons.left = false
    } else if (e.button === 2) {
      if (this.mouseButtons.right) this.mouseReleased.right = true
      this.mouseButtons.right = false
    }
  }
  private onWheel = (e: WheelEvent) => {
    this.wheel += Math.sign(e.deltaY)
    e.preventDefault()
  }

  isDown(code: string): boolean {
    return this.down.has(code)
  }
  // True on the frame a key transitioned to down.
  justPressed(code: string): boolean {
    return this.pressedEdge.has(code)
  }
  justReleased(code: string): boolean {
    return this.releasedEdge.has(code)
  }
  anyDown(...codes: string[]): boolean {
    return codes.some((c) => this.down.has(c))
  }

  // ---- by action ----
  // What everything outside the settings screen should use.

  held(a: Action): boolean {
    return binds.keys(a).some((c) => c && this.down.has(c))
  }
  did(a: Action): boolean {
    return binds.keys(a).some((c) => c && this.pressedEdge.has(c))
  }

  // Call once per rendered frame, AFTER controllers have read edges.
  endFrame() {
    this.pressedEdge.clear()
    this.releasedEdge.clear()
    this.mousePressed.left = this.mousePressed.right = false
    this.mouseReleased.left = this.mouseReleased.right = false
    this.wheel = 0
    this.movementX = 0
    this.movementY = 0
  }

  clear() {
    this.down.clear()
    this.pressedEdge.clear()
    this.mouseButtons.left = this.mouseButtons.right = false
  }
}

