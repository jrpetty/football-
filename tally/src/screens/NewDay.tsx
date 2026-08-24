// ---------------------------------------------------------------------------
// Tonight's count.
//
// The brief describes this as a sequence of steps, and it is — but it is laid
// out as one scrolling page rather than a wizard. Standing at a bar you want
// every figure visible at once: the running verdict updates as each one lands,
// a misread is corrected without paging backwards, and there is no state in
// which the app is holding a number she cannot see. The order down the page is
// the order in the brief.
// ---------------------------------------------------------------------------

import { useEffect, useMemo, useRef, useState } from 'react'
import { FigureCard, emptyFigure, type FigureState } from '../components/FigureCard.tsx'
import { MoneyInput } from '../components/MoneyInput.tsx'
import { VerdictPanel } from '../components/Verdict.tsx'
import { formatLong, isAfterMidnightForTradingDay, tradingDayKey } from '../core/date.ts'
import { parsePence, penceToInput } from '../core/money.ts'
import { reconcile } from '../core/reconcile.ts'
import type { Capture, DayRecord } from '../core/types.ts'
import { emptyDay } from '../core/types.ts'
import { getDay, getPhoto, saveDay, savePhoto } from '../storage/db.ts'
import { loadSettings } from '../storage/settings.ts'
import { makeThumbnail } from '../ocr/index.ts'

function figureFromCapture(capture: Capture, photo?: Blob): FigureState {
  const text = penceToInput(capture.pence)
  return {
    ...emptyFigure(),
    text,
    source: capture.source,
    edited: capture.edited,
    confidence: capture.confidence,
    notes: capture.notes ?? '',
    scannedText: capture.source === 'manual' ? undefined : text,
    photo,
  }
}

async function captureFromFigure(figure: FigureState, keepPhotos: boolean, existingPhotoId?: string): Promise<Capture> {
  const capture: Capture = {
    pence: parsePence(figure.text),
    source: figure.source,
    edited: figure.edited,
  }
  if (figure.confidence) capture.confidence = figure.confidence
  if (figure.notes) capture.notes = figure.notes

  if (keepPhotos && figure.photo) {
    // A shrunk copy: the original off a modern phone is several megabytes, and
    // a year of them at that size is more than a browser will hold.
    const thumb = await makeThumbnail(figure.photo)
    capture.photoId = await savePhoto(thumb)
  } else if (existingPhotoId && keepPhotos) {
    capture.photoId = existingPhotoId
  }
  return capture
}

interface Props {
  onSaved: (date: string) => void
  initialDate?: string
}

export function NewDay({ onSaved, initialDate }: Props) {
  const settings = useMemo(() => loadSettings(), [])
  const [date, setDate] = useState(initialDate ?? tradingDayKey())
  const [till, setTill] = useState<FigureState>(emptyFigure)
  const [card, setCard] = useState<FigureState>(emptyFigure)
  const [cashText, setCashText] = useState('')
  const [note, setNote] = useState('')
  const [existing, setExisting] = useState<DayRecord | null>(null)
  const [saving, setSaving] = useState(false)
  const [toast, setToast] = useState('')
  const lateNight = useRef(isAfterMidnightForTradingDay()).current

  // Re-opening a night edits it rather than starting a second copy of it, so
  // correcting yesterday is the same gesture as entering tonight.
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const found = await getDay(date).catch(() => undefined)
      if (cancelled) return
      setExisting(found ?? null)
      if (!found) {
        setTill(emptyFigure())
        setCard(emptyFigure())
        setCashText('')
        setNote('')
        return
      }
      const [tillPhoto, cardPhoto] = await Promise.all([
        found.till.photoId ? getPhoto(found.till.photoId).catch(() => undefined) : undefined,
        found.card.photoId ? getPhoto(found.card.photoId).catch(() => undefined) : undefined,
      ])
      if (cancelled) return
      setTill(figureFromCapture(found.till, tillPhoto))
      setCard(figureFromCapture(found.card, cardPhoto))
      setCashText(penceToInput(found.cashPence))
      setNote(found.note)
    })()
    return () => {
      cancelled = true
    }
  }, [date])

  const r = reconcile({
    tillPence: parsePence(till.text),
    cardPence: parsePence(card.text),
    cashPence: parsePence(cashText),
    tolerancePence: settings.tolerancePence,
  })

  const busy = till.scanning || card.scanning

  async function save() {
    setSaving(true)
    try {
      const base = existing ?? emptyDay(date)
      const record: DayRecord = {
        ...base,
        date,
        till: await captureFromFigure(till, settings.keepPhotos, existing?.till.photoId),
        card: await captureFromFigure(card, settings.keepPhotos, existing?.card.photoId),
        cashPence: parsePence(cashText),
        note,
        updatedAt: Date.now(),
      }
      await saveDay(record)
      onSaved(date)
    } catch (err) {
      setSaving(false)
      setToast(err instanceof Error ? `Could not save: ${err.message}` : 'Could not save that night.')
      setTimeout(() => setToast(''), 6000)
    }
  }

  return (
    <>
      <div className="main with-bar">
        <section className="card">
          <div className="card-head">
            <h2>{formatLong(date)}</h2>
            {existing && <span className="badge">Already saved</span>}
          </div>
          <div className="field" style={{ marginBottom: 0 }}>
            <label htmlFor="date">Trading day</label>
            <input id="date" type="date" value={date} onChange={(e) => e.target.value && setDate(e.target.value)} />
            {lateNight && !initialDate && (
              <p className="help">
                It is past midnight, so this has defaulted to last night's trade — the session you have just
                closed. Change it above if that is not right.
              </p>
            )}
          </div>
        </section>

        <FigureCard
          title="Till roll total"
          hint="Z read"
          kind="till"
          value={till}
          onChange={setTill}
        />

        <FigureCard
          title="Card total"
          hint="End-of-day slip"
          kind="card"
          value={card}
          onChange={setCard}
        />

        <section className="card">
          <div className="card-head">
            <h2>Cash counted</h2>
            <span className="hint">From the drawer</span>
          </div>
          <div className="figure">
            <MoneyInput id="figure-cash" label="Cash counted" value={cashText} onChange={setCashText} />
          </div>
          <p className="note">The one figure with no receipt behind it — count the drawer and type it in.</p>
        </section>

        <section className="card">
          <div className="field" style={{ marginBottom: 0 }}>
            <label htmlFor="note">Note (optional)</label>
            <textarea
              id="note"
              value={note}
              placeholder="Anything worth remembering about tonight"
              onChange={(e) => setNote(e.target.value)}
            />
          </div>
        </section>
      </div>

      <div className="verdict-bar">
        <div className="inner">
          <VerdictPanel r={r} />
          <button
            type="button"
            className="btn-primary"
            onClick={() => void save()}
            disabled={saving || busy}
          >
            {saving ? 'Saving…' : busy ? 'Reading the photograph…' : existing ? 'Update this night' : 'Save this night'}
          </button>
        </div>
      </div>

      {toast && <div className="toast" role="alert">{toast}</div>}
    </>
  )
}
