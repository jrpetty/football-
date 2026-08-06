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
  // Side-spin, which bends the flight left or right.
  spin = 0
  // Spin in the vertical plane: positive is topspin, which presses the ball
  // down and makes it skid on off the turf; negative is backspin, which holds
  // it up in the air and checks it on landing.
  vSpin = 0

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
    this.vSpin = 0
    this.z = 0
    this.pz = 0
  }

  launch(vx: number, vy: number, vz: number, spin: number, team: Team, id: number, vSpin = 0) {
    this.vx = vx
    this.vy = vy
    this.vz = vz
    this.spin = spin
    this.vSpin = vSpin
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
    // "Rolling" means no vertical motion, not merely low. Testing height alone
    // meant a ball dropping at 20 m/s that happened to be inside the last
    // centimetre at the top of a step was treated as already rolling — and its
    // whole bounce was quietly thrown away. At 120 Hz a fast ball covers 17 cm
    // per step, so this fired constantly on exactly the hardest, flattest balls.
    this.airborne = this.z > 0.015 || Math.abs(this.vz) > 0.02

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
      // Vertical-plane spin. Topspin presses the ball down so a driven shot dips
      // under the bar; backspin holds it up so a chip floats and hangs.
      const hs = this.horizontalSpeed
      if (hs > 0.3 && Math.abs(this.vSpin) > 0.01) {
        this.vz -= BALL.magnusVertical * this.vSpin * hs * dt
      }
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
      // On the deck the ball is rolling, so its surface speed is its travel
      // speed by definition. A ball still slipping — one that's just been driven
      // with topspin, or checked with backspin — is dragged toward that rolling
      // condition by friction rather than snapping to it, which is what lets a
      // back-spun ball stop dead and screw back at you.
      const slip = hs - this.vSpin
      if (Math.abs(slip) > 0.01) {
        const pull = Math.min(Math.abs(slip), BALL.turfFriction * SIM.gravity * 2.5 * dt)
        const s = Math.sign(slip)
        this.vx -= (hs > 1e-4 ? this.vx / hs : 0) * pull * 0.4 * s
        this.vy -= (hs > 1e-4 ? this.vy / hs : 0) * pull * 0.4 * s
        this.vSpin += pull * 0.6 * s
      } else {
        this.vSpin = hs
      }
    }

    // Side-spin bleeds off through the air; vertical spin doesn't decay on its
    // own — it's traded with the ball's travel at every contact instead.
    this.spin -= this.spin * BALL.spinDecay * dt
    if (this.airborne) this.vSpin -= this.vSpin * BALL.spinDecay * 0.35 * dt

    this.x += this.vx * dt
    this.y += this.vy * dt
    this.z += this.vz * dt

    if (this.z < 0) {
      this.z = 0
      if (this.vz < 0) this.bounce()
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

  // A bounce off the turf, solved as a real impact rather than a pair of damping
  // multipliers. Two impulses act at the contact patch:
  //
  //   Normal — the ball squashes and springs back, keeping a fraction e of its
  //     downward speed. e is not a constant: a ball hit hard deforms further and
  //     loses proportionally more, which is why a driven shot skids off the turf
  //     while a gently dropped ball sits up.
  //
  //   Tangential — friction acting on how fast the contact patch is *sliding*.
  //     That slip is the ball's forward speed minus the speed its own surface is
  //     already turning at, so spin is not a modifier bolted onto the bounce; it
  //     is half of what decides the bounce. A back-spun ball's surface is
  //     turning backwards, so the patch slips hard forwards, friction bites hard
  //     and the ball checks — sometimes back towards you. A top-spun ball's
  //     surface is already going with the slide, so there is little to scrub and
  //     it kicks on low and fast.
  //
  // When friction can kill the slip outright the ball comes out *rolling*, and
  // the split between what the ball keeps and what goes into spin is set by a
  // football's moment of inertia — near enough a hollow sphere, I = ⅔mR², which
  // works out at losing two fifths of the slip and putting three fifths into
  // rotation. When it can't, the ball skids on and keeps sliding.
  private bounce() {
    const impact = -this.vz

    // Restitution falls off with impact speed.
    const e = clamp(BALL.restitution - BALL.restitutionFalloff * impact, BALL.restitutionMin, 1)
    this.vz = impact * e

    // Coulomb limit: friction can only deliver so much for a given impact.
    const grip = BALL.turfFriction * (1 + e) * impact
    const hs = this.horizontalSpeed
    if (hs > 1e-4) {
      const dx = this.vx / hs
      const dy = this.vy / hs
      // Slip at the contact patch: how fast the ball is travelling minus how
      // fast its surface is already turning underneath it. vSpin is carried as
      // that surface speed in m/s, so this is a straight subtraction.
      const slip = hs - this.vSpin
      const needed = Math.abs(slip) * 0.4 // 2/5 of the slip, from I = 2/3 mR²

      let nhs: number
      if (needed <= grip) {
        // Enough grip to stop the slide: the ball leaves the turf rolling.
        nhs = hs - slip * 0.4
        this.vSpin = nhs // rolling, so surface speed matches travel
      } else {
        // Not enough: it skids on, shedding what friction it can.
        const j = grip * Math.sign(slip)
        nhs = hs - j
        this.vSpin += j * 1.5
      }
      // Side-spin works against the turf too, dragging the ball sideways as it
      // lands — why a heavily curled ball kicks away off the bounce. Same
      // friction budget, so it can't conjure pace out of nothing.
      const side = clamp(this.spin * BALL.bounceSideKick, -grip, grip)
      this.vx = dx * nhs - dy * side
      this.vy = dy * nhs + dx * side
      this.spin *= 0.62
    } else {
      this.vSpin *= 0.5
      this.spin *= 0.62
    }

    this.justBounced = impact > 1.0
    if (this.vz < BALL.settleBounce) {
      this.vz = 0 // too little left to leave the ground: settle into a roll
      this.vSpin = this.horizontalSpeed
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
  // Reflect off a vertical surface — a post, a board, a body.
  //
  // Solved the same way as the turf: a normal impulse, and a tangential one
  // acting on how fast the contact patch is sliding across the surface. Which
  // means side-spin decides where a ball comes off a post, exactly as it should
  // — a curler that clips the inside of the upright squirts back across the
  // goal, and the same ball with the spin the other way spits out for a corner.
  // It used to reflect like a billiard ball and scrub the spin to 60%, so the
  // most dramatic thing that can happen to a struck ball was pure geometry.
  //
  // `grip` is how much the surface bites: a post is hard and grabby, a body in a
  // shirt much less so.
  reflect(nx: number, ny: number, restitution: number, grip = BALL.postGrip) {
    const d = this.vx * nx + this.vy * ny
    if (d >= 0) return
    // Normal: straight back out, keeping a fraction of the approach.
    this.vx -= (1 + restitution) * d * nx
    this.vy -= (1 + restitution) * d * ny

    // Tangential. The surface of a ball with side-spin is moving along this
    // tangent at −spin, so the slip is the difference between how fast the ball
    // is sliding across the surface and how fast its own skin already is.
    const tx = -ny
    const ty = nx
    const along = this.vx * tx + this.vy * ty
    const slip = along + this.spin
    if (Math.abs(slip) > 1e-4) {
      const budget = grip * (1 + restitution) * Math.abs(d)
      const needed = Math.abs(slip) * 0.4 // 2/5 of the slip, from I = 2/3 mR²
      const j = Math.min(needed, budget) * Math.sign(slip)
      this.vx -= tx * j
      this.vy -= ty * j
      // ...and the rest of the exchange goes into the spin, which is why the
      // ball comes off a post turning differently from how it arrived.
      this.spin -= (j / 0.4) * 0.6
    }
  }

  clampSpin() {
    this.spin = clamp(this.spin, -BALL.maxSpin, BALL.maxSpin)
    this.vSpin = clamp(this.vSpin, -BALL.maxVSpin, BALL.maxVSpin)
  }
}
