import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { monthlyCsv, monthlyTakings, wageBill, yearEndPack, type YearEndInput } from '../src/core/yearEnd.ts'
import type { DayStats } from '../src/core/analytics.ts'
import { shiftFor, type Person } from '../src/core/rota.ts'
import { ML_PER_PINT, type StockItem } from '../src/core/stock.ts'
import { cellarValue } from '../src/core/margin.ts'
import { weekdayOf } from '../src/core/date.ts'

const VAT = 2000

function night(date: string, takingsPence: number, cashPence = 0, cardPence = 0): DayStats {
  return {
    date, weekday: weekdayOf(date), takingsPence, cashPence, cardPence,
    guestCount: null, avePence: null, departments: [], variancePence: null,
    cashVariancePence: null, cardVariancePence: null, verdict: 'balanced',
    hasZRead: false, voidCount: null, voidPence: null, noSaleCount: null, clerks: [], items: [],
  }
}

const kelly: Person = { id: 'k', name: 'Kelly', slot: 1, defaultStartMin: 1080, defaultEndMin: 1410, ratePencePerHour: 1221 }
const dave: Person = { id: 'd', name: 'Dave', slot: 2, defaultStartMin: 1080, defaultEndMin: 1410 }

// --- by month -------------------------------------------------------------------

test('takings are grouped into months with VAT taken out of the gross', () => {
  const months = monthlyTakings([night('2026-01-05', 120000), night('2026-01-06', 80000), night('2026-02-01', 60000)], VAT)
  assert.equal(months.length, 2)
  assert.equal(months[0]!.label, 'January 2026')
  assert.equal(months[0]!.nights, 2)
  assert.equal(months[0]!.takingsPence, 200000)
  // £2,000 gross is £1,666.67 net and £333.33 of VAT — inside the figure, not on top.
  assert.equal(months[0]!.netPence, 166667)
  assert.equal(months[0]!.vatPence, 33333)
})

test('months come out in order whatever order the nights arrive in', () => {
  const months = monthlyTakings([night('2026-03-01', 100), night('2026-01-01', 100), night('2026-02-01', 100)], VAT)
  assert.deepEqual(months.map((m) => m.key), ['2026-01', '2026-02', '2026-03'])
})

test('an unfinished night is left out rather than counted as nothing', () => {
  const unfinished = { ...night('2026-01-05', 0), takingsPence: null }
  const months = monthlyTakings([night('2026-01-06', 80000), unfinished], VAT)
  assert.equal(months[0]!.nights, 1)
})

test('the cash and card split is carried through', () => {
  const months = monthlyTakings([night('2026-01-05', 120000, 20000, 100000)], VAT)
  assert.equal(months[0]!.cashPence, 20000)
  assert.equal(months[0]!.cardPence, 100000)
})

// --- wages -----------------------------------------------------------------------

test('wages total the hours rostered and cost them at each rate', () => {
  const shifts = [shiftFor(kelly, '2026-01-05'), shiftFor(kelly, '2026-01-06'), shiftFor(dave, '2026-01-05')]
  const rows = wageBill(shifts, [kelly, dave], '2026-01-01', '2026-12-31')
  const k = rows.find((r) => r.name === 'Kelly')!
  assert.equal(k.shifts, 2)
  assert.equal(k.minutes, 660)
  assert.equal(k.costPence, 13431) // 11 hours at £12.21
})

test('somebody with no rate is reported as unknown, never as free', () => {
  const rows = wageBill([shiftFor(dave, '2026-01-05')], [kelly, dave], '2026-01-01', '2026-12-31')
  assert.equal(rows[0]!.costPence, null)
})

test('shifts outside the year are not counted', () => {
  const shifts = [shiftFor(kelly, '2025-12-31'), shiftFor(kelly, '2026-01-05')]
  assert.equal(wageBill(shifts, [kelly], '2026-01-01', '2026-12-31')[0]!.shifts, 1)
})

test('a shift by someone since gone is still counted', () => {
  // Their hours were worked and paid; losing them would understate the year.
  const rows = wageBill([shiftFor(kelly, '2026-01-05')], [], '2026-01-01', '2026-12-31')
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
    shifts: [shiftFor(kelly, '2026-01-05')],
    people: [kelly],
    cellar: cellarValue([
      { item: taddy, countedBaseUnits: 0, deliveredBaseUnits: 0, pouredBaseUnits: 0, expectedBaseUnits: 72 * ML_PER_PINT },
    ]),
    vatBp: VAT,
    ...over,
  })
}

test('the pack says up front that it is not a return', () => {
  // The single most important line in the document.
  assert.match(pack(), /WORKING FIGURES, NOT A RETURN/)
})

test('it carries the summary, the months, the stock and the wages', () => {
  const text = pack()
  assert.match(text, /Takings \(gross\)\s+£2,000\.00/)
  assert.match(text, /January 2026/)
  assert.match(text, /February 2026/)
  assert.match(text, /Value on hand\s+£95\.00/)
  assert.match(text, /Kelly/)
  assert.match(text, /VAT, ROUGHLY/)
})

test('the monthly rows add up to the total', () => {
  const text = pack()
  // £1,200 + £800 gross, so the total line has to say £2,000.
  assert.match(text, /Total\s+2\s+£2,000\.00/)
})

test('with purchases entered it shows both sides of the VAT', () => {
  const text = pack({ purchasesPence: 50000 })
  assert.match(text, /On stock bought \(input\)\s+£100\.00/)
  assert.match(text, /Difference/)
  assert.match(text, /comes off the purchase invoices/)
})

test('without purchases it shows one side and says so', () => {
  assert.match(pack({ purchasesPence: null }), /only the output side is shown/)
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
  const csv = monthlyCsv(monthlyTakings([night('2026-01-05', 120000, 20000, 100000)], VAT))
  const [header, row] = csv.split('\n')
  assert.equal(header, 'Month,Nights,Gross,Net,VAT,Cash,Card')
  assert.match(row as string, /^January 2026,1,1200\.00,1000\.00,200\.00,200\.00,1000\.00$/)
})

test('wages are costed off the total hours, matching the staff profile exactly', () => {
  // Rounding each shift separately drifts a penny at a time, and the year-end
  // pack would then disagree with the same person's own profile.
  const shifts = [shiftFor(kelly, '2026-01-05'), shiftFor(kelly, '2026-01-06')]
  const row = wageBill(shifts, [kelly], '2026-01-01', '2026-12-31')[0]!
  assert.equal(row.costPence, Math.round((row.minutes * 1221) / 60))
})
