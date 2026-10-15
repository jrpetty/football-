/**
 * "Why these scores?" — pick a model and see exactly how each category score and the Gauntlet Index were worked
 * out from its recorded test scores (src/core/score-explain.ts), plus a plain-English Random Baseline explainer.
 * Clicking a category cell or the Index in the leaderboard table opens this panel on that model and category.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import type { Leaderboard } from '../../types.ts';
import { explainScore, RANDOM_BASELINE_EXPLAINER, type CategoryLine, type TestLine } from '../../../../src/core/score-explain.ts';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { Link } from '../../router.tsx';
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

function TestRow({ t, color }: { t: TestLine; color: string }) {
  return (
    <li className={cx('why-test', t.status !== 'scored' && 'is-out')}>
      <div className="why-test-head">
        <Link to={`/tests/${encodeURIComponent(t.id)}`} className="why-test-name">
          {t.name}
        </Link>
        {t.weight !== 1 && <span className="badge">counts ×{t.weight}</span>}
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
      {t.summary && <p className="why-summary">“{t.summary}”</p>}
      {t.notes.map((n) => (
        <p key={n} className="why-note">
          <Icon.Info width={14} height={14} /> {n}
        </p>
      ))}
    </li>
  );
}

function CategoryBlock({ c, open, onToggle, baselineRow }: { c: CategoryLine; open: boolean; onToggle: () => void; baselineRow: boolean }) {
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
              <TestRow key={t.id} t={t} color={c.color} />
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
  const ref = useRef<HTMLElement>(null);

  useEffect(() => {
    if (!focus) return;
    setId(focus.contestantId);
    setOpen(focus.categoryId ? new Set([focus.categoryId]) : new Set());
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
            Pick a model to see exactly how each category score was worked out — which tests it is made of, what the model scored on each, and how they add up to the Gauntlet
            Index. Tip: click any score in the table above to jump here.
          </p>
        </div>
      </header>
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
              <CategoryBlock key={c.id} c={c} open={open.has(c.id)} onToggle={() => toggle(c.id)} baselineRow={ex.isBaseline} />
            ))}
          </div>
        </>
      )}
    </section>
  );
}
