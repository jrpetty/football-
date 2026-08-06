// Ball-flight audit. Every number here is measured off the real simulation and
// printed next to what a real football does, so tuning arguments are settled by
// comparison rather than by feel.
import { World } from '../src/game/match/world'
import { makeKick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { SIM, FIELD, BALL } from '../src/game/config'

// The sound layer is a no-op without a user gesture, but the goal fanfare still
// reaches for window.setTimeout. Node has neither, so stub the one call.
;(globalThis as unknown as { window: unknown }).window = globalThis

const cfg = {
  teamSize: 1,
  halfLength: 6000,
  mode: 'training' as const,
  singleKeeper: false,
  view: '3d' as const,
  position: 'MID' as const,
  heightSens: 1.6,
  curveSens: 1.15,
  humanControlled: true,
}

interface Flight {
  release: number // m/s off the boot
  apex: number // m
  carry: number // m travelled before the first bounce
  hang: number // s in the air
  range: number // m travelled before it stops (or leaves the pitch)
  bend: number // m of lateral deviation vs an identical spinless ball
}

// Strike from deep in your own half, aimed straight down the pitch.
function strike(power: number, loft: number, spin: number, fromX = 4): Flight {
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = fromX
  p.y = FIELD.width / 2
  p.vx = 0
  p.vy = 0
  p.heading = 0
  p.kickCooldown = 0
  w.ball.setPos(fromX + 0.55, FIELD.width / 2, 0)
  w.ball.vx = 0
  w.ball.vy = 0
  w.ball.vz = 0
  w.ball.lastTouchId = -1

  const cmd: Command = emptyCommand()
  cmd.aim = { x: 1, y: 0 }
  cmd.kick = makeKick('strike', power, { x: 1, y: 0 }, { loft, spin })
  w.update(SIM.dt, cmd)

  const x0 = w.ball.x
  const y0 = w.ball.y
  const release = Math.hypot(w.ball.vx, w.ball.vy, w.ball.vz)
  let apex = 0
  let carry = 0
  let hang = 0
  let airborne = false // it has to leave the ground before it can land
  let landed = false
  let t = 0
  let range = 0
  let lastX = x0

  for (let i = 0; i < 120 * 20; i++) {
    w.update(SIM.dt, emptyCommand())
    // A goal resets the ball to the centre spot, which would corrupt the trace.
    if (w.phase !== 'playing') break
    t += SIM.dt
    apex = Math.max(apex, w.ball.z)
    if (!airborne && w.ball.z > 0.25) airborne = true
    if (airborne && !landed && w.ball.z <= 0.02) {
      landed = true
      hang = t
      carry = Math.hypot(w.ball.x - x0, w.ball.y - y0)
    }
    // Stop measuring range once it hits a wall — the rebound isn't travel.
    if (w.ball.x < lastX - 0.05) break
    lastX = w.ball.x
    range = Math.hypot(w.ball.x - x0, w.ball.y - y0)
    if (w.ball.speed < BALL.settleSpeed && (landed || !airborne)) break
  }
  // A ball struck along the ground never "lands" — its carry is how far it ran.
  if (!airborne) {
    carry = range
    hang = 0
  } else if (!landed) {
    hang = t
    carry = range
  }

  // Bend: the same ball with the spin removed, so the number is deviation
  // caused by the spin rather than by the aim.
  let bend = 0
  if (spin !== 0) {
    const twin = strike(power, loft, 0, fromX)
    void twin
    const w2 = new World(cfg)
    const q = w2.getControlledPlayer()!
    q.x = fromX
    q.y = FIELD.width / 2
    q.heading = 0
    q.kickCooldown = 0
    w2.ball.setPos(fromX + 0.55, FIELD.width / 2, 0)
    w2.ball.lastTouchId = -1
    const c2: Command = emptyCommand()
    c2.aim = { x: 1, y: 0 }
    c2.kick = makeKick('strike', power, { x: 1, y: 0 }, { loft, spin: 0 })
    w2.update(SIM.dt, c2)
    for (let i = 0; i < 120 * 20; i++) {
      w2.update(SIM.dt, emptyCommand())
      if (w2.ball.x >= w.ball.x) break
    }
    bend = Math.abs(w.ball.y - w2.ball.y)
  }
  return { release, apex, carry, hang, range, bend }
}

const f = (n: number, d = 1) => n.toFixed(d).padStart(6)
const line = (label: string, fl: Flight, real: string) =>
  console.log(
    `${label.padEnd(30)} ${f(fl.release)} m/s ${f(fl.apex)} m ${f(fl.carry)} m ${f(fl.hang, 2)} s   ${real}`,
  )

console.log(
  `${''.padEnd(30)} ${'release'.padStart(6)}     ${'apex'.padStart(6)}   ${'carry'.padStart(6)}   ${'hang'.padStart(6)}     real football`,
)
console.log('─'.repeat(112))

line('tap pass (10%)', strike(0.1, 0, 0), 'short square ball')
line('firm pass (40%)', strike(0.4, 0, 0), '~18-22 m/s driven')
line('driven strike (100%, flat)', strike(1, 0, 0), '30-35 m/s, record 38')
line('driven low (100%, flick down)', strike(1, -1, 0), 'dips late, skids on')
line('half-lofted (70%, half flick)', strike(0.7, 0.5, 0), '')
line('LONG BALL (100%, full flick)', strike(1, 1, 0), '50-70 m carry, 3-4 s hang, 12-20 m apex')
line('chip (35%, full flick)', strike(0.35, 1, 0), 'soft, floats, ~15 m')

console.log('\ncurve (lateral deviation vs the same ball with no spin):')
for (const [label, power, loft, spin] of [
  ['bent pass (50%)', 0.5, 0, 1],
  ['whipped cross (75%)', 0.75, 0.55, 1.3],
  ['full curler (100%)', 1, 0.35, 1.6],
] as const) {
  const fl = strike(power, loft, spin)
  console.log(
    `  ${label.padEnd(28)} bend ${fl.bend.toFixed(2)} m over ${fl.carry.toFixed(1)} m` +
      `   (a real free kick bends 3-5 m over 20-25 m)`,
  )
}

// ---- rolling and bouncing --------------------------------------------------
console.log('\nrolling and bouncing:')
{
  // How far does a rolled ball actually travel?
  for (const v of [8, 15, 22]) {
    const w = new World(cfg)
    // Park the training player off the ball's line — standing in the way, they
    // trap it and the roll reads as friction that isn't there.
    const p = w.getControlledPlayer()!
    p.x = 4
    p.y = 3
    w.ball.setPos(3, FIELD.width / 2, 0)
    w.ball.vx = v
    w.ball.vy = 0
    w.ball.vz = 0
    w.ball.lastTouchId = 99
    const x0 = w.ball.x
    let t = 0
    for (let i = 0; i < 120 * 30; i++) {
      w.update(SIM.dt, emptyCommand())
      t += SIM.dt
      if (w.ball.speed < BALL.settleSpeed) break
      if (w.ball.x > FIELD.length - 1) break
    }
    console.log(
      `  rolled at ${String(v).padStart(2)} m/s -> ran ${(w.ball.x - x0).toFixed(1)} m in ${t.toFixed(
        1,
      )} s   (real grass: a 15 m/s pass runs ~40-60 m)`,
    )
  }

  // Bounce height from a 2 m drop — the FIFA test, roughly.
  const w = new World(cfg)
  w.ball.setPos(FIELD.length / 2, FIELD.width / 2, 2)
  w.ball.vx = 0
  w.ball.vy = 0
  w.ball.vz = 0
  w.ball.lastTouchId = 99
  let bounced = false
  let peak = 0
  for (let i = 0; i < 120 * 8; i++) {
    w.update(SIM.dt, emptyCommand())
    if (!bounced && w.ball.vz > 0) bounced = true
    if (bounced) peak = Math.max(peak, w.ball.z)
    if (bounced && w.ball.vz < 0 && w.ball.z < peak - 0.05) break
  }
  console.log(
    `  dropped from 2.00 m -> rebounds to ${peak.toFixed(2)} m` +
      `   (FIFA spec on steel: 1.15-1.55 m; on grass ~0.9-1.2 m)`,
  )
}

// ---- what does the flick actually buy you? ---------------------------------
console.log('\nloft sweep at full power (does the down-flick do anything?):')
for (const loft of [-1, -0.5, 0, 0.25, 0.5, 0.75, 1]) {
  const fl = strike(1, loft, 0)
  console.log(
    `  loft ${String(loft).padStart(5)}  apex ${fl.apex.toFixed(2).padStart(5)} m` +
      `  carry ${fl.carry.toFixed(1).padStart(5)} m  hang ${fl.hang.toFixed(2)} s`,
  )
}

// ---- how long does it take to cross the pitch? -----------------------------
console.log('\ntempo:')
{
  const fl = strike(1, 0.25, 0)
  console.log(
    `  a flat-ish full strike covers the ${FIELD.length} m pitch in about ${(
      FIELD.length / fl.release
    ).toFixed(2)} s of flight`,
  )
}
