import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { monthlyCsv, monthlyTakings, wageBill, yearEndPack, type YearEndInput } from '../src/core/yearEnd.ts'
import type { DayStats } from '../src/core/analytics.ts'
import { shiftAt, type Person } from '../src/core/rota.ts'
import { ML_PER_PINT, type StockItem } from '../src/core/stock.ts'
import { cellarValue } from '../src/core/margin.ts'
import { weekdayOf } from '../src/core/date.ts'

/** 18:00 to 23:30 — the hours these tests put people on for. Hours belong to
    the night now, so every shift here says which ones it means. */
const EVENING = { startMin: 1080, endMin: 1410 }
const on = (person: Person, date: string, hours = EVENING) => shiftAt(person.id, date, hours)


function night(date: string, takingsPence: number, cashPence = 0, cardPence = 0): DayStats {
  return {
    date, weekday: weekdayOf(date), takingsPence, cashPence, cardPence,
    guestCount: null, avePence: null, departments: [], variancePence: null,
    cashVariancePence: null, cardVariancePence: null, verdict: 'balanced',
    hasZRead: false, voidCount: null, voidPence: null, noSaleCount: null, clerks: [], items: [],
  }
}

const kelly: Person = { id: 'k', name: 'Kelly', slot: 1, ratePencePerHour: 1221 }
const dave: Person = { id: 'd', name: 'Dave', slot: 2 }

// --- by month -------------------------------------------------------------------

test('takings are grouped into months, as rung', () => {
  const months = monthlyTakings([night('2026-01-05', 120000), night('2026-01-06', 80000), night('2026-02-01', 60000)])
  assert.equal(months.length, 2)
  assert.equal(months[0]!.label, 'January 2026')
  assert.equal(months[0]!.nights, 2)
  assert.equal(months[0]!.takingsPence, 200000)
})

test('months come out in order whatever order the nights arrive in', () => {
  const months = monthlyTakings([night('2026-03-01', 100), night('2026-01-01', 100), night('2026-02-01', 100)])
  assert.deepEqual(months.map((m) => m.key), ['2026-01', '2026-02', '2026-03'])
})

test('an unfinished night is left out rather than counted as nothing', () => {
  const unfinished = { ...night('2026-01-05', 0), takingsPence: null }
  const months = monthlyTakings([night('2026-01-06', 80000), unfinished])
  assert.equal(months[0]!.nights, 1)
})

test('the cash and card split is carried through', () => {
  const months = monthlyTakings([night('2026-01-05', 120000, 20000, 100000)])
  assert.equal(months[0]!.cashPence, 20000)
  assert.equal(months[0]!.cardPence, 100000)
})

// --- wages -----------------------------------------------------------------------

test('wages total the hours rostered and cost them at each rate', () => {
  const shifts = [on(kelly, '2026-01-05'), on(kelly, '2026-01-06'), on(dave, '2026-01-05')]
  const rows = wageBill(shifts, [kelly, dave], '2026-01-01', '2026-12-31')
  const k = rows.find((r) => r.name === 'Kelly')!
  assert.equal(k.shifts, 2)
  assert.equal(k.minutes, 660)
  assert.equal(k.costPence, 13431) // 11 hours at £12.21
})

test('somebody with no rate is reported as unknown, never as free', () => {
  const rows = wageBill([on(dave, '2026-01-05')], [kelly, dave], '2026-01-01', '2026-12-31')
  assert.equal(rows[0]!.costPence, null)
})

test('shifts outside the year are not counted', () => {
  const shifts = [on(kelly, '2025-12-31'), on(kelly, '2026-01-05')]
  assert.equal(wageBill(shifts, [kelly], '2026-01-01', '2026-12-31')[0]!.shifts, 1)
})

test('a shift by someone since gone is still counted', () => {
  // Their hours were worked and paid; losing them would understate the year.
  const rows = wageBill([on(kelly, '2026-01-05')], [], '2026-01-01', '2026-12-31')
  assert.equal(rows.length, 1)
  assert.match(rows[0]!.name, /no longer on the books/)
})

// --- the whole pack ----------------------------------------------------------------

