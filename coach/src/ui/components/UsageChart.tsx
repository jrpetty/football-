// The last 30 days of cloud spend, one column a day, stacked by feature
// (screen reading at the base). Hover or focus a day for its breakdown; the
// same numbers are in the table view.

import { useEffect, useRef, useState } from 'react'
import { FEATURE_LABEL, USAGE_FEATURES, type UsageFeature, type UsageSummary } from '../../core/usage.ts'
import { dateShort, usd } from '../format.ts'

/** Each feature keeps its colour whatever else is on the chart (categorical slots 1–5, validated on the panel surface). */
export const FEATURE_COLOR: Readonly<Record<UsageFeature, string>> = {
  vision: 'var(--series-1)',
  answer: 'var(--series-2)',
  review: 'var(--series-3)',
  research: 'var(--series-4)',
  knowledge: 'var(--series-5)',
}

const H = 200
const M = { top: 10, right: 8, bottom: 24, left: 48 }
const GAP = 2

function useWidth<T extends Element>(): [React.RefObject<T>, number] {
  const ref = useRef<T>(null)
  const [w, setW] = useState(640)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => setW(Math.max(280, Math.floor(e.contentRect.width))))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  return [ref, w]
}

/** A clean step for about four gridlines. */
function niceStep(max: number): number {
  const raw = max / 4
  const p = 10 ** Math.floor(Math.log10(raw))
  return ([1, 2, 2.5, 5, 10].find((m) => m * p >= raw) ?? 10) * p
}

function tick(v: number, step: number): string {
  return `$${v.toFixed(step >= 1 ? 0 : step >= 0.01 ? 2 : 3)}`
}

