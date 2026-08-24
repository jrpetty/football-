// ---------------------------------------------------------------------------
// What the receipt says about itself.
//
// This replaces the confidence score. "80% sure" tells her nothing she can act
// on; "the departments come to £2,192.40 but the total says £2,192.80" tells
// her exactly which six lines to look at. Every check names the figures it
// compared, so a disagreement is a place to look rather than a mood.
// ---------------------------------------------------------------------------

import type { CrossfootVerdict } from '../core/crossfoot.ts'

export function CrossfootSummary({ verdict }: { verdict: CrossfootVerdict }) {
  if (verdict.checks.length === 0) return null

  if (verdict.clean) {
    return (
      <p className="note" role="status">
        <span className="badge good">Adds up</span> All {verdict.checks.length} of the roll’s own
        sums agree — the departments, the payments, the counts and the percentages.
      </p>
    )
  }

  const errors = verdict.errors.length
  const warnings = verdict.warnings.length
  return (
    <p className={`note ${errors ? 'bad' : 'warn'}`} role="status">
      <span className={`badge ${errors ? 'bad' : 'warn'}`}>
        {errors ? `${errors} ${errors === 1 ? 'figure disagrees' : 'figures disagree'}` : `${warnings} to check`}
      </span>{' '}
      {errors
        ? 'Something was misread. The failing sums are listed below.'
        : 'Nothing is provably wrong, but these are worth a glance.'}
    </p>
  )
}

export function CrossfootList({
  verdict,
  showPassing = false,
}: {
  verdict: CrossfootVerdict
  showPassing?: boolean
}) {
  const shown = showPassing ? verdict.checks : verdict.checks.filter((c) => !c.ok)
  if (shown.length === 0) return null

  return (
    <ul className="checks">
      {shown.map((c) => (
        <li key={c.id} className={c.ok ? 'ok' : c.severity === 'error' ? 'bad' : 'warn'}>
          <span className="mark" aria-hidden="true">{c.ok ? '✓' : c.severity === 'error' ? '✕' : '!'}</span>
          <span>
            {c.label}
            {!c.ok && c.expected !== undefined && (
              <>
                <br />
                <span className="said">
                  should be {c.expected} — the roll says {c.actual}
                </span>
              </>
            )}
          </span>
        </li>
      ))}
    </ul>
  )
}
