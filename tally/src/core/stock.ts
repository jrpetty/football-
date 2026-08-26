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
  /**
   * What the brewery charges, and what that buys — a firkin at £95 is
   * `{ pence: 9500, baseUnits: 72 * 568 }`.
   *
   * Ex VAT, because that is how an invoice quotes it and how gross profit is
   * worked out. Absent means no cost is known, and every margin figure for
   * this line reads as unknown rather than as free beer.
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
export const CONTAINER_SIZES = [
  { name: 'firkin', servings: 72, hint: '9 gallons' },
  { name: 'kil', servings: 144, hint: '18 gallons' },
  { name: 'keg', servings: 88, hint: '11 gallons' },
  { name: 'pin', servings: 36, hint: '4½ gallons' },
  { name: 'case', servings: 24, hint: 'bottles' },
  { name: 'box', servings: 12, hint: 'packets' },
] as const

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
  if (b.full > 0) parts.push(`${b.full} ${b.containerName}${b.full === 1 ? '' : 's'}`)
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

export function cellarHealth(args: {
  items: readonly StockItem[]
  pours: readonly Pour[]
  /** Most recent first, as listStockCounts returns them. */
  counts: readonly StockCount[]
  deliveries: readonly Delivery[]
  /** Every night's sold lines, whatever order. */
  days: ReadonlyArray<{ date: string; items: readonly SoldLine[] }>
  today: string
  costOfServing: (item: StockItem, baseUnits: number) => number | null
}): CellarHealth {
  const { items, pours, counts, deliveries, days, today, costOfServing } = args

  const soldBetween = (from: string, to?: string): SoldLine[] =>
    days
      .filter((d) => d.date > from && (to === undefined || d.date <= to))
      .flatMap((d) => d.items as SoldLine[])

  const usageBetween = (from: string, to?: string) => pourUsage(soldBetween(from, to), pours).used

  const deliveredBetween = (from: string, to?: string) => {
    const acc = new Map<string, number>()
    for (const d of deliveries.filter((x) => x.date > from && (to === undefined || x.date <= to))) {
      for (const line of d.lines) acc.set(line.stockItemId, (acc.get(line.stockItemId) ?? 0) + line.baseUnits)
    }
    return acc
  }

  const latest = counts[0]
  const previous = counts[1]

  // With no take yet, a week is enough history to say what is moving without
  // averaging a line's whole life into its rate.
  const since = latest?.date ?? addDaysKey(today, -7)
  const sinceDays = Math.max(1, Math.round((Date.parse(today) - Date.parse(since)) / 86_400_000))

  const opening = new Map((latest?.lines ?? []).map((l) => [l.stockItemId, l.baseUnits]))
  const openUsage = usageBetween(since)
  const ledger = buildLedger(items, opening, deliveredBetween(since), openUsage)
  const dead = deadStock(ledger, openUsage, sinceDays, costOfServing)

  let gapPence: number | null = null
  let gapLines: StockVariance[] = []
  if (latest && previous) {
    const closedOpening = new Map(previous.lines.map((l) => [l.stockItemId, l.baseUnits]))
    const closed = buildLedger(
      items,
      closedOpening,
      deliveredBetween(previous.date, latest.date),
      usageBetween(previous.date, latest.date),
    )
    gapLines = compareToCount(closed, new Map(latest.lines.map((l) => [l.stockItemId, l.baseUnits])))
      .filter((v) => v.varianceBaseUnits !== null && v.varianceBaseUnits !== 0)
      .sort((a, b) => (a.varianceBaseUnits ?? 0) - (b.varianceBaseUnits ?? 0))

    if (gapLines.length === 0) {
      // A judged window where every line counted exactly as expected is a
      // reconciled cellar — £0 out, which is an answer, not an unknown.
      gapPence = 0
    } else {
      // Valued line by line at each line's own cost. Lines with no cost cannot
      // be valued and are left out; if none of the discrepancies could be
      // valued, the answer is "unknown" rather than a zero that reads as fine.
      let valuedAny = false
      let total = 0
      for (const line of gapLines) {
        const pence = costOfServing(line.item, line.varianceBaseUnits as number)
        if (pence === null) continue
        valuedAny = true
        total += pence
      }
      gapPence = valuedAny ? total : null
    }
  }

  return { since, sinceDays, ledger, dead, gapPence, gapLines }
}
