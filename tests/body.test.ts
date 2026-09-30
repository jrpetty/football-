// The ball is controlled by clicks, not by bodies.
//
// A solid body used to push the ball out of overlap every step and hand it a
// third of its own velocity on top, so sprinting into a ball at rest shoved it
// eleven metres at ten metres a second with nothing pressed. Nothing was
// attached to anybody, and it played exactly as though it were.
//
// The rule now is one line — a body may take pace off the ball and may never put
// any on — and it is tested from both sides: bodies must no longer move a ball
// that is not moving, and must still stop one that is.
import { World } from '../src/game/match/world'
import { makeKick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { FIELD, SHIELD, BODY } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(54)} ${detail}`)

const cfg = {
  teamSize: 4, halfLength: 6000, mode: 'match' as const, singleKeeper: false,
  view: '3d' as const, quality: 'medium' as const, position: 'MID' as const,
  heightSens: 1.6, curveSens: 1.15, humanControlled: true,
}
const DT = 1 / 120
const GY = FIELD.width / 2
const world = (mode: 'match' | 'training' = 'training') => {
  const w = new World({ ...cfg, mode })
  w.phase = 'playing'
  return w
}
const run = (dx: number, dy: number, sprint = false, walk = false): Command => {
  const c = emptyCommand()
  c.move = { x: dx, y: dy }
  c.aim = { x: dx, y: dy }
  c.sprint = sprint
  c.walk = walk
  return c
}

// ---- 1. running into a ball does nothing -------------------------------------
{
  let worst = 0
  let cases = 0
  const label: string[] = []
  for (const [name, sprint, walk] of [['sprint', true, false], ['run', false, false], ['walk', false, true]] as const) {
    for (const off of [0, 0.3, 0.5, -0.4]) {
      const w = world()
      const p = w.getControlledPlayer()!
      p.x = 12; p.y = GY; p.heading = 0
      w.ball.setPos(20, GY + off, 0)
      w.ball.stop()
      const sx = w.ball.x, sy = w.ball.y
      // Long enough to run well past it.
      for (let i = 0; i < 120 * 4; i++) w.update(DT, run(1, 0, sprint, walk))
      worst = Math.max(worst, Math.hypot(w.ball.x - sx, w.ball.y - sy))
      cases++
      if (off === 0) label.push(`${name} ${p.x - sx > 2 ? 'passed it' : 'still short'}`)
    }
  }
  ok(
    'running into a ball at rest does not move it',
    worst < 1e-9,
    `${cases} runs (sprint / run / walk, dead-on and off-centre): the ball moved ${worst.toFixed(6)} m in every one`,
  )
}

// ---- 2. a body can never add pace ---------------------------------------------
{
  let seed = 11
  const rnd = () => ((seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296)
  let gained = 0
  let worst = 0
  const N = 600
  for (let i = 0; i < N; i++) {
    const w = world()
    const p = w.getControlledPlayer()!
    p.x = 25; p.y = GY
    const a = rnd() * Math.PI * 2
    const pv = rnd() * 8.4
    p.vx = Math.cos(a) * pv; p.vy = Math.sin(a) * pv
    const b = rnd() * Math.PI * 2
    const off = 0.15 + rnd() * 0.5
    w.ball.setPos(p.x + Math.cos(b) * off, p.y + Math.sin(b) * off, 0)
    const bv = rnd() * 14
    const va = rnd() * Math.PI * 2
    w.ball.vx = Math.cos(va) * bv; w.ball.vy = Math.sin(va) * bv; w.ball.vz = 0
    w.ball.spin = 0; w.ball.vSpin = 0
    const before = w.ball.speed
    w.update(DT, run(Math.cos(a), Math.sin(a), pv > 6))
    const d = w.ball.speed - before
    if (d > 0.05) gained++
    worst = Math.max(worst, d)
  }
  ok(
    'and a body can never put pace on the ball',
    gained === 0,
    `${gained}/${N} random collisions (any body speed, any ball, any angle) left it faster; worst gain ${worst.toFixed(2)} m/s`,
  )
}

// ---- 3. but bodies still stop a ball that is coming ---------------------------
{
  // A wall of players must still block a pass and a defender still deflect a
  // shot. Taking pace off is exactly what a body is for.
  const w = world('match')
  const d = w.players.find((p) => p.team === 'away' && p.role !== 'GK')!
  d.x = 34; d.y = GY
  const me = w.getControlledPlayer()!
  me.x = 5; me.y = 5
  w.ball.setPos(26, GY, 0)
  w.ball.vx = 12; w.ball.vy = 0; w.ball.vz = 0
  let through = false
  let peakBack = 0
  for (let i = 0; i < 120 * 2; i++) {
    w.update(DT, emptyCommand())
    if (w.ball.x > d.x + 0.6) through = true
    peakBack = Math.max(peakBack, -w.ball.vx)
  }
  ok(
    'a pass into a defender is still blocked',
    !through && peakBack > 1,
    `12 m/s at a standing defender: it never got past him and came back at ${peakBack.toFixed(1)} m/s`,
  )
  ok(
    'and comes off him softer than it arrived',
    peakBack < 12 * BODY.restitution * 1.15,
    `${peakBack.toFixed(1)} m/s back off a ${BODY.restitution} restitution body — it lost ${(100 - (peakBack / 12) * 100).toFixed(0)}% of its pace`,
  )
}

// ---- 4. clicks are what move it -------------------------------------------------
{
  // Same run, twice: once tapping the right button every so often, once not.
  const play = (taps: boolean) => {
    const w = world()
    const p = w.getControlledPlayer()!
    p.x = 10; p.y = GY; p.heading = 0
    w.ball.setPos(10.8, GY, 0)
    w.ball.stop()
    const sx = w.ball.x
    let maxGap = 0
    let landed = 0
    for (let i = 0; i < 120 * 4; i++) {
      const c = run(1, 0, false)
      if (taps && i % 48 === 0) {
        // A tap, straight ahead, and a light one.
        c.kick = makeKick('touch', 0.15, { x: 1, y: 0 }, { loft: 0, spin: 0 })
      }
      const hadKick = !!c.kick
      const bx = w.ball.x
      w.update(DT, c)
      if (hadKick && Math.abs(w.ball.x - bx) > 1e-6) landed++
      maxGap = Math.max(maxGap, Math.hypot(w.ball.x - p.x, w.ball.y - p.y))
    }
    return { moved: w.ball.x - sx, ahead: w.ball.x - p.x, maxGap, landed, run: p.x - 10 }
  }
  const tapped = play(true)
  const bare = play(false)
  ok(
    'tapping the ball along moves it with you',
    tapped.moved > 15 && tapped.maxGap < 3,
    `four seconds of running and tapping: the ball went ${tapped.moved.toFixed(1)} m and was never more than ${tapped.maxGap.toFixed(1)} m from you`,
  )
  ok(
    'and without the taps it is left where it lay',
    bare.moved === 0 && bare.ahead < -10,
    `the same run with no clicks: the ball moved ${bare.moved.toFixed(1)} m and you finished ${(-bare.ahead).toFixed(0)} m past it`,
  )
}

// ---- 5. shielding is a body, and it still does not carry the ball ----------------
{
  // Walking into a ball with your shoulder across it used to take it with you —
  // the shield gave the ball 85% of your own movement.
  const w = world()
  const p = w.getControlledPlayer()!
  p.x = 20; p.y = GY; p.heading = 0
  w.ball.setPos(21.2, GY, 0)
  w.ball.stop()
  const sx = w.ball.x
  const c = run(1, 0, false)
  c.shield = true
  for (let i = 0; i < 120 * 3; i++) w.update(DT, c)
  ok(
    'shielding does not carry a ball along with you',
    Math.abs(w.ball.x - sx) < 1e-9,
    `shuffled ${(p.x - 20).toFixed(1)} m forward with the ball at your feet: it moved ${(w.ball.x - sx).toFixed(3)} m`,
  )

  // What a shield does do is take the pace off something arriving. Measured
  // against an ordinary body taking the very same ball, so it is the shield that
  // is being tested and not the bounce in general.
  const bounce = (shield: boolean) => {
    const w2 = world()
    const q = w2.getControlledPlayer()!
    q.x = 20; q.y = GY; q.heading = 0
    const cmd = emptyCommand()
    cmd.shield = shield
    cmd.aim = { x: 0, y: 1 }
    w2.ball.setPos(21.6, GY, 0)
    w2.ball.stop()
    // Settle into the shield stance with the ball within reach.
    for (let i = 0; i < 40; i++) w2.update(DT, cmd)
    const stance = q.shielding
    w2.ball.setPos(23, GY, 0)
    w2.ball.vx = -9; w2.ball.vy = 0; w2.ball.vz = 0
    // Only the speed it leaves at counts: while it is still arriving it is 9.
    let leaves = 0
    let hit = false
    for (let i = 0; i < 120; i++) {
      w2.update(DT, cmd)
      if (w2.ball.vx > 0) { hit = true; leaves = Math.max(leaves, w2.ball.speed) }
    }
    return { stance, leaves, hit }
  }
  const soft = bounce(true)
  const hard = bounce(false)
  ok(
    'but it still deadens a ball that hits it',
    soft.stance && soft.hit && hard.hit && soft.leaves < hard.leaves * 0.5,
    `the same 9 m/s ball comes off a shielding body at ${soft.leaves.toFixed(1)} m/s and off an ordinary one at ${hard.leaves.toFixed(1)} m/s`,
  )
}

// ---- 6. a touch still puts the ball exactly where the click says --------------
{
  const w = world()
  const p = w.getControlledPlayer()!
  p.x = 20; p.y = GY; p.heading = 0
  w.ball.setPos(19.4, GY + 0.5, 0) // behind and to the side: not where a body would ever have put it
  w.ball.stop()
  const c = emptyCommand()
  c.aim = { x: 1, y: 0 }
  c.kick = makeKick('touch', 0.4, { x: 1, y: 0 }, { loft: 0, spin: 0 })
  w.update(DT, c)
  ok(
    'a click plays it wherever it is, in the direction you aim',
    w.ball.vx > 3 && Math.abs(w.ball.vy) < 0.5,
    `a ball behind and beside you, touched straight ahead: it left at ${w.ball.vx.toFixed(1)} m/s, ${w.ball.vy.toFixed(2)} sideways`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
