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
import { useEffect, useMemo, useState } from 'react';
import { useApp } from '../store.ts';
import { useStickyState, clearSticky } from '../useStickyState.ts';
import { detectHardware, detectionToBuild } from '../../core/detect.ts';
import { deriveVerdict, diagnose, type DetectedSystem, type Measurement, type Severity } from '../../core/health.ts';
import { DIFFICULTY_LABEL, guideFor } from '../../core/fixguides.ts';
import {
  detectDegradation, machineId as machineIdOf, measurementKey, seriesFor,
  trackableKeys, verifyFixes, type AppliedFix, type HealthSession,
} from '../../core/history.ts';
import { comparePeers, findPeers, type PeerComparison } from '../../core/peers.ts';
import { benchFindings, type BenchResult } from '../../core/browserbench.ts';
import { deriveCpuIndex, deriveGpuIndex } from '../../core/indices.ts';
import { BrowserBenchPanel } from '../components/BrowserBench.tsx';
import { addSession, historyFor, loadSessions, removeSession } from '../sessions.ts';
import measuredJson from '../../../data/measured/records.json';
import type { PerfRecord } from '../../core/types.ts';
import { PRESETS } from '../../core/presets.ts';
import { exportJson } from '../export.ts';
import type { RamConfig, Resolution, Storage } from '../../core/types.ts';

type Stage = 'consent' | 'detect' | 'confirm' | 'measure' | 'report' | 'history';

/**
 * A command block with a copy button.
 *
 * The reason this exists: the command was previously rendered as plain text
 * and the first real user retyped it without the leading `powershell`, from
 * the wrong directory, and got an error that reads as though the script is
 * broken. A command that can be copied cannot be mistyped.
 */
function CopyBlock({ lines }: { lines: string[] }) {
  const [copied, setCopied] = useState(false);
  const text = lines.join('\n');
  return (
    <div style={{ position: 'relative' }}>
      <pre
        className="mono"
        style={{
          background: 'var(--surface-2)', padding: '12px 14px', borderRadius: 'var(--r)',
          fontSize: 12, overflowX: 'auto', border: '1px solid var(--line)', margin: '0 0 8px',
        }}
      >{text}</pre>
      <button
        className="btn no-print"
        style={{ position: 'absolute', top: 8, right: 8 }}
        onClick={() => {
          navigator.clipboard?.writeText(text).then(
            () => { setCopied(true); setTimeout(() => setCopied(false), 1400); },
            () => setCopied(false),
          );
        }}
      >{copied ? 'copied' : 'copy'}</button>
    </div>
  );
}

/**
 * The imported measurement corpus — the ONLY source of real peers. It ships
 * empty, and a screen that dressed that up as a comparison would be inventing
 * social proof, so the peer panel says so plainly instead.
 */
const measuredRecords = (measuredJson as { records: PerfRecord[] }).records ?? [];

const SEVERITY_LABEL: Record<Severity, string> = {
  critical: 'critical',
  major: 'major',
  minor: 'minor',
  ok: 'healthy',
  unknown: 'unknown',
};

