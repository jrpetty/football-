// ---------------------------------------------------------------------------
// Capturing the till roll.
//
// This is now the whole of the nightly job: photograph the receipt, let it be
// read, check the verdict. So the card is built around the photographs rather
// than around the scan.
//
// Three things follow from that. The roll is longer than a phone's camera
// frame — the reference one took three photographs — so they are taken all at
// once, read separately, and folded into one read. Each photograph can be
// thrown away and retaken on its own, and what is left re-folds from the reads
// already in hand: one blurred picture out of three costs one picture, not
// three, and is not paid for twice. And a photograph is kept whether or not
// anything could read it — no key, no signal, scanning switched off — because
// the picture of the receipt is the record. It can be read later, in a tap.
//
// There is always a way through without scanning. The total can simply be
// typed, and the night is still a complete record. The department detail is
// what makes the dashboard possible, not what makes the night valid.
// ---------------------------------------------------------------------------

import { useEffect, useRef, useState } from 'react'
import { MoneyInput } from './MoneyInput.tsx'
import { CrossfootSummary, CrossfootList } from './CrossfootPanel.tsx'
import { crossfootVerdict } from '../core/crossfoot.ts'
import { formatMoney, parsePence } from '../core/money.ts'
import { isZReadEmpty, sectionLabel, sectionsIn, type ZRead } from '../core/zread.ts'
import type { CaptureConfidence, CaptureSource } from '../core/types.ts'
import { foldRolls, scanZReadBatch, type PhotoOutcome, type Roll } from '../ocr/scanZRead.ts'
import { effectiveEngine, hasApiKey, loadSettings, type EnginePreference } from '../storage/settings.ts'
import { IconCamera, IconReceipt, IconTickSmall, IconTrash } from './icons.tsx'
import { Lightbox } from './Lightbox.tsx'

/**
 * One photograph of the roll.
 *
 * One list rather than "the saved ones" and "tonight's": a picture taken at
 * closing with no signal and read the next morning has to be able to cross
 * that line without becoming a different kind of thing. So every shot carries
 * its image, where it is stored if it has been saved, and what it read.
 */
export interface RollShot {
  blob: Blob
  /** Where it already lives in the database, once the night has been saved. */
  id?: string
  /** What this one photograph read — or that nothing has read it yet. */
  outcome: PhotoOutcome
}

export interface RollState {
  zRead?: ZRead
  /**
   * What had been read before this sitting's photographs — a night reopened,
   * or figures corrected by hand. Everything else folds on top of it, so
   * dropping a photograph never takes saved or corrected figures with it.
   */
  base?: ZRead
  /** Every photograph of the roll, in the order they go together. */
  shots: RollShot[]
  scanning: boolean
  progress?: { done: number; total: number }
  error: string
  notes: string
  confidence?: CaptureConfidence
  /**
   * Whether the photographs are pieces of one receipt or separate ones.
   *
   * 'auto' tells them apart by the Z counter, which increments once per Z
   * read. The other two are for a till whose header never comes out legible,
   * or a roll photographed so the header appears twice.
   */
  how: 'auto' | 'separate' | 'together'
  /** Used when the roll was not scanned — she just types the session total. */
  totalText: string
  source: CaptureSource
  edited: boolean
}

export function emptyRoll(): RollState {
  return {
    shots: [],
    how: 'auto',
    scanning: false,
    error: '',
    notes: '',
    totalText: '',
    source: 'manual',
    edited: false,
  }
}

/** Renumber so a shot's outcome always states where it sits in the roll. */
function renumber(shots: readonly RollShot[]): RollShot[] {
  return shots.map((shot, index) => ({ ...shot, outcome: { ...shot.outcome, index } }))
}

/** A photograph just added: kept, and waiting for something to read it. */
export function newShot(blob: Blob, index: number): RollShot {
  return { blob, outcome: { index, sections: [], unread: true } }
}

/** A photograph already stored against this night, and already read. */
export function savedShot(id: string, blob: Blob, index: number, read: boolean): RollShot {
  return { id, blob, outcome: { index, sections: [], ...(read ? {} : { unread: true }) } }
}

/** The night's takings, whichever way they were captured. */
export function rollTotalPence(roll: RollState): number | null {
  const typed = parsePence(roll.totalText)
  if (typed !== null) return typed
  const z = roll.zRead
  return z?.deptTotal?.pence ?? z?.transaction.paidTotalPence ?? null
}

/** Photographs taken but not yet read — because there was no key, or no signal. */
export function unreadShots(roll: RollState): number[] {
  return roll.shots.flatMap((shot, i) => (shot.outcome.unread ? [i] : []))
}

