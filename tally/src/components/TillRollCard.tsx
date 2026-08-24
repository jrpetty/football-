// ---------------------------------------------------------------------------
// Capturing the till roll.
//
// The roll is long — the reference one took three photographs — so this takes
// them one at a time and merges each into what is already held. Photographing
// the summary after the item list adds to it rather than replacing it.
//
// There is always a way through without scanning. If the key is missing, the
// signal is down, or the photograph is unreadable, the total can simply be
// typed, and the night is still a complete record. The department detail is
// what makes the dashboard possible, not what makes the night valid.
// ---------------------------------------------------------------------------

import { useEffect, useRef, useState } from 'react'
import { MoneyInput } from './MoneyInput.tsx'
import { CrossfootSummary, CrossfootList } from './CrossfootPanel.tsx'
import { crossfootVerdict } from '../core/crossfoot.ts'
import { formatMoney, parsePence } from '../core/money.ts'
import { isZReadEmpty, type ZRead } from '../core/zread.ts'
import type { CaptureConfidence, CaptureSource } from '../core/types.ts'
import { describeZReadError, scanZRead } from '../ocr/scanZRead.ts'

export interface RollState {
  zRead?: ZRead
  photos: Blob[]
  scanning: boolean
  error: string
  notes: string
  confidence?: CaptureConfidence
  rawText?: string
  /** Used when the roll was not scanned — she just types the session total. */
  totalText: string
  source: CaptureSource
  edited: boolean
}

export function emptyRoll(): RollState {
  return { photos: [], scanning: false, error: '', notes: '', totalText: '', source: 'manual', edited: false }
}

/** The night's takings, whichever way they were captured. */
export function rollTotalPence(roll: RollState): number | null {
  const typed = parsePence(roll.totalText)
  if (typed !== null) return typed
  const z = roll.zRead
  return z?.deptTotal?.pence ?? z?.transaction.paidTotalPence ?? null
}

interface Props {
  value: RollState
  onChange: (next: RollState) => void
  onReview: () => void
}

export function TillRollCard({ value, onChange, onReview }: Props) {
  const fileRef = useRef<HTMLInputElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const [showChecks, setShowChecks] = useState(false)

  useEffect(() => () => abortRef.current?.abort(), [])

  const z = value.zRead
  const captured = !isZReadEmpty(z)
  const verdict = captured && z ? crossfootVerdict(z) : null

  async function onFile(file: File) {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    const photos = [...value.photos, file]
    onChange({ ...value, photos, scanning: true, error: '' })

    try {
      const result = await scanZRead({
        file,
        signal: controller.signal,
        ...(value.zRead ? { existing: value.zRead } : {}),
      })
      if (controller.signal.aborted) return
      onChange({
        ...value,
        photos,
        scanning: false,
        error: '',
        zRead: result.zRead,
        confidence: result.confidence,
        notes: result.notes,
        rawText: result.rawText,
        source: result.engine,
        // A scanned roll supplies its own total, so the typed box steps aside.
        totalText: '',
      })
    } catch (err) {
      if (controller.signal.aborted) return
      onChange({ ...value, photos, scanning: false, error: describeZReadError(err) })
    }
  }

  const takings = rollTotalPence(value)
  const t = z?.transaction

  return (
    <section className="card">
      <div className="card-head">
        <h2>Till roll</h2>
        <span className="hint">Z read</span>
      </div>

      {captured ? (
        <>
          <div className="zrow" style={{ borderTop: 0 }}>
            <span className="zname">
              Taken
              <small>
                {z?.header.zNumber ? `Z ${z.header.zNumber}` : 'from the roll'}
                {t?.guestCount ? ` · ${t.guestCount} sales` : ''}
              </small>
            </span>
            <strong className="num" style={{ fontSize: 22 }}>
              {takings === null ? '—' : formatMoney(takings)}
            </strong>
          </div>
          {t?.cashPence !== undefined && (
            <div className="zrow">
              <span className="zname">Till says cash<small>what should be in the drawer</small></span>
              <strong className="num">{formatMoney(t.cidPence ?? t.cashPence)}</strong>
            </div>
          )}
          {t?.cardPence !== undefined && (
            <div className="zrow">
              <span className="zname">Till says card<small>to check the slip against</small></span>
              <strong className="num">{formatMoney(t.cardPence)}</strong>
            </div>
          )}
        </>
      ) : (
        <div className="figure">
          <MoneyInput
            id="figure-till"
            label="Till roll total"
            value={value.totalText}
            onChange={(text) => onChange({ ...value, totalText: text, edited: value.source !== 'manual' })}
          />
          <button
            type="button"
            className="btn-scan"
            onClick={() => fileRef.current?.click()}
            disabled={value.scanning}
            aria-label="Photograph the till roll"
          >
            {value.scanning ? (
              <><span className="spinner" /><span>Reading…</span></>
            ) : (
              <><span className="glyph" aria-hidden="true">📷</span><span>Scan roll</span></>
            )}
          </button>
        </div>
      )}

      <input
        ref={fileRef}
        type="file"
        accept="image/*"
        capture="environment"
        className="visually-hidden"
        data-testid="file-roll"
        onChange={(e) => {
          const file = e.target.files?.[0]
          e.target.value = ''
          if (file) void onFile(file)
        }}
      />

      {value.error && <p className="note bad" role="status">{value.error}</p>}
      {value.notes && !value.error && <p className="note warn" role="status">{value.notes}</p>}
      {verdict && <CrossfootSummary verdict={verdict} />}
      {verdict && !verdict.clean && <CrossfootList verdict={verdict} />}

      {captured && (
        <>
          <div className="alts">
            <button type="button" className="btn-small" onClick={onReview}>
              Check every figure
            </button>
            <button type="button" className="btn-small" onClick={() => fileRef.current?.click()} disabled={value.scanning}>
              {value.scanning ? 'Reading…' : `Add another photo (${value.photos.length})`}
            </button>
            <button
              type="button"
              className="btn-small"
              onClick={() => onChange({ ...emptyRoll(), photos: [] })}
            >
              Start the roll again
            </button>
          </div>
          {verdict && verdict.clean && (
            <>
              <div className="alts">
                <button type="button" className="btn-small" onClick={() => setShowChecks((v) => !v)}>
                  {showChecks ? 'Hide the sums' : `Show the ${verdict.checks.length} sums that agree`}
                </button>
              </div>
              {showChecks && <CrossfootList verdict={verdict} showPassing />}
            </>
          )}
        </>
      )}

      {!captured && (
        <p className="note">
          Photograph the roll and every department, payment and count comes across. Or just type the
          session total — the night still saves either way.
        </p>
      )}
    </section>
  )
}
