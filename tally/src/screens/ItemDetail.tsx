// ---------------------------------------------------------------------------
// One item, everything known about it, on one card.
//
// The dashboard answers "how is trade?"; this answers "how is the Guinness?" —
// a different question, and the one a delisting or a price rise actually turns
// on. Nothing here is computed fresh: the history comes from itemProfile, the
// margin from the same margin() as everywhere else, the stock from the same
// cellarHealth the Cellar screen reads. One item, the app's one set of answers.
// ---------------------------------------------------------------------------

import { useMemo } from 'react'
import type { DayStats } from '../core/analytics.ts'
import { itemProfile, WEEKDAY_ORDER } from '../core/itemHistory.ts'
import { formatMoney } from '../core/money.ts'
import { formatQty } from '../core/zread.ts'
import { formatShort } from '../core/date.ts'
import { buildIndex, lookup, type PriceBookEntry } from '../core/priceBook.ts'
import { costOf, margin } from '../core/margin.ts'
import { marginMoves } from '../core/history.ts'
import {
  cellarHealth,
  describeStock,
  type Delivery,
  type StockCount,
} from '../core/stock.ts'
import type { StockConfig } from '../storage/db.ts'
import { tradingDayKey } from '../core/date.ts'
import { BarChart, StatTile, TrendChart } from '../components/charts.tsx'
import { IconChevronRight } from '../components/icons.tsx'

interface Props {
  all: DayStats[]
  code: string
  name: string
  book: PriceBookEntry[]
  stock: StockConfig
  deliveries: Delivery[]
  stockCounts: StockCount[]
  onBack: () => void
}

