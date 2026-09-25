/**
 * Small colour legend ("what each colour means") and the drawn status icons
 * used across the visuals. Icons are inline SVG so they look the same on
 * every OS (no emoji).
 */
import type { CSSProperties } from 'react';
import { cx } from '../ui.tsx';
import type { Tone } from './vizModel.ts';
import './viz.css';

export function ToneIcon({ tone, className }: { tone: Tone; className?: string }) {
  const label = tone === 'good' ? 'right' : tone === 'bad' ? 'wrong' : tone === 'warn' ? 'warning' : 'info';
  return (
    <svg viewBox="0 0 20 20" className={cx('vz-ic', `vz-ic-${tone}`, className)} role="img" aria-label={label}>
      <circle cx="10" cy="10" r="9" />
      {tone === 'good' && <path d="M5.5 10.5l3 3 6-7" />}
      {tone === 'bad' && <path d="M6.5 6.5l7 7M13.5 6.5l-7 7" />}
      {tone === 'warn' && <path d="M10 5.5v6M10 14.2v.3" />}
      {tone === 'neutral' && <path d="M10 9v5M10 6.2v.3" />}
    </svg>
  );
}

export type SwatchKind = 'good' | 'bad' | 'warn' | 'missed' | 'accent' | 'muted' | 'decoy' | 'target' | 'guide';

export interface LegendItem {
  kind: SwatchKind;
  label: string;
}

function Swatch({ kind }: { kind: SwatchKind }) {
  const style: CSSProperties = {};
  if (kind === 'decoy')
    return (
      <svg viewBox="0 0 16 16" className="vz-sw" aria-hidden="true">
        <path d="M8 1.5l6.5 6.5L8 14.5 1.5 8z" className="vz-sw-decoy" />
      </svg>
    );
  if (kind === 'missed' || kind === 'guide')
    return (
      <svg viewBox="0 0 16 16" className="vz-sw" aria-hidden="true">
        <rect x="1.5" y="1.5" width="13" height="13" rx="2" className={`vz-sw-${kind}`} />
      </svg>
    );
  return <span className={cx('vz-sw', 'vz-sw-box', `vz-sw-${kind}`)} style={style} aria-hidden="true" />;
}

export function VizLegend({ items, className }: { items: LegendItem[]; className?: string }) {
  return (
    <ul className={cx('vz-legend', className)} aria-label="Legend">
      {items.map((it) => (
        <li key={it.label}>
          <Swatch kind={it.kind} />
          {it.label}
        </li>
      ))}
    </ul>
  );
}
