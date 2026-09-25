/**
 * Who is ahead, step by step: a diverging area chart around a zero line.
 * Above the line = side A ahead (its colour), below = side B ahead. Used for
 * chess material balance and poker chip balance. A vertical marker follows
 * the replay position. Pure SVG, scales to its container.
 */
import { useId } from 'react';
import { cx } from '../ui.tsx';
import './viz.css';

export interface BalanceSide {
  label: string;
  color: string;
}

export function BalanceTimeline({
  values,
  at,
  sides,
  unit,
  title,
  className,
  height = 96,
  onSeek,
}: {
  /** One value per step (index 0 = start). Positive = side A ahead. */
  values: number[];
  /** Current step (marker), or null. */
  at?: number | null;
  sides: [BalanceSide, BalanceSide];
  /** e.g. "points of material", "chips". */
  unit: string;
  title: string;
  className?: string;
  height?: number;
  /** Click a step to jump there. */
  onSeek?: (step: number) => void;
}) {
  const id = useId().replace(/:/g, '');
  const W = 600;
  const H = 100;
  const n = values.length;
  if (n < 2) {
    return (
      <div className={cx('bt', className)}>
        <div className="bt-head">
          <span className="bt-t">{title}</span>
        </div>
        <div className="bt-empty">Not enough steps yet.</div>
      </div>
    );
  }
  const max = Math.max(1, ...values.map((v) => Math.abs(v)));
  const x = (i: number) => (i / (n - 1)) * W;
  const y = (v: number) => H / 2 - (v / max) * (H / 2 - 6);
  const line = values.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(' ');
  const area = `${line} L${W} ${H / 2} L0 ${H / 2} Z`;
  const cur = at === null || at === undefined ? null : Math.max(0, Math.min(n - 1, at));
  const now = cur === null ? values[n - 1]! : values[cur]!;
  const lead = now > 0 ? sides[0] : now < 0 ? sides[1] : null;
  return (
    <div className={cx('bt', className)}>
      <div className="bt-head">
        <span className="bt-t">{title}</span>
        <span className="bt-now" style={{ color: lead?.color }}>
          {lead ? `${lead.label} +${Math.abs(now)} ${unit}` : 'Level'}
        </span>
      </div>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="none"
        className={cx('bt-svg', onSeek && 'seek')}
        style={{ height }}
        role="img"
        aria-label={`${title}: ${lead ? `${lead.label} ahead by ${Math.abs(now)} ${unit}` : 'level'}`}
        onClick={
          onSeek
            ? (e) => {
                const r = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
                onSeek(Math.round(((e.clientX - r.left) / r.width) * (n - 1)));
              }
            : undefined
        }
      >
        <defs>
          <clipPath id={`bt-a-${id}`}>
            <rect x="0" y="0" width={W} height={H / 2} />
          </clipPath>
          <clipPath id={`bt-b-${id}`}>
            <rect x="0" y={H / 2} width={W} height={H / 2} />
          </clipPath>
        </defs>
        <path d={area} fill={sides[0].color} opacity={0.35} clipPath={`url(#bt-a-${id})`} />
        <path d={area} fill={sides[1].color} opacity={0.35} clipPath={`url(#bt-b-${id})`} />
        <line x1="0" x2={W} y1={H / 2} y2={H / 2} className="bt-zero" vectorEffect="non-scaling-stroke" />
        <path d={line} fill="none" className="bt-line" vectorEffect="non-scaling-stroke" />
        {cur !== null && <line x1={x(cur)} x2={x(cur)} y1="0" y2={H} className="bt-marker" vectorEffect="non-scaling-stroke" />}
      </svg>
      <div className="bt-legend">
        <span>
          <i style={{ background: sides[0].color }} /> above the line: {sides[0].label} ahead
        </span>
        <span>
          <i style={{ background: sides[1].color }} /> below: {sides[1].label} ahead
        </span>
      </div>
    </div>
  );
}
