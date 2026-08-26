// ---------------------------------------------------------------------------
// What things should cost.
//
// The reconciliation elsewhere in this app answers one question: is the money
// in the drawer the money the till says was taken? A night can pass that test
// perfectly and still have lost a hundred pounds — because the till was told
// the wrong price, and then counted correctly all evening.
//
// That is what this measures, and it is a different loss entirely:
//
//     cash variance   = counted        − what the till says it took
//     price variance  = what it took   − what the menu says it should have
//
// The second is invisible to the first. A pint rung at £4.00 when the board
// says £4.20 balances to the penny every single time.
//
// The arithmetic is only as honest as its caveat, which is stated here and
// again in the interface: the till's average price is value ÷ quantity, so any
// legitimately discounted sale drags it below the menu price. This roll has an
// "O A P" line on it, so discounts plainly happen. A gap is a question worth
// asking, never an accusation.
// ---------------------------------------------------------------------------

export interface PriceBookEntry {
  /** The PLU code, when known — the only identifier that cannot drift. */
  code?: string
  /** As printed on the till roll, which is how a codeless entry is matched. */
  name: string
  /** The price on the board, in pence. */
  pence: number
  note?: string
}

export interface SoldItem {
  code: string
  name: string
  qtyMilli: number
  pence: number
}

export type PriceVerdict = 'matches' | 'under' | 'over' | 'unpriced'

export interface PriceCheck {
  code: string
  name: string
  qtyMilli: number
  /** What the till actually took for this line. */
  takenPence: number
  /** value ÷ quantity — the average, which discounts pull down. */
  avgPencePerItem: number
  /** The board price, when this item is in the book. */
  expectedPencePerItem: number | null
  /** Board price × quantity sold. */
  expectedTakePence: number | null
  /** Taken − expected. Negative means the till took less than the board says. */
  variancePence: number | null
  verdict: PriceVerdict
}

export interface PriceReport {
  rows: PriceCheck[]
  /** Lines the book had a price for. */
  pricedCount: number
  /** Lines it did not, which are excluded from every total below. */
  unpricedCount: number
  /** Net across the priced lines. */
  variancePence: number
  /** Only the shortfalls, which a net figure can hide. */
  underPence: number
  overPence: number
  /** Takings that could be checked at all. */
  checkedTakePence: number
}

/** Match on the printed name when there is no code to go on. */
function normalise(name: string): string {
  return name.trim().toUpperCase().replace(/\s+/g, ' ')
}

/**
 * A penny either way is rounding, not mispricing.
 *
 * The till reports whole pence but the average is a division, so an item sold
 * an odd number of times can land a penny off a price that was rung correctly
 * every time.
 */
export const PRICE_TOLERANCE_PENCE = 1

export function buildIndex(book: readonly PriceBookEntry[]): Map<string, PriceBookEntry> {
  const index = new Map<string, PriceBookEntry>()
  // Names first, so a code — the stronger key — overwrites a name collision.
  for (const entry of book) index.set(`name:${normalise(entry.name)}`, entry)
  for (const entry of book) if (entry.code) index.set(`code:${entry.code.toUpperCase()}`, entry)
  return index
}

export function lookup(
  index: Map<string, PriceBookEntry>,
  item: { code: string; name: string },
): PriceBookEntry | undefined {
  return index.get(`code:${item.code.toUpperCase()}`) ?? index.get(`name:${normalise(item.name)}`)
}

export function checkPrices(items: readonly SoldItem[], book: readonly PriceBookEntry[]): PriceReport {
  const index = buildIndex(book)
  const rows: PriceCheck[] = []

  let pricedCount = 0
  let unpricedCount = 0
  let variancePence = 0
  let underPence = 0
  let overPence = 0
  let checkedTakePence = 0

  for (const item of items) {
    const qty = item.qtyMilli / 1000
    const avg = qty > 0 ? Math.round(item.pence / qty) : 0
    const entry = lookup(index, item)

    if (!entry || qty <= 0) {
      unpricedCount++
      rows.push({
        code: item.code,
        name: item.name,
        qtyMilli: item.qtyMilli,
        takenPence: item.pence,
        avgPencePerItem: avg,
        expectedPencePerItem: null,
        expectedTakePence: null,
        variancePence: null,
        verdict: 'unpriced',
      })
      continue
    }

    // Expected takings are computed from the whole line, not from the rounded
    // average — rounding once at the end beats rounding every item.
    const expectedTake = Math.round(entry.pence * qty)
    const variance = item.pence - expectedTake
    const perItemGap = avg - entry.pence

    pricedCount++
    variancePence += variance
    checkedTakePence += item.pence
    if (variance < 0) underPence += -variance
    if (variance > 0) overPence += variance

    rows.push({
      code: item.code,
      name: item.name,
      qtyMilli: item.qtyMilli,
      takenPence: item.pence,
      avgPencePerItem: avg,
      expectedPencePerItem: entry.pence,
      expectedTakePence: expectedTake,
      variancePence: variance,
      verdict:
        Math.abs(perItemGap) <= PRICE_TOLERANCE_PENCE ? 'matches' : perItemGap < 0 ? 'under' : 'over',
    })
  }

  // Biggest shortfall first — that is the line worth looking at — with the
  // lines that have no price set pushed to the end rather than being treated as
  // a variance of zero and shuffled in among the real findings.
  rows.sort((a, b) => {
    const aUnpriced = a.variancePence === null
    const bUnpriced = b.variancePence === null
    if (aUnpriced !== bUnpriced) return aUnpriced ? 1 : -1
    if (aUnpriced) return b.takenPence - a.takenPence
    return (a.variancePence ?? 0) - (b.variancePence ?? 0)
  })

  return { rows, pricedCount, unpricedCount, variancePence, underPence, overPence, checkedTakePence }
}

