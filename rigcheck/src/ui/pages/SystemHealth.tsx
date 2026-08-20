/**
 * System Health — "is my machine performing as well as it should?"
 *
 * The consent step is not a formality and is not styled like one. This screen
 * asks someone to run a script that reads their hardware, so the honest thing
 * is to say exactly what it reads, exactly what it does NOT read, and where the
 * data goes — before the button, in language a person can check, not buried in
 * a paragraph they will scroll past. The detector genuinely omits identifying
 * fields (the machine name and the CPU's serial are deliberately blank in its
 * output), and nothing is transmitted anywhere, because there is nowhere to
 * transmit it to: this page is static and has no server.
 *
 * After that it is three things in order of what a person needs:
 *   1. What was detected, with anything the detector had to guess called out.
 *   2. What is wrong, worst first, with the cost and the fix.
 *   3. How the machine actually measured against what the model expects — which
 *      is the only part that can catch a fault the specification cannot see.
 */
import { useMemo, useState } from 'react';
import { useApp } from '../store.ts';
import { detectHardware, detectionToBuild } from '../../core/detect.ts';
import { diagnose, type DetectedSystem, type Measurement, type Severity } from '../../core/health.ts';
import { PRESETS } from '../../core/presets.ts';
import { exportJson } from '../export.ts';
import type { RamConfig, Resolution, Storage } from '../../core/types.ts';

type Stage = 'consent' | 'detect' | 'confirm' | 'measure' | 'report';

const SEVERITY_LABEL: Record<Severity, string> = {
  critical: 'critical',
  major: 'major',
  minor: 'minor',
  ok: 'healthy',
  unknown: 'unknown',
};

