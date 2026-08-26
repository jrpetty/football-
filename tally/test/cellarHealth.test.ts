import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { cellarHealth, ML_PER_PINT, type Delivery, type Pour, type StockCount, type StockItem } from '../src/core/stock.ts'
import { costOf } from '../src/core/margin.ts'

const taddy: StockItem = {
  id: 'taddy', name: 'Taddy Lager', kind: 'liquid',
  servingBaseUnits: ML_PER_PINT, servingName: 'pint',
  container: { name: 'firkin', baseUnits: 72 * ML_PER_PINT },
  cost: { pence: 9500, baseUnits: 72 * ML_PER_PINT },
}
const crisps: StockItem = { id: 'crisps', name: 'Crisps', kind: 'count', servingBaseUnits: 1, servingName: 'each' }

const pours: Pour[] = [
  { itemCode: '1', itemName: 'PINT TADDY LAGER', stockItemId: 'taddy', baseUnits: ML_PER_PINT },
  { itemCode: '9', itemName: 'CRISPS', stockItemId: 'crisps', baseUnits: 1 },
]

const night = (date: string, pints: number) => ({
  date,
  items: [{ code: '1', name: 'PINT TADDY LAGER', qtyMilli: pints * 1000 }],
})

function health(over: Partial<Parameters<typeof cellarHealth>[0]> = {}) {
  return cellarHealth({
    items: [taddy],
    pours,
    counts: [],
    deliveries: [],
    days: [],
    today: '2026-08-26',
    costOfServing: costOf,
    ...over,
  })
}

test('with no stock take yet, the open window is the last week', () => {
  const h = health()
  assert.equal(h.since, '2026-08-19')
  assert.equal(h.sinceDays, 7)
  assert.equal(h.gapPence, null, 'nothing can be judged with no counts')
  assert.deepEqual(h.gapLines, [])
})

test('the open window is delivery in, pours out, from the last count', () => {
  // The same figures the Cellar screen shows: 144 in, 129.5 poured, 14.5 left.
  const counts: StockCount[] = [{ date: '2026-08-20', lines: [{ stockItemId: 'taddy', baseUnits: 0 }] }]
  const deliveries: Delivery[] = [{ id: 'd', date: '2026-08-21', lines: [{ stockItemId: 'taddy', baseUnits: 144 * ML_PER_PINT }] }]
  const h = health({ counts, deliveries, days: [night('2026-08-22', 100), night('2026-08-23', 29.5)] })
  const line = h.ledger.find((l) => l.item.id === 'taddy')!
  assert.equal(line.expectedBaseUnits, 14.5 * ML_PER_PINT)
})

test('a night on the count date itself belongs to the window before it', () => {
  // The count is taken at close. The same night's pours must not come off the
  // new window as well, or they would be counted against the cellar twice.
  const counts: StockCount[] = [{ date: '2026-08-20', lines: [{ stockItemId: 'taddy', baseUnits: 72 * ML_PER_PINT }] }]
  const h = health({ counts, days: [night('2026-08-20', 40)] })
  const line = h.ledger.find((l) => l.item.id === 'taddy')!
  assert.equal(line.pouredBaseUnits, 0, 'the count-day pours are inside the closed window')
})

test('two counts make a judgeable window, valued at cost', () => {
  // Between the takes: started at a firkin, poured 30, so 42 should be left.
  // The clipboard says 38 — four pints of Taddy gone, at £1.32 a pint.
  const counts: StockCount[] = [
    { date: '2026-08-24', lines: [{ stockItemId: 'taddy', baseUnits: 38 * ML_PER_PINT }] },
    { date: '2026-08-17', lines: [{ stockItemId: 'taddy', baseUnits: 72 * ML_PER_PINT }] },
  ]
  const h = health({ counts, days: [night('2026-08-20', 30)] })
  assert.equal(h.gapLines.length, 1)
  assert.equal(h.gapLines[0]!.varianceBaseUnits, -4 * ML_PER_PINT)
  assert.equal(h.gapPence, -528, 'four pints at 132p, negative because it is missing')
})

test('a gap on a line with no cost reads as unknown, never as fine', () => {
  const counts: StockCount[] = [
    { date: '2026-08-24', lines: [{ stockItemId: 'crisps', baseUnits: 30 }] },
    { date: '2026-08-17', lines: [{ stockItemId: 'crisps', baseUnits: 40 }] },
  ]
  const h = health({ items: [crisps], counts })
  assert.equal(h.gapLines.length, 1, 'the missing packets are still listed')
  assert.equal(h.gapPence, null, 'but a zero here would read as "all fine"')
})

test('costed and uncosted lines mix without the uncosted ones zeroing the value', () => {
  const counts: StockCount[] = [
    { date: '2026-08-24', lines: [{ stockItemId: 'taddy', baseUnits: 70 * ML_PER_PINT }, { stockItemId: 'crisps', baseUnits: 30 }] },
    { date: '2026-08-17', lines: [{ stockItemId: 'taddy', baseUnits: 72 * ML_PER_PINT }, { stockItemId: 'crisps', baseUnits: 40 }] },
  ]
  const h = health({ items: [taddy, crisps], counts })
  assert.equal(h.gapPence, -264, 'the two missing pints are valued; the crisps cannot be')
})

test('an overage values positive — a delivery booked twice reads as heavy', () => {
  const counts: StockCount[] = [
    { date: '2026-08-24', lines: [{ stockItemId: 'taddy', baseUnits: 80 * ML_PER_PINT }] },
    { date: '2026-08-17', lines: [{ stockItemId: 'taddy', baseUnits: 72 * ML_PER_PINT }] },
  ]
  const h = health({ counts })
  assert.equal(h.gapPence, 1056, 'eight pints heavy at 132p')
})

test('dead stock is judged over the same open window the screen shows', () => {
  const counts: StockCount[] = [{ date: '2026-07-29', lines: [{ stockItemId: 'taddy', baseUnits: 72 * ML_PER_PINT }] }]
  const h = health({ counts, days: [night('2026-08-01', 2)], today: '2026-08-26' })
  assert.equal(h.sinceDays, 28)
  assert.equal(h.dead.length, 1)
  assert.equal(h.dead[0]!.reason, 'not selling')
})

test('a judged window that reconciled exactly is £0 out, not unknown', () => {
  // The distinction matters: null means "cannot say", and a cellar that was
  // counted and agreed to the pint deserves better than "cannot say".
  const counts: StockCount[] = [
    { date: '2026-08-24', lines: [{ stockItemId: 'taddy', baseUnits: 42 * ML_PER_PINT }] },
    { date: '2026-08-17', lines: [{ stockItemId: 'taddy', baseUnits: 72 * ML_PER_PINT }] },
  ]
  const h = health({ counts, days: [night('2026-08-20', 30)] })
  assert.deepEqual(h.gapLines, [])
  assert.equal(h.gapPence, 0)
})
