// ---------------------------------------------------------------------------
// Categories and lines over time.
//
// Three ways this could produce a confident wrong answer, and one test each:
// counting a night with no item list as a night that sold nothing, comparing a
// part-finished week with a whole one, and calling two nights a trend.
// ---------------------------------------------------------------------------

import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import {
  categoryWeeks,
  itemWeeks,
  MOVER_BP,
  movers,
  takingsWeeks,
  weekLabel,
  weeksBack,
} from '../src/core/trends.ts'
import type { DayStats } from '../src/core/analytics.ts'
import { weekdayOf } from '../src/core/date.ts'

interface Sold {
  code: string
  name: string
  qty: number
  pounds: number
}

function night(date: string, sold: Sold[] = [], over: Partial<DayStats> = {}): DayStats {
  const pence = sold.reduce((a, s) => a + Math.round(s.pounds * 100), 0)
  return {
    date,
    weekday: weekdayOf(date),
    takingsPence: pence || 100000,
    cashPence: null, cardPence: null, guestCount: null, avePence: null,
    departments: [],
    variancePence: null, cashVariancePence: null, cardVariancePence: null,
    verdict: 'balanced',
    hasZRead: true,
    voidCount: null, voidPence: null, noSaleCount: null, clerks: [],
    items: sold.map((s) => ({ code: s.code, name: s.name, qtyMilli: s.qty * 1000, pence: Math.round(s.pounds * 100) })),
    ...over,
  }
}

function dept(date: string, rows: Array<[string, string, number, number]>, over: Partial<DayStats> = {}): DayStats {
  return night(date, [], {
    departments: rows.map(([code, label, pounds, qty]) => ({ code, label, pence: Math.round(pounds * 100), qtyMilli: qty * 1000 })),
    ...over,
  })
}

// --- the weeks themselves ------------------------------------------------------

test('weeks run Monday to Sunday and end with the one we are in', () => {
  // Thursday 17 September 2026.
  const weeks = weeksBack('2026-09-17', 3)
  assert.deepEqual(weeks, ['2026-08-31', '2026-09-07', '2026-09-14'])
  assert.equal(weekdayOf(weeks[0]!), 'Monday')
})

test('a week is labelled by the Monday it opens on', () => {
  assert.equal(weekLabel('2026-09-14'), '14 Sept')
})

test('a quiet week in the middle is a zero, not a missing point', () => {
  const s = categoryWeeks(
    [
      dept('2026-09-01', [['D01', 'DRAUGHT BEERS', 900, 200]]),
      // Nothing at all the week of the 7th — shut, or simply not entered.
      dept('2026-09-15', [['D01', 'DRAUGHT BEERS', 700, 150]]),
    ],
    '2026-09-17',
    4,
  )
  assert.equal(s.length, 1)
  assert.deepEqual(s[0]?.buckets.map((b) => b.pence), [90000, 0, 70000])
})

test('weeks before the records start are not weeks the pub took nothing', () => {
  // A pub three weeks in would otherwise open on nine weeks of floor, which
  // makes the chart unreadable and says something untrue.
  const s = categoryWeeks([dept('2026-09-15', [['D01', 'DRAUGHT BEERS', 900, 200]])], '2026-09-17', 8)
  assert.equal(s[0]?.buckets.length, 1)
  assert.equal(s[0]?.buckets[0]?.start, '2026-09-14')
})

// --- the three ways to be confidently wrong ------------------------------------

test('a night with no item list does not count as a night that sold nothing', () => {
  const stats = [
    night('2026-09-07', [{ code: '1', name: 'PINT TADDY', qty: 100, pounds: 400 }]),
    night('2026-09-08', [{ code: '1', name: 'PINT TADDY', qty: 100, pounds: 400 }]),
    night('2026-09-09', [{ code: '1', name: 'PINT TADDY', qty: 100, pounds: 400 }]),
    // Captured for its totals, no item list on it.
    night('2026-09-10', [], { hasZRead: true }),
  ]
  const s = itemWeeks(stats, '2026-09-17', '1', 'PINT TADDY', 2)
  const week = s.buckets.find((b) => b.start === '2026-09-07')!
  assert.equal(week.rollNights, 3, 'three nights carried an item list, not four')
  assert.equal(week.nights, 4, 'all four traded')
})

