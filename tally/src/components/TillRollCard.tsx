// ---------------------------------------------------------------------------
// Capturing the till roll.
//
// The roll is longer than a phone's camera frame — the reference one took three
// photographs — so this takes them all at once. Drop the lot in one place and
// each is read separately, announces which sections it turned out to contain,
// and is folded into a single read. Order does not matter, one bad photograph
// does not take the others with it, and anything still missing is named rather
// than silently absent.
//
// There is always a way through without scanning. If the key is missing, the
// signal is down, or a photograph is unreadable, the total can simply be typed,
// and the night is still a complete record. The department detail is what makes
// the dashboard possible, not what makes the night valid.
// ---------------------------------------------------------------------------

import { useEffect, useRef, useState } from 'react'
import { MoneyInput } from './MoneyInput.tsx'
import { CrossfootSummary, CrossfootList } from './CrossfootPanel.tsx'
import { crossfootVerdict } from '../core/crossfoot.ts'
import { formatMoney, parsePence } from '../core/money.ts'
import { isZReadEmpty, sectionLabel, sectionsIn, type ZRead } from '../core/zread.ts'
import type { CaptureConfidence, CaptureSource } from '../core/types.ts'
import { scanZReadBatch, type PhotoOutcome } from '../ocr/scanZRead.ts'
import { IconCamera, IconReceipt, IconTickSmall } from './icons.tsx'

export interface RollState {
  zRead?: ZRead
  photos: Blob[]
  photoOutcomes: PhotoOutcome[]
  scanning: boolean
  progress?: { done: number; total: number }
  error: string
  notes: string
  confidence?: CaptureConfidence
  /** Used when the roll was not scanned — she just types the session total. */
  totalText: string
  source: CaptureSource
  edited: boolean
}

export function emptyRoll(): RollState {
  return {
    photos: [],
    photoOutcomes: [],
    scanning: false,
    error: '',
    notes: '',
    totalText: '',
    source: 'manual',
    edited: false,
  }
}

/** The night's takings, whichever way they were captured. */
export function rollTotalPence(roll: RollState): number | null {
  const typed = parsePence(roll.totalText)
  if (typed !== null) return typed
  const z = roll.zRead
  return z?.deptTotal?.pence ?? z?.transaction.paidTotalPence ?? null
}

/** What the roll should eventually contain, so a gap can be pointed at. */
const WANTED = ['departments', 'totals'] as const

interface Props {
  value: RollState
  onChange: (next: RollState) => void
  onReview: () => void
  /** Where this sits in the nightly walk down the page. */
  step?: number
  done?: boolean
}

