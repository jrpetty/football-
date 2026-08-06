// The backheel is a right-click move, and that is what limits it.
//
// It was already true that a left click could not produce one — but only
// because all three call sites happened to do the right thing, and a skill
// arriving on a strike was silently dropped rather than refused. These pin the
// rule down so it stays true: a skill cannot be attached to anything but a
// touch, and a backheel cannot exceed what the right button can generate.
//
// The ceiling is the design. A heel is not a swing, so the price of playing
// the ball behind you is that you cannot get a shot on it.
import { World } from '../src/game/match/world'
import { makeKick, skillFromFlick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import type { Skill } from '../src/game/types'
import { FIELD, KICK, CONTROL } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(48)} ${detail}`)

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

// Fire one kick from a standing start and report what the ball did. `backing`
// puts the player in reverse, which is the condition a backheel needs.
function fire(type: 'touch' | 'strike', power: number, skill: Skill | null, backing = false) {
  const w = new World(cfg)
  w.phase = 'playing'
  const p = w.getControlledPlayer()!
  p.x = FIELD.length / 2
  p.y = FIELD.width / 2
  p.heading = 0
  if (backing) {
    p.vx = -3
    p.vy = 0
  }
  w.ball.setPos(p.x + 0.5, p.y, 0)
  w.ball.vx = w.ball.vy = w.ball.vz = 0
  const c = emptyCommand()
  c.aim = { x: 1, y: 0 }
  c.kick = makeKick(type, power, { x: 1, y: 0 }, { loft: 0, spin: 0 }, skill)
  w.update(1 / 120, c)
  // The player faces +x, so a negative vx means the ball went behind them.
  return { speed: w.ball.speed, vx: w.ball.vx, kick: c.kick }
}

// ---- 1. a strike cannot carry a skill at all --------------------------------
{
  const k = makeKick('strike', 1, { x: 1, y: 0 }, { loft: 0, spin: 0 }, 'backheel')
  ok(
    'a strike is built without a skill on it',
    k.skill === undefined,
    `asked for a backheel on a left click; the request came back carrying none`,
  )
}

{
  const k = makeKick('touch', 1, { x: 1, y: 0 }, { loft: 0, spin: 0 }, 'backheel')
  ok(
    'a touch keeps one',
    k.skill === 'backheel',
    `the same call on the right button keeps the backheel`,
  )
}

// ---- 2. and the simulation does the forward thing anyway --------------------
{
  const r = fire('strike', 1, 'backheel', true)
  ok(
    'a left click sends it forwards, never behind you',
    r.vx > 0,
    `full strike asking for a backheel went +${r.vx.toFixed(1)} m/s — straight ahead, as a strike does`,
  )
}

{
  const r = fire('touch', 1, 'backheel', true)
  ok(
    'a right click sends it behind you',
    r.vx < 0,
    `${r.vx.toFixed(1)} m/s — past the player rather than in front of them`,
  )
}

// ---- 3. the ceiling ---------------------------------------------------------
{
  const heel = fire('touch', 1, 'backheel', true)
  const boot = fire('strike', 1, null)
  ok(
    'a backheel cannot exceed what a touch can generate',
    heel.speed <= KICK.touchMax + 1e-6,
    `${heel.speed.toFixed(2)} m/s against a touch ceiling of ${KICK.touchMax}`,
  )
  ok(
    'so it is nowhere near a shot',
    heel.speed < boot.speed * 0.45,
    `heel ${heel.speed.toFixed(1)} m/s vs boot ${boot.speed.toFixed(1)} m/s — ${(boot.speed / heel.speed).toFixed(1)}× the pace on the left button`,
  )
}

{
  // Every skill, at full charge, against the touch ceiling. None of them is a
  // way of getting strike power out of the right button.
  const worst = (['backheel', 'drag', 'roll', 'lift'] as Skill[])
    .map((s) => ({ s, v: fire('touch', 1, s, s === 'backheel').speed }))
    .sort((a, b) => b.v - a.v)[0]
  ok(
    'and no close-control move beats it',
    worst.v <= KICK.touchMax + 1e-6,
    `the quickest is ${worst.s} at ${worst.v.toFixed(2)} m/s, ceiling ${KICK.touchMax}`,
  )
}

// ---- 4. it is still the strongest touch you have ----------------------------
{
  const heel = fire('touch', 1, 'backheel', true)
  const drag = fire('touch', 1, 'drag')
  ok(
    'a backheel still beats a drag-back',
    heel.speed > drag.speed * 1.6,
    `${heel.speed.toFixed(1)} m/s past you vs ${drag.speed.toFixed(1)} m/s under you`,
  )
}

// ---- 5. and it still takes the deliberate flick -----------------------------
{
  const soft = skillFromFlick({ x: 0, y: CONTROL.skillFlickMin * 0.6 }, true)
  const hard = skillFromFlick({ x: 0, y: CONTROL.skillFlickMin * 1.4 }, true)
  const notBacking = skillFromFlick({ x: 0, y: CONTROL.skillFlickMin * 1.4 }, false)
  ok(
    'a gentle flick is not a backheel',
    soft === null && hard === 'backheel' && notBacking === 'drag',
    `soft flick → nothing, hard flick backing away → backheel, hard flick standing → drag`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
