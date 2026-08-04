import { BALL, SIM } from '../config'
import { clamp } from '../core/math'
import type { Vec2 } from '../core/vec'
import type { Team } from '../types'

// The ball is the star of the show. It lives in 3D: (x, y) on the pitch plane
// plus a height z. Velocity is likewise 3D. `spin` is scalar side-spin that the
// Magnus term turns into a curved flight path — positive and negative spin bend
// opposite ways. Everything else (gravity, drag, rolling friction, bounces) is
// standard integration tuned in config.ts to feel right rather than be exact.
export class Ball {
  x = 0
  y = 0
  z = 0
  vx = 0
  vy = 0
  vz = 0
  spin = 0

  // Who last touched it — drives throw-in / corner / goal-kick awards and stats.
  lastTouchTeam: Team | null = null
  lastTouchId: number | null = null

  // Bookkeeping the sim reads each tick.
  justBounced = false
  airborne = false

  get pos(): Vec2 {
    return { x: this.x, y: this.y }
  }

  get horizontalSpeed(): number {
    return Math.hypot(this.vx, this.vy)
  }

  get speed(): number {
    return Math.hypot(this.vx, this.vy, this.vz)
  }

  setPos(x: number, y: number, z = 0) {
    this.x = x
    this.y = y
    this.z = z
  }

  stop() {
    this.vx = this.vy = this.vz = 0
    this.spin = 0
    this.z = 0
  }

  // Launch the ball. `vel` is the horizontal release velocity; `vz` the vertical
  // component (0 for a ground ball). `spin` bends the flight.
  launch(vx: number, vy: number, vz: number, spin: number, team: Team, id: number) {
    this.vx = vx
    this.vy = vy
    this.vz = vz
    this.spin = spin
    this.lastTouchTeam = team
    this.lastTouchId = id
  }

  integrate() {
    const dt = SIM.dt
    this.justBounced = false
    this.airborne = this.z > 0.02 || this.vz > 0.02

    if (this.airborne) {
      // Gravity.
      this.vz -= SIM.gravity * dt

      // Air drag on the full 3D velocity (linear damping — stable and cheap).
      const drag = 1 - BALL.airDrag * dt
      this.vx *= drag
      this.vy *= drag
      this.vz *= drag

      // Magnus: side-spin pushes the ball perpendicular to its horizontal travel.
      this.applyMagnus(dt, 1)
    } else {
      // Rolling on the turf: friction bleeds speed; spin still bends it, though
      // the ground scrubs some of the effect compared with a ball in flight.
      const hs = this.horizontalSpeed
      if (hs > 0) {
        const decel = BALL.groundFriction * dt
        const factor = Math.max(0, 1 - decel / Math.max(hs, 0.001))
        this.vx *= factor
        this.vy *= factor
      }
      this.applyMagnus(dt, 0.62)
      this.z = 0
      this.vz = 0
    }

    // Spin bleeds off over time regardless.
    this.spin -= this.spin * BALL.spinDecay * dt

    // Integrate position.
    this.x += this.vx * dt
    this.y += this.vy * dt
    this.z += this.vz * dt

    // Ground contact → bounce.
    if (this.z < 0) {
      this.z = 0
      if (this.vz < 0) {
        this.vz = -this.vz * BALL.restitution
        // Each bounce scrubs a little horizontal pace and spin.
        this.vx *= 0.86
        this.vy *= 0.86
        this.spin *= 0.7
        this.justBounced = Math.abs(this.vz) > 1.2
        if (this.vz < 1.0) this.vz = 0 // settle low bounces into a roll
      }
    }

    // Speed cap keeps mishits sane.
    const sp = this.speed
    if (sp > BALL.maxSpeed) {
      const s = BALL.maxSpeed / sp
      this.vx *= s
      this.vy *= s
      this.vz *= s
    }

    // Kill microscopic rolling so the ball can actually come to rest.
    if (!this.airborne && this.horizontalSpeed < BALL.settleSpeed) {
      this.vx *= 0.9
      this.vy *= 0.9
      if (this.horizontalSpeed < 0.06) {
        this.vx = 0
        this.vy = 0
      }
    }
  }

  private applyMagnus(dt: number, gain: number) {
    const hs = this.horizontalSpeed
    if (hs < 0.2 || Math.abs(this.spin) < 0.01) return
    // Left-hand perpendicular to travel direction.
    const px = -this.vy / hs
    const py = this.vx / hs
    const acc = BALL.magnus * this.spin * gain
    this.vx += px * acc * dt
    this.vy += py * acc * dt
  }

  // Reflect off a vertical surface (goal post / wall) given a surface normal.
  reflect(nx: number, ny: number, restitution: number) {
    const d = this.vx * nx + this.vy * ny
    if (d >= 0) return
    this.vx -= (1 + restitution) * d * nx
    this.vy -= (1 + restitution) * d * ny
    this.spin *= 0.6
  }

  clampSpin() {
    this.spin = clamp(this.spin, -BALL.maxSpeed, BALL.maxSpeed)
  }
}
