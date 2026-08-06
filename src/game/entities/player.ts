import { DEFEND, GK, PLAYER, SHIELD } from '../config'
import { clamp, clamp01, angleDelta } from '../core/math'
import * as V from '../core/vec'
import type { Vec2 } from '../core/vec'
import type { Role, Team } from '../types'

// Which movement the renderer should play for a contact. 'touch' and 'strike'
// map one-to-one onto the two mouse buttons, which is the whole point: what you
// see should tell you which button was pressed and how hard.
export type KickAnim = 'touch' | 'strike' | 'header' | 'cushion'
// Index order for the wire — a contact travels as one byte, not a string.
export const KICK_ANIMS: KickAnim[] = ['touch', 'strike', 'header', 'cushion']

// A single footballer. Movement is momentum-based: you steer a target velocity
// and the body accelerates/decelerates toward it, so sharp reversals cost you a
// beat — decisions matter more than twitch. Stamina gates top speed.
export class Player {
  x = 0
  y = 0
  // Height off the turf. Zero almost all the time — a footballer is a
  // two-dimensional problem until the moment they leave the ground, and then
  // everything they can reach moves up with them.
  z = 0
  vx = 0
  vy = 0
  vz = 0
  heading = 0 // facing angle (rad); slews toward travel/aim
  stamina = PLAYER.staminaMax

  slideTimer = 0 // >0 while actually sliding along the ground
  slideRecover = 0 // >0 while getting back up: beaten, and out of the play
  slideWon = false // this slide has already made contact with the ball
  tackleCooldown = 0 // debounce so one tap = one slide
  // Debounce for the tackles stat only. Winning the ball is now just touching it
  // while someone else had it, and a contested ball gets touched many times a
  // second — without this, one scramble reads as forty tackles.
  tackleCredit = 0
  kickCooldown = 0 // brief lockout after releasing the ball
  saveCooldown = 0 // keeper: debounce so one stop counts as one save
  slideVel: Vec2 = { x: 0, y: 0 } // carried momentum during a slide
  // Keeper dive: the same commitment as a slide, but with hands and a height.
  diveTimer = 0
  diveRecover = 0
  diveWon = false
  diveVel: Vec2 = { x: 0, y: 0 }
  diveHeight = 1.2 // how high this particular dive reaches
  diveFrom: Vec2 = { x: 0, y: 0 } // where it launched, so the body is a swept line
  sprinting = false // set each tick by steer(); read by the dribble/first-touch model
  shielding = false // holding the ball off with your body: side-on, slow, no strike

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
  pz = 0
  landTimer = 0 // >0 just after landing: gathering yourself before the next one
  jumpCooldown = 0

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

  get airborne(): boolean {
    // Any vertical motion counts, not just upward. Testing `vz > 0` would call
    // a player who is falling and within the last two centimetres "grounded" —
    // so they would never run the landing branch and would simply stop, hanging
    // there. Exactly the bug the ball had at the bottom of a bounce.
    return this.z > 0.02 || Math.abs(this.vz) > 0.02
  }

  // Can you leave the ground right now? Not mid-air, not off the floor, and not
  // in the instant after landing.
  get canJump(): boolean {
    if (this.airborne || this.sliding || this.diving) return false
    if (this.slideRecover > 0 || this.diveRecover > 0) return false
    return this.landTimer <= 0 && this.jumpCooldown <= 0
  }

  jump() {
    this.vz = PLAYER.jumpSpeed + this.speed * PLAYER.jumpRunBonus
    this.jumpCooldown = PLAYER.jumpCooldown
    this.stamina = clamp(this.stamina - 3, 0, PLAYER.staminaMax)
  }

  get diving(): boolean {
    return this.diveTimer > 0
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
    const base = this.shielding
      ? SHIELD.speed
      : sprint && this.stamina > 1
        ? PLAYER.sprintSpeed
        : walk
          ? PLAYER.walkSpeed
          : PLAYER.runSpeed
    const fatigue = clamp(this.energy, 0, 1)
    const mult = PLAYER.tiredFactor + (1 - PLAYER.tiredFactor) * fatigue
    // Getting back up off the floor. This is the whole cost of a slide: commit
    // and miss, and the player you dived at is gone before you're upright.
    const up = this.slideRecover > 0 ? clamp01(1 - this.slideRecover / DEFEND.slideRecovery) : 1
    return base * mult * (0.25 + 0.75 * up)
  }

