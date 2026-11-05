/**
 * "Which model for this job?" (#/business/jobs): for business owners. Pick a job ("Check supplier invoices before
 * paying"), see every model ranked with a plain verdict (Ready, with spot checks / Use with a human check / Not
 * ready / Not enough data), how often it is right, what it gets wrong, what a task costs and how long it takes,
 * and open a real case it did. Compare two models on the same case, or scan the whole picture in the heat-map.
 *
 * Every figure comes from GET /api/business-jobs (src/core/business-jobs.ts: the published rule, the cluster
 * bootstrap, the scorer's own marks). Query: ?job=<id>, &compare=<a>,<b>.
 */
import { useEffect, useMemo, useState } from 'react';
import { useAsync } from '../../hooks.ts';
import { Link, navigate, setQuery, useRoute } from '../../router.tsx';
import { useViewerCaption } from '../../context.tsx';
import { ErrorState, LoadingPage, cx } from '../../components/ui.tsx';
import { ExplainIcon } from '../../components/viz/ExplainIcon.tsx';
import { ModelMonogram } from '../../components/viz/ModelBadge.tsx';
import { AreaIcon } from '../../simple/icons.tsx';
import { businessApi } from '../businessApi.ts';
import { ruleInWords, tasksPerJob, type BusinessJob, type BusinessJobsView, type JobModelResult } from '../../../../src/core/business-jobs.ts';
import { Caveat, JobDemoNote, ScoreBar, SuitabilityBadge, costWords, modelStyle, secondsWords } from './parts.tsx';
import { JobCaseDialog } from './JobCase.tsx';
import { JobCompare } from './JobCompare.tsx';
import { JobHeatmap } from './JobHeatmap.tsx';
import '../business.css';
import './jobs.css';

export default function JobsPage() {
  const route = useRoute();
  const state = useAsync(() => businessApi.jobs(), []);
  const view = state.data;
  const jobId = route.query.get('job');
  const job = view?.jobs.find((j) => j.id === jobId) ?? null;
  const compare = route.query.get('compare');

  useViewerCaption(
    job ? `Which AI model can ${job.name.toLowerCase()}? Every model ranked on Gauntlet’s tests for this job.` : 'Which AI model for this job? Pick a job your business does and see which models are ready for it.',
    'Marked by code against worked-out answers · never by AI judges',
  );
  useEffect(() => {
    if (job) window.scrollTo({ top: 0 });
  }, [job?.id]);

  if (state.loading && !view) return <LoadingPage />;
  if (state.error) return <ErrorState error={state.error} onRetry={state.reload} />;
  if (!view) return null;

  return (
    <div className="page biz-page bj-page" data-area="business">
      <header className="bj-hero">
        <div className="biz-eyebrow">
          <AreaIcon.Business /> Gauntlet Business
        </div>
        {job ? (
          <>
            <nav className="bj-crumb no-broadcast" aria-label="Back">
              <Link to="/business/jobs">← All jobs</Link>
            </nav>
            <div className="bj-jobhead">
              <span className="bj-glyph lg" aria-hidden="true">
                <ExplainIcon name={job.icon} />
              </span>
              <div>
                <h1>{job.name}</h1>
                <p className="lede">{job.what}</p>
              </div>
            </div>
          </>
        ) : (
          <>
            <h1>
              Which model for <span className="hl">this job?</span>
            </h1>
            <p className="lede">{view.tagline}</p>
          </>
        )}
        <Caveat view={view} job={job ?? undefined} />
      </header>
      <JobDemoNote view={view} />

      {job ? (
        <>
          <div className="bj-modes" role="tablist" aria-label="How to show this job">
            <button type="button" role="tab" aria-selected={!compare} className={cx('bj-mode', !compare && 'on')} onClick={() => setQuery({ compare: null })}>
              Every model, ranked
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={!!compare}
              className={cx('bj-mode', !!compare && 'on')}
              onClick={() => {
                const rows = view.results[job.id] ?? [];
                const two = rows.filter((r) => r.answers > 0).slice(0, 2).map((r) => r.modelId);
                setQuery({ compare: two.length === 2 ? two.join(',') : 'pick' });
              }}
            >
              Compare two models on this job
            </button>
          </div>
          {compare ? <JobCompare view={view} job={job} pair={compare} /> : <RankedList view={view} job={job} />}
          <TestsBehind view={view} job={job} />
          <HowWeDecide view={view} />
        </>
      ) : (
        <>
          <JobPicker view={view} />
          <section className="biz-section" id="overview">
            <div className="biz-section-head">
              <h2>Every model, every job</h2>
              <p>One square per model and job. Green: ready with spot checks. Amber: use with a human check. Red: not ready. Grey: not enough data. Click a square to open that job.</p>
            </div>
            <JobHeatmap view={view} onPick={(id) => navigate('/business/jobs', { job: id })} />
          </section>
          <HowWeDecide view={view} />
        </>
      )}
      <p className="biz-apart">
        <ExplainIcon name="lock" /> Built from the Business tests: <Link to="/business/tests">see every test</Link> · <Link to="/business/results">Business results</Link> · <Link to="/business/ladder">Business Ladder</Link>
      </p>
    </div>
  );
}

