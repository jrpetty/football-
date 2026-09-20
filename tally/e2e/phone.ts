// ---------------------------------------------------------------------------
// The app on a phone.
//
// The other suites prove the arithmetic and the flow. This one asks a
// different question: does it actually fit, and can it be tapped?
//
// Two sizes, both smaller than the 390px the rest of the tests use: an iPhone
// SE and a small Android, which is the narrowest anybody is likely to hand
// this to. On every screen it checks three things that make an app unusable on
// a phone and are invisible on a laptop:
//
//   - the page scrolls sideways, so half of every row is off the edge
//   - something is drawn outside the window and cannot be reached
//   - a control is too small to hit with a thumb
//
// The engine here is Chromium, because that is the only one this machine can
// run. An iPhone runs WebKit, so this is a proxy for the layout and not for
// Safari itself.
// ---------------------------------------------------------------------------

import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { extname, join, normalize, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Page } from 'playwright'
import { launchChromium } from '../scripts/browser.ts'
import { GARDENERS_ARMS } from '../test/fixtures/gardenersArms.ts'

const here = dirname(fileURLToPath(import.meta.url))
const dist = join(here, '..', 'dist')
if (!existsSync(join(dist, 'index.html'))) {
  console.error('No build found. Run `npm run build` first.')
  process.exit(1)
}

const MIME: Record<string, string> = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.map': 'application/json',
}
const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost')
  const rel = normalize(decodeURIComponent(url.pathname)).replace(/^(\.\.[/\\])+/, '')
  let file = join(dist, rel)
  if (rel === '/' || rel === '\\' || !existsSync(file)) file = join(dist, 'index.html')
  res.writeHead(200, { 'content-type': MIME[extname(file)] ?? 'application/octet-stream' })
  res.end(await readFile(file))
})
await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
const address = server.address()
const base = `http://127.0.0.1:${typeof address === 'object' && address ? address.port : 0}/`

let failures = 0
let checks = 0
function check(label: string, ok: boolean, detail = ''): void {
  checks++
  if (ok) console.log(`  ok    ${label}`)
  else {
    failures++
    console.log(`  FAIL  ${label}${detail ? `\n        ${detail}` : ''}`)
  }
}

/**
 * Go to a section.
 *
 * The bar has three tabs and a More drawer: Trade, the rota, the price list and
 * the settings live behind More, so reaching one of them is two taps rather than
 * one. Routed through here so a test says where it is going and not how.
 */
async function goTab(page: Page, name: string): Promise<void> {
  // Tonight, Sold and the Cellar are on the bar. The nights, the rota, the
  // price list and the settings are behind More, so those cost two taps.
  if (['Nights', 'Rota', 'Price list', 'Settings'].includes(name)) {
    await page.click('.tabs button:has-text("More")')
    await page.click(`.door:has-text("${name}")`)
  } else {
    await page.click(`.tabs button:has-text("${name}")`)
  }
}

/**
 * Get at the card and cash boxes.
 *
 * The roll is the night now: photograph it and the app says what sold and what
 * it took. Counting the drawer against that is a second job, behind "Check the
 * money balances" — open already on a night that has figures in it.
 */
async function openMoney(page: Page): Promise<void> {
  if (!(await page.locator('#figure-card').isVisible().catch(() => false))) {
    await page.click('[data-testid="check-money"]')
    await page.waitForSelector('#figure-card', { timeout: 5000 })
  }
}

/**
 * Open one of the cellar's panels.
 *
 * Three chips are on the screen — what is down there, the stock take, a
 * delivery. Week by week, the scales, the costs and the set-up are behind
 * More…, so a test asks for the panel and this finds it either way.
 */
async function cellarPanel(page: Page, label: string): Promise<void> {
  await page.waitForSelector('.chip-row', { timeout: 5000 })
  if ((await page.locator(`.chip:has-text("${label}")`).count()) === 0) {
    await page.click('[data-testid="cellar-more"]')
  }
  await page.click(`.chip:has-text("${label}")`)
}

/** Anything deliberately small: a text link in a row, not a control to hit. */
const TINY_BY_DESIGN = ['hours-change', 'shot-open', 'lb-close', 'lb-nav']

interface Trouble {
  sideways: number
  offscreen: string[]
  small: string[]
}

