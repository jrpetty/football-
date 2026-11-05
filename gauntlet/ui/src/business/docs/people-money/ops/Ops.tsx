/**
 * Numbers for the Board (work.board-report): the memo and the exports as spreadsheets, and the KPI tiles the
 * model gave against the correct ones. What Do We Reorder? (work.stock-reorder): the stock list with the model's
 * order quantities against the cheapest workable order, each item's lowest projected stock against its safety
 * stock, and the order's cost against the optimum.
 */
import { useMemo } from 'react';
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import type { KindProps } from '../../../../components/viz/closeup/types.ts';
import { VizFrame, type VizMode } from '../../../../components/viz/VizFrame.tsx';
import { cx } from '../../../../components/ui.tsx';
import { DocBeats, Fact, Impact, Paper, Stamp, Tick } from '../kit.tsx';
import { gbp } from '../model/common.ts';
import { boardAnswer, boardQuestion, kpiText, reorderAnswer, reorderQuestion, type BoardQuestion, type KpiRow, type ReorderAnswer, type ReorderQuestion } from '../model/opsdata.ts';
import './ops.css';

// ───────────────────────────── Board ─────────────────────────────

function Sheet({ g, max }: { g: BoardQuestion['grids'][number]; max: number }) {
  return (
    <div className="pm-sheet">
      <div className="pm-sheet-t">{g.title}</div>
      <div className="pm-scroll">
        <table>
          <thead>
            <tr>
              <th />
              {g.head.map((_h, i) => (
                <th key={i}>{String.fromCharCode(65 + i)}</th>
              ))}
            </tr>
            <tr className="names">
              <th>1</th>
              {g.head.map((h) => (
                <td key={h}>{h}</td>
              ))}
            </tr>
          </thead>
          <tbody>
            {g.rows.slice(0, max).map((r, i) => (
              <tr key={i} className={cx(/cancel|void|test|rerun/i.test(r.join(' ')) && 'flag')}>
                <th>{i + 2}</th>
                {r.map((c, j) => (
                  <td key={j}>{c}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {g.rows.length > max && <div className="pm-more">+ {g.rows.length - max} more rows</div>}
    </div>
  );
}

function Tiles({ rows, show }: { rows: KpiRow[]; show: 'key' | 'model' }) {
  return (
    <div className="pm-kpis">
      {rows.map((r) => (
        <div key={r.key} className={cx('pm-kpi', show === 'model' && (r.ok ? 'ok' : 'no'))}>
          <span className="l">{r.label}</span>
          {show === 'key' || r.ok ? (
            <b>{kpiText(r.key, show === 'key' ? r.want : r.got)}</b>
          ) : (
            <>
              <b className="bad">{kpiText(r.key, r.got)}</b>
              <span className="c">correct {kpiText(r.key, r.want)}</span>
            </>
          )}
          {show === 'model' && (
            <span className="m">
              <Tick ok={r.ok} />
            </span>
          )}
        </div>
      ))}
    </div>
  );
}

function boardSentence(a: { answered: boolean; rows: KpiRow[] }): string {
  if (!a.answered) return 'No KPI table could be read from the reply, so nothing scored.';
  const wrong = a.rows.filter((r) => !r.ok);
  if (!wrong.length) return `All ${a.rows.length} KPIs exact: the board pack could go out as it is.`;
  return `${a.rows.length - wrong.length} of ${a.rows.length} KPIs exact; wrong: ${wrong
    .slice(0, 3)
    .map((r) => `${r.label.replace(/^./, (c) => c.toLowerCase())} (${kpiText(r.key, r.got)}, correct ${kpiText(r.key, r.want)})`)
    .join(', ')}${wrong.length > 3 ? ` and ${wrong.length - 3} more` : ''}.`;
}

function boardImpact(a: { answered: boolean; rows: KpiRow[] }) {
  if (!a.answered) return null;
  const nr = a.rows.find((r) => r.key === 'net_revenue');
  if (nr && nr.ok === false && nr.got !== null && nr.want !== null) return <Impact tone="bad">The board would read net revenue of {kpiText(nr.key, nr.got)}: {gbp(Math.abs(nr.got - nr.want))} {nr.got > nr.want ? 'more' : 'less'} than the business really made.</Impact>;
  const wrong = a.rows.filter((r) => !r.ok);
  if (!wrong.length) return <Impact tone="good">Every number on the board’s KPI table is right.</Impact>;
  return <Impact tone="warn">{wrong.length} of the board’s KPIs would be wrong: {wrong.map((r) => r.label.replace(/^./, (c) => c.toLowerCase())).join(', ')}.</Impact>;
}

export function BoardBeats(p: KindProps) {
  const q = useMemo(() => boardQuestion(p.prompt || p.q.turns[0] || ''), [p.prompt, p.q]);
  const keyA = useMemo(() => boardAnswer(p.q), [p.q]);
  const a = useMemo(() => (p.a ? boardAnswer(p.a) : null), [p.a]);
  if (!q) return <p className="lc-empty">This report could not be drawn.</p>;
  return (
    <div className="pm-closeup">
      <DocBeats
        prompt={p.prompt}
        reply={p.reply}
        answerHead={p.answerHead}
        verdict={p.verdict}
        sentence={a ? boardSentence(a) : null}
        impact={a ? boardImpact(a) : null}
        question={{
          title: 'The question',
          sub: `${q.company}: twelve KPIs for the board, ${q.quarter}, from three messy exports and the finance director’s memo`,
          alt: `A finance memo with ${q.memo.length} KPI definitions and three exports: ${q.grids.map((g) => `${g.title} (${g.rows.length} rows)`).join(', ')}.`,
          node: (
            <div className="pm-cvq">
              <Paper className="pm-memo" label="Finance memo">
                <b>Finance memo · KPI definitions</b>
                <span className="muted">from {q.from}</span>
                <ol>
                  {q.memo.map((m, i) => (
                    <li key={i}>{m.replace(/^\d+\.\s*/, '')}</li>
                  ))}
                </ol>
              </Paper>
              <div className="pm-pile">
                {q.grids.map((g) => (
                  <Sheet key={g.title} g={g} max={10} />
                ))}
              </div>
            </div>
          ),
        }}
        keyBeat={{ title: 'The correct KPI table', sub: 'Each to its stated rounding', alt: keyA.rows.map((r) => `${r.label}: ${kpiText(r.key, r.want)}`).join('. '), node: <Tiles rows={keyA.rows} show="key" /> }}
        answer={
          a
            ? {
                title: 'The model’s answer',
                sub: a.answered ? 'Its KPI tiles: green exact, red wrong with the correct figure' : 'No KPI table could be read from the reply',
                alt: a.rows.map((r) => `${r.label}: ${kpiText(r.key, r.got)}${r.ok ? '' : `, correct ${kpiText(r.key, r.want)}`}`).join('. '),
                node: <Tiles rows={a.rows} show="model" />,
              }
            : null
        }
        result={null}
      />
    </div>
  );
}

export function BoardInline({ input, mode }: { input: CaseVisualInput; mode: VizMode }) {
  const a = boardAnswer(input);
  if (!a.rows.length) return null;
  const right = a.rows.filter((r) => r.ok).length;
  return (
    <VizFrame mode={mode} tone={!a.answered ? 'bad' : right === a.rows.length ? 'good' : right >= 9 ? 'half' : 'bad'} className="pm-inline" eyebrow="Numbers for the Board · KPI table" headline={boardSentence(a)} big={`${right}/${a.rows.length}`} bigSub="KPIs exact">
      <div className="pm-doc-root pm-pile">
        {boardImpact(a)}
        <Tiles rows={a.rows} show="model" />
      </div>
    </VizFrame>
  );
}

// ───────────────────────────── Reorder ─────────────────────────────

function StockList({ q, a, show }: { q: ReorderQuestion; a: ReorderAnswer | null; show: 'none' | 'key' | 'model' }) {
  return (
    <div className="pm-scroll">
      <table className="pm-stock">
        <thead>
          <tr>
            <th>Item</th>
            <th>Supplier</th>
            <th className="r">In stock</th>
            <th className="r">Case · MOQ · room</th>
            {show === 'none' ? <th>Last 8 weeks sold</th> : <th className="r">Sells / wk</th>}
            {show !== 'none' && <th className="r">Safety</th>}
            {show === 'model' && <th className="r">Model orders</th>}
            {show !== 'none' && <th className="r">Cheapest order</th>}
            {show !== 'none' && <th>Lowest stock before next delivery</th>}
          </tr>
        </thead>
        <tbody>
          {q.items.map((it) => {
            const r = a?.rows.find((x) => x.sku === it.sku);
            const hist = q.history.rows.get(it.sku) ?? [];
            const nums = hist.map((h) => Number(h.replace(/\*/g, '')) || 0);
            const top = Math.max(1, ...nums);
            const low = show === 'model' ? r?.lowGot : r?.lowBest;
            const diff = show === 'model' && r && r.got !== r.best;
            return (
              <tr key={it.sku} className={cx(show === 'model' && r?.broken && 'bad', diff && !r?.broken && 'warn')}>
                <td>
                  <b>{it.name}</b>
                  <span className="sub">{it.sku}</span>
                </td>
                <td>{it.supplier}</td>
                <td className="r">{it.stock}</td>
                <td className="r sub2">
                  {it.caseSize} · {it.moq} · {it.max}
                  {it.open !== '-' && <span className="sub">{it.open}</span>}
                </td>
                {show === 'none' ? (
                  <td>
                    <span className="pm-spark">
                      {nums.map((n, i) => (
                        <i key={i} className={cx(/\*/.test(hist[i] ?? '') && 'promo', /BH|CLOSED/.test(q.history.weeks[i] ?? '') && 'bh')} style={{ height: `${Math.max(8, (n / top) * 100)}%` }} title={`${q.history.weeks[i]}: ${hist[i]}`} />
                      ))}
                    </span>
                  </td>
                ) : (
                  <td className="r">{r?.forecast}</td>
                )}
                {show !== 'none' && <td className="r">{r?.safety}</td>}
                {show === 'model' && (
                  <td className={cx('r big', r?.broken ? 'no' : diff ? 'warn' : 'ok')}>
                    {r?.got ? r.got : '—'}
                    {r?.got ? <span className="sub">{r.got / r.caseSize} cs</span> : null}
                  </td>
                )}
                {show !== 'none' && (
                  <td className="r big">
                    {r?.best ? r.best : '—'}
                    {r?.best ? <span className="sub">{r.best / r.caseSize} cs</span> : null}
                  </td>
                )}
                {show !== 'none' && r && (
                  <td>
                    <span className="pm-lowbar">
                      <i className="safe" style={{ width: `${Math.min(100, (r.safety / Math.max(r.max, 1)) * 100)}%` }} />
                      <i className={cx('lvl', (low ?? 0) < r.safety && 'below')} style={{ width: `${Math.max(1, Math.min(100, ((low ?? 0) / Math.max(r.max, 1)) * 100))}%` }} />
                    </span>
                    <span className={cx('sub', (low ?? 0) < r.safety && 'no')}>
                      {low} {show === 'model' && r.broken ? `· ${r.broken}` : ''}
                    </span>
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function reorderSentence(a: ReorderAnswer, score: number | null): string {
  if (!a.answered) return 'No order could be read from the reply, so nothing scored.';
  const broken = a.rows.filter((r) => r.broken);
  if (broken.length) return `The order breaks a rule: ${broken.map((r) => `${r.name} ${r.broken}`).slice(0, 2).join('; ')}. A broken order scores 0.`;
  const same = a.rows.every((r) => r.got === r.best);
  if (same && a.statedOk) return `Ordered exactly the cheapest workable order, ${gbp(a.bestTotal)}, and quoted its cost right.`;
  if (same) return `Ordered the cheapest workable order, but quoted ${gbp(a.modelTotal)} instead of ${gbp(a.bestTotal)}${score !== null ? ', which halves the score' : ''}.`;
  return `A workable order, but it costs ${gbp(a.modelCost)}: ${gbp((a.modelCost ?? 0) - a.bestTotal)} more than the cheapest one (${gbp(a.bestTotal)}).`;
}

function reorderImpact(a: ReorderAnswer) {
  if (!a.answered) return null;
  const broken = a.rows.filter((r) => r.broken);
  const short = broken.find((r) => (r.lowGot ?? 0) < r.safety);
  if (short) return <Impact tone="bad">{short.name} would run down to {short.lowGot} before the next delivery (safety stock {short.safety}): a real risk of running out.</Impact>;
  if (broken.length) return <Impact tone="bad">The supplier or the stock room could not take this order: {broken[0]!.name} {broken[0]!.broken}.</Impact>;
  if (a.modelCost !== null && a.modelCost > a.bestTotal + 0.004) return <Impact tone="warn">Spends {gbp(a.modelCost - a.bestTotal)} more than it needs to this week.</Impact>;
  return <Impact tone="good">The cheapest order that keeps every shelf above its safety stock.</Impact>;
}

export function ReorderBeats(p: KindProps) {
  const q = useMemo(() => reorderQuestion(p.prompt || p.q.turns[0] || ''), [p.prompt, p.q]);
  const keyA = useMemo(() => reorderAnswer(p.q), [p.q]);
  const a = useMemo(() => (p.a ? reorderAnswer(p.a) : null), [p.a]);
  if (!q || !keyA) return <p className="lc-empty">This stock order could not be drawn.</p>;
  return (
    <div className="pm-closeup">
      <DocBeats
        prompt={p.prompt}
        reply={p.reply}
        answerHead={p.answerHead}
        verdict={p.verdict}
        sentence={a ? reorderSentence(a, p.a?.score ?? null) : null}
        impact={a ? reorderImpact(a) : null}
        wideAnswer
        question={{
          title: 'The question',
          sub: `${q.shop}, ${q.today}: ${q.items.length} items from ${q.suppliers.length} suppliers; order enough, as cheaply as possible, within ${q.cash}`,
          alt: `A stock list of ${q.items.length} items with stock, case sizes, minimum orders and room, and eight weeks of sales; ${q.suppliers.length} suppliers with lead times and delivery charges.`,
          node: (
            <div className="pm-pile">
              <ul className="pm-suppliers">
                {q.suppliers.map((s) => (
                  <li key={s}>{s}</li>
                ))}
              </ul>
              <StockList q={q} a={keyA} show="none" />
            </div>
          ),
        }}
        keyBeat={{ title: 'The cheapest workable order', sub: `${gbp(keyA.bestTotal)}, found by an exact search`, alt: keyA.rows.filter((r) => r.best).map((r) => `${r.name}: ${r.best}`).join('. '), node: <StockList q={q} a={keyA} show="key" /> }}
        answer={
          a
            ? {
                title: 'The model’s answer',
                sub: a.answered ? 'Its order beside the cheapest one; the bar shows each item’s lowest stock before the next delivery against its safety stock (grey)' : 'No order could be read from the reply',
                alt: a.rows.filter((r) => r.got !== r.best || r.broken).map((r) => `${r.name}: model ${r.got}, cheapest ${r.best}${r.broken ? ` (${r.broken})` : ''}`).join('. '),
                node: (
                  <div className="pm-pile">
                    <div className="pm-facts">
                      <Fact k="order really costs" v={gbp(a.modelCost)} tone={a.rows.some((r) => r.broken) ? 'bad' : (a.modelCost ?? 0) > a.bestTotal + 0.004 ? 'bad' : 'good'} />
                      <Fact k="cheapest possible" v={gbp(a.bestTotal)} />
                      <Fact k="it quoted" v={gbp(a.modelTotal)} tone={a.statedOk ? 'good' : 'bad'} />
                      <span className="pm-onapp">
                        {a.rows.some((r) => r.broken) ? (
                          <Stamp tone="bad" className="onapp">
                            Breaks a rule
                          </Stamp>
                        ) : (a.modelCost ?? 0) > a.bestTotal + 0.004 ? (
                          <Stamp tone="warn" className="onapp">
                            Works · dearer
                          </Stamp>
                        ) : (
                          <Stamp tone="good" className="onapp">
                            Optimal ✓
                          </Stamp>
                        )}
                      </span>
                    </div>
                    <StockList q={q} a={a} show="model" />
                  </div>
                ),
              }
            : null
        }
        result={
          a && a.checks.length
            ? {
                title: 'The result',
                alt: a.checks.map((c) => `${c.label}: ${c.passed ? 'passed' : `failed, ${c.detail ?? ''}`}`).join('. '),
                node: (
                  <ul className="pm-problems">
                    {a.checks.filter((c) => !c.passed).map((c) => (
                      <li key={c.label} className={c.passed ? 't-good' : 't-bad'}>
                        <Tick ok={c.passed} />
                        <span>
                          {c.label}
                          {c.detail && !c.passed ? `: ${c.detail}` : ''}
                        </span>
                      </li>
                    ))}
                    {a.checks.some((c) => c.passed) && (
                      <li className="t-good">
                        <Tick ok />
                        <span>{a.checks.filter((c) => c.passed).map((c) => c.label).join(' · ')}</span>
                      </li>
                    )}
                  </ul>
                ),
              }
            : null
        }
      />
    </div>
  );
}

export function ReorderInline({ input, mode }: { input: CaseVisualInput; mode: VizMode }) {
  const q = reorderQuestion(input.turns[0] ?? '');
  const a = reorderAnswer(input);
  if (!q || !a) return null;
  const broken = a.rows.some((r) => r.broken);
  return (
    <VizFrame mode={mode} tone={!a.answered || broken ? 'bad' : (a.modelCost ?? 0) > a.bestTotal + 0.004 || !a.statedOk ? 'half' : 'good'} className="pm-inline" eyebrow={`What Do We Reorder? · ${q.shop}`} headline={reorderSentence(a, input.score)} big={gbp(a.modelCost)} bigSub={`cheapest ${gbp(a.bestTotal)}`}>
      <div className="pm-doc-root pm-pile">
        {reorderImpact(a)}
        <StockList q={q} a={a} show="model" />
      </div>
    </VizFrame>
  );
}
