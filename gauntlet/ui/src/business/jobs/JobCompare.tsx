/**
 * "Compare two models on this job": the two side by side (verdict, accuracy, mistakes, cost, speed), the paired
 * comparison on the tasks both did (src/core/paired.ts: "ahead" only when the whole 95% range is on one side),
 * then the same task with both answers: the task where they differ most.
 */
import { useMemo } from 'react';
import { setQuery } from '../../router.tsx';
import { cx } from '../../components/ui.tsx';
import { ModelMonogram } from '../../components/viz/ModelBadge.tsx';
import { pairedDifference, pairedWords } from '../../../../src/core/paired.ts';
import { pickCompareCase, type BusinessJob, type BusinessJobsView, type JobAnswer } from '../../../../src/core/business-jobs.ts';
import { ScoreBar, SuitabilityBadge, costWords, modelStyle, secondsWords } from './parts.tsx';
import { AnswerPanel, caseLabel } from './JobCase.tsx';

const clusters = (xs: JobAnswer[] | undefined) => {
  const out: Record<string, number[]> = {};
  for (const a of xs ?? []) (out[a.caseId] ??= []).push(a.score);
  return out;
};

export function JobCompare({ view, job, pair }: { view: BusinessJobsView; job: BusinessJob; pair: string }) {
  const rows = view.results[job.id] ?? [];
  const tested = rows.filter((r) => r.answers > 0);
  const [aId, bId] = pair.split(',');
  const a = tested.find((r) => r.modelId === aId) ?? null;
  const b = tested.find((r) => r.modelId === bId && r.modelId !== a?.modelId) ?? null;
  const set = (which: 0 | 1, id: string) => {
    const next = [a?.modelId ?? '', b?.modelId ?? ''];
    next[which] = id;
    setQuery({ compare: next.join(',') });
  };
  const modelOf = (id: string) => view.models.find((m) => m.id === id)!;

  const paired = useMemo(() => {
    if (!a || !b) return null;
    const A = view.answers[a.modelId] ?? {};
    const B = view.answers[b.modelId] ?? {};
    return pairedDifference(job.tests.map((t) => ({ a: clusters(A[t]), b: clusters(B[t]) })));
  }, [a, b, view, job]);
  const same = useMemo(() => (a && b ? pickCompareCase(job, view.answers[a.modelId] ?? {}, view.answers[b.modelId] ?? {}, view.tests) : null), [a, b, view, job]);

  if (tested.length < 2) return <p className="bj-empty">At least two models need results on this job before they can be compared.</p>;
  return (
    <section className="biz-section bj-compare" aria-label="Compare two models on this job">
      <div className="bj-pickers">
        {([0, 1] as const).map((w) => (
          <label key={w} className="bj-picker">
            <span>{w === 0 ? 'Model A' : 'Model B'}</span>
            <select value={(w === 0 ? a : b)?.modelId ?? ''} onChange={(e) => set(w, e.target.value)}>
              {!(w === 0 ? a : b) && <option value="">Choose a model</option>}
              {tested.map((r) => (
                <option key={r.modelId} value={r.modelId} disabled={(w === 0 ? b : a)?.modelId === r.modelId}>
                  {modelOf(r.modelId).label}
                </option>
              ))}
            </select>
          </label>
        ))}
      </div>
      {a && b && (
        <>
          <div className="bj-vs">
            {[a, b].map((r) => {
              const m = modelOf(r.modelId);
              const cost = costWords(r.costPerTaskUsd);
              return (
                <article key={r.modelId} className={cx('bj-vs-card', `v-${r.verdict}`)} style={modelStyle(m)}>
                  <header>
                    <ModelMonogram label={m.label} color={m.color} size="lg" />
                    <b>{m.label}</b>
                  </header>
                  <SuitabilityBadge verdict={r.verdict} size="lg" />
                  <ScoreBar score={r.score} ci={r.ci95} verdict={r.verdict} />
                  <dl>
                    <div>
                      <dt>Per task</dt>
                      <dd className="tnum">{cost?.one ?? '–'}</dd>
                    </div>
                    <div>
                      <dt>Per 1,000 tasks</dt>
                      <dd className="tnum">{cost?.thousand ?? '–'}</dd>
                    </div>
                    <div>
                      <dt>Speed</dt>
                      <dd className="tnum">{secondsWords(r.medianSec)}</dd>
                    </div>
                  </dl>
                  {r.mistakes[0] && (
                    <p className="bj-vs-mis">
                      Most common mistake: <b>{r.mistakes[0].text.replace(/^Serious: /, '')}</b>
                    </p>
                  )}
                </article>
              );
            })}
          </div>
          {paired && (
            <p className={cx('bj-paired', paired.verdict === 'tied' || paired.verdict === 'no-data' ? 'tied' : 'ahead')}>
              <b>On the same tasks:</b> {pairedWords(paired, modelOf(a.modelId).label, modelOf(b.modelId).label)}
              {paired.cases ? <span className="muted"> ({paired.cases} tasks both did)</span> : null}
            </p>
          )}
          {same ? (
            <>
              <div className="biz-section-head">
                <h2>The same task, two answers</h2>
                <p>
                  {view.tests[same.testId]?.name}, {caseLabel(same.testId, same.caseId).toLowerCase()}: the task where these two differ most
                  {same.a.score === same.b.score ? ' (they did equally well on every shared task)' : ''}.
                </p>
              </div>
              <div className="bj-two">
                <AnswerPanel model={modelOf(a.modelId)} answer={same.a} testId={same.testId} testName={view.tests[same.testId]?.name ?? same.testId} program={view.tests[same.testId]?.kind === 'program'} compact />
                <AnswerPanel model={modelOf(b.modelId)} answer={same.b} testId={same.testId} testName={view.tests[same.testId]?.name ?? same.testId} program={view.tests[same.testId]?.kind === 'program'} compact />
              </div>
            </>
          ) : (
            <p className="bj-empty">These two models have no task in common on this job yet.</p>
          )}
        </>
      )}
    </section>
  );
}
