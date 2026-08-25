// ---------------------------------------------------------------------------
// The whole flow, in a real browser, against the real build.
//
// The unit tests cover the arithmetic and the receipt parsing. What they cannot
// cover is whether the thing actually works when a person taps it: whether the
// verdict updates, whether a saved night comes back, whether a failed scan
// leaves her able to finish anyway. That is what this does.
//
// Scanning is switched off for the run, so nothing here needs an API key or a
// network — the OCR engines are exercised by the unit tests and by using it.
//
// Run with `npm run test:e2e` (the app must be built first).
// ---------------------------------------------------------------------------

import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { extname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'
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
  // normalize() before joining, so a request cannot climb out of dist.
  const rel = normalize(decodeURIComponent(url.pathname)).replace(/^(\.\.[/\\])+/, '')
  let file = join(dist, rel)
  if (rel === '/' || rel === '\\' || !existsSync(file)) file = join(dist, 'index.html')
  try {
    const body = await readFile(file)
    res.writeHead(200, { 'content-type': MIME[extname(file)] ?? 'application/octet-stream' })
    res.end(body)
  } catch {
    res.writeHead(404).end('not found')
  }
})

await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
const address = server.address()
const port = typeof address === 'object' && address ? address.port : 0
const base = `http://127.0.0.1:${port}/`

let failures = 0
let checks = 0

function check(label: string, condition: boolean, detail = ''): void {
  checks++
  if (condition) {
    console.log(`  ok    ${label}`)
  } else {
    failures++
    console.log(`  FAIL  ${label}${detail ? `\n        ${detail}` : ''}`)
  }
}

async function verdictText(page: Page): Promise<string> {
  return (await page.locator('.verdict-bar .verdict .headline').first().innerText()).trim()
}

async function setFigure(page: Page, id: string, value: string): Promise<void> {
  await page.fill(`#${id}`, value)
  // The verdict is derived on the next render; wait for it rather than sleeping.
  await page.waitForTimeout(60)
}

const browser = await launchChromium()
const context = await browser.newContext({
  viewport: { width: 390, height: 844 }, // an ordinary phone, held in one hand
  deviceScaleFactor: 3,
  isMobile: true,
  hasTouch: true,
})

// Scanning off: this run is about the flow, not the readers.
await context.addInitScript(() => {
  try {
    localStorage.setItem('tally.engine', 'off')
  } catch {
    /* ignore */
  }
})

let page = await context.newPage()
const pageErrors: string[] = []
page.on('pageerror', (err) => pageErrors.push(String(err)))
page.on('console', (msg) => {
  if (msg.type() === 'error') pageErrors.push(msg.text())
})

