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
// count of things. Pints and shots are how it is spoken about, never how it is
// stored, because a pint of one beer and a shot of one spirit have to add up in
// the same column. The conversions are fixed constants rather than measured:
// a pint is defined here as 568ml, so a cask entered as 72 pints and 72 pints
// of sales cancel exactly. Using the true 568.26ml would leave a slow drift
// that looks like shrinkage and is arithmetic.
// ---------------------------------------------------------------------------

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

export type StockKind = 'liquid' | 'count'

export interface StockItem {
  id: string
  /** "Taddy Lager", "Vodka", "Crisps". */
  name: string
  kind: StockKind
  /**
   * What one serving is, in base units — 568 for a pint, 30 for a shot, 1 for
   * a packet. How the on-hand figure is spoken about.
   */
  servingBaseUnits: number
  /** "pint", "shot", "packet". */
  servingName: string
  /** What one delivery container holds, in base units. */
  container?: { name: string; baseUnits: number }
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

  // "175ML ROSE", "550ml alc free" — the measure is printed in the name.
  const measured = /^(\d{2,4})\s*ML\s+(.+)$/.exec(upper)
  if (measured?.[1] && measured[2]) {
    return base(titleise(measured[2]), Number(measured[1]), 'liquid', `${measured[1]}ml`, true)
  }

  // A bottle whose size is not printed cannot be poured by volume.
  if (/^BOT(TLE)?\b/.test(upper)) return base(titleise(clean.replace(/^BOT(TLE)?\s*/i, '')), 1, 'count', 'bottle', false)

  // Whole words only. Substring matching makes GINGER BEER a gin and pours it
  // as a 30ml shot for ever, which is worse than not guessing: a wrong pour is
  // silent, where an unguessed one lands on the list to check. That trade also
  // costs the guess on "Raspgin", and that is the right way round.
  if (SPIRIT_WORDS.some((w) => new RegExp(`\\b${w}\\b`).test(upper))) {
    return base(titleise(clean), mlPerShot, 'liquid', 'shot', true)
  }

  // Everything else — crisps, nuts, a dash, open food — is counted, not poured.
  return base(titleise(clean), 1, 'count', 'each', false)
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
  /** Base units at the last physical count. */
  countedBaseUnits: number
  deliveredBaseUnits: number
  pouredBaseUnits: number
  /** counted + delivered − poured. */
  expectedBaseUnits: number
}

export function buildLedger(
  items: readonly StockItem[],
  opening: ReadonlyMap<string, number>,
  delivered: ReadonlyMap<string, number>,
  poured: ReadonlyMap<string, number>,
): StockLine[] {
  return items.map((item) => {
    const countedBaseUnits = opening.get(item.id) ?? 0
    const deliveredBaseUnits = delivered.get(item.id) ?? 0
    const pouredBaseUnits = poured.get(item.id) ?? 0
    return {
      item,
      countedBaseUnits,
      deliveredBaseUnits,
      pouredBaseUnits,
      expectedBaseUnits: countedBaseUnits + deliveredBaseUnits - pouredBaseUnits,
    }
  })
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
    return {
      ...line,
      actualBaseUnits,
      varianceBaseUnits: actualBaseUnits === null ? null : actualBaseUnits - line.expectedBaseUnits,
    }
  })
}

// --- speaking about it -------------------------------------------------------

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
  if (servingName === 'each') return ''
  if (/\d/.test(servingName)) return ` × ${servingName}`
  return ` ${servingName}${Math.abs(quantity) === 1 ? '' : 's'}`
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
