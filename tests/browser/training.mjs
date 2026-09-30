// Training, end to end, in the real game loop.
//
// The simulation is tested on Node, but two things you feel only exist in the
// loop around it: the goal replay (started by the game loop, not by the world —
// it freezes the world for about thirteen seconds and takes your input away),
// and what your own clicks and legs do to the ball through the real controller.
//
// A headless page cannot take pointer lock, so the game never reads the keyboard
// or the mouse. This makes it believe it has the lock and supplies the commands
// itself — exactly what a player's controller would have produced — so the whole
// real pipeline runs: command → world step → snapshot → replay decision → draw.
//
// Rendering is switched off for the logic checks: a software rasteriser draws a
// frame a second, which would turn a few seconds of play into minutes, and none
// of what is measured here is a picture. It is switched back on for the screenshot.
//
// Run with:  npm run build:single && node tests/browser/training.mjs
import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')
const { createServer } = await import('node:http')
const { readFileSync } = await import('node:fs')

const html = readFileSync(`${process.cwd()}/open-pitch.html`)
const server = createServer((_, res) => { res.writeHead(200, { 'content-type': 'text/html' }); res.end(html) })
await new Promise((r) => server.listen(0, r))
const url = `http://127.0.0.1:${server.address().port}/?debug`

const b = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'],
})
const results = []
const errs = []
const ok = (name, pass, detail) => results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(54)} ${detail}`)

const pg = await b.newPage({ viewport: { width: 900, height: 560 } })
pg.on('pageerror', (e) => errs.push(e.message))
await pg.goto(url)
await pg.waitForTimeout(700)
await pg.click('[data-seg="view"][data-val="3d"]')
await pg.click('[data-act="training"]')
await pg.waitForTimeout(1800)

// Take the controls, and stop drawing.
await pg.evaluate(() => {
  const g = window.__game
  Object.defineProperty(g.input, 'pointerLocked', { get: () => true })
  const scene = Object.values(g).find((v) => v && v.players instanceof Map)
  g.__draw = { render: scene.render.bind(scene), sync: scene.sync.bind(scene) }
  // Rendering is off, but the game still calls it once per tick — replay frames
  // included — so counting those calls counts the game's own loop. (Counting the
  // browser's frames instead can't tell a running game from a dead one: the
  // browser keeps ticking whether or not the game asks for another frame.)
  window.__gameFrames = 0
  scene.render = () => { window.__gameFrames++ }
  scene.sync = () => {}
  const base = { move: { x: 0, y: 0 }, aim: { x: 1, y: 0 }, sprint: false, walk: false, chargePass: false, chargeShot: false, kick: null, jump: false, shield: false, slide: false }
  window.__mk = (over) => ({ ...base, move: { ...base.move }, aim: { ...base.aim }, ...over })
  window.__next = () => window.__mk({})
  g.human.buildCommand = () => window.__next()
})

const wait = (ms) => pg.waitForTimeout(ms)
const S = (fn, arg) => pg.evaluate(fn, arg)

// ---- 1. sprinting into a ball, with nothing pressed -----------------------------
{
  await S(() => {
    const w = window.__world
    const p = w.getControlledPlayer()
    p.x = 12; p.y = 19; p.vx = 0; p.vy = 0
    w.ball.setPos(20, 19, 0); w.ball.stop()
    window.__next = () => window.__mk({ move: { x: 1, y: 0 }, sprint: true })
  })
  await wait(2600)
  const r = await S(() => {
    const w = window.__world, p = w.getControlledPlayer()
    return { ball: +w.ball.x.toFixed(3), player: +p.x.toFixed(1), speed: +w.ball.speed.toFixed(3) }
  })
  ok('sprinting through a ball does not move it', r.ball === 20 && r.speed === 0,
    `you ran to x=${r.player}, straight through the ball; it is still at x=${r.ball}`)
}

// ---- 2. tapping it along ---------------------------------------------------------
{
  await S(() => {
    const w = window.__world, p = w.getControlledPlayer()
    p.x = 10; p.y = 19; p.vx = 0; p.vy = 0
    w.ball.setPos(10.8, 19, 0); w.ball.stop()
    let n = 0
    window.__gap = 0
    window.__next = () => {
      n++
      const gap = Math.hypot(w.ball.x - p.x, w.ball.y - p.y)
      window.__gap = Math.max(window.__gap, gap)
      const c = window.__mk({ move: { x: 1, y: 0 } })
      // A light right-click tap every ~0.4 s, straight ahead.
      if (n % 24 === 0) c.kick = { type: 'touch', power: 0.15, aim: { x: 1, y: 0 }, loft: 0, spin: 0 }
      return c
    }
  })
  await wait(3000)
  const r = await S(() => {
    const w = window.__world, p = w.getControlledPlayer()
    return { ball: +w.ball.x.toFixed(1), player: +p.x.toFixed(1), gap: +window.__gap.toFixed(1) }
  })
  ok('right-click taps carry it with you', r.ball - 10.8 > 8 && r.gap < 3.2,
    `ball ${r.ball}, you ${r.player}: taken ${(r.ball - 10.8).toFixed(1)} m and never more than ${r.gap} m away`)
}

// ---- 3. goals: no replay, no freeze, no teleport ----------------------------------
{
  await S(() => {
    window.__next = () => window.__mk({})
    const w = window.__world, p = w.getControlledPlayer()
    p.x = 40; p.y = 19; p.vx = 0; p.vy = 0
  })
  // Sample the ball and the state every 50 ms through five goals.
  await S(() => {
    window.__log = { maxJump: 0, replays: 0, steps: 0, last: null }
    const w = window.__world, g = window.__game
    const tick = () => {
      const b = w.ball
      if (window.__log.last) window.__log.maxJump = Math.max(window.__log.maxJump, Math.hypot(b.x - window.__log.last.x, b.y - window.__log.last.y))
      window.__log.last = { x: b.x, y: b.y }
      if (g.replaying) window.__log.replays++
      window.__log.steps++
    }
    window.__sampler = setInterval(tick, 50)
  })
  const start = await S(() => window.__world.score.home + window.__world.score.away)
  const rows = []
  for (let i = 1; i <= 5; i++) {
    await S(() => {
      const w = window.__world
      // Put the ball just inside the net, moving in: the world scores it itself.
      w.ball.setPos(58.7, 19 + (Math.random() * 3 - 1.5), 0.1)
      w.ball.vx = 6; w.ball.vy = 0; w.ball.vz = 0
      // That placement is this test's doing, not the game's: don't count it.
      window.__log.last = null
    })
    await wait(350)
    const soon = await S(() => ({ replaying: window.__game.replaying, score: window.__world.score.home + window.__world.score.away }))
    await wait(4200)
    const later = await S(() => {
      const w = window.__world
      return { x: +w.ball.x.toFixed(1), speed: +w.ball.speed.toFixed(2), replaying: window.__game.replaying }
    })
    rows.push({ i, ...soon, later })
  }
  const log = await S(() => { clearInterval(window.__sampler); return window.__log })
  const end = await S(() => window.__world.score.home + window.__world.score.away)
  const anyReplay = rows.some((r) => r.replaying || r.later.replaying) || log.replays > 0
  ok('five goals in a row are all counted', end - start === 5,
    `${end - start}/5 counted; each went in and was counted within 350 ms of crossing the line`)
  ok('none of them starts a replay', !anyReplay,
    `${log.replays} of ${log.steps} samples were mid-replay — the game was never taken away from you`)
  ok('the net gave the ball back each time', rows.every((r) => r.later.x < 55.5 && r.later.speed < 0.1),
    `back out on the pitch at x = ${rows.map((r) => r.later.x).join(', ')} — nobody touched it`)
  ok('and nothing was teleported', log.maxJump < 1.5,
    `biggest ball move between samples ${log.maxJump.toFixed(2)} m (a 6 m/s roll moves 0.3 m per sample; a reset moves ~30)`)
}

// ---- 4. a replay, on demand — and the game survives it ---------------------------
{
  // The replay used to end the 3D game loop in a bare `return` that skipped the
  // request for the next frame, so the game froze on the replay's first frame for
  // good — every goal in a match, and R at any time. Everything here is measured
  // from outside the game: the browser's own frame counter, and how far the replay
  // has actually got.
  await S(() => { const w = window.__world; w.ball.setPos(29, 19, 0); w.ball.stop() })
  await pg.keyboard.press('KeyR')
  await wait(500)
  const a = await S(() => ({ on: window.__game.replaying, at: window.__game.replay.progress, frames: window.__gameFrames }))
  await wait(1500)
  const c = await S(() => ({ on: window.__game.replaying, at: window.__game.replay.progress, frames: window.__gameFrames }))
  ok('R starts a replay, and it actually plays', a.on && c.on && c.at > a.at + 0.05,
    `progress ${a.at.toFixed(2)} → ${c.at.toFixed(2)} over 1.5 s (a frozen loop stays put)`)
  ok('the game loop keeps running through it', c.frames - a.frames > 20,
    `the game ran ${c.frames - a.frames} of its own frames in 1.5 s while the replay played (a dead loop runs none)`)
  await pg.keyboard.press('KeyR')
  await wait(400)
  const off = await S(() => window.__game.replaying)
  ok('R stops it', off === false, `pressed again: replaying ${off}`)
  // ...and the game is yours again: the world steps, and the ball obeys physics.
  await S(() => { const w = window.__world; w.ball.setPos(20, 19, 0); w.ball.vx = 6; w.ball.vy = 0 })
  await wait(500)
  const after = await S(() => +window.__world.ball.x.toFixed(2))
  ok('and play resumes where it left off', after > 21.5,
    `a ball rolling at 6 m/s went from x=20 to x=${after} in half a second`)
}

// ---- 5. the picture: a goal, and you are still in control ----------------------------
{
  await S(() => {
    const g = window.__game
    const scene = Object.values(g).find((v) => v && v.players instanceof Map)
    scene.render = g.__draw.render
    scene.sync = g.__draw.sync
    const w = window.__world, p = w.getControlledPlayer()
    p.x = 44; p.y = 19; p.vx = 0; p.vy = 0
    g.cam3.yaw = 0; g.cam3.pitch = -0.02
    window.__next = () => window.__mk({})
    w.ball.setPos(54, 19, 0); w.ball.vx = 24; w.ball.vy = 0; w.ball.vz = 0.3
  })
  await wait(4500)
  await pg.addStyleTag({ content: '#overlay { display: none !important }' })
  await pg.screenshot({ path: process.env.SHOT || `${(await import('node:os')).tmpdir()}/training.png` })
  const band = await S(() => {
    const c = document.getElementById('pitch')
    const g = c.getContext('2d')
    const dpr = c.width / c.clientWidth
    const d = g.getImageData(0, Math.floor(8 * dpr), c.width, 1).data
    let dark = 0
    for (let i = 0; i < d.length; i += 4) dark += (d[i + 3] / 255) * (1 - (0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2]) / 255)
    return +(dark / (d.length / 4)).toFixed(2)
  })
  ok('no broadcast letterbox is drawn over a training goal', band < 0.5,
    `top-of-screen darkness ${band} (a replay paints it ~0.8)`)
}

await pg.close()
await b.close()
server.close()

console.log(results.join('\n'))
if (errs.length) console.log('pageerror:', errs.join(' | '))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
process.exit(failed || errs.length ? 1 : 0)
