// Training has no restart.
//
// Score as many goals as you like: nothing freezes, nothing is put back for you.
// After a goal the net holds the ball for a beat and rolls it back out through
// the mouth; you go and get it, wherever you are standing, and shoot again.
//
// This drives the real loop a player would — fetch the ball, strike it at the
// goal, fetch it again — for a long run of goals, and watches for the ways it
// used to go wrong: a goal counted twice, a goal not counted, the ball or the
// player being teleported, and the ball never coming back out of the net.
import { World } from '../src/game/match/world'
import { makeKick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { FIELD, TRAINING } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(50)} ${detail}`)

const cfg = {
  teamSize: 4, halfLength: 6000, mode: 'training' as const, singleKeeper: false,
  view: '3d' as const, quality: 'medium' as const, position: 'MID' as const,
  heightSens: 1.6, curveSens: 1.15, humanControlled: true,
}
const DT = 1 / 120
const GY = FIELD.width / 2
const goals = (w: World) => w.score.home + w.score.away

// ---- a player who fetches the ball and shoots, and a monitor over all of it ----
interface Watch {
  steps: number
  ballJumps: number // a step where the ball moved further than any ball can
  playerJumps: number // ditto for the player
  worstBall: number
  worstPlayer: number
}
function newWatch(): Watch {
  return { steps: 0, ballJumps: 0, playerJumps: 0, worstBall: 0, worstPlayer: 0 }
}

// One step, with the monitor watching. `kicked` excuses the ball for that step: a
// strike legitimately moves a resting ball up to a reach's length onto the boot.
function stepWatched(w: World, cmd: Command, watch: Watch, kicked: boolean) {
  const p = w.getControlledPlayer()!
  const bx = w.ball.x, by = w.ball.y, px = p.x, py = p.y
  w.update(DT, cmd)
  watch.steps++
  const db = Math.hypot(w.ball.x - bx, w.ball.y - by)
  const dp = Math.hypot(p.x - px, p.y - py)
  if (!kicked) watch.worstBall = Math.max(watch.worstBall, db)
  watch.worstPlayer = Math.max(watch.worstPlayer, dp)
  if (!kicked && db > 1.0) watch.ballJumps++
  if (dp > 0.5) watch.playerJumps++
}

// Get to the ball: walk to a point just behind it, on the far side from the goal.
function walkTo(w: World, goalX: number, watch: Watch) {
  const p = w.getControlledPlayer()!
  const dirToGoal = Math.sign(goalX - w.ball.x) || 1
  for (let i = 0; i < 120 * 30; i++) {
    const tx = w.ball.x - dirToGoal * 1.0
    const ty = w.ball.y
    const dx = tx - p.x, dy = ty - p.y
    const d = Math.hypot(dx, dy)
    // Ready when it is close, nearly still and — the part a real player would not
    // forget — actually out on the pitch: a ball still inside the net is waiting
    // to be given back, and playing it from there is playing it from behind the line.
    const onPitch = w.ball.x > 0.3 && w.ball.x < FIELD.length - 0.3
    const close = onPitch && Math.hypot(w.ball.x - p.x, w.ball.y - p.y) < 1.7 && w.ball.speed < 0.6
    if (close) return
    const c = emptyCommand()
    c.move = { x: dx / (d || 1), y: dy / (d || 1) }
    c.aim = { x: dirToGoal, y: 0 }
    c.sprint = d > 6
    stepWatched(w, c, watch, false)
  }
}

// Walk to the ball and hit it at the goal — aiming at `aimY`, the middle by
// default. If the ball is lying on the line (the shooter stood in front and
// blocked its way out) a sensible player does not shoot along the goal line: they
// play it out with a click first, which is the only way anything moves it.
// Returns true if that produced a goal.
function fetchAndShoot(w: World, goalX: number, watch: Watch, aimY = GY, strike = 0.8): boolean {
  const before = goals(w)
  walkTo(w, goalX, watch)
  if (Math.abs(w.ball.x - goalX) < 3) {
    const away = -(Math.sign(goalX - w.ball.x) || 1)
    const t = emptyCommand()
    t.aim = { x: away, y: 0 }
    t.kick = makeKick('touch', 0.3, t.aim, { loft: 0, spin: 0 })
    stepWatched(w, t, watch, true)
    for (let i = 0; i < 120 * 4 && w.ball.speed > 0.05; i++) stepWatched(w, emptyCommand(), watch, false)
    walkTo(w, goalX, watch)
  }
  const c = emptyCommand()
  const ax = goalX - w.ball.x, ay = aimY - w.ball.y
  const al = Math.hypot(ax, ay) || 1
  c.aim = { x: ax / al, y: ay / al }
  c.kick = makeKick('strike', strike, c.aim, { loft: 0, spin: 0 })
  stepWatched(w, c, watch, true)
  // Watch it go.
  for (let i = 0; i < 120 * 5 && goals(w) === before; i++) stepWatched(w, emptyCommand(), watch, false)
  return goals(w) === before + 1
}

// Let the ball settle wherever the net leaves it.
function settle(w: World, watch: Watch, seconds = 8) {
  for (let i = 0; i < 120 * seconds; i++) {
    stepWatched(w, emptyCommand(), watch, false)
    if (i > 120 * TRAINING.returnDelay + 60 && w.ball.speed < 0.02) return i * DT
  }
  return seconds
}

// ---- 1. an unbroken run of goals -------------------------------------------
{
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = FIELD.length - 15; p.y = GY
  w.ball.setPos(p.x + 0.8, p.y, 0)
  const watch = newWatch()
  const N = 20
  let scored = 0
  let double = 0
  let missed = 0
  for (let i = 0; i < N; i++) {
    const g0 = goals(w)
    const good = fetchAndShoot(w, FIELD.length, watch)
    settle(w, watch)
    const gained = goals(w) - g0
    if (gained > 1) double++
    if (!good) missed++
    scored += gained
  }
  ok(
    'twenty goals in a row, each counted once',
    scored === N && double === 0 && missed === 0,
    `${scored}/${N} counted, ${double} counted twice, ${missed} not counted`,
  )
  ok(
    'and the ball is never teleported',
    watch.ballJumps === 0,
    `across ${watch.steps.toLocaleString()} steps the ball's biggest single-step move was ${watch.worstBall.toFixed(2)} m`,
  )
  ok(
    'nor is the player',
    watch.playerJumps === 0,
    `biggest single-step move ${watch.worstPlayer.toFixed(3)} m — a sprint, not a reset`,
  )
}

