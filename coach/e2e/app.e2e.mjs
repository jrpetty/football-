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
  // Linux: global keys through X11 (xdotool), not a desktop portal over D-Bus that a test runner may have.
  args: ['.', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', ...(process.platform === 'linux' ? ['--no-sandbox', '--ozone-platform=x11', '--disable-features=GlobalShortcutsPortal'] : [])],
  cwd: root,
  env: { ...process.env, SIGHTLINE_USER_DATA: userData, ...(process.platform === 'linux' ? { DBUS_SESSION_BUS_ADDRESS: 'disabled:' } : {}) },
})
const mainLog = []
app.process().stdout?.on('data', (d) => mainLog.push(String(d).trim()))
app.process().stderr?.on('data', (d) => mainLog.push(String(d).trim()))
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
  await sleep(1000)
  holdKey('F7', 3000)
  await sleep(2500)
  const held = await win.evaluate(() => window.__ptt.splice(0))
  const env = process.platform === 'linux' ? ` (DISPLAY=${process.env.DISPLAY}, WAYLAND_DISPLAY=${process.env.WAYLAND_DISPLAY ?? ''}, XDG_SESSION_TYPE=${process.env.XDG_SESSION_TYPE ?? ''}, shortcut ${info.talkShortcut})` : ''
  assert(JSON.stringify(held) === JSON.stringify(['start', 'stop']), `held: expected start, stop; got ${JSON.stringify(held)}${env}`)
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

/** A small AoE4World history (as the app keeps it) and a build for the English, written straight to the data folder. */
function seedDocs() {
  const now = Math.floor(Date.now() / 1000)
  const games = []
  const facts = {}
  for (let i = 0; i < 8; i++) {
    const id = 7000 + i
    games.push({ gameId: id, startedAt: now - (i < 3 ? i + 1 : 8 + i * 3) * 86400, durationSec: 1300, map: 'Dry Arabia', kind: 'rm_1v1', civ: 'english', opponents: ['french'], result: i % 3 ? 'win' : 'loss', rating: 1100 })
    facts[id] = { gameId: id, villagers: { 600: i < 3 ? 34 : 30 }, ages: { feudal: i < 3 ? 270 : 320 }, build: [], opponentArmy: [] }
  }
  const history = { schema: 1, profileId: 4242, syncedAt: new Date().toISOString(), games, facts, noSummary: [] }
  const step = (atSec, kind, key, name, count = 1) => ({ atSec, kind, key, name, count })
  const build = {
    schema: 1,
    id: 'b-test',
    name: 'Test English opening',
    civ: 'english',
    source: 'custom',
    createdAt: new Date().toISOString(),
    steps: [step(30, 'building', 'mill', 'Mill'), step(95, 'building', 'mining_camp', 'Mining Camp'), step(180, 'villagers', 'villagers', 'Villagers', 14), step(270, 'age', 'feudal', 'Feudal Age (Council Hall)'), step(300, 'villagers', 'villagers', 'Villagers', 21)],
  }
  return { history, builds: { schema: 1, builds: [build], active: { english: 'b-test' } } }
}

await check('Progress: the week from AoE4World, trends and win rates; the practice panel; build orders', async () => {
  // The history is saved before the profile is linked, and it is fresh, so nothing is asked of AoE4World.
  await settings({ saveTendencies: true, profileId: 'aoe4', profileModeId: 'skirmish-vs-ai', commentary: false, muted: true, context: { aoe4: { civ: 'English' } } })
  const { history, builds } = seedDocs()
  const saved = await win.evaluate(([h, b]) => Promise.all([window.sightline.saveDoc('aoe4world', 'aoe4', h), window.sightline.saveDoc('builds', 'aoe4', b)]), [history, builds])
  assert(saved.every(Boolean), `documents not saved: ${JSON.stringify(saved)}`)
  await settings({ aoe4world: { profileId: 4242, name: 'Tester', auto: true } })
  await win.click('nav >> text=Progress')
  await win.waitForSelector('#week-h', { timeout: 10_000 })
  const week = await win.evaluate(() => [...document.querySelectorAll('.week-lines li')].map((l) => l.textContent))
  assert(week.some((l) => /^3 games this week/.test(l)), `week: ${week.join(' | ')}`)
  assert(week.some((l) => /^Feudal Age 50 s faster than the 4 weeks before/.test(l)), `week: ${week.join(' | ')}`)
  const charts = await win.locator('.trend-chart svg').count()
  assert(charts >= 2, `trend charts: ${charts}`)
  const rates = await win.textContent('section:has(#wr-h)')
  assert(/French/.test(rates ?? ''), 'win rates by opponent')
  const training = await win.textContent('section:has(#builds-h)')
  assert(/Training with: Test English opening/.test(training ?? ''), 'the saved build is the one in use')
  await win.screenshot({ path: join(out, 'progress.png'), fullPage: true })
  return { week }
})

await check('build trainer: the next steps are called during the AoE IV demo against the AI', async () => {
  await win.click('nav >> text=Coach')
  await win.waitForSelector(HOME, { timeout: 10_000 })
  const card = await win.locator('#opp-h').count()
  assert(card === 1, 'the opponent card is on the Coach screen')
  await startDemo()
  await win.waitForFunction(() => [...document.querySelectorAll('.captions:not(.not-said) li, .caption-hero')].some((el) => /Build: /.test(el.textContent ?? '')), null, { timeout: 60_000 })
  const said = await win.evaluate(() => [...document.querySelectorAll('.captions:not(.not-said) li, .caption-hero')].map((el) => el.textContent).filter((t) => /Build: /.test(t ?? '')))
  const panel = await win.textContent('section:has(#training-h)')
  assert(/Test English opening/.test(panel ?? ''), 'the Training panel names the build')
  await win.screenshot({ path: join(out, 'build-trainer.png') })
  await stopDemo()
  return { said: said.slice(0, 3) }
})

await app.close()
rmSync(userData, { recursive: true, force: true })
writeFileSync(join(out, 'results.json'), JSON.stringify({ results, logs: logs.slice(-80), main: mainLog.slice(-80) }, null, 1))
console.log(`\n${results.length - failed} of ${results.length} passed`)
if (failed) {
  console.log('Last app console lines:\n' + logs.slice(-20).join('\n'))
  console.log('Last main-process lines:\n' + mainLog.slice(-20).join('\n'))
  process.exitCode = 1
}
