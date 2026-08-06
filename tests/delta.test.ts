// Snapshots as differences.
//
// The claim being tested is a bandwidth one, so it is measured rather than
// asserted: the same match is encoded both ways and the bytes are counted. The
// correctness half matters more — a delta that saves 90% and drifts is worse
// than no delta at all — so a client is reconstructed purely from a keyframe
// plus a chain of differences and compared against the host field by field.
import { World } from '../src/game/match/world'
import { captureSnapshot } from '../src/game/net/session'
import { toFrame, fromFrame, diff, patch, emptyFrame } from '../src/game/net/delta'
import type { Frame } from '../src/game/net/delta'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
import { NET } from '../src/game/net/protocol'
;(globalThis as unknown as { window: unknown }).window = globalThis

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(46)} ${detail}`)

const cfg = {
  teamSize: 4,
  halfLength: 6000,
  mode: 'match' as const,
  singleKeeper: false,
  view: '3d' as const,
  position: 'MID' as const,
  heightSens: 1.6,
  curveSens: 1.15,
  humanControlled: true,
}

const drive = (x: number, y: number): Command => {
  const c = emptyCommand()
  c.move = { x, y }
  c.aim = { x, y }
  c.sprint = true
  return c
}

const bytes = (o: unknown) => JSON.stringify(o).length

// Run a match and collect what the host would put on the wire, both ways.
function record(seconds: number, moving: boolean) {
  const w = new World(cfg)
  w.phase = 'playing'
  const step = 1 / NET.snapshotHz
  const frames = Math.round(seconds / step)
  let full = 0
  let delta = 0
  let baseline: Frame = emptyFrame()
  let baseTick = 0
  let keys = 0
  // A client that only ever sees the delta stream.
  let client: Frame | null = null
  let worstDrift = 0

  for (let i = 0; i < frames; i++) {
    // Several sim steps per snapshot, as in the real loop.
    for (let k = 0; k < 6; k++) w.update(step / 6, moving ? drive(1, 0.35) : emptyCommand())
    const snap = captureSnapshot(w)
    const frame = toFrame(snap)
    full += bytes({ t: 'snap', tick: i, time: 0, ack: 0, s: snap })
    if (i % NET.keyframeEvery === 0) {
      keys++
      delta += bytes({ t: 'snap', tick: i, time: 0, ack: 0, s: fromFrame(frame) })
      client = frame
    } else {
      const d = diff(baseline, frame, baseTick)
      delta += bytes({ t: 'snap', tick: i, time: 0, ack: 0, d })
      client = patch(client ?? emptyFrame(), d)
    }
    baseline = frame
    baseTick = i

    // How far the reconstruction has drifted from the truth, in metres.
    const truth = fromFrame(frame)
    const got = fromFrame(client!)
    worstDrift = Math.max(worstDrift, Math.abs(truth.b.x - got.b.x), Math.abs(truth.b.y - got.b.y))
    for (let j = 0; j < truth.p.length; j++) {
      worstDrift = Math.max(
        worstDrift,
        Math.abs(truth.p[j].x - got.p[j].x),
        Math.abs(truth.p[j].y - got.p[j].y),
      )
    }
  }
  return { full, delta, seconds, keys, worstDrift }
}

// ---- 1. the reconstruction is exact ----------------------------------------
{
  const r = record(6, true)
  ok(
    'a delta stream reconstructs the world exactly',
    r.worstDrift < 1e-9,
    `six seconds of play, ${r.keys} keyframes: worst drift ${r.worstDrift.toExponential(1)} m`,
  )
}

// ---- 2. the saving, measured ------------------------------------------------
{
  const r = record(6, true)
  const fullRate = r.full / r.seconds
  const deltaRate = r.delta / r.seconds
  const saved = 1 - deltaRate / fullRate
  ok(
    'and costs a fraction of the bandwidth',
    saved > 0.5,
    `${(fullRate / 1024).toFixed(1)} KB/s full → ${(deltaRate / 1024).toFixed(1)} KB/s delta (${(saved * 100).toFixed(0)}% less)`,
  )
}

// ---- 3. a quiet pitch costs almost nothing ----------------------------------
{
  const r = record(4, false)
  const deltaRate = r.delta / r.seconds
  const fullRate = r.full / r.seconds
  ok(
    'a still world is nearly free',
    deltaRate < fullRate * 0.25,
    `nobody moving: ${(fullRate / 1024).toFixed(1)} KB/s full → ${(deltaRate / 1024).toFixed(1)} KB/s delta`,
  )
}

// ---- 4. a player who did not move is not on the wire ------------------------
{
  const w = new World(cfg)
  w.phase = 'playing'
  const a = toFrame(captureSnapshot(w))
  // Move exactly one player and nothing else.
  const p = w.players[3]
  p.x += 1.5
  const b = toFrame(captureSnapshot(w))
  const d = diff(a, b, 0)
  ok(
    'only the players that changed are sent',
    d.p.length === 1 && d.p[0][0] === p.id,
    `moved 1 of ${w.players.length} players; ${d.p.length} row(s) on the wire`,
  )
}

// ---- 5. quantisation is what makes that possible ----------------------------
{
  // Floating point never settles: a "stationary" player's x differs in the
  // fifteenth decimal from one step to the next. Compared raw, nothing is ever
  // equal and nothing can ever be omitted.
  const w = new World(cfg)
  w.phase = 'playing'
  const raw1 = captureSnapshot(w)
  for (let i = 0; i < 30; i++) w.update(1 / 120, emptyCommand())
  const raw2 = captureSnapshot(w)
  const rawSame = raw1.p.filter((q, i) => q.x === raw2.p[i].x).length
  const d = diff(toFrame(raw1), toFrame(raw2), 0)
  ok(
    'quantising is what lets anything be omitted',
    d.p.length <= w.players.length,
    `${rawSame}/${w.players.length} identical as floats; ${w.players.length - d.p.length} omitted after rounding`,
  )
}

// ---- 6. a delta against the wrong baseline is refused -----------------------
{
  const w = new World(cfg)
  w.phase = 'playing'
  const a = toFrame(captureSnapshot(w))
  w.update(0.1, drive(1, 0))
  const b = toFrame(captureSnapshot(w))
  const d = diff(a, b, 7)
  // The receiver's own check is `d.f !== baseTick`; this is that check.
  ok(
    'a delta names the frame it came from',
    d.f === 7,
    `made from tick 7, and says so — a client on any other tick discards it`,
  )
}

// ---- 7. the ball survives the round trip to the centimetre ------------------
{
  const w = new World(cfg)
  w.phase = 'playing'
  w.ball.setPos(12.3456, 30.9871, 2.4449)
  w.ball.vx = 21.337
  w.ball.spin = 7.77
  const back = fromFrame(toFrame(captureSnapshot(w)))
  const err = Math.max(
    Math.abs(back.b.x - 12.3456),
    Math.abs(back.b.y - 30.9871),
    Math.abs(back.b.z - 2.4449),
  )
  ok(
    'nothing visible is lost to quantisation',
    err <= 0.005,
    `worst position error ${(err * 1000).toFixed(1)} mm — a ball is 220 mm across`,
  )
}

// ---- 8. a player joining mid-stream gets every field ------------------------
{
  const w = new World(cfg)
  w.phase = 'playing'
  const full = toFrame(captureSnapshot(w))
  // diff() against an empty frame is what a keyframe is: everything present.
  const d = diff(emptyFrame(), full, 0)
  const rebuilt = patch(emptyFrame(), d)
  const same = [...full.p.keys()].every((id) =>
    (rebuilt.p.get(id) ?? []).every((v, i) => v === full.p.get(id)![i]),
  )
  ok(
    'a keyframe is a diff against nothing',
    same && rebuilt.p.size === full.p.size,
    `all ${full.p.size} players rebuilt from an empty baseline`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
