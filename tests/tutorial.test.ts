// The first lesson: does each step end when — and only when — the thing it
// asks for really happens?
//
// The tutorial watches the world and never steers it, so it can be driven with
// exactly the commands a controller would produce. What is checked is the
// dishonest ways through it: a step that ticks off because you clicked at
// nothing, a "strike" that was a nudge, a lift that was a soft chip, a
// shield that lasted half a second.
import { World } from '../src/game/match/world'
import { Tutorial, LESSON } from '../src/game/match/tutorial'
import { makeKick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import type { Command } from '../src/game/types'
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

const fresh = () => {
  const w = new World({ ...cfg })
  const t = new Tutorial()
  return { w, t, me: w.getControlledPlayer()! }
}
// Advance the world and the tutorial together, as the game loop does.
const run = (w: World, t: Tutorial, seconds: number, make: (i: number) => Command) => {
  const n = Math.round(seconds / DT)
  for (let i = 0; i < n; i++) {
    const c = make(i)
    w.update(DT, c)
    t.observe(w, c, DT)
  }
}
const idle = () => emptyCommand()
// Put a lesson on a given step. The world is fresh, so its counters and the
// lesson's starting counts are both zero, which is what entering a step does.
const STEP_INDEX: Record<string, number> = { look: 0, run: 1, touch: 2, strike: 3, shape: 4, jump: 5, shield: 6, slide: 7, score: 8 }
const at = (t: Tutorial, step: string) => {
  t.index = STEP_INDEX[step]
}

// ---- 1. it starts at the start, and standing still teaches nothing --------------
{
  const { w, t } = fresh()
  run(w, t, 6, idle)
  ok(
    'it starts at "look" and idling does not move it',
    t.step.id === 'look' && t.index === 0 && t.total === 9 && !t.finished,
    `after six seconds of doing nothing: step ${t.index + 1} of ${t.total} ("${t.step.id}")`,
  )
}

// ---- 2. looking around -----------------------------------------------------------
{
  const a = fresh()
  // A wobble of the hand is not looking around.
  run(a.w, a.t, 3, (i) => {
    const c = emptyCommand()
    const ang = Math.sin(i * 0.05) * 0.08
    c.aim = { x: Math.cos(ang), y: Math.sin(ang) }
    return c
  })
  const wobbled = a.t.step.id
  const b = fresh()
  run(b.w, b.t, 2, (i) => {
    const c = emptyCommand()
    const ang = (i / 240) * Math.PI * 1.2
    c.aim = { x: Math.cos(ang), y: Math.sin(ang) }
    return c
  })
  ok(
    'a real turn ends it; a wobble does not',
    wobbled === 'look' && b.t.step.id === 'run',
    `a ±0.08 rad wobble for 3 s: still "${wobbled}"; a ${(Math.PI * 1.2).toFixed(1)} rad sweep: on to "${b.t.step.id}"`,
  )
}

// ---- 3. running -----------------------------------------------------------------
{
  const walk = fresh()
  at(walk.t, 'run')
  run(walk.w, walk.t, 6, () => {
    const c = emptyCommand()
    c.move = { x: 1, y: 0 }; c.aim = { x: 1, y: 0 }; c.walk = true
    return c
  })
  const walked = walk.t.step.id

  const spot = fresh()
  ;spot.t.index = 1
  run(spot.w, spot.t, 4, () => {
    const c = emptyCommand()
    c.sprint = true // no direction: sprinting on the spot
    return c
  })
  const onSpot = spot.t.step.id

  const go = fresh()
  ;go.t.index = 1
  go.me.x = 4
  run(go.w, go.t, 4, () => {
    const c = emptyCommand()
    c.move = { x: 1, y: 0 }; c.aim = { x: 1, y: 0 }; c.sprint = true
    return c
  })
  ok(
    'running needs distance and a sprint',
    walked === 'run' && onSpot === 'run' && go.t.step.id === 'touch',
    `walking 6 s: "${walked}"; sprinting on the spot: "${onSpot}"; sprinting ${(go.me.x - 4).toFixed(0)} m: "${go.t.step.id}"`,
  )
}

// ---- 4. first touch ---------------------------------------------------------------
{
  const far = fresh()
  ;far.t.index = 2
  far.me.x = 10; far.me.y = GY
  far.w.ball.setPos(40, GY, 0); far.w.ball.stop()
  run(far.w, far.t, 4, (i) => {
    const c = emptyCommand()
    c.aim = { x: 1, y: 0 }
    if (i % 30 === 0) c.kick = makeKick('touch', 0.2, { x: 1, y: 0 }, { loft: 0, spin: 0 })
    return c
  })
  const missed = far.w.session.touches

  const near = fresh()
  ;near.t.index = 2
  near.me.x = 10; near.me.y = GY; near.me.heading = 0
  near.w.ball.setPos(10.8, GY, 0); near.w.ball.stop()
  let landed = 0
  run(near.w, near.t, 6, (i) => {
    const c = emptyCommand()
    c.move = { x: 1, y: 0 }; c.aim = { x: 1, y: 0 }
    if (i % 40 === 0) c.kick = makeKick('touch', 0.15, { x: 1, y: 0 }, { loft: 0, spin: 0 })
    if (c.kick) landed++
    return c
  })
  ok(
    'a touch only counts if it reached the ball',
    missed === 0 && far.t.step.id === 'touch' && near.w.session.touches >= LESSON.touches && near.t.step.id !== 'touch',
    `${missed} of 4 s of clicks at a ball 30 m away counted; ${near.w.session.touches} of ${landed} clicks with it at your feet did, and the lesson moved on to "${near.t.step.id}"`,
  )
}

// ---- 5. strike ----------------------------------------------------------------------
const strikeAt = (power: number, loft: number, spin: number, step: string) => {
  const s = fresh()
  at(s.t, step)
  s.me.x = 20; s.me.y = GY; s.me.heading = 0
  s.w.ball.setPos(20.7, GY, 0); s.w.ball.stop()
  run(s.w, s.t, 1.0, (i) => {
    const c = emptyCommand()
    c.aim = { x: 1, y: 0 }
    if (i === 0) c.kick = makeKick('strike', power, { x: 1, y: 0 }, { loft, spin })
    return c
  })
  return s
}
{
  const soft = strikeAt(0.12, 0, 0, 'strike')
  const hard = strikeAt(1, 0, 0, 'strike')
  ok(
    'a nudge is not a strike; a hard one is',
    soft.t.step.id === 'strike' && hard.t.step.id === 'shape',
    `a 12% strike left at ${soft.w.session.lastStrike.speed.toFixed(1)} m/s (needs ${LESSON.strike}): "${soft.t.step.id}"; a full one at ${hard.w.session.lastStrike.speed.toFixed(1)}: "${hard.t.step.id}"`,
  )
}

// ---- 6. shaping it -----------------------------------------------------------------
{
  const flat = strikeAt(1, 0, 0, 'shape')
  const chip = strikeAt(0.12, 0.8, 0, 'shape')
  const lift = strikeAt(1, 0.7, 0, 'shape')
  const liftedText = lift.t.coach(lift.w)!.text
  // Now the other half, on top of the lift.
  const t = lift.t
  const me = lift.me
  me.x = 20; me.y = GY
  lift.w.ball.setPos(20.7, GY, 0); lift.w.ball.stop()
  run(lift.w, t, 1.0, (i) => {
    const c = emptyCommand()
    c.aim = { x: 1, y: 0 }
    if (i === 0) c.kick = makeKick('strike', 1, { x: 1, y: 0 }, { loft: 0, spin: 0.7 })
    return c
  })
  ok(
    'shaping needs a lift and a bend, both at real pace',
    flat.t.step.id === 'shape' && chip.t.step.id === 'shape' && liftedText.includes('lifted ✓') && liftedText.includes('not bent yet') && t.step.id === 'jump',
    `flat drive: still "shape"; a soft chip: still "shape"; a hard lift: "${liftedText.slice(-32)}"; then a hard curl: on to "${t.step.id}"`,
  )
}

// ---- 7. jumping, shielding, sliding ---------------------------------------------------
{
  const j = fresh()
  at(j.t, 'jump')
  run(j.w, j.t, 0.5, idle)
  const before = j.t.step.id
  run(j.w, j.t, 0.6, (i) => {
    const c = emptyCommand()
    if (i < 5) c.jump = true
    return c
  })
  ok('a jump ends the jump step', before === 'jump' && j.t.step.id === 'shield', `standing: "${before}"; after pressing jump: "${j.t.step.id}"`)

  const brief = fresh()
  at(brief.t, 'shield')
  run(brief.w, brief.t, 0.4, () => {
    const c = emptyCommand()
    c.shield = true
    return c
  })
  const tooShort = brief.t.step.id
  const held = fresh()
  at(held.t, 'shield')
  run(held.w, held.t, 1.6, () => {
    const c = emptyCommand()
    c.shield = true
    return c
  })
  ok('a shield has to be held', tooShort === 'shield' && held.t.step.id === 'slide',
    `0.4 s: still "${tooShort}"; 1.6 s: "${held.t.step.id}"`)

  const s = fresh()
  at(s.t, 'slide')
  s.me.x = 20; s.me.y = GY
  run(s.w, s.t, 0.3, (i) => {
    const c = emptyCommand()
    c.move = { x: 1, y: 0 }; c.aim = { x: 1, y: 0 }
    if (i < 3) c.slide = true
    return c
  })
  ok('a slide ends the slide step', s.t.step.id === 'score', `after the slide: "${s.t.step.id}"`)
}

// ---- 8. scoring finishes it ---------------------------------------------------------------
{
  const g = fresh()
  at(g.t, 'score')
  g.me.x = 48; g.me.y = GY; g.me.heading = 0
  g.w.ball.setPos(49, GY, 0); g.w.ball.stop()
  run(g.w, g.t, 2.5, (i) => {
    const c = emptyCommand()
    c.aim = { x: 1, y: 0 }
    if (i === 0) c.kick = makeKick('strike', 1, { x: 1, y: 0 }, { loft: 0, spin: 0 })
    return c
  })
  const coach = g.t.coach(g.w)
  run(g.w, g.t, 8, idle)
  ok(
    'a goal finishes the lesson, and its last word leaves on its own',
    g.t.finished && g.w.session.goals === 1 && coach?.title === "That's the lot" && g.t.coach(g.w) === null,
    `${g.w.session.goals} goal: finished, said "${coach?.title}", and 8 s later said nothing`,
  )
}

// ---- 9. skipping -------------------------------------------------------------------------------
{
  const s = fresh()
  const before = s.t.index
  s.t.skip(s.w)
  s.t.skip(s.w)
  for (let i = 0; i < 20; i++) s.t.skip(s.w)
  ok('a step can be skipped, and skipping past the end finishes it', before === 0 && s.t.finished,
    `step ${before + 1} → after 22 skips: finished ${s.t.finished}`)
}

// ---- 10. the session counters that the lesson stands on -----------------------------------------
{
  const w = new World({ ...cfg })
  const me = w.getControlledPlayer()!
  me.x = 20; me.y = GY; me.heading = 0
  const take = (type: 'touch' | 'strike', power: number) => {
    w.ball.setPos(20.7, GY, 0); w.ball.stop()
    me.kickCooldown = 0
    const c = emptyCommand()
    c.aim = { x: 1, y: 0 }
    c.kick = makeKick(type, power, { x: 1, y: 0 }, { loft: 0, spin: 0 })
    w.update(DT, c)
  }
  take('touch', 0.2)
  take('touch', 0.2)
  take('strike', 0.5)
  take('strike', 1)
  const s = w.session
  ok(
    'the world counts your touches and strikes and your fastest ball',
    s.touches === 2 && s.strikes === 2 && s.topSpeed > 20 && Math.abs(s.lastStrike.speed - s.topSpeed) < 0.5,
    `${s.touches} touches, ${s.strikes} strikes, top speed ${(s.topSpeed * 3.6).toFixed(0)} km/h`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
