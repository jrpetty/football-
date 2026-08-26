import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { likeForLike, YEAR_IN_DAYS, type DayStats } from '../src/core/analytics.ts'
import { addDays, weekdayOf } from '../src/core/date.ts'

function night(date: string, takingsPence: number | null): DayStats {
  return {
    date, weekday: weekdayOf(date), takingsPence, cashPence: null, cardPence: null,
    guestCount: null, avePence: null, departments: [], variancePence: null,
    cashVariancePence: null, cardVariancePence: null, verdict: 'incomplete',
    hasZRead: false, voidCount: null, voidPence: null, noSaleCount: null,
    clerks: [], items: [],
  }
}

test('a year back lands on the same weekday', () => {
  // The reason 364 and not 365: comparing a Saturday with a Friday in a trade
  // where one takes four times the other is worse than not comparing.
  assert.equal(weekdayOf('2026-08-23'), weekdayOf(addDays('2026-08-23', -YEAR_IN_DAYS)))
})

test('growth is reported across nights that traded in both years', () => {
  const days = [
    night('2026-08-22', 200000),
    night('2026-08-23', 100000),
    night(addDays('2026-08-22', -YEAR_IN_DAYS), 160000),
    night(addDays('2026-08-23', -YEAR_IN_DAYS), 80000),
  ]
  const r = likeForLike(days, '2026-08-22', '2026-08-23')
  assert.equal(r.matchedNights, 2)
  assert.equal(r.matchedPence, 300000)
  assert.equal(r.matchedLastYearPence, 240000)
  assert.equal(r.changeBp, 2500) // up a quarter
})

test('extra opening days do not read as growth', () => {
  // This year the pub opened an extra night. Totting both windows up would
  // claim growth the pub has not had; the matched comparison does not.
  const days = [
    night('2026-08-22', 200000),
    night('2026-08-23', 100000),
    night(addDays('2026-08-22', -YEAR_IN_DAYS), 200000),
  ]
  const r = likeForLike(days, '2026-08-22', '2026-08-23')
  assert.equal(r.nights, 2)
  assert.equal(r.lastYearNights, 1)
  assert.equal(r.matchedNights, 1)
  assert.equal(r.changeBp, 0, 'the night that traded both years is flat, and that is the truth')
  // The raw totals are still reported, so the extra night is visible.
  assert.equal(r.takingsPence, 300000)
  assert.equal(r.lastYearTakingsPence, 200000)
})

test('no history a year back is reported as not comparable', () => {
  const r = likeForLike([night('2026-08-23', 100000)], '2026-08-23', '2026-08-23')
  assert.equal(r.comparable, false)
  assert.equal(r.changeBp, null)
})

test('an unfinished night is left out of both sides', () => {
  const days = [
    night('2026-08-23', null),
    night(addDays('2026-08-23', -YEAR_IN_DAYS), 80000),
  ]
  const r = likeForLike(days, '2026-08-23', '2026-08-23')
  assert.equal(r.matchedNights, 0)
})

test('a fall is reported as a fall', () => {
  const days = [
    night('2026-08-23', 60000),
    night(addDays('2026-08-23', -YEAR_IN_DAYS), 120000),
  ]
  assert.equal(likeForLike(days, '2026-08-23', '2026-08-23').changeBp, -5000)
})
