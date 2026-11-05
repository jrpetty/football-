/**
 * The Case close-up: one case of a Business test (not a ladder) as three pictures — the paperwork the model was
 * handed, its decisions drawn on that paperwork, and the result — exactly like the Level close-up does for a ladder
 * level. Opened from a Business test's card ("See an example"), from each case on the test page ("See this case")
 * and deep-linkable (?case=c03&c=<model>&run=<run id>).
 *
 * Data comes from the same API calls as the Result Inspector: the test with its rendered cases, a run's results
 * and one full result. Without a run id it uses the newest run that has this test. Nothing is computed that the
 * scorer did not record; when the documents cannot be drawn the close-up says so.
 */
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import type { KeyboardEvent as RKeyboardEvent, ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { MOCK, api } from '../../api.ts';
import type { CaseResult, RunDetail } from '../../types.ts';
import { questionInput } from '../../../../src/presenter/visuals/closeup.ts';
import { caseInputFor, useTestDetail } from '../../components/viz/CaseVisualPanel.tsx';
import { ModelMonogram } from '../../components/viz/ModelBadge.tsx';
import { Mark } from '../../components/viz/closeup/parts.tsx';
import { cx } from '../../components/ui.tsx';
import { navigate, useRoute } from '../../router.tsx';
import { caseVerdict } from './customers-data/kit.ts';
import { docVisualFor } from './docVisuals.ts';
import '../../components/viz/closeup/closeup.css';
import './customers-data/docs.css';

interface Row {
  id: string;
  label: string;
  color?: string;
  attempts: Array<{ key: string; repeat: number; score: number | null; status: string }>;
}

const runCache = new Map<string, Promise<RunDetail>>();
function loadRun(id: string): Promise<RunDetail> {
  let p = runCache.get(id);
  if (!p) {
    p = api.run(id);
    p.catch(() => runCache.delete(id));
    runCache.set(id, p);
  }
  return p;
}

const latestCache = new Map<string, Promise<string | null>>();
/** The newest run with results for this test (null: none). */
function latestRunWith(testId: string): Promise<string | null> {
  let p = latestCache.get(testId);
  if (!p) {
    p = api.runs().then(async (runs) => {
      const sorted = [...runs].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 12);
      for (const r of sorted) {
        try {
          const d = await loadRun(r.id);
          if (d.results.some((x) => x.testId === testId && x.status === 'ok')) return r.id;
        } catch {
          /* try the next run */
        }
      }
      return null;
    });
    p.catch(() => latestCache.delete(testId));
    latestCache.set(testId, p);
  }
  return p;
}

const isBaseline = (id: string) => /(^|[-_.])(random|baseline)([-_.]|$)/i.test(id);

/** The case whose scores differ most between models (the most telling one to show). */
export function interestingCase(results: Array<{ caseId: string; contestantId: string; score: number | null; status: string }>, caseIds: string[]): string | null {
  let best: { id: string; spread: number; mean: number } | null = null;
  for (const id of caseIds) {
    const xs = results.filter((r) => r.caseId === id && r.status === 'ok' && typeof r.score === 'number' && !isBaseline(r.contestantId)).map((r) => r.score as number);
    if (!xs.length) continue;
    const spread = Math.max(...xs) - Math.min(...xs);
    const mean = xs.reduce((a, b) => a + b, 0) / xs.length;
    if (!best || spread > best.spread + 1e-9 || (Math.abs(spread - best.spread) < 1e-9 && mean < best.mean)) best = { id, spread, mean };
  }
  return best?.id ?? null;
}

