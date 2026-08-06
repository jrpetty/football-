// Keeping as a role you play. The point of these is that a human keeper saves
// nothing by standing there — the dive has to be aimed, timed and committed to.
import { World } from '../src/game/match/world'
import { makeKick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { SIM, FIELD, GK } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis

const cfg = {
  teamSize: 3,
  halfLength: 6000,
  mode: 'match' as const,
  singleKeeper: false,
  view: '3d' as const,
  position: 'GK' as const,
  heightSens: 1.6,
  curveSens: 1.15,
  humanControlled: true,
}

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(44)} ${detail}`)

// You, in your own goal. Everyone else is parked out of the way.
function stage() {
  const w = new World(cfg)
  w.phase = 'playing'
  const me = w.getControlledPlayer()!
  for (const p of w.players) {
    if (p.id === me.id) continue
    p.x = 2
    p.y = 2
    p.vx = p.vy = 0
  }
  me.x = 1.6
  me.y = FIELD.width / 2
  me.vx = me.vy = 0
  me.heading = 0
  me.kickCooldown = 0
  me.tackleCooldown = 0
  return { w, me }
}

// Fire a shot at a point in the goal mouth and report whether it went in.
function shotAt(
  yOffset: number,
  z: number,
  keeper: (t: number, w: World) => Command,
  power = 14,
) {
  const { w, me } = stage()
  const gy = FIELD.width / 2 + yOffset
  // Launch from 16 m out, aimed at (goal line, gy, z).
  const fromX = 17
  const t = (fromX - 0.2) / power
  w.ball.setPos(fromX, FIELD.width / 2, 0.3)
  w.ball.vx = -power
  w.ball.vy = (gy - FIELD.width / 2) / t
  w.ball.vz = (z - 0.3) / t + 0.5 * SIM.gravity * t
  w.ball.spin = 0
  w.ball.vSpin = 0
  w.ball.lastTouchTeam = 'away'
  w.ball.lastTouchId = 99
  let clock = 0
  const before = w.score.away
  for (let i = 0; i < 120 * 4; i++) {
    w.update(SIM.dt, keeper(clock, w))
    clock += SIM.dt
    if (w.score.away > before) return { conceded: true, saved: false, me, w }
    if (w.possessorId === me.id) return { conceded: false, saved: true, me, w }
    // A parry sends it back upfield.
    if (w.ball.vx > 2 && w.ball.x > 3) return { conceded: false, saved: true, me, w }
  }
  return { conceded: false, saved: false, me, w }
}

const idle = (): Command => emptyCommand()

// Dive at a fixed moment, in a direction, at a height.
const diveAt = (when: number, dir: { x: number; y: number }, power: number, loft: number) => {
  let fired = false
  return (t: number): Command => {
    const c = emptyCommand()
    c.aim = dir
    if (!fired && t >= when) {
      fired = true
      c.kick = makeKick('strike', power, dir, { loft, spin: 0 })
    }
    return c
  }
}

// ---- 1. standing still saves nothing ---------------------------------------
{
  const r = shotAt(2.6, 0.4, idle)
  ok(
    'a keeper who does nothing concedes',
    r.conceded,
    'shot into the corner, no dive: it went in',
  )
}

// ---- 2. a well-timed dive saves it -----------------------------------------
{
  // Sweep the release moment — the drill is timing, so the test has to allow a
  // window rather than demand one exact frame.
  let saves = 0
  let firstAt = -1
  for (let when = 0.6; when <= 1.15; when += 0.05) {
    const r = shotAt(2.6, 0.4, diveAt(when, { x: -0.1, y: 1 }, 0.85, -0.6))
    if (r.saved) {
      saves++
      if (firstAt < 0) firstAt = when
    }
  }
  ok(
    'diving the right way at the right time saves it',
    saves > 0,
    `${saves} of 12 release times saved it (earliest ${firstAt.toFixed(2)}s)`,
  )
}

// ---- 3. diving the wrong way does not ---------------------------------------
{
  let saves = 0
  for (let when = 0.6; when <= 1.15; when += 0.05) {
    const r = shotAt(2.6, 0.4, diveAt(when, { x: -0.1, y: -1 }, 0.85, -0.6))
    if (r.saved) saves++
  }
  ok('diving the wrong way saves nothing', saves === 0, `0 of 12 wrong-way dives reached it`)
}

// ---- 4. height matters too --------------------------------------------------
{
  // A high shot, met with a dive aimed along the floor.
  let low = 0
  let high = 0
  for (let when = 0.6; when <= 1.15; when += 0.05) {
    if (shotAt(2.4, 2.1, diveAt(when, { x: -0.1, y: 1 }, 0.85, -1)).saved) low++
    if (shotAt(2.4, 2.1, diveAt(when, { x: -0.1, y: 1 }, 0.85, 1)).saved) high++
  }
  ok(
    'the flick decides how high you reach',
    high > low,
    `shot at 2.1 m: flick up saved ${high}/12, flick down saved ${low}/12`,
  )
}

// ---- 5. the dive is a real commitment ---------------------------------------
{
  const { w, me } = stage()
  const c = emptyCommand()
  c.aim = { x: 0, y: 1 }
  c.kick = makeKick('strike', 1, { x: 0, y: 1 }, { loft: 0, spin: 0 })
  w.update(SIM.dt, c)
  const diving = me.diving
  let down = 0
  for (let i = 0; i < 120 * 3; i++) {
    w.update(SIM.dt, emptyCommand())
    if (me.diving || me.diveRecover > 0) down += SIM.dt
    else break
  }
  ok(
    'a full dive puts you on the floor for real time',
    diving && down > 1.0,
    `${down.toFixed(2)}s committed (dive up to ${GK.diveTimeMax}s + ${GK.diveRecovery}s recovery)`,
  )
}

// ---- 6. a keeper still distributes with the same button ---------------------
{
  const { w, me } = stage()
  w.ball.setPos(me.x, me.y, 0)
  w.ball.stop()
  w.possessorId = me.id
  w.keeperHold = 1.0
  w.keeperHoldId = me.id
  w.ball.lastTouchId = me.id
  me.kickCooldown = 0
  const c = emptyCommand()
  c.aim = { x: 1, y: 0 }
  c.kick = makeKick('strike', 0.9, { x: 1, y: 0 }, { loft: 0.6, spin: 0 })
  w.update(SIM.dt, c)
  ok(
    'with the ball in hand, left click still kicks it',
    w.ball.speed > 15 && !me.diving,
    `distributed at ${w.ball.speed.toFixed(1)} m/s instead of diving`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
