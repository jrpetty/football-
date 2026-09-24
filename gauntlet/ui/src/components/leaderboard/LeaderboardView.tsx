/** Full leaderboard composition, shared by the Leaderboard page and Run detail. */
import { useMemo } from 'react';
import type { Leaderboard } from '../../types.ts';
import { fmtCost, fmtDateTime, fmtIndex, fmtInt } from '../../format.ts';
import { Callout, HashTag } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { LeaderboardTable } from './LeaderboardTable.tsx';
import { CategoryPanel, MedalTable, Podium, ScatterPanel, SpeedPanel } from './Panels.tsx';
import { isBaseline } from './util.ts';

export function LeaderboardSummary({ lb }: { lb: Leaderboard }) {
  const stats = useMemo(() => {
    const contenders = lb.rows.filter((r) => !isBaseline(r));
    const leader = [...contenders].filter((r) => typeof r.index === 'number').sort((a, b) => (a.rank ?? 99) - (b.rank ?? 99))[0];
    const spend = lb.rows.reduce((s, r) => s + (r.totals?.costUsd ?? 0), 0);
    const judge = lb.rows.reduce((s, r) => s + (r.totals?.judgeCostUsd ?? 0), 0);
    const cases = lb.rows.reduce((s, r) => s + (r.totals?.cases ?? 0), 0);
    const cats = new Set((lb.tests ?? []).map((t) => t.category)).size;
    return { contenders: contenders.length, leader, spend, judge, cases, cats };
  }, [lb]);

  return (
    <div className="stats lb-stats">
      <div className="stat leader-stat" style={{ ['--c' as string]: stats.leader?.color ?? 'var(--accent)' }}>
        <span className="k">Leader</span>
        <span className="v">{stats.leader ? stats.leader.label : '—'}</span>
        <span className="s">{stats.leader ? `Gauntlet Index ${fmtIndex(stats.leader.index)}` : 'No scored models yet'}</span>
      </div>
      <div className="stat">
        <span className="k">Models ranked</span>
        <span className="v">{fmtInt(stats.contenders)}</span>
        <span className="s">{lb.rows.some(isBaseline) ? 'plus random baseline' : 'no baseline in scope'}</span>
      </div>
      <div className="stat">
        <span className="k">Tests</span>
        <span className="v">{fmtInt(lb.tests?.length ?? 0)}</span>
        <span className="s">
          {stats.cats} categories · {fmtInt(stats.cases)} scored cases
        </span>
      </div>
      <div className="stat">
        <span className="k">Total spend</span>
        <span className="v">{fmtCost(stats.spend)}</span>
        <span className="s">+ {fmtCost(stats.judge)} judges</span>
      </div>
      <div className="stat">
        <span className="k">Suite fingerprint</span>
        <span className="v" style={{ fontSize: '1.1rem', paddingTop: 4 }}>
          <HashTag value={lb.fingerprint} label="Fingerprint" n={12} />
        </span>
        <span className="s">Generated {fmtDateTime(lb.generatedAt)}</span>
      </div>
    </div>
  );
}

export function LeaderboardView({ lb, podium = true }: { lb: Leaderboard; podium?: boolean }) {
  return (
    <div className="lb-view">
      {podium && (
        <div className="only-broadcast">
          <Podium rows={lb.rows} big />
        </div>
      )}
      <div className="no-broadcast">
        <LeaderboardSummary lb={lb} />
      </div>
      {lb.staleExcluded > 0 && (
        <div className="no-broadcast">
          <Callout tone="info" icon={<Icon.Fingerprint />}>
            <strong>{fmtInt(lb.staleExcluded)} stale result{lb.staleExcluded === 1 ? '' : 's'} excluded.</strong> Their test definition or model configuration changed after they
            were recorded (hash mismatch), so they no longer count toward this leaderboard. Re-run those tests to include them.
          </Callout>
        </div>
      )}
      <section className="card lb-card">
        <LeaderboardTable lb={lb} />
      </section>
      <div className="grid split-7-5 lb-charts">
        <ScatterPanel lb={lb} />
        <MedalTable lb={lb} />
      </div>
      <div className="grid cols-2 lb-charts-2">
        <SpeedPanel lb={lb} />
        <CategoryPanel lb={lb} />
      </div>
    </div>
  );
}
