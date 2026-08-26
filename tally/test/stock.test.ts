import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ML_PER_PINT,
  ML_PER_HALF,
  DEFAULT_ML_PER_SHOT,
  buildLedger,
  compareToCount,
  formatServings,
  formatServingsSigned,
  guessPour,
  basisKeepsUnit,
  basisOf,
  basisTakesAmount,
  servingOf,
  pourUsage,
  servingsToBase,
  type Pour,
  type StockItem,
} from '../src/core/stock.ts'
import { GARDENERS_ARMS } from './fixtures/gardenersArms.ts'

const sold = GARDENERS_ARMS.plus.map((p) => ({ code: p.code, name: p.name, qtyMilli: p.qtyMilli }))

const taddy: StockItem = {
  id: 'taddy',
  name: 'Taddy Lager',
  kind: 'liquid',
  servingBaseUnits: ML_PER_PINT,
  servingName: 'pint',
  container: { name: 'firkin', baseUnits: 72 * ML_PER_PINT },
}
const vodka: StockItem = {
  id: 'vodka',
  name: 'Vodka',
  kind: 'liquid',
  servingBaseUnits: DEFAULT_ML_PER_SHOT,
  servingName: 'shot',
  container: { name: 'bottle', baseUnits: 700 },
}

// --- reading the pours off the till's own names ------------------------------

test('reads a pint from the item name', () => {
  const g = guessPour('P00014', 'PINT TADDY LAGER')
  assert.equal(g.stockName, 'Taddy Lager')
  assert.equal(g.baseUnits, 568)
  assert.equal(g.servingName, 'pint')
  assert.equal(g.sure, true)
})

test('a half is exactly half a pint, so two of them cancel one', () => {
  assert.equal(guessPour('P00021', 'HALF TADDY LAGER').baseUnits, ML_PER_HALF)
  assert.equal(ML_PER_HALF * 2, ML_PER_PINT)
})

test('a half draws on the same cellar line as the pint', () => {
  // Otherwise the pub appears to stock "Taddy Lager" and "Half Taddy Lager"
  // separately, and neither figure is the truth.
  assert.equal(guessPour('P00014', 'PINT TADDY LAGER').stockName, guessPour('P00021', 'HALF TADDY LAGER').stockName)
})

test('reads a measure printed in the name', () => {
  const g = guessPour('P00030', '175ML HOUSE WINE')
  assert.equal(g.stockName, 'House Wine')
  assert.equal(g.baseUnits, 175)
  assert.equal(g.sure, true)
})

test('the three house wine measures all draw on one bottle', () => {
  const names = ['125ML HOUSE WINE', '175ML HOUSE WINE', '250ML HOUSE WINE']
  const guesses = names.map((n) => guessPour('x', n))
  assert.equal(new Set(guesses.map((g) => g.stockName)).size, 1)
  assert.deepEqual(guesses.map((g) => g.baseUnits), [125, 175, 250])
})

test('a spirit pours a shot', () => {
  assert.equal(guessPour('P00041', 'VODKA').baseUnits, 30)
  assert.equal(guessPour('P00040', 'GIN').servingName, 'shot')
  assert.equal(guessPour('P00032', 'BOURBON').baseUnits, 30)
  assert.equal(guessPour('P00034', 'Spiced rum').baseUnits, 30)
  assert.equal(guessPour('P00035', 'PEACH SCHNAPPS').baseUnits, 30)
})

test('ginger beer is not a gin', () => {
  // Substring matching would pour GINGER BEER as a 30ml shot of spirits for
  // ever, and silently. Whole words only.
  const g = guessPour('P00053', 'GINGER BEER')
  assert.equal(g.kind, 'count')
  assert.equal(g.servingName, 'bottle', 'it is a bottle off a shelf, and counted like one')
  assert.notEqual(g.baseUnits, 30)
})

// --- how each kind of drink is counted ----------------------------------------

test('a bottle is counted by the bottle, whatever size is printed on it', () => {
  // The measure printed here is the bottle's own size, not a pour out of
  // something bigger — so counting it in millilitres would be counting the
  // fridge in millilitres.
  const alcFree = guessPour('P00059', '550ml alc free')
  assert.equal(alcFree.kind, 'count')
  assert.equal(alcFree.servingName, 'bottle')
  assert.equal(alcFree.baseUnits, 1)
  assert.equal(alcFree.stockName, 'alc free')
  assert.equal(alcFree.sure, true)

  const mixer = guessPour('x', '275ML CRANBERRY')
  assert.equal(mixer.servingName, 'bottle')
  assert.equal(mixer.baseUnits, 1)
})

