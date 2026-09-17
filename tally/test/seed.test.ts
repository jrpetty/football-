import { test } from 'node:test'
import assert from 'node:assert/strict'
import { seedCellar } from '../src/storage/seed.ts'
import { ML_PER_PINT, formatServings, type StockItem } from '../src/core/stock.ts'

const { items, count } = seedCellar()
const byId = new Map(items.map((i) => [i.id, i]))
const found = new Map(count.lines.map((l) => [l.stockItemId, l.baseUnits]))

/** What a line was counted at, in the words the cellar uses. */
function reads(id: string): string {
  const item = byId.get(id) as StockItem
  return formatServings(found.get(id) ?? 0, item)
}

test('every figure counted belongs to a line the cellar knows', () => {
  for (const line of count.lines) assert.ok(byId.has(line.stockItemId), line.stockItemId)
  assert.equal(count.lines.length, items.length, 'and every line was counted')
})

test('the casks come to what the sheet said', () => {
  // Five kegs and one pretty much full, counted as six.
  assert.equal(found.get('taddy-lager'), 6 * 176 * ML_PER_PINT)
  assert.equal(reads('taddy-lager'), '1056 pints')
  // Two kegs and a tenth.
  assert.equal(reads('alpine'), '302.4 pints')
})

test('a keg on the scales is the pints that are in it', () => {
  // A small keg is 13.40kg empty and 64.75 full, so 51.35kg of beer in 88 pints.
  assert.equal(reads('stout'), '218.2 pints', 'two kegs and one at 38.05kg')
  assert.equal(reads('cider'), '171 pints', 'one keg and one at 61.85kg')
  assert.equal(reads('dark-mild'), '67.9 pints', 'one keg at 53kg')
})

test('the wine is bottles and what is left in the open one', () => {
  assert.equal(found.get('rose'), 25 * 750)
  assert.equal(found.get('red-wine'), 15 * 750 + 625)
  assert.equal(found.get('house-wine'), 74 * 750 + 375)
  assert.equal(reads('house-wine'), '55875 ml')
})

test('a wine bottle can be weighed, and a middle keg cannot yet', () => {
  const rose = byId.get('rose') as StockItem
  assert.deepEqual(rose.container, { name: 'wine bottle', baseUnits: 750, emptyKg: 0.175, fullKg: 1.125 })
  const alpine = byId.get('alpine') as StockItem
  assert.equal(alpine.container?.emptyKg, undefined, 'nobody has weighed an empty one')
  assert.equal(alpine.container?.fullKg, 103, 'but the full one was weighed, and that is kept')
  assert.equal(alpine.container?.baseUnits, 144 * ML_PER_PINT)
})

test('the bottles and the packets are counted one by one', () => {
  assert.equal(reads('cherry'), '46 bottles')
  assert.equal(reads('tonic'), '41 bottles')
  assert.equal(reads('crisps-cheese'), '107 units')
  assert.equal(byId.get('crisps-cheese')?.container?.baseUnits, 25, 'twenty-five to a box')
  assert.equal(reads('salted-nuts'), '68 units')
  assert.equal(byId.get('salted-nuts')?.container, undefined, 'nuts come loose')
})

test('nothing that was not counted is claimed to have been', () => {
  // The spirits and the post mix were not done. A line missing from a count is
  // "not counted", which the whole cellar treats differently from none — so
  // the one thing this must never do is invent a zero for them.
  for (const id of ['vodka', 'gin', 'bourbon', 'spiced-rum', 'post-mix']) {
    assert.ok(!byId.has(id), id)
    assert.ok(!found.has(id), id)
  }
})

test('it is one night’s count, dated', () => {
  assert.match(count.date, /^\d{4}-\d{2}-\d{2}$/)
  assert.ok((count.note ?? '').length > 0)
})
