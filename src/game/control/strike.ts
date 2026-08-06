import { CONTROL, KICK } from '../config'
import { clamp } from '../core/math'
import * as V from '../core/vec'
import type { Vec2 } from '../core/vec'
import type { KickRequest, KickType, Skill } from '../types'

// The striking model, shared by the 2D and 3D controllers.
//
// Power is how long you held the button. Height and curve come from how you
// flicked the mouse in the last instant before release — up to lift it, down to
// drive it low, sideways to bend it. Only motion inside CONTROL.flickWindow
// counts, so a steady aim followed by a late flick is what shapes the ball; a
// wild flick sends it into orbit, which is the intended risk.

interface Sample {
  t: number
  x: number
  y: number
}

export class FlickTracker {
  private samples: Sample[] = []
  private clock = 0

  // Aiming movement before you press shouldn't count as a flick — the flick is
  // what you do *during* the charge. Cleared the moment a button goes down.
  reset() {
    this.samples.length = 0
  }

  // Feed the frame's raw mouse delta (pixels).
  push(dx: number, dy: number, dt: number) {
    this.clock += dt
    this.samples.push({ t: this.clock, x: dx, y: dy })
    // Keep a little more than the window so we can always cover it.
    const cutoff = this.clock - CONTROL.flickWindow * 2.5
    while (this.samples.length && this.samples[0].t < cutoff) this.samples.shift()
  }

  // Total mouse travel during the final `window` seconds.
  flick(window = CONTROL.flickWindow): Vec2 {
    const from = this.clock - window
    let x = 0
    let y = 0
    for (const s of this.samples) {
      if (s.t >= from) {
        x += s.x
        y += s.y
      }
    }
    return { x, y }
  }
}

// Where you were pointing *before* the flick. The late flick is meant to shape
// the ball, not to re-aim it, so the strike direction is the aim as it stood
// when the flick began — otherwise a hard curve would also throw the shot wide.
export class AimTracker {
  private samples: { t: number; a: Vec2 }[] = []
  private clock = 0

  push(a: Vec2, dt: number) {
    this.clock += dt
    this.samples.push({ t: this.clock, a: { x: a.x, y: a.y } })
    const cutoff = this.clock - CONTROL.flickWindow * 3
    while (this.samples.length && this.samples[0].t < cutoff) this.samples.shift()
  }

  settled(fallback: Vec2, window = CONTROL.flickWindow): Vec2 {
    const at = this.clock - window
    for (let i = this.samples.length - 1; i >= 0; i--) {
      if (this.samples[i].t <= at) return this.samples[i].a
    }
    return this.samples.length ? this.samples[0].a : fallback
  }
}

// A button whose power builds while held. Charge is measured against the wall
// clock rather than accumulated frame deltas, so a dropped frame or a stutter
// can't quietly rob you of power mid-strike.
export class ChargeButton {
  charge = 0
  held = false
  private downAt = 0
  private armed = false // a press is in flight and hasn't been released yet

  // Driven by the press edge, so a tap that starts and ends inside a single
  // frame still records its (very short) hold rather than reading as full power.
  press() {
    this.downAt = performance.now() / 1000
    this.armed = true
    this.held = true
  }

  update(down: boolean, fillTime: number) {
    this.held = down && this.armed
    this.charge = this.held ? Math.min(fillTime, performance.now() / 1000 - this.downAt) : 0
  }

  // Normalised 0..1 power, then resets ready for the next press.
  release(fillTime: number): number {
    if (!this.armed) return 0
    const held = Math.min(fillTime, performance.now() / 1000 - this.downAt)
    this.armed = false
    this.held = false
    this.charge = 0
    return clamp(held / fillTime, 0, 1)
  }

  fraction(fillTime: number): number {
    return clamp(this.charge / fillTime, 0, 1)
  }
}

// Convert a raw flick (pixels) into loft and spin, applying the player's
// sensitivity settings. Screen-space up (negative dy) means lift.
export function shapeFromFlick(
  flick: Vec2,
  heightSens = CONTROL.heightSensitivity,
  curveSens = CONTROL.curveSensitivity,
): { loft: number; spin: number } {
  const loft = clamp((-flick.y / CONTROL.flickRef) * heightSens, -1, 1)
  const spin = clamp((flick.x / CONTROL.flickRef) * curveSens, -1.6, 1.6)
  return { loft, spin }
}

// Read a close-control move out of the flick made during a touch. The same
// wrist vocabulary that shapes a strike shapes your feet: drag it back, roll it
// across your body, or lift it over a leg. Only a decisive flick counts, so an
// ordinary touch is unaffected by small aiming drift.
export function skillFromFlick(flick: Vec2, movingBack = false): Skill | null {
  const mag = Math.hypot(flick.x, flick.y)
  if (mag < CONTROL.skillFlickMin) return null
  // Whichever axis dominates decides the move.
  if (Math.abs(flick.y) > Math.abs(flick.x)) {
    if (flick.y <= 0) return 'lift' // up = lift it over a leg
    // Down. Pulling it back where you stand is a drag; doing it while already
    // going backwards is a backheel — the same wrist, and the difference is
    // whether your body was going that way too.
    return movingBack ? 'backheel' : 'drag'
  }
  return 'roll' // sideways = roll it across you
}

// Assemble the request the simulation consumes.
export function makeKick(
  type: KickType,
  power: number,
  aim: Vec2,
  shape: { loft: number; spin: number },
  skill: Skill | null = null,
): KickRequest {
  return {
    type,
    power: clamp(power, 0, 1),
    aim: V.len(aim) > 0.01 ? V.normalize(aim) : { x: 1, y: 0 },
    loft: shape.loft,
    spin: shape.spin,
    skill: skill ?? undefined,
  }
}

// Speed range for a given kick type, so the simulation and the HUD agree.
export function speedRange(type: KickType): [number, number] {
  switch (type) {
    case 'touch':
      return [KICK.touchMin, KICK.touchMax]
    case 'strike':
      return [KICK.strikeMin, KICK.strikeMax]
    case 'shot':
      return [KICK.shotMin, KICK.shotMax]
    case 'clear':
      return [KICK.shotMax * 0.85, KICK.shotMax * 0.85]
    default:
      return [KICK.passMin, KICK.passMax]
  }
}
