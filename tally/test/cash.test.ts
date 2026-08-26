import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import {
  countPieces,
  countTotal,
  DENOMINATIONS,
  splitDrawer,
  suggestFloat,
  type Tally,
} from '../src/core/cash.ts'

test('a counted drawer adds up', () => {
  // Eleven twenties, six tens, a five, and some silver.
  const tally: Tally = { 2000: 11, 1000: 6, 500: 1, 100: 12, 50: 7, 20: 4 }
  assert.equal(countTotal(tally), 22000 + 6000 + 500 + 1200 + 350 + 80)
})

test('an empty drawer is nothing, not a crash', () => {
  assert.equal(countTotal({}), 0)
  assert.deepEqual(countPieces({}), { notes: 0, coins: 0 })
})

test('a stray or half-typed entry cannot move the total', () => {
  // Mid-keystroke and mistyped values must not flicker the figure through
  // something wrong — a wrong total looks exactly like a shortfall.
  assert.equal(countTotal({ 2000: Number.NaN }), 0)
  assert.equal(countTotal({ 2000: -3 }), 0)
  assert.equal(countTotal({ 2000: Number.POSITIVE_INFINITY }), 0)
  assert.equal(countTotal({ 9999: 5 }), 0, 'not a denomination this drawer holds')
  assert.equal(countTotal({ 2000: 2.7 }), 4000, 'two and a bit twenties is two twenties')
})

test('notes and coins are counted apart', () => {
  const pieces = countPieces({ 2000: 11, 1000: 6, 100: 12, 50: 7 })
  assert.deepEqual(pieces, { notes: 17, coins: 19 })
})

test('every denomination is a real one, biggest first', () => {
  const values = DENOMINATIONS.map((d) => d.pence)
  assert.deepEqual(values, [...values].sort((a, b) => b - a))
  assert.equal(values.includes(5000), true)
  assert.equal(values.includes(1), true)
})

// --- the float ----------------------------------------------------------------

test('the float comes off before anything reconciles', () => {
  // The fault this exists to prevent: £551.80 in the drawer with a £200 float
  // is £351.80 of takings. Counting the lot would read £200 over — every
  // single night, consistently enough that it looks like the pub doing well.
  const split = splitDrawer(55180, 20000)
  assert.equal(split.takingsPence, 35180)
  assert.equal(split.impossible, false)
})

test('no float leaves the drawer as it is', () => {
  assert.equal(splitDrawer(35180, 0).takingsPence, 35180)
})

test('a float bigger than the drawer is reported, not clamped', () => {
  // Showing zero takings on a night that clearly took money would be worse
  // than saying the two figures cannot both be right.
  const split = splitDrawer(15000, 20000)
  assert.equal(split.impossible, true)
  assert.equal(split.takingsPence, -5000, 'the arithmetic is left visible')
})

test('a negative float is treated as none rather than added on', () => {
  assert.equal(splitDrawer(35180, -5000).takingsPence, 35180)
})

// --- making tomorrow's float --------------------------------------------------

test('a suggested float comes to the figure asked for', () => {
  for (const target of [10000, 15000, 20000, 25000, 5000]) {
    const tally = suggestFloat(target)
    assert.ok(tally, `nothing suggested for ${target}`)
    assert.equal(countTotal(tally as Tally), target, `${target} did not add up`)
  }
})

test('a float is change, not a handful of fifty pound notes', () => {
  // Greedy-largest-first would hand back £200 as four fifties, which cannot
  // give anybody change for a fiver.
  const tally = suggestFloat(20000) as Tally
  assert.ok((tally[100] ?? 0) >= 15, 'wants a good depth of pound coins')
  assert.ok((tally[50] ?? 0) >= 10, 'and of fifty pence pieces')
  assert.equal(tally[5000] ?? 0, 0, 'no fifty pound notes in a float')
})

test('a large float does not degenerate into a bag of pennies', () => {
  // The regression: the leftover pass ran smallest-first, so a £300 float came
  // back with twelve hundred 1p coins.
  for (const target of [30000, 50000]) {
    const tally = suggestFloat(target) as Tally
    assert.ok(tally, `nothing suggested for ${target}`)
    assert.equal(countTotal(tally), target)
    assert.ok((tally[1] ?? 0) <= 10, `${target} suggested ${tally[1]} pennies`)
    assert.equal(tally[5000] ?? 0, 0, 'and still no fifties')
  }
})

test('an odd amount still makes up exactly', () => {
  const tally = suggestFloat(20037) as Tally
  assert.equal(countTotal(tally), 20037)
  assert.equal(tally[2] ?? 0, 1)
  assert.equal(tally[5] !== undefined, true)
})

test('a nonsense target suggests nothing', () => {
  assert.equal(suggestFloat(0), null)
  assert.equal(suggestFloat(-100), null)
  assert.equal(suggestFloat(Number.NaN), null)
})

test('a float smaller than the working depth is still made up', () => {
  const tally = suggestFloat(500) as Tally
  assert.equal(countTotal(tally), 500)
})
