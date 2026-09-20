// ---------------------------------------------------------------------------
// What sold, and what it made.
//
// The whole app hangs off one fact: the till roll already states everything.
// It prints, for every department and for every button on the till, how many
// went and what they took. So there is nothing here to estimate — the job is to
// add up the receipts that have been read and to be exact about which receipts
// those were.
//
// Two breakdowns, kept apart on purpose:
//
//   categories  the DEPT block — "DRAUGHT BEERS  406.000 Q  *1492.25"
//   items       the PLU block  — "P00014 PINT TADDY  120.000 Q  * 636.00"
//
// They are two separate statements by the same till and they do not have to
// agree. A roll torn below the departments has categories and no items; a
// photograph that missed the top has items and no categories. Adding them
// together, or quietly using one to fill in the other, would turn a gap in the
// paper into a number she would act on. So each side carries its own total and
// its own count of how many receipts it came off, and the screen says so.
//
// There is also no way to put an item into a category from the roll: the PLU
// lines carry no department code. Guessing from the name would be a fuzzy match
// over money, and a wrong match is worse than no match.
//
// Units follow the till: it prints quantity in thousandths (406.000 Q), so that
// is what is carried, and nothing is rounded until it is shown.
// ---------------------------------------------------------------------------

import { departmentLabel } from './departments.ts'
import { shareBp, type ZRead } from './zread.ts'

/** One department, or one button on the till, totalled over the receipts read. */
export interface SoldLine {
  /** "D01", or "P00014". */
  code: string
  name: string
  /** Units sold, in thousandths, as the till prints them. */
  qtyMilli: number
  pence: number
  /** What one went for, averaged over the span. Null when none were sold. */
  eachPence: number | null
  /** Share of the money on this side of the breakdown. */
  shareBp: number
}

/** One of the two breakdowns, with the receipts that actually carried it. */
export interface SoldSide {
  lines: SoldLine[]
  /** Money taken across these lines. */
  pence: number
  /** Units sold across these lines. */
  qtyMilli: number
  /**
   * How many of the receipts had this block on them.
   *
   * The denominator for everything above. Two of seven nights means the table
   * is two nights' trade, however much it looks like a week's.
   */
  reads: number
}

export interface Sold {
  /** Receipts offered, read or not. */
  offered: number
  /** Receipts that said anything at all. */
  reads: number
  categories: SoldSide
  items: SoldSide
}

interface Acc {
  code: string
  name: string
  qtyMilli: number
  pence: number
}

function finish(acc: Map<string, Acc>, reads: number, sort: 'value' | 'quantity'): SoldSide {
  const rows = [...acc.values()]
  const pence = rows.reduce((a, r) => a + r.pence, 0)
  const qtyMilli = rows.reduce((a, r) => a + r.qtyMilli, 0)
  const lines = rows
    .map((r) => ({
      ...r,
      eachPence: r.qtyMilli > 0 ? Math.round(r.pence / (r.qtyMilli / 1000)) : null,
      shareBp: shareBp(r.pence, pence),
    }))
    .sort((a, b) =>
      sort === 'quantity'
        ? b.qtyMilli - a.qtyMilli || b.pence - a.pence || a.name.localeCompare(b.name)
        : b.pence - a.pence || b.qtyMilli - a.qtyMilli || a.name.localeCompare(b.name),
    )
  return { lines, pence, qtyMilli, reads }
}

/**
 * Add up every receipt handed in.
 *
 * Ordered biggest-first by money, because that is the order she reads it in: a
 * hundred mixers at £1.85 matter less to the till than forty pints at £5.30.
 * `sort: 'quantity'` puts the busiest buttons first instead, which is the
 * question behind "what are we actually pouring".
 */
export function soldFrom(
  reads: readonly (ZRead | undefined | null)[],
  sort: 'value' | 'quantity' = 'value',
): Sold {
  const cats = new Map<string, Acc>()
  const items = new Map<string, Acc>()
  let catReads = 0
  let itemReads = 0
  let anything = 0

  for (const z of reads) {
    if (!z) continue
    let said = false

    if (z.departments.length > 0) {
      catReads += 1
      said = true
      for (const d of z.departments) {
        const key = d.code.toUpperCase()
        const found = cats.get(key)
        if (found) {
          found.qtyMilli += d.qtyMilli
          found.pence += d.pence
        } else {
          cats.set(key, {
            code: key,
            name: departmentLabel(d.code, d.name),
            qtyMilli: d.qtyMilli,
            pence: d.pence,
          })
        }
      }
    }

    if (z.plus.length > 0) {
      itemReads += 1
      said = true
      for (const p of z.plus) {
        // Keyed on the code, because the code is what the till is consistent
        // about; a name read slightly differently on two nights must not become
        // two lines. The name shown is the first one read, which is the one she
        // will recognise from the receipt in her hand.
        const key = p.code.toUpperCase()
        const found = items.get(key)
        if (found) {
          found.qtyMilli += p.qtyMilli
          found.pence += p.pence
        } else {
          items.set(key, { code: key, name: p.name, qtyMilli: p.qtyMilli, pence: p.pence })
        }
      }
    }

    if (said) anything += 1
  }

  return {
    offered: reads.length,
    reads: anything,
    categories: finish(cats, catReads, sort),
    items: finish(items, itemReads, sort),
  }
}

/** True when nothing was read, so a screen can say so rather than show zeroes. */
export function soldIsEmpty(sold: Sold): boolean {
  return sold.categories.lines.length === 0 && sold.items.lines.length === 0
}

/**
 * How many receipts a side is missing, out of those that said anything.
 *
 * Nought means the table covers every night in the span. Anything else is worth
 * saying out loud: a table that covers four of seven nights is not a week.
 */
export function shortBy(sold: Sold, side: SoldSide): number {
  return Math.max(0, sold.reads - side.reads)
}
