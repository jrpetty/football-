/**
 * Run detail while a run is live: the race (share of cases finished and mean
 * score so far per model, the leader in gold) with a link to the Live Arena.
 * Built from the results the page already polls, so it updates every refresh.
 */
import { Link, pathOf } from '../router.tsx';
import { fmtCost } from '../format.ts';
import type { RunDetail } from '../types.ts';
import { Icon } from './icons.tsx';
import { isBaseline } from './leaderboard/util.ts';
import { RaceTrack, type RaceLane } from './viz/RaceTrack.tsx';
import '../styles/live-visual.css';

export function LiveRaceCard({ d }: { d: RunDetail }) {
  const m = d.manifest;
  const per = (m.tests ?? []).reduce((s, t) => s + t.caseIds.length, 0) * (m.settings?.repeats ?? 1);
  const lanes: RaceLane[] = (m.contestants ?? []).map((c) => {
    const rs = d.results.filter((r) => r.contestantId === c.id);
    const scored = rs.filter((r) => typeof r.score === 'number');
    return {
      id: c.id,
      label: c.label,
      color: c.color,
      progress: per ? rs.length / per : 0,
      done: rs.length,
      total: per,
      score: scored.length ? scored.reduce((a, r) => a + (r.score as number), 0) / scored.length : null,
      rank: null,
      costUsd: rs.reduce((a, r) => a + (r.metrics?.costUsd ?? 0), 0),
      finished: per > 0 && rs.length >= per,
      baseline: isBaseline({ contestantId: c.id, vendor: c.vendor, label: c.label }),
    };
  });
  const ranked = lanes.filter((l) => l.score !== null && !l.baseline).sort((a, b) => b.score! - a.score!);
  ranked.forEach((l, i) => (l.rank = i + 1));
  if (lanes.length < 2) return null;
  return (
    <section className="rd-race" aria-label="Live race">
      <div className="rd-race-top">
        <span className="badge live">
          <span className="dot" />
          LIVE
        </span>
        <Link to={pathOf('runs', m.id, 'live')} className="btn sm live">
          <Icon.Broadcast /> Watch the live arena
        </Link>
      </div>
      <RaceTrack lanes={lanes} fmtCost={fmtCost} title="The race so far" />
    </section>
  );
}
