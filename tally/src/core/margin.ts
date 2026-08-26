// ---------------------------------------------------------------------------
// Gross profit.
//
// The reconciliation says whether the money is there. The price check says
// whether the till charged what the board says. Neither of them answers the
// question a landlady actually lies awake on, which is whether the pint is
// worth pouring at all.
//
//     GP% = (what it sells for − what it cost) ÷ what it sells for
//
// Deliberately the plain version, with no tax arithmetic anywhere in it. This
// pub asked for the figures the way the bar talks about them — the price on
// the board against the price on the invoice — and the tax treatment of either
// is the accountant's page, not this app's. What matters here is only that the
// same basis is used everywhere, so a margin on Trade, a margin in an alert
// and a margin in the cellar can never disagree about what "margin" means.
// ---------------------------------------------------------------------------

import type { PriceBookEntry } from './priceBook.ts'
import { normalise } from './match.ts'
import type { Pour, SoldLine, StockItem, StockLine } from './stock.ts'

/** What a serving costs, off the invoice price — null when none is entered. */
export function costOf(item: StockItem | undefined, baseUnits: number): number | null {
  if (!item?.cost || item.cost.baseUnits <= 0) return null
  return Math.round((item.cost.pence * baseUnits) / item.cost.baseUnits)
}

export interface Margin {
  /** What the customer pays. */
  sellPence: number
  /** What it cost, as the invoice charges it. */
  costPence: number
  grossProfitPence: number
  /** Gross profit as a share of the selling price, in basis points. */
  gpBp: number
}

export function margin(sellPence: number, costPence: number): Margin {
  const grossProfitPence = sellPence - costPence
  return {
    sellPence,
    costPence,
    grossProfitPence,
    gpBp: sellPence > 0 ? Math.round((grossProfitPence / sellPence) * 10000) : 0,
  }
}

// --- per line ----------------------------------------------------------------

export type MarginGap = 'price' | 'cost' | 'pour' | null

export interface MarginLine {
  code: string
  name: string
  qtyMilli: number
  margin: Margin | null
  /** Gross profit across everything sold of this line in the period. */
  periodProfitPence: number | null
  /** What is stopping a figure, when there isn't one. */
  missing: MarginGap
}

export interface MarginReport {
  lines: MarginLine[]
  /** Profit on the lines that could be worked out at all. */
  profitPence: number
  /** The takings behind that profit, so the blended GP is honest about coverage. */
  netSalesPence: number
  blendedGpBp: number | null
  costedCount: number
  uncostedCount: number
}

/**
 * Gross profit for every line the till sold.
 *
 * Three things have to line up for a figure to exist: the board price (price
 * book), what one sale takes out of the cellar (pour), and what that costs
 * (stock item). Whichever is missing is named, because "no GP shown" is
 * useless and "no cost entered for Taddy" is a job.
 */
export function marginReport(
  sold: readonly SoldLine[],
  book: readonly PriceBookEntry[],
  pours: readonly Pour[],
  items: readonly StockItem[],
): MarginReport {
  const byCode = new Map(book.filter((b) => b.code).map((b) => [b.code!.toUpperCase(), b]))
  const byName = new Map(book.map((b) => [normalise(b.name), b]))
  const pourByCode = new Map(pours.map((p) => [p.itemCode.toUpperCase(), p]))
  const pourByName = new Map(pours.map((p) => [normalise(p.itemName), p]))
  const itemById = new Map(items.map((i) => [i.id, i]))

  let profitPence = 0
  let netSalesPence = 0
  let costedCount = 0
  let uncostedCount = 0

  const lines = sold.map((line) => {
    const price = byCode.get(line.code.toUpperCase()) ?? byName.get(normalise(line.name))
    const pour = pourByCode.get(line.code.toUpperCase()) ?? pourByName.get(normalise(line.name))
    const cost = pour ? costOf(itemById.get(pour.stockItemId), pour.baseUnits) : null

    let missing: MarginGap = null
    if (!price) missing = 'price'
    else if (!pour) missing = 'pour'
    else if (cost === null) missing = 'cost'

    if (!price || cost === null) {
      uncostedCount++
      return { code: line.code, name: line.name, qtyMilli: line.qtyMilli, margin: null, periodProfitPence: null, missing }
    }

    const m = margin(price.pence, cost)
    // Quantities are thousandths, so multiply before dividing.
    const period = Math.round((m.grossProfitPence * line.qtyMilli) / 1000)
    profitPence += period
    netSalesPence += Math.round((m.sellPence * line.qtyMilli) / 1000)
    costedCount++

    return { code: line.code, name: line.name, qtyMilli: line.qtyMilli, margin: m, periodProfitPence: period, missing: null }
  })

  return {
    // Worst margin first: the point of the screen is to find the line that is
    // not paying its way, not to admire the one that is.
    lines: lines.sort((a, b) => {
      if (a.margin && b.margin) return a.margin.gpBp - b.margin.gpBp
      if (a.margin) return -1
      if (b.margin) return 1
      return b.qtyMilli - a.qtyMilli
    }),
    profitPence,
    netSalesPence,
    blendedGpBp: netSalesPence > 0 ? Math.round((profitPence / netSalesPence) * 10000) : null,
    costedCount,
    uncostedCount,
  }
}

// --- what the cellar is worth ------------------------------------------------

export interface CellarValue {
  lines: Array<{ item: StockItem; baseUnits: number; pence: number | null }>
  /** Money sitting downstairs, at what it cost to buy. */
  totalPence: number
  /** Lines with stock but no cost entered — the total is short by these. */
  unvaluedCount: number
}

/**
 * What is in the cellar, valued at cost.
 *
 * At cost rather than at what it will sell for, because that is the money
 * actually tied up in it — the figure an accountant asks for at year end and
 * the one that says whether too much is sitting in the cold.
 */
export function cellarValue(ledger: readonly StockLine[]): CellarValue {
  let totalPence = 0
  let unvaluedCount = 0

  const lines = ledger.map((line) => {
    const pence = costOf(line.item, Math.max(0, line.expectedBaseUnits))
    if (pence === null) {
      if (line.expectedBaseUnits > 0) unvaluedCount++
    } else {
      totalPence += pence
    }
    return { item: line.item, baseUnits: line.expectedBaseUnits, pence }
  })

  return { lines: lines.sort((a, b) => (b.pence ?? -1) - (a.pence ?? -1)), totalPence, unvaluedCount }
}
