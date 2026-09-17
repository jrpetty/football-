// ---------------------------------------------------------------------------
// Stock.
//
// The till roll already records exactly what left the cellar: 120 pints of
// Taddy, eight vodkas, seven 175ml wines. So usage does not need counting — it
// needs converting. What has to be counted by hand is only two things: what
// arrives on delivery day, and what is actually there when someone looks.
//
//     on hand  =  last count  +  delivered  −  poured
//     variance =  what is actually there  −  on hand
//
// That variance is the number the whole thing exists for. It is not the same
// loss as a short drawer or an underpriced pint: this one is beer that left the
// cellar without going through the till at all.
//
// UNITS. Everything liquid is stored in whole millilitres, everything else as a
// count of things. Pints are how a cask is spoken about and shots are worked
// out when they are wanted, but neither is how anything is stored, because a
// pint of one beer and a shot of one spirit have to add up in the same column. The conversions are fixed constants rather than measured:
// a pint is defined here as 568ml, so a cask entered as 72 pints and 72 pints
// of sales cancel exactly. Using the true 568.26ml would leave a slow drift
// that looks like shrinkage and is arithmetic.
// ---------------------------------------------------------------------------

import { addDays as addDaysKey } from './date.ts'

/** The app's pint. Fixed so deliveries and sales cancel exactly. */
export const ML_PER_PINT = 568

/** Half a pint, exactly — two of them are a pint, which matters. */
export const ML_PER_HALF = ML_PER_PINT / 2

/**
 * A shot, in millilitres.
 *
 * 30ml because that is what this pub pours. British pubs are more often 25ml or
 * 35ml, so it is a setting rather than a constant.
 */
export const DEFAULT_ML_PER_SHOT = 30

/** A wine bottle, which is how a cellar counts anything poured by the glass. */
export const ML_PER_BOTTLE = 750

/** Where a glass starts before anybody says otherwise — the common house pour. */
export const DEFAULT_GLASS_ML = 175

export type StockKind = 'liquid' | 'count'

export interface StockItem {
  id: string
  /** "Taddy Lager", "Vodka", "Crisps". */
  name: string
  kind: StockKind
  /**
   * What one serving is, in base units — 568 for a pint, 1 for a millilitre,
   * 1 for a bottle off a shelf. How the line is counted and spoken about.
   * Never a shot: shots are worked out of the millilitres.
   */
  servingBaseUnits: number
  /** "pint", "ml", "bottle", "unit". */
  servingName: string
  /**
   * What one delivery container holds, in base units — and, for a keg that
   * gets weighed rather than guessed at, what it weighs empty and full, so a
   * reading off the scales turns into pints.
   */
  container?: { name: string; baseUnits: number; emptyKg?: number; fullKg?: number }
  /**
   * What the brewery charges, and what that buys — a firkin at £95 is
   * `{ pence: 9500, baseUnits: 72 * 568 }`.
   *
   * Entered exactly as the invoice charges it. Absent means no cost is known,
   * and every margin figure for this line reads as unknown rather than as
   * free beer.
   */
  cost?: { pence: number; baseUnits: number }
  /**
   * Every cost this line has been at, oldest first.
   *
   * Alongside `cost` rather than instead of it: everything that reads a cost
   * keeps working untouched, and the log answers the separate question of when
   * the brewery moved and whether the board followed.
   */
  costHistory?: Array<{ date: string; pence: number; baseUnits: number }>
}

/** One sold line, and what it takes out of the cellar. */
export interface Pour {
  /** The till's PLU code. */
  itemCode: string
  /** As printed, so a pour can be recognised after a code change. */
  itemName: string
  stockItemId: string
  /** Base units one sale removes: 568 for a pint, 284 for a half, 30 for a shot. */
  baseUnits: number
}

export interface Delivery {
  id: string
  /** Trading-day key it arrived on. */
  date: string
  lines: Array<{ stockItemId: string; baseUnits: number; note?: string }>
  note?: string
}

/** A physical count — someone in the cellar with a clipboard. */
export interface StockCount {
  date: string
  lines: Array<{ stockItemId: string; baseUnits: number }>
  note?: string
}

// --- reading what the till poured -------------------------------------------

export interface SoldLine {
  code: string
  name: string
  qtyMilli: number
}

/**
 * Turn a night's sales into base units out of the cellar.
 *
 * Anything with no pour set is reported rather than dropped: an unmapped line
 * is stock leaving the building uncounted, which is precisely the thing this is
 * supposed to notice.
 */
export function pourUsage(
  sold: readonly SoldLine[],
  pours: readonly Pour[],
): { used: Map<string, number>; unmapped: SoldLine[] } {
  const byCode = new Map(pours.map((p) => [p.itemCode.toUpperCase(), p]))
  const byName = new Map(pours.map((p) => [p.itemName.trim().toUpperCase(), p]))

  const used = new Map<string, number>()
  const unmapped: SoldLine[] = []

  for (const line of sold) {
    const pour = byCode.get(line.code.toUpperCase()) ?? byName.get(line.name.trim().toUpperCase())
    if (!pour) {
      unmapped.push(line)
      continue
    }
    // Quantities arrive in thousandths, so multiply before dividing to keep the
    // whole thing in integers.
    const base = Math.round((line.qtyMilli * pour.baseUnits) / 1000)
    used.set(pour.stockItemId, (used.get(pour.stockItemId) ?? 0) + base)
  }

  return { used, unmapped }
}

// --- guessing the pours from the till's own names ----------------------------

/** Spirits, which the till names without a measure because the measure is the shot. */
const SPIRIT_WORDS = [
  'VODKA', 'GIN', 'RUM', 'WHISKY', 'WHISKEY', 'BOURBON', 'BRANDY', 'TEQUILA',
  'SCHNAPPS', 'SAMBUCA', 'AMARETTO', 'PORT', 'SHERRY', 'LIQUEUR', 'ARCHERS',
]

/**
 * The measures a glass of wine is poured at.
 *
 * These are the only sizes that mean "poured from a bigger bottle". Any other
 * size printed on a line is the container the drink arrives in — a 275ml
 * mixer, a 550ml alcohol-free — and those are counted by the bottle.
 */
const WINE_MEASURES = new Set([125, 175, 250])

/** At or below this, a printed measure is a spirit measure, not a bottle. */
const SPIRIT_MEASURE_MAX_ML = 60

/**
 * Sold by the bottle, one at a time.
 *
 * The juices, the mixers and the alcohol-free all come out of a fridge and go
 * back on a shelf, so they are counted the way they are stored: in bottles.
 * BEER only reaches this list after PINT and HALF have had their turn, so what
 * is left of it — a fruit beer, a ginger beer — is the bottled sort.
 */