export function SystemHealth() {
  const { data } = useApp();
  const [stage, setStage] = useStickyState<Stage>('sh.stage', 'consent');
  const [agreed, setAgreed] = useStickyState('sh.agreed', false);
  const [bench, setBench] = useState<BenchResult | null>(null);
  const [text, setText] = useStickyState('sh.text', '');

  // Confirmed specification. Seeded from the detector, then editable — the
  // detector guesses some fields and being able to correct them matters more
  // than the convenience of not having to.
  const [cpuId, setCpuId] = useStickyState('sh.cpuId', '');
  const [gpuId, setGpuId] = useStickyState('sh.gpuId', '');
  const [ram, setRam] = useStickyState<RamConfig>('sh.ram', { totalGB: 16, channels: 2, speedMTs: 3200, type: 'DDR4' });
  const [ratedMTs, setRatedMTs] = useStickyState<number | ''>('sh.ratedMTs', '');
  const [storage, setStorage] = useStickyState<Storage>('sh.storage', 'nvme-gen4');
  const [resolution, setResolution] = useStickyState<Resolution>('sh.resolution', '1440p');
  const [refreshHz, setRefreshHz] = useStickyState('sh.refreshHz', 144);
  const [pcieGen, setPcieGen] = useStickyState<number | ''>('sh.pcieGen', '');
  const [pcieWidth, setPcieWidth] = useStickyState<number | ''>('sh.pcieWidth', '');
  const [airflow, setAirflow] = useStickyState<'restricted' | 'moderate' | 'good' | 'excellent'>('sh.airflow', 'good');
  const [psuWatts, setPsuWatts] = useStickyState<number | ''>('sh.psuWatts', '');
  const [driverDate, setDriverDate] = useStickyState('sh.driverDate', '');
  const [uptimeDays, setUptimeDays] = useStickyState<number | ''>('sh.uptimeDays', '');

  const [sessions, setSessions] = useState<HealthSession[]>(() => loadSessions());
  const [machineLabel, setMachineLabel] = useStickyState('sh.machineLabel', 'my pc');
  const [sessionNote, setSessionNote] = useStickyState('sh.sessionNote', '');
  const [fixesApplied, setFixesApplied] = useStickyState<AppliedFix[]>('sh.fixesApplied', []);
  const [saved, setSaved] = useState(false);

  const [measurements, setMeasurements] = useStickyState<Measurement[]>('sh.measurements', []);
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

  /**
   * The report, with anything the in-browser run found folded in.
   *
   * Merged rather than shown separately: a person reading "what is wrong" wants
   * one ranked list, and "the integrated graphics are rendering" belongs at the
   * top of it, not in a second panel further down that they may never reach.
   */
  const report = useMemo(() => {
    if (!system) return null;
    const base = diagnose(system, measurements, data);
    if (!bench) return base;
    const gpuRec = data.gpus.get(system.build.gpuId);
    const cpuRec = data.cpus.get(system.build.cpuId);
    // Indices are derived against the catalogue anchors rather than stored on
    // the record, so they are computed the same way the estimator computes them.
    const extra = benchFindings(bench, {
      expectedGpuName: gpuRec?.fullName,
      expectedGpuIndex: gpuRec ? deriveGpuIndex(gpuRec, data.anchorGpu, system.build.ram).index.raster : undefined,
      expectedCpuIndex: cpuRec
        ? deriveCpuIndex(cpuRec, system.build.ram, data.anchorCpu, data.anchorRam).index.throughput
        : undefined,
    });
    const rank: Record<Severity, number> = { critical: 0, major: 1, minor: 2, unknown: 3, ok: 4 };
    const findings = [...extra, ...base.findings].sort((a, b) => rank[a.severity] - rank[b.severity]);
    return {
      ...base,
      findings,
      // Recomputed, not inherited. The verdict is derived FROM the findings, so
      // adding findings without re-deriving it left "Broadly healthy, with 1
      // minor thing worth tidying" sitting directly above a critical finding.
      verdict: deriveVerdict(findings, base.comparisons, measurements),
      recoverablePct: findings.reduce((acc, f) => acc * (1 + (f.estimatedGainPct ?? 0)), 1) - 1,
      // The browser run answers the adapter question directly, so listing it as
      // something this report could not see would be false once it has run.
      notChecked: bench?.gpu?.renderer
        ? base.notChecked.filter((n) => !/rendering on the intended adapter/i.test(n))
        : base.notChecked,
    };
  }, [system, measurements, data, bench]);

  const thisMachineId = system ? machineIdOf(system) : '';
  const machineHistory = useMemo(
    () => (thisMachineId ? historyFor(sessions, thisMachineId) : []),
    [sessions, thisMachineId],
  );

  /**
   * Peer comparison, one per measurement. Own history is drawn from earlier
   * sessions of THIS machine at identical settings — real data, but n=1
   * machine, which the comparison says out loud rather than passing off as
   * peer evidence.
   */
  const peerComparisons = useMemo(() => {
    if (!system) return [];
    return measurements.map((m) => {
      const key = measurementKey(m);
      const own = machineHistory
        .flatMap((s) => s.measurements.filter((x) => measurementKey(x) === key))
        .map((x) => x.avgFps);
      const peers = findPeers(measuredRecords, {
        cpuId: system.build.cpuId, gpuId: system.build.gpuId,
        gameId: m.gameId, resolution: m.resolution, preset: m.preset,
      });
      return {
        label: `${data.games.get(m.gameId)?.name ?? m.gameId} at ${m.resolution}, ${m.preset ?? 'high'}`,
        c: comparePeers({ avgFps: m.avgFps, gameId: m.gameId, resolution: m.resolution, preset: m.preset }, peers, own),
      };
    });
  }, [system, measurements, machineHistory, data]);

  const saveSession = () => {
    if (!system || !report) return;
    const s: HealthSession = {
      id: `hs-${Date.now().toString(36)}`,
      machineId: thisMachineId,
      machineLabel: machineLabel.trim() || 'my pc',
      at: new Date().toISOString(),
      system,
      measurements,
      findings: report.findings.map((f) => ({ id: f.id, title: f.title, severity: f.severity, estimatedGainPct: f.estimatedGainPct })),
      fixesApplied,
      note: sessionNote.trim() || undefined,
    };
    setSessions(addSession(s));
    setSaved(true);
    setFixesApplied([]);
    setSessionNote('');
  };

  /**
   * A changed specification or a new measurement makes this a DIFFERENT check,
   * so the save button has to come back.
   *
   * Without this the flag stuck after the first save and the button stayed
   * disabled for the rest of the session — which silently broke the entire
   * before-and-after feature, since a second session could never be recorded.
   * Found by driving the flow rather than by reading it.
   */
  useEffect(() => {
    setSaved(false);
  }, [system, measurements]);

  /** Findings outstanding at the most recent session — the candidates to mark fixed. */
  const outstanding = machineHistory.length
    ? machineHistory[machineHistory.length - 1].findings
    : [];

  const STAGES: { key: Stage; label: string; hint: string }[] = [
    { key: 'consent', label: 'What this reads', hint: 'and what it does not' },
    { key: 'detect', label: 'Read the machine', hint: 'or type it in' },
    { key: 'confirm', label: 'Check the specs', hint: 'correct anything wrong' },
    { key: 'measure', label: 'Add benchmarks', hint: 'optional, but it is the point' },
    { key: 'report', label: 'The verdict', hint: 'what is wrong and what it costs' },
    { key: 'history', label: 'Over time', hint: 'did the fixes work' },
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
        {/* The flow now remembers where you were, so it needs a way to forget.
            Saved sessions are not touched: those live in their own store and are
            the whole point of coming back. */}
        {stage !== 'consent' && (
          <button
            className="btn no-print"
            style={{ marginTop: 10 }}
            title="Forget this check and go back to the start. Saved sessions are kept."
            onClick={() => {
              clearSticky('sh.');
              setStage('consent'); setAgreed(false); setText(''); setBench(null);
              setCpuId(''); setGpuId('');
              setRam({ totalGB: 16, channels: 2, speedMTs: 3200, type: 'DDR4' });
              setRatedMTs(''); setStorage('nvme-gen4'); setResolution('1440p'); setRefreshHz(144);
              setPcieGen(''); setPcieWidth(''); setAirflow('good'); setPsuWatts('');
              setDriverDate(''); setUptimeDays('');
              setMachineLabel('my pc'); setSessionNote('');
              setFixesApplied([]); setMeasurements([]);
            }}
          >
            start this check again
          </button>
        )}
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
              Open PowerShell, then run <b>both</b> of these. The first line moves to the folder holding
              the script — PowerShell starts in <span className="mono">C:\\WINDOWS\\system32</span>, where
              the script is not, and running the second line on its own from there fails:
            </p>
            <CopyBlock
              lines={[
                'cd "$env:USERPROFILE\\Downloads\\rigcheck\\harness"',
                'powershell -ExecutionPolicy Bypass -File .\\detect-hardware.ps1',
              ]}
            />
            <p className="mini">
              Change the first line to wherever you actually put the folder. The word{' '}
              <span className="mono">powershell</span> at the start of the second line is not optional:
              without it PowerShell reads <span className="mono">-ExecutionPolicy</span> as a command name
              and answers{' '}
              <span className="mono">The term '-ExecutionPolicy' is not recognized</span>.
            </p>
            <p className="mini">
              It writes <span className="mono">out/hardware-&lt;timestamp&gt;.json</span>. Open it, read it
              — it is short and human-readable — then paste it below. A dxdiag dump or a line you typed
              from memory works too; anything ambiguous is asked about rather than assumed.
            </p>

            <BrowserBenchPanel
              onResult={setBench}
              expectedGpuName={data.gpus.get(gpuId)?.fullName}
            />
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
      {/* Reaching the verdict without a processor and card chosen renders nothing
          at all — a blank screen with no explanation, which reads as a broken
          app rather than as a missing input. Say what is missing and offer the
          way back. */}
      {stage === 'report' && !(report && system) && (
        <div className="panel">
          <div className="panel-head"><h2>Nothing to judge yet</h2></div>
          <div className="panel-body">
            <p className="mini" style={{ marginTop: 0 }}>
              The verdict needs to know which processor and graphics card are in the machine. Nothing
              read them and nothing was typed in, so there is nothing here to compare against.
            </p>
            <div className="wizard-nav">
              <button className="btn primary" onClick={() => setStage('confirm')}>choose the parts</button>
              <button className="btn" onClick={() => setStage('detect')}>go back and read the machine</button>
            </div>
          </div>
        </div>
      )}

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
                    <FixGuideBlock findingId={f.id} />
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

          <PeerPanel comparisons={peerComparisons} />

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

          <div className="panel">
            <div className="panel-head">
              <h2>Keep this, so you can compare later</h2>
              <span className="spacer" />
              {machineHistory.length > 0 && <span className="mini">{machineHistory.length} earlier session(s) on this machine</span>}
            </div>
            <div className="panel-body">
              <p className="mini" style={{ marginTop: 0 }}>
                A single check is a snapshot. Saving it means the next one can answer the two questions this screen
                cannot answer on its own — did the fix work, and is the machine getting slower. Stored in this browser
                only; nothing is sent anywhere.
              </p>

              <div className="grid two">
                <div className="field">
                  <label>what to call this machine</label>
                  <input type="text" value={machineLabel} onChange={(e) => setMachineLabel(e.target.value)} placeholder="my pc" />
                  <span className="mini">
                    Identity is the CPU and GPU pairing, so swapping memory or a drive keeps the history — that is the
                    point. Changing the CPU or GPU starts a new machine.
                  </span>
                </div>
                <div className="field">
                  <label>note (optional)</label>
                  <input type="text" value={sessionNote} onChange={(e) => setSessionNote(e.target.value)} placeholder="e.g. after adding the second RAM stick" />
                </div>
              </div>

              {outstanding.length > 0 && (
                <div className="field">
                  <label>have you fixed anything since the last check?</label>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {outstanding.map((f) => {
                      const on = fixesApplied.some((x) => x.findingId === f.id);
                      return (
                        <button
                          key={f.id}
                          className={`toggle${on ? ' on' : ''}`}
                          onClick={() =>
                            setFixesApplied((cur) =>
                              on
                                ? cur.filter((x) => x.findingId !== f.id)
                                : [...cur, { findingId: f.id, title: f.title, predictedGainPct: f.estimatedGainPct, appliedAt: new Date().toISOString() }],
                            )
                          }
                        >
                          {f.title}
                          {f.estimatedGainPct != null && <span className="sub"> (+{Math.round(f.estimatedGainPct * 100)}% predicted)</span>}
                        </button>
                      );
                    })}
                  </div>
                  <span className="mini">
                    Marking a fix lets the next measurement check the prediction against reality. Mark one at a time if
                    you can — several applied together share one measurement and cannot be told apart afterwards.
                  </span>
                </div>
              )}

              <div className="wizard-nav">
                <button className="btn" onClick={saveSession} disabled={saved}>
                  {saved ? 'saved' : machineHistory.length ? 'save this check' : 'save this as the baseline'}
                </button>
                {machineHistory.length > 0 && (
                  <button className="btn" onClick={() => setStage('history')}>see the history</button>
                )}
              </div>
            </div>
          </div>

          <div className="wizard-nav">
            <button className="btn" onClick={() => setStage('measure')}>back</button>
            <button className="btn" onClick={() => setStage('confirm')}>change the specs</button>
          </div>
        </>
      )}

      {/* --------------------------------------------------------- history -- */}
      {stage === 'history' && (
        machineHistory.length === 0 ? (
          <div className="panel">
            <div className="panel-head"><h2>Nothing on record yet</h2></div>
            <div className="panel-body">
              <div className="empty">
                No saved sessions for this machine. Run a check, save it as the baseline, then come back after you
                change something — that is when this becomes useful.
              </div>
              <div className="wizard-nav">
                <button className="btn" onClick={() => setStage('report')}>back to the report</button>
              </div>
            </div>
          </div>
        ) : (
          <>
            <HistoryPanel
              sessions={machineHistory}
              gameName={(id) => data.games.get(id)?.name ?? id}
              onDelete={(id) => setSessions(removeSession(id))}
            />
            <div className="wizard-nav">
              <button className="btn" onClick={() => setStage('report')}>back to the report</button>
            </div>
          </>
        )
      )}
    </>
  );
}

