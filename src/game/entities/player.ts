import { PLAYER } from '../config'
import { clamp, clamp01, angleDelta } from '../core/math'
import * as V from '../core/vec'
import type { Vec2 } from '../core/vec'
import type { Role, Team } from '../types'

// A single footballer. Movement is momentum-based: you steer a target velocity
// and the body accelerates/decelerates toward it, so sharp reversals cost you a
// beat — decisions matter more than twitch. Stamina gates top speed.
export class Player {
  x = 0
  y = 0
  vx = 0
  vy = 0
  heading = 0 // facing angle (rad); slews toward travel/aim
  stamina = PLAYER.staminaMax

  slideTimer = 0 // >0 while grounded & exposed after a slide tackle
  tackleCooldown = 0 // debounce so one tap = one challenge
  kickCooldown = 0 // brief lockout after releasing the ball
  saveCooldown = 0 // keeper: debounce so one stop counts as one save
  slideVel: Vec2 = { x: 0, y: 0 } // carried momentum during a slide
  sprinting = false // set each tick by steer(); read by the dribble/first-touch model

  // Kick animation state: counts down while the striking leg swings through.
  kickTimer = 0
  kickLeg = 0 // which leg is striking (0 or 1)

  // Previous-step position, so the renderer can interpolate between physics
  // steps instead of snapping once per step.
  px = 0
  py = 0

  // AI scratch state (ignored for human-controlled players).
  aiDecide = 0 // countdown before a ball-carrier reconsiders pass/shoot
  aiSeed = Math.random() // per-player variation so runs/decisions aren't identical

  readonly radius = PLAYER.radius

  constructor(
    public id: number,
    public team: Team,
    public role: Role,
    public number: number,
    public homePos: Vec2,
    public isHuman = false,
  ) {
    this.x = this.px = homePos.x
    this.y = this.py = homePos.y
    this.heading = team === 'home' ? 0 : Math.PI
  }

  get pos(): Vec2 {
    return { x: this.x, y: this.y }
  }

  get vel(): Vec2 {
    return { x: this.vx, y: this.vy }
  }

  get speed(): number {
    return Math.hypot(this.vx, this.vy)
  }

  get sliding(): boolean {
    return this.slideTimer > 0
  }

  get facing(): Vec2 {
    return { x: Math.cos(this.heading), y: Math.sin(this.heading) }
  }

  // Fraction of full stamina remaining, 0..1.
  get energy(): number {
    return clamp01(this.stamina / PLAYER.staminaMax)
  }

  // Current speed ceiling given tier + fatigue. Three gears: a controlled walk
  // for setting your feet, a default running pace, and a sprint that costs you.
  topSpeed(sprint: boolean, walk = false): number {
    const base =
      sprint && this.stamina > 1
        ? PLAYER.sprintSpeed
        : walk
          ? PLAYER.walkSpeed
          : PLAYER.runSpeed
    const fatigue = clamp(this.energy, 0, 1)
    const mult = PLAYER.tiredFactor + (1 - PLAYER.tiredFactor) * fatigue
    return base * mult
  }

  // Steer toward a desired velocity. `moveDir` is a heading (need not be unit);
  // `throttle` 0..1 scales effort. Handles accel/decel, turning momentum, stamina.
  steer(moveDir: Vec2, throttle: number, sprint: boolean, dt: number, walk = false) {
    this.tackleCooldown = Math.max(0, this.tackleCooldown - dt)
    this.kickCooldown = Math.max(0, this.kickCooldown - dt)
    this.saveCooldown = Math.max(0, this.saveCooldown - dt)
    this.kickTimer = Math.max(0, this.kickTimer - dt)

    if (this.sliding) {
      // While sliding the player is committed: coast on carried momentum and decay.
      this.slideTimer -= dt
      this.vx = this.slideVel.x
      this.vy = this.slideVel.y
      const decay = Math.max(0, 1 - 6 * dt)
      this.slideVel = V.scale(this.slideVel, decay)
      this.stamina = clamp(this.stamina - 2 * dt, 0, PLAYER.staminaMax)
      return
    }

    const dirLen = V.len(moveDir)
    const wantsMove = dirLen > 0.01 && throttle > 0.01
    const canSprint = sprint && this.stamina > 1 && wantsMove
    this.sprinting = canSprint
    const top = this.topSpeed(canSprint, walk)
    const target: Vec2 = wantsMove
      ? V.scale(V.normalize(moveDir), top * clamp01(throttle))
      : { x: 0, y: 0 }

    const dvx = target.x - this.vx
    const dvy = target.y - this.vy
    const dl = Math.hypot(dvx, dvy)
    if (dl > 1e-4) {
      const rate = wantsMove ? PLAYER.accel : PLAYER.decel
      const step = Math.min(dl, rate * dt)
      this.vx += (dvx / dl) * step
      this.vy += (dvy / dl) * step
    }

    // Stamina: drains sprinting, recovers otherwise.
    if (canSprint) {
      this.stamina = clamp(this.stamina - PLAYER.sprintDrain * dt, 0, PLAYER.staminaMax)
    } else {
      this.stamina = clamp(this.stamina + PLAYER.staminaRegen * dt, 0, PLAYER.staminaMax)
    }
  }

  // Turn the body toward a direction, at a limited rate so a change of mind
  // reads as a turn rather than an instant snap. A player pivots faster on the
  // spot than at a sprint, which is both true and stops fast runs looking twitchy.
  faceDirection(dir: Vec2, dt: number) {
    if (V.len(dir) < 0.01) return
    const target = Math.atan2(dir.y, dir.x)
    const d = angleDelta(this.heading, target)
    const pace = 1 - clamp01(this.speed / PLAYER.sprintSpeed) * 0.45
    const rate = PLAYER.turnRate * pace * dt
    this.heading += clamp(d, -rate, rate)
  }

  // Point the body toward a world position (used when aiming a kick).
  faceToward(target: Vec2, dt: number, rate = PLAYER.turnRate * 1.5) {
    const a = Math.atan2(target.y - this.y, target.x - this.x)
    const d = angleDelta(this.heading, a)
    this.heading += clamp(d, -rate * dt, rate * dt)
  }

  integrate(dt: number) {
    this.px = this.x
    this.py = this.y
    this.x += this.vx * dt
    this.y += this.vy * dt
  }

  // Position to draw at, interpolated between the last two physics steps.
  renderPos(alpha: number): Vec2 {
    const a = alpha < 0 ? 0 : alpha > 1 ? 1 : alpha
    return { x: this.px + (this.x - this.px) * a, y: this.py + (this.y - this.py) * a }
  }

  startSlide(dir: Vec2, speed: number) {
    this.slideTimer = 0.55
    this.slideVel = V.scale(V.normalize(dir), speed)
    this.stamina = clamp(this.stamina - PLAYER.slideDrain, 0, PLAYER.staminaMax)
  }
}
