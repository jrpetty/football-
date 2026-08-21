import { useMemo, useState } from 'react';
import { SweepCurve } from '../components/SweepCurve.tsx';
import { fmt } from '../components/Parts.tsx';
import { useApp } from '../store.ts';
import { sweepComponent } from '../../core/queries.ts';
import { loadPrices, priceLookup } from '../pricing.ts';

/** Rows rendered in the candidate table; the count is stated when it truncates. */
const ROW_CAP = 60;

export function UpgradeAdvisor() {
  const { builds, games, resolutions, data } = useApp();
  const base = builds[0];
  const [axis, setAxis] = useState<'cpu' | 'gpu'>('gpu');
  const [scope, setScope] = useState<'priced' | 'ladder' | 'all'>('priced');

  const prices = useMemo(() => loadPrices(), []);
  const priceOf = useMemo(() => priceLookup(prices), [prices]);

  /**
   * Candidate scope.
   *
   * Plotting all 200+ parts produces an unreadable smear of overlapping markers
   * and makes the knee meaningless, so the default is the priced set — which is
   * both a natural performance ladder and the only set where frames-per-pound
   * can actually be computed. "Ladder" spreads evenly across the index range for
   * when pricing is sparse; "all" is available but is a data view, not a chart.
   */
  const candidates = useMemo(() => {
    const pool =
      axis === 'gpu'
        ? [...data.gpus.values()].filter((g) => g.formFactor === 'desktop' && g.shaders && g.boostClockMHz).map((g) => g.id)
        : (() => {
            const cur = data.cpus.get(base.cpuId);
            return [...data.cpus.values()].filter((c) => (cur ? c.socket === cur.socket : true)).map((c) => c.id);
          })();

    const current = axis === 'gpu' ? base.gpuId : base.cpuId;

    if (scope === 'all') return pool;

    if (scope === 'priced') {
      const priced = pool.filter((id) => priceOf(id) != null);
      // Fall back rather than render an empty chart if nothing in scope is priced.
      if (priced.length >= 4) return [...new Set([...priced, current])];
    }

    // Ladder: 24 parts spread evenly by index so the curve is legible.
    const ranked = pool
      .map((id) => {
        const g = data.gpus.get(id);
        const c = data.cpus.get(id);
        const key = g
          ? (g.shaders ?? 0) * (g.boostClockMHz ?? 0)
          : (c?.cores ?? 0) * (c?.boostClockMHz ?? 0);
        return { id, key };
      })
      .filter((r) => r.key > 0)
      .sort((a, b) => a.key - b.key);
    const step = Math.max(1, Math.floor(ranked.length / 24));
    const ladder = ranked.filter((_, i) => i % step === 0).map((r) => r.id);
    return [...new Set([...ladder, current])];
  }, [axis, data, base.cpuId, base.gpuId, scope, priceOf]);

  const sweep = useMemo(
    () => sweepComponent(base, axis, candidates, games, resolutions, data, priceOf),
    [base, axis, candidates, games, resolutions, data, priceOf],
  );

  const priced = sweep.points.filter((p) => p.price != null).length;

  return (
    <>
      <div className="page-head">
        <h1>Upgrade Advisor</h1>
        <p>
          Where the upgrade curve flattens. The knee is the point past which marginal frames per pound
          collapse — the answer to "how far up the stack is it worth going".
        </p>
      </div>

      <div className="panel">
        <div className="panel-head">
          <span>sweep axis</span>
          <div className="toggle-row" style={{ marginLeft: 8 }}>
            <button className={`toggle${axis === 'gpu' ? ' on' : ''}`} onClick={() => setAxis('gpu')}>swap GPU</button>
            <button className={`toggle${axis === 'cpu' ? ' on' : ''}`} onClick={() => setAxis('cpu')}>swap CPU</button>
          </div>
          <span className="spacer" />
          <span style={{ fontSize: 10, letterSpacing: '0.08em', color: 'var(--faint)', textTransform: 'uppercase', marginRight: 6 }}>
            candidates
          </span>
          <div className="toggle-row">
            <button className={`toggle${scope === 'priced' ? ' on' : ''}`} onClick={() => setScope('priced')} title="Only parts with a price — the set where frames-per-pound is computable">
              priced
            </button>
            <button className={`toggle${scope === 'ladder' ? ' on' : ''}`} onClick={() => setScope('ladder')} title="24 parts spread evenly across the performance range">
              ladder
            </button>
            <button className={`toggle${scope === 'all' ? ' on' : ''}`} onClick={() => setScope('all')} title="Every part — a data view rather than a legible chart">
              all
            </button>
          </div>
        </div>
        <div className="panel-body">
          <div className="note" style={{ marginBottom: 10 }}>
            Base build: <b className="mono">{data.cpus.get(base.cpuId)?.fullName}</b> +{' '}
            <b className="mono">{data.gpus.get(base.gpuId)?.fullName}</b> · {candidates.length} candidates
            {axis === 'cpu' && ' on the same socket'} · {games.length} titles · {resolutions.join(', ')}
          </div>
          <SweepCurve sweep={sweep} currency="£" />
          {sweep.saturation?.saturated && (
            <div className="note bad" style={{ marginTop: 10 }}>
              <b>This axis is saturated.</b> {sweep.saturation.detail}
            </div>
          )}
          <div className="note warn" style={{ marginTop: 10 }}>
            {sweep.kneeExplain}
          </div>
          {priced < sweep.points.length && (
            <div className="note bad" style={{ marginTop: 8 }}>
              Pricing covers {priced}/{sweep.points.length} candidates. Frames-per-pound is only computed
              where a price exists — this query is only as good as the pricing data, which is
              operator-maintained in <span className="mono">data/pricing/</span>.
            </div>
          )}
        </div>
      </div>

      <div className="panel">
        <div className="panel-head">candidates — table view</div>
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>candidate</th>
                <th className="n">geomean fps</th>
                <th className="n">±</th>
                <th className="n">vs base</th>
                <th className="n">price</th>
                <th className="n">fps / £</th>
                <th className="n">marginal fps / £</th>
              </tr>
            </thead>
            <tbody>
              {sweep.points
                .slice()
                .reverse()
                .map((p) => {
                  const isKnee = sweep.kneeIndex != null && sweep.points[sweep.kneeIndex]?.candidateId === p.candidateId;
                  return (
                    <tr key={p.candidateId} style={isKnee ? { background: 'color-mix(in srgb, var(--chart-knee) 10%, transparent)' } : undefined}>
                      <td>
                        {p.candidateName}
                        {isKnee && <span className="tag" style={{ marginLeft: 6, color: 'var(--chart-knee)' }}>knee</span>}
                        {p.blocked && <span className="tag vram" style={{ marginLeft: 6 }}>blocked</span>}
                      </td>
                      <td className="n" style={{ fontSize: 13 }}>{fmt(p.geomeanFps, 1)}</td>
                      <td className="n sub">{(p.uncertainty * 100).toFixed(0)}%</td>
                      <td className="n" style={{ color: p.deltaVsBase > 0 ? 'var(--measured)' : p.deltaVsBase < 0 ? 'var(--extrapolated)' : 'var(--faint)' }}>
                        {p.deltaVsBase > 0 ? '+' : ''}{(p.deltaVsBase * 100).toFixed(1)}%
                      </td>
                      <td className="n">{p.price != null ? `£${p.price.toFixed(0)}` : <span className="sub">—</span>}</td>
                      <td className="n">{p.fpsPerCurrency != null ? p.fpsPerCurrency.toFixed(3) : <span className="sub">—</span>}</td>
                      <td className="n">{p.marginalPerCurrency != null ? p.marginalPerCurrency.toFixed(3) : <span className="sub">—</span>}</td>
                    </tr>
                  );
                })
                .slice(0, ROW_CAP)}
              {sweep.points.length > ROW_CAP && (
                <tr>
                  <td colSpan={8} className="sub" style={{ textAlign: 'center' }}>
                    Showing the first {ROW_CAP} of {sweep.points.length} candidates, ranked as above. A
                    screen that states a count and then quietly renders fewer rows is the kind of thing
                    this tool exists not to do.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
