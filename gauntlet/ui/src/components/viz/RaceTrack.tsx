/**
 * The live race: one track per model. The runner (a disc in the model's
 * colour with its live rank) sits at the share of the model's cases that are
 * finished; the chip on the right is its mean score so far. The leader's
 * track glows gold. Recorded numbers only: progress = finished / total cases,
 * score = mean of graded cases.
 */
import type { CSSProperties } from 'react';
import { cx } from '../ui.tsx';
import { TweenNumber } from './TweenNumber.tsx';
import './viz.css';

export interface RaceLane {
  id: string;
  label: string;
  color: string;
  /** Finished cases / total (0..1). */
  progress: number;
  done: number;
  total: number;
  /** Mean score so far (0..1), or null before the first graded case. */
  score: number | null;
  /** Live rank by mean score (null = unranked, e.g. a baseline or nothing graded yet). */
  rank: number | null;
  costUsd?: number;
  /** What it is working on now, e.g. "Survival Island". */
  now?: string;
  finished?: boolean;
  baseline?: boolean;
}

export function RaceTrack({ lanes, compact, title = 'The race', fmtCost, finished }: { lanes: RaceLane[]; compact?: boolean; title?: string; fmtCost?: (usd: number) => string; finished?: boolean }) {
  const leader = lanes.find((l) => l.rank === 1);
  return (
    <section className={cx('race', compact && 'compact', finished && 'finished')} aria-label={title}>
      <div className="race-head">
        <span className="race-t">{title}</span>
        <span className="race-key">
          <span>
            <i className="rk-dot" /> position = share of cases finished
          </span>
          <span>
            <i className="rk-chip" /> mean score so far
          </span>
          <span>
            <i className="rk-lead" /> leader
          </span>
        </span>
      </div>
      <ol className="race-lanes">
        {lanes.map((l) => {
          const lead = leader?.id === l.id;
          const pct = Math.max(0, Math.min(1, l.progress)) * 100;
          return (
            <li key={l.id} className={cx('race-lane', lead && 'lead', l.finished && 'done', l.baseline && 'base')} style={{ ['--c' as string]: l.color, ['--p' as string]: `${pct}%` } as CSSProperties}>
              <span className="race-name" title={l.label}>
                {lead && (
                  <svg viewBox="0 0 24 24" className="race-crown" aria-label="Leader">
                    <path d="M3 8l4.5 4L12 5l4.5 7L21 8l-2 11H5Z" fill="currentColor" />
                  </svg>
                )}
                <span className="ellipsis">{l.label}</span>
              </span>
              <span className="race-track" aria-hidden="true">
                <span className="race-fill" />
                <span className="race-runner">
                  <b>{l.rank ?? '–'}</b>
                </span>
                <span className="race-flag" />
              </span>
              <span className="race-meta tnum">
                {l.done}/{l.total}
                {fmtCost && l.costUsd !== undefined && <span className="race-cost"> · {fmtCost(l.costUsd)}</span>}
              </span>
              <span className={cx('race-score tnum', l.score === null && 'none')} title="Mean score so far (0–100)">
                {l.score === null ? '—' : <TweenNumber value={l.score * 100} decimals={1} />}
              </span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
