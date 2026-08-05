import { CONTROL } from '../config'
import type { InputManager } from '../core/input'
import type { Camera3D } from '../render3d/camera3d'
import type { World } from '../match/world'
import { emptyCommand } from '../types'
import type { Command } from '../types'
import type { Vec2 } from '../core/vec'
import { AimTracker, ChargeButton, FlickTracker, makeKick, shapeFromFlick, skillFromFlick } from './strike'

export interface Sensitivity {
  height: number
  curve: number
}

// Input → Command for the 3D views, built around the Pro Soccer Online striking
// model:
//   RIGHT mouse — the soft touch. Tap for a close-control nudge, hold for a
//     heavier push into space. A/D while touching knocks the ball to that side.
//   LEFT mouse  — the strike. One continuous scale: a tap rolls a short pass, a
//     full bar is a shot. Height and curve come from flicking the mouse in the
//     last moments before you release — up to lift it, down to drive it low,
//     sideways to bend it.
// Movement is camera-relative, and the strike direction is where you were
// pointing as the flick began, so a curve bends the ball without re-aiming it.
export class Human3DController {
  private strike = new ChargeButton()
  private touch = new ChargeButton()
  private flick = new FlickTracker()
  private aim = new AimTracker()

  // Read by the HUD.
  chargeType: 'touch' | 'strike' | null = null
  charge = 0
  liveLoft = 0
  liveSpin = 0

  constructor(public sens: Sensitivity = { height: CONTROL.heightSensitivity, curve: CONTROL.curveSensitivity }) {}

  buildCommand(world: World, cam: Camera3D, input: InputManager, dt: number): Command {
    const cmd = emptyCommand()
    const p = world.getControlledPlayer()
    if (!p) return cmd

    const look = cam.aimSim() // unit, horizontal look direction
    // Camera-right on the pitch plane. The sim's (x, y) maps to world (X, Z)
    // with Y up, so right = forward × up = (−look.y, look.x). Getting this
    // backwards silently swaps the A and D strafe keys.
    const right = { x: -look.y, y: look.x }
    this.flick.push(input.movementX, input.movementY, dt)
    this.aim.push(look, dt)

    // --- movement (camera-relative) ---
    let f = 0
    let s = 0
    if (input.anyDown('KeyW', 'ArrowUp')) f += 1
    if (input.anyDown('KeyS', 'ArrowDown')) f -= 1
    if (input.anyDown('KeyD', 'ArrowRight')) s += 1
    if (input.anyDown('KeyA', 'ArrowLeft')) s -= 1
    cmd.move = { x: look.x * f + right.x * s, y: look.y * f + right.y * s }
    cmd.sprint = input.anyDown('ShiftLeft', 'ShiftRight')
    // Hold Alt to walk — useful for setting your feet before a strike.
    cmd.walk = input.anyDown('AltLeft', 'AltRight')
    cmd.aim = look

    // --- charging ---
    if (input.mousePressed.left) {
      this.strike.press()
      this.flick.reset()
    }
    if (input.mousePressed.right) {
      this.touch.press()
      this.flick.reset()
    }
    this.strike.update(input.mouseButtons.left, CONTROL.strikeCharge)
    this.touch.update(input.mouseButtons.right, CONTROL.touchCharge)

    // Live shaping preview while a strike is charging.
    if (this.strike.held) {
      const shape = shapeFromFlick(this.flick.flick(), this.sens.height, this.sens.curve)
      this.liveLoft = shape.loft
      this.liveSpin = shape.spin
    } else if (!this.touch.held) {
      this.liveLoft = 0
      this.liveSpin = 0
    }

    // --- release: the strike (pass / shot, decided by power) ---
    if (input.mouseReleased.left) {
      const power = this.strike.release(CONTROL.strikeCharge)
      const shape = shapeFromFlick(this.flick.flick(), this.sens.height, this.sens.curve)
      cmd.kick = makeKick('strike', Math.max(power, 0.08), this.aim.settled(look), shape)
    }

    // --- release: the touch (close control) ---
    if (input.mouseReleased.right) {
      const power = this.touch.release(CONTROL.touchCharge)
      const raw = this.flick.flick()
      const shape = shapeFromFlick(raw, this.sens.height, this.sens.curve)
      // A/D during a touch knocks the ball out to that side rather than straight on.
      let dirv: Vec2 = look
      if (s !== 0) {
        dirv = {
          x: look.x * 0.55 + right.x * s * 1.0,
          y: look.y * 0.55 + right.y * s * 1.0,
        }
      } else if (f < 0) {
        dirv = { x: -look.x, y: -look.y } // pull it back
      }
      // A decisive flick during the touch turns it into a close-control move.
      const skill = skillFromFlick(raw)
      cmd.kick = makeKick(
        'touch',
        Math.max(power, 0.12),
        dirv,
        { loft: Math.max(0, shape.loft), spin: shape.spin * 0.4 },
        skill,
      )
    }

    // --- defending ---
    if (input.isDown('KeyF')) cmd.tackle = true
    if (input.justPressed('KeyC')) cmd.slide = true

    // --- HUD feedback ---
    if (this.strike.held) {
      this.chargeType = 'strike'
      this.charge = this.strike.fraction(CONTROL.strikeCharge)
    } else if (this.touch.held) {
      this.chargeType = 'touch'
      this.charge = this.touch.fraction(CONTROL.touchCharge)
    } else {
      this.chargeType = null
      this.charge = 0
    }

    return cmd
  }
}