export function TillRollCard({ value, onChange, onReview, step, done }: Props) {
  const pickRef = useRef<HTMLInputElement>(null)
  const cameraRef = useRef<HTMLInputElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const [dragging, setDragging] = useState(false)
  const [showChecks, setShowChecks] = useState(false)
  const [previews, setPreviews] = useState<string[]>([])

  useEffect(() => () => abortRef.current?.abort(), [])

  useEffect(() => {
    const urls = value.photos.map((p) => URL.createObjectURL(p))
    setPreviews(urls)
    // Revoked on replacement and unmount; a night of retaken photographs would
    // otherwise hold every one of them in memory.
    return () => urls.forEach((u) => URL.revokeObjectURL(u))
  }, [value.photos])

  const z = value.zRead
  const captured = !isZReadEmpty(z)
  const verdict = captured && z ? crossfootVerdict(z) : null
  const have = z ? sectionsIn(z) : []
  const missing = WANTED.filter((w) => !have.includes(w))

  // Every photograph failing for the same reason is one problem, not several.
  const errors = value.photoOutcomes.map((p) => p.error).filter((e): e is string => !!e)
  const sharedError =
    errors.length > 1 && errors.length === value.photoOutcomes.length && new Set(errors).size === 1

  async function addFiles(files: File[]) {
    if (files.length === 0) return
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    const photos = [...value.photos, ...files]
    let working: RollState = {
      ...value,
      photos,
      scanning: true,
      error: '',
      progress: { done: 0, total: files.length },
    }
    onChange(working)

    try {
      const result = await scanZReadBatch({
        files,
        signal: controller.signal,
        ...(value.zRead ? { existing: value.zRead } : {}),
        onProgress: (done, total) => {
          working = { ...working, progress: { done, total } }
          onChange(working)
        },
      })
      if (controller.signal.aborted) return

      const failures = result.photos.filter((p) => p.error)
      const blanks = result.photos.filter((p) => !p.error && p.sections.length === 0)
      const engineNotes = result.photos.map((p) => p.notes).filter((n): n is string => !!n && n.length > 0)

      // Whether anything was actually read decides what may be thrown away. A
      // scan that failed must not take the figure she had already typed with
      // it — losing her work because the camera did not help is the worst
      // possible outcome of pressing a button marked "add photos".
      const gotSomething = !isZReadEmpty(result.zRead)

      onChange({
        ...value,
        photos,
        // Numbered from the start of the roll, not of this batch, so the list
        // reads as one roll however many goes it took.
        photoOutcomes: [
          ...value.photoOutcomes,
          ...result.photos.map((p) => ({ ...p, index: value.photoOutcomes.length + p.index })),
        ],
        scanning: false,
        progress: undefined,
        error: failures.length === files.length ? (failures[0]?.error ?? 'Nothing could be read.') : '',
        zRead: result.zRead,
        confidence: result.photos.find((p) => p.confidence)?.confidence,
        notes: [
          blanks.length ? `${blanks.length} photograph${blanks.length > 1 ? 's' : ''} read nothing — retake ${blanks.length > 1 ? 'them' : 'it'} or add the missing part.` : '',
          ...engineNotes,
        ]
          .filter(Boolean)
          .join(' '),
        totalText: gotSomething ? '' : value.totalText,
        source: gotSomething ? 'vision' : value.source,
      })
    } catch (err) {
      if (controller.signal.aborted) return
      onChange({
        ...value,
        photos,
        scanning: false,
        progress: undefined,
        error: err instanceof Error ? err.message : 'Could not read those photographs.',
      })
    }
  }

  const fromInput = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = [...(e.target.files ?? [])]
    e.target.value = ''
    void addFiles(files)
  }

  const takings = rollTotalPence(value)
  const t = z?.transaction

  return (
    <section className="card">
      <div className="card-head">
        {step !== undefined && (
          <span className={`step-dot${done ? ' done' : ''}`} aria-hidden="true">
            {done ? <IconTickSmall size={13} /> : step}
          </span>
        )}
        <h2>Till roll</h2>
        <span className="hint">Z read</span>
      </div>

      {/* --- the drop area ------------------------------------------------- */}
      <div
        className={`dropzone${dragging ? ' over' : ''}${value.scanning ? ' busy' : ''}`}
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault()
          setDragging(false)
          void addFiles([...e.dataTransfer.files].filter((f) => f.type.startsWith('image/')))
        }}
        onClick={() => !value.scanning && pickRef.current?.click()}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            pickRef.current?.click()
          }
        }}
        aria-label="Add photographs of the till roll"
      >
        {value.scanning ? (
          <>
            <span className="spinner" />
            <strong>
              Reading {value.progress ? `${value.progress.done} of ${value.progress.total}` : ''}…
            </strong>
            <span className="dz-hint">The whole roll at once — this takes a few seconds.</span>
          </>
        ) : (
          <>
            <span className="dz-glyph" aria-hidden="true"><IconReceipt size={30} strokeWidth={1.5} /></span>
            <strong>{value.photos.length ? 'Add more of the roll' : 'Add the till roll'}</strong>
            <span className="dz-hint">
              Tap to pick every photo at once — the roll takes two or three. It works out which is which.
            </span>
          </>
        )}
      </div>

      <div className="alts">
        <button type="button" className="btn-small" onClick={() => cameraRef.current?.click()} disabled={value.scanning}>
          <IconCamera size={17} /> Use the camera
        </button>
        {value.photos.length > 0 && (
          <button
            type="button"
            className="btn-small"
            onClick={() => onChange({ ...emptyRoll() })}
            disabled={value.scanning}
          >
            Start the roll again
          </button>
        )}
      </div>

      {/* Multiple, and deliberately without `capture` — that attribute forces a
          single camera shot and hides the photo library, which is exactly where
          the three pictures of the roll already are. */}
      <input
        ref={pickRef}
        type="file"
        accept="image/*"
        multiple
        className="visually-hidden"
        data-testid="file-roll"
        onChange={fromInput}
      />
      <input
        ref={cameraRef}
        type="file"
        accept="image/*"
        capture="environment"
        className="visually-hidden"
        data-testid="file-roll-camera"
        onChange={fromInput}
      />

      {/* --- what each photograph turned out to be -------------------------- */}
      {value.photoOutcomes.length > 0 && (
        <ul className="shots">
          {value.photoOutcomes.map((p, i) => (
            <li key={p.index}>
              {previews[i] ? <img src={previews[i]} alt="" /> : <span className="shot-blank" aria-hidden="true" />}
              <span className="shot-what">
                {p.error ? (
                  // One shared cause is stated once, underneath, rather than
                  // shouted next to every thumbnail.
                  sharedError ? 'Not read' : <span className="shot-bad">{p.error}</span>
                ) : p.sections.length === 0 ? (
                  <span className="shot-bad">Nothing readable — retake this one</span>
                ) : (
                  p.sections.map(sectionLabel).join(' · ')
                )}
              </span>
            </li>
          ))}
        </ul>
      )}

      {captured && missing.length > 0 && (
        <p className="note warn" role="status">
          Still missing {missing.map(sectionLabel).join(' and ').toLowerCase()} — add the rest of the roll.
        </p>
      )}

      {/* --- what was found ------------------------------------------------- */}
      {captured ? (
        <>
          <div className="zrow">
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
        !value.scanning && (
          <>
            <p className="note">Or skip the photographs and type the session total:</p>
            <div className="figure">
              <MoneyInput
                id="figure-till"
                label="Till roll total"
                value={value.totalText}
                onChange={(text) => onChange({ ...value, totalText: text, edited: value.source !== 'manual' })}
              />
            </div>
          </>
        )
      )}

      {value.error && <p className="note bad" role="status">{value.error}</p>}
      {value.notes && !value.error && <p className="note warn" role="status">{value.notes}</p>}
      {verdict && <CrossfootSummary verdict={verdict} />}
      {verdict && !verdict.clean && <CrossfootList verdict={verdict} />}

      {captured && (
        <>
          <div className="alts">
            <button type="button" className="btn-small" onClick={onReview}>Check every figure</button>
            {verdict && verdict.clean && (
              <button type="button" className="btn-small" onClick={() => setShowChecks((v) => !v)}>
                {showChecks ? 'Hide the sums' : `Show the ${verdict.checks.length} sums that agree`}
              </button>
            )}
          </div>
          {showChecks && verdict && <CrossfootList verdict={verdict} showPassing />}
        </>
      )}
    </section>
  )
}
