// ---------------------------------------------------------------------------
// Tonight's count.
//
// One scrolling page rather than a wizard. Standing at a bar you want every
// figure visible at once: the verdict updates as each lands, a misread is
// corrected without paging backwards, and there is no state in which the app is
// holding a number she cannot see. The order down the page is the order of the
// job — roll, card machine, drawer.
//
// Since the till roll states what the card machine and the drawer should hold,
// the last two are checks against a stated figure rather than raw inputs, and
// the verdict can say which side the difference is on.
// ---------------------------------------------------------------------------

import { useEffect, useMemo, useRef, useState } from 'react'
import { FigureCard, emptyFigure, type FigureState } from '../components/FigureCard.tsx'
import { MoneyInput } from '../components/MoneyInput.tsx'
import { TillRollCard, emptyRoll, rollTotalPence, type RollState } from '../components/TillRollCard.tsx'
import { ItemisedLegs, VerdictPanel } from '../components/Verdict.tsx'
import { formatLong, isAfterMidnightForTradingDay, tradingDayKey } from '../core/date.ts'
import { formatMoney, parsePence, penceToInput } from '../core/money.ts'
import { reconcileFull, tillExpectations } from '../core/reconcile.ts'
import type { Capture, DayRecord } from '../core/types.ts'
import { emptyDay } from '../core/types.ts'
import { isZReadEmpty, type ZRead } from '../core/zread.ts'
import { getDay, getPhoto, saveDay, savePhoto } from '../storage/db.ts'
import { loadSettings } from '../storage/settings.ts'
import { makeThumbnail } from '../ocr/index.ts'
import { IconTickSmall } from '../components/icons.tsx'

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
    capture.photoId = await savePhoto(await makeThumbnail(figure.photo))
  } else if (existingPhotoId && keepPhotos) {
    capture.photoId = existingPhotoId
  }
  return capture
}

interface Props {
  onSaved: (date: string) => void
  onReviewRoll: (zRead: ZRead, apply: (next: ZRead) => void) => void
  initialDate?: string
}

export function NewDay({ onSaved, onReviewRoll, initialDate }: Props) {
  const settings = useMemo(() => loadSettings(), [])
  const [date, setDate] = useState(initialDate ?? tradingDayKey())
  const [roll, setRoll] = useState<RollState>(emptyRoll)
  const [card, setCard] = useState<FigureState>(emptyFigure)
  const [cashText, setCashText] = useState('')
  const [note, setNote] = useState('')
  const [existing, setExisting] = useState<DayRecord | null>(null)
  const [saving, setSaving] = useState(false)
  const [toast, setToast] = useState('')
  const lateNight = useRef(isAfterMidnightForTradingDay()).current

  // Re-opening a night edits it rather than starting a second copy of it.
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const found = await getDay(date).catch(() => undefined)
      if (cancelled) return
      setExisting(found ?? null)
      if (!found) {
        setRoll(emptyRoll())
        setCard(emptyFigure())
        setCashText('')
        setNote('')
        return
      }
      const cardPhoto = found.card.photoId ? await getPhoto(found.card.photoId).catch(() => undefined) : undefined
      if (cancelled) return
      setRoll({
        ...emptyRoll(),
        ...(found.zRead ? { zRead: found.zRead } : {}),
        totalText: found.zRead ? '' : penceToInput(found.till.pence),
        source: found.till.source,
        edited: found.till.edited,
      })
      setCard(figureFromCapture(found.card, cardPhoto))
      setCashText(penceToInput(found.cashPence))
      setNote(found.note)
    })()
    return () => {
      cancelled = true
    }
  }, [date])

  const zRead = roll.zRead && !isZReadEmpty(roll.zRead) ? roll.zRead : undefined
  const expected = tillExpectations(zRead)

  const r = reconcileFull({
    tillPence: rollTotalPence(roll),
    cardPence: parsePence(card.text),
    cashPence: parsePence(cashText),
    tolerancePence: settings.tolerancePence,
    ...(zRead ? { zRead } : {}),
  })

  const busy = roll.scanning || card.scanning

  async function save() {
    setSaving(true)
    try {
      const base = existing ?? emptyDay(date)
      const photoIds: string[] = []
      if (settings.keepPhotos) {
        for (const photo of roll.photos) photoIds.push(await savePhoto(await makeThumbnail(photo)))
      }

      const record: DayRecord = {
        ...base,
        date,
        till: {
          pence: rollTotalPence(roll),
          source: roll.source,
          edited: roll.edited,
        },
        card: await captureFromFigure(card, settings.keepPhotos, existing?.card.photoId),
        cashPence: parsePence(cashText),
        note,
        updatedAt: Date.now(),
      }
      if (zRead) record.zRead = zRead
      if (photoIds.length) record.zPhotoIds = [...(base.zPhotoIds ?? []), ...photoIds]

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

        <TillRollCard
          value={roll}
          onChange={setRoll}
          onReview={() => {
            if (roll.zRead) onReviewRoll(roll.zRead, (next) => setRoll({ ...roll, zRead: next }))
          }}
          step={1}
          done={rollTotalPence(roll) !== null}
        />

        <FigureCard
          title="Card machine"
          hint={expected.cardPence === undefined ? 'End-of-day slip' : `till says ${formatMoney(expected.cardPence)}`}
          kind="card"
          value={card}
          onChange={setCard}
          step={2}
          done={parsePence(card.text) !== null}
        />

        <section className="card">
          <div className="card-head">
            <span className={`step-dot${parsePence(cashText) !== null ? ' done' : ''}`} aria-hidden="true">
              {parsePence(cashText) !== null ? <IconTickSmall size={13} /> : 3}
            </span>
            <h2>Cash counted</h2>
            <span className="hint">
              {expected.cashPence === undefined ? 'From the drawer' : `till says ${formatMoney(expected.cashPence)}`}
            </span>
          </div>
          <div className="figure">
            <MoneyInput id="figure-cash" label="Cash counted" value={cashText} onChange={setCashText} />
          </div>
          <p className="note">The one figure with no receipt behind it — count the drawer and type it in.</p>
        </section>

        <ItemisedLegs r={r} />

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
          <VerdictPanel r={r.overall} />
          <button type="button" className="btn-primary" onClick={() => void save()} disabled={saving || busy}>
            {saving ? 'Saving…' : busy ? 'Reading the photograph…' : existing ? 'Update this night' : 'Save this night'}
          </button>
        </div>
      </div>

      {toast && <div className="toast" role="alert">{toast}</div>}
    </>
  )
}
