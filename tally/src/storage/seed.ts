// ---------------------------------------------------------------------------
// The cellar, as it was counted.
//
// A stock take taken by hand and typed up here once, so the app opens with the
// pub's own figures in it rather than with a file to go and find. It is written
// as the sheet was written — kegs, weights off the scales, bottles on shelves —
// and the arithmetic that turns that into millilitres is the app's own, the
// same readScales the count sheet uses when she weighs a keg herself.
//
// It runs once. A marker in the browser says it has, so re-opening the app does
// not keep re-counting the cellar, and clearing everything does not bring it
// back from the dead.
//
// The cellar it lands in is merged, never replaced: the lines here take their
// sizes and weights, everything else is left exactly as it was, and the pours
// that tell the till what to subtract are untouched.
// ---------------------------------------------------------------------------

import { ML_PER_PINT, readScales, type StockCount, type StockItem } from '../core/stock.ts'
import { mergeStockConfig } from './export.ts'
import { loadStockConfig, saveStockConfig, saveStockCount } from './db.ts'

/** Which stock take this is. Changing it makes the app take a new one in. */
const MARKER = 'tally.seeded'
const SEED = 'cellar-2026-09-16'
const DATE = '2026-09-16'

interface Keg {
  name: string
  pints: number
  emptyKg?: number
  fullKg?: number
}

// The three sizes, and what they weigh. The middle keg has not been weighed
// empty yet, so it carries no weights and cannot be read off the scales.
const LARGE: Keg = { name: 'large keg', pints: 176, emptyKg: 22.4, fullKg: 123 }
// Weighed full but not yet empty, so it keeps the one figure there is and
// cannot be read off the scales until the other arrives. Typing the empty
// weight against the line is then all it needs.
const MIDDLE: Keg = { name: 'middle keg', pints: 144, fullKg: 103 }
const SMALL: Keg = { name: 'small keg', pints: 88, emptyKg: 13.4, fullKg: 64.75 }

/** A wine bottle, weighed empty and full, so a part bottle reads off the scales. */
const WINE = { name: 'wine bottle', ml: 750, emptyKg: 0.175, fullKg: 1.125 }

interface Cask {
  id: string
  name: string
  keg: Keg
  /** Whole kegs, which may be a fraction where one was called a tenth. */
  full: number
  /** One more on the scales, in kilograms, keg and all. */
  onScalesKg?: number
}

const CASKS: Cask[] = [
  { id: 'taddy-lager', name: 'Taddy Lager', keg: LARGE, full: 6 },
  { id: 'alpine', name: 'Alpine', keg: MIDDLE, full: 2.1 },
  { id: 'stout', name: 'Stout', keg: SMALL, full: 2, onScalesKg: 38.05 },
  { id: 'cider', name: 'Cider', keg: SMALL, full: 1, onScalesKg: 61.85 },
  { id: 'dark-mild', name: 'Dark Mild', keg: SMALL, full: 0, onScalesKg: 53 },
]

/** Bottles and millilitres: whole bottles, and what is left in an open one. */
const WINES: Array<{ id: string; name: string; bottles: number; looseMl: number }> = [
  { id: 'rose', name: 'Rose', bottles: 25, looseMl: 0 },
  { id: 'red-wine', name: 'Red wine', bottles: 15, looseMl: 625 },
  // The house wine the till already pours by the glass. Seventy-four on the
  // sheet, one off since: 73 whole bottles and the open one.
  { id: 'house-wine', name: 'White wine', bottles: 73, looseMl: 375 },
]

/** Sold whole, counted by the bottle. */
const BOTTLES: Array<[string, string, number]> = [
  ['cherry', 'Cherry', 46],
  ['raspberry', 'Raspberry', 22],
  ['chocolate', 'Chocolate', 39],
  ['apricot', 'Apricot', 38],
  ['pear', 'Pear', 85],
  ['strawberry', 'Strawberry', 6],
  ['nut-brown', 'Nut Brown', 31],
  ['pure-brew-bottled', 'Pure Brew (bottled)', 30],
  ['alc-free', 'alc free', 29],
  ['orange-juice', 'Orange Juice', 53],
  ['apple-juice', 'Apple Juice', 56],
  ['passion', 'Passion', 39],
  ['elderflower', 'Elderflower', 39],
  ['rasp-and-cran', 'Rasp and Cran', 25],
  ['tonic', 'Tonic', 41],
  ['ginger-beer', 'Ginger Beer', 58],
]

