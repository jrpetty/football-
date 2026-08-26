// ---------------------------------------------------------------------------
// The sum the whole app exists to do.
//
//     variance = (card + cash) − till roll
//
// Negative is short: less money is present than the till says was taken.
// Positive is over. Zero, or near enough, is a night that balances.
// ---------------------------------------------------------------------------

import type { DayRecord } from './types.ts'

export type Verdict = 'balanced' | 'short' | 'over' | 'incomplete'

export interface Reconciliation {
  verdict: Verdict
  /** (card + cash) − till, in pence. Zero when incomplete. */
  variancePence: number
  /** Which of the three figures are still missing. */
  missing: Array<'till' | 'card' | 'cash'>
  /** Everything needed for the sum is present. */
  complete: boolean
}

/**
 * Pennies of slack allowed before a night is called out.
 *
 * Not zero. A till that has taken four hundred cash transactions is routinely
 * a few pence out through rounding and honest miscounting, and an app that
 * cried wolf every single night would be ignored inside a week — which is the
 * one failure mode that actually matters here. Adjustable in settings.
 */
export const DEFAULT_TOLERANCE_PENCE = 50

export interface ReconcileInput {
  tillPence: number | null
  cardPence: number | null
  cashPence: number | null
  tolerancePence?: number
}

export function reconcile(input: ReconcileInput): Reconciliation {
  const { tillPence, cardPence, cashPence } = input
  const tolerance = Math.max(0, input.tolerancePence ?? DEFAULT_TOLERANCE_PENCE)

  const missing: Array<'till' | 'card' | 'cash'> = []
  if (tillPence === null) missing.push('till')
  if (cardPence === null) missing.push('card')
  if (cashPence === null) missing.push('cash')

  if (tillPence === null || cardPence === null || cashPence === null) {
    return { verdict: 'incomplete', variancePence: 0, missing, complete: false }
  }

  const variancePence = cardPence + cashPence - tillPence
  const verdict: Verdict =
    Math.abs(variancePence) <= tolerance ? 'balanced' : variancePence < 0 ? 'short' : 'over'

  return { verdict, variancePence, missing, complete: true }
}

export function reconcileDay(day: DayRecord, tolerancePence?: number): Reconciliation {
  return reconcile({
    tillPence: day.till.pence,
    cardPence: day.card.pence,
    cashPence: day.cashPence,
    tolerancePence,
  })
}

/** The one line she reads at the end. */
export function verdictHeadline(r: Reconciliation): string {
  switch (r.verdict) {
    case 'balanced':
      return 'Balanced'
    case 'short':
      return 'Short'
    case 'over':
      return 'Over'
    case 'incomplete':
      return 'Not finished'
  }
}

/** Total takings for the night, by the receipts rather than the till roll. */
export function countedPence(day: DayRecord): number | null {
  if (day.card.pence === null || day.cashPence === null) return null
  return day.card.pence + day.cashPence
}

// ---------------------------------------------------------------------------
// Reconciling against the till's own expectations.
//
// The v1 sum above treats the till roll as a single number and asks whether the
// money adds up to it. The real Z read is more forthcoming than that: it states
// CASH £351.80 and CREDIT CARD £1,841.00 separately, and prints CID for what
// should physically be in the drawer.
//
// That turns one blended answer into two answerable ones. "You are £12 short"
// leaves her hunting through a whole night; "the card machine agrees to the
// penny, the drawer is £12 light" is most of the way to knowing why. The two
// variances necessarily sum to the overall one, because the receipt's own
// arithmetic guarantees cash + card = paid total — and crossfoot checks it.
// ---------------------------------------------------------------------------

import type { ZRead } from './zread.ts'

export interface LegResult {
  /** What the till says should be there. */
  expectedPence: number
  /** What was actually counted, or read off the card slip. */
  countedPence: number
  variancePence: number
  verdict: Exclude<Verdict, 'incomplete'>
}

export interface DayReconciliation {
  /** Always present: the blended (card + cash) − till roll answer. */
  overall: Reconciliation
  /** The drawer against CID. Present only when both figures exist. */
  cash?: LegResult
  /** The card slip against the till's own card figure. */
  card?: LegResult
  /** True when the till stated expectations to check against. */
  itemised: boolean
}

function leg(expectedPence: number, countedPence: number, tolerance: number): LegResult {
  const variancePence = countedPence - expectedPence
  return {
    expectedPence,
    countedPence,
    variancePence,
    verdict: Math.abs(variancePence) <= tolerance ? 'balanced' : variancePence < 0 ? 'short' : 'over',
  }
}

/**
 * What the till expects to find, drawn from the Z read.
 *
 * CID is preferred for cash because it is the drawer figure specifically; where
 * it was not captured, the cash taken is the same number on a till that records
 * no payouts, which is how this one runs.
 *
 * Note that the float is not this function's business. Whatever change is left
 * in the drawer overnight is subtracted before a night is reconciled at all —
 * `cashPence` always means the takings — so the figure compared here is
 * like for like whether or not the pub floats the till.
 */
export function tillExpectations(z: ZRead | undefined): { cashPence?: number; cardPence?: number; totalPence?: number } {
  if (!z) return {}
  const t = z.transaction
  const out: { cashPence?: number; cardPence?: number; totalPence?: number } = {}
  const cash = t.cidPence ?? t.cashPence
  if (cash !== undefined) out.cashPence = cash
  if (t.cardPence !== undefined) out.cardPence = t.cardPence
  const total = t.paidTotalPence ?? z.deptTotal?.pence
  if (total !== undefined) out.totalPence = total
  return out
}

export function reconcileFull(
  input: ReconcileInput & { zRead?: ZRead },
): DayReconciliation {
  const tolerance = Math.max(0, input.tolerancePence ?? DEFAULT_TOLERANCE_PENCE)
  const expected = tillExpectations(input.zRead)

  const overall = reconcile({
    ...input,
    // When the roll was captured in full, the till total is the paid total the
    // till itself printed, not a separately-read figure that could disagree.
    tillPence: input.tillPence ?? expected.totalPence ?? null,
  })

  const result: DayReconciliation = { overall, itemised: false }

  if (expected.cashPence !== undefined && input.cashPence !== null) {
    result.cash = leg(expected.cashPence, input.cashPence, tolerance)
    result.itemised = true
  }
  if (expected.cardPence !== undefined && input.cardPence !== null) {
    result.card = leg(expected.cardPence, input.cardPence, tolerance)
    result.itemised = true
  }

  return result
}

/** One sentence naming where the money went missing, when that is knowable. */
export function itemisedHeadline(r: DayReconciliation): string | null {
  if (!r.itemised) return null
  const cashOff = r.cash && r.cash.verdict !== 'balanced'
  const cardOff = r.card && r.card.verdict !== 'balanced'
  if (!cashOff && !cardOff) return 'The drawer and the card machine both agree with the till.'
  if (cashOff && !cardOff) return 'The card machine agrees with the till — the difference is in the drawer.'
  if (cardOff && !cashOff) return 'The drawer agrees with the till — the difference is on the card machine.'
  return 'Both the drawer and the card machine disagree with the till.'
}
