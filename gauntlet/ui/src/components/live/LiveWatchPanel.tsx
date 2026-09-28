/**
 * Run page, while a run is live: "Watch it think" tiles plus the commentary
 * feed, with a link to the full-screen Watch view for recording.
 */
import { useCallback, useMemo } from 'react';
import { useNow } from '../../hooks.ts';
import { Link, pathOf } from '../../router.tsx';
import { Icon } from '../icons.tsx';
import { WatchGrid } from './WatchGrid.tsx';
import { CommentaryFeed } from './CommentaryFeed.tsx';
import { useLiveRun } from './useLiveRun.ts';
import { currentTest } from './watchModel.ts';
import '../../styles/live-watch.css';

export function LiveWatchPanel({ runId }: { runId: string }) {
  const live = useLiveRun(runId);
  const now = useNow(live.active ? 250 : null);
  const names = useMemo(() => new Map((live.manifest?.tests ?? []).map((t) => [t.id, t.name])), [live.manifest]);
  const testName = useCallback((id: string) => names.get(id) ?? id, [names]);
  if (!live.watch || !live.ctx) return null;
  const cur = currentTest(live.watch);
  return (
    <section className="card lwp" aria-label="Watch it think">
      <div className="card-head">
        <div className="t">
          <h2>
            Watch it think
            {cur && <span className="lwp-now"> · now testing {testName(cur)}</span>}
          </h2>
          <div className="desc">Each model’s answer as it types. A tick or cross flashes when the answer is marked.</div>
        </div>
        <Link to={pathOf('runs', runId, 'watch')} className="btn sm live">
          <Icon.Maximize /> Full screen for recording
        </Link>
      </div>
      <div className="lwp-body">
        <WatchGrid watch={live.watch} ctx={live.ctx} now={now} testName={testName} currentTest={cur} compact />
        <CommentaryFeed lines={live.lines} now={now} max={14} />
      </div>
    </section>
  );
}
