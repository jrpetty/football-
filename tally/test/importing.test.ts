import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { applyPrices, proposePrices } from '../src/core/priceBook.ts'
import { dayToDate, proposeShifts, shiftAt, shiftsFrom, weekDays, type Person } from '../src/core/rota.ts'
import { parseBoardPrice } from '../src/ocr/scanList.ts'

/** 18:00 to 23:30 — the hours these tests put people on for. Hours belong to
    the night now, so every shift here says which ones it means. */
const EVENING = { startMin: 1080, endMin: 1410 }
const on = (person: Person, date: string, hours = EVENING) => shiftAt(person.id, date, hours)


const TILL = [
  { code: '1', name: 'PINT TADDY LAGER' },
  { code: '2', name: 'HALF TADDY LAGER' },
  { code: '3', name: 'PINT ALPINE' },
  { code: '9', name: 'CRISPS' },
]

// --- the price board ----------------------------------------------------------

test('a board price is read only when it reads cleanly', () => {
  assert.equal(parseBoardPrice('4.00'), 400)
  assert.equal(parseBoardPrice('£4'), 400)
  assert.equal(parseBoardPrice(' 4,20 '), 420)
  assert.equal(parseBoardPrice('4.5'), 450)
  // Nonsense is dropped rather than guessed — one tap to add, months to notice.
  assert.equal(parseBoardPrice('four pounds'), null)
  assert.equal(parseBoardPrice(''), null)
  assert.equal(parseBoardPrice('0'), null)
  assert.equal(parseBoardPrice('4.00.50'), null)
})

test('a new price is proposed as new', () => {
  const [row] = proposePrices([{ name: 'Pint Alpine', pence: 300 }], TILL, [])
  assert.equal(row!.status, 'new')
  assert.equal(row!.code, '3')
})

test('a price that has gone up is proposed as a change, showing the old one', () => {
  const book = [{ code: '3', name: 'PINT ALPINE', pence: 300 }]
  const [row] = proposePrices([{ name: 'Pint Alpine', pence: 320 }], TILL, book)
  assert.equal(row!.status, 'changed')
  assert.equal(row!.wasPence, 300)
  assert.equal(row!.pence, 320)
})

test('a price that has not moved is proposed as nothing to do', () => {
  const book = [{ code: '3', name: 'PINT ALPINE', pence: 300 }]
  const [row] = proposePrices([{ name: 'Pint Alpine', pence: 300 }], TILL, book)
  assert.equal(row!.status, 'same')
})

test('a board line that could be the pint or the half is flagged, never applied', () => {
  const [row] = proposePrices([{ name: 'Taddy Lager', pence: 400 }], TILL, [])
  assert.equal(row!.status, 'ambiguous')
  assert.equal(row!.between?.length, 2)
  // And it carries no code or name, so it cannot be written by accident.
  assert.equal(row!.code, undefined)
})

test('something the till has never sold is left unmatched', () => {
  const [row] = proposePrices([{ name: 'Pork Scratchings', pence: 150 }], TILL, [])
  assert.equal(row!.status, 'unmatched')
})

test('applying replaces by code and leaves the rest of the book alone', () => {
  const book = [
    { code: '3', name: 'PINT ALPINE', pence: 300 },
    { code: '9', name: 'CRISPS', pence: 170 },
  ]
  const proposals = proposePrices([{ name: 'Pint Alpine', pence: 320 }], TILL, book)
  const next = applyPrices(book, proposals, '2026-08-26')
  assert.equal(next.length, 2)
  assert.equal(next.find((b) => b.code === '3')!.pence, 320)
  assert.equal(next.find((b) => b.code === '9')!.pence, 170, 'untouched')
})

test('applying an unmatched row writes nothing', () => {
  const proposals = proposePrices([{ name: 'Pork Scratchings', pence: 150 }], TILL, [])
  assert.deepEqual(applyPrices([], proposals, '2026-08-26'), [])
})

// --- the rota -----------------------------------------------------------------

const WEEK = weekDays('2026-08-24') // Monday 24th to Sunday 30th August 2026
const kelly: Person = { id: 'k', name: 'Kelly', slot: 1 }
const dave: Person = { id: 'd', name: 'Dave', slot: 2 }