/** The model to show first: one with some right and some wrong (the lowest score above zero), else the first. */
export function interestingModel(rows: Array<{ id: string; attempts: Array<{ score: number | null }> }>): string | null {
  const mean = (r: (typeof rows)[number]) => {
    const xs = r.attempts.map((a) => a.score).filter((s): s is number => typeof s === 'number');
    return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null;
  };
  const real = rows.filter((r) => !isBaseline(r.id));
  const mixed = real.filter((r) => {
    const m = mean(r);
    return m !== null && m > 0 && m < 0.9995;
  });
  const pool = mixed.length ? mixed : real;
  return [...pool].sort((a, b) => (mean(a) ?? 2) - (mean(b) ?? 2))[0]?.id ?? rows[0]?.id ?? null;
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

function useDialog(onClose: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    ref.current?.focus({ preventScroll: true });
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || document.fullscreenElement) return;
      e.stopImmediatePropagation();
      e.preventDefault();
      close.current();
    };
    window.addEventListener('keydown', onKey, true);
    return () => {
      window.removeEventListener('keydown', onKey, true);
      document.body.style.overflow = overflow;
      prev?.focus?.({ preventScroll: true });
    };
  }, []);
  const onKeyDown = useCallback((e: RKeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'Tab' || !ref.current) return;
    const items = [...ref.current.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => el.offsetParent !== null || el === document.activeElement);
    if (!items.length) return;
    const first = items[0]!;
    const last = items[items.length - 1]!;
    if (e.shiftKey && (document.activeElement === first || document.activeElement === ref.current)) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }, []);
  return { ref, onKeyDown };
}

function tokenState(scores: number[]): { tone: 'pass' | 'fail' | 'partial'; text: string } {
  if (!scores.length) return { tone: 'fail', text: 'not run' };
  const mean = scores.reduce((a, b) => a + b, 0) / scores.length;
  if (mean >= 0.9995) return { tone: 'pass', text: '100' };
  if (mean <= 0) return { tone: 'fail', text: '0' };
  return { tone: 'partial', text: String(Math.round(mean * 100)) };
}

