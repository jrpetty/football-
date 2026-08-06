import './style.css'
import { SIM } from './config'
import { sfx } from './audio/sfx'
import { InputManager } from './core/input'
import { HumanController } from './control/human'
import { World } from './match/world'
import { Camera } from './render/camera'
import { Renderer } from './render/renderer'
import { Hud } from './ui/hud'
import type { NetInfo } from './ui/hud'
import { DRILL_INFO } from './match/drills'
import { Replay } from './match/replay'
import { HostSession, ClientSession } from './net/session'
import { Lobby } from './net/lobby'
import type { NetHandoff } from './net/lobby'
import { playerName, clientToken } from './net/identity'
import { Screens } from './ui/screens'
import { drawReplayOverlay } from './ui/replayOverlay'
import { Game3D } from './game3d'
import type { Hooks } from './game3d'
import { emptyCommand } from './types'
import type { Command, MatchConfig } from './types'

// Anything boot() can drive, regardless of 2D/3D presentation.
interface RunningGame {
  start(): void
  connect(h: NetHandoff): void
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
    // Your own shirt carries your name from the start, not just once you go
    // online — in training it is the only tag on the pitch.
    this.world.setName(this.world.controlledId, playerName())
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


  // ---- online ----------------------------------------------------------
  //
  // The host runs the simulation exactly as it always did and broadcasts it.
  // A client runs the same simulation locally, predicting its own player from
  // its own input, and is pulled onto the host's version as snapshots arrive —
  // so its own movement never waits for a round trip, and everyone else's is
  // smooth because it's drawn a fraction of a second in the past.
  private host: HostSession | null = null
  private client: ClientSession | null = null

  connect(h: NetHandoff) {
    if (h.role === 'host') {
      this.host = new HostSession(this.world, h.transport)
    } else {
      this.client = new ClientSession(this.world, h.transport, playerName(), clientToken(), h.spectate)
    }
  }

  get spectating(): boolean {
    return !!this.client?.spectator
  }

  private netInfo(): NetInfo | null {
    if (this.host) {
      return { role: 'host', ping: 0, reconnecting: false, spectating: false, peers: this.host.roster() }
    }
    if (this.client) {
      return {
        role: 'guest',
        ping: this.client.ping,
        reconnecting: this.client.reconnecting,
        spectating: this.client.spectator,
      }
    }
    return null
  }

  private netUpdate(dt: number, cmd: Command) {
    this.host?.update(dt)
    this.client?.update(dt, cmd)
  }

  get online(): boolean {
    return !!(this.host || this.client)
  }


  // ---- replay ----------------------------------------------------------
  // Always recording; a goal starts one, R plays back the last few seconds.
  private replay = new Replay()
  private lastGoals = 0

  private replayUpdate(dt: number): boolean {
    if (this.replay.playing) {
      if (!this.replay.update(this.world, dt) || this.input.did('replay') ||
          this.input.justPressed('Escape')) {
        this.replay.stop(this.world)
      }
      return this.replay.playing
    }
    this.replay.record(this.world, dt)
    const goals = this.world.score.home + this.world.score.away
    if (goals !== this.lastGoals) {
      this.lastGoals = goals
      this.replay.start(this.world, 'GOAL')
    } else if (this.input.did('replay')) {
      this.replay.start(this.world, 'REPLAY')
    }
    return this.replay.playing
  }

  stop() {
    this.running = false
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

    if (!this.paused) {
      // A spectator holds no shirt: it steps the world like everyone else and
      // watches, so it never builds a command at all.
      const cmd = this.spectating
        ? emptyCommand()
        : this.human.buildCommand(this.world, this.cam, this.input, dt)
      if (this.replayUpdate(dt)) {
        // The world is being played back rather than simulated, so nothing is
        // stepped and no input is read — just drawn.
      } else {
        this.world.update(dt, cmd)
        this.netUpdate(dt, cmd)
      }
      // A replay is about the ball, not about whoever you happen to be
      // playing, so the camera drops the player half of its focus for it.
      this.cam.follow(this.replay.playing ? this.replay.focus(this.world) : this.cameraFocus(), dt)
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
    if (this.input.mousePressed.left || this.input.mousePressed.right) sfx.unlock()
    if (this.input.did('mute')) sfx.toggleMute()
    if (this.input.did('view')) this.cam.cycleZoom()
    if (this.input.wheel !== 0 && this.input.wheel < 0) this.cam.cycleZoom()

    if (this.input.justPressed('Escape') || this.input.did('pause')) {
      this.paused ? this.resume() : this.pause()
    }

    if (
      this.config.mode === 'training' &&
      this.input.did('spawnBall') &&
      !this.paused
    ) {
      const pos = this.input.mouseInside
        ? this.cam.screenToWorld(this.input.mouse.x, this.input.mouse.y)
        : { x: 29, y: 19 }
      this.world.spawnBallAt(pos)
    }
    // Cycle through the gauntlet. Each drill keeps its own score, so you can go
    // back to one and pick up where you left off.
    if (this.config.mode === 'training' && this.input.did('nextDrill') && !this.paused) {
      const id = this.world.drills?.next()
      if (id) this.world.setAnnounce(DRILL_INFO[id].name, DRILL_INFO[id].brief, 2.2)
    }
  }

  private pause() {
    if (this.config.mode === 'training' ? false : this.world.phase === 'fulltime') return
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
    // A replay is a cutaway, so the live furniture goes with it — no charge
    // meter for a shot you already took, no stamina bar for a run that's over.
    // The 3D view does the same.
    if (this.replay.playing) {
      drawReplayOverlay(this.ctx, this.cssW, this.cssH, this.replay.label, this.replay.progress)
      return
    }
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
        zoomLabel: this.spectating ? 'spectating' : this.cam.mode,
        net: this.netInfo(),
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
  // The controls list rebinds keys, which means it has to hear a raw keypress.
  screens.input = input
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

  let net: NetHandoff | null = null

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
    if (net) game.connect(net)
    // Opt-in inspection hook for automated tests (?debug in the URL).
    if (location.search.includes('debug')) {
      const w = window as unknown as { __world?: unknown; __game?: unknown; __sfx?: unknown }
      w.__world = game.world
      w.__game = game
      w.__sfx = sfx
    }
  }

  // Online. The lobby produces a Transport and a role; everything after that is
  // the ordinary game with some Commands arriving over the wire.
  const lobby = new Lobby()
  lobby.onCancel = () => {
    lobby.dispose()
    screens.showMenu()
  }
  lobby.onReady = (h) => {
    net = h
    startGame({ ...lastConfig, mode: 'match', humanControlled: true } as MatchConfig)
  }
  screens.onOnline = (config) => {
    lastConfig = config
    screens.show()
    lobby.render(screens.root)
  }

  screens.onStart = (config) => {
    net = null
    startGame(config)
  }
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