async function inspect(page: Page): Promise<Trouble> {
  return await page.evaluate((tiny: string[]) => {
    const doc = document.documentElement
    const width = doc.clientWidth
    const describe = (el: Element) => {
      const cls = typeof el.className === 'string' ? el.className.split(' ').slice(0, 2).join('.') : ''
      const text = (el.textContent ?? '').trim().slice(0, 24)
      return `${el.tagName.toLowerCase()}${cls ? `.${cls}` : ''}${text ? ` “${text}”` : ''}`
    }
    // Something inside a sideways-scrolling table is meant to be wider than
    // the screen; the page itself is not.
    const scrollable = (el: Element): boolean => {
      for (let p: Element | null = el; p; p = p.parentElement) {
        if (p === doc) return false
        const style = getComputedStyle(p)
        if (style.overflowX === 'auto' || style.overflowX === 'scroll') return true
      }
      return false
    }
    const offscreen: string[] = []
    const small: string[] = []
    for (const el of Array.from(document.body.querySelectorAll('*'))) {
      const r = el.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) continue
      const style = getComputedStyle(el)
      if (style.visibility === 'hidden' || style.display === 'none') continue
      if (!scrollable(el) && (r.right > width + 1 || r.left < -1)) {
        if (offscreen.length < 6) offscreen.push(`${describe(el)} [${Math.round(r.left)}…${Math.round(r.right)}] of ${width}`)
      }
      // A file picker hidden behind a button of its own is neither tapped nor
      // typed into, so its size says nothing about using this on a phone.
      const hidden = el.classList.contains('visually-hidden') ||
        (el.tagName === 'INPUT' && ['file', 'hidden'].includes((el as HTMLInputElement).type))
      if (hidden) continue
      const tappable = el.tagName === 'BUTTON' || el.tagName === 'SELECT' ||
        (el.tagName === 'INPUT' && !['checkbox', 'radio'].includes((el as HTMLInputElement).type))
      if (tappable && r.height < 40 && !tiny.some((t) => (el.className ?? '').toString().includes(t))) {
        if (small.length < 6) small.push(`${describe(el)} is ${Math.round(r.height)}px tall`)
      }
      // A box small enough to zoom the whole page when it is tapped.
      if (el.tagName === 'INPUT' || el.tagName === 'SELECT' || el.tagName === 'TEXTAREA') {
        const size = parseFloat(style.fontSize)
        if (size < 16 && small.length < 6) small.push(`${describe(el)} has ${size}px text, which zooms the page on an iPhone`)
      }
    }
    return { sideways: doc.scrollWidth - width, offscreen, small }
  }, TINY_BY_DESIGN)
}

async function screen(page: Page, label: string): Promise<void> {
  await page.waitForTimeout(220)
  const t = await inspect(page)
  check(`${label}: does not scroll sideways`, t.sideways <= 0, `${t.sideways}px of overhang`)
  check(`${label}: nothing is drawn off the edge`, t.offscreen.length === 0, t.offscreen.join('\n        '))
  check(`${label}: everything can be tapped`, t.small.length === 0, t.small.join('\n        '))
}

/** Eight weeks, six nights a week: the pub is shut on Mondays. */
const NIGHTS_SEEDED = 41

