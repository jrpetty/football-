/**
 * Building blocks of the Business area: the five-level staircase, a scenario's mini ladder, the full
 * scenario ladder for one model or 2–4 side by side, score-with-range bars, and the "How objective is this?"
 * panel. Every number comes from GET /api/business-ladder (src/core/business-ladder.ts); nothing is typed in.
 */
import type { CSSProperties, ReactNode } from 'react';
import { ExplainIcon } from '../components/viz/ExplainIcon.tsx';
import { Link, pathOf } from '../router.tsx';
import { cx } from '../components/ui.tsx';
import { pairedWords } from '../../../src/core/paired.ts';
import type { BusinessLadderView, LadderLevel, LadderLevelResult, LadderModelView, LadderScenario, LadderScenarioResult, PairedResult } from './businessApi.ts';

export const pts = (x: number | null | undefined): string => (x === null || x === undefined ? '—' : String(Math.round(x * 100)));

export function scenarioStyle(s: LadderScenario): CSSProperties {
  return { ['--sc' as string]: s.color } as CSSProperties;
}

export function ScenarioGlyph({ scenario, size = 'md' }: { scenario: LadderScenario; size?: 'sm' | 'md' | 'lg' }) {
  return (
    <span className={cx('biz-glyph', `biz-glyph-${size}`)} style={scenarioStyle(scenario)} aria-hidden="true">
      <ExplainIcon name={scenario.icon} />
    </span>
  );
}

/** "Level 1 · Assist" */
export function LevelTag({ level, name, muted }: { level: number; name?: string; muted?: boolean }) {
  return (
    <span className={cx('biz-level-tag', muted && 'muted')}>
      <b>L{level}</b>
      {name && <span>{name}</span>}
    </span>
  );
}

/** A 0–100 track with the pass line at 70, the 95% range as a band and the score as a dot. */
export function ScoreRange({ score, ci, color, pass = 70, label, compact }: { score: number | null; ci: [number, number] | null; color?: string; pass?: number; label?: ReactNode; compact?: boolean }) {
  if (score === null) return <span className="biz-score none">not run</span>;
  const lo = ci ? ci[0] * 100 : score * 100;
  const hi = ci ? ci[1] * 100 : score * 100;
  const passed = score * 100 >= pass;
  return (
    <span className={cx('biz-score', compact && 'compact', passed ? 'pass' : 'fail')} style={color ? ({ ['--mc' as string]: color } as CSSProperties) : undefined} title={`${pts(score)} out of 100 · 95% range ${Math.round(lo)}–${Math.round(hi)} · pass mark ${pass}`}>
      {label && <span className="lbl">{label}</span>}
      <span className="num">{pts(score)}</span>
      <span className="track" aria-hidden="true">
        <span className="band" style={{ left: `${lo}%`, width: `${Math.max(1, hi - lo)}%` }} />
        <span className="passline" style={{ left: `${pass}%` }} />
        <span className="dot" style={{ left: `${score * 100}%` }} />
      </span>
      <span className="rng">
        {Math.round(lo)}–{Math.round(hi)}
      </span>
    </span>
  );
}

/** The five levels as a rising staircase (level 1 lowest). */
export function LevelsStaircase({ levels, compact }: { levels: LadderLevel[]; compact?: boolean }) {
  return (
    <ol className={cx('biz-stairs', compact && 'compact')} aria-label="The five levels">
      {levels.map((l) => (
        <li key={l.level} className="step" style={{ ['--i' as string]: l.level } as CSSProperties}>
          <div className="step-card">
            <div className="step-num">{l.level}</div>
            <div className="step-name">{l.name}</div>
            <p className="step-what">{l.what}</p>
            <p className="step-check">
              <ExplainIcon name="eye" />
              {l.check}
            </p>
          </div>
        </li>
      ))}
    </ol>
  );
}

/** Five rungs for a scenario: built ones solid (with how many tests), planned ones dashed "coming soon". */
export function MiniLadder({ scenario, view, height }: { scenario: LadderScenario; view: BusinessLadderView; height?: number }) {
  return (
    <div className="biz-mini" style={scenarioStyle(scenario)} aria-label={`${scenario.name}: levels built`}>
      {[5, 4, 3, 2, 1].map((lv) => {
        const refs = scenario.tests.filter((t) => t.level === lv);
        const built = refs.filter((t) => view.tests[t.id]?.exists);
        const name = view.definition.levels.find((l) => l.level === lv)?.name ?? `Level ${lv}`;
        return (
          <div key={lv} className={cx('rung', built.length ? 'built' : 'soon', height !== undefined && lv <= height && 'reached')}>
            <span className="lv">L{lv}</span>
            <span className="nm">{name}</span>
            <span className="st">{built.length ? `${built.length} test${built.length === 1 ? '' : 's'}` : refs.length ? 'coming soon' : 'not planned yet'}</span>
          </div>
        );
      })}
    </div>
  );
}

