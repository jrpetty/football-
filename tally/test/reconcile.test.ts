import { test } from 'node:test'
import assert from 'node:assert/strict'
import { reconcile, DEFAULT_TOLERANCE_PENCE } from '../src/core/reconcile.ts'
import { reconcileFull, itemisedHeadline } from '../src/core/reconcile.ts'
import { parseTolerance } from '../src/storage/settings.ts'
import { GARDENERS_ARMS } from './fixtures/gardenersArms.ts'

const at = (till: number | null, card: number | null, cash: number | null) =>
  reconcile({ tillPence: till, cardPence: card, cashPence: cash })

test('a night that balances exactly', () => {
  const r = at(100000, 60000, 40000)
  assert.equal(r.verdict, 'balanced')
  assert.equal(r.variancePence, 0)
  assert.equal(r.complete, true)
})

test('short means less money present than the till says was taken', () => {
  const r = at(100000, 60000, 39000)
  assert.equal(r.verdict, 'short')
  assert.equal(r.variancePence, -1000)
})

test('over means more money present than the till says', () => {
  const r = at(100000, 60000, 41000)
  assert.equal(r.verdict, 'over')
  assert.equal(r.variancePence, 1000)
})

test('a few pence either way still counts as balanced', () => {
  // An app that cried wolf over 3p every night would be ignored inside a week.
  assert.equal(at(100000, 60000, 40003).verdict, 'balanced')
  assert.equal(at(100000, 60000, 39997).verdict, 'balanced')
})

test('the tolerance is a boundary, not a fudge', () => {
  const short = 100000 - DEFAULT_TOLERANCE_PENCE
  assert.equal(at(100000, 0, short).verdict, 'balanced', 'exactly on tolerance')
  assert.equal(at(100000, 0, short - 1).verdict, 'short', 'one penny past it')
})

test('the tolerance can be tightened to nothing', () => {
  const r = reconcile({ tillPence: 100000, cardPence: 0, cashPence: 99999, tolerancePence: 0 })
  assert.equal(r.verdict, 'short')
  assert.equal(r.variancePence, -1)
})

test('an unfinished night says so instead of guessing', () => {
  const r = at(100000, null, 40000)
  assert.equal(r.verdict, 'incomplete')
  assert.equal(r.complete, false)
  assert.deepEqual(r.missing, ['card'])
  assert.equal(r.variancePence, 0, 'no half-computed number to misread')
})

test('names every figure still outstanding', () => {
  assert.deepEqual(at(null, null, null).missing, ['till', 'card', 'cash'])
})

test('zero is a real figure, not a missing one', () => {
  // A card machine that took nothing all night prints £0.00, and that is data.
  const r = at(40000, 0, 40000)
  assert.equal(r.verdict, 'balanced')
  assert.equal(r.complete, true)
})

test('integer pence throughout — no floating point drift', () => {
  // 0.1 + 0.2 in pounds would not equal 0.3. In pence it is just 30.
  const r = at(30, 10, 20)
  assert.equal(r.variancePence, 0)
  assert.equal(r.verdict, 'balanced')
})

test('a fresh install gets the intended tolerance, not zero', () => {
  // Number(null) is 0, not NaN. Read naively, "never configured" becomes "warn
  // about every penny" — the exact behaviour the tolerance exists to avoid,
  // shipped as the default.
  assert.equal(parseTolerance(null), DEFAULT_TOLERANCE_PENCE)
  assert.equal(parseTolerance(''), DEFAULT_TOLERANCE_PENCE)
  assert.equal(parseTolerance('   '), DEFAULT_TOLERANCE_PENCE)
  assert.equal(parseTolerance('not a number'), DEFAULT_TOLERANCE_PENCE)
  assert.equal(parseTolerance('-5'), DEFAULT_TOLERANCE_PENCE)
})

test('a tolerance she has set is honoured, including zero', () => {
  assert.equal(parseTolerance('0'), 0, 'deliberately strict is a real choice')
  assert.equal(parseTolerance('100'), 100)
})

// --- reconciling against the till's own figures -----------------------------

test('splits the variance into the drawer and the card machine', () => {
  // The real receipt: the till says £351.80 cash and £1,841.00 card. She counts
  // £339.80 in the drawer and the card slip agrees exactly.
  const r = reconcileFull({
    tillPence: null,
    cardPence: 184100,
    cashPence: 33980,
    zRead: GARDENERS_ARMS,
  })
  assert.equal(r.itemised, true)
  assert.equal(r.card?.verdict, 'balanced')
  assert.equal(r.cash?.verdict, 'short')
  assert.equal(r.cash?.variancePence, -1200)
  assert.equal(
    itemisedHeadline(r),
    'The card machine agrees with the till — the difference is in the drawer.',
  )
})

test('takes the till total from the roll rather than asking for it twice', () => {
  const r = reconcileFull({
    tillPence: null,
    cardPence: 184100,
    cashPence: 35180,
    zRead: GARDENERS_ARMS,
  })
  assert.equal(r.overall.complete, true, 'the roll supplies the till figure')
  assert.equal(r.overall.verdict, 'balanced')
  assert.equal(r.overall.variancePence, 0)
})

test('the two legs always sum to the overall variance', () => {
  // Guaranteed by the receipt's own cash + card = paid total, which crossfoot
  // verifies — so if this ever drifts, the roll was misread.
  const r = reconcileFull({
    tillPence: null,
    cardPence: 183900,
    cashPence: 34980,
    zRead: GARDENERS_ARMS,
  })
  assert.equal((r.cash?.variancePence ?? 0) + (r.card?.variancePence ?? 0), r.overall.variancePence)
})

test('names the card machine when that is the side that disagrees', () => {
  const r = reconcileFull({
    tillPence: null,
    cardPence: 183000,
    cashPence: 35180,
    zRead: GARDENERS_ARMS,
  })
  assert.equal(
    itemisedHeadline(r),
    'The drawer agrees with the till — the difference is on the card machine.',
  )
})

test('says so plainly when everything agrees', () => {
  const r = reconcileFull({ tillPence: null, cardPence: 184100, cashPence: 35180, zRead: GARDENERS_ARMS })
  assert.equal(itemisedHeadline(r), 'The drawer and the card machine both agree with the till.')
})

test('falls back to the plain sum when no roll was captured', () => {
  const r = reconcileFull({ tillPence: 219280, cardPence: 184100, cashPence: 35180 })
  assert.equal(r.itemised, false)
  assert.equal(itemisedHeadline(r), null)
  assert.equal(r.overall.verdict, 'balanced')
})

test('prefers cash in drawer over cash taken, when the till states both', () => {
  const z = structuredClone(GARDENERS_ARMS)
  z.transaction.cidPence = 45180 // £100 float left in overnight
  const r = reconcileFull({ tillPence: null, cardPence: 184100, cashPence: 45180, zRead: z })
  assert.equal(r.cash?.expectedPence, 45180, 'CID is the drawer figure specifically')
  assert.equal(r.cash?.verdict, 'balanced')
})