const BOTTLE_WORDS = [
  'JUICE', 'TONIC', 'LEMONADE', 'COKE', 'PEPSI', 'PRESSE', 'CORDIAL',
  'ELDERFLOWER', 'ALC', 'ALCOHOL', 'BEER',
]

export interface PourGuess {
  itemCode: string
  itemName: string
  /** The cellar line it draws on, as a name — an id is assigned on saving. */
  stockName: string
  baseUnits: number
  kind: StockKind
  servingName: string
  /** How confident the guess is, which decides what needs checking by hand. */
  sure: boolean
}

/**
 * Read a pour off the item's printed name.
 *
 * The till names its lines the way the cellar thinks: PINT TADDY LAGER, HALF
 * OBB, 175ML HOUSE WINE. That is nearly a recipe already, and guessing it turns
 * an afternoon of typing into a list to check. Every guess is shown before it
 * counts for anything, and the unsure ones are flagged.
 */
export function guessPour(code: string, name: string, mlPerShot = DEFAULT_ML_PER_SHOT): PourGuess {
  const clean = name.trim()
  const upper = clean.toUpperCase()

  const base = (stockName: string, baseUnits: number, kind: StockKind, servingName: string, sure: boolean): PourGuess => ({
    itemCode: code,
    itemName: clean,
    stockName,
    baseUnits,
    kind,
    servingName,
    sure,
  })

  const pint = /^PINT\s+(.+)$/.exec(upper)
  if (pint?.[1]) return base(titleise(pint[1]), ML_PER_PINT, 'liquid', 'pint', true)

  const half = /^HALF\s+(.+)$/.exec(upper)
  if (half?.[1]) return base(titleise(half[1]), ML_PER_HALF, 'liquid', 'pint', true)

  // A measure printed in the name. What it means depends entirely on its size,
  // and getting that wrong is the difference between counting a fridge and
  // counting a wine cellar:
  //   175ml on a rose is a glass poured out of a bottle
  //   25ml on a whisky is a measure poured out of a bottle
  //   550ml on an alcohol-free is the bottle itself, sold whole
  // Read off the original rather than the shouted copy, so a line the till
  // deliberately typed in lower case — "550ml alc free" — keeps its name.
  const measured = /^(\d{2,4})\s*ML\s+(.+)$/i.exec(clean)
  if (measured?.[1] && measured[2]) {
    const ml = Number(measured[1])
    const rest = titleise(measured[2])
    // Either way it is poured by the measure, so the line is counted in
    // millilitres and the measure printed on the till is what one sale takes.
    if (WINE_MEASURES.has(ml) || ml <= SPIRIT_MEASURE_MAX_ML) return base(rest, ml, 'liquid', 'ml', true)
    return base(rest, 1, 'count', 'bottle', true)
  }

  // A bottle is counted as a bottle. Nobody counts the fridge in millilitres.
  if (/^BOT(TLE)?\b/.test(upper)) return base(titleise(clean.replace(/^BOT(TLE)?\s*/i, '')), 1, 'count', 'bottle', true)

  // Whole words only. Substring matching makes GINGER BEER a gin and pours it
  // as a 30ml shot for ever, which is worse than not guessing: a wrong pour is
  // silent, where an unguessed one lands on the list to check. That trade also
  // costs the guess on "Raspgin", and that is the right way round.
  //
  // Spirits are checked before the bottled words on purpose: a line reading
  // GIN AND TONIC pours gin, and the tonic in its name must not turn it into
  // something counted off a shelf.
  if (SPIRIT_WORDS.some((w) => new RegExp(`\\b${w}\\b`).test(upper))) {
    return base(titleise(clean), mlPerShot, 'liquid', 'ml', true)
  }

  // The juices, the mixers, the alcohol-free, the bottled beers: all sold one
  // bottle at a time, and counted the same way.
  if (BOTTLE_WORDS.some((w) => new RegExp(`\\b${w}\\b`).test(upper))) {
    return base(titleise(clean), 1, 'count', 'bottle', true)
  }

  // Everything else — crisps, nuts, a dash, open food — is counted, not poured.
  return base(titleise(clean), 1, 'count', 'unit', false)
}

/** "PINT TADDY LAGER" -> "Taddy Lager", leaving deliberate casing alone. */
function titleise(text: string): string {
  const t = text.trim()
  if (/[a-z]/.test(t)) return t
  return t
    .toLowerCase()
    .split(' ')
    .map((w) => (w.length > 2 && !/\d/.test(w) ? w.charAt(0).toUpperCase() + w.slice(1) : w.toUpperCase()))
    .join(' ')
}

// --- the ledger --------------------------------------------------------------

export interface StockLine {
  item: StockItem
  /**
   * Whether the line was on the opening count at all.
   *
   * A line left blank on the sheet was not counted, which is not the same as
   * none — and everything downstream that would otherwise read the blank as
   * a zero (a negative "left", a cellar valued short, a variance against
   * nothing) has to know the difference.
   */
  counted: boolean
  /** Base units at the last physical count; 0 when the line was not counted. */
  countedBaseUnits: number
  deliveredBaseUnits: number
  pouredBaseUnits: number
  /** counted + delivered − poured. Meaningless when `counted` is false. */
  expectedBaseUnits: number
}

export function buildLedger(
  items: readonly StockItem[],
  opening: ReadonlyMap<string, number>,
  delivered: ReadonlyMap<string, number>,
  poured: ReadonlyMap<string, number>,
): StockLine[] {
  return items.map((item) => {
    const counted = opening.has(item.id)
    const countedBaseUnits = opening.get(item.id) ?? 0
    const deliveredBaseUnits = delivered.get(item.id) ?? 0
    const pouredBaseUnits = poured.get(item.id) ?? 0
    return {
      item,
      counted,
      countedBaseUnits,
      deliveredBaseUnits,
      pouredBaseUnits,
      expectedBaseUnits: countedBaseUnits + deliveredBaseUnits - pouredBaseUnits,
    }
  })
}

/**
 * Below this many nights of cover, a line is worth putting on the order.
 *
 * Five rather than two or three: the brewery delivers on set days, so what
 * matters is whether a line survives to the next drop, not whether it survives
 * to tomorrow. It is the number that turns "two firkins left" into "order it".
 */
export const LOW_NIGHTS = 5

