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
import { useToast } from '../components/toast.ts'
import { dayStats, itemTotals } from '../core/analytics.ts'
import { addDays, formatShort, tradingDayKey } from '../core/date.ts'
import {
  basisKeepsUnit,
  basisOf,
  cellarHealth,
  deliveryLinesFrom,
  describeStock,
  proposeDelivery,
  proposePours,
  combineStockLines,
  linesNothingSells,
  type PourProposal,
  formatServings,
  formatServingsSigned,
  LOW_NIGHTS,
  takeDue,
  takeWeeks,
  TAKE_EVERY_DAYS,
  measureOf,
  inMeasures,
  type Measure,
  presetBaseUnits,
  presetsFor,
  presetSizeText,
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
import { ChartCard, VarianceChart } from '../components/charts.tsx'
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
import { formatMoney, formatSigned, parsePence, penceToInput } from '../core/money.ts'

type Panel = 'levels' | 'weeks' | 'delivery' | 'count' | 'scales' | 'costs' | 'setup'

/**
 * The panels, and which of them earn a place on the screen every time.
 *
 * `deep` is not "advanced" — the scales and the costs are as plain as the rest.
 * It means "not this week": a thing she reaches for now and then, which a row of
 * seven equal chips turns into a thing she has to read past every time.
 */
const PANELS: { key: Panel; label: string; deep?: true }[] = [
  { key: 'levels', label: 'What’s down there' },
  { key: 'count', label: 'Stock take' },
  { key: 'delivery', label: 'Delivery in' },
  { key: 'weeks', label: 'Week by week', deep: true },
  { key: 'scales', label: 'The scales', deep: true },
  { key: 'costs', label: 'What it costs', deep: true },
  { key: 'setup', label: 'Set up', deep: true },
]

/**
 * What one serving of a line built from the till is.
 *
 * The guess already says which of the four it is — a pint off a tap, a
 * millilitre out of a bottle, a bottle off a shelf, a unit off the rack — so
 * there is nothing left to work out here. Wine sold at three measures out of
 * one bottle needs no special case any more: all three are millilitres of the
 * same bottle.
 */
function servingFor(guess: PourGuess): Pick<StockItem, 'kind' | 'servingBaseUnits' | 'servingName'> {
  return servingOf(guess.servingName as Basis)
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
/** The two choices that are not a cellar line. */
const NEW_LINE = '\u0000new'
const NOT_STOCK = '\u0000none'

function idFor(name: string): string {
  return name.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'item'
}

export function Stock({ onChanged }: { onChanged: () => void }) {
  const [panel, setPanel] = useState<Panel>('levels')
  /** The four panels that are not a weekly job, kept behind one chip. */
  const [deeper, setDeeper] = useState(false)
  // The workings behind what is left: counted, came in, poured. Three more
  // columns, and six will not go on a phone — so the answer is on the screen
  // and the sum behind it is a tap.
  const [workings, setWorkings] = useState(false)
  const [config, setConfig] = useState<StockConfig | null>(null)
  const [deliveries, setDeliveries] = useState<Delivery[]>([])
  const [counts, setCounts] = useState<Array<{ date: string; lines: Array<{ stockItemId: string; baseUnits: number }> }>>([])
  const [days, setDays] = useState<ReturnType<typeof dayStats>[]>([])
  const [book, setBook] = useState<PriceBookEntry[]>([])
  const [toast, say] = useToast(4000)

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

  /**
   * Change how a line is counted: pints, millilitres, bottles or units.
   *
   * Reading till names gets most lines right, but not all of them, and a line
   * left on the wrong one is wrong in the cellar figures every week after.
   *
   * Moving between the two poured ways keeps everything, because a firkin is
   * 40,896 millilitres whether the line is counted in pints or in millilitres,
   * and so is every count ever taken of it. Moving between poured and counted
   * cannot: 700 stops meaning millilitres and starts meaning bottles, so the
   * unit and the cost go, and the toast says so.
   */
  async function setMeasure(item: StockItem, basis: Basis) {
    if (!config) return
    const from = basisOf(item)
    if (basis === from) return
    const shape = servingOf(basis)
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
    // the moment the serving changes: 72 pints is 40,896 millilitres of the
    // same barrel. Dropped rather than blanked: a draft of '' is still a draft,
    // and would sit in front of the stored figure instead of letting it through.
    setDrafts((d) => {
      const rest = { ...d }
      delete rest[`${item.id}:size`]
      if (!keeps) delete rest[`${item.id}:price`]
      return rest
    })
    await saveStockConfig(next)
    onChanged()
    say(
      keeps
        ? `${item.name} is now counted in ${pluralServing(shape.servingName)}.`
        : `${item.name} is now counted in ${pluralServing(shape.servingName)} — set its unit and cost again.`,
    )
  }

  /**
   * What one sale takes off the cellar.
   *
   * Entered in the line's own units — pints off a tap, millilitres out of a
   * bottle — so a double is plainly 60 where a single is 30. Guessed off the till's
   * names to begin with, and wrong often enough — a double, a schooner, a
   * jug — that leaving it unfixable would quietly cost the stock figures every
   * week.
   */
  /**
   * The measure the bar pours, in millilitres.
   *
   * Nothing in the cellar is held in shots, so changing this never moves a
   * single figure: it is what a count in millilitres is read back as, and
   * where a newly guessed spirit line starts.
   */
  async function setHouseMeasure(text: string) {
    if (!config) return
    setDrafts((d) => ({ ...d, 'house:measure': text }))
    const ml = Number(text.trim())
    if (!Number.isFinite(ml) || ml <= 0 || ml > 500) return
    const next = { ...config, mlPerShot: Math.round(ml) }
    setConfig(next)
    await saveStockConfig(next)
    onChanged()
  }

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
        const name = patch.name ?? i.container?.name ?? 'container'
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
            ...(config.notStock ? { notStock: config.notStock } : {}),
          })
        : null,
    [config, counts, deliveries, days],
  )

  // Built once: rebuilding it inside the items loop made every keystroke in a
  // cost box do items-times-book work for the same answer.
  const priceIndex = useMemo(() => buildIndex(book), [book])

  /**
   * The serve each line counted in millilitres goes out in, so a count can be
   * read back as shots without anybody typing a shot anywhere.
   */
  const measures = useMemo(() => {
    const out = new Map<string, Measure>()
    for (const item of config?.items ?? []) {
      const m = measureOf(item, config?.pours ?? [])
      if (m) out.set(item.id, m)
    }
    return out
  }, [config])
  const latestCount = counts[0]
  const previousCount = counts[1]
  const since = health?.since ?? addDays(tradingDayKey(), -7)
  const ledger = health?.ledger ?? []
  const result = health?.gapLines ?? []

  /**
   * The cellar as the till has left it, turned into the two things worth
   * knowing: what to order, and how long everything else has.
   */
  const runwayLines = health?.runway ?? []
  const runwayById = useMemo(() => new Map(runwayLines.map((r) => [r.item.id, r])), [runwayLines])
  const lowLines = runwayLines.filter((r) => r.nightsLeft <= LOW_NIGHTS)
  /** Sales in this window with no pour, which are what make the figures lie. */
  const windowUnmapped = health?.unmapped ?? []

  /**
   * The weekly rhythm: when the next take is due, and every window two takes
   * have closed between them.
   *
   * The run of windows is the series worth having. One week £40 light is a
   * miscount; six weeks light in a row, worsening, is a problem with a name.
   */
  const due = useMemo(() => takeDue(counts, tradingDayKey()), [counts])
  const takes = useMemo(
    () =>
      config
        ? takeWeeks({
            items: config.items,
            pours: config.pours,
            counts,
            deliveries,
            days,
            costOfServing: costOf,
            ...(config.notStock ? { notStock: config.notStock } : {}),
          })
        : [],
    [config, counts, deliveries, days],
  )
  /** Oldest first for the chart, so time runs left to right as it reads. */
  const takeRun = useMemo(
    () =>
      [...takes]
        .reverse()
        .filter((w) => w.gapPence !== null)
        .map((w) => ({ date: w.until, label: formatShort(w.until), variancePence: w.gapPence as number })),
    [takes],
  )

  /**
   * Every sold line that takes nothing off the cellar yet, with a proposal.
   *
   * The join the whole thing hangs off: until a sold line knows which cellar
   * line it draws on, a receipt takes nothing off the stock and the figures
   * quietly read high. A cellar counted onto paper first has no till codes on
   * it at all, so every line starts here.
   */
  const toMatch = useMemo(() => {
    if (!config) return []
    const sold = itemTotals(days).map((i) => ({ code: i.code, name: i.name, qtyMilli: i.qtyMilli }))
    return proposePours(sold, config.items, config.pours, config.mlPerShot, bestMatch, config.notStock)
  }, [config, days])

  /** What each row has been set to, before any of it is saved. */
  const [picks, setPicks] = useState<Record<string, string>>({})

  /**
   * Cellar lines nothing on the till draws on.
   *
   * The mirror of an unmapped sale, and just as quiet: a line no pour points
   * at can never go down, so it sits at whatever it was last counted at
   * looking like stock that never moves.
   */
  const orphans = useMemo(() => (config ? linesNothingSells(config.items, config.pours) : []), [config])
  const [totalling, setTotalling] = useState<Set<string>>(new Set())
  const [totalName, setTotalName] = useState('')

  /**
   * Count several lines the way the till sells them.
   *
   * Six flavours of crisp come into the cellar and go out through one button;
   * five fruit beers go out through FRUIT BEER. Counted apart, no sale can
   * come off any of them without somebody guessing which one went.
   */
  async function totalLines() {
    if (!config) return
    const from = [...totalling]
    if (from.length < 2) return say('Pick at least two lines to total together.')
    const name = totalName.trim() || (config.items.find((i) => i.id === from[0])?.name ?? 'Total')
    const kinds = new Set(from.map((id) => config.items.find((i) => i.id === id)?.kind))
    if (kinds.size > 1) {
      return say('Those are counted in different ways, so they cannot be totalled into one line.')
    }
    const out = combineStockLines({
      items: config.items,
      pours: config.pours,
      counts,
      deliveries,
      into: { id: idFor(name), name },
      from,
    })
    const next = { ...config, items: out.items, pours: out.pours }
    setConfig(next)
    await saveStockConfig(next)
    for (const c of out.counts) await saveStockCount(c)
    for (const d of out.deliveries) await saveDelivery(d)
    setCounts(await listStockCounts().catch(() => counts))
    setDeliveries(await listDeliveries().catch(() => deliveries))
    setTotalling(new Set())
    setTotalName('')
    onChanged()
    say(`${out.absorbed.length + 1} lines are now counted as one — “${name}”.`)
  }
  const pickFor = (row: PourProposal): string =>
    picks[row.itemCode] ?? (row.stockItemId ?? (row.how === 'new' ? NEW_LINE : ''))

  /**
   * Save the lot. One button rather than one per row: forty lines off a till
   * roll is forty taps, and the proposals are right often enough that
   * checking them and accepting them together is the honest shape of the job.
   */
  async function keepPours() {
    if (!config) return
    const items = new Map(config.items.map((i) => [i.id, i]))
    const pours: Pour[] = [...config.pours]
    const notStock = [...(config.notStock ?? [])]
    let made = 0
    let tied = 0

    for (const row of toMatch) {
      const pick = pickFor(row)
      if (pick === '') continue
      if (pick === NOT_STOCK) {
        notStock.push(row.itemCode)
        continue
      }
      const takes = parseFloat(drafts[`match:${row.itemCode}`] ?? '')
      let id = pick
      if (pick === NEW_LINE) {
        const shape = servingFor(row.guess)
        id = idFor(row.guess.stockName)
        // A new line whose id is taken by a line counted another way gets its
        // own, for the same reason the matcher will not cross kinds.
        if (items.has(id) && items.get(id)!.kind !== shape.kind) {
          id = `${id}-${shape.kind === 'count' ? 'bottled' : 'draught'}`
        }
        if (!items.has(id)) {
          items.set(id, { id, name: row.guess.stockName, ...shape })
          made++
        }
      }
      const item = items.get(id)
      // Typed in the line's own servings — "1" pint, "175" ml — and held in
      // base units, which is the only place a unit is ever assumed.
      const baseUnits =
        Number.isFinite(takes) && takes > 0 && item ? Math.round(takes * item.servingBaseUnits) : row.baseUnits
      pours.push({ itemCode: row.itemCode, itemName: row.itemName, stockItemId: id, baseUnits })
      tied++
    }

    const next: StockConfig = {
      ...config,
      items: [...items.values()].sort((a, b) => a.name.localeCompare(b.name)),
      pours,
      ...(notStock.length > 0 ? { notStock } : {}),
    }
    setConfig(next)
    await saveStockConfig(next)
    setPicks({})
    setDrafts({})
    onChanged()
    say(
      tied === 0
        ? 'Nothing tied up — every line was left blank.'
        : `${tied} till ${tied === 1 ? 'line' : 'lines'} now come off the cellar${made > 0 ? `, ${made} of them on new lines` : ''}.`,
    )
  }

  if (config === null) return <div className="main"><p className="note"><span className="spinner" /> Loading…</p></div>

  // --- setting the cellar up from the till's own item list -------------------
  async function buildFromTill() {
    const seen = itemTotals(days)
    if (seen.length === 0) return say('No items yet — scan a till roll with its item list first.')

    const items = new Map<string, StockItem>(config!.items.map((i) => [i.id, i]))
    const pours: Pour[] = [...config!.pours]

    // Matched against the cellar that already exists before anything is
    // invented. A cellar counted onto paper calls the drink "Taddy Lager" and
    // the till calls it "PINT TADDY LAGER"; making a second line for it would
    // split the stock in two, which is worse than doing nothing.
    for (const row of proposePours(seen, config!.items, pours, config!.mlPerShot, bestMatch, config!.notStock)) {
      let id = row.stockItemId
      if (id === null) {
        const shape = servingFor(row.guess)
        const plain = idFor(row.guess.stockName)
        const existing = items.get(plain)
        const clash = existing !== undefined && existing.kind !== shape.kind
        id = clash ? `${plain}-${shape.kind === 'count' ? 'bottled' : 'draught'}` : plain
        if (!items.has(id)) {
          const name = clash
            ? `${row.guess.stockName} (${shape.kind === 'count' ? 'bottled' : 'draught'})`
            : row.guess.stockName
          items.set(id, { id, name, ...shape })
        }
      }
      pours.push({ itemCode: row.itemCode, itemName: row.itemName, stockItemId: id, baseUnits: row.baseUnits })
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
      {/* Three chips, not seven. What is down there is the answer she came
          for; the stock take and a delivery are the two things she does to it.
          Week by week, the scales, the costs and the set-up are all real — and
          all things she wants perhaps monthly, which is not a reason to put
          them on the screen every time.

          They sit on the page rather than in a card headed "The cellar", which
          is what the lit tab says already. */}
      <div className="chip-row">
        {PANELS.filter((p) => !p.deep || deeper || panel === p.key).map((p) => (
          <button
            key={p.key}
            type="button"
            className="chip"
            aria-pressed={panel === p.key}
            onClick={() => {
              setPanel(p.key)
              setDrafts({})
              // A delivery is dated the day it arrives. A stock take is dated
              // the trading day it draws a line under, which for a count done
              // before opening is last night's.
              if (p.key === 'count') setSheetDate(countDate())
              else if (p.key === 'delivery') setSheetDate(tradingDayKey())
            }}
          >
            {p.label}
          </button>
        ))}
        {!deeper && (
          <button type="button" className="chip" onClick={() => setDeeper(true)} data-testid="cellar-more">
            More…
          </button>
        )}
      </div>

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

      {/* --- the cellar as the till has left it ------------------------------ */}
      {panel === 'levels' && config.items.length > 0 && (
        <>
          {/* The weekly take, where she is already looking. Between takes these
              figures are the till's word for it, and that is worth saying at
              the top of the screen that shows them rather than on a tab she
              has to go and find. */}
          {due.overdueDays > 0 && (
            <section className="card">
              <p className="note warn" style={{ marginTop: 0, marginBottom: 10 }}>
                <strong>
                  The stock take is {due.overdueDays} {due.overdueDays === 1 ? 'day' : 'days'} overdue.
                </strong>{' '}
                Everything below is what the till says should be down there. A take is what turns it
                into what is.
              </p>
              <button type="button" className="btn-small" data-testid="take-due" onClick={() => setPanel('weeks')}>
                Settle the week
              </button>
            </section>
          )}

          {lowLines.length > 0 && (
            <section className="card">
              <div className="card-head">
                <h2>Worth ordering</h2>
                <span className="hint">at the rate it is going</span>
              </div>
              <ul className="low-list">
                {lowLines.map((r) => (
                  <li key={r.item.id}>
                    <span className="low-name">{r.item.name}</span>
                    <span className="low-left num">
                      {r.leftBaseUnits <= 0 ? 'out' : formatServings(r.leftBaseUnits, r.item)}
                    </span>
                    <span className={`low-days${r.nightsLeft <= 1 ? ' urgent' : ''}`}>
                      {r.leftBaseUnits <= 0
                        ? 'out — more has been poured than was booked in'
                        : r.nightsLeft <= 0
                          ? 'not enough for another night'
                          : r.nightsLeft === 1
                            ? 'about one more night'
                            : `about ${r.nightsLeft} more nights`}
                    </span>
                  </li>
                ))}
              </ul>
              <p className="note" style={{ marginBottom: 0 }}>
                Nights of trade, at the rate poured over the {health?.readNights ?? 0}{' '}
                {health?.readNights === 1 ? 'night' : 'nights'} read since {formatShort(since)}.
              </p>
            </section>
          )}

          <section className="card">
            <div className="card-head">
              <h2>What’s down there now</h2>
              <span className="hint">
                {config.items.length} lines ·{' '}
                {health?.through ? `till read to ${formatShort(health.through)}` : `since ${formatShort(since)}`}
              </span>
            </div>
            <p className="note" style={{ marginTop: 0 }}>
              Last stock take, plus what came in, less what the till poured. It moves as each
              receipt is read.
            </p>
            <div className="table-wrap">
              <table className="data">
                {/* Three columns, which is what fits: the line, what is
                    left of it, and how many more nights that is. The sum it
                    came from — counted, came in, poured — opens underneath.
                    Six columns fit nothing but a laptop, and a sideways
                    scrollbar across the one answer she came for is not a
                    layout, it is a hiding place. */}
                <thead>
                  <tr>
                    <th scope="col">Line</th>
                    <th scope="col">Left</th>
                    <th scope="col">Nights</th>
                    {workings && (
                      <>
                        <th scope="col">Counted</th>
                        <th scope="col">In</th>
                        <th scope="col">Poured</th>
                      </>
                    )}
                  </tr>
                </thead>
                <tbody>
                  {ledger
                    .filter((l) => l.countedBaseUnits || l.deliveredBaseUnits || l.pouredBaseUnits)
                    .map((l) => {
                      const r = runwayById.get(l.item.id)
                      return (
                        <tr key={l.item.id}>
                          <th scope="row">{l.item.name}</th>
                          {l.counted ? (
                            <td className={`num delta ${l.expectedBaseUnits < 0 ? 'short' : ''}`} title={describeStock(l.expectedBaseUnits, l.item)}>
                              {formatServings(l.expectedBaseUnits, l.item)}
                              {measures.has(l.item.id) && (
                                <small className="in-serves">{inMeasures(l.expectedBaseUnits, measures.get(l.item.id) as Measure)}</small>
                              )}
                            </td>
                          ) : (
                            <td className="num faint">—</td>
                          )}
                          {/* No rate, no figure: a line nothing has poured is
                              not "lasts forever", it is simply unknown. */}
                          <td className={`num${r && r.nightsLeft <= LOW_NIGHTS ? ' short' : ' faint'}`}>
                            {r === undefined
                              ? '—'
                              : r.leftBaseUnits <= 0
                                ? 'out'
                                : r.nightsLeft <= 0
                                  ? '<1'
                                  : `${r.nightsLeft}n`}
                          </td>
                          {workings && (
                            <>
                              <td className={l.counted ? 'num' : 'num faint'}>
                                {l.counted ? formatServings(l.countedBaseUnits, l.item) : 'not counted'}
                              </td>
                              <td className="num">{formatServings(l.deliveredBaseUnits, l.item)}</td>
                              <td className="num">{formatServings(l.pouredBaseUnits, l.item)}</td>
                            </>
                          )}
                        </tr>
                      )
                    })}
                </tbody>
              </table>
            </div>
            <div className="alts">
              <button
                type="button"
                className="btn-small"
                data-testid="workings"
                onClick={() => setWorkings((v) => !v)}
              >
                {workings ? 'Hide the workings' : 'Show the workings'}
              </button>
            </div>
            {(health?.nightsWithoutItems ?? 0) > 0 && (
              <p className="note warn">
                {health!.nightsWithoutItems}{' '}
                {health!.nightsWithoutItems === 1 ? 'night has' : 'nights have'} a receipt with no
                item list on it, so nothing came off the cellar for{' '}
                {health!.nightsWithoutItems === 1 ? 'it' : 'them'}. The item list is the part of the
                roll that names each drink — photograph that part too and these figures come right.
              </p>
            )}
            {windowUnmapped.length > 0 && (
              <>
                <p className="note bad">
                  The till sold {windowUnmapped.length}{' '}
                  {windowUnmapped.length === 1 ? 'line' : 'lines'} the cellar knows nothing about, so
                  nothing came off for {windowUnmapped.length === 1 ? 'it' : 'them'} and these figures
                  read high: {windowUnmapped.slice(0, 4).map((u) => u.name).join(', ')}
                  {windowUnmapped.length > 4 ? ` and ${windowUnmapped.length - 4} more` : ''}.
                </p>
                <div className="alts">
                  <button type="button" className="btn-small" data-testid="fix-pours" onClick={() => setPanel('setup')}>
                    Set what those pour
                  </button>
                </div>
              </>
            )}
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
        </>
      )}

      {/* --- the weekly rhythm ----------------------------------------------- */}
      {panel === 'weeks' && config.items.length > 0 && (
        <>
          <section className="card">
            <div className="card-head">
              <h2>The stock take</h2>
              <span className="hint">every {TAKE_EVERY_DAYS} days</span>
            </div>
            {due.last === null ? (
              <p className="note" style={{ marginTop: 0, marginBottom: 10 }}>
                No stock take yet. The first one is a starting point rather than a judgement —
                nothing can be measured until there is a second.
              </p>
            ) : (
              <p className="note" style={{ marginTop: 0, marginBottom: 10 }}>
                <strong>
                  {due.overdueDays > 0
                    ? `Due ${due.overdueDays} ${due.overdueDays === 1 ? 'day' : 'days'} ago.`
                    : `Next one due ${formatShort(due.dueOn as string)}.`}
                </strong>{' '}
                The last was {formatShort(due.last)}
                {due.sinceDays !== null && `, ${due.sinceDays} ${due.sinceDays === 1 ? 'day' : 'days'} ago`}. Between
                takes the cellar is the till’s word for it; a take is what settles it.
              </p>
            )}
            <button type="button" className="btn-small" data-testid="do-take" onClick={() => { setPanel('count'); setDrafts({}); setSheetDate(countDate()) }}>
              {due.last === null ? 'Take the first one' : 'Do this week’s'}
            </button>
          </section>

          {takeRun.length > 0 && (
            <ChartCard
              title="Every week, settled"
              subtitle="what the count said against what the till said, at cost"
            >
              <VarianceChart points={takeRun} />
              <p className="note">
                Below the line is stock that left without going through the till. One week out is a
                miscount. A run of them, getting worse, is something else.
              </p>
            </ChartCard>
          )}

          {takes.length === 0 ? (
            <section className="card">
              <p className="note" style={{ marginTop: 0, marginBottom: 0 }}>
                Two takes make a week that can be judged: what the first said, plus what came in,
                less what the till poured, against what the second found. There{' '}
                {counts.length === 1 ? 'is one take so far' : 'are no takes yet'}.
              </p>
            </section>
          ) : (
            // The most recent week in full, the ones behind it as a line each.
            // Eight cards of tables is a scroll nobody finishes, and the chart
            // above already carries the shape of the run.
            takes.slice(0, 1).map((w) => (
              <section className="card" key={w.until}>
                <div className="card-head">
                  <h2>{formatShort(w.since)} to {formatShort(w.until)}</h2>
                  <span className="hint">
                    {w.days === TAKE_EVERY_DAYS ? 'a week' : `${w.days} days`} · {w.rollNights}{' '}
                    {w.rollNights === 1 ? 'night read' : 'nights read'}
                  </span>
                </div>
                <div className="zrow">
                  <span className="zname">
                    Out by
                    <small>at what the stock cost</small>
                  </span>
                  <strong className={`num ${w.gapPence === null ? '' : w.gapPence < 0 ? 'short' : 'over'}`}>
                    {w.gapPence === null ? 'nothing to judge' : w.gapPence === 0 ? 'nothing — it settled' : formatSigned(w.gapPence)}
                  </strong>
                </div>
                {w.lines.filter((v) => v.varianceBaseUnits !== 0).length > 0 && (
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
                        {w.lines.filter((v) => v.varianceBaseUnits !== 0).slice(0, 12).map((v) => (
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
                )}
                {/* Each week's own blind spots, not the cellar's. A week whose
                    item list went missing has a gap that is partly explained,
                    and one that did not has not — and reading a variance
                    without knowing which is how a clean cellar gets somebody
                    accused. */}
                {(w.nightsWithoutItems > 0 || w.unmapped.length > 0) && (
                  <p className="note warn">
                    Part of this gap is not shrinkage but blindness:{' '}
                    {w.nightsWithoutItems > 0 &&
                      `${w.nightsWithoutItems} ${w.nightsWithoutItems === 1 ? 'night' : 'nights'} had a receipt with no item list on it`}
                    {w.nightsWithoutItems > 0 && w.unmapped.length > 0 && ', and '}
                    {w.unmapped.length > 0 &&
                      `${w.unmapped.length} sold ${w.unmapped.length === 1 ? 'line has' : 'lines have'} no pour set (${w.unmapped.slice(0, 3).map((u) => u.name).join(', ')})`}
                    . Nothing came off the cellar for{' '}
                    {w.nightsWithoutItems > 0 && w.unmapped.length > 0 ? 'either' : 'that'}, so this
                    week reads shorter than it was.
                  </p>
                )}
              </section>
            ))
          )}

          {takes.length > 1 && (
            <section className="card">
              <div className="card-head">
                <h2>The weeks before</h2>
                <span className="hint">{takes.length - 1} settled</span>
              </div>
              <div className="day-list">
                {takes.slice(1, 14).map((w) => (
                  <div className="zrow" key={w.until}>
                    <span className="zname">
                      {formatShort(w.since)} to {formatShort(w.until)}
                      <small>
                        {w.days === TAKE_EVERY_DAYS ? 'a week' : `${w.days} days`} · {w.rollNights}{' '}
                        {w.rollNights === 1 ? 'night read' : 'nights read'}
                        {(w.nightsWithoutItems > 0 || w.unmapped.length > 0) && ' · partly blind'}
                      </small>
                    </span>
                    <strong className={`num ${w.gapPence === null ? '' : w.gapPence < 0 ? 'short' : 'over'}`}>
                      {w.gapPence === null ? '—' : w.gapPence === 0 ? 'settled' : formatSigned(w.gapPence)}
                    </strong>
                  </div>
                ))}
              </div>
            </section>
          )}
        </>
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
            measures={measures}
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
            // A line counted in millilitres is costed by the serve it pours,
            // because a tenth of a penny a millilitre tells nobody anything.
            const measure = measures.get(item.id)
            const perServing = costOf(item, measure ? measure.ml : item.servingBaseUnits)
            const perServingName = measure ? measure.name : item.servingName
            const presets = presetsFor(item)
            const pour = config.pours.find((p) => p.stockItemId === item.id)
            const sell = pour ? lookup(priceIndex, { code: pour.itemCode, name: pour.itemName }) : undefined
            const pourCost = pour ? costOf(item, pour.baseUnits) : null
            const gp = sell && pourCost !== null ? margin(sell.pence, pourCost) : null
            const sizeText = drafts[sizeKey] ?? (servings ? String(servings) : '')
            const basis = basisOf(item)

            return (
              <div className="stock-line" key={item.id}>
                <div className="stock-line-head">
                  <strong>{item.name}</strong>
                  <span className="hint">
                    {perServing === null
                      ? `not costed · per ${perServingName}`
                      : `${formatMoney(perServing)} a ${perServingName}`}
                    {gp && ` · ${(gp.gpBp / 100).toFixed(1)}% GP`}
                  </span>
                </div>
                <div className="stock-line-row">
                  <select
                    aria-label={`${item.name} measured in`}
                    value={basis}
                    onChange={(e) => void setMeasure(item, e.target.value as Basis)}
                  >
                    <option value="pint">pints</option>
                    <option value="ml">millilitres</option>
                    <option value="bottle">bottles</option>
                    <option value="unit">units</option>
                  </select>
                  <select
                    aria-label={`${item.name} container`}
                    value={presets.some((c) => c.name === item.container?.name) ? item.container!.name : ''}
                    onChange={(e) => {
                      const preset = presets.find((c) => c.name === e.target.value)
                      // Every preset knows its own size — a firkin is 72 pints
                      // of beer, a 70cl bottle is 700ml — so it lands in this
                      // line's own units whichever way the line is counted.
                      const size = preset
                        ? String(Math.round((presetBaseUnits(preset, item) / item.servingBaseUnits) * 100) / 100)
                        : ''
                      void setLine(item, { ...(preset ? { name: preset.name, sizeText: size } : { name: '' }) })
                    }}
                  >
                    <option value="">container…</option>
                    {presets.map((c) => (
                      <option key={c.name} value={c.name}>
                        {c.name} ({presetSizeText(c)})
                      </option>
                    ))}
                  </select>
                  <span className="stock-field stock-size">
                    <input
                      aria-label={`${item.name} servings per container`}
                      inputMode="numeric"
                      placeholder={basis === 'pint' ? '72' : basis === 'ml' ? '700' : '1'}
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
                    <small>a {item.container?.name ?? 'container'}</small>
                  </span>
                </div>
              </div>
            )
          })}

          <p className="note">
            Four ways to count and no more: the tap beers in pints, anything poured out of a bottle
            in millilitres, the bottled drinks — the juices, the mixers, the alcohol-free — by the
            bottle, and everything else in units. Change it on a line the till's own names sent the
            wrong way; moving between poured and counted clears its unit and cost, since they were
            measured the old way.
          </p>
          <p className="note">
            Shots are never typed. A bottle counted at 350ml is read back in whatever the till takes
            off that line — {Math.round((350 / config.mlPerShot) * 10) / 10} at a {config.mlPerShot}ml
            single — so what gets written down is exact, and the shots work themselves out. What each
            sale takes is under Set up.
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
          <div className="field">
            <label htmlFor="house-measure">The measure this bar pours</label>
            <span className="stock-field">
              <input
                id="house-measure"
                aria-label="The house measure"
                inputMode="numeric"
                value={drafts['house:measure'] ?? String(config.mlPerShot)}
                onChange={(e) => void setHouseMeasure(e.target.value)}
              />
              <small>ml a shot</small>
            </span>
            <p className="help">
              What a single spirit goes out as. Nothing in the cellar is held in shots, so changing
              it moves no figure: it is where a line newly guessed off the till starts. What a count
              in millilitres is read back as comes from whatever the till takes off that line, in
              the table below — so a line the till has never sold stays in millilitres.
            </p>
          </div>
          {/* The join the whole thing hangs off. A cellar counted onto paper
              has no till codes on it, so until these are tied up a receipt
              takes nothing off the stock at all. */}
          {toMatch.length > 0 ? (
            <div className="match-pours">
              <div className="card-head" style={{ marginTop: 18 }}>
                <h2>Tie the till to the cellar</h2>
                <span className="hint">{toMatch.length} to go</span>
              </div>
              <p className="note warn" style={{ marginTop: 0 }} data-testid="to-match">
                {toMatch.length} sold {toMatch.length === 1 ? 'line takes' : 'lines take'} nothing off
                the cellar. Each one below has been matched to the line it looks like — check them and
                save, and from then on every receipt comes off the stock on its own.
              </p>
              <ul className="match-list">
                {toMatch.slice(0, 40).map((row) => {
                  const chosen = pickFor(row)
                  const item = config.items.find((i) => i.id === chosen)
                  return (
                    <li key={row.itemCode}>
                      <span className="match-sold">
                        {row.itemName}
                        <small>
                          {row.how === 'matched'
                            ? 'matched to a line you already have'
                            : row.how === 'ambiguous'
                              ? `could be ${row.between.map((i) => i.name).join(' or ')}`
                              : 'nothing like it in the cellar'}
                        </small>
                      </span>
                      <select
                        aria-label={`${row.itemName} comes off`}
                        data-testid={`match-${row.itemCode}`}
                        value={chosen}
                        onChange={(e) => setPicks((p) => ({ ...p, [row.itemCode]: e.target.value }))}
                      >
                        <option value="">leave it for now</option>
                        <option value={NEW_LINE}>new line — {row.guess.stockName}</option>
                        <option value={NOT_STOCK}>not cellar stock</option>
                        {config.items.map((i) => (
                          <option key={i.id} value={i.id}>{i.name}</option>
                        ))}
                      </select>
                      {chosen !== '' && chosen !== NOT_STOCK && (
                        <span className="stock-field match-takes">
                          <small>takes</small>
                          <input
                            aria-label={`${row.itemName} takes`}
                            inputMode="decimal"
                            value={
                              drafts[`match:${row.itemCode}`] ??
                              String(
                                Math.round((row.baseUnits / (item?.servingBaseUnits ?? 1)) * 100) / 100,
                              )
                            }
                            onChange={(e) =>
                              setDrafts((d) => ({ ...d, [`match:${row.itemCode}`]: e.target.value }))
                            }
                          />
                          <small>
                            {pluralServing(
                              item?.servingName ?? (chosen === NEW_LINE ? row.guess.servingName : 'unit'),
                            )}
                          </small>
                        </span>
                      )}
                    </li>
                  )
                })}
              </ul>
              {toMatch.length > 40 && (
                <p className="note">
                  The forty biggest are shown. Save these and the rest come up next.
                </p>
              )}
              <button type="button" className="btn-primary" data-testid="keep-pours" onClick={() => void keepPours()}>
                Save these
              </button>
              <p className="note">
                “Takes” is what one sale removes, in that line’s own units — a pint takes 1, a large
                glass of wine takes 175 millilitres, a single takes {config.mlPerShot}. “Not cellar
                stock” is for the coffee and the room hire: said once, they stop being reported as
                stock that walked.
              </p>
            </div>
          ) : (
            <p className="note" style={{ marginTop: 14 }}>
              Every line the till has sold comes off the cellar. Anything new it starts selling will
              appear here to be tied up.
            </p>
          )}

          {orphans.length > 0 && (
            <>
              <div className="card-head" style={{ marginTop: 22 }}>
                <h2>Nothing sells these</h2>
                <span className="hint">{orphans.length} lines</span>
              </div>
              <p className="note warn" style={{ marginTop: 0 }} data-testid="orphans">
                No sale comes off {orphans.length === 1 ? 'this line' : 'these lines'}, so{' '}
                {orphans.length === 1 ? 'it' : 'they'} can only ever sit at whatever was last
                counted — which looks exactly like stock that never moves. Either the till sells{' '}
                {orphans.length === 1 ? 'it' : 'them'} under a name not tied up yet, or the cellar
                counts {orphans.length === 1 ? 'it' : 'them'} finer than the till sells{' '}
                {orphans.length === 1 ? 'it' : 'them'} — six flavours of crisp behind one button.
              </p>
              <ul className="match-list">
                {orphans.map((i) => (
                  <li key={i.id}>
                    <span className="match-sold">
                      {i.name}
                      <small>counted in {pluralServing(i.servingName)}</small>
                    </span>
                    <label className="total-pick">
                      <input
                        type="checkbox"
                        aria-label={`Total ${i.name} with others`}
                        checked={totalling.has(i.id)}
                        onChange={(e) => {
                          const next = new Set(totalling)
                          if (e.target.checked) next.add(i.id)
                          else next.delete(i.id)
                          setTotalling(next)
                          if (next.size === 1 && totalName.trim() === '') {
                            setTotalName(config.items.find((x) => x.id === [...next][0])?.name ?? '')
                          }
                        }}
                      />
                      <span>total</span>
                    </label>
                  </li>
                ))}
              </ul>
              {totalling.size > 0 && (
                <div className="field">
                  <label htmlFor="total-name">Count those {totalling.size} as one line called</label>
                  <input
                    id="total-name"
                    data-testid="total-name"
                    value={totalName}
                    placeholder="Fruit beer"
                    onChange={(e) => setTotalName(e.target.value)}
                  />
                  <button type="button" className="btn-primary" data-testid="total-lines" onClick={() => void totalLines()}>
                    Total them into one
                  </button>
                  <p className="help">
                    The counts, the deliveries and the pours all move together. A stock take only
                    gets a total where every one of them was counted on it — adding up three
                    counted lines and three blank ones would turn a partial count into a whole one.
                  </p>
                </div>
              )}
            </>
          )}

          <div className="alts">
            <button type="button" className="btn-small" onClick={() => void buildFromTill()}>
              Make a line for everything the till sells
            </button>
          </div>
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
                    <th scope="col">Nights</th>
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
