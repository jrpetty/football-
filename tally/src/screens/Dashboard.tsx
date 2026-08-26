// ---------------------------------------------------------------------------
// The dashboard.
//
// Every number here comes from core/analytics.ts, which is pure and tested.
// Nothing on this screen counts anything itself — that is what stops a chart
// quietly disagreeing with the table beside it.
//
// The department table is always visible rather than hidden behind a toggle.
// Partly because percentages were asked for explicitly, and partly because the
// palette validator returns a contrast relief on the light surface: three of
// the eight hues sit below 3:1 there, and the documented remedy is visible
// labels or a table view. The table is that relief, so it is not optional.
// ---------------------------------------------------------------------------

import { useEffect, useMemo, useState } from 'react'
import {
  clerkTotals,
  dayStats,
  departmentTotals,
  departmentsPresent,
  filterDays,
  itemTotals,
  lastNDays,
  timeSeries,
  totals,
  weekdayTotals,
  type DayStats,
  type Filter,
} from '../core/analytics.ts'
import { departmentSlot } from '../core/departments.ts'
import { formatShort, tradingDayKey } from '../core/date.ts'
import { formatMoney, formatSigned } from '../core/money.ts'
import { formatQty } from '../core/zread.ts'
import {
  listDays,
  listDeliveries,
  listPeople,
  listShifts,
  listStockCounts,
  loadPriceBook,
  loadStockConfig,
  EMPTY_STOCK,
  type StockConfig,
} from '../storage/db.ts'
import { cellarHealth, type Delivery, type StockCount } from '../core/stock.ts'
import { searchItems } from '../core/itemHistory.ts'
import { ItemDetail } from './ItemDetail.tsx'
import { costOf } from '../core/margin.ts'
import { marginReport } from '../core/margin.ts'
import { forecastWeek, MIN_FOR_WEATHER, type DayWeather } from '../core/forecast.ts'
import { marginMoves } from '../core/history.ts'
import { alertSummary, weeklyAlerts } from '../core/alerts.ts'
import { saveDigest } from '../storage/db.ts'
import { IconAlert } from '../components/icons.tsx'
import { likeForLike } from '../core/analytics.ts'
import { listWeather, saveWeather } from '../storage/db.ts'
import { describeWeatherError, fetchForecast, fetchHistory } from '../weather/openMeteo.ts'
import { addDays } from '../core/date.ts'
import {
  crewFor,
  crewStats,
  formatHours,
  labourShareBp,
  MIN_NIGHTS_FOR_COMPARISON,
  type Person,
  type Shift,
} from '../core/rota.ts'
import { checkPrices, priceHeadline, type PriceBookEntry } from '../core/priceBook.ts'
import { loadSettings } from '../storage/settings.ts'
import { AskCard } from '../components/AskCard.tsx'
import type { AskData } from '../core/askContext.ts'
import { IconChart } from '../components/icons.tsx'
import {
  BarChart,
  ChartCard,
  Legend,
  ShareBar,
  StatTile,
  TrendChart,
  VarianceChart,
  seriesVar,
} from '../components/charts.tsx'

const RANGES = [
  { key: '7', label: 'Last 7 nights', days: 7 },
  { key: '30', label: '30 nights', days: 30 },
  { key: '90', label: '90 nights', days: 90 },
  { key: 'all', label: 'All', days: 0 },
] as const

const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

