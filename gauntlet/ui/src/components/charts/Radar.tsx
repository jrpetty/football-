/** Category radar comparing up to three models (all-pairs colour cap). */
import { useMemo, useState } from 'react';
import type { CategoryInfo } from '../../types.ts';
import { useElementSize, useRootFontSize } from '../../hooks.ts';
import { fmtScore } from '../../format.ts';
import { FloatingTip } from '../ui.tsx';
import { shortCat } from '../leaderboard/util.ts';

export interface RadarSeries {
  id: string;
  label: string;
  color: string;
  scores: Record<string, number | null>;
}

export function Radar({ categories, series }: { categories: CategoryInfo[]; series: RadarSeries[] }) {
  const [ref, size] = useElementSize<HTMLDivElement>();
  const fs = useRootFontSize();
  const s = fs / 14;
  const [hover, setHover] = useState<{ sid: string; cid: string; x: number; y: number } | null>(null);

  const width = Math.max(260, size.width);
  const height = Math.round(Math.min(width * 0.86, 560 * s));

  const geo = useMemo(() => {
    const n = categories.length;
    const labelSize = 11.5 * s;
    const pad = Math.max(...categories.map((c) => shortCat(c.id, c.name).length), 4) * labelSize * 0.6 + 22 * s;
    const R = Math.max(40, Math.min(width / 2 - pad, height / 2 - 26 * s));
    const cx = width / 2;
    const cy = height / 2;
    const ang = (i: number) => -Math.PI / 2 + (i * 2 * Math.PI) / Math.max(1, n);
    const pt = (i: number, v: number) => [cx + Math.cos(ang(i)) * R * v, cy + Math.sin(ang(i)) * R * v] as const;
    return { n, R, cx, cy, ang, pt, labelSize };
  }, [categories, width, height, s]);

  const { n, R, cx, cy, ang, pt, labelSize } = geo;
  if (n < 3)
    return (
      <div className="radar" ref={ref}>
        <div className="chart-empty">A radar needs at least three categories with scores.</div>
      </div>
    );

  const ring = (v: number) =>
    Array.from({ length: n }, (_, i) => pt(i, v))
      .map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`)
      .join(' ') + ' Z';

  const hs = hover ? series.find((x) => x.id === hover.sid) : null;
  const hc = hover ? categories.find((c) => c.id === hover.cid) : null;

  return (
    <div className="radar" ref={ref}>
      <svg width={width} height={height} role="img" aria-label={`Category radar: ${series.map((x) => x.label).join(', ')}`}>
        <title>Category scores by model</title>
        <g className="grid-lines">
          {[0.25, 0.5, 0.75, 1].map((v) => (
            <path key={v} d={ring(v)} fill="none" />
          ))}
          {categories.map((c, i) => {
            const [x, y] = pt(i, 1);
            return <line key={c.id} x1={cx} y1={cy} x2={x} y2={y} />;
          })}
        </g>
        <g className="tick-labels" style={{ fontSize: 10 * s }}>
          {[25, 50, 75, 100].map((v) => (
            <text key={v} x={cx + 4 * s} y={cy - (R * v) / 100 - 3 * s}>
              {v}
            </text>
          ))}
        </g>
        {series.map((sr, si) => {
          const d =
            categories
              .map((c, i) => pt(i, Math.max(0, Math.min(1, sr.scores[c.id] ?? 0))))
              .map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`)
              .join(' ') + ' Z';
          return (
            <g key={sr.id} className="radar-series" style={{ animationDelay: `${si * 120}ms` }}>
              <path d={d} fill={sr.color} fillOpacity={0.1} stroke={sr.color} strokeWidth={2 * Math.min(s, 1.4)} strokeLinejoin="round" />
              {categories.map((c, i) => {
                const v = sr.scores[c.id];
                const [x, y] = pt(i, Math.max(0, Math.min(1, v ?? 0)));
                return (
                  <g key={c.id}>
                    <circle
                      cx={x}
                      cy={y}
                      r={4 * s}
                      fill={v === null || v === undefined ? 'var(--chart-surface)' : sr.color}
                      stroke={v === null || v === undefined ? sr.color : 'var(--chart-surface)'}
                      strokeWidth={2}
                    />
                    <circle
                      cx={x}
                      cy={y}
                      r={12 * s}
                      fill="transparent"
                      onPointerMove={(e) => setHover({ sid: sr.id, cid: c.id, x: e.clientX, y: e.clientY })}
                      onPointerLeave={() => setHover(null)}
                    />
                  </g>
                );
              })}
            </g>
          );
        })}
        <g className="axis-labels" style={{ fontSize: labelSize }}>
          {categories.map((c, i) => {
            const a = ang(i);
            const cos = Math.cos(a);
            const sin = Math.sin(a);
            const x = cx + cos * (R + 12 * s);
            const y = cy + sin * (R + 12 * s);
            const anchor = cos > 0.25 ? 'start' : cos < -0.25 ? 'end' : 'middle';
            const dy = sin > 0.6 ? '0.9em' : sin < -0.6 ? '-0.2em' : '0.35em';
            const label = shortCat(c.id, c.name);
            return (
              <g key={c.id}>
                <text x={x} y={y} dy={dy} textAnchor={anchor}>
                  <title>{c.name}</title>
                  {label}
                </text>
                <circle cx={cx + cos * R} cy={cy + sin * R} r={3 * s} fill={c.color} />
              </g>
            );
          })}
        </g>
      </svg>
      {hover && hs && hc && (
        <FloatingTip x={hover.x} y={hover.y}>
          <div className="tip-title">
            <span className="key" style={{ background: hs.color }} />
            {hs.label}
          </div>
          <div className="tip-row">
            <span>{hc.name}</span>
            <b>{fmtScore(hs.scores[hc.id], 'pct')}</b>
          </div>
        </FloatingTip>
      )}
    </div>
  );
}