test('the juices and the mixers are bottles too', () => {
  for (const name of ['ORANGE JUICE', 'APPLE JUICE', 'TONIC', 'ELDERFLOWER', 'FRUIT BEER']) {
    const g = guessPour('x', name)
    assert.equal(g.kind, 'count', name)
    assert.equal(g.servingName, 'bottle', name)
  }
})

test('a bottle named as one is a bottle, and sure of it', () => {
  const g = guessPour('P00050', 'Bot pure brew')
  assert.equal(g.kind, 'count')
  assert.equal(g.servingName, 'bottle')
  assert.equal(g.sure, true, 'the till said bottle — there is nothing left to check')
})

test('a spirit with the tonic in its name still pours the spirit', () => {
  // GIN AND TONIC must not be counted off a shelf because of the tonic.
  const g = guessPour('x', 'GIN AND TONIC')
  assert.equal(g.kind, 'liquid')
  assert.equal(g.servingName, 'shot')
  assert.equal(g.baseUnits, 30)
})

test('a measure small enough to be a spirit is poured, not counted', () => {
  const g = guessPour('x', '25ML BELLS')
  assert.equal(g.kind, 'liquid')
  assert.equal(g.servingName, 'shot')
  assert.equal(g.stockName, 'Bells')
})

test('the tap beers stay in pints', () => {
  for (const name of ['PINT TADDY LAGER', 'PINT OBB', 'PINT CIDER', 'PINT STOUT']) {
    const g = guessPour('x', name)
    assert.equal(g.kind, 'liquid', name)
    assert.equal(g.servingName, 'pint', name)
    assert.equal(g.baseUnits, ML_PER_PINT, name)
  }
})

test('an unguessable spirit is left unsure rather than guessed wrong', () => {
  // "Raspgin" is raspberry gin, but catching it needs the substring match that
  // breaks ginger beer. A missed guess costs a tap; a wrong one costs the
  // stock figures every week.
  assert.equal(guessPour('P00047', 'Raspgin').sure, false)
})

test('a different house measure changes every spirit at once', () => {
  assert.equal(guessPour('P00041', 'VODKA', 25).baseUnits, 25)
  assert.equal(guessPour('P00041', 'VODKA', 35).baseUnits, 35)
})

test('crisps are counted, not poured — and it says it is unsure', () => {
  const g = guessPour('P00074', 'CRISPS')
  assert.equal(g.kind, 'count')
  assert.equal(g.baseUnits, 1)
  assert.equal(g.sure, false, 'so it lands on the list to check rather than being trusted')
})

test('keeps a name the till deliberately typed in mixed case', () => {
  assert.equal(guessPour('P00034', 'Spiced rum').stockName, 'Spiced rum')
  assert.equal(guessPour('P00050', 'Bot pure brew').stockName, 'pure brew')
})

test('guesses a pour for every line on the real roll', () => {
  const guesses = sold.map((s) => guessPour(s.code, s.name))
  assert.equal(guesses.length, 38)
  const sure = guesses.filter((g) => g.sure).length
  assert.ok(sure >= 24, `only ${sure} of 38 were confident enough to not need checking`)
})

// --- what a night takes out of the cellar ------------------------------------

const pours: Pour[] = [
  { itemCode: 'P00014', itemName: 'PINT TADDY LAGER', stockItemId: 'taddy', baseUnits: ML_PER_PINT },
  { itemCode: 'P00021', itemName: 'HALF TADDY LAGER', stockItemId: 'taddy', baseUnits: ML_PER_HALF },
  { itemCode: 'P00041', itemName: 'VODKA', stockItemId: 'vodka', baseUnits: DEFAULT_ML_PER_SHOT },
]

test('converts a night of sales into what left the cellar', () => {
  const { used } = pourUsage(sold, pours)
  // 120 pints plus 19 halves is 129.5 pints of Taddy.
  assert.equal(used.get('taddy'), 120 * ML_PER_PINT + 19 * ML_PER_HALF)
  assert.equal(used.get('taddy'), Math.round(129.5 * ML_PER_PINT))
  assert.equal(used.get('vodka'), 8 * 30, 'eight shots')
})

test('pints and halves of the same beer add into one figure', () => {
  const { used } = pourUsage(sold, pours)
  assert.equal(formatServings(used.get('taddy') ?? 0, taddy), '129.5 pints')
})

