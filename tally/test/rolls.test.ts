// ---------------------------------------------------------------------------
// Two receipts, or two photographs of one?
//
// Opposite jobs, and the difference is the whole night's takings. Photographs
// of ONE roll are pieces of the same record and merge — a department seen on
// two of them is the same money read twice. Separate Z reads are separate
// trade and add.
//
// Get it backwards and the day reads at a third of what was taken, or at
// triple. Nothing else in the app is that far wrong that quietly.
// ---------------------------------------------------------------------------

import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { foldRolls, type PhotoOutcome } from '../src/ocr/scanZRead.ts'
import { addZRead, emptyZRead, sectionsIn, type ZRead } from '../src/core/zread.ts'

/** A receipt, as short as it can be and still be one. */
function roll(over: {
  z?: number
  draught?: number
  cash?: number
  card?: number
  guests?: number
  pints?: number
} = {}): ZRead {
  const z: ZRead = {
    header: over.z === undefined ? {} : { zNumber: over.z },
    departments: over.draught === undefined ? [] : [{ code: 'D01', name: 'DRAUGHT BEERS', qtyMilli: 100_000, pence: over.draught }],
    groups: [],
    transaction: {
      ...(over.cash === undefined ? {} : { cashPence: over.cash, cashCount: 10 }),
      ...(over.card === undefined ? {} : { cardPence: over.card, cardCount: 20 }),
      ...(over.guests === undefined ? {} : { guestCount: over.guests }),
      ...(over.cash !== undefined && over.card !== undefined ? { paidTotalPence: over.cash + over.card } : {}),
    },
    clerks: [],
    plus: over.pints === undefined ? [] : [{ code: 'P1', name: 'PINT TADDY LAGER', qtyMilli: over.pints * 1000, pence: over.pints * 400 }],
  }
  if (over.draught !== undefined) z.deptTotal = { qtyMilli: 100_000, pence: over.draught }
  return z
}

const shot = (index: number, parsed: ZRead): PhotoOutcome => ({ index, sections: sectionsIn(parsed), parsed })

// --- pieces of one roll -----------------------------------------------------------

test('photographs of one roll are one receipt, and do not double it', () => {
  // The top carries the header and the departments; the bottom carries the
  // totals. Only the top has a Z number on it.
  const top = roll({ z: 1685, draught: 149_225 })
  const bottom = roll({ cash: 35_180, card: 184_100, guests: 267 })
  const out = foldRolls([shot(0, top), shot(1, bottom)])
  assert.equal(out.rolls.length, 1, 'one receipt')
  assert.deepEqual(out.rolls[0]?.photos, [0, 1])
  assert.equal(out.zRead.deptTotal?.pence, 149_225, 'not doubled')
  assert.equal(out.zRead.transaction.cashPence, 35_180)
})

test('the same section photographed twice is the later reading, not both', () => {
  const first = roll({ z: 1685, draught: 149_225 })
  const retake = roll({ z: 1685, draught: 149_925 })
  const out = foldRolls([shot(0, first), shot(1, retake)])
  assert.equal(out.rolls.length, 1)
  assert.equal(out.zRead.deptTotal?.pence, 149_925, 'the retake wins; they do not add')
})

// --- separate receipts ------------------------------------------------------------

test('two Z reads are two receipts, and they add', () => {
  // A lunchtime and an evening, or two tills cashed up together.
  const lunch = roll({ z: 1685, draught: 40_000, cash: 10_000, card: 30_000, guests: 50, pints: 30 })
  const evening = roll({ z: 1686, draught: 120_000, cash: 25_000, card: 95_000, guests: 150, pints: 90 })
  const out = foldRolls([shot(0, lunch), shot(1, evening)])
  assert.equal(out.rolls.length, 2)
  assert.deepEqual(out.rolls.map((r) => r.zNumber), [1685, 1686])
  assert.equal(out.zRead.deptTotal?.pence, 160_000, 'the draught adds')
  assert.equal(out.zRead.transaction.cashPence, 35_000, 'the cash adds')
  assert.equal(out.zRead.transaction.cardPence, 125_000, 'the card adds')
  assert.equal(out.zRead.transaction.guestCount, 200)
  assert.equal(out.zRead.plus[0]?.qtyMilli, 120_000, '30 pints and 90 is 120 off the cellar')
})

