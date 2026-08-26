import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { nightSummary, summaryFilename } from '../src/core/summary.ts'
import { reconcileFull } from '../src/core/reconcile.ts'
import { shiftAt, type Person } from '../src/core/rota.ts'
import { GARDENERS_ARMS } from './fixtures/gardenersArms.ts'
import type { DayRecord } from '../src/core/types.ts'

/** 18:00 to 23:30 — the hours these tests put people on for. Hours belong to
    the night now, so every shift here says which ones it means. */
const EVENING = { startMin: 1080, endMin: 1410 }
const on = (person: Person, date: string, hours = EVENING) => shiftAt(person.id, date, hours)


const kelly: Person = { id: 'k', name: 'Kelly', slot: 1 }

/** The real night: £12 light in the drawer, the card slip agreeing exactly. */
const night: DayRecord = {
  date: '2026-08-23',
  till: { pence: 219280, source: 'vision', edited: false },
  card: { pence: 184100, source: 'manual', edited: false },
  cashPence: 33980,
  note: 'Quiz night',
  zRead: GARDENERS_ARMS,
  createdAt: 0,
  updatedAt: 0,
}

function summarise(day: DayRecord = night, shifts = [on(kelly, '2026-08-23')]) {
  return nightSummary({
    day,
    reconciliation: reconcileFull({
      tillPence: day.till.pence,
      cardPence: day.card.pence,
      cashPence: day.cashPence,
      tolerancePence: 5,
      ...(day.zRead ? { zRead: day.zRead } : {}),
    }),
    people: [kelly],
    shifts,
  })
}

test('the summary leads with the date and the four figures', () => {
  const text = summarise()
  assert.match(text, /Sunday 23 August/)
  assert.match(text, /Till roll\s+£2,192\.80/)
  assert.match(text, /Card machine\s+£1,841\.00/)
  assert.match(text, /Cash counted\s+£339\.80/)
  assert.match(text, /Card \+ cash\s+£2,180\.80/)
})

test('it states the verdict in words, not just a number', () => {
  assert.match(summarise(), /SHORT by £12\.00/)
})

test('it says which leg the difference is on', () => {
  // The whole point for an accountant: not "£12 out" but "£12 out of the drawer,
  // and the card machine agrees to the penny".
  const text = summarise()
  assert.match(text, /Drawer\s+till says £351\.80, counted £339\.80 \(−£12\.00\)/)
  assert.match(text, /Card machine\s+till says £1,841\.00, counted £1,841\.00/)
})

test('it breaks the takings down by department with shares', () => {
  const text = summarise()
  assert.match(text, /Draught beers\s+£1,492\.25 \(68\.1%\)/)
  assert.match(text, /Total\s+£2,192\.80/)
})

test('it carries the trading detail an accountant asks about', () => {
  const text = summarise()
  assert.match(text, /Sales\s+267/)
  assert.match(text, /Voids/)
})

test('it names who was on and for how long', () => {
  const text = summarise()
  assert.match(text, /Who was on/)
  assert.match(text, /Kelly\s+18:00–23:30 \(5h 30m\)/)
})

test('the note is carried across', () => {
  assert.match(summarise(), /Quiz night/)
})

test('a night nobody was rostered on simply omits the crew', () => {
  const text = summarise(night, [])
  assert.equal(text.includes('Who was on'), false)
})

test('an unfinished night says so rather than inventing a verdict', () => {
  const unfinished: DayRecord = { ...night, cashPence: null, note: '' }
  const text = summarise(unfinished, [])
  assert.match(text, /NOT FINISHED/)
  assert.match(text, /Cash counted\s+—/)
})

test('a balanced night reads as balanced', () => {
  const balanced: DayRecord = { ...night, cashPence: 35180 }
  assert.match(summarise(balanced, []), /BALANCED/)
})

test('a typed night says the figures were typed', () => {
  const typed: DayRecord = { ...night, till: { pence: 219280, source: 'manual', edited: false } }
  assert.match(summarise(typed, []), /typed in/)
})

test('the filename needs no renaming', () => {
  assert.equal(summaryFilename('2026-08-23'), 'takings-2026-08-23.txt')
})

test('a float is spelled out so the banking can be followed', () => {
  // An accountant reconciling the bank needs to know the drawer held more than
  // the takings figure says.
  const withFloat: DayRecord = { ...night, cashPence: 33980, floatPence: 20000 }
  const text = summarise(withFloat, [])
  assert.match(text, /Drawer counted\s+£539\.80/)
  assert.match(text, /Less float\s+−£200\.00/)
  assert.match(text, /Cash takings\s+£339\.80/)
})

test('a night with no float reads exactly as it always did', () => {
  const text = summarise(night, [])
  assert.match(text, /Cash counted\s+£339\.80/)
  assert.equal(text.includes('Less float'), false)
})

test('the float never changes what reconciles', () => {
  // Both nights took the same money; one left change in the drawer. The
  // verdict must be identical.
  const withFloat: DayRecord = { ...night, floatPence: 20000 }
  assert.match(summarise(withFloat, []), /SHORT by £12\.00/)
  assert.match(summarise(night, []), /SHORT by £12\.00/)
})
