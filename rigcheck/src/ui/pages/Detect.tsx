import { useMemo, useState } from 'react';
import { useApp, makeBuild } from '../store.ts';
import { detectHardware, detectionToBuild } from '../../core/detect.ts';
import { fmt } from '../components/Parts.tsx';
import { estimate } from '../../core/engine.ts';
import { useNavigate } from 'react-router-dom';

const SAMPLE = `Operating System: Windows 11 Pro 64-bit
Processor: AMD Ryzen 5 3600 6-Core Processor (12 CPUs), ~3.6GHz
Memory: 16384MB RAM
Card name: NVIDIA GeForce RTX 3060
Display Memory: 12288 MB
Display Mode: 2560 x 1440 (144Hz)`;

/**
 * Identify a machine from whatever text the operator can get hold of — a dxdiag
 * dump, the harness detector's JSON, a retailer's spec blurb, or a line typed
 * from memory — rather than making them hand-pick two parts out of 707.
 *
 * Candidates are shown with confidence rather than silently resolved: choosing
 * wrongly between a 3GB and 6GB GTX 1060 would corrupt every number downstream,
 * so an ambiguous match is a question, not an assumption.
 */
export function Detect() {
  const { data, builds, setBuilds } = useApp();
  const [text, setText] = useState(SAMPLE);
  const navigate = useNavigate();

  const result = useMemo(() => (text.trim() ? detectHardware(text, data) : null), [text, data]);
  const candidate = useMemo(() => (result ? detectionToBuild(result, 'detected') : null), [result]);

  const preview = useMemo(() => {
    if (!candidate) return null;
    return estimate(candidate, 'cyberpunk-2077', candidate.target.resolution, data);
  }, [candidate, data]);

  const apply = () => {
    if (!candidate) return;
    setBuilds([{ ...makeBuild(), ...candidate, id: builds[0]?.id ?? 'detected', label: 'detected machine' }, ...builds.slice(1)]);
    navigate('/analyser');
  };

  return (
    <>
      <div className="page-head">
        <h1>Identify a machine from a spec</h1>
        <p>
          Paste anything that describes the hardware — a dxdiag dump, <span className="mono">systeminfo</span>,
          the JSON from <span className="mono">harness/detect-hardware.ps1</span>, a listing, or just a line
          you typed. Matches are shown with confidence; anything ambiguous is asked about rather than assumed.
        </p>
      </div>

      <div className="grid two">
        <div className="panel">
          <div className="panel-head">
            <h2 className="micro">input</h2>
            <span className="spacer" />
            <button className="btn" onClick={() => setText(SAMPLE)}>sample</button>
            <button className="btn" onClick={() => setText('')}>clear</button>
          </div>
          <div className="panel-body">
            <textarea
              value={text}
              onChange={(e) => setText(e.target.value)}
              aria-label="Paste hardware description, dxdiag output, or harness JSON"
              placeholder="paste a dxdiag dump, systeminfo output, harness JSON, or just: Ryzen 5 3600 / RTX 3060"
              style={{ minHeight: 260 }}
            />
          </div>
        </div>

        <div>
          <div className="panel">
            <div className="panel-head"><h2 className="micro">what was recognised</h2></div>
            <div className="panel-body">
              {!result && <div className="empty">Paste something to identify.</div>}
              {result && (
                <>
                  {(['cpu', 'gpu'] as const).map((kind) => {
                    const hits = result[kind];
                    return (
                      <div key={kind} style={{ marginBottom: 14 }}>
                        <div style={{ fontSize: 10, letterSpacing: '0.08em', color: 'var(--faint)', textTransform: 'uppercase', marginBottom: 4 }}>
                          {kind}
                        </div>
                        {hits.length === 0 && <div className="mini">Nothing matched.</div>}
                        {hits.slice(0, 3).map((h, i) => (
                          <div key={h.record.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '3px 0' }}>
                            <span className="meter" style={{ width: 54 }}>
                              <i className={h.confidence > 85 ? 'good' : h.confidence > 65 ? '' : 'warn'} style={{ width: `${h.confidence}%` }} />
                            </span>
                            <span className="mono" style={{ fontSize: 12, color: i === 0 ? 'var(--ink)' : 'var(--faint)' }}>
                              {h.record.fullName}
                            </span>
                            <span className="mini">{h.confidence}%</span>
                          </div>
                        ))}
                      </div>
                    );
                  })}

                  <dl className="kv">
                    {result.ram?.totalGB && <><dt>memory</dt><dd>{result.ram.totalGB}GB · {result.ram.channels ?? '?'}ch · {result.ram.speedMTs ?? '?'} MT/s</dd></>}
                    {result.storage && <><dt>storage</dt><dd>{result.storage}</dd></>}
                    {result.resolution && <><dt>display</dt><dd>{result.resolution}{result.refreshHz ? ` @ ${result.refreshHz}Hz` : ''}</dd></>}
                    {result.driverVersion && <><dt>driver</dt><dd>{result.driverVersion}</dd></>}
                  </dl>

                  {result.warnings.map((w, i) => (
                    <div className="note warn" key={i} style={{ marginTop: 8 }}>{w}</div>
                  ))}
                  {result.unmatched.map((u, i) => (
                    <div className="note bad" key={i} style={{ marginTop: 8 }}>
                      Not in the catalogue: {u}. Either the part is missing from the seed data, or the name
                      is written differently — search for it manually to check.
                    </div>
                  ))}
                </>
              )}
            </div>
          </div>

          {candidate && (
            <div className="panel">
              <div className="panel-head">
                <h2 className="micro">resulting build</h2>
                <span className="spacer" />
                <button className="btn primary" onClick={apply}>use this build →</button>
              </div>
              <div className="panel-body">
                <dl className="kv">
                  <dt>CPU</dt><dd>{data.cpus.get(candidate.cpuId)?.fullName}</dd>
                  <dt>GPU</dt><dd>{data.gpus.get(candidate.gpuId)?.fullName}</dd>
                  <dt>memory</dt><dd>{candidate.ram.totalGB}GB {candidate.ram.channels}-channel {candidate.ram.type}</dd>
                  <dt>target</dt><dd>{candidate.target.resolution} @ {candidate.target.refreshHz}Hz</dd>
                </dl>
                {preview?.status === 'ok' && (
                  <div className="note" style={{ marginTop: 10 }}>
                    Sanity check — Cyberpunk 2077 at {candidate.target.resolution}: <b className="mono">{fmt(preview.avgFps)} fps</b>.
                    If that looks wildly wrong for this machine, the match is probably on the wrong variant.
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </>
  );
}