export interface StockRunway {
  item: StockItem
  /** What is left now, in base units. */
  leftBaseUnits: number
  /** Going out per night of trade, in base units. */
  perNightBaseUnits: number
  /** Whole nights of trade left at that rate. Zero means it is out. */
  nightsLeft: number
}

/**
 * How long each line lasts at the rate it has been going out.
 *
 * This is the whole payoff of taking the till off the stock: not "what should
 * be down there" — which is only interesting to somebody about to count it —
 * but "order more Taddy, it goes on Thursday".
 *
 * Measured per NIGHT OF TRADE READ, not per day on the calendar, and that is
 * the load-bearing decision. Nights go missing: a receipt photographed with no
 * signal sits unread for a week, and the pub is shut on Mondays besides. Divide
 * by calendar days and three read nights out of thirty make a keg that has a
 * week in it look like it has two months — the one error that would get
 * somebody to trust this and then run dry on a Saturday. Per night read, the
 * rate is the rate however many nights are in.
 *
 * Only lines with both ends known are here. A line that was never counted has
 * no level to run down, and a line nothing has poured has no rate, so neither
 * gets a guess dressed up as a figure.
 */
export function runway(lines: readonly StockLine[], overNights: number): StockRunway[] {
  const nights = Math.max(1, overNights)
  const out: StockRunway[] = []
  for (const line of lines) {
    if (!line.counted || line.pouredBaseUnits <= 0) continue
    const perNightBaseUnits = line.pouredBaseUnits / nights
    const left = Math.max(0, line.expectedBaseUnits)
    out.push({
      item: line.item,
      leftBaseUnits: line.expectedBaseUnits,
      perNightBaseUnits,
      nightsLeft: Math.floor(left / perNightBaseUnits),
    })
  }
  return out.sort((a, b) => a.nightsLeft - b.nightsLeft)
}

/** One row per item the till could not place, with the quantities added up. */
function mergeSold(sold: readonly SoldLine[]): SoldLine[] {
  const acc = new Map<string, SoldLine>()
  for (const line of sold) {
    const key = `${line.code}\u0000${line.name.trim().toUpperCase()}`
    const seen = acc.get(key)
    if (seen) seen.qtyMilli += line.qtyMilli
    else acc.set(key, { ...line })
  }
  return [...acc.values()].sort((a, b) => b.qtyMilli - a.qtyMilli)
}

export interface StockVariance extends StockLine {
  /** What was actually found, when someone has counted since. */
  actualBaseUnits: number | null
  /** actual − expected. Negative is stock that left without a sale. */
  varianceBaseUnits: number | null
}

export function compareToCount(
  lines: readonly StockLine[],
  actual: ReadonlyMap<string, number>,
): StockVariance[] {
  return lines.map((line) => {
    const actualBaseUnits = actual.has(line.item.id) ? (actual.get(line.item.id) ?? 0) : null
    // No verdict without both ends: a line missing from either count has
    // nothing to be measured against, and "short by the lot" is not a finding.
    const judged = line.counted && actualBaseUnits !== null
    return {
      ...line,
      actualBaseUnits,
      varianceBaseUnits: judged ? (actualBaseUnits as number) - line.expectedBaseUnits : null,
    }
  })
}

// --- speaking about it -------------------------------------------------------

/** "pints", "glasses", "halves" — and "ml", which takes no s at all. */
export function pluralServing(servingName: string): string {
  if (servingName === 'ml') return 'ml'
  // 'each' is what older copies of this app called a unit.
  if (servingName === 'each') return 'items'
  if (servingName === 'half') return 'halves'
  if (/(s|x|ch|sh)$/.test(servingName)) return `${servingName}es`
  return `${servingName}s`
}

/** 40896 base units of a pint line -> "72 pints". */
export function formatServings(baseUnits: number, item: StockItem): string {
  if (item.servingBaseUnits <= 0) return String(baseUnits)
  const servings = baseUnits / item.servingBaseUnits
  const rounded = Math.round(servings * 10) / 10
  const n = Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1)
  return `${n}${pluralise(item.servingName, rounded)}`
}

/**
 * Only pluralise a name that is actually a word.
 *
 * "pint" becomes pints; "each" does not become eachs, and "550ml" does not
 * become 550mls. A counted thing reads better as the bare number anyway — the
 * line already says what it is.
 */
function pluralise(servingName: string, quantity: number): string {
  if (servingName === 'ml') return ' ml'
  if (servingName === 'each') return ''
  if (/\d/.test(servingName)) return ` × ${servingName}`
  return ` ${Math.abs(quantity) === 1 ? servingName : pluralServing(servingName)}`
}

/** The same, signed, for a variance where the direction is the point. */
export function formatServingsSigned(baseUnits: number, item: StockItem): string {
  if (baseUnits === 0) return formatServings(0, item)
  const sign = baseUnits < 0 ? '−' : '+'
  return `${sign}${formatServings(Math.abs(baseUnits), item)}`
}

/** Servings typed by a person -> base units. */
export function servingsToBase(servings: number, item: StockItem): number {
  return Math.round(servings * item.servingBaseUnits)
}


// --- containers --------------------------------------------------------------

/**
 * The containers a British cellar actually receives.
 *
 * Cask sizes are the traditional ones and are not going to change: a firkin is
 * nine gallons, a kilderkin eighteen, and in pints that is 72 and 144. Keg beer
 * arrives as 11 gallons (88 pints) or as 50 litres, which is 88 pints as near
 * as the cellar cares. Offered as a list because "how many pints in a kil" is
 * obvious in the trade and looked up by everybody else.
 */
export type PresetKind = 'keg' | 'bottle' | 'pack'

export interface ContainerPreset {
  name: string
  /**
   * What the size means: a keg is so many pints of beer, a bottle so many
   * millilitres, a pack so many things. Held that way rather than in the
   * line's own servings so a firkin is 40,896ml whether the line is counted
   * in pints or in millilitres.
   */
  kind: PresetKind
  size: number
  hint: string
}

export const CONTAINER_SIZES: ContainerPreset[] = [
  { name: 'firkin', kind: 'keg', size: 72, hint: '9 gallons' },
  { name: 'kil', kind: 'keg', size: 144, hint: '18 gallons' },
  { name: 'keg', kind: 'keg', size: 88, hint: '11 gallons' },
  { name: 'pin', kind: 'keg', size: 36, hint: '4½ gallons' },
  { name: 'case', kind: 'pack', size: 24, hint: 'bottles' },
  { name: 'box', kind: 'pack', size: 12, hint: 'packets' },
  { name: '70cl bottle', kind: 'bottle', size: 700, hint: 'spirits' },
  { name: 'litre bottle', kind: 'bottle', size: 1000, hint: 'spirits' },
  { name: 'wine bottle', kind: 'bottle', size: ML_PER_BOTTLE, hint: '75cl' },
]

