import { useMemo, useState } from 'react';
import { PartPicker, fmt } from '../components/Parts.tsx';
import { useApp } from '../store.ts';
import { useDebounced } from '../useDebounced.ts';
import { bestFromInventory } from '../../core/queries.ts';
import { appraiseTrade, batchScore, calculateMargin, allocateBudget } from '../../core/analysis.ts';
import { loadPrices, priceLookup } from '../pricing.ts';
import type { Build, Resolution } from '../../core/types.ts';

/**
 * The trade view: valuing a pile, deciding whether to build or break it up,
 * checking margin, and triaging many machines at once. Everything here is about
 * money rather than frames, which is why it is a screen of its own.
 */
export function TradeDesk() {
  const { games, data } = useApp();
  const prices = useMemo(() => loadPrices(), []);
  const priceOf = useMemo(() => priceLookup(prices), [prices]);

  const [cpuIds, setCpuIds] = useState<string[]>(['amd-ryzen-5-3600', 'intel-core-i5-12400f']);
  const [gpuIds, setGpuIds] = useState<string[]>(['nvidia-geforce-rtx-3060-12gb', 'amd-radeon-rx-6600']);
  const [addCpu, setAddCpu] = useState('amd-ryzen-5-5600');
  const [addGpu, setAddGpu] = useState('nvidia-geforce-gtx-1660-super');
  const [salePrice, setSalePrice] = useState(650);
  const [premium, setPremium] = useState(18);
  const [budget, setBudget] = useState(700);
  /* The field stays on `budget` so typing is immediate; the 196-pairing budget
     allocation reads the settled value. Same reason as the wizard's slider —
     re-running the search on every keystroke is work nobody asked for. */
  const settledBudget = useDebounced(budget, 200);
  const [batchText, setBatchText] = useState(
    'Lot A,amd-ryzen-5-3600,nvidia-geforce-rtx-3060-12gb\nLot B,intel-core-i5-12400f,amd-radeon-rx-6600\nLot C,intel-core-i7-2600k,nvidia-geforce-gtx-1060-6gb',
  );

  const inv = useMemo(
    () => bestFromInventory({ cpuIds, gpuIds, ramKits: [{ totalGB: 16, channels: 2, speedMTs: 3200, type: 'DDR4' }], storage: ['nvme-gen3'] }, games.slice(0, 6), ['1080p'], data),
    [cpuIds, gpuIds, games, data],
  );

  const appraisal = useMemo(
    () =>
      appraiseTrade(
        { cpuIds, gpuIds },
        // Was passing the ARITHMETIC mean into a field named geomeanFps, so the
        // appraisal was scored against a figure inflated by the esports titles.
        inv ? { build: inv.build, geomeanFps: inv.geomeanFps } : null,
        priceOf,
        { assemblyPremium: 1 + premium / 100 },
      ),
    [cpuIds, gpuIds, inv, priceOf, premium],
  );

  const margin = useMemo(() => {
    if (!inv) return null;
    const gpu = data.gpus.get(inv.build.gpuId);
    const cpu = data.cpus.get(inv.build.cpuId);
    if (!gpu || !cpu) return null;
    return calculateMargin(inv.build, gpu, cpu, priceOf, { salePrice });
  }, [inv, data, priceOf, salePrice]);

  const allocation = useMemo(() => {
    const base: Build = {
      id: 'alloc', cpuId: cpuIds[0] ?? 'amd-ryzen-5-3600', gpuId: gpuIds[0] ?? 'nvidia-geforce-rtx-3060-12gb',
      ram: { totalGB: 16, channels: 2, speedMTs: 3200, type: 'DDR4' }, storage: 'nvme-gen3',
      target: { resolution: '1440p' as Resolution, refreshHz: 144 },
    };
    const cpuPool = [...data.cpus.values()].filter((c) => priceOf(c.id) != null).map((c) => c.id).slice(0, 14);
    const gpuPool = [...data.gpus.values()].filter((g) => priceOf(g.id) != null).map((g) => g.id).slice(0, 14);
    return allocateBudget(settledBudget, base, cpuPool, gpuPool, games.slice(0, 4), '1440p', data, priceOf);
  }, [settledBudget, cpuIds, gpuIds, games, data, priceOf]);

  const batch = useMemo(() => {
    const machines = batchText
      .split('\n')
      .map((l) => l.split(',').map((x) => x.trim()))
      .filter((p) => p.length >= 3 && p[0])
      .map(([label, cpuId, gpuId]) => ({ label, cpuId, gpuId }));
    return batchScore(machines, games.slice(0, 5), '1080p', data, priceOf);
  }, [batchText, games, data, priceOf]);

  return (
    <>
      <div className="page-head">
        <h1>Trade Desk</h1>
        <p>
          Value a pile, decide whether it is worth more assembled or broken up, check the margin, and
          triage a lot of machines at once.
        </p>
      </div>

      <div className="note bad" style={{ marginBottom: 12 }}>
        Every figure here rests on the prices in <span className="mono">data/pricing/gbp-used.json</span>,
        which are indicative placeholders, not sourced quotes. Replace them with your own cost basis
        before acting on any of it — {appraisal.unpricedParts.length > 0 && `${appraisal.unpricedParts.length} of the parts below have no price at all, and `}
        the arithmetic is only ever as good as those numbers.
      </div>

      <div className="grid rail">
        <div className="panel">
          <div className="panel-head">the pile</div>
          <div className="panel-body">
            <PartPicker kind="cpu" label="add CPU" value={addCpu} onChange={setAddCpu} />
            <button className="btn" style={{ marginBottom: 10 }} onClick={() => !cpuIds.includes(addCpu) && setCpuIds([...cpuIds, addCpu])}>+ add</button>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 14 }}>
              {cpuIds.map((id) => (
                <span className="chip on" key={id}>
                  {data.cpus.get(id)?.brand ?? id}
                  <span className="mini" style={{ marginLeft: 4 }}>{priceOf(id) != null ? `£${priceOf(id)}` : 'no price'}</span>
                  <button aria-label={`Remove ${data.cpus.get(id)?.fullName ?? id}`} onClick={() => setCpuIds(cpuIds.filter((x) => x !== id))}>×</button>
                </span>
              ))}
            </div>
            <PartPicker kind="gpu" label="add GPU" value={addGpu} onChange={setAddGpu} />
            <button className="btn" style={{ marginBottom: 10 }} onClick={() => !gpuIds.includes(addGpu) && setGpuIds([...gpuIds, addGpu])}>+ add</button>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 14 }}>
              {gpuIds.map((id) => (
                <span className="chip on" key={id}>
                  {data.gpus.get(id)?.brand ?? id}
                  <span className="mini" style={{ marginLeft: 4 }}>{priceOf(id) != null ? `£${priceOf(id)}` : 'no price'}</span>
                  <button aria-label={`Remove ${data.gpus.get(id)?.fullName ?? id}`} onClick={() => setGpuIds(gpuIds.filter((x) => x !== id))}>×</button>
                </span>
              ))}
            </div>
            <div className="field">
              <label>assembly premium %</label>
              <input type="number" value={premium} onChange={(e) => setPremium(Number(e.target.value))} />
              <span className="mini">What a working, tested machine adds over the sum of its parts in your market.</span>
            </div>
          </div>
        </div>

        <div>
          <div className="panel">
            <div className="panel-head">build it or break it up?</div>
            <div className="panel-body">
              <div className="grid three" style={{ marginBottom: 12 }}>
                <div className="stat">
                  <span className="v">£{appraisal.partsOutTotal.toFixed(0)}</span>
                  <span className="k">sold as parts</span>
                </div>
                <div className="stat">
                  <span className="v">£{appraisal.buildTotal.toFixed(0)}</span>
                  <span className="k">built + leftovers</span>
                </div>
                <div className="stat">
                  <span className="v" style={{ color: appraisal.advantage > 0 ? 'var(--measured)' : appraisal.advantage < 0 ? 'var(--extrapolated)' : 'var(--faint)' }}>
                    {appraisal.advantage > 0 ? '+' : ''}£{appraisal.advantage.toFixed(0)}
                  </span>
                  <span className="k">{appraisal.verdict}</span>
                </div>
              </div>
              <div className="note">{appraisal.reasoning}</div>
              {inv && (
                <div className="note" style={{ marginTop: 8 }}>
                  Best assembly: <b className="mono">{data.cpus.get(inv.build.cpuId)?.fullName}</b> +{' '}
                  <b className="mono">{data.gpus.get(inv.build.gpuId)?.fullName}</b> — {fmt(inv.geomeanFps, 1)} geomean fps.
                </div>
              )}
            </div>
          </div>

          {margin && (
            <div className="panel">
              <div className="panel-head">margin on the best build</div>
              <div className="panel-body">
                <div className="field" style={{ maxWidth: 200 }}>
                  <label>sale price £</label>
                  <input type="number" value={salePrice} onChange={(e) => setSalePrice(Number(e.target.value))} />
                </div>
                <div className="grid three">
                  <div className="stat"><span className="v">£{margin.cost.toFixed(0)}</span><span className="k">parts cost</span></div>
                  <div className="stat">
                    <span className="v" style={{ color: margin.grossMargin > 0 ? 'var(--measured)' : 'var(--blocked)' }}>£{margin.grossMargin.toFixed(0)}</span>
                    <span className="k">gross margin ({margin.marginPct.toFixed(0)}%)</span>
                  </div>
                  <div className="stat"><span className="v">£{margin.runningCostPerYearGBP.toFixed(0)}</span><span className="k">buyer's yearly electricity</span></div>
                </div>
                <div className="note" style={{ marginTop: 10 }}>
                  Break-even at £{margin.breakEvenSalePrice.toFixed(0)}. Running cost is worth quoting to a
                  buyer: an efficient machine is genuinely cheaper to own, and it is a fair selling point.
                </div>
              </div>
            </div>
          )}

          <div className="panel">
            <div className="panel-head">budget allocator</div>
            <div className="panel-body">
              <div className="field" style={{ maxWidth: 200 }}>
                <label>budget £</label>
                <input type="number" value={budget} onChange={(e) => setBudget(Number(e.target.value))} />
              </div>
              {allocation.split && allocation.best ? (
                <>
                  <div className="split-bar" style={{ marginBottom: 8 }}>
                    <div style={{ width: `${allocation.split.cpuPct}%`, background: 'color-mix(in srgb, var(--cpu) 35%, var(--surface-3))' }}>
                      CPU {allocation.split.cpuPct.toFixed(0)}%
                    </div>
                    <div style={{ width: `${allocation.split.gpuPct}%`, background: 'color-mix(in srgb, var(--gpu) 35%, var(--surface-3))' }}>
                      GPU {allocation.split.gpuPct.toFixed(0)}%
                    </div>
                  </div>
                  <div className="note">{allocation.advice}</div>
                  <div className="mini" style={{ marginTop: 6 }}>
                    Winner: {allocation.best.cpuName} + {allocation.best.gpuName} — {fmt(allocation.best.geomeanFps, 1)} fps
                    at £{allocation.best.cost?.toFixed(0)}. {allocation.affordable} of {allocation.considered} pairings were
                    both priced and affordable.
                  </div>
                </>
              ) : (
                <div className="note bad">{allocation.advice}</div>
              )}
            </div>
          </div>

          <div className="panel">
            <div className="panel-head">batch triage</div>
            <div className="panel-body">
              <div className="field">
                <label>one machine per line: label, cpu-id, gpu-id</label>
                <textarea value={batchText} onChange={(e) => setBatchText(e.target.value)} style={{ minHeight: 90 }} />
              </div>
            </div>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>machine</th><th className="n">geomean fps</th><th className="n">power</th>
                    <th className="n">PSU</th><th className="n">latency</th><th>smoothness</th>
                    <th className="n">cost</th><th className="n">fps/£</th>
                  </tr>
                </thead>
                <tbody>
                  {batch.map((r) => (
                    <tr key={r.label}>
                      <td>{r.label}{r.error && <span className="tag vram" style={{ marginLeft: 6 }}>{r.error}</span>}</td>
                      <td className="n">{r.error ? '—' : fmt(r.geomeanFps, 1)}</td>
                      <td className="n sub">{r.error ? '—' : `${r.powerW}W`}</td>
                      <td className="n sub">{r.error ? '—' : `${r.recommendedPsuW}W`}</td>
                      <td className="n sub">{r.latencyMs ? `${r.latencyMs.toFixed(0)}ms` : '—'}</td>
                      <td className="sub">{r.smoothness ?? '—'}</td>
                      <td className="n sub">{r.cost ? `£${r.cost.toFixed(0)}` : '—'}</td>
                      <td className="n">{r.fpsPerCurrency ? r.fpsPerCurrency.toFixed(3) : <span className="sub">—</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
