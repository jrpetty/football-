import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import {
  breakdown,
  containersToBase,
  containerBaseUnits,
  describeStock,
  ML_PER_PINT,
  type StockItem,
} from '../src/core/stock.ts'

/** A cask ale in kils — 144 pints to the container. */
const taddy: StockItem = {
  id: 'taddy',
  name: 'Taddy Lager',
  kind: 'liquid',
  servingBaseUnits: ML_PER_PINT,
  servingName: 'pint',
  container: { name: 'kil', baseUnits: 144 * ML_PER_PINT },
}

const crisps: StockItem = { id: 'c', name: 'Crisps', kind: 'count', servingBaseUnits: 1, servingName: 'each' }

test('a container size is read off the item', () => {
  assert.equal(containerBaseUnits(taddy), 144 * ML_PER_PINT)
  assert.equal(containerBaseUnits(crisps), null)
})

test('stock reads as barrels and what is left in the open one', () => {
  // 174 pints is one kil and thirty pints, which is how it is counted.
  const b = breakdown(174 * ML_PER_PINT, taddy)!
  assert.equal(b.full, 1)
  assert.equal(b.partServings, 30)
  assert.equal(b.totalServings, 174)
  assert.equal(describeStock(174 * ML_PER_PINT, taddy), '1 kil + 30 pints')
})

test('exact barrels read as barrels alone', () => {
  assert.equal(describeStock(288 * ML_PER_PINT, taddy), '2 kils')
  assert.equal(describeStock(144 * ML_PER_PINT, taddy), '1 kil')
})

test('less than a barrel reads as what is in it', () => {
  assert.equal(describeStock(30 * ML_PER_PINT, taddy), '30 pints')
  assert.equal(breakdown(30 * ML_PER_PINT, taddy)!.full, 0)
})

test('an empty cellar line says nothing rather than "0 kils"', () => {
  assert.equal(describeStock(0, taddy), '0 pints')
})

test('a line with no container falls back to plain servings', () => {
  assert.equal(describeStock(40, crisps), '40')
  assert.equal(breakdown(40, crisps), null)
})

test('a container no bigger than a serving is not a container', () => {
  // A 550ml bottle sold as a 550ml measure is itself the serving; describing
  // it as "40 bottles + 0" would be noise.
  const bottle: StockItem = {
    id: 'b', name: 'Alcohol free', kind: 'liquid',
    servingBaseUnits: 550, servingName: '550ml',
    container: { name: 'bottle', baseUnits: 550 },
  }
  assert.equal(breakdown(550 * 3, bottle), null)
})

test('counting barrels and a remainder gives back the base units', () => {
  assert.equal(containersToBase(1, 30, taddy), 174 * ML_PER_PINT)
  assert.equal(containersToBase(2, 0, taddy), 288 * ML_PER_PINT)
  assert.equal(containersToBase(0, 12.5, taddy), Math.round(12.5 * ML_PER_PINT))
})

test('a count and its description survive a round trip', () => {
  const base = containersToBase(3, 45, taddy)
  const b = breakdown(base, taddy)!
  assert.equal(b.full, 3)
  assert.equal(b.partServings, 45)
})
