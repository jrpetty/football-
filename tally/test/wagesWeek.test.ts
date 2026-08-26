import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { wagesCsv, wagesSummary, weekWages } from '../src/core/wagesWeek.ts'
import { shiftFor, type Person, type Shift } from '../src/core/rota.ts'

// 18:00 to 23:30 — five and a half hours, £12.21 an hour.
const kelly: Person = { id: 'k', name: 'Kelly', slot: 1, defaultStartMin: 1080, defaultEndMin: 1410, ratePencePerHour: 1221 }
// No rate: hours known, money unknown.
const dave: Person = { id: 'd', name: 'Dave', slot: 2, defaultStartMin: 1080, defaultEndMin: 1410 }
// A name with a comma in it, for the CSV.
const smith: Person = { id: 's', name: 'Smith, D', slot: 3, defaultStartMin: 1080, defaultEndMin: 1410, ratePencePerHour: 1000 }

const MONDAY = '2026-01-05'

// --- the week itself ---------------------------------------------------------

test('the week runs Monday to Sunday and only counts shifts inside it', () => {
  const shifts = [
    shiftFor(kelly, '2026-01-05'), // Monday
    shiftFor(kelly, '2026-01-11'), // Sunday — last day in
    shiftFor(kelly, '2026-01-12'), // the Monday after — out
    shiftFor(kelly, '2026-01-04'), // the Sunday before — out
  ]
  const week = weekWages(MONDAY, shifts, [kelly])
  assert.equal(week.sunday, '2026-01-11')
  assert.equal(week.rows.length, 1)
  assert.equal(week.rows[0]!.shifts, 2)
  assert.equal(week.rows[0]!.minutes, 660)
})

test('the money is costed off the total minutes, matching the year-end pack', () => {
  const week = weekWages(MONDAY, [shiftFor(kelly, '2026-01-05'), shiftFor(kelly, '2026-01-06')], [kelly])
  assert.equal(week.rows[0]!.costPence, 13431) // 11 hours at £12.21, rounded once
  assert.equal(week.totalPence, 13431)
  assert.equal(week.anyUnpriced, false)
})

test('a shift past midnight counts its real hours', () => {
  // 18:00 to 00:30 is six and a half hours, not minus seventeen and a half.
  const late: Shift = { id: `${MONDAY}:k`, date: MONDAY, personId: 'k', startMin: 1080, endMin: 30 }
  const week = weekWages(MONDAY, [late], [kelly])
  assert.equal(week.rows[0]!.minutes, 390)
})

test('nobody with a rate means the total is unknown, not zero', () => {
  const week = weekWages(MONDAY, [shiftFor(dave, MONDAY)], [dave])
  assert.equal(week.totalPence, null)
  assert.equal(week.anyUnpriced, true)
  assert.equal(week.totalMinutes, 330)
})

test('a mixed week totals the priced people and says someone is missing', () => {
  const week = weekWages(MONDAY, [shiftFor(kelly, MONDAY), shiftFor(dave, MONDAY)], [kelly, dave])
  assert.equal(week.totalPence, 6716) // Kelly's 5h30m only
  assert.equal(week.anyUnpriced, true)
})

// --- the text ----------------------------------------------------------------

test('the summary names the week and lists each person with hours and money', () => {
  const week = weekWages(MONDAY, [shiftFor(kelly, '2026-01-05'), shiftFor(kelly, '2026-01-06')], [kelly])
  const text = wagesSummary(week)
  // The heading has no year; the ISO line right under it does.
  assert.ok(text.startsWith('Wages — week beginning Monday 5 January'))
  assert.ok(text.includes('Monday 2026-01-05 to Sunday 2026-01-11'))
  assert.ok(text.includes('Kelly'))
  assert.ok(text.includes('11h over 2 shifts · £134.31'))
  assert.ok(text.includes('Hours in total'))
  assert.ok(text.includes('11h'))
  assert.ok(text.includes('At the rates set'))
  assert.ok(text.includes('£134.31'))
  assert.ok(text.includes('a cross-check for payroll, not the payroll itself'))
})

test('one shift reads "1 shift", not "1 shifts"', () => {
  const week = weekWages(MONDAY, [shiftFor(kelly, MONDAY)], [kelly])
  assert.ok(wagesSummary(week).includes('over 1 shift ·'))
})

test('a person with no rate is written as missing a rate, and the total warns', () => {
  const week = weekWages(MONDAY, [shiftFor(kelly, MONDAY), shiftFor(dave, MONDAY)], [kelly, dave])
  const text = wagesSummary(week)
  assert.ok(text.includes('— no rate set'))
  assert.ok(text.includes('no hourly rate set, so the money total is short'))
})

test('a fully priced week carries no warning', () => {
  const week = weekWages(MONDAY, [shiftFor(kelly, MONDAY)], [kelly])
  assert.ok(!wagesSummary(week).includes('no hourly rate'))
})

test('an empty week says so instead of sending a blank table', () => {
  const text = wagesSummary(weekWages(MONDAY, [], []))
  assert.ok(text.includes('Nobody was rostered this week.'))
  assert.ok(!text.includes('Hours in total'))
})

// --- the spreadsheet ---------------------------------------------------------

test('the CSV has one row per person with decimal hours and a total row', () => {
  const week = weekWages(MONDAY, [shiftFor(kelly, '2026-01-05'), shiftFor(kelly, '2026-01-06'), shiftFor(dave, MONDAY)], [kelly, dave])
  const lines = wagesCsv(week).split('\n')
  assert.equal(lines[0], 'Name,Shifts,Hours,Rate set,Amount')
  assert.ok(lines.includes('Kelly,2,11.00,yes,134.31'))
  assert.ok(lines.includes('Dave,1,5.50,no,'))
  assert.equal(lines[lines.length - 1], 'Total,,16.50,,134.31')
})

test('a name with a comma is quoted so the row cannot split', () => {
  const week = weekWages(MONDAY, [shiftFor(smith, MONDAY)], [smith])
  assert.ok(wagesCsv(week).includes('"Smith, D",1,5.50,yes,55.00'))
})
