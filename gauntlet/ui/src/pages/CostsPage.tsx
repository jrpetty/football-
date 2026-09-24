/** Cost Planner — test × model cost table for a suite before you spend anything. */
import { useMemo, useState } from 'react';
import { api } from '../api.ts';
import { useAsync, useDebounced } from '../hooks.ts';
import { Link, setQuery, useRoute } from '../router.tsx';
import { useMeta, useViewerCaption } from '../context.tsx';
import { Callout, ErrorState, PageHead, Seg, SkeletonRows, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtCost, fmtInt } from '../format.ts';
import { isBaseline } from '../components/leaderboard/util.ts';
import type { ContestantView, RunEstimate, SuiteView } from '../types.ts';

const apiModel = (c: ContestantView) => c.enabled && c.providerType !== 'manual' && c.providerType !== 'mock' && !isBaseline({ contestantId: c.id, vendor: c.vendor, label: c.label });

export default function CostsPage() {
  const { query } = useRoute();
  const { cat } = useMeta();
  const requestedSuite = query.get('suite');
  const repeats = Math.max(1, Math.min(5, Number(query.get('repeats') ?? 1) || 1));
  const modelsParam = query.get('models');
  const lists = useAsync<[SuiteView[], ContestantView[]]>(() => Promise.all([api.suites(), api.contestants()]), []);
  const [suites, contestants] = lists.data ?? [[], []];
  const suiteId = requestedSuite && (!suites.length || suites.some((s) => s.id === requestedSuite)) ? requestedSuite : suites.some((s) => s.id === 'core') ? 'core' : suites[0]?.id ?? 'core';
  const defaults = useMemo(() => contestants.filter(apiModel).map((c) => c.id), [contestants]);
  const models = modelsParam === null ? defaults : modelsParam === 'none' ? [] : modelsParam.split(',').filter(Boolean);
  const key = `${suiteId}|${repeats}|${modelsParam === null ? '*' : models.join(',')}`;
  const debKey = useDebounced(key, 250);
  const [hover, setHover] = useState<string | null>(null);

  const est = useAsync<RunEstimate | null>(() => {
    const [s, r, m] = debKey.split('|');
    if (!lists.data || m === '' || !lists.data[0].some((x) => x.id === s)) return Promise.resolve(null);
    return api.costs(s, Number(r), m === '*' ? undefined : m.split(','));
  }, [debKey, !!lists.data]);

  const byId = useMemo(() => new Map(contestants.map((c) => [c.id, c])), [contestants]);
  const e = est.data;
  const cols = e?.perContestant ?? [];
  const maxCell = useMemo(() => Math.max(1e-9, ...(e?.perTest ?? []).flatMap((t) => Object.values(t.perContestant))), [e]);
  const measuredCount = (e?.perTest ?? []).filter((t) => t.basis !== 'definition').length;
  useViewerCaption(
    `What a run would cost before we press start: each model’s estimated bill for every test${repeats > 1 ? `, with each question asked ${repeats} times` : ''}. Darker cells are the expensive ones.`,
    e ? `${measuredCount} of ${e.perTest.length} tests priced from real past usage; the rest from each test’s token estimate · judge fees included` : undefined,
  );

  const toggleModel = (id: string) => {
    const set = new Set(models);
    if (set.has(id)) set.delete(id);
    else set.add(id);
    setQuery({ models: [...set].join(',') || 'none' });
  };

  const quickSuite = suites.find((s) => s.id === 'quick') ?? [...suites].sort((a, b) => a.testCount - b.testCount)[0];
  const cheapestStart = () => {
    const priced = contestants.filter(apiModel).sort((a, b) => a.pricing.inputPerM + a.pricing.outputPerM - (b.pricing.inputPerM + b.pricing.outputPerM));
    setQuery({ suite: quickSuite?.id ?? suiteId, repeats: 1, models: priced.slice(0, 3).map((c) => c.id).join(',') || 'none' });
  };

  return (
    <div className="page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Dollar style={{ width: 14, height: 14 }} /> Plan
          </span>
        }
        title="Cost Planner"
        sub="What a suite will cost, test by test and model by model — before you spend a cent."
        actions={
          <Link to={`/run/new?suite=${encodeURIComponent(suiteId)}`} className="btn primary">
            <Icon.Rocket /> Plan this run
          </Link>
        }
      />

      <section className="card">
        <div className="card-body stack">
          <div className="row wrap" style={{ gap: 14 }}>
            <label className="field" style={{ minWidth: 240 }}>
              <span className="label">Suite</span>
              <select className="select" value={suiteId} onChange={(ev) => setQuery({ suite: ev.target.value })}>
                {suites.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name} · {s.testCount} tests
                  </option>
                ))}
                {!suites.length && <option value={suiteId}>{suiteId}</option>}
              </select>
            </label>
            <div className="field">
              <span className="label">Repeats</span>
              <Seg label="Repeats" value={String(repeats)} onChange={(v) => setQuery({ repeats: v })} options={[1, 2, 3, 4, 5].map((n) => ({ value: String(n), label: `${n}×` }))} />
            </div>
            <span className="spacer" />
            <button className="btn sm ghost" onClick={() => setQuery({ models: null })} disabled={modelsParam === null}>
              Reset models
            </button>
          </div>
          <div className="field">
            <span className="label">Models · {models.length} selected (default: every enabled API model)</span>
            <div className="chip-list">
              {contestants
                .filter((c) => c.enabled && c.providerType !== 'manual')
                .map((c) => (
                  <button key={c.id} type="button" className={cx('toggle-chip', models.includes(c.id) && 'on')} aria-pressed={models.includes(c.id)} onClick={() => toggleModel(c.id)}>
                    <span className="sw" style={{ background: c.color }} />
                    {c.label}
                  </button>
                ))}
            </div>
          </div>
        </div>
      </section>

      <Callout tone="info" icon={<Icon.Sparkles />}>
        <strong>Cheapest way to start:</strong> the <em>{quickSuite?.name ?? 'Quick Check'}</em> suite with 1 repeat on 2–3 inexpensive models — usually a few cents.{' '}
        <button className="btn xs" onClick={cheapestStart}>
          Show me
        </button>
      </Callout>

      {lists.error ? (
        <ErrorState error={lists.error} onRetry={lists.reload} />
      ) : est.error && !e ? (
        <ErrorState error={est.error} onRetry={est.reload} title="Couldn’t estimate costs" />
      ) : !e ? (
        <div className="card pad">{models.length === 0 && lists.data ? <p className="muted">Select at least one model.</p> : <SkeletonRows rows={8} h={34} />}</div>
      ) : (
        <>
          <div className="stats">
            <div className="stat leader-stat" style={{ ['--c' as string]: 'var(--accent)' }}>
              <span className="k">Central estimate</span>
              <span className="v">{fmtCost(e.estCostUsd)}</span>
              <span className="s">incl. {fmtCost(e.judgeCostUsd)} for judges</span>
            </div>
            <div className="stat">
              <span className="k">Conservative upper bound</span>
              <span className="v">{fmtCost(e.estCostUsdHigh)}</span>
              <span className="s">a sensible spending cap</span>
            </div>
            <div className="stat">
              <span className="k">Jobs · API calls</span>
              <span className="v">{fmtInt(e.jobs)}</span>
              <span className="s">{fmtInt(e.calls)} calls</span>
            </div>
            <div className="stat">
              <span className="k">Measured tests</span>
              <span className="v">
                {measuredCount}/{e.perTest.length}
              </span>
              <span className="s">the rest use declared estimates</span>
            </div>
          </div>

          <section className={cx('card', est.loading && 'refetching')}>
            <div className="card-head">
              <div className="t">
                <h2>Test × model</h2>
                <div className="desc">
                  USD for all cases × {repeats} repeat{repeats === 1 ? '' : 's'}. <span className="basis measured">measured ✓</span> = based on real token usage from previous runs of this exact test version; estimates get more accurate after
                  every run.
                </div>
              </div>
            </div>
            <div className="table-wrap scroll-shadow">
              <table className="table compact cost-table">
                <thead>
                  <tr>
                    <th className="ct-test">Test</th>
                    <th className="num">Cases</th>
                    {cols.map((p) => {
                      const c = byId.get(p.contestantId);
                      return (
                        <th key={p.contestantId} className={cx('num', hover === p.contestantId && 'hl')} onMouseEnter={() => setHover(p.contestantId)} onMouseLeave={() => setHover(null)}>
                          <span className="m-col-head" style={{ justifyContent: 'flex-end' }}>
                            <span className="sw" style={{ background: c?.color }} />
                            <span className="ellipsis" style={{ maxWidth: 130 }}>
                              {c?.label ?? p.contestantId}
                            </span>
                          </span>
                        </th>
                      );
                    })}
                    <th className="num">Judges</th>
                    <th className="num">Total</th>
                  </tr>
                </thead>
                <tbody>
                  {e.perTest.map((t) => {
                    const total = Object.values(t.perContestant).reduce((s, v) => s + v, 0) + t.judgeUsd;
                    const info = cat(t.category);
                    return (
                      <tr key={t.testId}>
                        <td className="ct-test">
                          <div className="row" style={{ gap: 8, minWidth: 0 }}>
                            <span className="cat-dot" style={{ background: info.color }} title={info.name} />
                            <span className="ellipsis" style={{ fontWeight: 600 }} title={t.name}>
                              {t.name}
                            </span>
                            {t.basis !== 'definition' && (
                              <span className="basis measured nowrap" title={t.basis === 'measured' ? 'Measured on these models' : 'Measured on other models'}>
                                measured ✓
                              </span>
                            )}
                          </div>
                        </td>
                        <td className="num">{t.cases}</td>
                        {cols.map((p) => {
                          const v = t.perContestant[p.contestantId] ?? 0;
                          const a = Math.round(Math.sqrt(v / maxCell) * 55);
                          return (
                            <td key={p.contestantId} className={cx('num cost-cell', hover === p.contestantId && 'hl')} style={p.manual ? undefined : { background: `color-mix(in srgb, var(--seq-6) ${a}%, transparent)` }}>
                              {p.manual ? <span className="muted">manual</span> : fmtCost(v)}
                            </td>
                          );
                        })}
                        <td className="num muted">{t.judgeUsd ? fmtCost(t.judgeUsd) : '—'}</td>
                        <td className="num" style={{ fontWeight: 700 }}>
                          {fmtCost(total)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
                <tfoot>
                  <tr>
                    <td className="ct-test">
                      <strong>Total per model</strong>
                    </td>
                    <td />
                    {cols.map((p) => (
                      <td key={p.contestantId} className="num">
                        <strong>{p.manual ? '—' : fmtCost(p.estCostUsd)}</strong>
                        {!p.manual && <div className="muted" style={{ fontSize: '0.72rem' }}>≤ {fmtCost(p.estCostUsdHigh)}</div>}
                      </td>
                    ))}
                    <td className="num">
                      <strong>{fmtCost(e.judgeCostUsd)}</strong>
                    </td>
                    <td className="num grand">
                      <strong>{fmtCost(e.estCostUsd)}</strong>
                      <div className="muted" style={{ fontSize: '0.72rem' }}>
                        ≤ {fmtCost(e.estCostUsdHigh)}
                      </div>
                    </td>
                  </tr>
                </tfoot>
              </table>
            </div>
          </section>
          {e.warnings.length > 0 && (
            <ul className="warn-list">
              {e.warnings.map((w, i) => (
                <li key={i}>
                  <Icon.Alert /> {w}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  );
}
