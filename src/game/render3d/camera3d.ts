import * as THREE from 'three'
import { clamp } from '../core/math'
import type { Vec2 } from '../core/vec'

export type ViewMode = 'third' | 'first'

// A neutral starting point near the pitch centre so the first frame isn't jarring.
const FIELD_CENTER = 29

// Owns the perspective camera and the look direction (yaw/pitch). Yaw doubles as
// the player's aim: it's the horizontal direction the camera — and therefore the
// player — is pointing, so kicks go where you look.
export class Camera3D {
  readonly cam: THREE.PerspectiveCamera
  yaw = 0
  pitch = -0.14
  mode: ViewMode = 'third'

  private curPos = new THREE.Vector3(FIELD_CENTER, 6, -6)
  private curLook = new THREE.Vector3(FIELD_CENTER, 1, 0)
  private tmpPos = new THREE.Vector3()
  private tmpLook = new THREE.Vector3()

  constructor(aspect: number) {
    this.cam = new THREE.PerspectiveCamera(62, aspect, 0.1, 400)
  }

  setAspect(a: number) {
    this.cam.aspect = a
    this.cam.updateProjectionMatrix()
  }

  // Mouse deltas from pointer lock.
  look(dx: number, dy: number, sens = 0.0022) {
    this.yaw += dx * sens
    this.pitch = clamp(this.pitch - dy * sens, -0.6, 0.45)
  }

  toggle() {
    this.mode = this.mode === 'third' ? 'first' : 'third'
  }

  // Horizontal aim direction in SIM coordinates (x forward, y depth).
  aimSim(): Vec2 {
    return { x: Math.cos(this.yaw), y: Math.sin(this.yaw) }
  }

  // Follow the controlled player. px,py are the player's SIM position.
  update(px: number, py: number, dt: number) {
    const fx = Math.cos(this.yaw)
    const fz = Math.sin(this.yaw)
    if (this.mode === 'third') {
      const dist = 7.5
      const height = 3.4 - this.pitch * 4
      this.tmpPos.set(px - fx * dist, height, py - fz * dist)
      this.tmpLook.set(px + fx * 4, 1.3, py + fz * 4)
      const k = 1 - Math.exp(-12 * dt)
      this.curPos.lerp(this.tmpPos, k)
      this.curLook.lerp(this.tmpLook, k)
    } else {
      const cp = Math.cos(this.pitch)
      this.curPos.set(px + fx * 0.3, 1.68, py + fz * 0.3)
      this.curLook.set(
        this.curPos.x + fx * cp,
        this.curPos.y + Math.sin(this.pitch),
        this.curPos.z + fz * cp,
      )
    }
    this.cam.position.copy(this.curPos)
    this.cam.lookAt(this.curLook)
  }
}
