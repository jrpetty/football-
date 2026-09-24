/**
 * Score vs cost scatter — Gauntlet Index (y) against spend (log x), each model
 * a labelled dot in its own colour, with 95% CI whiskers, the Pareto frontier
 * and the random baseline as a reference line.
 */
import { useMemo, useState } from 'react';
import type { PointerEvent as RPointerEvent } from 'react';
import type { LeaderboardRow } from '../../types.ts';
import { useElementSize, useRootFontSize } from '../../hooks.ts';
import { fmtCost, fmtCostTick, fmtIndex } from '../../format.ts';
import { FloatingTip } from '../ui.tsx';
import { clamp, linearScale, logScale, logTicks, measureText, niceTicks, overlap } from './scale.ts';
import type { Box } from './scale.ts';
import { isBaseline } from '../leaderboard/util.ts';

export type CostMode = 'total' | 'perCase';

interface Pt {
  id: string;
  label: string;
  vendor: string;
  color: string;
  cost: number;
  index: number;
  ci: [number, number] | null;
  costPerPoint: number | null;
  frontier: boolean;
  x: number;
  y: number;
}

export function costOf(row: LeaderboardRow, mode: CostMode): number {
  const total = row.totals?.costUsd ?? 0;
  if (mode === 'total') return total;
  const cases = row.totals?.cases ?? 0;
  return cases > 0 ? total / cases : 0;
}

