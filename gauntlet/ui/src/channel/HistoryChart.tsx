/** Metric vs release date: one line per model family, every model a labelled dot. */
import { useMemo, useState } from 'react';
import { useElementSize, useRootFontSize } from '../hooks.ts';
import { FloatingTip } from '../components/ui.tsx';
import type { HistoryData, HistoryPoint } from './channelApi.ts';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DAY = 86_400_000;

export function metricValue(p: HistoryPoint, metric: string): number | null {
  if (metric === 'index') return p.index;
  const v = p.categoryScores[metric];
  return typeof v === 'number' ? Math.round(v * 1000) / 10 : null;
}

function niceStep(span: number, target: number): number {
  const raw = span / Math.max(1, target);
  const mag = 10 ** Math.floor(Math.log10(raw));
  const n = raw / mag;
  return (n >= 5 ? 10 : n >= 2 ? 5 : n >= 1 ? 2 : 1) * mag;
}

interface Placed {
  x: number;
  y: number;
  anchor: 'start' | 'end';
}

function place(points: Array<{ x: number; y: number; w: number }>, bounds: { w: number; h: number; top: number }, lh: number): Array<Placed | null> {
  const boxes: Array<{ x1: number; x2: number; y1: number; y2: number }> = [];
  const out: Array<Placed | null> = points.map(() => null);
  const order = points.map((p, i) => ({ ...p, i })).sort((a, b) => a.y - b.y);
  for (const p of order) {
    let done = false;
    for (const dy of [0, -lh, lh, -2 * lh, 2 * lh, -3 * lh, 3 * lh]) {
      for (const side of ['start', 'end'] as const) {
        const x = side === 'start' ? p.x + lh * 0.7 : p.x - lh * 0.7;
        const y = p.y + lh * 0.35 + dy;
        const b = { x1: side === 'start' ? x : x - p.w, x2: side === 'start' ? x + p.w : x, y1: y - lh * 0.85, y2: y + lh * 0.2 };
        if (b.x2 > bounds.w - 2 || b.x1 < 2 || b.y1 < bounds.top - lh * 0.5 || b.y2 > bounds.h) continue;
        if (boxes.some((o) => b.x1 < o.x2 && b.x2 > o.x1 && b.y1 < o.y2 && b.y2 > o.y1)) continue;
        boxes.push(b);
        out[p.i] = { x, y, anchor: side };
        done = true;
        break;
      }
      if (done) break;
    }
  }
  return out;
}