/** The sizes worth offering a line: kegs and bottles hold liquid, packs hold things. */
export function presetsFor(item: StockItem): ContainerPreset[] {
  return CONTAINER_SIZES.filter((c) => (item.kind === 'liquid' ? c.kind !== 'pack' : c.kind === 'pack'))
}

/** What a preset holds, in this line's base units. */
export function presetBaseUnits(preset: ContainerPreset, item: StockItem): number {
  if (preset.kind === 'keg' && item.kind === 'liquid') return preset.size * ML_PER_PINT
  return preset.size
}

/** How a preset says its size: "72 pints", "700ml", "24". */
export function presetSizeText(preset: ContainerPreset): string {
  return preset.kind === 'keg' ? `${preset.size} pints` : preset.kind === 'bottle' ? `${preset.size}ml` : String(preset.size)
}

// --- how a line is measured --------------------------------------------------
//
// Four ways to count, and no more, because a cellar only has four:
//
//   pints    the tap beers, at the app's fixed 568ml
//   ml       anything poured out of a bottle by the measure — the spirits,
//            the wine — counted in millilitres, which is what can actually be
//            read off a bottle or a set of scales
//   bottles  what is sold whole: the juices, the mixers, the alcohol-free
//   units    everything else on a shelf: the crisps, the nuts
//
// Shots are deliberately not one of them. A shot is not a thing you count, it
// is a thing you pour: the count is millilitres and the shots are worked out
// from it, at whatever the bar's measure is. That way a half-empty bottle is
// 350ml — exact — rather than "eleven and a bit", and changing the house
// measure never quietly rewrites what is in the cellar.

export type Basis = 'pint' | 'ml' | 'bottle' | 'unit'

export interface Serving {
  kind: StockKind
  servingBaseUnits: number
  servingName: string
}

/** What one serving is, for each of the four. No sizes to set: they are sizes. */
export function servingOf(basis: Basis): Serving {
  switch (basis) {
    case 'pint':
      // Fixed at the app's pint so deliveries and sales cancel exactly.
      return { kind: 'liquid', servingBaseUnits: ML_PER_PINT, servingName: 'pint' }
    case 'ml':
      return { kind: 'liquid', servingBaseUnits: 1, servingName: 'ml' }
    case 'bottle':
      return { kind: 'count', servingBaseUnits: 1, servingName: 'bottle' }
    case 'unit':
      return { kind: 'count', servingBaseUnits: 1, servingName: 'unit' }
  }
}

/**
 * Which of the four a line is counted in.
 *
 * Reads a line saved by an older copy of this app too — anything liquid that
 * is not the pint was a shot or a glass or an opened bottle, and all of those
 * are millilitres now.
 */
export function basisOf(item: StockItem): Basis {
  if (item.kind === 'liquid') return item.servingBaseUnits === ML_PER_PINT ? 'pint' : 'ml'
  return item.servingName === 'bottle' ? 'bottle' : 'unit'
}

/**
 * A line as one of the four, whatever it was counted in before.
 *
 * Safe to run over everything on every load because quantities are all held in
 * base units: a spirit that used to be counted in 30ml shots holds exactly the
 * same millilitres afterwards, and its barrel, its cost and every count ever
 * taken of it are untouched. Only the unit it is spoken and typed in changes.
 */
export function normaliseItem(item: StockItem): StockItem {
  const shape = servingOf(basisOf(item))
  if (
    shape.kind === item.kind &&
    shape.servingBaseUnits === item.servingBaseUnits &&
    shape.servingName === item.servingName
  ) {
    return item
  }
  return { ...item, ...shape }
}

export function normaliseItems(items: readonly StockItem[]): StockItem[] {
  return items.map(normaliseItem)
}

/**
 * Whether changing how a line is counted can keep the unit and the cost.
 *
 * Both are held in base units, and base units mean millilitres on a poured
 * line and things on a counted one. Pints to millilitres is safe — a firkin is
 * 40,896 of them either way. Millilitres to bottles is not: 700 would stop
 * meaning 700ml and start meaning 700 bottles.
 */
export function basisKeepsUnit(from: Basis, to: Basis): boolean {
  return servingOf(from).kind === servingOf(to).kind
}

// --- working the shots out of the millilitres --------------------------------

/** A serve poured off a line: its size, and what that size is called. */
export interface Measure {
  /** One serve, in millilitres. */
  ml: number
  /** shot, glass, half, pint, bottle — read off the size itself. */
  name: string
}

/** What a measure of this size goes by behind the bar. */
export function measureName(ml: number): string {
  if (ml === ML_PER_PINT) return 'pint'
  if (ml === ML_PER_HALF) return 'half'
  if (ml <= SPIRIT_MEASURE_MAX_ML) return 'shot'
  if (ml <= 300) return 'glass'
  return 'bottle'
}

/**
 * The serve a line counted in millilitres goes out in.
 *
 * Taken from what the till actually takes off it, and from nothing else:
 * whichever size it pours most often, and the smaller one where two are level.
 * A line the till has never sold has no measure, and gets no conversion —
 * reading a bottle of red back as "395.8 shots" because the house pours 30ml
 * of spirits is a confident answer to a question nobody asked. It gets one the
 * moment the till has a price on it.
 *
 * Null too for the lines already counted in serves: a pint is a pint, and a
 * bottle off a shelf does not need converting.
 */
export function measureOf(item: StockItem, pours: readonly Pour[]): Measure | null {
  if (item.kind !== 'liquid' || item.servingBaseUnits !== 1) return null
  const seen = new Map<number, number>()
  for (const p of pours) {
    if (p.stockItemId !== item.id || p.baseUnits <= 1) continue
    seen.set(p.baseUnits, (seen.get(p.baseUnits) ?? 0) + 1)
  }
  let best: number | null = null
  for (const [ml, times] of seen) {
    const bestTimes = best === null ? 0 : (seen.get(best) ?? 0)
    if (best === null || times > bestTimes || (times === bestTimes && ml < best)) best = ml
  }
  return best !== null && best > 1 ? { ml: best, name: measureName(best) } : null
}

/** "11.7 shots" — millilitres, as the serves they will go out in. */
export function inMeasures(baseUnits: number, measure: Measure): string {
  const n = Math.round((baseUnits / measure.ml) * 10) / 10
  const shown = Number.isInteger(n) ? String(n) : n.toFixed(1)
  return `${shown} ${Math.abs(n) === 1 ? measure.name : pluralServing(measure.name)}`
}

