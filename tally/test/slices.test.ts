// A photograph of a till roll is a photograph of a ribbon: tall, thin, and
// mostly small print. Squashed to fit the vision limit it reads back as a roll
// with departments and no drinks on it, because the department block is printed
// large and the item list is not. These are the sums that stop that.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { joinSlices, slicesFor } from '../src/ocr/image.ts'
import { parseZRead } from '../src/ocr/parseZRead.ts'

test('a squarish photograph is sent whole', () => {
  // Little is lost squashing these, and an extra request would buy a few per
  // cent of resolution for a whole second round trip.
  assert.equal(slicesFor(1000, 1000), 1)
  assert.equal(slicesFor(1200, 1400), 1)
})

test('an ordinary portrait snap of a receipt is cut in two', () => {
  // Four to three, which is what a phone gives you holding a roll up. Sent
  // whole, the long edge is squashed by nearly two thirds and the item list
  // goes with it — which is the fault this exists to fix.
  assert.equal(slicesFor(3024, 4032), 2)
  assert.equal(slicesFor(4032, 3024), 2)
})

test('a tall photograph of a roll is cut into more', () => {
  assert.equal(slicesFor(1500, 4000), 3)
  assert.equal(slicesFor(1200, 4000), 4)
  // And the same photograph on its side.
  assert.equal(slicesFor(4000, 1500), 3)
})

test('however long the roll, it is never more than four bands', () => {
  assert.equal(slicesFor(400, 12000), 4)
  assert.equal(slicesFor(100, 40000), 4)
})

test('a photograph with no size at all does not divide by zero', () => {
  assert.equal(slicesFor(0, 0), 1)
  assert.equal(slicesFor(1000, 0), 1)
})

test('joining two bands drops the lines the seam repeats', () => {
  const a = 'PLU\nP00001\nPINT DARK MILD\n*22.40\nP00011\nPINT OBB'
  const b = 'P00011\nPINT OBB\n*151.20\nP00013\nPINT ALPINE'
  assert.equal(
    joinSlices([a, b]),
    'PLU\nP00001\nPINT DARK MILD\n*22.40\nP00011\nPINT OBB\n*151.20\nP00013\nPINT ALPINE',
  )
})

test('bands that do not overlap are simply put end to end', () => {
  assert.equal(joinSlices(['one\ntwo', 'three\nfour']), 'one\ntwo\nthree\nfour')
})

test('one band is itself', () => {
  assert.equal(joinSlices(['only\nthis']), 'only\nthis')
})

test('a button caught on both bands is counted once, not twice', () => {
  // The real hazard. 42 pints of OBB read on the seam of two bands must not
  // become 84, which would be a hundred and fifty pounds of beer that never
  // left the cellar.
  const top = `PLU
P00001                          8.000 Q
PINT DARK MILD                  *22.40
P00011                         42.000 Q
PINT OBB                       *151.20`
  const bottom = `P00011                         42.000 Q
PINT OBB                       *151.20
P00013                         30.000 Q
PINT ALPINE                     *90.00`

  const z = parseZRead(joinSlices([top, bottom]))
  assert.equal(z.plus.length, 3)
  const obb = z.plus.filter((p) => p.code === 'P00011')
  assert.equal(obb.length, 1)
  assert.equal(obb[0]?.qtyMilli, 42000)
  assert.equal(obb[0]?.pence, 15120)
  assert.equal(z.plus.reduce((a, p) => a + p.pence, 0), 2240 + 15120 + 9000)
})

test('a repeat the seam-trimmer misses is still only counted once', () => {
  // Belt and braces: the two readings of the seam need not be identical text —
  // a space out of place is enough to get past the trimmer — so the parser
  // refuses a second line for a code it already has.
  const z = parseZRead(`PLU
P00011                         42.000 Q
PINT OBB                       *151.20
P00011                        42.000 Q
PINT OBB                      *151.20`)
  assert.equal(z.plus.length, 1)
  assert.equal(z.plus[0]?.pence, 15120)
})
