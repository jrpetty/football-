// ---------------------------------------------------------------------------
// Every night so far, most recent first.
//
// Kept deliberately plain. The brief asks for a list and a variance flag and
// nothing fancier, and the weekly patterns worth spotting ("Fridays are always
// short") are a phase two question that wants more nights than exist yet.
// ---------------------------------------------------------------------------

import { useEffect, useState } from 'react'
import { formatShort } from '../core/date.ts'
import { formatMoney, formatSigned } from '../core/money.ts'
import { reconcileDay } from '../core/reconcile.ts'
import type { DayRecord } from '../core/types.ts'
import { listDays } from '../storage/db.ts'
import { loadSettings } from '../storage/settings.ts'

interface Props {
  onOpen: (date: string) => void
  onStart: () => void
  /** Bumped by the shell after a save, so the list reflects it. */
  refreshKey: number
}

export function History({ onOpen, onStart, refreshKey }: Props) {
  const [days, setDays] = useState<DayRecord[] | null>(null)
  const [error, setError] = useState('')
  const tolerance = loadSettings().tolerancePence

  useEffect(() => {
    let cancelled = false
    listDays()
      .then((d) => !cancelled && setDays(d))
      .catch((err: unknown) => !cancelled && setError(err instanceof Error ? err.message : String(err)))
    return () => {
      cancelled = true
    }
  }, [refreshKey])

  if (error) {
    return <div className="main"><p className="note bad">Could not read the saved nights: {error}</p></div>
  }

  if (days === null) {
    return <div className="main"><p className="note"><span className="spinner" /> Loading…</p></div>
  }

  if (days.length === 0) {
    return (
      <div className="main">
        <div className="empty">
          <p>No nights recorded yet.</p>
          <p>Close up, photograph the till roll and the card slip, and the first one will appear here.</p>
        </div>
        <button type="button" className="btn-primary" onClick={onStart}>Count tonight</button>
      </div>
    )
  }

  return (
    <div className="main">
      <p className="note">{days.length} {days.length === 1 ? 'night' : 'nights'} recorded</p>
      <div className="day-list">
        {days.map((day) => {
          const r = reconcileDay(day, tolerance)
          const counted = day.card.pence !== null && day.cashPence !== null ? day.card.pence + day.cashPence : null
          return (
            <button type="button" key={day.date} className="card day-row" onClick={() => onOpen(day.date)}>
              <span className="when">
                <span className="date">{formatShort(day.date)}</span>
                <br />
                <span className="takings num">
                  {counted === null ? 'Not finished' : `Took ${formatMoney(counted)}`}
                </span>
              </span>
              <span className={`delta ${r.verdict}`}>
                {r.verdict === 'incomplete'
                  ? '—'
                  : r.verdict === 'balanced'
                    ? '✅'
                    : formatSigned(r.variancePence)}
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
