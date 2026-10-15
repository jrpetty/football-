/**
 * "Why these scores?" — pick a model and see exactly how each category score and the Gauntlet Index were worked
 * out from its recorded test scores (src/core/score-explain.ts), plus a plain-English Random Baseline explainer.
 * Clicking a category cell or the Index in the leaderboard table opens this panel on that model and category.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import type { Leaderboard } from '../../types.ts';
import {
  explainScore,
  questionBreakdown,
  RANDOM_BASELINE_EXPLAINER,
  type CategoryLine,
  type QuestionBreakdown,
  type TestLine,
  type Verdict,
} from '../../../../src/core/score-explain.ts';
import { api } from '../../api.ts';
import { plainAttempt, plainCaseName } from '../clarity/plain.ts';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { href, Link } from '../../router.tsx';
import { isBaseline } from './util.ts';
import { RandomGuessLine } from '../viz/RandomGuessLine.tsx';
import './score-why.css';

export interface ScoreFocus {
  contestantId: string;
  categoryId: string | null;
  /** Bumped on every click so re-clicking the same cell scrolls again. */
  nonce: number;
}

const one = (v: number) => (Math.round(v * 10) / 10).toFixed(1);

function Bar({ score, color, baseline }: { score: number | null; color: string; baseline?: number | null }) {
  return (
    <div className="why-bar" aria-hidden="true">
      {typeof score === 'number' && <i style={{ width: `${Math.max(0, Math.min(100, score))}%`, background: color }} />}
      <RandomGuessLine pct={baseline} label={false} />
    </div>
  );
}

const VERDICT: Record<Verdict, { label: string; icon: string }> = {
  right: { label: 'Right', icon: '✓' },
  partly: { label: 'Partly right', icon: '◐' },
  wrong: { label: 'Wrong', icon: '✗' },
  out: { label: 'Not counted', icon: '–' },
};

/** Where a single answer opens: its run's page with the answer inspector on that exact answer. */
function answerHref(runId: string, testId: string, contestantId: string, key: string): string {
  return href(`/runs/${runId}`, { tab: 'leaderboard', test: testId, c: contestantId.replace(/~openrouter$/, ''), key });
}

const answersCache = new Map<string, Promise<QuestionBreakdown>>();

