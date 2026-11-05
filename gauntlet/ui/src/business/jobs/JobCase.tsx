/**
 * "See it do this job": one real case from the job's tests, as the model answered it. Ladder tests open the Level
 * close-up (the question, the answer and the result, drawn). Every other test opens a dialog with the same answer
 * drawn by the case-visual registry (CaseVisualPanel: documents, stamps and marks once a test has its own
 * renderer), the plain mistakes the scorer recorded, and the full prompt and raw reply behind "Show".
 * The compare mode puts two of these answer panels side by side for the same case.
 */
import { useEffect, useMemo, useState } from 'react';
import configJson from '../../../../config/business-jobs.json';
import { api } from '../../api.ts';
import type { CaseResult } from '../../types.ts';
import { Modal, cx } from '../../components/ui.tsx';
import { CaseVisualPanel } from '../../components/viz/CaseVisualPanel.tsx';
import { LevelCloseUp } from '../../components/viz/LevelCloseUp.tsx';
import { ModelMonogram } from '../../components/viz/ModelBadge.tsx';
import { ladderSpecOf } from '../../ladders.ts';
import { classifyMistakes, type BusinessJobsConfig, type JobAnswer, type JobModelInfo } from '../../../../src/core/business-jobs.ts';

const MISTAKE_RULES = (configJson as unknown as BusinessJobsConfig).mistakes;
import { pct } from './parts.tsx';

const cache = new Map<string, Promise<CaseResult>>();
export function useCaseResult(runId: string | null, key: string | null): { res: CaseResult | null; error: string | null } {
  const [s, setS] = useState<{ res: CaseResult | null; error: string | null }>({ res: null, error: null });
  useEffect(() => {
    if (!runId || !key) return;
    let alive = true;
    setS({ res: null, error: null });
    const k = `${runId}::${key}`;
    let p = cache.get(k);
    if (!p) {
      p = api.result(runId, key);
      p.catch(() => cache.delete(k));
      cache.set(k, p);
    }
    p.then((res) => alive && setS({ res, error: null })).catch((e: unknown) => alive && setS({ res: null, error: e instanceof Error ? e.message : String(e) }));
    return () => {
      alive = false;
    };
  }, [runId, key]);
  return s;
}

/** The ladder level of a ladder case id ("L06" → 6), or null for any other test. */
export function ladderLevelOf(testId: string, caseId: string): number | null {
  const spec = ladderSpecOf(testId);
  const m = caseId.match(/^L(\d{2})$/);
  return spec && !spec.planned && m ? Number(m[1]) : null;
}

/** Pretty case label: "Level 6", "Case 4", "Week 2" (simulation seeds). */
export function caseLabel(testId: string, caseId: string): string {
  const lv = ladderLevelOf(testId, caseId);
  if (lv) return `Level ${lv}`;
  const c = caseId.match(/^c(\d+)$/i);
  if (c) return `Case ${Number(c[1])}`;
  if (/^seed-/.test(caseId)) return 'One simulated run';
  return caseId;
}

function lastReply(res: CaseResult): string {
  const t = res.transcript.filter((x) => !x.judge);
  return t.map((x) => x.response).join('\n\n— next reply —\n\n');
}
function promptOf(res: CaseResult): string {
  const first = res.transcript.find((x) => !x.judge);
  if (!first) return '';
  return [first.system ? `System:\n${first.system}` : '', ...first.messages.map((m) => (typeof m.content === 'string' ? m.content : ''))].filter(Boolean).join('\n\n');
}

/** One model's answer to one case: verdict line, what went wrong here, the drawn answer, and the raw text behind "Show". */
export function AnswerPanel({ model, answer, testName, testId, program, compact }: { model: JobModelInfo; answer: JobAnswer; testName: string; testId: string; program?: boolean; compact?: boolean }) {
  const { res, error } = useCaseResult(answer.runId, answer.key);
  const right = answer.score >= 1;
  // What went wrong, read from the answer as loaded (the same marks the picture below is drawn from).
  const all = useMemo(() => (res ? classifyMistakes(testId, res.scoreDetail, MISTAKE_RULES, !!program) : answer.mistakes), [res, testId, program, answer.mistakes]);
  const mistakes = [...new Set(all)];
  return (
    <section className={cx('bj-answer', right ? 'ok' : 'no', compact && 'compact')} aria-label={`${model.label}’s answer`}>
      <header className="bj-answer-head">
        <ModelMonogram label={model.label} color={model.color} size="md" />
        <div>
          <b>{model.label}</b>
          <span className="muted">
            {testName} · {caseLabel(testId, answer.caseId)}
          </span>
        </div>
        <span className={cx('bj-verdict', right ? 'ok' : 'no')}>{right ? '✓ Got it right' : `✕ ${pct(answer.score)} right`}</span>
      </header>
      {!right && mistakes.length > 0 && (
        <div className="bj-wrong">
          <b>What went wrong here</b>
          <ul>
            {mistakes.slice(0, 5).map((m) => (
              <li key={m}>
                It {m.replace(/^Serious: /, '')}
                {all.filter((x) => x === m).length > 1 && <span className="muted"> ×{all.filter((x) => x === m).length}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
      {!right && !mistakes.length && <p className="bj-wrong muted">The marker did not record which part was wrong for this answer; the full marks are below.</p>}
      <div className="bj-answer-visual">
        {error ? <p className="muted">This answer couldn’t be loaded: {error}</p> : !res ? <p className="muted">Loading the answer…</p> : <CaseVisualPanel res={res} mode="inspector" modelLabel={model.label} />}
      </div>
      {res && (
        <div className="bj-raw">
          <details>
            <summary>Show the full prompt</summary>
            <pre>{promptOf(res) || 'No prompt recorded.'}</pre>
          </details>
          <details>
            <summary>Show the raw reply</summary>
            <pre>{lastReply(res) || 'No reply recorded.'}</pre>
          </details>
        </div>
      )}
    </section>
  );
}

/** The dialog behind "See it do this job". */
export function JobCaseDialog({ jobName, model, testId, testName, program, answer, note, onClose }: { jobName: string; model: JobModelInfo; testId: string; testName: string; program?: boolean; answer: JobAnswer; note: string; onClose: () => void }) {
  const level = ladderLevelOf(testId, answer.caseId);
  if (level) return <LevelCloseUp testId={testId} level={level} contestantId={model.id} runId={answer.runId} onClose={onClose} />;
  return (
    <Modal
      open
      onClose={onClose}
      width={1180}
      title={
        <span className="bj-dlg-title">
          <span className="biz-eyebrow">{jobName}</span>
          {model.label} does one task
        </span>
      }
      foot={
        <button type="button" className="btn" onClick={onClose} data-autofocus>
          Close
        </button>
      }
    >
      <p className={cx('bj-note', answer.score >= 1 ? 'ok' : 'no')}>{answer.score >= 1 ? note : 'A task it got wrong, from Gauntlet’s tests. Here is exactly what it did.'}</p>
      <AnswerPanel model={model} answer={answer} testName={testName} testId={testId} program={program} />
    </Modal>
  );
}
