// Training dummies and the free-kick wall.
//
// The point of a dummy is that going through it isn't available. A slalom you
// can walk over teaches nothing and a wall you can shoot through makes bending
// it pointless, so both halves of "solid" are worth pinning down: the ball
// comes off one, and so does a body.
//
// The other thing worth checking is placement. A free-kick wall is only worth
// bending round if it is genuinely between the spot and the goal — put it a
// metre to the side and it is scenery.
import { World } from '../src/game/match/world'
import { emptyCommand } from '../src/game/types'
import { DUMMY, FIELD } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(48)} ${detail}`)

const training = () =>
  new World({
    teamSize: 4,
    halfLength: 6000,
    mode: 'training',
    singleKeeper: false,
    view: '3d',
  quality: 'medium' as const,
    position: 'MID',
    heightSens: 1.6,
    curveSens: 1.15,
    humanControlled: true,
  })

// ---- 1. they exist, and only in training ------------------------------------
{
  const w = training()
  const slalom = w.drills!.dummies.filter((d) => d.kind === 'slalom')
  const wall = w.drills!.dummies.filter((d) => d.kind === 'wall')
  ok(
    'the training ground is set out',
    slalom.length >= 5 && wall.length === DUMMY.wallMen,
    `${slalom.length} slalom mannequins and a ${wall.length}-man wall`,
  )
}

{
  const w = new World({
    teamSize: 4, halfLength: 120, mode: 'match', singleKeeper: false,
    view: '3d',
  quality: 'medium' as const, position: 'MID', heightSens: 1.6, curveSens: 1.15, humanControlled: true,
  })
  ok(
    'and only in training',
    w.drills === null,
    `a match has no drills object at all, so no apparatus on the pitch`,
  )
}

// ---- 2. off to one side ------------------------------------------------------
{
  const w = training()
  const ys = w.drills!.dummies.map((d) => d.y)
  const worst = Math.max(...ys)
  ok(
    'and it is off to one side',
    worst < FIELD.width * 0.45,
    `every piece is within ${worst.toFixed(1)} m of the touchline, on a ${FIELD.width} m pitch`,
  )
}

{
  // The drills all work through the middle toward a goal. If the apparatus
  // reached into that corridor it would be an obstacle rather than a facility.
  const w = training()
  const inTheWay = w.drills!.dummies.filter((d) => Math.abs(d.y - FIELD.width / 2) < 6)
  ok(
    'and out of the way of the drills',
    inTheWay.length === 0,
    `nothing within 6 m of the centre line the gauntlet is played down`,
  )
}

// ---- 3. solid to the ball ----------------------------------------------------
{
  const w = training()
  const m = w.drills!.dummies.find((d) => d.kind === 'slalom')!
  // Roll it straight at one from 4 m out.
  w.ball.setPos(m.x - 4, m.y, 0)
  w.ball.vx = 9
  w.ball.vy = 0
  for (let i = 0; i < 120; i++) w.update(1 / 120, emptyCommand())
  ok(
    'a ball rolled at one comes back off it',
    w.ball.vx < 0 && w.ball.x < m.x,
    `9 m/s into it, ${w.ball.vx.toFixed(1)} m/s out — it never got past`,
  )
}

{
  const w = training()
  const m = w.drills!.dummies.find((d) => d.kind === 'slalom')!
  // Over the top is past it, the same rule the drill wall has. Fired from
  // close and fast, so it is still above the dummy's head when it gets there
  // rather than having dropped onto it on the way.
  w.ball.setPos(m.x - 1.5, m.y, DUMMY.height + 1)
  w.ball.vx = 14
  w.ball.vy = 0
  w.ball.vz = 0
  for (let i = 0; i < 60; i++) w.update(1 / 120, emptyCommand())
  ok(
    'but one clipped over the top is past it',
    w.ball.x > m.x,
    `sent over at ${(DUMMY.height + 1).toFixed(1)} m, and it is now ${(w.ball.x - m.x).toFixed(1)} m beyond`,
  )
}

{
  const w = training()
  const m = w.drills!.dummies.find((d) => d.kind === 'wall')!
  w.ball.setPos(m.x - 3, m.y, 0)
  w.ball.vx = 16
  // Peak, not the reading at some arbitrary moment — it starts righting itself
  // the instant it is knocked.
  let peak = 0
  for (let i = 0; i < 90; i++) {
    w.update(1 / 120, emptyCommand())
    peak = Math.max(peak, m.lean)
  }
  ok(
    'and it rocks when you hit it',
    peak > 0.05,
    `a 16 m/s strike leaned it ${((peak * 180) / Math.PI).toFixed(0)}°`,
  )
}

{
  const w = training()
  const m = w.drills!.dummies.find((d) => d.kind === 'wall')!
  w.ball.setPos(m.x - 3, m.y, 0)
  w.ball.vx = 16
  let peak = 0
  for (let i = 0; i < 90; i++) {
    w.update(1 / 120, emptyCommand())
    peak = Math.max(peak, m.lean)
  }
  for (let i = 0; i < 240; i++) w.update(1 / 120, emptyCommand())
  ok(
    'and then rights itself',
    peak > 0.05 && m.lean < 0.01,
    `leaned ${((peak * 180) / Math.PI).toFixed(0)}°, back upright a second and a half later`,
  )
}

// ---- 4. solid to a body ------------------------------------------------------
{
  const w = training()
  const p = w.getControlledPlayer()!
  const m = w.drills!.dummies.find((d) => d.kind === 'slalom')!
  p.x = m.x - 3
  p.y = m.y
  p.heading = 0
  const run = emptyCommand()
  run.move = { x: 1, y: 0 }
  run.aim = { x: 1, y: 0 }
  run.sprint = true
  for (let i = 0; i < 180; i++) w.update(1 / 120, run)
  const clearance = Math.hypot(p.x - m.x, p.y - m.y)
  ok(
    'you cannot run through one',
    clearance >= DUMMY.radius + p.radius - 0.05,
    `sprinted straight at it for 1.5 s and stayed ${clearance.toFixed(2)} m away (bodies touch at ${(DUMMY.radius + p.radius).toFixed(2)} m)`,
  )
}

{
  // Turned aside, not stopped dead — a dummy is something you go round.
  const w = training()
  const p = w.getControlledPlayer()!
  const m = w.drills!.dummies.find((d) => d.kind === 'slalom')!
  p.x = m.x - 3
  p.y = m.y - 0.25 // barely off-centre, as you would be weaving
  const run = emptyCommand()
  run.move = { x: 1, y: 0 }
  run.aim = { x: 1, y: 0 }
  run.sprint = true
  for (let i = 0; i < 240; i++) w.update(1 / 120, run)
  ok(
    'a glancing contact turns you rather than stopping you',
    p.x > m.x + 0.5,
    `clipped it off-centre and carried on, ending ${(p.x - m.x).toFixed(1)} m past it`,
  )
}

// ---- 5. the wall is where a wall belongs -------------------------------------
{
  const w = training()
  const spot = w.drills!.wallSpot!
  const wall = w.drills!.dummies.filter((d) => d.kind === 'wall')
  const mid = {
    x: wall.reduce((a, d) => a + d.x, 0) / wall.length,
    y: wall.reduce((a, d) => a + d.y, 0) / wall.length,
  }
  const dist = Math.hypot(mid.x - spot.x, mid.y - spot.y)
  ok(
    'the wall stands the regulation ten yards off',
    Math.abs(dist - DUMMY.wallDistance) < 0.05,
    `${dist.toFixed(2)} m from the spot`,
  )
}

{
  const w = training()
  const spot = w.drills!.wallSpot!
  const wall = w.drills!.dummies.filter((d) => d.kind === 'wall')
  const goal = { x: FIELD.length, y: FIELD.width / 2 }
  // How far the wall's centre sits off the straight line from spot to goal. If
  // it is not on that line, the straight ball is still on and there is nothing
  // to bend around.
  const dx = goal.x - spot.x
  const dy = goal.y - spot.y
  const len = Math.hypot(dx, dy)
  const mid = {
    x: wall.reduce((a, d) => a + d.x, 0) / wall.length,
    y: wall.reduce((a, d) => a + d.y, 0) / wall.length,
  }
  const off = Math.abs((mid.x - spot.x) * (dy / len) - (mid.y - spot.y) * (dx / len))
  ok(
    'and squarely in the way',
    off < 0.05,
    `its centre is ${(off * 100).toFixed(1)} cm off the line from the spot to the goal`,
  )
}

{
  // The straight ball has to be genuinely blocked: fire one at the goal along
  // that line and it must not get through.
  const w = training()
  const spot = w.drills!.wallSpot!
  const goal = { x: FIELD.length, y: FIELD.width / 2 }
  const dx = goal.x - spot.x
  const dy = goal.y - spot.y
  const len = Math.hypot(dx, dy)
  w.ball.setPos(spot.x, spot.y, 0)
  w.ball.vx = (dx / len) * 26
  w.ball.vy = (dy / len) * 26
  let blocked = false
  for (let i = 0; i < 240; i++) {
    w.update(1 / 120, emptyCommand())
    // Coming back toward the taker means the wall did its job.
    if (w.ball.vx * dx + w.ball.vy * dy < 0) blocked = true
  }
  ok(
    'so the straight ball is simply not on',
    blocked,
    `driven flat at the goal from the spot and it came back off the wall`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
