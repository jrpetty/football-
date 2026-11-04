/**
 * Business home (#/business): the landing page for business viewers. What the area measures in one line, the
 * five levels, the six business scenarios with what is built and what is coming, how objective it is, and a
 * call to run the Business tests with the Cost Planner's estimate.
 */
import { useMemo, type CSSProperties } from 'react';
import { api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, href } from '../router.tsx';
import { useViewerCaption } from '../context.tsx';
import { ErrorState, LoadingPage, Skeleton } from '../components/ui.tsx';
import { ExplainIcon } from '../components/viz/ExplainIcon.tsx';
import { AreaIcon } from '../simple/icons.tsx';
import { money } from '../money.ts';
import { BUSINESS_SUITE_ID } from '../../../src/core/areas.ts';
import { ladderTestIds } from '../../../src/core/business-ladder.ts';
import { businessApi, type BusinessLadderView } from './businessApi.ts';
import { DemoNote, LevelsStaircase, MiniLadder, ObjectivityPanel, ScenarioGlyph, scenarioStyle } from './LadderParts.tsx';
import './business.css';

/** The built Business tests (ids). */
export function builtBusinessTests(view: BusinessLadderView): string[] {
  return ladderTestIds(view.definition).filter((id) => view.tests[id]?.exists);
}

/** New Run, preset to the Business suite when it exists, otherwise to the built Business tests. */
export function runBusinessHref(view: BusinessLadderView | null | undefined): string {
  if (!view || view.suiteId === BUSINESS_SUITE_ID) return `/run/new?suite=${BUSINESS_SUITE_ID}`;
  return `/run/new?tests=${builtBusinessTests(view).join(',')}`;
}

export default function BusinessHomePage() {
  const state = useAsync(() => businessApi.ladder(), []);
  const view = state.data;
  const estimate = useAsync(async () => {
    if (!view) return null;
    const cs = (await api.contestants()).filter((c) => c.enabled && c.providerType !== 'manual' && c.providerType !== 'mock');
    if (!cs.length) return null;
    const req = view.suiteId === BUSINESS_SUITE_ID ? { suiteId: BUSINESS_SUITE_ID } : { testIds: builtBusinessTests(view) };
    const est = await api.estimate({ ...req, contestantIds: cs.map((c) => c.id), repeats: 1 }).catch(() => null);
    return est && est.jobs > 0 ? { usd: est.estCostUsd, models: cs.length } : null;
  }, [view]);

  useViewerCaption('Gauntlet Business: the jobs companies really hand to AI, from reading one invoice to running a whole department, on a five-level ladder.', 'Every Business test is machine-checked against computed answers · never part of the Gauntlet Index');

  const stats = useMemo(() => {
    if (!view) return null;
    const ids = ladderTestIds(view.definition);
    const built = ids.filter((id) => view.tests[id]?.exists).length;
    return { built, planned: ids.length - built, cases: ids.reduce((s, id) => s + (view.tests[id]?.cases ?? 0), 0) };
  }, [view]);

  if (state.loading && !view) return <LoadingPage />;
  if (state.error) return <ErrorState error={state.error} onRetry={state.reload} />;
  if (!view || !stats) return null;
  const def = view.definition;
  // The best height any model reached per scenario (for the mini ladders).
  const best = (sid: string) => {
    let top: { label: string; color?: string; height: number } | null = null;
    for (const m of view.models) {
      const h = m.scenarios.find((s) => s.id === sid)?.height ?? 0;
      if (h > 0 && (!top || h > top.height)) top = { label: m.label, color: m.color, height: h };
    }
    return top;
  };

  return (
    <div className="page biz-page biz-home" data-area="business">
      <header className="biz-hero">
        <div className="biz-hero-copy">
          <div className="biz-eyebrow">
            <AreaIcon.Business /> Gauntlet Business
          </div>
          <h1>
            How far can AI go <span className="hl">at work?</span>
          </h1>
          <p className="lede">Real company jobs on a five-level ladder: from reading one invoice for a person to check, to running a whole department for a month.</p>
          <div className="biz-hero-cta">
            <Link to={runBusinessHref(view)} className="btn lg biz-cta no-broadcast">
              Run the Business tests
              {estimate.loading ? (
                <Skeleton w={70} h={14} />
              ) : estimate.data ? (
                <span className="est">
                  about {money(estimate.data.usd)} for {estimate.data.models} model{estimate.data.models === 1 ? '' : 's'}
                </span>
              ) : null}
            </Link>
            <Link to="/business/ladder" className="btn lg ghost">
              <ExplainIcon name="ladder" /> See the Business Ladder
            </Link>
          </div>
          {!estimate.loading && !estimate.data && (
            <p className="biz-hint no-broadcast">
              <Link to={href('/costs', view.suiteId === BUSINESS_SUITE_ID ? { suite: BUSINESS_SUITE_ID } : undefined)}>Open the Cost Planner</Link> to price a run before you start.
            </p>
          )}
        </div>
        <dl className="biz-hero-stats">
          <div>
            <dt>Business areas</dt>
            <dd>{def.scenarios.length}</dd>
          </div>
          <div>
            <dt>Levels</dt>
            <dd>5</dd>
          </div>
          <div>
            <dt>Tests built</dt>
            <dd>{stats.built}</dd>
          </div>
          <div>
            <dt>Coming soon</dt>
            <dd>{stats.planned}</dd>
          </div>
        </dl>
      </header>
      <DemoNote view={view} />

      <section className="biz-section">
        <div className="biz-section-head">
          <h2>The five levels</h2>
          <p>The same ladder in every business area. Each step up hands the AI more of the job and leaves a person less to check. A model reaches a level when it scores at least {def.passMark} on every test at that level and every level below.</p>
        </div>
        <LevelsStaircase levels={def.levels} />
      </section>

      <section className="biz-section">
        <div className="biz-section-head">
          <h2>Six business areas</h2>
          <p>Each area is its own ladder, built from the easiest jobs up. Solid rungs have tests today; dashed rungs are being built.</p>
        </div>
        <div className="biz-cards">
          {def.scenarios.map((s) => {
            const top = best(s.id);
            const built = s.tests.filter((t) => view.tests[t.id]?.exists).length;
            return (
              <article key={s.id} className="biz-card" style={scenarioStyle(s)}>
                <header>
                  <ScenarioGlyph scenario={s} size="lg" />
                  <div>
                    <h3>{s.name}</h3>
                    <p>{s.summary}</p>
                  </div>
                </header>
                <MiniLadder scenario={s} view={view} height={top?.height} />
                <footer>
                  <span className="meta">
                    {built} of {s.tests.length} tests built
                  </span>
                  {top ? (
                    <span className="top" style={{ ['--mc' as string]: top.color ?? 'var(--accent)' } as CSSProperties}>
                      <i />
                      {top.label} · Level {top.height}
                    </span>
                  ) : (
                    <span className="meta">No results yet</span>
                  )}
                  <Link to={`/business/tests?scenario=${s.id}`} className="go">
                    Tests <span aria-hidden="true">→</span>
                  </Link>
                </footer>
              </article>
            );
          })}
        </div>
      </section>

      <ObjectivityPanel />

      <p className="biz-apart">
        <ExplainIcon name="lock" /> Business results are their own suite and their own leaderboard: they never feed the Gauntlet Index. <Link to="/business/results">Business results</Link> · <Link to="/leaderboard">Benchmark leaderboard</Link>
      </p>
    </div>
  );
}
