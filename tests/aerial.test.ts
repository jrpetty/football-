// Headless verification of aerial control and flick skill moves.
import { World } from '../src/game/match/world'
import { makeKick, shapeFromFlick, skillFromFlick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { SIM, PLAYER, FIELD } from '../src/game/config'

// Pitch coordinates run 0..length / 0..width, so the origin is a CORNER. Test
// from the middle or the out-of-bounds clamp quietly rewrites the result.
const CX = FIELD.length / 2
const CY = FIELD.width / 2

const cfg = {
  teamSize: 1,
  halfLength: 600,
  mode: 'training' as const,
  singleKeeper: false,
  view: '3d' as const,
  position: 'MID' as const,
  heightSens: 1.6,
  curveSens: 1.15,
  humanControlled: true,
}

// Park the player at the origin facing +x, with the ball arriving at `ballZ`.
function setup(ballZ: number, incoming: number, vz: number) {
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = CX
  p.y = CY
  p.vx = 0
  p.vy = 0
  p.heading = 0
  p.kickCooldown = 0
  w.ball.setPos(CX + 0.55, CY, ballZ)
  w.ball.vx = incoming
  w.ball.vy = 0
  w.ball.vz = vz
  w.ball.spin = 0
  w.ball.vSpin = 0
  w.ball.lastTouchId = -1
  return { w, p }
}

// One physics step with the given command.
function tick(w: World, cmd: Command = emptyCommand()) {
  w.update(SIM.dt, cmd)
}

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(32)} ${detail}`)

// ---------------------------------------------------------------- 1. cushion
{
  const { w, p } = setup(1.0, 9, -4)
  const cmd = emptyCommand()
  cmd.aim = { x: 1, y: 0 }
  cmd.kick = makeKick('touch', 0.2, { x: 1, y: 0 }, { loft: 0, spin: 0 })
  tick(w, cmd)
  const speed = Math.hypot(w.ball.vx, w.ball.vy)
  const dist = Math.hypot(w.ball.x - p.x, w.ball.y - p.y)
  ok(
    'cushion kills the pace',
    speed < 3 && dist < PLAYER.reach,
    `9.8 -> ${speed.toFixed(2)} m/s, z=${w.ball.z.toFixed(2)}, ${dist.toFixed(2)}m from feet`,
  )
}

// ------------------------------------------------------- 2. cushion-up (pop)
{
  const { w } = setup(1.0, 9, -4)
  const cmd = emptyCommand()
  cmd.aim = { x: 1, y: 0 }
  cmd.kick = makeKick('touch', 0.2, { x: 1, y: 0 }, { loft: 0.8, spin: 0 })
  tick(w, cmd)
  ok('cushion up keeps it airborne', w.ball.vz > 2, `vz = ${w.ball.vz.toFixed(2)} m/s`)
}

// ---------------------------------------------------------------- 3. volley
{
  const { w } = setup(0.9, 6, -3)
  const cmd = emptyCommand()
  cmd.aim = { x: 1, y: 0 }
  cmd.kick = makeKick('strike', 1.0, { x: 1, y: 0 }, { loft: 0.2, spin: 0 })
  tick(w, cmd)
  const speed = Math.hypot(w.ball.vx, w.ball.vy, w.ball.vz)
  ok('volley beats a ground strike', speed > 34, `${speed.toFixed(1)} m/s (ground max 34)`)
}

// ---------------------------------------------------------------- 4. header
{
  const { w } = setup(1.75, 5, -2)
  const cmd = emptyCommand()
  cmd.aim = { x: 1, y: 0 }
  cmd.kick = makeKick('strike', 1.0, { x: 1, y: 0 }, { loft: 0, spin: 0 })
  tick(w, cmd)
  const speed = Math.hypot(w.ball.vx, w.ball.vy, w.ball.vz)
  ok(
    'header rises and carries',
    w.ball.vz > 3 && speed > 12 && speed < 26,
    `${speed.toFixed(1)} m/s, vz ${w.ball.vz.toFixed(1)}`,
  )
}

// --------------------------------------------------------- 5. above reach
{
  const { w } = setup(2.6, 5, -2)
  const before = w.ball.vx
  const cmd = emptyCommand()
  cmd.aim = { x: 1, y: 0 }
  cmd.kick = makeKick('strike', 1.0, { x: 1, y: 0 }, { loft: 0, spin: 0 })
  tick(w, cmd)
  ok(
    'above reach sails over you',
    Math.abs(w.ball.vx - before) < 0.5,
    `vx ${before.toFixed(1)} -> ${w.ball.vx.toFixed(1)} at z=2.6`,
  )
}

// ------------------------------------------------------------ 6. skill moves
const flicks = [
  { name: 'drag back', flick: { x: 0, y: 160 }, check: 'behind' },
  { name: 'roll right', flick: { x: 160, y: 0 }, check: 'right' },
  { name: 'roll left', flick: { x: -160, y: 0 }, check: 'left' },
  { name: 'lift over', flick: { x: 0, y: -160 }, check: 'up' },
] as const
// Facing +x, screen-right (+flick.x) should send the ball to +y.
for (const f of flicks) {
  const skill = skillFromFlick(f.flick)
  const { w } = setup(0, 0, 0)
  // Shape the touch exactly the way the human controllers do.
  const shape = shapeFromFlick(f.flick)
  const cmd = emptyCommand()
  cmd.aim = { x: 1, y: 0 }
  cmd.kick = makeKick(
    'touch',
    0.5,
    { x: 1, y: 0 },
    { loft: Math.max(0, shape.loft), spin: shape.spin * 0.4 },
    skill,
  )
  tick(w, cmd)
  let pass = false
  let detail = ''
  if (f.check === 'behind') {
    pass = skill === 'drag' && w.ball.vx < -1
    detail = `skill=${skill} vx=${w.ball.vx.toFixed(1)} (want negative)`
  } else if (f.check === 'right' || f.check === 'left') {
    const want = f.check === 'right' ? 1 : -1
    pass = skill === 'roll' && Math.sign(w.ball.vy) === want && Math.abs(w.ball.vy) > Math.abs(w.ball.vx)
    detail = `skill=${skill} vx=${w.ball.vx.toFixed(1)} vy=${w.ball.vy.toFixed(1)} (want vy ${want > 0 ? '+' : '-'})`
  } else {
    pass = skill === 'lift' && w.ball.vz > 3 && w.ball.vx > 1
    detail = `skill=${skill} vz=${w.ball.vz.toFixed(1)} vx=${w.ball.vx.toFixed(1)}`
  }
  ok(f.name, pass, detail)
}

// The roll must be deterministic, not a coin flip on the aim's rounding.
{
  const sides = new Set<number>()
  for (let i = 0; i < 40; i++) {
    const { w } = setup(0, 0, 0)
    const shape = shapeFromFlick({ x: 160, y: 0 })
    const cmd = emptyCommand()
    cmd.aim = { x: 1, y: 0 }
    cmd.kick = makeKick('touch', 0.5, { x: 1, y: 0 }, { loft: 0, spin: shape.spin * 0.4 }, 'roll')
    tick(w, cmd)
    sides.add(Math.sign(w.ball.vy))
  }
  ok('roll side is deterministic', sides.size === 1, `40 rolls -> sides ${[...sides].join(',')}`)
}

// --------------------------------------- 7. small drift is NOT a skill move
{
  const skill = skillFromFlick({ x: 40, y: 20 })
  ok('aim drift stays a plain touch', skill === null, `skillFromFlick(40,20) = ${skill}`)
}

// -------------------------------------- 8. cushion -> volley, the full combo
{
  const { w } = setup(1.2, 11, -5)
  const c1 = emptyCommand()
  c1.aim = { x: 1, y: 0 }
  c1.kick = makeKick('touch', 0.2, { x: 1, y: 0 }, { loft: 0.8, spin: 0 })
  tick(w, c1)
  let volleyed = false
  let speed = 0
  let hang = 0
  for (let i = 0; i < 400; i++) {
    const cmd = emptyCommand()
    cmd.aim = { x: 1, y: 0 }
    if (!volleyed && w.ball.vz < 0 && w.ball.z < 1.0 && w.ball.z > 0.65) {
      cmd.kick = makeKick('strike', 1.0, { x: 1, y: 0 }, { loft: 0.3, spin: 0 })
      volleyed = true
      hang = i * SIM.dt
    }
    tick(w, cmd)
    if (volleyed) {
      speed = Math.hypot(w.ball.vx, w.ball.vy, w.ball.vz)
      break
    }
  }
  ok(
    'cushion then volley',
    volleyed && speed > 30,
    `hung ${hang.toFixed(2)}s, volleyed at ${speed.toFixed(1)} m/s`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
