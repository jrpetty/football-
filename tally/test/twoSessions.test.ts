// Her night of 18 September 2026: two Z reads, six photographs, one answer.
//
// Every expected figure here is off the paper, added up by hand in the fixture's
// own comment. Nothing is taken from the app.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { foldRolls, type PhotoOutcome } from '../src/ocr/scanZRead.ts'
import { soldFrom } from '../src/core/sold.ts'
import { parseZRead } from '../src/ocr/parseZRead.ts'
import type { ZRead } from '../src/core/zread.ts'
import { asPhotos, BOTH, DAY_SESSION, EVENING_SESSION } from './fixtures/twoSessions.ts'

function shots(...reads: ZRead[]): PhotoOutcome[] {
  return reads.map((parsed, index) => ({ index, sections: [], parsed }))
}

const day = asPhotos(DAY_SESSION, { stampClerks: true })
const evening = asPhotos(EVENING_SESSION)

test('the two sessions are told apart and added', () => {
  const { rolls, zRead } = foldRolls(shots(evening.top, day.top))
  assert.equal(rolls.length, 2)
  assert.equal(zRead.deptTotal?.pence, BOTH.pence)
  assert.equal(zRead.deptTotal?.qtyMilli, BOTH.qtyMilli)
  assert.equal(zRead.transaction.cashPence, BOTH.cashPence)
  assert.equal(zRead.transaction.cardPence, BOTH.cardPence)
  assert.equal(zRead.transaction.guestCount, BOTH.guestCount)
})

test('an average is worked out again, not averaged', () => {
  const { zRead } = foldRolls(shots(evening.top, day.top))
  // £2,011.05 over 264 sales is £7.62 — not the mean of £7.48 and £7.82.
  assert.equal(zRead.transaction.avePence, Math.round(BOTH.pence / BOTH.guestCount))
  assert.equal(zRead.transaction.avePence, 762)
})

test('six photographs in the order they came out of the camera', () => {
  // Evening top, evening items, evening clerks, DAY ITEMS, day top, day clerks.
  // The day's item list arrives before the day's own header — the case that was
  // quietly overwriting the evening's items with the day's.
  const { rolls, zRead, guessed } = foldRolls(
    shots(evening.top, evening.items, evening.clerks, day.items, day.top, day.clerks),
  )
  assert.equal(rolls.length, 2)
  assert.equal(guessed, undefined)
  assert.equal(zRead.deptTotal?.pence, BOTH.pence)
  assert.equal(zRead.pluTotal?.pence, BOTH.pence)
  assert.equal(zRead.pluTotal?.qtyMilli, BOTH.qtyMilli)
})

test('each session keeps its own item list rather than one overwriting the other', () => {
  const { rolls } = foldRolls(
    shots(evening.top, evening.items, evening.clerks, day.items, day.top, day.clerks),
  )
  const byZ = new Map(rolls.map((r) => [r.zNumber, r]))
  // The day poured 88 pints of Taddy; the evening poured 35.
  assert.equal(byZ.get(1712)?.zRead.plus.find((p) => p.code === 'P00014')?.qtyMilli, 88000)
  assert.equal(byZ.get(1713)?.zRead.plus.find((p) => p.code === 'P00014')?.qtyMilli, 35000)
})

test('the buttons add across both sessions', () => {
  const { zRead } = foldRolls(
    shots(evening.top, evening.items, evening.clerks, day.items, day.top, day.clerks),
  )
  const sold = soldFrom([zRead])
  // 88 pints of Taddy in the day and 35 in the evening is 123, at £352 + £140.
  const taddy = sold.items.lines.find((l) => l.code === 'P00014')
  assert.equal(taddy?.qtyMilli, 123000)
  assert.equal(taddy?.pence, 49200)
  // Draught beers: 273 + 172 units, £958.00 + £603.75.
  const draught = sold.categories.lines.find((l) => l.code === 'D01')
  assert.equal(draught?.qtyMilli, 445000)
  assert.equal(draught?.pence, 156175)
  assert.equal(sold.categories.pence, BOTH.pence)
  assert.equal(sold.categories.qtyMilli, BOTH.qtyMilli)
  assert.equal(sold.items.pence, BOTH.pence)
})

