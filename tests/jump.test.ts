// Jumping. It exists for one reason — to raise everything you can reach — so
// that is what these check, along with the price of leaving the ground.
import { World } from '../src/game/match/world'
import { makeKick } from '../src/game/control/strike'
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
const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(40)} ${detail}`)

const CX = FIELD.length / 2
const CY = FIELD.width / 2

function stage() {
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = CX
  p.y = CY
  p.vx = p.vy = 0
  p.heading = 0
  p.kickCooldown = 0
  return { w, p }
}
const jumpCmd = (): Command => {
  const c = emptyCommand()
  c.aim = { x: 1, y: 0 }
  c.jump = true
  return c
}

// ---- 1. you actually leave the ground, by a human amount --------------------
{
  const { w, p } = stage()
  w.update(SIM.dt, jumpCmd())
  let peak = 0
  let hang = 0
  for (let i = 0; i < 120 * 3; i++) {
    w.update(SIM.dt, emptyCommand())
    peak = Math.max(peak, p.z)
    if (p.airborne) hang += SIM.dt
    else if (peak > 0) break
  }
  ok(
    'a jump gets you off the ground',
    peak > 0.5 && peak < 0.9 && hang > 0.5,
    `${peak.toFixed(2)} m up, ${hang.toFixed(2)}s of hang   (a footballer: ~0.6-0.8 m)`,
  )
}

// ---- 2. and everything you can reach comes with you ------------------------
{
  // A ball hanging just above standing reach.
  const height = PLAYER.aerialReach + 0.35
  const attempt = (jump: boolean) => {
    const { w, p } = stage()
    w.ball.setPos(p.x + 0.5, p.y, height)
    w.ball.vx = 0
    w.ball.vy = 0
    w.ball.vz = 0
    w.ball.lastTouchId = 99
    let struck = false
    for (let i = 0; i < 120 * 2; i++) {
      const c = emptyCommand()
      c.aim = { x: 1, y: 0 }
      if (jump && i === 0) c.jump = true
      // Keep the ball pinned there so only reach is being tested.
      w.ball.setPos(p.x + 0.5, p.y, height)
      w.ball.vz = 0
      if (i > 4) c.kick = makeKick('strike', 0.8, { x: 1, y: 0 }, { loft: 0, spin: 0 })
      w.update(SIM.dt, c)
      if (w.ball.horizontalSpeed > 3) {
        struck = true
        break
      }
    }
    return struck
  }
  ok(
    'a jump reaches a ball you cannot standing',
    !attempt(false) && attempt(true),
    `ball at ${height.toFixed(2)} m (standing reach is ${PLAYER.aerialReach}): stood — no contact, jumped — headed`,
  )
}

// ---- 3. in the air you cannot steer ----------------------------------------
{
  const { w, p } = stage()
  const c = jumpCmd()
  c.move = { x: 1, y: 0 }
  w.update(SIM.dt, c)
  const vx0 = p.vx
  // Now ask hard for the opposite direction while airborne.
  const back = emptyCommand()
  back.aim = { x: 1, y: 0 }
  back.move = { x: -1, y: 0 }
  back.sprint = true
  let maxChange = 0
  for (let i = 0; i < 120 * 2 && p.airborne; i++) {
    w.update(SIM.dt, back)
    maxChange = Math.max(maxChange, Math.abs(p.vx - vx0))
  }
  ok(
    'you cannot steer in mid-air',
    maxChange < 0.05,
    `asked for a full reversal while up: velocity changed by ${maxChange.toFixed(3)} m/s`,
  )
}

// ---- 4. and you cannot slide or shield off the ground -----------------------
{
  const { w, p } = stage()
  w.ball.setPos(p.x + 0.9, p.y, 0)
  w.ball.stop()
  w.ball.lastTouchId = 99
  w.update(SIM.dt, jumpCmd())
  const c = emptyCommand()
  c.aim = { x: 1, y: 0 }
  c.move = { x: 1, y: 0 }
  c.slide = true
  c.shield = true
  let slid = false
  let shielded = false
  for (let i = 0; i < 30; i++) {
    w.update(SIM.dt, c)
    if (p.sliding) slid = true
    if (p.shielding) shielded = true
  }
  ok(
    'no sliding or shielding in the air',
    !slid && !shielded && p.airborne,
    'both refused while off the ground',
  )
}

// ---- 5. landing costs you a beat -------------------------------------------
{
  const { w, p } = stage()
  w.update(SIM.dt, jumpCmd())
  for (let i = 0; i < 120 * 3 && p.airborne; i++) w.update(SIM.dt, emptyCommand())
  const canImmediately = p.canJump
  ok(
    'you cannot bunny-hop',
    !canImmediately && p.landTimer > 0,
    `${p.landTimer.toFixed(2)}s of gather on landing before the next one`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
