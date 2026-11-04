/**
 * The Business Ladder (#/business/ladder): for one model, or 2–4 side by side, each business scenario as a
 * ladder from Level 1 (bottom) to Level 5 (top), with the height reached, every level's score and 95% range,
 * "coming soon" levels, and (for two or more models) paired comparisons in plain words.
 */
import { useMemo, type CSSProperties } from 'react';
import { useAsync } from '../hooks.ts';
import { Link, setQuery, useRoute } from '../router.tsx';
import { useViewerCaption } from '../context.tsx';
import { Empty, ErrorState, LoadingPage, PageHead, cx } from '../components/ui.tsx';
import { AreaIcon } from '../simple/icons.tsx';
import { businessApi } from './businessApi.ts';
import { DemoNote, LevelsStaircase, ObjectivityPanel, ScenarioLadder } from './LadderParts.tsx';
import { runBusinessHref } from './BusinessHomePage.tsx';
import './business.css';

export default function BusinessLadderPage() {
  const { query } = useRoute();
  const picked = useMemo(() => (query.get('models') ?? '').split(',').filter(Boolean).slice(0, 4), [query]);
  const state = useAsync(() => businessApi.ladder(picked.length > 1 ? picked : []), [picked.join(',')]);
  const view = state.data;
  const models = view?.models ?? [];
  // Rank by total height, then by mean level-1 score, so the default pick is the strongest climber.
  const ranked = useMemo(
    () =>
      models.slice().sort((a, b) => {
        const h = (m: typeof a) => m.scenarios.reduce((s, x) => s + x.height, 0);
        const l1 = (m: typeof a) => m.scenarios.reduce((s, x) => s + (x.levels[0]?.score ?? 0), 0);
        return h(b) - h(a) || l1(b) - l1(a);
      }),
    [models],
  );
  const selected = picked.filter((id) => models.some((m) => m.id === id));
  const shown = (selected.length ? selected : ranked[0] ? [ranked[0].id] : []).map((id) => models.find((m) => m.id === id)!);
  const toggle = (id: string) => {
    const cur = shown.map((m) => m.id);
    const next = cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id].slice(-4);
    setQuery({ models: next.join(',') || null });
  };

  useViewerCaption(
    shown.length > 1 ? 'Side by side on the Business Ladder: how far each model climbs in each business area. “Tied” means the gap is within the noise.' : 'The Business Ladder: how far up five levels of real company work a model can go, in six business areas.',
    'A level is reached when every test at it, and every level below, scores at least 70 · ranges are 95%',
  );

  if (state.loading && !view) return <LoadingPage />;
  if (state.error) return <ErrorState error={state.error} onRetry={state.reload} />;
  if (!view) return null;
  const def = view.definition;
  const single = shown.length === 1 ? shown[0]! : null;
  const reached = single ? single.scenarios.filter((s) => s.height > 0).length : 0;

  return (
    <div className="page biz-page biz-ladder-page" data-area="business">
      <PageHead
        eyebrow={
          <span className="biz-eyebrow">
            <AreaIcon.Business /> Business
          </span>
        }
        title={def.title}
        sub={def.tagline}
        actions={
          <div className="row no-broadcast" style={{ gap: 8 }}>
            <Link to="/business/tests" className="btn ghost">
              Business tests
            </Link>
            <Link to={runBusinessHref(view)} className="btn biz-cta">
              Run the Business tests
            </Link>
          </div>
        }
      />
      <DemoNote view={view} />

      <details className="biz-levels-box" open>
        <summary>
          <b>The five levels</b> <span>Each level hands the AI more of the job and leaves a person less to check.</span>
        </summary>
        <LevelsStaircase levels={def.levels} compact />
      </details>

      {models.length === 0 ? (
        <Empty
          icon={<AreaIcon.Business />}
          title="No Business results yet"
          actions={
            <Link to={runBusinessHref(view)} className="btn biz-cta">
              Run the Business tests
            </Link>
          }
        >
          Run the Business tests with a few models and their ladders appear here.
        </Empty>
      ) : (
        <>
          <div className="biz-picker" role="group" aria-label="Models to show (pick up to four)">
            <span className="lbl">{shown.length > 1 ? `Comparing ${shown.length} models` : 'Showing'}</span>
            {ranked.map((m) => {
              const on = shown.some((x) => x.id === m.id);
              return (
                <button key={m.id} type="button" className={cx('biz-model', on && 'on')} aria-pressed={on} onClick={() => toggle(m.id)} style={{ ['--mc' as string]: m.color ?? 'var(--accent)' } as CSSProperties}>
                  <i />
                  {m.label}
                  <span className="cov" title="Business tests this model has results for">
                    {m.coverage.run}/{m.coverage.built}
                  </span>
                </button>
              );
            })}
            <span className="hint">Pick up to four to compare.</span>
          </div>

          {single && (
            <p className="biz-summary">
              <b style={{ color: single.color }}>{single.label}</b> reached at least Level 1 in <b>{reached}</b> of {def.scenarios.length} business areas
              {single.coverage.run < single.coverage.built && ` · results for ${single.coverage.run} of ${single.coverage.built} built tests`}.
            </p>
          )}
          {shown.length > 1 && view.comparisons.length > 0 && (
            <p className="biz-summary">
              Comparisons use only the questions both models answered (a paired test). <b>“Ahead”</b> means the whole 95% range of the gap is on one side of zero; otherwise it is <b>statistically tied</b>.
            </p>
          )}

          <div className={cx('biz-grid', shown.length > 1 && 'wide')}>
            {def.scenarios.map((s) => (
              <ScenarioLadder key={s.id} view={view} scenario={s} models={shown} />
            ))}
          </div>
        </>
      )}

      <ObjectivityPanel />
    </div>
  );
}
