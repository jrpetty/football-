import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { bestMatch, normalise, score } from '../src/core/match.ts'

const TILL = [
  'PINT TADDY LAGER',
  'HALF TADDY LAGER',
  'PINT ALPINE',
  'HALF ALPINE',
  'PINT OBB',
  'GINGER BEER',
  'SPICED RUM',
  '175ML HOUSE WINE',
  '250ML HOUSE WINE',
  'CRISPS',
]

const pick = (written: string) => bestMatch(written, TILL, (t) => t)

test('normalising strips what a handwritten board adds', () => {
  assert.equal(normalise('  Taddy   Lager!  '), 'TADDY LAGER')
  assert.equal(normalise('175ml House Wine'), '175ML HOUSE WINE')
})

test('an exact name matches itself', () => {
  const m = pick('Pint Alpine')
  assert.equal(m.kind, 'matched')
  assert.equal(m.kind === 'matched' && m.value, 'PINT ALPINE')
})

test('a board name that could be the pint or the half is refused, not guessed', () => {
  // This is the whole reason the ambiguity branch exists: silently picking the
  // pint would price every half wrong for as long as nobody noticed.
  const m = pick('Taddy Lager')
  assert.equal(m.kind, 'ambiguous')
  assert.equal(m.kind === 'ambiguous' && m.between.length, 2)
})

test('the measure disambiguates once it is written down', () => {
  const m = pick('Half Taddy Lager')
  assert.equal(m.kind, 'matched')
  assert.equal(m.kind === 'matched' && m.value, 'HALF TADDY LAGER')
})

test('gin does not match ginger beer', () => {
  // The exact trap that broke the cellar guesser once already.
  assert.equal(pick('Gin').kind, 'unmatched')
  assert.ok(score('Gin', 'GINGER BEER') < 0.5)
})

test('a wine is matched by its measure', () => {
  const m = pick('House Wine 175ml')
  assert.equal(m.kind, 'matched')
  assert.equal(m.kind === 'matched' && m.value, '175ML HOUSE WINE')
})

test('something not on the till at all is left alone', () => {
  assert.equal(pick('Pork Scratchings').kind, 'unmatched')
  assert.equal(pick('').kind, 'unmatched')
})

test('a shortened first name still finds the person', () => {
  const people = ['Kelly', 'Dave', 'Marie']
  const m = bestMatch('Kel', people, (p) => p)
  assert.equal(m.kind, 'matched')
  assert.equal(m.kind === 'matched' && m.value, 'Kelly')
})

test('two people with the same first name are reported, not picked between', () => {
  const people = ['Dave Smith', 'Dave Jones']
  const m = bestMatch('Dave', people, (p) => p)
  assert.equal(m.kind, 'ambiguous')
})

test('an empty candidate list matches nothing', () => {
  assert.equal(bestMatch('Kelly', [], (p: string) => p).kind, 'unmatched')
})
