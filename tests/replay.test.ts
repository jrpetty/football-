// Replays. The point of these is that a replay must be a faithful recording of
// what happened and must leave the live world exactly where it found it —
// anything less and watching your goal would cost you the game you were playing.
import { World } from '../src/game/match/world'
import { Replay, REPLAY } from '../src/game/match/replay'
import { makeKick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import { SIM, FIELD } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis

const results: string[] = []
const ok = (n: string, p: boolean, d: string) =>
  results.push(`${p ? 'PASS' : 'FAIL'}  ${n.padEnd(44)} ${d}`)

const cfg = {
  teamSize: 1, halfLength: 6000, mode: 'training' as const, singleKeeper: false,
  view: '3d' as const, position: 'MID' as const, heightSens: 1.6, curveSens: 1.15,
  humanControlled: true,
}

// Strike a ball across the pitch, recording the whole time.
function played(seconds = 4) {
  const w = new World(cfg)
  const r = new Replay()
  const p = w.getControlledPlayer()!
  p.x = 8
  p.y = FIELD.width / 2
  p.heading = 0
  p.kickCooldown = 0
  w.ball.setPos(8.55, FIELD.width / 2, 0)
  w.ball.lastTouchId = -1
  const c = emptyCommand()
  c.aim = { x: 1, y: 0 }
  c.kick = makeKick('strike', 0.7, { x: 1, y: 0 }, { loft: 0.4, spin: 1.2 })
  const path: { x: number; y: number }[] = []
  for (let i = 0; i < Math.round(seconds / SIM.dt); i++) {
    w.update(SIM.dt, i === 0 ? c : emptyCommand())
    r.record(w, SIM.dt)
    path.push({ x: w.ball.x, y: w.ball.y })
  }
  return { w, r, path, p }
}

// ---- 1. it records ----------------------------------------------------------
{
  const { r } = played(4)
  ok(
    'it keeps a rolling window of the match',
    r.available > 3 && r.available <= REPLAY.seconds + 0.5,
    `${r.available.toFixed(1)}s of history at ${REPLAY.hz} Hz (window is ${REPLAY.seconds}s)`,
  )
}

// ---- 2. playback retraces what actually happened ---------------------------
{
  const { w, r, path } = played(4)
  const started = r.start(w, 'GOAL')
  // Walk the whole replay and collect where the ball went.
  const seen: { x: number; y: number }[] = []
  for (let i = 0; i < 2000; i++) {
    if (!r.update(w, SIM.dt)) break
    seen.push({ x: w.ball.x, y: w.ball.y })
  }
  // Every point the replay showed should lie on the path the ball really took.
  let worst = 0
  for (const s of seen) {
    let best = Infinity
    for (const q of path) best = Math.min(best, Math.hypot(s.x - q.x, s.y - q.y))
    worst = Math.max(worst, best)
  }
  ok(
    'playback retraces the real flight',
    started && seen.length > 50 && worst < 0.4,
    `${seen.length} frames, furthest any of them strayed from the true path: ${worst.toFixed(2)} m`,
  )
}

// ---- 3. ...and it runs in slow motion ---------------------------------------
{
  const { w, r } = played(4)
  r.start(w, 'GOAL', 3)
  let frames = 0
  for (let i = 0; i < 5000; i++) {
    if (!r.update(w, SIM.dt)) break
    frames++
  }
  const took = frames * SIM.dt
  ok(
    'it plays back slower than life',
    took > 3 / REPLAY.speed - 1,
    `three seconds of football took ${took.toFixed(1)}s to watch (${REPLAY.speed}x)`,
  )
}

// ---- 4. the live world is put back exactly as it was ------------------------
{
  const { w, r } = played(4)
  const before = {
    bx: w.ball.x, by: w.ball.y, bz: w.ball.z,
    vx: w.ball.vx, vy: w.ball.vy, spin: w.ball.spin,
    px: w.players[0].x, py: w.players[0].y,
  }
  r.start(w, 'GOAL')
  for (let i = 0; i < 400; i++) if (!r.update(w, SIM.dt)) break
  r.stop(w)
  const moved = Math.hypot(w.ball.x - before.bx, w.ball.y - before.by, w.ball.z - before.bz)
  const vel = Math.hypot(w.ball.vx - before.vx, w.ball.vy - before.vy)
  const pl = Math.hypot(w.players[0].x - before.px, w.players[0].y - before.py)
  ok(
    'watching it costs you nothing',
    moved < 0.02 && vel < 0.02 && pl < 0.02 && !r.playing,
    `ball back within ${moved.toFixed(3)} m and ${vel.toFixed(3)} m/s, player within ${pl.toFixed(3)} m`,
  )
}

// ---- 5. it refuses when there's nothing to show -----------------------------
{
  const w = new World(cfg)
  const r = new Replay()
  r.record(w, 0.1)
  ok('nothing to replay is not a replay', !r.start(w, 'GOAL'), 'a tenth of a second in: declined')
}

// ---- 6. recording is paused while you watch ---------------------------------
{
  const { w, r } = played(4)
  const had = r.available
  r.start(w, 'GOAL')
  for (let i = 0; i < 200; i++) {
    r.update(w, SIM.dt)
    r.record(w, SIM.dt) // the caller does this unconditionally; it must no-op
  }
  ok(
    'a replay does not record itself',
    Math.abs(r.available - had) < 0.05,
    `history stayed at ${r.available.toFixed(1)}s while playing back`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