test('a line only one session sold comes through whole', () => {
  const { zRead } = foldRolls(shots(day.items, evening.items, day.top, evening.top))
  const sold = soldFrom([zRead])
  // Vodka is the evening only; pint cider the day only.
  assert.equal(sold.items.lines.find((l) => l.code === 'P00041')?.qtyMilli, 12000)
  assert.equal(sold.items.lines.find((l) => l.code === 'P00002')?.qtyMilli, 13000)
})

test('the clerk report of a session is not a second session', () => {
  // This is the trap. The day's department block is #4631 and its clerk report
  // is #4633 — a different receipt number, the same 16:57 read. Counted twice,
  // the day alone would come to £2,349.50.
  const { rolls, zRead } = foldRolls(shots(day.top, day.clerks))
  assert.equal(rolls.length, 1)
  assert.equal(zRead.deptTotal?.pence, 117475)
  assert.equal(zRead.transaction.paidTotalPence, 117475)
})

test('a session cut in three is still one session', () => {
  const { rolls, zRead } = foldRolls(shots(day.top, day.items, day.clerks))
  assert.equal(rolls.length, 1)
  assert.equal(zRead.deptTotal?.pence, 117475)
  assert.equal(zRead.pluTotal?.pence, 117475)
  assert.equal(zRead.clerks.length, 2)
})

test('told they are one roll, they merge instead of adding', () => {
  const { rolls, zRead } = foldRolls(shots(evening.top, day.top), { how: 'together' })
  assert.equal(rolls.length, 1)
  // The later photograph wins on a merge, so this is the day alone. It is the
  // wrong answer for this night, and it is the answer she asked for.
  assert.equal(zRead.deptTotal?.pence, 117475)
})

test('told they are separate, every photograph adds', () => {
  const { rolls, zRead } = foldRolls(shots(evening.top, day.top), { how: 'separate' })
  assert.equal(rolls.length, 2)
  assert.equal(zRead.deptTotal?.pence, BOTH.pence)
})

test('a block that states no total of its own is reported as a guess', () => {
  const orphan: ZRead = {
    header: {},
    departments: [],
    groups: [],
    transaction: { noSaleCount: 3 },
    clerks: [],
    plus: [],
  }
  const { rolls, guessed } = foldRolls(shots(day.top, orphan, evening.top))
  assert.equal(rolls.length, 2)
  assert.deepEqual(guessed, [1])
  assert.equal(rolls[0]?.photos.includes(1), true)
})

test('the Z counter is read when the till prints it on its own line', () => {
  // Her rolls print it above GT1 rather than beside it. Read only beside GT1, it
  // came back undefined on every one of her receipts — and two receipts with no
  // counter cannot be told apart, which is how a session went missing.
  const z = parseZRead(`#4743    18/09/2026 22:46:49
0004 CLERK0004              000000

        *Z1*
                    Z1  1713
GT1        *00001425085.96
GT2        *00001446576.68
GT3       -00000021490.72`)
  assert.equal(z.header.zNumber, 1713)
  assert.equal(z.header.receiptNo, '4743')
  assert.equal(z.header.printedAt, '18/09/2026 22:46:49')
})

test('and still when it prints it beside GT1', () => {
  const z = parseZRead(`GT1                         *0000140111.26     Z1 1685`)
  assert.equal(z.header.zNumber, 1685)
})

test('"*Z1*" on its own is the words Z read, not a counter', () => {
  const z = parseZRead(`        *Z1*
ALL CLERK    *Z1*`)
  assert.equal(z.header.zNumber, undefined)
})
