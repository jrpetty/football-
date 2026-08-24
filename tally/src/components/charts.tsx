// ---------------------------------------------------------------------------
// Charts.
//
// Hand-drawn SVG rather than a charting library: five small forms, each with
// exactly the behaviour wanted, weigh less than any dependency that could draw
// them — and this app is meant to open instantly on a phone with one bar of
// signal.
//
// The specs are not improvised. Marks are thin, bars cap at 24px with a 4px
// rounded data-end and a square baseline, lines are 2px, markers carry a 2px
// ring in the surface colour so they stay legible where they overlap, area
// fills are a 10% wash, and gridlines are hairline and recessive. Touching
// marks are separated by a 2px gap of surface rather than a stroke — white
// doing the separating, so no ink is spent on something that is not data.
//
// Colour is assigned by department in core/departments.ts, never by position in
// the list, so filtering one out cannot repaint the others.
// ---------------------------------------------------------------------------

import { useEffect, useRef, useState, type ReactNode } from 'react'
import { formatMoney, formatSigned } from '../core/money.ts'

/** Real pixel width of a container, so text is set in pixels and stays crisp. */
export function useMeasure(): [React.RefObject<HTMLDivElement>, number] {
  const ref = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width ?? 0
      setWidth(w)
    })
    ro.observe(el)
    setWidth(el.getBoundingClientRect().width)
    return () => ro.disconnect()
  }, [])
  return [ref, width]
}

export const seriesVar = (slot: number): string => `var(--series-${Math.min(8, Math.max(1, slot))})`

// --- tooltip ----------------------------------------------------------------

interface TipState {
  x: number
  y: number
  title: string
  rows: Array<{ label: string; value: string; color?: string }>
}

function Tooltip({ tip, width }: { tip: TipState | null; width: number }) {
  if (!tip) return null
  // Kept inside the chart's own box; a tooltip clipped by the phone's edge is
  // worse than one that shifts.
  const left = Math.min(Math.max(tip.x, 70), Math.max(70, width - 70))
  return (
    <div className="chart-tip" style={{ left, top: tip.y }} role="presentation">
      <div className="chart-tip-title">{tip.title}</div>
      {tip.rows.map((r, i) => (
        <div className="chart-tip-row" key={i}>
          {r.color && <span className="chart-tip-dot" style={{ background: r.color }} />}
          <span className="chart-tip-label">{r.label}</span>
          <span className="chart-tip-value num">{r.value}</span>
        </div>
      ))}
    </div>
  )
}

// --- stat tile --------------------------------------------------------------

export function StatTile({
  label,
  value,
  detail,
  tone,
}: {
  label: string
  value: string
  detail?: string
  tone?: 'good' | 'bad' | 'warn'
}) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className={`stat-value num${tone ? ` ${tone}` : ''}`}>{value}</div>
      {detail && <div className="stat-detail">{detail}</div>}
    </div>
  )
}

// --- legend -----------------------------------------------------------------

export function Legend({ items }: { items: Array<{ label: string; color: string }> }) {
  // Present whenever two or more things share a chart: identity must never
  // depend on colour-matching alone.
  if (items.length < 2) return null
  return (
    <ul className="legend">
      {items.map((it) => (
        <li key={it.label}>
          <span className="legend-dot" style={{ background: it.color }} aria-hidden="true" />
          {it.label}
        </li>
      ))}
    </ul>
  )
}

// --- part-to-whole ----------------------------------------------------------

export interface ShareRow {
  key: string
  label: string
  pence: number
  percentBp: number
  slot: number
}

/**
 * The department mix as one horizontal bar.
 *
 * Horizontal because the category names are long, and a stacked bar because the
 * question is what share each takes of the night — not a pie, which cannot be
 * read when two segments are close.
 */
export function ShareBar({ rows }: { rows: ShareRow[] }) {
  const [ref, width] = useMeasure()
  const [tip, setTip] = useState<TipState | null>(null)
  const total = rows.reduce((a, r) => a + r.pence, 0)
  const height = 30
  const gap = 2

  if (total <= 0) return <p className="note">Nothing to break down yet.</p>

  // Each segment gives up the gap; the last one keeps its end flush.
  let x = 0
  const segments = rows.map((r, i) => {
    const w = Math.max(0, (r.pence / total) * width - (i < rows.length - 1 ? gap : 0))
    const seg = { ...r, x, w }
    x += w + gap
    return seg
  })

  return (
    <div className="chart" ref={ref}>
      {width > 0 && (
        <svg width={width} height={height} role="img" aria-label="Share of takings by department">
          <defs>
            <clipPath id="share-round">
              <rect x={0} y={0} width={width} height={height} rx={4} />
            </clipPath>
          </defs>
          <g clipPath="url(#share-round)">
            {segments.map((s) => (
              <rect
                key={s.key}
                x={s.x}
                y={0}
                width={s.w}
                height={height}
                fill={seriesVar(s.slot)}
                onMouseEnter={() =>
                  setTip({
                    x: s.x + s.w / 2,
                    y: height + 6,
                    title: s.label,
                    rows: [
                      { label: 'Taken', value: formatMoney(s.pence), color: seriesVar(s.slot) },
                      { label: 'Share', value: `${(s.percentBp / 100).toFixed(2)}%` },
                    ],
                  })
                }
                onMouseLeave={() => setTip(null)}
              />
            ))}
          </g>
        </svg>
      )}
      <Tooltip tip={tip} width={width} />
    </div>
  )
}