test('a week still being traded is carried but marked partial', () => {
  const s = takingsWeeks([night('2026-09-08'), night('2026-09-15'), night('2026-09-16')], '2026-09-17', 2)
  const thisWeek = s.buckets[s.buckets.length - 1]!
  assert.equal(thisWeek.start, '2026-09-14')
  assert.equal(thisWeek.partial, true)
  assert.equal(s.buckets[0]?.partial, false, 'a week that is over is over')
})

test('a week missing a night’s item list is partial too, because its quantities are short', () => {
  const stats = [
    night('2026-08-31', [{ code: '1', name: 'PINT TADDY', qty: 100, pounds: 400 }]),
    night('2026-09-01', [], { hasZRead: true }),
  ]
  const s = itemWeeks(stats, '2026-09-17', '1', 'PINT TADDY', 3)
  assert.equal(s.buckets.find((b) => b.start === '2026-08-31')?.partial, true)
})

test('a part-finished week is never what the change is measured against', () => {
  const full = (date: string, pounds: number) => night(date, [{ code: '1', name: 'PINT TADDY', qty: 100, pounds }])
  const stats = [
    // Two whole weeks, flat.
    ...['2026-08-31', '2026-09-01', '2026-09-02', '2026-09-03'].map((d) => full(d, 400)),
    ...['2026-09-07', '2026-09-08', '2026-09-09', '2026-09-10'].map((d) => full(d, 400)),
    // This week: one night in, which would read as a 75% collapse.
    full('2026-09-14', 400),
  ]
  const s = itemWeeks(stats, '2026-09-17', '1', 'PINT TADDY', 4)
  assert.equal(s.changeBp, 0, 'flat, because the part-week was left out of it')
})

test('too few nights on either side is no trend at all', () => {
  const stats = [
    night('2026-09-01', [{ code: '1', name: 'PINT TADDY', qty: 100, pounds: 400 }]),
    night('2026-09-08', [{ code: '1', name: 'PINT TADDY', qty: 50, pounds: 200 }]),
  ]
  const s = itemWeeks(stats, '2026-09-17', '1', 'PINT TADDY', 4)
  assert.equal(s.changeBp, null, 'one night against one night is not a halving')
})

test('a real week-on-week move is stated in basis points of the nightly rate', () => {
  const week = (monday: string, pounds: number) =>
    [0, 1, 2, 3].map((i) => {
      const d = new Date(`${monday}T00:00:00`)
      d.setDate(d.getDate() + i)
      return night(d.toISOString().slice(0, 10), [{ code: '1', name: 'PINT TADDY', qty: 100, pounds }])
    })
  const s = itemWeeks([...week('2026-08-31', 400), ...week('2026-09-07', 300)], '2026-09-17', '1', 'PINT TADDY', 4)
  assert.equal(s.changeBp, -2500, 'four hundred to three hundred is a quarter off')
})

// --- rising and falling ----------------------------------------------------------

function run(from: string, nights: number, sold: (i: number) => Sold[]): DayStats[] {
  const out: DayStats[] = []
  const d = new Date(`${from}T00:00:00`)
  for (let i = 0; i < nights; i++) {
    out.push(night(d.toISOString().slice(0, 10), sold(i)))
    d.setDate(d.getDate() + 1)
  }
  return out
}

test('a line halving over a month is reported as falling', () => {
  const stats = run('2026-07-01', 24, () => [{ code: '1', name: 'PINT ALPINE', qty: 40, pounds: 200 }]).concat(
    run('2026-07-25', 24, () => [{ code: '1', name: 'PINT ALPINE', qty: 20, pounds: 100 }]),
  )
  const { falling, rising } = movers(stats, 24)
  assert.equal(rising.length, 0)
  assert.equal(falling[0]?.label, 'PINT ALPINE')
  assert.equal(falling[0]?.changeBp, -5000)
  assert.equal(falling[0]?.nights, 24)
  assert.equal(falling[0]?.beforeNights, 24)
})

test('a line that stopped selling entirely is the most important mover there is', () => {
  const stats = run('2026-07-01', 24, () => [
    { code: '1', name: 'PINT ALPINE', qty: 40, pounds: 200 },
    { code: '2', name: 'PINT TADDY', qty: 40, pounds: 200 },
  ]).concat(run('2026-07-25', 24, () => [{ code: '2', name: 'PINT TADDY', qty: 40, pounds: 200 }]))
  const { falling } = movers(stats, 24)
  assert.equal(falling[0]?.label, 'PINT ALPINE')
  assert.equal(falling[0]?.changeBp, -10000, 'gone altogether')
  assert.equal(falling[0]?.nowPencePerNight, 0)
})

