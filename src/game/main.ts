import './style.css'
import { InputManager } from './core/input'
import { HumanController } from './control/human'
import { World } from './match/world'
import { Camera } from './render/camera'
import { Renderer } from './render/renderer'
import { Hud } from './ui/hud'
import { Screens } from './ui/screens'
import { Game3D } from './game3d'
import type { Hooks } from './game3d'
import type { MatchConfig } from './types'

// Anything boot() can drive, regardless of 2D/3D presentation.
interface RunningGame {
  start(): void
  stop(): void
  readonly world: World
}

// One running match. Owns the fixed-step world plus everything needed to draw
// and drive it. The render/update split lives in the rAF loop.
class Game {
  private ctx: CanvasRenderingContext2D
  readonly world: World
  private cam = new Camera()
  private renderer: Renderer
  private hud = new Hud()
  private human: HumanController
  private running = false
  private paused = false
  private last = 0
  private fps = 60
  private cssW = 0
  private cssH = 0
  private endShown = false

  constructor(
    private canvas: HTMLCanvasElement,
    private config: MatchConfig,
    private input: InputManager,
    private screens: Screens,
    private hooks: Hooks,
  ) {
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('2D canvas unsupported')
    this.ctx = ctx
    this.renderer = new Renderer(ctx)
    this.human = new HumanController({ height: config.heightSens, curve: config.curveSens })
    this.world = new World(config)
    this.resize()
    this.cam.mode = 'follow'
    const b = this.world.ball
    this.cam.x = b.x
    this.cam.y = b.y
  }

  start() {
    this.running = true
    this.last = performance.now()
    requestAnimationFrame(this.tick)
  }

  stop() {
    this.running = false
  }

  private tick = (now: number) => {
    if (!this.running) return
    let dt = (now - this.last) / 1000
    this.last = now
    if (dt > 0.05) dt = 0.05
    if (dt < 0) dt = 0
    this.fps += (1 / Math.max(dt, 1e-4) - this.fps) * 0.1

    this.resize()
    this.handleGlobalKeys()

    if (!this.paused) {
      const cmd = this.human.buildCommand(this.world, this.cam, this.input, dt)
      this.world.update(dt, cmd)
      this.cam.follow(this.cameraFocus(), dt)
      this.maybeEnd()
    }

    this.render()
    this.input.endFrame()
    // Lightweight hook for automated smoke-testing; harmless in normal play.
    ;(window as unknown as { __pitch?: unknown }).__pitch = {
      phase: this.world.phase,
      clock: this.world.clock,
      score: this.world.score,
      ballSpeed: this.world.ball.speed,
      ballZ: this.world.ball.z,
      possessor: this.world.possessorId,
      controlled: this.world.controlledId,
      chargeType: this.human.chargeType,
      charge: this.human.charge,
      loft: this.human.liveLoft,
      fps: this.fps,
      zoom: this.cam.mode,
    }
    requestAnimationFrame(this.tick)
  }

  private cameraFocus() {
    const b = this.world.ball
    const p = this.world.getControlledPlayer()
    const px = p ? p.x : b.x
    const py = p ? p.y : b.y
    return {
      x: b.x * 0.6 + px * 0.4 + b.vx * 0.1,
      y: b.y * 0.6 + py * 0.4 + b.vy * 0.1,
    }
  }

  private handleGlobalKeys() {
    if (this.input.justPressed('KeyV')) this.cam.cycleZoom()
    if (this.input.wheel !== 0 && this.input.wheel < 0) this.cam.cycleZoom()

    if (this.input.justPressed('Escape') || this.input.justPressed('KeyP')) {
      this.paused ? this.resume() : this.pause()
    }

    if (
      this.config.mode === 'freeplay' &&
      this.input.justPressed('KeyB') &&
      !this.paused
    ) {
      const pos = this.input.mouseInside
        ? this.cam.screenToWorld(this.input.mouse.x, this.input.mouse.y)
        : { x: 29, y: 19 }
      this.world.spawnBallAt(pos)
    }
  }

  private pause() {
    if (this.config.mode === 'freeplay' ? false : this.world.phase === 'fulltime') return
    this.paused = true
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
    const w = this.canvas.clientWidth || window.innerWidth
    const h = this.canvas.clientHeight || window.innerHeight
    if (w === this.cssW && h === this.cssH && this.canvas.width) return
    this.cssW = w
    this.cssH = h
    this.canvas.width = Math.floor(w * dpr)
    this.canvas.height = Math.floor(h * dpr)
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    this.cam.setViewport(w, h)
  }

  private render() {
    this.renderer.draw(this.world, this.cam)
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
        zoomLabel: this.cam.mode,
      },
      this.cssW,
      this.cssH,
    )
  }
}

// ---- bootstrap ------------------------------------------------------------

function boot() {
  const canvasEl = document.getElementById('pitch') as HTMLCanvasElement | null
  const overlay = document.getElementById('overlay')
  const containerEl = document.getElementById('scene3d')
  if (!canvasEl || !overlay || !containerEl) throw new Error('missing #pitch / #overlay / #scene3d')
  const canvas: HTMLCanvasElement = canvasEl
  const container: HTMLElement = containerEl

  const input = new InputManager(canvas)
  const screens = new Screens(overlay)
  let game: RunningGame | null = null
  let lastConfig: MatchConfig | null = null

  const hooks: Hooks = {
    toMenu: () => {
      game?.stop()
      game = null
      resetStage()
      screens.showMenu()
    },
    restart: () => {
      if (lastConfig) startGame(lastConfig)
    },
  }

  function resetStage() {
    canvas.classList.remove('hud-only', 'unlocked')
    container.innerHTML = ''
  }

  function startGame(config: MatchConfig) {
    game?.stop()
    // ?ai in the URL runs both teams on AI (used to measure balance in tests).
    if (location.search.includes('ai')) config = { ...config, humanControlled: false }
    lastConfig = config
    screens.hide()
    resetStage()
    game =
      config.view === '3d'
        ? new Game3D(canvas, container, config, input, screens, hooks)
        : new Game(canvas, config, input, screens, hooks)
    game.start()
    // Opt-in inspection hook for automated tests (?debug in the URL).
    if (location.search.includes('debug')) {
      ;(window as unknown as { __world?: unknown }).__world = game.world
    }
  }

  screens.onStart = (config) => startGame(config)
  screens.showMenu()
}

// Wait for the document to be parsed before looking for the canvas. Vite's
// module build defers automatically, but the single-file build inlines a classic
// script that would otherwise run before <body> exists.
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot, { once: true })
} else {
  boot()
}