test('names the lines with no pour set rather than dropping them', () => {
  // A sold line nothing maps is stock leaving the building uncounted, which is
  // the exact thing this is meant to catch.
  const { unmapped } = pourUsage(sold, pours)
  assert.equal(unmapped.length, 35)
  assert.ok(unmapped.some((u) => u.name === 'PINT OBB'))
})

test('an empty pour list uses nothing and blames nobody', () => {
  const { used, unmapped } = pourUsage(sold, [])
  assert.equal(used.size, 0)
  assert.equal(unmapped.length, 38)
})

test('matches a pour by name when the code has changed', () => {
  const renamed = [{ code: 'P99999', name: 'PINT TADDY LAGER', qtyMilli: 10000 }]
  const { used, unmapped } = pourUsage(renamed, pours)
  assert.equal(unmapped.length, 0)
  assert.equal(used.get('taddy'), 10 * ML_PER_PINT)
})

// --- the ledger --------------------------------------------------------------

test('works out what should be left', () => {
  const opening = new Map([['taddy', 20 * ML_PER_PINT]])
  const delivered = new Map([['taddy', 144 * ML_PER_PINT]]) // two firkins
  const poured = new Map([['taddy', Math.round(129.5 * ML_PER_PINT)]])
  const [line] = buildLedger([taddy], opening, delivered, poured)
  assert.ok(line)
  assert.equal(formatServings(line.expectedBaseUnits, taddy), '34.5 pints')
})

test('a delivery and the sales that drink it cancel exactly', () => {
  // The reason a pint is a fixed 568ml: 72 in and 72 out must leave nothing.
  const opening = new Map([['taddy', 0]])
  const delivered = new Map([['taddy', 72 * ML_PER_PINT]])
  const poured = new Map([['taddy', 72 * ML_PER_PINT]])
  const [line] = buildLedger([taddy], opening, delivered, poured)
  assert.equal(line?.expectedBaseUnits, 0)
})

test('compares what should be there with what is', () => {
  const lines = buildLedger([taddy], new Map([['taddy', 100 * ML_PER_PINT]]), new Map(), new Map([['taddy', 40 * ML_PER_PINT]]))
  const [v] = compareToCount(lines, new Map([['taddy', 57 * ML_PER_PINT]]))
  assert.equal(v?.expectedBaseUnits, 60 * ML_PER_PINT)
  assert.equal(formatServingsSigned(v?.varianceBaseUnits ?? 0, taddy), '−3 pints')
})

test('an uncounted line has no variance rather than a variance of zero', () => {
  const lines = buildLedger([taddy], new Map(), new Map(), new Map())
  const [v] = compareToCount(lines, new Map())
  assert.equal(v?.actualBaseUnits, null)
  assert.equal(v?.varianceBaseUnits, null, 'not counted is not the same as nothing missing')
})

// --- speaking about it -------------------------------------------------------

test('says pints and shots, not millilitres', () => {
  assert.equal(formatServings(72 * ML_PER_PINT, taddy), '72 pints')
  assert.equal(formatServings(ML_PER_PINT, taddy), '1 pint')
  assert.equal(formatServings(ML_PER_HALF, taddy), '0.5 pints')
  assert.equal(formatServings(700, vodka), '23.3 shots', 'a 70cl bottle at 30ml')
})

test('does not invent plurals for things that are not words', () => {
  const crisps: StockItem = { id: 'c', name: 'Crisps', kind: 'count', servingBaseUnits: 1, servingName: 'each' }
  const alcFree: StockItem = { id: 'a', name: 'Alc Free', kind: 'liquid', servingBaseUnits: 550, servingName: '550ml' }
  // "79 eachs" and "3 550mls" are how a computer talks.
  assert.equal(formatServings(79, crisps), '79')
  assert.equal(formatServings(3 * 550, alcFree), '3 × 550ml')
  assert.equal(formatServings(550, alcFree), '1 × 550ml')
})

test('a serving typed in comes back the same', () => {
  assert.equal(servingsToBase(72, taddy), 72 * ML_PER_PINT)
  assert.equal(formatServings(servingsToBase(23, vodka), vodka), '23 shots')
})

test('a shortfall reads as a shortfall', () => {
  assert.equal(formatServingsSigned(-2 * ML_PER_PINT, taddy), '−2 pints')
  assert.equal(formatServingsSigned(3 * ML_PER_PINT, taddy), '+3 pints')
  assert.equal(formatServingsSigned(0, taddy), '0 pints')
})

