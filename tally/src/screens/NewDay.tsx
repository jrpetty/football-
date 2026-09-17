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
import { formatLong, formatShort, isAfterMidnightForTradingDay, tradingDayKey } from '../core/date.ts'
import { formatMoney, parsePence, penceToInput } from '../core/money.ts'
import { describeMissing, reconcileFull, tillExpectations } from '../core/reconcile.ts'
import { dayStats } from '../core/analytics.ts'
import type { Capture, DayRecord } from '../core/types.ts'
import { emptyDay } from '../core/types.ts'
import { isZReadEmpty, type ZRead } from '../core/zread.ts'
import {
  deleteStockCount,
  getDay,
  listDays,
  listDeliveries,
  listStockCounts,
  getPhoto,
  getStockCount,
  loadStockConfig,
  saveDay,
  savePhoto,
  saveStockConfig,
  saveStockCount,
} from '../storage/db.ts'
import { CountSheet } from '../components/CountSheet.tsx'
import { KegCalculator } from '../components/KegCalculator.tsx'
import {
  draftsFromCount,
  kegReading,
  measureOf,
  nightCellar,
  pourUsage,
  sheetLines,
  withKegWeights,
  type Delivery,
  type KegWeights,
  type Measure,
  type Pour,
  type StockCount,
  type StockItem,
} from '../core/stock.ts'
import { CellarGap } from '../components/CellarGap.tsx'
import { costOf } from '../core/margin.ts'
import { loadSettings } from '../storage/settings.ts'
import { makeThumbnail } from '../ocr/index.ts'
import { IconTickSmall } from '../components/icons.tsx'
import { CashCount } from '../components/CashCount.tsx'
import { splitDrawer, type Tally } from '../core/cash.ts'