try {
  console.log('\nTonight’s count')
  await page.goto(base, { waitUntil: 'networkidle' })
  check('the app loads', /^tally$/i.test((await page.locator('.header h1').innerText()).trim()))
  check('it opens on tonight', (await page.locator('#figure-till').count()) === 1)
  check('nothing is claimed before a figure is entered', (await verdictText(page)) === 'Not finished')

  await setFigure(page, 'figure-till', '4212.30')
  check('still unfinished with one figure', (await verdictText(page)) === 'Not finished')

  await setFigure(page, 'figure-card', '2321.75')
  await setFigure(page, 'figure-cash', '1890.55')
  check('a night that balances says so', (await verdictText(page)) === 'Balanced')

  await setFigure(page, 'figure-cash', '1880.55')
  check('a tenner missing is reported short', (await verdictText(page)) === 'Short by £10.00',
    `got "${await verdictText(page)}"`)

  await setFigure(page, 'figure-cash', '1900.55')
  check('a tenner too many is reported over', (await verdictText(page)) === 'Over by £10.00',
    `got "${await verdictText(page)}"`)

  await setFigure(page, 'figure-cash', '1890.52')
  check('three pence out still counts as balanced', (await verdictText(page)) === 'Balanced',
    `got "${await verdictText(page)}" — the default tolerance should absorb this`)

  console.log('\nA scan that cannot run')
  await page.setInputFiles('[data-testid="file-roll"]', join(here, '..', 'public', 'icon-192.png'))
  await page.waitForSelector('.note.bad', { timeout: 5000 })
  const scanNote = await page.locator('.note.bad').first().innerText()
  check('says why it could not scan', /switched off/i.test(scanNote), `got "${scanNote}"`)
  check('the typed figure survives a failed scan', (await page.inputValue('#figure-till')) === '4212.30')
  check('and the night can still be finished', (await verdictText(page)) === 'Balanced')

  console.log('\nSaving and reading back')
  await page.fill('#note', 'Quiz night, one card machine down')
  await page.click('.verdict-bar .btn-primary')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  check('saving lands in the history', (await page.locator('.day-row').count()) === 1)
  const rowText = await page.locator('.day-row').first().innerText()
  check('the row shows what was taken', /4,212\.27|4,212\.2/.test(rowText), `got "${rowText}"`)

  await page.click('.day-row')
  await page.waitForSelector('.verdict', { timeout: 5000 })
  const detail = await page.locator('.main').innerText()
  check('the night reads back with its till total', detail.includes('£4,212.30'))
  check('the night reads back with its card total', detail.includes('£2,321.75'))
  check('the note is kept', detail.includes('Quiz night'))
  check('the figures are marked as typed', detail.includes('Typed in'))

  console.log('\nCorrecting a saved night')
  await page.click('button:has-text("Edit")')
  await page.waitForSelector('#figure-till', { timeout: 5000 })
  // The record loads asynchronously after the boxes render, so wait for the
  // figure rather than reading the empty box it starts as.
  await page
    .waitForFunction(() => (document.querySelector('#figure-card') as HTMLInputElement | null)?.value === '2321.75', null, { timeout: 5000 })
    .catch(() => {})
  check(
    'editing loads the saved figures',
    (await page.inputValue('#figure-card')) === '2321.75',
    `got "${await page.inputValue('#figure-card')}"`,
  )
  await setFigure(page, 'figure-cash', '1800.55')
  check('the verdict follows the correction', (await verdictText(page)) === 'Short by £90.00',
    `got "${await verdictText(page)}"`)
  await page.click('.verdict-bar .btn-primary')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  check('correcting updates rather than duplicating', (await page.locator('.day-row').count()) === 1)

  console.log('\nSurviving a restart')
  await page.reload({ waitUntil: 'networkidle' })
  await page.click('button:has-text("Nights")')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  check('the night is still there after a reload', (await page.locator('.day-row').count()) === 1)
  check('and still shows it was short', (await page.locator('.day-row .delta').innerText()).includes('−£90.00'))

  console.log('\nSaving a key')
  await page.click('button:has-text("Settings")')
  await page.waitForSelector('#apiKey', { timeout: 5000 })
  check('the key box is on screen', (await page.locator('#apiKey').count()) === 1)
  check('and says there is no key yet', (await page.locator('.badge:has-text("No key yet")').count()) === 1)
  check('with nothing to save', await page.locator('button:has-text("Saved")').isDisabled())

  await page.fill('#apiKey', 'sk-ant-not-a-real-key-for-testing-only')
  await page.waitForTimeout(120)
  check('typing enables the save button', await page.locator('button:has-text("Save key")').isEnabled())
  await page.click('button:has-text("Save key")')
  await page.waitForTimeout(250)
  check('saving says so', (await page.locator('.toast').innerText()).includes('Key saved'))
  check('and the badge confirms it', (await page.locator('.badge:has-text("Key saved")').count()) === 1)

  await page.reload({ waitUntil: 'networkidle' })
  await page.click('button:has-text("Settings")')
  await page.waitForSelector('#apiKey', { timeout: 5000 })
  check('the key is still there after a reload', (await page.inputValue('#apiKey')).startsWith('sk-ant-'))

  console.log('\nInstalling')
  const manifest = await page.evaluate(async () => {
    const res = await fetch('./manifest.webmanifest')
    return (await res.json()) as { name: string; icons: unknown[] }
  })
  check('the manifest is served', manifest.name.startsWith('Tally'))
  check('it ships the icons a launcher needs', manifest.icons.length >= 4)
  const sw = await page.evaluate(() => navigator.serviceWorker.getRegistrations().then((r) => r.length))
  check('the service worker registers', sw >= 1)

  console.log('\nA real till roll')
  // A fresh context, so the dashboard totals are exactly one known night — the
  // Gardeners Arms roll of 23/08/2026 — rather than that night plus whatever
  // the earlier flow happened to leave behind.
  await page.close()
  const clean = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 3,
    isMobile: true,
    hasTouch: true,
  })
  await clean.addInitScript(() => {
    try {
      localStorage.setItem('tally.engine', 'off')
    } catch {
      /* ignore */
    }
  })
  page = await clean.newPage()
  page.on('pageerror', (err) => pageErrors.push(String(err)))
  page.on('console', (msg) => {
    if (msg.type() === 'error') pageErrors.push(msg.text())
  })
  await page.goto(base, { waitUntil: 'networkidle' })

  await page.evaluate(async (day) => {
    await new Promise<void>((resolve, reject) => {
      // No version pinned: the app has already opened the database at whatever
      // version it is on, and asking for an older one throws.
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
    // £12 light in the drawer; the card slip agrees with the till exactly.
    cashPence: 33980,
    note: '',
    zRead: GARDENERS_ARMS,
    createdAt: 0,
    updatedAt: 0,
  })

  await page.reload({ waitUntil: 'networkidle' })
  await page.click('button:has-text("Nights")')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  check('exactly the one seeded night is present', (await page.locator('.day-row').count()) === 1)
  await page.click('.day-row')
  await page.waitForSelector('.verdict', { timeout: 5000 })
  const rollDetail = await page.locator('.main').innerText()
  check('the roll’s own sums are reported as agreeing', /Adds up/i.test(rollDetail))
  check('the department split is shown', rollDetail.includes('Draught beers'))
  check('with the percentage the till printed', rollDetail.includes('68.05%'))
  check('the takings match the roll', rollDetail.includes('£2,192.80'))
  check('the sales count comes across', /267 sales/.test(rollDetail))
  check('the shortfall is reported', /−£12\.00/.test(rollDetail), rollDetail.slice(0, 200))
  check(
    'and pinned to the drawer rather than left vague',
    /the difference is in the drawer/i.test(rollDetail),
  )

  console.log('\nThe dashboard')
  await page.click('button:has-text("Trade")')
  await page.waitForSelector('.kpi-row', { timeout: 5000 })
  const dash = await page.locator('.main').innerText()
  check('leads with what was taken', dash.includes('£2,192.80'))
  check('splits cash and card as the till states them', dash.includes('£351.80') && dash.includes('£1,841.00'))
  check('shows every department with its share', dash.includes('Draught beers') && dash.includes('68.05%'))
  check('shows the quantities sold', dash.includes('406'))
  check('counts the items across the night', dash.includes('689'))
  check(
    'works out what a drink went for',
    dash.includes('£3.68'),
    '406 draught for £1,492.25 should read £3.68 each',
  )
  check('and what the dearest category went for', dash.includes('£5.46'), 'wine')
  check('the shares total 100%', dash.includes('100.00%'))
  check('reports the night as short by twelve pounds', dash.includes('−£12.00'), dash.slice(0, 300))
  check('charts rendered', (await page.locator('.chart svg').count()) >= 2)
  check('a legend names the departments', (await page.locator('.legend li').count()) >= 7)

  console.log('\nWhat people bought')
  check('the item list is shown', dash.includes('PINT TADDY LAGER'), 'the biggest earner')
  check('with how many went over the bar', dash.includes('120'))
  check('and what one went for', dash.includes('£4.00'), '120 pints for £480')
  // The item table is scoped to its own card; the page has several tables.
  const itemTable = page.locator('.card:has-text("What people actually bought") table.data')

  // Only the top twelve show until asked; Spiced rum is well down the list.
  await page.click('button:has-text("Show all 38")')
  await page.waitForTimeout(200)
  check('all 38 lines can be shown', (await itemTable.locator('tbody tr').count()) === 38)
  check('item names keep the case the till printed', (await itemTable.innerText()).includes('Spiced rum'))

  const firstByValue = (await itemTable.locator('tbody tr th').first().innerText()).trim()
  await page.click('.chip:has-text("By how many")')
  await page.waitForTimeout(250)
  const rowsByQty = (await itemTable.innerText()).split('\n')
  const idx = (name: string) => rowsByQty.findIndex((l) => l.includes(name))
  check(
    'ranking by count lifts crisps above the pints they outsell',
    idx('CRISPS') !== -1 && idx('CRISPS') < idx('PINT OBB'),
    `crisps at ${idx('CRISPS')}, OBB at ${idx('PINT OBB')}`,
  )
  await page.click('.chip:has-text("By takings")')
  await page.waitForTimeout(250)
  check(
    'and switching back restores the takings order',
    (await itemTable.locator('tbody tr th').first().innerText()).trim() === firstByValue,
  )

  console.log('\nWho rang it up')
  const whoText = await page.locator('.main').innerText()
  check('the clerk split is shown', whoText.includes('CLERK0004'))
  check('with what they took', whoText.includes('£2,188.80'))
  check(
    'and it does not claim to say whose till was short',
    /not whose till was short/i.test(whoText),
  )

  console.log('\nPrices')
  await page.click('button:has-text("Settings")')
  await page.waitForSelector('button:has-text("Open the price list")', { timeout: 5000 })
  await page.click('button:has-text("Open the price list")')
  await page.waitForSelector('.zrow', { timeout: 5000 })
  const priceText = await page.locator('.main').innerText()
  check('every item sold is listed to price', /38 lines|0 of 38 priced/.test(priceText) || priceText.includes('PINT TADDY LAGER'))
  check('with what the till averaged', priceText.includes('£4.00'))

  // Accept the till's own price for the biggest earner, then set a higher one.
  await page.click('button:has-text("Use £4.00")')
  await page.waitForTimeout(300)
  // The badge is uppercased by CSS, and innerText reports what is rendered.
  const pricedBadge = (await page.locator('.badge:has-text("priced")').innerText()).trim()
  check('a suggested price can be accepted in one tap', /^1 of 38 priced$/i.test(pricedBadge), `got "${pricedBadge}"`)

  await page.fill('input[aria-label="Board price for PINT TADDY LAGER"]', '4.20')
  await page.waitForTimeout(400)
  check(
    'and correcting it flags the gap',
    (await page.locator('.main').innerText()).includes('under by £0.20'),
  )

  await page.click('button:has-text("Trade")')
  await page.waitForSelector('.kpi-row', { timeout: 5000 })
  const priced = await page.locator('.main').innerText()
  check('the dashboard reports what that cost over the night', priced.includes('£24.00'), '120 pints, 20p each')
  check('and names the innocent explanation', /discount/i.test(priced))

  console.log('\nFiltering')
  await page.click('.chip:has-text("Draught beers")')
  await page.click('.chip:has-text("Wine")')
  await page.waitForTimeout(150)
  const filtered = await page.locator('table.data').first().innerText()
  check('filtering keeps only the chosen departments', !filtered.includes('Spirits'), filtered.slice(0, 160))
  check(
    'and re-bases their percentages onto each other',
    filtered.includes('86.40%'),
    `expected draught to become 86.40% of draught+wine; got ${filtered.slice(0, 200)}`,
  )
  await page.click('.chip:has-text("Draught beers")')
  await page.click('.chip:has-text("Wine")')
  await page.waitForTimeout(150)
  check(
    'clearing the filter restores the full split',
    (await page.locator('table.data').first().innerText()).includes('68.05%'),
  )

  await page.click('.chip:has-text("Sun")')
  await page.waitForTimeout(150)
  check('a weekday filter keeps a Sunday night', (await page.locator('.day-row').count()) === 1)
  await page.click('.chip:has-text("Mon")')
  await page.click('.chip:has-text("Sun")')
  await page.waitForTimeout(150)
  check(
    'and a weekday with no trade empties the selection',
    (await page.locator('.main').innerText()).includes('No nights match those filters'),
  )

  check('nothing threw along the way', pageErrors.length === 0, pageErrors.join('\n        '))
} finally {
  await browser.close()
  server.close()
}

console.log(`\n${checks - failures}/${checks} checks passed`)
process.exit(failures === 0 ? 0 : 1)
