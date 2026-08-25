import { test } from 'node:test'
import assert from 'node:assert/strict'
import { checkPrices, priceHeadline, type PriceBookEntry, type SoldItem } from '../src/core/priceBook.ts'
import { GARDENERS_ARMS } from './fixtures/gardenersArms.ts'

const sold: SoldItem[] = GARDENERS_ARMS.plus.map((p) => ({
  code: p.code,
  name: p.name,
  qtyMilli: p.qtyMilli,
  pence: p.pence,
}))

/** The prices the real roll implies, so a matching book means no variance. */
const AS_RUNG: PriceBookEntry[] = [
  { code: 'P00014', name: 'PINT TADDY LAGER', pence: 400 },
  { code: 'P00011', name: 'PINT OBB', pence: 360 },
  { code: 'P00013', name: 'PINT ALPINE', pence: 300 },
  { code: 'P00021', name: 'HALF TADDY LAGER', pence: 200 },
]

test('a book that matches the till finds nothing wrong', () => {
  const r = checkPrices(sold, AS_RUNG)
  assert.equal(r.pricedCount, 4)
  assert.equal(r.variancePence, 0)
  assert.equal(r.underPence, 0)
  assert.ok(r.rows.filter((x) => x.verdict !== 'unpriced').every((x) => x.verdict === 'matches'))
})

test('finds a pint rung under the board price, and what it cost over the night', () => {
  // The board says £4.20; the till averaged £4.00 across 120 pints.
  const r = checkPrices(sold, [{ code: 'P00014', name: 'PINT TADDY LAGER', pence: 420 }])
  const taddy = r.rows.find((x) => x.code === 'P00014')
  assert.equal(taddy?.verdict, 'under')
  assert.equal(taddy?.avgPencePerItem, 400)
  assert.equal(taddy?.expectedTakePence, 50400, '120 at £4.20')
  assert.equal(taddy?.variancePence, -2400, '£24 less than the board says')
  assert.equal(r.underPence, 2400)
})

test('finds a line rung above the board price too', () => {
  const r = checkPrices(sold, [{ code: 'P00014', name: 'PINT TADDY LAGER', pence: 380 }])
  assert.equal(r.rows.find((x) => x.code === 'P00014')?.verdict, 'over')
  assert.equal(r.overPence, 2400)
})

test('keeps shortfalls and overs apart, because a net figure hides both', () => {
  const r = checkPrices(sold, [
    { code: 'P00014', name: 'PINT TADDY LAGER', pence: 420 }, // £24 under
    { code: 'P00011', name: 'PINT OBB', pence: 324 }, // 66 x 36p over = £23.76
  ])
  assert.equal(r.underPence, 2400)
  assert.equal(r.overPence, 2376)
  assert.equal(r.variancePence, -24, 'the net is almost nothing, which is the trap')
})

test('puts the biggest shortfall at the top', () => {
  const r = checkPrices(sold, [
    { code: 'P00011', name: 'PINT OBB', pence: 370 }, // 66 x 10p = £6.60 under
    { code: 'P00014', name: 'PINT TADDY LAGER', pence: 420 }, // £24 under
  ])
  assert.equal(r.rows[0]?.code, 'P00014', 'the line worth looking at first')
})

test('matches on the printed name when the code has changed', () => {
  const r = checkPrices(sold, [{ name: 'PINT TADDY LAGER', pence: 420 }])
  assert.equal(r.pricedCount, 1)
  assert.equal(r.rows[0]?.variancePence, -2400)
})

test('matches a name whatever case or spacing it was written in', () => {
  const r = checkPrices(sold, [{ name: '  pint   taddy lager ', pence: 400 }])
  assert.equal(r.pricedCount, 1)
  assert.equal(r.rows[0]?.verdict, 'matches')
})

test('a code beats a name when the two disagree', () => {
  const r = checkPrices(sold, [
    { name: 'PINT TADDY LAGER', pence: 900 },
    { code: 'P00014', name: 'SOMETHING ELSE', pence: 400 },
  ])
  assert.equal(r.rows.find((x) => x.code === 'P00014')?.verdict, 'matches')
})

test('an item with no price set is reported, not silently ignored', () => {
  const r = checkPrices(sold, [{ code: 'P00014', name: 'PINT TADDY LAGER', pence: 400 }])
  assert.equal(r.pricedCount, 1)
  assert.equal(r.unpricedCount, 37)
  assert.equal(r.variancePence, 0, 'unpriced lines cannot count toward a variance')
})

test('a penny of rounding is not a mispricing', () => {
  // 3 sold for £9.90 is £3.30 each exactly; at a board price of £3.31 the
  // average is a penny out through division alone.
  const r = checkPrices([{ code: 'P00068', name: 'O A P', qtyMilli: 3000, pence: 990 }], [
    { code: 'P00068', name: 'O A P', pence: 331 },
  ])
  assert.equal(r.rows[0]?.verdict, 'matches')
})

test('says plainly when nothing could be checked', () => {
  const r = checkPrices(sold, [])
  assert.equal(r.pricedCount, 0)
  assert.equal(priceHeadline(r), 'No prices set yet, so nothing could be checked.')
})

test('does not divide by a quantity of zero', () => {
  const r = checkPrices([{ code: 'P00099', name: 'NEVER SOLD', qtyMilli: 0, pence: 0 }], [
    { code: 'P00099', name: 'NEVER SOLD', pence: 400 },
  ])
  assert.equal(r.rows[0]?.verdict, 'unpriced')
  assert.equal(r.unpricedCount, 1)
})

test('the headline never states a gap as proof of anything', () => {
  const r = checkPrices(sold, [{ code: 'P00014', name: 'PINT TADDY LAGER', pence: 420 }])
  const line = priceHeadline(r)
  assert.ok(/discount/i.test(line), 'the innocent explanation is named alongside the finding')
  assert.ok(!/theft|stealing|steal/i.test(line))
})

test('unpriced lines sort to the end, not in among the findings', () => {
  const r = checkPrices(sold, [{ code: 'P00014', name: 'PINT TADDY LAGER', pence: 420 }])
  assert.equal(r.rows[0]?.code, 'P00014', 'the one real finding leads')
  assert.ok(r.rows.slice(1).every((x) => x.verdict === 'unpriced'))
  // Among the unpriced, biggest takings first — those are worth pricing next.
  assert.equal(r.rows[1]?.name, 'PINT OBB')
})
