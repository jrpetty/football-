// ---------------------------------------------------------------------------
// A roll photographed in pieces, and taken apart again.
//
// The roll does not fit in one frame, so it arrives as two or three
// photographs. One of them comes out blurred often enough that "start again"
// cannot be the only answer — that means retaking all three and paying to read
// all three. So a roll has to be re-derivable from the reads already in hand,
// minus the one being thrown away.
//
// These tests split the real Gardeners Arms roll the way a phone splits it and
// then put it back together, because the arithmetic that matters is which
// figure wins when two photographs both show a line.
// ---------------------------------------------------------------------------

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { foldRolls, type PhotoOutcome } from '../src/ocr/scanZRead.ts'
import { parseZRead } from '../src/ocr/parseZRead.ts'
import { emptyZRead, isZReadEmpty, sectionsIn, type ZRead } from '../src/core/zread.ts'
import { GARDENERS_ARMS_TEXT } from './fixtures/gardenersArms.ts'

const WHOLE = parseZRead(GARDENERS_ARMS_TEXT)

/** The roll torn where a phone would tear it: departments, then the rest. */
const CUT = GARDENERS_ARMS_TEXT.indexOf('ALL CLERK')
const TOP = parseZRead(GARDENERS_ARMS_TEXT.slice(0, CUT))
const BOTTOM = parseZRead(GARDENERS_ARMS_TEXT.slice(CUT))

function shot(index: number, parsed: ZRead): PhotoOutcome {
  return { index, sections: sectionsIn(parsed), parsed }
}

/**
 * The same read, allowing for a key that is absent against one that is present
 * and undefined. Folding writes every key; parsing only writes the ones it
 * found. That is a difference in shape, not in what the roll says.
 */
/** The whole pile folded the way the card folds it. */
function fold(outcomes: PhotoOutcome[], base?: ZRead): ZRead {
  return foldRolls(outcomes, base ? { base } : {}).zRead
}

function same(actual: ZRead, expected: ZRead): void {
  assert.deepEqual(JSON.parse(JSON.stringify(actual)), JSON.parse(JSON.stringify(expected)))
}

test('two photographs of one roll come back as the whole roll', () => {
  const merged = fold([shot(0, TOP), shot(1, BOTTOM)])
  assert.equal(merged.departments.length, WHOLE.departments.length)
  assert.equal(merged.deptTotal?.pence, WHOLE.deptTotal?.pence)
  assert.equal(merged.transaction.paidTotalPence, WHOLE.transaction.paidTotalPence)
})

test('the order they were picked in does not change the answer', () => {
  const forwards = fold([shot(0, TOP), shot(1, BOTTOM)])
  // Handed over back to front, but still numbered as they sit in the roll.
  const backwards = fold([shot(1, BOTTOM), shot(0, TOP)])
  same(backwards, forwards)
})

test('dropping one photograph drops only what it read', () => {
  const both = [shot(0, TOP), shot(1, BOTTOM)]
  assert.ok(both.length === 2)

  // The second one was blurred. What is left must be exactly the first — not
  // a re-scan, not a blank, and above all not the merged figures lingering on.
  const left = fold(both.filter((o) => o.index !== 1))
  same(left, TOP)
  assert.equal(left.departments.length, TOP.departments.length)
  assert.equal(left.clerks.length, 0, "the clerk section went with the photograph that showed it")
})

test('dropping the last photograph leaves nothing, rather than something stale', () => {
  const left = fold([])
  assert.ok(isZReadEmpty(left))
  same(left, emptyZRead())
})

test('a photograph that read nothing takes nothing with it', () => {
  const blurred: PhotoOutcome = { index: 1, sections: [] }
  const failed: PhotoOutcome = { index: 2, sections: [], error: 'Rate limited' }
  const merged = fold([shot(0, TOP), blurred, failed])
  same(merged, TOP)
})

test('a night reopened folds tonight onto what was already read', () => {
  // The base is the night as it was saved. A photograph added the next morning
  // adds to it; it does not replace it.
  const merged = fold([shot(0, BOTTOM)], TOP)
  assert.equal(merged.departments.length, TOP.departments.length)
  assert.equal(merged.transaction.paidTotalPence, BOTTOM.transaction.paidTotalPence)
})

test('a figure corrected by hand is not undone by throwing a photograph away', () => {
  // She corrected the department total in the review screen, so it is the base.
  const corrected: ZRead = { ...WHOLE, deptTotal: { pence: 219999, qtyMilli: 0 } }
  const left = fold([], corrected)
  assert.equal(left.deptTotal?.pence, 219999)
})

test('a later photograph of the same line wins over an earlier one', () => {
  // The same section shot twice — the retake is the one to believe.
  const first = parseZRead('DEPT./GROUP\nD01                             406.000 Q\nDRAUGHT BEERS                    *1492.25\n')
  const retake = parseZRead('DEPT./GROUP\nD01                             406.000 Q\nDRAUGHT BEERS                    *1499.25\n')
  const merged = fold([shot(0, first), shot(1, retake)])
  assert.equal(merged.departments[0]?.pence, 149925)
})
