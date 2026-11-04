/** Full leaderboard composition, shared by the Leaderboard page and Run detail. */
import { useMemo, useState, type ReactNode } from 'react';
import type { Leaderboard } from '../../types.ts';
import { fmtCost, fmtDateTime, fmtIndex, fmtInt } from '../../format.ts';
import { Callout, HashTag } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { LeaderboardTable } from './LeaderboardTable.tsx';
import { CategoryPanel, MedalTable, Podium, ScatterPanel, SpeedPanel } from './Panels.tsx';
import { isBaseline } from './util.ts';
import { ImageGlyph } from '../VisionImage.tsx';
import { BaselineExplainer, ScoreWhyPanel, type ScoreFocus } from './ScoreWhy.tsx';
import { ScoreNameContext } from './scoreName.tsx';
import { scoreNameFor } from '../../../../src/core/areas.ts';
import { LadderHeightsPanel } from './LadderHeights.tsx';
import { ladderSpecOf } from '../../ladders.ts';

export function LeaderboardSummary({ lb }: { lb: Leaderboard }) {
  const scoreName = scoreNameFor(lb.tests);
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
        <span className="s">{stats.leader ? `${scoreName} ${fmtIndex(stats.leader.index)}` : 'No scored models yet'}</span>
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
      <div className="stat" data-dev>
        <span className="k">Suite fingerprint</span>
        <span className="v" style={{ fontSize: '1.1rem', paddingTop: 4 }}>
          <HashTag value={lb.fingerprint} label="Fingerprint" n={12} />
        </span>
        <span className="s">Generated {fmtDateTime(lb.generatedAt)}</span>
      </div>
    </div>
  );
}

interface LbNote {
  key: string;
  /** Short words for the collapsed line ("17 stale results excluded"). */
  short: string;
  icon: ReactNode;
  body: ReactNode;
}

/**
 * The notes under the summary, as one hierarchy: a fair-play problem stays visible (it changes scores), and
 * every other note (stale results, skipped questions, what the Random Baseline is) folds into one quiet line.
 */
function LeaderboardNotes({ lb }: { lb: Leaderboard }) {
  const baseline = lb.rows.find(isBaseline);
  const notes: LbNote[] = [];
  if (lb.staleExcluded > 0) {
    notes.push({
      key: 'stale',
      short: `${fmtInt(lb.staleExcluded)} stale result${lb.staleExcluded === 1 ? '' : 's'} excluded`,
      icon: <Icon.Fingerprint />,
      body: (
        <>
          <strong>{fmtInt(lb.staleExcluded)} stale result{lb.staleExcluded === 1 ? '' : 's'} excluded.</strong> Their test definition or model configuration changed after they were recorded (hash
          mismatch), so they no longer count toward this leaderboard. Re-run those tests to include them.
        </>
      ),
    });
  }
  const noPaint = lb.rows.filter((r) => (r.totals.skippedImageOutput ?? 0) > 0).map((r) => r.label);
  if (noPaint.length) {
    notes.push({
      key: 'paint',
      short: `painting skipped for ${noPaint.length} model${noPaint.length === 1 ? '' : 's'}`,
      icon: <ImageGlyph />,
      body: (
        <>
          <strong>Painting tests skipped for {noPaint.join(', ')}.</strong> A model that can’t make pictures is not asked to paint (and a picture-only model is not asked text questions). Those
          cases are not scored as 0 and are left out of the averages.
        </>
      ),
    });
  }
  const blind = lb.rows.filter((r) => (r.totals.skipped ?? 0) > (r.totals.skippedImageOutput ?? 0)).map((r) => r.label);
  if (blind.length) {
    const one = blind.length === 1;
    notes.push({
      key: 'blind',
      short: `picture questions skipped for ${blind.length} model${one ? '' : 's'}`,
      icon: <ImageGlyph />,
      body: (
        <>
          <strong>Picture questions skipped for {blind.join(', ')}.</strong> {one ? 'This model can’t' : 'These models can’t'} see images, so the vision cases were not sent to {one ? 'it' : 'them'}.
          They are not scored as 0 and are left out of {one ? 'its' : 'their'} averages.
        </>
      ),
    });
  }
  const fair = lb.rows.filter((r) => (r.totals.fairPlayViolations ?? 0) > 0 || (r.totals.fairPlayFlagged ?? 0) > 0);
  if (!notes.length && !fair.length && !baseline) return null;
  const shorts = [...notes.map((n) => n.short), ...(baseline ? [`random guessing scored ${fmtIndex(baseline.index)}`] : [])];
  return (
    <div className="lb-notes no-broadcast">
      {fair.length > 0 && (
        <Callout tone="bad" icon={<Icon.Alert />}>
          <strong>Fair play:</strong>{' '}
          {fair
            .map((r) => {
              const v = r.totals.fairPlayViolations ?? 0;
              const f = r.totals.fairPlayFlagged ?? 0;
              return `${r.label}: ${[v ? `${v} violation${v === 1 ? '' : 's'} (scored 0)` : '', f ? `${f} flagged for possible outside help` : ''].filter(Boolean).join(', ')}`;
            })
            .join(' · ')}
          . Every model is told the fair-play rules at the start of every prompt; open a result to see the offending text.
        </Callout>
      )}
      {shorts.length > 0 && (
        <details className="lb-notes-more">
          <summary>
            <Icon.Info />
            <span className="lbn-k">About these results</span>
            <span className="lbn-list">{shorts.join(' · ')}</span>
            <span className="lbn-more">Details</span>
          </summary>
          <div className="lbn-body">
            {notes.map((n) => (
              <div key={n.key} className="lbn-item">
                <span className="lbn-icon" aria-hidden="true">
                  {n.icon}
                </span>
                <p>{n.body}</p>
              </div>
            ))}
            {baseline && <BaselineExplainer score={baseline.index} />}
          </div>
        </details>
      )}
    </div>
  );
}

export function LeaderboardView({ lb, podium = true, planned = [], demo }: { lb: Leaderboard; podium?: boolean; /** Ladder tests of this suite still being built ("coming soon"). */ planned?: string[]; demo?: boolean }) {
  const hasLadders = planned.length > 0 || (lb.tests ?? []).some((t) => ladderSpecOf(t.id));
  const [focus, setFocus] = useState<ScoreFocus | null>(null);
  // "Gauntlet Index" only for the benchmark; Business / Everyday Life leaderboards have their own score name.
  const scoreName = useMemo(() => scoreNameFor(lb.tests), [lb.tests]);
  return (
    <ScoreNameContext.Provider value={scoreName}>
      <div className="lb-view">
        {podium && (
          <div className="only-broadcast">
            <Podium rows={lb.rows} big />
          </div>
        )}
        <div className="no-broadcast">
          <LeaderboardSummary lb={lb} />
        </div>
        <LeaderboardNotes lb={lb} />
        <section className="card lb-card">
          <LeaderboardTable lb={lb} onExplain={(contestantId, categoryId) => setFocus((f) => ({ contestantId, categoryId, nonce: (f?.nonce ?? 0) + 1 }))} />
        </section>
        {hasLadders && <LadderHeightsPanel lb={lb} planned={planned} demo={demo} />}
        <ScoreWhyPanel lb={lb} focus={focus} />
        <div className="grid split-7-5 lb-charts">
          <ScatterPanel lb={lb} />
          <MedalTable lb={lb} />
        </div>
        <div className="grid cols-2 lb-charts-2">
          <SpeedPanel lb={lb} />
          <CategoryPanel lb={lb} />
        </div>
      </div>
    </ScoreNameContext.Provider>
  );
}