export function heightWords(r: LadderScenarioResult, levels: LadderLevel[]): { title: string; note: string | null } {
  const name = (n: number) => levels.find((l) => l.level === n)?.name ?? '';
  const title = r.height > 0 ? `Reached Level ${r.height} · ${name(r.height)}` : r.stoppedBecause === 'not-run' || r.stoppedBecause === 'partial' ? 'Not run yet' : 'Not yet at Level 1';
  let note: string | null = null;
  if (r.stoppedBecause === 'partial') note = `Level ${r.stoppedAt} is only partly run`;
  else if (r.stoppedBecause === 'not-run' && r.height > 0) note = `Level ${r.stoppedAt} not run yet`;
  else if (r.justShort) note = `Just short of Level ${r.stoppedAt} (within the noise of 70)`;
  else if (r.borderline) note = 'Borderline: its range dips under 70';
  else if (r.height > 0 && r.height >= r.topBuilt) note = r.topBuilt < 5 ? `Top of what is built (Level ${r.topBuilt})` : 'The top of the ladder';
  return { title, note };
}

function statusChip(l: LadderLevelResult): ReactNode {
  switch (l.status) {
    case 'coming-soon':
      return <span className="biz-chip soon">Coming soon</span>;
    case 'not-run':
      return <span className="biz-chip muted">Not run</span>;
    case 'partial':
      return <span className="biz-chip muted">Partly run</span>;
    case 'passed':
      return <span className={cx('biz-chip', l.borderline ? 'warn' : 'good')}>{l.borderline ? 'Passed · borderline' : 'Passed'}</span>;
    case 'failed':
      return <span className={cx('biz-chip', l.justShort ? 'warn' : 'bad')}>{l.justShort ? 'Just short' : 'Below 70'}</span>;
  }
}

function verdictClass(r: PairedResult | null): string {
  return !r ? 'none' : r.verdict === 'a-ahead' ? 'a' : r.verdict === 'b-ahead' ? 'b' : 'tie';
}

/**
 * One business scenario as a ladder (Level 5 at the top, Level 1 at the bottom) for one model, or 2–4 models
 * side by side. The reached height is highlighted; levels with no test yet say "coming soon".
 */
