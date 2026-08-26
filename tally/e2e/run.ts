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
  await page.setInputFiles('[data-testid="file-roll"]', join(here, '..', 'public', 'icon-192.png'))
  await page.waitForSelector('.note.bad', { timeout: 5000 })
  const scanNote = await page.locator('.note.bad').first().innerText()
  check('says why it could not scan', /switched off/i.test(scanNote), `got "${scanNote}"`)
  check('the typed figure survives a failed scan', (await page.inputValue('#figure-till')) === '4212.30')
  check('and the night can still be finished', (await verdictText(page)) === 'Balanced')

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
  check('a pint pours a pint', /PINT TADDY LAGER[\s\S]*?1 pint/.test(pourText), pourText.slice(0, 160))
  check('a half pours half of one', /HALF TADDY LAGER[\s\S]*?0\.5 pints/.test(pourText))
  check('a spirit pours a shot', /VODKA[\s\S]*?1 shot/.test(pourText))
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

  // The cellar is worth something now.
  await page.click('.chip:has-text("What’s left")')
  await page.waitForTimeout(400)
  const value = await page.locator('.main').innerText()
  check('the cellar is valued at what the stock cost', value.includes('Money in the cellar'), value.slice(0, 160))
  check(
    'at the right figure, right through from the delivery',
    value.includes('£19.13'),
    '144 pints in less 129.5 poured is 14.5 left, at £95 the firkin',
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
  await newCopy.setInputFiles('[data-testid="file-restore"]', savedTo as string)
  await newCopy.waitForTimeout(1800)
  const said = await newCopy.locator('.toast').innerText().catch(() => '')
  check('it says what came back', /Restored/.test(said), said)

  await newCopy.click('button:has-text("Nights")')
  await newCopy.waitForSelector('.day-row', { timeout: 5000 })
  check('the nights came across', (await newCopy.locator('.day-row').count()) >= 1)

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

  check('nothing threw along the way', pageErrors.length === 0, pageErrors.join('\n        '))
} finally {
  await browser.close()
  server.close()
}

console.log(`\n${checks - failures}/${checks} checks passed`)
process.exit(failures === 0 ? 0 : 1)
