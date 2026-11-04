// The surface both map renderers (2D canvas and 3D WebGL) present to the game
// and the UI, so either can be swapped in.

import type { World } from '../sim/world.ts'
import type { Overlay, Selection } from './renderer.ts'

export interface MapView {
  world: World
  cam: { x: number; y: number; zoom: number }
  target: { x: number; y: number; zoom: number } | null
  vw: number
  vh: number
  selection: Selection
  hover: { x: number; y: number } | null
  follow: boolean
  cinematic: boolean
  overlay: Overlay
  showNames: boolean
  resize(w: number, h: number, dpr: number): void
  render(now: number): void
  pan(dx: number, dy: number): void
  zoomAt(sx: number, sy: number, factor: number): void
  flyTo(x: number, y: number, zoom?: number): void
  pick(sx: number, sy: number): Selection
  screenToWorld(sx: number, sy: number): { x: number; y: number }
  setWorld(w: World): void
  selectedTribeId(): number
  renderMinimap(c: HTMLCanvasElement): void
  rotate?(dx: number): void
  dispose?(): void
}

export type ViewMode = '3d' | '2d'

export function preferredView(): ViewMode {
  try {
    return localStorage.getItem('genesis.renderer') === '2d' ? '2d' : '3d'
  } catch {
    return '3d'
  }
}
export function setPreferredView(v: ViewMode) {
  try { localStorage.setItem('genesis.renderer', v) } catch { /* private mode */ }
}
