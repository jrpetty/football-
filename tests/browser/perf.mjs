// Where the frame time actually goes.
//
// This runs under swiftshader, so the raw frame rate here says nothing about a
// real machine — software rasterisation is dominated by fill rate, which a GPU
// does for free. What it *can* measure honestly is the CPU-side work, which is
// the same on every machine: the simulation, the scene sync, the HUD. Those are
// reported in milliseconds per frame.
//
// For the GPU side it reports the things that actually drive cost — draw calls,
// triangles, and how much shadow map is being redrawn — rather than a frame
// rate that means nothing here.
//
// Run with:  npm run build:single && node tests/browser/perf.mjs
import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')
const { createServer } = await import('node:http')
const { readFileSync } = await import('node:fs')

const html = readFileSync(`${process.cwd()}/open-pitch.html`)
const server = createServer((_, res) => {
  res.writeHead(200, { 'content-type': 'text/html' })
  res.end(html)
})
await new Promise((r) => server.listen(0, r))
const url = `http://127.0.0.1:${server.address().port}/?debug`

const b = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'],
})

// Wrap the hot methods and accumulate time in each, then read it back. Done in
// the page so it measures the shipped build rather than a rebuilt copy.
const INSTRUMENT = `(() => {
  const g = window.__game, w = window.__world
  const scene = Object.values(g).find((v) => v && v.players instanceof Map)
  const stats = { frames: 0, sim: 0, sync: 0, submit: 0, hud: 0, total: 0 }
  const wrap = (obj, name, key) => {
    if (!obj || typeof obj[name] !== 'function') return
    const fn = obj[name].bind(obj)
    obj[name] = (...a) => { const t = performance.now(); const r = fn(...a); stats[key] += performance.now() - t; return r }
  }
  wrap(w, 'update', 'sim')
  if (scene) { wrap(scene, 'sync', 'sync'); wrap(scene, 'render', 'submit') }
  const hud = Object.values(g).find((v) => v && typeof v.draw === 'function' && v !== scene)
  wrap(hud, 'draw', 'hud')
  // The 2D renderer, when that is the view.
  const r2 = Object.values(g).find((v) => v && typeof v.draw === 'function' && v !== hud && v !== scene)
  wrap(r2, 'draw', 'submit')
  let last = performance.now()
  const tick = () => {
    const now = performance.now()
    stats.total += now - last
    last = now
    stats.frames++
    requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
  window.__perf = { stats, scene, renderer: scene && scene.renderer }
})()`

const READ = `(() => {
  const p = window.__perf
  const s = p.stats
  const n = Math.max(1, s.frames)
  const info = p.renderer ? p.renderer.info : null
  return {
    frames: s.frames,
    fps: +(1000 / (s.total / n)).toFixed(1),
    ms: {
      frame: +(s.total / n).toFixed(2),
      sim: +(s.sim / n).toFixed(2),
      sync: +(s.sync / n).toFixed(2),
      submit: +(s.submit / n).toFixed(2),
      hud: +(s.hud / n).toFixed(2),
    },
    gpu: info
      ? { calls: info.render.calls, triangles: info.render.triangles, programs: info.programs ? info.programs.length : 0, textures: info.memory.textures, geometries: info.memory.geometries }
      : null,
  }
})()`

const run = async (label, viewText, seconds = 6) => {
  const pg = await b.newPage({ viewport: { width: 1280, height: 720 } })
  const errs = []
  pg.on('pageerror', (e) => errs.push(e.message))
  await pg.goto(url)
  await pg.waitForTimeout(700)
  await pg.click(`text=${viewText}`)
  await pg.click('text=🎯  Training')
  await pg.waitForTimeout(2500) // let it settle before measuring
  await pg.evaluate(INSTRUMENT)
  await pg.waitForTimeout(seconds * 1000)
  const out = await pg.evaluate(READ)
  await pg.close()
  return { label, ...out, errs }
}

const rows = []
rows.push(await run('3D training', '🎮 Immersive 3D'))
rows.push(await run('2D training', '🗺️ Classic 2D'))
await b.close()
server.close()

for (const r of rows) {
  console.log(`\n── ${r.label} ── ${r.frames} frames, ${r.fps} FPS (swiftshader — not a real GPU)`)
  console.log(`   CPU per frame:  ${r.ms.frame} ms total`)
  console.log(`     simulation    ${r.ms.sim} ms`)
  console.log(`     scene sync    ${r.ms.sync} ms`)
  console.log(`     draw submit   ${r.ms.submit} ms`)
  console.log(`     hud           ${r.ms.hud} ms`)
  const other = +(r.ms.frame - r.ms.sim - r.ms.sync - r.ms.submit - r.ms.hud).toFixed(2)
  console.log(`     everything else / GPU wait  ${other} ms`)
  if (r.gpu) {
    console.log(`   GPU work:  ${r.gpu.calls} draw calls · ${r.gpu.triangles.toLocaleString()} triangles`)
    console.log(`              ${r.gpu.programs} shader programs · ${r.gpu.textures} textures · ${r.gpu.geometries} geometries`)
  }
  if (r.errs.length) console.log('   pageerror:', r.errs.join(' | '))
}
