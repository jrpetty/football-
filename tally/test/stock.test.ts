import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ML_PER_PINT,
  ML_PER_HALF,
  DEFAULT_ML_PER_SHOT,
  buildLedger,
  compareToCount,
  cellarHealth,
  nightCellar,
  draftsFromCount,
  sheetLines,
  weighable,
  kegReading,
  readScales,
  withKegWeights,
  kegNameFor,
  deadStock,
  describeStock,
  formatServings,
  formatServingsSigned,
  guessPour,
  basisKeepsUnit,
  basisOf,
  normaliseItem,
  measureOf,
  measureName,
  inMeasures,
  presetsFor,
  presetBaseUnits,
  servingOf,
  pourUsage,
  servingsToBase,
  type Pour,
  type StockCount,
  type StockItem,
} from '../src/core/stock.ts'
import { costOf } from '../src/core/margin.ts'
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
  servingBaseUnits: 1,
  servingName: 'ml',
  container: { name: '70cl bottle', baseUnits: 700 },
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
  assert.equal(g.servingName, 'ml', 'poured out of a bottle, so counted in millilitres')
  assert.equal(g.sure, true)
})

test('the three house wine measures all draw on one bottle', () => {
  const names = ['125ML HOUSE WINE', '175ML HOUSE WINE', '250ML HOUSE WINE']
  const guesses = names.map((n) => guessPour('x', n))
  assert.equal(new Set(guesses.map((g) => g.stockName)).size, 1)
  assert.deepEqual(guesses.map((g) => g.baseUnits), [125, 175, 250])
  // Three measures out of one bottle, and no special case needed for it: all
  // three are millilitres of the same bottle.
  assert.deepEqual(new Set(guesses.map((g) => g.servingName)), new Set(['ml']))
})

test('a spirit pours a shot, off a line counted in millilitres', () => {
  assert.equal(guessPour('P00041', 'VODKA').baseUnits, 30)
  assert.equal(guessPour('P00040', 'GIN').servingName, 'ml')
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
  assert.equal(g.servingName, 'ml')
  assert.equal(g.baseUnits, 30)
})