// ---- 2. the net gives it back ------------------------------------------------
{
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = FIELD.length - 15; p.y = GY
  w.ball.setPos(p.x + 0.8, p.y, 0)
  const watch = newWatch()
  fetchAndShoot(w, FIELD.length, watch)
  // Nobody touches anything from here. The net alone has to give it back.
  const entered = w.ball.x > FIELD.length
  let held = 0
  for (let i = 0; i < 120 * 1.2; i++) {
    stepWatched(w, emptyCommand(), watch, false)
    if (w.ball.x > FIELD.length) held += DT
  }
  const t = settle(w, watch)
  const out = FIELD.length - w.ball.x
  ok(
    'the ball goes in, and the net holds it for a beat',
    entered && held > TRAINING.returnDelay * 0.6,
    `it sat in the mesh for ${held.toFixed(2)} s (the net's hold is ${TRAINING.returnDelay} s)`,
  )
  ok(
    'then rolls it back out to rest in front of the goal',
    w.ball.x < FIELD.length && out > 1.5 && out < 14 && w.ball.speed < 0.05,
    `at rest ${out.toFixed(1)} m out, ${(t + held).toFixed(1)} s after it went in — nobody touched it`,
  )
}

// ---- 3. it scores once, not once per step ------------------------------------
{
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = 20; p.y = GY
  // Put it in the net and leave it there for a good while.
  w.ball.setPos(FIELD.length + 0.6, GY, 0.1)
  w.ball.vx = 6
  const watch = newWatch()
  for (let i = 0; i < 120 * 0.6; i++) stepWatched(w, emptyCommand(), watch, false)
  ok(
    'a ball sitting in the net scores once',
    goals(w) === 1,
    `${goals(w)} goal after the ball spent ${(0.6).toFixed(1)} s over the line`,
  )
}

// ---- 4. and can score again the moment it is out ----------------------------
{
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = FIELD.length - 15; p.y = GY
  w.ball.setPos(p.x + 0.8, p.y, 0)
  const watch = newWatch()
  fetchAndShoot(w, FIELD.length, watch)
  const first = goals(w)
  settle(w, watch)
  const second = fetchAndShoot(w, FIELD.length, watch)
  ok(
    'the next one counts as soon as the ball is back out',
    first === 1 && second && goals(w) === 2,
    `goal ${first}, fetched it yourself, goal ${goals(w)} — no restart in between`,
  )
}

