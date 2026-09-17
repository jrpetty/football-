// An independent check of the shipped app.
//
// Her figures are typed in again here, from the message she sent, and the
// arithmetic is done again here too — no constant and no function is borrowed
// from the app. Then the app is opened for the first time and every line is
// read back out of its own database. Anything that disagrees is a fault.

import { launchChromium } from '../scripts/browser.ts'
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { extname, join } from 'node:path'
import { existsSync } from 'node:fs'

const PINT = 568

// --- her sheet, typed again ---------------------------------------------------
const LARGE = { empty: 22.4, full: 123, pints: 176 }
const MIDDLE = { full: 103, pints: 144 }
const SMALL = { empty: 13.4, full: 64.75, pints: 88 }
const BOTTLE_ML = 750

/** share of a keg, then its pints, then millilitres. */
function part(keg: { empty: number; full: number; pints: number }, kg: number): number {
  const share = (kg - keg.empty) / (keg.full - keg.empty)
  return Math.round(Math.min(1, Math.max(0, share)) * keg.pints * PINT)
}

const expected: Record<string, number> = {
  // Taddy: 5 kegs plus a pretty much full one, counted as 6.
  'taddy-lager': 6 * LARGE.pints * PINT,
  // Alpine: 2 kegs and a tenth.
  alpine: Math.round(2.1 * MIDDLE.pints * PINT),
  // Stout: 2 kegs and one at 38.05 kg.
  stout: 2 * SMALL.pints * PINT + part(SMALL, 38.05),
  // Cider: 1 keg and one at 61.85 kg.
  cider: SMALL.pints * PINT + part(SMALL, 61.85),
  // Dark mild: one keg at 53 kg.
  'dark-mild': part(SMALL, 53),
  rose: 25 * BOTTLE_ML,
  'red-wine': 15 * BOTTLE_ML + 625,
  'house-wine': 73 * BOTTLE_ML + 375,
  cherry: 46, raspberry: 22, chocolate: 39, apricot: 38, pear: 85, strawberry: 6,
  'nut-brown': 31, 'pure-brew-bottled': 30, 'alc-free': 29,
  'orange-juice': 53, 'apple-juice': 56, passion: 39, elderflower: 39,
  'rasp-and-cran': 25, tonic: 41, 'ginger-beer': 58,
  'crisps-sweet-chilli': 93, 'crisps-steak': 66, 'crisps-prawn-cocktail': 56,
  'crisps-sea-salted': 97, 'crisps-vinegar': 106, 'crisps-cheese': 107,
  'salted-nuts': 68, 'dry-roast': 58,
}

const containers: Record<string, { name: string; baseUnits: number; emptyKg?: number; fullKg?: number }> = {
  'taddy-lager': { name: 'large keg', baseUnits: LARGE.pints * PINT, emptyKg: 22.4, fullKg: 123 },
  alpine: { name: 'middle keg', baseUnits: MIDDLE.pints * PINT, fullKg: 103 },
  stout: { name: 'small keg', baseUnits: SMALL.pints * PINT, emptyKg: 13.4, fullKg: 64.75 },
  cider: { name: 'small keg', baseUnits: SMALL.pints * PINT, emptyKg: 13.4, fullKg: 64.75 },
  'dark-mild': { name: 'small keg', baseUnits: SMALL.pints * PINT, emptyKg: 13.4, fullKg: 64.75 },
  rose: { name: 'wine bottle', baseUnits: 750, emptyKg: 0.175, fullKg: 1.125 },
  'red-wine': { name: 'wine bottle', baseUnits: 750, emptyKg: 0.175, fullKg: 1.125 },
  'house-wine': { name: 'wine bottle', baseUnits: 750, emptyKg: 0.175, fullKg: 1.125 },
  'crisps-cheese': { name: 'box', baseUnits: 25 },
}

/** Nothing here was counted, so nothing here may carry a figure. */
const NOT_COUNTED = ['vodka', 'gin', 'bourbon', 'spiced-rum', 'peach-schnapps', 'post-mix', 'crisps', 'fruit-beer']

