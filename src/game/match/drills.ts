import { FIELD, SIM } from '../config'
import type { World } from './world'
import * as F from './field'

export interface Target {
  x: number // on the goal line
  y: number
  z: number
  radius: number
  hit: boolean
  hitAt: number // seconds since it was struck, for the fade-out
}

const MAX_TRAIL = 400

export interface TrailPoint {
  x: number
  y: number
  z: number
}

// Solo practice apparatus for the training pitch: targets hung in the goal, a
// running accuracy tally, and a recording of the last strike's flight.
//
// The trail is the important one. Height and curve come from a mouse flick, and
// without seeing the shape of the ball you just hit it is very hard to learn
// what a given flick actually does — this draws the path so the feedback loop
// closes.
export class Drills {
  targets: Target[] = []
  trail: TrailPoint[] = []
  trailAge = 0

  shots = 0
  hits = 0
  best = 0
  streak = 0

  private recording = false
  private registeredThisShot = false
  private sampleTimer = 0
  private lastBallSpeed = 0

  constructor() {
    this.resetTargets()
  }

  // Four targets in the corners of the goal a right-footed player would aim at,
  // plus the two bottom corners which are the hardest and most useful to drill.
  resetTargets() {
    const [a, b] = F.goalPostYs()
    const inset = 0.85
    const gx = FIELD.length // the goal you attack in training
    const high = FIELD.goalHeight - 0.55
    const low = 0.5
    this.targets = [
      { x: gx, y: a + inset, z: high, radius: 0.55, hit: false, hitAt: 0 },
      { x: gx, y: b - inset, z: high, radius: 0.55, hit: false, hitAt: 0 },
      { x: gx, y: a + inset, z: low, radius: 0.55, hit: false, hitAt: 0 },
      { x: gx, y: b - inset, z: low, radius: 0.55, hit: false, hitAt: 0 },
    ]
  }

  // Called once per *physics* step. Target detection has to live here rather
  // than once a frame: a struck ball covers a third of a metre per step, so a
  // frame-rate check would sail straight through the target without noticing.
  step(world: World) {
    const ball = world.ball

    // A sudden jump in speed means the ball has just been struck away.
    const sp = ball.speed
    if (sp > this.lastBallSpeed + 2.5 && sp > 6) {
      this.recording = true
      this.trail = []
      this.trailAge = 0
      this.shots++
      this.registeredThisShot = false
    }
    this.lastBallSpeed = sp

    if (this.recording) {
      this.sampleTimer += SIM.dt
      if (this.sampleTimer >= 0.02) {
        this.sampleTimer = 0
        this.trail.push({ x: ball.x, y: ball.y, z: ball.z })
        if (this.trail.length > MAX_TRAIL) this.trail.shift()
      }
      if (sp < 1.5 || world.possessorId !== null) this.recording = false
    }

    // The ball is repositioned instantly after a goal; that jump isn't travel,
    // so ignore any step where it moved further than it possibly could have.
    const stepDist = Math.hypot(ball.x - ball.px, ball.y - ball.py, ball.z - ball.pz)
    if (stepDist > 1) return

    for (const t of this.targets) {
      if (t.hit || this.registeredThisShot) continue
      // Did this step carry the ball through the plane the targets hang in?
      const before = ball.px - t.x
      const after = ball.x - t.x
      if (before >= 0 || after < 0) continue
      const f = before === after ? 0 : before / (before - after)
      const cy = ball.py + (ball.y - ball.py) * f
      const cz = ball.pz + (ball.z - ball.pz) * f
      if (Math.hypot(cy - t.y, cz - t.z) < t.radius) {
        t.hit = true
        t.hitAt = 0
        this.hits++
        this.streak++
        this.best = Math.max(this.best, this.streak)
        this.registeredThisShot = true
      }
    }
  }

  // Per-frame housekeeping: fading out hit markers and ageing the trail.
  update(_world: World, dt: number) {
    for (const t of this.targets) {
      if (!t.hit) continue
      t.hitAt += dt
      if (t.hitAt > 1.2) {
        t.hit = false
        t.hitAt = 0
      }
    }
    if (!this.recording && this.trail.length) {
      this.trailAge += dt
      if (this.trailAge > 8) this.trail = []
    }
  }

  // Called when a shot clearly missed everything, to break the streak.
  missed() {
    this.streak = 0
  }

  get accuracy(): number {
    return this.shots ? this.hits / this.shots : 0
  }

  reset() {
    this.shots = 0
    this.hits = 0
    this.streak = 0
    this.best = 0
    this.trail = []
    this.resetTargets()
  }
}