export function ScoreCostScatter({ rows, mode, tall }: { rows: LeaderboardRow[]; mode: CostMode; tall?: boolean }) {
  const [ref, size] = useElementSize<HTMLDivElement>();
  const fs = useRootFontSize();
  const [hover, setHover] = useState<{ id: string; x: number; y: number } | null>(null);

  const s = fs / 14;
  const width = Math.max(280, size.width);
  const height = Math.round(clamp(width * (tall ? 0.6 : 0.52), 300 * s, tall ? 820 : 560 * s));

  const model = useMemo(() => {
    const scored = rows.filter((r) => typeof r.index === 'number' && Number.isFinite(r.index));
    const baseline = scored.find(isBaseline) ?? null;
    const candidates = scored.filter((r) => !isBaseline(r));
    const plotted = candidates.filter((r) => costOf(r, mode) > 0);
    const unplotted = candidates.filter((r) => costOf(r, mode) <= 0);

    const margin = { top: 34 * s, right: 18 * s, bottom: 52 * s, left: 44 * s };
    const plotW = width - margin.left - margin.right;
    const plotH = height - margin.top - margin.bottom;

    const costs = plotted.map((r) => costOf(r, mode));
    let cMin = costs.length ? Math.min(...costs) : 0.01;
    let cMax = costs.length ? Math.max(...costs) : 1;
    if (cMin === cMax) {
      cMin /= 3;
      cMax *= 3;
    }
    cMin /= 1.9;
    cMax *= 1.9;
    const x = logScale([cMin, cMax], [margin.left, margin.left + plotW]);

    const yVals: number[] = [];
    for (const r of plotted) {
      yVals.push(r.index as number);
      if (r.indexCi95) yVals.push(r.indexCi95[0], r.indexCi95[1]);
    }
    if (baseline) yVals.push(baseline.index as number);
    const lo = yVals.length ? Math.max(0, Math.floor((Math.min(...yVals) - 4) / 10) * 10) : 0;
    const hi = yVals.length ? Math.min(100, Math.ceil((Math.max(...yVals) + 4) / 10) * 10) : 100;
    const yt = niceTicks(lo, hi, 6);
    const y = linearScale([yt.min, yt.max], [margin.top + plotH, margin.top]);

    // Pareto frontier: cheapest-first, keep strictly improving scores.
    const byCost = [...plotted].sort((a, b) => costOf(a, mode) - costOf(b, mode));
    const frontierIds = new Set<string>();
    let best = -Infinity;
    for (const r of byCost) {
      if ((r.index as number) > best) {
        best = r.index as number;
        frontierIds.add(r.contestantId);
      }
    }

    const pts: Pt[] = plotted.map((r) => {
      const c = costOf(r, mode);
      return {
        id: r.contestantId,
        label: r.label,
        vendor: r.vendor,
        color: r.color,
        cost: c,
        index: r.index as number,
        ci: r.indexCi95,
        costPerPoint: r.costPerPoint,
        frontier: frontierIds.has(r.contestantId),
        x: x(c),
        y: y(r.index as number),
      };
    });
    const frontier = pts.filter((p) => p.frontier).sort((a, b) => a.cost - b.cost);

    // Greedy label placement, best models first. Points that (nearly) coincide
    // share one stacked label so a name never sits beside someone else's dot.
    const r = 6.5 * s;
    const labelSize = 12.5 * s;
    const lh = labelSize * 1.2;
    const gap = r + 5 * s;
    const placed: Box[] = [];
    const dots: Box[] = pts.map((p) => ({ x: p.x - r - 2, y: p.y - r - 2, w: 2 * r + 4, h: 2 * r + 4 }));
    const bounds = { x0: margin.left + 2, x1: width - 4, y0: margin.top - 6 * s, y1: margin.top + plotH - 2 };
    const labels: Array<{ ids: string[]; lines: string[]; x: number; y: number; anchor: 'start' | 'middle' | 'end'; leader: [number, number, number, number] | null; color: string }> = [];

    const order = [...pts].sort((a, b) => b.index - a.index);
    const done = new Set<string>();
    const clusters: Pt[][] = [];
    for (const p of order) {
      if (done.has(p.id)) continue;
      const group = order.filter((q) => !done.has(q.id) && Math.hypot(q.x - p.x, q.y - p.y) <= r * 2.2);
      group.forEach((q) => done.add(q.id));
      clusters.push(group);
    }

    for (const group of clusters) {
      const cx = group.reduce((a, q) => a + q.x, 0) / group.length;
      const cy = group.reduce((a, q) => a + q.y, 0) / group.length;
      const lines = group.map((q) => q.label);
      const w = Math.max(...lines.map((l) => measureText(l, labelSize, 650)));
      const h = lh * lines.length;
      const own = new Set(group.map((q) => q.id));
      const cands: Array<{ box: Box; x: number; y: number; anchor: 'start' | 'middle' | 'end' }> = [
        // Side placements first, then diagonals (which stay visually attached to the dot), then centred above/below.
        { box: { x: cx + gap, y: cy - h / 2, w, h }, x: cx + gap, y: cy, anchor: 'start' },
        { box: { x: cx - gap - w, y: cy - h / 2, w, h }, x: cx - gap, y: cy, anchor: 'end' },
        { box: { x: cx + gap * 0.7, y: cy - gap - h + 2, w, h }, x: cx + gap * 0.7, y: cy - gap - h / 2 + 2, anchor: 'start' },
        { box: { x: cx + gap * 0.7, y: cy + gap - 2, w, h }, x: cx + gap * 0.7, y: cy + gap + h / 2 - 2, anchor: 'start' },
        { box: { x: cx - gap * 0.7 - w, y: cy - gap - h + 2, w, h }, x: cx - gap * 0.7, y: cy - gap - h / 2 + 2, anchor: 'end' },
        { box: { x: cx - gap * 0.7 - w, y: cy + gap - 2, w, h }, x: cx - gap * 0.7, y: cy + gap + h / 2 - 2, anchor: 'end' },
        { box: { x: cx - w / 2, y: cy - gap - h, w, h }, x: cx, y: cy - gap - h / 2, anchor: 'middle' },
        { box: { x: cx - w / 2, y: cy + gap, w, h }, x: cx, y: cy + gap + h / 2, anchor: 'middle' },
      ];
      let chosen: (typeof cands)[number] | null = null;
      let bestCost = Infinity;
      for (const c of cands) {
        const b = c.box;
        if (b.x < bounds.x0 || b.x + b.w > bounds.x1 || b.y < bounds.y0 || b.y + b.h > bounds.y1) continue;
        let cost = 0;
        for (const o of placed) cost += overlap(b, o);
        pts.forEach((q, i) => {
          if (own.has(q.id)) return;
          cost += overlap(b, dots[i]) * 2;
          // Keep clear of other dots' surroundings so a label never reads as a neighbour's.
          const halo = { x: dots[i].x - 8 * s, y: dots[i].y - 4 * s, w: dots[i].w + 16 * s, h: dots[i].h + 8 * s };
          cost += overlap(b, halo) * 0.35;
        });
        if (cost < bestCost) {
          bestCost = cost;
          chosen = c;
        }
        if (cost === 0) break;
      }
      // Drop labels that would collide; the legend + tooltip still carry identity.
      if (chosen && bestCost < lh * 1.5) {
        placed.push(chosen.box);
        const b = chosen.box;
        const nx = Math.max(b.x, Math.min(cx, b.x + b.w));
        const ny = Math.max(b.y + 2, Math.min(cy, b.y + b.h - 2));
        const dist = Math.hypot(nx - cx, ny - cy);
        const leader: [number, number, number, number] | null = dist > r + 4 ? [cx + ((nx - cx) / dist) * (r + 2), cy + ((ny - cy) / dist) * (r + 2), nx, ny] : null;
        labels.push({ ids: group.map((q) => q.id), lines, x: chosen.x, y: chosen.y, anchor: chosen.anchor, leader, color: group[0].color });
      }
    }

    const xt = logTicks(cMin, cMax);
    return { pts, frontier, baseline, unplotted, margin, plotW, plotH, x, y, xt, yt, labels, r, labelSize };
  }, [rows, mode, width, height, s]);

  const { pts, frontier, baseline, unplotted, margin, plotW, plotH, x, y, xt, yt, labels, r, labelSize } = model;
  const hovered = hover ? pts.find((p) => p.id === hover.id) : null;
  const tickSize = 11.5 * s;

  const onMove = (e: RPointerEvent<SVGSVGElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    let best: Pt | null = null;
    let bd = (30 * s) ** 2;
    for (const p of pts) {
      const d = (p.x - mx) ** 2 + (p.y - my) ** 2;
      if (d < bd) {
        bd = d;
        best = p;
      }
    }
    setHover(best ? { id: best.id, x: e.clientX, y: e.clientY } : null);
  };

  const frontierPath = frontier.map((p, i) => `${i ? 'L' : 'M'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');
  const frontierArea =
    frontier.length > 1
      ? `${frontierPath} L${frontier[frontier.length - 1].x.toFixed(1)},${(margin.top + plotH).toFixed(1)} L${frontier[0].x.toFixed(1)},${(margin.top + plotH).toFixed(1)} Z`
      : '';
  const costLabel = mode === 'total' ? 'Total spend per model' : 'Average spend per case';

  return (
    <div className="scatter" ref={ref}>
      {pts.length === 0 ? (
        <div className="chart-empty">No priced, scored models to plot yet.</div>
      ) : (
        <svg
          width={width}
          height={height}
          role="img"
          aria-labelledby="scatter-title scatter-desc"
          onPointerMove={onMove}
          onPointerLeave={() => setHover(null)}
          style={{ ['--s' as string]: s }}
        >
          <title id="scatter-title">Gauntlet Index versus cost</title>
          <desc id="scatter-desc">
            {pts.map((p) => `${p.label}: index ${fmtIndex(p.index)}, cost ${fmtCost(p.cost)}`).join('; ')}
          </desc>
          <defs>
            <linearGradient id="frontier-wash" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor="var(--accent)" stopOpacity="0.13" />
              <stop offset="1" stopColor="var(--accent)" stopOpacity="0" />
            </linearGradient>
          </defs>

          {/* Grid */}
          <g className="grid-lines">
            {yt.ticks.map((t) => (
              <line key={`y${t}`} x1={margin.left} x2={margin.left + plotW} y1={y(t)} y2={y(t)} />
            ))}
            {xt.map((t) => (
              <line key={`x${t}`} x1={x(t)} x2={x(t)} y1={margin.top} y2={margin.top + plotH} />
            ))}
          </g>
          <line className="axis-line" x1={margin.left} x2={margin.left + plotW} y1={margin.top + plotH} y2={margin.top + plotH} />
          <g className="tick-labels" style={{ fontSize: tickSize }}>
            {yt.ticks.map((t) => (
              <text key={`yl${t}`} x={margin.left - 8 * s} y={y(t)} dy="0.35em" textAnchor="end">
                {t}
              </text>
            ))}
            {xt.map((t) => (
              <text key={`xl${t}`} x={x(t)} y={margin.top + plotH + 18 * s} textAnchor="middle">
                {fmtCostTick(t)}
              </text>
            ))}
          </g>
          <text className="axis-title" x={margin.left - 8 * s} y={margin.top - 16 * s} style={{ fontSize: tickSize }}>
            GAUNTLET INDEX
          </text>
          <text className="axis-title" x={margin.left + plotW / 2} y={height - 8 * s} textAnchor="middle" style={{ fontSize: tickSize }}>
            {width < 560 ? 'SPEND (USD, LOG) →' : `${costLabel.toUpperCase()} (USD, LOG SCALE) →`}
          </text>
          <text className="better-hint" x={margin.left + 10 * s} y={margin.top + 16 * s} style={{ fontSize: tickSize }}>
            ↖ cheaper &amp; stronger
          </text>

          {/* Random baseline reference */}
          {baseline && typeof baseline.index === 'number' && baseline.index >= yt.min && (
            <g className="baseline-ref">
              <line x1={margin.left} x2={margin.left + plotW} y1={y(baseline.index)} y2={y(baseline.index)} />
              <text x={margin.left + plotW - 4 * s} y={y(baseline.index) - 6 * s} textAnchor="end" style={{ fontSize: tickSize }}>
                {baseline.label} · {fmtIndex(baseline.index)}
              </text>
            </g>
          )}

          {/* Pareto frontier */}
          {frontier.length > 1 && (
            <g className="frontier">
              <path d={frontierArea} fill="url(#frontier-wash)" stroke="none" />
              <path className="frontier-line" d={frontierPath} pathLength={1} />
            </g>
          )}

          {/* CI whiskers */}
          <g className="whiskers">
            {pts.map(
              (p) =>
                p.ci && (
                  <g key={p.id} stroke={p.color}>
                    <line x1={p.x} x2={p.x} y1={y(p.ci[0])} y2={y(p.ci[1])} />
                    <line x1={p.x - 4 * s} x2={p.x + 4 * s} y1={y(p.ci[0])} y2={y(p.ci[0])} />
                    <line x1={p.x - 4 * s} x2={p.x + 4 * s} y1={y(p.ci[1])} y2={y(p.ci[1])} />
                  </g>
                ),
            )}
          </g>

          {/* Dots */}
          <g className="dots">
            {pts.map((p, i) => (
              <g
                key={p.id}
                className={hover?.id === p.id ? 'dot active' : 'dot'}
                style={{ animationDelay: `${120 + i * 70}ms` }}
                tabIndex={0}
                role="img"
                aria-label={`${p.label}: Gauntlet Index ${fmtIndex(p.index)}, cost ${fmtCost(p.cost)}${p.frontier ? ', on the Pareto frontier' : ''}`}
                onFocus={(e) => {
                  const b = (e.currentTarget as SVGGElement).getBoundingClientRect();
                  setHover({ id: p.id, x: b.left + b.width / 2, y: b.top + b.height / 2 });
                }}
                onBlur={() => setHover(null)}
              >
                <circle cx={p.x} cy={p.y} r={16 * s} fill="transparent" />
                {p.frontier && <circle className="frontier-halo" cx={p.x} cy={p.y} r={r + 5 * s} stroke={p.color} />}
                <circle cx={p.x} cy={p.y} r={r} fill={p.color} className="dot-mark" />
              </g>
            ))}
          </g>

          {/* Direct labels */}
          <g className="leaders">
            {labels.map((l) => (l.leader ? <line key={l.ids.join('|')} x1={l.leader[0]} y1={l.leader[1]} x2={l.leader[2]} y2={l.leader[3]} stroke={l.color} /> : null))}
          </g>
          <g className="point-labels" style={{ fontSize: labelSize }}>
            {labels.map((l) => (
              <text key={l.ids.join('|')} x={l.x} y={l.y} textAnchor={l.anchor}>
                {l.lines.map((line, i) => (
                  <tspan key={line} x={l.x} dy={i === 0 ? `${0.35 - ((l.lines.length - 1) * 1.2) / 2}em` : '1.2em'}>
                    {line}
                  </tspan>
                ))}
              </text>
            ))}
          </g>
        </svg>
      )}

      <div className="chart-legend" aria-label="Legend">
        {pts.map((p) => (
          <span key={p.id} className="lg-item">
            <span className="lg-dot" style={{ background: p.color }} />
            {p.label}
          </span>
        ))}
        {frontier.length > 1 && (
          <span className="lg-item">
            <span className="lg-line accent" />
            Pareto frontier
          </span>
        )}
        {baseline && (
          <span className="lg-item">
            <span className="lg-line dashed" />
            {baseline.label}
          </span>
        )}
        <span className="lg-item">
          <span className="lg-whisker" />
          95% CI
        </span>
      </div>
      {unplotted.length > 0 && (
        <div className="chart-note">Not plotted (no recorded cost): {unplotted.map((r) => r.label).join(', ')}</div>
      )}

      {hovered && hover && (
        <FloatingTip x={hover.x} y={hover.y}>
          <div className="tip-title">
            <span className="key" style={{ background: hovered.color, height: 10, width: 10, borderRadius: 5 }} />
            {hovered.label}
          </div>
          <div className="tip-row">
            <span>Gauntlet Index</span>
            <b>{fmtIndex(hovered.index)}</b>
          </div>
          {hovered.ci && (
            <div className="tip-row">
              <span>95% CI</span>
              <b>
                {fmtIndex(hovered.ci[0])} – {fmtIndex(hovered.ci[1])}
              </b>
            </div>
          )}
          <div className="tip-row">
            <span>{mode === 'total' ? 'Total cost' : 'Cost / case'}</span>
            <b>{fmtCost(hovered.cost)}</b>
          </div>
          <div className="tip-row">
            <span>$ / point</span>
            <b>{fmtCost(hovered.costPerPoint)}</b>
          </div>
          {hovered.frontier && <div style={{ color: 'var(--accent)', fontWeight: 650 }}>On the Pareto frontier</div>}
        </FloatingTip>
      )}
    </div>
  );
}