// --- the shipped app ----------------------------------------------------------
import { fileURLToPath } from 'node:url'
const here = join(fileURLToPath(import.meta.url), '..')
const single = process.argv[2] ?? join(here, '..', 'dist', 'index.html')
const root = join(single, '..')
const MIME: Record<string, string> = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png' }
const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost')
  let file = join(root, decodeURIComponent(url.pathname))
  if (url.pathname === '/' || !existsSync(file)) file = single
  res.writeHead(200, { 'content-type': MIME[extname(file)] ?? 'application/octet-stream' })
  res.end(await readFile(file))
})
await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
const addr = server.address()
const base = `http://127.0.0.1:${typeof addr === 'object' && addr ? addr.port : 0}/`

let bad = 0
const say = (ok: boolean, label: string, detail = '') => {
  if (!ok) bad++
  if (!ok) console.log(`  FAIL  ${label}${detail ? ` — ${detail}` : ''}`)
}

const browser = await launchChromium()
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
const page = await ctx.newPage()
const errors: string[] = []
page.on('pageerror', (e) => errors.push(String(e)))
console.log(`checking ${single}\n`)
await page.goto(base, { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)

const stored = await page.evaluate(() => new Promise<{ items: Array<{ id: string; name: string; kind: string; servingBaseUnits: number; servingName: string; container?: unknown }>; counts: Array<{ date: string; lines: Array<{ stockItemId: string; baseUnits: number }> }> }>((resolve) => {
  const req = indexedDB.open('tally')
  req.onsuccess = () => {
    const db = req.result
    const tx = db.transaction(['stock', 'stockcounts'], 'readonly')
    const cfg = tx.objectStore('stock').get('config')
    const cnt = tx.objectStore('stockcounts').getAll()
    tx.oncomplete = () => resolve({ items: (cfg.result as { items: never[] })?.items ?? [], counts: cnt.result as never[] })
  }
}))

say(stored.counts.length === 1, 'exactly one stock take', `${stored.counts.length} found`)
const count = stored.counts[0]
console.log(`  the count is dated ${count?.date}, with ${count?.lines.length} lines on it`)
const got = new Map((count?.lines ?? []).map((l) => [l.stockItemId, l.baseUnits]))
const items = new Map(stored.items.map((i) => [i.id, i]))

for (const [id, want] of Object.entries(expected)) {
  say(items.has(id), `${id}: has a line`)
  say(got.get(id) === want, `${id}: counted`, `app ${got.get(id)}, sheet says ${want}`)
}
say(got.size === Object.keys(expected).length, 'nothing extra was counted', `${got.size} counted, ${Object.keys(expected).length} expected`)
for (const id of NOT_COUNTED) say(!got.has(id), `${id}: not counted, as it was not`)

for (const [id, want] of Object.entries(containers)) {
  const c = items.get(id)?.container as Record<string, unknown> | undefined
  say(!!c, `${id}: has a container`)
  if (c) {
    say(c.name === want.name && c.baseUnits === want.baseUnits, `${id}: container size`, JSON.stringify(c))
    say(c.emptyKg === want.emptyKg, `${id}: empty weight`, `app ${String(c.emptyKg)}, sheet ${String(want.emptyKg)}`)
    say(c.fullKg === want.fullKg, `${id}: full weight`, `app ${String(c.fullKg)}, sheet ${String(want.fullKg)}`)
  }
}

// It has to survive being closed and opened, and not count itself again.
await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
const again = await page.evaluate(() => new Promise<number>((resolve) => {
  const req = indexedDB.open('tally')
  req.onsuccess = () => { const g = req.result.transaction('stockcounts', 'readonly').objectStore('stockcounts').getAll(); g.onsuccess = () => resolve((g.result as never[]).length) }
}))
say(again === 1, 'still one stock take after a restart', `${again}`)
say(errors.length === 0, 'nothing threw', errors.join('; '))

await browser.close(); server.close()
console.log(bad === 0 ? `\n  all ${Object.keys(expected).length} lines agree with the sheet` : `\n  ${bad} disagreements`)
process.exit(bad === 0 ? 0 : 1)
