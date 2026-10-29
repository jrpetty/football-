// End-to-end checks on the real app (Electron), run in CI on Linux (under
// xvfb) and on Windows. They drive the built app with Playwright, with an
// empty data folder of their own, and check what a player would see:
//
//   1. the app starts and the Coach screen shows;
//   2. the talk key: held down (with the key repeats a real keyboard sends)
//      it starts once and stops once; tapped twice it starts, then stops;
//   3. the Age of Empires IV demo against the AI with commentary on: lines
//      appear in "Coach said";
//   4. the same demo set to ranked: commentary waits for the end of the match,
//      and "Why didn't it say that?" lists the lines held back with the reason.
//
// No microphone or speech recognition is needed: the key test checks the key
// events reaching the app (the fake microphone Chromium provides is used).
//
//   npm run build && node e2e/app.e2e.mjs [output folder]
// Linux needs xdotool and an X display (xvfb-run -a node e2e/app.e2e.mjs).

import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { _electron } from 'playwright-core'
import { holdKey, tapKey } from './keys.mjs'

const require = createRequire(import.meta.url)
const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const out = process.argv[2] ?? join(root, 'e2e-output')
mkdirSync(out, { recursive: true })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const HOME = 'text=Your coach, watching the game with you'
const results = []
let failed = 0

async function check(name, fn) {
  const t0 = Date.now()
  try {
    const detail = await fn()
    results.push({ name, ok: true, sec: (Date.now() - t0) / 1000, detail })
    console.log(`ok - ${name}`)
  } catch (err) {
    failed++
    results.push({ name, ok: false, sec: (Date.now() - t0) / 1000, error: String(err?.stack ?? err) })
    console.log(`not ok - ${name}\n  ${String(err?.message ?? err).split('\n').join('\n  ')}`)
  }
}

function assert(cond, message) {
  if (!cond) throw new Error(message)
}

const userData = mkdtempSync(join(tmpdir(), 'sightline-e2e-'))
const app = await _electron.launch({
  executablePath: require('electron'),
  args: ['.', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', ...(process.platform === 'linux' ? ['--no-sandbox'] : [])],
  cwd: root,
  env: { ...process.env, SIGHTLINE_USER_DATA: userData },
})
const win = await app.firstWindow()
const logs = []
win.on('console', (m) => logs.push(`${m.type()}: ${m.text()}`))
win.on('pageerror', (e) => logs.push(`pageerror: ${e.message}`))

/** Saves settings over the current ones and reloads the window. */
async function settings(patch) {
  const cur = await win.evaluate(() => window.sightline.loadSettings())
  await win.evaluate((s) => window.sightline.saveSettings(s), { ...cur, onboarded: true, ...patch })
  await win.reload()
  await win.waitForSelector(HOME, { timeout: 30_000 })
}

async function startDemo() {
  await win.click('text=Age of Empires IV replay')
  await win.waitForSelector('button.stop', { timeout: 30_000 })
}

async function stopDemo() {
  await win.click('button.stop')
  await win.waitForSelector(HOME, { timeout: 30_000 })
}

await check('the app starts and shows the Coach screen', async () => {
  await win.waitForSelector('body', { timeout: 30_000 })
  // A first start shows the welcome steps; the tests skip them.
  await win.waitForFunction(() => !!window.sightline, null, { timeout: 30_000 })
  await settings({ profileId: 'aoe4', profileModeId: 'skirmish-vs-ai', muted: true })
  const info = await win.evaluate(() => window.sightline.info())
  return { platform: info.platform ?? process.platform, version: info.version ?? null }
})

await check('talk key: held with key repeats, it starts once and stops once; tapped twice, it starts then stops', async () => {
  await settings({ voiceInput: true, listen: 'key', hotkeys: { talk: 'F7', mark: 'F6', stop: 'CommandOrControl+Shift+F12' } })
  const info = await win.evaluate(() => window.sightline.info())
  assert(!info.shortcutErrors?.length, `shortcut problems: ${JSON.stringify(info.shortcutErrors)}`)
  await win.evaluate(() => {
    window.__ptt = []
    window.sightline.onPushToTalk((a) => window.__ptt.push(a))
  })
  await win.bringToFront()
  holdKey('F7', 3000)
  await sleep(2500)
  const held = await win.evaluate(() => window.__ptt.splice(0))
  assert(JSON.stringify(held) === JSON.stringify(['start', 'stop']), `held: expected start, stop; got ${JSON.stringify(held)}`)
  await sleep(1000)
  tapKey('F7')
  await sleep(2500)
  tapKey('F7')
  await sleep(2500)
  const tapped = await win.evaluate(() => window.__ptt.splice(0))
  assert(JSON.stringify(tapped) === JSON.stringify(['start', 'stop']), `tapped: expected start, stop; got ${JSON.stringify(tapped)}`)
  return { held, tapped }
})

await check('commentary against the AI: lines appear in "Coach said"', async () => {
  await settings({ voiceInput: false, commentary: true, mode: 'competitive-safe', profileId: 'aoe4', profileModeId: 'skirmish-vs-ai' })
  const chip = (await win.textContent('.btn.toggle.commentary')).trim()
  assert(!/after the match/.test(chip), `commentary chip says "${chip}"`)
  await startDemo()
  // A fight, an age-up or a villager checkpoint called out, beyond the scouting lines at the start.
  await win.waitForFunction(() => [...document.querySelectorAll('.captions:not(.not-said) li, .caption-hero')].some((el) => /Fight|villagers|Age at/.test(el.textContent ?? '')), null, { timeout: 150_000 })
  const said = await win.evaluate(() => [...document.querySelectorAll('.captions:not(.not-said) li')].map((li) => li.textContent))
  assert(new Set(said).size === said.length, `a line was shown twice: ${said.join(' | ')}`)
  await win.screenshot({ path: join(out, 'commentary-vs-ai.png') })
  await stopDemo()
  return { chip, said: said.slice(0, 6) }
})

await check('ranked: commentary waits for the end of the match, and "Why didn\'t it say that?" gives the reason', async () => {
  await settings({ commentary: true, profileId: 'aoe4', profileModeId: 'ranked-1v1' })
  const chip = (await win.textContent('.btn.toggle.commentary')).trim()
  assert(/after the match/.test(chip), `commentary chip says "${chip}"`)
  await startDemo()
  await win.click('#notsaid-h + button')
  await win.waitForFunction(() => document.querySelectorAll('.not-said li').length >= 1, null, { timeout: 150_000 })
  const rows = await win.evaluate(() => [...document.querySelectorAll('.not-said li')].map((li) => li.textContent))
  assert(rows.some((r) => /not allowed|held until the match ends|post-match/i.test(r)), `no policy reason in: ${rows.join(' | ')}`)
  const spoken = await win.evaluate(() => [...document.querySelectorAll('.captions:not(.not-said) li')].map((li) => li.textContent))
  assert(spoken.length === 0, `shown during ranked play: ${spoken.join(' | ')}`)
  await win.screenshot({ path: join(out, 'ranked-not-said.png') })
  await stopDemo()
  return { chip, rows: rows.slice(0, 4), captions: spoken }
})

await app.close()
rmSync(userData, { recursive: true, force: true })
writeFileSync(join(out, 'results.json'), JSON.stringify({ results, logs: logs.slice(-80) }, null, 1))
console.log(`\n${results.length - failed} of ${results.length} passed`)
if (failed) {
  console.log('Last app console lines:\n' + logs.slice(-20).join('\n'))
  process.exitCode = 1
}