/** What each line counted in millilitres pours, so a count reads back in shots. */
function measuresFrom(config: { items: StockItem[]; pours: Pour[] } | null): Map<string, Measure> {
  const out = new Map<string, Measure>()
  for (const item of config?.items ?? []) {
    const m = measureOf(item, config?.pours ?? [])
    if (m) out.set(item.id, m)
  }
  return out
}

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
  // The cellar, counted as the night is closed. Kept as typed until saved.
  const [stockItems, setStockItems] = useState<StockItem[]>([])
  /** The serve each millilitre-counted line pours, so the sheet can say the shots. */
  const [measures, setMeasures] = useState<ReadonlyMap<string, Measure>>(new Map())
  /** Everything the cellar needs to say what should be down there tonight. */
  const [pours, setPours] = useState<Pour[]>([])
  const [counts, setCounts] = useState<StockCount[]>([])
  const [deliveries, setDeliveries] = useState<Delivery[]>([])
  const [soldBefore, setSoldBefore] = useState<Array<{ date: string; items: { code: string; name: string; qtyMilli: number }[] }>>([])
  /** Whether the keg calculator is out above the count sheet. */
  const [weighOpen, setWeighOpen] = useState(false)
  const [cellarDrafts, setCellarDrafts] = useState<Record<string, string>>({})
  const [cellarOpen, setCellarOpen] = useState(false)
  const [hadCount, setHadCount] = useState(false)
  const [existing, setExisting] = useState<DayRecord | null>(null)
  /**
   * A night saved earlier with the receipt still to come.
   *
   * The cellar gets counted as the doors are locked and the roll is read the
   * next morning, so the two halves of a night are often hours apart. Rather
   * than trusting her to notice that the date has rolled over, the night that
   * is still waiting says so and offers itself back.
   */
  const [unfinished, setUnfinished] = useState<{ date: string; missing: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const [toast, setToast] = useState('')
  const lateNight = useRef(isAfterMidnightForTradingDay()).current

  // The cellar's lines, and the night's own count if one was taken — shown as
  // it was counted, so a correction starts from the sheet rather than from
  // nothing. Loaded together, on the date alone: the lines can change while
  // the sheet is open (weights kept from the keg calculator), and that must
  // not reload the count over what has been typed since.
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const cfg = await loadStockConfig().catch(() => null)
      const count = await getStockCount(date).catch(() => undefined)
      if (cancelled) return
      const items = cfg?.items ?? []
      setStockItems(items)
      setMeasures(measuresFrom(cfg))
      setPours(cfg?.pours ?? [])
      const [savedCounts, savedDeliveries, savedDays] = await Promise.all([
        listStockCounts().catch(() => []),
        listDeliveries().catch(() => []),
        listDays().catch(() => []),
      ])
      if (cancelled) return
      setCounts(savedCounts)
      setDeliveries(savedDeliveries)
      setSoldBefore(savedDays.map((d) => ({ date: d.date, items: dayStats(d, settings.tolerancePence).items })))
      setHadCount(!!count)
      setCellarDrafts(count && items.length ? draftsFromCount(count, items) : {})
      setCellarOpen(!!count)
    })()
    return () => {
      cancelled = true
    }
  }, [date])

  // Anything saved but not finished, so it can be offered back rather than
  // quietly waiting in the Nights list for somebody to remember it.
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const saved = await listDays().catch(() => [])
      if (cancelled) return
      const waiting = saved
        .map((d) => ({ day: d, stats: dayStats(d, settings.tolerancePence) }))
        .find(({ day, stats }) => stats.takingsPence === null && day.date !== date)
      setUnfinished(
        waiting
          ? {
              date: waiting.day.date,
              missing: describeMissing(
                reconcileFull({
                  tillPence: waiting.day.till.pence,
                  cardPence: waiting.day.card.pence,
                  cashPence: waiting.day.cashPence,
                  tolerancePence: settings.tolerancePence,
                }).overall.missing,
              ),
            }
          : null,
      )
    })()
    return () => {
      cancelled = true
    }
  }, [date, settings.tolerancePence, saving])

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
  /**
   * Weights from the keg calculator, kept against the line so its row grows a
   * box for the scales — and the reading that was on them at the time goes
   * straight into that row, so one tap both sets the line up and counts it.
   */
  async function keepWeights(
    weights: KegWeights,
    itemIds: string[],
    grossKg: number | null,
    size?: { name: string; baseUnits: number },
  ) {
    const cfg = await loadStockConfig()
    const next = { ...cfg, items: withKegWeights(cfg.items, itemIds, weights, size) }
    await saveStockConfig(next)
    setStockItems(next.items)
    setMeasures(measuresFrom(next))
    const id = itemIds.length === 1 ? itemIds[0] : undefined
    if (grossKg !== null && id !== undefined) {
      const item = next.items.find((i) => i.id === id)
      const r = item ? kegReading(item, grossKg) : null
      if (r) setCellarDrafts((d) => ({ ...d, [`${id}:kg`]: String(grossKg), [id]: String(r.servings) }))
    }
  }

  const cellarLines = sheetLines(stockItems, cellarDrafts)
  const cellarCounted = cellarLines.length

  /** Tonight's sold lines, off the roll as it stands, before anything is saved. */
  const soldTonight = useMemo(
    () => (zRead ? zRead.plus.map((p) => ({ code: p.code, name: p.name, qtyMilli: p.qtyMilli })) : []),
    [zRead],
  )

  /**
   * What should be down there against what has been counted, worked out now
   * rather than after saving.
   *
   * The same nightCellar the saved night uses, handed the count being typed
   * and the roll being read, so the answer she sees standing in the cellar is
   * the answer the night will show tomorrow. Until the roll's item list is in
   * there is nothing to take off, so it says that instead of reporting a
   * night's trade as stock that walked.
   */
  const gap = useMemo(() => {
    if (cellarLines.length === 0 || soldTonight.length === 0) return null
    return nightCellar({
      date,
      items: stockItems,
      pours,
      counts: [...counts.filter((c) => c.date !== date), { date, lines: cellarLines }],
      deliveries,
      days: [...soldBefore.filter((d) => d.date !== date), { date, items: soldTonight }],
      costOfServing: costOf,
    })
  }, [date, stockItems, pours, counts, deliveries, soldBefore, soldTonight, cellarLines])

  /** Sold lines the cellar knows nothing about, which the gap has to own up to. */
  const unmapped = useMemo(() => pourUsage(soldTonight, pours).unmapped.length, [soldTonight, pours])

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

      // The cellar count is the night's own: dated the same trading day, so
      // the night and its count are one record in all but storage. A sheet
      // left entirely blank is no count at all — and a count cleared on a
      // correction is taken off rather than quietly kept.
      if (cellarLines.length > 0) await saveStockCount({ date, lines: cellarLines })
      else if (hadCount) await deleteStockCount(date)

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

        {unfinished && (
          <section className="card">
            <p className="note" style={{ marginTop: 0, marginBottom: 10 }}>
              <strong>{formatLong(unfinished.date)} is still to finish.</strong> It is saved, with{' '}
              {unfinished.missing} still to go in.
            </p>
            <button type="button" className="btn-small" onClick={() => setDate(unfinished.date)}>
              Finish that night
            </button>
          </section>
        )}

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

        {/* The cellar, counted as the doors are locked. Optional, because a
            night is complete without it — but done nightly it turns every
            night into a closed window, judged against the night before. */}
        <section className="card">
          <div className="card-head">
            <span className={`step-dot${cellarCounted ? ' done' : ''}`} aria-hidden="true">
              {cellarCounted ? <IconTickSmall size={13} /> : 4}
            </span>
            <h2>The cellar</h2>
            <span className="hint">{stockItems.length === 0 ? 'not set up yet' : cellarCounted ? `${cellarCounted} lines counted` : 'count it as you lock up'}</span>
          </div>
          {stockItems.length === 0 ? (
            <p className="note" style={{ marginTop: 0 }}>
              Once the cellar is set up on the Cellar tab, it can be counted here every night — and
              each night then shows what should have been down there against what was.
            </p>
          ) : !cellarOpen ? (
            <>
              <button type="button" onClick={() => setCellarOpen(true)} data-testid="count-cellar">
                Count the cellar
              </button>
              <p className="note" style={{ marginBottom: 0 }}>
                Counted nightly, every night closes its own window: last night’s count, plus what came
                in, less what the till poured, against what is actually there. Kegs go on the scales
                rather than being guessed at.
              </p>
            </>
          ) : (
            <>
              {weighOpen ? (
                <div className="keg-inline">
                  <KegCalculator items={stockItems} onKeep={keepWeights} />
                  <div className="alts">
                    <button type="button" className="btn-small" onClick={() => setWeighOpen(false)}>
                      Put the scales away
                    </button>
                  </div>
                </div>
              ) : (
                <div className="alts" style={{ marginTop: 0, marginBottom: 12 }}>
                  <button type="button" className="btn-small" onClick={() => setWeighOpen(true)}>
                    Weigh a keg
                  </button>
                </div>
              )}
              <CountSheet
                items={stockItems}
                drafts={cellarDrafts}
                onChange={setCellarDrafts}
                word="counted"
                scales
                measures={measures}
              />
              <p className="note">
                Whole containers in the first box, loose servings in the last. A keg that has been
                weighed empty and full has a box for the scales too — the reading fills the count in.
                Anything left blank was not counted, which is not the same as none.
              </p>

              {cellarCounted > 0 && (
                <div className="cellar-gap">
                  <div className="card-head">
                    <h2>Against the till</h2>
                    <span className="hint">
                      {gap?.window ? `since ${formatShort(gap.window.since)}` : 'as you count'}
                    </span>
                  </div>
                  {soldTonight.length === 0 ? (
                    <p className="note" style={{ marginTop: 0, marginBottom: 0 }}>
                      Photograph the till roll and this says what should be down there against what
                      you have counted. Without the roll's own item list there is nothing to take
                      off, and every pint sold tonight would read as a pint that walked.
                    </p>
                  ) : gap?.window ? (
                    <CellarGap gap={gap.window} live unmapped={unmapped} />
                  ) : (
                    <p className="note" style={{ marginTop: 0, marginBottom: 0 }}>
                      The first count there is, so nothing before it to compare with. From the next
                      one on, this says what should have been down there against what was.
                    </p>
                  )}
                </div>
              )}
              <div className="alts">
                <button type="button" className="btn-small" onClick={() => setCellarOpen(false)}>
                  {cellarCounted ? 'Hide the sheet' : 'Not tonight'}
                </button>
              </div>
            </>
          )}
        </section>

        <section className="card">
          <p className="note" style={{ marginTop: 0, marginBottom: 0 }}>
            Nothing has to be done in one go. Save it with whatever is filled in — the cellar counted
            and the roll still on the bar, or the other way about — and open the same date again to
            finish it. An unfinished night is left out of the takings and the averages until it has a
            figure, so a half-done night never reads as a night that took nothing.
          </p>
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
          <VerdictPanel r={r.overall} />
          <button type="button" className="btn-primary" onClick={() => void save()} disabled={saving || busy}>
            {saving
              ? 'Saving…'
              : busy
                ? 'Reading the photograph…'
                : existing
                  ? 'Update this night'
                  : r.overall.complete
                    ? 'Save this night'
                    : 'Save it as it is'}
          </button>
        </div>
      </div>

      {toast && <div className="toast" role="alert">{toast}</div>}
    </>
  )
}
