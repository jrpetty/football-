import { CONTROL } from '../config'
import * as V from '../core/vec'
import type { Vec2 } from '../core/vec'
import type { InputManager } from '../core/input'
import type { Camera } from '../render/camera'
import type { World } from '../match/world'
import { emptyCommand } from '../types'
import type { Command } from '../types'
import { AimTracker, ChargeButton, FlickTracker, makeKick, shapeFromFlick, skillFromFlick } from './strike'
import type { Sensitivity } from './human3d'

// Input → Command for the 2D top-down view. Same striking model as the 3D
// controller (right mouse = soft touch, left mouse = strike, power from hold
// time, height and curve from the late mouse flick); the only difference is that
// aim comes from the cursor's position on the pitch rather than a look vector.
export class HumanController {
  private strike = new ChargeButton()
  private touch = new ChargeButton()
  private flick = new FlickTracker()
  private aim = new AimTracker()

  chargeType: 'touch' | 'strike' | null = null
  charge = 0
  liveLoft = 0
  liveSpin = 0

  constructor(
    public sens: Sensitivity = { height: CONTROL.heightSensitivity, curve: CONTROL.curveSensitivity },
  ) {}

  buildCommand(world: World, cam: Camera, input: InputManager, dt: number): Command {
    const cmd = emptyCommand()
    const p = world.getControlledPlayer()
    if (!p) return cmd

    this.flick.push(input.movementX, input.movementY, dt)

    // --- movement ---
    let mx = 0
    let my = 0
    if (input.anyDown('KeyW', 'ArrowUp')) my -= 1
    if (input.anyDown('KeyS', 'ArrowDown')) my += 1
    if (input.anyDown('KeyA', 'ArrowLeft')) mx -= 1
    if (input.anyDown('KeyD', 'ArrowRight')) mx += 1
    cmd.move = { x: mx, y: my }
    cmd.sprint = input.anyDown('ShiftLeft', 'ShiftRight')
    // Hold Alt to walk — useful for setting your feet before a strike.
    cmd.walk = input.anyDown('AltLeft', 'AltRight')

    // --- aim: the cursor, falling back to the way the player is running ---
    const moving = mx !== 0 || my !== 0
    let look: Vec2 = moving ? V.normalize({ x: mx, y: my }) : p.facing
    if (input.mouseInside) {
      const wp = cam.screenToWorld(input.mouse.x, input.mouse.y)
      const a = V.dir(p.pos, wp)
      if (V.len(a) > 0.01) look = a
    }
    cmd.aim = look
    this.aim.push(look, dt)

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

    if (this.strike.held) {
      const shape = shapeFromFlick(this.flick.flick(), this.sens.height, this.sens.curve)
      this.liveLoft = shape.loft
      this.liveSpin = shape.spin
    } else if (!this.touch.held) {
      this.liveLoft = 0
      this.liveSpin = 0
    }

    if (input.mouseReleased.left) {
      const power = this.strike.release(CONTROL.strikeCharge)
      const shape = shapeFromFlick(this.flick.flick(), this.sens.height, this.sens.curve)
      cmd.kick = makeKick('strike', Math.max(power, 0.08), this.aim.settled(look), shape)
    }

    if (input.mouseReleased.right) {
      const power = this.touch.release(CONTROL.touchCharge)
      const raw = this.flick.flick()
      const shape = shapeFromFlick(raw, this.sens.height, this.sens.curve)
      // Holding a direction pushes the touch that way: sideways to knock it wide,
      // back into yourself to pull it out of a defender's reach.
      let dirv: Vec2 = look
      if (moving) {
        const wish = V.normalize({ x: mx, y: my })
        dirv = V.normalize({ x: look.x * 0.45 + wish.x, y: look.y * 0.45 + wish.y })
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
    // There is no tackle button: you win the ball with the same two clicks you
    // use for everything else. The slide is the one dedicated defensive move.
    if (input.justPressed('KeyC')) cmd.slide = true
    // Space jumps. Everything you can play rises with you, so this is how you
    // attack a ball that would otherwise sail over your head.
    if (input.justPressed('Space')) cmd.jump = true
    // Hold Q to shield: side-on, slow, ball behind your body, no strike.
    cmd.shield = input.isDown('KeyQ')

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
