import { BALL, SIM } from '../config'
import { clamp } from '../core/math'
import type { Vec2 } from '../core/vec'
import type { Team } from '../types'

// The ball. It lives in 3D: (x, y) on the pitch plane plus a height z, with a
// matching 3D velocity and a scalar side-spin that bends the flight.
//
// The model is deliberately grounded in real numbers rather than arbitrary
// damping: a size-5 ball (0.11 m radius, 0.43 kg), real gravity, and *quadratic*
// aerodynamic drag — force proportional to v², which is what actually governs a
// football. That's what makes a driven ball hold its line and a floated one die
// into its landing, instead of everything decaying at the same rate.
//
// Positions from the previous physics step are kept so the renderer can
// interpolate between steps. Without that the ball visibly jumps at low frame
// rates even though the simulation is perfectly smooth.
export class Ball {
  x = 0
  y = 0
  z = 0
  vx = 0
  vy = 0
  vz = 0
  spin = 0

  // Previous-step position, for render interpolation.
  px = 0
  py = 0
  pz = 0

  // Who last touched it — drives restarts and stats.
  lastTouchTeam: Team | null = null
  lastTouchId: number | null = null

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
    this.x = this.px = x
    this.y = this.py = y
    this.z = this.pz = z
  }

  stop() {
    this.vx = this.vy = this.vz = 0
    this.spin = 0
    this.z = 0
    this.pz = 0
  }

  launch(vx: number, vy: number, vz: number, spin: number, team: Team, id: number) {
    this.vx = vx
    this.vy = vy
    this.vz = vz
    this.spin = spin
    this.lastTouchTeam = team
    this.lastTouchId = id
  }

  // Render position, interpolated between the last two physics steps.
  renderPos(alpha: number): { x: number; y: number; z: number } {
    const a = clamp(alpha, 0, 1)
    return {
      x: this.px + (this.x - this.px) * a,
      y: this.py + (this.y - this.py) * a,
      z: this.pz + (this.z - this.pz) * a,
    }
  }

  integrate() {
    const dt = SIM.dt
    this.px = this.x
    this.py = this.y
    this.pz = this.z
    this.justBounced = false
    this.airborne = this.z > 0.015 || this.vz > 0.02

    const speed = this.speed
    if (speed > 0.01) {
      // Quadratic aerodynamic drag: a = -k |v| v, acting on the full 3D
      // velocity. k folds together air density, drag coefficient, frontal area
      // and mass, so it is a single tunable with real physical meaning.
      const decel = BALL.dragK * speed * dt
      const f = Math.max(0, 1 - decel)
      this.vx *= f
      this.vy *= f
      this.vz *= f
    }

    if (this.airborne) {
      this.vz -= SIM.gravity * dt
      this.applyMagnus(dt, 1)
    } else {
      // Rolling on turf: rolling resistance is a near-constant deceleration,
      // independent of speed, which is why a rolled pass carries a long way and
      // then dies quickly at the end.
      const hs = this.horizontalSpeed
      if (hs > 0) {
        const drop = BALL.rollFriction * dt
        const f = Math.max(0, 1 - drop / Math.max(hs, 1e-4))
        this.vx *= f
        this.vy *= f
      }
      // A rolling ball still bends, but the turf scrubs much of the effect.
      this.applyMagnus(dt, BALL.groundMagnus)
      this.z = 0
      this.vz = 0
    }

    this.spin -= this.spin * BALL.spinDecay * dt

    this.x += this.vx * dt
    this.y += this.vy * dt
    this.z += this.vz * dt

    if (this.z < 0) {
      this.z = 0
      if (this.vz < 0) {
        this.vz = -this.vz * BALL.restitution
        // A bounce scrubs pace off the ground and bleeds spin.
        this.vx *= BALL.bounceGrip
        this.vy *= BALL.bounceGrip
        this.spin *= 0.72
        this.justBounced = Math.abs(this.vz) > 1.0
        if (this.vz < BALL.settleBounce) this.vz = 0 // settle into a roll
      }
    }

    const sp = this.speed
    if (sp > BALL.maxSpeed) {
      const s = BALL.maxSpeed / sp
      this.vx *= s
      this.vy *= s
      this.vz *= s
    }

    if (!this.airborne && this.horizontalSpeed < BALL.settleSpeed) {
      this.vx *= 0.86
      this.vy *= 0.86
      if (this.horizontalSpeed < 0.05) {
        this.vx = 0
        this.vy = 0
      }
    }
  }

  // Side-spin pushes the ball perpendicular to its horizontal travel. The force
  // scales with speed, so a firmly struck ball bends hard and a dying one
  // straightens out — the same way a real cross does.
  private applyMagnus(dt: number, gain: number) {
    const hs = this.horizontalSpeed
    if (hs < 0.3 || Math.abs(this.spin) < 0.01) return
    const px = -this.vy / hs
    const py = this.vx / hs
    const acc = BALL.magnus * this.spin * hs * gain
    this.vx += px * acc * dt
    this.vy += py * acc * dt
  }

  // Reflect off a vertical surface (post / body) given a surface normal.
  reflect(nx: number, ny: number, restitution: number) {
    const d = this.vx * nx + this.vy * ny
    if (d >= 0) return
    this.vx -= (1 + restitution) * d * nx
    this.vy -= (1 + restitution) * d * ny
    this.spin *= 0.6
  }

  clampSpin() {
    this.spin = clamp(this.spin, -BALL.maxSpin, BALL.maxSpin)
  }
}