export function Dashboard({ refreshKey, onOpen }: { refreshKey: number; onOpen: (date: string) => void }) {
  const [all, setAll] = useState<DayStats[] | null>(null)
  const [error, setError] = useState('')
  const [range, setRange] = useState<string>('30')
  const [weekdays, setWeekdays] = useState<string[]>([])
  const [depts, setDepts] = useState<string[]>([])
  const [onlyUnbalanced, setOnlyUnbalanced] = useState(false)
  const [itemSort, setItemSort] = useState<'value' | 'quantity'>('value')
  const [showAllItems, setShowAllItems] = useState(false)
  const [itemQuery, setItemQuery] = useState('')
  /** The item whose card is open, if any. The dashboard stays mounted behind it. */
  const [openItem, setOpenItem] = useState<{ code: string; name: string } | null>(null)
  const [book, setBook] = useState<PriceBookEntry[]>([])
  const [people, setPeople] = useState<Person[]>([])
  const [shifts, setShifts] = useState<Shift[]>([])
  const [stock, setStock] = useState<StockConfig>(EMPTY_STOCK)
  const [deliveries, setDeliveries] = useState<Delivery[]>([])
  const [stockCounts, setStockCounts] = useState<StockCount[]>([])
  const [weather, setWeather] = useState<DayWeather[]>([])
  const [ahead, setAhead] = useState<DayWeather[]>([])
  const [weatherNote, setWeatherNote] = useState('')
  /** The night notes, kept beside the stats for the question box. */
  const [notes, setNotes] = useState<ReadonlyMap<string, string>>(new Map())

  useEffect(() => {
    let cancelled = false
    const tolerance = loadSettings().tolerancePence
    listDays()
      .then((days) => {
        if (cancelled) return
        setAll(days.map((d) => dayStats(d, tolerance)))
        setNotes(new Map(days.filter((d) => d.note.trim() !== '').map((d) => [d.date, d.note])))
      })
      .catch((err: unknown) => !cancelled && setError(err instanceof Error ? err.message : String(err)))
    return () => {
      cancelled = true
    }
  }, [refreshKey])

  useEffect(() => {
    let cancelled = false
    void Promise.all([loadPriceBook(), loadStockConfig(), listDeliveries(), listStockCounts()])
      .then(([b, cfg, dels, cts]) => {
        if (cancelled) return
        setBook(b)
        setStock(cfg)
        setDeliveries(dels)
        setStockCounts(cts)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [refreshKey])

  useEffect(() => {
    let cancelled = false
    void Promise.all([listPeople(), listShifts()])
      .then(([p, sh]) => {
        if (cancelled) return
        setPeople(p)
        setShifts(sh)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [refreshKey])

  /**
   * Keep the weather caught up with the takings.
   *
   * Every night already recorded wants the weather it had, and the coming
   * fortnight wants the forecast — so this looks at what is stored, works out
   * what is missing, and asks only for that. Re-running it costs nothing once
   * the history is filled in, which is what makes it safe to run on every
   * visit rather than on a schedule nobody would remember to keep.
   */
  useEffect(() => {
    if (all === null || all.length === 0) return
    const place = loadSettings().place
    if (!place.name) return

    let cancelled = false
    const controller = new AbortController()

    void (async () => {
      try {
        const stored = await listWeather()
        if (cancelled) return
        const have = new Set(stored.map((w) => w.date))
        setWeather(stored)

        const today = tradingDayKey()
        const traded = all.map((d) => d.date).filter((d) => d < today)
        const missing = traded.filter((d) => !have.has(d)).sort()

        // One request covering the whole gap rather than one per night: the
        // archive is served by date range, and a pub with a year of history
        // would otherwise make three hundred calls.
        if (missing.length > 0) {
          const from = missing[0] as string
          const to = missing[missing.length - 1] as string
          const fetched = await fetchHistory(place, from, to, controller.signal)
          if (cancelled) return
          if (fetched.length > 0) {
            await saveWeather(fetched)
            setWeather(await listWeather())
          }
        }

        const forecast = await fetchForecast(place, 14, controller.signal)
        if (cancelled) return
        setAhead(forecast)
        setWeatherNote('')
      } catch (err) {
        if (cancelled || controller.signal.aborted) return
        // The weather is a nicety. Losing it must not disturb anything else on
        // the screen, so this reports quietly and the rest carries on.
        setWeatherNote(describeWeatherError(err))
      }
    })()

    return () => {
      cancelled = true
      controller.abort()
    }
  }, [all, refreshKey])

  const filter: Filter = useMemo(() => {
    const chosen = RANGES.find((r) => r.key === range)
    const f: Filter = {}
    if (chosen && chosen.days > 0) f.from = lastNDays(tradingDayKey(), chosen.days)
    if (weekdays.length) f.weekdays = weekdays
    if (onlyUnbalanced) f.onlyUnbalanced = true
    return f
  }, [range, weekdays, onlyUnbalanced])

  const selected = useMemo(() => (all ? filterDays(all, filter) : []), [all, filter])
  const t = useMemo(() => totals(selected), [selected])
  const deptRows = useMemo(() => departmentTotals(selected, depts), [selected, depts])
  const available = useMemo(() => (all ? departmentsPresent(all) : []), [all])
  const series = useMemo(() => timeSeries(selected), [selected])
  const week = useMemo(() => weekdayTotals(selected), [selected])
  const clerks = useMemo(() => clerkTotals(selected), [selected])
  const items = useMemo(() => itemTotals(selected, itemSort), [selected, itemSort])
  const crew = useMemo(
    () =>
      crewStats(
        selected.map((d) => ({ date: d.date, variancePence: d.variancePence, takingsPence: d.takingsPence })),
        shifts,
        people,
        loadSettings().tolerancePence,
      ),
    [selected, shifts, people],
  )

  /** Hours and wages across the selected nights that actually have a rota. */
  const labour = useMemo(() => {
    let minutes = 0
    let cost = 0
    let priced = false
    let nights = 0
    let takings = 0
    for (const day of selected) {
      const night = crewFor(day.date, shifts, people)
      if (night.shifts.length === 0) continue
      nights++
      minutes += night.minutes
      takings += day.takingsPence ?? 0
      if (night.costPence !== null) {
        cost += night.costPence
        priced = true
      }
    }
    return { minutes, costPence: priced ? cost : null, nights, takingsPence: takings }
  }, [selected, shifts, people])

  const gp = useMemo(
    () =>
      marginReport(
        itemTotals(selected).map((i) => ({ code: i.code, name: i.name, qtyMilli: i.qtyMilli })),
        book,
        stock.pours,
        stock.items,
      ),
    [selected, book, stock],
  )

  /** The seven nights after today, with whatever forecast exists for them. */
  const forecast = useMemo(() => {
    if (all === null) return null
    const today = tradingDayKey()
    const byDate = new Map(ahead.map((w) => [w.date, w]))
    const upcoming = Array.from({ length: 7 }, (_, i) => {
      const date = addDays(today, i + 1)
      const w = byDate.get(date)
      return w ? { date, weather: w } : { date }
    })
    return forecastWeek(
      all.filter((d) => d.takingsPence !== null).map((d) => ({ date: d.date, takingsPence: d.takingsPence as number })),
      weather,
      upcoming,
    )
  }, [all, weather, ahead])

  const yoy = useMemo(() => {
    if (all === null || selected.length === 0) return null
    const dates = selected.map((d) => d.date).sort()
    return likeForLike(all, dates[0] as string, dates[dates.length - 1] as string)
  }, [all, selected])

  const moves = useMemo(() => marginMoves(stock.items, stock.pours, book), [stock, book])

  // One cellar picture, shared by the alerts and the question box — computed
  // once so the two can never disagree about what is on hand.
  const cellar = useMemo(
    () =>
      all !== null && stock.items.length > 0
        ? cellarHealth({
            items: stock.items,
            pours: stock.pours,
            counts: stockCounts,
            deliveries,
            days: all,
            today: tradingDayKey(),
            costOfServing: costOf,
          })
        : null,
    [all, stock, deliveries, stockCounts],
  )

  /**
   * The week's findings.
   *
   * Computed over fixed windows rather than whatever the filter chips happen
   * to say: a weekly digest that changed when "Didn't balance" was tapped
   * would be a different list every visit, and the nudge notification would be
   * describing a filter nobody remembers setting.
   */
  const alerts = useMemo(() => {
    if (all === null) return []

    // Each weekday against ITS OWN nights, and only THIS year's against last
    // year's. Two earlier versions of this line were each wrong in turn:
    // passing the whole history made every weekday report the same figure
    // (like-for-like windows do not know about weekdays), and an unbounded
    // window let a slump from two years ago keep firing "Fridays down" after
    // Fridays had fully recovered, because every past year paired against the
    // year before it with equal weight.
    const today = tradingDayKey()
    const yearBack = lastNDays(today, 364)
    const weekdayYoY = WEEKDAYS.map((weekday) => {
      const mine = all.filter((d) => d.weekday === weekday && d.takingsPence !== null)
      if (mine.length === 0) return null
      return { weekday, change: likeForLike(mine, yearBack, today) }
    }).filter((x): x is { weekday: string; change: ReturnType<typeof likeForLike> } => x !== null)

    const lastMonth = all.filter((d) => d.date >= lastNDays(today, 30))
    const monthGp = marginReport(
      itemTotals(lastMonth).map((i) => ({ code: i.code, name: i.name, qtyMilli: i.qtyMilli })),
      book,
      stock.pours,
      stock.items,
    )

    return weeklyAlerts({
      recent: [...all].sort((a, b) => b.date.localeCompare(a.date)),
      weekdayYoY,
      gp: monthGp,
      moves,
      deadStock: cellar?.dead ?? [],
      cellarGapPence: cellar?.gapPence ?? null,
      cellarCountAgeDays: cellar && stockCounts.length > 0 ? cellar.sinceDays : null,
      // Lines with no board price specifically — uncostedCount also counts
      // lines that have a price but no cost yet, which is a different job.
      unpricedCount: monthGp.lines.filter((l) => l.missing === 'price').length,
    })
  }, [all, book, stock, stockCounts, moves, cellar])

  // Left where the service worker can find it, since a worker cannot run any
  // of the above itself. See public/sw.js.
  useEffect(() => {
    if (all === null) return
    void saveDigest(alertSummary(alerts), alerts.length).catch(() => {})
  }, [alerts, all])

  const prices = useMemo(
    () => checkPrices(itemTotals(selected).map((i) => ({ code: i.code, name: i.name, qtyMilli: i.qtyMilli, pence: i.pence })), book),
    [selected, book],
  )

  // Everything the question box may be asked about, in one place.
  const askData = useMemo<AskData>(
    () => ({ days: all ?? [], notes, book, cellar, people, shifts, weather, today: tradingDayKey() }),
    [all, notes, book, cellar, people, shifts, weather],
  )

  if (error) return <div className="main"><p className="note bad">Could not read the saved nights: {error}</p></div>
  if (all === null) return <div className="main"><p className="note"><span className="spinner" /> Loading…</p></div>

  if (all.length === 0) {
    return (
      <div className="main">
        <div className="empty">
          <span className="empty-mark"><IconChart size={40} strokeWidth={1.4} /></span>
          <p>Nothing to show yet.</p>
          <p>Save a night or two and this fills in — takings, the department split, and where the money went astray.</p>
        </div>
      </div>
    )
  }

  const toggle = (list: string[], value: string, set: (v: string[]) => void) =>
    set(list.includes(value) ? list.filter((v) => v !== value) : [...list, value])

  const shareRows = deptRows.map((d) => ({
    key: d.code,
    label: d.label,
    pence: d.pence,
    percentBp: d.percentBp,
    slot: departmentSlot(d.code),
  }))

  const deptTotalPence = deptRows.reduce((a, d) => a + d.pence, 0)
  const deptItemsMilli = deptRows.reduce((a, d) => a + d.qtyMilli, 0)
  const withZRead = selected.filter((s) => s.hasZRead).length

  if (openItem) {
    return (
      <ItemDetail
        all={all}
        code={openItem.code}
        name={openItem.name}
        book={book}
        stock={stock}
        deliveries={deliveries}
        stockCounts={stockCounts}
        onBack={() => setOpenItem(null)}
      />
    )
  }

  return (
    <div className="main">
      {alerts.length > 0 && (
        <section className="card alerts">
          <div className="card-head">
            <h2>Worth knowing</h2>
            <span className="badge warn">{alerts.length}</span>
          </div>
          {alerts.map((a) => (
            <div className={`alert ${a.level}`} key={a.id}>
              <span className="alert-mark" aria-hidden="true">
                <IconAlert size={16} strokeWidth={2} />
              </span>
              <span className="alert-words">
                <strong>{a.headline}</strong>
                <small>{a.detail}</small>
              </span>
            </div>
          ))}
          <p className="note">
            Only things worth acting on appear here, and never more than five — a list nobody
            finishes is a list nobody reads.
          </p>
        </section>
      )}

      <AskCard data={askData} />

      {/* --- filters, in one row above the charts --------------------------- */}
      <section className="card">
        <div className="card-head"><h2>Show me</h2></div>
        <div className="filters">
          <div className="chip-row">
            {RANGES.map((r) => (
              <button
                key={r.key}
                type="button"
                className="chip"
                aria-pressed={range === r.key}
                onClick={() => setRange(r.key)}
              >
                {r.label}
              </button>
            ))}
          </div>

          <div className="chip-row">
            {WEEKDAYS.map((d) => (
              <button
                key={d}
                type="button"
                className="chip"
                aria-pressed={weekdays.includes(d)}
                onClick={() => toggle(weekdays, d, setWeekdays)}
              >
                {d.slice(0, 3)}
              </button>
            ))}
            <button
              type="button"
              className="chip"
              aria-pressed={onlyUnbalanced}
              onClick={() => setOnlyUnbalanced((v) => !v)}
            >
              Didn’t balance
            </button>
          </div>

          {available.length > 0 && (
            <div className="chip-row">
              {available.map((d) => (
                <button
                  key={d.code}
                  type="button"
                  className="chip"
                  aria-pressed={depts.includes(d.code)}
                  onClick={() => toggle(depts, d.code, setDepts)}
                >
                  <span className="swatch" style={{ background: seriesVar(departmentSlot(d.code)) }} aria-hidden="true" />
                  {d.label}
                </button>
              ))}
            </div>
          )}
        </div>
      </section>

      {/* --- the headline --------------------------------------------------- */}
      <section className="card hero">
        <StatTile
          label={`Taken over ${t.nights} ${t.nights === 1 ? 'night' : 'nights'}`}
          value={formatMoney(t.takingsPence)}
          detail={t.guestCount > 0 ? `${t.guestCount.toLocaleString('en-GB')} sales, ${formatMoney(t.avePence ?? 0)} average` : undefined}
        />
      </section>

      <div className="kpi-row">
        <StatTile label="Cash" value={formatMoney(t.cashPence)} detail={t.takingsPence > 0 ? `${Math.round((t.cashPence / t.takingsPence) * 100)}% of takings` : undefined} />
        <StatTile label="Card" value={formatMoney(t.cardPence)} detail={t.takingsPence > 0 ? `${Math.round((t.cardPence / t.takingsPence) * 100)}% of takings` : undefined} />
        <StatTile
          label="Net difference"
          value={formatSigned(t.netVariancePence)}
          detail={`over ${t.reconciledNights} reconciled`}
          tone={t.netVariancePence === 0 ? 'good' : t.netVariancePence < 0 ? 'bad' : 'warn'}
        />
        <StatTile
          label="Total out"
          value={formatMoney(t.absVariancePence)}
          detail="short and over added together"
          tone={t.absVariancePence === 0 ? 'good' : undefined}
        />
        <StatTile
          label="Items sold"
          value={formatQty(t.itemsMilli)}
          detail={t.itemsPerSale === null ? undefined : `${t.itemsPerSale} a round`}
        />
        <StatTile label="Balanced" value={`${t.balancedNights}/${t.nights}`} detail={`${t.shortNights} short, ${t.overNights} over`} tone={t.balancedNights === t.nights ? 'good' : undefined} />
      </div>

      {/* --- what next week might take --------------------------------------- */}
      {forecast && forecast.nightsUsed >= 3 && (
        <ChartCard
          title="What next week might take"
          subtitle={
            forecast.weatherNights >= MIN_FOR_WEATHER
              ? `from ${forecast.nightsUsed} nights and the forecast`
              : `from ${forecast.nightsUsed} nights of trade`
          }
        >
          <div className="kpi-row">
            <StatTile
              label="Next seven nights"
              value={formatMoney(forecast.totalPence)}
              detail={`somewhere between ${formatMoney(forecast.lowPence)} and ${formatMoney(forecast.highPence)}`}
            />
            {forecast.perDegreePence !== null && (
              <StatTile
                label="Each degree warmer"
                value={formatSigned(forecast.perDegreePence)}
                detail={
                  forecast.perMmRainPence === null || forecast.perMmRainPence === 0
                    ? 'a night, on this pub’s own history'
                    : `a night · ${formatSigned(forecast.perMmRainPence)} per mm of rain`
                }
                tone={forecast.perDegreePence > 0 ? 'good' : undefined}
              />
            )}
          </div>

          <div className="table-wrap">
            <table className="data crew">
              <thead>
                <tr>
                  <th scope="col">Night</th>
                  <th scope="col">Usually</th>
                  <th scope="col">Weather</th>
                  <th scope="col">Estimate</th>
                </tr>
              </thead>
              <tbody>
                {forecast.days.map((d) => (
                  <tr key={d.date}>
                    <th scope="row">{formatShort(d.date)}</th>
                    <td className="num">{formatMoney(d.basePence)}</td>
                    <td className="num">
                      {d.weather ? `${d.weather.tempC}°${d.weather.rainMm > 0 ? ` · ${d.weather.rainMm}mm` : ''}` : '—'}
                    </td>
                    <td className="num">{formatMoney(d.estimatePence)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <p className="note">
            {forecast.weatherNights >= MIN_FOR_WEATHER
              ? 'The usual figure for each weekday, moved by what the weather is forecast to do — measured on this pub, not on pubs in general.'
              : `Weekday averages only so far. Once ${MIN_FOR_WEATHER} nights have weather recorded against them, the forecast starts moving these figures — fitting weather to fewer than that produces a confident number that is really noise.`}{' '}
            It is a steer, not a budget.
          </p>
          {weatherNote && <p className="note warn">{weatherNote}</p>}
          {!loadSettings().place.name && (
            <p className="note warn">
              No location set, so there is no weather to work with. Set the town in Settings and the
              forecast learns what warm dry Saturdays are worth to this pub.
            </p>
          )}
        </ChartCard>
      )}

      {/* --- against last year ------------------------------------------------ */}
      {yoy && yoy.comparable && (
        <ChartCard title="Against last year" subtitle={`${yoy.matchedNights} nights that traded both years`}>
          <div className="kpi-row">
            <StatTile
              label="Like for like"
              value={yoy.changeBp === null ? '—' : `${yoy.changeBp > 0 ? '+' : ''}${(yoy.changeBp / 100).toFixed(1)}%`}
              detail={`${formatMoney(yoy.matchedPence)} against ${formatMoney(yoy.matchedLastYearPence)}`}
              tone={yoy.changeBp === null ? undefined : yoy.changeBp > 0 ? 'good' : yoy.changeBp < 0 ? 'bad' : undefined}
            />
            <StatTile
              label="Nights open"
              value={`${yoy.nights} v ${yoy.lastYearNights}`}
              detail="this year against last"
            />
          </div>
          <p className="note">
            Compared night against the same night a year before — 52 weeks back, so a Saturday meets a
            Saturday. Only nights that traded in both years count towards the percentage: opening an
            extra night is more takings but it is not growth, and totting both years up regardless
            would call it growth anyway.
          </p>
        </ChartCard>
      )}

      {(t.voidCount > 0 || t.noSaleCount > 0) && (
        <ChartCard title="Worth an eye" subtitle="over the whole selection">
          <div className="kpi-row">
            <StatTile
              label="Voids"
              value={String(t.voidCount)}
              detail={`${formatMoney(t.voidPence)} rung up then cancelled`}
            />
            <StatTile
              label="No sales"
              value={String(t.noSaleCount)}
              detail="drawer opened with nothing sold"
            />
          </div>
          <p className="note">
            Neither is wrong on its own — a mis-rung round gets voided, and the drawer opens to give
            change for the phone. They are here because they are the two figures on the roll that are
            worth knowing the usual number for, so an unusual one stands out.
          </p>
        </ChartCard>
      )}

      {/* --- trend ---------------------------------------------------------- */}
      <ChartCard title="Takings by night" subtitle={`${t.nights} nights`}>
        <TrendChart points={series.map((s) => ({ date: s.date, label: formatShort(s.date), pence: s.takingsPence ?? 0 }))} />
      </ChartCard>

      {/* --- department mix -------------------------------------------------- */}
      <ChartCard
        title="What sold"
        subtitle={withZRead === 0 ? 'needs a till roll' : `${withZRead} of ${selected.length} nights`}
      >
        {shareRows.length === 0 ? (
          <p className="note">
            Scan a full till roll and the department split appears here — draught, spirits, wine and the rest.
          </p>
        ) : (
          <>
            <ShareBar rows={shareRows} />
            <Legend items={shareRows.map((r) => ({ label: r.label, color: seriesVar(r.slot) }))} />
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th scope="col">Department</th>
                    <th scope="col">Sold</th>
                    <th scope="col">Taken</th>
                    <th scope="col">Each</th>
                    <th scope="col">Share</th>
                  </tr>
                </thead>
                <tbody>
                  {deptRows.map((d) => (
                    <tr key={d.code}>
                      <th scope="row">
                        <span className="swatch" style={{ background: seriesVar(departmentSlot(d.code)) }} aria-hidden="true" />
                        {d.label}
                      </th>
                      <td className="num">{formatQty(d.qtyMilli)}</td>
                      <td className="num">{formatMoney(d.pence)}</td>
                      <td className="num">{d.avgPencePerItem === null ? '—' : formatMoney(d.avgPencePerItem)}</td>
                      <td className="num">{(d.percentBp / 100).toFixed(2)}%</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <td>Total</td>
                    <td className="num">{formatQty(deptItemsMilli)}</td>
                    <td className="num">{formatMoney(deptTotalPence)}</td>
                    <td className="num">
                      {deptItemsMilli > 0 ? formatMoney(Math.round(deptTotalPence / (deptItemsMilli / 1000))) : '—'}
                    </td>
                    <td className="num">100.00%</td>
                  </tr>
                </tfoot>
              </table>
            </div>
          </>
        )}
      </ChartCard>

      {/* --- variance -------------------------------------------------------- */}
      <ChartCard title="How far out each night was" subtitle="short below the line, over above">
        <VarianceChart
          points={series
            .filter((s) => s.variancePence !== null)
            .map((s) => ({ date: s.date, label: formatShort(s.date), variancePence: s.variancePence ?? 0 }))}
        />
        <Legend
          items={[
            { label: 'Short', color: 'var(--bad)' },
            { label: 'Over', color: 'var(--warn)' },
          ]}
        />
      </ChartCard>

      {/* --- weekday pattern -------------------------------------------------- */}
      <ChartCard title="Average night by weekday" subtitle="is Friday really busier?">
        <BarChart
          rows={week.map((w) => ({
            key: w.weekday,
            label: w.weekday.slice(0, 3),
            value: w.avgTakingsPence,
            detail: `${w.nights} ${w.nights === 1 ? 'night' : 'nights'}`,
          }))}
        />
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th scope="col">Day</th>
                <th scope="col">Nights</th>
                <th scope="col">Avg taken</th>
                <th scope="col">Avg out</th>
                <th scope="col">Short</th>
              </tr>
            </thead>
            <tbody>
              {week.map((w) => (
                <tr key={w.weekday}>
                  <th scope="row">{w.weekday}</th>
                  <td className="num">{w.nights}</td>
                  <td className="num">{formatMoney(w.avgTakingsPence)}</td>
                  <td className="num">{w.avgVariancePence === null ? '—' : formatSigned(w.avgVariancePence)}</td>
                  <td className="num">{w.shortNights}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </ChartCard>

      {prices.pricedCount > 0 && (
        <ChartCard
          title="Rung at the right price?"
          subtitle={`${prices.pricedCount} of ${prices.pricedCount + prices.unpricedCount} lines priced`}
        >
          <div className="kpi-row">
            <StatTile
              label="Under the board price"
              value={formatMoney(prices.underPence)}
              detail="what those lines would have taken"
              tone={prices.underPence === 0 ? 'good' : 'bad'}
            />
            <StatTile
              label="Over"
              value={formatMoney(prices.overPence)}
              detail="rung above the board price"
              tone={prices.overPence === 0 ? undefined : 'warn'}
            />
          </div>

          {prices.rows.filter((r) => r.verdict === 'under' || r.verdict === 'over').length > 0 && (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th scope="col">Item</th>
                    <th scope="col">Sold</th>
                    <th scope="col">Board</th>
                    <th scope="col">Rang at</th>
                    <th scope="col">Difference</th>
                  </tr>
                </thead>
                <tbody>
                  {prices.rows
                    .filter((r) => r.verdict === 'under' || r.verdict === 'over')
                    .map((r) => (
                      <tr key={r.code}>
                        <th scope="row">{r.name}</th>
                        <td className="num">{formatQty(r.qtyMilli)}</td>
                        <td className="num">{r.expectedPencePerItem === null ? '—' : formatMoney(r.expectedPencePerItem)}</td>
                        <td className="num">{formatMoney(r.avgPencePerItem)}</td>
                        <td className={`num delta ${(r.variancePence ?? 0) < 0 ? 'short' : 'over'}`}>
                          {formatSigned(r.variancePence ?? 0)}
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          )}

          <p className="note">{priceHeadline(prices)}</p>
          <p className="note">
            The till's price here is its takings divided by how many went out, so a sale rung at a
            discount — an OAP price, a staff drink — pulls it under the board price quite honestly. A
            gap is a question worth asking, not a finding.
          </p>
        </ChartCard>
      )}

      {items.length > 0 && (() => {
        const matches = searchItems(items, itemQuery)
        const shownItems = itemQuery.trim() !== '' ? matches : showAllItems ? items : items.slice(0, 12)
        return (
        <ChartCard
          title="What people actually bought"
          subtitle={`${items.length} lines on the till`}
        >
          {/* Type a drink, get its whole story. The search runs over every
              line the till has ever sold, not just the rows on show. */}
          <div className="item-search">
            <input
              aria-label="Find an item"
              type="search"
              placeholder="Find a drink — Taddy, wine, crisps…"
              value={itemQuery}
              onChange={(e) => setItemQuery(e.target.value)}
            />
          </div>

          <div className="chip-row" style={{ marginBottom: 12 }}>
            <button type="button" className="chip" aria-pressed={itemSort === 'value'} onClick={() => setItemSort('value')}>
              By takings
            </button>
            <button type="button" className="chip" aria-pressed={itemSort === 'quantity'} onClick={() => setItemSort('quantity')}>
              By how many
            </button>
          </div>

          <BarChart
            rows={items.slice(0, 8).map((i) => ({
              key: i.code,
              // Trimmed to what fits the column; the table below carries the
              // full name, so nothing is lost by shortening it here.
              label: i.name.length > 15 ? `${i.name.slice(0, 14)}…` : i.name,
              value: itemSort === 'value' ? i.pence : i.qtyMilli / 1000,
              detail: itemSort === 'value' ? 'Taken' : 'Sold',
            }))}
            format={itemSort === 'value' ? formatMoney : (v) => String(Math.round(v))}
            labelWidth={112}
          />

          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Item</th>
                  <th scope="col">Sold</th>
                  <th scope="col">Taken</th>
                  <th scope="col">Each</th>
                  <th scope="col">Share</th>
                </tr>
              </thead>
              <tbody>
                {shownItems.map((i) => (
                  // The whole row opens the item's own card; the button in the
                  // name cell is what a screen reader lands on.
                  <tr
                    key={i.code}
                    className="item-row"
                    onClick={() => setOpenItem({ code: i.code, name: i.name })}
                  >
                    <th scope="row">
                      <button
                        type="button"
                        className="item-open"
                        onClick={(e) => {
                          e.stopPropagation()
                          setOpenItem({ code: i.code, name: i.name })
                        }}
                      >
                        {i.name}
                      </button>
                    </th>
                    <td className="num">{formatQty(i.qtyMilli)}</td>
                    <td className="num">{formatMoney(i.pence)}</td>
                    <td className="num">{i.avgPencePerItem === null ? '—' : formatMoney(i.avgPencePerItem)}</td>
                    <td className="num">{(i.percentBp / 100).toFixed(2)}%</td>
                  </tr>
                ))}
                {shownItems.length === 0 && (
                  <tr>
                    <td colSpan={5}>Nothing the till sells matches “{itemQuery}”.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          {itemQuery.trim() === '' && items.length > 12 && (
            <div className="alts">
              <button type="button" className="btn-small" onClick={() => setShowAllItems((v) => !v)}>
                {showAllItems ? 'Show the top twelve' : `Show all ${items.length}`}
              </button>
            </div>
          )}
        </ChartCard>
        )
      })()}

      {gp.costedCount > 0 && (
        <ChartCard
          title="What it actually makes"
          subtitle={`${gp.costedCount} of ${gp.costedCount + gp.uncostedCount} lines costed`}
        >
          <div className="kpi-row">
            <StatTile
              label="Gross profit"
              value={formatMoney(gp.profitPence)}
              detail="the till price, less what the beer cost"
            />
            <StatTile
              label="GP rate"
              value={gp.blendedGpBp === null ? '—' : `${(gp.blendedGpBp / 100).toFixed(1)}%`}
              detail="across the costed lines"
              tone={gp.blendedGpBp !== null && gp.blendedGpBp >= 5500 ? 'good' : gp.blendedGpBp !== null && gp.blendedGpBp < 4500 ? 'bad' : undefined}
            />
          </div>

          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Line</th>
                  <th scope="col">Sold</th>
                  <th scope="col">Sells at</th>
                  <th scope="col">Costs</th>
                  <th scope="col">GP</th>
                  <th scope="col">Made</th>
                </tr>
              </thead>
              <tbody>
                {gp.lines
                  .filter((l) => l.margin)
                  .slice(0, 12)
                  .map((l) => (
                    <tr key={l.code}>
                      <th scope="row">{l.name}</th>
                      <td className="num">{formatQty(l.qtyMilli)}</td>
                      <td className="num">{formatMoney(l.margin!.sellPence)}</td>
                      <td className="num">{formatMoney(l.margin!.costPence)}</td>
                      <td className={`num${l.margin!.gpBp < 4500 ? ' bad' : ''}`}>
                        {(l.margin!.gpBp / 100).toFixed(1)}%
                      </td>
                      <td className="num">{formatMoney(l.periodProfitPence ?? 0)}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>

          <p className="note">
            Worst margin first, because that is the line worth looking at. Margin here is the plain
            one — the price on the board against the price on the invoice.
            {gp.uncostedCount > 0 && ` ${gp.uncostedCount} lines have no price or no cost entered yet and are left out of both figures above.`}
          </p>
        </ChartCard>
      )}

      {moves.length > 0 && (
        <ChartCard
          title="What changed underneath"
          subtitle={`${moves.filter((m) => m.verdict === 'squeezed').length} being absorbed`}
        >
          <div className="table-wrap">
            <table className="data crew">
              <thead>
                <tr>
                  <th scope="col">Line</th>
                  <th scope="col">Cost</th>
                  <th scope="col">Sells at</th>
                  <th scope="col">GP then</th>
                  <th scope="col">Now</th>
                </tr>
              </thead>
              <tbody>
                {moves.map((m) => (
                  <tr key={m.code}>
                    <th scope="row">
                      {m.name}
                      <br />
                      <span className={`hint${m.verdict === 'squeezed' ? ' bad' : ''}`}>
                        {m.verdict === 'squeezed'
                          ? 'you are absorbing this'
                          : m.verdict === 'kept up'
                            ? 'the board kept up'
                            : m.verdict === 'improved'
                              ? 'better than it was'
                              : 'unchanged'}
                      </span>
                    </th>
                    <td className="num">
                      {formatMoney(m.costThenPence)}
                      {m.costNowPence !== m.costThenPence && <> → {formatMoney(m.costNowPence)}</>}
                    </td>
                    <td className="num">
                      {formatMoney(m.priceThenPence)}
                      {m.priceNowPence !== m.priceThenPence && <> → {formatMoney(m.priceNowPence)}</>}
                    </td>
                    <td className="num">{(m.then.gpBp / 100).toFixed(1)}%</td>
                    <td className={`num${m.gpChangeBp <= -100 ? ' bad' : m.gpChangeBp >= 100 ? ' good' : ''}`}>
                      {(m.now.gpBp / 100).toFixed(1)}%
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="note">
            A brewery puts a cask up and nothing breaks — the night still balances, the cellar still
            reconciles, and the pint quietly makes less. This is the only place that shows up. Lines
            marked as absorbed are ones where the cost moved and the board did not follow.
          </p>
        </ChartCard>
      )}

      {crew.length > 0 && labour.nights > 0 && (
        <ChartCard
          title="Who was on"
          subtitle={`${labour.nights} of ${selected.length} nights rostered`}
        >
          <div className="kpi-row">
            <StatTile label="Hours worked" value={formatHours(labour.minutes)} detail={`over ${labour.nights} ${labour.nights === 1 ? 'night' : 'nights'}`} />
            {labour.costPence !== null && (
              <StatTile
                label="Wages"
                value={formatMoney(labour.costPence)}
                detail={
                  labourShareBp(labour.costPence, labour.takingsPence) === null
                    ? undefined
                    : `${((labourShareBp(labour.costPence, labour.takingsPence) as number) / 100).toFixed(1)}% of takings`
                }
              />
            )}
          </div>

          <div className="table-wrap">
            {/* Six columns, not seven: "other nights" is implied by the
                difference beside it, and a seventh pushes the one column that
                matters off the side of a phone. */}
            <table className="data crew">
              <thead>
                <tr>
                  <th scope="col">Person</th>
                  <th scope="col">Nights</th>
                  <th scope="col">Hours</th>
                  <th scope="col">Avg take</th>
                  <th scope="col">Their nights</th>
                  <th scope="col">vs others</th>
                </tr>
              </thead>
              <tbody>
                {crew.map((c) => (
                  <tr key={c.personId}>
                    <th scope="row">
                      <span className="swatch" style={{ background: seriesVar(c.slot) }} aria-hidden="true" />
                      {c.name}
                    </th>
                    <td className="num">{c.nightsOn}</td>
                    <td className="num">{formatHours(c.minutes)}</td>
                    <td className="num">{c.avgTakingsOnPence === null ? '—' : formatMoney(c.avgTakingsOnPence)}</td>
                    <td className="num">{c.avgVarianceOnPence === null ? '—' : formatSigned(c.avgVarianceOnPence)}</td>
                    <td className={`num${c.meaningful && c.differencePence !== null ? ` delta ${c.differencePence < 0 ? 'short' : c.differencePence > 0 ? 'over' : 'balanced'}` : ''}`}>
                      {c.meaningful && c.differencePence !== null ? formatSigned(c.differencePence) : 'too soon'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <p className="note">
            The last column compares the drawer on the nights each person worked against the nights
            they did not. Most nights have two or three people on, so this can never single anybody
            out — it is a place to look, not a finding. Nothing is shown until there are at least{' '}
            {MIN_NIGHTS_FOR_COMPARISON} nights on both sides, and nights with no rota are left out of
            it entirely rather than counted as nights off.
          </p>
        </ChartCard>
      )}

      {clerks.length > 0 && (
        <ChartCard title="Who rang it up" subtitle={`${clerks.length} behind the bar`}>
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Clerk</th>
                  <th scope="col">Nights</th>
                  <th scope="col">Sales</th>
                  <th scope="col">Took</th>
                  <th scope="col">Average</th>
                  <th scope="col">Voids</th>
                </tr>
              </thead>
              <tbody>
                {clerks.map((c) => (
                  <tr key={c.code}>
                    <th scope="row">{c.name}</th>
                    <td className="num">{c.nights}</td>
                    <td className="num">{c.sales || '—'}</td>
                    <td className="num">{formatMoney(c.pence)}</td>
                    <td className="num">{c.avgPence === null ? '—' : formatMoney(c.avgPence)}</td>
                    <td className="num">{c.voids || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="note">
            This is who took what, not whose till was short. The drawer is counted once for the whole
            night, so a shortfall cannot be pinned on a person from this receipt — only a till with a
            drawer each could tell you that.
          </p>
        </ChartCard>
      )}

      {/* --- the nights themselves --------------------------------------------- */}
      <ChartCard title="The nights" subtitle="tap one to open it">
        <div className="day-list">
          {[...selected].reverse().map((s) => (
            <button type="button" key={s.date} className="card day-row" onClick={() => onOpen(s.date)}>
              <span className="when">
                <span className="date">{formatShort(s.date)}</span>
                <br />
                <span className="takings num">
                  {s.takingsPence === null ? 'Not finished' : `Took ${formatMoney(s.takingsPence)}`}
                  {s.guestCount ? ` · ${s.guestCount} sales` : ''}
                </span>
              </span>
              <span className={`delta ${s.verdict}`}>
                {s.verdict === 'incomplete' ? '—' : s.verdict === 'balanced' ? '✅' : formatSigned(s.variancePence ?? 0)}
              </span>
            </button>
          ))}
          {selected.length === 0 && <p className="note">No nights match those filters.</p>}
        </div>
      </ChartCard>
    </div>
  )
}