/** The container a line comes in, in base units — null when none is set. */
export function containerBaseUnits(item: StockItem): number | null {
  return item.container && item.container.baseUnits > 0 ? item.container.baseUnits : null
}

export interface ContainerBreakdown {
  /** Whole unopened containers. */
  full: number
  /** What is left in the one on the stillage, in servings. */
  partServings: number
  /** The lot, in servings, which is what everything else works in. */
  totalServings: number
  containerName: string
  servingName: string
}

/**
 * How a cellar actually reads: "two kils and about thirty pints".
 *
 * The whole point of tracking a container size is that nobody counts 174 pints.
 * They count the barrels stacked against the wall and estimate the one that is
 * running. Null when the line has no container set, in which case servings are
 * the only sensible unit.
 */
export function breakdown(baseUnits: number, item: StockItem): ContainerBreakdown | null {
  const size = containerBaseUnits(item)
  if (size === null || item.servingBaseUnits <= 0) return null
  const perContainer = size / item.servingBaseUnits
  if (perContainer <= 1) return null

  const totalServings = baseUnits / item.servingBaseUnits
  const full = Math.floor(totalServings / perContainer)
  const partServings = Math.round((totalServings - full * perContainer) * 10) / 10
  return {
    full,
    partServings,
    totalServings: Math.round(totalServings * 10) / 10,
    containerName: item.container?.name ?? 'container',
    servingName: item.servingName,
  }
}

/** "2 kils + 30 pints", or just the servings when there is no container. */
export function describeStock(baseUnits: number, item: StockItem): string {
  const b = breakdown(baseUnits, item)
  if (!b || (b.full === 0 && b.partServings === 0)) return formatServings(baseUnits, item)

  const parts: string[] = []
  if (b.full > 0) parts.push(`${b.full} ${b.full === 1 ? b.containerName : pluralServing(b.containerName)}`)
  if (b.partServings > 0) parts.push(formatServings(servingsToBase(b.partServings, item), item))
  // A part-used barrel with nothing in it is not worth mentioning; a cellar
  // with nothing in it at all is.
  return parts.length > 0 ? parts.join(' + ') : formatServings(baseUnits, item)
}

/** Full containers plus a part-used remainder, back into base units. */
export function containersToBase(full: number, partServings: number, item: StockItem): number {
  const size = containerBaseUnits(item) ?? 0
  return Math.round(full * size + partServings * item.servingBaseUnits)
}


// --- weighing a keg ----------------------------------------------------------
//
// Nobody can see inside a keg. What they can do is put it on the bathroom
// scales: a full one and an empty one weighed once give the weight of the
// beer, and every reading after that is a share of it. Calibrated by weighing
// rather than by assuming a density, because a kil of stout and a kil of
// lager do not weigh the same and the pub has scales, not a hydrometer.

export interface KegReading {
  /** What the reading amounts to, in base units, held within the keg's size. */
  baseUnits: number
  /** The same in the line's servings, to a tenth. */
  servings: number
  /** How full: 0 empty, 1 full. */
  share: number
  /**
   * A reading lighter than an empty keg or heavier than a full one is a wrong
   * reading or a wrong calibration, and is said rather than silently clamped.
   */
  outside: 'under' | 'over' | null
}

/** What a keg weighs empty and what it weighs full, in kilograms, keg and all. */
export interface KegWeights {
  emptyKg: number
  fullKg: number
}

/** Whether a pair of weights can be used: both real, and full heavier than empty. */
export function scalesUsable(w: Partial<KegWeights> | undefined): w is KegWeights {
  return (
    !!w &&
    w.emptyKg !== undefined &&
    w.fullKg !== undefined &&
    Number.isFinite(w.emptyKg) &&
    Number.isFinite(w.fullKg) &&
    w.emptyKg >= 0 &&
    w.fullKg > w.emptyKg
  )
}

/**
 * A reading off the scales for any keg: what one weighs empty and full, what
 * it holds when full, and the serving it is counted in.
 *
 * The keg's own weight comes off first — a 10 kg firkin reading 70 kg holds
 * 60 kg of beer, not 70 — and what is left is a share of a full one. The share
 * is held between empty and full for the count, and a reading outside that is
 * said, because it is a wrong reading or a wrong calibration.
 */
export function readScales(
  weights: KegWeights,
  holdsBaseUnits: number,
  servingBaseUnits: number,
  grossKg: number,
): KegReading | null {
  if (!scalesUsable(weights) || !(holdsBaseUnits > 0) || !Number.isFinite(grossKg)) return null
  const raw = (grossKg - weights.emptyKg) / (weights.fullKg - weights.emptyKg)
  const share = Math.min(1, Math.max(0, raw))
  const baseUnits = Math.round(share * holdsBaseUnits)
  const servings = servingBaseUnits > 0 ? Math.round((baseUnits / servingBaseUnits) * 10) / 10 : baseUnits
  return { baseUnits, servings, share, outside: raw < 0 ? 'under' : raw > 1 ? 'over' : null }
}

/** Whether a line can be weighed: a container with both weights, full heavier than empty. */
export function weighable(item: StockItem): boolean {
  const c = item.container
  return !!c && c.baseUnits > 0 && scalesUsable(c)
}

/** A reading off the scales, in kilograms of keg-and-all, as the line's own servings. */
export function kegReading(item: StockItem, grossKg: number): KegReading | null {
  const c = item.container
  if (!c || !weighable(item)) return null
  return readScales({ emptyKg: c.emptyKg as number, fullKg: c.fullKg as number }, c.baseUnits, item.servingBaseUnits, grossKg)
}

/**
 * The lines named, with these weights kept against them — or, given null,
 * with their weights taken off. The weights are the container's, so a line
 * with no container is left as it is, unless a size is given for it: then
 * the keg on the scales is what that line comes in, and it says so.
 */
export function withKegWeights(
  items: readonly StockItem[],
  itemIds: readonly string[],
  weights: KegWeights | null,
  sizeForUnsized?: { name: string; baseUnits: number },
): StockItem[] {
  const ids = new Set(itemIds)
  return items.map((i) => {
    if (!ids.has(i.id)) return i
    const container: NonNullable<StockItem['container']> | undefined =
      i.container ?? (sizeForUnsized && sizeForUnsized.baseUnits > 0 ? sizeForUnsized : undefined)
    if (!container) return i
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { emptyKg: _e, fullKg: _f, ...bare } = container
    return { ...i, container: weights ? { ...bare, emptyKg: weights.emptyKg, fullKg: weights.fullKg } : bare }
  })
}