const browser = await launchChromium()
const errors: string[] = []
try {
  for (const phone of [
    { name: 'a small Android', width: 360, height: 640 },
    { name: 'an iPhone SE', width: 375, height: 667 },
  ]) {
    console.log(`\nOn ${phone.name} (${phone.width}×${phone.height})`)
    const context = await browser.newContext({
      viewport: { width: phone.width, height: phone.height },
      deviceScaleFactor: 2,
      isMobile: true,
      hasTouch: true,
    })
    await context.addInitScript(() => {
      try {
        localStorage.setItem('tally.engine', 'off')
        // The cellar stock take is already in, as far as these tests are
        // concerned: they build their own cellar and judge their own figures.
        localStorage.setItem('tally.seeded', 'cellar-2026-09-16')
      } catch {
        /* ignore */
      }
    })
    const page = await context.newPage()
    page.on('pageerror', (err) => errors.push(`${phone.name}: ${String(err)}`))
    await page.goto(base, { waitUntil: 'networkidle' })

    // Eight weeks of nights rather than one. A single night leaves the
    // week-by-week views, the movers and every stock-take window unrendered —
    // and a screen that never draws cannot be checked for fitting a phone,
    // which is exactly how a layout bug ships.
    await page.evaluate(async (roll) => {
      const days: unknown[] = []
      const d = new Date('2026-07-06T00:00:00')
      for (let i = 0; i < 48; i++) {
        if (d.getDay() !== 1) {
          days.push({
            date: d.toISOString().slice(0, 10),
            till: { pence: 219280, source: 'vision', edited: false },
            card: { pence: 184100, source: 'manual', edited: false },
            cashPence: 33980,
            note: '',
            zRead: roll,
            createdAt: 0,
            updatedAt: 0,
          })
        }
        d.setDate(d.getDate() + 1)
      }
      await new Promise<void>((resolve, reject) => {
        const req = indexedDB.open('tally')
        req.onsuccess = () => {
          const tx = req.result.transaction(['days', 'stockcounts'], 'readwrite')
          for (const day of days) tx.objectStore('days').put(day)
          // Two takes a week apart, so the cellar has a settled window to show.
          tx.objectStore('stockcounts').put({ date: '2026-08-10', lines: [] })
          tx.objectStore('stockcounts').put({ date: '2026-08-17', lines: [] })
          tx.oncomplete = () => resolve()
          tx.onerror = () => reject(tx.error)
        }
        req.onerror = () => reject(req.error)
      })
    }, GARDENERS_ARMS)
    await page.reload({ waitUntil: 'networkidle' })
    await goTab(page, 'Nights')
    await page.waitForSelector('.day-row', { timeout: 8000 })
    check('the seeded nights all landed', (await page.locator('.day-row').count()) === NIGHTS_SEEDED)
    await goTab(page, 'Tonight')
    await page.waitForTimeout(300)

    await screen(page, 'Tonight')
    await goTab(page, 'Sold')
    await page.waitForTimeout(500)
    await screen(page, 'Trade')
    // The whole of Trade, not just the window the chips open on: the weekly
    // charts and the movers only draw over a longer range.
    await page.click('.chip:has-text("90 nights")')
    await page.waitForTimeout(600)
    await screen(page, 'Trade over 90 nights')
    // One line's own card, which carries its own weekly table.
    await page.fill('input[aria-label="Find an item"]', 'TADDY')
    await page.waitForTimeout(400)
    await page.locator('.item-open').first().click()
    await page.waitForTimeout(600)
    await screen(page, 'one item')
    await page.click('button:has-text("Back")')
    await page.waitForTimeout(400)
    await goTab(page, 'Cellar')
    await page.click('button:has-text("Build the cellar from the till")')
    await page.waitForTimeout(600)
    await screen(page, 'the cellar')
    for (const chip of ['Week by week', 'Delivery in', 'Stock take', 'The scales', 'What it costs', 'Set up']) {
      await cellarPanel(page, chip)
      await screen(page, `the cellar — ${chip.toLowerCase()}`)
    }
    await cellarPanel(page, 'Stock take')
    await page.click('button:has-text("Weigh a keg")')
    await screen(page, 'the keg calculator')

    await goTab(page, 'Rota')
    await page.waitForSelector('button:has-text("Add the first person")', { timeout: 5000 })
    await page.click('button:has-text("Add the first person")')
    await page.fill('#person-name', 'Kelly')
    await page.fill('#person-rate', '12.21')
    await page.click('button:has-text("Add to the rota")')
    await page.waitForTimeout(300)
    await screen(page, 'who works here')
    await page.click('.chip:has-text("The week")')
    await page.waitForSelector('.day-card', { timeout: 5000 })
    await screen(page, 'the week by night')
    await page.locator('.day-open').nth(5).click()
    await page.waitForTimeout(250)
    await page.locator('.day-edit .chip:has-text("Kelly")').click()
    await screen(page, 'a night on the rota, open')
    await page.click('.chip:has-text("By person")')
    await screen(page, 'the week by person')
    await page.click('.chip:has-text("Records")')
    await screen(page, 'the records')

    await goTab(page, 'Nights')
    await page.waitForSelector('.day-row', { timeout: 5000 })
    await screen(page, 'the nights')
    await page.click('.day-row')
    await page.waitForSelector('.verdict', { timeout: 5000 })
    await screen(page, 'one night')

    await goTab(page, 'Settings')
    await page.waitForSelector('#apiKey', { timeout: 5000 })
    await screen(page, 'settings')
    await page.click('[data-testid="start-again"]')
    await page.waitForSelector('[data-testid="confirm-clear"]', { timeout: 5000 })
    await screen(page, 'settings — about to start again')

    // The cellar has no signal, and that is where it gets used. After one
    // visit it has to open with the network off and still hold the night.
    await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined))
    await context.setOffline(true)
    await page.reload({ waitUntil: 'domcontentloaded' })
    const opened = await page
      .waitForSelector('.main', { timeout: 10_000 })
      .then(() => true)
      .catch(() => false)
    check('opens with the signal off', opened)
    await goTab(page, 'Nights').catch(() => undefined)
    await page.waitForTimeout(700)
    check(
      'and the nights are still there with no network at all',
      (await page.locator('.day-row').count()) === NIGHTS_SEEDED,
      (await page.locator('.main').innerText().catch(() => 'nothing rendered')).slice(0, 200),
    )
    await context.setOffline(false)

    await page.screenshot({ path: join(here, '..', 'shots', `phone-${phone.width}.png`), fullPage: false })
    await context.close()
  }
  check('nothing threw along the way', errors.length === 0, errors.join('\n        '))
} finally {
  await browser.close()
  server.close()
}

console.log(`\n${checks - failures}/${checks} checks passed`)
process.exit(failures === 0 ? 0 : 1)