const RANK: Record<CaptureConfidence, number> = { low: 0, medium: 1, high: 2 }

/** The least confident reading of the lot — a warning is only useful at its worst. */
function worstConfidence(outcomes: readonly PhotoOutcome[]): CaptureConfidence | undefined {
  let worst: CaptureConfidence | undefined
  for (const o of outcomes) {
    if (!o.confidence) continue
    if (!worst || RANK[o.confidence] < RANK[worst]) worst = o.confidence
  }
  return worst
}

/** What the roll should eventually contain, so a gap can be pointed at. */
const WANTED = ['departments', 'totals'] as const

/** Whether the reader can run at all, and why not when it cannot. */
interface Readiness {
  preference: EnginePreference
  engine: EnginePreference
  keyed: boolean
}

function readiness(): Readiness {
  const s = loadSettings()
  return { preference: s.engine, engine: effectiveEngine(s), keyed: hasApiKey(s) }
}

function preflightWords(r: Readiness): string | null {
  if (r.engine !== 'off') return null
  if (r.preference === 'off') {
    return 'Scanning is switched off in Settings, so photographs are kept but not read. Type the session total below — the night is still a complete record.'
  }
  if (!r.keyed) {
    return 'No API key yet, so nothing can read the roll. Photograph it anyway — the pictures are kept with the night, and one tap reads them once the key is in Settings.'
  }
  return 'No signal, so the roll cannot be read this minute. Photograph it anyway — the pictures are kept with the night and can be read the moment you are back on wifi.'
}

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
  const [viewing, setViewing] = useState<number | null>(null)
  const [ready, setReady] = useState<Readiness>(readiness)

  useEffect(() => () => abortRef.current?.abort(), [])

  // Whether the roll can be read is not fixed for the evening: the wifi drops,
  // or she goes to Settings, pastes the key and comes back. Re-checked on the
  // events that actually change it rather than on every keystroke.
  useEffect(() => {
    const refresh = () => setReady(readiness())
    refresh()
    const visible = () => document.visibilityState === 'visible' && refresh()
    window.addEventListener('online', refresh)
    window.addEventListener('offline', refresh)
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', visible)
    return () => {
      window.removeEventListener('online', refresh)
      window.removeEventListener('offline', refresh)
      window.removeEventListener('focus', refresh)
      document.removeEventListener('visibilitychange', visible)
    }
  }, [])

  const shots = value.shots

  useEffect(() => {
    const urls = shots.map((shot) => URL.createObjectURL(shot.blob))
    setPreviews(urls)
    // Revoked on replacement and unmount; a night of retaken photographs would
    // otherwise hold every one of them in memory.
    return () => urls.forEach((u) => URL.revokeObjectURL(u))
  }, [shots])

  const z = value.zRead
  const captured = !isZReadEmpty(z)
  const verdict = captured && z ? crossfootVerdict(z) : null
  const have = z ? sectionsIn(z) : []
  const missing = WANTED.filter((w) => !have.includes(w))
  const unread = unreadShots(value)
  const preflight = preflightWords(ready)

  /**
   * How the photographs were sorted into receipts.
   *
   * Said out loud because it is the one decision here that can be wrong by a
   * whole night's takings: three photographs of one roll added together would
   * treble the day, and two separate receipts merged would lose one of them.
   */
  const grouped: Roll[] = foldRolls(
    shots.map((shot) => shot.outcome),
    { how: value.how, ...(value.base ? { base: value.base } : {}) },
  ).rolls
  const setHow = (how: RollState['how']) => {
    const outcomes = shots.map((shot) => shot.outcome)
    const merged = foldRolls(outcomes, { how, ...(value.base ? { base: value.base } : {}) }).zRead
    const gotSomething = !isZReadEmpty(merged)
    const next: RollState = { ...value, how }
    if (gotSomething) next.zRead = merged
    onChange(next)
  }

  // Every photograph failing for the same reason is one problem, not several.
  const errors = shots.map((shot) => shot.outcome.error).filter((e): e is string => !!e)
  const sharedError = errors.length > 1 && errors.length === shots.length && new Set(errors).size === 1

  /**
   * Read some of the photographs already in hand and fold them into the roll.
   *
   * Takes the indexes rather than the files, so a result always lands back on
   * the photograph it came from. That is what lets one bad picture be retaken,
   * or a whole roll be read later when the signal comes back, without
   * disturbing anything already read.
   */
  async function scanShots(from: RollState, indices: number[]) {
    if (indices.length === 0) return
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    let working: RollState = {
      ...from,
      scanning: true,
      error: '',
      progress: { done: 0, total: indices.length },
    }
    onChange(working)

    try {
      const result = await scanZReadBatch({
        files: indices.map((i) => from.shots[i]!.blob),
        signal: controller.signal,
        onProgress: (doneSoFar, total) => {
          working = { ...working, progress: { done: doneSoFar, total } }
          onChange(working)
        },
      })
      if (controller.signal.aborted) return

      const next = [...from.shots]
      result.photos.forEach((p, n) => {
        const at = indices[n]!
        next[at] = { ...next[at]!, outcome: { ...p, index: at } }
      })
      const outcomes = next.map((shot) => shot.outcome)

      const merged = foldRolls(outcomes, { how: from.how, ...(from.base ? { base: from.base } : {}) }).zRead
      // Whether anything was actually read decides what may be thrown away. A
      // scan that failed must not take the figure she had already typed with
      // it — losing her work because the camera did not help is the worst
      // possible outcome of pressing a button marked "add photos".
      const gotSomething = !isZReadEmpty(merged)

      const failures = result.photos.filter((p) => p.error)
      const blanks = result.photos.filter((p) => !p.error && p.sections.length === 0)
      const engineNotes = result.photos.map((p) => p.notes).filter((n): n is string => !!n && n.length > 0)

      onChange({
        ...from,
        shots: next,
        scanning: false,
        progress: undefined,
        error: failures.length === indices.length ? (failures[0]?.error ?? 'Nothing could be read.') : '',
        ...(gotSomething ? { zRead: merged } : {}),
        confidence: worstConfidence(outcomes),
        notes: [
          blanks.length
            ? `${blanks.length} photograph${blanks.length > 1 ? 's' : ''} read nothing — retake ${blanks.length > 1 ? 'them' : 'it'} or add the missing part.`
            : '',
          ...engineNotes,
        ]
          .filter(Boolean)
          .join(' '),
        totalText: gotSomething ? '' : from.totalText,
        source: gotSomething ? 'vision' : from.source,
      })
    } catch (err) {
      if (controller.signal.aborted) return
      const message = err instanceof Error ? err.message : 'Could not read those photographs.'
      const next = [...from.shots]
      for (const at of indices) next[at] = { ...next[at]!, outcome: { index: at, sections: [], error: message } }
      onChange({ ...from, shots: next, scanning: false, progress: undefined, error: message })
    }
  }

  async function addFiles(files: File[]) {
    if (files.length === 0) return
    const at = shots.length
    // Kept first, read second — deliberately in that order. The photograph is
    // the record; reading it is a convenience on top.
    const next: RollState = {
      ...value,
      shots: [...shots, ...files.map((f, i) => newShot(f, at + i))],
      error: '',
    }
    const fresh = readiness()
    setReady(fresh)
    if (fresh.engine === 'off') {
      onChange(next)
      return
    }
    await scanShots(next, files.map((_, i) => at + i))
  }

  /** Throw one photograph away and re-fold the roll from what is left. */
  function removeShot(at: number) {
    const next = renumber(shots.filter((_, i) => i !== at))
    const outcomes = next.map((shot) => shot.outcome)
    const merged = foldRolls(outcomes, { how: value.how, ...(value.base ? { base: value.base } : {}) }).zRead
    const gotSomething = !isZReadEmpty(merged)
    const nextRoll: RollState = {
      ...value,
      shots: next,
      error: '',
      notes: '',
      confidence: worstConfidence(outcomes),
      source: gotSomething ? value.source : 'manual',
    }
    if (gotSomething) nextRoll.zRead = merged
    else delete nextRoll.zRead
    onChange(nextRoll)
  }

  const fromInput = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = [...(e.target.files ?? [])]
    e.target.value = ''
    void addFiles(files)
  }

  const takings = rollTotalPence(value)
  const t = z?.transaction
  const total = shots.length
  const saved = shots.filter((shot) => shot.id).length

  return (
    <section className="card">
      <div className="card-head">
        {step !== undefined && (
          <span className={`step-dot${done ? ' done' : ''}`} aria-hidden="true">
            {done ? <IconTickSmall size={13} /> : step}
          </span>
        )}
        <h2>Till roll</h2>
        <span className="hint">
          {total === 0 ? 'Z read' : `${total} photograph${total > 1 ? 's' : ''}`}
        </span>
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
            <strong>{total ? 'Add more of the roll' : 'Add the till roll'}</strong>
            <span className="dz-hint">
              Tap to pick every photo at once — the roll takes two or three. It works out which is which.
            </span>
          </>
        )}
      </div>

      <div className="alts">
        <button type="button" className="btn-small" onClick={() => cameraRef.current?.click()} disabled={value.scanning}>
          <IconCamera size={17} /> {total ? 'Take another' : 'Use the camera'}
        </button>
        {unread.length > 0 && ready.engine !== 'off' && (
          <button
            type="button"
            className="btn-small"
            data-testid="read-kept"
            onClick={() => void scanShots(value, unread)}
            disabled={value.scanning}
          >
            Read {unread.length === 1 ? 'that photograph' : `those ${unread.length} photographs`}
          </button>
        )}
        {total > 0 && (
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

      {preflight && (
        <p className="note warn" role="status" data-testid="roll-preflight">
          {preflight}
        </p>
      )}

      {/* --- what each photograph turned out to be -------------------------- */}
      {total > 0 && (
        <ul className="shots">
          {shots.map((shot, i) => {
            const o = shot.outcome
            return (
              <li key={shot.id ?? `shot:${i}`}>
                {previews[i] ? (
                  <button
                    type="button"
                    className="shot-open"
                    onClick={() => setViewing(i)}
                    aria-label={`Open photograph ${i + 1} full screen`}
                  >
                    <img src={previews[i]} alt="" />
                  </button>
                ) : (
                  <span className="shot-blank" aria-hidden="true" />
                )}
                <span className="shot-what">
                  {o.error ? (
                    // One shared cause is stated once, underneath, rather than
                    // shouted next to every thumbnail.
                    sharedError ? 'Not read' : <span className="shot-bad">{o.error}</span>
                  ) : o.unread ? (
                    'Kept — not read yet'
                  ) : o.sections.length > 0 ? (
                    o.sections.map(sectionLabel).join(' · ')
                  ) : shot.id ? (
                    'Saved with this night'
                  ) : (
                    <span className="shot-bad">Nothing readable — retake this one</span>
                  )}
                </span>
                <button
                  type="button"
                  className="shot-drop"
                  data-testid={`drop-shot-${i}`}
                  onClick={() => removeShot(i)}
                  disabled={value.scanning}
                  aria-label={`Throw away photograph ${i + 1}`}
                  title="Throw this photograph away and keep the rest"
                >
                  <IconTrash size={15} />
                </button>
              </li>
            )
          })}
        </ul>
      )}

      {saved > 0 && (
        <p className="note">
          Throwing away a photograph saved earlier removes the picture. Figures already read off it
          stay until the roll is started again.
        </p>
      )}

      {/* What it made of them: one receipt or several, and what they came to.
          A day can be two tills or a lunchtime and an evening, and those add;
          pieces of one roll do not. */}
      {grouped.length > 1 && (
        <div className="rolls" data-testid="rolls">
          <p className="note" style={{ marginTop: 0 }}>
            <strong>{grouped.length} separate receipts, added together.</strong>{' '}
            {grouped
              .map((r, i) => `${r.zNumber !== undefined ? `Z ${r.zNumber}` : `receipt ${i + 1}`} (${r.photos.length} ${r.photos.length === 1 ? 'photo' : 'photos'})`)
              .join(', ')}
            . Their takings, card and cash are all added.
          </p>
          <div className="alts" style={{ marginTop: 0 }}>
            <button type="button" className="btn-small" data-testid="one-roll" onClick={() => setHow('together')}>
              No — these are one receipt
            </button>
          </div>
        </div>
      )}
      {grouped.length === 1 && shots.length > 1 && (
        <div className="rolls">
          <p className="note" style={{ marginTop: 0 }}>
            {value.how === 'together'
              ? 'Treated as one receipt, so a section photographed twice counts once.'
              : `${shots.length} photographs of one receipt${grouped[0]?.zNumber !== undefined ? ` — Z ${grouped[0].zNumber}` : ''}, so nothing is counted twice.`}{' '}
            If they are separate receipts from the same day, say so and their figures will add.
          </p>
          <div className="alts" style={{ marginTop: 0 }}>
            <button type="button" className="btn-small" data-testid="separate-rolls" onClick={() => setHow('separate')}>
              These are separate receipts
            </button>
            {value.how !== 'auto' && (
              <button type="button" className="btn-small" onClick={() => setHow('auto')}>
                Work it out again
              </button>
            )}
          </div>
        </div>
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
            <p className="note">
              {total > 0
                ? 'Nothing read off the roll yet — type the session total and the night still stands:'
                : 'Or skip the photographs and type the session total:'}
            </p>
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

      {viewing !== null && previews.length > 0 && (
        <Lightbox
          photos={previews.map((url, i) => ({ url, label: `Photograph ${i + 1} of the roll` }))}
          initial={viewing}
          onClose={() => setViewing(null)}
        />
      )}
    </section>
  )
}
