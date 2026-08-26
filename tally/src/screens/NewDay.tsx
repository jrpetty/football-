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
import { CashCount } from '../components/CashCount.tsx'
import { splitDrawer, type Tally } from '../core/cash.ts'

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
  /** What was in the drawer, float and all — the figure she actually counts. */
  const [drawerText, setDrawerText] = useState('')
  const [floatText, setFloatText] = useState(() => {
    const standing = loadSettings().standingFloatPence
    return standing > 0 ? penceToInput(standing) : ''
  })
  const [tally, setTally] = useState<Tally>({})
  const [counting, setCounting] = useState(false)
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
        setDrawerText('')
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
      // Stored as takings and float apart; shown as the drawer she counted.
      const floatPence = found.floatPence ?? 0
      setFloatText(floatPence > 0 ? penceToInput(floatPence) : '')
      setDrawerText(found.cashPence === null ? '' : penceToInput(found.cashPence + floatPence))
      setNote(found.note)
    })()
    return () => {
      cancelled = true
    }
  }, [date])

  const zRead = roll.zRead && !isZReadEmpty(roll.zRead) ? roll.zRead : undefined
  const expected = tillExpectations(zRead)

  // The drawer holds the float as well as the takings, and only the takings
  // reconcile. Without this subtraction a £200 float reads as £200 over every
  // night — consistently enough to look like the pub doing well.
  const drawerPence = parsePence(drawerText)
  const floatPence = parsePence(floatText) ?? 0
  const split = drawerPence === null ? null : splitDrawer(drawerPence, floatPence)
  const takingsCashPence = split ? split.takingsPence : null

  const r = reconcileFull({
    tillPence: rollTotalPence(roll),
    cardPence: parsePence(card.text),
    cashPence: takingsCashPence,
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
        cashPence: takingsCashPence,
        note,
        updatedAt: Date.now(),
      }
      if (floatPence > 0) record.floatPence = floatPence
      else delete record.floatPence
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
            <span className={`step-dot${drawerPence !== null ? ' done' : ''}`} aria-hidden="true">
              {drawerPence !== null ? <IconTickSmall size={13} /> : 3}
            </span>
            <h2>Cash counted</h2>
            <span className="hint">
              {expected.cashPence === undefined ? 'From the drawer' : `till says ${formatMoney(expected.cashPence)}`}
            </span>
          </div>

          <div className="figure">
            <MoneyInput
              id="figure-cash"
              label="Cash counted"
              value={drawerText}
              onChange={setDrawerText}
            />
            <button
              type="button"
              className="btn-scan"
              onClick={() => setCounting((v) => !v)}
              aria-label="Count the drawer out in notes and coins"
              aria-expanded={counting}
            >
              <span className="glyph num" aria-hidden="true">£</span>
              <span>{counting ? 'Hide' : 'Count'}</span>
            </button>
          </div>

          {counting && (
            <CashCount
              tally={tally}
              onChange={setTally}
              onUse={(pence) => {
                setDrawerText(penceToInput(pence))
                setCounting(false)
              }}
              onClose={() => setCounting(false)}
            />
          )}

          <div className="field" style={{ marginTop: 14, marginBottom: 0 }}>
            <label htmlFor="figure-float">Float left in the drawer</label>
            <div className="figure">
              <MoneyInput
                id="figure-float"
                label="Float left in the drawer"
                value={floatText}
                onChange={setFloatText}
                placeholder="0.00"
              />
            </div>
          </div>

          {split && split.floatPence > 0 && (
            <div className="zrow" style={{ marginTop: 10 }}>
              <span className="zname">
                Takings in the drawer
                <small>
                  {formatMoney(split.drawerPence)} counted, less {formatMoney(split.floatPence)} float
                </small>
              </span>
              <strong className="num">{formatMoney(split.takingsPence)}</strong>
            </div>
          )}

          {split?.impossible ? (
            <p className="note bad" role="status">
              The float is more than was counted in the drawer, so one of the two is wrong. Nothing
              will reconcile until they agree.
            </p>
          ) : (
            <p className="note">
              Count the whole drawer, float and all, and put the float in the second box — it is not
              takings, so it comes off before anything is compared with the till.
            </p>
          )}
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
