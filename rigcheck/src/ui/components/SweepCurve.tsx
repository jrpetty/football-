import { useState } from 'react';
import type { SweepResult } from '../../core/types.ts';

/**
 * The upgrade curve. One series, one axis — geometric-mean FPS against candidate
 * position on the curve. The knee is direct-labelled; nothing else is, because a
 * number on every point is noise.
 *
 * The shaded ribbon is the model's own uncertainty. It is drawn deliberately: a
 * curve that looks precise while the band is +/-20% would be a lie told with
 * geometry.
 */
export function SweepCurve({ sweep, currency }: { sweep: SweepResult; currency?: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const raw = sweep.points.filter((p) => !p.blocked);
  if (raw.length < 2) {
    return <div className="empty">Not enough candidates to draw a curve.</div>;
  }

  const priced = raw.every((p) => p.price != null);
  /**
   * The sweep orders points by performance, which is what knee detection needs.
   * The chart needs them ordered by whatever is on the X axis, or the polyline
   * doubles back on itself and the uncertainty ribbon tears open. A cheaper part
   * that outperforms a dearer one is real signal, not an error — it just has to
   * be drawn in X order to be readable.
   */
  const pts = priced ? [...raw].sort((a, b) => a.price! - b.price!) : raw;

  const W = 720;
  const H = 260;
  const M = { t: 14, r: 16, b: 34, l: 46 };
  const iw = W - M.l - M.r;
  const ih = H - M.t - M.b;

  const xs = pts.map((p, i) => (priced ? p.price! : i));
  const xMin = Math.min(...xs);
  const xMax = Math.max(...xs);
  const yMax = Math.max(...pts.map((p) => p.geomeanFps * (1 + p.uncertainty))) * 1.04;
  const yMin = 0;

  const sx = (v: number) => M.l + (xMax === xMin ? iw / 2 : ((v - xMin) / (xMax - xMin)) * iw);
  const sy = (v: number) => M.t + ih - ((v - yMin) / (yMax - yMin)) * ih;

  /**
   * When prices exist, the connected path follows the Pareto frontier only.
   * Connecting every point would imply a progression between purchases nobody
   * would choose between — two cards at the same price with different
   * performance are not a step along a curve. Off-frontier parts are still
   * plotted, as hollow marks, because "this card is dominated" is useful to see.
   */
  const frontier = priced ? pts.filter((p) => p.onFrontier) : pts;
  const fx = frontier.map((p) => (priced ? p.price! : pts.indexOf(p)));

  const line = frontier
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${sx(fx[i]).toFixed(1)},${sy(p.geomeanFps).toFixed(1)}`)
    .join(' ');

  const bandPath = frontier.length
    ? frontier.map((p, i) => `${i === 0 ? 'M' : 'L'}${sx(fx[i]).toFixed(1)},${sy(p.geomeanFps * (1 + p.uncertainty)).toFixed(1)}`).join(' ') +
      ' ' +
      frontier
        .slice()
        .reverse()
        .map((p, j) => {
          const i = frontier.length - 1 - j;
          return `L${sx(fx[i]).toFixed(1)},${sy(p.geomeanFps * (1 - p.uncertainty)).toFixed(1)}`;
        })
        .join(' ') +
      ' Z'
    : '';

  const yTicks = 4;
  const kneePoint = sweep.kneeIndex != null ? sweep.points[sweep.kneeIndex] : null;
  const kneeIdx = kneePoint ? pts.findIndex((p) => p.candidateId === kneePoint.candidateId) : -1;

  return (
    <div className="chart-wrap">
      <svg className="curve" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Upgrade curve: geometric mean FPS across candidate parts">
        {Array.from({ length: yTicks + 1 }, (_, i) => {
          const v = (yMax / yTicks) * i;
          return (
            <g key={i}>
              <line className="grid-line" x1={M.l} y1={sy(v)} x2={W - M.r} y2={sy(v)} />
              <text x={M.l - 6} y={sy(v) + 3} textAnchor="end">{v.toFixed(0)}</text>
            </g>
          );
        })}

        <path className="band" d={bandPath} />
        <path className="series" d={line} />

        {pts.map((p, i) => {
          const dominated = priced && p.onFrontier === false;
          return (
            <circle
              key={p.candidateId}
              className={`pt${kneePoint && p.candidateId === kneePoint.candidateId ? ' knee' : ''}${dominated ? ' dominated' : ''}`}
              cx={sx(xs[i])}
              cy={sy(p.geomeanFps)}
              r={hover === i ? 6 : pts.length > 20 ? 3.2 : 4.5}
            />
          );
        })}

        {kneePoint && kneeIdx >= 0 && (() => {
          const kx = sx(xs[kneeIdx]);
          // Anchor away from whichever edge is closer so the label never clips.
          const flip = kx > M.l + iw * 0.6;
          return (
            <text
              x={flip ? kx - 9 : kx + 9}
              y={sy(kneePoint.geomeanFps) - 11}
              textAnchor={flip ? 'end' : 'start'}
              style={{ fill: 'var(--chart-knee)' }}
            >
              knee · {kneePoint.candidateName.replace(/^(NVIDIA|AMD|Intel) /, '')}
            </text>
          );
        })()}

        <line className="axis" x1={M.l} y1={M.t} x2={M.l} y2={M.t + ih} />
        <line className="axis" x1={M.l} y1={M.t + ih} x2={W - M.r} y2={M.t + ih} />
        <text x={M.l} y={H - 8}>{priced ? `${currency ?? ''}${xMin.toFixed(0)}` : 'slowest'}</text>
        <text x={W - M.r} y={H - 8} textAnchor="end">{priced ? `${currency ?? ''}${xMax.toFixed(0)}` : 'fastest'}</text>
        <text x={M.l + iw / 2} y={H - 8} textAnchor="middle">{priced ? 'price' : 'candidate, ordered by performance'}</text>

        {hover != null && (
          <line className="crosshair" x1={sx(xs[hover])} y1={M.t} x2={sx(xs[hover])} y2={M.t + ih} />
        )}

        {/* Hit targets larger than the marks. */}
        {pts.map((p, i) => (
          <rect
            key={`h${p.candidateId}`}
            className="hit"
            x={sx(xs[i]) - iw / pts.length / 2}
            y={M.t}
            width={iw / pts.length}
            height={ih}
            onMouseEnter={() => setHover(i)}
            onMouseLeave={() => setHover(null)}
          />
        ))}
      </svg>

      {priced && (
        <div className="legend" style={{ marginTop: 6, paddingLeft: 46 }}>
          <span className="item"><span className="dot" style={{ background: 'var(--chart-series)' }} /> on the price/performance frontier</span>
          <span className="item"><span className="dot" style={{ background: 'transparent', border: '1.5px solid var(--chart-series)' }} /> dominated — something cheaper is faster</span>
          <span className="item"><span className="dot" style={{ background: 'var(--chart-knee)' }} /> knee</span>
          <span className="item">shaded ribbon = model uncertainty</span>
        </div>
      )}

      {hover != null && (
        <div
          className="chart-tip"
          style={{
            left: `${(sx(xs[hover]) / W) * 100}%`,
            top: `${(sy(pts[hover].geomeanFps) / H) * 100}%`,
            transform: 'translate(-50%, -125%)',
          }}
        >
          <div style={{ color: 'var(--ink)' }}>{pts[hover].candidateName}</div>
          <div style={{ color: 'var(--muted)' }}>
            {pts[hover].geomeanFps.toFixed(1)} fps ±{(pts[hover].uncertainty * 100).toFixed(0)}%
          </div>
          <div style={{ color: 'var(--faint)' }}>
            {pts[hover].deltaVsBase >= 0 ? '+' : ''}
            {(pts[hover].deltaVsBase * 100).toFixed(1)}% vs base
            {pts[hover].price != null && ` · ${currency ?? ''}${pts[hover].price!.toFixed(0)}`}
          </div>
          {priced && pts[hover].onFrontier === false && (
            <div style={{ color: 'var(--extrapolated)' }}>dominated — something cheaper is faster</div>
          )}
        </div>
      )}
    </div>
  );
}
