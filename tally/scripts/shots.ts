// Screenshots of the built app for design review: each screen, both themes,
// seeded with the real Gardeners Arms night. Not a test — a pair of eyes.
import { createServer } from 'node:http'
import { readFile, mkdir } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { extname, join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { launchChromium } from './browser.ts'
import { GARDENERS_ARMS } from '../test/fixtures/gardenersArms.ts'

const here = dirname(fileURLToPath(import.meta.url))
const dist = join(here, '..', 'dist')
const out = process.env.SHOT_DIR ?? join(here, '..', 'shots')
await mkdir(out, { recursive: true })

const MIME: Record<string, string> = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml', '.png': 'image/png',
}
const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost')
  let file = join(dist, decodeURIComponent(url.pathname))
  if (url.pathname === '/' || !existsSync(file)) file = join(dist, 'index.html')
  try {
    res.writeHead(200, { 'content-type': MIME[extname(file)] ?? 'application/octet-stream' })
    res.end(await readFile(file))
  } catch { res.writeHead(404).end() }
})
await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
const addr = server.address()
const base = `http://127.0.0.1:${typeof addr === 'object' && addr ? addr.port : 0}/`

const browser = await launchChromium()

for (const scheme of ['dark', 'light'] as const) {
  const ctx = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
    colorScheme: scheme,
  })
  await ctx.addInitScript(() => { try { localStorage.setItem('tally.engine', 'off') } catch {} })
  const page = await ctx.newPage()
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.evaluate(async (day) => {
    await new Promise<void>((resolve, reject) => {
      const req = indexedDB.open('tally')
      req.onupgradeneeded = () => {
        const db = req.result
        if (!db.objectStoreNames.contains('days')) db.createObjectStore('days', { keyPath: 'date' })
        if (!db.objectStoreNames.contains('photos')) db.createObjectStore('photos', { keyPath: 'id' })
      }
      req.onsuccess = () => {
        const tx = req.result.transaction('days', 'readwrite')
        tx.objectStore('days').put(day)
        tx.oncomplete = () => resolve()
        tx.onerror = () => reject(tx.error)
      }
      req.onerror = () => reject(req.error)
    })
  }, {
    date: '2026-08-23',
    till: { pence: 219280, source: 'vision', edited: false },
    card: { pence: 184100, source: 'manual', edited: false },
    cashPence: 33980,
    note: '',
    zRead: GARDENERS_ARMS,
    createdAt: 0, updatedAt: 0,
  })
  await page.reload({ waitUntil: 'networkidle' })

  // Tonight, part-filled so the steps and verdict show life.
  await page.fill('#figure-till', '2192.80')
  await page.fill('#figure-card', '1841.00')
  await page.waitForTimeout(200)
  await page.screenshot({ path: join(out, `tonight-${scheme}.png`), fullPage: false })

  await page.click('button:has-text("Trade")')
  await page.waitForSelector('.kpi-row', { timeout: 5000 })
  await page.waitForTimeout(250)
  await page.screenshot({ path: join(out, `trade-${scheme}.png`), fullPage: false })
  await page.screenshot({ path: join(out, `trade-full-${scheme}.png`), fullPage: true })

  await page.click('button:has-text("Cellar")')
  await page.waitForTimeout(300)
  await page.screenshot({ path: join(out, `cellar-${scheme}.png`), fullPage: false })

  await page.click('button:has-text("Nights")')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  await page.waitForTimeout(200)
  await page.screenshot({ path: join(out, `nights-${scheme}.png`), fullPage: false })

  await page.click('button:has-text("Settings")')
  await page.waitForTimeout(300)
  await page.screenshot({ path: join(out, `settings-${scheme}.png`), fullPage: false })

  // The saved night, opened.
  await page.click('button:has-text("Nights")')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  await page.click('.day-row')
  await page.waitForSelector('.verdict', { timeout: 5000 })
  await page.waitForTimeout(200)
  await page.screenshot({ path: join(out, `night-${scheme}.png`), fullPage: false })

  // The roll review, reached by editing the night.
  await page.click('button:has-text("Edit")')
  await page.waitForSelector('button:has-text("Check every figure")', { timeout: 5000 })
  await page.click('button:has-text("Check every figure")')
  await page.waitForTimeout(300)
  await page.screenshot({ path: join(out, `review-${scheme}.png`), fullPage: false })

  // The price list.
  await page.click('button:has-text("Settings")')
  await page.waitForSelector('button:has-text("Open the price list")', { timeout: 5000 })
  await page.click('button:has-text("Open the price list")')
  await page.waitForTimeout(300)
  await page.screenshot({ path: join(out, `prices-${scheme}.png`), fullPage: false })

  await ctx.close()
}

await browser.close()
server.close()
console.log('shots written to', out)