export function ItemDetail({ all, code, name, book, stock, deliveries, stockCounts, onBack }: Props) {
  const profile = useMemo(() => itemProfile(all, code, name), [all, code, name])

  const pour = useMemo(
    () =>
      stock.pours.find(
        (p) =>
          (code && p.itemCode.trim().toUpperCase() === code.trim().toUpperCase()) ||
          p.itemName.trim().toUpperCase() === name.trim().toUpperCase(),
      ),
    [stock, code, name],
  )
  const stockItem = pour ? stock.items.find((i) => i.id === pour.stockItemId) : undefined
  const entry = useMemo(() => lookup(buildIndex(book), { code, name }), [book, code, name])
  const pourCost = pour && stockItem ? costOf(stockItem, pour.baseUnits) : null
  const gp = entry && pourCost !== null ? margin(entry.pence, pourCost) : null
  const move = useMemo(
    () => marginMoves(stock.items, stock.pours, book).find((m) => m.code === code || m.name === name),
    [stock, book, code, name],
  )

  const onHand = useMemo(() => {
    if (!stockItem) return null
    const health = cellarHealth({
      items: stock.items,
      pours: stock.pours,
      counts: stockCounts,
      deliveries,
      days: all,
      today: tradingDayKey(),
      costOfServing: costOf,
    })
    return health.ledger.find((l) => l.item.id === stockItem.id) ?? null
  }, [stockItem, stock, stockCounts, deliveries, all])

  const priceLog = [...(entry?.history ?? [])].reverse()
  const costLog = [...(stockItem?.costHistory ?? [])].reverse()

  // The average the till actually rung against the board price: a persistent
  // gap here is discounts, or the till told the wrong price.
  const rungGap =
    entry && profile.avgPencePerItem !== null ? profile.avgPencePerItem - entry.pence : null

  return (
    <div className="main">
      <section className="card">
        <div className="card-head">
          <h2>{profile.name}</h2>
          {code && <span className="badge">PLU {code}</span>}
        </div>
        <div className="kpi-row">
          <StatTile
            label="Sold"
            value={formatQty(profile.totalQtyMilli)}
            detail={`over ${profile.nights.length} of ${profile.nightsWithRoll} nights with a roll`}
          />
          <StatTile label="Taken" value={formatMoney(profile.totalPence)} detail={profile.perWeek !== null ? `about ${profile.perWeek} a week` : undefined} />
          <StatTile
            label="Goes for"
            value={profile.avgPencePerItem === null ? '—' : formatMoney(profile.avgPencePerItem)}
            detail={
              entry
                ? rungGap !== null && Math.abs(rungGap) > 1
                  ? `board says ${formatMoney(entry.pence)} — ${rungGap < 0 ? 'under' : 'over'} by ${formatMoney(Math.abs(rungGap))}`
                  : `matches the board at ${formatMoney(entry.pence)}`
                : 'no board price set'
            }
            tone={rungGap !== null && rungGap < -1 ? 'warn' : undefined}
          />
          <StatTile
            label="Margin"
            value={gp ? `${(gp.gpBp / 100).toFixed(1)}%` : '—'}
            detail={
              gp
                ? `${formatMoney(gp.grossProfitPence)} a ${stockItem?.servingName ?? 'sale'} after ${formatMoney(gp.costPence)} cost`
                : pourCost === null && pour
                  ? 'no cost entered in the cellar'
                  : 'not linked to the cellar yet'
            }
            tone={gp ? (gp.gpBp >= 6000 ? 'good' : gp.gpBp < 5000 ? 'bad' : undefined) : undefined}
          />
          {onHand && (
            <StatTile
              label="In the cellar"
              value={describeStock(Math.max(0, onHand.expectedBaseUnits), onHand.item)}
              detail="what should be left right now"
            />
          )}
          {profile.recentChangeBp !== null && (
            <StatTile
              label="Last 4 weeks"
              value={`${profile.recentChangeBp > 0 ? '+' : ''}${(profile.recentChangeBp / 100).toFixed(0)}%`}
              detail="against the four before"
              tone={profile.recentChangeBp <= -1500 ? 'bad' : profile.recentChangeBp >= 1500 ? 'good' : undefined}
            />
          )}
        </div>
        {move && move.verdict === 'squeezed' && (
          <p className="note warn">
            The cost has gone from {formatMoney(move.costThenPence)} to {formatMoney(move.costNowPence)} and the
            board has not moved — the margin has slipped {(Math.abs(move.gpChangeBp) / 100).toFixed(1)} points.
          </p>
        )}
      </section>

      <section className="card">
        <div className="card-head">
          <h2>Night by night</h2>
          <span className="hint">how many went out</span>
        </div>
        <TrendChart
          points={profile.nights.map((n) => ({ date: n.date, label: formatShort(n.date), pence: n.qtyMilli }))}
          format={(v) => formatQty(v)}
          axisFormat={(v) => formatQty(v)}
          seriesLabel="Sold"
        />
      </section>

      <section className="card">
        <div className="card-head">
          <h2>Which nights sell it</h2>
          <span className="hint">all time</span>
        </div>
        <BarChart
          rows={WEEKDAY_ORDER.map((weekday) => {
            const w = profile.byWeekday.find((x) => x.weekday === weekday)
            return { key: weekday, label: weekday.slice(0, 3), value: (w?.qtyMilli ?? 0) / 1000, detail: 'Sold' }
          })}
          format={(v) => String(Math.round(v))}
          labelWidth={44}
        />
      </section>

      {(priceLog.length > 0 || costLog.length > 0) && (
        <section className="card">
          <div className="card-head"><h2>What has changed</h2></div>
          {priceLog.map((point) => (
            <div className="zrow" key={`p-${point.date}`}>
              <span className="zname">Board price<small>{formatShort(point.date)}</small></span>
              <strong className="num">{formatMoney(point.pence)}</strong>
            </div>
          ))}
          {costLog.map((point) => (
            <div className="zrow" key={`c-${point.date}`}>
              <span className="zname">
                Cost per {stockItem?.container?.name ?? 'container'}
                <small>{formatShort(point.date)}</small>
              </span>
              <strong className="num">{formatMoney(point.pence)}</strong>
            </div>
          ))}
          <p className="note">
            Newest first. A cost that rose without the board following is where margin quietly leaks.
          </p>
        </section>
      )}

      <section className="card">
        <div className="card-head">
          <h2>Recent nights</h2>
          <span className="hint">latest first</span>
        </div>
        {[...profile.nights].reverse().slice(0, 10).map((n) => (
          <div className="zrow" key={n.date}>
            <span className="zname">
              {formatShort(n.date)}
              <small>{n.weekday}</small>
            </span>
            <span className="num">{formatQty(n.qtyMilli)}</span>
            <strong className="num">{formatMoney(n.pence)}</strong>
          </div>
        ))}
        {profile.nights.length === 0 && (
          <p className="note" style={{ marginTop: 0 }}>
            Never seen on a till roll yet. It will appear here the first night it sells.
          </p>
        )}
        {rungGap !== null && rungGap < -1 && (
          <p className="note">
            Going over the bar below the board price on average — discounts do that honestly, but it
            is the gap the price check on Trade is watching.
          </p>
        )}
      </section>

      <button type="button" className="btn-small" onClick={onBack}>
        <span style={{ transform: 'rotate(180deg)', display: 'inline-flex' }} aria-hidden="true">
          <IconChevronRight size={14} />
        </span>
        Back to the trade
      </button>
    </div>
  )
}
