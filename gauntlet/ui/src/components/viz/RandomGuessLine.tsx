/**
 * A dashed "random guessing: X%" reference line across a 0–100 score bar or
 * chart. Drop it inside any `position: relative` track; it spans the track's
 * full height (plus a little overhang). Only drawn from a recorded Random
 * Baseline score; callers pass null when the run has no baseline, and the
 * line disappears.
 */
import type { CSSProperties } from 'react';
import { cx } from '../ui.tsx';
import './random-guess.css';

export function randomGuessText(pct: number): string {
  return `random guessing: ${Math.round(pct)}%`;
}

export function RandomGuessLine({ pct, label = true, className, placement = 'above' }: {
  /** Random guessing's score on the 0–100 scale, or null to draw nothing. */
  pct: number | null | undefined;
  /** Show the text label (usually only on the first bar of a stack). */
  label?: boolean;
  className?: string;
  /** Label above (default) or below the track. */
  placement?: 'above' | 'below';
}) {
  if (typeof pct !== 'number' || !Number.isFinite(pct)) return null;
  const x = Math.max(0, Math.min(100, pct));
  // Keep the label inside the track near either end.
  const anchor = x < 12 ? 'start' : x > 88 ? 'end' : 'mid';
  return (
    <i className={cx('rg-line', `rg-${anchor}`, `rg-${placement}`, className)} style={{ left: `${x}%` } as CSSProperties} aria-hidden={!label} role={label ? 'note' : undefined}>
      {label && <span>{randomGuessText(pct)}</span>}
    </i>
  );
}
