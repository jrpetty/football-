// The date on the receipt, and the one way it must never be read.
//
// A British till prints the day first. Read month-first, a third of the year
// still parses — 05/09 becomes the ninth of May instead of the fifth of
// September — and the night is filed under a day the pub may not even have
// opened. It never looks wrong on screen, which is what makes it worth this
// many tests.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { tradingDayFromPrinted } from '../src/core/date.ts'
import { DAY_SESSION, EVENING_SESSION } from './fixtures/twoSessions.ts'

test('her two receipts both land on the eighteenth of September', () => {
  assert.equal(tradingDayFromPrinted(DAY_SESSION.header.printedAt), '2026-09-18')
  assert.equal(tradingDayFromPrinted(EVENING_SESSION.header.printedAt), '2026-09-18')
})

test('day first, not month first', () => {
  // The whole point. Read the American way this is the ninth of May.
  assert.equal(tradingDayFromPrinted('05/09/2026 20:00:00'), '2026-09-05')
  assert.equal(tradingDayFromPrinted('01/02/2026 20:00:00'), '2026-02-01')
  assert.equal(tradingDayFromPrinted('12/11/2026 20:00:00'), '2026-11-12')
})

test('a day past the twelfth proves it either way', () => {
  assert.equal(tradingDayFromPrinted('31/12/2026 23:59:00'), '2026-12-31')
  assert.equal(tradingDayFromPrinted('25/12/2026 14:00:00'), '2026-12-25')
})

test('a roll rung off after midnight belongs to the night before', () => {
  assert.equal(tradingDayFromPrinted('19/09/2026 00:30:00'), '2026-09-18')
  assert.equal(tradingDayFromPrinted('19/09/2026 04:59:00'), '2026-09-18')
  // Five in the morning is a new day, which is the cutoff the app runs on.
  assert.equal(tradingDayFromPrinted('19/09/2026 05:00:00'), '2026-09-19')
})

test('over the end of a month, and the end of a year', () => {
  assert.equal(tradingDayFromPrinted('01/10/2026 01:00:00'), '2026-09-30')
  assert.equal(tradingDayFromPrinted('01/01/2027 02:15:00'), '2026-12-31')
})

test('across the British Summer Time change, which is a local-time question', () => {
  // The clocks go back at 2am on 25 October 2026. A roll rung off at half past
  // midnight that night is still the twenty-fourth's trade.
  assert.equal(tradingDayFromPrinted('25/10/2026 00:30:00'), '2026-10-24')
  assert.equal(tradingDayFromPrinted('29/03/2026 03:30:00'), '2026-03-28')
})

test('a leap day is a real day and the day after it is not', () => {
  assert.equal(tradingDayFromPrinted('29/02/2024 21:00:00'), '2024-02-29')
  // 2026 is not a leap year, so this is a misreading and must say so.
  assert.equal(tradingDayFromPrinted('29/02/2026 21:00:00'), null)
})

test('a date it cannot read gives nothing, rather than a guess', () => {
  assert.equal(tradingDayFromPrinted(undefined), null)
  assert.equal(tradingDayFromPrinted(''), null)
  assert.equal(tradingDayFromPrinted('rubbish'), null)
  // Thirteen months, a thirty-second day, a twenty-fifth hour: all misreads.
  assert.equal(tradingDayFromPrinted('18/13/2026 20:00:00'), null)
  assert.equal(tradingDayFromPrinted('32/09/2026 20:00:00'), null)
  assert.equal(tradingDayFromPrinted('18/09/2026 25:00:00'), null)
  assert.equal(tradingDayFromPrinted('31/09/2026 20:00:00'), null)
})

test('the time is optional, and a dateless roll is midday, not midnight', () => {
  // Midday rather than midnight, so a roll whose time could not be read is not
  // pushed back a day by the cutoff on nothing but a missing figure.
  assert.equal(tradingDayFromPrinted('18/09/2026'), '2026-09-18')
})

test('a till that prints dots or dashes is read the same way', () => {
  assert.equal(tradingDayFromPrinted('18.09.2026 22:46'), '2026-09-18')
  assert.equal(tradingDayFromPrinted('18-09-2026 22:46'), '2026-09-18')
})

test('single-figure days and months, as some tills print them', () => {
  assert.equal(tradingDayFromPrinted('1/9/2026 20:00'), '2026-09-01')
  assert.equal(tradingDayFromPrinted('9/1/2026 20:00'), '2026-01-09')
})
