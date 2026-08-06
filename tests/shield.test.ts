// Shielding: body positioning and nothing else. These assertions exist mostly
// to prove what shielding is NOT — it must not attract the ball, hold it, or
// follow it around. Everything it does has to come from the body being wide,
// soft, side-on and slow.
import { World } from '../src/game/match/world'
import { makeKick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { SIM, FIELD, SHIELD, PLAYER } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis

const cfg = {
  teamSize: 3,
  halfLength: 6000,
  mode: 'match' as const,
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
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(42)} ${detail}`)

const CX = FIELD.length / 2
const CY = FIELD.width / 2

// A live pitch with the human at the centre spot, everyone else parked far away
// unless a test moves them in.
function stage() {
  const w = new World(cfg)
  w.phase = 'playing'
  const me = w.getControlledPlayer()!
  for (const p of w.players) {
    p.x = 2
    p.y = 2
    p.vx = 0
    p.vy = 0
  }
  me.x = CX
  me.y = CY
  me.heading = 0
  me.kickCooldown = 0
  return { w, me }
}

const shieldCmd = (aim = { x: 1, y: 0 }): Command => {
  const c = emptyCommand()
  c.aim = aim
  c.shield = true
  return c
}

// ---- 1. shielding does not pull the ball toward you -------------------------
{
  const { w, me } = stage()
  // Ball sitting a metre and a half away, dead still, well inside reach.
  w.ball.setPos(CX + 1.3, CY, 0)
  w.ball.stop()
  w.ball.lastTouchId = 99
  const before = Math.hypot(w.ball.x - me.x, w.ball.y - me.y)
  for (let i = 0; i < 120 * 2; i++) w.update(SIM.dt, shieldCmd())
  const after = Math.hypot(w.ball.x - me.x, w.ball.y - me.y)
  ok(
    'shielding does not attract the ball',
    Math.abs(after - before) < 0.05 && w.ball.horizontalSpeed < 0.1,
    `ball stayed at ${after.toFixed(2)} m (was ${before.toFixed(2)}) and never moved`,
  )
}

// ---- 2. ...and does not carry it around either ------------------------------
{
  const { w, me } = stage()
  w.ball.setPos(CX + 1.3, CY, 0)
  w.ball.stop()
  w.ball.lastTouchId = 99
  const bx = w.ball.x
  // Walk away from it while shielding.
  const c = shieldCmd()
  c.move = { x: -1, y: 0 }
  for (let i = 0; i < 120 * 2; i++) w.update(SIM.dt, c)
  ok(
    'and does not drag it with you',
    Math.abs(w.ball.x - bx) < 0.05,
    `you moved ${(CX - me.x).toFixed(2)} m away; the ball moved ${Math.abs(w.ball.x - bx).toFixed(3)} m`,
  )
}

// ---- 3. the body deadens a ball instead of pinging it -----------------------
{
  const rebound = (shield: boolean) => {
    const { w, me } = stage()
    w.ball.setPos(me.x + 4, me.y, 0)
    w.ball.vx = -10
    w.ball.vSpin = -10
    w.ball.lastTouchId = 99
    const c = shield ? shieldCmd() : emptyCommand()
    c.aim = { x: 1, y: 0 }
    for (let i = 0; i < 120 * 2; i++) {
      w.update(SIM.dt, c)
      if (w.ball.vx > 0.05) break // it has come back off the body
    }
    return w.ball.horizontalSpeed
  }
  const hard = rebound(false)
  const soft = rebound(true)
  ok(
    'a shielding body deadens the ball',
    soft < hard * 0.55,
    `${hard.toFixed(1)} m/s off a running body vs ${soft.toFixed(1)} m/s off a shielding one`,
  )
}

// ---- 4. you cannot strike while shielding -----------------------------------
{
  const { w, me } = stage()
  w.ball.setPos(CX + 0.6, CY, 0)
  w.ball.stop()
  w.ball.lastTouchId = 99
  const c = shieldCmd()
  c.kick = makeKick('strike', 1, { x: 1, y: 0 }, { loft: 0, spin: 0 })
  w.update(SIM.dt, c)
  const struck = w.ball.horizontalSpeed
  ok('you cannot strike while shielding', struck < 1, `full-power strike produced ${struck.toFixed(2)} m/s`)
  void me
}

// ---- 5. ...but a touch still releases it ------------------------------------
{
  const { w } = stage()
  w.ball.setPos(CX + 0.6, CY, 0)
  w.ball.stop()
  w.ball.lastTouchId = 99
  const c = shieldCmd()
  c.kick = makeKick('touch', 0.6, { x: 1, y: 0 }, { loft: 0, spin: 0 })
  w.update(SIM.dt, c)
  ok(
    'but a touch knocks it out of the shield',
    w.ball.horizontalSpeed > 3,
    `touch produced ${w.ball.horizontalSpeed.toFixed(1)} m/s — the way out`,
  )
}

// ---- 6. shielding slows you right down --------------------------------------
{
  const top = (shield: boolean) => {
    const { w, me } = stage()
    w.ball.setPos(me.x + 1.0, me.y, 0)
    w.ball.stop()
    w.ball.lastTouchId = 99
    const c = shield ? shieldCmd() : emptyCommand()
    c.aim = { x: 1, y: 0 }
    c.move = { x: 0, y: 1 }
    c.sprint = true
    let peak = 0
    for (let i = 0; i < 120 * 4; i++) {
      // Keep the ball with them so the shield stays live.
      w.ball.setPos(me.x + 1.0, me.y, 0)
      w.update(SIM.dt, c)
      peak = Math.max(peak, me.speed)
    }
    return peak
  }
  const shielded = top(true)
  const free = top(false)
  ok(
    'shielding costs you your legs',
    shielded < free * 0.45 && shielded < SHIELD.speed + 0.2,
    `${shielded.toFixed(1)} m/s shielding vs ${free.toFixed(1)} m/s sprinting`,
  )
}

// ---- 7. the body actually blocks an opponent --------------------------------
{
  // Opponent, shielder and ball in a line: defender — shielder — ball.
  const contested = (shield: boolean) => {
    const { w, me } = stage()
    const foe = w.players.find((p) => p.team === 'away')!
    me.x = CX
    me.y = CY
    me.heading = Math.PI // side-on comes from the sim, not from us
    foe.x = CX - 1.1
    foe.y = CY
    w.ball.setPos(CX + 0.75, CY, 0)
    w.ball.stop()
    w.ball.lastTouchId = 99
    foe.kickCooldown = 0
    const cmds = new Map<number, Command>()
    const mine = shield ? shieldCmd({ x: -1, y: 0 }) : emptyCommand()
    // Give the defender a firm touch at the ball.
    const foeCmd = emptyCommand()
    foeCmd.aim = { x: 1, y: 0 }
    foeCmd.kick = makeKick('touch', 0.8, { x: 1, y: 0 }, { loft: 0, spin: 0 })
    void cmds
    // One step with both commands: the human's goes through update(), and the
    // opponent's is injected the same way the AI's would be.
    ;(w as unknown as { buildCommands: (c: Command, dt: number) => Map<number, Command> }).buildCommands
    const orig = (w as any).buildCommands.bind(w)
    ;(w as any).buildCommands = (c: Command, dt: number) => {
      const m = orig(c, dt)
      m.set(foe.id, foeCmd)
      return m
    }
    w.update(SIM.dt, mine)
    return w.ball.horizontalSpeed
  }
  const through = contested(false)
  const blocked = contested(true)
  ok(
    'an opponent cannot play a ball you are in front of',
    blocked < 0.5 && through > 3,
    `defender got ${through.toFixed(1)} m/s on it unshielded, ${blocked.toFixed(1)} m/s through a body`,
  )
}

// ---- 8. step aside and they have it -----------------------------------------
{
  const { w, me } = stage()
  const foe = w.players.find((p) => p.team === 'away')!
  me.x = CX
  me.y = CY + 1.6 // out of the line entirely
  foe.x = CX - 1.1
  foe.y = CY
  w.ball.setPos(CX + 0.75, CY, 0)
  w.ball.stop()
  w.ball.lastTouchId = 99
  foe.kickCooldown = 0
  const foeCmd = emptyCommand()
  foeCmd.aim = { x: 1, y: 0 }
  foeCmd.kick = makeKick('touch', 0.8, { x: 1, y: 0 }, { loft: 0, spin: 0 })
  const orig = (w as any).buildCommands.bind(w)
  ;(w as any).buildCommands = (c: Command, dt: number) => {
    const m = orig(c, dt)
    m.set(foe.id, foeCmd)
    return m
  }
  w.update(SIM.dt, shieldCmd({ x: -1, y: 0 }))
  ok(
    'stand in the wrong place and it is theirs',
    w.ball.horizontalSpeed > 3,
    `shielding ${(1.6).toFixed(1)} m off the line: defender took it at ${w.ball.horizontalSpeed.toFixed(1)} m/s`,
  )
}

// ---- 9. you can't shield a ball you haven't got ------------------------------
{
  const { w, me } = stage()
  w.ball.setPos(CX + 6, CY, 0) // miles away
  w.ball.stop()
  w.ball.lastTouchId = 99
  w.update(SIM.dt, shieldCmd())
  ok(
    'shielding needs the ball near you',
    !me.shielding,
    `ball ${(6).toFixed(0)} m away (reach is ${(me.radius + 0.11 + PLAYER.reach * 1.3).toFixed(2)} m)`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
