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
import { listDays } from '../storage/db.ts'
import { loadSettings } from '../storage/settings.ts'
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

  useEffect(() => {
    let cancelled = false
    const tolerance = loadSettings().tolerancePence
    listDays()
      .then((days) => {
        if (cancelled) return
        setAll(days.map((d) => dayStats(d, tolerance)))
      })
      .catch((err: unknown) => !cancelled && setError(err instanceof Error ? err.message : String(err)))
    return () => {
      cancelled = true
    }
  }, [refreshKey])

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

  if (error) return <div className="main"><p className="note bad">Could not read the saved nights: {error}</p></div>
  if (all === null) return <div className="main"><p className="note"><span className="spinner" /> Loading…</p></div>

  if (all.length === 0) {
    return (
      <div className="main">
        <div className="empty">
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

  return (
    <div className="main">
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

      {items.length > 0 && (
        <ChartCard
          title="What people actually bought"
          subtitle={`${items.length} lines on the till`}
        >
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
                {(showAllItems ? items : items.slice(0, 12)).map((i) => (
                  <tr key={i.code}>
                    <th scope="row">{i.name}</th>
                    <td className="num">{formatQty(i.qtyMilli)}</td>
                    <td className="num">{formatMoney(i.pence)}</td>
                    <td className="num">{i.avgPencePerItem === null ? '—' : formatMoney(i.avgPencePerItem)}</td>
                    <td className="num">{(i.percentBp / 100).toFixed(2)}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {items.length > 12 && (
            <div className="alts">
              <button type="button" className="btn-small" onClick={() => setShowAllItems((v) => !v)}>
                {showAllItems ? 'Show the top twelve' : `Show all ${items.length}`}
              </button>
            </div>
          )}
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
