import { binds, label as keyLabel } from '../core/bindings'
import type { Action } from '../core/bindings'
import { angleDelta } from '../core/math'
import type { Command } from '../types'
import type { World } from './world'

// The first lesson: nine things, in the order you would want them, each one
// ticked off by doing it rather than by reading it.
//
// It watches; it never steers. Nothing here changes what the simulation does or
// what a button means — a step is complete when the world says the thing
// happened (a touch landed, a strike left at real pace, a foot left the
// ground), so it is impossible to "pass" a step by clicking at nothing, and
// impossible to get stuck because the tutorial disagreed with the game about
// what a strike is.

interface Step {
  id: string
  title: string
  // The instruction, with the player's own keys in it. `have` says how far
  // through the step they are, for the steps that ask for more than one thing.
  text: (have: string) => string
}

const k = (a: Action) => keyLabel(binds.get(a))

// Thresholds, named so the test and the text agree with the code.
export const LESSON = {
  turn: 1.6, // radians of looking around
  run: 14, // metres covered
  sprint: 1, // seconds at a sprint
  touches: 5,
  strike: 16, // m/s: a ball that was actually struck, not nudged
  lift: 0.35, // loft asked for by the wrist
  bend: 0.3, // spin asked for by the wrist
  shield: 1, // seconds held
}

const STEPS: Step[] = [
  { id: 'look', title: 'Look around', text: () => 'Move the mouse. Where you look is where you aim — everything you kick goes that way.' },
  { id: 'run', title: 'Run', text: (h) => `Hold ${k('up')} to run, and ${k('sprint')} to sprint. Cover some ground. ${h}` },
  { id: 'touch', title: 'First touch', text: (h) => `Right click to touch the ball. Tap it along as you run — it only moves when you click. ${h}  (${k('spawnBall')} puts it back in front of you)` },
  { id: 'strike', title: 'Strike', text: () => 'Hold left click to charge, and let go to strike. The longer you hold, the harder it goes — hit one properly.' },
  { id: 'shape', title: 'Shape it', text: (h) => `Flick the mouse UP as you charge to lift it; flick SIDEWAYS to bend it. Do one of each. ${h}` },
  { id: 'jump', title: 'Jump', text: () => `Press ${k('jump')}. Everything you can reach goes up with you.` },
  { id: 'shield', title: 'Shield', text: (h) => `Hold ${k('shield')} to turn side-on and shield the ball with your body. ${h}` },
  { id: 'slide', title: 'Slide', text: () => `Press ${k('slide')} to slide. It is all timing.` },
  { id: 'score', title: 'Score', text: () => 'Put one in either goal. Nothing resets afterwards — the net gives it back.' },
]

export class Tutorial {
  readonly total = STEPS.length
  index = 0
  finished = false
  // How long it has been finished, so the last message can leave on its own.
  private finishedFor = 0

  // Progress within the current step.
  private turned = 0
  private travelled = 0
  private sprinting = 0
  private shielded = 0
  private lifted = false
  private bent = false
  private jumped = false
  private slid = false
  private base = { touches: 0, strikes: 0, goals: 0 }
  private prevAim: { x: number; y: number } | null = null
  private prevPos: { x: number; y: number } | null = null

  constructor() {
    this.enter(null)
  }

  private enter(world: World | null) {
    this.turned = this.travelled = this.sprinting = this.shielded = 0
    this.lifted = this.bent = this.jumped = this.slid = false
    if (world) {
      this.base = { touches: world.session.touches, strikes: world.session.strikes, goals: world.session.goals }
    }
  }

  get step(): Step {
    return STEPS[Math.min(this.index, STEPS.length - 1)]
  }

  skip(world: World) {
    if (this.finished) return
    this.advance(world)
  }

  private advance(world: World) {
    this.index++
    if (this.index >= STEPS.length) {
      this.finished = true
      return
    }
    this.enter(world)
  }

  // Called once per simulation frame, after the world has stepped with `cmd`.
  observe(world: World, cmd: Command, dt: number) {
    if (this.finished) {
      this.finishedFor += dt
      return
    }
    const me = world.getControlledPlayer()
    if (!me) return
    const s = world.session

    // Bookkeeping that every step is allowed to lean on.
    const aim = cmd.aim.x || cmd.aim.y ? Math.atan2(cmd.aim.y, cmd.aim.x) : null
    if (aim !== null && this.prevAim) this.turned += Math.abs(angleDelta(Math.atan2(this.prevAim.y, this.prevAim.x), aim))
    if (aim !== null) this.prevAim = { x: cmd.aim.x, y: cmd.aim.y }
    if (this.prevPos) this.travelled += Math.hypot(me.x - this.prevPos.x, me.y - this.prevPos.y)
    this.prevPos = { x: me.x, y: me.y }
    const pace = Math.hypot(me.vx, me.vy)
    if (cmd.sprint && pace > 6) this.sprinting += dt
    if (me.shielding) this.shielded += dt
    if (me.z > 0.08) this.jumped = true
    if (me.slideTimer > 0) this.slid = true

    // A strike that just happened.
    const struck = s.strikes > this.base.strikes
    if (struck) {
      this.base.strikes = s.strikes
      if (s.lastStrike.speed >= LESSON.strike) {
        if (s.lastStrike.loft >= LESSON.lift) this.lifted = true
        if (Math.abs(s.lastStrike.spin) >= LESSON.bend) this.bent = true
      }
    }

    let done = false
    switch (this.step.id) {
      case 'look': done = this.turned >= LESSON.turn; break
      case 'run': done = this.travelled >= LESSON.run && this.sprinting >= LESSON.sprint; break
      case 'touch': done = s.touches - this.base.touches >= LESSON.touches; break
      case 'strike': done = struck && s.lastStrike.speed >= LESSON.strike; break
      case 'shape': done = this.lifted && this.bent; break
      case 'jump': done = this.jumped; break
      case 'shield': done = this.shielded >= LESSON.shield; break
      case 'slide': done = this.slid; break
      case 'score': done = s.goals > this.base.goals; break
    }
    if (done) this.advance(world)
  }

  // Progress inside the step, in words.
  private have(world: World | null): string {
    switch (this.step.id) {
      case 'run': return `${Math.min(100, Math.round((this.travelled / LESSON.run) * 100))}%`
      case 'touch': return world ? `${Math.min(LESSON.touches, world.session.touches - this.base.touches)}/${LESSON.touches}` : ''
      case 'shape': return `${this.lifted ? 'lifted ✓' : 'not lifted yet'} · ${this.bent ? 'bent ✓' : 'not bent yet'}`
      case 'shield': return `${Math.min(LESSON.shield, this.shielded).toFixed(1)}/${LESSON.shield}s`
      default: return ''
    }
  }

  // What the HUD should say right now, or nothing.
  coach(world: World): { title: string; text: string; done: number; total: number } | null {
    if (this.finished) {
      if (this.finishedFor > 7) return null
      return {
        title: "That's the lot",
        text: 'Nothing resets in training — keep going as long as you like. Esc lets go of the mouse.',
        done: this.total,
        total: this.total,
      }
    }
    return { title: this.step.title, text: this.step.text(this.have(world)), done: this.index, total: this.total }
  }
}
