// Football on the radar: every player and the ball read from the radar to
// about a metre, the teams told apart (by the colours the player set, or by
// colour groups and the first goal), which end each team defends (half-time
// included), possession, turnovers, shots and kick-offs, team shapes and
// formations, each goal with its last seconds, and the EA SPORTS FC demo end
// to end through the engine.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readRadar, RADAR_COLOURS, type RadarColour } from '../src/core/pitch/radar.ts'
import { isPitchRecord, PitchTracker, type PitchMatchRecord } from '../src/core/pitch/tracker.ts'
import { pitchInsights } from '../src/core/pitch/insights.ts'
import { isMatchRecord } from '../src/core/analyses/matchRecorder.ts'
import { drawRadar } from '../src/sim/fcRadar.ts'
import { fcDemoPicture, fcMatchSim } from '../src/sim/fcMatch.ts'
import { fcMatchReplay } from '../src/sim/visionReplay.ts'
import { mulberry32 } from '../src/sim/syntheticArena.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { engineFor, settings } from './helpers.ts'
import { defaultContext } from '../src/core/engine.ts'

const W = 1280
const H = 720
const REGION = { x: 0.405, y: 0.83, w: 0.19, h: 0.16 }
const BOX = { x: Math.round(REGION.x * W), y: Math.round(REGION.y * H), w: Math.round(REGION.w * W), h: Math.round(REGION.h * H) }

function backdrop(seed: number): Uint8ClampedArray {
  const rnd = mulberry32(seed)
  const data = new Uint8ClampedArray(W * H * 4)
  const base = [rnd() * 255, rnd() * 255, rnd() * 255]
  for (let i = 0; i < W * H; i++) {
    data[i * 4] = base[0] * 0.5 + rnd() * 90
    data[i * 4 + 1] = base[1] * 0.5 + rnd() * 90
    data[i * 4 + 2] = base[2] * 0.5 + rnd() * 90
    data[i * 4 + 3] = 255
  }
  return data
}

test('the radar: every player within a metre or so, the right team, and the ball', () => {
  const rnd = mulberry32(5)
  const pairs: [RadarColour, RadarColour][] = [
    ['blue', 'red'],
    ['green', 'purple'],
    ['orange', 'blue'],
    ['white', 'red'],
  ]
  let found = 0
  let total = 0
  let wrong = 0
  let balls = 0
  const errs: number[] = []
  for (let trial = 0; trial < 16; trial++) {
    const [ca, cb] = pairs[trial % pairs.length]
    const data = backdrop(trial)
    const players = Array.from({ length: 22 }, (_, i) => ({ x: 4 + rnd() * 97, y: 3 + rnd() * 62, team: (i < 11 ? 0 : 1) as 0 | 1 }))
    const ball = { x: 10 + rnd() * 85, y: 6 + rnd() * 56 }
    drawRadar(data, W, BOX, { players, ball }, { colours: [RADAR_COLOURS[ca], RADAR_COLOURS[cb]] })
    const r = readRadar({ width: W, height: H, data }, REGION, { ours: ca, theirs: cb })
    for (const p of players) {
      total++
      let best = Infinity
      let team = ''
      for (const d of r.dots) {
        const e = Math.hypot(d.x - p.x, d.y - p.y)
        if (e < best) {
          best = e
          team = d.team
        }
      }
      if (best < 2.5) {
        found++
        errs.push(best)
        if (team !== (p.team === 0 ? 'ours' : 'theirs')) wrong++
      }
    }
    if (r.balls.some((b) => Math.hypot(b.x - ball.x, b.y - ball.y) < 2)) balls++
  }
  errs.sort((a, b) => a - b)
  // Random scenes overlap far more than a match does.
  assert.ok(found / total >= 0.95, `${found} of ${total} players found`)
  assert.ok(wrong <= total * 0.03, `${wrong} on the wrong team`)
  assert.ok(errs[Math.floor(errs.length * 0.95)] <= 1.2, `p95 ${errs[Math.floor(errs.length * 0.95)]} m`)
  assert.ok(balls >= 14, `ball found ${balls} of 16`)
  // No radar (a replay or a menu): nothing read.
  assert.equal(readRadar({ width: W, height: H, data: backdrop(99) }, REGION).ok, false)
})