function QuestionList({ scope, contestantId, testId, testScore }: { scope: string; contestantId: string; testId: string; testScore: number | null }) {
  const [data, setData] = useState<QuestionBreakdown | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [only, setOnly] = useState<Verdict | 'all'>('all');
  useEffect(() => {
    const k = `${scope}|${contestantId}|${testId}`;
    let p = answersCache.get(k);
    if (!p) {
      p = api.scoreAnswers(scope, contestantId, testId).then(questionBreakdown);
      answersCache.set(k, p);
      p.catch(() => answersCache.delete(k));
    }
    let live = true;
    p.then((d) => live && setData(d)).catch((e: unknown) => live && setError(e instanceof Error ? e.message : String(e)));
    return () => {
      live = false;
    };
  }, [scope, contestantId, testId]);

  if (error) return <p className="why-note">Couldn’t load the answers: {error}</p>;
  if (!data) return <p className="muted why-loading">Loading every answer…</p>;
  if (data.questions.length === 0) return <p className="muted">No answers recorded for this test.</p>;
  const shown = data.questions.filter((q) => only === 'all' || q.verdict === only);
  const agrees = data.score === null || testScore === null || Math.abs(data.score - testScore) < 0.1;
  const counts: Array<[Verdict, number]> = [
    ['right', data.right],
    ['partly', data.partly],
    ['wrong', data.wrong],
    ['out', data.leftOut],
  ];
  return (
    <div className="why-qs">
      <div className="why-q-strip" role="img" aria-label={`${data.right} right, ${data.partly} partly right, ${data.wrong} wrong, ${data.leftOut} not counted`}>
        {data.questions.map((q) => (
          <i key={q.caseId} className={`v-${q.verdict}`} title={`${plainCaseName(q.caseId)}: ${q.score === null ? 'not counted' : one(q.score)}`} />
        ))}
      </div>
      <div className="why-q-filter chip-list" role="group" aria-label="Show">
        <button type="button" className={cx('toggle-chip', only === 'all' && 'on')} aria-pressed={only === 'all'} onClick={() => setOnly('all')}>
          All {data.questions.length}
        </button>
        {counts
          .filter(([, n]) => n > 0)
          .map(([v, n]) => (
            <button key={v} type="button" className={cx('toggle-chip', `v-${v}`, only === v && 'on')} aria-pressed={only === v} onClick={() => setOnly(only === v ? 'all' : v)}>
              <b aria-hidden="true">{VERDICT[v].icon}</b> {VERDICT[v].label} {n}
            </button>
          ))}
      </div>
      {data.formula && (
        <p className="why-formula">
          <span className="eyebrow">Test score</span> <code>{data.formula}</code>
          <span className="muted">— the average of the question scores{data.leftOut ? `; ${data.leftOut} not counted` : ''}</span>
        </p>
      )}
      {!agrees && <p className="why-note">These answers average {one(data.score!)}, not {one(testScore!)} — the leaderboard may have changed since this page loaded. Refresh to update.</p>}
      <ol className="why-q-list">
        {shown.map((q) => (
          <li key={q.caseId} className={`why-q v-${q.verdict}`}>
            <span className="why-q-badge" aria-label={VERDICT[q.verdict].label}>
              {VERDICT[q.verdict].icon}
            </span>
            <div className="why-q-main">
              <div className="why-q-head">
                <span className="why-q-name">{plainCaseName(q.caseId)}</span>
                <span className="why-q-verdict">{VERDICT[q.verdict].label}</span>
                <span className="why-q-score tnum">{q.score === null ? '—' : one(q.score)}</span>
              </div>
              {q.triesSum && <p className="why-q-tries">{q.triesSum}</p>}
              {q.tries.map((t) => (
                <div key={t.key} className="why-q-try">
                  <p>
                    {q.tries.length > 1 && <b>{plainAttempt(t.repeat)}: </b>}
                    {t.why}
                  </p>
                  <a className="why-open" href={answerHref(t.runId, testId, t.contestantId, t.key)}>
                    {q.tries.length > 1 ? `Open ${plainAttempt(t.repeat).toLowerCase()}` : 'Open the full answer'} <Icon.ChevronRight width={14} height={14} />
                  </a>
                </div>
              ))}
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}

function TestRow({ t, color, scope, contestantId, open, onToggle }: { t: TestLine; color: string; scope: string; contestantId: string; open: boolean; onToggle: () => void }) {
  const canOpen = t.status !== 'not-run';
  return (
    <li className={cx('why-test', t.status !== 'scored' && 'is-out', open && 'open')}>
      <div className="why-test-head">
        <span className="why-test-name">{t.name}</span>
        {t.weight !== 1 && <span className="badge">counts ×{t.weight}</span>}
        <Link to={`/tests/${encodeURIComponent(t.id)}`} className="why-about">
          What is this test?
        </Link>
        <span className="why-test-score tnum">{t.score === null ? (t.status === 'not-run' ? 'not run' : 'no score') : one(t.score)}</span>
      </div>
      <Bar score={t.score} color={color} baseline={t.baseline} />
      <div className="why-test-facts">
        {t.answers > 0 && (
          <span>
            {t.passed !== null ? `${t.passed} of ${t.answers} answers reached the pass mark` : `${t.answers} answer${t.answers === 1 ? '' : 's'} marked`}
            {' · '}each answer is marked 0–100; the test score is their average
          </span>
        )}
        {t.baseline !== null && <span>random guessing: {one(t.baseline)}</span>}
      </div>
      {t.summary && !open && <p className="why-summary">“{t.summary}”</p>}
      {t.notes.map((n) => (
        <p key={n} className="why-note">
          <Icon.Info width={14} height={14} /> {n}
        </p>
      ))}
      {canOpen && (
        <button type="button" className="why-q-toggle" aria-expanded={open} onClick={onToggle}>
          {open ? 'Hide the questions' : 'See every question and its score'}
          <Icon.ChevronDown width={16} height={16} className={cx('why-chev', open && 'up')} />
        </button>
      )}
      {open && <QuestionList scope={scope} contestantId={contestantId} testId={t.id} testScore={t.score} />}
    </li>
  );
}

function CategoryBlock({
  c,
  open,
  onToggle,
  baselineRow,
  scope,
  contestantId,
  openTests,
  onToggleTest,
}: {
  c: CategoryLine;
  open: boolean;
  onToggle: () => void;
  baselineRow: boolean;
  scope: string;
  contestantId: string;
  openTests: Set<string>;
  onToggleTest: (testId: string) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  return (
    <div ref={ref} className={cx('why-cat', open && 'open')} style={{ ['--cat' as string]: c.color } as CSSProperties} data-cat={c.id}>
      <button type="button" className="why-cat-head" aria-expanded={open} onClick={onToggle}>
        <span className="cat-dot" style={{ background: c.color }} aria-hidden="true" />
        <span className="why-cat-name">{c.name}</span>
        <span className="why-cat-score tnum">{c.score === null ? '—' : one(c.score)}</span>
        <Bar score={c.score} color={c.color} baseline={baselineRow ? null : c.baseline} />
        <Icon.ChevronDown width={16} height={16} className="why-chev" />
      </button>
      {open && (
        <div className="why-cat-body">
          <p className="why-headline">{c.headline}</p>
          {c.formula && (
            <p className="why-formula">
              <span className="eyebrow">The sum</span> <code>{c.formula}</code>
            </p>
          )}
          {c.points.length > 0 && (
            <ul className="why-points">
              {c.points.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          )}
          <ol className="why-tests">
            {c.tests.map((t) => (
              <TestRow key={t.id} t={t} color={c.color} scope={scope} contestantId={contestantId} open={openTests.has(t.id)} onToggle={() => onToggleTest(t.id)} />
            ))}
          </ol>
        </div>
      )}
    </div>
  );
}

/** Plain-English "what is the Random Baseline?" card, with its recorded score when there is one. */
export function BaselineExplainer({ score, defaultOpen = false }: { score?: number | null; defaultOpen?: boolean }) {
  return (
    <details className="baseline-explainer" open={defaultOpen}>
      <summary>
        <span className="be-chip">Random Baseline</span>
        <span className="be-short">
          <strong>What is it?</strong> {RANDOM_BASELINE_EXPLAINER.short}
          {typeof score === 'number' && (
            <>
              {' '}
              Here it scored <b className="tnum">{one(score)}</b>.
            </>
          )}
        </span>
        <span className="be-more">How it guesses</span>
      </summary>
      <div className="be-body">
        <div>
          <h4>How it answers</h4>
          <ul>
            {RANDOM_BASELINE_EXPLAINER.how.map((h) => (
              <li key={h}>{h}</li>
            ))}
          </ul>
        </div>
        <div>
          <h4>Why it’s there</h4>
          <ul>
            {RANDOM_BASELINE_EXPLAINER.why.map((h) => (
              <li key={h}>{h}</li>
            ))}
          </ul>
        </div>
      </div>
    </details>
  );
}

export function ScoreWhyPanel({ lb, focus }: { lb: Leaderboard; focus: ScoreFocus | null }) {
  const ordered = useMemo(() => [...lb.rows].sort((a, b) => Number(isBaseline(a)) - Number(isBaseline(b)) || (a.rank || 99) - (b.rank || 99)), [lb.rows]);
  const [id, setId] = useState<string>(() => ordered.find((r) => !isBaseline(r))?.contestantId ?? ordered[0]?.contestantId ?? '');
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [openTests, setOpenTests] = useState<Set<string>>(new Set());
  const ref = useRef<HTMLElement>(null);
  const scope = lb.scope.kind === 'run' ? `run:${lb.scope.runId}` : `suite:${lb.scope.suiteId}`;
  const toggleTest = (tid: string) =>
    setOpenTests((s) => {
      const n = new Set(s);
      if (n.has(tid)) n.delete(tid);
      else n.add(tid);
      return n;
    });

  useEffect(() => {
    if (!focus) return;
    setId(focus.contestantId);
    setOpen(focus.categoryId ? new Set([focus.categoryId]) : new Set());
    setOpenTests(new Set());
    requestAnimationFrame(() => {
      const target = focus.categoryId ? ref.current?.querySelector(`[data-cat="${CSS.escape(focus.categoryId)}"]`) : ref.current;
      (target ?? ref.current)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  }, [focus]);

  const ex = useMemo(() => explainScore(lb, id), [lb, id]);
  if (!lb.rows.length) return null;
  const row = lb.rows.find((r) => r.contestantId === id);
  const toggle = (cid: string) =>
    setOpen((s) => {
      const n = new Set(s);
      if (n.has(cid)) n.delete(cid);
      else n.add(cid);
      return n;
    });
  const allOpen = ex ? ex.categories.every((c) => open.has(c.id)) : false;

  return (
    <section className="card why-panel" ref={ref} aria-labelledby="why-title">
      <header className="why-top">
        <div>
          <h3 id="why-title">Why these scores?</h3>
          <p className="muted">
            Pick a model, open a category, then open a test to see every question: what the model answered, the mark it got and why. Each answer opens in full. Tip: click any
            score in the table above to jump straight here.
          </p>
        </div>
      </header>
      <ol className="why-ladder" aria-label="How a score is built">
        <li>
          <b>1</b>
          <span>
            <strong>Each answer</strong> is marked 0–100
          </span>
        </li>
        <li>
          <b>2</b>
          <span>
            <strong>Test score</strong> = average of its questions
          </span>
        </li>
        <li>
          <b>3</b>
          <span>
            <strong>Category score</strong> = average of its tests
          </span>
        </li>
        <li>
          <b>4</b>
          <span>
            <strong>Gauntlet Index</strong> = average of the categories
          </span>
        </li>
      </ol>
      <div className="chip-list why-models" role="tablist" aria-label="Model">
        {ordered.map((r) => (
          <button
            key={r.contestantId}
            type="button"
            role="tab"
            aria-selected={r.contestantId === id}
            className={cx('toggle-chip', r.contestantId === id && 'on', isBaseline(r) && 'is-baseline')}
            style={{ ['--c' as string]: r.color } as CSSProperties}
            onClick={() => {
              setId(r.contestantId);
              setOpen(new Set());
              setOpenTests(new Set());
            }}
          >
            <i className="why-dot" aria-hidden="true" />
            {isBaseline(r) ? 'Random Baseline (luck)' : r.label}
          </button>
        ))}
      </div>
      {ex && row && (
        <>
          <div className={cx('why-index', ex.isBaseline && 'is-baseline')} style={{ ['--c' as string]: row.color } as CSSProperties}>
            <div className="why-index-num">
              <span className="k">Gauntlet Index</span>
              <span className="v tnum">{ex.index === null ? '—' : one(ex.index)}</span>
              <span className="s">out of 100</span>
            </div>
            <div className="why-index-text">
              <p className="why-headline">{ex.headline}</p>
              {ex.indexFormula && !ex.isBaseline && (
                <p className="why-formula">
                  <span className="eyebrow">The sum</span> <code>{ex.indexFormula}</code>
                </p>
              )}
              {ex.points.length > 0 && (
                <ul className="why-points">
                  {ex.points.map((p) => (
                    <li key={p}>{p}</li>
                  ))}
                </ul>
              )}
            </div>
          </div>
          {ex.isBaseline && <BaselineExplainer score={ex.index} defaultOpen />}
          <div className="why-cats-head">
            <span className="eyebrow">Category by category</span>
            <span className="muted">Each score is the average of that category’s tests. The dashed line on each bar is what random guessing scored.</span>
            <span className="spacer" />
            <button type="button" className="btn ghost sm" onClick={() => setOpen(allOpen ? new Set() : new Set(ex.categories.map((c) => c.id)))}>
              {allOpen ? 'Close all' : 'Open all'}
            </button>
          </div>
          <div className="why-cats">
            {ex.categories.map((c) => (
              <CategoryBlock
                key={c.id}
                c={c}
                open={open.has(c.id)}
                onToggle={() => toggle(c.id)}
                baselineRow={ex.isBaseline}
                scope={scope}
                contestantId={ex.contestantId}
                openTests={openTests}
                onToggleTest={toggleTest}
              />
            ))}
          </div>
        </>
      )}
    </section>
  );
}
