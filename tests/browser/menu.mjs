// The front of house, and the settings that reach into the game.
//
// Three things a screenshot cannot settle and a person would notice at once:
// that you can start playing without scrolling, that the live scene behind the
// title screen is really there and really goes away when a match starts, and
// that a setting moved in the drawer changes what the game does — including from
// the pause screen, mid-match, where "close the settings and it takes" is the
// only thing anyone expects.
//
// Run with:  npm run build:single && node tests/browser/menu.mjs
import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')
const { createServer } = await import('node:http')
const { readFileSync } = await import('node:fs')

const html = readFileSync(`${process.cwd()}/open-pitch.html`)
const server = createServer((_, res) => { res.writeHead(200, { 'content-type': 'text/html' }); res.end(html) })
await new Promise((r) => server.listen(0, r))
const base = `http://127.0.0.1:${server.address().port}/?debug`

const b = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'],
})
const results = []
const errs = []
const ok = (name, pass, detail) => results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(56)} ${detail}`)

// ---- 1. the title screen, with its stage --------------------------------------------
{
  const pg = await b.newPage({ viewport: { width: 1280, height: 720 } })
  pg.on('pageerror', (e) => errs.push(e.message))
  await pg.goto(base + '&backdrop')
  await pg.waitForTimeout(1500)

  const fold = await pg.evaluate(() => {
    const t = document.querySelector('.title')
    const box = (sel) => { const r = document.querySelector(sel).getBoundingClientRect(); return Math.round(r.bottom) }
    return {
      inner: innerHeight,
      scroll: t.scrollHeight,
      training: box('[data-act="training"]'),
      online: box('[data-act="online"]'),
      canvases: document.querySelectorAll('#scene3d canvas').length,
      font: getComputedStyle(document.querySelector('.headline')).fontFamily.split(',')[0],
      fontLoaded: document.fonts.check('900 40px "Big Shoulders Display"'),
    }
  })
  ok('you can start playing without scrolling',
    fold.training < fold.inner && fold.online < fold.inner && fold.scroll <= fold.inner,
    `at 1280×720 both buttons end by y=${Math.max(fold.training, fold.online)}, the page is ${fold.scroll}px tall in a ${fold.inner}px window`)
  ok('it is set in the embedded typeface', fold.fontLoaded && fold.font.includes('Big Shoulders'),
    `headline font "${fold.font}", loaded: ${fold.fontLoaded}`)
  ok('a live scene is running behind it', fold.canvases === 1, `${fold.canvases} WebGL canvas in the stage`)

  await pg.click('[data-act="training"]')
  await pg.waitForTimeout(2200)
  const inGame = await pg.evaluate(() => ({
    canvases: document.querySelectorAll('#scene3d canvas').length,
    running: !!window.__game && !!window.__world,
  }))
  ok('starting a match replaces the stage with the match', inGame.canvases === 1 && inGame.running,
    `${inGame.canvases} canvas in the stage while playing — the title's was let go, not left running underneath`)

  // Back out: the stage returns.
  await pg.keyboard.press('KeyP')
  await pg.waitForTimeout(500)
  await pg.click('[data-act="menu"]')
  await pg.waitForTimeout(1800)
  const back = await pg.evaluate(() => ({
    canvases: document.querySelectorAll('#scene3d canvas').length,
    title: !!document.querySelector('.title'),
  }))
  ok('and coming back to the title brings the stage back', back.canvases === 1 && back.title,
    `${back.canvases} canvas, title showing: ${back.title}`)
  await pg.close()
}

// ---- 2. an automated browser is not made to draw the stage ------------------------------
{
  const pg = await b.newPage({ viewport: { width: 900, height: 600 } })
  pg.on('pageerror', (e) => errs.push(e.message))
  await pg.goto(base)
  await pg.waitForTimeout(900)
  const n = await pg.evaluate(() => ({ canvases: document.querySelectorAll('#scene3d canvas').length, bare: !!document.querySelector('.title.bare') }))
  ok('without WebGL to spare the title paints its own floor', n.canvases === 0 && n.bare,
    `no stage canvas (${n.canvases}), and the title is in its bare form: ${n.bare}`)
  await pg.close()
}

