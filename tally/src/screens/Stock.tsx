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
  cellarHealth,
  containersToBase,
  CONTAINER_SIZES,
  deliveryLinesFrom,
  describeStock,
  proposeDelivery,
  formatServings,
  formatServingsSigned,
  guessPour,
  ML_PER_BOTTLE,
  ML_PER_PINT,
  pourUsage,
  servingsToBase,
  type Delivery,
  type Pour,
  type PourGuess,
  type DeliveryProposal,
  type StockItem,
} from '../core/stock.ts'
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

type Panel = 'levels' | 'delivery' | 'count' | 'costs' | 'setup'

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
): Pick<StockItem, 'kind' | 'servingBaseUnits' | 'servingName'> {
  if (guess.kind !== 'liquid') return { kind: 'count', servingBaseUnits: 1, servingName: 'each' }
  if (guess.servingName === 'pint') return { kind: 'liquid', servingBaseUnits: ML_PER_PINT, servingName: 'pint' }
  if (guess.servingName === 'shot') return { kind: 'liquid', servingBaseUnits: guess.baseUnits, servingName: 'shot' }
  if ((measures?.size ?? 1) > 1) return { kind: 'liquid', servingBaseUnits: ML_PER_BOTTLE, servingName: 'bottle' }
  return { kind: 'liquid', servingBaseUnits: guess.baseUnits, servingName: guess.servingName }
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
   * Set what a container costs and what it holds.
   *
   * Both boxes are kept as typed while she works, because a half-entered "£95
   * for " is not yet a cost and must not be stored as one. The item only gains
   * a cost once both sides are real numbers; clearing either takes it away
   * again, which is how a mistyped price is undone.
   */
  /**
   * Set the unit a line arrives in and what it costs, together.
   *
   * Deliberately one function taking both boxes rather than two taking one
   * each. The cost is a price *per container*, so neither figure means
   * anything without the other — and handling them separately meant typing
   * the price before the size silently threw the price away, because at that
   * moment there was no size to attach it to. Reading both drafts on every
   * keystroke means whichever is typed second completes the pair.
   */
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
    const hasSize = sizeText.trim() !== '' && Number.isFinite(servings) && servings > 0
    const baseUnits = hasSize ? Math.round(servings * item.servingBaseUnits) : 0

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
        return {
          ...bare,
          ...(hasSize ? { container: { name, baseUnits } } : {}),
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

  const vatBp = loadSettings().vatBp
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
        items.set(id, { id, name: guess.stockName, ...servingFor(guess, measuresFor.get(guess.stockName)) })
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
    // both — "two kils and about thirty pints" is one line, not two.
    const lines = (config?.items ?? [])
      .map((item) => {
        const fullText = drafts[`${item.id}:full`] ?? ''
        const looseText = drafts[item.id] ?? ''
        if (fullText.trim() === '' && looseText.trim() === '') return null

        const full = fullText.trim() === '' ? 0 : Number(fullText)
        const loose = looseText.trim() === '' ? 0 : Number(looseText)
        if (!Number.isFinite(full) || !Number.isFinite(loose)) return null

        const baseUnits = item.container
          ? containersToBase(full, loose, item)
          : servingsToBase(loose, item)
        return { stockItemId: item.id, baseUnits }
      })
      .filter((l): l is { stockItemId: string; baseUnits: number } => l !== null)

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
          {(['levels', 'delivery', 'count', 'costs', 'setup'] as const).map((p) => (
            <button key={p} type="button" className="chip" aria-pressed={panel === p} onClick={() => { setPanel(p); setDrafts({}) }}>
              {p === 'levels' ? 'What’s left' : p === 'delivery' ? 'Delivery in' : p === 'count' ? 'Stock take' : p === 'costs' ? 'What it costs' : 'Set up'}
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
                      <td className="num">{formatServings(l.countedBaseUnits, l.item)}</td>
                      <td className="num">{formatServings(l.deliveredBaseUnits, l.item)}</td>
                      <td className="num">{formatServings(l.pouredBaseUnits, l.item)}</td>
                      <td className={`num delta ${l.expectedBaseUnits < 0 ? 'short' : ''}`} title={describeStock(l.expectedBaseUnits, l.item)}>
                        {formatServings(l.expectedBaseUnits, l.item)}
                      </td>
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
          {ledger.some((l) => l.expectedBaseUnits < 0) && (
            <p className="note warn">
              A line has gone below zero, which means more was poured than was ever booked in. Either a
              delivery was missed or the pour is set wrong.
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
          </div>

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

          {config.items.map((item) => {
            const perContainer = item.container
              ? Math.round(item.container.baseUnits / item.servingBaseUnits)
              : 0
            // A container worth counting is one that holds more than a serving.
            const counted = perContainer > 1
            const word = panel === 'delivery' ? 'delivered' : 'counted'
            return (
              <div className="zrow" key={item.id}>
                <span className="zname">
                  {item.name}
                  <small>
                    {counted
                      ? `${item.container!.name}s of ${perContainer}, then loose ${item.servingName}s`
                      : `in ${item.servingName}s`}
                  </small>
                </span>
                {counted && (
                  <input
                    aria-label={`${item.name} ${item.container!.name}s ${word}`}
                    inputMode="decimal"
                    placeholder={item.container!.name}
                    value={drafts[`${item.id}:full`] ?? ''}
                    onChange={(e) => setDrafts((d) => ({ ...d, [`${item.id}:full`]: e.target.value }))}
                  />
                )}
                <input
                  aria-label={`${item.name} ${word}`}
                  inputMode="decimal"
                  placeholder={counted ? item.servingName : '—'}
                  value={drafts[item.id] ?? ''}
                  onChange={(e) => setDrafts((d) => ({ ...d, [item.id]: e.target.value }))}
                />
              </div>
            )
          })}
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
      {panel === 'costs' && config.items.length > 0 && (
        <section className="card">
          <div className="card-head">
            <h2>What it costs</h2>
            <span className="badge">{config.items.filter((i) => i.cost).length} of {config.items.length} costed</span>
          </div>
          <p className="note" style={{ marginTop: 0 }}>
            What the invoice charges, and what that buys. A firkin of Taddy at £95 is £95 for 72 pints.
            Enter it <strong>ex VAT</strong>, the way the invoice shows it — the VAT on the selling price
            is taken off separately, which is the step that otherwise flatters a margin by six points.
          </p>

          {config.items.map((item) => {
            const servings = item.container ? Math.round(item.container.baseUnits / item.servingBaseUnits) : 0
            const priceKey = `${item.id}:price`
            const sizeKey = `${item.id}:size`
            const perServing = costOf(item, item.servingBaseUnits)
            const pour = config.pours.find((p) => p.stockItemId === item.id)
            const sell = pour ? lookup(priceIndex, { code: pour.itemCode, name: pour.itemName }) : undefined
            const pourCost = pour ? costOf(item, pour.baseUnits) : null
            const gp = sell && pourCost !== null ? margin(sell.pence, pourCost, vatBp) : null
            const sizeText = drafts[sizeKey] ?? (servings ? String(servings) : '')

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
                    aria-label={`${item.name} container`}
                    value={CONTAINER_SIZES.some((c) => c.name === item.container?.name) ? item.container!.name : ''}
                    onChange={(e) => {
                      const preset = CONTAINER_SIZES.find((c) => c.name === e.target.value)
                      void setLine(item, {
                        ...(preset ? { name: preset.name, sizeText: String(preset.servings) } : { name: '' }),
                      })
                    }}
                  >
                    <option value="">unit…</option>
                    {CONTAINER_SIZES.map((c) => (
                      <option key={c.name} value={c.name}>
                        {c.name} ({c.servings})
                      </option>
                    ))}
                  </select>
                  <input
                    aria-label={`${item.name} servings per container`}
                    inputMode="numeric"
                    placeholder={item.servingName === 'pint' ? '72' : '1'}
                    value={sizeText}
                    onChange={(e) => void setLine(item, { sizeText: e.target.value })}
                  />
                  <input
                    aria-label={`${item.name} cost`}
                    inputMode="decimal"
                    placeholder="£ —"
                    value={drafts[priceKey] ?? (item.cost ? penceToInput(item.cost.pence) : '')}
                    onChange={(e) => void setLine(item, { priceText: e.target.value })}
                  />
                </div>
              </div>
            )
          })}

          <p className="note">
            The unit is what a delivery arrives as and what the price is for — a kil of Taddy is 144
            pints, a firkin 72. Set it once and the cellar counts in barrels rather than in pints, and
            the invoice price divides itself down. Anything left blank simply has no margin figure;
            nothing is ever assumed to be free.
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
                  return (
                    <tr key={p.itemCode}>
                      <th scope="row">{p.itemName}</th>
                      <td>{item?.name ?? p.stockItemId}</td>
                      <td className="num">{item ? formatServings(p.baseUnits, item) : `${p.baseUnits}`}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <p className="note">
            A pint is taken as 568ml and a shot as {config.mlPerShot}ml, so pints and halves of the same
            beer add into one figure. Halves are exactly half a pint, which is why two of them cancel one.
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
