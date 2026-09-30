// The training pre-view has one job: to be true.
//
// A line on the pitch that says "this is where it will go" is worth having only
// if it is where it goes. So it is held to the ball: with the strike's small
// random imperfection switched off, the place the preview says the ball first
// comes down must be the place the real ball, played through the real world,
// first comes down — across power, lift, drive and curve.
import { World } from '../src/game/match/world'
import { makeKick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import { FIELD } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(56)} ${detail}`)

const cfg = {
  teamSize: 4, halfLength: 6000, mode: 'training' as const, singleKeeper: false,
  view: '3d' as const, quality: 'medium' as const, position: 'MID' as const,
  heightSens: 1.6, curveSens: 1.15, humanControlled: true,
}
const DT = 1 / 120
const GY = FIELD.width / 2

const setup = () => {
  const w = new World({ ...cfg })
  w.phase = 'playing'
  const me = w.getControlledPlayer()!
  // Near one end, so there is a whole pitch in front of it to fly across.
  me.x = 3; me.y = GY; me.heading = 0
  w.ball.setPos(3.7, GY, 0); w.ball.stop()
  return { w, me }
}

// The strike has a whisper of scatter; take it out so the two can be compared.
const realRandom = Math.random
Math.random = () => 0.5

// Fly the real thing: the first place it comes down, and where it is after
// `at` seconds — a comparison that holds for a ball that never leaves the turf.
const flyReal = (power: number, loft: number, spin: number, at = 0.8) => {
  const { w } = setup()
  const c = emptyCommand()
  c.aim = { x: 1, y: 0 }
  c.kick = makeKick('strike', power, { x: 1, y: 0 }, { loft, spin })
  w.update(DT, c)
  let land: { x: number; y: number } | null = null
  // After each step, starting with the one that struck it.
  const track = [{ x: w.ball.x, y: w.ball.y, z: w.ball.z }]
  for (let i = 1; i <= 120 * 3; i++) {
    w.update(DT, emptyCommand())
    if (!land && w.ball.justBounced) land = { x: w.ball.x, y: w.ball.y }
    track.push({ x: w.ball.x, y: w.ball.y, z: w.ball.z })
  }
  return { land, track, there: track[Math.round(at / DT)] }
}
const previewOf = (power: number, loft: number, spin: number) => {
  const { w } = setup()
  return w.previewStrike(makeKick('strike', power, { x: 1, y: 0 }, { loft, spin }))
}

// ---- 1. it lands where the ball lands ------------------------------------------------
{
  const cases: [string, number, number, number][] = [
    ['lofted', 0.65, 0.8, 0],
    ['lofted, soft', 0.5, 0.6, 0],
    ['a chip', 0.35, 0.95, 0],
    ['curled left', 0.6, 0.6, -0.7],
    ['curled right', 0.6, 0.6, 0.7],
    ['lofted and curled', 0.6, 0.7, 0.6],
  ]
  let worst = 0
  const detail: string[] = []
  let checked = 0
  for (const [name, power, loft, spin] of cases) {
    const real = flyReal(power, loft, spin).land
    const pv = previewOf(power, loft, spin)
    if (!real || !pv || !pv.land) {
      detail.push(`${name}: ${!real ? 'real ball never bounced' : 'no preview landing'}`)
      continue
    }
    const d = Math.hypot(real.x - pv.land.x, real.y - pv.land.y)
    worst = Math.max(worst, d)
    checked++
    detail.push(`${name} ${d.toFixed(2)}`)
  }
  ok(
    'the preview lands where the real ball lands',
    checked === cases.length && worst < 0.3,
    `${checked}/${cases.length} compared; worst miss ${worst.toFixed(3)} m — ${detail.join(', ')}`,
  )
}

// ---- 1b. and it is where the ball is at every moment of the flight ------------------------
{
  const cases: [string, number, number, number][] = [
    ['a hard driven ball', 0.9, -0.7, 0],
    ['a firm pass', 0.6, 0.1, 0],
    ['a full-power lift', 0.95, 0.8, 0.3],
    ['a soft roll', 0.2, 0, 0],
    ['a curled drive', 0.85, -0.3, 0.8],
    ['a curled lob', 0.5, 0.9, -0.9],
  ]
  let worst = 0
  let compared = 0
  const detail: string[] = []
  for (const [name, power, loft, spin] of cases) {
    const { track } = flyReal(power, loft, spin)
    const pv = previewOf(power, loft, spin)!
    // The preview keeps every other step; its point k is the ball after 2k
    // steps of its own, which is the world's track at 2k − 1 (the step that
    // struck it already includes one).
    let caseWorst = 0
    for (let k = 1; k < pv.points.length; k++) {
      const t = track[2 * k - 1]
      const q = pv.points[k]
      caseWorst = Math.max(caseWorst, Math.hypot(t.x - q.x, t.y - q.y, t.z - q.z))
      compared++
    }
    worst = Math.max(worst, caseWorst)
    detail.push(`${name} ${caseWorst.toFixed(3)}`)
  }
  ok(
    'the line is where the ball is, at every point along it',
    worst < 0.05 && compared > 300,
    `${compared} points against the real ball's track, worst gap ${worst.toFixed(4)} m — ${detail.join(', ')}`,
  )
}

