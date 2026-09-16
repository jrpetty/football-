// ---------------------------------------------------------------------------
// The cellar.
//
// Three things happen here and only two of them involve counting. Stock arrives
// and is counted in. Stock is counted again when someone goes down with a
// clipboard. Everything in between — what was actually poured — comes off the
// till roll, because the roll already knows: 120 pints of Taddy, eight vodkas,
// seven 175ml wines.
//
//     on hand  =  last count  +  delivered  −  poured
//     variance =  what is really there  −  on hand
//
// That last figure is beer that left the cellar without going through the till.
// It is a different loss from a short drawer or an underpriced pint, and the
// only one of the three that a perfect night's reconciliation cannot see.
// ---------------------------------------------------------------------------

import { useEffect, useMemo, useRef, useState } from 'react'
import { dayStats, itemTotals } from '../core/analytics.ts'
import { addDays, formatShort, tradingDayKey } from '../core/date.ts'
import {
  basisKeepsUnit,
  basisOf,
  basisTakesAmount,
  cellarHealth,
  CONTAINER_SIZES,
  DEFAULT_GLASS_ML,
  deliveryLinesFrom,
  describeStock,
  proposeDelivery,
  formatServings,
  formatServingsSigned,
  guessPour,
  ML_PER_BOTTLE,
  pourUsage,
  servingOf,
  servingsToBase,
  pluralServing,
  sheetLines,
  weighable,
  kegReading,
  withKegWeights,
  type KegWeights,
  type Delivery,
  type Basis,
  type Pour,
  type PourGuess,
  type DeliveryProposal,
  type StockItem,
} from '../core/stock.ts'
import { CountSheet } from '../components/CountSheet.tsx'
import { KegCalculator } from '../components/KegCalculator.tsx'
import { bestMatch } from '../core/match.ts'
import { record } from '../core/history.ts'
import { scanDeliveryNote } from '../ocr/scanList.ts'
import { describeZReadError } from '../ocr/scanZRead.ts'
import { IconCamera, IconTickSmall } from '../components/icons.tsx'
import {
  listDays,
  listDeliveries,
  listStockCounts,
  loadStockConfig,
  saveDelivery,
  saveStockConfig,
  saveStockCount,
  type StockConfig,
} from '../storage/db.ts'
import { loadSettings } from '../storage/settings.ts'
import { loadPriceBook } from '../storage/db.ts'
import { cellarValue, costOf, margin } from '../core/margin.ts'
import { buildIndex, lookup, type PriceBookEntry } from '../core/priceBook.ts'
import { formatMoney, parsePence, penceToInput } from '../core/money.ts'

type Panel = 'levels' | 'delivery' | 'count' | 'scales' | 'costs' | 'setup'

/**
 * How a cellar line is counted, given every measure that draws on it.
 *
 * Beer is pints whether it is sold in pints or halves. A line poured at several
 * different measures — 125, 175 and 250ml of the same wine — is coming out of a
 * bottle, so the cellar counts bottles. A line with exactly one measure is
 * itself the container: a 550ml alcohol-free is counted as 550ml bottles, not
 * as a fraction of a wine bottle.
 */
function servingFor(
  guess: PourGuess,
  measures: Set<number> | undefined,
  mlPerShot: number,
): Pick<StockItem, 'kind' | 'servingBaseUnits' | 'servingName'> {
  // A counted line keeps the word the guess used: a bottle of juice is counted
  // in bottles, a bag of crisps in each.
  if (guess.kind !== 'liquid') return servingOf(guess.servingName === 'bottle' ? 'bottle' : 'each', 1)
  if (guess.servingName === 'pint') return servingOf('pint', 0)
  // Every shot starts at the house measure, whatever an individual line
  // prints. A double pours two of them; it does not redefine what a shot is,
  // and the cellar has to hold one answer to "how much is a shot" or the
  // spirits never add up. The measure itself is hers to set, per line.
  if (guess.servingName === 'shot') return servingOf('shot', mlPerShot)
  // Wine sold at three measures out of one bottle is stocked as bottles.
  if ((measures?.size ?? 1) > 1) return { kind: 'liquid', servingBaseUnits: ML_PER_BOTTLE, servingName: 'bottle' }
  return servingOf('glass', guess.baseUnits)
}

/**
 * The trading day a stock take taken now belongs to.
 *
 * Cellars get counted in the morning, before the doors open, and a count
 * taken then is the stock at the close of the night before. Dating it today
 * would quietly leave today's whole trade out of the reconciliation. The
 * trading day already rolls over at 5am; this carries the same idea through
 * the morning, until the pub could plausibly have started selling.
 */
function countDate(): string {
  const key = tradingDayKey()
  return new Date().getHours() < 12 ? addDays(key, -1) : key
}

/** A stable id from a name, so re-running setup does not duplicate a line. */
function idFor(name: string): string {
  return name.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'item'
}

