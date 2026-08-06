// Rebinding, persistence and name tags, in a real browser.
//
// None of these can be proved on Node: rebinding is a DOM interaction, storage
// is a browser API, and a name tag is a thing you either see above a player or
// do not. So this drives the actual page — clicks the key, presses a new one,
// reloads, and reads the tag sprites back out of the running scene.
//
// Run with:  npm run build:single && node tests/browser/settings.mjs
import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')
const b = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'],
})
const errs = []
const results = []
const ok = (name, pass, detail) => results.push(`${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(44)} ${detail}`)

// file:// blocks localStorage in Chromium, so serve the build over http.
const { createServer } = await import('node:http')
const { readFileSync } = await import('node:fs')
const html = readFileSync(`${process.cwd()}/open-pitch.html`)
const server = createServer((_, res) => {
  res.writeHead(200, { 'content-type': 'text/html' })
  res.end(html)
})
await new Promise((r) => server.listen(0, r))
const url = `http://127.0.0.1:${server.address().port}/?debug`

const pg = await b.newPage({ viewport: { width: 1000, height: 640 } })
pg.on('pageerror', (e) => errs.push(e.message))
await pg.goto(url)
await pg.waitForTimeout(600)

// ---- 1. the controls list is really bound to the bindings -------------------
{
  const shown = await pg.textContent('[data-bind="shield"]')
  ok('the key list shows what is bound', shown.trim() === 'Q', `shield reads "${shown.trim()}"`)
}

// ---- 2. rebinding ------------------------------------------------------------
{
  await pg.click('[data-bind="shield"]')
  const listening = await pg.getAttribute('[data-bind="shield"]', 'class')
  await pg.keyboard.press('KeyE')
  await pg.waitForTimeout(120)
  const shown = await pg.textContent('[data-bind="shield"]')
  ok(
    'clicking a key and pressing another rebinds it',
    listening.includes('listening') && shown.trim() === 'E',
    `waited for a key, then took E`,
  )
}

// ---- 3. a clash clears the other row ----------------------------------------
{
  // Slide is C; give C to the shield and the slide row must go blank.
  await pg.click('[data-bind="shield"]')
  await pg.keyboard.press('KeyC')
  await pg.waitForTimeout(120)
  const shield = (await pg.textContent('[data-bind="shield"]')).trim()
  const slide = (await pg.textContent('[data-bind="slide"]')).trim()
  ok(
    'and takes the key off whatever had it',
    shield === 'C' && slide === '—',
    `shield "${shield}", slide "${slide}" — the list updated both rows`,
  )
  await pg.click('[data-act="resetkeys"]')
  await pg.waitForTimeout(120)
}

// ---- 4. Escape is refused ----------------------------------------------------
{
  await pg.click('[data-bind="shield"]')
  await pg.keyboard.press('Escape')
  await pg.waitForTimeout(150)
  const shown = (await pg.textContent('[data-bind="shield"]')).trim()
  ok('Escape is refused', shown === 'Q', `shield stayed on ${shown}`)
}

// ---- 5. it survives a reload -------------------------------------------------
{
  await pg.click('[data-bind="jump"]')
  await pg.keyboard.press('KeyJ')
  await pg.waitForTimeout(120)
  // A slider too, to prove settings and not just keys are kept.
  await pg.fill('input[data-sens="height"]', '3.4')
  await pg.dispatchEvent('input[data-sens="height"]', 'input')
  await pg.fill('[data-f="name"]', 'JAMIE')
  await pg.dispatchEvent('[data-f="name"]', 'input')
  await pg.waitForTimeout(400)
  await pg.reload()
  await pg.waitForTimeout(600)
  const jump = (await pg.textContent('[data-bind="jump"]')).trim()
  const sens = await pg.inputValue('input[data-sens="height"]')
  const name = await pg.inputValue('[data-f="name"]')
  ok(
    'keys, sliders and your name survive a reload',
    jump === 'J' && Number(sens) === 3.4 && name === 'JAMIE',
    `jump ${jump}, height sensitivity ${sens}, name "${name}"`,
  )
  await pg.click('[data-act="resetkeys"]')
  await pg.waitForTimeout(200)
}

// ---- 6. the prose follows the binding ----------------------------------------
{
  await pg.click('[data-bind="shield"]')
  await pg.keyboard.press('KeyB')
  await pg.waitForTimeout(150)
  await pg.reload()
  await pg.waitForTimeout(600)
  const foot = await pg.textContent('.controls')
  ok(
    'the written instructions follow the binding',
    foot.includes('Hold B to shield'),
    `the shield paragraph now says "Hold B to shield"`,
  )
  await pg.click('[data-act="resetkeys"]')
  await pg.waitForTimeout(200)
}

// ---- 7. name tags ------------------------------------------------------------
{
  await pg.click('text=🎮 Immersive 3D')
  await pg.click('text=🎯  Training')
  await pg.waitForTimeout(2200)
  const tags = await pg.evaluate(() => {
    const game = window.__game
    const scene = Object.values(game).find((v) => v && v.players instanceof Map && v.players.size)
    const w = window.__world
    const sprites = scene.scene.children.filter((c) => c.isSprite)
    // A tag should sit above the head of the player it belongs to. Every
    // player is checked, not just one: a single tag in the right place could
    // be a coincidence, all of them cannot.
    const overHead = w.players.filter((p) =>
      sprites.some(
        (s) => Math.hypot(s.position.x - p.x, s.position.z - p.y) < 0.1 && s.position.y > 2,
      ),
    ).length
    const free = w.players.find((p) => !w.isClaimed(p)) ?? null
    return {
      sprites: sprites.length,
      players: w.players.length,
      overHead,
      freeLabel: free ? w.nameFor(free) : null,
      you: w.nameFor(w.player(w.controlledId)),
      youClaimed: w.isClaimed(w.player(w.controlledId)),
    }
  })
  ok(
    'every player wears a name tag',
    tags.sprites === tags.players && tags.overHead === tags.players,
    `${tags.sprites} tags for ${tags.players} players, all of them over a head`,
  )
  // Your own shirt reads as you; anything nobody is in reads as what it is.
  // The name was set to JAMIE earlier in this same session.
  ok(
    'and it says who that is',
    tags.youClaimed && tags.you === 'JAMIE' &&
      (tags.freeLabel === null || /^(GK|DEF|MID|FWD) \d+$/.test(tags.freeLabel)),
    `you are "${tags.you}"${tags.freeLabel ? `, an empty shirt is "${tags.freeLabel}"` : ' (training: one player)'}`,
  )
}

// ---- 8. the new contact sounds exist and are wired ---------------------------
{
  const sound = await pg.evaluate(() => {
    const s = window.__sfx
    return ['header', 'volley', 'cushion', 'jump', 'land'].map((k) => typeof s?.[k])
  })
  ok(
    'the five missing contact sounds are there',
    sound.every((t) => t === 'function'),
    `header, volley, cushion, jump, land — all ${sound.filter((t) => t === 'function').length}/5 present`,
  )
}

await pg.screenshot({ path: process.env.SHOT || 'settings.png' })
await pg.close()
await b.close()
server.close()

console.log(results.join('\n'))
if (errs.length) console.log('pageerror:', errs.join(' | '))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n${results.length - failed}/${results.length} passed`)
process.exit(failed || errs.length ? 1 : 0)