// ---- 2. it responds to the wrist -----------------------------------------------------
{
  const flat = previewOf(0.55, 0.0, 0)!
  const lofted = previewOf(0.55, 0.9, 0)!
  const curlL = previewOf(0.8, 0.3, -0.8)!
  const curlR = previewOf(0.8, 0.3, 0.8)!
  // A ball that never leaves the ground has no landing, so where it ends up is
  // where its line stops.
  const end = (pv: NonNullable<ReturnType<typeof previewOf>>) => pv.land ?? pv.points[pv.points.length - 1]
  ok(
    'a lifted ball peaks higher than a flat one, and comes down where it says',
    lofted.peak > flat.peak + 1 && lofted.land !== null,
    `peak ${flat.peak.toFixed(1)} m flat, ${lofted.peak.toFixed(1)} m lifted; the lifted one lands at ${lofted.land ? lofted.land.x.toFixed(1) : '—'}`,
  )
  ok(
    'a curl bends it, and opposite flicks bend opposite ways',
    (end(curlL).y - GY) * (end(curlR).y - GY) < 0 && Math.abs(end(curlL).y - end(curlR).y) > 1.5,
    `ends ${(end(curlL).y - GY).toFixed(1)} m to one side, ${(end(curlR).y - GY).toFixed(1)} m to the other`,
  )
}

// ---- 3. it is silent when a strike would not happen ------------------------------------
{
  const far = setup()
  far.w.ball.setPos(18, GY, 0)
  const outOfReach = far.w.previewStrike(makeKick('strike', 0.8, { x: 1, y: 0 }, { loft: 0, spin: 0 }))

  const air = setup()
  air.w.ball.setPos(3.7, GY, 2.5)
  const airborne = air.w.previewStrike(makeKick('strike', 0.8, { x: 1, y: 0 }, { loft: 0, spin: 0 }))

  const ok1 = setup()
  const fine = ok1.w.previewStrike(makeKick('strike', 0.8, { x: 1, y: 0 }, { loft: 0, spin: 0 }))
  ok(
    'no preview for a ball you cannot reach or one in the air',
    outOfReach === null && airborne === null && fine !== null,
    `ball 15 m off: ${outOfReach === null ? 'nothing' : 'a line'}; ball at 2.5 m up: ${airborne === null ? 'nothing' : 'a line'}; at your feet: ${fine ? 'a line' : 'nothing'}`,
  )
}

// ---- 4. and it leaves the world exactly as it found it ------------------------------------
{
  const { w } = setup()
  const before = JSON.stringify({ b: [w.ball.x, w.ball.y, w.ball.z, w.ball.vx, w.ball.vy, w.ball.vz, w.ball.spin], s: w.session, sc: w.score })
  for (let i = 0; i < 50; i++) w.previewStrike(makeKick('strike', Math.random(), { x: 1, y: 0 }, { loft: 0.5, spin: 0.4 }))
  const after = JSON.stringify({ b: [w.ball.x, w.ball.y, w.ball.z, w.ball.vx, w.ball.vy, w.ball.vz, w.ball.spin], s: w.session, sc: w.score })
  ok('asking for a preview changes nothing', before === after, `50 previews later the ball, the session tally and the score are identical`)
}

// ---- 5. it is bounded ----------------------------------------------------------------------
{
  const pv = previewOf(1, 0.5, 0.5)!
  ok('a preview is a short, finite line', pv.points.length > 10 && pv.points.length <= 181,
    `${pv.points.length} points for a full-power lofted curl`)
}

Math.random = realRandom
console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