// --- setting how a line is measured -------------------------------------------

test('each basis says what one serving is', () => {
  assert.deepEqual(servingOf('pint', 0), { kind: 'liquid', servingBaseUnits: ML_PER_PINT, servingName: 'pint' })
  assert.deepEqual(servingOf('shot', 30), { kind: 'liquid', servingBaseUnits: 30, servingName: 'shot' })
  assert.deepEqual(servingOf('shot', 25), { kind: 'liquid', servingBaseUnits: 25, servingName: 'shot' })
  assert.deepEqual(servingOf('glass', 175), { kind: 'liquid', servingBaseUnits: 175, servingName: '175ml' })
  assert.deepEqual(servingOf('open', 750), { kind: 'liquid', servingBaseUnits: 750, servingName: 'bottle' })
  assert.deepEqual(servingOf('bottle', 0), { kind: 'count', servingBaseUnits: 1, servingName: 'bottle' })
  assert.deepEqual(servingOf('each', 0), { kind: 'count', servingBaseUnits: 1, servingName: 'each' })
})

test('a pint stays 568ml whatever size is handed to it', () => {
  // The pint is what makes deliveries and sales cancel exactly; it is not a
  // number anybody gets to set.
  assert.equal(servingOf('pint', 999).servingBaseUnits, ML_PER_PINT)
  assert.equal(servingOf('bottle', 550).servingBaseUnits, 1)
})

test('a measure is never zero or a fraction of a millilitre', () => {
  assert.equal(servingOf('shot', 0).servingBaseUnits, 1)
  assert.equal(servingOf('shot', -5).servingBaseUnits, 1)
  assert.equal(servingOf('glass', 175.4).servingBaseUnits, 175)
})

test('only the poured measures have a size to set', () => {
  assert.equal(basisTakesAmount('shot'), true)
  assert.equal(basisTakesAmount('glass'), true)
  assert.equal(basisTakesAmount('open'), true)
  assert.equal(basisTakesAmount('pint'), false)
  assert.equal(basisTakesAmount('bottle'), false)
  assert.equal(basisTakesAmount('each'), false)
})

test('a line reports the basis it is on, and survives a round trip', () => {
  for (const [basis, ml] of [['pint', 0], ['shot', 30], ['glass', 175], ['open', 750], ['bottle', 0], ['each', 0]] as const) {
    const item: StockItem = { id: 'x', name: 'X', ...servingOf(basis, ml) }
    assert.equal(basisOf(item), basis, basis)
  }
})

test('the unit and the cost survive a change between two poured measures', () => {
  // Both are held in millilitres, so a firkin is still a firkin.
  assert.equal(basisKeepsUnit('pint', 'shot'), true)
  assert.equal(basisKeepsUnit('glass', 'pint'), true)
  assert.equal(basisKeepsUnit('open', 'glass'), true, 'wine is poured either way')
  assert.equal(basisKeepsUnit('bottle', 'each'), true)
})

test('but not a change between poured and counted', () => {
  // 700 would stop meaning millilitres and start meaning bottles.
  assert.equal(basisKeepsUnit('shot', 'bottle'), false)
  assert.equal(basisKeepsUnit('each', 'pint'), false)
  assert.equal(basisKeepsUnit('open', 'bottle'), false, 'a poured bottle and a counted one are not the same unit')
})

test('a container holds what the measure set against it says it holds', () => {
  // One 70cl bottle: 23 shots at 30ml, 28 at 25ml. The bottle does not change.
  const spirit: StockItem = { id: 'g', name: 'Gin', ...servingOf('shot', 30), container: { name: '70cl bottle', baseUnits: 700 } }
  assert.equal(Math.floor(700 / spirit.servingBaseUnits), 23)
  const at25: StockItem = { ...spirit, ...servingOf('shot', 25) }
  assert.equal(Math.floor(700 / at25.servingBaseUnits), 28)
  assert.equal(at25.container!.baseUnits, 700, 'the bottle is still 70cl')
})

test('what a sale takes is set in the line’s own measure', () => {
  const spirit: StockItem = { id: 'g', name: 'Gin', ...servingOf('shot', 30) }
  assert.equal(servingsToBase(2, spirit), 60, 'a double is two shots')
  assert.equal(servingsToBase(0.5, { id: 'b', name: 'Beer', ...servingOf('pint', 0) }), 284, 'a half')
})