/** One sentence, honest about what a gap does and does not prove. */
export function priceHeadline(report: PriceReport): string {
  if (report.pricedCount === 0) return 'No prices set yet, so nothing could be checked.'
  if (report.underPence === 0 && report.overPence === 0) {
    return `Every one of the ${report.pricedCount} priced lines rang at the board price.`
  }
  if (report.underPence > 0 && report.overPence === 0) {
    return `The till took less than the board says on some lines. Discounts explain some of this — check the ones at the top.`
  }
  if (report.overPence > 0 && report.underPence === 0) {
    return `Some lines rang above the board price. Usually a price rise the board has not caught up with.`
  }
  return 'Some lines rang under the board price and some over. Worth a look at both ends.'
}


// --- reading the board -------------------------------------------------------

import { bestMatch } from './match.ts'

export interface PriceProposal {
  /** The line as written on the board. */
  written: string
  pence: number
  /** The till line it was matched to, when one was found. */
  code?: string
  name?: string
  /** What the book already says, so a change is visible as a change. */
  wasPence?: number
  status: 'new' | 'changed' | 'same' | 'ambiguous' | 'unmatched'
  /** When ambiguous, the lines it could equally have been. */
  between?: string[]
}

/**
 * Turn a photographed price board into a proposal for the book.
 *
 * Nothing here writes anything. Every row comes back labelled with what it
 * would do — add a price, change one, or nothing at all — and the two failure
 * cases are labelled just as plainly, because a board reading "Taddy Lager
 * 4.00" genuinely cannot be resolved to the pint or the half without asking.
 */
export function proposePrices(
  scanned: ReadonlyArray<{ name: string; pence: number }>,
  tillItems: ReadonlyArray<{ code: string; name: string }>,
  book: readonly PriceBookEntry[],
): PriceProposal[] {
  const byCode = new Map(book.filter((b) => b.code).map((b) => [b.code!.toUpperCase(), b]))
  const byName = new Map(book.map((b) => [normalise(b.name), b]))

  return scanned.map((row) => {
    const match = bestMatch(row.name, tillItems, (t) => t.name)

    if (match.kind === 'unmatched') return { written: row.name, pence: row.pence, status: 'unmatched' as const }
    if (match.kind === 'ambiguous') {
      return {
        written: row.name,
        pence: row.pence,
        status: 'ambiguous' as const,
        between: match.between.map((t) => t.name),
      }
    }

    const item = match.value
    const existing = byCode.get(item.code.toUpperCase()) ?? byName.get(normalise(item.name))
    const status = !existing ? 'new' : existing.pence === row.pence ? 'same' : 'changed'
    return {
      written: row.name,
      pence: row.pence,
      code: item.code,
      name: item.name,
      ...(existing ? { wasPence: existing.pence } : {}),
      status: status as PriceProposal['status'],
    }
  })
}

/** Fold accepted proposals into the book, replacing by code where there is one. */
export function applyPrices(book: readonly PriceBookEntry[], accepted: readonly PriceProposal[]): PriceBookEntry[] {
  const next = [...book]
  for (const row of accepted) {
    // Hoisted so the narrowing survives into the closure below.
    const name = row.name
    if (!name) continue
    const code = row.code
    const at = next.findIndex((b) =>
      code && b.code ? b.code.toUpperCase() === code.toUpperCase() : normalise(b.name) === normalise(name),
    )
    const entry: PriceBookEntry = { name, pence: row.pence, ...(code ? { code } : {}) }
    const found = at >= 0 ? next[at] : undefined
    if (found) next[at] = { ...found, ...entry }
    else next.push(entry)
  }
  return next
}