export function ScenarioLadder({ view, scenario, models, slide }: { view: BusinessLadderView; scenario: LadderScenario; models: LadderModelView[]; slide?: boolean }) {
  const results = models.map((m) => ({ m, r: m.scenarios.find((s) => s.id === scenario.id)! }));
  const single = models.length === 1;
  const pair = models.length === 2 ? view.comparisons.find((c) => (c.a === models[0]!.id && c.b === models[1]!.id) || (c.a === models[1]!.id && c.b === models[0]!.id)) : undefined;
  const flipped = pair && pair.a !== models[0]!.id;
  const builtLevels = new Set(scenario.tests.filter((t) => view.tests[t.id]?.exists).map((t) => t.level)).size;
  const label = (id: string) => models.find((m) => m.id === id)?.label ?? id;
  const pairWords = (r: PairedResult | null) => {
    if (!r || !pair) return null;
    return pairedWords(r, label(pair.a), label(pair.b));
  };
  return (
    <section className={cx('biz-ladder', slide && 'slide', single ? 'one' : 'many')} style={scenarioStyle(scenario)} aria-label={`${scenario.name} ladder`}>
      <header className="biz-ladder-head">
        <ScenarioGlyph scenario={scenario} />
        <div className="t">
          <h3>{scenario.name}</h3>
          {scenario.summary && !slide && <p>{scenario.summary}</p>}
        </div>
      </header>
      <div className="biz-heights">
        {results.map(({ m, r }) => {
          const w = heightWords(r, view.definition.levels);
          return (
            <div key={m.id} className={cx('biz-height', r.height > 0 ? 'up' : 'zero')} style={{ ['--mc' as string]: m.color ?? 'var(--accent)' } as CSSProperties}>
              {!single && (
                <span className="who">
                  <i />
                  {m.label}
                </span>
              )}
              <span className="h">
                <span className="big">{r.height}</span>
                <span className="of">/ 5</span>
              </span>
              <span className="w">
                <b>{w.title}</b>
                {w.note && <em>{w.note}</em>}
              </span>
            </div>
          );
        })}
      </div>
      <ol className="biz-rungs" reversed>
        {[5, 4, 3, 2, 1].map((lv) => {
          const level = view.definition.levels.find((l) => l.level === lv)!;
          const per = results.map(({ m, r }) => ({ m, r, l: r.levels.find((x) => x.level === lv)! }));
          const soon = per[0]!.l.status === 'coming-soon';
          const reachedBy = per.filter(({ r }) => r.height >= lv && !soon);
          const atTop = per.filter(({ r }) => r.height === lv);
          const planned = scenario.tests.filter((t) => t.level === lv && !view.tests[t.id]?.exists);
          const cmp = pair?.scenarios.find((s) => s.scenarioId === scenario.id)?.levels.find((x) => x.level === lv)?.result ?? null;
          return (
            <li key={lv} className={cx('biz-rung', soon && 'soon', reachedBy.length > 0 && 'reached', single && atTop.length > 0 && 'top')}>
              <div className="rung-lv" aria-label={`Level ${lv}`}>
                {lv}
              </div>
              <div className="rung-body">
                <div className="rung-title">
                  <b>{level.name}</b>
                  {single ? statusChip(per[0]!.l) : soon ? <span className="biz-chip soon">Coming soon</span> : null}
                  {!single &&
                    atTop.map(({ m }) => (
                      <span key={m.id} className="biz-flag" style={{ ['--mc' as string]: m.color ?? 'var(--accent)' } as CSSProperties} title={`${m.label} reached this level`}>
                        {m.label}
                      </span>
                    ))}
                </div>
                {!slide && !soon && <p className="rung-what">{level.what}</p>}
                {soon ? (
                  <p className="rung-soon">{planned.length ? `Being built: ${planned.map((t) => view.tests[t.id]?.name ?? t.id).join(', ')}` : 'No test planned for this level yet.'}</p>
                ) : single ? (
                  <ul className="rung-tests">
                    {per[0]!.l.tests
                      .filter((t) => t.exists)
                      .map((t) => (
                        <li key={t.id}>
                          <Link to={pathOf('tests', t.id)} className="tname">
                            {view.tests[t.id]?.name ?? t.id}
                          </Link>
                          <ScoreRange score={t.score} ci={t.ci95} color={models[0]!.color} pass={view.definition.passMark} />
                        </li>
                      ))}
                    {planned.length > 0 && <li className="more">Also coming: {planned.map((t) => view.tests[t.id]?.name ?? t.id).join(', ')}</li>}
                  </ul>
                ) : (
                  <div className="rung-compare">
                    {per.map(({ m, l }) => (
                      <ScoreRange key={m.id} score={l.score} ci={l.ci95} color={m.color} pass={view.definition.passMark} label={m.label} compact />
                    ))}
                    {pair && cmp && (
                      <div className={cx('biz-verdict', verdictClass(cmp), flipped && 'flip')}>
                        <ExplainIcon name="scale" />
                        {pairWords(cmp)}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </li>
          );
        })}
      </ol>
      {!single && !slide && view.comparisons.length > 0 && (models.length > 2 || builtLevels > 1) && (
        <footer className="biz-pairs">
          {view.comparisons
            .filter((c) => models.some((m) => m.id === c.a) && models.some((m) => m.id === c.b))
            .map((c) => {
              const r = c.scenarios.find((s) => s.scenarioId === scenario.id)?.overall ?? null;
              return (
                <div key={`${c.a}-${c.b}`} className={cx('biz-verdict', verdictClass(r))}>
                  <span className="vs">
                    {label(c.a)} v {label(c.b)}
                  </span>
                  {r ? pairedWords(r, label(c.a), label(c.b)) : 'No shared questions'}
                </div>
              );
            })}
        </footer>
      )}
    </section>
  );
}

/** "How objective is this?" — the same text on the Business home and the ladder page. */
export function ObjectivityPanel({ compact }: { compact?: boolean }) {
  const items: Array<[string, string, string]> = [
    ['check', 'Machine-checked', 'Every Business test is marked by code against answers worked out by a solver. No AI judges, no opinions.'],
    ['scale', 'Same for every model', 'Identical prompts, the same output limit and the same rules for every model. Speed is shown, never scored.'],
    ['hidden', 'Hard to game', 'Fresh documents from fixed seeds, so nothing can be memorised; guessing, hedging and copying the question score nothing.'],
    ['percent', 'Honest about noise', 'Every score has a 95% range. A gap inside the noise is called a tie, never a win.'],
  ];
  return (
    <section className={cx('biz-objective', compact && 'compact')} aria-label="How objective is this?">
      <h2>
        <ExplainIcon name="shield" />
        How objective is this?
      </h2>
      <div className="grid">
        {items.map(([icon, t, d]) => (
          <div key={t} className="item">
            <ExplainIcon name={icon} />
            <div>
              <b>{t}</b>
              <p>{d}</p>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

export function DemoNote({ view }: { view: BusinessLadderView | null | undefined }) {
  if (!view?.demo) return null;
  return (
    <div className="biz-demo" role="note">
      <b>Demo data</b> These scores are made up to show the layout (demo mode). They are not real results; run the Business tests to see your own.
    </div>
  );
}
