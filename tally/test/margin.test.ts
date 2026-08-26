import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { cellarValue, costOf, margin, marginReport } from '../src/core/margin.ts'
import { ML_PER_HALF, ML_PER_PINT, type Pour, type StockItem, type StockLine } from '../src/core/stock.ts'

const VAT = 2000

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

test('VAT comes out of the sale before profit is counted', () => {
  // £4.00 inc VAT is £3.33 net. This is the step that flatters a pub's
  // margin by six or seven points when it gets skipped.
  const m = margin(400, 132, VAT)
  assert.equal(m.sellExVatPence, 333)
  assert.equal(m.grossProfitPence, 201)
  assert.equal(m.gpBp, 6036) // 60.36%
})

test('a zero VAT rate leaves the price alone', () => {
  const m = margin(400, 132, 0)
  assert.equal(m.sellExVatPence, 400)
  assert.equal(m.gpBp, 6700)
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
  const r = marginReport(sold, book, pours, [taddy, crisps], VAT)

  const pint = r.lines.find((l) => l.code === '1')!
  assert.equal(pint.margin?.gpBp, 6036)
  assert.equal(pint.periodProfitPence, 201 * 120) // £241.20 on 120 pints

  // Crisps have a price and a pour but no cost, so they are named as uncosted
  // rather than counted as pure profit.
  const packet = r.lines.find((l) => l.code === '9')!
  assert.equal(packet.margin, null)
  assert.equal(packet.missing, 'cost')

  assert.equal(r.profitPence, 24120)
  assert.equal(r.costedCount, 1)
  assert.equal(r.uncostedCount, 1)
})

test('an unpriced line says so is the price that is missing', () => {
  const sold = [{ code: '3', name: 'PINT ALPINE', qtyMilli: 66_000 }]
  const r = marginReport(sold, book, pours, [taddy], VAT)
  assert.equal(r.lines[0]!.missing, 'price')
})

test('a priced line with no pour set names the pour', () => {
  const sold = [{ code: '4', name: 'PINT OBB', qtyMilli: 66_000 }]
  const r = marginReport(sold, [...book, { code: '4', name: 'PINT OBB', pence: 360 }], pours, [taddy], VAT)
  assert.equal(r.lines[0]!.missing, 'pour')
})

test('the blended rate covers only what could be costed', () => {
  const sold = [
    { code: '1', name: 'PINT TADDY LAGER', qtyMilli: 100_000 },
    { code: '2', name: 'HALF TADDY LAGER', qtyMilli: 100_000 },
  ]
  const r = marginReport(sold, book, pours, [taddy], VAT)
  // Not quite the pint's own 60.36%: rounding to whole pence rounds the half's
  // net sale up (166.67 to 167) and its cost down, so a half is a whisker more
  // profitable than half a pint. Real, and worth leaving visible rather than
  // smoothing away — it is the same rounding the till itself does.
  assert.equal(r.blendedGpBp, 6040)
  assert.equal(r.costedCount, 2)
})

test('the worst margin is listed first', () => {
  const thin = { code: '2', name: 'HALF TADDY LAGER', pence: 100 } // sold at cost-ish
  const sold = [
    { code: '1', name: 'PINT TADDY LAGER', qtyMilli: 1000 },
    { code: '2', name: 'HALF TADDY LAGER', qtyMilli: 1000 },
  ]
  const r = marginReport(sold, [book[0]!, thin], pours, [taddy], VAT)
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
