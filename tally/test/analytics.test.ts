import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  dayStats,
  filterDays,
  lastNDays,
  totals,
  departmentTotals,
  weekdayTotals,
  timeSeries,
  departmentsPresent,
} from '../src/core/analytics.ts'
import { emptyDay, type DayRecord } from '../src/core/types.ts'
import { GARDENERS_ARMS } from './fixtures/gardenersArms.ts'

/** A night built on the real receipt, with the counted figures varied. */
function night(date: string, countedCash = 35180, countedCard = 184100): DayRecord {
  const d = emptyDay(date, 0)
  d.zRead = structuredClone(GARDENERS_ARMS)
  d.card = { pence: countedCard, source: 'manual', edited: false }
  d.cashPence = countedCash
  return d
}

test('flattens a night from its Z read', () => {
  const s = dayStats(night('2026-08-23'))
  assert.equal(s.takingsPence, 219280)
  assert.equal(s.cashPence, 35180)
  assert.equal(s.cardPence, 184100)
  assert.equal(s.guestCount, 267)
  assert.equal(s.avePence, 821)
  assert.equal(s.weekday, 'Sunday', '23 August 2026 was a Sunday')
  assert.equal(s.hasZRead, true)
  assert.equal(s.departments.length, 7)
})

test('gives departments the names a person uses', () => {
  const s = dayStats(night('2026-08-23'))
  assert.equal(s.departments.find((d) => d.code === 'D01')?.label, 'Draught beers')
  assert.equal(s.departments.find((d) => d.code === 'D08')?.label, 'Open food')
})

test('carries both legs of the variance through', () => {
  const s = dayStats(night('2026-08-23', 33980, 184100))
  assert.equal(s.cashVariancePence, -1200)
  assert.equal(s.cardVariancePence, 0)
  assert.equal(s.variancePence, -1200)
  assert.equal(s.verdict, 'short')
})

test('a night with no roll still produces usable stats', () => {
  const d = emptyDay('2026-08-22', 0)
  d.till = { pence: 300000, source: 'manual', edited: false }
  d.card = { pence: 200000, source: 'manual', edited: false }
  d.cashPence = 100000
  const s = dayStats(d)
  assert.equal(s.hasZRead, false)
  assert.equal(s.takingsPence, 300000)
  assert.equal(s.verdict, 'balanced')
  assert.deepEqual(s.departments, [])
})

test('filters by date range inclusively at both ends', () => {
  const stats = ['2026-08-20', '2026-08-21', '2026-08-22'].map((d) => dayStats(night(d)))
  assert.deepEqual(
    filterDays(stats, { from: '2026-08-21', to: '2026-08-22' }).map((s) => s.date),
    ['2026-08-21', '2026-08-22'],
  )
})

test('filters by weekday, which is how "are Fridays short?" gets asked', () => {
  const stats = ['2026-08-20', '2026-08-21', '2026-08-22'].map((d) => dayStats(night(d)))
  const fridays = filterDays(stats, { weekdays: ['Friday'] })
  assert.deepEqual(fridays.map((s) => s.date), ['2026-08-21'])
})

test('filters to only the nights that did not balance', () => {
  const stats = [dayStats(night('2026-08-20')), dayStats(night('2026-08-21', 33980))]
  assert.deepEqual(filterDays(stats, { onlyUnbalanced: true }).map((s) => s.date), ['2026-08-21'])
})

test('counts back the right number of days, inclusive of today', () => {
  assert.equal(lastNDays('2026-08-23', 7), '2026-08-17')
  assert.equal(lastNDays('2026-08-23', 1), '2026-08-23')
  assert.equal(lastNDays('2026-09-01', 7), '2026-08-26', 'across a month end')
})

test('totals a run of nights', () => {
  const stats = ['2026-08-21', '2026-08-22'].map((d) => dayStats(night(d)))
  const t = totals(stats)
  assert.equal(t.nights, 2)
  assert.equal(t.takingsPence, 219280 * 2)
  assert.equal(t.guestCount, 534)
  assert.equal(t.balancedNights, 2)
})

