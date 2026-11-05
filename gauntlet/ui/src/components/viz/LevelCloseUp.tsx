/**
 * The Level close-up: one level of a difficulty ladder told as three pictures, top to bottom (side by side on
 * wide screens): the question as the model got it, the model's answer drawn the same way, and the result with
 * where it went wrong. With no model chosen it shows the question, the answer key behind "Reveal the answer",
 * and every model's result at this level (pick one to see its answer).
 *
 * Data comes from the same API calls as the Result Inspector: the test definition and its rendered cases
 * (GET /api/tests/:id), then either a run's results (GET /api/runs/:id and /results/:key) or, without a run,
 * every model's climb (GET /api/ladders/:id) and the answers behind it (GET /api/score-answers).
 * Nothing is computed that the scorer did not record; when a picture cannot be drawn, the plain text stands.
 */
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import type { KeyboardEvent as RKeyboardEvent, ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { MOCK, api } from '../../api.ts';
import type { CaseResult, RunDetail } from '../../types.ts';
import { ladderRuleOf, ladderSpecOf } from '../../ladders.ts';
import { passesRung } from '../../../../src/core/ladders.ts';
import { closeUpKind, levelCaseId, levelVerdict, questionInput, type CloseUpKind } from '../../../../src/presenter/visuals/closeup.ts';
import type { LadderBoard } from '../../../../src/core/ladders.ts';
import { caseInputFor, useTestDetail } from './CaseVisualPanel.tsx';
import { ModelMonogram } from './ModelBadge.tsx';
import { cx } from '../ui.tsx';
import { Mark } from './closeup/parts.tsx';
import { GridBeats, IslandBeats } from './closeup/logicBeats.tsx';
import { PegBeats, SnakesBeats } from './closeup/puzzleBeats.tsx';
import { EngineBeats } from './closeup/engineBeats.tsx';
import { BusinessBeats } from './closeup/businessBeats.tsx';
import { HorizonBeats, TextBeats } from './closeup/otherBeats.tsx';
import type { KindProps } from './closeup/types.ts';
import { docVisualFor } from '../../business/docs/docVisuals.ts';
import '../../styles/viz.css';
import './closeup/closeup.css';

const BEATS: Record<CloseUpKind, (p: KindProps) => JSX.Element> = {
  grid: GridBeats,
  islanders: IslandBeats,
  pegs: PegBeats,
  snakes: SnakesBeats,
  engine: EngineBeats,
  business: BusinessBeats,
  sql: BusinessBeats,
  horizon: HorizonBeats,
  text: TextBeats,
};

interface Who {
  id: string;
  label: string;
  color?: string;
}

interface Attempt {
  runId: string;
  key: string;
  repeat: number;
  score: number | null;
  status: string;
}

interface LevelRow extends Who {
  scores: number[];
  attempts: Attempt[] | null;
}

// ───────────────────────────── data ─────────────────────────────

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
const resultCache = new Map<string, Promise<CaseResult>>();
function loadResult(runId: string, key: string): Promise<CaseResult> {
  const k = `${runId}::${key}`;
  let p = resultCache.get(k);
  if (!p) {
    p = api.result(runId, key);
    p.catch(() => resultCache.delete(k));
    resultCache.set(k, p);
  }
  return p;
}

const isBaseline = (id: string) => /(^|[-_.])(random|baseline)([-_.]|$)/i.test(id);

/** Every model's attempts at one level: from a run, or (no run) pooled across runs like the ladder view. */
function useLevelRows(testId: string, caseId: string, runKey: string | null): { rows: LevelRow[] | null; error: string | null } {
  const [state, setState] = useState<{ rows: LevelRow[] | null; error: string | null }>({ rows: null, error: null });
  useEffect(() => {
    let alive = true;
    setState({ rows: null, error: null });
    const done = (rows: LevelRow[]) => alive && setState({ rows, error: null });
    const fail = (e: unknown) => alive && setState({ rows: [], error: e instanceof Error ? e.message : String(e) });
    if (runKey) {
      loadRun(runKey)
        .then((run) => {
          const who = new Map(run.manifest.contestants.map((c) => [c.id, c]));
          const order = run.manifest.contestants.map((c) => c.id);
          const by = new Map<string, Attempt[]>();
          for (const r of run.results) {
            if (r.testId !== testId || r.caseId !== caseId) continue;
            by.set(r.contestantId, [...(by.get(r.contestantId) ?? []), { runId: runKey, key: r.key, repeat: r.repeat, score: r.score, status: r.status }]);
          }
          done(
            order
              .filter((id) => by.has(id))
              .map((id) => {
                const at = by.get(id)!.sort((a, b) => a.repeat - b.repeat);
                const c = who.get(id)!;
                return { id, label: c.label, color: c.color, attempts: at, scores: at.filter((a) => typeof a.score === 'number').map((a) => a.score as number) };
              }),
          );
        })
        .catch(fail);
    } else {
      const level = Number(caseId.slice(1));
      api
        .ladder(testId)
        .then((b: LadderBoard) => done(b.climbs.map((c) => ({ id: c.id, label: c.label, color: c.color, attempts: null, scores: c.climb.rungs[level - 1]?.scores ?? [] })).filter((r) => r.scores.length > 0)))
        .catch(fail);
    }
    return () => {
      alive = false;
    };
  }, [testId, caseId, runKey]);
  return state;
}

/** The selected model's attempts at the level (from the run, or every pooled answer). */
function useAttempts(testId: string, caseId: string, row: LevelRow | null, scope: string | undefined): Attempt[] | null {
  const [list, setList] = useState<Attempt[] | null>(null);
  useEffect(() => {
    if (!row) {
      setList(null);
      return;
    }
    if (row.attempts) {
      setList(row.attempts);
      return;
    }
    let alive = true;
    setList(null);
    api
      .scoreAnswers(scope ?? 'suite:all', row.id, testId)
      .then((xs) => alive && setList(xs.filter((x) => x.caseId === caseId).sort((a, b) => a.runId.localeCompare(b.runId) || a.repeat - b.repeat).map((x) => ({ runId: x.runId, key: x.key, repeat: x.repeat, score: x.score, status: x.status }))))
      .catch(() => alive && setList([]));
    return () => {
      alive = false;
    };
  }, [testId, caseId, row, scope]);
  return list;
}

function useResult(at: Attempt | null): { res: CaseResult | null; error: string | null } {
  const [s, setS] = useState<{ res: CaseResult | null; error: string | null }>({ res: null, error: null });
  useEffect(() => {
    if (!at) {
      setS({ res: null, error: null });
      return;
    }
    let alive = true;
    setS({ res: null, error: null });
    loadResult(at.runId, at.key)
      .then((res) => alive && setS({ res, error: null }))
      .catch((e: unknown) => alive && setS({ res: null, error: e instanceof Error ? e.message : String(e) }));
    return () => {
      alive = false;
    };
  }, [at]);
  return s;
}

// ───────────────────────────── dialog plumbing ─────────────────────────────

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
    // Capture phase: Esc closes only the close-up, never a drawer underneath it.
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (document.fullscreenElement) return;
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

// ───────────────────────────── pieces ─────────────────────────────

function rowState(scores: number[], passMark: number): { tone: 'pass' | 'fail' | 'partial' | 'some'; text: string; mark: string } {
  if (!scores.length) return { tone: 'fail', text: 'not run', mark: '–' };
  const passes = scores.filter((s) => passesRung(s, passMark)).length;
  const mean = scores.reduce((a, b) => a + b, 0) / scores.length;
  if (passes === scores.length) return { tone: 'pass', text: scores.length > 1 ? `${passes}/${scores.length}` : 'passed', mark: '✓' };
  if (passes > 0) return { tone: 'some', text: `${passes}/${scores.length}`, mark: '◐' };
  if (mean > 0 && passMark < 1) return { tone: 'partial', text: `${Math.round(mean * 100)}%`, mark: '◐' };
  return { tone: 'fail', text: scores.length > 1 ? `0/${scores.length}` : 'failed', mark: '✕' };
}

function ModelRow({ rows, selected, passMark, onPick, title }: { rows: LevelRow[]; selected: string | null; passMark: number; onPick: (id: string | null) => void; title: ReactNode }) {
  return (
    <div className="lc-models" role="group" aria-label="Every model at this level">
      <span className="lc-models-t">{title}</span>
      <div className="lc-models-list">
        {rows.map((r) => {
          const st = rowState(r.scores, passMark);
          return (
            <button
              key={r.id}
              type="button"
              className={cx('lc-token', `t-${st.tone}`, selected === r.id && 'sel')}
              aria-pressed={selected === r.id}
              onClick={() => onPick(r.id)}
              aria-label={`${r.label}: ${st.text} at this level${selected === r.id ? ' (shown)' : ''}`}
            >
              <ModelMonogram label={r.label} color={r.color} size="sm" baseline={isBaseline(r.id)} />
              <span className="lc-token-name">{r.label}</span>
              <span className="lc-token-mark" aria-hidden="true">
                <Mark tone={st.tone} /> {st.text}
              </span>
            </button>
          );
        })}
        {selected && (
          <button type="button" className="lc-token q" onClick={() => onPick(null)}>
            Question only
          </button>
        )}
      </div>
    </div>
  );
}

function AttemptTabs({ list, idx, onPick, passMark }: { list: Attempt[]; idx: number; onPick: (i: number) => void; passMark: number }) {
  if (list.length < 2) return null;
  return (
    <div className="lc-attempts" role="tablist" aria-label="Attempts">
      {list.map((a, i) => {
        const ok = typeof a.score === 'number' && passesRung(a.score, passMark);
        return (
          <button key={a.key} type="button" role="tab" aria-selected={i === idx} className={cx('lc-att', i === idx && 'sel', ok ? 'ok' : 'no')} onClick={() => onPick(i)}>
            Attempt {i + 1}{' '}
            <span aria-label={ok ? 'passed' : 'not passed'}>
              <Mark tone={ok ? 'pass' : typeof a.score === 'number' && a.score > 0 && passMark < 1 ? 'partial' : 'fail'} />
              {!ok && typeof a.score === 'number' && a.score > 0 && passMark < 1 ? `\u2009${Math.round(a.score * 100)}%` : ''}
            </span>
          </button>
        );
      })}
    </div>
  );
}

// ───────────────────────────── the close-up ─────────────────────────────

export function LevelCloseUp(props: { testId: string; level: number; contestantId?: string; runId?: string | null; scope?: string; onClose: () => void; mode?: 'panel' | 'slide' }): JSX.Element {
  const { testId, onClose, scope } = props;
  const mode = props.mode ?? 'panel';
  const spec = ladderSpecOf(testId);
  const rule = ladderRuleOf(testId);
  const levels = rule.levels ?? 10;
  const [level, setLevel] = useState(props.level);
  const [who, setWho] = useState<string | null>(props.contestantId ?? null);
  const [attemptIdx, setAttemptIdx] = useState(0);
  useEffect(() => setLevel(props.level), [props.level]);
  useEffect(() => setWho(props.contestantId ?? null), [props.contestantId]);
  useEffect(() => setAttemptIdx(0), [level, who]);

  const caseId = levelCaseId(level);
  const runKey = props.runId ?? (scope?.startsWith('run:') ? scope.slice(4) : null);
  const detail = useTestDetail(testId);
  const { rows, error: rowsError } = useLevelRows(testId, caseId, runKey);
  const row = useMemo(() => rows?.find((r) => r.id === who) ?? null, [rows, who]);
  const attempts = useAttempts(testId, caseId, row, scope);
  const attempt = attempts?.[Math.min(attemptIdx, attempts.length - 1)] ?? null;
  const { res, error: resError } = useResult(who ? attempt : null);

  const rendered = detail?.rendered.find((r) => r.caseId === caseId) ?? null;
  const def = detail?.definition;
  const scorerType = def?.kind === 'prompt' ? (def.cases.find((c) => c.id === caseId)?.scorer ?? def.scorer).type : detail?.summary.scorerType;
  const q = useMemo(() => questionInput(testId, caseId, rendered, { scorerType, category: def?.category }), [testId, caseId, rendered, scorerType, def?.category]);
  const a = useMemo(() => (res && detail !== undefined ? caseInputFor(res, detail, row?.label) : null), [res, detail, row?.label]);
  const verdict = res ? levelVerdict(res.score, rule.passMark, res.status) : null;
  const prompt = (a?.turns.length ? a.turns : rendered?.turns ?? []).join('\n\n— next message —\n\n');
  const reply = a ? a.replies.join('\n\n— next reply —\n\n') : null;

  const { ref, onKeyDown } = useDialog(onClose);
  const titleId = useId();
  const kind = closeUpKind(testId);
  const Beats = docVisualFor(testId)?.Beats ?? BEATS[kind];
  const rung = spec?.rungs?.[level - 1];
  const name = spec?.name ?? def?.name ?? testId;
  const questionOnly = !who;
  const loadingAnswer = !!who && (!attempts || (attempts.length > 0 && !res && !resError));

  let body: ReactNode;
  if (detail === undefined) body = <div className="lc-loading">Loading level {level}…</div>;
  else if (!q) body = <div className="lc-loading">This level’s question could not be loaded.</div>;
  else if (loadingAnswer) body = <div className="lc-loading">Loading {row?.label ?? 'the model'}’s answer…</div>;
  else if (who && attempts && attempts.length === 0) body = <div className="lc-loading">{row?.label ?? 'This model'} has no recorded answer at level {level}.</div>;
  else if (resError) body = <div className="lc-loading">Couldn’t load this answer: {resError}</div>;
  else
    body = (
      <Beats
        key={`${caseId}|${who ?? ''}|${attempt?.key ?? ''}`}
        q={q}
        a={questionOnly ? null : a}
        verdict={questionOnly ? null : verdict}
        passMark={rule.passMark}
        prompt={prompt}
        reply={reply}
        answerHead={attempts && who ? <AttemptTabs list={attempts} idx={Math.min(attemptIdx, attempts.length - 1)} onPick={setAttemptIdx} passMark={rule.passMark} /> : undefined}
        mode={mode}
        modelLabel={row?.label}
        rung={rung}
      />
    );

  const modelRow =
    rows && rows.length > 0 ? (
      <ModelRow rows={rows} selected={who} passMark={rule.passMark} onPick={setWho} title={questionOnly ? 'How each model did at this level — pick one to see its answer' : 'Switch model'} />
    ) : rowsError ? (
      <p className="lc-models lc-empty">Results could not be loaded: {rowsError}</p>
    ) : null;

  return createPortal(
    <div className={cx('lc-root', `mode-${mode}`)}>
      <div className="lc-backdrop" onClick={onClose} aria-hidden="true" />
      <div className="lc-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={ref} tabIndex={-1} onKeyDown={onKeyDown}>
        <header className="lc-head">
          <div className="lc-title">
            <span className="lc-eyebrow">{name}</span>
            <h2 id={titleId}>
              Level {level} <small>of {levels}</small>
              {rung && <span className="lc-rung">{rung}</span>}
            </h2>
          </div>
          {row && (
            <span className="lc-who">
              <ModelMonogram label={row.label} color={row.color} size="md" baseline={isBaseline(row.id)} />
              <b>{row.label}</b>
            </span>
          )}
          <nav className="lc-nav" aria-label="Levels">
            <button type="button" className="btn sm" disabled={level <= 1} onClick={() => setLevel((l) => Math.max(1, l - 1))} aria-label={level > 1 ? `Previous level, level ${level - 1}` : 'No previous level'}>
              <span aria-hidden="true">←</span> L{Math.max(1, level - 1)}
            </button>
            <span className="lc-nav-cur tnum" aria-hidden="true">
              L{level}
            </span>
            <button type="button" className="btn sm" disabled={level >= levels} onClick={() => setLevel((l) => Math.min(levels, l + 1))} aria-label={level < levels ? `Next level, level ${level + 1}` : 'No next level'}>
              L{Math.min(levels, level + 1)} <span aria-hidden="true">→</span>
            </button>
          </nav>
          <button type="button" className="btn ghost icon lc-close" onClick={onClose} aria-label="Close the level close-up" data-autofocus>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </header>
        {MOCK && (
          <div className="lc-demo">
            Demo data — not real results.<span className="lc-demo-more"> The questions and answer keys are the real ones; the models and their answers are invented.</span>
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
