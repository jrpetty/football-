import { SIM } from './config'
import { InputManager } from './core/input'
import { Human3DController } from './control/human3d'
import { World } from './match/world'
import { Camera3D } from './render3d/camera3d'
import { Scene3D } from './render3d/scene'
import { Hud } from './ui/hud'
import { Screens } from './ui/screens'
import { emptyCommand } from './types'
import type { MatchConfig } from './types'

export interface Hooks {
  toMenu: () => void
  restart: () => void
}

// The 3D presentation of a match. Shares the exact same World simulation as the
// 2D game — this class only adds a WebGL renderer, a first/third-person camera,
// pointer-lock mouse-look and camera-relative controls. The HUD is drawn on the
// 2D #pitch canvas layered transparently over the WebGL scene.
export class Game3D {
  readonly world: World
  private scene: Scene3D
  readonly cam3: Camera3D
  private hud = new Hud()
  private human: Human3DController
  private ctx: CanvasRenderingContext2D
  private running = false
  private paused = false
  private last = 0
  private fps = 60
  private cssW = 0
  private cssH = 0
  private endShown = false

  constructor(
    private canvas: HTMLCanvasElement,
    container: HTMLElement,
    private config: MatchConfig,
    private input: InputManager,
    private screens: Screens,
    private hooks: Hooks,
  ) {
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('2D canvas unsupported')
    this.ctx = ctx
    canvas.classList.add('hud-only', 'unlocked')
    this.human = new Human3DController({ height: config.heightSens, curve: config.curveSens })
    this.scene = new Scene3D(container)
    this.cam3 = new Camera3D(1)
    this.world = new World(config)
    // Face down the pitch to start (home attacks +x → yaw 0; away → π).
    this.cam3.yaw = 0
    this.resize()
  }

  start() {
    this.running = true
    this.last = performance.now()
    document.addEventListener('pointerlockchange', this.onLockChange)
    this.canvas.addEventListener('mousedown', this.onCanvasDown)
    requestAnimationFrame(this.tick)
  }

  stop() {
    this.running = false
    document.removeEventListener('pointerlockchange', this.onLockChange)
    this.canvas.removeEventListener('mousedown', this.onCanvasDown)
    this.input.exitPointerLock()
    this.canvas.classList.remove('hud-only', 'unlocked')
    this.scene.dispose()
  }

  private onCanvasDown = () => {
    if (!this.paused && !this.input.pointerLocked) this.input.requestPointerLock()
  }

  private onLockChange = () => {
    if (!this.input.pointerLocked && !this.paused && this.running && this.world.phase !== 'fulltime') {
      this.pause()
    }
  }

  private tick = (now: number) => {
    if (!this.running) return
    const raw = Math.max(0, (now - this.last) / 1000)
    this.last = now
    // Report the true frame rate, but hand the simulation a bounded delta so a
    // stall can't destabilise the physics. The bound lives in SIM so the loop and
    // the world agree — clamping tighter here would quietly run the match slow.
    this.fps += (1 / Math.max(raw, 1e-4) - this.fps) * 0.1
    const dt = Math.min(raw, SIM.maxFrameDt)

    this.resize()
    this.handleGlobalKeys()

    const locked = this.input.pointerLocked
    if (!this.paused) {
      const cp = this.world.getControlledPlayer()
      if (locked) {
        this.cam3.look(this.input.movementX, this.input.movementY)
        const cmd = this.human.buildCommand(this.world, this.cam3, this.input, dt)
        this.world.update(dt, cmd)
        const c = this.world.getControlledPlayer()
        if (c) c.heading = this.cam3.yaw // face where you look
      } else {
        // AI keeps playing; the human's player idles until they click to lock.
        this.world.update(dt, emptyCommand())
      }
      if (cp) this.cam3.update(cp.x, cp.y, dt)
      this.maybeEnd()
    }

    this.render(locked, dt)
    this.canvas.classList.toggle('unlocked', !locked)
    this.input.endFrame()
    ;(window as unknown as { __pitch?: unknown }).__pitch = {
      phase: this.world.phase,
      clock: this.world.clock,
      score: this.world.score,
      ballSpeed: this.world.ball.speed,
      possessor: this.world.possessorId,
      fps: this.fps,
      zoom: this.cam3.mode,
      view: '3d',
    }
    requestAnimationFrame(this.tick)
  }

