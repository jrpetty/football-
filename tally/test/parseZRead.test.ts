import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseZRead, readLine } from '../src/ocr/parseZRead.ts'
import { crossfootVerdict } from '../src/core/crossfoot.ts'
import { emptyZRead, isZReadEmpty, mergeZRead, sectionsIn } from '../src/core/zread.ts'
import { GARDENERS_ARMS, GARDENERS_ARMS_TEXT, GARDENERS_ARMS_FLAT } from './fixtures/gardenersArms.ts'

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

// --- several photographs of one roll ----------------------------------------

test('names which sections a photograph turned out to contain', () => {
  assert.deepEqual(sectionsIn(parsed), ['departments', 'totals', 'clerks'])
  assert.deepEqual(sectionsIn(emptyZRead()), [])
})

test('recognises an item-list photograph on its own', () => {
  const z = parseZRead(`PLU/EAN
P00001  PINT DARK MILD        5.000 Q      *14.00
***TOTAL                      5.000 Q      *14.00
`)
  assert.deepEqual(sectionsIn(z), ['items'])
})

test('the roll comes out the same whichever order the photographs arrive in', () => {
  // She will not photograph the roll in a consistent order at midnight, and it
  // must not matter that she does not.
  const summary = parseZRead(GARDENERS_ARMS_TEXT.slice(0, GARDENERS_ARMS_TEXT.indexOf('ALL CLERK')))
  const clerks = parseZRead(GARDENERS_ARMS_TEXT.slice(GARDENERS_ARMS_TEXT.indexOf('ALL CLERK')))

  const forwards = mergeZRead(summary, clerks)
  const backwards = mergeZRead(clerks, summary)

  assert.equal(forwards.deptTotal?.pence, 219280)
  assert.equal(backwards.deptTotal?.pence, 219280)
  assert.equal(forwards.clerks.length, 3)
  assert.equal(backwards.clerks.length, 3)
  assert.equal(forwards.transaction.paidTotalPence, backwards.transaction.paidTotalPence)
  assert.equal(crossfootVerdict(forwards).clean, true)
  assert.equal(crossfootVerdict(backwards).clean, true)
})

test('merging keeps sections a later photograph does not carry', () => {
  const items = parseZRead(`PLU/EAN
P00001  PINT DARK MILD        5.000 Q      *14.00
`)
  // Photographing the summary after the item list must add to it, not wipe it.
  const merged = mergeZRead(items, parsed)
  assert.equal(merged.plus.length, 1, 'the items survive')
  assert.equal(merged.departments.length, 7, 'and the departments arrive')
})

test('an unreadable photograph contributes nothing rather than corrupting the roll', () => {
  const merged = mergeZRead(parsed, parseZRead('~~~ blurred ~~~'))
  assert.deepEqual(merged.departments, parsed.departments)
  assert.equal(crossfootVerdict(merged).clean, true)
})

test('a scan that read nothing leaves an empty read, not a broken one', () => {
  // The interface leans on this to decide whether it may discard a figure she
  // typed by hand: nothing read means nothing may be thrown away.
  const nothing = parseZRead('~~~~\n####\n')
  assert.equal(isZReadEmpty(nothing), true)
  assert.equal(isZReadEmpty(parsed), false)
})

// --- the layout the till actually prints ------------------------------------

test('reads a department split across three printed lines', () => {
  // This is how the real receipt prints, and the shape an earlier parser could
  // not read at all: code and quantity, then name and value, then percentage.
  const z = parseZRead(`DEPT./GROUP
D01                             406.000 Q
DRAUGHT BEERS                    *1492.25
                                   68.05%
`)
  assert.deepEqual(z.departments, [
    { code: 'D01', name: 'DRAUGHT BEERS', qtyMilli: 406000, pence: 149225, percentBp: 6805 },
  ])
})

test('reads a tender whose amount is on the line below its count', () => {
  const z = parseZRead(`TRANSACTION
CASH                                 57 Q
                                  *351.80
CREDIT CARD                         210 Q
                                 *1841.00
`)
  assert.equal(z.transaction.cashCount, 57)
  assert.equal(z.transaction.cashPence, 35180)
  assert.equal(z.transaction.cardCount, 210)
  assert.equal(z.transaction.cardPence, 184100)
})

test('reads a group subtotal with no label on its value line', () => {
  const z = parseZRead(`GROUP01                         687.000 Q
                                 *2188.40
                                   99.80%
`)
  assert.deepEqual(z.groups, [{ code: 'GROUP01', qtyMilli: 687000, pence: 218840, percentBp: 9980 }])
})

test('the printed layout and a flattened one give the identical read', () => {
  // A vision model may tidy the columns despite being told not to. Which way it
  // chose must not change a single figure.
  const printed = parseZRead(GARDENERS_ARMS_TEXT)
  const flat = parseZRead(GARDENERS_ARMS_FLAT)
  assert.deepEqual(printed, flat)
  assert.deepEqual(printed, GARDENERS_ARMS)
})

