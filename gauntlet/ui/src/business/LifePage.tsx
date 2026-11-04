/**
 * Everyday Life (#/life): the personal-admin tests (`life.*`), kept apart from the benchmark with their own
 * results. Lists the tests in the library and links to their own leaderboard.
 */
import { api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, href, pathOf } from '../router.tsx';
import { useViewerCaption } from '../context.tsx';
import { Empty, ErrorState, LoadingPage, PageHead } from '../components/ui.tsx';
import { AreaIcon } from '../simple/icons.tsx';
import { LIFE_SUITE_ID, areaInfo, areaOfTest } from '../../../src/core/areas.ts';
import type { TestSummary } from '../types.ts';
import './business.css';

export default function LifePage() {
  const state = useAsync<TestSummary[]>(() => api.tests(), []);
  const info = areaInfo('life');
  useViewerCaption('Everyday Life: the personal admin people ask an AI for help with, checked against exact answers.', 'Separate from the Gauntlet Index');
  if (state.loading && !state.data) return <LoadingPage />;
  if (state.error) return <ErrorState error={state.error} onRetry={state.reload} />;
  const tests = (state.data ?? []).filter((t) => areaOfTest(t) === 'life');
  return (
    <div className="page biz-page life-page" data-area="life">
      <PageHead
        eyebrow={
          <span className="biz-eyebrow life">
            <AreaIcon.Life /> {info.name}
          </span>
        }
        title="Everyday Life"
        sub={info.blurb}
        actions={
          <div className="row no-broadcast" style={{ gap: 8 }}>
            <Link to={href('/leaderboard', { suite: LIFE_SUITE_ID })} className="btn ghost">
              Everyday Life results
            </Link>
            {tests.length > 0 && (
              <Link to={href('/run/new', { tests: tests.map((t) => t.id).join(',') })} className="btn life-cta">
                Run the Everyday Life tests
              </Link>
            )}
          </div>
        }
      />
      {tests.length === 0 ? (
        <Empty icon={<AreaIcon.Life />} title="Everyday Life tests are on their way">
          Trip planning on a budget, splitting bills fairly, picking the cheapest tariff and more. They will appear here as soon as they are in the test library.
        </Empty>
      ) : (
        <div className="life-grid">
          {tests.map((t) => (
            <article key={t.id} className="biz-tcard life">
              <div className="tc-top">
                <b>{t.name}</b>
                <span className="biz-chip">{t.caseCount} cases</span>
              </div>
              {t.hook && <p className="tc-hook">{t.hook}</p>}
              <p className="tc-what">{t.description}</p>
              <Link to={pathOf('tests', t.id)} className="tc-go">
                See in depth <span aria-hidden="true">→</span>
              </Link>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
