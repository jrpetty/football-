/** Multi-series line chart with crosshair tooltip and an optional "current x" marker. */
import { useMemo, useState } from 'react';
import type { PointerEvent as RPointerEvent } from 'react';
import { useElementSize, useRootFontSize } from '../../hooks.ts';
import { FloatingTip } from '../ui.tsx';
import { SERIES_PALETTE, linearScale, measureText, niceTicks } from './scale.ts';

export interface LineSeries {
  name: string;
  color?: string;
  points: Array<{ x: number; y: number }>;
}

function fmtNum(v: number): string {
  if (!Number.isFinite(v)) return '—';
  const a = Math.abs(v);
  if (a >= 1e6) return `${+(v / 1e6).toFixed(1)}M`;
  if (a >= 1e4) return `${+(v / 1e3).toFixed(1)}k`;
  if (a >= 100) return String(Math.round(v));
  return String(+v.toFixed(2));
}

export function LineChart({ series, marker, height: hProp, xLabel, title }: { series: LineSeries[]; marker?: number | null; height?: number; xLabel?: string; title: string }) {
  const [ref, size] = useElementSize<HTMLDivElement>();
  const fs = useRootFontSize();
  const s = fs / 14;
  const [hx, setHx] = useState<{ x: number; cx: number; cy: number } | null>(null);
  const width = Math.max(240, size.width);
  const height = hProp ?? Math.round(Math.min(Math.max(width * 0.36, 200 * s), 340 * s));

  const colored = useMemo(() => series.map((sr, i) => ({ ...sr, color: sr.color ?? SERIES_PALETTE[i % SERIES_PALETTE.length] })), [series]);

  const geo = useMemo(() => {
    const all = colored.flatMap((sr) => sr.points);
    const xs = all.map((p) => p.x);
    const ys = all.map((p) => p.y);
    const xMin = xs.length ? Math.min(...xs) : 0;
    const xMax = xs.length ? Math.max(...xs) : 1;
    const yt = niceTicks(Math.min(0, ...ys), ys.length ? Math.max(...ys) : 1, 5);
    const endLabels = colored.length <= 4;
    const maxLabelW = endLabels ? Math.max(0, ...colored.map((sr) => measureText(sr.name, 11.5 * s, 600))) : 0;
    const margin = { top: 14 * s, right: (endLabels ? maxLabelW + 16 * s : 12 * s), bottom: 30 * s, left: 44 * s };
    const x = linearScale([xMin, xMax === xMin ? xMin + 1 : xMax], [margin.left, width - margin.right]);
    const y = linearScale([yt.min, yt.max], [height - margin.bottom, margin.top]);
    const maxTicks = Math.max(3, Math.min(10, Math.floor(width / (70 * s))));
    const span = xMax - xMin;
    let xTicks: number[];
    if (xs.every((v) => Number.isInteger(v)) && span > 0 && span <= 40) {
      const step = Math.max(1, Math.ceil(span / maxTicks));
      xTicks = [];
      for (let v = xMin; v <= xMax; v += step) xTicks.push(v);
    } else {
      xTicks = niceTicks(xMin, xMax, maxTicks).ticks.filter((t) => t >= xMin && t <= xMax);
    }
    const xs2 = Array.from(new Set(xs)).sort((a, b) => a - b);
    return { x, y, yt, xt: xTicks, margin, xs: xs2, endLabels };
  }, [colored, width, height, s]);

  const { x, y, yt, xt, margin, xs, endLabels } = geo;
  if (!colored.some((sr) => sr.points.length)) {
    return (
      <div ref={ref} className="linechart">
        <div className="chart-empty">No series data.</div>
      </div>
    );
  }

  const onMove = (e: RPointerEvent<SVGSVGElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    let best = xs[0];
    let bd = Infinity;
    for (const v of xs) {
      const d = Math.abs(x(v) - mx);
      if (d < bd) {
        bd = d;
        best = v;
      }
    }
    setHx({ x: best, cx: e.clientX, cy: e.clientY });
  };

  // End labels: nudge apart minimally; drop if they would still collide.
  const ends = colored
    .map((sr) => {
      const last = sr.points[sr.points.length - 1];
      return last ? { name: sr.name, color: sr.color, y: y(last.y), x: x(last.x) } : null;
    })
    .filter((v): v is { name: string; color: string; y: number; x: number } => v !== null)
    .sort((a, b) => a.y - b.y);
  let collide = false;
  for (let i = 1; i < ends.length; i++) if (ends[i].y - ends[i - 1].y < 13 * s) collide = true;

  return (
    <div ref={ref} className="linechart">
      <svg width={width} height={height} role="img" aria-label={title} onPointerMove={onMove} onPointerLeave={() => setHx(null)}>
        <title>{title}</title>
        <g className="grid-lines">
          {yt.ticks.map((t) => (
            <line key={t} x1={margin.left} x2={width - margin.right} y1={y(t)} y2={y(t)} />
          ))}
        </g>
        <line className="axis-line" x1={margin.left} x2={width - margin.right} y1={height - margin.bottom} y2={height - margin.bottom} />
        <g className="tick-labels" style={{ fontSize: 11 * s }}>
          {yt.ticks.map((t) => (
            <text key={t} x={margin.left - 7 * s} y={y(t)} dy="0.35em" textAnchor="end">
              {fmtNum(t)}
            </text>
          ))}
          {xt.map((t) => (
            <text key={t} x={x(t)} y={height - margin.bottom + 17 * s} textAnchor="middle">
              {fmtNum(t)}
            </text>
          ))}
        </g>
        {xLabel && (
          <text className="axis-title" x={width - margin.right} y={height - 2 * s} textAnchor="end" style={{ fontSize: 10.5 * s }}>
            {xLabel.toUpperCase()}
          </text>
        )}
        {marker !== undefined && marker !== null && Number.isFinite(marker) && (
          <line className="marker-line" x1={x(marker)} x2={x(marker)} y1={margin.top} y2={height - margin.bottom} />
        )}
        {colored.map((sr) => {
          const d = sr.points.map((p, i) => `${i ? 'L' : 'M'}${x(p.x).toFixed(1)},${y(p.y).toFixed(1)}`).join(' ');
          const last = sr.points[sr.points.length - 1];
          return (
            <g key={sr.name}>
              <path d={d} fill="none" stroke={sr.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
              {last && <circle cx={x(last.x)} cy={y(last.y)} r={4} fill={sr.color} stroke="var(--chart-surface)" strokeWidth={2} />}
            </g>
          );
        })}
        {endLabels && !collide && (
          <g className="point-labels" style={{ fontSize: 11.5 * s }}>
            {ends.map((e) => (
              <text key={e.name} x={e.x + 8 * s} y={e.y} dy="0.35em">
                {e.name}
              </text>
            ))}
          </g>
        )}
        {hx && (
          <g>
            <line className="crosshair" x1={x(hx.x)} x2={x(hx.x)} y1={margin.top} y2={height - margin.bottom} />
            {colored.map((sr) => {
              const p = sr.points.find((q) => q.x === hx.x);
              return p ? <circle key={sr.name} cx={x(p.x)} cy={y(p.y)} r={4.5} fill={sr.color} stroke="var(--chart-surface)" strokeWidth={2} /> : null;
            })}
          </g>
        )}
      </svg>
      {colored.length > 1 && (
        <div className="chart-legend">
          {colored.map((sr) => (
            <span key={sr.name} className="lg-item">
              <span className="lg-line" style={{ background: sr.color }} />
              {sr.name}
            </span>
          ))}
        </div>
      )}
      {hx && (
        <FloatingTip x={hx.cx} y={hx.cy}>
          <div className="tip-title">
            {xLabel ?? 'x'} {fmtNum(hx.x)}
          </div>
          {colored.map((sr) => {
            const p = sr.points.find((q) => q.x === hx.x);
            return (
              <div className="tip-row" key={sr.name}>
                <span className="row" style={{ gap: 6 }}>
                  <span className="key" style={{ background: sr.color }} />
                  {sr.name}
                </span>
                <b>{p ? fmtNum(p.y) : '—'}</b>
              </div>
            );
          })}
        </FloatingTip>
      )}
    </div>
  );
}
