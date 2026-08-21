import { useMemo } from 'react';
import { fmt } from '../components/Parts.tsx';
import { useApp } from '../store.ts';
import { useStickyState } from '../useStickyState.ts';
import { PilePicker } from '../components/PilePicker.tsx';
import { bestFromInventory } from '../../core/queries.ts';
import type { PartInventory } from '../../core/types.ts';

export function InventoryOptimiser() {
  const { games, resolutions, data } = useApp();
  /* One pile, shared with Trade Desk, and sticky so a list of parts you have
     assembled survives a glance at another screen. */
  const [pile, setPile] = useStickyState<PartInventory>('pile', {
    cpuIds: ['amd-ryzen-5-3600', 'intel-core-i5-12400f'],
    gpuIds: ['nvidia-geforce-rtx-3060-12gb', 'amd-radeon-rx-6600'],
    ramKits: [{ totalGB: 16, channels: 2, speedMTs: 3200, type: 'DDR4' }],
    storage: ['nvme-gen3', 'sata-ssd'],
  }, (v) => {
    const p = v as PartInventory | null;
    return !!p && Array.isArray(p.cpuIds) && Array.isArray(p.gpuIds)
      && Array.isArray(p.ramKits) && Array.isArray(p.storage);
  });

  const result = useMemo(
    () => bestFromInventory(pile, games, resolutions, data),
    [pile, games, resolutions, data],
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
          <div className="panel-head"><h2 className="micro">parts on hand</h2></div>
          <div className="panel-body">
            <PilePicker pile={pile} onChange={setPile} />
          </div>
        </div>

        <div>
          {!result && <div className="panel"><div className="empty">No compatible combination in this pile.</div></div>}
          {result && (
            <>
              <div className="panel">
                <div className="panel-head"><h2 className="micro">best assembly</h2></div>
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
                <div className="panel-head"><h2 className="micro">left over</h2></div>
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
                  <div className="panel-head"><h2 className="micro">combinations rejected</h2></div>
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
