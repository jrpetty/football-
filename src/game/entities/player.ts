import { PLAYER } from '../config'
import { clamp, clamp01, angleDelta } from '../core/math'
import * as V from '../core/vec'
import type { Vec2 } from '../core/vec'
import type { Role, Team } from '../types'

// Which movement the renderer should play for a contact. 'touch' and 'strike'
// map one-to-one onto the two mouse buttons, which is the whole point: what you
// see should tell you which button was pressed and how hard.
export type KickAnim = 'touch' | 'strike' | 'header' | 'cushion'

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

  // Kick animation state: counts down while the contact plays out.
  kickTimer = 0
  kickLeg = 0 // which leg is striking (0 or 1)
  // What the contact was, so the renderer prods with the instep for a touch,
  // swings through for a strike, arches the back for a header, and reaches up
  // to take the pace off for a cushion.
  kickKind: KickAnim = 'strike'
  // How hard, 0..1. The strike animation is a continuous scale from a pushed
  // pass to a full swing, exactly like the kick model it's showing.
  kickPower = 0
  // The duration this particular contact was given, so the renderer can turn
  // the countdown back into 0..1 progress whatever the length.
  kickAnimLen = PLAYER.strikeAnimMax

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

    this.applyMomentum(target, top, dt)

    // Stamina: drains sprinting, recovers otherwise.
    if (canSprint) {
      this.stamina = clamp(this.stamina - PLAYER.sprintDrain * dt, 0, PLAYER.staminaMax)
    } else {
      this.stamina = clamp(this.stamina + PLAYER.staminaRegen * dt, 0, PLAYER.staminaMax)
    }
  }

  // Move the current velocity toward the target one under three separate
  // budgets, because a running body cannot change direction as freely as it can
  // change pace. The change needed is split into the part along the way you're
  // already travelling and the part across it:
  //
  //   along, positive — you're asking for more pace. Hard from a standstill,
  //     almost impossible near your ceiling, which is what gives a run its
  //     wind-up instead of snapping to full speed in a frame.
  //   along, negative — you're braking. A bigger budget: you can plant.
  //   across          — you're turning. Bounded, so a sprint arcs rather than
  //     pivoting on the spot, and a full reversal has to go through a stop.
  private applyMomentum(target: Vec2, top: number, dt: number) {
    const dvx = target.x - this.vx
    const dvy = target.y - this.vy
    if (Math.hypot(dvx, dvy) < 1e-4) return

    const speed = this.speed
    // With no pace of your own there is no "across" — every direction is ahead.
    const u =
      speed > 0.05
        ? { x: this.vx / speed, y: this.vy / speed }
        : V.normalize({ x: dvx, y: dvy })

    const along = dvx * u.x + dvy * u.y
    const acrossX = dvx - along * u.x
    const acrossY = dvy - along * u.y
    const across = Math.hypot(acrossX, acrossY)

    // Effort left for going faster, shrinking as you approach your ceiling.
    const headroom = clamp01(1 - (speed / Math.max(top, 0.01)) ** 2)
    const alongRate = along > 0 ? PLAYER.accel * headroom : PLAYER.brake
    const alongStep = Math.min(Math.abs(along), alongRate * dt) * Math.sign(along)
    const acrossStep = Math.min(across, PLAYER.lateral * dt)

    this.vx += u.x * alongStep
    this.vy += u.y * alongStep
    if (across > 1e-4) {
      this.vx += (acrossX / across) * acrossStep
      this.vy += (acrossY / across) * acrossStep
    }

    // Don't let the combined push carry you past your own ceiling — but only
    // clamp pace this step actually added. Letting go of sprint lowers the
    // ceiling, and clamping to it there would strip a metre per second out of a
    // running player instantly, which reads as hitting an invisible wall.
    const s = this.speed
    if (s > top && speed <= top) {
      this.vx = (this.vx / s) * top
      this.vy = (this.vy / s) * top
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

  // Begin a contact animation. The length is part of the read: a tap is over in
  // a fifth of a second, a full strike takes more than twice that, so the swing
  // itself tells you how much was put into the ball.
  startKick(kind: KickAnim, power = 0) {
    this.kickKind = kind
    this.kickPower = clamp01(power)
    this.kickAnimLen =
      kind === 'touch'
        ? PLAYER.touchAnimTime
        : kind === 'header'
          ? PLAYER.headerAnimTime
          : kind === 'cushion'
            ? PLAYER.cushionAnimTime
            : PLAYER.strikeAnimMin +
              (PLAYER.strikeAnimMax - PLAYER.strikeAnimMin) * this.kickPower
    this.kickTimer = this.kickAnimLen
  }

  startSlide(dir: Vec2, speed: number) {
    this.slideTimer = 0.55
    this.slideVel = V.scale(V.normalize(dir), speed)
    this.stamina = clamp(this.stamina - PLAYER.slideDrain, 0, PLAYER.staminaMax)
  }
}