/** Packets off a shelf. The crisps come twenty-five to a box. */
const PACKETS: Array<[string, string, number, boolean]> = [
  ['crisps-sweet-chilli', 'Crisps — sweet chilli', 93, true],
  ['crisps-steak', 'Crisps — steak', 66, true],
  ['crisps-prawn-cocktail', 'Crisps — prawn cocktail', 56, true],
  ['crisps-sea-salted', 'Crisps — sea salted', 97, true],
  ['crisps-vinegar', 'Crisps — vinegar', 106, true],
  ['crisps-cheese', 'Crisps — cheese', 107, true],
  ['salted-nuts', 'Salted Nuts', 68, false],
  ['dry-roast', 'Dry Roast', 58, false],
]

function kegContainer(keg: Keg): NonNullable<StockItem['container']> {
  // Each weight is kept on its own. A keg weighed full but not empty cannot be
  // read off the scales — that needs both — but throwing away the one figure
  // somebody has already taken would mean weighing it twice.
  const container: NonNullable<StockItem['container']> = { name: keg.name, baseUnits: keg.pints * ML_PER_PINT }
  if (keg.emptyKg !== undefined) container.emptyKg = keg.emptyKg
  if (keg.fullKg !== undefined) container.fullKg = keg.fullKg
  return container
}

/** What one cask line holds, in millilitres: whole kegs plus the one on the scales. */
export function caskBaseUnits(cask: Cask): number {
  const holds = cask.keg.pints * ML_PER_PINT
  let total = Math.round(cask.full * holds)
  if (cask.onScalesKg !== undefined && cask.keg.emptyKg !== undefined && cask.keg.fullKg !== undefined) {
    const reading = readScales(
      { emptyKg: cask.keg.emptyKg, fullKg: cask.keg.fullKg },
      holds,
      ML_PER_PINT,
      cask.onScalesKg,
    )
    total += reading?.baseUnits ?? 0
  }
  return total
}

/** The lines this stock take needs, and what was found on each. */
export function seedCellar(): { items: StockItem[]; count: StockCount } {
  const items: StockItem[] = []
  const lines: StockCount['lines'] = []

  for (const cask of CASKS) {
    items.push({
      id: cask.id,
      name: cask.name,
      kind: 'liquid',
      servingBaseUnits: ML_PER_PINT,
      servingName: 'pint',
      container: kegContainer(cask.keg),
    })
    lines.push({ stockItemId: cask.id, baseUnits: caskBaseUnits(cask) })
  }

  for (const wine of WINES) {
    items.push({
      id: wine.id,
      name: wine.name,
      kind: 'liquid',
      servingBaseUnits: 1,
      servingName: 'ml',
      container: { name: WINE.name, baseUnits: WINE.ml, emptyKg: WINE.emptyKg, fullKg: WINE.fullKg },
    })
    lines.push({ stockItemId: wine.id, baseUnits: wine.bottles * WINE.ml + wine.looseMl })
  }

  for (const [id, name, bottles] of BOTTLES) {
    items.push({ id, name, kind: 'count', servingBaseUnits: 1, servingName: 'bottle' })
    lines.push({ stockItemId: id, baseUnits: bottles })
  }

  for (const [id, name, packets, boxed] of PACKETS) {
    items.push({
      id,
      name,
      kind: 'count',
      servingBaseUnits: 1,
      servingName: 'unit',
      ...(boxed ? { container: { name: 'box', baseUnits: 25 } } : {}),
    })
    lines.push({ stockItemId: id, baseUnits: packets })
  }

  return { items, count: { date: DATE, lines, note: 'Stock take typed up from the cellar sheet.' } }
}

/**
 * Put the stock take in, once.
 *
 * Silent either way: a browser that will not let the app read its own marker
 * is not a reason to fail to start, and nor is a database that will not open.
 */
export async function seedOnce(): Promise<boolean> {
  try {
    if (localStorage.getItem(MARKER) === SEED) return false
  } catch {
    return false
  }
  try {
    const { items, count } = seedCellar()
    const current = await loadStockConfig()
    await saveStockConfig(mergeStockConfig(current, { items, pours: [], mlPerShot: current.mlPerShot }))
    await saveStockCount(count)
    localStorage.setItem(MARKER, SEED)
    return true
  } catch {
    return false
  }
}