/** The name a keg of this many pints goes by, for a line given its size from the scales. */
export function kegNameFor(pints: number): string {
  return CONTAINER_SIZES.find((c) => c.kind === 'keg' && c.size === pints)?.name ?? 'keg'
}

// --- the count sheet ---------------------------------------------------------
//
// A sheet is a box or two per line: whole containers, and loose servings on
// top. What is typed is kept as text until it is saved, so the arithmetic
// that turns "2 kils and 30 pints" into base units lives here, tested, and
// the two screens that show a sheet — the Cellar and Tonight — share it.

/** The drafts a sheet holds, keyed `<item id>:full` and `<item id>`. */
export type SheetDrafts = Readonly<Record<string, string>>

export interface CountLine {
  stockItemId: string
  baseUnits: number
}

/**
 * The lines a sheet amounts to. A line with both boxes blank is not on the
 * sheet at all — not counted, which is not the same as none — and a box that
 * does not hold a number leaves its line off rather than guessing at it.
 */
export function sheetLines(items: readonly StockItem[], drafts: SheetDrafts): CountLine[] {
  const out: CountLine[] = []
  for (const item of items) {
    const fullText = (drafts[`${item.id}:full`] ?? '').trim()
    const looseText = (drafts[item.id] ?? '').trim()
    if (fullText === '' && looseText === '') continue
    const full = fullText === '' ? 0 : Number(fullText)
    const loose = looseText === '' ? 0 : Number(looseText)
    if (!Number.isFinite(full) || !Number.isFinite(loose) || full < 0 || loose < 0) continue
    out.push({
      stockItemId: item.id,
      baseUnits: item.container ? containersToBase(full, loose, item) : servingsToBase(loose, item),
    })
  }
  return out
}

/**
 * A saved count back into the boxes it was typed in, so a night being
 * corrected shows the cellar as it was counted rather than an empty sheet.
 */
export function draftsFromCount(count: StockCount, items: readonly StockItem[]): Record<string, string> {
  const drafts: Record<string, string> = {}
  for (const line of count.lines) {
    const item = items.find((i) => i.id === line.stockItemId)
    if (!item) continue
    const b = breakdown(line.baseUnits, item)
    if (b) {
      if (b.full > 0) drafts[`${item.id}:full`] = String(b.full)
      drafts[item.id] = String(b.partServings)
    } else {
      const servings = item.servingBaseUnits > 0 ? line.baseUnits / item.servingBaseUnits : line.baseUnits
      drafts[item.id] = String(Math.round(servings * 10) / 10)
    }
  }
  return drafts
}

// --- reading a delivery note --------------------------------------------------

export interface DeliveryProposal {
  /** The line as written on the note. */
  written: string
  quantity: number
  unit: string
  stockItemId?: string
  itemName?: string
  /** What that comes to in base units, once the unit is understood. */
  baseUnits?: number
  /** How the quantity was read: as containers, or as bare servings. */
  countedAs: 'container' | 'serving' | null
  status: 'ready' | 'ambiguous' | 'unmatched' | 'no-container'
  between?: string[]
}

/**
 * Turn a photographed delivery note into a proposal for the cellar.
 *
 * The unit is the hard part. "2 KIL TADDY" is 288 pints and "2 TADDY" on a note
 * from a brewery that only sells kils is also 288 pints — but a bare 2 against a
 * line with no container set could mean two pints, and guessing wrong by a
 * factor of 144 is not a small error. So a bare quantity is only read as
 * containers when the line has a container to read it as, and anything else is
 * handed back for a person to say.
 */
export function proposeDelivery(
  scanned: ReadonlyArray<{ name: string; quantity: number; unit: string }>,
  items: readonly StockItem[],
  match: <T>(written: string, candidates: readonly T[], label: (c: T) => string) =>
    | { kind: 'matched'; value: T; score: number }
    | { kind: 'ambiguous'; between: T[]; score: number }
    | { kind: 'unmatched' },
): DeliveryProposal[] {
  return scanned.map((row) => {
    const found = match(row.name, items, (i) => i.name)
    if (found.kind === 'unmatched') {
      return { written: row.name, quantity: row.quantity, unit: row.unit, countedAs: null, status: 'unmatched' as const }
    }
    if (found.kind === 'ambiguous') {
      return {
        written: row.name,
        quantity: row.quantity,
        unit: row.unit,
        countedAs: null,
        status: 'ambiguous' as const,
        between: found.between.map((i) => i.name),
      }
    }

    const item = found.value
    const container = containerBaseUnits(item)
    const unit = row.unit.trim().toLowerCase()
    // An explicit serving unit on the note overrides the container.
    const saysServings = unit !== '' && (unit === item.servingName || unit === `${item.servingName}s`)

    if (saysServings || container === null) {
      if (container === null && !saysServings && unit !== '') {
        // A unit the cellar has no size for — "case" against a line set up in
        // pints. Refusing beats multiplying by a number nobody has given.
        return {
          written: row.name,
          quantity: row.quantity,
          unit: row.unit,
          stockItemId: item.id,
          itemName: item.name,
          countedAs: null,
          status: 'no-container' as const,
        }
      }
      return {
        written: row.name,
        quantity: row.quantity,
        unit: row.unit,
        stockItemId: item.id,
        itemName: item.name,
        baseUnits: servingsToBase(row.quantity, item),
        countedAs: 'serving' as const,
        status: 'ready' as const,
      }
    }

    return {
      written: row.name,
      quantity: row.quantity,
      unit: row.unit,
      stockItemId: item.id,
      itemName: item.name,
      baseUnits: Math.round(row.quantity * container),
      countedAs: 'container' as const,
      status: 'ready' as const,
    }
  })
}

/** The delivery lines an accepted proposal would book in. */
export function deliveryLinesFrom(proposals: readonly DeliveryProposal[]): Array<{ stockItemId: string; baseUnits: number }> {
  const out: Array<{ stockItemId: string; baseUnits: number }> = []
  for (const p of proposals) {
    if (p.status !== 'ready' || !p.stockItemId || p.baseUnits === undefined) continue
    // A note listing the same beer twice is two drops of the same line.
    const found = out.find((l) => l.stockItemId === p.stockItemId)
    if (found) found.baseUnits += p.baseUnits
    else out.push({ stockItemId: p.stockItemId, baseUnits: p.baseUnits })
  }
  return out
}


// --- what is not selling ------------------------------------------------------

