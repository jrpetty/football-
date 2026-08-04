import { clamp01 } from '../core/math'
import type { InputManager } from '../core/input'
import type { Camera3D } from '../render3d/camera3d'
import type { World } from '../match/world'
import { emptyCommand } from '../types'
import type { Command } from '../types'

const CHARGE_TIME = 0.85

// Input → Command for the 3D views. Movement is camera-relative (W is "where I'm
// looking"), and the kick aim is the camera's horizontal look direction, so what
// you see is where it goes. Same charge/loft model as the 2D controller.
export class Human3DController {
  private passCharge = 0
  private shotCharge = 0

  chargeType: 'pass' | 'shot' | 'through' | null = null
  charge = 0
  lofted = false

  buildCommand(world: World, cam: Camera3D, input: InputManager, dt: number): Command {
    const cmd = emptyCommand()
    const p = world.getControlledPlayer()
    if (!p) return cmd

    const aim = cam.aimSim() // unit, horizontal look direction
    const right = { x: aim.y, y: -aim.x } // aim rotated −90° on the pitch plane

    let f = 0
    let s = 0
    if (input.anyDown('KeyW', 'ArrowUp')) f += 1
    if (input.anyDown('KeyS', 'ArrowDown')) f -= 1
    if (input.anyDown('KeyD', 'ArrowRight')) s += 1
    if (input.anyDown('KeyA', 'ArrowLeft')) s -= 1
    cmd.move = { x: aim.x * f + right.x * s, y: aim.y * f + right.y * s }
    cmd.sprint = input.anyDown('ShiftLeft', 'ShiftRight')
    cmd.aim = aim

    const loft = input.isDown('Space')
    this.lofted = loft

    if (input.mouseButtons.left) this.passCharge = Math.min(CHARGE_TIME, this.passCharge + dt)
    if (input.mouseReleased.left) {
      cmd.kick = { type: 'pass', power: Math.max(clamp01(this.passCharge / CHARGE_TIME), 0.12), aim, lofted: loft, chip: false }
      this.passCharge = 0
    }

    if (input.mouseButtons.right) this.shotCharge = Math.min(CHARGE_TIME, this.shotCharge + dt)
    if (input.mouseReleased.right) {
      cmd.kick = { type: 'shot', power: Math.max(clamp01(this.shotCharge / CHARGE_TIME), 0.35), aim, lofted: false, chip: loft }
      this.shotCharge = 0
    }

    if (input.justPressed('KeyE') && !cmd.kick) {
      cmd.kick = { type: 'through', power: 0.7, aim, lofted: loft, chip: false }
    }

    if (input.isDown('KeyF')) cmd.tackle = true
    if (input.justPressed('KeyC')) cmd.slide = true
    if (input.justPressed('KeyQ') && world.config.mode === 'match') cmd.requestSwitch = true

    if (input.mouseButtons.right) {
      this.chargeType = 'shot'
      this.charge = this.shotCharge / CHARGE_TIME
    } else if (input.mouseButtons.left) {
      this.chargeType = 'pass'
      this.charge = this.passCharge / CHARGE_TIME
    } else {
      this.chargeType = null
      this.charge = 0
    }

    return cmd
  }
}
