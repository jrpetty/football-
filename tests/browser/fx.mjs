// What you see happen: particles, camera, and the players' skinned bodies.
//
// None of it is simulation, so none of it can be tested on Node. What is
// checked here is that the presentation layer reacts to the things the world
// does — a goal, a hard strike, a sprint — that it lets go of them afterwards
// (a particle system that never empties is a leak with a pretty face), and that
// the players, now one skinned mesh per material on a shared skeleton, are
// still animating and still cheap.
//
// Run with:  npm run build:single && node tests/browser/fx.mjs
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
const ok = (name, pass, detail) => results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(56)} ${detail}`)

const pg = await b.newPage({ viewport: { width: 640, height: 400 } })
pg.on('pageerror', (e) => errs.push(e.message))
await pg.goto(url)
await pg.waitForTimeout(700)
await pg.click('[data-seg="view"][data-val="3d"]')
await pg.click('[data-act="training"]')
await pg.waitForTimeout(1800)

await pg.evaluate(() => {
  const g = window.__game
  Object.defineProperty(g.input, 'pointerLocked', { get: () => true })
  const scene = Object.values(g).find((v) => v && v.players instanceof Map)
  window.__scene = scene
  window.__bursts = []
  const burst = scene.burst.bind(scene)
  scene.burst = (w, e) => { window.__bursts.push(e.type); burst(w, e) }
  // The camera's shake dies away in well under a second, so what is asked of it
  // is recorded as it happens.
  // Particles live a fraction of a second and a software rasteriser draws a
  // frame every quarter of one, so a spot reading can land between a burst
  // and the next frame. The peak since it was last cleared can't.
  window.__peak = { matter: 0, light: 0 }
  const upd = scene.fx.update.bind(scene.fx)
  scene.fx.update = (dt) => {
    upd(dt)
    window.__peak.matter = Math.max(window.__peak.matter, scene.fx.matter.count)
    window.__peak.light = Math.max(window.__peak.light, scene.fx.light.count)
  }
  window.__punch = 0
  const punch = g.cam3.punch.bind(g.cam3)
  g.cam3.punch = (a) => { window.__punch += a; punch(a) }
  const base = { move: { x: 0, y: 0 }, aim: { x: 1, y: 0 }, sprint: false, walk: false, chargePass: false, chargeShot: false, kick: null, jump: false, shield: false, slide: false }
  window.__mk = (over) => ({ ...base, move: { ...base.move }, aim: { ...base.aim }, ...over })
  window.__next = () => window.__mk({})
  g.human.buildCommand = () => window.__next()
})
const wait = (ms) => pg.waitForTimeout(ms)
const S = (fn, arg) => pg.evaluate(fn, arg)

// ---- 1. a goal ------------------------------------------------------------------
{
  await S(() => {
    const w = window.__world, p = w.getControlledPlayer()
    p.x = 40; p.y = 19; p.vx = 0; p.vy = 0
    window.__bursts.length = 0
    window.__punch = 0
    w.ball.setPos(58.6, 19, 0.1); w.ball.vx = 8; w.ball.vy = 0; w.ball.vz = 0
  })
  await wait(700)
  const r = await S(() => ({
    bursts: window.__bursts.slice(),
    live: window.__scene.fx.live,
    shake: +window.__punch.toFixed(2),
  }))
  ok('a goal is met with confetti', r.bursts.includes('goal') && r.live > 60,
    `${r.live} particles alive 0.7 s after it went in (bursts: ${r.bursts.join(', ')})`)
  ok('and the camera feels it', r.shake >= 0.5, `the camera was shaken by ${r.shake} (0 is still)`)
  await wait(5200)
  const later = await S(() => ({ live: window.__scene.fx.live, shake: window.__game.cam3.trauma }))
  ok('and it all lets go afterwards', later.live === 0 && later.shake === 0,
    `5 s on: ${later.live} particles left, camera trauma ${later.shake}`)
}

// ---- 2. a hard strike ---------------------------------------------------------------
{
  await S(() => {
    const w = window.__world, p = w.getControlledPlayer()
    p.x = 20; p.y = 19; p.vx = 0; p.vy = 0; p.heading = 0
    w.ball.setPos(20.7, 19, 0); w.ball.stop()
    window.__bursts.length = 0
    window.__peak.matter = 0; window.__peak.light = 0
    let fired = false
    window.__next = () => {
      const c = window.__mk({})
      if (!fired) { fired = true; c.kick = { type: 'strike', power: 1, aim: { x: 1, y: 0 }, loft: 0.05, spin: 0 } }
      return c
    }
  })
  // Read it while the ball is still on its way — before it can reach a goal
  // or a wall, both of which have particles of their own.
  await pg.waitForFunction(() => window.__world.ball.x > 24, null, { polling: 10 })
  const r = await S(() => ({ ...window.__peak, bursts: window.__bursts.slice(), speed: +window.__world.ball.speed.toFixed(1), x: +window.__world.ball.x.toFixed(1) }))
  ok('a hard strike throws up turf', r.bursts.includes('kick') && r.matter >= 8 && r.matter < 60,
    `${r.matter} bits of turf in the air at once, ball at x=${r.x}, still on its way`)
  ok('and the ball leaves a streak', r.light >= 4,
    `${r.light} glints of light along its path (it left at over 30 m/s)`)
  await S(() => { window.__next = () => window.__mk({}) })
  await wait(4000)
  const soft = await S(() => {
    const w = window.__world, p = w.getControlledPlayer()
    p.x = 20; p.y = 19; p.vx = 0; p.vy = 0
    w.ball.setPos(20.7, 19, 0); w.ball.stop()
    window.__scene.fx.matter.update(20); window.__scene.fx.light.update(20) // clear the pools
    window.__peak.matter = 0; window.__peak.light = 0
    window.__bursts.length = 0
    let fired = false
    window.__next = () => {
      const c = window.__mk({})
      if (!fired) { fired = true; c.kick = { type: 'touch', power: 0.05, aim: { x: 1, y: 0 }, loft: 0, spin: 0 } }
      return c
    }
    return true
  })
  await wait(1500)
  const t = await S(() => ({ ...window.__peak }))
  ok('a tap does far less', t.matter < r.matter && t.light === 0,
    `${t.matter} bits of turf and ${t.light} glints, against ${r.matter} and ${r.light}`)
  void soft
}

// ---- 3. a sprint opens the view, and standing still gives it back ---------------------
{
  await S(() => { window.__next = () => window.__mk({}); const p = window.__world.getControlledPlayer(); p.x = 8; p.y = 19; p.vx = 0; p.vy = 0 })
  await wait(1200)
  const still = await S(() => +window.__game.cam3.cam.fov.toFixed(1))
  await S(() => { window.__next = () => window.__mk({ move: { x: 1, y: 0 }, sprint: true }) })
  await wait(1500)
  const run = await S(() => +window.__game.cam3.cam.fov.toFixed(1))
  await S(() => { window.__next = () => window.__mk({}) })
  await wait(2200)
  const after = await S(() => +window.__game.cam3.cam.fov.toFixed(1))
  ok('sprinting opens the field of view', run > still + 2, `${still}° standing, ${run}° at a sprint`)
  ok('and stopping closes it again', Math.abs(after - still) < 0.7, `${after}° a couple of seconds after stopping`)
}

// ---- 3b. the shot pre-view ---------------------------------------------------------------
{
  await S(() => {
    window.__next = () => window.__mk({})
    const w = window.__world, p = w.getControlledPlayer()
    p.x = 6; p.y = 19; p.vx = 0; p.vy = 0; p.heading = 0
    w.ball.setPos(6.7, 19, 0); w.ball.stop()
    // What the controller reports while a strike charges.
    window.__game.human.previewKick = { type: 'strike', power: 0.6, aim: { x: 1, y: 0 }, loft: 0.6, spin: 0.4 }
  })
  await wait(1500)
  const on = await S(() => {
    const sc = window.__scene
    const pts = sc.previewPts
    const a = pts.geometry.getAttribute('position')
    const n = pts.geometry.drawRange.count
    return {
      pointsVisible: pts.visible,
      ringVisible: sc.previewRing.visible,
      n,
      first: [+a.getX(0).toFixed(2), +a.getZ(0).toFixed(2)],
      ring: [+sc.previewRing.position.x.toFixed(1), +sc.previewRing.position.z.toFixed(1)],
    }
  })
  ok('while a strike charges in training, a line is drawn from the ball',
    on.pointsVisible && on.ringVisible && on.n > 20 && Math.abs(on.first[0] - 6.8) < 0.5,
    `${on.n} dots starting at x=${on.first[0]}, a ring where it comes down (x=${on.ring[0]})`)

  await S(() => { window.__game.human.previewKick = null })
  await wait(700)
  const off = await S(() => ({ p: window.__scene.previewPts.visible, r: window.__scene.previewRing.visible }))
  ok('and it goes when you let go', !off.p && !off.r, `dots visible: ${off.p}, ring visible: ${off.r}`)
}

// ---- 4. the players --------------------------------------------------------------------
{
  const r = await S(() => {
    const w = window.__world, scene = window.__scene
    const me = w.getControlledPlayer()
    const rig = scene.players.get(me.id)
    const before = scene.renderer.info
    const calls = () => { scene.render(window.__game.cam3.cam); return scene.renderer.info.render.calls }
    const one = calls()
    // Eleven more, so this is a match's worth of bodies.
    const P = me.constructor
    for (let i = 0; i < 11; i++) {
      w.players.push(new P(100 + i, i < 5 ? 'home' : 'away', i % 5 === 0 ? 'GK' : 'MID', i + 2, { x: 15 + i * 2, y: 10 + (i % 5) * 4 }, false))
    }
    scene.sync(w, me.id, true, -1, 1 / 60)
    const twelve = calls()
    void before
    return {
      meshes: rig.skinned.length,
      allSkinned: rig.skinned.every((m) => m.isSkinnedMesh && m.skeleton === rig.skinned[0].skeleton),
      bones: rig.bones.length,
      perPlayer: +((twelve - one) / 11).toFixed(1),
      twelve,
    }
  })
  ok('a body is a few skinned meshes on one skeleton', r.meshes >= 4 && r.meshes <= 8 && r.allSkinned && r.bones >= 13,
    `${r.meshes} skinned meshes sharing ${r.bones} bones`)
  ok('so a full match of bodies is cheap to draw', r.perPlayer <= 16 && r.twelve < 300,
    `${r.perPlayer} draw calls per extra player (shadow pass included); ${r.twelve} for twelve on the pitch`)

  // Running has to move the skin, not just the bones.
  await S(() => {
    const w = window.__world, me = w.getControlledPlayer()
    me.x = 8; me.y = 8; me.vx = 0; me.vy = 0
    window.__next = () => window.__mk({ move: { x: 1, y: 0 }, sprint: true })
  })
  await wait(900)
  const moved = await S(() => {
    const scene = window.__scene
    const rig = scene.players.get(window.__world.getControlledPlayer().id)
    scene.render(window.__game.cam3.cam)
    const m = rig.skinned[0].skeleton.boneMatrices
    // At rest every bone matrix is the identity (world × inverse-bind). The
    // further from it, the more the skin has been carried by the skeleton.
    let off = 0
    for (let i = 0; i < m.length; i += 16) {
      for (let k = 0; k < 16; k++) off = Math.max(off, Math.abs(m[i + k] - (k % 5 === 0 ? 1 : 0)))
    }
    return +off.toFixed(2)
  })
  ok('running moves the skin', moved > 0.15, `largest bone-matrix deviation from rest: ${moved}`)
}

await pg.close()
await b.close()
server.close()

console.log(results.join('\n'))
if (errs.length) console.log('pageerror:', errs.join(' | '))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
process.exit(failed || errs.length ? 1 : 0)