export function Stock({ onChanged }: { onChanged: () => void }) {
  const [panel, setPanel] = useState<Panel>('levels')
  const [config, setConfig] = useState<StockConfig | null>(null)
  const [deliveries, setDeliveries] = useState<Delivery[]>([])
  const [counts, setCounts] = useState<Array<{ date: string; lines: Array<{ stockItemId: string; baseUnits: number }> }>>([])
  const [days, setDays] = useState<ReturnType<typeof dayStats>[]>([])
  const [book, setBook] = useState<PriceBookEntry[]>([])
  const [toast, setToast] = useState('')

  /** Draft numbers being typed into the delivery or count sheets. */
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [sheetDate, setSheetDate] = useState(tradingDayKey())
  const [scanning, setScanning] = useState(false)
  const [scanError, setScanError] = useState('')
  const [scanNotes, setScanNotes] = useState('')
  const [proposals, setProposals] = useState<DeliveryProposal[] | null>(null)
  const [rejected, setRejected] = useState<Set<number>>(new Set())
  /** Whether the keg calculator is out above the stock take. */
  const [weighOpen, setWeighOpen] = useState(false)
  const noteRef = useRef<HTMLInputElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => () => abortRef.current?.abort(), [])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const tolerance = loadSettings().tolerancePence
      const [cfg, dels, cts, saved, prices] = await Promise.all([
        loadStockConfig().catch(() => null),
        listDeliveries().catch(() => []),
        listStockCounts().catch(() => []),
        listDays().catch(() => []),
        loadPriceBook().catch(() => []),
      ])
      if (cancelled) return
      setConfig(cfg ?? { items: [], pours: [], mlPerShot: 30 })
      setDeliveries(dels)
      setCounts(cts)
      setDays(saved.map((d) => dayStats(d, tolerance)))
      setBook(prices)
    })()
    return () => {
      cancelled = true
    }
  }, [])

  function say(message: string) {
    setToast(message)
    setTimeout(() => setToast(''), 4000)
  }

  /**
   * Change how a line is measured, and how much of it a serving is.
   *
   * Both are the user's to set. Reading till names gets most lines right, but
   * no amount of reading can know that this cellar pours a 30ml measure where
   * most of the country pours 25, or that the house white goes out in 175s —
   * and a line left on the wrong basis is wrong in the cellar figures every
   * week after.
   *
   * What happens to the unit and the cost depends on whether the change moves
   * between poured and counted. Pints to shots keeps both: a firkin is 40,896
   * millilitres either way. Shots to bottles cannot, because 700 stops meaning
   * millilitres and starts meaning bottles, so they go and the toast says so.
   */
  async function setMeasure(item: StockItem, patch: { basis?: Basis; amountText?: string }) {
    if (!config) return
    const from = basisOf(item)
    const basis = patch.basis ?? from
    const amountKey = `${item.id}:amount`

    // The size the box is showing, which is what an unfinished "17" of a 175
    // still is until it is a real number.
    const shown = patch.amountText ?? drafts[amountKey] ?? String(item.servingBaseUnits)
    if (patch.amountText !== undefined) setDrafts((d) => ({ ...d, [amountKey]: patch.amountText as string }))

    let ml = Number(shown.trim())
    if (!Number.isFinite(ml) || ml <= 0) {
      // A half-typed size is left in the box rather than saved as nonsense.
      if (patch.amountText !== undefined) return
      ml = item.servingBaseUnits
    }
    // Switching between the two poured kinds starts at a sensible size rather
    // than carrying a 30ml shot over as a 30ml glass of wine.
    if (patch.basis !== undefined && patch.basis !== from) {
      ml =
        patch.basis === 'shot'
          ? config.mlPerShot
          : patch.basis === 'glass'
            ? DEFAULT_GLASS_ML
            : patch.basis === 'open'
              ? ML_PER_BOTTLE
              : ml
      setDrafts((d) => ({ ...d, [amountKey]: String(ml) }))
    }

    const shape = servingOf(basis, ml)
    if (
      shape.kind === item.kind &&
      shape.servingBaseUnits === item.servingBaseUnits &&
      shape.servingName === item.servingName
    ) {
      return
    }

    const keeps = basisKeepsUnit(from, basis)
    const next = {
      ...config,
      items: config.items.map((i) => {
        if (i.id !== item.id) return i
        if (keeps) return { ...i, ...shape }
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        const { container: _c, cost: _p, ...bare } = i
        return { ...bare, ...shape }
      }),
    }
    setConfig(next)
    // The size box counts servings, so what it holds means something different
    // the moment the serving changes: 72 pints is 234 glasses of the same
    // barrel. Clearing the box lets it re-read the barrel in the new measure
    // rather than showing a number that has quietly stopped being true.
    // Dropped rather than blanked: a draft of '' is still a draft, and would sit
    // in front of the stored figure instead of letting it through.
    setDrafts((d) => {
      const rest = { ...d }
      delete rest[`${item.id}:size`]
      if (!keeps) delete rest[`${item.id}:price`]
      return rest
    })
    await saveStockConfig(next)
    onChanged()
    if (patch.basis !== undefined) {
      say(
        keeps
          ? `${item.name} is now measured in ${shape.servingName}s.`
          : `${item.name} is now counted in ${shape.servingName}s — set its unit and cost again.`,
      )
    }
  }

  /**
   * What one sale takes off the cellar.
   *
   * Entered in the line's own servings, because that is how it is thought
   * about: a double is two shots, not sixty millilitres. Guessed off the till's
   * names to begin with, and wrong often enough — a double, a schooner, a
   * jug — that leaving it unfixable would quietly cost the stock figures every
   * week.
   */
  async function setPour(pour: Pour, text: string) {
    if (!config) return
    const key = `pour:${pour.itemCode}`
    setDrafts((d) => ({ ...d, [key]: text }))
    const item = config.items.find((i) => i.id === pour.stockItemId)
    if (!item) return
    const servings = Number(text.trim())
    if (!Number.isFinite(servings) || servings <= 0) return

    const baseUnits = servingsToBase(servings, item)
    if (baseUnits === pour.baseUnits) return
    const next = {
      ...config,
      pours: config.pours.map((p) => (p.itemCode === pour.itemCode ? { ...p, baseUnits } : p)),
    }
    setConfig(next)
    await saveStockConfig(next)
    onChanged()
  }

  /**
   * Set the unit a line arrives in and what it costs, together.
   *
   * Deliberately one function taking both boxes rather than two taking one
   * each. The cost is a price *per container*, so neither figure means
   * anything without the other — and handling them separately meant typing
   * the price before the size silently threw the price away, because at that
   * moment there was no size to attach it to. Reading both drafts on every
   * keystroke means whichever is typed second completes the pair. Both boxes
   * are kept as typed while she works: a half-entered "£95 for " is not yet a
   * cost, and clearing either side takes the cost away again, which is how a
   * mistyped price is undone.
   */
  /**
   * What a keg weighs empty and full, so a reading off the scales is pints.
   *
   * Weighed rather than assumed: a kil of stout and a kil of lager do not
   * weigh the same, and the pub owns scales, not a hydrometer. Kept as typed
   * until both are real numbers with full heavier than empty; a half-typed
   * weight is left in the box rather than stored as a calibration.
   */
  async function setScales(item: StockItem, patch: { emptyText?: string; fullText?: string }) {
    if (!config || !item.container) return
    const emptyKey = `${item.id}:empty`
    const fullKey = `${item.id}:fullkg`
    const emptyText = patch.emptyText ?? drafts[emptyKey] ?? (item.container.emptyKg !== undefined ? String(item.container.emptyKg) : '')
    const fullText = patch.fullText ?? drafts[fullKey] ?? (item.container.fullKg !== undefined ? String(item.container.fullKg) : '')
    setDrafts((d) => ({ ...d, [emptyKey]: emptyText, [fullKey]: fullText }))

    const empty = Number(emptyText.trim())
    const full = Number(fullText.trim())
    const valid = emptyText.trim() !== '' && fullText.trim() !== '' && Number.isFinite(empty) && Number.isFinite(full) && empty >= 0 && full > empty
    const cleared = emptyText.trim() === '' && fullText.trim() === ''
    if (!valid && !cleared) return

    const next = { ...config, items: withKegWeights(config.items, [item.id], valid ? { emptyKg: empty, fullKg: full } : null) }
    setConfig(next)
    await saveStockConfig(next)
    onChanged()
  }

  /**
   * Weights from the keg calculator, kept against one line or every line in
   * that size of keg. The reading that was on the scales at the time goes
   * straight into the stock take, if one is open.
   */
  async function keepWeights(
    weights: KegWeights,
    itemIds: string[],
    grossKg: number | null,
    size?: { name: string; baseUnits: number },
  ) {
    if (!config) return
    const next = { ...config, items: withKegWeights(config.items, itemIds, weights, size) }
    setConfig(next)
    await saveStockConfig(next)
    onChanged()
    const id = itemIds.length === 1 ? itemIds[0] : undefined
    if (panel === 'count' && grossKg !== null && id !== undefined) {
      const item = next.items.find((i) => i.id === id)
      const r = item ? kegReading(item, grossKg) : null
      if (r) setDrafts((d) => ({ ...d, [`${id}:kg`]: String(grossKg), [id]: String(r.servings) }))
    }
  }

  async function setLine(item: StockItem, patch: { name?: string; sizeText?: string; priceText?: string }) {
    const sizeKey = `${item.id}:size`
    const priceKey = `${item.id}:price`
    const currentSize = drafts[sizeKey] ?? (item.container ? String(Math.round(item.container.baseUnits / item.servingBaseUnits)) : '')
    const currentPrice = drafts[priceKey] ?? (item.cost ? penceToInput(item.cost.pence) : '')

    const sizeText = patch.sizeText ?? currentSize
    const priceText = patch.priceText ?? currentPrice
    setDrafts((d) => ({ ...d, [sizeKey]: sizeText, [priceKey]: priceText }))
    if (!config) return

    const servings = Number(sizeText.trim())
    const typedSize = sizeText.trim() !== '' && Number.isFinite(servings) && servings > 0
    // The box shows the container's millilitres divided into servings, rounded
    // for reading. Multiplying that rounded figure back out would shave a few
    // millilitres off the barrel every time the price beside it was touched, so
    // a container nobody has just retyped is carried across exactly as it is.
    const untouched = patch.sizeText === undefined && item.container !== undefined
    const hasSize = untouched || typedSize
    const baseUnits = untouched
      ? (item.container as { baseUnits: number }).baseUnits
      : typedSize
        ? Math.round(servings * item.servingBaseUnits)
        : 0

    const pence = parsePence(priceText)
    const hasPrice = pence !== null && pence > 0

    const next = {
      ...config,
      items: config.items.map((i) => {
        if (i.id !== item.id) return i
        const name = patch.name ?? i.container?.name ?? 'unit'
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        const { container: _c, cost: _p, ...bare } = i
        const costs = hasPrice && hasSize
          ? record(i.costHistory ?? [], { date: tradingDayKey(), pence, baseUnits })
          : i.costHistory
        // The scales weights ride along: a price edit must not lose them. A
        // different size of keg is a different keg, though — a kil does not
        // weigh what a firkin does, empty or full — so a line moved to another
        // container needs weighing again.
        const sameKeg = patch.name === undefined || patch.name === i.container?.name
        const weights = sameKeg
          ? {
              ...(i.container?.emptyKg !== undefined ? { emptyKg: i.container.emptyKg } : {}),
              ...(i.container?.fullKg !== undefined ? { fullKg: i.container.fullKg } : {}),
            }
          : {}
        return {
          ...bare,
          ...(hasSize ? { container: { name, baseUnits, ...weights } } : {}),
          // A price with no size is not yet a cost; it waits in the box.
          ...(hasPrice && hasSize ? { cost: { pence, baseUnits } } : {}),
          // Kept whatever happens to the current cost: a line going uncosted
          // must not erase what it used to cost.
          ...(costs && costs.length > 0 ? { costHistory: costs } : {}),
        }
      }),
    }
    setConfig(next)
    await saveStockConfig(next)
    onChanged()
  }

  /**
   * The cellar's whole state, from the shared core.
   *
   * Once computed privately here, which meant the weekly alerts could never
   * see the figure this screen was showing. Now both read cellarHealth, so
   * what the screen says and what the alerts say cannot disagree.
   */
  const health = useMemo(
    () =>
      config
        ? cellarHealth({
            items: config.items,
            pours: config.pours,
            counts,
            deliveries,
            days,
            today: tradingDayKey(),
            costOfServing: costOf,
          })
        : null,
    [config, counts, deliveries, days],
  )

  // Built once: rebuilding it inside the items loop made every keystroke in a
  // cost box do items-times-book work for the same answer.
  const priceIndex = useMemo(() => buildIndex(book), [book])
  const latestCount = counts[0]
  const previousCount = counts[1]
  const since = health?.since ?? addDays(tradingDayKey(), -7)
  const ledger = health?.ledger ?? []
  const result = health?.gapLines ?? []

  const unmapped = useMemo(() => {
    if (!config) return []
    const sold = itemTotals(days).map((i) => ({ code: i.code, name: i.name, qtyMilli: i.qtyMilli }))
    return pourUsage(sold, config.pours).unmapped
  }, [config, days])

  if (config === null) return <div className="main"><p className="note"><span className="spinner" /> Loading…</p></div>

  // --- setting the cellar up from the till's own item list -------------------
  async function buildFromTill() {
    const seen = itemTotals(days)
    if (seen.length === 0) return say('No items yet — scan a till roll with its item list first.')

    const items = new Map<string, StockItem>(config!.items.map((i) => [i.id, i]))
    const pours: Pour[] = [...config!.pours]

    // Guess everything first, so a cellar line can be sized by all the measures
    // that draw on it rather than by whichever happened to be seen last.
    const guesses = seen
      .filter((sold) => !pours.some((p) => p.itemCode === sold.code))
      .map((sold) => ({ sold, guess: guessPour(sold.code, sold.name, config!.mlPerShot) }))

    const measuresFor = new Map<string, Set<number>>()
    for (const { guess } of guesses) {
      if (guess.kind !== 'liquid') continue
      const set = measuresFor.get(guess.stockName) ?? new Set<number>()
      set.add(guess.baseUnits)
      measuresFor.set(guess.stockName, set)
    }

    for (const { sold, guess } of guesses) {
      const id = idFor(guess.stockName)
      if (!items.has(id)) {
        items.set(id, { id, name: guess.stockName, ...servingFor(guess, measuresFor.get(guess.stockName), config!.mlPerShot) })
      }
      pours.push({ itemCode: sold.code, itemName: sold.name, stockItemId: id, baseUnits: guess.baseUnits })
    }

    const next = { ...config!, items: [...items.values()].sort((a, b) => a.name.localeCompare(b.name)), pours }
    setConfig(next)
    await saveStockConfig(next)
    onChanged()
    say(`${next.items.length} cellar lines set up from ${seen.length} till lines.`)
  }

  async function readNote(file: File) {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    setScanning(true)
    setScanError('')
    setScanNotes('')
    try {
      const result = await scanDeliveryNote(file, controller.signal)
      if (controller.signal.aborted) return
      const rows = proposeDelivery(result.lines, config?.items ?? [], bestMatch)
      setProposals(rows)
      setRejected(new Set())
      setScanNotes(result.notes)
      if (rows.length === 0) setScanError('No stock lines could be read on that note.')
    } catch (err) {
      if (controller.signal.aborted) return
      setScanError(describeZReadError(err))
    } finally {
      if (!controller.signal.aborted) setScanning(false)
    }
  }

  function acceptable(rows: DeliveryProposal[]): DeliveryProposal[] {
    return rows.filter((r, i) => !rejected.has(i) && r.status === 'ready')
  }

  async function bookNote() {
    if (!proposals) return
    const lines = deliveryLinesFrom(acceptable(proposals))
    if (lines.length === 0) return say('Nothing on that note to book in.')
    const delivery: Delivery = { id: `${sheetDate}-${Date.now().toString(36)}`, date: sheetDate, lines }
    await saveDelivery(delivery)
    setDeliveries(await listDeliveries())
    setProposals(null)
    onChanged()
    say(`Delivery of ${lines.length} ${lines.length === 1 ? 'line' : 'lines'} booked in from the note.`)
    setPanel('levels')
  }

  async function saveSheet(kind: 'delivery' | 'count') {
    // Each line can be entered as whole containers, as loose servings, or as
    // both — "two kils and about thirty pints" is one line, not two. The
    // arithmetic is the core's, shared with the count Tonight takes.
    const lines = sheetLines(config?.items ?? [], drafts)

    if (lines.length === 0) return say('Nothing entered yet.')

    if (kind === 'delivery') {
      const delivery: Delivery = { id: `${sheetDate}-${Date.now().toString(36)}`, date: sheetDate, lines }
      await saveDelivery(delivery)
      setDeliveries(await listDeliveries())
      say(`Delivery of ${lines.length} lines booked in for ${formatShort(sheetDate)}.`)
    } else {
      await saveStockCount({ date: sheetDate, lines })
      setCounts(await listStockCounts())
      say(`Stock take saved for ${formatShort(sheetDate)}.`)
    }
    setDrafts({})
    onChanged()
    setPanel('levels')
  }

  return (
    <div className="main">
      <section className="card">
        <div className="card-head">
          <h2>The cellar</h2>
          <span className="badge">{config.items.length} lines</span>
        </div>
        <div className="chip-row">
          {(['levels', 'delivery', 'count', 'scales', 'costs', 'setup'] as const).map((p) => (
            <button
              key={p}
              type="button"
              className="chip"
              aria-pressed={panel === p}
              onClick={() => {
                setPanel(p)
                setDrafts({})
                // A delivery is dated the day it arrives. A stock take is dated
                // the trading day it draws a line under, which for a count done
                // before opening is last night's.
                if (p === 'count') setSheetDate(countDate())
                else if (p === 'delivery') setSheetDate(tradingDayKey())
              }}
            >
              {p === 'levels' ? 'What’s left' : p === 'delivery' ? 'Delivery in' : p === 'count' ? 'Stock take' : p === 'scales' ? 'The scales' : p === 'costs' ? 'What it costs' : 'Set up'}
            </button>
          ))}
        </div>
      </section>

      {config.items.length === 0 && (
        <section className="card">
          <p className="note" style={{ marginTop: 0 }}>
            Nothing set up yet. The till roll already lists everything the pub sells, so the cellar can be
            built from it in one go — then checked over.
          </p>
          <button type="button" className="btn-primary" onClick={() => void buildFromTill()}>
            Build the cellar from the till
          </button>
        </section>
      )}

      {/* --- what should be left --------------------------------------------- */}
      {panel === 'levels' && config.items.length > 0 && (
        <section className="card">
          <div className="card-head">
            <h2>What should be left</h2>
            <span className="hint">since {formatShort(since)}</span>
          </div>
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Line</th>
                  <th scope="col">Counted</th>
                  <th scope="col">In</th>
                  <th scope="col">Poured</th>
                  <th scope="col">Left</th>
                </tr>
              </thead>
              <tbody>
                {ledger
                  .filter((l) => l.countedBaseUnits || l.deliveredBaseUnits || l.pouredBaseUnits)
                  .map((l) => (
                    <tr key={l.item.id}>
                      <th scope="row">{l.item.name}</th>
                      <td className={l.counted ? 'num' : 'num faint'}>
                        {l.counted ? formatServings(l.countedBaseUnits, l.item) : 'not counted'}
                      </td>
                      <td className="num">{formatServings(l.deliveredBaseUnits, l.item)}</td>
                      <td className="num">{formatServings(l.pouredBaseUnits, l.item)}</td>
                      {l.counted ? (
                        <td className={`num delta ${l.expectedBaseUnits < 0 ? 'short' : ''}`} title={describeStock(l.expectedBaseUnits, l.item)}>
                          {formatServings(l.expectedBaseUnits, l.item)}
                        </td>
                      ) : (
                        <td className="num faint">—</td>
                      )}
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
          {ledger.every((l) => !l.pouredBaseUnits) && (
            <p className="note">
              Nothing poured in this window yet. Usage fills in on its own as nights are saved — the roll
              already knows what went out.
            </p>
          )}
          {ledger.some((l) => l.counted && l.expectedBaseUnits < 0) && (
            <p className="note warn">
              A line has gone below zero, which means more was poured than was ever booked in. Either a
              delivery was missed or the pour is set wrong.
            </p>
          )}
          {ledger.some((l) => !l.counted && (l.deliveredBaseUnits || l.pouredBaseUnits)) && (
            <p className="note">
              A line marked not counted was left blank on the last stock take, so what is left of it
              cannot be said — only what has come in and gone out since.
            </p>
          )}
        </section>
      )}

      {/* --- counting a delivery or a stock take ----------------------------- */}
      {(panel === 'delivery' || panel === 'count') && config.items.length > 0 && (
        <section className="card">
          <div className="card-head">
            <h2>{panel === 'delivery' ? 'Delivery in' : 'Stock take'}</h2>
            <span className="hint">{panel === 'delivery' ? 'what arrived' : 'what is actually there'}</span>
          </div>
          <div className="field">
            <label htmlFor="sheet-date">Date</label>
            <input id="sheet-date" type="date" value={sheetDate} onChange={(e) => e.target.value && setSheetDate(e.target.value)} />
            {panel === 'count' && (
              <p className="help">
                Dated by the trading day it closes. A count done in the morning, before opening, is
                the stock at the end of last night — so before midday this starts on last night.
              </p>
            )}
          </div>

          {panel === 'count' && weighOpen && (
            <div className="keg-inline">
              <KegCalculator items={config.items} onKeep={keepWeights} />
              <div className="alts">
                <button type="button" className="btn-small" onClick={() => setWeighOpen(false)}>
                  Put the scales away
                </button>
              </div>
            </div>
          )}
          {panel === 'count' && !weighOpen && (
            <div className="alts" style={{ marginTop: 0, marginBottom: 12 }}>
              <button type="button" className="btn-small" onClick={() => setWeighOpen(true)}>
                Weigh a keg
              </button>
            </div>
          )}

          {panel === 'delivery' && (
            <>
              <div className="alts">
                <button type="button" className="btn-small" onClick={() => noteRef.current?.click()} disabled={scanning}>
                  {scanning ? <><span className="spinner" /> Reading the note…</> : <><IconCamera size={17} /> Photograph the note</>}
                </button>
              </div>
              <input
                ref={noteRef}
                type="file"
                accept="image/*"
                className="visually-hidden"
                data-testid="file-note"
                onChange={(e) => {
                  const file = e.target.files?.[0]
                  e.target.value = ''
                  if (file) void readNote(file)
                }}
              />
              {scanError && <p className="note bad" role="status">{scanError}</p>}
              {scanNotes && !scanError && <p className="note warn" role="status">{scanNotes}</p>}
            </>
          )}
          {panel === 'delivery' && proposals && (
            <div className="proposal">
              <div className="card-head">
                <h2>What the note says</h2>
                <span className="badge">{acceptable(proposals).length} to book in</span>
              </div>
              <p className="note" style={{ marginTop: 0 }}>
                Nothing is in the cellar yet. Quantities are read as whole containers where the line
                has a size set — two kils is 288 pints, not two.
              </p>
              {proposals.map((row, i) => {
                const off = rejected.has(i)
                const item = config.items.find((it) => it.id === row.stockItemId)
                return (
                  <div className="zrow" key={`${row.written}-${i}`}>
                    <span className="zname">
                      {row.itemName ?? row.written}
                      <small>
                        {row.status === 'unmatched' && `“${row.written}” — nothing in the cellar matches`}
                        {row.status === 'ambiguous' && `“${row.written}” — could be ${row.between?.join(' or ')}`}
                        {row.status === 'no-container' && `${row.quantity} ${row.unit} — no size set for that unit`}
                        {row.status === 'ready' && item && (
                          <>
                            {row.quantity} {row.unit || (row.countedAs === 'container' ? item.container?.name ?? '' : item.servingName)}
                            {' · '}
                            {describeStock(row.baseUnits ?? 0, item)}
                          </>
                        )}
                      </small>
                    </span>
                    {row.status === 'ready' ? (
                      <button
                        type="button"
                        className="chip"
                        aria-pressed={!off}
                        aria-label={`${off ? 'Include' : 'Skip'} ${row.itemName ?? row.written}`}
                        onClick={() =>
                          setRejected((r) => {
                            const next = new Set(r)
                            if (next.has(i)) next.delete(i)
                            else next.add(i)
                            return next
                          })
                        }
                      >
                        {off ? 'Skipped' : <IconTickSmall size={13} />}
                      </button>
                    ) : (
                      <span className="badge warn">by hand</span>
                    )}
                  </div>
                )
              })}
              <div className="btn-row" style={{ marginTop: 14 }}>
                <button type="button" className="btn-primary" onClick={() => void bookNote()}>
                  Book {acceptable(proposals).length} in
                </button>
                <button type="button" className="btn-small" onClick={() => setProposals(null)}>
                  Throw it away
                </button>
              </div>
            </div>
          )}

          <CountSheet
            items={config.items}
            drafts={drafts}
            onChange={setDrafts}
            word={panel === 'delivery' ? 'delivered' : 'counted'}
            scales={panel === 'count'}
          />
          {panel === 'count' && (
            <p className="note">
              {config.items.some((i) => weighable(i))
                ? 'A line with its keg weighed empty and full has a box for the scales: put the reading in and it works out the pints for you. Weigh a keg, above, to set another line up the same way.'
                : 'Kegs can be weighed rather than guessed at. Weigh a keg, above: one empty and one full, kept against the line, and from then on its row has a box for the reading.'}
            </p>
          )}
          <button type="button" className="btn-primary" style={{ marginTop: 12 }} onClick={() => void saveSheet(panel)}>
            {panel === 'delivery' ? 'Book the delivery in' : 'Save the stock take'}
          </button>
          <p className="note">
            {panel === 'delivery'
              ? 'Count it in as it comes off the lorry. Anything left blank is simply not part of this delivery.'
              : 'What is really down there. Anything left blank was not counted, which is not the same as none.'}
          </p>
        </section>
      )}

      {/* --- the pours ------------------------------------------------------- */}
      {/* --- what the brewery charges ---------------------------------------- */}
      {/* --- the scales -------------------------------------------------------- */}
      {panel === 'scales' && config.items.length > 0 && (
        <section className="card">
          <div className="card-head">
            <h2>The scales</h2>
            <span className="hint">what is really in a keg</span>
          </div>
          <p className="note" style={{ marginTop: 0 }}>
            Weigh a keg empty and weigh one full — once each, for each size of keg. From then on
            any keg on the scales is pints: its own weight comes off, and what is left is the share
            of a full one.
          </p>
          <KegCalculator items={config.items} onKeep={keepWeights} />
          <p className="note">
            Keep the weights against a line and its row on the stock take — and on Tonight’s count —
            has a box for the reading. A firkin, a kil and a keg each weigh their own, so a line that
            changes size needs weighing again.
          </p>
        </section>
      )}

      {panel === 'costs' && config.items.length > 0 && (
        <section className="card">
          <div className="card-head">
            <h2>What it costs</h2>
            <span className="badge">{config.items.filter((i) => i.cost).length} of {config.items.length} costed</span>
          </div>
          <p className="note" style={{ marginTop: 0 }}>
            What the invoice charges, and what that buys. A firkin of Taddy at £95 is £95 for 72
            pints, and the margin on every pour works itself out from there.
          </p>

          {config.items.map((item) => {
            const servings = item.container ? Math.round(item.container.baseUnits / item.servingBaseUnits) : 0
            const priceKey = `${item.id}:price`
            const sizeKey = `${item.id}:size`
            const perServing = costOf(item, item.servingBaseUnits)
            const pour = config.pours.find((p) => p.stockItemId === item.id)
            const sell = pour ? lookup(priceIndex, { code: pour.itemCode, name: pour.itemName }) : undefined
            const pourCost = pour ? costOf(item, pour.baseUnits) : null
            const gp = sell && pourCost !== null ? margin(sell.pence, pourCost) : null
            const sizeText = drafts[sizeKey] ?? (servings ? String(servings) : '')
            const basis = basisOf(item)
            const takesAmount = basisTakesAmount(basis)

            return (
              <div className="stock-line" key={item.id}>
                <div className="stock-line-head">
                  <strong>{item.name}</strong>
                  <span className="hint">
                    {perServing === null
                      ? `not costed · per ${item.servingName}`
                      : `${formatMoney(perServing)} a ${item.servingName}`}
                    {gp && ` · ${(gp.gpBp / 100).toFixed(1)}% GP`}
                  </span>
                </div>
                <div className="stock-line-row">
                  <select
                    aria-label={`${item.name} measured in`}
                    value={basis}
                    onChange={(e) => void setMeasure(item, { basis: e.target.value as Basis })}
                  >
                    <option value="pint">pints</option>
                    <option value="shot">shots</option>
                    <option value="glass">glasses</option>
                    <option value="open">bottles, poured</option>
                    <option value="bottle">bottles, whole</option>
                    <option value="each">each</option>
                  </select>
                  {/* The size of that measure, where the measure has one. A pint
                      is 568ml by definition and a bottle is one bottle, so those
                      show what they are rather than inviting a number that would
                      not mean anything. */}
                  <span className="stock-field">
                    <input
                      aria-label={`${item.name} millilitres per serving`}
                      inputMode="numeric"
                      disabled={!takesAmount}
                      value={
                        takesAmount
                          ? drafts[`${item.id}:amount`] ?? String(item.servingBaseUnits)
                          : basis === 'pint'
                            ? '568'
                            : '1'
                      }
                      onChange={(e) => void setMeasure(item, { amountText: e.target.value })}
                    />
                    <small>{item.kind === 'liquid' ? 'ml' : 'each'}</small>
                  </span>
                  <select
                    aria-label={`${item.name} container`}
                    value={CONTAINER_SIZES.some((c) => c.name === item.container?.name) ? item.container!.name : ''}
                    onChange={(e) => {
                      const preset = CONTAINER_SIZES.find((c) => c.name === e.target.value)
                      // A preset written in millilitres is turned into this
                      // line's own servings, so a 70cl bottle is 23.33 shots
                      // at a 30ml measure and 28 at a 25ml one.
                      const size = preset
                        ? preset.ml !== undefined
                          ? String(Math.round((preset.ml / item.servingBaseUnits) * 100) / 100)
                          : String(preset.servings)
                        : ''
                      void setLine(item, { ...(preset ? { name: preset.name, sizeText: size } : { name: '' }) })
                    }}
                  >
                    <option value="">unit…</option>
                    {CONTAINER_SIZES.map((c) => (
                      <option key={c.name} value={c.name}>
                        {c.name} ({c.ml !== undefined ? `${c.ml}ml` : c.servings})
                      </option>
                    ))}
                  </select>
                  <span className="stock-field">
                    <input
                      aria-label={`${item.name} servings per container`}
                      inputMode="numeric"
                      placeholder={
                        item.servingName === 'pint'
                          ? '72'
                          : item.servingName === 'shot'
                            ? String(Math.round((700 / item.servingBaseUnits) * 100) / 100)
                            : '1'
                      }
                      value={sizeText}
                      onChange={(e) => void setLine(item, { sizeText: e.target.value })}
                    />
                    <small>{pluralServing(item.servingName)}</small>
                  </span>
                  {item.kind === 'liquid' && item.container && (
                    <>
                      <span className="stock-field">
                        <small>empty</small>
                        <input
                          aria-label={`${item.name} empty keg weight`}
                          inputMode="decimal"
                          placeholder="kg"
                          value={drafts[`${item.id}:empty`] ?? (item.container.emptyKg !== undefined ? String(item.container.emptyKg) : '')}
                          onChange={(e) => void setScales(item, { emptyText: e.target.value })}
                        />
                        <small>kg</small>
                      </span>
                      <span className="stock-field">
                        <small>full</small>
                        <input
                          aria-label={`${item.name} full keg weight`}
                          inputMode="decimal"
                          placeholder="kg"
                          value={drafts[`${item.id}:fullkg`] ?? (item.container.fullKg !== undefined ? String(item.container.fullKg) : '')}
                          onChange={(e) => void setScales(item, { fullText: e.target.value })}
                        />
                        <small>kg</small>
                      </span>
                    </>
                  )}
                  <span className="stock-field stock-cost">
                    <small>£</small>
                    <input
                      aria-label={`${item.name} cost`}
                      inputMode="decimal"
                      placeholder="—"
                      value={drafts[priceKey] ?? (item.cost ? penceToInput(item.cost.pence) : '')}
                      onChange={(e) => void setLine(item, { priceText: e.target.value })}
                    />
                    <small>a {item.container?.name ?? 'unit'}</small>
                  </span>
                </div>
              </div>
            )
          })}

          <p className="note">
            Each line says how it is counted: the tap beers in pints, the spirits in shots of{' '}
            {config.mlPerShot}ml, the bottled drinks — the juices, the mixers, the alcohol-free — by
            the bottle. Change it on a line the till's own names sent the wrong way; its unit and
            cost are cleared with it, since they were measured the old way.
          </p>
          <p className="note">
            The unit is what a delivery arrives as and what the price is for — a kil of Taddy is 144
            pints, a firkin 72, a spirit bottle 70cl. Set it once and the cellar counts in barrels
            rather than in pints, and the invoice price divides itself down. Anything left blank
            simply has no margin figure; nothing is ever assumed to be free.
          </p>
          <p className="note">
            Kegs can be weighed instead of guessed at. Put a full one and an empty one on the scales
            once and type both weights against the line; from then on the stock take has a box for
            the reading, and a keg at 45 kg comes out as the pints that are actually in it. The scales, on their own chip, work the same sum
            for any keg and keep the weights here.
          </p>
        </section>
      )}

      {panel === 'setup' && (
        <section className="card">
          <div className="card-head">
            <h2>What each sale pours</h2>
            <span className="hint">{config.pours.length} set</span>
          </div>
          <button type="button" onClick={() => void buildFromTill()}>
            Add any new lines from the till
          </button>
          {unmapped.length > 0 && (
            <p className="note warn">
              {unmapped.length} sold {unmapped.length === 1 ? 'line has' : 'lines have'} no pour set, so
              {unmapped.length === 1 ? ' it does' : ' they do'} not come off the cellar at all.
            </p>
          )}
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Sold as</th>
                  <th scope="col">Takes from</th>
                  <th scope="col">Each</th>
                </tr>
              </thead>
              <tbody>
                {config.pours.map((p) => {
                  const item = config.items.find((i) => i.id === p.stockItemId)
                  const each = item ? p.baseUnits / item.servingBaseUnits : p.baseUnits
                  return (
                    <tr key={p.itemCode}>
                      <th scope="row">{p.itemName}</th>
                      <td>{item?.name ?? p.stockItemId}</td>
                      <td className="num">
                        {item ? (
                          <span className="pour-each">
                            <input
                              aria-label={`${p.itemName} takes`}
                              inputMode="decimal"
                              value={drafts[`pour:${p.itemCode}`] ?? String(Math.round(each * 100) / 100)}
                              onChange={(e) => void setPour(p, e.target.value)}
                            />
                            <small>{item.servingName}</small>
                          </span>
                        ) : (
                          `${p.baseUnits}`
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <p className="note">
            What each sale takes off the cellar, in that line's own measure — so a double is 2 and a
            half is 0.5. Guessed off the till's own names to start with; change any it read wrong.
            Halves are exactly half a pint, which is why two of them cancel one, and the size of a
            shot or a glass is set against the line itself under What it costs.
          </p>
        </section>
      )}

      {panel === 'levels' && config.items.length > 0 && (() => {
        const value = cellarValue(ledger)
        if (value.totalPence === 0 && value.unvaluedCount === 0) return null
        return (
          <section className="card">
            <div className="card-head">
              <h2>What is down there</h2>
              <span className="hint">at what it cost</span>
            </div>
            <div className="zrow">
              <span className="zname">
                Money in the cellar
                <small>stock on hand, valued at the invoice price</small>
              </span>
              <strong className="num" style={{ fontSize: 20 }}>{formatMoney(value.totalPence)}</strong>
            </div>
            {value.lines
              .filter((l) => l.pence !== null && l.pence > 0)
              .slice(0, 6)
              .map((l) => (
                <div className="zrow" key={l.item.id}>
                  <span className="zname">
                    {l.item.name}
                    <small>{describeStock(l.baseUnits, l.item)}</small>
                  </span>
                  <span className="num">{formatMoney(l.pence as number)}</span>
                </div>
              ))}
            {value.unvaluedCount > 0 && (
              <p className="note warn">
                {value.unvaluedCount} {value.unvaluedCount === 1 ? 'line has' : 'lines have'} stock but no
                cost entered, so the real figure is higher than this. Put the invoice prices in under
                “What it costs”.
              </p>
            )}
          </section>
        )
      })()}

      {panel === 'levels' && config.items.length > 0 && (() => {
        const days = health?.sinceDays ?? 1
        const slow = health?.dead ?? []
        if (slow.length === 0) return null
        return (
          <section className="card">
            <div className="card-head">
              <h2>Not earning its keep</h2>
              <span className="hint">over {days} days</span>
            </div>
            <div className="table-wrap">
              <table className="data crew">
                <thead>
                  <tr>
                    <th scope="col">Line</th>
                    <th scope="col">A week</th>
                    <th scope="col">On hand</th>
                    <th scope="col">Lasts</th>
                    <th scope="col">Tied up</th>
                  </tr>
                </thead>
                <tbody>
                  {slow.map((l) => (
                    <tr key={l.item.id}>
                      <th scope="row">
                        {l.item.name}
                        <br />
                        <span className="hint">{l.reason === 'not selling' ? 'barely sells' : 'too much ordered'}</span>
                      </th>
                      <td className="num">{l.perWeek}</td>
                      <td className="num">{describeStock(l.onHandBaseUnits, l.item)}</td>
                      <td className="num">{l.weeksOfCover === null ? '—' : `${l.weeksOfCover}w`}</td>
                      <td className="num">{l.tiedUpPence === null ? '—' : formatMoney(l.tiedUpPence)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="note">
              Two different problems. “Barely sells” is a listing decision — under {2} a week, it may
              not be worth the space at all. “Too much ordered” is an ordering one: the beer is fine,
              there is just over two months of it downstairs. The column that matters is the last one.
            </p>
          </section>
        )
      })()}

      {panel === 'levels' && result.length > 0 && (
        <section className="card">
          <div className="card-head">
            <h2>Last stock take</h2>
            <span className="hint">
              {formatShort(previousCount?.date ?? '')} to {formatShort(latestCount?.date ?? '')}
            </span>
          </div>
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Line</th>
                  <th scope="col">Should be</th>
                  <th scope="col">Was</th>
                  <th scope="col">Out by</th>
                </tr>
              </thead>
              <tbody>
                {result.map((v) => (
                  <tr key={v.item.id}>
                    <th scope="row">{v.item.name}</th>
                    <td className="num">{formatServings(v.expectedBaseUnits, v.item)}</td>
                    <td className="num">{formatServings(v.actualBaseUnits ?? 0, v.item)}</td>
                    <td className={`num delta ${(v.varianceBaseUnits ?? 0) < 0 ? 'short' : 'over'}`}>
                      {formatServingsSigned(v.varianceBaseUnits ?? 0, v.item)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="note">
            Short here is stock that left the cellar without going through the till. Before reading it
            that way, though: spillage, line cleaning, a wrong pour setting and a missed delivery all
            land in this column too, and all of them are commoner than the alternative.
          </p>
        </section>
      )}

      {deliveries.length > 0 && panel === 'levels' && (
        <section className="card">
          <div className="card-head"><h2>Deliveries</h2></div>
          <div className="day-list">
            {deliveries.slice(0, 6).map((d) => (
              <div className="zrow" key={d.id}>
                <span className="zname">
                  {formatShort(d.date)}
                  <small>{d.lines.length} lines</small>
                </span>
              </div>
            ))}
          </div>
        </section>
      )}

      {toast && <div className="toast" role="status">{toast}</div>}
    </div>
  )
}
