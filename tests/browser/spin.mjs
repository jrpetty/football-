// The ball turns at its true angular rate, about the right axis.
// Does the ball mesh actually turn at the rate the physics says?
//
// Needs a browser: this reads the actual mesh orientation out of the running
// scene. Run with:  npm run build:single && node tests/browser/spin.mjs
import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')
const b = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined, args:['--use-gl=swiftshader','--enable-unsafe-swiftshader'] })
const pg = await b.newPage({ viewport:{ width:800, height:520 } })
pg.on('pageerror', e => console.log('pageerror:', e.message))
await pg.goto(`file://${process.cwd()}/open-pitch.html?debug`)
await pg.waitForTimeout(900)
await pg.click('text=🎮 Immersive 3D')
await pg.click('text=🎯  Training (solo, no AI)')
await pg.waitForTimeout(2200)
const out = await pg.evaluate(() => {
  const game = window.__game, w = window.__world
  const scene = Object.values(game).find(v => v && v.players instanceof Map && v.players.size)
  game.running = false
  const mesh = scene.ball
  const R = 0.11
  const rows = []
  const measure = (label, vx, vy, vSpin, spin) => {
    w.ball.setPos(29, 19, 1.5)
    w.ball.vx = vx; w.ball.vy = vy; w.ball.vz = 0; w.ball.vSpin = vSpin; w.ball.spin = spin
    mesh.quaternion.identity()
    const dt = 1/240, steps = 24
    // Accumulate per step: the angle between two orientations wraps at pi, and a
    // spinning football clears that many times over in a tenth of a second.
    let total = 0
    for (let i = 0; i < steps; i++) {
      const before = mesh.quaternion.clone()
      scene.spinBall(w.ball, dt)
      const d = mesh.quaternion.clone().multiply(before.invert())
      total += 2 * Math.acos(Math.min(1, Math.abs(d.w)))
    }
    const measured = total / (dt * steps)
    const expected = Math.hypot(vSpin / R, spin / R)
    rows.push({ label, measured: +measured.toFixed(1), expected: +expected.toFixed(1),
      revs: +(measured / (2 * Math.PI)).toFixed(1) })
  }
  // Direction check: for a ball rolling forwards along +X, the point on TOP of
  // it must travel forwards and the point at the BOTTOM must be stationary
  // against the ground. That is what rolling means, and it's the one thing the
  // rate alone can't tell you.
  const dirCheck = (label, vx, vSpin, spinY) => {
    w.ball.setPos(29, 19, 1.5)
    w.ball.vx = vx; w.ball.vy = 0; w.ball.vz = 0; w.ball.vSpin = vSpin; w.ball.spin = spinY
    mesh.quaternion.identity()
    const R = 0.11
    const top = new (mesh.position.constructor)(0, R, 0)
    const bottom = new (mesh.position.constructor)(0, -R, 0)
    const t0 = top.clone(), b0 = bottom.clone()
    const dt = 1/2000
    scene.spinBall(w.ball, dt)
    const t1 = t0.clone().applyQuaternion(mesh.quaternion)
    const b1 = b0.clone().applyQuaternion(mesh.quaternion)
    // Surface velocity of each point, plus the ball's own travel.
    const topV = (t1.x - t0.x) / dt + vx
    const botV = (b1.x - b0.x) / dt + vx
    const sideV = (t1.z - t0.z) / dt
    rows.push({ label, top: +topV.toFixed(1), bottom: +botV.toFixed(1), side: +sideV.toFixed(1) })
  }
  dirCheck('ROLLING fwd 8 m/s', 8, 8, 0)
  dirCheck('BACKSPIN at 8 m/s', 8, -8, 0)
  dirCheck('SIDE-SPIN +, 8 m/s', 8, 0, 8)

  measure('rolling at 8 m/s', 8, 0, 8, 0)
  measure('driven, heavy topspin', 34, 0, 27, 0)
  measure('backspun chip', 16, 0, -5, 0)
  measure('pure side-spin', 25, 0, 0, 8)
  measure('curled + backspin', 25, 0, -4, 6)
  return rows
})
console.log('direction (world m/s of the surface point, ball travelling +X at 8):')
for (const r of out.filter(r => r.top !== undefined))
  console.log(`  ${r.label.padEnd(20)} top ${String(r.top).padStart(6)}   bottom ${String(r.bottom).padStart(6)}   top sideways ${r.side}`)
console.log('\nrate:                    measured rad/s   expected   rev/s')
for (const r of out.filter(r => r.measured !== undefined))
  console.log(`  ${r.label.padEnd(22)} ${String(r.measured).padStart(8)}   ${String(r.expected).padStart(8)}   ${r.revs}`)
await b.close()