  private handleGlobalKeys() {
    if (this.input.justPressed('KeyV')) this.cam3.toggle()
    if (this.input.justPressed('KeyP')) this.paused ? this.resume() : this.pause()
    if (this.config.mode === 'training' && this.input.justPressed('KeyB') && !this.paused) {
      const cp = this.world.getControlledPlayer()
      const aim = this.cam3.aimSim()
      const pos = cp ? { x: cp.x + aim.x * 4, y: cp.y + aim.y * 4 } : { x: 29, y: 19 }
      this.world.spawnBallAt(pos)
    }
  }

  private pause() {
    if (this.config.mode === 'match' && this.world.phase === 'fulltime') return
    this.paused = true
    this.input.exitPointerLock()
    this.screens.onResume = () => this.resume()
    this.screens.onRestart = () => this.hooks.restart()
    this.screens.onMenu = () => this.hooks.toMenu()
    this.screens.showPause()
  }

  private resume() {
    this.paused = false
    this.screens.hide()
  }

  private maybeEnd() {
    if (this.config.mode !== 'match') return
    if (this.world.phase === 'fulltime' && !this.endShown) {
      this.endShown = true
      this.input.exitPointerLock()
      const s = this.world.stats
      const total = Math.max(1, s.home.possessionTicks + s.away.possessionTicks)
      const poss = Math.round((s.home.possessionTicks / total) * 100)
      const stats = `Possession ${poss}% – ${100 - poss}% · Shots ${s.home.shots}–${s.away.shots} · Saves ${s.home.saves}–${s.away.saves}`
      this.screens.onRestart = () => this.hooks.restart()
      this.screens.onMenu = () => this.hooks.toMenu()
      this.screens.showEnd(this.world.score.home, this.world.score.away, stats)
    }
  }

  private resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const w = window.innerWidth
    const h = window.innerHeight
    if (w === this.cssW && h === this.cssH && this.canvas.width) return
    this.cssW = w
    this.cssH = h
    this.canvas.width = Math.floor(w * dpr)
    this.canvas.height = Math.floor(h * dpr)
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    this.scene.resize(w, h)
    this.cam3.setAspect(w / h)
  }

  private render(locked: boolean, dt: number) {
    const controlledId = this.world.getControlledPlayer()?.id ?? -1
    const first = this.cam3.mode === 'first'
    this.scene.sync(this.world, controlledId, !first, first ? controlledId : -1, dt)
    this.scene.render(this.cam3.cam)

    this.ctx.clearRect(0, 0, this.cssW, this.cssH)
    this.hud.draw(
      this.ctx,
      this.world,
      {
        chargeType: this.human.chargeType,
        charge: this.human.charge,
        loft: this.human.liveLoft,
        spin: this.human.liveSpin,
        fps: this.fps,
        mode: this.config.mode,
        zoomLabel: this.cam3.mode === 'first' ? '1st person' : '3rd person',
      },
      this.cssW,
      this.cssH,
    )
    // Aiming reticle (centre) when locked; a prompt to click when not.
    if (locked) {
      this.ctx.strokeStyle = 'rgba(255,255,255,0.7)'
      this.ctx.lineWidth = 2
      this.ctx.beginPath()
      this.ctx.arc(this.cssW / 2, this.cssH / 2, 4, 0, Math.PI * 2)
      this.ctx.stroke()
    } else if (!this.paused) {
      this.ctx.fillStyle = 'rgba(6,12,9,0.55)'
      this.ctx.fillRect(this.cssW / 2 - 150, this.cssH / 2 - 26, 300, 52)
      this.ctx.fillStyle = '#eaf1ff'
      this.ctx.font = '600 18px system-ui, sans-serif'
      this.ctx.textAlign = 'center'
      this.ctx.textBaseline = 'middle'
      this.ctx.fillText('Click to look around & play', this.cssW / 2, this.cssH / 2)
    }
  }
}
