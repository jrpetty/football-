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
import {
  TillRollCard,
  emptyRoll,
  rollTotalPence,
  savedShot,
  type RollShot,
  type RollState,
} from '../components/TillRollCard.tsx'
import { ItemisedLegs, VerdictPanel } from '../components/Verdict.tsx'
import { formatLong, formatShort, isAfterMidnightForTradingDay, tradingDayKey } from '../core/date.ts'
import { formatMoney, parsePence, penceToInput } from '../core/money.ts'
import { describeMissing, reconcileFull, tillExpectations } from '../core/reconcile.ts'
import { dayStats } from '../core/analytics.ts'
import type { Capture, DayRecord } from '../core/types.ts'
import { emptyDay } from '../core/types.ts'
import { isZReadEmpty, type ZRead } from '../core/zread.ts'
import {
  deletePhoto,
  deleteStockCount,
  getDay,
  listDays,
  listDeliveries,
  listStockCounts,
  getPhoto,
  getStockCount,
  loadStockConfig,
  requestPersistence,
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
import { IconBarrel, IconPencil, IconTickSmall } from '../components/icons.tsx'
import { CashCount } from '../components/CashCount.tsx'
import { useToast } from '../components/toast.ts'
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
  /** Where to send her when the cellar has not been set up yet. */
  onOpenCellar: () => void
  initialDate?: string
}