export function CaseCloseUp(props: { testId: string; caseId?: string | null; contestantId?: string | null; runId?: string | null; onClose: () => void; mode?: 'panel' | 'slide' }): JSX.Element {
  const { testId, onClose } = props;
  const mode = props.mode ?? 'panel';
  const detail = useTestDetail(testId);
  const [runId, setRunId] = useState<string | null | undefined>(props.runId ?? undefined);
  const [run, setRun] = useState<RunDetail | null>(null);
  const [caseId, setCaseId] = useState<string | null>(props.caseId ?? null);
  const [who, setWho] = useState<string | null>(props.contestantId ?? null);
  const [attemptIdx, setAttemptIdx] = useState(0);
  const [res, setRes] = useState<{ r: CaseResult | null; error: string | null }>({ r: null, error: null });
  const autoPick = useRef(!props.contestantId);

  useEffect(() => {
    if (props.runId) return setRunId(props.runId);
    let alive = true;
    latestRunWith(testId)
      .then((id) => alive && setRunId(id))
      .catch(() => alive && setRunId(null));
    return () => {
      alive = false;
    };
  }, [testId, props.runId]);
  useEffect(() => {
    if (!runId) return;
    let alive = true;
    loadRun(runId)
      .then((d) => alive && setRun(d))
      .catch(() => alive && setRun(null));
    return () => {
      alive = false;
    };
  }, [runId]);

  const caseIds = useMemo(() => detail?.rendered.map((r) => r.caseId) ?? [], [detail]);
  const results = useMemo(() => run?.results.filter((r) => r.testId === testId) ?? [], [run, testId]);
  useEffect(() => {
    if (caseId || !caseIds.length || runId === undefined) return;
    setCaseId(interestingCase(results, caseIds) ?? caseIds[0]!);
  }, [caseId, caseIds, results, runId]);

  const rows: Row[] = useMemo(() => {
    if (!run || !caseId) return [];
    const order = run.manifest.contestants;
    return order
      .map((c) => ({ id: c.id, label: c.label, color: c.color, attempts: results.filter((r) => r.contestantId === c.id && r.caseId === caseId).sort((a, b) => a.repeat - b.repeat).map((r) => ({ key: r.key, repeat: r.repeat, score: r.score, status: r.status })) }))
      .filter((r) => r.attempts.length > 0);
  }, [run, caseId, results]);
  useEffect(() => {
    if (!autoPick.current || !rows.length) return;
    autoPick.current = false;
    setWho(interestingModel(rows));
  }, [rows]);
  useEffect(() => setAttemptIdx(0), [caseId, who]);

  const row = rows.find((r) => r.id === who) ?? null;
  const attempt = row?.attempts[Math.min(attemptIdx, row.attempts.length - 1)] ?? null;
  useEffect(() => {
    if (!attempt || !runId) return setRes({ r: null, error: null });
    let alive = true;
    setRes({ r: null, error: null });
    api
      .result(runId, attempt.key)
      .then((r) => alive && setRes({ r, error: null }))
      .catch((e: unknown) => alive && setRes({ r: null, error: e instanceof Error ? e.message : String(e) }));
    return () => {
      alive = false;
    };
  }, [attempt, runId]);

  const rendered = detail?.rendered.find((r) => r.caseId === caseId) ?? null;
  const def = detail?.definition;
  const scorerType = def?.kind === 'prompt' ? (def.cases.find((c) => c.id === caseId)?.scorer ?? def.scorer).type : detail?.summary.scorerType;
  const q = useMemo(() => (caseId ? questionInput(testId, caseId, rendered, { scorerType, category: def?.category }) : null), [testId, caseId, rendered, scorerType, def?.category]);
  const a = useMemo(() => (res.r && detail !== undefined ? caseInputFor(res.r, detail, row?.label) : null), [res.r, detail, row?.label]);
  const verdict = res.r ? caseVerdict(res.r.score, res.r.status) : null;
  const prompt = (a?.turns.length ? a.turns : rendered?.turns ?? []).join('\n\n— next message —\n\n');
  const reply = a ? a.replies.join('\n\n— next reply —\n\n') : null;

  const { ref, onKeyDown } = useDialog(onClose);
  const titleId = useId();
  const visual = docVisualFor(testId);
  const name = def?.name ?? testId;
  const idx = caseId ? caseIds.indexOf(caseId) : -1;
  const questionOnly = !who;
  const loadingAnswer = !!who && !!attempt && !res.r && !res.error;

  let body: ReactNode;
  if (detail === undefined || (caseId === null && runId === undefined)) body = <div className="lc-loading">Loading…</div>;
  else if (!visual) body = <div className="lc-loading">This test has no document picture yet.</div>;
  else if (!q) body = <div className="lc-loading">This case could not be loaded.</div>;
  else if (loadingAnswer) body = <div className="lc-loading">Loading {row?.label ?? 'the model'}’s answer…</div>;
  else if (res.error) body = <div className="lc-loading">Couldn’t load this answer: {res.error}</div>;
  else
    body = (
      <visual.Beats
        key={`${caseId}|${who ?? ''}|${attempt?.key ?? ''}`}
        q={q}
        a={questionOnly ? null : a}
        verdict={questionOnly ? null : verdict}
        passMark={1}
        prompt={prompt}
        reply={reply}
        answerHead={
          row && row.attempts.length > 1 ? (
            <div className="lc-attempts" role="tablist" aria-label="Attempts">
              {row.attempts.map((x, i) => (
                <button key={x.key} type="button" role="tab" aria-selected={i === attemptIdx} className={cx('lc-att', i === attemptIdx && 'sel', (x.score ?? 0) >= 0.9995 ? 'ok' : 'no')} onClick={() => setAttemptIdx(i)}>
                  Attempt {i + 1} <span>{typeof x.score === 'number' ? Math.round(x.score * 100) : '–'}</span>
                </button>
              ))}
            </div>
          ) : undefined
        }
        mode={mode}
        modelLabel={row?.label}
      />
    );

  const modelRow = rows.length ? (
    <div className="lc-models" role="group" aria-label="Every model on this case">
      <span className="lc-models-t">{questionOnly ? 'How each model did on this case — pick one to see its answer' : 'Switch model'}</span>
      <div className="lc-models-list">
        {rows.map((r) => {
          const st = tokenState(r.attempts.map((x) => x.score).filter((s): s is number => typeof s === 'number'));
          return (
            <button key={r.id} type="button" className={cx('lc-token', `t-${st.tone}`, who === r.id && 'sel')} aria-pressed={who === r.id} onClick={() => setWho(r.id)} aria-label={`${r.label}: ${st.text} of 100 on this case${who === r.id ? ' (shown)' : ''}`}>
              <ModelMonogram label={r.label} color={r.color} size="sm" baseline={isBaseline(r.id)} />
              <span className="lc-token-name">{r.label}</span>
              <span className="lc-token-mark" aria-hidden="true">
                <Mark tone={st.tone} /> {st.text}
              </span>
            </button>
          );
        })}
        {who && (
          <button type="button" className="lc-token q" onClick={() => setWho(null)}>
            Question only
          </button>
        )}
      </div>
    </div>
  ) : runId === null ? (
    <p className="lc-models lc-empty">No run has answers for this test yet: here is the question and its answer key.</p>
  ) : null;

  const go = (d: number) => {
    const next = caseIds[idx + d];
    if (next) setCaseId(next);
  };

  return createPortal(
    <div className={cx('lc-root', `mode-${mode}`)}>
      <div className="lc-backdrop" onClick={onClose} aria-hidden="true" />
      <div className="lc-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={ref} tabIndex={-1} onKeyDown={onKeyDown}>
        <header className="lc-head">
          <div className="lc-title">
            <span className="lc-eyebrow">{name}</span>
            <h2 id={titleId}>
              Case {idx >= 0 ? idx + 1 : '…'} <small>of {caseIds.length || '…'}</small>
              {caseId && <span className="lc-rung">{caseId}</span>}
            </h2>
          </div>
          {row && (
            <span className="lc-who">
              <ModelMonogram label={row.label} color={row.color} size="md" baseline={isBaseline(row.id)} />
              <b>{row.label}</b>
            </span>
          )}
          <nav className="lc-nav" aria-label="Cases">
            <button type="button" className="btn sm" disabled={idx <= 0} onClick={() => go(-1)} aria-label="Previous case">
              <span aria-hidden="true">←</span> Previous
            </button>
            <button type="button" className="btn sm" disabled={idx < 0 || idx >= caseIds.length - 1} onClick={() => go(1)} aria-label="Next case">
              Next <span aria-hidden="true">→</span>
            </button>
          </nav>
          <button type="button" className="btn ghost icon lc-close" onClick={onClose} aria-label="Close the case close-up" data-autofocus>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </header>
        {MOCK && (
          <div className="lc-demo">
            Demo data — not real results.<span className="lc-demo-more"> The documents and answer keys are the real ones; the models and their answers are invented.</span>
          </div>
        )}
        {!questionOnly && modelRow}
        <div className="lc-body">{body}</div>
        {questionOnly && modelRow}
      </div>
    </div>,
    document.body,
  );
}