/** A column's top segment: 4px rounded at the data end, square at the bottom. */
function topPath(x: number, y: number, w: number, h: number): string {
  const r = Math.min(4, w / 2, h)
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`
}

export function UsageChart({ daily }: { daily: UsageSummary['daily'] }) {
  const [ref, width] = useWidth<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)
  const [table, setTable] = useState(false)
  const total = daily.reduce((n, d) => n + d.usd, 0)
  const present = USAGE_FEATURES.filter((f) => daily.some((d) => (d.byFeature[f] ?? 0) > 0))
  const max = Math.max(...daily.map((d) => d.usd))
  const peak = daily.find((d) => d.usd === max)
  const step = max > 0 ? niceStep(max) : 1
  const top = Math.max(step, Math.ceil(max / step) * step)
  const plotW = width - M.left - M.right
  const plotH = H - M.top - M.bottom
  const band = plotW / daily.length
  const barW = Math.max(2, Math.min(24, band - 4))
  const yOf = (v: number) => M.top + plotH - (v / top) * plotH
  const ticks = Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step)
  const labelled = new Set(daily.map((_, i) => i).filter((i) => (daily.length - 1 - i) % 7 === 0))
  const h = hover !== null ? daily[hover] : null

  return (
    <div className="usage-chart" ref={ref}>
      <div className="chart-head">
        <ul className="chart-legend" aria-label="Legend">
          {present.map((f) => (
            <li key={f}>
              <span className="swatch" style={{ background: FEATURE_COLOR[f] }} aria-hidden="true" />
              {FEATURE_LABEL[f]}
            </li>
          ))}
        </ul>
        <button type="button" className="btn sm ghost" aria-pressed={table} onClick={() => setTable(!table)}>
          {table ? 'Show chart' : 'Show as table'}
        </button>
      </div>
      {total === 0 ? (
        <p className="muted small chart-empty">Nothing spent in the last 30 days.</p>
      ) : table ? (
        <table className="table num-table">
          <thead>
            <tr>
              <th>Day</th>
              {present.map((f) => (
                <th key={f} className="num">
                  {FEATURE_LABEL[f]}
                </th>
              ))}
              <th className="num">Total</th>
            </tr>
          </thead>
          <tbody>
            {daily
              .filter((d) => d.usd > 0)
              .map((d) => (
                <tr key={d.day}>
                  <td>{dateShort(d.day + 'T12:00:00')}</td>
                  {present.map((f) => (
                    <td key={f} className="num">
                      {d.byFeature[f] ? usd(d.byFeature[f]!) : '—'}
                    </td>
                  ))}
                  <td className="num">
                    <b>{usd(d.usd)}</b>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      ) : (
        <div className="chart-frame" onPointerLeave={() => setHover(null)}>
          <svg width={width} height={H} role="img" aria-label={`Spend by day over the last 30 days: ${usd(total)} in all${peak ? `, most on ${dateShort(peak.day + 'T12:00:00')} (${usd(peak.usd)})` : ''}.`}>
            {ticks.map((t) => (
              <g key={t}>
                <line className={t === 0 ? 'axis' : 'grid'} x1={M.left} x2={width - M.right} y1={yOf(t)} y2={yOf(t)} />
                <text className="tick" x={M.left - 8} y={yOf(t)} dy="0.32em" textAnchor="end">
                  {tick(t, step)}
                </text>
              </g>
            ))}
            {daily.map((d, i) => {
              const x = M.left + i * band + (band - barW) / 2
              let base = yOf(0)
              const segs = USAGE_FEATURES.filter((f) => (d.byFeature[f] ?? 0) > 0)
              return (
                <g key={d.day} className={hover !== null && hover !== i ? 'dim' : undefined}>
                  {hover === i ? <rect className="col-wash" x={M.left + i * band} y={M.top} width={band} height={plotH} /> : null}
                  {segs.map((f, k) => {
                    const hgt = ((d.byFeature[f] ?? 0) / top) * plotH
                    const below = k === 0 ? 0 : GAP
                    const drawn = Math.max(1, hgt - below)
                    const y = base - below - drawn
                    base = y
                    return k === segs.length - 1 ? (
                      <path key={f} d={topPath(x, y, barW, drawn)} fill={FEATURE_COLOR[f]} />
                    ) : (
                      <rect key={f} x={x} y={y} width={barW} height={drawn} fill={FEATURE_COLOR[f]} />
                    )
                  })}
                  <rect
                    className="hit"
                    x={M.left + i * band}
                    y={M.top}
                    width={band}
                    height={plotH}
                    tabIndex={0}
                    aria-label={`${dateShort(d.day + 'T12:00:00')}: ${usd(d.usd)}`}
                    onPointerEnter={() => setHover(i)}
                    onFocus={() => setHover(i)}
                    onBlur={() => setHover(null)}
                  />
                  {labelled.has(i) ? (
                    <text className="tick" x={M.left + i * band + band / 2} y={H - 6} textAnchor={i === daily.length - 1 ? 'end' : 'middle'}>
                      {i === daily.length - 1 ? 'Today' : dateShort(d.day + 'T12:00:00')}
                    </text>
                  ) : null}
                </g>
              )
            })}
          </svg>
          {h && hover !== null ? (
            <div
              className={`chart-tip${hover > daily.length / 2 ? ' left' : ''}`}
              role="status"
              // Beside the column, not over it: right of it in the first half, left of it in the second.
              style={{ left: M.left + hover * band + (hover > daily.length / 2 ? -6 : band + 6), top: M.top }}
            >
              <div className="tip-head">
                <b>{usd(h.usd)}</b> <span className="muted">{dateShort(h.day + 'T12:00:00')}</span>
              </div>
              {USAGE_FEATURES.filter((f) => (h.byFeature[f] ?? 0) > 0)
                .reverse()
                .map((f) => (
                  <div key={f} className="tip-row">
                    <span className="line-key" style={{ background: FEATURE_COLOR[f] }} aria-hidden="true" />
                    <b>{usd(h.byFeature[f]!)}</b>
                    <span className="muted">{FEATURE_LABEL[f]}</span>
                  </div>
                ))}
            </div>
          ) : null}
        </div>
      )}
    </div>
  )
}