// ---- 5. either end ------------------------------------------------------------
{
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = 15; p.y = GY
  w.ball.setPos(p.x - 0.8, p.y, 0)
  const watch = newWatch()
  let n = 0
  for (let i = 0; i < 6; i++) if (fetchAndShoot(w, 0, watch)) n++
  ok(
    'the far goal works exactly the same',
    n === 6 && w.score.away === 6,
    `${n}/6 at the other end, all credited to the right side (${w.score.away})`,
  )
}

// ---- 6. the drill you were on is still there -----------------------------------
{
  const w = new World(cfg)
  const drill = w.drills!.current
  const dummies = w.drills!.dummies.length
  const p = w.getControlledPlayer()!
  p.x = FIELD.length - 15; p.y = GY
  w.ball.setPos(p.x + 0.8, p.y, 0)
  const watch = newWatch()
  for (let i = 0; i < 5; i++) { fetchAndShoot(w, FIELD.length, watch); settle(w, watch) }
  ok(
    'nothing about the session is reset',
    w.drills!.current === drill && w.drills!.dummies.length === dummies,
    `still on "${drill}" with the ${dummies} pieces of apparatus after 5 goals`,
  )
}

// ---- 6b. the ball always comes back out, whichever post it ends up against ------
{
  // A ball that ends up against the side netting near a post has to be rolled out
  // clear of it. Written on the post's own line, the first version of the net
  // return rolled such a ball straight into the post and back into the net, for
  // ever: the goal counted, and then the ball sat trapped inside it.
  //
  // Placed rather than shot, so it is exactly the same every time — a shot at a
  // corner has scatter, a weak foot and side-spin, and misses about as often as
  // it should.
  const pa = (FIELD.width - FIELD.goalWidth) / 2
  const pb = (FIELD.width + FIELD.goalWidth) / 2
  let cases = 0, counted = 0, stuck = 0
  for (const goalX of [FIELD.length, 0]) {
    const inward = goalX === 0 ? -1 : 1 // which way is "into the net"
    for (const y of [pa + 0.02, pa + 0.3, pb - 0.3, pb - 0.02]) {
      for (const vy of [-3, 0, 3]) {
        const w = new World(cfg)
        w.getControlledPlayer()!.x = FIELD.length / 2
        w.ball.setPos(goalX + inward * 0.7, y, 0.1)
        w.ball.vx = inward * 2
        w.ball.vy = vy
        const watch = newWatch()
        cases++
        for (let i = 0; i < 120 * 10; i++) stepWatched(w, emptyCommand(), watch, false)
        if (goals(w) === 1) counted++
        if (w.ball.x < 0 || w.ball.x > FIELD.length) stuck++
      }
    }
  }
  ok(
    'a ball against either post is rolled out, never trapped',
    counted === cases && stuck === 0,
    `${cases} placements (both ends, both posts, drifting each way): ${counted} counted once, ${stuck} still in the net after 10 s`,
  )
}

// ---- 6c. somebody standing in the mouth ------------------------------------------
{
  // Score from close range and you are standing right in front of the goal. The
  // ball the net gives back must roll out *through* you. Stopped by your body it
  // rebounded back over the line for a second goal off one shot — or died inside
  // the net at the back, out of your reach, because you cannot step into the goal
  // and nothing but a click can move a ball any more.
  for (const goalX of [FIELD.length, 0]) {
    const inward = goalX === 0 ? -1 : 1
    const w = new World(cfg)
    const p = w.getControlledPlayer()!
    p.x = goalX - inward * 1.4; p.y = GY // right in front of the line, in the ball's way
    w.ball.setPos(goalX + inward * 0.5, GY, 0.1)
    w.ball.vx = inward * 5
    const watch = newWatch()
    for (let i = 0; i < 120 * 12; i++) stepWatched(w, emptyCommand(), watch, false)
    const out = Math.min(w.ball.x, FIELD.length - w.ball.x)
    ok(
      `the ball rolls out through a player in the ${goalX === 0 ? 'far ' : ''}mouth`,
      goals(w) === 1 && out > 2.5 && w.ball.speed < 0.05,
      `${goals(w)} goal; the ball is out on the pitch ${out.toFixed(1)} m from the line and at rest — not rebounded, not trapped in the net`,
    )
  }
  // And the next shot is an ordinary new goal.
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = FIELD.length - 1.4; p.y = GY
  w.ball.setPos(FIELD.length + 0.5, GY, 0.1)
  w.ball.vx = 5
  const watch = newWatch()
  for (let i = 0; i < 120 * 6; i++) stepWatched(w, emptyCommand(), watch, false)
  const again = fetchAndShoot(w, FIELD.length, watch)
  ok(
    'and the shot after it is a new goal',
    again && goals(w) === 2,
    `fetched it, shot, scored: ${goals(w)} goals`,
  )
}