export interface DeadStockLine {
  item: StockItem
  onHandBaseUnits: number
  /** Servings a week, from the till, over the window measured. */
  perWeek: number
  /** How long the stock on hand would last at that rate. Null when nothing sells. */
  weeksOfCover: number | null
  /** Money tied up in it, when the line has a cost. */
  tiedUpPence: number | null
  reason: 'not selling' | 'overstocked' | null
}

/** Selling fewer than this many a week is slow for a pub, whatever the line. */
export const SLOW_PER_WEEK = 2

/** More than this many weeks of stock is money standing still. */
export const OVERSTOCKED_WEEKS = 8

/**
 * What is taking up cellar space without earning it.
 *
 * Two different problems, deliberately named apart. A line selling two a week
 * is a listing decision — it may not be worth stocking at all. A line selling
 * perfectly well but with three months of it downstairs is an ordering
 * decision, and the beer is fine. Lumping them together as "dead stock" would
 * suggest delisting something that just needs a smaller order.
 */
export function deadStock(
  ledger: readonly StockLine[],
  usedBaseUnits: ReadonlyMap<string, number>,
  days: number,
  costOfServing: (item: StockItem, baseUnits: number) => number | null,
): DeadStockLine[] {
  const weeks = Math.max(1, days / 7)

  return ledger
    // Weeks of cover needs something to divide: a line nobody has counted has
    // no on-hand figure to be sitting on.
    .filter((line) => line.counted)
    .map((line) => {
      const used = usedBaseUnits.get(line.item.id) ?? 0
      const servings = line.item.servingBaseUnits > 0 ? used / line.item.servingBaseUnits : 0
      const perWeek = Math.round((servings / weeks) * 10) / 10
      const onHand = Math.max(0, line.expectedBaseUnits)
      const onHandServings = line.item.servingBaseUnits > 0 ? onHand / line.item.servingBaseUnits : 0

      const weeksOfCover = perWeek > 0 ? Math.round((onHandServings / perWeek) * 10) / 10 : null
      const tiedUpPence = costOfServing(line.item, onHand)

      // Nothing on hand is not a problem, whatever it sells.
      const reason: DeadStockLine['reason'] =
        onHandServings <= 0
          ? null
          : perWeek < SLOW_PER_WEEK
            ? 'not selling'
            : weeksOfCover !== null && weeksOfCover > OVERSTOCKED_WEEKS
              ? 'overstocked'
              : null

      return { item: line.item, onHandBaseUnits: onHand, perWeek, weeksOfCover, tiedUpPence, reason }
    })
    .filter((l) => l.reason !== null)
    // Most money standing still first — that is the one worth acting on.
    .sort((a, b) => (b.tiedUpPence ?? 0) - (a.tiedUpPence ?? 0) || (b.weeksOfCover ?? 0) - (a.weeksOfCover ?? 0))
}


// --- the cellar, judged in one place ------------------------------------------

/**
 * Everything the app concludes about the cellar, computed once.
 *
 * This existed first as private arithmetic inside the Cellar screen, which
 * produced a quiet failure: the weekly alerts were built to say "the cellar is
 * £600 light" and could never say it, because the only code that knew the
 * figure was a screen the alerts cannot see. One shared function means the
 * screen and the alerts cannot drift apart — they are reading the same answer.
 *
 * Two windows, and they are different questions. The OPEN window runs from the
 * last stock take to now and answers "what should be down there" — it has no
 * verdict in it, because nothing has been counted at its far end. The CLOSED
 * window runs between the last two takes and is the only one that can be
 * judged, because it has a count at both ends.
 */
export interface CellarHealth {
  /** Where the open window starts. */
  since: string
  sinceDays: number
  /** The open window: what should be on hand now. */
  ledger: StockLine[]
  /**
   * Sales the till rang that no cellar line knows about.
   *
   * These are the reason the running total can be wrong without anything
   * looking wrong: a pint sold against a line with no pour set comes off
   * nothing, so the cellar reads high by exactly that much and says nothing.
   * With nobody counting the cellar to catch it, naming them is the only
   * defence there is.
   */
  unmapped: SoldLine[]
  /** The last night folded in, so the figure can say how current it is. */
  through: string | null
  /** How long each line lasts at the rate it is going out, shortest first. */
  runway: StockRunway[]
  /** Nights in this window that have a receipt read — what the rate is per. */
  readNights: number
  /**
   * Nights whose receipt was read for its totals but carries no item list.
   *
   * Nothing came off the cellar for them, and nothing is wrong with the night
   * — the takings are as good as any other. But the cellar is short by an
   * evening's trade for each one, so it has to say so rather than quietly
   * reading high.
   */
  nightsWithoutItems: number
  /** Lines taking up space without earning it, over the open window. */
  dead: DeadStockLine[]
  /**
   * The closed window's variance, valued at cost. Null until two counts exist
   * or when none of the discrepancies carries a cost; £0 when the window was
   * judged and reconciled exactly.
   */
  gapPence: number | null
  /** The closed window line by line, worst first. Empty until two counts exist. */
  gapLines: StockVariance[]
}

// --- one window between two counts ------------------------------------------
//
// Every judgement the app makes about a cellar is a window: it opens on one
// count, takes deliveries in and pours out, and closes on the next count. The
// weekly picture and a single night's picture are the same arithmetic over
// different pairs, so they share it here and cannot drift apart.

function soldBetween(days: ReadonlyArray<{ date: string; items: readonly SoldLine[] }>, from: string, to?: string): SoldLine[] {
  return days
    .filter((d) => d.date > from && (to === undefined || d.date <= to))
    .flatMap((d) => d.items as SoldLine[])
}

function deliveredBetween(deliveries: readonly Delivery[], from: string, to?: string): Map<string, number> {
  const acc = new Map<string, number>()
  for (const d of deliveries.filter((x) => x.date > from && (to === undefined || x.date <= to))) {
    for (const line of d.lines) acc.set(line.stockItemId, (acc.get(line.stockItemId) ?? 0) + line.baseUnits)
  }
  return acc
}

export interface CellarWindow {
  /** The count the window opens on. */
  since: string
  /** The count it closes on and is judged against. */
  until: string
  /** Every line judged, worst first. A line missing from either count is not judged. */
  lines: StockVariance[]
  /**
   * The gap valued at cost. 0 when every judged line came out exact; null when
   * nothing could be judged, or none of what was out could be valued.
   */
  gapPence: number | null
}

