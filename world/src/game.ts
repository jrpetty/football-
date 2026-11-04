// The running game: owns the world, the renderer and the mind scheduler, and
// drives them from one requestAnimationFrame loop. React panels subscribe to a
// throttled "version" so the UI refreshes a few times a second, not every frame.

import { World, DEFAULT_CONFIG, type WorldConfig } from './sim/world.ts'
import { REAL_SEC_PER_HOUR, HOUR } from './sim/time.ts'
import { Renderer } from './render/renderer.ts'
import { mind } from './ai/mind.ts'
import { saveToSlot, loadSlot } from './sim/save.ts'
import type { ChronicleEntry } from './sim/types.ts'

export const SPEEDS = [1, 3, 10, 30, 100]

export interface Toast {
  id: number
  icon: string
  text: string
  kind: ChronicleEntry['kind']
  at: number
  agentId?: number
}

class Game {
  world: World
  renderer: Renderer | null = null
  speed = 3
  paused = false
  version = 0
  fps = 60
  simMsPerFrame = 0
  toasts: Toast[] = []
  private toastId = 1
  private subs = new Set<() => void>()
  private raf = 0
  private last = 0
  private lastEmit = 0
  private lastAutosave = Date.now()
  private canvas: HTMLCanvasElement | null = null
  private unlisten: (() => void) | null = null

  constructor() {
    this.world = new World({ ...DEFAULT_CONFIG, seed: Math.floor(Math.random() * 1e9) })
    this.hook()
  }

  subscribe = (fn: () => void) => {
    this.subs.add(fn)
    return () => this.subs.delete(fn)
  }
  getVersion = () => this.version

  emit() {
    this.version++
    for (const s of this.subs) s()
  }

  private hook() {
    mind.attach(this.world)
    this.unlisten?.()
    const onLog = (e: ChronicleEntry) => {
      if (e.kind === 'speech') return
      const important = e.kind === 'milestone' || e.kind === 'tech' || e.kind === 'tribe' || e.kind === 'event' || (e.kind === 'build' && /first|town hall|monument|launch|university/i.test(e.text)) || e.kind === 'ai'
      if (!important && !(e.kind === 'social' && /partners/.test(e.text)) && e.kind !== 'death' && e.kind !== 'birth') return
      this.toasts.push({ id: this.toastId++, icon: e.icon, text: e.text, kind: e.kind, at: Date.now(), agentId: e.agentId })
      if (this.toasts.length > 3) this.toasts.shift()
    }
    this.world.listeners.add(onLog)
    this.unlisten = () => this.world.listeners.delete(onLog)
  }

  attachCanvas(c: HTMLCanvasElement) {
    this.canvas = c
    this.renderer = new Renderer(c, this.world)
    this.resize()
    cancelAnimationFrame(this.raf)
    this.last = performance.now()
    const loop = (now: number) => {
      this.raf = requestAnimationFrame(loop)
      this.frame(now)
    }
    this.raf = requestAnimationFrame(loop)
  }

  detach() {
    cancelAnimationFrame(this.raf)
  }

  resize() {
    if (!this.canvas || !this.renderer) return
    const r = this.canvas.getBoundingClientRect()
    this.renderer.resize(r.width, r.height, Math.min(2, window.devicePixelRatio || 1))
  }

  private frame(now: number) {
    const dt = Math.min(0.25, (now - this.last) / 1000)
    this.last = now
    this.fps = this.fps * 0.95 + (dt > 0 ? 1 / dt : 60) * 0.05
    if (!this.paused) {
      const minutes = (dt * this.speed * HOUR) / REAL_SEC_PER_HOUR
      const t0 = performance.now()
      this.world.tick(Math.min(minutes, 6 * HOUR))
      this.simMsPerFrame = this.simMsPerFrame * 0.9 + (performance.now() - t0) * 0.1
    }
    mind.tick()
    this.renderer?.render(now)
    const n = Date.now()
    this.toasts = this.toasts.filter((t) => n - t.at < 6000)
    if (now - this.lastEmit > 220) {
      this.lastEmit = now
      this.emit()
    }
    if (n - this.lastAutosave > 120_000) {
      this.lastAutosave = n
      void saveToSlot('autosave', this.world).catch(() => {})
    }
  }

  setSpeed(s: number) {
    this.speed = s
    this.paused = false
    this.emit()
  }
  togglePause() {
    this.paused = !this.paused
    this.emit()
  }

  newWorld(cfg: WorldConfig) {
    this.setWorld(new World(cfg))
  }

  setWorld(w: World) {
    this.world = w
    this.hook()
    this.renderer?.setWorld(w)
    this.toasts = []
    this.emit()
  }

  async load(slot: string): Promise<boolean> {
    const w = await loadSlot(slot)
    if (!w) return false
    this.setWorld(w)
    return true
  }

  selectAgent(id: number, fly = true) {
    const r = this.renderer
    if (!r) return
    r.selection = { kind: 'agent', id }
    const a = this.world.agentById.get(id)
    if (a && fly) r.flyTo(a.x, a.y, Math.max(r.cam.zoom, 2))
    this.emit()
  }
  selectBuilding(id: number) {
    const r = this.renderer
    if (!r) return
    r.selection = { kind: 'building', id }
    const b = this.world.buildingById.get(id)
    if (b) r.flyTo(b.x + 0.5, b.y + 0.5)
    this.emit()
  }
  focusTribe(id: number) {
    const r = this.renderer
    const t = this.world.tribeById(id)
    if (!r || !t) return
    r.selection = { kind: 'tribe', id }
    r.flyTo(t.center.x + 0.5, t.center.y + 0.5, 1.3)
    this.emit()
  }
}

export const game = new Game()
// handy for debugging from the console
;(globalThis as unknown as { genesis: Game }).genesis = game
