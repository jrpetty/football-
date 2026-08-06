// The graphics setting has to actually change what the renderer does.
//
// A quality control that quietly does nothing is worse than none at all — it
// hands people a knob that cannot help them and a reason to stop looking. So
// this reads the live renderer state back out for each setting, and counts the
// pixels each one would shade.
//
// Run with:  npm run build:single && node tests/browser/quality.mjs
import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')
const { createServer } = await import('node:http')
const { readFileSync } = await import('node:fs')
const html = readFileSync(`${process.cwd()}/open-pitch.html`)
const server = createServer((_, res) => { res.writeHead(200, {'content-type':'text/html'}); res.end(html) })
await new Promise((r) => server.listen(0, r))
const url = `http://127.0.0.1:${server.address().port}/?debug`
const b = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'],
})

const results = []
const ok = (name, pass, detail) => results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(44)} ${detail}`)
const errs = []

const read = async (label) => {
  // deviceScaleFactor 2 on purpose: with the default of 1 there is nothing for
  // the pixel-ratio cap to bite on, and the setting would look inert when it
  // is the single biggest lever it has.
  const pg = await b.newPage({ viewport: { width: 900, height: 560 }, deviceScaleFactor: 2 })
  pg.on('pageerror', (e) => errs.push(e.message))
  await pg.goto(url)
  await pg.waitForTimeout(600)
  await pg.click(`[data-seg="quality"][data-val="${label}"]`)
  await pg.click('text=🎮 Immersive 3D')
  await pg.click('text=🎯  Training')
  await pg.waitForTimeout(2200)
  const out = await pg.evaluate(() => {
    const w = window.__world
    const s = Object.values(window.__game).find((v) => v && v.players instanceof Map)
    const r = s.renderer
    const c = r.domElement
    const light = s.scene.children.find((o) => o.isDirectionalLight)
    return {
      pixelRatio: r.getPixelRatio(),
      pixels: c.width * c.height,
      shadows: r.shadowMap.enabled,
      shadowMap: light && light.castShadow ? light.shadow.mapSize.width : 0,
      antialias: r.getContext().getContextAttributes().antialias,
      calls: r.info.render.calls,
      // The stand-in shadow under the ball, which only exists when there is no
      // shadow map to cast a real one. Put the ball in the air and see whether
      // anything on the floor tracks it.
      blob: (() => {
        w.ball.setPos(34, 12, 3)
        w.ball.stop()
        s.sync(w, -1, false, -1, 0.016)
        const disc = s.scene.children.find((o) => o.isMesh && o.geometry?.type === 'CircleGeometry')
        if (!disc) return null
        return { x: +disc.position.x.toFixed(2), y: +disc.position.z.toFixed(2), onFloor: disc.position.y < 0.1 }
      })(),
    }
  })
  await pg.close()
  return out
}

const low = await read('low')
const med = await read('medium')
const high = await read('high')
await b.close()
server.close()

console.log(`          pixel ratio   pixels shaded   shadows   shadow map   MSAA`)
for (const [n, q] of [['low', low], ['medium', med], ['high', high]]) {
  console.log(
    `${n.padEnd(9)} ${String(q.pixelRatio).padStart(6)}   ${q.pixels.toLocaleString().padStart(13)}   ${String(q.shadows).padStart(7)}   ${String(q.shadowMap).padStart(10)}   ${q.antialias}`,
  )
}
console.log('')

ok('low shades fewer pixels than high', low.pixels < high.pixels,
   `${low.pixels.toLocaleString()} vs ${high.pixels.toLocaleString()} — ${(high.pixels / low.pixels).toFixed(1)}× the work`)
ok('low turns shadows off entirely', low.shadows === false && med.shadows && high.shadows,
   `low off, medium and high on`)
ok('and antialiasing with them', low.antialias === false && high.antialias === true,
   `MSAA: low ${low.antialias}, high ${high.antialias}`)
ok('the shadow map grows with quality', med.shadowMap < high.shadowMap,
   `medium ${med.shadowMap}², high ${high.shadowMap}²`)
ok('the setting is remembered, not reset', med.pixelRatio <= 1.25,
   `medium capped the pixel ratio at ${med.pixelRatio}`)
// Quality is a fill-rate setting, not a level-of-detail one: the same world is
// drawn either way. Low is one call heavier, and only because it adds the
// painted shadow under the ball that the shadow map would otherwise cast.
ok('quality changes fill, not geometry', Math.abs(low.calls - high.calls) <= 1,
   `${high.calls} calls at high, ${low.calls} at low — the one extra is the ball's painted shadow`)

// Shadows off is the cheapest frame there is, but the ball's shadow is the only
// thing that says where a ball in flight will land — in this game that is not
// decoration. Low keeps that one and pays a single draw call for it.
ok('low keeps a shadow under the ball',
   !!low.blob && low.blob.x === 34 && low.blob.y === 12 && low.blob.onFloor,
   low.blob ? `a disc on the floor at ${low.blob.x},${low.blob.y}, under a ball 3 m up` : 'no disc found')
ok('and high does not need one', high.blob === null,
   `nothing painted — the shadow map casts the real thing`)

console.log(results.join('\n'))
if (errs.length) console.log('pageerror:', errs.join(' | '))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
process.exit(failed || errs.length ? 1 : 0)
