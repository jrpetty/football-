import { useMemo, useState } from 'react';
import { PartPicker, fmt } from '../components/Parts.tsx';
import { useApp } from '../store.ts';
import { bestFromInventory } from '../../core/queries.ts';
import type { PartInventory, RamConfig } from '../../core/types.ts';

export function InventoryOptimiser() {
  const { games, resolutions, data } = useApp();
  const [cpuIds, setCpuIds] = useState<string[]>(['amd-ryzen-5-3600', 'intel-core-i7-2600k']);
  const [gpuIds, setGpuIds] = useState<string[]>(['nvidia-geforce-rtx-3060-12gb', 'nvidia-geforce-gtx-1060-6gb']);
  const [ramKits, setRamKits] = useState<RamConfig[]>([
    { totalGB: 16, channels: 2, speedMTs: 3200, type: 'DDR4' },
    { totalGB: 8, channels: 1, speedMTs: 2400, type: 'DDR4' },
  ]);
  const [addCpu, setAddCpu] = useState('amd-ryzen-5-5600');
  const [addGpu, setAddGpu] = useState('amd-radeon-rx-6600');

  const inventory: PartInventory = useMemo(
    () => ({ cpuIds, gpuIds, ramKits, storage: ['nvme-gen3', 'sata-ssd'] }),
    [cpuIds, gpuIds, ramKits],
  );

  const result = useMemo(
    () => bestFromInventory(inventory, games, resolutions, data),
    [inventory, games, resolutions, data],
  );

  return (
    <>
      <div className="page-head">
        <h1>Inventory Optimiser</h1>
        <p>
          Given a pile of parts, the best machine you can actually assemble — and what is left over.
          Socket, memory generation and channel limits are hard filters, so it never proposes a build
          that cannot physically exist.
        </p>
      </div>

      <div className="grid rail">
        <div className="panel">
          <div className="panel-head">parts on hand</div>
          <div className="panel-body">
            <PartPicker kind="cpu" label="add CPU" value={addCpu} onChange={setAddCpu} />
            <button className="btn" style={{ marginBottom: 12 }} onClick={() => !cpuIds.includes(addCpu) && setCpuIds([...cpuIds, addCpu])}>
              + add to pile
            </button>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 14 }}>
              {cpuIds.map((id) => (
                <span className="chip on" key={id}>
                  {data.cpus.get(id)?.brand ?? id}
                  <button aria-label={`Remove ${data.cpus.get(id)?.fullName ?? id}`} onClick={() => setCpuIds(cpuIds.filter((x) => x !== id))}>×</button>
                </span>
              ))}
            </div>

            <PartPicker kind="gpu" label="add GPU" value={addGpu} onChange={setAddGpu} />
            <button className="btn" style={{ marginBottom: 12 }} onClick={() => !gpuIds.includes(addGpu) && setGpuIds([...gpuIds, addGpu])}>
              + add to pile
            </button>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 14 }}>
              {gpuIds.map((id) => (
                <span className="chip on" key={id}>
                  {data.gpus.get(id)?.brand ?? id}
                  <button aria-label={`Remove ${data.gpus.get(id)?.fullName ?? id}`} onClick={() => setGpuIds(gpuIds.filter((x) => x !== id))}>×</button>
                </span>
              ))}
            </div>

            <div className="field">
              <label>memory kits</label>
              {ramKits.map((k, i) => (
                <div key={i} style={{ display: 'flex', gap: 4, marginBottom: 4, alignItems: 'center' }}>
                  <span className="chip on" style={{ flex: 1 }}>
                    {k.totalGB}GB · {k.channels}ch · {k.speedMTs} · {k.type}
                  </span>
                  <button className="btn danger" aria-label={`Remove memory kit ${i + 1}`} onClick={() => setRamKits(ramKits.filter((_, j) => j !== i))}>×</button>
                </div>
              ))}
              <button
                className="btn"
                onClick={() => setRamKits([...ramKits, { totalGB: 32, channels: 2, speedMTs: 6000, type: 'DDR5' }])}
              >
                + add 32GB DDR5-6000
              </button>
            </div>
          </div>
        </div>

        <div>
          {!result && <div className="panel"><div className="empty">No compatible combination in this pile.</div></div>}
          {result && (
            <>
              <div className="panel">
                <div className="panel-head">best assembly</div>
                <div className="panel-body">
                  <div className="grid two">
                    <dl className="kv">
                      <dt>CPU</dt><dd>{data.cpus.get(result.build.cpuId)?.fullName}</dd>
                      <dt>GPU</dt><dd>{data.gpus.get(result.build.gpuId)?.fullName}</dd>
                      <dt>memory</dt><dd>{result.build.ram.totalGB}GB · {result.build.ram.channels}-channel · {result.build.ram.speedMTs} MT/s</dd>
                      <dt>storage</dt><dd>{result.build.storage}</dd>
                    </dl>
                    <div>
                      <div className="stat">
                        <span className="v">{fmt(result.geomeanFps, 1)}</span>
                        <span className="k">geomean fps across {games.length} titles</span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <div className="panel">
                <div className="panel-head">left over</div>
                <div className="panel-body">
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                    {[...result.unused.cpuIds.map((id) => data.cpus.get(id)?.brand ?? id),
                      ...result.unused.gpuIds.map((id) => data.gpus.get(id)?.brand ?? id),
                      ...result.unused.ramKits.map((k) => `${k.totalGB}GB ${k.channels}ch`),
                    ].map((label, i) => (
                      <span className="chip" key={i}>{label}</span>
                    ))}
                    {result.unused.cpuIds.length + result.unused.gpuIds.length + result.unused.ramKits.length === 0 && (
                      <span className="sub">Everything was used.</span>
                    )}
                  </div>
                </div>
              </div>

              {result.rejected.length > 0 && (
                <div className="panel">
                  <div className="panel-head">combinations rejected</div>
                  <div className="table-wrap">
                    <table className="data">
                      <thead><tr><th>reason</th><th className="n">count</th></tr></thead>
                      <tbody>
                        {result.rejected.map((r) => (
                          <tr key={r.reason}><td>{r.reason}</td><td className="n">{r.count}</td></tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <div className="panel-body">
                    <div className="note">
                      Rejections are shown rather than silently dropped, so a surprising recommendation can
                      be traced to the constraint that caused it.
                    </div>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </>
  );
}