function windowBetween(
  items: readonly StockItem[],
  pours: readonly Pour[],
  deliveries: readonly Delivery[],
  days: ReadonlyArray<{ date: string; items: readonly SoldLine[] }>,
  previous: StockCount,
  latest: StockCount,
  costOfServing: (item: StockItem, baseUnits: number) => number | null,
): CellarWindow {
  const opening = new Map(previous.lines.map((l) => [l.stockItemId, l.baseUnits]))
  const closed = buildLedger(
    items,
    opening,
    deliveredBetween(deliveries, previous.date, latest.date),
    pourUsage(soldBetween(days, previous.date, latest.date), pours).used,
  )
  const lines = compareToCount(closed, new Map(latest.lines.map((l) => [l.stockItemId, l.baseUnits])))
    .filter((v) => v.varianceBaseUnits !== null)
    .sort((a, b) => (a.varianceBaseUnits ?? 0) - (b.varianceBaseUnits ?? 0))

  let gapPence: number | null = null
  if (lines.length > 0) {
    const off = lines.filter((v) => v.varianceBaseUnits !== 0)
    if (off.length === 0) {
      // Judged and exact on every line: £0 out, which is an answer.
      gapPence = 0
    } else {
      // Valued line by line at each line's own cost; lines with no cost cannot
      // be valued and are left out. If none could be, the answer is unknown
      // rather than a zero that reads as fine.
      let valuedAny = false
      let total = 0
      for (const line of off) {
        const pence = costOfServing(line.item, line.varianceBaseUnits as number)
        if (pence === null) continue
        valuedAny = true
        total += pence
      }
      gapPence = valuedAny ? total : null
    }
  }
  return { since: previous.date, until: latest.date, lines, gapPence }
}

export interface NightCellar {
  /** The count taken that night. */
  count: StockCount
  /** How it compares with the count before it — null on the first count ever. */
  window: CellarWindow | null
}

/**
 * The cellar as counted on one night, judged against the count before.
 *
 * Counted nightly, every night closes a window: what the last count said,
 * plus what came in, less what the till poured, against what was actually
 * found. The first count has nothing before it and says so rather than being
 * compared with an empty cellar.
 */
export function nightCellar(args: {
  date: string
  items: readonly StockItem[]
  pours: readonly Pour[]
  counts: readonly StockCount[]
  deliveries: readonly Delivery[]
  days: ReadonlyArray<{ date: string; items: readonly SoldLine[] }>
  costOfServing: (item: StockItem, baseUnits: number) => number | null
}): NightCellar | null {
  const sorted = [...args.counts].sort((a, b) => a.date.localeCompare(b.date))
  const count = sorted.find((c) => c.date === args.date)
  if (!count) return null
  let previous: StockCount | null = null
  for (const c of sorted) if (c.date < args.date) previous = c
  return {
    count,
    window: previous
      ? windowBetween(args.items, args.pours, args.deliveries, args.days, previous, count, args.costOfServing)
      : null,
  }
}

export function cellarHealth(args: {
  items: readonly StockItem[]
  pours: readonly Pour[]
  /** Most recent first, as listStockCounts returns them. */
  counts: readonly StockCount[]
  deliveries: readonly Delivery[]
  /**
   * Every night's sold lines, whatever order.
   *
   * `hasZRead` lets a night with a receipt but no item list be told apart from
   * a night with no receipt at all. Both take nothing off the cellar, but only
   * one of them is something to go and fix.
   */
  days: ReadonlyArray<{ date: string; items: readonly SoldLine[]; hasZRead?: boolean }>
  today: string
  costOfServing: (item: StockItem, baseUnits: number) => number | null
}): CellarHealth {
  const { items, pours, counts, deliveries, days, today, costOfServing } = args

  // Newest first however they arrived, so "latest" means latest.
  const byDate = [...counts].sort((a, b) => b.date.localeCompare(a.date))
  const latest = byDate[0]
  const previous = byDate[1]

  // With no take yet, the window opens where the records do: the day before
  // the first delivery or the first night with an item list, whichever came
  // first, so everything ever booked in and everything ever poured is in it.
  // An earlier version used the last seven days here, which quietly dropped
  // any delivery older than a week from a cellar nobody had counted yet — a
  // firkin booked in a fortnight ago simply stopped existing.
  const firstRecord = (): string | null => {
    let first: string | null = null
    for (const d of days) if (d.items.length > 0 && (first === null || d.date < first)) first = d.date
    for (const d of deliveries) if (first === null || d.date < first) first = d.date
    return first
  }
  const opened = firstRecord()
  const since = latest?.date ?? (opened !== null ? addDaysKey(opened, -1) : addDaysKey(today, -7))
  const sinceDays = Math.max(1, Math.round((Date.parse(today) - Date.parse(since)) / 86_400_000))

  // Before any stock take exists, every line opens at nothing: a cellar that
  // starts with a delivery is empty before it, and an app that could say
  // nothing until the first count would never get to the first count. Once a
  // count has been taken, a line left off it is genuinely uncounted — which is
  // a different thing from none, and is carried through as such.
  const opening = latest
    ? new Map(latest.lines.map((l) => [l.stockItemId, l.baseUnits]))
    : new Map(items.map((item) => [item.id, 0]))
  const openSales = soldBetween(days, since)
  const openPour = pourUsage(openSales, pours)
  const openUsage = openPour.used
  const ledger = buildLedger(items, opening, deliveredBetween(deliveries, since), openUsage)
  const dead = deadStock(ledger, openUsage, sinceDays, costOfServing)

  // The last night with an item list on it, and how many there are. Not simply
  // "today" and "the days since": a cellar run off the till is only as current
  // as the last receipt that was read, and the rate is per night read, not per
  // day on the calendar.
  let through: string | null = null
  let readNights = 0
  let nightsWithoutItems = 0
  for (const d of days) {
    if (d.date <= since) continue
    if (d.items.length === 0) {
      if (d.hasZRead) nightsWithoutItems++
      continue
    }
    readNights++
    if (through === null || d.date > through) through = d.date
  }

  // The most recent closed window — the same one a night's own card shows
  // for that night, through the same function.
  let gapPence: number | null = null
  let gapLines: StockVariance[] = []
  if (latest && previous) {
    const window = windowBetween(items, pours, deliveries, days, previous, latest, costOfServing)
    gapLines = window.lines.filter((v) => v.varianceBaseUnits !== 0)
    gapPence = window.gapPence
  }

  return {
    since,
    sinceDays,
    ledger,
    unmapped: mergeSold(openPour.unmapped),
    through,
    readNights,
    nightsWithoutItems,
    runway: runway(ledger, readNights),
    dead,
    gapPence,
    gapLines,
  }
}