test('averages spend across the whole selection, not across the nightly averages', () => {
  // A quiet night and a busy one must not weigh the same. Two identical nights
  // is the degenerate case, so make them differ.
  const quiet = emptyDay('2026-08-24', 0)
  quiet.zRead = structuredClone(GARDENERS_ARMS)
  quiet.zRead.deptTotal = { qtyMilli: 1000, pence: 10000, percentBp: 10000 }
  quiet.zRead.transaction.guestCount = 5
  quiet.zRead.transaction.avePence = 2000
  const t = totals([dayStats(night('2026-08-23')), dayStats(quiet)])
  assert.equal(t.guestCount, 272)
  assert.equal(t.avePence, Math.round((219280 + 10000) / 272))
  assert.notEqual(t.avePence, Math.round((821 + 2000) / 2), 'not a mean of means')
})

test('keeps a net variance from hiding a bad fortnight', () => {
  // One night £50 over and one £50 short nets to zero, which would read as a
  // fortnight with nothing wrong.
  const stats = [dayStats(night('2026-08-21', 40180)), dayStats(night('2026-08-22', 30180))]
  const t = totals(stats)
  assert.equal(t.netVariancePence, 0)
  assert.equal(t.absVariancePence, 10000, 'two £50 swings, so £100 of movement')
  assert.equal(t.shortNights, 1)
  assert.equal(t.overNights, 1)
})

test('adds departments up across nights, with each share of the total', () => {
  const rows = departmentTotals(['2026-08-21', '2026-08-22'].map((d) => dayStats(night(d))))
  const draught = rows.find((r) => r.code === 'D01')
  assert.equal(draught?.pence, 149225 * 2)
  assert.equal(draught?.qtyMilli, 406000 * 2)
  assert.equal(draught?.percentBp, 6805, 'the same share as one night of it')
  assert.equal(rows.reduce((a, r) => a + r.percentBp, 0) > 9990, true, 'shares account for the whole')
})

test('keeps departments in registry order so a legend never reshuffles', () => {
  const rows = departmentTotals([dayStats(night('2026-08-23'))])
  assert.deepEqual(rows.map((r) => r.code), ['D01', 'D02', 'D03', 'D04', 'D05', 'D07', 'D08'])
})

test('filtering departments re-bases the percentages onto what is shown', () => {
  const stats = [dayStats(night('2026-08-23'))]
  const rows = departmentTotals(stats, ['D01', 'D03'])
  assert.equal(rows.length, 2)
  assert.equal(rows.reduce((a, r) => a + r.percentBp, 0), 10000, 'the two now share 100%')
  assert.equal(rows.find((r) => r.code === 'D01')?.percentBp, 8640)
})

test('groups by weekday for the pattern question', () => {
  const stats = [
    dayStats(night('2026-08-21', 33980)), // Friday, £12 short
    dayStats(night('2026-08-28', 33980)), // Friday, £12 short
    dayStats(night('2026-08-22')), // Saturday, balanced
  ]
  const week = weekdayTotals(stats)
  const friday = week.find((w) => w.weekday === 'Friday')
  assert.equal(friday?.nights, 2)
  assert.equal(friday?.shortNights, 2)
  assert.equal(friday?.avgVariancePence, -1200)
  const saturday = week.find((w) => w.weekday === 'Saturday')
  assert.equal(saturday?.avgVariancePence, 0)
})

test('leaves out weekdays with no trade rather than drawing empty bars', () => {
  const week = weekdayTotals([dayStats(night('2026-08-21'))])
  assert.deepEqual(week.map((w) => w.weekday), ['Friday'])
})

test('orders a trend oldest first', () => {
  const stats = ['2026-08-22', '2026-08-20', '2026-08-21'].map((d) => dayStats(night(d)))
  assert.deepEqual(timeSeries(stats).map((s) => s.date), ['2026-08-20', '2026-08-21', '2026-08-22'])
})

test('lists the departments available to filter on', () => {
  const present = departmentsPresent([dayStats(night('2026-08-23'))])
  assert.equal(present.length, 7)
  assert.equal(present[0]?.label, 'Draught beers')
})

test('an empty selection totals to zero rather than dividing by it', () => {
  const t = totals([])
  assert.equal(t.nights, 0)
  assert.equal(t.avePence, null)
  assert.deepEqual(departmentTotals([]), [])
  assert.deepEqual(weekdayTotals([]), [])
})
