// The training gauntlet: every drill scores the thing it claims to teach, and
// refuses to score the thing it claims to punish.
import { World } from '../src/game/match/world'
import { makeKick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { SIM, FIELD } from '../src/game/config'
import { DRILL_ORDER } from '../src/game/match/drills'
import type { DrillId } from '../src/game/match/drills'
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

function stage(drill: DrillId, at = { x: 24, y: FIELD.width / 2 }) {
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  w.drills!.select(drill)
  p.x = at.x
  p.y = at.y
  p.vx = 0
  p.vy = 0
  p.heading = 0
  p.kickCooldown = 0
  return { w, p, d: w.drills! }
}

const run = (w: World, seconds: number, cmd: () => Command = emptyCommand) => {
  const n = Math.round(seconds / SIM.dt)
  for (let i = 0; i < n; i++) w.update(SIM.dt, cmd())
}

// ---- 1. every drill sets itself up and can be cycled ------------------------
{
  const { w, d } = stage('targets')
  const seen: string[] = []
  for (let i = 0; i < DRILL_ORDER.length; i++) seen.push(d.next())
  const props = DRILL_ORDER.map((id) => {
    d.select(id)
    return `${id}:${d.targets.length}t/${d.gates.length}g/${d.wall.length}w`
  })
  ok(
    'all six drills exist and cycle',
    seen.length === 6 && new Set(seen).size === 6,
    props.join('  '),
  )
  void w
}

// ---- 2. gates: a driven ball scores, a lofted one doesn't -------------------
{
  const drive = (loft: number) => {
    const { w, d, p } = stage('gates', { x: 24, y: FIELD.width / 2 - 6 })
    w.ball.setPos(p.x + 0.55, p.y, 0)
    w.ball.lastTouchId = -1
    const c = emptyCommand()
    c.aim = { x: 1, y: 0 }
    c.kick = makeKick('strike', 0.6, { x: 1, y: 0 }, { loft, spin: 0 })
    w.update(SIM.dt, c)
    run(w, 4)
    return { passed: d.gates.filter((g) => g.passed).length, pts: d.score.points }
  }
  const driven = drive(-1)
  const lofted = drive(1)
  ok(
    'gates reward a driven ball only',
    driven.passed > 0 && lofted.passed === 0,
    `flick down: ${driven.passed} gate(s), ${driven.pts} pts · flick up: ${lofted.passed} gates`,
  )
}

// ---- 3. curl: the wall is solid ---------------------------------------------
{
  // Stand on the drill's own spot — the wall is placed relative to it, so
  // shooting from anywhere else isn't testing the drill.
  const d0 = new World(cfg).drills!
  d0.select('curl')
  const spot = d0.spot!
  const { w, d, p } = stage('curl', { x: spot.x - 0.8, y: spot.y })
  w.ball.setPos(spot.x, spot.y, 0)
  w.ball.lastTouchId = -1
  const c = emptyCommand()
  c.aim = { x: 1, y: 0 }
  // Straight at the wall, flat, no bend at all.
  c.kick = makeKick('strike', 0.8, { x: 1, y: 0 }, { loft: -1, spin: 0 })
  w.update(SIM.dt, c)
  let hitWall = false
  for (let i = 0; i < 120 * 3; i++) {
    const before = w.ball.vx
    w.update(SIM.dt, emptyCommand())
    if (before > 2 && w.ball.vx < 0) hitWall = true
    if (hitWall) break
  }
  ok(
    'you cannot go through the wall',
    hitWall && w.score.home === 0,
    `flat and straight came back off it; ${d.wall.length} bodies at x=${d.wall[0].x.toFixed(0)}`,
  )
}

// ---- 4. curl: bending it round scores -------------------------------------
{
  const d0 = new World(cfg).drills!
  d0.select('curl')
  const spot = d0.spot!
  const { w, d, p } = stage('curl', { x: spot.x - 0.8, y: spot.y })
  w.ball.setPos(spot.x, spot.y, 0)
  w.ball.lastTouchId = -1
  // Rather than hand-tune one trajectory, sweep a spread of "wide and bending
  // back" shots and require that the drill is solvable — that some honest curled
  // ball goes round the wall and in.
  let scored = 0
  let bestNote = ''
  for (const [ay, spin, power] of [
    [-0.20, 1.2, 0.68],
    [-0.26, 1.4, 0.7],
    [-0.32, 1.6, 0.72],
    [0.2, -1.2, 0.68],
    [0.26, -1.4, 0.7],
    [0.32, -1.6, 0.72],
  ] as const) {
    const t = stage('curl', { x: spot.x - 0.8, y: spot.y })
    t.w.ball.setPos(spot.x, spot.y, 0)
    t.w.ball.lastTouchId = -1
    const len = Math.hypot(1, ay)
    const aim = { x: 1 / len, y: ay / len }
    t.p.heading = Math.atan2(aim.y, aim.x)
    const c2 = emptyCommand()
    c2.aim = aim
    c2.kick = makeKick('strike', power, aim, { loft: 0.12, spin })
    t.w.update(SIM.dt, c2)
    run(t.w, 4)
    if (t.w.score.home > 0) {
      scored++
      if (!bestNote) bestNote = `${(ay > 0 ? 'right' : 'left')} of the wall, spin ${spin} — "${t.d.verdict?.text}"`
    }
  }
  ok(
    'bending it round the wall scores',
    scored > 0,
    `${scored}/6 curled attempts found a way in — ${bestNote || 'none did'}`,
  )
  void w
  void d
  void p
}

// ---- 5. cushion: a ball is served at you -----------------------------------
{
  const { w, d } = stage('cushion')
  run(w, 1.5)
  ok(
    'the cushion drill serves you a ball',
    d.serveLive && w.ball.speed > 5,
    `served "${d.serveLabel}" at ${w.ball.speed.toFixed(1)} m/s, attempt ${d.score.attempts}`,
  )
}

// ---- 6. cushion: a soft touch scores better than a heavy one ---------------
{
  const attempt = (power: number) => {
    const { w, d, p } = stage('cushion')
    // Wait for the serve, then meet it with a touch of the given weight.
    let done = false
    for (let i = 0; i < 120 * 10 && !done; i++) {
      const c = emptyCommand()
      c.aim = { x: 1, y: 0 }
      const near = Math.hypot(w.ball.x - p.x, w.ball.y - p.y) < 1.4
      if (d.serveLive && near) c.kick = makeKick('touch', power, { x: 1, y: 0 }, { loft: 0, spin: 0 })
      w.update(SIM.dt, c)
      if (d.verdict) done = true
    }
    return { text: d.verdict?.text ?? 'none', pts: d.score.points }
  }
  const soft = attempt(0.02)
  const hard = attempt(1.0)
  ok(
    'a softer first touch scores higher',
    soft.pts > hard.pts,
    `tap: "${soft.text}" ${soft.pts} pts · full: "${hard.text}" ${hard.pts} pts`,
  )
}

// ---- 7. scores are kept per drill ------------------------------------------
{
  const { w, d, p } = stage('gates', { x: 24, y: FIELD.width / 2 - 6 })
  w.ball.setPos(p.x + 0.55, p.y, 0)
  w.ball.lastTouchId = -1
  const c = emptyCommand()
  c.aim = { x: 1, y: 0 }
  c.kick = makeKick('strike', 0.6, { x: 1, y: 0 }, { loft: -1, spin: 0 })
  w.update(SIM.dt, c)
  run(w, 4)
  const gatePts = d.scores.gates.points
  d.select('targets')
  d.select('gates')
  ok(
    'each drill keeps its own score',
    d.scores.gates.points === gatePts && gatePts > 0 && d.scores.targets.points === 0,
    `gates ${d.scores.gates.points} pts survived a round trip; targets still ${d.scores.targets.points}`,
  )
}

// ---- 8. targets still work --------------------------------------------------
{
  const { w, d, p } = stage('targets', { x: FIELD.length - 14, y: FIELD.width / 2 })
  const t = d.targets[2] // a bottom corner
  const dir = { x: t.x - p.x, y: t.y - p.y }
  const len = Math.hypot(dir.x, dir.y)
  w.ball.setPos(p.x + 0.55, p.y, 0)
  w.ball.lastTouchId = -1
  const c = emptyCommand()
  c.aim = { x: dir.x / len, y: dir.y / len }
  c.kick = makeKick('strike', 0.8, c.aim, { loft: -0.3, spin: 0 })
  w.update(SIM.dt, c)
  run(w, 3)
  ok(
    'target practice still scores',
    d.scores.targets.attempts > 0,
    `${d.scores.targets.scored}/${d.scores.targets.attempts}, ${d.scores.targets.points} pts`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
