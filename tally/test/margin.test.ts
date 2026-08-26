import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { cellarValue, costOf, margin, marginReport } from '../src/core/margin.ts'
import { ML_PER_HALF, ML_PER_PINT, type Pour, type StockItem, type StockLine } from '../src/core/stock.ts'

/** A firkin — 72 pints — at £95 ex VAT, which is about what a cask costs. */
const taddy: StockItem = {
  id: 'taddy',
  name: 'Taddy Lager',
  kind: 'liquid',
  servingBaseUnits: ML_PER_PINT,
  servingName: 'pint',
  cost: { pence: 9500, baseUnits: 72 * ML_PER_PINT },
}

const crisps: StockItem = {
  id: 'crisps',
  name: 'Crisps',
  kind: 'count',
  servingBaseUnits: 1,
  servingName: 'packet',
  // No cost entered.
}

test('margin is the till price less the invoice price, nothing else', () => {
  // The deliberate plain basis: the price on the board against the price on
  // the invoice, with no tax arithmetic anywhere in the app.
  const m = margin(400, 132)
  assert.equal(m.grossProfitPence, 268)
  assert.equal(m.gpBp, 6700)
})

test('a pint sold at cost makes nothing, and below cost makes less', () => {
  assert.equal(margin(400, 400).gpBp, 0)
  assert.equal(margin(400, 500).gpBp, -2500)
  assert.equal(margin(0, 100).gpBp, 0, 'a free pint has no rate to speak of')
})

test('a pint costs its share of the barrel', () => {
  // £95 across 72 pints is £1.3194, so 132p.
  assert.equal(costOf(taddy, ML_PER_PINT), 132)
  // A half costs half of that, near enough to the penny.
  assert.equal(costOf(taddy, ML_PER_HALF), 66)
})

test('a line with no cost entered reports nothing rather than free', () => {
  assert.equal(costOf(crisps, 1), null)
  assert.equal(costOf(undefined, 568), null)
})

test('a nonsense container size cannot divide by zero', () => {
  const broken: StockItem = { ...taddy, cost: { pence: 9500, baseUnits: 0 } }
  assert.equal(costOf(broken, 568), null)
})

// --- the joined report --------------------------------------------------------

const pours: Pour[] = [
  { itemCode: '1', itemName: 'PINT TADDY LAGER', stockItemId: 'taddy', baseUnits: ML_PER_PINT },
  { itemCode: '2', itemName: 'HALF TADDY LAGER', stockItemId: 'taddy', baseUnits: ML_PER_HALF },
  { itemCode: '9', itemName: 'CRISPS', stockItemId: 'crisps', baseUnits: 1 },
]

const book = [
  { code: '1', name: 'PINT TADDY LAGER', pence: 400 },
  { code: '2', name: 'HALF TADDY LAGER', pence: 200 },
  { code: '9', name: 'CRISPS', pence: 170 },
]

test('gross profit across a night joins price, pour and cost', () => {
  const sold = [
    { code: '1', name: 'PINT TADDY LAGER', qtyMilli: 120_000 },
    { code: '9', name: 'CRISPS', qtyMilli: 79_000 },
  ]
  const r = marginReport(sold, book, pours, [taddy, crisps])

  const pint = r.lines.find((l) => l.code === '1')!
  assert.equal(pint.margin?.gpBp, 6700)
  assert.equal(pint.periodProfitPence, 268 * 120) // £321.60 on 120 pints

  // Crisps have a price and a pour but no cost, so they are named as uncosted
  // rather than counted as pure profit.
  const packet = r.lines.find((l) => l.code === '9')!
  assert.equal(packet.margin, null)
  assert.equal(packet.missing, 'cost')

  assert.equal(r.profitPence, 32160)
  assert.equal(r.costedCount, 1)
  assert.equal(r.uncostedCount, 1)
})

test('an unpriced line says so is the price that is missing', () => {
  const sold = [{ code: '3', name: 'PINT ALPINE', qtyMilli: 66_000 }]
  const r = marginReport(sold, book, pours, [taddy])
  assert.equal(r.lines[0]!.missing, 'price')
})

test('a priced line with no pour set names the pour', () => {
  const sold = [{ code: '4', name: 'PINT OBB', qtyMilli: 66_000 }]
  const r = marginReport(sold, [...book, { code: '4', name: 'PINT OBB', pence: 360 }], pours, [taddy])
  assert.equal(r.lines[0]!.missing, 'pour')
})

test('the blended rate covers only what could be costed', () => {
  const sold = [
    { code: '1', name: 'PINT TADDY LAGER', qtyMilli: 100_000 },
    { code: '2', name: 'HALF TADDY LAGER', qtyMilli: 100_000 },
  ]
  const r = marginReport(sold, book, pours, [taddy])
  // A half sells for exactly half a pint and costs exactly half, so on the
  // plain basis the blend lands exactly on the same rate as either line.
  assert.equal(r.blendedGpBp, 6700)
  assert.equal(r.costedCount, 2)
})

test('the worst margin is listed first', () => {
  const thin = { code: '2', name: 'HALF TADDY LAGER', pence: 100 } // sold at cost-ish
  const sold = [
    { code: '1', name: 'PINT TADDY LAGER', qtyMilli: 1000 },
    { code: '2', name: 'HALF TADDY LAGER', qtyMilli: 1000 },
  ]
  const r = marginReport(sold, [book[0]!, thin], pours, [taddy])
  assert.equal(r.lines[0]!.code, '2', 'the line that is not paying its way comes first')
})

// --- the cellar ---------------------------------------------------------------

function line(item: StockItem, expectedBaseUnits: number): StockLine {
  return { item, countedBaseUnits: 0, deliveredBaseUnits: 0, pouredBaseUnits: 0, expectedBaseUnits }
}

test('the cellar is valued at what the beer cost', () => {
  // Half a firkin left is half of £95.
  const v = cellarValue([line(taddy, 36 * ML_PER_PINT)])
  assert.equal(v.totalPence, 4750)
  assert.equal(v.unvaluedCount, 0)
})

test('stock with no cost is counted as unvalued, not as nothing', () => {
  const v = cellarValue([line(taddy, 72 * ML_PER_PINT), line(crisps, 40)])
  assert.equal(v.totalPence, 9500)
  assert.equal(v.unvaluedCount, 1, 'the total is short by the crisps and says so')
})

test('a line that has run negative does not subtract from the valuation', () => {
  // A negative on-hand means the books are wrong, not that the cellar owes money.
  const v = cellarValue([line(taddy, -5 * ML_PER_PINT)])
  assert.equal(v.totalPence, 0)
})
