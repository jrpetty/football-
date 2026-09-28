/**
 * OBS "Live run" overlay (/overlay/live?run=<id|latest>, or view=live): one
 * transparent lower-third band with the leader, the progress bar, the spend so
 * far and the commentary crawl. Live data comes from useLiveRun; the "demo"
 * target animates the overlay page's made-up data through the same commentary.
 */
import { useMemo } from 'react';
import type { CSSProperties } from 'react';
import { BrandMark } from '../Brand.tsx';
import { cx } from '../ui.tsx';
import { fmtCost } from '../../format.ts';
import type { OverlayData, RunEvent } from '../../types.ts';
import { CommentaryCrawl } from './CommentaryFeed.tsx';
import { commentate, emptyCommentary, type CommentaryContext, type CommentaryLine } from './commentary.ts';
import { watchLeader } from './watchModel.ts';
import { useLiveRun } from './useLiveRun.ts';
import '../../styles/live-watch.css';

interface BandData {
  live: boolean;
  runName: string;
  leader: { label: string; color: string; score: number } | null;
  completed: number;
  total: number;
  spend: number | null;
  lines: CommentaryLine[];
}

const CROWN = (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M3 8l4.5 4L12 5l4.5 7L21 8l-2 11H5Z" fill="currentColor" />
  </svg>
);

function Band({ d }: { d: BandData }) {
  const frac = d.total ? d.completed / d.total : 0;
  return (
    <div className="ov-panel ovl">
      <div className="ovl-top">
        <div className="ov-brand">
          <BrandMark className="ov-mark" />
          <span className="ov-word">Gauntlet</span>
          {d.live && <span className="ov-live">Live</span>}
        </div>
        <div className="ovl-leader" style={{ '--c': d.leader?.color ?? 'transparent' } as CSSProperties}>
          <span className="ovl-k">
            {CROWN}
            {d.live ? 'Leader' : 'Winner'}
          </span>
          {d.leader ? (
            <>
              <span className="ov-sw" />
              <span className="ovl-name">{d.leader.label}</span>
              <span className="ovl-score tnum">
                {Math.round(d.leader.score)}
                <small>avg</small>
              </span>
            </>
          ) : (
            <span className="ov-dim">no answers graded yet</span>
          )}
        </div>
        <div className="ovl-prog">
          <div className="ovl-prog-row">
            <span className="ovl-k">Progress</span>
            <span className="tnum">
              <b>{Math.floor(frac * 100)}%</b>
              <span className="ov-dim">
                {' '}
                · {d.completed} of {d.total} answers graded
              </span>
            </span>
          </div>
          <div className="ovl-bar">
            <i style={{ width: `${Math.max(1, frac * 100)}%` }} />
          </div>
        </div>
        {d.spend !== null && (
          <div className="ovl-spend">
            <span className="ovl-k">Spent</span>
            <b className="tnum">{fmtCost(d.spend)}</b>
          </div>
        )}
      </div>
      <CommentaryCrawl lines={d.lines} label={d.live ? 'Live' : 'Recap'} className="ovl-crawl" />
    </div>
  );
}

function LiveBand({ runId }: { runId: string }) {
  const live = useLiveRun(runId);
  const w = live.watch;
  if (!w || !live.manifest) return null;
  const lead = watchLeader(w);
  return (
    <Band
      d={{
        live: live.active,
        runName: live.manifest.name || live.manifest.id,
        leader: lead ? { label: lead.label, color: lead.color, score: (lead.sum / lead.scored) * 100 } : null,
        completed: w.completed,
        total: w.total,
        spend: w.costUsd,
        lines: live.lines,
      }}
    />
  );
}

/** Demo: the overlay page's made-up results, run through the real commentary function. */
function demoLines(d: OverlayData): CommentaryLine[] {
  const idOf = new Map(d.tests.map((t) => [t.name, t.id]));
  const items = [...d.ticker].reverse();
  const ctx: CommentaryContext = {
    contestants: d.standings.map((s) => ({ id: s.id, label: s.label, color: s.color })),
    tests: d.tests.map((t) => ({ id: t.id, name: t.name, caseIds: items.filter((x) => x.testName === t.name).map((x) => x.key) })),
    repeats: 1,
  };
  let st = emptyCommentary();
  const out: CommentaryLine[] = [];
  for (const x of items) {
    const e: RunEvent = { type: 'job.finished', runId: 'demo', key: x.key, contestantId: x.contestantId, testId: idOf.get(x.testName) ?? x.testName, caseId: x.key, repeat: 0, status: 'ok', score: x.score, summary: x.summary, metrics: undefined as never, at: new Date().toISOString() };
    const r = commentate(st, e, ctx);
    st = r.state;
    out.unshift(...r.lines.reverse());
  }
  return out;
}

export function LiveOverlay({ data, demo }: { data: OverlayData | null; demo: boolean }) {
  const lines = useMemo(() => (demo && data ? demoLines(data) : []), [demo, data]);
  if (!data) return null;
  if (!demo) return <LiveBand runId={data.runId} />;
  const top = data.standings[0];
  return (
    <div className={cx('ovl-demo')}>
      <Band
        d={{
          live: data.status === 'running',
          runName: data.runName,
          leader: top && top.score !== null ? { label: top.label, color: top.color, score: top.score } : null,
          completed: data.progress.completed,
          total: data.progress.total,
          spend: null,
          lines,
        }}
      />
    </div>
  );
}