// ---- 3. settings reach the game -----------------------------------------------------------
{
  const pg = await b.newPage({ viewport: { width: 1000, height: 640 } })
  pg.on('pageerror', (e) => errs.push(e.message))
  await pg.goto(base)
  await pg.waitForTimeout(900)
  const setRange = async (name, v) => {
    await pg.fill(`input[data-set="${name}"]`, String(v))
    await pg.dispatchEvent(`input[data-set="${name}"]`, 'input')
  }

  await pg.click('[data-open="display"]')
  await pg.waitForTimeout(200)
  await setRange('fov', 84)
  await pg.click('[data-seg="camDist"][data-val="far"]')
  await pg.click('[data-tab="controls"]')
  await setRange('lookSens', 2)
  await pg.check('input[data-set="invertY"]')
  await pg.click('.drawer .foot [data-close]')
  await pg.evaluate(() => document.querySelector('[data-act="training"]').click())
  await pg.waitForTimeout(2000)

  const cam = await pg.evaluate(() => {
    const c = window.__game.cam3
    c.yaw = 0; c.pitch = 0
    c.look(100, 0)
    const yaw = c.yaw
    c.pitch = 0
    c.look(0, 100)
    const pitch = c.pitch
    return { fov: c.baseFov, distance: c.distance, yaw: +yaw.toFixed(3), pitch: +pitch.toFixed(3) }
  })
  ok('field of view and camera distance are what you chose', cam.fov === 84 && cam.distance > 9,
    `FOV ${cam.fov}°, camera ${cam.distance} m back (standard is 7.5)`)
  ok('mouse look is scaled, and can be inverted', Math.abs(cam.yaw - 100 * 0.0022 * 2) < 0.005 && cam.pitch > 0,
    `100 counts of mouse moved the view ${cam.yaw} rad (2× of ${(100 * 0.0022).toFixed(2)}); pushing forward looked ${cam.pitch > 0 ? 'up' : 'down'}`)

  // ...and mid-match, from the pause screen, they take on the next frame.
  await pg.keyboard.press('KeyP')
  await pg.waitForTimeout(500)
  await pg.click('[data-act="settings"]')
  await pg.waitForTimeout(300)
  await pg.click('[data-tab="display"]')
  await setRange('fov', 96)
  await pg.click('[data-seg="camDist"][data-val="close"]')
  await pg.waitForTimeout(200)
  const live = await pg.evaluate(() => ({ fov: window.__game.cam3.baseFov, distance: window.__game.cam3.distance }))
  ok('and they take effect straight away from the pause screen', live.fov === 96 && live.distance < 7,
    `moved while paused: FOV ${live.fov}°, camera ${live.distance} m back`)

  // Volume is a real setting: the audio graph's master follows it.
  await pg.click('[data-tab="audio"]')
  await setRange('volume', 0.4)
  const vol = await pg.evaluate(() => ({ v: window.__sfx.volume, saved: JSON.parse(localStorage.getItem('open-pitch') || '{}').volume }))
  await pg.waitForTimeout(400)
  const saved = await pg.evaluate(() => JSON.parse(localStorage.getItem('open-pitch') || '{}'))
  ok('volume, field of view and the rest are saved', vol.v === 0.4 && saved.volume === 0.4 && saved.fov === 96 && saved.camDist === 'close' && saved.invertY === true && saved.lookSens === 2,
    `on disk: volume ${saved.volume}, fov ${saved.fov}, camera ${saved.camDist}, invert ${saved.invertY}, look ${saved.lookSens}`)

  // Escape closes the drawer and nothing else.
  await pg.keyboard.press('Escape')
  await pg.waitForTimeout(200)
  const esc = await pg.evaluate(() => ({ drawer: !!document.querySelector('.drawer'), pause: !!document.querySelector('[data-act="resume"]') }))
  ok('Escape closes the drawer and leaves the pause screen alone', !esc.drawer && esc.pause, `drawer open: ${esc.drawer}, pause showing: ${esc.pause}`)
  await pg.close()
}

