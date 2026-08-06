import { SIM } from './config'
import { sfx } from './audio/sfx'
import { InputManager } from './core/input'
import { Human3DController } from './control/human3d'
import { World } from './match/world'
import { Camera3D } from './render3d/camera3d'
import { Scene3D } from './render3d/scene'
import { Hud } from './ui/hud'
import type { NetInfo } from './ui/hud'
import { DRILL_INFO } from './match/drills'
import { Screens } from './ui/screens'
import { drawReplayOverlay } from './ui/replayOverlay'
import { Replay } from './match/replay'
import { HostSession, ClientSession } from './net/session'
import type { NetHandoff } from './net/lobby'
import { playerName, clientToken } from './net/identity'
import { emptyCommand } from './types'
import type { Command, MatchConfig } from './types'

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
    // Your own shirt carries your name from the start, not just once you go
    // online — in training it is the only tag on the pitch.
    this.world.setName(this.world.controlledId, playerName())
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

  private netUpdate(dt: number, cmd: Command) {
    this.host?.update(dt)
    this.client?.update(dt, cmd)
  }

  get online(): boolean {
    return !!(this.host || this.client)
  }

  // Watching rather than playing: no shirt, no input, a camera that goes where
  // you point it.
  get spectating(): boolean {
    return !!this.client?.spectator
  }

  // What a spectator is watching. The ball by default, because that is where
  // football happens; left/right walk through the players so you can follow one.
  private spectateOn = -1

  private spectateKeys() {
    const ids = this.world.players.map((p) => p.id)
    if (this.input.did('left') || this.input.did('right')) {
      const dir = this.input.did('right') ? 1 : -1
      const i = ids.indexOf(this.spectateOn)
      // −1 is the ball, and it sits at one end of the ring rather than being
      // skipped: pressing back past the first player returns you to it.
      const next = i < 0 ? (dir > 0 ? 0 : ids.length - 1) : i + dir
      this.spectateOn = next < 0 || next >= ids.length ? -1 : ids[next]
    }
    if (this.input.did('up')) this.spectateOn = -1
    if (this.input.did('sprint')) this.cam3.spectateDist = this.cam3.spectateDist > 10 ? 7 : 22
  }

  private spectateFocus(): { x: number; y: number; z: number } {
    const p = this.spectateOn >= 0 ? this.world.player(this.spectateOn) : null
    if (p) return { x: p.x, y: p.y, z: p.z + 1 }
    const b = this.world.ball
    return { x: b.x, y: b.y, z: b.z }
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


  // ---- replay ----------------------------------------------------------
  //
  // Always recording, because you cannot know a shot was worth watching until
  // it has already gone in. A goal starts one automatically; R plays back the
  // last few seconds whenever you like.
  private replay = new Replay()
  private replayAngle = 0
  private lastGoals = 0

  private replayUpdate(dt: number): boolean {
    if (this.replay.playing) {
      this.replayAngle += dt * 0.55
      if (!this.replay.update(this.world, dt) || this.input.did('replay') ||
          this.input.justPressed('Escape')) {
        this.replay.stop(this.world)
      }
      return true
    }
    this.replay.record(this.world, dt)
    const goals = this.world.score.home + this.world.score.away
    if (goals !== this.lastGoals) {
      this.lastGoals = goals
      this.replayAngle = 0
      this.replay.start(this.world, 'GOAL')
    } else if (this.input.did('replay')) {
      this.replayAngle = 0
      this.replay.start(this.world, 'REPLAY')
    }
    return this.replay.playing
  }

  get replaying(): boolean {
    return this.replay.playing
  }
  get replayInfo() {
    return { label: this.replay.label, progress: this.replay.progress }
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
    // Browsers only allow audio to start from a gesture, so the same click that
    // grabs the pointer is what brings the stadium to life.
    sfx.unlock()
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
    if (!this.paused && this.replayUpdate(dt)) {
      // Orbit the ball while the replay runs: the whole reason it exists is
      // that from behind your own player you never see the thing you just did.
      const f = this.replay.focus(this.world)
      this.cam3.orbit(f.x, f.y, f.z, this.replayAngle)
      this.scene.sync(this.world, -1, false, -1, dt, true)
      this.scene.render(this.cam3.cam)
      this.drawReplayOverlay()
      return
    }
    if (!this.paused) {
      const cp = this.world.getControlledPlayer()
      if (this.spectating) {
        // A spectator holds no shirt, so it never builds a command — it just
        // steps the world like everyone else and points a camera at it.
        if (locked) this.cam3.look(this.input.movementX, this.input.movementY)
        this.spectateKeys()
        this.world.update(dt, emptyCommand())
        this.netUpdate(dt, emptyCommand())
        const f = this.spectateFocus()
        this.cam3.spectate(f.x, f.y, f.z, dt)
      } else if (locked) {
        this.cam3.look(this.input.movementX, this.input.movementY)
        const cmd = this.human.buildCommand(this.world, this.cam3, this.input, dt)
        this.world.update(dt, cmd)
        this.netUpdate(dt, cmd)
        if (cp) this.cam3.update(cp.x, cp.y, dt)
      } else {
        // Nobody is driving: the world still steps — the ball keeps rolling and
        // anyone else on the pitch is a person on their own machine — but your
        // player stands still until you click to take the pointer.
        this.world.update(dt, emptyCommand())
        this.netUpdate(dt, emptyCommand())
        if (cp) this.cam3.update(cp.x, cp.y, dt)
      }
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
    if (this.input.did('view')) this.cam3.toggle()
    if (this.input.did('mute')) sfx.toggleMute()
    if (this.input.did('pause')) this.paused ? this.resume() : this.pause()
    if (this.config.mode === 'training' && this.input.did('spawnBall') && !this.paused) {
      const cp = this.world.getControlledPlayer()
      const aim = this.cam3.aimSim()
      const pos = cp ? { x: cp.x + aim.x * 4, y: cp.y + aim.y * 4 } : { x: 29, y: 19 }
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

  private drawReplayOverlay() {
    const { label, progress } = this.replayInfo
    this.ctx.clearRect(0, 0, this.cssW, this.cssH)
    drawReplayOverlay(this.ctx, this.cssW, this.cssH, label, progress)
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
        zoomLabel: this.spectating ? 'spectating' : this.cam3.mode === 'first' ? '1st person' : '3rd person',
        net: this.netInfo(),
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
