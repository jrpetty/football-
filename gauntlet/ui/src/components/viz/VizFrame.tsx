/**
 * Shared frame for every "answer vs truth" case visual: a one-glance headline
 * (colour-coded, with the key number big), the body, and a colour legend.
 */
import type { ReactNode } from 'react';
import { cx } from '../ui.tsx';
import '../../styles/viz.css';

export type VizTone = 'good' | 'bad' | 'half' | 'neutral';
export type VizMode = 'inspector' | 'slide';

export function ToneMark({ tone }: { tone: VizTone }) {
  const label = tone === 'good' ? 'Right' : tone === 'bad' ? 'Wrong' : tone === 'half' ? 'Partly right' : 'Info';
  return (
    <span className={cx('vz-mark', `t-${tone}`)} role="img" aria-label={label}>
      <svg viewBox="0 0 24 24" aria-hidden="true">
        {tone === 'good' && <path d="M5 12.5l4.2 4.2L19 7" />}
        {tone === 'bad' && <path d="M6.5 6.5l11 11M17.5 6.5l-11 11" />}
        {tone === 'half' && <path d="M6 12h12" />}
        {tone === 'neutral' && <path d="M12 7v6M12 17v.5" />}
      </svg>
    </span>
  );
}

export interface LegendItem {
  /** good, bad, half, neutral, trap, count, key, or a role class such as r-knight. */
  tone: string;
  label: string;
}

export function VizLegend({ items }: { items: LegendItem[] }) {
  return (
    <ul className="vz-legend" aria-label="Colour key">
      {items.map((it) => (
        <li key={it.label}>
          <i className={cx('vz-sw', `t-${it.tone}`)} aria-hidden="true" />
          {it.label}
        </li>
      ))}
    </ul>
  );
}

export function VizFrame({
  mode,
  tone,
  eyebrow,
  headline,
  big,
  bigSub,
  legend,
  children,
  className,
}: {
  mode: VizMode;
  tone: VizTone;
  eyebrow?: ReactNode;
  headline: ReactNode;
  /** The key number / answer, shown huge next to the headline. */
  big?: ReactNode;
  bigSub?: ReactNode;
  legend?: LegendItem[];
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cx('vz', `vz-${mode}`, className)} aria-label="Answer vs truth">
      <header className={cx('vz-head', `t-${tone}`)}>
        <ToneMark tone={tone} />
        <div className="vz-head-t">
          {eyebrow && <div className="vz-eyebrow">{eyebrow}</div>}
          <h3 className="vz-headline">{headline}</h3>
        </div>
        {big !== undefined && (
          <div className="vz-big">
            <b className="tnum">{big}</b>
            {bigSub && <small>{bigSub}</small>}
          </div>
        )}
      </header>
      <div className="vz-body">{children}</div>
      {legend && legend.length > 0 && <VizLegend items={legend} />}
    </section>
  );
}

/** Small "not recorded" placeholder used inside visuals. */
export function NotRecorded({ what }: { what: string }) {
  return <div className="vz-missing">{what} was not recorded.</div>;
}
