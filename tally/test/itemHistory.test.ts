import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { itemProfile, searchItems } from '../src/core/itemHistory.ts'
import type { DayStats } from '../src/core/analytics.ts'
import { addDays, weekdayOf } from '../src/core/date.ts'

function night(date: string, items: Array<{ code: string; name: string; qty: number; pence: number }>): DayStats {
  return {
    date, weekday: weekdayOf(date), takingsPence: 200000, cashPence: null, cardPence: null,
    guestCount: null, avePence: null, departments: [], variancePence: null,
    cashVariancePence: null, cardVariancePence: null, verdict: 'balanced',
    hasZRead: items.length > 0, voidCount: null, voidPence: null, noSaleCount: null, clerks: [],
    items: items.map((i) => ({ code: i.code, name: i.name, qtyMilli: i.qty * 1000, pence: i.pence })),
  }
}

const taddy = (qty: number, pence: number) => ({ code: '1', name: 'PINT TADDY LAGER', qty, pence })

test('one item is followed across its nights, matched by code', () => {
  const p = itemProfile(
    [
      night('2026-08-21', [taddy(100, 40000)]),
      night('2026-08-22', [taddy(120, 48000)]),
      night('2026-08-23', [{ code: '9', name: 'CRISPS', qty: 20, pence: 3400 }]),
    ],
    '1',
    'PINT TADDY LAGER',
  )
  assert.equal(p.nights.length, 2)
  assert.equal(p.totalQtyMilli, 220_000)
  assert.equal(p.totalPence, 88000)
  assert.equal(p.avgPencePerItem, 400)
  assert.equal(p.firstSeen, '2026-08-21')
  assert.equal(p.lastSeen, '2026-08-22')
})

test('a renamed till code is still the same item by its printed name', () => {
  const p = itemProfile(
    [
      night('2026-08-21', [{ code: '1', name: 'PINT TADDY LAGER', qty: 50, pence: 20000 }]),
      night('2026-08-22', [{ code: '41', name: 'Pint Taddy Lager', qty: 60, pence: 24000 }]),
    ],
    '',
    'PINT TADDY LAGER',
  )
  assert.equal(p.nights.length, 2, 'the name carries identity when the code moved')
})

test('nights without a roll do not drag the rate down', () => {
  // Two roll-nights selling 100 each, plus five typed-only nights. The rate is
  // per roll-night, or skipping the photographs would read as trade dying.
  const days = [
    night('2026-08-22', [taddy(100, 40000)]),
    night('2026-08-23', [taddy(100, 40000)]),
    ...Array.from({ length: 5 }, (_, i) => night(addDays('2026-08-16', i), [])),
  ]
  const p = itemProfile(days, '1', 'PINT TADDY LAGER')
  assert.equal(p.nightsWithRoll, 2)
  // Two roll-nights a day apart: 200 pints across a two-day span is 700 a week.
  assert.equal(p.perWeek, 700)
})

test('the weekday split knows which nights sell it', () => {
  const p = itemProfile(
    [
      night('2026-08-21', [taddy(80, 32000)]), // Friday
      night('2026-08-22', [taddy(120, 48000)]), // Saturday
      night('2026-08-28', [taddy(90, 36000)]), // Friday again
    ],
    '1',
    'PINT TADDY LAGER',
  )
  const friday = p.byWeekday.find((w) => w.weekday === 'Friday')!
  const saturday = p.byWeekday.find((w) => w.weekday === 'Saturday')!
  const monday = p.byWeekday.find((w) => w.weekday === 'Monday')!
  assert.equal(friday.qtyMilli, 170_000)
  assert.equal(saturday.qtyMilli, 120_000)
  assert.equal(monday.qtyMilli, 0)
  assert.equal(p.byWeekday[0]!.weekday, 'Monday', 'Monday first, like every weekday list in the app')
})

test('a real decline shows as a falling rate', () => {
  // Eight weeks of rolls: the first month sells 100 a night, the last month 50.
  const days = Array.from({ length: 56 }, (_, i) => {
    const date = addDays('2026-07-01', i)
    return night(date, [taddy(i < 28 ? 100 : 50, i < 28 ? 40000 : 20000)])
  })
  const p = itemProfile(days, '1', 'PINT TADDY LAGER')
  assert.equal(p.recentChangeBp, -5000, 'half the rate is minus fifty percent')
})

test('too little history withholds the trend rather than inventing one', () => {
  const days = Array.from({ length: 8 }, (_, i) => night(addDays('2026-08-01', i), [taddy(50, 20000)]))
  const p = itemProfile(days, '1', 'PINT TADDY LAGER')
  assert.equal(p.recentChangeBp, null, 'eight nights cannot fill both four-week halves')
})

test('an item that vanished still measures over the full roll span', () => {
  // Sold for the first week, gone since. The rate must fall as weeks pass,
  // not freeze at the last good week.
  const days = Array.from({ length: 28 }, (_, i) =>
    night(addDays('2026-08-01', i), i < 7 ? [taddy(70, 28000)] : [{ code: '9', name: 'CRISPS', qty: 5, pence: 850 }]),
  )
  const p = itemProfile(days, '1', 'PINT TADDY LAGER')
  assert.equal(p.perWeek, Math.round(((490 / 28) * 7) * 10) / 10)
  assert.equal(p.lastSeen, '2026-08-07')
})

test('an item never sold comes back empty rather than crashing', () => {
  const p = itemProfile([night('2026-08-22', [taddy(100, 40000)])], '99', 'GHOST ALE')
  assert.equal(p.nights.length, 0)
  assert.equal(p.avgPencePerItem, null)
  assert.equal(p.firstSeen, null)
})

test('search finds by part of a name, whatever the case', () => {
  const items = [
    { code: '1', name: 'PINT TADDY LAGER' },
    { code: '2', name: 'HALF TADDY LAGER' },
    { code: '9', name: 'CRISPS' },
  ]
  assert.equal(searchItems(items, 'taddy').length, 2)
  assert.equal(searchItems(items, 'CriSp').length, 1)
  assert.equal(searchItems(items, '').length, 3, 'no query means everything')
  assert.equal(searchItems(items, 'guinness').length, 0)
})
