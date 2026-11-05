/**
 * Presenter slide "Which model for <job>?" for a run that includes Business tests: the job this run covers best
 * (most of its tests run, then most tasks), with the run's models ranked by the same published rule as the
 * "Which model for this job?" page (src/core/business-jobs.ts), computed from the run's own recorded results.
 */
import configJson from '../../../../config/business-jobs.json';
import { buildBusinessJobsView, tasksPerJob, type BusinessJob, type BusinessJobsConfig, type BusinessJobsView, type JobTestInfo } from '../../../../src/core/business-jobs.ts';
import type { CaseResultLite, TestSnapshot } from '../../types.ts';
import { ModelMonogram } from '../../components/viz/ModelBadge.tsx';
import { ScoreBar, SuitabilityBadge, costWords } from './parts.tsx';
import '../business.css';
import './jobs.css';

const config = configJson as unknown as BusinessJobsConfig;
const JOB_TESTS = new Set(config.jobs.flatMap((j) => j.tests));

export interface JobsSlideContender {
  id: string;
  label: string;
  color: string;
  baseline: boolean;
}

/** True when the run scored any test behind a business job. */
export function runHasBusinessJobs(results: readonly CaseResultLite[]): boolean {
  return results.some((r) => JOB_TESTS.has(r.testId) && typeof r.score === 'number');
}

/** The view for the run, and the job it covers best (null when it covers none). */
export function jobsFromRun(results: readonly CaseResultLite[], contenders: readonly JobsSlideContender[], snaps: readonly TestSnapshot[]): { view: BusinessJobsView; job: BusinessJob } | null {
  const tests: Record<string, JobTestInfo> = {};
  for (const s of snaps) if (JOB_TESTS.has(s.id)) tests[s.id] = { name: s.name, exists: true, kind: s.kind, cases: s.caseIds.length };
  const view = buildBusinessJobsView({ config, tests, models: contenders.filter((c) => !c.baseline).map((c) => ({ id: c.id, label: c.label, color: c.color })), results });
  const cover = (j: BusinessJob) => j.tests.filter((t) => tests[t]).length / j.tests.length;
  const ready = (j: BusinessJob) => ((view.results[j.id] ?? []).some((r) => r.verdict === 'ready') ? 1 : 0);
  // The job the run covers best; among equals, one where a model is ready (the clearer story), then the most tasks.
  const job = view.jobs
    .filter((j) => (view.results[j.id] ?? []).some((r) => r.answers > 0))
    .sort((a, b) => cover(b) - cover(a) || ready(b) - ready(a) || tasksPerJob(b, tests) - tasksPerJob(a, tests))[0];
  return job ? { view, job } : null;
}

export function jobsSlideTitle(job: BusinessJob | null | undefined): string {
  return job ? `Which model for “${job.name}”?` : 'Which model for this job?';
}

export function JobsSlide({ results, contenders, snaps }: { results: readonly CaseResultLite[]; contenders: readonly JobsSlideContender[]; snaps: readonly TestSnapshot[] }) {
  const data = jobsFromRun(results, contenders, snaps);
  if (!data) return null;
  const { view, job } = data;
  const rows = (view.results[job.id] ?? []).filter((r) => r.answers > 0).slice(0, 6);
  return (
    <div className="s-bizjobs biz-page" data-area="business">
      <div className="tr-top">
        <span className="pcat" style={{ ['--cc' as string]: 'var(--area-business)' }}>
          <i />
          Business
        </span>
        <span className="tr-test">
          {jobsSlideTitle(job)}
        </span>
        <span className="tr-k">{job.what}</span>
      </div>
      <div className="bj-slide-rows">
        {rows.map((r) => {
          const m = view.models.find((x) => x.id === r.modelId)!;
          const cost = costWords(r.costPerTaskUsd);
          return (
            <div key={r.modelId} className={`bj-slide-row v-${r.verdict}`}>
              <ModelMonogram label={m.label} color={m.color} size="lg" />
              <b>{m.label}</b>
              <SuitabilityBadge verdict={r.verdict} size="lg" />
              <div>
                <ScoreBar score={r.score} ci={r.ci95} verdict={r.verdict} />
                <span className="muted">
                  {[r.mistakes[0] ? `Most common slip: ${r.mistakes[0].text.replace(/^Serious: /, '')}` : r.score === 1 ? 'Every task right' : '', cost ? `${cost.thousand} per 1,000 tasks` : ''].filter(Boolean).join(' · ')}
                </span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