/** Query keys of the deep link: ?case=<id>&c=<model>&run=<run>. */
function useCaseQuery() {
  const route = useRoute();
  const set = (testId: string | null, caseId: string | null) => {
    const q: Record<string, string | null> = {};
    for (const [k, v] of route.query) q[k] = v;
    q.example = testId;
    q.case = caseId;
    if (testId === null) {
      q.c = null;
      q.run = null;
    }
    navigate(route.path, q, true);
  };
  return { route, set };
}

/** "See an example" on a Business test card: opens the close-up on the most telling case of the newest run. */
export function CaseExampleButton({ testId, className }: { testId: string; className?: string }) {
  const { route, set } = useCaseQuery();
  if (!docVisualFor(testId)) return null;
  const open = route.query.get('example') === testId;
  return (
    <>
      <button type="button" className={cx('btn sm', className)} onClick={() => set(testId, null)}>
        See an example
      </button>
      {open && <CaseCloseUp testId={testId} caseId={route.query.get('case')} contestantId={route.query.get('c')} runId={route.query.get('run')} onClose={() => set(null, null)} mode={route.query.get('slide') === '1' ? 'slide' : 'panel'} />}
    </>
  );
}

/** "See a case up close" on a test page: one button per case. */
export function CaseCloseUpLauncher({ testId, caseIds }: { testId: string; caseIds: string[] }) {
  const { route, set } = useCaseQuery();
  if (!docVisualFor(testId) || !caseIds.length) return null;
  const open = route.query.get('example') === testId;
  return (
    <section className="card lc-launch" aria-label="See one case up close">
      <div className="lc-launch-t">
        <b>See a case up close</b>
        <span className="muted">The documents as the model got them, its decisions drawn on them, and what each mistake would have cost.</span>
      </div>
      <div className="lc-launch-levels">
        {caseIds.map((id, i) => (
          <button key={id} type="button" className="btn sm" onClick={() => set(testId, id)} title={id}>
            Case {i + 1}
          </button>
        ))}
      </div>
      {open && <CaseCloseUp testId={testId} caseId={route.query.get('case')} contestantId={route.query.get('c')} runId={route.query.get('run')} onClose={() => set(null, null)} mode={route.query.get('slide') === '1' ? 'slide' : 'panel'} />}
    </section>
  );
}
