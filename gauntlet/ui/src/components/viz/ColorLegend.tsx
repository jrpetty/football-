/** A one-line key that says what each colour or mark on screen means. */
import type { ReactNode } from 'react';
import { cx } from '../ui.tsx';
import './viz.css';

export interface LegendEntry {
  /** CSS colour of the swatch, or a custom mark. */
  color?: string;
  mark?: ReactNode;
  /** 'dot' (default), 'bar' or 'ring'. */
  shape?: 'dot' | 'bar' | 'ring';
  label: ReactNode;
}

export function ColorLegend({ items, className, label = 'Key' }: { items: LegendEntry[]; className?: string; label?: string }) {
  return (
    <div className={cx('clg', className)} role="note" aria-label={label}>
      {items.map((it, i) => (
        <span key={i} className="clg-i">
          {it.mark ?? <i className={cx('clg-sw', it.shape ?? 'dot')} style={{ ['--c' as string]: it.color }} />}
          {it.label}
        </span>
      ))}
    </div>
  );
}
