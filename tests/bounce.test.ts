// The bounce, asserted rather than admired. Every case here is something a real
// ball does that a damping-multiplier model cannot.
import { World } from '../src/game/match/world'
import { makeKick } from '../src/game/control/strike'
import { emptyCommand } from '../src/game/types'
import { SIM, FIELD, BALL, SIM as S } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis

const cfg = {
  teamSize: 1,
  halfLength: 6000,
  mode: 'training' as const,
  singleKeeper: false,
  view: '3d' as const,
  position: 'MID' as const,
  heightSens: 1.6,
  curveSens: 1.15,
  humanControlled: true,
}

const results: string[] = []
const ok = (name: string, pass: boolean, detail: string) =>
  results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(38)} ${detail}`)

// A bare pitch with the player parked out of the way, so nothing intercepts.
function pitch() {
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = 3
  p.y = 3
  p.vx = 0
  p.vy = 0
  w.ball.lastTouchId = 99
  return w
}

// One bounce, in isolation. The ball starts 3cm off the deck already moving
// down, so the impact is exactly what's asked for — dropping it from height
// instead would let backspin's lift change the arrival speed and the comparison
// would be measuring flight, not the bounce.
function bounceOf(vx: number, vz: number, vSpin: number) {
  const w = pitch()
  w.ball.setPos(10, FIELD.width / 2, 0.03)
  w.ball.vx = vx
  w.ball.vy = 0
  w.ball.vz = vz
  w.ball.vSpin = vSpin
  w.ball.spin = 0
  w.ball.lastTouchId = 99
  for (let i = 0; i < 40; i++) {
    const prev = w.ball.vz
    w.update(SIM.dt, emptyCommand())
    // An upward impulse means the turf just acted on it.
    if (w.ball.vz > prev + 0.05 || (prev < -0.5 && w.ball.vz === 0)) {
      return { vx: w.ball.vx, vz: w.ball.vz, vSpin: w.ball.vSpin }
    }
  }
  return { vx: w.ball.vx, vz: w.ball.vz, vSpin: w.ball.vSpin }
}

// A free drop from `z`, reporting how high it comes back.
function dropFrom(z: number) {
  const w = pitch()
  w.ball.setPos(10, FIELD.width / 2, z)
  w.ball.vx = w.ball.vy = w.ball.vz = 0
  w.ball.vSpin = 0
  w.ball.lastTouchId = 99
  let bounced = false
  let peak = 0
  let impact = 0
  for (let i = 0; i < 120 * 8; i++) {
    const prev = w.ball.vz
    w.update(SIM.dt, emptyCommand())
    if (!bounced && w.ball.vz > prev + 0.05) {
      bounced = true
      impact = -prev
    }
    if (bounced) peak = Math.max(peak, w.ball.z)
    if (bounced && w.ball.vz < 0 && w.ball.z < peak - 0.05) break
  }
  return { peak, e: peak > 0 ? Math.sqrt((2 * S.gravity * peak) / (impact * impact)) : 0 }
}

// ---- 1. restitution falls off with impact speed ----------------------------
{
  const soft = bounceOf(0, -3, 0)
  const hard = bounceOf(0, -20, 0)
  const eSoft = soft.vz / 3
  const eHard = hard.vz / 20
  ok(
    'a hard ball bounces lower than a soft one',
    eSoft > eHard + 0.15,
    `e = ${eSoft.toFixed(2)} at 3 m/s vs ${eHard.toFixed(2)} at 20 m/s`,
  )
}

// ---- 2. the FIFA-ish drop test ---------------------------------------------
{
  const r = dropFrom(2)
  ok(
    'a 2 m drop rebounds to real height',
    r.peak > 0.85 && r.peak < 1.25,
    `${r.peak.toFixed(2)} m, e = ${r.e.toFixed(2)}   (grass: 0.9-1.2 m)`,
  )
}

// ---- 3. spin decides where the bounce goes ---------------------------------
{
  const plain = bounceOf(14, -8, 0)
  const top = bounceOf(14, -8, 20) // heavy topspin
  const back = bounceOf(14, -8, -14) // heavy backspin
  ok(
    'topspin skids on, backspin checks',
    top.vx > plain.vx && plain.vx > back.vx,
    `top ${top.vx.toFixed(1)} > none ${plain.vx.toFixed(1)} > back ${back.vx.toFixed(1)} m/s`,
  )
  // One bounce understates it: a back-spun ball is still back-spun afterwards,
  // so it checks again at the next contact and the next. What matters is how far
  // it actually runs on, which is what a defender is judging.
  const runOn = (vSpin: number) => {
    const w = pitch()
    w.ball.setPos(10, FIELD.width / 2, 0.03)
    w.ball.vx = 14
    w.ball.vy = 0
    w.ball.vz = -8
    w.ball.vSpin = vSpin
    w.ball.lastTouchId = 99
    const x0 = w.ball.x
    for (let i = 0; i < 120 * 20; i++) {
      w.update(SIM.dt, emptyCommand())
      if (w.ball.speed < BALL.settleSpeed) break
      if (w.ball.x > FIELD.length - 2) break
    }
    return w.ball.x - x0
  }
  const runPlain = runOn(0)
  const runBack = runOn(-14)
  const runTop = runOn(20)
  ok(
    'backspin kills the run-on, topspin extends it',
    runBack < runPlain * 0.7 && runTop > runPlain * 1.2,
    `back ${runBack.toFixed(1)} m < none ${runPlain.toFixed(1)} m < top ${runTop.toFixed(1)} m`,
  )
}

// ---- 4. a plain ball starts rolling out of the bounce ----------------------
{
  const r = bounceOf(12, -9, 0)
  ok(
    'a bounce spins an unspun ball up',
    r.vSpin > 3,
    `vSpin 0 -> ${r.vSpin.toFixed(1)} m/s of surface (rolling at ${r.vx.toFixed(1)})`,
  )
}

// ---- 5. a rolling ball keeps rolling ---------------------------------------
{
  const w = pitch()
  w.ball.setPos(3, FIELD.width / 2, 0)
  w.ball.vx = 15
  w.ball.vSpin = 15 // properly rolling
  w.ball.lastTouchId = 99
  const x0 = w.ball.x
  for (let i = 0; i < 120 * 30; i++) {
    w.update(SIM.dt, emptyCommand())
    if (w.ball.speed < BALL.settleSpeed || w.ball.x > FIELD.length - 2) break
  }
  const ran = w.ball.x - x0
  ok('a rolled ball carries realistically', ran > 35, `15 m/s ran ${ran.toFixed(1)} m   (real: 40-60 m)`)
}

// ---- 6. the down-flick is a low driven pass --------------------------------
{
  const w = pitch()
  const p = w.getControlledPlayer()!
  p.x = 4
  p.y = FIELD.width / 2
  p.heading = 0
  p.kickCooldown = 0
  w.ball.setPos(4.55, FIELD.width / 2, 0)
  w.ball.lastTouchId = -1
  const cmd = emptyCommand()
  cmd.aim = { x: 1, y: 0 }
  // Only a half flick down — this has to work without perfect execution.
  cmd.kick = makeKick('strike', 0.6, { x: 1, y: 0 }, { loft: -0.5, spin: 0 })
  w.update(SIM.dt, cmd)
  let maxZ = 0
  let ran = 0
  const x0 = w.ball.x
  for (let i = 0; i < 120 * 8; i++) {
    w.update(SIM.dt, emptyCommand())
    maxZ = Math.max(maxZ, w.ball.z)
    ran = w.ball.x - x0
    if (w.ball.x > FIELD.length - 3 || w.ball.speed < BALL.settleSpeed) break
  }
  ok(
    'half a down-flick keeps it on the floor',
    maxZ < 0.05 && ran > 20,
    `never left the deck (max ${maxZ.toFixed(3)} m), ran ${ran.toFixed(1)} m`,
  )
}

// ---- 7. a chip checks up on landing ----------------------------------------
{
  const w = pitch()
  const p = w.getControlledPlayer()!
  p.x = 10
  p.y = FIELD.width / 2
  p.heading = 0
  p.kickCooldown = 0
  w.ball.setPos(10.55, FIELD.width / 2, 0)
  w.ball.lastTouchId = -1
  const cmd = emptyCommand()
  cmd.aim = { x: 1, y: 0 }
  cmd.kick = makeKick('strike', 0.35, { x: 1, y: 0 }, { loft: 1, spin: 0 })
  w.update(SIM.dt, cmd)
  const launch = Math.hypot(w.ball.vx, w.ball.vy)
  let landed = false
  let afterVx = 0
  for (let i = 0; i < 120 * 8; i++) {
    const wasDown = w.ball.vz < 0
    w.update(SIM.dt, emptyCommand())
    if (!landed && wasDown && w.ball.vz >= 0 && w.ball.z < 0.05) {
      landed = true
      afterVx = w.ball.vx
      break
    }
  }
  ok(
    'a backspun chip sits down on landing',
    landed && afterVx < launch * 0.45,
    `left the boot at ${launch.toFixed(1)}, first bounce leaves ${afterVx.toFixed(1)} m/s`,
  )
}

// ---- 8. the long ball still meets its brief --------------------------------
{
  const w = pitch()
  const p = w.getControlledPlayer()!
  p.x = 1.5
  p.y = FIELD.width / 2
  p.heading = 0
  p.kickCooldown = 0
  w.ball.setPos(2, FIELD.width / 2, 0)
  w.ball.lastTouchId = -1
  const cmd = emptyCommand()
  cmd.aim = { x: 1, y: 0 }
  cmd.kick = makeKick('strike', 1, { x: 1, y: 0 }, { loft: 1, spin: 0 })
  w.update(SIM.dt, cmd)
  let zAtFarGoal = -1
  let apex = 0
  for (let i = 0; i < 120 * 10; i++) {
    w.update(SIM.dt, emptyCommand())
    apex = Math.max(apex, w.ball.z)
    if (zAtFarGoal < 0 && w.ball.x >= FIELD.length - 0.2) zAtFarGoal = w.ball.z
    if (w.phase !== 'playing' || zAtFarGoal >= 0) break
  }
  ok(
    'full power + full flick reaches the far goal',
    zAtFarGoal >= 0 && zAtFarGoal < FIELD.goalHeight + 2.5,
    `crosses the far line at ${zAtFarGoal.toFixed(2)} m (bar is ${FIELD.goalHeight}), apex ${apex.toFixed(1)} m`,
  )
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
