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
    // The cellar stock take is already in, as far as these tests are
    // concerned: they build their own cellar and judge their own figures.
    localStorage.setItem('tally.seeded', 'cellar-2026-09-16')
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

  console.log('\nCounting the drawer out')
  await page.click('button[aria-label="Count the drawer out in notes and coins"]')
  await page.waitForSelector('input[aria-label="How many £20"]', { timeout: 5000 })
  // Ninety-four twenties, a five, five pounds in coin, and some silver.
  await page.fill('input[aria-label="How many £20"]', '94')
  await page.fill('input[aria-label="How many £5"]', '1')
  await page.fill('input[aria-label="How many £1"]', '5')
  await page.fill('input[aria-label="How many 50p"]', '3')
  await page.fill('input[aria-label="How many 5p"]', '1')
  await page.waitForTimeout(200)
  check(
    'the counter adds the drawer up',
    (await page.locator('.counter').innerText()).includes('£1,891.55'),
    (await page.locator('.counter').innerText()).slice(-120),
  )
  await page.click('button:has-text("Use £1,891.55")')
  await page.waitForTimeout(200)
  check(
    'and puts the total in the cash box',
    (await page.inputValue('#figure-cash')) === '1891.55',
    `got "${await page.inputValue('#figure-cash')}"`,
  )

  // Back to the balancing figure for the rest of the run.
  await setFigure(page, 'figure-cash', '1890.52')
  check('the night still balances after counting', (await verdictText(page)) === 'Balanced')

  console.log('\nThe float')
  // The fault this exists to prevent: leave £200 in the drawer and, without
  // saying so, every night reads exactly £200 over — consistently enough that
  // it looks like the pub doing well rather than like a bug.
  // Exactly £200 on top of the balancing figure of £1,890.55.
  await setFigure(page, 'figure-cash', '2090.55')
  check('an unexplained float reads as over', (await verdictText(page)) === 'Over by £200.00',
    `got "${await verdictText(page)}"`)

  await page.fill('#figure-float', '200')
  await page.waitForTimeout(200)
  check(
    'declaring the float balances the night',
    (await verdictText(page)) === 'Balanced',
    `got "${await verdictText(page)}" — the float must come off before anything reconciles`,
  )
  check(
    'and the takings are shown apart from the drawer',
    (await page.locator('.main').innerText()).includes('£2,090.55 counted, less £200.00 float'),
  )

  await page.fill('#figure-float', '3000')
  await page.waitForTimeout(200)
  check(
    'a float bigger than the drawer is called out',
    (await page.locator('.note.bad').first().innerText()).includes('more than was counted'),
  )

  await page.fill('#figure-float', '')
  await setFigure(page, 'figure-cash', '1890.52')
  await page.waitForTimeout(150)
  check('clearing the float restores the plain count', (await verdictText(page)) === 'Balanced')

  console.log('\nA scan that cannot run')
  // Said before the photographs are taken, not after three identical failures.
  const preflight = await page.locator('[data-testid="roll-preflight"]').first().innerText()
  check('says up front that nothing will read the roll', /switched off/i.test(preflight), `got "${preflight}"`)

  await page.setInputFiles('[data-testid="file-roll"]', join(here, '..', 'public', 'icon-192.png'))
  await page.waitForSelector('.shots li', { timeout: 5000 })
  // The picture of the receipt is the record whether or not anything could
  // read it, so it is kept and said to be waiting rather than thrown away.
  const kept = await page.locator('.shots li').first().innerText()
  check('the photograph is kept anyway', /not read yet/i.test(kept), `got "${kept}"`)
  check('the typed figure survives', (await page.inputValue('#figure-till')) === '4212.30')
  check('and the night can still be finished', (await verdictText(page)) === 'Balanced')

  console.log('\nThrowing one photograph away')
  await page.setInputFiles('[data-testid="file-roll"]', [
    join(here, '..', 'public', 'icon-192.png'),
    join(here, '..', 'public', 'icon-512.png'),
  ])
  await page.waitForTimeout(250)
  check('all three are held', (await page.locator('.shots li').count()) === 3)
  await page.click('[data-testid="drop-shot-1"]')
  await page.waitForTimeout(200)
  check('dropping one leaves the others', (await page.locator('.shots li').count()) === 2)
  check('and the typed figure is untouched', (await page.inputValue('#figure-till')) === '4212.30')
  await page.click('[data-testid="drop-shot-1"]')
  await page.waitForTimeout(200)
  check('down to one', (await page.locator('.shots li').count()) === 1)

  console.log('\nSaving and reading back')
  // Saved with a float on, so the round trip through storage is covered: the
  // record keeps takings and float apart, and the night must come back showing
  // the drawer she actually counted.
  await page.fill('#figure-float', '200')
  await setFigure(page, 'figure-cash', '2090.55')
  await page.fill('#note', 'Quiz night, one card machine down')
  await page.click('.verdict-bar .btn-primary')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  check('saving lands in the history', (await page.locator('.day-row').count()) === 1)
  const rowText = await page.locator('.day-row').first().innerText()
  check('the row shows what was taken', /4,212\.30/.test(rowText), `got "${rowText}"`)

  await page.click('.day-row')
  await page.waitForSelector('.verdict', { timeout: 5000 })
  const detail = await page.locator('.main').innerText()
  check('the night reads back with its till total', detail.includes('£4,212.30'))
  check('the night reads back with its card total', detail.includes('£2,321.75'))
  check('the drawer is read back as counted', detail.includes('£2,090.55'), 'float included')
  check('with the float shown coming off it', /Less float/.test(detail) && detail.includes('−£200.00'))
  check('and the takings stated apart', detail.includes('£1,890.55'))
  check('the night still balanced', (await page.locator('.verdict .headline').first().innerText()).trim() === 'Balanced')
  check('the note is kept', detail.includes('Quiz night'))
  check('the figures are marked as typed', detail.includes('Typed in'))

  console.log('\nThe photographs, full screen')
  // The failed scan above still kept its photograph, and a kept photograph
  // must be readable — that is the whole point of keeping it.
  await page.waitForSelector('.photo-thumb', { timeout: 5000 })
  check('the roll photograph is kept on the night', (await page.locator('.photo-thumb').count()) === 1)
  check('and labelled as the roll', (await page.locator('.photo-thumb span').innerText()).trim() === 'Till roll')
  await page.click('.photo-thumb')
  await page.waitForSelector('[data-testid="lightbox"]', { timeout: 5000 })
  check('tapping it opens the viewer', (await page.locator('[data-testid="lightbox"] img').count()) === 1)
  check('with the caption naming it', (await page.locator('.lb-caption').innerText()).trim() === 'Till roll')
  check('one photograph means no page-turn arrows', (await page.locator('.lb-nav').count()) === 0)

  // Double-tap zooms in; the stage says so with a class the cursor rides on.
  await page.locator('[data-testid="lightbox"] img').dblclick()
  await page.waitForTimeout(250)
  check('a double tap zooms in', (await page.locator('.lb-stage.zoomed').count()) === 1)
  await page.locator('[data-testid="lightbox"] img').dblclick()
  await page.waitForTimeout(250)
  check('and a second one zooms back out', (await page.locator('.lb-stage.zoomed').count()) === 0)

  await page.keyboard.press('Escape')
  await page.waitForSelector('[data-testid="lightbox"]', { state: 'detached', timeout: 5000 })
  check('escape puts it away', (await page.locator('[data-testid="lightbox"]').count()) === 0)
  await page.click('.photo-thumb')
  await page.waitForSelector('[data-testid="lightbox"]', { timeout: 5000 })
  await page.click('.lb-close')
  await page.waitForSelector('[data-testid="lightbox"]', { state: 'detached', timeout: 5000 })
  check('so does the close button, which is what a phone has', (await page.locator('[data-testid="lightbox"]').count()) === 0)

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
  check(
    'and the float comes back with them',
    (await page.inputValue('#figure-float')) === '200.00',
    `got "${await page.inputValue('#figure-float')}"`,
  )
  check(
    'with the drawer as it was counted, not the takings',
    (await page.inputValue('#figure-cash')) === '2090.55',
    `got "${await page.inputValue('#figure-cash')}" — the float must be added back for editing`,
  )
  // Float taken back out, so the rest of the run reads as it always did.
  await page.fill('#figure-float', '')
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
  // The row headlines what the till says was taken — the same figure Trade
  // and the year-end pack use — not what was counted. A night £90 short once
  // read as having taken £90 less than every other screen said it had.
  check(
    'and headlines what the till took, not what was counted',
    (await page.locator('.day-row .takings').innerText()).includes('£4,212.30'),
    `got "${await page.locator('.day-row .takings').innerText()}"`,
  )

  console.log('\nAsking without a key')
  // Deliberately before any key is saved: the honest answer is a pointer to
  // Settings, not a spinner that dies quietly.
  await page.click('button:has-text("Trade")')
  await page.waitForSelector('.card:has(h2:text("Ask the till"))', { timeout: 5000 })
  await page.fill('input[aria-label="Ask a question about the records"]', 'What did we take last night?')
  await page.click('.ask-row .btn-primary')
  await page.waitForSelector('.card:has(h2:text("Ask the till")) .note.bad', { timeout: 5000 })
  const askErr = await page.locator('.card:has(h2:text("Ask the till")) .note.bad').innerText()
  check('it points at the key in Settings', /API key in Settings/.test(askErr), askErr)
  check(
    'the question is not lost to the failure',
    (await page.inputValue('input[aria-label="Ask a question about the records"]')) === 'What did we take last night?',
  )

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
      // The cellar stock take is already in, as far as these tests are
      // concerned: they build their own cellar and judge their own figures.
      localStorage.setItem('tally.seeded', 'cellar-2026-09-16')
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

  console.log('\nThe cellar')
  await page.click('button:has-text("Cellar")')
  await page.waitForSelector('button:has-text("Build the cellar from the till")', { timeout: 5000 })
  await page.click('button:has-text("Build the cellar from the till")')
  await page.waitForTimeout(600)
  const built = await page.locator('.main').innerText()
  check('the cellar is built from the till’s own item list', /cellar lines set up|lines/i.test(built))
  check('with the beers as their own lines', built.includes('Taddy Lager'))

  await page.click('.chip:has-text("Set up")')
  await page.waitForTimeout(300)
  const setup = page.locator('.card:has-text("What each sale pours") table.data')
  const pourText = await setup.innerText()
  // Each pour is a box now, so the amount is its value and the measure the word
  // beside it.
  const takes = async (line: string) => ({
    amount: await page.locator(`input[aria-label="${line} takes"]`).inputValue(),
    measure: (await page.locator(`tr:has(input[aria-label="${line} takes"]) .pour-each small`).innerText()).trim(),
  })
  const pint = await takes('PINT TADDY LAGER')
  check('a pint pours a pint', pint.amount === '1' && pint.measure === 'pint', JSON.stringify(pint))
  const half = await takes('HALF TADDY LAGER')
  check('a half pours half of one', half.amount === '0.5' && half.measure === 'pint', JSON.stringify(half))
  const shot = await takes('VODKA')
  check('a spirit pours the house measure', shot.amount === '30' && shot.measure === 'ml', JSON.stringify(shot))
  check('a measured wine pours its measure', pourText.includes('175ML HOUSE WINE'))

  // Book two firkins of Taddy in, then look at what should be left.
  await page.click('.chip:has-text("Delivery in")')
  await page.waitForSelector('input[aria-label="Taddy Lager delivered"]', { timeout: 5000 })
  await page.fill('input[aria-label="Taddy Lager delivered"]', '144')
  await page.click('button:has-text("Book the delivery in")')
  await page.waitForTimeout(600)
  const levels = await page.locator('.main').innerText()
  check('the delivery is booked in', /144 pints/.test(levels), levels.slice(0, 300))
  check(
    'and the night’s pouring is already taken off it',
    /129\.5 pints/.test(levels),
    '120 pints plus 19 halves',
  )
  check('leaving what should be in the cellar', /14\.5 pints/.test(levels), '144 in, 129.5 out')

  console.log('\nThe rota')
  await page.click('button:has-text("Rota")')
  await page.waitForSelector('button:has-text("Add the first person")', { timeout: 5000 })
  await page.click('button:has-text("Add the first person")')
  await page.waitForSelector('#person-name', { timeout: 5000 })

  check(
    'adding somebody asks for a name and a rate, not for hours they "usually" work',
    (await page.locator('#person-start').count()) === 0 && (await page.locator('#person-end').count()) === 0,
  )

  await page.fill('#person-name', 'Kelly')
  await page.fill('#person-rate', '12.21')
  await page.click('button:has-text("Add to the rota")')
  await page.waitForTimeout(300)
  check('someone can be put on the books', (await page.locator('.badge:has-text("1 person")').count()) === 1)

  await page.fill('#person-name', 'Dave')
  await page.click('button:has-text("Add to the rota")')
  await page.waitForTimeout(300)
  check('and a second', (await page.locator('.badge:has-text("2 people")').count()) === 1)

  await page.click('.chip:has-text("The week")')
  await page.waitForSelector('.day-card', { timeout: 5000 })
  check('the week shows seven nights', (await page.locator('.day-card').count()) === 7)
  check('and starts with nobody on', (await page.locator('.day-nobody').count()) === 7)

  // Put both on the Saturday — the fifth row, Monday being the first.
  await page.locator('.day-open').nth(5).click()
  await page.waitForTimeout(200)
  await page.locator('.day-edit .chip:has-text("Kelly")').click()
  await page.waitForTimeout(200)
  await page.locator('.day-edit .chip:has-text("Dave")').click()
  await page.waitForTimeout(250)
  const saturday = await page.locator('.day-card').nth(5).innerText()
  check('both go on the night', saturday.includes('Kelly') && saturday.includes('Dave'), saturday.slice(0, 120))
  check('and the hours are totted up', /11h/.test(saturday), `got "${saturday}"`)

  const wages = await page.locator('.day-edit').innerText()
  check('wages count only the person with a rate', wages.includes('£67.16'), wages.slice(-160))

  console.log('\nThe hours belong to the night')
  check('the night opens with its hours in a box', (await page.locator('.night-hours input').count()) === 2)
  check(
    'starting at six until close, since nothing else has been rostered',
    (await page.locator('.night-hours input').first().inputValue()) === '18:00',
  )
  // Four o'clock start: everyone already on the night moves with it, so the
  // box is never describing hours the crew is not actually on.
  await page.locator('.night-hours input').first().fill('16:00')
  await page.waitForTimeout(400)
  const longer = await page.locator('.day-edit').innerText()
  check('changing it moves everybody on that night', /15h/.test(longer), longer.slice(-200))
  check('and the wages follow the hours', longer.includes('£91.58'), longer.slice(-200))
  await page.locator('.night-hours input').first().fill('18:00')
  await page.waitForTimeout(400)
  check(
    'putting it back restores the night',
    (await page.locator('.day-edit').innerText()).includes('£67.16'),
  )

  console.log('\nThe week’s wages, sent on')
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.evaluate(() => {
    ;(navigator as { share?: unknown }).share = undefined
  })
  await page.click('button:has-text("Send the week")')
  await page.waitForTimeout(400)
  const wagesText = await page.evaluate(() => navigator.clipboard.readText())
  check('the wages summary names the week', /Wages — week beginning/.test(wagesText), wagesText.slice(0, 60))
  check('each person gets hours, shifts and money', /Kelly\s+5h 30m over 1 shift · £67\.16/.test(wagesText))
  check('a missing rate is said, not priced at nothing', /Dave\s+5h 30m over 1 shift — no rate set/.test(wagesText))
  check('the hours foot to the week', /Hours in total\s+11h/.test(wagesText))
  check('and it says it is a cross-check, not the payroll', /cross-check for payroll/.test(wagesText))
  check(
    'the button says what it did',
    (await page.locator('p:has-text("paste it to whoever runs payroll")').count()) === 1,
  )

  // The whole week, copied forward.
  await page.locator('.week-nav button[aria-label="The week after"]').click()
  await page.waitForTimeout(250)
  check('the next week starts empty', (await page.locator('.day-nobody').count()) === 7)
  await page.click('button:has-text("Copy last week")')
  await page.waitForTimeout(400)
  check('copying last week brings the shifts forward', (await page.locator('.day-nobody').count()) === 6)
  check('and says how many it moved', (await page.locator('.toast').innerText()).includes('2 shifts'))

  await page.reload({ waitUntil: 'networkidle' })
  await page.click('button:has-text("Rota")')
  await page.waitForSelector('.day-card', { timeout: 5000 })
  check('the rota survives a restart', (await page.locator('.day-nobody').count()) === 6)

  console.log('\nThe rota, person by person')
  // The other way round: pick a person, tap the days they are on, and set the
  // hours on each of those days. This week — whichever week next week is when
  // this runs, so the dates come off the chips — has only the copied
  // Saturday on it so far.
  await page.click('.chip:has-text("By person")')
  await page.waitForSelector('.person-week', { timeout: 5000 })
  check('everyone on the books gets their own week', (await page.locator('.person-week').count()) === 2)
  const kellyWeek = page.locator('.person-week:has-text("Kelly")')
  const kellyChips = kellyWeek.locator('.day-chips .chip')
  const wedDate = ((await kellyChips.nth(2).getAttribute('aria-label')) ?? '').replace('Kelly on ', '')
  const satDate = ((await kellyChips.nth(5).getAttribute('aria-label')) ?? '').replace('Kelly on ', '')
  check('the week runs Monday to Sunday, a chip a day', (await kellyChips.count()) === 7 && /^\d{4}-\d\d-\d\d$/.test(wedDate))
  check('the night she is already on is ticked', (await kellyWeek.locator('.chip[aria-pressed="true"]').count()) === 1)
  await kellyChips.nth(2).click()
  await page.waitForTimeout(300)
  check('tapping a day puts her on it', (await kellyWeek.locator('.chip[aria-pressed="true"]').count()) === 2)
  check(
    'at the hours the rota was last set to',
    (await page.locator(`input[aria-label="Kelly starts on ${wedDate}"]`).inputValue()) === '18:00',
  )
  // A lunchtime instead: twelve till three, on that day alone.
  await page.locator(`input[aria-label="Kelly starts on ${wedDate}"]`).fill('12:00')
  await page.locator(`input[aria-label="Kelly finishes on ${wedDate}"]`).fill('15:00')
  await page.waitForTimeout(400)
  const kellyText = await kellyWeek.innerText()
  check('the day’s hours are its own', /Wednesday\n[^\n]* · 3h\b/.test(kellyText), kellyText.slice(0, 200))
  check('and her week is totted up', /2 nights · 8h 30m · £103\.79/.test(kellyText), kellyText.slice(0, 120))
  check(
    'the Saturday kept its own hours',
    (await page.locator(`input[aria-label="Kelly starts on ${satDate}"]`).inputValue()) === '18:00',
  )
  // The night view shows the same thing, because it is the same shift.
  await page.click('.chip:has-text("By night")')
  await page.waitForSelector('.day-card', { timeout: 5000 })
  const wedCard = await page.locator('.day-card').nth(2).innerText()
  check('the night view agrees', wedCard.includes('Kelly') && /3h/.test(wedCard), wedCard.slice(0, 120))
  check('and Wednesday is no longer empty', (await page.locator('.day-nobody').count()) === 5)

  // Back to the week of the seeded night, so the dashboard has a rostered
  // night to report on rather than a rota that never overlaps the trade.
  while ((await page.locator('.week-when strong').innerText()).trim() !== '17 Aug – 23 Aug') {
    await page.locator('.week-nav button[aria-label="The week before"]').click()
    await page.waitForTimeout(120)
  }
  await page.locator('.day-open').nth(6).click() // the Sunday
  await page.waitForTimeout(200)
  await page.locator('.day-edit .chip:has-text("Kelly")').click()
  await page.waitForTimeout(300)

  await page.click('button:has-text("Trade")')
  await page.waitForSelector('.kpi-row', { timeout: 5000 })
  const onTonight = await page.locator('.main').innerText()
  check('the dashboard reports who was on', onTonight.includes('Who was on'), onTonight.slice(0, 120))
  check('with the hours they worked', /5h 30m/.test(onTonight))
  check('and the wage bill for the night', onTonight.includes('£67.16'), '5.5 hours at £12.21')
  check(
    'and withholds a comparison it cannot support',
    onTonight.includes('too soon'),
    'one night is nowhere near enough to compare anybody',
  )

  await page.click('button:has-text("Nights")')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  await page.click('.day-row')
  await page.waitForSelector('.verdict', { timeout: 5000 })
  const nightCrew = await page.locator('.main').innerText()
  check('the night itself names who worked it', nightCrew.includes('Who was on') && nightCrew.includes('Kelly'))

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

  console.log('\nWhat it costs, and what it makes')
  await page.click('button:has-text("Cellar")')
  await page.waitForSelector('.chip:has-text("What it costs")', { timeout: 5000 })
  await page.click('.chip:has-text("What it costs")')
  await page.waitForSelector('input[aria-label="Taddy Lager cost"]', { timeout: 5000 })

  console.log('\nHow each kind of drink is counted')
  // Straight off the till's own names, into the four ways there are: the tap
  // beers in pints, anything poured out of a bottle in millilitres, the
  // bottled drinks by the bottle, everything else in units.
  check(
    'a tap beer is counted in pints',
    (await page.locator('select[aria-label="Taddy Lager measured in"]').inputValue()) === 'pint',
  )
  check(
    'a spirit is counted in millilitres, not in shots',
    (await page.locator('select[aria-label="Vodka measured in"]').inputValue()) === 'ml',
  )
  check(
    'and there is no measure to type against a line at all',
    (await page.locator('input[aria-label="Vodka millilitres per serving"]').count()) === 0,
  )
  check(
    'wine poured at three measures is millilitres of one bottle',
    (await page.locator('select[aria-label="Rose measured in"]').inputValue()) === 'ml',
  )
  check(
    'the alcohol-free is counted by the bottle, not by its 550ml',
    (await page.locator('select[aria-label="alc free measured in"]').inputValue()) === 'bottle',
  )
  check(
    'and so is the juice',
    (await page.locator('select[aria-label="Orange Juice measured in"]').inputValue()) === 'bottle',
  )
  check(
    'the crisps are units',
    (await page.locator('select[aria-label="Crisps measured in"]').inputValue()) === 'unit',
  )
  check(
    'there are four ways to count and no more',
    (await page.locator('select[aria-label="Vodka measured in"] option').count()) === 4,
  )

  check(
    'a bottled line is not merged into the cask it shares a name with',
    (await page.locator('select[aria-label="pure brew (bottled) measured in"]').inputValue()) === 'bottle',
    'BOT PURE BREW is counted off a shelf; PINT PURE BREW is poured out of a cask',
  )

  console.log('\nThe shots, worked out of the millilitres')
  // The bar pours 30ml, so a bottle counted at 350ml is eleven and a bit — and
  // the app is the one that works that out.
  await page.click('.chip:has-text("Stock take")')
  await page.waitForSelector('input[aria-label="Vodka counted"]', { timeout: 5000 })
  await page.fill('input[aria-label="Vodka counted"]', '350')
  await page.waitForTimeout(250)
  check(
    'a count in millilitres says what it is in shots',
    /350 ml is 11\.7 shots/.test(await page.locator('.main').innerText()),
    (await page.locator('.zrow:has-text("Vodka")').innerText()).slice(0, 120),
  )
  // What the till takes off the line is the truth about the measure, so a line
  // the till pours 25ml of reads back in 25s — and not a millilitre in the
  // cellar moves when it changes.
  await page.click('.chip:has-text("Set up")')
  await page.waitForSelector('input[aria-label="VODKA takes"]', { timeout: 5000 })
  await page.fill('input[aria-label="VODKA takes"]', '25')
  await page.waitForTimeout(400)
  await page.click('.chip:has-text("Stock take")')
  await page.waitForSelector('input[aria-label="Vodka counted"]', { timeout: 5000 })
  await page.fill('input[aria-label="Vodka counted"]', '350')
  await page.waitForTimeout(250)
  check(
    'the measure the till pours is the one it reads back in',
    /350 ml is 14 shots/.test(await page.locator('.main').innerText()),
    (await page.locator('.zrow:has-text("Vodka")').innerText()).slice(0, 120),
  )
  await page.click('.chip:has-text("Set up")')
  await page.waitForSelector('input[aria-label="VODKA takes"]', { timeout: 5000 })
  await page.fill('input[aria-label="VODKA takes"]', '30')
  await page.waitForTimeout(400)
  // The house measure, for a line the till has not sold anything off yet.
  await page.fill('input[aria-label="The house measure"]', '25')
  await page.waitForTimeout(400)
  await page.reload({ waitUntil: 'networkidle' })
  await page.click('button:has-text("Cellar")')
  await page.waitForSelector('.chip:has-text("Set up")', { timeout: 5000 })
  await page.click('.chip:has-text("Set up")')
  await page.waitForSelector('input[aria-label="The house measure"]', { timeout: 5000 })
  check(
    'the house measure is hers to set, and is kept',
    (await page.locator('input[aria-label="The house measure"]').inputValue()) === '25',
  )
  await page.fill('input[aria-label="The house measure"]', '30')
  await page.waitForTimeout(400)
  await page.click('.chip:has-text("What it costs")')
  await page.waitForSelector('select[aria-label="Crisps measured in"]', { timeout: 5000 })

  console.log('\nChanging how a line is counted')
  await page.selectOption('select[aria-label="Crisps measured in"]', 'bottle')
  await page.waitForTimeout(400)
  check(
    'a counted line can be recounted by hand',
    (await page.locator('select[aria-label="Crisps measured in"]').inputValue()) === 'bottle',
  )
  check(
    'and going from counted to poured says the unit and cost went with it',
    /set its unit and cost again/.test(
      await (async () => {
        await page.selectOption('select[aria-label="Crisps measured in"]', 'ml')
        await page.waitForTimeout(400)
        return page.locator('.toast').innerText()
      })(),
    ),
  )
  await page.selectOption('select[aria-label="Crisps measured in"]', 'unit')
  await page.waitForTimeout(400)

  console.log('\nWhat each sale takes off the cellar')
  await page.click('.chip:has-text("Set up")')
  await page.waitForSelector('input[aria-label="VODKA takes"]', { timeout: 5000 })
  check(
    'a single takes the house measure off the cellar, in millilitres',
    (await page.locator('input[aria-label="VODKA takes"]').inputValue()) === '30',
  )
  check('and the table says which unit that is', (await page.locator('.pour-each small').first().innerText()).length > 0)
  check(
    'and no sale takes nought off the cellar',
    (await page.locator('.pour-each input').evaluateAll((boxes) =>
      boxes.every((b) => (b as HTMLInputElement).value.trim() !== '0'),
    )),
    'a bottle taken off a line counted in pints rounds to nought, silently',
  )
  // A till line that is really a double: 60ml off the cellar, not 30.
  await page.fill('input[aria-label="VODKA takes"]', '60')
  await page.waitForTimeout(400)
  await page.reload({ waitUntil: 'networkidle' })
  await page.click('button:has-text("Cellar")')
  await page.waitForSelector('.chip:has-text("Set up")', { timeout: 5000 })
  await page.click('.chip:has-text("Set up")')
  await page.waitForSelector('input[aria-label="VODKA takes"]', { timeout: 5000 })
  check(
    'a pour set by hand survives a restart',
    (await page.locator('input[aria-label="VODKA takes"]').inputValue()) === '60',
  )
  await page.fill('input[aria-label="VODKA takes"]', '30')
  await page.waitForTimeout(400)
  check(
    'and can be put back',
    (await page.locator('input[aria-label="VODKA takes"]').inputValue()) === '30',
  )
  await page.click('.chip:has-text("What it costs")')
  await page.waitForSelector('input[aria-label="Taddy Lager cost"]', { timeout: 5000 })

  // A firkin of Taddy: £95 for 72 pints, as the invoice charges it.
  // Deliberately price first, then
  // size: a price with no size yet is not a cost, and an earlier version threw
  // it away instead of waiting for the size to arrive.
  await page.fill('input[aria-label="Taddy Lager cost"]', '95.00')
  await page.fill('input[aria-label="Taddy Lager servings per container"]', '72')
  await page.waitForTimeout(400)
  const costed = await page.locator('.main').innerText()
  check('a barrel price becomes a price per pint', costed.includes('£1.32'), '£95 across 72 pints')
  check(
    'and the margin is worked out against the board price',
    /68\.6% GP/.test(costed),
    'the board says £4.20 and the firkin makes each pint £1.32',
  )

  // A firkin is 40,896ml however the line is measured. The box beside it shows
  // that divided into servings and rounded, and an earlier version multiplied
  // the rounded figure back out — shaving millilitres off the barrel every time
  // the price next to it was touched.
  await page.selectOption('select[aria-label="Taddy Lager measured in"]', 'ml')
  await page.waitForTimeout(400)
  check(
    'the barrel re-reads itself in the new unit',
    (await page.locator('input[aria-label="Taddy Lager servings per container"]').inputValue()) === String(72 * 568),
    `got "${await page.locator('input[aria-label="Taddy Lager servings per container"]').inputValue()}" — a firkin is ${72 * 568}ml`,
  )
  // A real edit to the price beside it — the same value again would be no edit
  // at all, since nothing changed to react to.
  await page.fill('input[aria-label="Taddy Lager cost"]', '96.00')
  await page.waitForTimeout(500)

  // Read the stored figure rather than the box, and read it here, while the
  // line is still on the other measure: the box shows the barrel divided into
  // servings, so a barrel going astray is invisible there — and switching back
  // and touching the price again would put it right by accident, hiding it.
  const barrel = await page.evaluate(async () => {
    return await new Promise<number | null>((resolve) => {
      const req = indexedDB.open('tally')
      req.onsuccess = () => {
        const tx = req.result.transaction('stock', 'readonly')
        const get = tx.objectStore('stock').get('config')
        get.onsuccess = () => {
          const cfg = get.result as { items: Array<{ name: string; container?: { baseUnits: number } }> } | undefined
          resolve(cfg?.items.find((i) => i.name === 'Taddy Lager')?.container?.baseUnits ?? null)
        }
        get.onerror = () => resolve(null)
      }
      req.onerror = () => resolve(null)
    })
  })
  check(
    'a barrel is still exactly a barrel after being measured another way',
    barrel === 72 * 568,
    `got ${barrel}ml — a firkin is ${72 * 568}ml however the line is measured`,
  )

  await page.selectOption('select[aria-label="Taddy Lager measured in"]', 'pint')
  await page.waitForTimeout(400)
  await page.fill('input[aria-label="Taddy Lager cost"]', '95.00')
  await page.waitForTimeout(400)
  check(
    'and it still costs what the invoice said',
    (await page.locator('.card:has-text("Taddy Lager")').first().innerText()).includes('£1.32'),
  )

  // The cellar is worth something now.
  await page.click('.chip:has-text("What’s down there")')
  await page.waitForTimeout(400)
  const value = await page.locator('.main').innerText()
  check('the cellar is valued at what the stock cost', value.includes('Money in the cellar'), value.slice(0, 160))
  check(
    'at the right figure, right through from the delivery',
    value.includes('£19.13'),
    '144 pints in less 129.5 poured is 14.5 left, at £95 the firkin',
  )

  // The cellar runs itself off the receipts: what the till poured comes off,
  // and what is left is counted in nights of trade rather than left as a
  // figure to be checked against a count nobody is taking.
  check(
    'the cellar says it is worked out from the till, not from a count',
    /less everything the till says was poured/i.test(value),
    value.slice(0, 260),
  )
  check('it says which night it has been read up to', /till read to/i.test(value), value.slice(0, 200))
  check(
    'a line that will not see another night is flagged for ordering',
    /Worth ordering/.test(value) && /Taddy Lager/.test(value),
    value.slice(0, 300),
  )
  check(
    'and says so in nights of trade rather than days on the calendar',
    /another night|more nights/.test(value),
    value.slice(value.indexOf('Worth ordering'), value.indexOf('Worth ordering') + 200),
  )

  await page.click('button:has-text("Trade")')
  await page.waitForSelector('.kpi-row', { timeout: 5000 })
  const profit = await page.locator('.main').innerText()
  check('the dashboard reports gross profit', profit.includes('What it actually makes'))
  check('with the rate across the costed lines', /68\.6%/.test(profit), profit.slice(0, 200))

  check(
    'and holds off forecasting from a single night',
    !(await page.locator('.main').innerText()).includes('What next week might take'),
    'three nights is the floor; one night forecasts nothing',
  )

  console.log('\nWhen the brewery put the price up')
  // The £95 entered earlier is dated today, and a second figure typed the same
  // day is a correction rather than a price rise — which is right, and means a
  // real rise has to be staged with an older point behind it.
  await page.evaluate(async () => {
    await new Promise<void>((resolve, reject) => {
      const req = indexedDB.open('tally')
      req.onsuccess = () => {
        const tx = req.result.transaction('stock', 'readwrite')
        const store = tx.objectStore('stock')
        const get = store.get('config')
        get.onsuccess = () => {
          const config = get.result as {
            items: Array<{ name: string; container?: { baseUnits: number }; costHistory?: unknown[] }>
          }
          const taddy = config.items.find((i) => i.name === 'Taddy Lager')
          if (taddy?.container) {
            taddy.costHistory = [
              { date: '2026-01-01', pence: 9500, baseUnits: taddy.container.baseUnits },
              ...((taddy.costHistory ?? []) as Array<Record<string, unknown>>).filter(
                (p) => p.date !== '2026-01-01',
              ),
            ]
          }
          store.put(config)
        }
        tx.oncomplete = () => resolve()
        tx.onerror = () => reject(tx.error)
      }
      req.onerror = () => reject(req.error)
    })
  })
  await page.reload({ waitUntil: 'networkidle' })

  // Now the cask goes up, and the board does not follow. Nothing anywhere
  // fails — the pint just makes less.
  await page.click('button:has-text("Cellar")')
  await page.waitForSelector('.chip:has-text("What it costs")', { timeout: 5000 })
  await page.click('.chip:has-text("What it costs")')
  await page.waitForSelector('input[aria-label="Taddy Lager cost"]', { timeout: 5000 })
  await page.fill('input[aria-label="Taddy Lager cost"]', '108.00')
  await page.waitForTimeout(500)
  const risen = await page.locator('.main').innerText()
  check('a risen cost re-prices the pint', risen.includes('£1.50'), '£108 across 72 pints')

  await page.click('button:has-text("Trade")')
  await page.waitForSelector('.kpi-row', { timeout: 5000 })
  const changed = await page.locator('.main').innerText()
  check('the movement is reported', changed.includes('What changed underneath'), changed.slice(0, 140))
  check('with both costs shown', /£1\.32 → £1\.50/.test(changed))
  check(
    'and names it as being absorbed',
    /absorbing this/.test(changed),
    'the board never moved, so the margin fell',
  )

  console.log('\nWorth knowing')
  const worth = await page.locator('.main').innerText()
  check('the week has findings at the top', worth.includes('Worth knowing'), worth.slice(0, 140))
  check(
    'including the cost rise nobody passed on',
    /costs £1\.50 now, up from £1\.32/.test(worth),
    worth.slice(0, 400),
  )
  const alertCount = await page.locator('.alert').count()
  check('and never more than five of them', alertCount > 0 && alertCount <= 5, `got ${alertCount}`)

  console.log('\nOne item, its whole story')
  // The costs and the price were set earlier in the run, so the card can be
  // checked with every panel lit: history, price, margin, and the cellar.
  await page.fill('input[aria-label="Find an item"]', 'taddy')
  await page.waitForTimeout(250)
  const foundTable = page.locator('.card:has-text("What people actually bought") table.data')
  const found = await foundTable.innerText()
  check('search narrows to the matching lines', found.includes('PINT TADDY LAGER') && !found.includes('CRISPS'), found.slice(0, 160))
  check('halves included, since they are taddy too', found.includes('HALF TADDY LAGER'))

  await page.click('.item-open:has-text("PINT TADDY LAGER")')
  await page.waitForTimeout(400)
  const card = await page.locator('.main').innerText()
  check('the card leads with the item', /PINT TADDY LAGER/.test(card))
  check('how many ever sold', /Sold\s+120/i.test(card), card.slice(0, 200))
  check('what it took', card.includes('£480.00'))
  check('what one goes for, against the board', /£4\.00/.test(card) && /board/i.test(card))
  // By this point the run has already staged the brewery rise to £108 a
  // firkin, so the card must show the margin as it stands NOW: £1.50 a pint
  // against the £4.20 board is 64.3% — and the squeeze note beside it.
  check('its margin from the cellar cost, as it stands today', /64\.3%/.test(card), card.slice(0, 260))
  check('with the squeeze called out on the card', /board has not moved/.test(card))
  check('what is left downstairs', /14\.5 pints/.test(card), 'the cellar leg on the same card')
  check('and the weekday that sold it', /Sun/.test(card))

  await page.click('button:has-text("Back to the trade")')
  await page.waitForTimeout(300)
  await page.fill('input[aria-label="Find an item"]', 'guinness')
  await page.waitForTimeout(250)
  check(
    'a drink the till has never sold says so',
    (await page.locator('.main').innerText()).includes('Nothing the till sells matches'),
  )
  await page.fill('input[aria-label="Find an item"]', '')
  await page.waitForTimeout(200)

  console.log('\nStaff records')
  await page.click('button:has-text("Rota")')
  await page.waitForSelector('.chip:has-text("Records")', { timeout: 5000 })
  await page.click('.chip:has-text("Records")')
  await page.waitForTimeout(400)
  const records = await page.locator('.main').innerText()
  check('the records list names the people', records.includes('Kelly'))
  check(
    'and refuses to rank anyone on one night',
    /too soon/i.test(records),
    'five countable nights is the floor',
  )

  await page.click('.person-row:has-text("Kelly")')
  await page.waitForTimeout(300)
  const profile = await page.locator('.main').innerText()
  check('a profile opens on the person', /nights worked/i.test(profile))
  check('showing the drawer on their nights', profile.includes('The drawer on their nights'))
  check('and says it is not evidence', /not evidence/.test(profile))

  console.log('\nSending a night on')
  await page.click('button:has-text("Nights")')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  await page.click('.day-row')
  await page.waitForSelector('button:has-text("Share this night")', { timeout: 5000 })
  // No share sheet in a headless browser, so the clipboard is the path taken.
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.evaluate(() => {
    ;(navigator as { share?: unknown }).share = undefined
  })
  await page.click('button:has-text("Share this night")')
  await page.waitForTimeout(500)
  const summary = await page.evaluate(() => navigator.clipboard.readText())
  check('the summary carries the date and the figures', /Sunday 23 August/.test(summary) && summary.includes('£2,192.80'))
  check('it states the verdict in words', /SHORT by £12\.00/.test(summary), summary.slice(0, 120))
  check('it says which leg was out', /Drawer\s+till says £351\.80/.test(summary))
  check('and who was on', summary.includes('Kelly'))

  // Back to the dashboard, which is where the next block picks up.
  await page.click('button:has-text("Trade")')
  await page.waitForSelector('.kpi-row', { timeout: 5000 })

  console.log('\nFiltering')
  await page.click('.chip:has-text("Draught beers")')
  await page.click('.chip:has-text("Wine")')
  await page.waitForTimeout(150)
  // The department mix specifically. The Trade screen has several tables now,
  // and "the first one" stopped meaning this one the moment another was added.
  const filtered = await page.locator('[data-testid="mix-table"]').innerText()
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
    (await page.locator('[data-testid="mix-table"]').innerText()).includes('68.05%'),
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

  console.log('\nWeek by week, and what is moving')
  // The whole point of holding the item list: a category and a line followed
  // through time, off the receipts, rather than one total for the window.
  await page.click('button:has-text("Trade")')
  await page.waitForSelector('.kpi-row', { timeout: 5000 })
  await page.click('.chip:has-text("90 nights")')
  await page.waitForTimeout(400)
  const overTime = page.locator('[data-testid="over-time"]')
  check('the trade screen plots whole weeks', (await overTime.count()) === 1)
  const weeksText = await page.locator('[data-testid="weeks-table"]').innerText()
  check(
    'the week we are in is marked as still filling',
    /still filling/i.test(weeksText),
    weeksText.slice(0, 200),
  )
  check(
    'and it says whole weeks run Monday to Sunday, off the receipts',
    /Monday to Sunday/.test(await page.locator('.main').innerText()),
  )
  // Every category the till printed is pickable, by its readable name.
  const options = await overTime.locator('option').allInnerTexts()
  check('every category the till printed can be plotted', options.includes('Draught beers'), options.join(', '))
  await overTime.selectOption({ label: 'Draught beers' })
  await page.waitForTimeout(300)
  check(
    'and picking one re-plots that category alone',
    (await page.locator('[data-testid="weeks-table"]').innerText()) !== weeksText,
  )

  console.log('\nCounting the cellar nightly')
  // The routine: read the roll, then go down and count. A keg is weighed, not
  // guessed at — a firkin of Taddy at 10 kg empty and 51 kg full.
  await page.click('button:has-text("Cellar")')
  await page.waitForSelector('.chip:has-text("What it costs")', { timeout: 5000 })
  await page.click('.chip:has-text("What it costs")')
  await page.waitForSelector('input[aria-label="Taddy Lager empty keg weight"]', { timeout: 5000 })
  await page.fill('input[aria-label="Taddy Lager empty keg weight"]', '10')
  await page.fill('input[aria-label="Taddy Lager full keg weight"]', '51')
  await page.waitForTimeout(400)

  // The count that opens the window: an ad-hoc take dated the 23rd, twenty pints.
  await page.click('.chip:has-text("Stock take")')
  await page.waitForSelector('#sheet-date', { timeout: 5000 })
  await page.fill('#sheet-date', '2026-08-23')
  await page.fill('input[aria-label="Taddy Lager counted"]', '20')
  await page.click('button:has-text("Save the stock take")')
  await page.waitForTimeout(500)

  // The next night, counted as it is closed — from Tonight, under the cash.
  await page.click('button:has-text("Tonight")')
  await page.waitForSelector('#date', { timeout: 5000 })
  await page.fill('#date', '2026-08-24')
  await page.waitForTimeout(300)
  await setFigure(page, 'figure-till', '500.00')
  await setFigure(page, 'figure-card', '300.00')
  await setFigure(page, 'figure-cash', '200.00')
  await page.waitForSelector('[data-testid="count-cellar"]', { timeout: 5000 })
  check('the night has a cellar step, after the cash', (await page.locator('[data-testid="count-cellar"]').count()) === 1)
  await page.click('[data-testid="count-cellar"]')
  await page.waitForSelector('input[aria-label="Taddy Lager on the scales"]', { timeout: 5000 })
  // 30.5 kg on the scales: 20.5 kg of beer, half a firkin, 36 pints.
  await page.fill('input[aria-label="Taddy Lager on the scales"]', '30.5')
  await page.waitForTimeout(200)
  check(
    'a reading off the scales becomes pints in the count',
    (await page.locator('input[aria-label="Taddy Lager counted"]').inputValue()) === '36',
    `got "${await page.locator('input[aria-label="Taddy Lager counted"]').inputValue()}" — 20.5 kg of a 41 kg fill is half of 72`,
  )
  check(
    'and says so beside the line',
    /30\.5 kg on the scales is 36 pints/.test(await page.locator('.main').innerText()),
  )
  // A reading lighter than the empty keg is a wrong reading, and is said.
  await page.fill('input[aria-label="Taddy Lager on the scales"]', '8')
  await page.waitForTimeout(200)
  check('a reading lighter than an empty keg is called out', /lighter than an empty one/.test(await page.locator('.main').innerText()))
  // She overrules by hand: seventeen pints in the one on the stillage.
  await page.fill('input[aria-label="Taddy Lager counted"]', '17')
  await page.waitForTimeout(200)
  check(
    'typing the count by hand clears the reading',
    (await page.locator('input[aria-label="Taddy Lager on the scales"]').inputValue()) === '',
  )
  await page.click('.verdict-bar .btn-primary')
  await page.waitForSelector('.day-row', { timeout: 5000 })

  // The night shows its own cellar: twenty should be there, seventeen were.
  await page.click('.day-row:has-text("24 Aug")')
  await page.waitForSelector('.verdict', { timeout: 5000 })
  await page.waitForSelector('.card:has-text("The cellar that night") table.data', { timeout: 5000 })
  const cellarNight = await page.locator('.card:has-text("The cellar that night")').innerText()
  check('the night carries its count, judged against the night before', /since Sun, 23 Aug/.test(cellarNight), cellarNight.slice(0, 200))
  check('should be twenty', /20 pints/.test(cellarNight))
  check('was seventeen', /17 pints/.test(cellarNight))
  check('three pints out', /−3 pints/.test(cellarNight), cellarNight.slice(0, 300))
  // At the cost as it stands: the brewery put the firkin up to £108 earlier
  // in this run, so three pints are £4.50 today, whatever they were in August.
  check('valued at the firkin price as it stands', /−£4\.50/.test(cellarNight), cellarNight.slice(-320))

  // The count comes back onto the sheet when the night is corrected.
  await page.click('button:has-text("Edit")')
  await page.waitForSelector('input[aria-label="Taddy Lager counted"]', { timeout: 5000 })
  check(
    'correcting the night shows the count as it was taken',
    (await page.locator('input[aria-label="Taddy Lager counted"]').inputValue()) === '17',
  )
  // And a night with no count says so, now that counting is a thing here.
  await page.click('button:has-text("Nights")')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  await page.click('.day-row:has-text("23 Aug")')
  await page.waitForSelector('.verdict', { timeout: 5000 })
  check(
    'the first count says it has nothing before it',
    /nothing before it to compare with/.test(await page.locator('.main').innerText()),
  )

  console.log('\nThe keg calculator')
  // The scales have a place of their own on the Cellar tab. Her own example:
  // a full keg at 110 kg, an empty one at 10 kg, a hundred pints between them
  // — so a keg reading 70 kg holds 60 pints, once its own 10 kg comes off.
  await page.click('button:has-text("Cellar")')
  await page.waitForSelector('.chip:has-text("The scales")', { timeout: 5000 })
  await page.click('.chip:has-text("The scales")')
  await page.waitForSelector('select[aria-label="Which keg"]', { timeout: 5000 })
  await page.selectOption('select[aria-label="Which keg"]', '*')
  await page.fill('input[aria-label="Pints the keg holds"]', '100')
  await page.fill('input[aria-label="Empty keg weight"]', '10')
  await page.fill('input[aria-label="Full keg weight"]', '110')
  await page.fill('input[aria-label="Keg on the scales"]', '70')
  await page.waitForTimeout(150)
  let calc = await page.locator('.keg-result').innerText()
  check('a keg reading 70 kg on a 10 kg keg holds 60 pints, not 70', /60 pints/.test(calc) && /60% full/.test(calc), `got "${calc}"`)
  await page.fill('input[aria-label="Keg on the scales"]', '8')
  await page.waitForTimeout(150)
  calc = await page.locator('.keg-result').innerText()
  check('a reading lighter than the empty keg is said, not rounded to nothing', /lighter than an empty keg/.test(calc), `got "${calc}"`)
  await page.fill('input[aria-label="Full keg weight"]', '5')
  await page.waitForTimeout(150)
  check(
    'a full keg lighter than an empty one is refused',
    (await page.locator('.main').innerText()).includes('A full keg has to weigh more than an empty one'),
  )

  // Against a line: Taddy was weighed at 10 and 51 under What it costs earlier,
  // and starts from those.
  const taddyOption = await page.locator('select[aria-label="Which keg"] option', { hasText: 'Taddy Lager' }).getAttribute('value')
  await page.selectOption('select[aria-label="Which keg"]', taddyOption ?? 'taddy-lager')
  await page.waitForTimeout(150)
  check(
    'a line weighed before starts from its own weights',
    (await page.locator('input[aria-label="Empty keg weight"]').inputValue()) === '10' &&
      (await page.locator('input[aria-label="Full keg weight"]').inputValue()) === '51',
  )
  check('and says so rather than offering to keep them again', (await page.locator('.main').innerText()).includes('Taddy Lager has these weights'))
  // Weighed again, a kilo heavier each way, and kept from here.
  await page.fill('input[aria-label="Empty keg weight"]', '11')
  await page.fill('input[aria-label="Full keg weight"]', '52')
  await page.fill('input[aria-label="Keg on the scales"]', '31.5')
  await page.waitForTimeout(150)
  calc = await page.locator('.keg-result').innerText()
  check('the reading is in the line’s own pints, of its own keg', /36 pints/.test(calc) && /of 72/.test(calc), `got "${calc}"`)
  await page.click('button:has-text("Keep for Taddy Lager")')
  await page.waitForTimeout(400)
  check('kept, it says so', (await page.locator('.main').innerText()).includes('Kept — Taddy Lager'))
  await page.click('.chip:has-text("What it costs")')
  await page.waitForSelector('input[aria-label="Taddy Lager empty keg weight"]', { timeout: 5000 })
  check(
    'and those are the weights the line now carries',
    (await page.locator('input[aria-label="Taddy Lager empty keg weight"]').inputValue()) === '11' &&
      (await page.locator('input[aria-label="Taddy Lager full keg weight"]').inputValue()) === '52',
  )

  // A tap beer with no size set yet can be set up from the scales: the keg on
  // them is the keg it comes in.
  await page.click('.chip:has-text("The scales")')
  await page.waitForSelector('select[aria-label="Which keg"]', { timeout: 5000 })
  const stoutOption = await page.locator('select[aria-label="Which keg"] option', { hasText: 'Stout' }).getAttribute('value')
  check('a tap beer with no size yet is still offered', stoutOption !== null)
  await page.selectOption('select[aria-label="Which keg"]', stoutOption ?? 'stout')
  await page.waitForTimeout(150)
  await page.fill('input[aria-label="Pints the keg holds"]', '144')
  await page.fill('input[aria-label="Empty keg weight"]', '15')
  await page.fill('input[aria-label="Full keg weight"]', '97')
  await page.waitForTimeout(150)
  check(
    'and keeping says what size that makes it',
    (await page.locator('.main').innerText()).includes('Stout has no size yet: keeping this makes it a kil of 144 pints'),
  )
  await page.click('button:has-text("Keep for Stout")')
  await page.waitForTimeout(400)
  await page.click('.chip:has-text("What it costs")')
  await page.waitForSelector('select[aria-label="Stout container"]', { timeout: 5000 })
  check(
    'a kil of 144 it is, weighed',
    (await page.locator('select[aria-label="Stout container"]').inputValue()) === 'kil' &&
      (await page.locator('input[aria-label="Stout servings per container"]').inputValue()) === '144' &&
      (await page.locator('input[aria-label="Stout empty keg weight"]').inputValue()) === '15',
    `container "${await page.locator('select[aria-label="Stout container"]').inputValue()}", size "${await page.locator('input[aria-label="Stout servings per container"]').inputValue()}"`,
  )

  // A different size of keg is a different keg. Moved to kils, the line needs
  // weighing again — silently carrying a firkin's weights over would turn every
  // reading into nonsense.
  await page.selectOption('select[aria-label="Taddy Lager container"]', 'kil')
  await page.waitForTimeout(400)
  check(
    'a line moved to another size of keg loses its weights',
    (await page.locator('input[aria-label="Taddy Lager empty keg weight"]').inputValue()) === '' &&
      (await page.locator('input[aria-label="Taddy Lager full keg weight"]').inputValue()) === '',
  )
  await page.selectOption('select[aria-label="Taddy Lager container"]', 'firkin')
  await page.waitForTimeout(400)
  check(
    'and back in firkins it is 72 pints again, still unweighed',
    (await page.locator('input[aria-label="Taddy Lager servings per container"]').inputValue()) === '72' &&
      (await page.locator('input[aria-label="Taddy Lager empty keg weight"]').inputValue()) === '',
  )

  // On Tonight the scales sit above the sheet, and keeping the weights with a
  // keg on them fills the row in — one tap sets the line up and counts it.
  await page.click('button:has-text("Nights")')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  await page.click('.day-row:has-text("24 Aug")')
  await page.waitForSelector('button:has-text("Edit")', { timeout: 5000 })
  await page.click('button:has-text("Edit")')
  await page.waitForSelector('input[aria-label="Taddy Lager counted"]', { timeout: 5000 })
  check('a keg not weighed has no scales box on the count', (await page.locator('input[aria-label="Taddy Lager on the scales"]').count()) === 0)
  await page.click('button:has-text("Weigh a keg")')
  await page.waitForSelector('select[aria-label="Which keg"]', { timeout: 5000 })
  await page.selectOption('select[aria-label="Which keg"]', taddyOption ?? 'taddy-lager')
  await page.fill('input[aria-label="Empty keg weight"]', '10')
  await page.fill('input[aria-label="Full keg weight"]', '51')
  await page.fill('input[aria-label="Keg on the scales"]', '30.5')
  await page.click('button:has-text("Keep for Taddy Lager")')
  await page.waitForSelector('input[aria-label="Taddy Lager on the scales"]', { timeout: 5000 })
  check(
    'kept from Tonight, the row grows its scales box with the reading already in it',
    (await page.locator('input[aria-label="Taddy Lager on the scales"]').inputValue()) === '30.5' &&
      (await page.locator('input[aria-label="Taddy Lager counted"]').inputValue()) === '36',
    `scales "${await page.locator('input[aria-label="Taddy Lager on the scales"]').inputValue()}", counted "${await page.locator('input[aria-label="Taddy Lager counted"]').inputValue()}"`,
  )

  console.log('\nThe cellar against the till, as it is counted')
  // A night with a roll on it, so there is something to take off: three pints
  // of Taddy sold against the seventeen counted the night before.
  const tinyRoll = {
    ...structuredClone(GARDENERS_ARMS),
    plus: [{ ...(GARDENERS_ARMS.plus.find((p) => /TADDY/.test(p.name)) as (typeof GARDENERS_ARMS)['plus'][number]), qtyMilli: 3000 }],
  }
  await page.evaluate(async (day) => {
    await new Promise<void>((resolve, reject) => {
      const req = indexedDB.open('tally')
      req.onsuccess = () => {
        const tx = req.result.transaction('days', 'readwrite')
        tx.objectStore('days').put(day)
        tx.oncomplete = () => resolve()
        tx.onerror = () => reject(tx.error)
      }
      req.onerror = () => reject(req.error)
    })
  }, {
    date: '2026-08-26',
    till: { pence: 1260, source: 'vision', edited: false },
    card: { pence: 760, source: 'manual', edited: false },
    cashPence: 500,
    note: '',
    zRead: tinyRoll,
    createdAt: 0,
    updatedAt: 0,
  })
  await page.reload({ waitUntil: 'networkidle' })
  await page.click('button:has-text("Nights")')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  await page.click('.day-row:has-text("26 Aug")')
  await page.waitForSelector('button:has-text("Edit")', { timeout: 5000 })
  await page.click('button:has-text("Edit")')
  await page.waitForSelector('[data-testid="count-cellar"]', { timeout: 5000 })
  await page.click('[data-testid="count-cellar"]')
  await page.waitForSelector('input[aria-label="Taddy Lager counted"]', { timeout: 5000 })
  // Seventeen were there on the 24th and three pints went through the till, so
  // fourteen is exactly right.
  await page.fill('input[aria-label="Taddy Lager counted"]', '14')
  await page.waitForTimeout(500)
  const live = await page.locator('.cellar-gap').innerText()
  check('the count sheet says what it is against', /Against the till/i.test(live), live.slice(0, 140))
  check('what should be down there', /14 pints/.test(live), live.slice(0, 300))
  check(
    'and nothing out when the count agrees with the roll',
    /nothing out/i.test(live),
    live.slice(0, 300),
  )
  // Two pints light, before anything is saved.
  await page.fill('input[aria-label="Taddy Lager counted"]', '12')
  await page.waitForTimeout(500)
  const short = await page.locator('.cellar-gap').innerText()
  check('it follows what is typed, with nothing saved yet', /−2 pints/.test(short), short.slice(0, 300))
  check('and puts money against the gap', /−£/.test(short), short.slice(-160))
  check(
    'with every sold line accounted for, it does not warn about pours',
    !/no pour set/.test(short),
    short.slice(-260),
  )
  await page.fill('input[aria-label="Taddy Lager counted"]', '14')
  await page.waitForTimeout(300)
  await page.click('.verdict-bar .btn-primary')
  await page.waitForSelector('.day-row', { timeout: 5000 })

  console.log('\nA night done in two goes')
  // The cellar gets counted as the doors are locked; the roll is read the next
  // morning. So a night has to save half done, be findable again, and — until
  // it has a figure — stay out of the takings rather than reading as a night
  // that took nothing.
  await page.click('button:has-text("Tonight")')
  await page.waitForSelector('#date', { timeout: 5000 })
  await page.fill('#date', '2026-08-25')
  await page.waitForTimeout(400)
  await page.click('[data-testid="count-cellar"]')
  await page.waitForSelector('input[aria-label="Taddy Lager counted"]', { timeout: 5000 })
  await page.fill('input[aria-label="Taddy Lager counted"]', '40')
  await page.waitForTimeout(250)
  check(
    'with no roll in yet, it says so rather than calling a night’s trade a loss',
    /Photograph the till roll/.test(await page.locator('.cellar-gap').innerText()),
    (await page.locator('.cellar-gap').innerText()).slice(0, 200),
  )
  check(
    'with nothing else filled in, the button offers to save it as it is',
    (await page.locator('.verdict-bar .btn-primary').innerText()).trim() === 'Save it as it is',
    await page.locator('.verdict-bar .btn-primary').innerText(),
  )
  await page.click('.verdict-bar .btn-primary')
  await page.waitForSelector('.day-row', { timeout: 5000 })

  const unfinishedRow = await page.locator('.day-row:has-text("25 Aug")').innerText()
  check(
    'the night is saved, and says what it is still waiting on',
    /Still needs the till roll total, the card total and the cash counted/.test(unfinishedRow),
    unfinishedRow.replace(/\n/g, ' | '),
  )

  await page.click('button:has-text("Trade")')
  await page.waitForSelector('.kpi-row', { timeout: 5000 })
  // Wide enough a window to take in a night from last month.
  await page.click('.chip:has-text("90 nights")')
  await page.waitForTimeout(400)
  const trade = await page.locator('.main').innerText()
  check('the trade figures say a night is still to finish', /still to finish/i.test(trade), trade.slice(0, 260))
  const taken = /taken over (\d+) nights?/i.exec(trade)
  check(
    'and count it among the nights of trade only once it has a figure',
    !!taken && !new RegExp(`taken over ${Number(taken[1]) + 1}`, 'i').test(trade),
    taken?.[0] ?? trade.slice(0, 160),
  )

  // Coming back to it: Tonight offers the unfinished night rather than leaving
  // it to be remembered.
  await page.click('button:has-text("Tonight")')
  await page.waitForSelector('#date', { timeout: 5000 })
  await page.fill('#date', '2026-08-26')
  await page.waitForTimeout(500)
  check(
    'a later night offers the unfinished one back',
    (await page.locator('button:has-text("Finish that night")').count()) === 1,
    (await page.locator('.main').innerText()).slice(0, 200),
  )
  await page.click('button:has-text("Finish that night")')
  await page.waitForTimeout(600)
  check('and opening it goes to that date', (await page.locator('#date').inputValue()) === '2026-08-25')
  check(
    'with the cellar count it was left with',
    (await page.locator('input[aria-label="Taddy Lager counted"]').inputValue()) === '40',
  )

  // The receipt, at last.
  await setFigure(page, 'figure-till', '900.00')
  await setFigure(page, 'figure-card', '600.00')
  await setFigure(page, 'figure-cash', '300.00')
  await page.waitForTimeout(300)
  check(
    'once it is complete the button goes back to updating it',
    (await page.locator('.verdict-bar .btn-primary').innerText()).trim() === 'Update this night',
  )
  await page.click('.verdict-bar .btn-primary')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  check(
    'and the night reads as a night of trade',
    /Took £900\.00/.test(await page.locator('.day-row:has-text("25 Aug")').innerText()),
    await page.locator('.day-row:has-text("25 Aug")').innerText(),
  )
  await page.click('button:has-text("Tonight")')
  await page.waitForSelector('#date', { timeout: 5000 })
  await page.waitForTimeout(500)
  check(
    'with nothing left unfinished, nothing is offered back',
    (await page.locator('button:has-text("Finish that night")').count()) === 0,
  )

  console.log('\nA roll photographed tonight, read in the morning')
  // The shape of the job now: photograph the receipt at closing, when there may
  // be no signal and nothing can read it, and pick it up in the morning. The
  // photographs have to still be there, still be marked unread, and still be
  // droppable one at a time.
  await page.click('button:has-text("Tonight")')
  await page.waitForSelector('#date', { timeout: 5000 })
  await page.fill('#date', '2026-08-27')
  await page.waitForTimeout(400)
  await page.setInputFiles('[data-testid="file-roll"]', [
    join(here, '..', 'public', 'icon-192.png'),
    join(here, '..', 'public', 'icon-512.png'),
  ])
  await page.waitForSelector('.shots li', { timeout: 5000 })
  check('both photographs are held', (await page.locator('.shots li').count()) === 2)
  check(
    'and the card counts them',
    /2 photographs/.test(await page.locator('.card:has(.dropzone) .card-head').innerText()),
    await page.locator('.card:has(.dropzone) .card-head').innerText(),
  )
  await page.click('.verdict-bar .btn-primary')
  await page.waitForSelector('.day-row', { timeout: 5000 })

  await page.click('button:has-text("Tonight")')
  await page.waitForSelector('#date', { timeout: 5000 })
  await page.fill('#date', '2026-08-28')
  await page.waitForTimeout(600)
  check(
    'the morning after, it says the roll is photographed but not read',
    /photographed but not read/i.test(await page.locator('.main').innerText()),
    (await page.locator('.main').innerText()).slice(0, 260),
  )
  await page.click('button:has-text("Finish the oldest"), button:has-text("Finish that night")')
  await page.waitForTimeout(700)
  // The oldest unfinished night is the 25th, which has no photographs. Go to
  // the photographed one directly.
  await page.fill('#date', '2026-08-27')
  await page.waitForTimeout(700)
  check('the photographs are still on the night', (await page.locator('.shots li').count()) === 2)
  check(
    'and still say nothing has read them',
    /not read yet/i.test(await page.locator('.shots li').first().innerText()),
    await page.locator('.shots li').first().innerText(),
  )

  await page.click('[data-testid="drop-shot-0"]')
  await page.waitForTimeout(250)
  check('one can be thrown away on its own', (await page.locator('.shots li').count()) === 1)
  await setFigure(page, 'figure-till', '810.00')
  await setFigure(page, 'figure-card', '510.00')
  await setFigure(page, 'figure-cash', '300.00')
  await page.waitForTimeout(300)
  await page.click('.verdict-bar .btn-primary')
  await page.waitForSelector('.day-row', { timeout: 5000 })

  await page.click('button:has-text("Tonight")')
  await page.waitForSelector('#date', { timeout: 5000 })
  await page.fill('#date', '2026-08-27')
  await page.waitForTimeout(700)
  check(
    'and the one thrown away is gone for good, not back on the next visit',
    (await page.locator('.shots li').count()) === 1,
  )

  console.log('\nMoving to a new copy')
  // The whole point of a backup: everything set up here has to arrive intact
  // in an empty copy of the app. The version this replaced saved only the
  // nights and lost the prices, the cellar and the rota without a word.
  await page.click('button:has-text("Settings")')
  await page.waitForSelector('button:has-text("Save everything")', { timeout: 5000 })
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.click('button:has-text("Save everything")'),
  ])
  const savedTo = await download.path()
  check('a backup file is produced', !!savedTo)
  check(
    'named so it is recognisable later',
    /\.tally\.json$/.test(download.suggestedFilename()),
    download.suggestedFilename(),
  )

  const backupText = savedTo ? await readFile(savedTo, 'utf8') : '{}'
  check('it does not carry the API key', !backupText.includes('sk-ant') && !backupText.includes('apiKey'))
  check('the small file leaves the photographs out', !/"photos"/.test(backupText))

  // And the bigger one, which carries the receipts themselves. A backup of the
  // figures without the receipts they were read off is a backup of somebody's
  // word for it.
  const [withShots] = await Promise.all([
    page.waitForEvent('download'),
    page.click('[data-testid="backup-receipts"]'),
  ])
  const shotsPath = await withShots.path()
  check(
    'the receipts file is named apart from the small one',
    /-with-receipts\.tally\.json$/.test(withShots.suggestedFilename()),
    withShots.suggestedFilename(),
  )
  const shotsText = shotsPath ? await readFile(shotsPath, 'utf8') : '{}'
  check('it carries the photographs', /"photos"/.test(shotsText))
  check('and is bigger for it', shotsText.length > backupText.length)
  check('but still leaves the key out', !shotsText.includes('sk-ant') && !shotsText.includes('apiKey'))
  check('and still parses as one file', (() => {
    try {
      return Array.isArray((JSON.parse(shotsText) as { photos?: unknown[] }).photos)
    } catch {
      return false
    }
  })())

  // A brand new copy of the app, with nothing in it at all.
  const fresh = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 3,
    isMobile: true,
    hasTouch: true,
  })
  await fresh.addInitScript(() => {
    try {
      localStorage.setItem('tally.engine', 'off')
      // The cellar stock take is already in, as far as these tests are
      // concerned: they build their own cellar and judge their own figures.
      localStorage.setItem('tally.seeded', 'cellar-2026-09-16')
    } catch {
      /* ignore */
    }
  })
  const newCopy = await fresh.newPage()
  newCopy.on('pageerror', (err) => pageErrors.push(String(err)))
  await newCopy.goto(base, { waitUntil: 'networkidle' })
  await newCopy.click('button:has-text("Nights")')
  await newCopy.waitForTimeout(400)
  check('the new copy starts empty', (await newCopy.locator('.day-row').count()) === 0)

  await newCopy.click('button:has-text("Settings")')
  await newCopy.waitForSelector('[data-testid="file-restore"]', { timeout: 5000 })
  await newCopy.setInputFiles('[data-testid="file-restore"]', shotsPath as string)
  await newCopy.waitForTimeout(2500)
  const said = await newCopy.locator('.toast').innerText().catch(() => '')
  check('it says what came back', /Restored/.test(said), said)
  check('including how many receipts', /receipt/.test(said), said)

  await newCopy.click('button:has-text("Nights")')
  await newCopy.waitForSelector('.day-row', { timeout: 5000 })
  check('the nights came across', (await newCopy.locator('.day-row').count()) >= 1)

  await newCopy.click('.day-row:has-text("27 Aug")')
  await newCopy.waitForSelector('.verdict', { timeout: 5000 })
  await newCopy.waitForTimeout(500)
  check(
    'and the receipt came with its night',
    (await newCopy.locator('.photo-thumb').count()) === 1,
    (await newCopy.locator('.main').innerText()).slice(0, 200),
  )

  await newCopy.click('button:has-text("Cellar")')
  await newCopy.waitForTimeout(700)
  const cellarBack = await newCopy.locator('.main').innerText()
  check('the cellar came across', cellarBack.includes('Taddy Lager'), cellarBack.slice(0, 120))
  await newCopy.click('.chip:has-text("What it costs")')
  await newCopy.waitForTimeout(500)
  check(
    'with the barrel costs still on it',
    (await newCopy.locator('.main').innerText()).includes('£1.50'),
    'the £108 firkin, not a blank box',
  )

  await newCopy.click('button:has-text("Rota")')
  await newCopy.waitForTimeout(700)
  check('the people came across', /Kelly/.test(await newCopy.locator('.main').innerText()))

  await fresh.close()

  console.log('\nA cellar set up by an older copy')
  // The four ways of counting replaced six, and a cellar saved by an older
  // copy has to survive that without a single millilitre moving. Everything is
  // held in base units, so the change is only what a line is spoken in — this
  // proves it on a cellar written the old way, straight into the database.
  const older = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  await older.addInitScript(() => {
    try {
      localStorage.setItem('tally.engine', 'off')
      // The cellar stock take is already in, as far as these tests are
      // concerned: they build their own cellar and judge their own figures.
      localStorage.setItem('tally.seeded', 'cellar-2026-09-16')
    } catch {
      /* ignore */
    }
  })
  const oldCopy = await older.newPage()
  oldCopy.on('pageerror', (err) => pageErrors.push(String(err)))
  await oldCopy.goto(base, { waitUntil: 'networkidle' })
  await oldCopy.evaluate(async () => {
    const put = (store: string, value: unknown) =>
      new Promise<void>((resolve, reject) => {
        const req = indexedDB.open('tally')
        req.onsuccess = () => {
          const tx = req.result.transaction(store, 'readwrite')
          tx.objectStore(store).put(value)
          tx.oncomplete = () => resolve()
          tx.onerror = () => reject(tx.error)
        }
        req.onerror = () => reject(req.error)
      })
    // Written the way the app used to: a spirit counted in 30ml shots, a wine
    // counted in 175ml glasses, crisps counted "each".
    await put('stock', {
      id: 'config',
      mlPerShot: 30,
      items: [
        {
          id: 'vodka', name: 'Vodka', kind: 'liquid', servingBaseUnits: 30, servingName: 'shot',
          container: { name: '70cl bottle', baseUnits: 700 },
          cost: { pence: 1400, baseUnits: 700 },
        },
        { id: 'rose', name: 'Rose', kind: 'liquid', servingBaseUnits: 175, servingName: '175ml' },
        { id: 'crisps', name: 'Crisps', kind: 'count', servingBaseUnits: 1, servingName: 'each' },
      ],
      pours: [{ itemCode: 'P00041', itemName: 'VODKA', stockItemId: 'vodka', baseUnits: 30 }],
    })
    // Two bottles and a bit: 1,400ml of vodka, counted the old way as 46.7 shots.
    await put('stockcounts', { date: '2026-08-20', lines: [{ stockItemId: 'vodka', baseUnits: 1400 }] })
  })
  await oldCopy.reload({ waitUntil: 'networkidle' })
  await oldCopy.click('button:has-text("Cellar")')
  await oldCopy.waitForSelector('.chip:has-text("What it costs")', { timeout: 5000 })
  await oldCopy.click('.chip:has-text("What it costs")')
  await oldCopy.waitForSelector('select[aria-label="Vodka measured in"]', { timeout: 5000 })
  check(
    'a line saved in shots is counted in millilitres now',
    (await oldCopy.locator('select[aria-label="Vodka measured in"]').inputValue()) === 'ml',
  )
  check(
    'a line saved in glasses too',
    (await oldCopy.locator('select[aria-label="Rose measured in"]').inputValue()) === 'ml',
  )
  check(
    'and one saved as “each” is a unit',
    (await oldCopy.locator('select[aria-label="Crisps measured in"]').inputValue()) === 'unit',
  )
  check(
    'the bottle it comes in is the same bottle',
    (await oldCopy.locator('input[aria-label="Vodka servings per container"]').inputValue()) === '700',
  )
  const oldCosts = await oldCopy.locator('.stock-line:has-text("Vodka")').innerText()
  check('and it still costs what it cost, priced by the shot', /£0\.60 a shot/.test(oldCosts), oldCosts.slice(0, 160))
  await oldCopy.click('.chip:has-text("What’s down there")')
  await oldCopy.waitForTimeout(500)
  const oldLevels = await oldCopy.locator('.main').innerText()
  check(
    'the count taken the old way is the same drink, to the millilitre',
    /1400 ml/.test(oldLevels),
    oldLevels.slice(0, 200),
  )
  check('read back as the shots it pours', /46\.7 shots/.test(oldLevels), oldLevels.slice(0, 200))
  await older.close()

  console.log('\nStarting again')
  // The one button in the app that destroys anything. It has to say what will
  // go, offer a copy first, and take a second tap — this is the last thing
  // anybody does by accident.
  await page.click('button:has-text("Settings")')
  await page.waitForSelector('[data-testid="start-again"]', { timeout: 5000 })
  // A key in the box, to prove the wipe takes the records and not the setup.
  await page.fill('#apiKey', 'sk-ant-not-a-real-key-for-testing-only')
  await page.click('button:has-text("Save key")')
  await page.waitForTimeout(250)
  check('clearing is two taps, not one', (await page.locator('[data-testid="confirm-clear"]').count()) === 0)
  await page.click('[data-testid="start-again"]')
  await page.waitForSelector('[data-testid="confirm-clear"]', { timeout: 5000 })
  const warning = await page.locator('.note.bad').first().innerText()
  check(
    'and says exactly what it will take, counted',
    /\d+ nights/.test(warning) && /cellar lines/.test(warning) && /cannot be undone/.test(warning),
    warning,
  )
  check('with a copy offered first', (await page.locator('button:has-text("Save a copy first")').count()) === 1)

  // Thinking better of it leaves everything exactly where it was.
  await page.click('button:has-text("Leave it alone")')
  await page.waitForTimeout(200)
  await page.click('button:has-text("Nights")')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  check('backing out changes nothing', (await page.locator('.day-row').count()) >= 1)

  await page.click('button:has-text("Settings")')
  await page.waitForSelector('[data-testid="start-again"]', { timeout: 5000 })
  await page.click('[data-testid="start-again"]')
  await page.waitForSelector('[data-testid="confirm-clear"]', { timeout: 5000 })
  await page.click('[data-testid="confirm-clear"]')
  // It reloads itself once it is done, so no screen is left holding a figure
  // that no longer exists anywhere.
  await page.waitForTimeout(2600)
  await page.click('button:has-text("Nights")')
  await page.waitForTimeout(600)
  check('afterwards there are no nights', (await page.locator('.day-row').count()) === 0)
  await page.click('button:has-text("Cellar")')
  await page.waitForTimeout(600)
  check(
    'and no cellar',
    (await page.locator('button:has-text("Build the cellar from the till")').count()) === 1,
    (await page.locator('.main').innerText()).slice(0, 120),
  )
  await page.click('button:has-text("Rota")')
  await page.waitForTimeout(600)
  check(
    'and nobody on the books',
    (await page.locator('button:has-text("Add the first person")').count()) === 1,
  )
  await page.click('button:has-text("Settings")')
  await page.waitForSelector('#apiKey', { timeout: 5000 })
  check(
    'but the key is kept, because a key is not data',
    (await page.inputValue('#apiKey')).startsWith('sk-ant-'),
    `key box holds "${await page.inputValue('#apiKey')}"`,
  )
  check(
    'and so are the settings',
    (await page.locator('.main').innerText()).includes('Start again'),
  )

  console.log('\nThe cellar it opens with')
  // Every other context above tells the app the stock take is already in, so
  // that they can build and judge their own cellar. This one does not: a copy
  // of the app opened for the first time has the pub's own count in it, with
  // nothing to go and find.
  const first = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  await first.addInitScript(() => {
    try {
      localStorage.setItem('tally.engine', 'off')
    } catch {
      /* ignore */
    }
  })
  const firstRun = await first.newPage()
  firstRun.on('pageerror', (err) => pageErrors.push(String(err)))
  await firstRun.goto(base, { waitUntil: 'networkidle' })
  await firstRun.click('button:has-text("Cellar")')
  await firstRun.waitForTimeout(900)
  const opened = await firstRun.locator('.main').innerText()
  check('a new copy opens with the stock take already in it', /Taddy Lager/.test(opened), opened.slice(0, 160))
  check('the casks, as they were counted', /1056 pints/.test(opened) && /218\.2 pints/.test(opened), opened.slice(0, 400))
  check('the wine, to the millilitre', /55125 ml/.test(opened))
  check('and the bottles off the shelf', /107 units/.test(opened) && /46 bottles/.test(opened))
  check(
    'the spirits say they were not counted, rather than none',
    /not counted/.test(opened) || !/Vodka/.test(opened),
    'a line nobody counted must never read as zero',
  )
  // And it does not keep re-counting the cellar every time it is opened.
  await firstRun.click('button:has-text("Cellar")')
  await firstRun.click('.chip:has-text("Stock take")')
  await firstRun.waitForSelector('input[aria-label="Taddy Lager large kegs counted"]', { timeout: 5000 })
  await firstRun.fill('input[aria-label="Taddy Lager large kegs counted"]', '3')
  await firstRun.click('button:has-text("Save the stock take")')
  await firstRun.waitForTimeout(600)
  await firstRun.reload({ waitUntil: 'networkidle' })
  await firstRun.click('button:has-text("Cellar")')
  await firstRun.waitForTimeout(900)
  check(
    'and re-opening it does not count the cellar again',
    /528 pints/.test(await firstRun.locator('.main').innerText()),
    'three kegs counted by hand must survive the next launch',
  )
  await first.close()

  console.log('\nTying the till to a cellar counted onto paper')
  // The case that actually matters, in a copy of its own so it disturbs
  // nothing: the cellar came off a handwritten sheet, so its lines are called
  // what she calls them and nothing on it carries a till code. Until these are
  // tied up a receipt takes nothing off the stock at all.
  const paper = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 3,
    isMobile: true,
    hasTouch: true,
  })
  await paper.addInitScript(() => {
    try {
      localStorage.setItem('tally.engine', 'off')
      localStorage.setItem('tally.seeded', 'cellar-2026-09-16')
    } catch {
      /* ignore */
    }
  })
  const tied = await paper.newPage()
  tied.on('pageerror', (err) => pageErrors.push(String(err)))
  await tied.goto(base, { waitUntil: 'networkidle' })
  await tied.evaluate(async (roll) => {
    await new Promise<void>((resolve, reject) => {
      const req = indexedDB.open('tally')
      req.onsuccess = () => {
        const tx = req.result.transaction(['stock', 'days'], 'readwrite')
        // A cellar off the sheet: her own names, no till codes anywhere.
        tx.objectStore('stock').put({
          id: 'config',
          items: [
            { id: 'taddy-sheet', name: 'Taddy Lager', kind: 'liquid', servingBaseUnits: 568, servingName: 'pint',
              container: { name: 'firkin', baseUnits: 72 * 568 } },
          ],
          pours: [],
          mlPerShot: 30,
          updatedAt: 0,
        })
        tx.objectStore('days').put({
          date: '2026-08-23',
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
  await tied.reload({ waitUntil: 'networkidle' })
  await tied.click('button:has-text("Cellar")')
  await tied.click('.chip:has-text("Set up")')
  await tied.waitForSelector('[data-testid="to-match"]', { timeout: 5000 })
  check(
    'it says plainly that nothing comes off the cellar yet',
    /take[s]? nothing off/.test(await tied.locator('[data-testid="to-match"]').innerText()),
    await tied.locator('[data-testid="to-match"]').innerText(),
  )

  // The till calls it "PINT TADDY LAGER"; the sheet calls it "Taddy Lager".
  const taddyPick = tied.locator('select[aria-label="PINT TADDY LAGER comes off"]')
  check('the till line is offered the line off the sheet', (await taddyPick.count()) === 1)
  check(
    'and is matched to it rather than to a second line invented beside it',
    (await taddyPick.inputValue()) === 'taddy-sheet',
    await taddyPick.inputValue(),
  )
  check(
    'with what one sale takes, in that line’s own units',
    (await tied.locator('input[aria-label="PINT TADDY LAGER takes"]').inputValue()) === '1',
  )

  await tied.click('[data-testid="keep-pours"]')
  await tied.waitForTimeout(800)
  check(
    'saving says how many now come off the cellar',
    /come off the cellar/.test(await tied.locator('.toast').innerText().catch(() => '')),
    await tied.locator('.toast').innerText().catch(() => ''),
  )
  await tied.waitForTimeout(400)
  check('and there is nothing left to tie up', (await tied.locator('[data-testid="to-match"]').count()) === 0)

  await tied.click('.chip:has-text("What’s down there")')
  await tied.waitForTimeout(700)
  const afterTie = await tied.locator('.main').innerText()
  check(
    'and the receipt now comes off the line off the sheet',
    /Taddy Lager/.test(afterTie) && !/knows nothing about/.test(afterTie),
    afterTie.slice(0, 500),
  )
  // The Taddy row must show a poured figure now, which is the whole point.
  const taddyRow = await tied.locator('tr:has-text("Taddy Lager")').first().innerText()
  check('with what the till poured off it', /pints/.test(taddyRow), taddyRow.replace(/\n/g, ' | '))
  await paper.close()

  check('nothing threw along the way', pageErrors.length === 0, pageErrors.join('\n        '))
} finally {
  await browser.close()
  server.close()
}

console.log(`\n${checks - failures}/${checks} checks passed`)
process.exit(failures === 0 ? 0 : 1)
