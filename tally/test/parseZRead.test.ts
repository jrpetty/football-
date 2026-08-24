import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseZRead, readLine } from '../src/ocr/parseZRead.ts'
import { crossfootVerdict } from '../src/core/crossfoot.ts'
import { GARDENERS_ARMS, GARDENERS_ARMS_TEXT } from './fixtures/gardenersArms.ts'

const parsed = parseZRead(GARDENERS_ARMS_TEXT)

test('reads the header, including the Z counter riding on the GT1 line', () => {
  assert.equal(parsed.header.receiptNo, '1233')
  assert.equal(parsed.header.printedAt, '23/08/2026 21:39:16')
  assert.equal(parsed.header.zNumber, 1685)
})

test('reads the lifetime grand totals, which exceed a night’s sanity cap', () => {
  // £140,111.26 would be an absurd night and a perfectly ordinary lifetime.
  assert.equal(parsed.header.gt1Pence, 14011126)
  assert.equal(parsed.header.gt2Pence, 14229683)
  assert.equal(parsed.header.gt3Pence, -2118557, 'GT3 is printed negative')
})

test('reads every department with its quantity, value and percentage', () => {
  assert.deepEqual(parsed.departments, GARDENERS_ARMS.departments)
})

test('files each department under the group subtotal that follows it', () => {
  assert.equal(parsed.departments.find((d) => d.code === 'D01')?.group, 'GROUP01')
  assert.equal(parsed.departments.find((d) => d.code === 'D08')?.group, 'GROUP02')
})

test('reads the group subtotals and the department total', () => {
  assert.deepEqual(parsed.groups, GARDENERS_ARMS.groups)
  assert.deepEqual(parsed.deptTotal, GARDENERS_ARMS.deptTotal)
})

test('reads the transaction block', () => {
  assert.deepEqual(parsed.transaction, GARDENERS_ARMS.transaction)
})

test('reads each clerk separately', () => {
  assert.deepEqual(parsed.clerks, GARDENERS_ARMS.clerks)
})

test('does not mistake one clerk’s takings for the pub’s night', () => {
  // CASH, CREDIT CARD, PAID TL and CID are printed once in TRANSACTION, again
  // under every clerk, and a third time under the clerk ***TOTAL. Clerk 4 took
  // £2,188.80 of the £2,192.80; a label-matching parser would report the wrong
  // one of those as the day.
  assert.equal(parsed.transaction.paidTotalPence, 219280)
  assert.equal(parsed.transaction.cardPence, 184100)
  assert.equal(parsed.clerks.find((c) => c.code === 'CLK#0004')?.paidTotalPence, 218880)
  assert.equal(parsed.clerks.find((c) => c.code === 'CLK#0004')?.cardPence, 183700)
})

test('the parsed receipt cross-foots, which is the real proof it read correctly', () => {
  const v = crossfootVerdict(parsed)
  assert.deepEqual(
    v.checks.filter((c) => !c.ok).map((c) => `${c.id}: expected ${c.expected}, got ${c.actual}`),
    [],
  )
  assert.equal(v.clean, true)
})

test('the whole parse matches the receipt as transcribed by hand', () => {
  assert.deepEqual(parsed, GARDENERS_ARMS)
})

test('splits a printed line into label, quantity, value and percentage', () => {
  assert.deepEqual(readLine('D01 DRAUGHT BEERS      406.000 Q     *1492.25    68.05%'), {
    label: 'D01 DRAUGHT BEERS',
    qtyMilli: 406000,
    pence: 149225,
    percentBp: 6805,
  })
})

test('reads a line carrying only a count', () => {
  assert.deepEqual(readLine('NO SALE                      5 Q'), {
    label: 'NO SALE',
    qtyMilli: 5000,
    pence: undefined,
    percentBp: undefined,
  })
})

test('strips the till’s decoration from labels', () => {
  assert.equal(readLine('****CID                               *351.80')?.label, 'CID')
  assert.equal(readLine('*DEPT TL   689.000 Q  *2192.80  100.00%')?.label, 'DEPT TL')
  assert.equal(readLine('AVE.                                    *8.21')?.label, 'AVE')
})

test('reads items when the PLU list is legible', () => {
  const z = parseZRead(`PLU/EAN
P00001  PINT DARK MILD        5.000 Q      *14.00
P00002  PINT CIDER           28.000 Q     *148.40
***TOTAL                     33.000 Q     *162.40
`)
  assert.deepEqual(z.plus, [
    { code: 'P00001', name: 'PINT DARK MILD', qtyMilli: 5000, pence: 1400 },
    { code: 'P00002', name: 'PINT CIDER', qtyMilli: 28000, pence: 14840 },
  ])
  assert.deepEqual(z.pluTotal, { qtyMilli: 33000, pence: 16240 })
})

test('survives a roll that was torn before the end', () => {
  const z = parseZRead(`DEPT./GROUP
D01 DRAUGHT BEERS      406.000 Q     *1492.25    68.05%
D02 SPIRITS             11.000 Q       *35.25     1.61%
`)
  assert.equal(z.departments.length, 2)
  assert.equal(z.deptTotal, undefined)
  // Nothing is asserted about figures that were never printed.
  assert.deepEqual(crossfootVerdict(z).errors, [])
})

test('returns an empty read rather than inventing one from noise', () => {
  const z = parseZRead('~~~~~\n#####\nnothing here\n')
  assert.equal(z.departments.length, 0)
  assert.equal(z.deptTotal, undefined)
  assert.equal(Object.keys(z.transaction).length, 0)
})
