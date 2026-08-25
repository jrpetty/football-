import { test } from 'node:test'
import assert from 'node:assert/strict'
import { crossfoot, crossfootVerdict, checkContinuity } from '../src/core/crossfoot.ts'
import { GARDENERS_ARMS } from './fixtures/gardenersArms.ts'
import { formatQty, type ZRead } from '../src/core/zread.ts'

const clone = (): ZRead => structuredClone(GARDENERS_ARMS)

test('a correctly-read Z read agrees with itself on every count', () => {
  const v = crossfootVerdict(GARDENERS_ARMS)
  const failed = v.checks.filter((c) => !c.ok)
  assert.deepEqual(
    failed.map((c) => `${c.id}: expected ${c.expected}, got ${c.actual}`),
    [],
    'the real receipt must cross-foot perfectly',
  )
  assert.equal(v.clean, true)
  assert.ok(v.passed >= 20, `expected a decent number of checks, ran ${v.passed}`)
})

test('catches a department misread', () => {
  const z = clone()
  // £252.90 mixers read as £262.90 — one digit, entirely plausible.
  z.departments[4]!.pence = 26290
  const v = crossfootVerdict(z)
  assert.equal(v.clean, false)
  assert.ok(v.errors.some((c) => c.id === 'departments-sum'))
  assert.ok(v.errors.some((c) => c.id === 'group-GROUP01'))
})

test('catches the 2188.80 / 2188.40 confusion this receipt actually contains', () => {
  // The clerk total (£2,188.80) and GROUP01 (£2,188.40) are different real
  // figures that look near-identical on thermal paper. Swapping one for the
  // other is the single most likely misreading of this receipt.
  const z = clone()
  z.groups[0]!.pence = 218880
  const v = crossfootVerdict(z)
  assert.ok(v.errors.some((c) => c.id === 'group-GROUP01'), 'group must disagree with its departments')
  assert.ok(v.errors.some((c) => c.id === 'groups-sum'))
})

test('catches a wrong card total', () => {
  const z = clone()
  z.transaction.cardPence = 183700 // the clerk-4 figure, not the total
  const v = crossfootVerdict(z)
  assert.ok(v.errors.some((c) => c.id === 'tenders-sum'))
})

test('catches a wrong transaction count', () => {
  const z = clone()
  z.transaction.cardCount = 209
  const v = crossfootVerdict(z)
  assert.ok(v.errors.some((c) => c.id === 'tender-counts'))
})

test('catches the 689 / 699 quantity ambiguity', () => {
  // Genuinely unreadable on the photograph. The department quantities settle it.
  const z = clone()
  z.deptTotal!.qtyMilli = 699000
  const v = crossfootVerdict(z)
  assert.ok(v.errors.some((c) => c.id === 'departments-qty'))
})

test('catches a percentage that does not match its own value', () => {
  const z = clone()
  z.departments[0]!.percentBp = 6905
  const v = crossfootVerdict(z)
  assert.ok(v.warnings.some((c) => c.id === 'percent-D01'))
})

test('catches an item list that does not reach the department total', () => {
  // The mistake actually made transcribing this receipt by eye: the spirits
  // lines were read one column out, leaving the item list over by £25.35.
  const z = clone()
  z.plus = [
    { code: 'P00001', name: 'PINT DARK MILD', qtyMilli: 5000, pence: 1400 },
    { code: 'P00002', name: 'PINT CIDER', qtyMilli: 28000, pence: 14840 },
  ]
  z.pluTotal = { qtyMilli: 33000, pence: 16240 }
  const v = crossfootVerdict(z)
  assert.ok(v.checks.some((c) => c.id === 'plu-sum' && c.ok), 'the list adds up to its own total')
  assert.ok(
    v.errors.some((c) => c.id === 'plu-matches-departments'),
    'but it does not reach the department total, which is the real complaint',
  )
})

test('a float in the drawer is a question, not an error', () => {
  const z = clone()
  z.transaction.cidPence = 45180 // a £100 float left in overnight
  const v = crossfootVerdict(z)
  assert.equal(v.errors.length, 0, 'nothing here means a figure was misread')
  assert.ok(v.warnings.some((c) => c.id === 'cid-matches-cash'))
})

test('only runs checks whose figures were actually captured', () => {
  const bare: ZRead = {
    header: {},
    departments: [],
    groups: [],
    transaction: { cashPence: 35180 },
    clerks: [],
    plus: [],
  }
  // Cash alone cannot be checked against anything, so nothing is claimed.
  assert.deepEqual(crossfoot(bare), [])
})

test('notices a night that was never entered', () => {
  const previous = clone()
  const tonight = clone()
  tonight.header.zNumber = 1687 // 1686 was never saved
  const checks = checkContinuity(previous, tonight)
  const seq = checks.find((c) => c.id === 'z-sequence')
  assert.ok(seq)
  assert.equal(seq.ok, false)
  assert.equal(seq.expected, '1686')
})

test('confirms an unbroken run of nights', () => {
  const previous = clone()
  const tonight = clone()
  tonight.header.zNumber = 1686
  tonight.header.gt1Pence = 14011126 + 219280
  const checks = checkContinuity(previous, tonight)
  assert.ok(checks.every((c) => c.ok), checks.filter((c) => !c.ok).map((c) => c.id).join(', '))
})

test('notices when the running grand total does not move by the night', () => {
  const previous = clone()
  const tonight = clone()
  tonight.header.zNumber = 1686
  tonight.header.gt1Pence = 14011126 + 200000
  const checks = checkContinuity(previous, tonight)
  assert.ok(checks.some((c) => c.id === 'gt1-delta' && !c.ok))
})

test('prints quantities the way the till does', () => {
  assert.equal(formatQty(406000), '406')
  assert.equal(formatQty(0), '0')
  assert.equal(formatQty(1500), '1.5')
  assert.equal(formatQty(4088420), '4088.42')
  // A stranded decimal point would be a nonsense reading of a whole number.
  assert.ok(!formatQty(120000).endsWith('.'))
  assert.ok(!formatQty(4000001 / 1000 * 1000).endsWith('.'))
})

test('the running grand totals check each other', () => {
  // GT1 + |GT3| = GT2 on the real receipt, to the penny.
  const v = crossfootVerdict(GARDENERS_ARMS)
  const gt = v.checks.find((c) => c.id === 'grand-totals')
  assert.ok(gt, 'the check should run when all three are captured')
  assert.equal(gt.ok, true)
})

test('and catch a digit misread out of the padding zeros', () => {
  // "-00000021185.57" was transcribed as -21185.57 when it is -2185.57. The
  // leading zeros hide where the figure starts, and nothing else on the roll
  // refers to GT3 — so without this check the error is invisible.
  const z = clone()
  z.header.gt3Pence = -2118557
  const v = crossfootVerdict(z)
  assert.ok(v.warnings.some((c) => c.id === 'grand-totals'))
})

test('the grand-total check stays quiet when a figure was not captured', () => {
  const z = clone()
  delete z.header.gt3Pence
  assert.ok(!crossfoot(z).some((c) => c.id === 'grand-totals'))
})
