import { useMemo, useState } from 'react';
import { BuildEditor } from '../components/BuildEditor.tsx';
import { fmt } from '../components/Parts.tsx';
import { useApp } from '../store.ts';
import { estimate } from '../../core/engine.ts';
import { machineReport, bottleneckMap, sensitivity } from '../../core/analysis.ts';
import { RESOLUTIONS } from '../../core/types.ts';
import type { Resolution } from '../../core/types.ts';

const AIRFLOW = ['restricted', 'moderate', 'good', 'excellent'] as const;
const PANELS = ['OLED', 'QD-OLED', 'TN', 'IPS', 'VA'] as const;

/**
 * The whole-machine view: what the box draws, how hot and loud it gets, how
 * responsive it feels, and where its constraint actually sits. Everything here
 * is a property of the machine rather than of one frame rate, which is why it
 * lives on its own screen rather than being buried in a per-game row.
 */
export function MachineReport() {
  const { builds, setBuilds, games, data } = useApp();
  const build = builds[0];
  const [airflow, setAirflow] = useState<(typeof AIRFLOW)[number]>('good');
  const [panel, setPanel] = useState<(typeof PANELS)[number]>('IPS');
  const [res, setRes] = useState<Resolution>(build?.target.resolution ?? '1440p');
  const [hours, setHours] = useState(15);
  const [sensGame, setSensGame] = useState(games[0] ?? 'cyberpunk-2077');

  const probe = useMemo(
    () => estimate(build, games[0] ?? 'cyberpunk-2077', res, data, { airflowTier: airflow }),
    [build, games, res, data, airflow],
  );

  const report = useMemo(
    () =>
      machineReport(build, data, {
        fps: probe.avgFps,
        panelType: panel,
        airflowTier: airflow,
        cpuBound: probe.limiter === 'cpu',
      }),
    [build, data, probe, panel, airflow],
  );

  const bottleneck = useMemo(() => bottleneckMap(build, games, data), [build, games, data]);
  const sens = useMemo(() => sensitivity(build, sensGame, res, data), [build, sensGame, res, data]);

  if (!report) return <div className="empty">Build references unknown parts.</div>;
  const { power, thermal, noise, latency, latencyVerdict: lv, psuVerdict } = report;

  return (
    <>
      <div className="page-head">
        <h1>Machine Report</h1>
        <p>
          What the machine draws, how hot and loud it runs, and how responsive it feels — the properties
          that belong to the box rather than to any one game.
        </p>
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'minmax(280px, 330px) minmax(0, 1fr)' }}>
        <div>
          <BuildEditor build={build} onChange={(b) => setBuilds([b, ...builds.slice(1)])} />
          <div className="panel">
            <div className="panel-head">environment</div>
            <div className="panel-body">
              <div className="field">
                <label>case airflow</label>
                <div className="toggle-row">
                  {AIRFLOW.map((a) => (
                    <button key={a} className={`toggle${a === airflow ? ' on' : ''}`} onClick={() => setAirflow(a)}>{a}</button>
                  ))}
                </div>
              </div>
              <div className="field">
                <label>display panel</label>
                <div className="toggle-row">
                  {PANELS.map((p) => (
                    <button key={p} className={`toggle${p === panel ? ' on' : ''}`} onClick={() => setPanel(p)}>{p}</button>
                  ))}
                </div>
              </div>
              <div className="field">
                <label>hours of gaming per week</label>
                <input type="number" value={hours} min={1} max={100} onChange={(e) => setHours(Number(e.target.value))} />
              </div>
              <div className="field">
                <label>resolution</label>
                <div className="toggle-row">
                  {RESOLUTIONS.map((r) => (
                    <button key={r} className={`toggle${r === res ? ' on' : ''}`} onClick={() => setRes(r)}>{r}</button>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>

        <div>
          <div className="panel">
            <div className="panel-head">power</div>
            <div className="panel-body">
              <div className="grid three" style={{ marginBottom: 12 }}>
                <div className="stat">
                  <span className="v">{Math.round(power.totalW)}<span style={{ fontSize: 12, color: 'var(--faint)' }}>W</span></span>
                  <span className="k">sustained under load</span>
                </div>
                <div className="stat">
                  <span className="v" style={{ color: 'var(--spec)' }}>{Math.round(power.transientPeakW)}<span style={{ fontSize: 12, color: 'var(--faint)' }}>W</span></span>
                  <span className="k">transient peak</span>
                </div>
                <div className="stat">
                  <span className="v">£{power.costPerYearGBP(hours).toFixed(0)}</span>
                  <span className="k">electricity / year at {hours}h a week</span>
                </div>
              </div>
              <div className={`note ${psuVerdict.ok ? '' : 'bad'}`}>{psuVerdict.message}</div>
              <table className="terms" style={{ marginTop: 10 }}>
                <tbody>
                  {power.terms.map((t, i) => (
                    <tr key={i}>
                      <td className="t-label">{t.label}</td>
                      <td className="t-value">{t.value}</td>
                      <td className="t-explain">{t.explain}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="grid two">
            <div className="panel">
              <div className="panel-head">thermals</div>
              <div className="panel-body">
                <div className="stat" style={{ marginBottom: 8 }}>
                  <span className="v" style={{ color: thermal.throttling ? 'var(--extrapolated)' : 'var(--measured)' }}>
                    {thermal.throttling ? `${(Math.min(thermal.cpuClockFactor, thermal.gpuClockFactor) * 100).toFixed(0)}%` : 'no throttle'}
                  </span>
                  <span className="k">{thermal.throttling ? 'of rated boost sustained' : 'cooling is adequate'}</span>
                </div>
                <div className="meter" style={{ marginBottom: 8 }}>
                  <i
                    className=""
                    style={{
                      width: `${Math.min(100, thermal.loadRatio * 100)}%`,
                      background: thermal.loadRatio > 1 ? 'var(--blocked)' : thermal.loadRatio > 0.8 ? 'var(--spec)' : 'var(--measured)',
                    }}
                  />
                </div>
                <div className="mini">
                  {Math.round(thermal.capacityW)}W of cooling against {Math.round(thermal.cpuHeatW)}W of CPU heat under
                  a gaming load ({Math.round(thermal.heatLoadW)}W total system heat including the GPU, which carries its
                  own cooler)
                </div>
                {thermal.terms.map((t, i) => (
                  <div className="note" key={i} style={{ marginTop: 8 }}>{t.explain}</div>
                ))}
              </div>
            </div>

            <div className="panel">
              <div className="panel-head">acoustics &amp; latency</div>
              <div className="panel-body">
                <div className="stat-row" style={{ marginBottom: 10 }}>
                  <div className="stat">
                    <span className="v">{noise.dba.toFixed(0)}<span style={{ fontSize: 12, color: 'var(--faint)' }}>dBA</span></span>
                    <span className="k">{noise.descriptor}</span>
                  </div>
                  {latency && lv && (
                    <div className="stat">
                      <span className="v">{latency.totalMs.toFixed(0)}<span style={{ fontSize: 12, color: 'var(--faint)' }}>ms</span></span>
                      <span className="k">click-to-photon</span>
                    </div>
                  )}
                </div>
                {lv && <span className={`pill ${lv.severity}`}>{lv.label}</span>}
                <div className="note" style={{ marginTop: 8 }}>{noise.terms[0].explain}</div>
                {latency?.terms.map((t, i) => (
                  <div className="note" key={i} style={{ marginTop: 8 }}>{t.explain}</div>
                ))}
              </div>
            </div>
          </div>

          <div className="panel">
            <div className="panel-head">where the bottleneck actually sits</div>
            <div className="panel-body">
              <div className="note warn" style={{ marginBottom: 6 }}>{bottleneck.summary}</div>
              <div className="note">{bottleneck.recommendation}</div>
            </div>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>title</th>
                    {RESOLUTIONS.map((r) => <th key={r} className="n">{r}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {games.map((gameId) => (
                    <tr key={gameId}>
                      <td>{data.games.get(gameId)?.name ?? gameId}</td>
                      {RESOLUTIONS.map((r) => {
                        const cell = bottleneck.cells.find((c) => c.gameId === gameId && c.resolution === r);
                        return (
                          <td key={r} className="n">
                            {cell && !cell.blocked ? (
                              <span className={`tag ${cell.limiter}`}>{cell.limiter}</span>
                            ) : (
                              <span className="sub">—</span>
                            )}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="panel">
            <div className="panel-head">
              <span>what would change this answer</span>
              <span className="spacer" />
              <select value={sensGame} onChange={(e) => setSensGame(e.target.value)} style={{ width: 220 }}>
                {games.map((g) => <option key={g} value={g}>{data.games.get(g)?.name ?? g}</option>)}
              </select>
            </div>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr><th>factor</th><th className="n">impact</th><th>detail</th></tr>
                </thead>
                <tbody>
                  {sens.factors.map((f) => (
                    <tr key={f.factor}>
                      <td>{f.factor}</td>
                      <td className="n" style={{ color: f.factor === 'Model uncertainty' ? 'var(--faint)' : Math.abs(f.impactPct) > 5 ? 'var(--extrapolated)' : 'var(--muted)' }}>
                        {f.impactPct > 0 ? '+' : ''}{f.impactPct.toFixed(1)}%
                      </td>
                      <td className="sub">{f.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="panel-body">
              <div className="note">{sens.robustness}</div>
              <div className="mini" style={{ marginTop: 6 }}>
                Baseline {fmt(sens.baselineFps)} fps. An uncertainty band says an answer is imprecise;
                this says which input to go and pin down.
              </div>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
