/**
 * The first thing on the Business home: "Which model for this job?" with every job as a one-click link, each
 * marked with the best verdict any model has for it today (from GET /api/business-jobs).
 */
import { useAsync } from '../../hooks.ts';
import { Link } from '../../router.tsx';
import { cx } from '../../components/ui.tsx';
import { ExplainIcon } from '../../components/viz/ExplainIcon.tsx';
import { SUITABILITY_LABEL, SUITABILITY_ORDER, type Suitability } from '../../../../src/core/business-jobs.ts';
import { businessApi } from '../businessApi.ts';
import { SuitabilityIcon } from './parts.tsx';
import './jobs.css';

export function JobsTeaser() {
  const state = useAsync(() => businessApi.jobs(), []);
  const view = state.data;
  const best = (jobId: string): Suitability => {
    const rows = view?.results[jobId] ?? [];
    return rows.reduce<Suitability>((b, r) => (SUITABILITY_ORDER.indexOf(r.verdict) < SUITABILITY_ORDER.indexOf(b) ? r.verdict : b), 'no-data');
  };
  return (
    <section className="bj-teaser" aria-labelledby="bj-teaser-h">
      <div className="bj-teaser-head">
        <div>
          <h2 id="bj-teaser-h">Which model for this job?</h2>
          <p>Pick a job your business does and see which AI models are ready for it, what they get wrong and what they cost.</p>
        </div>
        <Link to="/business/jobs" className="btn biz-cta">
          See every job <span aria-hidden="true">→</span>
        </Link>
      </div>
      {view && (
        <p className="bj-teaser-key">
          {view.demo && <span className="bj-teaser-demo">Demo data — not real results</span>}
          Best model today:
          {(['ready', 'check', 'not-ready'] as const).map((v) => (
            <span key={v} className={`v-${v}`}>
              <SuitabilityIcon verdict={v} /> {SUITABILITY_LABEL[v].toLowerCase()}
            </span>
          ))}
        </p>
      )}
      {view && (
        <div className="bj-teaser-jobs">
          {view.jobs.map((j) => {
            const v = best(j.id);
            return (
              <Link key={j.id} to={`/business/jobs?job=${j.id}`} className={cx('bj-teaser-job', `v-${v}`)} title={`Best today: ${SUITABILITY_LABEL[v]}`}>
                <span className="bj-glyph" aria-hidden="true">
                  <ExplainIcon name={j.icon} />
                </span>
                <span>{j.name}</span>
                <span className="sr-only">Best today: {SUITABILITY_LABEL[v]}</span>
                <SuitabilityIcon verdict={v} />
              </Link>
            );
          })}
        </div>
      )}
    </section>
  );
}
