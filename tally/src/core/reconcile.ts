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
