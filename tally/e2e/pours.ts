// An independent check that the stock is actually coming off.
//
// The figures are typed in again here — her opening counts from the sheet, the
// quantities from the real till roll — and the subtraction is done again here
// too. No constant and no function is borrowed from the app. Then the app is
// opened, the receipt put in, the till tied to the cellar through its own
// interface, and every line read back off the screen she reads.
//
// Anything that disagrees is a fault. A cellar that is quietly not being
// reduced looks exactly like a cellar that is.

import { launchChromium } from '../scripts/browser.ts'
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { extname, join } from 'node:path'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { GARDENERS_ARMS } from '../test/fixtures/gardenersArms.ts'

const PINT = 568
const HALF = 284

// --- what was down there, typed again from her sheet ----------------------------
const LARGE_PINTS = 176
const MIDDLE_PINTS = 144
const SMALL_PINTS = 88
const BOTTLE_ML = 750

function part(emptyKg: number, fullKg: number, pints: number, kg: number): number {
  return Math.round(((kg - emptyKg) / (fullKg - emptyKg)) * pints * PINT)
}

const opening: Record<string, number> = {
  'taddy-lager': 6 * LARGE_PINTS * PINT,
  alpine: Math.round(2.1 * MIDDLE_PINTS * PINT),
  stout: 2 * SMALL_PINTS * PINT + part(13.4, 64.75, SMALL_PINTS, 38.05),
  cider: SMALL_PINTS * PINT + part(13.4, 64.75, SMALL_PINTS, 61.85),
  'dark-mild': part(13.4, 64.75, SMALL_PINTS, 53),
  rose: 25 * BOTTLE_ML,
  'house-wine': 73 * BOTTLE_ML + 375,
  'ginger-beer': 58,
  elderflower: 39,
  tonic: 41,
  'orange-juice': 53,
  'apple-juice': 56,
  'alc-free': 29,
  'pure-brew-bottled': 30,
  'salted-nuts': 68,
  'dry-roast': 58,
  // Counted by flavour on her sheet, totalled because the till sells them
  // through one button. The six figures added up here, not taken from the app.
  crisps: 93 + 66 + 56 + 97 + 106 + 107,
}

// --- what the roll says went out, typed again from the printed receipt ------------
//
// Each entry is what one sale takes, then how many were rung up. A pint takes
// 568ml, a half 284, a glass of wine its printed measure, a bottle off a shelf
// one of itself.
const poured: Record<string, number> = {
  'taddy-lager': 120 * PINT + 19 * HALF,
  alpine: 66 * PINT + 26 * HALF,
  stout: 24 * PINT,
  cider: 28 * PINT + 6 * HALF,
  'dark-mild': 5 * PINT,
  rose: 6 * 175 + 3 * 250,
  'house-wine': 8 * 125 + 7 * 175 + 11 * 250,
  'ginger-beer': 6,
  elderflower: 5,
  tonic: 3,
  'orange-juice': 1,
  'apple-juice': 7,
  'alc-free': 1,
  'pure-brew-bottled': 1,
  'salted-nuts': 3,
  'dry-roast': 4,
  crisps: 79,
}

/** How the app says each line's name, so a row can be found on the screen. */
const shown: Record<string, string> = {
  'taddy-lager': 'Taddy Lager',
  alpine: 'Alpine',
  stout: 'Stout',
  cider: 'Cider',
  'dark-mild': 'Dark Mild',
  rose: 'Rose',
  'house-wine': 'White wine',
  'ginger-beer': 'Ginger Beer',
  elderflower: 'Elderflower',
  tonic: 'Tonic',
  'orange-juice': 'Orange Juice',
  'apple-juice': 'Apple Juice',
  'alc-free': 'alc free',
  'pure-brew-bottled': 'Pure Brew (bottled)',
  'salted-nuts': 'Salted Nuts',
  'dry-roast': 'Dry Roast',
  crisps: 'Crisps',
}

/** Lines counted on the sheet that the roll never sold — they must not move. */
const UNTOUCHED: Record<string, number> = {
  'red-wine': 14 * BOTTLE_ML + 625,
  cherry: 46,
  pear: 85,
  'nut-brown': 31,
}

/** How the screen writes a figure, so the expectation can be compared as text. */
function asShown(id: string, baseUnits: number): string {
  if (id === 'rose' || id === 'house-wine' || id === 'red-wine') return `${baseUnits} ml`
  if (id === 'crisps') return String(baseUnits)
  if (['taddy-lager', 'alpine', 'stout', 'cider', 'dark-mild'].includes(id)) {
    const pints = Math.round((baseUnits / PINT) * 10) / 10
    return `${pints} pint`
  }
  return String(baseUnits)
}

// --- the shipped app ------------------------------------------------------------
const here = join(fileURLToPath(import.meta.url), '..')
const single = process.argv[2] ?? join(here, '..', 'dist', 'index.html')
const root = join(single, '..')
const MIME: Record<string, string> = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png',
}
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
let checks = 0
const say = (ok: boolean, label: string, detail = '') => {
  checks++
  if (ok) {
    console.log(`  ok    ${label}`)
  } else {
    bad++
    console.log(`  FAIL  ${label}${detail ? `\n        ${detail}` : ''}`)
  }
}

const browser = await launchChromium()
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
await ctx.addInitScript(() => {
  // Scanning off: the receipt is put in already read, which is what is being
  // checked. Nothing here depends on an API key.
  try { localStorage.setItem('tally.engine', 'off') } catch { /* ignore */ }
})
const page = await ctx.newPage()
const errors: string[] = []
page.on('pageerror', (e) => errors.push(String(e)))
console.log(`checking ${single}\n`)
await page.goto(base, { waitUntil: 'networkidle' })
// The real stock take seeds itself on first run; give it a moment to land.
await page.waitForTimeout(1800)

