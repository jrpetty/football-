// Spin off the woodwork. The most dramatic thing that can happen to a struck
// ball used to be settled by pure geometry: it reflected like a billiard ball
// and the spin was thrown away. These check it is now a real contact.
import { Ball } from '../src/game/physics/ball'
import { World } from '../src/game/match/world'
import { emptyCommand } from '../src/game/types'
import { SIM, FIELD, BALL } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis

const results: string[] = []
const ok = (n: string, p: boolean, d: string) =>
  results.push(`${p ? 'PASS' : 'FAIL'}  ${n.padEnd(44)} ${d}`)

// Bounce a ball travelling +x off a wall whose outward normal is −x.
function offPost(spin: number, vx = 20, vy = 0) {
  const b = new Ball()
  b.vx = vx
  b.vy = vy
  b.spin = spin
  b.reflect(-1, 0, 0.5)
  return { vx: b.vx, vy: b.vy, spin: b.spin, angle: (Math.atan2(b.vy, -b.vx) * 180) / Math.PI }
}

// ---- 1. spin sends it off at a different angle ------------------------------
{
  const none = offPost(0)
  const left = offPost(-9)
  const right = offPost(9)
  ok(
    'side-spin changes where it comes off',
    Math.abs(none.vy) < 0.01 && right.vy > 1.5 && left.vy < -1.5,
    `no spin ${none.angle.toFixed(1)}°, spinning one way ${right.angle.toFixed(1)}°, the other ${left.angle.toFixed(1)}°`,
  )
}

// ---- 2. ...and the two directions are mirror images -------------------------
{
  const l = offPost(-9)
  const r = offPost(9)
  ok(
    'it is symmetric',
    Math.abs(l.vy + r.vy) < 0.01,
    `${r.vy.toFixed(2)} vs ${l.vy.toFixed(2)} m/s across`,
  )
}

// ---- 3. the ball comes off turning differently than it arrived -------------
{
  const r = offPost(9)
  ok(
    'the contact changes the spin too',
    Math.abs(r.spin - 9) > 1 && Math.abs(r.spin) < 9,
    `arrived with 9.0 m/s of surface, left with ${r.spin.toFixed(1)}`,
  )
}

// ---- 4. friction is bounded, not free --------------------------------------
{
  // A glancing contact has almost no normal impulse, so it cannot grip much.
  const hard = offPost(12, 24)
  const soft = offPost(12, 2)
  ok(
    'a soft contact grips less than a hard one',
    Math.abs(soft.vy) < Math.abs(hard.vy),
    `24 m/s into it: ${hard.vy.toFixed(2)} across · 2 m/s: ${soft.vy.toFixed(2)}`,
  )
}

// ---- 5. an unspun ball is scrubbed and spun up -----------------------------
{
  // Not a mirror. A ball sliding across a surface with no spin of its own is
  // exactly the case friction acts hardest on: it loses tangential pace and
  // comes off turning, the same way it does off the turf. Expecting angle-in
  // equals angle-out is the billiard-ball assumption this replaced.
  const r = offPost(0, 15, 6)
  ok(
    'an unspun ball is scrubbed and spun up',
    r.vy < 6 && r.vy > 0 && Math.abs(r.spin) > 1,
    `came in at 15,6 with no spin — left at ${r.vx.toFixed(1)},${r.vy.toFixed(1)} turning at ${r.spin.toFixed(1)}`,
  )
}

// ---- 6. it works on a real post in a real match ----------------------------
{
  const cfg = {
    teamSize: 1, halfLength: 6000, mode: 'training' as const, singleKeeper: false,
    view: '3d' as const,
  quality: 'medium' as const, position: 'MID' as const, heightSens: 1.6, curveSens: 1.15,
    humanControlled: true,
  }
  const hitPost = (spin: number) => {
    const w = new World(cfg)
    const p = w.getControlledPlayer()!
    p.x = 4
    p.y = 3
    const [pa] = (function () {
      const half = FIELD.goalWidth / 2
      return [FIELD.width / 2 - half, FIELD.width / 2 + half]
    })()
    // Start it close to the upright and fire straight at it: from further out
    // the Magnus bend curls it past the post on one side, and then the test is
    // measuring whether it hit anything rather than how it came off.
    w.ball.setPos(FIELD.length - 1.6, pa, 0.4)
    w.ball.vx = 22
    w.ball.vy = 0
    w.ball.spin = spin
    w.ball.lastTouchId = 99
    let rebounded = false
    for (let i = 0; i < 120 * 2; i++) {
      w.update(SIM.dt, emptyCommand())
      if (w.ball.vx < 0) {
        rebounded = true
        break
      }
    }
    return { vy: w.ball.vy, rebounded }
  }
  const a = hitPost(10)
  const b = hitPost(-10)
  ok(
    'a real post does it too',
    a.rebounded && b.rebounded && Math.abs(a.vy - b.vy) > 1,
    `off the upright: ${a.vy.toFixed(2)} m/s across one way, ${b.vy.toFixed(2)} the other`,
  )
  void BALL
}

console.log(results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