export function HistoryChart({ data, metricLabel, height: hProp, big, highlight }: { data: HistoryData; metricLabel: string; height?: number; big?: boolean; highlight?: string | null }) {
  const [ref, size] = useElementSize<HTMLDivElement>();
  const fs = useRootFontSize();
  const s = big ? 1.9 : fs / 14;
  const width = Math.max(320, size.width);
  // In Broadcast mode keep the whole chart above the viewer caption strip.
  const broadcast = !big && document.documentElement.getAttribute('data-broadcast') === 'on';
  const auto = Math.round(Math.min(Math.max(width * 0.42, 280 * s), 520 * s));
  const height = hProp ?? (broadcast ? Math.min(auto, Math.round(window.innerHeight * 0.5)) : auto);
  const [hover, setHover] = useState<{ p: HistoryPoint; cx: number; cy: number } | null>(null);

  const geo = useMemo(() => {
    const pts = data.families.flatMap((f) => f.points.map((p) => ({ p, f, v: metricValue(p, data.metric)!, t: Date.parse(p.releaseDate!) })));
    if (!pts.length) return null;
    const m = { l: 50 * s, r: 18 * s, t: 18 * s, b: 40 * s };
    const ts = pts.map((x) => x.t);
    const pad = Math.max(30 * DAY, (Math.max(...ts) - Math.min(...ts)) * 0.05);
    const t0 = Math.min(...ts) - pad;
    const t1 = Math.max(...ts) + pad * 3;
    const vs = pts.map((x) => x.v);
    const step = niceStep(Math.max(10, Math.max(...vs) - Math.min(...vs)), 5);
    const y0 = Math.max(0, Math.floor((Math.min(...vs) - step / 2) / step) * step);
    const y1 = Math.min(100, Math.ceil((Math.max(...vs) + step / 2) / step) * step);
    const sx = (t: number) => m.l + ((t - t0) / (t1 - t0)) * (width - m.l - m.r);
    const sy = (v: number) => height - m.b - ((v - y0) / (y1 - y0 || 1)) * (height - m.t - m.b);
    const yTicks: number[] = [];
    for (let v = y0; v <= y1 + 1e-9; v += step) yTicks.push(v);
    const months = (t1 - t0) / (30.4 * DAY);
    const every = months <= 8 ? 1 : months <= 24 ? 3 : months <= 48 ? 6 : 12;
    const xTicks: Array<{ t: number; label: string }> = [];
    const start = new Date(t0);
    let y = start.getUTCFullYear();
    let mo = Math.ceil(start.getUTCMonth() / every) * every;
    for (let g = 0; g < 200; g++) {
      if (mo >= 12) {
        y += Math.floor(mo / 12);
        mo %= 12;
      }
      const t = Date.UTC(y, mo, 1);
      if (t > t1) break;
      if (t >= t0) xTicks.push({ t, label: every === 12 || mo === 0 || !xTicks.length ? `${MONTHS[mo]} ${y}` : MONTHS[mo]! });
      mo += every;
    }
    const lf = 12 * s;
    const labels = place(
      pts.map((x) => ({ x: sx(x.t), y: sy(x.v), w: x.p.label.length * lf * 0.56 })),
      { w: width, h: height - m.b, top: m.t },
      lf * 1.15,
    );
    return { pts, m, sx, sy, yTicks, xTicks, labels, lf };
  }, [data, width, height, s]);

  if (!geo)
    return (
      <div ref={ref} className="ch-chart-empty">
        No dated models with results yet. Add release dates below, or run a suite first.
      </div>
    );
  const { pts, m, sx, sy, yTicks, xTicks, labels, lf } = geo;
  return (
    <div ref={ref} className={big ? 'ch-chart big' : 'ch-chart'} onPointerLeave={() => setHover(null)}>
      <svg width={width} height={height} role="img" aria-label={`${metricLabel} by release date, one line per model family`}>
        {yTicks.map((v) => (
          <g key={`y${v}`}>
            <line className="gridline" x1={m.l} x2={width - m.r} y1={sy(v)} y2={sy(v)} />
            <text className="tick" x={m.l - 8 * s} y={sy(v) + 4 * s} textAnchor="end" fontSize={11 * s}>
              {+v.toFixed(1)}
            </text>
          </g>
        ))}
        {xTicks.map((x) => (
          <g key={`x${x.t}`}>
            <line className="gridline" x1={sx(x.t)} x2={sx(x.t)} y1={m.t} y2={height - m.b} />
            <text className="tick" x={sx(x.t)} y={height - m.b + 18 * s} textAnchor="middle" fontSize={11 * s}>
              {x.label}
            </text>
          </g>
        ))}
        {data.families.map((f) =>
          f.line.length > 1 ? (
            <polyline
              key={f.family}
              points={f.line.map((p) => `${sx(Date.parse(p.releaseDate!))},${sy(metricValue(p, data.metric)!)}`).join(' ')}
              fill="none"
              stroke={f.color}
              strokeWidth={2.6 * s}
              strokeLinejoin="round"
              opacity={highlight && highlight !== f.family ? 0.2 : 0.9}
            />
          ) : null,
        )}
        {pts.map((x) => (
          <circle
            key={x.p.contestantId}
            cx={sx(x.t)}
            cy={sy(x.v)}
            r={6 * s}
            fill={x.p.color}
            stroke="var(--surface-1)"
            strokeWidth={2 * s}
            opacity={highlight && highlight !== x.f.family ? 0.25 : 1}
            onPointerMove={(e) => setHover({ p: x.p, cx: e.clientX, cy: e.clientY })}
          />
        ))}
        {labels.map((l, i) =>
          l ? (
            <text key={`l${i}`} className="pt-label" x={l.x} y={l.y} textAnchor={l.anchor} fontSize={lf} opacity={highlight && highlight !== pts[i]!.f.family ? 0.3 : 1}>
              {pts[i]!.p.label}
            </text>
          ) : null,
        )}
      </svg>
      <div className="ch-legend">
        {data.families.map((f) => (
          <span key={f.family}>
            <i style={{ background: f.color }} />
            {f.family}
          </span>
        ))}
      </div>
      {hover && (
        <FloatingTip x={hover.cx} y={hover.cy}>
          <strong>{hover.p.label}</strong>
          <div>
            {metricLabel}: {metricValue(hover.p, data.metric)?.toFixed(1)}
            {data.metric === 'index' && hover.p.indexCi95 ? ` (95% CI ${hover.p.indexCi95[0].toFixed(1)}–${hover.p.indexCi95[1].toFixed(1)})` : ''}
          </div>
          <div className="muted">
            {hover.p.family}
            {hover.p.tier ? ` · ${hover.p.tier}` : ''} · released {hover.p.releaseDate}
          </div>
        </FloatingTip>
      )}
    </div>
  );
}
