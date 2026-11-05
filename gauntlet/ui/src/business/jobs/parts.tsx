/**
 * Shared pieces of "Which model for this job?": the suitability badge (colour + icon + words, never colour alone),
 * the accuracy bar with its 95% range, money and time in plain words, and the caveat line.
 */
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../../components/ui.tsx';
import { money } from '../../money.ts';
import { SUITABILITY_LABEL, tasksPerJob, type BusinessJob, type BusinessJobsView, type JobModelInfo, type Suitability } from '../../../../src/core/business-jobs.ts';

export const pct = (x: number | null | undefined) => (typeof x === 'number' ? `${Math.round(x * 100)}%` : '–');

const ICON: Record<Suitability, ReactNode> = {
  ready: <path d="M5 12.5l4.2 4.2L19 7" />,
  check: (
    <>
      <circle cx="12" cy="12" r="3.2" />
      <path d="M2.5 12s3.6-6.5 9.5-6.5S21.5 12 21.5 12s-3.6 6.5-9.5 6.5S2.5 12 2.5 12z" />
    </>
  ),
  'not-ready': <path d="M6.5 6.5l11 11M17.5 6.5l-11 11" />,
  'no-data': <path d="M9.2 9.3a2.9 2.9 0 1 1 4.3 2.5c-.9.5-1.5 1.1-1.5 2.2M12 17.6v.1" />,
};

export function SuitabilityIcon({ verdict }: { verdict: Suitability }) {
  return (
    <svg className="bj-vicon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {ICON[verdict]}
    </svg>
  );
}

/** The verdict as a badge: icon and words on the verdict colour. `size` lg for the ranked list. */
export function SuitabilityBadge({ verdict, size = 'md', short }: { verdict: Suitability; size?: 'sm' | 'md' | 'lg'; short?: boolean }) {
  const words = short ? { ready: 'Ready', check: 'Human check', 'not-ready': 'Not ready', 'no-data': 'No data' }[verdict] : SUITABILITY_LABEL[verdict];
  return (
    <span className={cx('bj-badge', `v-${verdict}`, `s-${size}`)}>
      <SuitabilityIcon verdict={verdict} />
      <span>{words}</span>
    </span>
  );
}

/** Accuracy as a bar, with the 95% range drawn as a whisker. */
export function ScoreBar({ score, ci, verdict }: { score: number | null; ci: [number, number] | null; verdict: Suitability }) {
  if (score === null) return <div className="bj-bar empty">Not tested yet</div>;
  return (
    <div className={cx('bj-bar', `v-${verdict}`)} role="img" aria-label={`${pct(score)} right${ci ? `, 95% range ${pct(ci[0])} to ${pct(ci[1])}` : ''}`}>
      <div className="bj-bar-track">
        <i className="fill" style={{ width: `${score * 100}%` }} />
        {ci && <i className="ci" style={{ left: `${ci[0] * 100}%`, width: `${Math.max(0.4, (ci[1] - ci[0]) * 100)}%` }} />}
      </div>
      <span className="bj-bar-v tnum">
        <b>{pct(score)}</b> right{ci && <small> (likely {pct(ci[0])}–{pct(ci[1])})</small>}
      </span>
    </div>
  );
}

/** "£0.0042 a task" / "£4.20 per 1,000 tasks". */
export function costWords(usd: number | null): { one: string; thousand: string } | null {
  if (usd === null) return null;
  return { one: money(usd), thousand: money(usd * 1000) };
}

export function secondsWords(s: number | null): string {
  if (s === null) return '–';
  if (s < 60) return `${s < 10 ? s.toFixed(1).replace(/\.0$/, '') : Math.round(s)} s`;
  const m = s / 60;
  return m < 60 ? `${m < 10 ? m.toFixed(1).replace(/\.0$/, '') : Math.round(m)} min` : `${(m / 60).toFixed(1)} h`;
}

/** Model colour as a CSS variable. */
export const modelStyle = (m: JobModelInfo | undefined): CSSProperties => ({ ['--mc' as string]: m?.color ?? 'var(--accent)' });

export function Caveat({ view, job }: { view: BusinessJobsView; job?: BusinessJob }) {
  const counts = view.jobs.map((j) => tasksPerJob(j, view.tests));
  const n = job ? tasksPerJob(job, view.tests) : null;
  const range = n !== null ? String(n) : `${Math.min(...counts)}–${Math.max(...counts)}`;
  return (
    <p className="bj-caveat">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" aria-hidden="true">
        <path d="M12 3l9.5 17h-19L12 3zM12 10v4.5M12 17.5v.1" />
      </svg>
      <span>
        Based on {range} tasks {job ? 'for this job' : 'per job'} in Gauntlet’s tests; check on your own documents before relying on it.
      </span>
    </p>
  );
}

export function JobDemoNote({ view }: { view: BusinessJobsView | null | undefined }) {
  if (!view?.demo) return null;
  return (
    <div className="biz-demo" role="note">
      <b>Demo data — not real results</b> The models, scores, costs and answers here are invented to show the page. The jobs, tests and answer keys are the real ones.
    </div>
  );
}