// The night, exactly as the roll printed it, dated after the stock take.
await page.evaluate(async (roll) => {
  await new Promise<void>((resolve, reject) => {
    const req = indexedDB.open('tally')
    req.onsuccess = () => {
      const tx = req.result.transaction('days', 'readwrite')
      tx.objectStore('days').put({
        date: '2026-09-17',
        till: { pence: 219280, source: 'vision', edited: false },
        card: { pence: 184100, source: 'manual', edited: false },
        cashPence: 33980, note: '', zRead: roll, createdAt: 0, updatedAt: 0,
      })
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    }
    req.onerror = () => reject(req.error)
  })
}, GARDENERS_ARMS)
await page.reload({ waitUntil: 'networkidle' })

// Before anything is tied up, nothing may come off — and it must say so.
await page.click('button:has-text("Cellar")')
await page.waitForTimeout(900)
const before = await page.locator('.main').innerText()
say(/knows nothing about/.test(before), 'before it is tied up, it owns up to taking nothing off')

// Tie the till to the cellar through the interface, as she would.
await page.click('.chip:has-text("Set up")')
await page.waitForSelector('[data-testid="keep-pours"]', { timeout: 8000 })
await page.click('[data-testid="keep-pours"]')
await page.waitForTimeout(1200)

await page.click('.chip:has-text("What’s down there")')
await page.waitForTimeout(1000)

for (const [id, open] of Object.entries(opening)) {
  const want = open - (poured[id] ?? 0)
  const name = shown[id] as string
  const row = await page.locator(`tr:has(th:text-is("${name}"))`).first().innerText().catch(() => '')
  const cells = row.split('\t').map((c) => c.trim())
  // Line | Left | Nights | Counted | In | Poured
  const left = cells[1] ?? ''
  say(
    left.startsWith(asShown(id, want)),
    `${name}: ${asShown(id, open)} less what the till poured leaves ${asShown(id, want)}`,
    `the app says "${left}" — whole row: ${row.replace(/\t/g, ' | ')}`,
  )
}

for (const [id, open] of Object.entries(UNTOUCHED)) {
  const name = {
    'red-wine': 'Red wine', cherry: 'Cherry', pear: 'Pear', 'nut-brown': 'Nut Brown',
  }[id] as string
  const row = await page.locator(`tr:has(th:text-is("${name}"))`).first().innerText().catch(() => '')
  const cells = row.split('\t').map((c) => c.trim())
  say(
    (cells[1] ?? '').startsWith(asShown(id, open)),
    `${name}: the roll never sold it, so it has not moved`,
    `the app says "${cells[1] ?? ''}"`,
  )
}

// --- the lines nothing sells, and counting them the way they are sold ----------
//
// The mirror of an unmapped sale: a line no pour points at can never go down,
// so it sits at whatever it was last counted at looking like stock that never
// moves. The fruit beers are the crisps all over again — several in the cellar,
// one button on the till.
await page.click('.chip:has-text("Set up")')
await page.waitForTimeout(700)
const orphanText = await page.locator('[data-testid="orphans"]').innerText().catch(() => '')
say(/never moves/.test(orphanText), 'it names the lines nothing on the till sells', orphanText.slice(0, 200))

const pageText = await page.locator('.main').innerText()
for (const name of ['Cherry', 'Raspberry', 'Nut Brown', 'Red wine']) {
  say(pageText.includes(name), `${name} is listed as a line nothing sells`)
}
say(!pageText.includes('Crisps — cheese'), 'and the crisps are not, because they were totalled')

// Total two of them, as she would the fruit beers.
await page.locator('input[aria-label="Total Cherry with others"]').check()
await page.locator('input[aria-label="Total Raspberry with others"]').check()
await page.fill('[data-testid="total-name"]', 'Fruit beer')
await page.click('[data-testid="total-lines"]')
await page.waitForTimeout(1200)
say(
  /counted as one/.test(await page.locator('.toast').innerText().catch(() => '')),
  'totalling two lines says what it did',
  await page.locator('.toast').innerText().catch(() => ''),
)

await page.click('.chip:has-text("What’s down there")')
await page.waitForTimeout(900)
const fruit = await page.locator('tr:has(th:text-is("Fruit beer"))').first().innerText().catch(() => '')
const fruitCells = fruit.split('\t').map((c) => c.trim())
// Line | Left | Nights | Counted | In | Poured
say(
  (fruitCells[3] ?? '').startsWith('68'),
  'and 46 cherry plus 22 raspberry is counted as 68',
  `the app says "${fruit.replace(/\t/g, ' | ')}"`,
)
// The till's own FRUIT BEER button now draws on the combined line, which is
// the whole reason for totalling them.
say(
  (fruitCells[5] ?? '').startsWith('3'),
  'and the three the till sold come off it',
  `the app says "${fruit.replace(/\t/g, ' | ')}"`,
)
say(
  (fruitCells[1] ?? '').startsWith('65'),
  'leaving 65',
  `the app says "${fruit.replace(/\t/g, ' | ')}"`,
)
const gone = await page.locator('.main').innerText()
say(!/\bCherry\b/.test(gone) && !/\bRaspberry\b/.test(gone), 'with the two old lines gone')

say(errors.length === 0, 'nothing threw along the way', errors.join('; '))

await ctx.close()
await browser.close()
server.close()
console.log(`\n${checks - bad}/${checks} checks passed`)
process.exit(bad === 0 ? 0 : 1)