test('a measure small enough to be a spirit is poured, not counted', () => {
  const g = guessPour('x', '25ML BELLS')
  assert.equal(g.kind, 'liquid')
  assert.equal(g.servingName, 'ml')
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

test('says what each line is counted in', () => {
  assert.equal(formatServings(72 * ML_PER_PINT, taddy), '72 pints')
  assert.equal(formatServings(ML_PER_PINT, taddy), '1 pint')
  assert.equal(formatServings(ML_PER_HALF, taddy), '0.5 pints')
  // A spirit is counted in millilitres, exactly, and never in "23.3 shots".
  assert.equal(formatServings(700, vodka), '700 ml')
  assert.equal(formatServings(1, vodka), '1 ml')
})

test('does not invent plurals for things that are not words', () => {
  const crisps: StockItem = { id: 'c', name: 'Crisps', ...servingOf('unit') }
  // "350 mls" and "79 eachs" are how a computer talks.
  assert.equal(formatServings(350, vodka), '350 ml')
  assert.equal(formatServings(79, crisps), '79 units')
  assert.equal(formatServings(1, crisps), '1 unit')
  // Saved by an older copy, and still readable until it is normalised.
  const old: StockItem = { id: 'a', name: 'Alc Free', kind: 'liquid', servingBaseUnits: 550, servingName: '550ml' }
  assert.equal(formatServings(3 * 550, old), '3 × 550ml')
})

test('a serving typed in comes back the same', () => {
  assert.equal(servingsToBase(72, taddy), 72 * ML_PER_PINT)
  assert.equal(formatServings(servingsToBase(350, vodka), vodka), '350 ml')
})

test('a shortfall reads as a shortfall', () => {
  assert.equal(formatServingsSigned(-2 * ML_PER_PINT, taddy), '−2 pints')
  assert.equal(formatServingsSigned(3 * ML_PER_PINT, taddy), '+3 pints')
  assert.equal(formatServingsSigned(0, taddy), '0 pints')
})

// --- setting how a line is counted --------------------------------------------

test('each of the four ways says what one serving is', () => {
  assert.deepEqual(servingOf('pint'), { kind: 'liquid', servingBaseUnits: ML_PER_PINT, servingName: 'pint' })
  assert.deepEqual(servingOf('ml'), { kind: 'liquid', servingBaseUnits: 1, servingName: 'ml' })
  assert.deepEqual(servingOf('bottle'), { kind: 'count', servingBaseUnits: 1, servingName: 'bottle' })
  assert.deepEqual(servingOf('unit'), { kind: 'count', servingBaseUnits: 1, servingName: 'unit' })
})

test('a line reports which of the four it is on, and survives a round trip', () => {
  for (const basis of ['pint', 'ml', 'bottle', 'unit'] as const) {
    const item: StockItem = { id: 'x', name: 'X', ...servingOf(basis) }
    assert.equal(basisOf(item), basis, basis)
  }
})

test('a pint is 568ml and nobody gets to set it', () => {
  assert.equal(servingOf('pint').servingBaseUnits, ML_PER_PINT)
})

test('a line saved in shots is millilitres now, holding exactly the same', () => {
  const legacy: StockItem = {
    id: 'v', name: 'Vodka', kind: 'liquid', servingBaseUnits: 30, servingName: 'shot',
    container: { name: '70cl bottle', baseUnits: 700 },
    cost: { pence: 1400, baseUnits: 700 },
  }
  const now = normaliseItem(legacy)
  assert.equal(now.servingBaseUnits, 1)
  assert.equal(now.servingName, 'ml')
  // The two that say how much there is are untouched, which is the whole point:
  // every count, delivery and cost ever taken is held in base units.
  assert.deepEqual(now.container, legacy.container, 'still a 700ml bottle')
  assert.deepEqual(now.cost, legacy.cost, 'still £14 for 700ml')
  assert.equal(formatServings(700, now), '700 ml')
})

test('and so is one saved in glasses, or in bottles it poured from', () => {
  const glass: StockItem = { id: 'r', name: 'Rose', kind: 'liquid', servingBaseUnits: 175, servingName: '175ml' }
  const poured: StockItem = { id: 'w', name: 'White', kind: 'liquid', servingBaseUnits: 750, servingName: 'bottle' }
  assert.equal(basisOf(normaliseItem(glass)), 'ml')
  assert.equal(basisOf(normaliseItem(poured)), 'ml')
  assert.equal(normaliseItem(glass).servingBaseUnits, 1)
})

test('a line saved as each is a unit, and one already right is left alone', () => {
  const each: StockItem = { id: 'c', name: 'Crisps', kind: 'count', servingBaseUnits: 1, servingName: 'each' }
  assert.equal(normaliseItem(each).servingName, 'unit')
  assert.equal(normaliseItem(taddy), taddy, 'the same object, not a copy')
})

test('the unit and the cost survive a change between the two poured ways', () => {
  // Both are held in millilitres, so a firkin is still a firkin.
  assert.equal(basisKeepsUnit('pint', 'ml'), true)
  assert.equal(basisKeepsUnit('ml', 'pint'), true)
  assert.equal(basisKeepsUnit('bottle', 'unit'), true)
})

test('but not a change between poured and counted', () => {
  // 700 would stop meaning millilitres and start meaning bottles.
  assert.equal(basisKeepsUnit('ml', 'bottle'), false)
  assert.equal(basisKeepsUnit('unit', 'pint'), false)
})

test('a barrel is the same barrel however the line is counted', () => {
  const inPints: StockItem = { id: 'b', name: 'Beer', ...servingOf('pint'), container: { name: 'firkin', baseUnits: 72 * ML_PER_PINT } }
  const inMl: StockItem = { ...inPints, ...servingOf('ml') }
  assert.equal(inMl.container!.baseUnits, 72 * ML_PER_PINT)
  assert.equal(formatServings(inPints.container!.baseUnits, inPints), '72 pints')
  assert.equal(formatServings(inMl.container!.baseUnits, inMl), '40896 ml')
})

// --- the shots, worked out of the millilitres ---------------------------------

test('a count in millilitres reads back as the shots it pours', () => {
  const m = measureOf(vodka, pours)
  assert.deepEqual(m, { ml: 30, name: 'shot' })
  assert.equal(inMeasures(350, m!), '11.7 shots')
  assert.equal(inMeasures(30, m!), '1 shot')
  assert.equal(inMeasures(0, m!), '0 shots')
})

test('the measure comes off what the till actually pours, not off a guess', () => {
  const rose: StockItem = { id: 'rose', name: 'Rose', ...servingOf('ml') }
  const wine: Pour[] = [
    { itemCode: 'a', itemName: '125ML ROSE', stockItemId: 'rose', baseUnits: 125 },
    { itemCode: 'b', itemName: '175ML ROSE', stockItemId: 'rose', baseUnits: 175 },
    { itemCode: 'c', itemName: '175ML ROSE LARGE', stockItemId: 'rose', baseUnits: 175 },
  ]
  assert.deepEqual(measureOf(rose, wine), { ml: 175, name: 'glass' }, 'the size it pours most often')
  assert.equal(inMeasures(750, { ml: 175, name: 'glass' }), '4.3 glasses', 'a bottle is four glasses and a drop')
})

test('a line the till has never sold stays in millilitres', () => {
  // Reading a bottle of red back as shots, because the house pours 30ml of
  // spirits, is a confident answer to a question nobody asked.
  const red: StockItem = { id: 'red', name: 'Red wine', ...servingOf('ml') }
  assert.equal(measureOf(red, []), null)
  assert.equal(measureOf(red, [{ itemCode: 'x', itemName: 'CORKAGE', stockItemId: 'other', baseUnits: 175 }]), null)
})

test('nothing already counted in serves needs converting', () => {
  assert.equal(measureOf(taddy, pours), null, 'a pint is a pint')
  const crisps: StockItem = { id: 'c', name: 'Crisps', ...servingOf('unit') }
  assert.equal(measureOf(crisps, pours), null)
})

test('a measure is named by its size, the way the bar names it', () => {
  assert.equal(measureName(25), 'shot')
  assert.equal(measureName(50), 'shot', 'a double')
  assert.equal(measureName(175), 'glass')
  assert.equal(measureName(ML_PER_HALF), 'half')
  assert.equal(measureName(ML_PER_PINT), 'pint')
  assert.equal(measureName(750), 'bottle')
})

// --- the sizes a delivery comes in --------------------------------------------

test('a preset holds what it holds, whichever way the line is counted', () => {
  const inPints: StockItem = { id: 'b', name: 'Beer', ...servingOf('pint') }
  const inMl: StockItem = { id: 'g', name: 'Gin', ...servingOf('ml') }
  const firkin = presetsFor(inPints).find((c) => c.name === 'firkin')!
  assert.equal(presetBaseUnits(firkin, inPints), 72 * ML_PER_PINT)
  assert.equal(presetBaseUnits(firkin, inMl), 72 * ML_PER_PINT, 'a firkin is 40,896ml either way')
  const bottle = presetsFor(inMl).find((c) => c.name === '70cl bottle')!
  assert.equal(presetBaseUnits(bottle, inMl), 700)
})

test('only the sizes that suit a line are offered', () => {
  const beer: StockItem = { id: 'b', name: 'Beer', ...servingOf('pint') }
  const juice: StockItem = { id: 'j', name: 'Juice', ...servingOf('bottle') }
  assert.ok(presetsFor(beer).some((c) => c.name === 'firkin'))
  assert.ok(!presetsFor(beer).some((c) => c.name === 'case'), 'beer does not come in cases of 24 pints')
  assert.deepEqual(presetsFor(juice).map((c) => c.name), ['case', 'box'])
  assert.equal(presetBaseUnits(presetsFor(juice)[0]!, juice), 24)
})

// --- a line nobody counted ----------------------------------------------------

const bag: StockItem = { id: 'crisps', name: 'Crisps', kind: 'count', servingBaseUnits: 1, servingName: 'each' }

test('a line left off the count is marked as not counted, not as counted at nothing', () => {
  const ledger = buildLedger([taddy, bag], new Map([['taddy', 10 * ML_PER_PINT]]), new Map(), new Map([['crisps', 5]]))
  const t = ledger.find((l) => l.item.id === 'taddy')!
  const c = ledger.find((l) => l.item.id === 'crisps')!
  assert.equal(t.counted, true)
  assert.equal(c.counted, false, 'blank on the sheet is not the same as none')
  // The arithmetic still runs, but nothing downstream may treat it as a fact.
  assert.equal(c.expectedBaseUnits, -5)
})

test('there is no verdict on a line missing from either count', () => {
  // Crisps were not on the opening count. Whatever was found at the closing
  // count, "short by the lot" would be a finding about the blank, not the crisps.
  const ledger = buildLedger([taddy, bag], new Map([['taddy', 10 * ML_PER_PINT]]), new Map(), new Map([['crisps', 5]]))
  const judged = compareToCount(ledger, new Map([['taddy', 8 * ML_PER_PINT], ['crisps', 40]]))
  assert.equal(judged.find((v) => v.item.id === 'crisps')!.varianceBaseUnits, null)
  assert.equal(judged.find((v) => v.item.id === 'taddy')!.varianceBaseUnits, -2 * ML_PER_PINT)
})

test('dead stock cannot be judged on a line with no count behind it', () => {
  const ledger = buildLedger([taddy, bag], new Map([['taddy', 200 * ML_PER_PINT]]), new Map(), new Map())
  const dead = deadStock(ledger, new Map(), 14, () => null)
  assert.ok(dead.some((d) => d.item.id === 'taddy'), 'a counted line with nothing selling is dead stock')
  assert.ok(!dead.some((d) => d.item.id === 'crisps'), 'an uncounted one has no on-hand figure to be sitting on')
})

// --- weighing a keg -------------------------------------------------------------

const kegged: StockItem = {
  ...taddy,
  // A firkin of 72 pints: 10 kg of steel, 51 kg with the beer in.
  container: { name: 'firkin', baseUnits: 72 * ML_PER_PINT, emptyKg: 10, fullKg: 51 },
}

test('a reading off the scales is the beer, not the steel', () => {
  // 30.5 kg on the scales less the 10 kg keg is 20.5 kg of beer, half the
  // 41 kg a full one holds — so 36 of the 72 pints.
  const r = kegReading(kegged, 30.5)!
  assert.equal(r.servings, 36)
  assert.equal(r.baseUnits, 36 * ML_PER_PINT)
  assert.equal(r.share, 0.5)
  assert.equal(r.outside, null)
})

test('a full keg reads full and an empty one reads empty', () => {
  assert.equal(kegReading(kegged, 51)!.servings, 72)
  assert.equal(kegReading(kegged, 10)!.servings, 0)
})

test('a reading past either end is held at the end and flagged', () => {
  // Lighter than an empty keg: the scales, or the calibration, are wrong.
  const light = kegReading(kegged, 8)!
  assert.equal(light.servings, 0)
  assert.equal(light.outside, 'under')
  const heavy = kegReading(kegged, 60)!
  assert.equal(heavy.servings, 72)
  assert.equal(heavy.outside, 'over')
})

test('a line cannot be weighed until both weights are set, the right way round', () => {
  assert.equal(weighable(taddy), false, 'no weights at all')
  assert.equal(weighable({ ...taddy, container: { name: 'firkin', baseUnits: 1, emptyKg: 10 } }), false, 'only one')
  assert.equal(weighable({ ...taddy, container: { name: 'firkin', baseUnits: 1, emptyKg: 51, fullKg: 10 } }), false, 'backwards')
  assert.equal(weighable(kegged), true)
  assert.equal(kegReading(taddy, 30), null)
})

test('the keg’s own weight comes off before a reading is pints', () => {
  // The example given for it: a full keg at 110 kg, an empty one at 10 kg, a
  // hundred pints of beer between them. A keg reading 70 kg is 60 pints, not
  // 70 — the 10 kg of keg is still on the scales.
  const r = readScales({ emptyKg: 10, fullKg: 110 }, 100 * ML_PER_PINT, ML_PER_PINT, 70)!
  assert.equal(r.servings, 60)
  assert.equal(r.share, 0.6)
  assert.equal(r.outside, null)
})

test('a spirit bottle weighs the same way, in shots', () => {
  // A 70cl bottle at 0.5 kg empty and 1.2 kg full, reading 0.85: half a
  // bottle, 350ml, 11.7 shots of 30.
  const r = readScales({ emptyKg: 0.5, fullKg: 1.2 }, 700, 30, 0.85)!
  assert.equal(r.baseUnits, 350)
  assert.equal(r.servings, 11.7)
})

test('nonsense weights give no reading rather than a wrong one', () => {
  assert.equal(readScales({ emptyKg: 51, fullKg: 10 }, 100, 1, 30), null, 'full lighter than empty')
  assert.equal(readScales({ emptyKg: 10, fullKg: 10 }, 100, 1, 30), null, 'no beer between them')
  assert.equal(readScales({ emptyKg: 10, fullKg: 51 }, 0, 1, 30), null, 'holds nothing')
  assert.equal(readScales({ emptyKg: 10, fullKg: 51 }, 100, 1, NaN), null, 'no reading')
})

test('weights are kept on the lines named, and only those with a container', () => {
  const stout: StockItem = { ...taddy, id: 'stout', name: 'Stout' }
  const loose: StockItem = { id: 'loose', name: 'Loose', kind: 'liquid', servingBaseUnits: ML_PER_PINT, servingName: 'pint' }
  const next = withKegWeights([taddy, stout, vodka, loose], ['taddy', 'stout', 'loose'], { emptyKg: 10, fullKg: 51 })
  assert.deepEqual(next[0].container, { name: 'firkin', baseUnits: 72 * ML_PER_PINT, emptyKg: 10, fullKg: 51 })
  assert.deepEqual(next[1].container, { name: 'firkin', baseUnits: 72 * ML_PER_PINT, emptyKg: 10, fullKg: 51 })
  assert.equal(next[2], vodka, 'a line not named is the same object')
  assert.equal(next[3], loose, 'a line with no container cannot carry weights')
  assert.equal(weighable(next[0]), true)

  const off = withKegWeights(next, ['taddy'], null)
  assert.deepEqual(off[0].container, taddy.container)
  assert.equal(off[1], next[1])
})

test('a line with no size yet can be given the keg on the scales along with its weights', () => {
  const stout: StockItem = { id: 'stout', name: 'Stout', kind: 'liquid', servingBaseUnits: ML_PER_PINT, servingName: 'pint' }
  const firkin = { name: 'firkin', baseUnits: 72 * ML_PER_PINT }
  const next = withKegWeights([stout, taddy], ['stout', 'taddy'], { emptyKg: 10, fullKg: 51 }, firkin)
  assert.deepEqual(next[0].container, { name: 'firkin', baseUnits: 72 * ML_PER_PINT, emptyKg: 10, fullKg: 51 })
  assert.equal(next[1].container?.name, 'firkin')
  assert.equal(next[1].container?.baseUnits, taddy.container?.baseUnits, 'a line with its own size keeps it')
  assert.equal(withKegWeights([stout], ['stout'], { emptyKg: 10, fullKg: 51 }, { name: 'keg', baseUnits: 0 })[0], stout, 'a size of nothing is no size')
})

test('a keg is named by what it holds, and an odd size is just a keg', () => {
  assert.equal(kegNameFor(72), 'firkin')
  assert.equal(kegNameFor(144), 'kil')
  assert.equal(kegNameFor(88), 'keg')
  assert.equal(kegNameFor(36), 'pin')
  assert.equal(kegNameFor(100), 'keg')
  assert.equal(kegNameFor(24), 'keg', 'a case of 24 is not a keg')
})

// --- the count sheet -----------------------------------------------------------

test('a sheet turns boxes into lines, and a blank line is not on the sheet', () => {
  const lines = sheetLines([kegged, bag], { 'taddy:full': '2', taddy: '30', crisps: '' })
  assert.equal(lines.length, 1, 'crisps left blank is not counted, not zero')
  assert.equal(lines[0]!.stockItemId, 'taddy')
  assert.equal(lines[0]!.baseUnits, 2 * 72 * ML_PER_PINT + 30 * ML_PER_PINT)
})

test('a sheet reads loose servings alone, and refuses nonsense', () => {
  assert.equal(sheetLines([kegged], { taddy: '12' })[0]!.baseUnits, 12 * ML_PER_PINT)
  assert.equal(sheetLines([kegged], { taddy: 'a few' }).length, 0)
  assert.equal(sheetLines([kegged], { 'taddy:full': '-1' }).length, 0)
})

test('a saved count comes back into the boxes it was typed in', () => {
  const count: StockCount = { date: '2026-08-23', lines: [{ stockItemId: 'taddy', baseUnits: 2 * 72 * ML_PER_PINT + 30 * ML_PER_PINT }] }
  const drafts = draftsFromCount(count, [kegged])
  assert.equal(drafts['taddy:full'], '2')
  assert.equal(drafts['taddy'], '30')
  // And the round trip lands on the same figure.
  assert.equal(sheetLines([kegged], drafts)[0]!.baseUnits, count.lines[0]!.baseUnits)
})

// --- a night's own count -------------------------------------------------------

test('a night with a count is judged against the count before it', () => {
  const counts: StockCount[] = [
    { date: '2026-08-23', lines: [{ stockItemId: 'taddy', baseUnits: 20 * ML_PER_PINT }] },
    { date: '2026-08-24', lines: [{ stockItemId: 'taddy', baseUnits: 12 * ML_PER_PINT }] },
  ]
  // Five pints went through the till on the 24th; eight are gone.
  const night = nightCellar({
    date: '2026-08-24',
    items: [kegged],
    pours: [{ itemCode: 'P00014', itemName: 'PINT TADDY LAGER', stockItemId: 'taddy', baseUnits: ML_PER_PINT }],
    counts,
    deliveries: [],
    days: [{ date: '2026-08-24', items: [{ code: 'P00014', name: 'PINT TADDY LAGER', qtyMilli: 5000 }] }],
    costOfServing: () => null,
  })!
  assert.equal(night.window!.since, '2026-08-23')
  const t = night.window!.lines[0]!
  assert.equal(t.expectedBaseUnits, 15 * ML_PER_PINT)
  assert.equal(t.actualBaseUnits, 12 * ML_PER_PINT)
  assert.equal(t.varianceBaseUnits, -3 * ML_PER_PINT)
})

test('the first count ever has nothing before it, and a night with no count has no card', () => {
  const counts: StockCount[] = [{ date: '2026-08-23', lines: [{ stockItemId: 'taddy', baseUnits: 20 * ML_PER_PINT }] }]
  const args = { items: [kegged], pours: [], counts, deliveries: [], days: [], costOfServing: () => null }
  assert.equal(nightCellar({ ...args, date: '2026-08-23' })!.window, null)
  assert.equal(nightCellar({ ...args, date: '2026-08-22' }), null)
})

test('the night’s gap is the same figure the cellar screen shows for its last take', () => {
  const counts: StockCount[] = [
    { date: '2026-08-23', lines: [{ stockItemId: 'taddy', baseUnits: 20 * ML_PER_PINT }] },
    { date: '2026-08-24', lines: [{ stockItemId: 'taddy', baseUnits: 12 * ML_PER_PINT }] },
  ]
  const costed: StockItem = { ...kegged, cost: { pence: 9500, baseUnits: 72 * ML_PER_PINT } }
  const shared = { items: [costed], pours: [], deliveries: [], days: [], costOfServing: costOf }
  const night = nightCellar({ ...shared, date: '2026-08-24', counts })!
  const health = cellarHealth({ ...shared, counts, today: '2026-08-26' })
  assert.equal(night.window!.gapPence, health.gapPence)
  assert.equal(night.window!.gapPence, Math.round((-8 * 9500) / 72), 'eight pints at the firkin price')
})

test('a cellar counted in boxes reads boxes, not boxs', () => {
  const crisps: StockItem = {
    id: 'c', name: 'Crisps', kind: 'count', servingBaseUnits: 1, servingName: 'unit',
    container: { name: 'box', baseUnits: 25 },
  }
  assert.equal(describeStock(93, crisps), '3 boxes + 18 units')
  assert.equal(describeStock(26, crisps), '1 box + 1 unit')
})