// --- trend ------------------------------------------------------------------

export interface TrendPoint {
  date: string
  label: string
  pence: number
}

/**
 * Takings night by night.
 *
 * One series, so no legend — the heading says what is plotted — and only the
 * final value is labelled. A number against every point is chaos and goes
 * unread; the axis and the tooltip carry the rest.
 */
export function TrendChart({ points }: { points: TrendPoint[] }) {
  const [ref, width] = useMeasure()
  const [tip, setTip] = useState<TipState | null>(null)
  const height = 168
  const padL = 46
  const padR = 14
  const padT = 12
  const padB = 26

  if (points.length === 0) return <p className="note">No nights in this range.</p>
  if (points.length === 1) {
    const only = points[0]!
    return <p className="note">One night so far: {only.label}, {formatMoney(only.pence)}.</p>
  }

  const plotW = Math.max(1, width - padL - padR)
  const plotH = height - padT - padB
  const max = Math.max(...points.map((p) => p.pence))
  // Rounded up to a clean number so the ticks read 0 / 1,000 / 2,000.
  const step = Math.max(1, Math.pow(10, Math.floor(Math.log10(Math.max(1, max)))) / 2)
  const top = Math.ceil(max / step) * step || 1
  const x = (i: number) => padL + (i / (points.length - 1)) * plotW
  const y = (v: number) => padT + plotH - (v / top) * plotH

  const line = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(p.pence).toFixed(1)}`).join(' ')
  const area = `${line} L${x(points.length - 1).toFixed(1)},${padT + plotH} L${padL},${padT + plotH} Z`
  const ticks = [0, top / 2, top]
  const last = points[points.length - 1]!

  return (
    <div className="chart" ref={ref}>
      {width > 0 && (
        <svg width={width} height={height} role="img" aria-label="Takings by night">
          {ticks.map((t) => (
            <g key={t}>
              <line x1={padL} x2={width - padR} y1={y(t)} y2={y(t)} stroke="var(--grid)" strokeWidth={1} />
              <text x={padL - 8} y={y(t) + 4} textAnchor="end" className="chart-axis">
                {t >= 1000_00 ? `£${Math.round(t / 100000)}k` : `£${Math.round(t / 100)}`}
              </text>
            </g>
          ))}

          <path d={area} fill="var(--series-1)" opacity={0.1} />
          <path d={line} fill="none" stroke="var(--series-1)" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />

          {/* The end point, ringed in the surface colour so it reads over the line. */}
          <circle cx={x(points.length - 1)} cy={y(last.pence)} r={4.5} fill="var(--series-1)" stroke="var(--surface)" strokeWidth={2} />

          <text x={padL} y={height - 8} className="chart-axis">{points[0]!.label}</text>
          <text x={width - padR} y={height - 8} textAnchor="end" className="chart-axis">{last.label}</text>

          {/* Hit targets wider than the marks, so a fingertip finds them. */}
          {points.map((p, i) => (
            <rect
              key={p.date}
              x={x(i) - plotW / (points.length - 1) / 2}
              y={padT}
              width={Math.max(12, plotW / (points.length - 1))}
              height={plotH}
              fill="transparent"
              onMouseEnter={() =>
                setTip({ x: x(i), y: y(p.pence) + 12, title: p.label, rows: [{ label: 'Took', value: formatMoney(p.pence), color: 'var(--series-1)' }] })
              }
              onMouseLeave={() => setTip(null)}
            />
          ))}
        </svg>
      )}
      <Tooltip tip={tip} width={width} />
    </div>
  )
}

// --- variance ---------------------------------------------------------------

export interface VariancePoint {
  date: string
  label: string
  variancePence: number
}

/**
 * How far out each night was, above and below the line.
 *
 * Status colours rather than a categorical pair, because short and over
 * genuinely *mean* something — and they are not opposites: being short is worse
 * than being over, which a symmetric warm/cool diverging pair would deny. Both
 * states carry a legend and a labelled axis, so colour is never the only signal.
 */
export function VarianceChart({ points }: { points: VariancePoint[] }) {
  const [ref, width] = useMeasure()
  const [tip, setTip] = useState<TipState | null>(null)
  const height = 150
  const padT = 12
  const padB = 26
  const gap = 2

  if (points.length === 0) return <p className="note">No reconciled nights in this range.</p>

  const plotH = height - padT - padB
  const extent = Math.max(50, ...points.map((p) => Math.abs(p.variancePence)))
  const zero = padT + plotH / 2
  // A little air at each end, so the first and last bars do not run into the
  // card's edges.
  const padX = 8
  const band = Math.max(1, (width - padX * 2) / points.length)
  const barW = Math.min(24, Math.max(3, band - gap))
  const scale = (v: number) => (v / extent) * (plotH / 2)

  return (
    <div className="chart" ref={ref}>
      {width > 0 && (
        <svg width={width} height={height} role="img" aria-label="How far out each night was">
          <line x1={padX} x2={width - padX} y1={zero} y2={zero} stroke="var(--grid)" strokeWidth={1} />

          {points.map((p, i) => {
            const h = Math.abs(scale(p.variancePence))
            const short = p.variancePence < 0
            const cx = padX + band * i + band / 2
            // Rounded at the data end, square against the baseline.
            const r = Math.min(4, h)
            const top = short ? zero : zero - h
            const path = short
              ? `M${cx - barW / 2},${zero} L${cx + barW / 2},${zero} L${cx + barW / 2},${zero + h - r} Q${cx + barW / 2},${zero + h} ${cx + barW / 2 - r},${zero + h} L${cx - barW / 2 + r},${zero + h} Q${cx - barW / 2},${zero + h} ${cx - barW / 2},${zero + h - r} Z`
              : `M${cx - barW / 2},${zero} L${cx - barW / 2},${top + r} Q${cx - barW / 2},${top} ${cx - barW / 2 + r},${top} L${cx + barW / 2 - r},${top} Q${cx + barW / 2},${top} ${cx + barW / 2},${top + r} L${cx + barW / 2},${zero} Z`
            return (
              <path
                key={p.date}
                d={h < 0.5 ? `M${cx - barW / 2},${zero - 1} h${barW} v2 h${-barW} Z` : path}
                fill={short ? 'var(--bad)' : 'var(--warn)'}
                onMouseEnter={() =>
                  setTip({
                    x: cx,
                    y: zero + 14,
                    title: p.label,
                    rows: [
                      {
                        label: short ? 'Short by' : p.variancePence === 0 ? 'Balanced' : 'Over by',
                        value: formatSigned(p.variancePence),
                        color: short ? 'var(--bad)' : 'var(--warn)',
                      },
                    ],
                  })
                }
                onMouseLeave={() => setTip(null)}
              />
            )
          })}

          <text x={2} y={padT + 4} className="chart-axis">over</text>
          <text x={2} y={height - padB + 14} className="chart-axis">short</text>
        </svg>
      )}
      <Tooltip tip={tip} width={width} />
    </div>
  )
}

// --- weekday ----------------------------------------------------------------

export interface BarRow {
  key: string
  label: string
  value: number
  /** Rendered under the bar, e.g. "3 nights". */
  detail?: string
}

/**
 * Average takings by weekday.
 *
 * One hue for every bar: the categories have no natural order, and colouring
 * each darker-where-bigger would double-encode the bar length as hue, spending
 * the only free channel on something the chart already shows.
 */
export function BarChart({ rows, format = formatMoney }: { rows: BarRow[]; format?: (v: number) => string }) {
  const [ref, width] = useMeasure()
  const [tip, setTip] = useState<TipState | null>(null)
  const rowH = 34
  const labelW = 92

  if (rows.length === 0) return <p className="note">Nothing to compare yet.</p>
  const max = Math.max(1, ...rows.map((r) => Math.abs(r.value)))
  const plotW = Math.max(1, width - labelW - 62)

  return (
    <div className="chart" ref={ref}>
      {width > 0 && (
        <svg width={width} height={rows.length * rowH} role="img" aria-label="By weekday">
          {rows.map((r, i) => {
            const w = Math.max(2, (Math.abs(r.value) / max) * plotW)
            const y = i * rowH + 6
            const h = Math.min(24, rowH - 12)
            const rr = Math.min(4, w)
            return (
              <g
                key={r.key}
                onMouseEnter={() =>
                  setTip({ x: labelW + w, y: y + h + 6, title: r.label, rows: [{ label: r.detail ?? 'Value', value: format(r.value), color: 'var(--series-1)' }] })
                }
                onMouseLeave={() => setTip(null)}
              >
                <text x={0} y={y + h / 2 + 4} className="chart-label">{r.label}</text>
                <path
                  d={`M${labelW},${y} L${labelW + w - rr},${y} Q${labelW + w},${y} ${labelW + w},${y + rr} L${labelW + w},${y + h - rr} Q${labelW + w},${y + h} ${labelW + w - rr},${y + h} L${labelW},${y + h} Z`}
                  fill="var(--series-1)"
                />
                <text x={labelW + w + 8} y={y + h / 2 + 4} className="chart-value num">{format(r.value)}</text>
              </g>
            )
          })}
        </svg>
      )}
      <Tooltip tip={tip} width={width} />
    </div>
  )
}

export function ChartCard({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <section className="card">
      <div className="card-head">
        <h2>{title}</h2>
        {subtitle && <span className="hint">{subtitle}</span>}
      </div>
      {children}
    </section>
  )
}
