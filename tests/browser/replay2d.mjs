// Both views draw the replay furniture.
//
// The replay logic was always shared, but only the 3D presentation drew the
// bands, the label and the progress bar — from above, a replay was
// indistinguishable from the match suddenly rewinding itself. Both now call
// one drawReplayOverlay(), and both hide the live HUD while it runs. This
// reads the actual pixels back out of the overlay canvas to prove it.
//
// Run with:  npm run build:single && node tests/browser/replay2d.mjs
import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')

const b = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'],
})

// The band is a near-black bar at 0.82 alpha right across the top. Alpha alone
// won't do as a measure: in 2D the canvas is the pitch and is opaque
// everywhere, while in 3D it is a transparent sheet over the WebGL one. So the
// metric is how much of the row is opaque *and* dark — high only where the
// band is, in either view.
const check = async (label, buttonText) => {
  const pg = await b.newPage({ viewport: { width: 800, height: 520 } })
  const errs = []
  pg.on('pageerror', (e) => errs.push(e.message))
  await pg.goto(`file://${process.cwd()}/open-pitch.html?debug`)
  await pg.waitForTimeout(900)
  await pg.click(`text=${buttonText}`)
  await pg.click('text=🎯  Training')
  await pg.waitForTimeout(1500)

  const sample = () =>
    pg.evaluate(() => {
      const c = document.getElementById('pitch')
      const g = c.getContext('2d')
      const dpr = c.width / c.clientWidth
      const dark = (y) => {
        const d = g.getImageData(0, Math.floor(y * dpr), c.width, 1).data
        let t = 0
        for (let i = 0; i < d.length; i += 4) {
          const lum = (0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2]) / 255
          t += (d[i + 3] / 255) * (1 - lum)
        }
        return +(t / (d.length / 4)).toFixed(2)
      }
      return { band: dark(8), foot: dark(c.clientHeight - 8), mid: dark(c.clientHeight / 2) }
    })

  const before = await sample()
  await pg.keyboard.press('KeyR')
  await pg.waitForTimeout(400)
  const during = await sample()
  const state = await pg.evaluate(() => {
    const g = window.__game
    const r = Object.values(g).find((v) => v && typeof v.progress === 'number' && 'playing' in v)
    return { playing: r?.playing, label: r?.label, progress: +(r?.progress ?? -1).toFixed(2) }
  })
  if (process.env.SHOT) await pg.screenshot({ path: `${process.env.SHOT}-${label}.png` })
  await pg.close()

  const ok =
    state.playing &&
    state.label === 'REPLAY' &&
    during.band > 0.75 &&
    during.foot > 0.75 &&
    before.band < 0.7 &&
    // ...and the bands are bands: the middle of the screen is the game, not a
    // tint over it.
    Math.abs(during.mid - before.mid) < 0.15
  console.log(
    `${ok && !errs.length ? 'PASS' : 'FAIL'}  ${label.padEnd(3)} top band ` +
      `${before.band} live → ${during.band} in replay · foot ${during.foot} · ` +
      `middle untouched at ${during.mid} · ${state.label} ${state.progress}`,
  )
  if (errs.length) console.log('      pageerror:', errs.join(' | '))
  return ok && !errs.length
}

const a = await check('2D', '🗺️ Classic 2D')
const c = await check('3D', '🎮 Immersive 3D')
await b.close()
process.exit(a && c ? 0 : 1)