// ---- 4. the tutorial ----------------------------------------------------------------------------
{
  const pg = await b.newPage({ viewport: { width: 1000, height: 640 } })
  pg.on('pageerror', (e) => errs.push(e.message))
  await pg.goto(base)
  await pg.waitForTimeout(900)
  const offered = await pg.evaluate(() => !!document.querySelector('.newhere [data-act="tutorial"]'))
  await pg.click('.newhere [data-act="tutorial"]')
  await pg.waitForTimeout(2000)
  await pg.evaluate(() => {
    const g = window.__game
    Object.defineProperty(g.input, 'pointerLocked', { get: () => true })
    const base = { move: { x: 0, y: 0 }, aim: { x: 1, y: 0 }, sprint: false, walk: false, chargePass: false, chargeShot: false, kick: null, jump: false, shield: false, slide: false }
    window.__mk = (o) => ({ ...base, move: { ...base.move }, aim: { ...base.aim }, ...o })
    window.__next = () => window.__mk({})
    g.human.buildCommand = () => window.__next()
    const scene = Object.values(g).find((v) => v && v.players instanceof Map)
    scene.render = () => {} // the lesson is logic; don't spend the run drawing
    scene.sync = () => {}
  })
  const start = await pg.evaluate(() => ({ has: !!window.__game.tutorial, step: window.__game.tutorial?.step.id, mode: window.__world.config.mode, view: window.__world.config.view }))
  ok('the title offers it to someone new, and it starts a lesson in training',
    offered && start.has && start.step === 'look' && start.mode === 'training' && start.view === '3d',
    `offered on the title: ${offered}; lesson on "${start.step}" in ${start.mode}, ${start.view}`)

  // Look around, in the real loop: the aim vector sweeps.
  await pg.evaluate(() => {
    let i = 0
    window.__next = () => { i++; const a = i * 0.03; return window.__mk({ aim: { x: Math.cos(a), y: Math.sin(a) } }) }
  })
  await pg.waitForTimeout(3500)
  const after = await pg.evaluate(() => window.__game.tutorial.step.id)
  ok('doing what it asks moves it on, in the real game loop', after !== 'look', `after looking around: "${after}"`)

  // Enter skips a step.
  const before = await pg.evaluate(() => window.__game.tutorial.index)
  await pg.keyboard.press('Enter')
  await pg.waitForTimeout(600)
  const skipped = await pg.evaluate(() => window.__game.tutorial.index)
  ok('Enter skips a step', skipped === before + 1, `step ${before + 1} → step ${skipped + 1}`)

  // Skipping the lot finishes it, and it never offers itself again.
  await pg.evaluate(() => { const t = window.__game.tutorial; for (let i = 0; i < 12; i++) t.skip(window.__world) })
  await pg.waitForTimeout(600)
  const done = await pg.evaluate(() => ({ finished: window.__game.tutorial.finished, seen: JSON.parse(localStorage.getItem('open-pitch') || '{}') }))
  await pg.waitForTimeout(400)
  const seen = await pg.evaluate(() => JSON.parse(localStorage.getItem('open-pitch') || '{}').tutorialSeen)
  ok('finishing it is remembered, so the title stops asking', done.finished && seen === true,
    `finished: ${done.finished}; saved as seen: ${seen}`)
  await pg.close()
}

await b.close()
server.close()

console.log(results.join('\n'))
if (errs.length) console.log('pageerror:', errs.join(' | '))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
process.exit(failed || errs.length ? 1 : 0)