export function SystemHealth() {
  const { data } = useApp();
  const [stage, setStage] = useState<Stage>('consent');
  const [agreed, setAgreed] = useState(false);
  const [text, setText] = useState('');

  // Confirmed specification. Seeded from the detector, then editable — the
  // detector guesses some fields and being able to correct them matters more
  // than the convenience of not having to.
  const [cpuId, setCpuId] = useState('');
  const [gpuId, setGpuId] = useState('');
  const [ram, setRam] = useState<RamConfig>({ totalGB: 16, channels: 2, speedMTs: 3200, type: 'DDR4' });
  const [ratedMTs, setRatedMTs] = useState<number | ''>('');
  const [storage, setStorage] = useState<Storage>('nvme-gen4');
  const [resolution, setResolution] = useState<Resolution>('1440p');
  const [refreshHz, setRefreshHz] = useState(144);
  const [pcieGen, setPcieGen] = useState<number | ''>('');
  const [pcieWidth, setPcieWidth] = useState<number | ''>('');
  const [airflow, setAirflow] = useState<'restricted' | 'moderate' | 'good' | 'excellent'>('good');
  const [psuWatts, setPsuWatts] = useState<number | ''>('');
  const [driverDate, setDriverDate] = useState('');
  const [uptimeDays, setUptimeDays] = useState<number | ''>('');

  const [measurements, setMeasurements] = useState<Measurement[]>([]);
  const [mGame, setMGame] = useState('cyberpunk-2077');
  const [mPreset, setMPreset] = useState('high');
  const [mAvg, setMAvg] = useState<number | ''>('');
  const [mLow, setMLow] = useState<number | ''>('');

  const detection = useMemo(() => (text.trim() ? detectHardware(text, data) : null), [text, data]);

  const applyDetection = () => {
    if (!detection) return;
    const b = detectionToBuild(detection);
    if (b) {
      setCpuId(b.cpuId);
      setGpuId(b.gpuId);
      setRam(b.ram);
      setStorage(b.storage);
      setResolution(b.target.resolution);
      setRefreshHz(b.target.refreshHz);
    }
    setStage('confirm');
  };

  const system: DetectedSystem | null = useMemo(() => {
    if (!data.cpus.has(cpuId) || !data.gpus.has(gpuId)) return null;
    return {
      build: { id: 'mine', cpuId, gpuId, ram, storage, target: { resolution, refreshHz } },
      memory: {
        ratedSpeedMTs: ratedMTs === '' ? undefined : Number(ratedMTs),
        configuredSpeedMTs: ram.speedMTs,
        modulesPopulated: ram.channels,
      },
      pcieLink: pcieWidth === '' ? undefined : { gen: pcieGen === '' ? undefined : Number(pcieGen), width: Number(pcieWidth) },
      cooling: { airflow },
      psuWatts: psuWatts === '' ? undefined : Number(psuWatts),
      driverDate: driverDate || undefined,
      uptimeDays: uptimeDays === '' ? undefined : Number(uptimeDays),
    };
  }, [cpuId, gpuId, ram, storage, resolution, refreshHz, ratedMTs, pcieGen, pcieWidth, airflow, psuWatts, driverDate, uptimeDays, data]);

  const report = useMemo(
    () => (system ? diagnose(system, measurements, data) : null),
    [system, measurements, data],
  );

  const STAGES: { key: Stage; label: string; hint: string }[] = [
    { key: 'consent', label: 'What this reads', hint: 'and what it does not' },
    { key: 'detect', label: 'Read the machine', hint: 'or type it in' },
    { key: 'confirm', label: 'Check the specs', hint: 'correct anything wrong' },
    { key: 'measure', label: 'Add benchmarks', hint: 'optional, but it is the point' },
    { key: 'report', label: 'The verdict', hint: 'what is wrong and what it costs' },
  ];
  const idx = STAGES.findIndex((s) => s.key === stage);

  return (
    <>
      <div className="page-head">
        <h1>System Health</h1>
        <p>
          Whether the machine you already own is performing as well as its parts say it should — and if
          not, which of the handful of invisible causes it is.
        </p>
      </div>

      <div className="steps">
        {STAGES.map((s, i) => (
          <button
            key={s.key}
            className={`step${s.key === stage ? ' on' : ''}${i < idx ? ' done' : ''}`}
            onClick={() => (i === 0 || agreed) && setStage(s.key)}
            disabled={i > 0 && !agreed}
          >
            <span className="n">{i < idx ? '✓' : i + 1}</span>
            <span className="lbl"><b>{s.label}</b><span>{s.hint}</span></span>
          </button>
        ))}
      </div>

      {/* --------------------------------------------------------- consent -- */}
      {stage === 'consent' && (
        <div className="consent">
          <h3>Before anything reads your machine</h3>
          <p className="lede">
            To tell you whether your PC is underperforming, this needs to know what is in it. You can type
            that in by hand and never run anything — that path is fully supported and reaches the same
            report. If you would rather not type it, there is a script that reads it for you, and here is
            precisely what it does.
          </p>

          <div className="consent-cols">
            <div>
              <h4>What it reads</h4>
              <ul>
                <li>CPU model, core and thread count, clocks, cache sizes</li>
                <li>GPU model, VRAM, driver version and date</li>
                <li>Memory: total, speed, type, how many slots are populated</li>
                <li>Storage: whether the system and games drives are NVMe, SATA or mechanical</li>
                <li>Motherboard model and chipset, BIOS date</li>
                <li>Display resolution and refresh rate</li>
                <li>Windows version and how long the machine has been up</li>
              </ul>
            </div>
            <div className="no">
              <h4>What it does not read, ever</h4>
              <ul>
                <li>Your machine name or username — the script blanks these deliberately</li>
                <li>The CPU's serial number, which is machine-identifying</li>
                <li>Any file, document, browser data, saved game or credential</li>
                <li>Any network address, Wi-Fi name or account</li>
                <li>What is installed, beyond where Steam keeps its game libraries</li>
              </ul>
            </div>
          </div>

          <div className="note" style={{ marginBottom: 14 }}>
            <b>Nothing is sent anywhere, because there is nowhere to send it.</b> This page is a static
            file with no server behind it: the script writes a JSON file on your own machine, and you
            choose whether to paste it here. Everything after that happens in your browser. You can read
            the script before running it — it is plain PowerShell in{' '}
            <span className="mono">harness/detect-hardware.ps1</span>, and reading a script before running
            it is a reasonable thing to insist on.
          </div>

          <div className="note warn" style={{ marginBottom: 14 }}>
            The detector needs Administrator to read a few things Windows will not otherwise report — the
            real VRAM figure and the PCIe link the card negotiated. It will say so. If you would rather
            not grant that, run it without: it works, and the fields it could not read are reported as
            unknown rather than guessed.
          </div>

          <label style={{ display: 'flex', gap: 8, alignItems: 'flex-start', fontSize: 13, cursor: 'pointer' }}>
            <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} style={{ marginTop: 3 }} />
            <span>
              I have read the above and want to continue. I understand nothing is transmitted, and that I
              can type my specifications in by hand instead of running anything.
            </span>
          </label>

          <div className="wizard-nav">
            <button className="btn" disabled={!agreed} onClick={() => setStage('detect')}>continue</button>
            <button className="btn" disabled={!agreed} onClick={() => setStage('confirm')}>
              skip the script, I will type it in
            </button>
          </div>
        </div>
      )}

      {/* ---------------------------------------------------------- detect -- */}
      {stage === 'detect' && (
        <div className="panel">
          <div className="panel-head"><h2>Read the machine</h2></div>
          <div className="panel-body">
            <p className="mini" style={{ marginTop: 0 }}>
              On the machine you want checked, from the <span className="mono">harness/</span> directory:
            </p>
            <pre
              className="mono"
              style={{ background: 'var(--surface-2)', padding: '10px 12px', borderRadius: 3, fontSize: 12, overflowX: 'auto', border: '1px solid var(--line)' }}
            >{`powershell -ExecutionPolicy Bypass -File .\\detect-hardware.ps1`}</pre>
            <p className="mini">
              It writes <span className="mono">out/hardware-&lt;timestamp&gt;.json</span>. Open it, read it
              — it is short and human-readable — then paste it below. A dxdiag dump or a line you typed
              from memory works too; anything ambiguous is asked about rather than assumed.
            </p>
            <textarea
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="paste the JSON, a dxdiag dump, or just: Ryzen 5 5600 / RTX 4070 / 32GB DDR4-3600"
              style={{ minHeight: 200, width: '100%' }}
            />
            {detection && (
              <div style={{ marginTop: 12 }}>
                <div className="stat-row" style={{ marginBottom: 8 }}>
                  <div className="stat">
                    <span className="v" style={{ fontSize: 14 }}>{detection.cpu[0]?.record.fullName ?? 'not recognised'}</span>
                    <span className="k">cpu</span>
                  </div>
                  <div className="stat">
                    <span className="v" style={{ fontSize: 14 }}>{detection.gpu[0]?.record.fullName ?? 'not recognised'}</span>
                    <span className="k">gpu</span>
                  </div>
                </div>
                {detection.warnings.map((w) => (
                  <div key={w} className="note warn" style={{ marginBottom: 6, fontSize: 12 }}>{w}</div>
                ))}
              </div>
            )}
            <div className="wizard-nav">
              <button className="btn" onClick={() => setStage('consent')}>back</button>
              <button className="btn" onClick={applyDetection} disabled={!detection?.cpu.length || !detection?.gpu.length}>
                use this
              </button>
              <button className="btn" onClick={() => setStage('confirm')}>type it in instead</button>
            </div>
          </div>
        </div>
      )}

      {/* --------------------------------------------------------- confirm -- */}
      {stage === 'confirm' && (
        <div className="panel">
          <div className="panel-head"><h2>Check the specifications</h2></div>
          <div className="panel-body">
            <p className="mini" style={{ marginTop: 0 }}>
              Correct anything that is wrong. Two of these are worth real care: <b>memory channels</b> is
              the single most common thing a detector gets wrong and the single largest silent
              performance loss, and <b>the PCIe link</b> is invisible without Administrator. Leave a field
              blank rather than guessing — a blank is reported as unchecked, a wrong value produces a
              wrong diagnosis.
            </p>

            <div className="grid two">
              <div>
                <div className="field">
                  <label>CPU</label>
                  <select value={cpuId} onChange={(e) => setCpuId(e.target.value)}>
                    <option value="">— pick your CPU —</option>
                    {[...data.cpus.values()].sort((a, b) => a.fullName.localeCompare(b.fullName)).map((c) => (
                      <option key={c.id} value={c.id}>{c.fullName}</option>
                    ))}
                  </select>
                </div>
                <div className="field">
                  <label>GPU</label>
                  <select value={gpuId} onChange={(e) => setGpuId(e.target.value)}>
                    <option value="">— pick your GPU —</option>
                    {[...data.gpus.values()].sort((a, b) => a.fullName.localeCompare(b.fullName)).map((g) => (
                      <option key={g.id} value={g.id}>{g.fullName}</option>
                    ))}
                  </select>
                </div>
                <div className="grid two">
                  <div className="field">
                    <label>memory installed (GB)</label>
                    <input type="number" value={ram.totalGB} onChange={(e) => setRam({ ...ram, totalGB: Number(e.target.value) })} />
                  </div>
                  <div className="field">
                    <label>memory channels</label>
                    <div className="toggle-row">
                      {([1, 2, 4] as const).map((c) => (
                        <button key={c} className={`toggle${ram.channels === c ? ' on' : ''}`} onClick={() => setRam({ ...ram, channels: c })}>{c}</button>
                      ))}
                    </div>
                  </div>
                </div>
                <div className="grid two">
                  <div className="field">
                    <label>running at (MT/s)</label>
                    <input type="number" value={ram.speedMTs} onChange={(e) => setRam({ ...ram, speedMTs: Number(e.target.value) })} />
                  </div>
                  <div className="field">
                    <label>kit is rated for (MT/s)</label>
                    <input
                      type="number"
                      value={ratedMTs}
                      placeholder="on the sticker"
                      onChange={(e) => setRatedMTs(e.target.value === '' ? '' : Number(e.target.value))}
                    />
                  </div>
                </div>
              </div>

              <div>
                <div className="field">
                  <label>games are installed on</label>
                  <div className="toggle-row">
                    {(['nvme-gen4', 'nvme-gen3', 'sata-ssd', 'hdd'] as const).map((s) => (
                      <button key={s} className={`toggle${storage === s ? ' on' : ''}`} onClick={() => setStorage(s)}>{s}</button>
                    ))}
                  </div>
                </div>
                <div className="grid two">
                  <div className="field">
                    <label>PCIe generation</label>
                    <input type="number" value={pcieGen} placeholder="unknown" onChange={(e) => setPcieGen(e.target.value === '' ? '' : Number(e.target.value))} />
                  </div>
                  <div className="field">
                    <label>PCIe lanes</label>
                    <input type="number" value={pcieWidth} placeholder="unknown" onChange={(e) => setPcieWidth(e.target.value === '' ? '' : Number(e.target.value))} />
                  </div>
                </div>
                <div className="field">
                  <label>case airflow</label>
                  <div className="toggle-row">
                    {(['restricted', 'moderate', 'good', 'excellent'] as const).map((a) => (
                      <button key={a} className={`toggle${airflow === a ? ' on' : ''}`} onClick={() => setAirflow(a)}>{a}</button>
                    ))}
                  </div>
                </div>
                <div className="grid two">
                  <div className="field">
                    <label>power supply (W)</label>
                    <input type="number" value={psuWatts} placeholder="unknown" onChange={(e) => setPsuWatts(e.target.value === '' ? '' : Number(e.target.value))} />
                  </div>
                  <div className="field">
                    <label>days since last reboot</label>
                    <input type="number" value={uptimeDays} placeholder="unknown" onChange={(e) => setUptimeDays(e.target.value === '' ? '' : Number(e.target.value))} />
                  </div>
                </div>
                <div className="grid two">
                  <div className="field">
                    <label>screen</label>
                    <div className="toggle-row">
                      {(['1080p', '1440p', '2160p', '3440x1440'] as const).map((r) => (
                        <button key={r} className={`toggle${resolution === r ? ' on' : ''}`} onClick={() => setResolution(r)}>{r}</button>
                      ))}
                    </div>
                  </div>
                  <div className="field">
                    <label>refresh (Hz)</label>
                    <input type="number" value={refreshHz} onChange={(e) => setRefreshHz(Number(e.target.value))} />
                  </div>
                </div>
                <div className="field">
                  <label>graphics driver date</label>
                  <input type="date" value={driverDate} onChange={(e) => setDriverDate(e.target.value)} />
                </div>
              </div>
            </div>

            <div className="wizard-nav">
              <button className="btn" onClick={() => setStage('detect')}>back</button>
              <button className="btn" onClick={() => setStage('measure')} disabled={!system}>
                {system ? 'next' : 'pick a CPU and GPU first'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* --------------------------------------------------------- measure -- */}
      {stage === 'measure' && (
        <div className="panel">
          <div className="panel-head">
            <h2>Add benchmark results</h2>
            <span className="spacer" />
            <span className="mini">{measurements.length} added</span>
          </div>
          <div className="panel-body">
            <div className="note bad" style={{ marginBottom: 12 }}>
              <b>Without at least one result this is a configuration review, not a health check.</b> It can
              tell you what SHOULD be wrong from the specification, and it cannot tell you what IS wrong.
              One measurement changes that.
            </div>
            <p className="mini" style={{ marginTop: 0 }}>
              Use a built-in benchmark if the game has one, or the harness (
              <span className="mono">run-benchmark.ps1</span>) if it does not. Note the preset and
              resolution honestly — comparing an ultra capture against a high expectation produces a
              fault that is not there.
            </p>

            <div className="grid two" style={{ alignItems: 'end' }}>
              <div className="field">
                <label>game</label>
                <select value={mGame} onChange={(e) => setMGame(e.target.value)}>
                  {[...data.games.values()].sort((a, b) => a.name.localeCompare(b.name)).map((g) => (
                    <option key={g.id} value={g.id}>{g.name}{g.builtInBenchmark ? ' — has a built-in benchmark' : ''}</option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label>preset used</label>
                <div className="toggle-row">
                  {PRESETS.map((p) => (
                    <button key={p} className={`toggle${mPreset === p ? ' on' : ''}`} onClick={() => setMPreset(p)}>{p}</button>
                  ))}
                </div>
              </div>
              <div className="grid two">
                <div className="field">
                  <label>average fps</label>
                  <input type="number" value={mAvg} onChange={(e) => setMAvg(e.target.value === '' ? '' : Number(e.target.value))} />
                </div>
                <div className="field">
                  <label>1% low (optional)</label>
                  <input type="number" value={mLow} onChange={(e) => setMLow(e.target.value === '' ? '' : Number(e.target.value))} />
                </div>
              </div>
              <div className="field">
                <button
                  className="btn"
                  disabled={mAvg === '' || Number(mAvg) <= 0}
                  onClick={() => {
                    setMeasurements((m) => [
                      ...m,
                      { gameId: mGame, resolution, preset: mPreset, avgFps: Number(mAvg), low1PctFps: mLow === '' ? undefined : Number(mLow) },
                    ]);
                    setMAvg('');
                    setMLow('');
                  }}
                >
                  add this result
                </button>
              </div>
            </div>

            {measurements.length > 0 && (
              <div className="table-wrap" style={{ marginTop: 12 }}>
                <table className="data">
                  <thead><tr><th>game</th><th>preset</th><th className="n">avg</th><th className="n">1% low</th><th /></tr></thead>
                  <tbody>
                    {measurements.map((m, i) => (
                      <tr key={`${m.gameId}-${i}`}>
                        <td>{data.games.get(m.gameId)?.name ?? m.gameId}</td>
                        <td className="sub">{m.preset}</td>
                        <td className="n mono">{m.avgFps}</td>
                        <td className="n sub">{m.low1PctFps ?? '—'}</td>
                        <td className="n">
                          <button className="btn" onClick={() => setMeasurements((xs) => xs.filter((_, j) => j !== i))}>remove</button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <div className="wizard-nav">
              <button className="btn" onClick={() => setStage('confirm')}>back</button>
              <button className="btn" onClick={() => setStage('report')}>
                {measurements.length ? 'see the verdict' : 'skip — configuration review only'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ---------------------------------------------------------- report -- */}
      {stage === 'report' && report && system && (
        <>
          <div className="panel">
            <div className="panel-head">
              <h2>Verdict</h2>
              <span className="spacer" />
              <button
                className="btn"
                onClick={() => exportJson('rigcheck-health-report.json', { system, measurements, report })}
              >
                export
              </button>
            </div>
            <div className="panel-body">
              <div className="verdict-hero">{report.verdict}</div>
              {report.findings.length > 0 && (
                <div className="stat-row" style={{ marginTop: 14 }}>
                  <div className="stat">
                    <span className="v">{report.findings.filter((f) => f.severity === 'critical').length}</span>
                    <span className="k">critical</span>
                  </div>
                  <div className="stat">
                    <span className="v">{report.findings.filter((f) => f.severity === 'major').length}</span>
                    <span className="k">major</span>
                  </div>
                  <div className="stat">
                    <span className="v">{report.findings.filter((f) => f.severity === 'minor').length}</span>
                    <span className="k">minor</span>
                  </div>
                  <div className="stat">
                    <span className="v" style={{ color: 'var(--measured)' }}>+{Math.round(report.recoverablePct * 100)}%</span>
                    <span className="k">recoverable, all fixed</span>
                  </div>
                </div>
              )}
            </div>
          </div>

          {report.findings.length > 0 && (
            <div className="panel">
              <div className="panel-head"><h2>What is wrong</h2><span className="spacer" /><span className="mini">worst first</span></div>
              <div className="panel-body">
                {report.findings.map((f) => (
                  <div key={f.id} className={`finding ${f.severity}`}>
                    <div className="head">
                      <span className={`pill ${f.severity === 'critical' ? 'critical' : f.severity === 'major' ? 'risk' : 'watch'}`}>
                        {SEVERITY_LABEL[f.severity]}
                      </span>
                      <span className="tag">{f.component}</span>
                      {f.measured && <span className="tag" title="from your benchmark, not from the specification">measured</span>}
                      <b>{f.title}</b>
                      <span className="spacer" style={{ flex: 1 }} />
                      {f.estimatedGainPct != null && <span className="gain">+{Math.round(f.estimatedGainPct * 100)}% if fixed</span>}
                    </div>
                    <div className="row"><span className="k">seen</span>{f.evidence}</div>
                    <div className="row"><span className="k">costs</span>{f.impact}</div>
                    {f.remedy && <div className="row"><span className="k">fix</span>{f.remedy}</div>}
                  </div>
                ))}
                <p className="mini" style={{ marginTop: 8 }}>
                  The recoverable figure is compounded, not added — two faults each costing 10% do not cost
                  20% together. It is also the gain over what the machine does today, and is a separate
                  quantity from any shortfall against expectation below. Do not add them.
                </p>
              </div>
            </div>
          )}

          {report.comparisons.length > 0 && (
            <div className="panel">
              <div className="panel-head"><h2>Measured against expected</h2></div>
              <div className="panel-body">
                <div className="table-wrap">
                  <table className="data">
                    <thead><tr><th>game</th><th className="n">you got</th><th className="n">expected</th><th className="n">band</th><th>verdict</th></tr></thead>
                    <tbody>
                      {report.comparisons.map((c, i) => (
                        <tr key={i}>
                          <td>{data.games.get(c.measurement.gameId)?.name ?? c.measurement.gameId}</td>
                          <td className="n mono">{c.measurement.avgFps}</td>
                          <td className="n mono sub">{c.expectedFps?.toFixed(0) ?? '—'}</td>
                          <td className="n sub">{c.bandLow != null ? `${c.bandLow.toFixed(0)}–${c.bandHigh!.toFixed(0)}` : '—'}</td>
                          <td>
                            <span className={`pill ${c.verdict === 'far below expectation' ? 'critical' : c.verdict === 'below expectation' ? 'risk' : c.verdict === 'as expected' ? 'ok' : 'watch'}`}>
                              {c.verdict}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {report.comparisons.map((c, i) => (
                  <p key={i} className="mini" style={{ marginTop: 8 }}>
                    <b>{data.games.get(c.measurement.gameId)?.name ?? c.measurement.gameId}</b> — {c.detail}
                  </p>
                ))}
              </div>
            </div>
          )}

          {report.healthy.length > 0 && (
            <div className="panel">
              <div className="panel-head"><h2>What is fine</h2></div>
              <div className="panel-body">
                {report.healthy.map((f) => (
                  <div key={f.id} className="finding ok">
                    <div className="head">
                      <span className="pill ok">healthy</span>
                      <span className="tag">{f.component}</span>
                      <b>{f.title}</b>
                    </div>
                    <div className="row"><span className="k">seen</span>{f.evidence}</div>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div className="panel">
            <div className="panel-head"><h2>What this could not check</h2></div>
            <div className="panel-body">
              <p className="mini" style={{ marginTop: 0 }}>
                A report that lists findings and stops looks complete, and a reader will assume everything
                else was verified. It was not. These are the things this cannot see, and several of them
                are more likely than anything above.
              </p>
              <ul style={{ fontSize: 12, lineHeight: 1.7, color: 'var(--muted)', paddingLeft: 18, margin: 0 }}>
                {report.notChecked.map((n) => <li key={n}>{n}</li>)}
              </ul>
            </div>
          </div>

          <div className="wizard-nav">
            <button className="btn" onClick={() => setStage('measure')}>back</button>
            <button className="btn" onClick={() => setStage('confirm')}>change the specs</button>
          </div>
        </>
      )}
    </>
  );
}