/* ---------------------------------------------------------- fix guide ---- */

/**
 * The walkthrough behind a finding.
 *
 * Collapsed by default: a report showing six findings each with a twelve-step
 * guide is a wall nobody reads. Opened, it is everything needed to actually do
 * the job — what to have ready, how long, the steps in order, how to check it
 * worked, and what goes wrong.
 */
function FixGuideBlock({ findingId }: { findingId: string }) {
  const [open, setOpen] = useState(false);
  const guide = guideFor(findingId);
  if (!guide) return null;
  return (
    <>
      <button className="btn" style={{ marginTop: 8 }} onClick={() => setOpen(!open)}>
        {open ? 'hide the walkthrough' : `show me how — ${guide.steps.length} steps, about ${guide.minutes} min`}
      </button>
      {open && (
        <div className="guide">
          <div className="guide-meta">
            <span><b>{guide.summary}</b></span>
            <span className="spacer" style={{ flex: 1 }} />
            <span>{DIFFICULTY_LABEL[guide.difficulty]}</span>
            <span>about {guide.minutes} minutes</span>
          </div>

          <h5>before you start</h5>
          <ul>{guide.needs.map((n) => <li key={n}>{n}</li>)}</ul>

          <h5>steps</h5>
          <ol>
            {guide.steps.map((s, i) => (
              <li key={i}>
                {s.do}
                {s.detail && <span className="d">{s.detail}</span>}
              </li>
            ))}
          </ol>

          <h5>how to know it worked</h5>
          <p className="verify" style={{ margin: 0 }}>{guide.verify}</p>

          <h5>what can go wrong</h5>
          <ul className="risk">{guide.risks.map((r) => <li key={r}>{r}</li>)}</ul>
        </div>
      )}
    </>
  );
}

