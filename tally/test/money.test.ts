import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parsePence, formatMoney, formatSigned, penceToInput } from '../src/core/money.ts'

test('reads the amounts a person types', () => {
  assert.equal(parsePence('12.34'), 1234)
  assert.equal(parsePence('£12.34'), 1234)
  assert.equal(parsePence(' £12.34 '), 1234)
  assert.equal(parsePence('12'), 1200)
  assert.equal(parsePence('0'), 0)
  assert.equal(parsePence('.5'), 50)
  assert.equal(parsePence('12.5'), 1250, 'one decimal place means tenths of a pound')
})

test('reads thousands the way a British till prints them', () => {
  assert.equal(parsePence('1,234.56'), 123456)
  assert.equal(parsePence('£1,234.56'), 123456)
  assert.equal(parsePence('12,345.67'), 1234567)
})

test('a bare group of three digits is thousands, not pence', () => {
  // "1,234" on a till roll is twelve hundred pounds. Reading it as £1.23 would
  // be a hundredfold error in the direction nobody would notice as absurd.
  assert.equal(parsePence('1,234'), 123400)
  assert.equal(parsePence('1.234'), 123400)
})

test('handles a continental card terminal', () => {
  assert.equal(parsePence('1.234,56'), 123456)
  assert.equal(parsePence('1 234,56'), 123456)
})

test('reads refunds and negative totals', () => {
  assert.equal(parsePence('-12.34'), -1234)
  assert.equal(parsePence('−12.34'), -1234, 'a real minus sign, as printed')
  assert.equal(parsePence('12.34-'), -1234, 'trailing minus, as some terminals print')
  assert.equal(parsePence('(12.34)'), -1234, 'accounting brackets')
})

test('refuses text that is not an amount', () => {
  assert.equal(parsePence(''), null)
  assert.equal(parsePence('   '), null)
  assert.equal(parsePence('TOTAL'), null)
  assert.equal(parsePence('abc'), null)
  assert.equal(parsePence('12.34.56'), null)
  assert.equal(parsePence('£'), null)
})

test('a three-digit tail is thousands, since the alternative is not a real amount', () => {
  // "12.345" cannot be twelve pounds and thirty-four and a half pence — no
  // such amount exists. Read as grouping it is £12,345, which at least is a
  // number. Whether it is a *believable* night's takings is a separate
  // question, and isPlausibleTakings answers it.
  assert.equal(parsePence('12.345'), 1234500)
})

test('refuses a decimal that cannot be grouping either', () => {
  assert.equal(parsePence('12.3456'), null, 'four decimals is simply a bad read')
  assert.equal(parsePence('12,34,56'), null, 'not how any till groups thousands')
})

test('refuses a figure no pub took in a night', () => {
  assert.equal(parsePence('999999999.00'), null)
})

test('repairs the characters a scanner misreads, but only inside numbers', () => {
  assert.equal(parsePence('l2.34', { loose: true }), 1234, 'lowercase L for 1')
  assert.equal(parsePence('I2.34', { loose: true }), 1234)
  assert.equal(parsePence('12.3O', { loose: true }), 1230, 'letter O for zero')
  assert.equal(parsePence('S6.78', { loose: true }), 5678, 'S for 5')
  assert.equal(parsePence('B4.21', { loose: true }), 8421, 'B for 8')
  assert.equal(parsePence('1|.00', { loose: true }), 1100)
})

test('does not invent an amount out of receipt wording', () => {
  // The whole reason the repair is guarded. Unguarded, these map to clean
  // digits and would parse as confident, entirely fictional money.
  assert.equal(parsePence('SALE', { loose: true }), null)
  assert.equal(parsePence('TOTAL', { loose: true }), null)
  assert.equal(parsePence('GOOSE', { loose: true }), null)
  assert.equal(parsePence('BILL', { loose: true }), null)
})

test('strict mode leaves a typed S alone', () => {
  assert.equal(parsePence('S6.78'), null, 'a person typing S meant to type S')
})

test('formats for a phone screen at midnight', () => {
  assert.equal(formatMoney(123456), '£1,234.56')
  assert.equal(formatMoney(0), '£0.00')
  assert.equal(formatMoney(5), '£0.05')
  assert.equal(formatSigned(-500), '−£5.00')
  assert.equal(formatSigned(500), '+£5.00')
  assert.equal(formatSigned(0), '£0.00')
})

test('round-trips through an editable field', () => {
  for (const pence of [0, 5, 50, 1234, 123456]) {
    assert.equal(parsePence(penceToInput(pence)), pence)
  }
  assert.equal(penceToInput(null), '')
})
