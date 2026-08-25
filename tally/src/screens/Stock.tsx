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

import { useEffect, useMemo, useState } from 'react'
import { dayStats, itemTotals } from '../core/analytics.ts'
import { addDays, formatShort, tradingDayKey } from '../core/date.ts'
import {
  buildLedger,
  compareToCount,
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
  type StockItem,
} from '../core/stock.ts'
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

type Panel = 'levels' | 'delivery' | 'count' | 'setup'

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
  const [toast, setToast] = useState('')

  /** Draft numbers being typed into the delivery or count sheets. */
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [sheetDate, setSheetDate] = useState(tradingDayKey())

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const tolerance = loadSettings().tolerancePence
      const [cfg, dels, cts, saved] = await Promise.all([
        loadStockConfig().catch(() => null),
        listDeliveries().catch(() => []),
        listStockCounts().catch(() => []),
        listDays().catch(() => []),
      ])
      if (cancelled) return
      setConfig(cfg ?? { items: [], pours: [], mlPerShot: 30 })
      setDeliveries(dels)
      setCounts(cts)
      setDays(saved.map((d) => dayStats(d, tolerance)))
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
   * Two windows, and they are different questions.
   *
   * "What is left" runs from the last stock take to now. "How did we do" runs
   * from the take before that to the last one, because only a window with a
   * count at both ends can be checked — an open window has nothing to compare
   * against yet.
   */
  const latestCount = counts[0]
  const previousCount = counts[1]
  const since = latestCount?.date ?? addDays(tradingDayKey(), -7)

  const usageBetween = (from: string, to?: string) => {
    const inWindow = days.filter((d) => d.date > from && (to === undefined || d.date <= to))
    const sold = itemTotals(inWindow).map((i) => ({ code: i.code, name: i.name, qtyMilli: i.qtyMilli }))
    return pourUsage(sold, config?.pours ?? []).used
  }

  const deliveredBetween = (from: string, to?: string) => {
    const acc = new Map<string, number>()
    for (const d of deliveries.filter((x) => x.date > from && (to === undefined || x.date <= to))) {
      for (const line of d.lines) acc.set(line.stockItemId, (acc.get(line.stockItemId) ?? 0) + line.baseUnits)
    }
    return acc
  }

  const ledger = useMemo(() => {
    if (!config) return []
    const opening = new Map((latestCount?.lines ?? []).map((l) => [l.stockItemId, l.baseUnits]))
    return buildLedger(config.items, opening, deliveredBetween(since), usageBetween(since))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config, days, deliveries, counts, since])

  /** The finished window: the last take measured against what was expected. */
  const result = useMemo(() => {
    if (!config || !latestCount || !previousCount) return []
    const opening = new Map(previousCount.lines.map((l) => [l.stockItemId, l.baseUnits]))
    const closed = buildLedger(
      config.items,
      opening,
      deliveredBetween(previousCount.date, latestCount.date),
      usageBetween(previousCount.date, latestCount.date),
    )
    return compareToCount(closed, new Map(latestCount.lines.map((l) => [l.stockItemId, l.baseUnits])))
      .filter((v) => v.varianceBaseUnits !== null && v.varianceBaseUnits !== 0)
      .sort((a, b) => (a.varianceBaseUnits ?? 0) - (b.varianceBaseUnits ?? 0))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config, days, deliveries, counts])

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

  async function saveSheet(kind: 'delivery' | 'count') {
    const lines = Object.entries(drafts)
      .map(([id, text]) => {
        const item = config!.items.find((i) => i.id === id)
        const servings = Number(text)
        if (!item || !Number.isFinite(servings) || text.trim() === '') return null
        return { stockItemId: id, baseUnits: servingsToBase(servings, item) }
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
          {(['levels', 'delivery', 'count', 'setup'] as const).map((p) => (
            <button key={p} type="button" className="chip" aria-pressed={panel === p} onClick={() => { setPanel(p); setDrafts({}) }}>
              {p === 'levels' ? 'What’s left' : p === 'delivery' ? 'Delivery in' : p === 'count' ? 'Stock take' : 'Set up'}
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
                      <td className={`num delta ${l.expectedBaseUnits < 0 ? 'short' : ''}`}>
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
          {config.items.map((item) => (
            <div className="zrow" key={item.id}>
              <span className="zname">
                {item.name}
                <small>in {item.servingName}s</small>
              </span>
              <input
                aria-label={`${item.name} ${panel === 'delivery' ? 'delivered' : 'counted'}`}
                inputMode="decimal"
                placeholder="—"
                value={drafts[item.id] ?? ''}
                onChange={(e) => setDrafts((d) => ({ ...d, [item.id]: e.target.value }))}
              />
            </div>
          ))}
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