// ───────────────────────────── job picker ─────────────────────────────

function JobPicker({ view }: { view: BusinessJobsView }) {
  const label = (id: string) => view.models.find((m) => m.id === id);
  return (
    <section className="biz-section" aria-labelledby="bj-pick-h">
      <div className="biz-section-head">
        <h2 id="bj-pick-h">Pick a job</h2>
        <p>Each card is a job businesses hand to AI. The models shown are ready for it today, with spot checks.</p>
      </div>
      <div className="bj-grid">
        {view.jobs.map((j) => {
          const rows = view.results[j.id] ?? [];
          const ready = rows.filter((r) => r.verdict === 'ready');
          const best = rows.find((r) => r.verdict !== 'no-data');
          return (
            <Link key={j.id} to={`/business/jobs?job=${j.id}`} className="bj-card" aria-label={`${j.name}: ${ready.length ? `${ready.length} model${ready.length === 1 ? '' : 's'} ready` : 'no model ready yet'}`}>
              <span className="bj-glyph" aria-hidden="true">
                <ExplainIcon name={j.icon} />
              </span>
              <span className="bj-card-t">
                <b>{j.name}</b>
                <span>{j.what}</span>
              </span>
              <span className="bj-card-foot">
                {ready.length ? (
                  <>
                    <span className="bj-ready-k">
                      <SuitabilityBadge verdict="ready" size="sm" short /> {ready.length === 1 ? '1 model' : `${ready.length} models`}
                    </span>
                    <span className="bj-tokens">
                      {ready.slice(0, 3).map((r) => {
                        const m = label(r.modelId);
                        return (
                          <span key={r.modelId} className="bj-token" style={modelStyle(m)}>
                            <ModelMonogram label={m?.label ?? r.modelId} color={m?.color} size="sm" />
                            {m?.label ?? r.modelId}
                          </span>
                        );
                      })}
                      {ready.length > 3 && <span className="bj-token more">+{ready.length - 3}</span>}
                    </span>
                  </>
                ) : best ? (
                  <span className="bj-none">
                    No model ready yet. Best: <b>{label(best.modelId)?.label}</b> <SuitabilityBadge verdict={best.verdict} size="sm" short />
                  </span>
                ) : (
                  <span className="bj-none">Not tested yet</span>
                )}
              </span>
            </Link>
          );
        })}
      </div>
    </section>
  );
}

// ───────────────────────────── one job, ranked ─────────────────────────────

function RankedList({ view, job }: { view: BusinessJobsView; job: BusinessJob }) {
  const rows = view.results[job.id] ?? [];
  const [open, setOpen] = useState<JobModelResult | null>(null);
  const ready = rows.filter((r) => r.verdict === 'ready').length;
  const headline = !rows.length
    ? 'No model has been tested on this job yet.'
    : ready
      ? `${ready} of ${rows.length} models are ready for this job, with spot checks.`
      : rows.some((r) => r.verdict === 'check')
        ? 'No model is ready to work alone yet; the best need a person to check their work.'
        : 'No model is good enough for this job yet.';
  return (
    <section className="biz-section" aria-labelledby="bj-rank-h">
      <div className="biz-section-head">
        <h2 id="bj-rank-h">{headline}</h2>
        <p>Best first. Each row shows how often the model got the job right, what it gets wrong, what it costs and how long it takes.</p>
      </div>
      <ol className="bj-rows">
        {rows.map((r, i) => (
          <ModelRow key={r.modelId} view={view} job={job} r={r} rank={i + 1} onSee={() => setOpen(r)} />
        ))}
      </ol>
      {open?.example && (
        <JobCaseDialog
          jobName={job.name}
          model={view.models.find((m) => m.id === open.modelId)!}
          testId={open.example.testId}
          testName={view.tests[open.example.testId]?.name ?? open.example.testId}
          program={view.tests[open.example.testId]?.kind === 'program'}
          answer={(view.answers[open.modelId]?.[open.example.testId] ?? []).find((a) => a.key === open.example!.key)!}
          note={open.example.note}
          onClose={() => setOpen(null)}
        />
      )}
    </section>
  );
}

