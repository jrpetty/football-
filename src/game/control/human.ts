import { KICK } from '../config'
import { clamp01 } from '../core/math'
import * as V from '../core/vec'
import type { InputManager } from '../core/input'
import type { Camera } from '../render/camera'
import type { World } from '../match/world'
import { emptyCommand } from '../types'
import type { Command } from '../types'

const CHARGE_TIME = 0.85 // seconds of hold to fill the power meter

// Translates raw input into the controlled player's Command. All the "feel"
// decisions (tap vs hold, aim from cursor, loft modifier) live here so the
// simulation stays pure. Charge state persists across frames on this object.
export class HumanController {
  private passCharge = 0
  private shotCharge = 0

  // Exposed for the HUD each frame.
  chargeType: 'pass' | 'shot' | 'through' | null = null
  charge = 0
  lofted = false

  buildCommand(world: World, cam: Camera, input: InputManager, dt: number): Command {
    const cmd = emptyCommand()
    const p = world.getControlledPlayer()
    if (!p) return cmd

    // --- movement ---
    let mx = 0
    let my = 0
    if (input.anyDown('KeyW', 'ArrowUp')) my -= 1
    if (input.anyDown('KeyS', 'ArrowDown')) my += 1
    if (input.anyDown('KeyA', 'ArrowLeft')) mx -= 1
    if (input.anyDown('KeyD', 'ArrowRight')) mx += 1
    cmd.move = { x: mx, y: my }
    cmd.sprint = input.anyDown('ShiftLeft', 'ShiftRight')

    // --- aim (cursor if the mouse is in play, else movement/facing) ---
    const moving = mx !== 0 || my !== 0
    let aim = moving ? V.normalize({ x: mx, y: my }) : p.facing
    if (input.mouseInside) {
      const wp = cam.screenToWorld(input.mouse.x, input.mouse.y)
      const a = V.dir(p.pos, wp)
      if (V.len(a) > 0.01) aim = a
    }
    cmd.aim = aim

    const loft = input.isDown('Space')
    this.lofted = loft

    // --- pass (left mouse: tap = short, hold = long) ---
    if (input.mouseButtons.left) {
      this.passCharge = Math.min(CHARGE_TIME, this.passCharge + dt)
    }
    if (input.mouseReleased.left) {
      const power = clamp01(this.passCharge / CHARGE_TIME)
      cmd.kick = {
        type: 'pass',
        power: Math.max(power, 0.12),
        aim,
        lofted: loft,
        chip: false,
      }
      this.passCharge = 0
    }

    // --- shot (right mouse: hold to charge, release to fire) ---
    if (input.mouseButtons.right) {
      this.shotCharge = Math.min(CHARGE_TIME, this.shotCharge + dt)
    }
    if (input.mouseReleased.right) {
      const power = clamp01(this.shotCharge / CHARGE_TIME)
      cmd.kick = {
        type: 'shot',
        power: Math.max(power, 0.35),
        aim,
        lofted: false,
        chip: loft, // loft modifier turns a shot into a chip over the keeper
      }
      this.shotCharge = 0
    }

    // --- through ball (E: weighted pass into space) ---
    if (input.justPressed('KeyE') && !cmd.kick) {
      cmd.kick = { type: 'through', power: 0.7, aim, lofted: loft, chip: false }
    }

    // --- defending ---
    if (input.isDown('KeyF')) cmd.tackle = true
    if (input.justPressed('KeyC')) cmd.slide = true
    if (input.justPressed('KeyQ') && world.config.mode === 'match') cmd.requestSwitch = true

    // --- HUD feedback ---
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

    void KICK
    return cmd
  }
}