test('a brand new line is new, not up by an infinite percentage', () => {
  const stats = run('2026-07-01', 24, () => [{ code: '2', name: 'PINT TADDY', qty: 40, pounds: 200 }]).concat(
    run('2026-07-25', 24, () => [
      { code: '2', name: 'PINT TADDY', qty: 40, pounds: 200 },
      { code: '3', name: 'PINT GUINNESS', qty: 40, pounds: 200 },
    ]),
  )
  const { rising } = movers(stats, 24)
  assert.ok(!rising.some((m) => m.label === 'PINT GUINNESS'), 'nothing to divide by')
})

test('a small wobble is not a mover', () => {
  const stats = run('2026-07-01', 24, () => [{ code: '1', name: 'PINT ALPINE', qty: 40, pounds: 200 }]).concat(
    run('2026-07-25', 24, () => [{ code: '1', name: 'PINT ALPINE', qty: 41, pounds: 205 }]),
  )
  const { rising, falling } = movers(stats, 24)
  assert.deepEqual([...rising, ...falling], [], `under ${MOVER_BP / 100}% is noise`)
})

test('a tiny line swinging wildly is left out, because it is not worth a decision', () => {
  const stats = run('2026-07-01', 24, () => [{ code: '9', name: 'PICKLED EGG', qty: 1, pounds: 0.5 }]).concat(
    run('2026-07-25', 24, () => [{ code: '9', name: 'PICKLED EGG', qty: 4, pounds: 2 }]),
  )
  assert.deepEqual(movers(stats, 24).rising, [])
})

test('with too little history, nothing is claimed either way', () => {
  const stats = run('2026-07-01', 4, () => [{ code: '1', name: 'PINT ALPINE', qty: 40, pounds: 200 }])
  assert.deepEqual(movers(stats, 24), { rising: [], falling: [] })
})

test('categories move as well as lines, and say which they are', () => {
  const withDept = (from: string, nights: number, pounds: number) =>
    run(from, nights, () => []).map((n) => ({
      ...n,
      departments: [{ code: 'D03', label: 'WINE', pence: pounds * 100, qtyMilli: 40_000 }],
    }))
  const { rising } = movers([...withDept('2026-07-01', 24, 100), ...withDept('2026-07-25', 24, 200)], 24)
  assert.equal(rising[0]?.kind, 'category')
  assert.equal(rising[0]?.label, 'Wine')
  assert.equal(rising[0]?.changeBp, 10000)
})

test('a pub photographing only the top of the roll still gets its categories', () => {
  // The department section is one frame; the item list runs to another. Told
  // nothing at all would be the worst answer for the commonest half-capture.
  const topOnly = (from: string, nights: number, pounds: number) =>
    run(from, nights, () => []).map((n) => ({
      ...n,
      departments: [{ code: 'D01', label: 'DRAUGHT BEERS', pence: pounds * 100, qtyMilli: 400_000 }],
    }))
  const { falling } = movers([...topOnly('2026-07-01', 24, 1500), ...topOnly('2026-07-25', 24, 1000)], 24)
  assert.equal(falling[0]?.kind, 'category')
  assert.equal(falling[0]?.changeBp, -3333)
})

test('a night captured for its totals alone does not make the takings week partial', () => {
  // The takings are complete. Only the levels the photographs missed are short,
  // and flagging all three would be as wrong as flagging none.
  const stats = [
    night('2026-09-07', [{ code: '1', name: 'PINT TADDY', qty: 100, pounds: 400 }]),
    { ...night('2026-09-08'), items: [], departments: [] },
  ]
  assert.equal(takingsWeeks(stats, '2026-09-17', 2).buckets[0]?.partial, false)
  assert.equal(itemWeeks(stats, '2026-09-17', '1', 'PINT TADDY', 2).buckets[0]?.partial, true)
})

test('a night captured down to its departments is a whole night for a category', () => {
  const stats = [
    dept('2026-09-07', [['D01', 'DRAUGHT BEERS', 900, 200]]),
    // Departments read, item list missed — complete for a category, not for a line.
    { ...dept('2026-09-08', [['D01', 'DRAUGHT BEERS', 800, 180]]), items: [] },
  ]
  const cat = categoryWeeks(stats, '2026-09-17', 2)[0]!
  assert.equal(cat.buckets[0]?.partial, false)
  assert.equal(cat.buckets[0]?.rollNights, 2, 'both nights had their departments read')
  assert.equal(itemWeeks(stats, '2026-09-17', '1', 'PINT TADDY', 2).buckets[0]?.partial, true)
})