function ModelRow({ view, job, r, rank, onSee }: { view: BusinessJobsView; job: BusinessJob; r: JobModelResult; rank: number; onSee: () => void }) {
  const m = view.models.find((x) => x.id === r.modelId);
  const cost = costWords(r.costPerTaskUsd);
  const total = tasksPerJob(job, view.tests);
  return (
    <li className={cx('bj-row', `v-${r.verdict}`)} style={modelStyle(m)}>
      <div className="bj-row-who">
        <span className="bj-rank tnum" aria-label={`Rank ${rank}`}>
          {rank}
        </span>
        <ModelMonogram label={m?.label ?? r.modelId} color={m?.color} size="lg" />
        <div className="bj-row-name">
          <b>{m?.label ?? r.modelId}</b>
          {m?.vendor && <span className="muted">{m.vendor}</span>}
        </div>
        <SuitabilityBadge verdict={r.verdict} size="lg" />
      </div>
      <p className="bj-why">{r.why}</p>
      <div className="bj-row-grid">
        <div className="bj-cell acc">
          <span className="bj-k">How often it’s right</span>
          <ScoreBar score={r.score} ci={r.ci95} verdict={r.verdict} />
          <span className="bj-sub muted">
            {r.tasks} of {total} tasks marked
            {r.answers > r.tasks ? ` (${r.answers} attempts: some tasks were run more than once)` : ''}
            {r.missingTests.length ? ` · not run yet: ${r.missingTests.map((t) => view.tests[t]?.name ?? t).join(', ')}` : ''}
          </span>
        </div>
        <div className="bj-cell wrong">
          <span className="bj-k">What goes wrong</span>
          {r.severe.answers > 0 && (
            <p className="bj-serious">
              <b>Serious:</b> {r.severe.rules.join('; ')}
            </p>
          )}
          {r.mistakes.length ? (
            <ul>
              {r.mistakes.filter((x) => !x.text.startsWith('Serious: ')).map((x) => (
                <li key={x.text}>
                  <span className="bj-mis">{capital(x.text)}</span>{' '}
                  <span className="muted tnum">
                    in {x.tasks} of {r.answers} {r.answers > r.tasks ? 'attempts' : r.answers === 1 ? 'task' : 'tasks'}
                  </span>
                </li>
              ))}
            </ul>
          ) : r.answers ? (
            <p className="muted">No mistakes recorded.</p>
          ) : (
            <p className="muted">Nothing to show yet.</p>
          )}
        </div>
        <div className="bj-cell money">
          <span className="bj-k">Cost</span>
          {cost ? (
            <>
              <b className="tnum bj-big">{cost.one}</b>
              <span className="muted">per task</span>
              <span className="tnum">{cost.thousand} per 1,000 tasks</span>
            </>
          ) : (
            <span className="muted">No cost recorded (copied by hand or free)</span>
          )}
        </div>
        <div className="bj-cell speed">
          <span className="bj-k">Speed</span>
          <b className="tnum bj-big">{secondsWords(r.medianSec)}</b>
          <span className="muted">per task (typical)</span>
        </div>
      </div>
      {r.example && (
        <div className="bj-see">
          <button type="button" className="btn biz-cta" onClick={onSee}>
            <ExplainIcon name="eye" /> See it do this job
          </button>
          <span className={cx('bj-see-note', r.example.right ? 'ok' : 'no')}>
            {r.example.right ? '✓' : '✕'} {r.example.note} <span className="muted">({view.tests[r.example.testId]?.name})</span>
          </span>
        </div>
      )}
    </li>
  );
}

const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

// ───────────────────────────── the tests behind a job, and the rule ─────────────────────────────

function TestsBehind({ view, job }: { view: BusinessJobsView; job: BusinessJob }) {
  return (
    <section className="bj-behind" aria-label="The tests behind this job">
      <b>The tests behind this job</b>
      <ul>
        {job.tests.map((t) => (
          <li key={t}>
            <Link to={`/tests/${encodeURIComponent(t)}`}>{view.tests[t]?.name ?? t}</Link>
            <span className="muted tnum"> · {view.tests[t]?.cases ?? 0} {view.tests[t]?.kind === 'program' ? 'simulated runs' : 'tasks'}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

export function HowWeDecide({ view }: { view: BusinessJobsView }) {
  const words = useMemo(() => ruleInWords(view.rule), [view.rule]);
  return (
    <section className="bj-how" aria-labelledby="bj-how-h">
      <h2 id="bj-how-h">How we decide</h2>
      <p>The same rule for every model and every job, fixed in advance. Every answer is marked by code against a worked-out answer key, never by an AI judge.</p>
      <dl>
        {words.map((w) => (
          <div key={w.verdict} className={`v-${w.verdict}`}>
            <dt>
              <SuitabilityBadge verdict={w.verdict} />
            </dt>
            <dd>{w.text}</dd>
          </div>
        ))}
      </dl>
      <p className="muted">
        “Allowing for luck”: we re-draw the tasks a thousand times and keep the range the score stays in 95 times out of 100. A job’s score is the average of its tests, each test counting equally. Cost is the real API cost recorded for each task; speed is the typical time per task.{' '}
        <Link to="/methodology">Full method</Link>
      </p>
    </section>
  );
}

