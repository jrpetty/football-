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

// --- before anybody has counted --------------------------------------------------

test('with no count yet, the window opens where the records do, so an old delivery still counts', () => {
  // A firkin booked in three weeks before today, and twenty pints poured since.
  // An earlier version opened the window seven days back and lost the firkin.
  const deliveries: Delivery[] = [{ id: 'd1', date: '2026-08-05', lines: [{ stockItemId: 'taddy', baseUnits: 72 * ML_PER_PINT }] }]
  const h = health({ deliveries, days: [night('2026-08-10', 20)] })
  assert.equal(h.since, '2026-08-04', 'the day before the first record')
  const t = h.ledger.find((l) => l.item.id === 'taddy')!
  assert.equal(t.counted, true, 'an uncounted cellar opens empty rather than unknowable')
  assert.equal(t.deliveredBaseUnits, 72 * ML_PER_PINT)
  assert.equal(t.expectedBaseUnits, 52 * ML_PER_PINT)
})

test('once a count exists, a line left off it is uncounted — not counted at nothing', () => {
  const counts: StockCount[] = [{ date: '2026-08-20', lines: [{ stockItemId: 'taddy', baseUnits: 30 * ML_PER_PINT }] }]
  const h = health({ items: [taddy, crisps], counts, days: [night('2026-08-22', 5)] })
  assert.equal(h.ledger.find((l) => l.item.id === 'taddy')!.counted, true)
  assert.equal(h.ledger.find((l) => l.item.id === 'crisps')!.counted, false)
})


// --- the cellar as the till leaves it ------------------------------------------
//
// Nobody is counting the cellar any more, so the running total off the till is
// the only figure there is. Two things follow. It has to say how long each line
// has left, because that is the whole reason to keep it. And it has to own up
// to the sales it could not place, because those are exactly what make it wrong
// while looking right.

test('a line running down says how many nights it has left', () => {
  // Opens at 300 pints on the 10th; four nights read, 200 pints out between
  // them — fifty a night, 100 left, so two more nights.
  const h = health({
    counts: [{ date: '2026-08-10', lines: [{ stockItemId: 'taddy', baseUnits: 300 * ML_PER_PINT }] }],
    days: [night('2026-08-15', 50), night('2026-08-16', 50), night('2026-08-17', 50), night('2026-08-18', 50)],
    today: '2026-08-30',
  })
  const r = h.runway.find((x) => x.item.id === 'taddy')
  assert.ok(r, 'a line with a level and a rate gets a runway')
  assert.equal(r.leftBaseUnits, 100 * ML_PER_PINT)
  assert.equal(h.readNights, 4)
  assert.equal(r.nightsLeft, 2)
})

test('a receipt still waiting to be read does not stretch the rate out', () => {
  // The killer error this is built against: measure per calendar day and four
  // read nights out of twenty make a fortnight's stock look like months.
  const days = [night('2026-08-15', 50), night('2026-08-16', 50), night('2026-08-17', 50), night('2026-08-18', 50)]
  const soon = health({
    counts: [{ date: '2026-08-10', lines: [{ stockItemId: 'taddy', baseUnits: 300 * ML_PER_PINT }] }],
    days,
    today: '2026-08-19',
  })
  const later = health({
    counts: [{ date: '2026-08-10', lines: [{ stockItemId: 'taddy', baseUnits: 300 * ML_PER_PINT }] }],
    days,
    today: '2026-09-30',
  })
  assert.equal(
    soon.runway[0]?.nightsLeft,
    later.runway[0]?.nightsLeft,
    'the same four nights of trade, so the same answer however long ago they were',
  )
})

test('a line nothing has poured gets no runway rather than an infinite one', () => {
  const h = health({
    counts: [{ date: '2026-08-20', lines: [{ stockItemId: 'taddy', baseUnits: 72 * ML_PER_PINT }] }],
    days: [],
  })
  assert.deepEqual(h.runway, [], 'no rate, so no figure — not "lasts forever"')
})

test('a line that was never counted gets no runway either', () => {
  // Poured, so there is a rate — but nothing to run down, because the last
  // stock take left the line blank and blank is not zero.
  const h = health({
    counts: [{ date: '2026-08-20', lines: [{ stockItemId: 'crisps', baseUnits: 40 }] }],
    items: [taddy, crisps],
    days: [night('2026-08-22', 100)],
  })
  assert.ok(!h.runway.some((r) => r.item.id === 'taddy'))
})

