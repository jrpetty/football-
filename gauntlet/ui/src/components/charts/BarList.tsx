/**
 * Horizontal bar list (HTML) — one bar per model in its identity colour,
 * value at the tip, baseline at the left. Thin bars, 4px rounded data end.
 */
import { useState } from 'react';
import { FloatingTip } from '../ui.tsx';

export interface BarDatum {
  id: string;
  label: string;
  color: string;
  value: number | null;
  display: string;
  detail?: string;
}

export function BarList({
  data,
  sort = 'desc',
  max,
  ariaLabel,
  emptyText = 'No data',
}: {
  data: BarDatum[];
  sort?: 'asc' | 'desc' | 'none';
  max?: number;
  ariaLabel: string;
  emptyText?: string;
}) {
  const [hover, setHover] = useState<{ d: BarDatum; x: number; y: number } | null>(null);
  const valid = data.filter((d) => typeof d.value === 'number' && Number.isFinite(d.value));
  const missing = data.filter((d) => !(typeof d.value === 'number' && Number.isFinite(d.value)));
  const sorted = sort === 'none' ? valid : [...valid].sort((a, b) => (sort === 'asc' ? (a.value as number) - (b.value as number) : (b.value as number) - (a.value as number)));
  const hi = max ?? Math.max(1e-9, ...sorted.map((d) => d.value as number));

  if (!sorted.length) return <div className="chart-empty">{emptyText}</div>;

  return (
    <div className="barlist" role="list" aria-label={ariaLabel}>
      {sorted.map((d) => {
        const pct = Math.max(0.5, Math.min(100, ((d.value as number) / hi) * 100));
        return (
          <div
            key={d.id}
            className="bl-row"
            role="listitem"
            tabIndex={0}
            aria-label={`${d.label}: ${d.display}`}
            onPointerMove={(e) => setHover({ d, x: e.clientX, y: e.clientY })}
            onPointerLeave={() => setHover(null)}
            onFocus={(e) => {
              const r = e.currentTarget.getBoundingClientRect();
              setHover({ d, x: r.left + r.width * 0.6, y: r.top + r.height / 2 });
            }}
            onBlur={() => setHover(null)}
          >
            <div className="bl-label">
              <span className="sw" style={{ background: d.color }} aria-hidden="true" />
              <span className="ellipsis">{d.label}</span>
            </div>
            <div className="bl-track">
              <div className="bl-bar" style={{ width: `${pct}%`, background: d.color }} />
              <span className="bl-value tnum">{d.display}</span>
            </div>
          </div>
        );
      })}
      {missing.length > 0 && <div className="chart-note">No data: {missing.map((d) => d.label).join(', ')}</div>}
      {hover && (
        <FloatingTip x={hover.x} y={hover.y}>
          <div className="tip-title">
            <span className="key" style={{ background: hover.d.color }} />
            {hover.d.label}
          </div>
          <div className="tip-row">
            <b style={{ fontSize: '1.05em' }}>{hover.d.display}</b>
          </div>
          {hover.d.detail && <div>{hover.d.detail}</div>}
        </FloatingTip>
      )}
    </div>
  );
}