/* ------------------------------------------------------------- history --- */

/** A measurement's history as a sparkline. Five points do not need a library. */
function Sparkline({ points }: { points: { at: string; avgFps: number }[] }) {
  const W = 260;
  const H = 34;
  if (points.length < 2) return <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ maxWidth: W }} />;
  const vals = points.map((p) => p.avgFps);
  const lo = Math.min(...vals);
  const hi = Math.max(...vals);
  const span = hi - lo < 1e-9 ? Math.max(hi * 0.1, 1) : (hi - lo) * 1.25;
  const mid = (hi + lo) / 2;
  const yLo = mid - span / 2;
  const x = (i: number) => 2 + (i / (points.length - 1)) * (W - 4);
  const y = (v: number) => 4 + (1 - (v - yLo) / span) * (H - 8);
  const declining = vals[vals.length - 1] < vals[0] * 0.96;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ maxWidth: W }} role="img" aria-label={`${points.length} measurements over time`}>
      <polyline
        points={vals.map((v, i) => `${x(i)},${y(v)}`).join(' ')}
        fill="none"
        stroke={declining ? 'var(--bad)' : 'var(--chart-series)'}
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
      {vals.map((v, i) => (
        <circle key={i} cx={x(i)} cy={y(v)} r={i === vals.length - 1 ? 3 : 2} fill={declining ? 'var(--bad)' : 'var(--chart-series)'}>
          <title>{`${points[i].at.slice(0, 10)} — ${v.toFixed(0)}fps`}</title>
        </circle>
      ))}
    </svg>
  );
}