/** A simulated match through the tracker; goals reported 1.5 s after they go in, as the scoreboard does. */
function playSim(colours: { ours?: RadarColour; theirs?: RadarColour }, seed = 3) {
  const endAt = 6 * 60_000
  const goals = [
    { at: 0.18 * endAt, team: 1 as const },
    { at: 0.33 * endAt, team: 0 as const },
    { at: 0.47 * endAt, team: 1 as const },
    { at: 0.62 * endAt, team: 0 as const },
    { at: 0.71 * endAt, team: 1 as const },
    { at: 0.86 * endAt, team: 1 as const },
  ]
  const sim = fcMatchSim({ seed, goals, halfTimeAt: 0.5 * endAt, secondHalfAt: 0.5 * endAt + 10_000, endAt })
  const tr = new PitchTracker(REGION, colours)
  tr.newMatch(0)
  let pending = goals.map((g) => ({ at: g.at + 1500, side: g.team === 0 ? ('for' as const) : ('against' as const) }))
  let truthOurs = 0
  let truthTheirs = 0
  let endsRight = 0
  let endsSeen = 0
  for (let t = 0; t <= endAt; t += 200) {
    const data = new Uint8ClampedArray(W * H * 4)
    for (let i = 0; i < W * H; i++) {
      data[i * 4] = 30 + ((i * 7) % 60)
      data[i * 4 + 1] = 70 + ((i * 13) % 50)
      data[i * 4 + 2] = 30
      data[i * 4 + 3] = 255
    }
    const sc = sim.sceneAt(t)
    if (sc) drawRadar(data, W, BOX, sc, { colours: [RADAR_COLOURS.blue, RADAR_COLOURS.red] })
    tr.frame({ width: W, height: H, data, timestamp: t }, false)
    for (const g of pending.filter((g) => g.at <= t)) tr.goal(t, g.side)
    pending = pending.filter((g) => g.at > t)
    const o = sim.ownerAt(t)
    if (o === 0) truthOurs += 0.2
    if (o === 1) truthTheirs += 0.2
    const live = tr.status(t)
    if (sc && live?.ok && live.sides === 'known' && live.ours.length >= 9) {
      // Normalised to the player attacking right: their goalkeeper near x = 0, the opponent's near x = 105.
      endsSeen++
      if (Math.min(...live.ours.map((p) => p[0])) < 20 && Math.max(...live.theirs.map((p) => p[0])) > 85) endsRight++
    }
  }
  return { record: tr.snapshot()!, truthOurs, truthTheirs, endsRight, endsSeen }
}

test('a match on the radar: ends, possession, shapes and formations, turnovers, shots, kick-offs, goals', () => {
  const { record: r, truthOurs, truthTheirs, endsRight, endsSeen } = playSim({ ours: 'blue', theirs: 'red' })
  assert.equal(r.sides, 'known')
  // Each team's own end, both halves (the teams change ends at half-time).
  assert.ok(endsRight / endsSeen >= 0.97, `${endsRight} of ${endsSeen}`)
  // Possession as the radar saw it, against the simulation's.
  const share = r.possession.ours / (r.possession.ours + r.possession.theirs)
  const truth = truthOurs / (truthOurs + truthTheirs)
  assert.ok(Math.abs(share - truth) <= 0.08, `possession ${share.toFixed(2)} vs ${truth.toFixed(2)}`)
  // Formations: 4-2-3-1 for the player, 4-3-3 for the opponent.
  assert.equal(r.shape.out?.formation, '4-2-3-1')
  assert.equal(r.theirShape.out?.formation, '4-3-3')
  // The team pushes up with the ball and drops without it.
  assert.ok(r.shape.in!.line > r.shape.out!.line + 5, `${r.shape.in!.line} vs ${r.shape.out!.line}`)
  assert.ok(r.events.filter((e) => e.kind === 'turnover').length >= 20)
  assert.ok(r.events.filter((e) => e.kind === 'kickoff').length >= 5)
  // Every goal, with its last seconds, at the right end.
  assert.equal(r.goals.length, 6)
  for (const g of r.goals) {
    assert.ok(g.frames.length >= 20)
    assert.ok(g.for ? g.x > 90 : g.x < 15, `goal ${g.for ? 'for' : 'against'} at ${g.x}`)
  }
  // Before the goals it concedes, the simulated team is pushed up with few players behind the ball.
  const against = r.goals.filter((g) => !g.for)
  assert.ok(against.filter((g) => g.defending && g.defending.behindBall <= 4).length >= 3, JSON.stringify(against.map((g) => g.defending)))
  const lines = pitchInsights([r])
  assert.ok(lines.some((l) => l.id === 'behind-ball' && l.playbook === 'dont-break-the-line'), lines.map((l) => l.id).join(', '))
  assert.ok(lines.some((l) => l.id === 'possession'))
})