test('a day column is resolved by name or by date', () => {
  assert.equal(dayToDate('Mon', WEEK), '2026-08-24')
  assert.equal(dayToDate('Monday', WEEK), '2026-08-24')
  assert.equal(dayToDate('Sat 29', WEEK), '2026-08-29')
  assert.equal(dayToDate('29', WEEK), '2026-08-29')
  assert.equal(dayToDate('Sun', WEEK), '2026-08-30')
})

test('a day column that means nothing is refused rather than defaulted', () => {
  assert.equal(dayToDate('', WEEK), null)
  assert.equal(dayToDate('whenever', WEEK), null)
  assert.equal(dayToDate('40', WEEK), null)
})

test('a shift with times on the paper uses them', () => {
  const [row] = proposeShifts([{ name: 'Kelly', day: 'Fri', start: '17:00', end: '23:00' }], [kelly], WEEK, [])
  assert.equal(row!.status, 'new')
  assert.equal(row!.date, '2026-08-28')
  assert.equal(row!.startMin, 17 * 60)
  assert.equal(row!.timesFrom, 'paper')
})

test('a ticked box is marked as having no times on the paper', () => {
  const [row] = proposeShifts([{ name: 'Kelly', day: 'Fri', start: '', end: '' }], [kelly], WEEK, [])
  // Six until close, since there is nothing else to go on — but flagged, so the
  // screen puts it in a box rather than crediting hours the paper never claimed.
  assert.equal(row!.startMin, 1080)
  assert.equal(row!.endMin, 1410)
  assert.equal(row!.timesFrom, 'chosen')
})

test('a ticked box starts at the hours that night is already rostered at', () => {
  // Dave is already on that Friday from four until eleven. Kelly's ticked box
  // starts there rather than at a house default nobody chose.
  const existing = [on(dave, '2026-08-28', { startMin: 16 * 60, endMin: 23 * 60 })]
  const [row] = proposeShifts([{ name: 'Kelly', day: 'Fri', start: '', end: '' }], [kelly, dave], WEEK, existing)
  assert.equal(row!.startMin, 16 * 60)
  assert.equal(row!.endMin, 23 * 60)
  assert.equal(row!.timesFrom, 'chosen')
})

test('a shift already on the rota is proposed as already there', () => {
  const existing = [on(kelly, '2026-08-28')]
  const [row] = proposeShifts([{ name: 'Kelly', day: 'Fri', start: '', end: '' }], [kelly], WEEK, existing)
  assert.equal(row!.status, 'already')
})

test('a name nobody on the books answers to is flagged', () => {
  const [row] = proposeShifts([{ name: 'Sandra', day: 'Fri', start: '', end: '' }], [kelly], WEEK, [])
  assert.equal(row!.status, 'unknown-person')
  assert.equal(row!.personId, undefined)
})

test('two people it could equally be are flagged, not picked between', () => {
  const daveJ: Person = { ...dave, id: 'd2', name: 'Dave Jones' }
  const daveS: Person = { ...dave, id: 'd3', name: 'Dave Smith' }
  const [row] = proposeShifts([{ name: 'Dave', day: 'Fri', start: '', end: '' }], [daveJ, daveS], WEEK, [])
  assert.equal(row!.status, 'ambiguous')
})

test('a known person on an unreadable day keeps the person and refuses the day', () => {
  const [row] = proposeShifts([{ name: 'Kelly', day: '???', start: '', end: '' }], [kelly], WEEK, [])
  assert.equal(row!.status, 'unknown-day')
  assert.equal(row!.personName, 'Kelly')
  assert.equal(row!.date, undefined)
})

test('only rows that resolved fully become shifts', () => {
  const proposals = proposeShifts(
    [
      { name: 'Kelly', day: 'Fri', start: '17:00', end: '23:00' },
      { name: 'Sandra', day: 'Fri', start: '', end: '' },
      { name: 'Kelly', day: '???', start: '', end: '' },
    ],
    [kelly],
    WEEK,
    [],
  )
  const shifts = shiftsFrom(proposals)
  assert.equal(shifts.length, 1)
  assert.equal(shifts[0]!.id, '2026-08-28:k')
})

