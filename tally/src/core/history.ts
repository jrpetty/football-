// ---------------------------------------------------------------------------
// When prices moved, and whether the board kept up.
//
// A brewery puts a cask up by four pounds. Nobody changes the board. Nothing
// breaks, nothing fails to balance, the cellar still reconciles — and the pint
// quietly makes forty pence less than it did. Do that across a year and half a
// dozen lines and it is thousands of pounds that never appear as a loss
// anywhere, because there is no moment at which anything went wrong.
//
// That is what this is for. Both prices are kept as an append-only log rather
// than a single current figure, so the question "when did that change, and did
// we follow it?" has an answer. The current price stays where it always was —
// the log sits alongside it, so nothing that reads a cost had to learn about
// history to keep working.
// ---------------------------------------------------------------------------

import type { PriceBookEntry } from './priceBook.ts'
import { costOf, margin, type Margin } from './margin.ts'
import type { Pour, StockItem } from './stock.ts'
import { normalise } from './match.ts'

/** What a container cost, from a given day. */
export interface CostPoint {
  date: string
  pence: number
  baseUnits: number
}

/** What a line sold for, from a given day. */
export interface PricePoint {
  date: string
  pence: number
}

/**
 * Add a change to a log, keeping it in date order.
 *
 * Two corrections on the same day are one change, not two: entering £95 and
 * then fixing it to £96 five seconds later should leave one entry, or the
 * history fills up with typing rather than with brewery decisions.
 *
 * A change that does not change anything is dropped entirely, so opening the
 * screen and closing it again never writes a point.
 */
export function record<T extends { date: string; pence: number }>(log: readonly T[], point: T): T[] {
  const last = [...log].sort((a, b) => a.date.localeCompare(b.date)).at(-1)
  if (last && last.date === point.date) {
    const rest = log.filter((p) => p.date !== point.date)
    return [...rest, point].sort((a, b) => a.date.localeCompare(b.date))
  }
  // Same figure as the one already standing: nothing happened.
  if (last && last.pence === point.pence && sameBasis(last, point)) return [...log]
  return [...log, point].sort((a, b) => a.date.localeCompare(b.date))
}

function sameBasis(a: unknown, b: unknown): boolean {
  const x = (a as { baseUnits?: number }).baseUnits
  const y = (b as { baseUnits?: number }).baseUnits
  return x === y
}

/** What was in force on a given date — the last change on or before it. */
export function at<T extends { date: string }>(log: readonly T[], date: string): T | null {
  let found: T | null = null
  for (const point of [...log].sort((a, b) => a.date.localeCompare(b.date))) {
    if (point.date > date) break
    found = point
  }
  return found
}

export type MoveVerdict = 'squeezed' | 'kept up' | 'improved' | 'unchanged'

export interface MarginMove {
  code: string
  name: string
  /** The earliest point both a cost and a price are known for. */
  fromDate: string
  toDate: string
  costThenPence: number
  costNowPence: number
  priceThenPence: number
  priceNowPence: number
  then: Margin
  now: Margin
  /** Now minus then, in basis points. Negative is margin lost. */
  gpChangeBp: number
  verdict: MoveVerdict
}

/**
 * Which lines have had their margin moved, and by what.
 *
 * "Squeezed" is the one that matters: the cost went up and the price did not
 * follow, so the pub is absorbing the increase. "Kept up" means both moved and
 * the margin held. A line with no history on either side simply does not
 * appear — there is nothing to say about it yet.
 */
export function marginMoves(
  items: readonly StockItem[],
  pours: readonly Pour[],
  book: readonly PriceBookEntry[],
  vatBp: number,
): MarginMove[] {
  const byId = new Map(items.map((i) => [i.id, i]))
  const bookByCode = new Map(book.filter((b) => b.code).map((b) => [b.code!.toUpperCase(), b]))
  const bookByName = new Map(book.map((b) => [normalise(b.name), b]))

  const moves: MarginMove[] = []

  for (const pour of pours) {
    const item = byId.get(pour.stockItemId)
    const entry = bookByCode.get(pour.itemCode.toUpperCase()) ?? bookByName.get(normalise(pour.itemName))
    if (!item || !entry) continue

    const costLog = item.costHistory ?? []
    const priceLog = entry.history ?? []
    // Something has to have moved, or there is no story.
    if (costLog.length + priceLog.length < 2) continue

    const dates = [...costLog.map((p) => p.date), ...priceLog.map((p) => p.date)].sort()
    const fromDate = dates[0] as string
    const toDate = dates[dates.length - 1] as string
    if (fromDate === toDate) continue

    const costThenPoint = at(costLog, fromDate)
    const costNowPoint = at(costLog, toDate)
    const priceThen = at(priceLog, fromDate)?.pence ?? entry.pence
    const priceNow = at(priceLog, toDate)?.pence ?? entry.pence

    // Cost per serving at each end, using the basis in force at the time.
    const costAtPoint = (point: CostPoint | null): number | null =>
      point ? costOf({ ...item, cost: { pence: point.pence, baseUnits: point.baseUnits } }, pour.baseUnits) : null

    const costThen = costAtPoint(costThenPoint)
    const costNow = costAtPoint(costNowPoint)
    if (costThen === null || costNow === null) continue

    const then = margin(priceThen, costThen, vatBp)
    const now = margin(priceNow, costNow, vatBp)
    const gpChangeBp = now.gpBp - then.gpBp

    // A point either way is noise — rounding, or a penny on a case.
    const verdict: MoveVerdict =
      Math.abs(gpChangeBp) < 100
        ? costNow !== costThen
          ? 'kept up'
          : 'unchanged'
        : gpChangeBp < 0
          ? 'squeezed'
          : 'improved'

    moves.push({
      code: pour.itemCode,
      name: pour.itemName,
      fromDate,
      toDate,
      costThenPence: costThen,
      costNowPence: costNow,
      priceThenPence: priceThen,
      priceNowPence: priceNow,
      then,
      now,
      gpChangeBp,
      verdict,
    })
  }

  // Worst squeeze first: the point of the screen is the line that is leaking.
  return moves.sort((a, b) => a.gpChangeBp - b.gpChangeBp)
}
