import { test } from 'node:test'
import assert from 'node:assert/strict'
import { extractTotals, bestTotal, amountsInLine } from '../src/ocr/extractTotal.ts'

// A Z-read off a pub EPOS, with the breakdown lines that make this hard.
const TILL_ROLL = `
SAMUEL SMITH'S
THE OLD BAR
Z READ  0142
21/08/2026  23:47

DEPT 1 DRAUGHT      2104.50
DEPT 2 BOTTLES       612.20
DEPT 3 SPIRITS       498.60
DEPT 4 FOOD          997.00

SUBTOTAL            3510.25
VAT @ 20%            702.05
NO SALE                   3
CUSTOMER COUNT          342

GROSS TOTAL        £4212.30

CASH               £1890.55
CARD               £2321.75
CHANGE GIVEN        £102.30
`

// An end-of-day report off a card terminal.
const CARD_SLIP = `
CARD PAYMENT SOLUTIONS
MID 12345678
TID 87654321
END OF DAY REPORT
21/08/2026  23:52

SALES          142    £2321.75
REFUNDS          1      £12.00
CONTACTLESS     98    £1204.10
--------------------------------
TOTAL                 £2321.75

AID A0000000031010
CARD **** **** **** 4291
`

test('reads the gross total off a till roll', () => {
  const best = bestTotal(TILL_ROLL, 'till')
  assert.ok(best)
  assert.equal(best.pence, 421230)
  assert.equal(best.label, 'GROSSTOTAL')
  assert.equal(best.guessed, false)
})

test('is not fooled by the subtotal sitting right above it', () => {
  // SUBTOTAL is a real total of a real thing, and the wrong one. It is also
  // the closest wording to what we want, which is exactly why it is vetoed.
  const totals = extractTotals(TILL_ROLL, 'till')
  assert.ok(!totals.some((t) => t.pence === 351025), 'the ex-VAT subtotal must never win')
})

test('is not fooled by the cash and card split underneath it', () => {
  const totals = extractTotals(TILL_ROLL, 'till')
  assert.ok(!totals.some((t) => t.pence === 189055), 'cash line')
  assert.ok(!totals.some((t) => t.pence === 232175), 'card line')
})

test('ignores the counters that are not money at all', () => {
  const totals = extractTotals(TILL_ROLL, 'till')
  assert.ok(!totals.some((t) => t.pence === 34200), 'customer count of 342')
  assert.ok(!totals.some((t) => t.pence === 300), 'three no-sales')
})

test('reads the total off a card terminal report', () => {
  const best = bestTotal(CARD_SLIP, 'card')
  assert.ok(best)
  assert.equal(best.pence, 232175)
  assert.equal(best.guessed, false)
})

test('ignores terminal identifiers and the masked card number', () => {
  const totals = extractTotals(CARD_SLIP, 'card')
  assert.ok(!totals.some((t) => t.pence === 1234567800), 'MID')
  assert.ok(!totals.some((t) => t.pence === 429100), 'last four of the PAN')
  assert.ok(!totals.some((t) => t.pence === 1200), 'the refund line')
})

test('prefers the grand total when a roll prints several', () => {
  const text = `TOTAL SALES 3000.00\nNET TOTAL 3510.25\nGRAND TOTAL 4212.30`
  assert.equal(bestTotal(text, 'till')?.pence, 421230)
})

test('reads a figure printed on the line below its wording', () => {
  const text = `SALES\nGRAND TOTAL\n£4212.30\n`
  const best = bestTotal(text, 'till')
  assert.equal(best?.pence, 421230)
})

test('survives the characters a scanner gets wrong on thermal paper', () => {
  // Faded roll: O for zero, S for 5, l for 1, B for 8.
  const text = `GROSS TOTAL   £42l2.3O`
  assert.equal(bestTotal(text, 'till')?.pence, 421230)
})

test('takes the right-hand figure when a line carries several', () => {
  const text = `TOTAL     142     £2321.75`
  assert.equal(bestTotal(text, 'card')?.pence, 232175)
})

test('does not merge two columns into one number', () => {
  // The failure this guards: "1" and "2104.50" in separate columns becoming
  // £12,104.50 — wrong, and wrong in a way that looks perfectly plausible.
  const amounts = amountsInLine('DEPT 1        2104.50')
  assert.ok(!amounts.some((a) => a.pence === 1210450))
  assert.ok(amounts.some((a) => a.pence === 210450))
})

test('still reads a space used as a thousands separator', () => {
  const amounts = amountsInLine('GROSS TOTAL   4 212.30')
  assert.ok(amounts.some((a) => a.pence === 421230))
})

test('falls back to the largest amount, and admits it is guessing', () => {
  const text = `THE OLD BAR\n21/08/2026\n\n2104.50\n£4212.30\n612.20\n`
  const best = bestTotal(text, 'till')
  assert.equal(best?.pence, 421230)
  assert.equal(best?.guessed, true, 'must be flagged so the interface can say so')
})

test('returns nothing rather than something wrong when the scan is unusable', () => {
  assert.equal(bestTotal('', 'till'), null)
  assert.equal(bestTotal('~~~ ### ~~~\nno text here', 'till'), null)
})

test('ranks candidates best-first so the interface can offer alternatives', () => {
  const totals = extractTotals(TILL_ROLL, 'till')
  assert.ok(totals.length >= 1)
  for (let i = 1; i < totals.length; i++) {
    assert.ok((totals[i - 1]?.score ?? 0) >= (totals[i]?.score ?? 0))
  }
})
