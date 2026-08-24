import { test } from 'node:test'
import assert from 'node:assert/strict'
import { tradingDayKey, dateKey, fromDateKey, addDays, formatLong } from '../src/core/date.ts'

test('an evening count belongs to that evening', () => {
  assert.equal(tradingDayKey(new Date(2026, 7, 21, 23, 30)), '2026-08-21')
})

test('a count after midnight belongs to the night before', () => {
  // Half past midnight on Saturday is Friday's trade. Getting this wrong would
  // mislabel every late night, invisibly.
  assert.equal(tradingDayKey(new Date(2026, 7, 22, 0, 30)), '2026-08-21')
  assert.equal(tradingDayKey(new Date(2026, 7, 22, 4, 59)), '2026-08-21')
})

test('past the cutoff it is a new day again', () => {
  assert.equal(tradingDayKey(new Date(2026, 7, 22, 5, 0)), '2026-08-22')
  assert.equal(tradingDayKey(new Date(2026, 7, 22, 11, 0)), '2026-08-22')
})

test('rolls back across a month and a year boundary', () => {
  assert.equal(tradingDayKey(new Date(2026, 8, 1, 1, 0)), '2026-08-31')
  assert.equal(tradingDayKey(new Date(2026, 0, 1, 2, 0)), '2025-12-31')
})

test('rolls back across a leap day', () => {
  assert.equal(tradingDayKey(new Date(2028, 2, 1, 1, 0)), '2028-02-29')
})

test('date keys are local, not UTC', () => {
  // Late on a British Summer Time evening, toISOString() is already tomorrow.
  const late = new Date(2026, 5, 30, 23, 30)
  assert.equal(dateKey(late), '2026-06-30')
})

test('a date key parses back to the same local day', () => {
  const d = fromDateKey('2026-08-21')
  assert.equal(d.getFullYear(), 2026)
  assert.equal(d.getMonth(), 7)
  assert.equal(d.getDate(), 21)
})

test('rejects a malformed key rather than inventing a date', () => {
  assert.ok(Number.isNaN(fromDateKey('not-a-date').getTime()))
  assert.equal(formatLong('not-a-date'), 'not-a-date')
})

test('shifts by whole days across month ends', () => {
  assert.equal(addDays('2026-08-31', 1), '2026-09-01')
  assert.equal(addDays('2026-09-01', -1), '2026-08-31')
})

test('reads the way she would say it', () => {
  assert.equal(formatLong('2026-08-21'), 'Friday 21 August')
})