test('colours left at the default: the two teams told apart, and which is the player’s worked out from the goals', () => {
  const { record: r } = playSim({})
  assert.equal(r.sides, 'known')
  // The player's team is the blue one.
  assert.ok(r.colours.ours[2] > r.colours.ours[0] && r.colours.theirs[0] > r.colours.theirs[2], JSON.stringify(r.colours))
  assert.equal(r.shape.out?.formation, '4-2-3-1')
})

test('stored pitch records are checked; a damaged one is refused', () => {
  const { record } = playSim({ ours: 'blue', theirs: 'red' }, 4)
  const json = JSON.parse(JSON.stringify(record)) as PitchMatchRecord
  assert.ok(isPitchRecord(json))
  assert.equal(isPitchRecord({ ...json, heat: { ball: [1], ours: [], theirs: [] } }), false)
  assert.equal(isPitchRecord({ ...json, goals: [{ for: 'yes' }] }), false)
  assert.equal(isPitchRecord({ ...json, version: 2 }), false)
})

test('EA SPORTS FC demo end to end: the radar read through the engine, the match record carries the pitch', () => {
  const fc = builtInProfile('ea-fc')
  const replay = fcMatchReplay({ seed: 6, realMinutes: 4 })
  const football = replay.truth.football as Parameters<typeof fcDemoPicture>[0]
  const region = fc.pitch!.radar
  const picture = fcDemoPicture(football, { seed: 6, region, colours: [RADAR_COLOURS.blue, RADAR_COLOURS.red] })
  const s = settings({ profileId: 'ea-fc', profileModeId: 'kickoff-vs-cpu', mode: 'training' })
  const e = engineFor(fc, { ...s, context: { ...s.context, 'ea-fc': { ...defaultContext(fc), radarColour: 'blue', opponentRadarColour: 'red' } } })
  const batches = [...replay.batches].sort((a, b) => a.arrivesAt - b.arrivesAt)
  let next = 0
  for (let t = 0; t <= replay.endsAt; t += 200) {
    while (next < batches.length && batches[next].arrivesAt <= t) e.ingestObservations(batches[next].observations, batches[next++].arrivesAt)
    e.processFrame(picture.frame(t))
  }
  while (next < batches.length) e.ingestObservations(batches[next].observations, batches[next++].arrivesAt)
  const rep = e.report()
  assert.equal(rep.matches.length, 1)
  const pitch = rep.matches[0].pitch
  assert.ok(pitch, 'the match record has the pitch')
  assert.equal(pitch.sides, 'known')
  const truth = replay.truth as { conceded: number; scored: number }
  assert.equal(pitch.goals.length, truth.conceded + truth.scored)
  assert.ok(isMatchRecord(JSON.parse(JSON.stringify(rep.matches[0]))))
  assert.ok((rep.pitch?.insights ?? []).length >= 2, (rep.pitch?.insights ?? []).map((l) => l.id).join(', '))
})
