// ---------------------------------------------------------------------------
// Tying the till's lines to the cellar's.
//
// This is the join everything else hangs off. Until a sold line knows which
// cellar line it draws on, a receipt takes nothing off the stock and the
// figures quietly read high.
//
// It matters most for a cellar counted onto paper first: those lines are
// called what the landlady calls them, and the till calls the same drink
// something longer. Building the cellar from the till instead makes a second
// set of lines beside the real ones and splits the stock in two.
// ---------------------------------------------------------------------------

import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import {
  DEFAULT_ML_PER_SHOT,
  ML_PER_PINT,
  pourUsage,
  proposePours,
  type Pour,
  type SoldLine,
  type StockItem,
} from '../src/core/stock.ts'
import { bestMatch } from '../src/core/match.ts'

/** The cellar as the seeded stock take leaves it: real names, no pours. */
const taddy: StockItem = {
  id: 'taddy', name: 'Taddy Lager', kind: 'liquid',
  servingBaseUnits: ML_PER_PINT, servingName: 'pint',
  container: { name: 'firkin', baseUnits: 72 * ML_PER_PINT },
}
const stout: StockItem = {
  id: 'stout', name: 'Extra Stout', kind: 'liquid',
  servingBaseUnits: ML_PER_PINT, servingName: 'pint',
}
const houseWhite: StockItem = {
  id: 'house-wine', name: 'White wine', kind: 'liquid',
  servingBaseUnits: 1, servingName: 'ml',
}
const crisps: StockItem = {
  id: 'crisps', name: 'Crisps', kind: 'count', servingBaseUnits: 1, servingName: 'each',
}
const CELLAR = [taddy, stout, houseWhite, crisps]

const sold = (code: string, name: string, qty = 10): SoldLine => ({ code, name, qtyMilli: qty * 1000 })

function propose(lines: SoldLine[], items = CELLAR, pours: Pour[] = [], notStock: string[] = []) {
  return proposePours(lines, items, pours, DEFAULT_ML_PER_SHOT, bestMatch, notStock)
}

test('a till line finds the cellar line somebody already counted', () => {
  const [row] = propose([sold('1', 'PINT TADDY LAGER')])
  assert.equal(row?.how, 'matched')
  assert.equal(row?.stockItemId, 'taddy', 'the line off the sheet, not a new one')
  assert.equal(row?.baseUnits, ML_PER_PINT, 'a pint takes a pint')
})

test('a half takes half as much off the same line', () => {
  const [row] = propose([sold('2', 'HALF TADDY LAGER')])
  assert.equal(row?.stockItemId, 'taddy')
  assert.equal(row?.baseUnits, ML_PER_PINT / 2)
})

test('a drink the cellar has never heard of is a new line, not a wrong one', () => {
  const [row] = propose([sold('7', 'PINT GUINNESS')])
  assert.equal(row?.how, 'new')
  assert.equal(row?.stockItemId, null)
  assert.ok(row?.guess.stockName)
})

test('a bottle is never matched to a line counted in pints', () => {
  // "BOT PURE BREW" and "PINT PURE BREW" are the same words and not the same
  // stock. Matching them would take a bottle off a line counted in pints,
  // which is nought pints, silently, for ever.
  const pureBrew: StockItem = {
    id: 'pure-brew', name: 'Pure Brew', kind: 'liquid',
    servingBaseUnits: ML_PER_PINT, servingName: 'pint',
  }
  const [row] = propose([sold('9', 'BOT PURE BREW')], [pureBrew])
  assert.notEqual(row?.stockItemId, 'pure-brew')
  assert.equal(row?.how, 'new')
})

test('lines already poured are left alone', () => {
  const pours: Pour[] = [{ itemCode: '1', itemName: 'PINT TADDY LAGER', stockItemId: 'taddy', baseUnits: ML_PER_PINT }]
  assert.deepEqual(propose([sold('1', 'PINT TADDY LAGER')], CELLAR, pours), [])
})

test('a line already poured under another code is matched by its printed name', () => {
  // The till's codes get reshuffled; its printed names do not.
  const pours: Pour[] = [{ itemCode: 'OLD', itemName: 'PINT TADDY LAGER', stockItemId: 'taddy', baseUnits: ML_PER_PINT }]
  assert.deepEqual(propose([sold('NEW', 'PINT TADDY LAGER')], CELLAR, pours), [])
})

test('the whole roll is proposed at once, biggest sellers in the order given', () => {
  const rows = propose([sold('1', 'PINT TADDY LAGER', 400), sold('7', 'PINT GUINNESS', 30), sold('3', 'CRISPS', 20)])
  assert.deepEqual(rows.map((r) => r.itemCode), ['1', '7', '3'])
})

// --- what is deliberately not cellar stock ----------------------------------------

test('a line said not to be stock stops being proposed', () => {
  assert.deepEqual(propose([sold('50', 'COFFEE')], CELLAR, [], ['50']), [])
})

test('and stops being reported as stock that left uncounted', () => {
  const lines = [sold('50', 'COFFEE'), sold('7', 'PINT GUINNESS')]
  const before = pourUsage(lines, [])
  assert.equal(before.unmapped.length, 2)
  const after = pourUsage(lines, [], ['50'])
  assert.equal(after.unmapped.length, 1, 'the coffee is not a gap, it is not stock')
  assert.equal(after.unmapped[0]?.name, 'PINT GUINNESS')
})

test('ignoring by printed name works too, for a till that renumbers itself', () => {
  assert.equal(pourUsage([sold('50', 'COFFEE')], [], ['coffee']).unmapped.length, 0)
})

test('ignoring a line takes nothing off the cellar rather than taking zero off it', () => {
  const pours: Pour[] = [{ itemCode: '1', itemName: 'PINT TADDY LAGER', stockItemId: 'taddy', baseUnits: ML_PER_PINT }]
  const { used } = pourUsage([sold('1', 'PINT TADDY LAGER', 10), sold('50', 'COFFEE', 99)], pours, ['50'])
  assert.equal(used.get('taddy'), 10 * ML_PER_PINT)
  assert.equal(used.size, 1)
})