test('the shortest runway comes first, because that is the one to order', () => {
  const h = health({
    items: [taddy, crisps],
    counts: [{ date: '2026-08-19', lines: [{ stockItemId: 'taddy', baseUnits: 300 * ML_PER_PINT }, { stockItemId: 'crisps', baseUnits: 80 }] }],
    days: [
      { date: '2026-08-22', items: [
        { code: '1', name: 'PINT TADDY LAGER', qtyMilli: 70 * 1000 },
        { code: '9', name: 'CRISPS', qtyMilli: 70 * 1000 },
      ] },
    ],
  })
  assert.equal(h.runway[0]?.item.id, 'crisps', 'ten packets left against 230 pints')
  assert.ok((h.runway[0]?.nightsLeft ?? 99) < (h.runway[1]?.nightsLeft ?? 0))
})

test('a line already gone reads as no nights left rather than a negative', () => {
  const h = health({
    counts: [{ date: '2026-08-19', lines: [{ stockItemId: 'taddy', baseUnits: 10 * ML_PER_PINT }] }],
    days: [night('2026-08-22', 40)],
  })
  const r = h.runway.find((x) => x.item.id === 'taddy')!
  assert.equal(r.nightsLeft, 0)
  assert.ok(r.leftBaseUnits < 0, 'the level itself still says how far past empty it went')
})

test('sales the cellar cannot place are named, because they are what makes it lie', () => {
  const h = health({
    counts: [{ date: '2026-08-19', lines: [{ stockItemId: 'taddy', baseUnits: 300 * ML_PER_PINT }] }],
    days: [
      { date: '2026-08-22', items: [
        { code: '1', name: 'PINT TADDY LAGER', qtyMilli: 20 * 1000 },
        { code: '7', name: 'PINT GUINNESS', qtyMilli: 30 * 1000 },
      ] },
      { date: '2026-08-23', items: [{ code: '7', name: 'PINT GUINNESS', qtyMilli: 10 * 1000 }] },
    ],
  })
  assert.equal(h.unmapped.length, 1, 'one line, not one per night')
  assert.equal(h.unmapped[0]?.name, 'PINT GUINNESS')
  assert.equal(h.unmapped[0]?.qtyMilli, 40 * 1000, 'both nights added up')
})

test('sales from before the last stock take are not held against the cellar', () => {
  const h = health({
    counts: [{ date: '2026-08-22', lines: [{ stockItemId: 'taddy', baseUnits: 300 * ML_PER_PINT }] }],
    days: [
      { date: '2026-08-21', items: [{ code: '7', name: 'PINT GUINNESS', qtyMilli: 30 * 1000 }] },
    ],
  })
  assert.deepEqual(h.unmapped, [], 'the count already accounted for that night')
})

test('the cellar says which night it has been read up to', () => {
  const h = health({
    counts: [{ date: '2026-08-19', lines: [{ stockItemId: 'taddy', baseUnits: 300 * ML_PER_PINT }] }],
    days: [night('2026-08-22', 10), night('2026-08-24', 10)],
  })
  assert.equal(h.through, '2026-08-24')
})

test('with no receipt read since the last count, it says so rather than naming a date', () => {
  const h = health({
    counts: [{ date: '2026-08-19', lines: [{ stockItemId: 'taddy', baseUnits: 300 * ML_PER_PINT }] }],
    days: [],
  })
  assert.equal(h.through, null)
})

test('a receipt read for its totals but with no item list is named, not ignored', () => {
  // The quiet way the cellar goes wrong now: the department totals are easy to
  // photograph, the item list runs to another frame, and a night captured
  // without it takes nothing off the cellar at all.
  const h = health({
    counts: [{ date: '2026-08-19', lines: [{ stockItemId: 'taddy', baseUnits: 300 * ML_PER_PINT }] }],
    days: [
      night('2026-08-22', 40),
      { date: '2026-08-23', items: [], hasZRead: true },
      { date: '2026-08-24', items: [], hasZRead: true },
      // No receipt at all — nothing to fix, so it is not counted here.
      { date: '2026-08-25', items: [] },
    ],
  })
  assert.equal(h.nightsWithoutItems, 2)
  assert.equal(h.readNights, 1, 'only the night with an item list sets the rate')
})

test('a night before the last stock take is not held against the cellar either', () => {
  const h = health({
    counts: [{ date: '2026-08-24', lines: [{ stockItemId: 'taddy', baseUnits: 300 * ML_PER_PINT }] }],
    days: [{ date: '2026-08-23', items: [], hasZRead: true }],
  })
  assert.equal(h.nightsWithoutItems, 0)
})
