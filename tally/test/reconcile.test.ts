import { test } from 'node:test'
import assert from 'node:assert/strict'
import { reconcile, DEFAULT_TOLERANCE_PENCE } from '../src/core/reconcile.ts'
import { parseTolerance } from '../src/storage/settings.ts'

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
