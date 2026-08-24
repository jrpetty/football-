// ---------------------------------------------------------------------------
// The answer.
//
// This is the one thing she is actually here for, so it says what happened in
// plain words, in one line, with the amount spelled out — never a bare number
// she has to interpret, and never a colour doing the work on its own.
// ---------------------------------------------------------------------------

import { formatMoney } from '../core/money.ts'
import type { Reconciliation } from '../core/reconcile.ts'

const MISSING_WORDS: Record<'till' | 'card' | 'cash', string> = {
  till: 'the till roll total',
  card: 'the card total',
  cash: 'the cash counted',
}

function list(items: string[]): string {
  if (items.length <= 1) return items[0] ?? ''
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

export function VerdictPanel({ r }: { r: Reconciliation }) {
  const amount = formatMoney(Math.abs(r.variancePence))

  const { glyph, headline, detail } =
    r.verdict === 'balanced'
      ? {
          glyph: '✅',
          headline: 'Balanced',
          detail: r.variancePence === 0 ? 'The money and the till agree exactly.' : `Out by ${amount} — near enough.`,
        }
      : r.verdict === 'short'
        ? {
            glyph: '⚠️',
            headline: `Short by ${amount}`,
            detail: 'There is less money than the till says was taken.',
          }
        : r.verdict === 'over'
          ? {
              glyph: '⚠️',
              headline: `Over by ${amount}`,
              detail: 'There is more money than the till says was taken.',
            }
          : {
              glyph: '⏳',
              headline: 'Not finished',
              detail: `Still need ${list(r.missing.map((m) => MISSING_WORDS[m]))}.`,
            }

  return (
    // aria-live so the verdict is announced when it changes, rather than only
    // being visible to someone already looking at the bottom of the screen.
    <div className={`verdict ${r.verdict}`} role="status" aria-live="polite">
      <span className="glyph" aria-hidden="true">{glyph}</span>
      <span className="words">
        <span className="headline">{headline}</span>
        <span className="detail"> {detail}</span>
      </span>
    </div>
  )
}