  // Steer toward a desired velocity. `moveDir` is a heading (need not be unit);
  // `throttle` 0..1 scales effort. Handles accel/decel, turning momentum, stamina.
  steer(moveDir: Vec2, throttle: number, sprint: boolean, dt: number, walk = false) {
    this.tackleCooldown = Math.max(0, this.tackleCooldown - dt)
    this.tackleCredit = Math.max(0, this.tackleCredit - dt)
    this.kickCooldown = Math.max(0, this.kickCooldown - dt)
    this.saveCooldown = Math.max(0, this.saveCooldown - dt)
    this.kickTimer = Math.max(0, this.kickTimer - dt)
    this.landTimer = Math.max(0, this.landTimer - dt)
    this.jumpCooldown = Math.max(0, this.jumpCooldown - dt)

    if (this.diving) {
      // A dive is fully committed: you go where you launched yourself and
      // nothing you press changes it until you land.
      this.diveTimer -= dt
      this.vx = this.diveVel.x
      this.vy = this.diveVel.y
      this.diveVel = V.scale(this.diveVel, Math.max(0, 1 - 3.5 * dt))
      return
    }
    this.diveRecover = Math.max(0, this.diveRecover - dt)

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

    if (this.airborne) {
      // Nothing to push against. Whatever you left the ground with is what you
      // land with, which is why a jump is a commitment rather than a dodge.
      this.stamina = clamp(this.stamina + PLAYER.staminaRegen * 0.5 * dt, 0, PLAYER.staminaMax)
      return
    }

    const dirLen = V.len(moveDir)
    const wantsMove = dirLen > 0.01 && throttle > 0.01
    const canSprint = sprint && this.stamina > 1 && wantsMove && !this.shielding
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
    this.pz = this.z
    this.x += this.vx * dt
    this.y += this.vy * dt
    if (this.airborne) {
      this.vz -= PLAYER.gravity * dt
      this.z += this.vz * dt
      if (this.z <= 0) {
        this.z = 0
        this.vz = 0
        this.landTimer = PLAYER.landRecovery
      }
    }
  }

  // Position to draw at, interpolated between the last two physics steps.
  renderPos(alpha: number): { x: number; y: number; z: number } {
    const a = alpha < 0 ? 0 : alpha > 1 ? 1 : alpha
    return {
      x: this.px + (this.x - this.px) * a,
      y: this.py + (this.y - this.py) * a,
      z: this.pz + (this.z - this.pz) * a,
    }
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

  // Launch a dive. `power` 0..1 is how far you commit; `height` -1..1 comes
  // from the flick and picks a low dive at your feet or a full stretch.
  startDive(dir: Vec2, power: number, height: number) {
    const p = clamp01(power)
    this.diveTimer = GK.diveTimeMin + (GK.diveTimeMax - GK.diveTimeMin) * p
    this.diveRecover = GK.diveRecovery * (0.5 + 0.5 * p)
    this.diveWon = false
    this.diveVel = V.scale(V.normalize(dir), GK.diveSpeedMin + (GK.diveSpeedMax - GK.diveSpeedMin) * p)
    const h = clamp(height, -1, 1)
    this.diveHeight = GK.diveHeightLow + ((h + 1) / 2) * (GK.diveHeightHigh - GK.diveHeightLow)
    this.diveFrom = { x: this.x, y: this.y }
    this.stamina = clamp(this.stamina - 6, 0, PLAYER.staminaMax)
  }

  startSlide(dir: Vec2, speed: number) {
    this.slideTimer = DEFEND.slideTime
    this.slideRecover = DEFEND.slideRecovery
    this.slideWon = false
    this.slideVel = V.scale(V.normalize(dir), speed)
    this.stamina = clamp(this.stamina - PLAYER.slideDrain, 0, PLAYER.staminaMax)
  }
}