test('both layouts cross-foot', () => {
  for (const [name, text] of [['printed', GARDENERS_ARMS_TEXT], ['flattened', GARDENERS_ARMS_FLAT]] as const) {
    const v = crossfootVerdict(parseZRead(text))
    assert.deepEqual(
      v.checks.filter((c) => !c.ok).map((c) => `${c.id}: expected ${c.expected}, got ${c.actual}`),
      [],
      `${name} layout`,
    )
  }
})

test('a department name is not mistaken for a record of its own', () => {
  // "DRAUGHT BEERS" on its own line must attach to the D01 above it, not become
  // a department called DRAUGHT with no code.
  const z = parseZRead(GARDENERS_ARMS_TEXT)
  assert.equal(z.departments.length, 7)
  assert.ok(z.departments.every((d) => /^D\d{2}$/.test(d.code)))
  assert.ok(z.departments.every((d) => d.name.length > 0))
})

test('still tells each clerk apart in the printed layout', () => {
  const z = parseZRead(GARDENERS_ARMS_TEXT)
  assert.equal(z.transaction.paidTotalPence, 219280, "the pub's night")
  assert.equal(z.clerks.find((c) => c.code === 'CLK#0004')?.paidTotalPence, 218880, "one clerk's share")
  assert.equal(z.clerks.find((c) => c.code === 'CLK#0004')?.cardPence, 183700)
  assert.equal(z.transaction.cardPence, 184100)
})

test('reads items split over two lines as well as one', () => {
  const split = parseZRead(`PLU/EAN
P00001
PINT DARK MILD                    5.000 Q
                                   *14.00
`)
  assert.deepEqual(split.plus, [{ code: 'P00001', name: 'PINT DARK MILD', qtyMilli: 5000, pence: 1400 }])
})

// --- robustness against how a transcription really arrives -------------------

test('reads the roll through inconsistent spacing and stray blank lines', () => {
  // A transcription will not come back column-perfect. None of this may change
  // a single figure.
  const messy = `DEPT./GROUP

D01     406.000 Q
DRAUGHT BEERS   * 1492.25

        68.05%

D02   11.000 Q
SPIRITS      *35.25
   1.61%
GROUP01   687.000 Q
   *2188.40
      99.80%
*DEPT TL    689.000 Q
      *2192.80
       100.00%
`
  const z = parseZRead(messy)
  assert.equal(z.departments.length, 2)
  assert.equal(z.departments[0]?.pence, 149225, 'a space after the star must not matter')
  assert.equal(z.departments[0]?.percentBp, 6805)
  assert.equal(z.departments[1]?.name, 'SPIRITS')
  assert.equal(z.groups[0]?.pence, 218840)
  assert.equal(z.deptTotal?.pence, 219280)
})

test('a department with no percentage printed is still read', () => {
  const z = parseZRead(`D04                               4.000 Q
BOTTLED BEERS                      *27.00
D05                             137.000 Q
MIXERS                            *252.90
`)
  assert.equal(z.departments.length, 2)
  assert.equal(z.departments[0]?.percentBp, undefined)
  assert.equal(z.departments[1]?.pence, 25290)
})

test('a record is closed by the next one, not by a blank line', () => {
  // The till leaves a blank line between the groups; a record must survive
  // being interrupted by nothing at all.
  const z = parseZRead(`D08                               2.000 Q
OPEN FOOD                           *4.40
                                    0.20%

GROUP02                           2.000 Q
                                    *4.40
`)
  assert.equal(z.departments[0]?.name, 'OPEN FOOD')
  assert.equal(z.departments[0]?.pence, 440)
  assert.equal(z.groups[0]?.pence, 440)
})

test('a stray line of noise does not attach itself to the record above', () => {
  const z = parseZRead(`D01                             406.000 Q
DRAUGHT BEERS                    *1492.25
                                   68.05%
~~~~~~~~~~~~
D02                              11.000 Q
SPIRITS                            *35.25
`)
  assert.equal(z.departments.length, 2)
  assert.equal(z.departments[0]?.pence, 149225)
  assert.equal(z.departments[1]?.pence, 3525)
})

test('the day summary survives the clerk section following it', () => {
  // The clerk block repeats CASH and CREDIT CARD with different figures. The
  // day's own numbers must still be the day's at the end of the parse.
  const z = parseZRead(GARDENERS_ARMS_TEXT)
  assert.equal(z.transaction.cashPence, 35180)
  assert.equal(z.transaction.cardPence, 184100)
  assert.equal(z.transaction.guestCount, 267)
  assert.equal(crossfootVerdict(z).clean, true)
})

test('a tidied department code is still the same department', () => {
  // A transcription may normalise "D01" to "D1". Losing a whole department to a
  // dropped leading zero would be an expensive way to be pedantic.
  const z = parseZRead(`D1                              406.000 Q
DRAUGHT BEERS                    *1492.25
GROUP1                          687.000 Q
                                 *2188.40
`)
  assert.equal(z.departments[0]?.code, 'D01')
  assert.equal(z.groups[0]?.code, 'GROUP01')
  assert.equal(z.departments[0]?.group, 'GROUP01', 'and it still files under its group')
})
