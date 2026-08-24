// ---------------------------------------------------------------------------
// One figure off one receipt: photograph it, check what was read, correct it.
//
// The correcting is the point. Receipt paper defeats scanners often enough
// that an app which merely *reported* a number would be trusted once and
// abandoned the second it was wrong. So the scanned figure lands in an ordinary
// editable box, the reading is shown with its confidence and its reasoning, and
// changing it is a single tap away — and is recorded, so it is possible to tell
// later how often the scanning was actually worth using.
// ---------------------------------------------------------------------------

import { useEffect, useRef, useState } from 'react'
import { MoneyInput } from './MoneyInput.tsx'
import { formatMoney, parsePence, penceToInput, isPlausibleTakings } from '../core/money.ts'
import type { CaptureConfidence, CaptureSource } from '../core/types.ts'
import type { ReceiptKind, TotalCandidate } from '../ocr/index.ts'
import { describeScanError, scanReceipt } from '../ocr/index.ts'

export interface FigureState {
  text: string
  source: CaptureSource
  edited: boolean
  confidence?: CaptureConfidence
  notes: string
  candidates: TotalCandidate[]
  photo?: Blob
  rawText?: string
  scanning: boolean
  error: string
  /** What the scanner put here, so an edit away from it can be detected. */
  scannedText?: string
}

export function emptyFigure(): FigureState {
  return { text: '', source: 'manual', edited: false, notes: '', candidates: [], scanning: false, error: '' }
}

const CONFIDENCE_WORDS: Record<CaptureConfidence, string> = {
  high: 'Read clearly',
  medium: 'Read — worth a glance',
  low: 'Hard to read — please check',
}

interface Props {
  title: string
  hint: string
  kind: ReceiptKind
  value: FigureState
  onChange: (next: FigureState) => void
}

export function FigureCard({ title, hint, kind, value, onChange }: Props) {
  const fileRef = useRef<HTMLInputElement>(null)
  const [preview, setPreview] = useState<string | null>(null)
  const [showRaw, setShowRaw] = useState(false)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    if (!value.photo) {
      setPreview(null)
      return
    }
    const url = URL.createObjectURL(value.photo)
    setPreview(url)
    // Revoked on replacement and on unmount; a night of retaken photographs
    // would otherwise hold every one of them in memory.
    return () => URL.revokeObjectURL(url)
  }, [value.photo])

  useEffect(() => () => abortRef.current?.abort(), [])

  async function onFile(file: File) {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    onChange({ ...value, photo: file, scanning: true, error: '', notes: '', candidates: [] })

    try {
      const result = await scanReceipt({ file, kind, signal: controller.signal })
      if (controller.signal.aborted) return
      const text = result.pence === null ? '' : penceToInput(result.pence)
      onChange({
        ...value,
        photo: file,
        scanning: false,
        error: '',
        text: text || value.text,
        scannedText: text || undefined,
        source: result.engine,
        edited: false,
        confidence: result.confidence,
        notes: result.notes,
        candidates: result.candidates,
        rawText: result.rawText,
      })
    } catch (err) {
      if (controller.signal.aborted) return
      // A failed scan is not a failed night. The photograph stays, the box is
      // left open, and she types the figure in as she always has.
      onChange({ ...value, photo: file, scanning: false, error: describeScanError(err) })
    }
  }

  function setText(text: string) {
    onChange({
      ...value,
      text,
      edited: value.scannedText !== undefined && text.trim() !== value.scannedText.trim(),
    })
  }

  function useCandidate(c: TotalCandidate) {
    const text = penceToInput(c.pence)
    onChange({ ...value, text, edited: value.scannedText !== undefined && text !== value.scannedText })
  }

  const pence = parsePence(value.text)
  const implausible = pence !== null && !isPlausibleTakings(pence)
  const alternatives = value.candidates
    .filter((c) => penceToInput(c.pence) !== value.text)
    .slice(0, 4)

  return (
    <section className="card">
      <div className="card-head">
        <h2>{title}</h2>
        <span className="hint">{hint}</span>
      </div>

      <div className="figure">
        <MoneyInput id={`figure-${kind}`} label={title} value={value.text} onChange={setText} />
        <button
          type="button"
          className="btn-scan"
          onClick={() => fileRef.current?.click()}
          disabled={value.scanning}
          aria-label={`Photograph the ${title.toLowerCase()}`}
        >
          {value.scanning ? (
            <><span className="spinner" /><span>Reading…</span></>
          ) : (
            <><span className="glyph" aria-hidden="true">📷</span><span>{value.photo ? 'Retake' : 'Scan'}</span></>
          )}
        </button>
      </div>

      <input
        ref={fileRef}
        type="file"
        accept="image/*"
        capture="environment"
        className="visually-hidden"
        data-testid={`file-${kind}`}
        onChange={(e) => {
          const file = e.target.files?.[0]
          // Cleared so photographing the same receipt twice still fires.
          e.target.value = ''
          if (file) void onFile(file)
        }}
      />

      {value.error && <p className="note bad" role="status">{value.error}</p>}

      {!value.error && value.confidence && !value.scanning && (
        <p className={`note${value.confidence === 'low' ? ' warn' : ''}`} role="status">
          <span className={`badge ${value.confidence === 'high' ? 'good' : value.confidence === 'low' ? 'bad' : 'warn'}`}>
            {CONFIDENCE_WORDS[value.confidence]}
          </span>
          {value.edited && <> <span className="badge">You corrected this</span></>}
          {value.notes && <> {value.notes}</>}
        </p>
      )}

      {implausible && (
        <p className="note warn" role="status">
          {formatMoney(pence)} is an unusual figure for one night — worth a second look.
        </p>
      )}

      {alternatives.length > 0 && !value.scanning && (
        <>
          <p className="note">Other figures on that receipt:</p>
          <div className="alts">
            {alternatives.map((c, i) => (
              <button type="button" key={`${c.pence}-${i}`} onClick={() => useCandidate(c)}>
                {c.label}: {formatMoney(c.pence)}
              </button>
            ))}
          </div>
        </>
      )}

      {preview && (
        <div className="shot">
          <img src={preview} alt={`The ${title.toLowerCase()} as photographed`} />
        </div>
      )}

      {value.rawText && (
        <>
          <div className="alts">
            <button type="button" className="btn-small" onClick={() => setShowRaw((v) => !v)}>
              {showRaw ? 'Hide what was scanned' : 'Show what was scanned'}
            </button>
          </div>
          {showRaw && <pre className="raw">{value.rawText}</pre>}
        </>
      )}
    </section>
  )
}