const taddy: StockItem = {
  id: 'taddy', name: 'Taddy Lager', kind: 'liquid',
  servingBaseUnits: ML_PER_PINT, servingName: 'pint',
  cost: { pence: 9500, baseUnits: 72 * ML_PER_PINT },
}

function pack(over: Partial<YearEndInput> = {}): string {
  return yearEndPack({
    from: '2026-01-01',
    to: '2026-12-31',
    days: [night('2026-01-05', 120000, 20000, 100000), night('2026-02-05', 80000, 10000, 70000)],
    shifts: [on(kelly, '2026-01-05')],
    people: [kelly],
    cellar: cellarValue([
      { item: taddy, countedBaseUnits: 0, deliveredBaseUnits: 0, pouredBaseUnits: 0, expectedBaseUnits: 72 * ML_PER_PINT },
    ]),
    ...over,
  })
}

test('the pack says up front that it is not a return', () => {
  // The single most important line in the document.
  assert.match(pack(), /WORKING FIGURES, NOT A RETURN/)
})

test('it carries the summary, the months, the stock and the wages', () => {
  const text = pack()
  assert.match(text, /Takings\s+£2,000\.00/)
  assert.match(text, /January 2026/)
  assert.match(text, /February 2026/)
  assert.match(text, /Value on hand\s+£95\.00/)
  assert.match(text, /Kelly/)
})

test('no tax arithmetic appears anywhere in the pack', () => {
  // The deliberate basis of the whole app: takings as rung, costs as invoiced.
  // The tax side belongs to the accountant, and the document says whose it is.
  const text = pack({ purchasesPence: 50000 })
  assert.equal(/VAT/.test(text), false)
  assert.match(text, /The tax side is yours/)
})

test('the monthly rows add up to the total', () => {
  const text = pack()
  // £1,200 + £800 gross, so the total line has to say £2,000.
  assert.match(text, /Total\s+2\s+£2,000\.00/)
})

test('with purchases entered, what was bought in is a plain line', () => {
  const text = pack({ purchasesPence: 50000 })
  assert.match(text, /STOCK BOUGHT IN/)
  assert.match(text, /Over the period\s+£500\.00/)
  assert.match(text, /purchase invoices, which remain the record/)
})

test('without purchases the section is simply absent', () => {
  assert.equal(/STOCK BOUGHT IN/.test(pack({ purchasesPence: null })), false)
})

test('uncosted stock is flagged so the valuation is not read as complete', () => {
  const uncosted: StockItem = { ...taddy, cost: undefined as never }
  const text = pack({
    cellar: cellarValue([
      { item: uncosted, countedBaseUnits: 0, deliveredBaseUnits: 0, pouredBaseUnits: 0, expectedBaseUnits: 72 * ML_PER_PINT },
    ]),
  })
  assert.match(text, /no cost entered, so the real figure is higher/)
})

test('rostered hours are labelled as a cross-check, not as payroll', () => {
  assert.match(pack(), /Rostered hours, not payroll/)
})

test('a year with nothing in it still produces a document', () => {
  const text = pack({ days: [], shifts: [], cellar: null })
  assert.match(text, /Nights traded\s+0/)
  assert.match(text, /WORKING FIGURES/)
})

// --- the spreadsheet -----------------------------------------------------------------

test('the csv has a header and a row per month in pounds', () => {
  const csv = monthlyCsv(monthlyTakings([night('2026-01-05', 120000, 20000, 100000)]))
  const [header, row] = csv.split('\n')
  assert.equal(header, 'Month,Nights,Takings,Cash,Card')
  assert.match(row as string, /^January 2026,1,1200\.00,200\.00,1000\.00$/)
})

test('wages are costed off the total hours, matching the staff profile exactly', () => {
  // Rounding each shift separately drifts a penny at a time, and the year-end
  // pack would then disagree with the same person's own profile.
  const shifts = [on(kelly, '2026-01-05'), on(kelly, '2026-01-06')]
  const row = wageBill(shifts, [kelly], '2026-01-01', '2026-12-31')[0]!
  assert.equal(row.costPence, Math.round((row.minutes * 1221) / 60))
})
