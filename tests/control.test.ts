// Nothing sticks to anybody, and the slide is precision only.
import { World } from '../src/game/match/world'
import { makeKick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { SIM, FIELD, DEFEND, PLAYER, BALL } from '../src/game/config'
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

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(40)} ${detail}`)

const CX = FIELD.length / 2
const CY = FIELD.width / 2

function stage(heading = 0) {
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = CX
  p.y = CY
  p.vx = 0
  p.vy = 0
  p.heading = heading
  p.kickCooldown = 0
  p.tackleCooldown = 0
  p.slideRecover = 0
  return { w, p }
}

// ---- 1. a pass at you is NOT trapped ---------------------------------------
{
  const { w, p } = stage()
  // Fire a firm ball straight at the player from in front.
  w.ball.setPos(CX + 6, CY, 0)
  w.ball.vx = -14
  w.ball.vy = 0
  w.ball.vSpin = -14
  w.ball.lastTouchId = 99
  // The tell for an auto-trap is the ball ending up sitting at your feet. Speed
  // at the moment of contact is the wrong thing to look at — the ball rebounds,
  // so the closest sample is taken *after* the collision either way.
  let arrival = 0
  for (let i = 0; i < 120 * 3; i++) {
    w.update(SIM.dt, emptyCommand())
    const d = Math.hypot(w.ball.x - p.x, w.ball.y - p.y)
    if (!arrival && d < 1.2) arrival = w.ball.horizontalSpeed
  }
  const rest = Math.hypot(w.ball.x - p.x, w.ball.y - p.y)
  ok(
    'a ball played at you is not trapped',
    arrival > 8 && rest > PLAYER.reach,
    `reached you at ${arrival.toFixed(1)} m/s and finished ${rest.toFixed(1)} m away ` +
      `(a trap would leave it under ${PLAYER.reach} m at ~0 m/s)`,
  )
}

// ---- 2. ...it bounces off you ----------------------------------------------
{
  const { w, p } = stage()
  w.ball.setPos(CX + 5, CY + 0.15, 0)
  w.ball.vx = -12
  w.ball.vSpin = -12
  w.ball.lastTouchId = 99
  let bouncedBack = false
  for (let i = 0; i < 120 * 3; i++) {
    w.update(SIM.dt, emptyCommand())
    if (w.ball.vx > 1) bouncedBack = true
    if (bouncedBack) break
  }
  ok('and rebounds off the body', bouncedBack, `ball came back off the player at ${w.ball.vx.toFixed(1)} m/s`)
  void p
}

// ---- 3. a touch still controls it ------------------------------------------
{
  const { w, p } = stage()
  w.ball.setPos(CX + 0.6, CY, 0.9)
  w.ball.vx = -9
  w.ball.vz = -4
  w.ball.lastTouchId = 99
  const cmd: Command = emptyCommand()
  cmd.aim = { x: 1, y: 0 }
  cmd.kick = makeKick('touch', 0.2, { x: 1, y: 0 }, { loft: 0, spin: 0 })
  w.update(SIM.dt, cmd)
  const speed = w.ball.horizontalSpeed
  ok(
    'but a right click still kills it',
    speed < 3,
    `9.8 m/s -> ${speed.toFixed(2)} m/s — control is something you do, not something you get`,
  )
  void p
}

// ---- 4. the slide reaches a ball inside the body ---------------------------
{
  const { w, p } = stage()
  // Ball a metre ahead, on the deck, dead in line.
  w.ball.setPos(CX + 1.0, CY, 0)
  w.ball.stop()
  w.ball.lastTouchId = 99
  const cmd = emptyCommand()
  cmd.move = { x: 1, y: 0 }
  cmd.aim = { x: 1, y: 0 }
  cmd.slide = true
  w.update(SIM.dt, cmd)
  let won = false
  for (let i = 0; i < 120; i++) {
    w.update(SIM.dt, emptyCommand())
    if (p.slideWon) {
      won = true
      break
    }
  }
  ok(
    'a slide through the ball wins it',
    won && w.ball.horizontalSpeed > 6,
    `won=${won}, cleared at ${w.ball.horizontalSpeed.toFixed(1)} m/s`,
  )
}

// ---- 5. ...and misses one outside it ---------------------------------------
{
  const { w, p } = stage()
  // Same slide, but the ball is off to the side by more than a body's width.
  w.ball.setPos(CX + 1.0, CY + 1.4, 0)
  w.ball.stop()
  w.ball.lastTouchId = 99
  const cmd = emptyCommand()
  cmd.move = { x: 1, y: 0 }
  cmd.aim = { x: 1, y: 0 }
  cmd.slide = true
  w.update(SIM.dt, cmd)
  for (let i = 0; i < 120; i++) w.update(SIM.dt, emptyCommand())
  ok(
    'a slide past the ball misses it',
    !p.slideWon && w.ball.horizontalSpeed < 0.5,
    `1.4 m off line (body is ${(PLAYER.radius + BALL.radius).toFixed(2)} m): won=${p.slideWon}`,
  )
}

// ---- 6. missing a slide leaves you beaten ----------------------------------
{
  const { w, p } = stage()
  w.ball.setPos(CX + 8, CY + 8, 0)
  w.ball.stop()
  const cmd = emptyCommand()
  cmd.move = { x: 1, y: 0 }
  cmd.aim = { x: 1, y: 0 }
  cmd.slide = true
  w.update(SIM.dt, cmd)
  let downFor = 0
  for (let i = 0; i < 120 * 3; i++) {
    w.update(SIM.dt, emptyCommand())
    if (p.slideRecover > 0) downFor += SIM.dt
    else break
  }
  ok(
    'a missed slide costs you real time',
    downFor > 1.0,
    `${downFor.toFixed(2)}s out of the play (slide ${DEFEND.slideTime}s + recovery ${DEFEND.slideRecovery}s)`,
  )
}

// ---- 7. a slide can't be spammed -------------------------------------------
{
  const { w, p } = stage()
  const cmd = emptyCommand()
  cmd.move = { x: 1, y: 0 }
  cmd.aim = { x: 1, y: 0 }
  cmd.slide = true
  w.update(SIM.dt, cmd)
  const first = p.slideTimer
  // Try to slide again immediately.
  w.update(SIM.dt, cmd)
  ok(
    'you cannot chain slides',
    p.tackleCooldown > 0 && p.slideTimer <= first,
    `cooldown ${p.tackleCooldown.toFixed(2)}s still running`,
  )
}

// ---- 8. a slide can't scoop a ball out of the air --------------------------
{
  const { w, p } = stage()
  w.ball.setPos(CX + 1.0, CY, 1.4) // head height
  w.ball.vx = 0
  w.ball.vy = 0
  w.ball.vz = 0
  w.ball.lastTouchId = 99
  const cmd = emptyCommand()
  cmd.move = { x: 1, y: 0 }
  cmd.aim = { x: 1, y: 0 }
  cmd.slide = true
  w.update(SIM.dt, cmd)
  const wonEarly = p.slideWon
  ok(
    'a slide cannot reach a ball above the body',
    !wonEarly,
    `ball at 1.4 m, slide reaches ${DEFEND.slideBallHeight} m`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
