/**
 * Viewer-clarity building blocks.
 *
 * The central mechanism: anything marked `data-dev` or `.dev-only` is hidden in Broadcast mode
 * (html[data-broadcast='on']), and `.plain-only` appears only there. `<Jargon>` pairs the two so the owner
 * keeps full detail while a viewer sees plain English. Styles live in styles/clarity.css.
 */
import type { ReactNode } from 'react';
import { SCORE_BANDS } from './plain.ts';
import { cx } from '../ui.tsx';

/** Shows `dev` normally and `plain` in Broadcast mode. */
export function Jargon({ dev, plain, title }: { dev: ReactNode; plain: ReactNode; title?: string }) {
  return (
    <>
      <span className="dev-only" title={title}>
        {dev}
      </span>
      <span className="plain-only">{plain}</span>
    </>
  );
}

/** Small "?" that explains a number on hover (and to screen readers). */
export function ScoreHint({ text, label = 'What does this number mean?' }: { text: string; label?: string }) {
  return (
    <span className="score-hint" tabIndex={0} role="note" aria-label={`${label} ${text}`}>
      <span aria-hidden="true">?</span>
      <span className="score-hint-pop" role="tooltip">
        {text}
      </span>
    </span>
  );
}

/** Green / amber / red key used by the results matrix and score pills. */
export function BandLegend({ compact }: { compact?: boolean }) {
  return (
    <span className={cx('band-legend', compact && 'compact')} aria-label="Colour key">
      {SCORE_BANDS.map((b) => (
        <span key={b.id} className="band-key">
          <i className={`band-sw band-${b.id}`} aria-hidden="true" />
          <b>{b.label}</b>
          <span className="muted tnum">{b.range}</span>
        </span>
      ))}
    </span>
  );
}

/**
 * One-line "what does 100 mean" strip for a score view. `baseline` is the recorded random-guessing
 * score on the same 0–100 scale (omit when there is no baseline in the data — never invent one).
 */
export function ScoreScale({ what, baseline, bands, className }: { what: ReactNode; baseline?: number | null; bands?: boolean; className?: string }) {
  return (
    <div className={cx('score-scale', className)} role="note">
      <span className="ss-k">Score out of 100</span>
      <span className="ss-t">
        <b>100</b> = {what}
        {typeof baseline === 'number' && Number.isFinite(baseline) && (
          <>
            {' '}
            · random guessing scored <b className="tnum">{baseline.toFixed(1)}</b>
          </>
        )}
      </span>
      {bands && <BandLegend compact />}
    </div>
  );
}