function HistoryPanel({
  sessions,
  gameName,
  onDelete,
}: {
  sessions: HealthSession[];
  gameName: (id: string) => string;
  onDelete: (id: string) => void;
}) {
  const degradation = useMemo(() => detectDegradation(sessions), [sessions]);
  const keys = useMemo(() => trackableKeys(sessions), [sessions]);
  const verdicts = useMemo(() => {
    // Verify each session's fixes against the session immediately before it,
    // which is the only pairing where "what changed" is answerable.
    const ordered = [...sessions].sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
    return ordered.flatMap((s, i) => (i === 0 ? [] : verifyFixes(ordered[i - 1], s)));
  }, [sessions]);

  const pct = (x: number) => `${x >= 0 ? '+' : ''}${(x * 100).toFixed(0)}%`;

  return (
    <>
      {verdicts.length > 0 && (
        <div className="panel">
          <div className="panel-head">
            <h2>Did the fixes work?</h2>
            <span className="spacer" />
            <span className="mini">measured, not predicted</span>
          </div>
          <div className="panel-body">
            {verdicts.map((v, i) => (
              <div key={i} className={`finding ${v.outcome === 'delivered' ? 'ok' : v.outcome === 'worse' ? 'critical' : v.outcome === 'partial' ? 'minor' : 'major'}`}>
                <div className="head">
                  <span className={`pill ${v.outcome === 'delivered' ? 'ok' : v.outcome === 'worse' ? 'critical' : v.outcome === 'unverifiable' ? 'watch' : 'risk'}`}>
                    {v.outcome === 'delivered' ? 'worked' : v.outcome === 'partial' ? 'partly worked' : v.outcome === 'no-change' ? 'no change' : v.outcome === 'worse' ? 'got worse' : 'cannot tell'}
                  </span>
                  <b>{v.fix.title}</b>
                  <span className="spacer" style={{ flex: 1 }} />
                  {v.actualGainPct != null && (
                    <span className="gain" style={{ color: v.actualGainPct >= 0.02 ? 'var(--good)' : 'var(--bad)' }}>
                      {pct(v.actualGainPct)} measured
                      {v.predictedGainPct != null && <span className="sub"> vs {pct(v.predictedGainPct)} predicted</span>}
                    </span>
                  )}
                </div>
                <div className="row">{v.detail}</div>
                {v.modelDisagreement && (
                  <div className="row" style={{ color: 'var(--spec)' }}>
                    <span className="k">note</span>
                    This is a miss by the model, not just by the fix. The prediction and the measurement disagree by more
                    than half the prediction's own size, which is worth more than the fix itself: it is the kind of
                    evidence that would let the model be corrected. Export this history and it can be.
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="panel">
        <div className="panel-head">
          <h2>Is it getting slower?</h2>
          <span className="spacer" />
          {degradation.span && <span className="mini">{degradation.span.days} days on record</span>}
        </div>
        <div className="panel-body">
          <div className={`note${degradation.degraded ? ' bad' : ''}`} style={{ marginBottom: 12 }}>
            {degradation.detail}
          </div>

          {degradation.changes.length > 0 && (
            <>
              <div className="mini" style={{ marginBottom: 4 }}>what changed between the first and latest session</div>
              <ul style={{ fontSize: 12, lineHeight: 1.6, color: 'var(--muted)', margin: '0 0 12px', paddingLeft: 18 }}>
                {degradation.changes.map((c) => <li key={c} className="mono">{c}</li>)}
              </ul>
            </>
          )}

          {keys.length === 0 ? (
            <div className="empty">
              Nothing is measured twice at the same settings yet. Pick one game, note the exact preset, and re-run it
              every time — that repeated measurement is the whole basis of a history.
            </div>
          ) : (
            keys.map((k) => {
              const series = seriesFor(sessions, k.key);
              const first = series[0];
              const last = series[series.length - 1];
              const delta = first && last ? last.avgFps / first.avgFps - 1 : 0;
              const parts = k.key.split('|');
              return (
                <div className="trend" key={k.key}>
                  <div className="who">
                    {gameName(k.gameId)}
                    <span className="sub mono">{parts[1]} · {parts[2]}{parts[3] !== 'none' ? ` · ${parts[3]} ${parts[4]}` : ''}{parts[6] === 'on' ? ' · RT' : ''}</span>
                  </div>
                  <Sparkline points={series} />
                  <div className="now">
                    {last?.avgFps.toFixed(0)}fps
                    <span className="delta" style={{ color: delta < -0.04 ? 'var(--bad)' : delta > 0.04 ? 'var(--good)' : 'var(--faint)' }}>
                      {pct(delta)} over {series.length} runs
                    </span>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>

      <div className="panel">
        <div className="panel-head">
          <h2>Sessions</h2>
          <span className="spacer" />
          <button className="btn" onClick={() => exportJson('rigcheck-machine-history.json', sessions)}>export history</button>
        </div>
        <div className="panel-body">
          <p className="mini" style={{ marginTop: 0 }}>
            Stored in this browser only. Export if you care about keeping it — a cleared cache takes the history with it,
            and six months of measurements is not something to lose to a browser setting.
          </p>
          <div className="timeline">
            {[...sessions].reverse().map((s) => (
              <div key={s.id} className={`timeline-item${s.fixesApplied.length ? ' has-fix' : ''}`}>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap' }}>
                  <span className="mono" style={{ fontSize: 12 }}>{s.at.slice(0, 16).replace('T', ' ')}</span>
                  <span className="sub" style={{ fontSize: 12 }}>
                    {s.measurements.length} measurement{s.measurements.length === 1 ? '' : 's'}, {s.findings.length} finding{s.findings.length === 1 ? '' : 's'}
                  </span>
                  {s.fixesApplied.map((f) => (
                    <span key={f.findingId} className="tag good">fixed: {f.title}</span>
                  ))}
                  <span className="spacer" style={{ flex: 1 }} />
                  <button className="btn" onClick={() => onDelete(s.id)}>delete</button>
                </div>
                {s.note && <div className="mini" style={{ marginTop: 2 }}>{s.note}</div>}
              </div>
            ))}
          </div>
        </div>
      </div>
    </>
  );
}

/* ---------------------------------------------------------------- peers --- */

function PeerPanel({ comparisons }: { comparisons: { label: string; c: PeerComparison }[] }) {
  if (!comparisons.length) return null;
  return (
    <div className="panel">
      <div className="panel-head"><h2>Compared with other machines</h2></div>
      <div className="panel-body">
        {comparisons.map(({ label, c }) => (
          <div key={label} style={{ marginBottom: 14 }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 4 }}>
              <b style={{ fontSize: 13, fontWeight: 500 }}>{label}</b>
              <span className={`pill ${c.tier === 'real-peers' ? 'ok' : c.tier === 'own-history' ? 'watch' : 'risk'}`}>
                {c.tier === 'real-peers' ? `${c.samples.length} real peer${c.samples.length === 1 ? '' : 's'}` : c.tier === 'own-history' ? 'your own history only' : 'no peers exist'}
              </span>
            </div>
            <p className="mini" style={{ margin: 0 }}>{c.detail}</p>
            {c.nextStep && <p className="mini" style={{ margin: '4px 0 0', color: 'var(--spec)' }}>{c.nextStep}</p>}
          </div>
        ))}
      </div>
    </div>
  );
}
