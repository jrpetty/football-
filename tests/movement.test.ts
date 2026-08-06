// Movement audit: top speeds, acceleration, braking and turning, each next to
// what a real footballer does. Momentum is the whole feel of the game, so it
// gets measured rather than eyeballed.
//
// Stamina is held full throughout. Fatigue is real and wanted, but it drops the
// speed ceiling mid-measurement, and a braking distance that quietly depends on
// how tired you were tells you nothing about the momentum model.
import { World } from '../src/game/match/world'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { SIM, FIELD, PLAYER } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis

const cfg = {
  teamSize: 1,
  halfLength: 6000,
  mode: 'training' as const,
  singleKeeper: false,
  view: '3d' as const,
  quality: 'medium' as const,
  position: 'MID' as const,
  heightSens: 1.6,
  curveSens: 1.15,
  humanControlled: true,
}

function fresh() {
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = 5
  p.y = FIELD.width / 2
  p.vx = 0
  p.vy = 0
  p.heading = 0
  return { w, p }
}

const drive = (dir: { x: number; y: number }, sprint = true, walk = false): Command => {
  const c = emptyCommand()
  c.move = dir
  c.aim = dir
  c.sprint = sprint
  c.walk = walk
  return c
}

console.log('player movement (stamina pinned full):')

// ---- acceleration ----
for (const [label, sprint, walk, ceiling] of [
  ['walk', false, true, PLAYER.walkSpeed],
  ['run', false, false, PLAYER.runSpeed],
  ['sprint', true, false, PLAYER.sprintSpeed],
] as const) {
  const { w, p } = fresh()
  let t = 0
  let t90 = 0
  for (let i = 0; i < 120 * 8; i++) {
    p.stamina = PLAYER.staminaMax
    w.update(SIM.dt, drive({ x: 1, y: 0 }, sprint, walk))
    t += SIM.dt
    if (!t90 && p.speed >= ceiling * 0.9) t90 = t
    if (t90) break
  }
  console.log(
    `  ${label.padEnd(7)} ${ceiling.toFixed(1)} m/s, 90% of it in ${t90.toFixed(2)}s` +
      `   (real: ~1.5-2.5s to top speed)`,
  )
}

// ---- braking ----
{
  const { w, p } = fresh()
  for (let i = 0; i < 120 * 4; i++) {
    p.stamina = PLAYER.staminaMax
    w.update(SIM.dt, drive({ x: 1, y: 0 }))
  }
  const from = p.speed
  const x0 = p.x
  let t = 0
  for (let i = 0; i < 120 * 4; i++) {
    p.stamina = PLAYER.staminaMax
    const c = emptyCommand()
    c.aim = { x: 1, y: 0 }
    w.update(SIM.dt, c)
    t += SIM.dt
    if (p.speed < 0.2) break
  }
  console.log(
    `  stopping from ${from.toFixed(1)} m/s: ${(p.x - x0).toFixed(2)} m in ${t.toFixed(2)}s` +
      `   (real: ~3-5 m)`,
  )
}

// ---- turning at pace ----
{
  const { w, p } = fresh()
  for (let i = 0; i < 120 * 4; i++) {
    p.stamina = PLAYER.staminaMax
    w.update(SIM.dt, drive({ x: 1, y: 0 }))
  }
  const from = p.speed
  // Hard left while at full pace: how tight is the arc?
  const path: { x: number; y: number }[] = []
  for (let i = 0; i < 120 * 3; i++) {
    p.stamina = PLAYER.staminaMax
    w.update(SIM.dt, drive({ x: 0, y: 1 }))
    path.push({ x: p.x, y: p.y })
    if (p.vy > p.speed * 0.95) break
  }
  const turnT = path.length * SIM.dt
  // Radius implied by the pace you held through it.
  const held = p.speed
  console.log(
    `  90° turn at ${from.toFixed(1)} m/s: took ${turnT.toFixed(2)}s, exited at ${held.toFixed(
      1,
    )} m/s   (real: ~0.6-1.0s, you lose pace)`,
  )
}

// ---- full reversal ----
{
  const { w, p } = fresh()
  for (let i = 0; i < 120 * 4; i++) {
    p.stamina = PLAYER.staminaMax
    w.update(SIM.dt, drive({ x: 1, y: 0 }))
  }
  const from = p.speed
  let t = 0
  let stopT = 0
  for (let i = 0; i < 120 * 8; i++) {
    p.stamina = PLAYER.staminaMax
    w.update(SIM.dt, drive({ x: -1, y: 0 }))
    t += SIM.dt
    if (!stopT && p.vx <= 0) stopT = t
    if (p.vx < -from * 0.8) break
  }
  console.log(
    `  full 180 from ${from.toFixed(1)} m/s: stopped at ${stopT.toFixed(2)}s, ` +
      `back to 80% pace at ${t.toFixed(2)}s   (real: ~1.5-2.5s)`,
  )
}

// ---- what fatigue costs you ----
{
  const { w, p } = fresh()
  let t = 0
  for (let i = 0; i < 120 * 30; i++) {
    w.update(SIM.dt, drive({ x: 1, y: 0 }))
    t += SIM.dt
    if (p.x > FIELD.length - 3) {
      p.x = 5
      p.vx = Math.min(p.vx, p.speed)
    }
    if (p.stamina <= 1) break
  }
  console.log(
    `  sprinting flat out drains you in ${t.toFixed(1)}s, dropping top speed to ` +
      `${p.topSpeed(true).toFixed(1)} m/s   (real: ~20-30s of repeated sprints)`,
  )
}