test('three receipts, each photographed twice, come to the sum of the three', () => {
  const shots: PhotoOutcome[] = []
  for (const [i, z] of [1685, 1686, 1687].entries()) {
    shots.push(shot(i * 2, roll({ z, draught: 50_000, pints: 40 })))
    shots.push(shot(i * 2 + 1, roll({ cash: 10_000, card: 40_000, guests: 60 })))
  }
  const out = foldRolls(shots)
  assert.equal(out.rolls.length, 3)
  assert.equal(out.zRead.deptTotal?.pence, 150_000)
  assert.equal(out.zRead.transaction.cashPence, 30_000)
  assert.equal(out.zRead.transaction.cardPence, 120_000)
  assert.equal(out.zRead.plus[0]?.qtyMilli, 120_000)
})

test('a photograph with no Z number joins the receipt that is open', () => {
  // Only the top of a roll carries the header, so most photographs have none.
  const out = foldRolls([
    shot(0, roll({ z: 1685, draught: 40_000 })),
    shot(1, roll({ cash: 10_000 })),
    shot(2, roll({ z: 1686, draught: 60_000 })),
    shot(3, roll({ cash: 20_000 })),
  ])
  assert.deepEqual(out.rolls.map((r) => r.photos), [[0, 1], [2, 3]])
  assert.equal(out.zRead.deptTotal?.pence, 100_000)
  assert.equal(out.zRead.transaction.cashPence, 30_000)
})

test('with no Z number anywhere it stays one receipt, rather than guessing', () => {
  // Doubling a night because a header came out unreadable would be the worst
  // way to be wrong here.
  const out = foldRolls([shot(0, roll({ draught: 40_000 })), shot(1, roll({ cash: 10_000 }))])
  assert.equal(out.rolls.length, 1)
  assert.equal(out.zRead.deptTotal?.pence, 40_000)
})

test('being told they are separate overrides the counter', () => {
  const out = foldRolls([shot(0, roll({ draught: 40_000 })), shot(1, roll({ draught: 60_000 }))], { how: 'separate' })
  assert.equal(out.rolls.length, 2)
  assert.equal(out.zRead.deptTotal?.pence, 100_000)
})

test('and being told they are one roll overrides it the other way', () => {
  const out = foldRolls([shot(0, roll({ z: 1, draught: 40_000 })), shot(1, roll({ z: 2, draught: 60_000 }))], { how: 'together' })
  assert.equal(out.rolls.length, 1)
  assert.equal(out.zRead.deptTotal?.pence, 60_000, 'merged, so the later reading wins')
})

test('a night reopened folds the first receipt onto what was already read', () => {
  const base = roll({ z: 1685, draught: 40_000, cash: 10_000 })
  const out = foldRolls([shot(0, roll({ card: 30_000 }))], { base })
  assert.equal(out.zRead.deptTotal?.pence, 40_000, 'kept')
  assert.equal(out.zRead.transaction.cardPence, 30_000, 'added to it')
})

// --- the arithmetic of adding two receipts -----------------------------------------

test('an average of two averages is worked out again, not averaged', () => {
  const a: ZRead = { ...emptyZRead(), transaction: { paidTotalPence: 10_000, guestCount: 100, avePence: 100 } }
  const b: ZRead = { ...emptyZRead(), transaction: { paidTotalPence: 90_000, guestCount: 100, avePence: 900 } }
  const sum = addZRead(a, b)
  assert.equal(sum.transaction.paidTotalPence, 100_000)
  assert.equal(sum.transaction.avePence, 500, '£1000 over 200 sales, not the mean of 100 and 900')
})

test('the till’s lifetime odometer is not added, because two readings of it are not a number', () => {
  const a: ZRead = { ...emptyZRead(), header: { zNumber: 1, gt1Pence: 14_011_126 } }
  const b: ZRead = { ...emptyZRead(), header: { zNumber: 2, gt1Pence: 14_230_406 } }
  const sum = addZRead(a, b)
  assert.equal(sum.header.gt1Pence, undefined)
  assert.equal(sum.header.zNumber, undefined, 'and a pair has no single Z number')
})

test('a figure only one receipt printed is kept, not lost', () => {
  const a: ZRead = { ...emptyZRead(), transaction: { cashPence: 10_000 } }
  const b: ZRead = { ...emptyZRead(), transaction: { cardPence: 30_000 } }
  const sum = addZRead(a, b)
  assert.equal(sum.transaction.cashPence, 10_000)
  assert.equal(sum.transaction.cardPence, 30_000)
})