// ---- 6d. a loose statistical backstop ----------------------------------------------
{
  // Shots at the corners genuinely miss now and then — there is scatter, a weak
  // foot and side-spin, and there should be. This does not assert that they all
  // score, only that scoring is happening and that no ball is ever left in the net.
  const pa = (FIELD.width - FIELD.goalWidth) / 2
  const pb = (FIELD.width + FIELD.goalWidth) / 2
  let shots = 0, counted = 0, stuck = 0
  for (const goalX of [FIELD.length, 0]) {
    for (const aimY of [pa + 0.6, pb - 0.6, pa + 0.6, pb - 0.6]) {
      for (const power of [0.5, 0.9]) {
        const w = new World(cfg)
        const p = w.getControlledPlayer()!
        p.x = goalX === 0 ? 14 : FIELD.length - 14; p.y = GY
        w.ball.setPos(p.x + (goalX === 0 ? -0.8 : 0.8), p.y, 0)
        const watch = newWatch()
        shots++
        if (fetchAndShoot(w, goalX, watch, aimY, power)) counted++
        settle(w, watch, 10)
        if (w.ball.x < 0 || w.ball.x > FIELD.length) stuck++
      }
    }
  }
  ok(
    'shots at the corners score, and none leaves the ball in the net',
    counted >= shots * 0.4 && stuck === 0,
    `${counted}/${shots} scored (some should miss), ${stuck} balls left stuck in the net`,
  )
}

// ---- 7. the net has sides ---------------------------------------------------
{
  // A ball inside the goal that drifted a few centimetres past the post line
  // used to stop counting as "in the mouth" and be pushed back out onto the
  // pitch — 2.3 m at the back of the net — in a single step. Corner shots were
  // teleported after they had scored, in matches as well as here.
  const [, pb] = [0, (FIELD.width + FIELD.goalWidth) / 2]
  for (const mode of ['training', 'match'] as const) {
    const w = new World({ ...cfg, mode, halfLength: 120 })
    w.phase = 'playing'
    w.ball.setPos(FIELD.length + 1.6, pb - 0.03, 0.1)
    w.ball.vx = 1
    w.ball.vy = 3 // drifting toward the post, out past the line
    let worst = 0
    let leftSideways = false
    for (let i = 0; i < 90; i++) {
      const ox = w.ball.x, oy = w.ball.y
      w.update(DT, emptyCommand())
      worst = Math.max(worst, Math.hypot(w.ball.x - ox, w.ball.y - oy))
      if (w.ball.x > FIELD.length && w.ball.y > pb + 0.05) leftSideways = true
    }
    ok(
      `a ball in the ${mode === 'match' ? 'match ' : ''}net cannot slip out of the side`,
      worst < 0.5 && !leftSideways,
      `drifted toward the post at 3 m/s: biggest single-step move ${worst.toFixed(2)} m, never past the post line`,
    )
  }
}

// ---- 8. matches are unchanged ---------------------------------------------------
{
  const m = new World({ ...cfg, mode: 'match' as const, halfLength: 120 })
  m.phase = 'playing'
  m.ball.setPos(FIELD.length + 0.6, GY, 0.1)
  m.ball.vx = 6
  for (let i = 0; i < 120; i++) m.update(DT, emptyCommand())
  ok(
    'a match goal still stops play and scores exactly once',
    m.phase === 'goal' && goals(m) === 1 && m.score.home === 1,
    `a second after the ball crossed: phase "${m.phase}", ${m.score.home}–${m.score.away}`,
  )
  // Through to the restart. Nothing rolled the ball back out of the net: a
  // match puts it back on the centre spot for the kickoff, as it always did.
  for (let i = 0; i < 120 * 6 && m.phase === 'goal'; i++) m.update(DT, emptyCommand())
  ok(
    'and kicks off from the centre spot afterwards',
    m.phase !== 'goal' && Math.hypot(m.ball.x - FIELD.length / 2, m.ball.y - GY) < 0.5,
    `phase "${m.phase}", ball ${m.ball.x.toFixed(1)},${m.ball.y.toFixed(1)}`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