export function NewDay({ onSaved, onReviewRoll, onOpenCellar, initialDate }: Props) {
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
  /** The note is blank almost every night, so it waits behind a word. */
  const [noteOpen, setNoteOpen] = useState(false)
  /** The date is right almost every night, so it waits behind the date itself. */
  const [dateOpen, setDateOpen] = useState(false)
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
  const [unfinished, setUnfinished] = useState<{
    date: string
    missing: string
    /** Photographed and saved, but nothing has read the roll yet. */
    unreadRoll: boolean
    /** How many more are waiting behind it. */
    also: number
  } | null>(null)
  const [saving, setSaving] = useState(false)
  const [toast, say] = useToast(6000)
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
  //
  // A week away from a signal is a week of nights photographed and not read,
  // so this counts them all rather than naming one and letting the rest queue
  // up invisibly — and it says which kind of unfinished each one is, because
  // "the roll is photographed but nothing has read it" is a different job from
  // "the card machine figure is missing".
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const saved = await listDays().catch(() => [])
      if (cancelled) return
      const waiting = saved
        .map((d) => ({ day: d, stats: dayStats(d, settings.tolerancePence) }))
        .filter(({ day, stats }) => stats.takingsPence === null && day.date !== date)
      // Oldest first: the one most likely to have been forgotten, and the
      // right order to knock a backlog off in.
      const first = waiting[waiting.length - 1]
      setUnfinished(
        first
          ? {
              date: first.day.date,
              unreadRoll: (first.day.zPhotoIds?.length ?? 0) > 0 && !first.day.zRead,
              also: waiting.length - 1,
              missing: describeMissing(
                reconcileFull({
                  tillPence: first.day.till.pence,
                  cardPence: first.day.card.pence,
                  cashPence: first.day.cashPence,
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
      // The roll's own photographs, brought back with it. Coming back the next
      // morning to finish a night, what she needs to see first is which parts
      // of the receipt are already in — otherwise the only safe move is to
      // photograph the whole roll again.
      // A night saved with the roll photographed but nothing read off it is the
      // ordinary case now: no signal at closing time, read in the morning. Its
      // photographs come back marked unread, so the card offers to read them.
      const wasRead = !!found.zRead
      const shots: RollShot[] = []
      for (const id of found.zPhotoIds ?? []) {
        const blob = await getPhoto(id).catch(() => undefined)
        if (blob) shots.push(savedShot(id, blob, shots.length, wasRead))
      }
      if (cancelled) return
      setRoll({
        ...emptyRoll(),
        // Both: `base` is what tonight's photographs fold on top of, so
        // dropping one of them never takes the saved figures with it.
        ...(found.zRead ? { zRead: found.zRead, base: found.zRead } : {}),
        shots,
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
      // The photographs this night still has: the ones carried over from an
      // earlier sitting, then tonight's. Written whole rather than appended,
      // so a photograph thrown away on the card is actually gone.
      const photoIds: string[] = []
      for (const shot of roll.shots) {
        if (shot.id) photoIds.push(shot.id)
        else if (settings.keepPhotos) photoIds.push(await savePhoto(await makeThumbnail(shot.blob)))
      }
      const keptIds = new Set(photoIds)

      const record: DayRecord = {
        ...base,
        date,
        till: {
          // ...base.till keeps the legacy single photograph of nights captured
          // before the roll was photographed in pieces; without it a re-save
          // would strand that picture in the database forever.
          ...base.till,
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
      if (photoIds.length) record.zPhotoIds = photoIds
      else delete record.zPhotoIds

      await saveDay(record)

      // Anything dropped on the card goes for good — after the record no
      // longer points at it, so a failure halfway leaves a night with a
      // missing picture rather than a picture with no night.
      for (const id of base.zPhotoIds ?? []) {
        if (!keptIds.has(id)) await deletePhoto(id).catch(() => undefined)
      }

      // The cellar count is the night's own: dated the same trading day, so
      // the night and its count are one record in all but storage. A sheet
      // left entirely blank is no count at all — and a count cleared on a
      // correction is taken off rather than quietly kept.
      if (cellarLines.length > 0) await saveStockCount({ date, lines: cellarLines })
      else if (hadCount) await deleteStockCount(date)

      // Now that the receipt is the record, ask the browser not to throw it
      // away under storage pressure. Asked here rather than on first run
      // because this is the moment it starts to matter, and it is her own
      // action; it is a no-op where unsupported, and failing is not an error.
      if (photoIds.length > 0) void requestPersistence().catch(() => undefined)

      onSaved(date)
    } catch (err) {
      setSaving(false)
      say(err instanceof Error ? `Could not save: ${err.message}` : 'Could not save that night.')
    }
  }

  return (
    <>
      <div className="main with-bar">
        {/* The date is right on all but a handful of nights, so it is a line of
            text she can tap rather than a card with a field in it. It opens by
            itself on the nights where it is worth a second look: a night being
            corrected, and the small hours, when the trading day is not the one
            the calendar says. */}
        <div className="daybar">
          <button
            type="button"
            className="daybar-date"
            aria-expanded={dateOpen || Boolean(initialDate) || (lateNight && !initialDate)}
            onClick={() => setDateOpen((v) => !v)}
          >
            {formatLong(date)}
          </button>
          {existing && <span className="badge">Already saved</span>}
        </div>

        {(dateOpen || initialDate || (lateNight && !initialDate)) && (
          <section className="card">
            <div className="field" style={{ marginBottom: 0 }}>
              <label htmlFor="date">Trading day</label>
              <input id="date" type="date" value={date} onChange={(e) => e.target.value && setDate(e.target.value)} />
              {lateNight && !initialDate && (
                <p className="help">
                  Past midnight, so this is last night's trade — the session you have just closed.
                </p>
              )}
            </div>
          </section>
        )}

        {unfinished && (
          <section className="card">
            <p className="note" style={{ marginTop: 0, marginBottom: 10 }}>
              {unfinished.unreadRoll ? (
                <>
                  <strong>{formatLong(unfinished.date)} has its roll photographed but not read.</strong>{' '}
                  Open it and the photographs are there waiting — one tap reads them.
                </>
              ) : (
                <>
                  <strong>{formatLong(unfinished.date)} is still to finish.</strong> It is saved, with{' '}
                  {unfinished.missing} still to go in.
                </>
              )}
              {unfinished.also > 0 &&
                ` ${unfinished.also} other ${unfinished.also === 1 ? 'night is' : 'nights are'} waiting too.`}
            </p>
            <button type="button" className="btn-small" onClick={() => setDate(unfinished.date)}>
              {unfinished.also > 0 ? 'Finish the oldest' : 'Finish that night'}
            </button>
          </section>
        )}

        <TillRollCard
          value={roll}
          onChange={setRoll}
          onReview={() => {
            // Set as the base too: figures corrected by hand are not undone by
            // throwing a photograph away afterwards.
            if (roll.zRead) onReviewRoll(roll.zRead, (next) => setRoll({ ...roll, zRead: next, base: next }))
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

        {/* The cellar. Deliberately outside the numbered walk: the night is the
            roll, the card machine and the drawer, and those three are what a
            step number should promise. Counting the cellar is a different job
            on a different rhythm — some do it nightly, most do not — and a
            step 4 that never gets ticked reads as a job left undone every
            single night.

            Shut, it is one line of text. There is no reason for a sheet of
            eighty lines to be on the screen of a landlady who has come here to
            cash up, and every reason for it to open in one tap when she has. */}
        {stockItems.length === 0 ? (
          <button type="button" className="shelf" onClick={onOpenCellar}>
            <span className="shelf-icon" aria-hidden="true"><IconBarrel size={15} /></span>
            <span className="shelf-text">Set the cellar up</span>
            <span className="shelf-hint">not done yet</span>
          </button>
        ) : !cellarOpen ? (
          <button
            type="button"
            className="shelf"
            onClick={() => setCellarOpen(true)}
            data-testid="count-cellar"
          >
            <span className={`shelf-icon${cellarCounted ? ' done' : ''}`} aria-hidden="true">
              {cellarCounted ? <IconTickSmall size={13} /> : <IconBarrel size={15} />}
            </span>
            <span className="shelf-text">Count the cellar</span>
            <span className="shelf-hint">
              {cellarCounted ? `${cellarCounted} lines counted` : 'optional'}
            </span>
          </button>
        ) : (
          <section className="card">
            <div className="card-head">
              <span className={`step-dot extra${cellarCounted ? ' done' : ''}`} aria-hidden="true">
                {cellarCounted ? <IconTickSmall size={13} /> : <IconBarrel size={14} />}
              </span>
              <h2>The cellar</h2>
              <span className="hint">
                {cellarCounted ? `${cellarCounted} lines counted` : 'optional'}
              </span>
            </div>
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
          </section>
        )}

        {/* A note gets written on perhaps one night in twenty. Shut, it costs a
            word; a textarea sitting empty under every night costs a card. */}
        {noteOpen || note ? (
          <section className="card">
            <div className="field" style={{ marginBottom: 0 }}>
              <label htmlFor="note">Note</label>
              <textarea
                id="note"
                value={note}
                placeholder="Anything worth remembering about tonight"
                onChange={(e) => setNote(e.target.value)}
              />
            </div>
          </section>
        ) : (
          <button type="button" className="shelf" onClick={() => setNoteOpen(true)}>
            <span className="shelf-icon" aria-hidden="true"><IconPencil size={14} /></span>
            <span className="shelf-text">Add a note</span>
            <span className="shelf-hint">optional</span>
          </button>
        )}

        <p className="note quiet">
          Save it half done and finish it later — an unfinished night is kept out of the takings
          until it has a figure, so it never reads as a night that took nothing.
        </p>
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
