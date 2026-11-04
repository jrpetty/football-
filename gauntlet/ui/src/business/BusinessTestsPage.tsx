/**
 * Business tests (#/business/tests): every Business test grouped by scenario, then by level. Each card shows
 * the level, what the AI has to do and how it is marked (from the test's hand-written explainer), with a link
 * to the full test page. Planned tests are shown as "coming soon".
 */
import { useEffect } from 'react';
import { useAsync } from '../hooks.ts';
import { Link, pathOf, useRoute } from '../router.tsx';
import { useViewerCaption } from '../context.tsx';
import { ErrorState, LoadingPage, PageHead, cx } from '../components/ui.tsx';
import { ExplainIcon } from '../components/viz/ExplainIcon.tsx';
import { AreaIcon } from '../simple/icons.tsx';
import { businessApi } from './businessApi.ts';
import { DemoNote, LevelTag, ScenarioGlyph, scenarioStyle } from './LadderParts.tsx';
import { runBusinessHref } from './BusinessHomePage.tsx';
import './business.css';

export default function BusinessTestsPage() {
  const state = useAsync(() => businessApi.ladder(), []);
  const view = state.data;
  const { query } = useRoute();
  const jump = query.get('scenario');
  useViewerCaption('Every Business test, by business area and level: what the AI has to do and exactly how it is marked.', 'Marked by machine against computed answers · no AI judges');
  // Scroll to a scenario when opened as #/business/tests?scenario=finance.
  useEffect(() => {
    if (view && jump) document.getElementById(`biz-${jump}`)?.scrollIntoView({ block: 'start' });
  }, [view, jump]);

  if (state.loading && !view) return <LoadingPage />;
  if (state.error) return <ErrorState error={state.error} onRetry={state.reload} />;
  if (!view) return null;
  const def = view.definition;
  return (
    <div className="page biz-page biz-tests" data-area="business">
      <PageHead
        eyebrow={
          <span className="biz-eyebrow">
            <AreaIcon.Business /> Business
          </span>
        }
        title="Business tests"
        sub="Every test on the Business Ladder, by business area and level. Each one is a real job, marked by machine against answers worked out by a solver."
        actions={
          <Link to={runBusinessHref(view)} className="btn biz-cta no-broadcast">
            Run the Business tests
          </Link>
        }
      />
      <DemoNote view={view} />
      <nav className="biz-jump" aria-label="Business areas">
        {def.scenarios.map((s) => (
          <a key={s.id} href={`#/business/tests?scenario=${s.id}`} style={scenarioStyle(s)} onClick={(e) => (e.preventDefault(), document.getElementById(`biz-${s.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' }))}>
            <ScenarioGlyph scenario={s} size="sm" />
            {s.name}
          </a>
        ))}
      </nav>
      {def.scenarios.map((s) => (
        <section key={s.id} id={`biz-${s.id}`} className="biz-scn" style={scenarioStyle(s)}>
          <header className="biz-scn-head">
            <ScenarioGlyph scenario={s} size="lg" />
            <div>
              <h2>{s.name}</h2>
              <p>{s.summary}</p>
            </div>
          </header>
          <div className="biz-scn-levels">
            {def.levels.map((lv) => {
              const refs = s.tests.filter((t) => t.level === lv.level);
              return (
                <div key={lv.level} className={cx('biz-scn-level', !refs.some((t) => view.tests[t.id]?.exists) && 'soon')}>
                  <div className="lvl-side">
                    <LevelTag level={lv.level} name={lv.name} />
                    <p>{lv.what}</p>
                  </div>
                  <div className="lvl-tests">
                    {refs.length === 0 && <div className="biz-tcard soon none">No test planned at this level yet.</div>}
                    {refs.map((t) => {
                      const info = view.tests[t.id];
                      if (!info?.exists)
                        return (
                          <div key={t.id} className="biz-tcard soon">
                            <div className="tc-top">
                              <b>{info?.name ?? t.id}</b>
                              <span className="biz-chip soon">Coming soon</span>
                            </div>
                            <p className="tc-what">Being built: a Level {lv.level} ({lv.name}) test for {s.name}.</p>
                          </div>
                        );
                      return (
                        <article key={t.id} className="biz-tcard">
                          <div className="tc-top">
                            {info.icon && <ExplainIcon name={info.icon} className="tc-icon" />}
                            <b>{info.name}</b>
                            <span className="biz-chip">{info.cases} cases</span>
                          </div>
                          {info.hook && <p className="tc-hook">{info.hook}</p>}
                          <div className="tc-cols">
                            <div>
                              <h4>What the AI does</h4>
                              <p>{info.whatItTests}</p>
                            </div>
                            <div>
                              <h4>How it is marked</h4>
                              <ul>
                                {(info.howScored ?? []).map((h) => (
                                  <li key={h}>
                                    <ExplainIcon name="check" />
                                    {h}
                                  </li>
                                ))}
                              </ul>
                            </div>
                          </div>
                          <Link to={pathOf('tests', t.id)} className="tc-go">
                            See in depth <span aria-hidden="true">→</span>
                          </Link>
                        </article>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      ))}
    </div>
  );
}
