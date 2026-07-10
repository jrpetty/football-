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
    this.x = homePos.x
    this.y = homePos.y
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

  // Current speed ceiling given tier + fatigue.
  topSpeed(sprint: boolean): number {
    const base = sprint && this.stamina > 1 ? PLAYER.sprintSpeed : PLAYER.runSpeed
    const fatigue = clamp(this.energy, 0, 1)
    const mult = PLAYER.tiredFactor + (1 - PLAYER.tiredFactor) * fatigue
    return base * mult
  }

  // Steer toward a desired velocity. `moveDir` is a heading (need not be unit);
  // `throttle` 0..1 scales effort. Handles accel/decel, turning momentum, stamina.
  steer(moveDir: Vec2, throttle: number, sprint: boolean, dt: number) {
    this.tackleCooldown = Math.max(0, this.tackleCooldown - dt)
    this.kickCooldown = Math.max(0, this.kickCooldown - dt)
    this.saveCooldown = Math.max(0, this.saveCooldown - dt)

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
    const top = this.topSpeed(canSprint)
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

    // Heading eases toward travel direction (or holds when nearly stopped).
    if (this.speed > 0.4) {
      const targetAngle = Math.atan2(this.vy, this.vx)
      const d = angleDelta(this.heading, targetAngle)
      this.heading += clamp(d, -PLAYER.turnRate * dt, PLAYER.turnRate * dt)
    }

    // Stamina: drains sprinting, recovers otherwise.
    if (canSprint) {
      this.stamina = clamp(this.stamina - PLAYER.sprintDrain * dt, 0, PLAYER.staminaMax)
    } else {
      this.stamina = clamp(this.stamina + PLAYER.staminaRegen * dt, 0, PLAYER.staminaMax)
    }
  }

  // Point the body toward a target instantly-ish (used when aiming a kick).
  faceToward(target: Vec2, dt: number, rate = PLAYER.turnRate * 1.5) {
    const a = Math.atan2(target.y - this.y, target.x - this.x)
    const d = angleDelta(this.heading, a)
    this.heading += clamp(d, -rate * dt, rate * dt)
  }

  integrate(dt: number) {
    this.x += this.vx * dt
    this.y += this.vy * dt
  }

  startSlide(dir: Vec2, speed: number) {
    this.slideTimer = 0.55
    this.slideVel = V.scale(V.normalize(dir), speed)
    this.stamina = clamp(this.stamina - PLAYER.slideDrain, 0, PLAYER.staminaMax)
  }
}
