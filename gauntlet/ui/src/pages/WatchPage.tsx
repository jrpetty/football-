/**
 * Watch it think — /runs/:id/watch. Built for recording a run live: every
 * model's answer typing in, side by side, the verdict flashing as each case is
 * graded, and a plain-English commentary (feed or lower-third crawl).
 */
import { useCallback, useMemo } from 'react';
import type { CSSProperties } from 'react';
import { api } from '../api.ts';
import { useAsync, useLocalStorage, useNow } from '../hooks.ts';
import { Link, pathOf, setQuery, useRoute } from '../router.tsx';
import { useMeta, useViewerCaption } from '../context.tsx';
import { ErrorState, LoadingPage, Progress, RunStatusBadge, Seg, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtClock, fmtCost, fmtInt } from '../format.ts';
import { WatchGrid } from '../components/live/WatchGrid.tsx';
import { CommentaryCrawl, CommentaryFeed } from '../components/live/CommentaryFeed.tsx';
import { currentTest, watchLeader } from '../components/live/watchModel.ts';
import { useLiveRun } from '../components/live/useLiveRun.ts';
import type { TestSummary } from '../types.ts';
import '../styles/live-watch.css';

type Commentary = 'feed' | 'crawl' | 'off';

export default function WatchPage({ runId }: { runId: string }) {
  const { query } = useRoute();
  const { cat } = useMeta();
  const live = useLiveRun(runId);
  const [pref, setPref] = useLocalStorage<Commentary>('gauntlet.watch.commentary', 'feed');
  const q = query.get('commentary');
  const mode: Commentary = q === 'feed' || q === 'crawl' || q === 'off' ? q : pref;
  const now = useNow(live.active ? 250 : null);
  const testInfo = useAsync(() => api.tests().catch(() => [] as TestSummary[]), []);
  const info = useMemo(() => new Map((testInfo.data ?? []).map((t) => [t.id, t])), [testInfo.data]);
  const m = live.manifest;
  const names = useMemo(() => new Map((m?.tests ?? []).map((t) => [t.id, t.name])), [m]);
  const testName = useCallback((id: string) => names.get(id) ?? id, [names]);
  const w = live.watch;
  const n = w?.tiles.size ?? 0;

  useViewerCaption(
    !w ? null : live.active ? `${n} AI models take the same test side by side. Each box shows what one model is writing right now, then flashes green (good), amber (partly right) or red (wrong) the moment it’s graded.` : 'This run has finished. Each box shows the last answer that model gave.',
    live.active ? 'Scores are out of 100 · token and cost figures marked ≈ are estimates until the answer is graded' : undefined,
  );

  if (live.error && !w) return <ErrorState error={live.error} onRetry={live.reload} title="Couldn’t load this run" />;
  if (!w || !m || !live.ctx) return <LoadingPage />;

  const frac = w.total ? w.completed / w.total : 0;
  const leader = watchLeader(w);
  const cur = currentTest(w);
  const curSnap = cur ? m.tests.find((t) => t.id === cur) : undefined;
  const curInfo = cur ? info.get(cur) : undefined;
  const curCat = curSnap ? cat(curSnap.category) : null;
  const started = m.startedAt ? Date.parse(m.startedAt) : Date.parse(m.createdAt);
  const elapsed = live.active ? now - started : m.finishedAt ? Date.parse(m.finishedAt) - started : 0;

  return (
    <div className={cx('page watch', `cm-${mode}`)}>
      <header className="watch-head">
        <div className="wh-title">
          <div className="row" style={{ gap: 10 }}>
            {live.active ? (
              <span className="badge live lg">
                <span className="dot" />
                LIVE
              </span>
            ) : (
              <RunStatusBadge status={m.status} lg />
            )}
            {!live.connected && live.active && (
              <span className="badge warn">
                <Icon.Wifi /> reconnecting…
              </span>
            )}
            <span className="wh-run ellipsis">{m.name || m.id}</span>
          </div>
          {curSnap ? (
            <div className="wh-now" key={curSnap.id} style={{ '--cc': curCat?.color } as CSSProperties}>
              <span className="wh-now-k">
                Now testing · test {m.tests.indexOf(curSnap) + 1} of {m.tests.length}
              </span>
              <span className="wh-now-name">{curSnap.name}</span>
              {curInfo?.hook && <span className="wh-now-hook">{curInfo.hook}</span>}
            </div>
          ) : (
            <div className="wh-now">
              <span className="wh-now-k">{live.active ? 'Starting…' : 'Run finished'}</span>
              <span className="wh-now-name">{m.tests.length} tests · {m.contestants.length} models</span>
            </div>
          )}
        </div>
        <div className="wh-stats">
          <div className="wh-stat wh-prog">
            <span className="k">Progress</span>
            <span className="v tnum">{Math.floor(frac * 100)}%</span>
            <Progress value={frac} striped={live.active} label="Overall progress" />
            <span className="s tnum">
              {fmtInt(w.completed)} of {fmtInt(w.total)} answers graded
            </span>
          </div>
          <div className="wh-stat wh-lead" style={{ '--c': leader?.color ?? 'var(--text-3)' } as CSSProperties}>
            <span className="k">Leader</span>
            {leader ? (
              <>
                <span className="v">
                  <i className="wh-sw" />
                  <span className="ellipsis">{leader.label}</span>
                </span>
                <span className="s tnum">averaging {Math.round((leader.sum / leader.scored) * 100)} out of 100</span>
              </>
            ) : (
              <span className="v muted">—</span>
            )}
          </div>
          <div className="wh-stat">
            <span className="k">Spent so far</span>
            <span className="v tnum">{fmtCost(w.costUsd)}</span>
            <span className="s tnum">{fmtClock(elapsed)} elapsed</span>
          </div>
        </div>
        <div className="wh-tools no-broadcast">
          <Seg<Commentary>
            label="Commentary"
            value={mode}
            onChange={(v) => {
              setPref(v);
              if (q) setQuery({ commentary: v });
            }}
            options={[
              { value: 'feed', label: 'Feed', title: 'Commentary in a column on the right' },
              { value: 'crawl', label: 'Crawl', title: 'Commentary scrolling along the bottom, TV style' },
              { value: 'off', label: 'Off' },
            ]}
          />
          <div className="row" style={{ gap: 6 }}>
            <Link to={pathOf('runs', runId, 'live')} className="btn sm">
              <Icon.Broadcast /> Live arena
            </Link>
            <Link to={pathOf('runs', runId)} className="btn sm">
              Run page
            </Link>
          </div>
        </div>
      </header>

      <div className="watch-main">
        <WatchGrid watch={w} ctx={live.ctx} now={now} testName={testName} currentTest={cur} />
        {mode === 'feed' && <CommentaryFeed lines={live.lines} now={now} max={40} />}
      </div>
      {mode === 'crawl' && <CommentaryCrawl lines={live.lines} className="watch-crawl" />}
      {!live.active && (
        <div className="callout no-broadcast">
          <Icon.Info />
          <div>
            This run is not running right now. <Link to={pathOf('runs', runId)}>Open the results</Link> or the <Link to={pathOf('present', runId)}>Presenter</Link>.
          </div>
        </div>
      )}
    </div>
  );
}
