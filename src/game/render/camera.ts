import { FIELD } from '../config'
import { clamp } from '../core/math'
import type { Vec2 } from '../core/vec'

export type ZoomMode = 'tv' | 'follow' | 'close'

const VIEW_WIDTH: Record<ZoomMode, number> = {
  tv: FIELD.length + FIELD.margin * 1.6,
  follow: 42,
  close: 30,
}

// Maps world metres ↔ screen pixels and smoothly chases the action. The view is
// width-fit: a target number of metres spans the canvas width, and the camera
// pans vertically to keep the focus in frame.
export class Camera {
  x = FIELD.length / 2
  y = FIELD.width / 2
  ppm = 12 // pixels per metre (recomputed on resize / zoom)
  mode: ZoomMode = 'follow'
  private w = 800
  private h = 600
  private heightScale = 0.55 // how strongly ball height lifts it on screen

  setViewport(cssW: number, cssH: number) {
    this.w = cssW
    this.h = cssH
    this.recompute()
  }

  cycleZoom() {
    this.mode = this.mode === 'tv' ? 'follow' : this.mode === 'follow' ? 'close' : 'tv'
    this.recompute()
  }

  private recompute() {
    this.ppm = this.w / VIEW_WIDTH[this.mode]
  }

  get width() {
    return this.w
  }
  get height() {
    return this.h
  }

  // Ease toward the focus point. `focus` is usually a blend of ball + player.
  follow(focus: Vec2, dt: number) {
    // Keep the camera from wandering too far past the pitch edges.
    const halfViewX = this.w / 2 / this.ppm
    const halfViewY = this.h / 2 / this.ppm
    const marginX = FIELD.margin + 2
    const marginY = FIELD.margin + 2
    const tx = clamp(
      focus.x,
      Math.min(FIELD.length / 2, halfViewX - marginX),
      Math.max(FIELD.length / 2, FIELD.length - halfViewX + marginX),
    )
    const ty = clamp(
      focus.y,
      Math.min(FIELD.width / 2, halfViewY - marginY),
      Math.max(FIELD.width / 2, FIELD.width - halfViewY + marginY),
    )
    const k = 1 - Math.exp(-8 * dt) // framerate-independent smoothing
    this.x += (tx - this.x) * k
    this.y += (ty - this.y) * k
  }

  worldToScreen(wx: number, wy: number, wz = 0): Vec2 {
    return {
      x: (wx - this.x) * this.ppm + this.w / 2,
      y: (wy - this.y) * this.ppm + this.h / 2 - wz * this.ppm * this.heightScale,
    }
  }

  screenToWorld(sx: number, sy: number): Vec2 {
    return {
      x: (sx - this.w / 2) / this.ppm + this.x,
      y: (sy - this.h / 2) / this.ppm + this.y,
    }
  }
}