test('a shortened name on the paper still finds the person', () => {
  const [row] = proposeShifts([{ name: 'Kel', day: 'Sat 29', start: '18:00', end: '00:30' }], [kelly, dave], WEEK, [])
  assert.equal(row!.personName, 'Kelly')
  assert.equal(row!.date, '2026-08-29')
  assert.equal(row!.endMin, 30)
})

// --- the delivery note --------------------------------------------------------

import { bestMatch } from '../src/core/match.ts'
import {

  deliveryLinesFrom,
  ML_PER_PINT,
  proposeDelivery,
  type StockItem,
} from '../src/core/stock.ts'

const cellar: StockItem[] = [
  {
    id: 'taddy', name: 'Taddy Lager', kind: 'liquid',
    servingBaseUnits: ML_PER_PINT, servingName: 'pint',
    container: { name: 'kil', baseUnits: 144 * ML_PER_PINT },
  },
  {
    id: 'alpine', name: 'Alpine', kind: 'liquid',
    servingBaseUnits: ML_PER_PINT, servingName: 'pint',
    container: { name: 'firkin', baseUnits: 72 * ML_PER_PINT },
  },
  { id: 'crisps', name: 'Crisps', kind: 'count', servingBaseUnits: 1, servingName: 'each' },
]

const propose = (rows: Array<{ name: string; quantity: number; unit: string }>) =>
  proposeDelivery(rows, cellar, bestMatch)

test('two kils on the note is two kils in the cellar', () => {
  const [row] = propose([{ name: 'Taddy Lager', quantity: 2, unit: 'kil' }])
  assert.equal(row!.status, 'ready')
  assert.equal(row!.countedAs, 'container')
  assert.equal(row!.baseUnits, 288 * ML_PER_PINT)
})

test('a bare quantity is read as containers when the line has one', () => {
  // The whole risk: reading "3" as three pints instead of three firkins is
  // wrong by a factor of 72.
  const [row] = propose([{ name: 'Alpine', quantity: 3, unit: '' }])
  assert.equal(row!.baseUnits, 216 * ML_PER_PINT)
  assert.equal(row!.countedAs, 'container')
})

test('a note that says pints means pints', () => {
  const [row] = propose([{ name: 'Taddy Lager', quantity: 20, unit: 'pints' }])
  assert.equal(row!.countedAs, 'serving')
  assert.equal(row!.baseUnits, 20 * ML_PER_PINT)
})

test('a line with no container falls back to servings', () => {
  const [row] = propose([{ name: 'Crisps', quantity: 48, unit: '' }])
  assert.equal(row!.status, 'ready')
  assert.equal(row!.baseUnits, 48)
})

test('a unit the cellar has no size for is refused, not multiplied', () => {
  const [row] = propose([{ name: 'Crisps', quantity: 4, unit: 'case' }])
  assert.equal(row!.status, 'no-container')
  assert.equal(row!.baseUnits, undefined)
})

test('a product not in the cellar is left unmatched', () => {
  const [row] = propose([{ name: 'Cask deposit', quantity: 2, unit: '' }])
  assert.equal(row!.status, 'unmatched')
})

test('the same beer twice on one note is added up, not listed twice', () => {
  const rows = propose([
    { name: 'Taddy Lager', quantity: 2, unit: 'kil' },
    { name: 'Taddy Lager', quantity: 1, unit: 'kil' },
  ])
  const lines = deliveryLinesFrom(rows)
  assert.equal(lines.length, 1)
  assert.equal(lines[0]!.baseUnits, 432 * ML_PER_PINT)
})

test('only ready lines are booked in', () => {
  const rows = propose([
    { name: 'Taddy Lager', quantity: 2, unit: 'kil' },
    { name: 'Cask deposit', quantity: 2, unit: '' },
    { name: 'Crisps', quantity: 4, unit: 'case' },
  ])
  assert.equal(deliveryLinesFrom(rows).length, 1)
})
