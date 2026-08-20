import { useMemo, useState } from 'react';
import { useApp } from '../store.ts';
import { deriveGpuIndex, deriveCpuIndex } from '../../core/indices.ts';
import { ANCHOR_RAM } from '../../core/catalogue.ts';
import { fmt } from '../components/Parts.tsx';
import { ANCHORS } from '../../core/constants.ts';
import { exportCsv } from '../export.ts';
import { PartPicker } from '../components/Parts.tsx';

type Tab = 'gpu' | 'cpu' | 'game' | 'compare' | 'provenance';

export function DataExplorer() {
  const { data } = useApp();
  const [tab, setTab] = useState<Tab>('gpu');
  const [q, setQ] = useState('');
  const [sort, setSort] = useState<'index' | 'name' | 'date'>('index');
  // Capability filters: find parts by what they can DO, not by name. "Every
  // 16GB+ mesh-shader card" is a question the search box cannot express.
  const [minVram, setMinVram] = useState(0);
  const [needMesh, setNeedMesh] = useState(false);
  const [needRt, setNeedRt] = useState(false);
  const [diffA, setDiffA] = useState('nvidia-geforce-rtx-3060-12gb');
  const [diffB, setDiffB] = useState('nvidia-geforce-rtx-4060');

  const gpuRows = useMemo(() => {
    const rows = [...data.gpus.values()].map((g) => ({
      g,
      idx: deriveGpuIndex(g, data.anchorGpu, ANCHOR_RAM),
    }));
    rows.sort((a, b) =>
      sort === 'index' ? b.idx.index.raster - a.idx.index.raster
      : sort === 'date' ? (b.g.launchDate ?? '').localeCompare(a.g.launchDate ?? '')
      : a.g.fullName.localeCompare(b.g.fullName));
    return rows.filter(
      (r) =>
        (!q || r.g.fullName.toLowerCase().includes(q.toLowerCase())) &&
        (minVram === 0 || (r.g.vramGB ?? 0) >= minVram) &&
        (!needMesh || r.g.caps.meshShaders) &&
        (!needRt || r.g.caps.rayTracing),
    );
  }, [data, q, sort, minVram, needMesh, needRt]);

  const cpuRows = useMemo(() => {
    const rows = [...data.cpus.values()].map((c) => ({
      c,
      idx: deriveCpuIndex(c, ANCHOR_RAM, data.anchorCpu, ANCHOR_RAM),
    }));
    rows.sort((a, b) =>
      sort === 'index' ? b.idx.index.throughput - a.idx.index.throughput
      : sort === 'date' ? (b.c.launchDate ?? '').localeCompare(a.c.launchDate ?? '')
      : a.c.fullName.localeCompare(b.c.fullName));
    return rows.filter((r) => !q || r.c.fullName.toLowerCase().includes(q.toLowerCase()));
  }, [data, q, sort]);

  return (
    <>
      <div className="page-head">
        <h1>Data Explorer</h1>
        <p>
          The catalogue behind every estimate, with derived indices shown alongside the specs they came
          from. Both indices are anchored at 100 on the reference parts.
        </p>
      </div>

      <div className="panel">
        <div className="panel-head">
          <div className="toggle-row">
            {(['gpu', 'cpu', 'game', 'compare', 'provenance'] as Tab[]).map((t) => (
              <button key={t} className={`toggle${tab === t ? ' on' : ''}`} onClick={() => setTab(t)}>
                {t === 'gpu' ? `GPUs (${data.gpus.size})` : t === 'cpu' ? `CPUs (${data.cpus.size})` : t === 'game' ? `games (${data.games.size})` : t === 'compare' ? 'compare parts' : 'provenance'}
              </button>
            ))}
          </div>
          <span className="spacer" />
          {(tab === 'gpu' || tab === 'cpu') && (
            <>
              <div className="toggle-row" style={{ marginRight: 8 }}>
                {(['index', 'name', 'date'] as const).map((s) => (
                  <button key={s} className={`toggle${sort === s ? ' on' : ''}`} onClick={() => setSort(s)}>{s}</button>
                ))}
              </div>
              <input
                type="text"
                value={q}
                placeholder="filter…"
                onChange={(e) => setQ(e.target.value)}
                style={{ width: 150 }}
              />
              <button
                className="btn"
                style={{ marginLeft: 6 }}
                onClick={() =>
                  exportCsv(
                    `rigcheck-${tab}s.csv`,
                    tab === 'gpu'
                      ? gpuRows.map(({ g, idx }) => ({ id: g.id, name: g.fullName, arch: g.architecture, raster_index: idx.index.raster.toFixed(1), rt_index: idx.index.rt.toFixed(1), shaders: g.shaders ?? '', boost_mhz: g.boostClockMHz ?? '', vram_gb: g.vramGB ?? '', bandwidth_gbs: g.memBandwidthGBs ?? '', tdp_w: g.tdpW ?? '', mesh_shaders: g.caps.meshShaders, ray_tracing: g.caps.rayTracing, dx_level: g.caps.dxFeatureLevel, driver_status: g.driverStatus }))
                      : cpuRows.map(({ c, idx }) => ({ id: c.id, name: c.fullName, arch: c.architecture, throughput: idx.index.throughput.toFixed(1), cache_endowment: idx.index.cacheEndowment.toFixed(2), cores: c.cores, threads: c.threads, socket: c.socket, memory: c.memoryType.join('/'), l3_mb: c.l3CacheMB ?? '', tdp_w: c.tdpW ?? '', vcache: !!c.vcache })),
                  )
                }
              >
                CSV
              </button>
            </>
          )}
        </div>

        {tab === 'gpu' && (
          <div className="panel-body" style={{ paddingTop: 8, paddingBottom: 8, borderBottom: '1px solid var(--line)' }}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
              <span className="mini">capability filter</span>
              <div className="toggle-row">
                {[0, 8, 12, 16, 24].map((v) => (
                  <button key={v} className={`toggle${minVram === v ? ' on' : ''}`} onClick={() => setMinVram(v)}>
                    {v === 0 ? 'any VRAM' : `${v}GB+`}
                  </button>
                ))}
              </div>
              <button className={`toggle${needMesh ? ' on' : ''}`} onClick={() => setNeedMesh(!needMesh)}>mesh shaders</button>
              <button className={`toggle${needRt ? ' on' : ''}`} onClick={() => setNeedRt(!needRt)}>ray tracing</button>
              <span className="mini">{gpuRows.length} match</span>
            </div>
          </div>
        )}

        {tab === 'gpu' && (
          <div className="table-wrap" style={{ maxHeight: '70vh', overflowY: 'auto' }}>
            <table className="data">
              <thead>
                <tr>
                  <th>part</th><th>arch</th><th className="n">raster idx</th><th className="n">rt idx</th>
                  <th className="n">shaders</th><th className="n">boost</th><th className="n">vram</th>
                  <th className="n">bw</th><th className="n">tdp</th><th>gates</th>
                </tr>
              </thead>
              <tbody>
                {gpuRows.slice(0, 400).map(({ g, idx }) => (
                  <tr key={g.id}>
                    <td>
                      {g.fullName}
                      {g.id === ANCHORS.gpuId && <span className="tag" style={{ marginLeft: 6 }}>anchor</span>}
                      {g.formFactor === 'igpu' && <span className="tag" style={{ marginLeft: 6 }}>igpu</span>}
                    </td>
                    <td className="sub">{g.architecture}</td>
                    <td className="n" style={{ fontSize: 13 }}>{fmt(idx.index.raster, 1)}</td>
                    <td className="n sub">{idx.index.rt ? fmt(idx.index.rt, 1) : '—'}</td>
                    <td className="n sub">{g.shaders ?? '—'}</td>
                    <td className="n sub">{g.boostClockMHz ?? '—'}</td>
                    <td className="n sub">{g.vramGB != null ? `${g.vramGB}GB` : 'shared'}</td>
                    <td className="n sub">{g.memBandwidthGBs ?? '—'}</td>
                    <td className="n sub">{g.tdpW ?? '—'}</td>
                    <td>
                      {g.caps.meshShaders && <span className="tag gpu" title="mesh shaders">MS</span>}{' '}
                      {g.caps.rayTracing && <span className="tag" title="hardware ray tracing">RT</span>}{' '}
                      <span className="tag" title="DirectX feature level">{g.caps.dxFeatureLevel}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {tab === 'cpu' && (
          <div className="table-wrap" style={{ maxHeight: '70vh', overflowY: 'auto' }}>
            <table className="data">
              <thead>
                <tr>
                  <th>part</th><th>arch</th><th className="n">throughput</th><th className="n">cache</th>
                  <th className="n">latency</th><th className="n">threads</th><th>socket</th><th>memory</th>
                </tr>
              </thead>
              <tbody>
                {cpuRows.slice(0, 400).map(({ c, idx }) => (
                  <tr key={c.id}>
                    <td>
                      {c.fullName}
                      {c.id === ANCHORS.cpuId && <span className="tag" style={{ marginLeft: 6 }}>anchor</span>}
                      {c.vcache && <span className="tag cpu" style={{ marginLeft: 6 }}>3D</span>}
                    </td>
                    <td className="sub">{c.architecture}</td>
                    <td className="n" style={{ fontSize: 13 }}>{fmt(idx.index.throughput, 1)}</td>
                    <td className="n sub" title="cache endowment, 1.00 = reference">{idx.index.cacheEndowment.toFixed(2)}</td>
                    <td className="n sub" title="latency score, higher is better">{idx.index.latencyScore.toFixed(2)}</td>
                    <td className="n sub">{c.cores}C/{c.threads}T</td>
                    <td className="sub">{c.socket}</td>
                    <td className="sub">{c.memoryType.join('/')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {tab === 'game' && (
          <div className="table-wrap" style={{ maxHeight: '70vh', overflowY: 'auto' }}>
            <table className="data">
              <thead>
                <tr>
                  <th>title</th><th>archetype</th><th className="n">year</th><th>engine</th>
                  <th className="n">vram 1440p</th><th>gates</th><th>bench</th>
                </tr>
              </thead>
              <tbody>
                {[...data.games.values()].map((g) => (
                  <tr key={g.id}>
                    <td>{g.name}{g.coreLoop && <span className="tag" style={{ marginLeft: 6 }}>core 12</span>}</td>
                    <td className="sub">{g.archetype}</td>
                    <td className="n sub">{g.year}</td>
                    <td className="sub">{g.engine ?? '—'}</td>
                    <td className="n sub">{g.vramDemandGB['1440p'] ?? '—'}</td>
                    <td>
                      {g.requirements.meshShaders && <span className="tag vram">mesh shaders</span>}{' '}
                      {g.requirements.rayTracingRequired && <span className="tag vram">RT required</span>}{' '}
                      {g.fpsCap && <span className="tag engine-cap">cap {g.fpsCap}</span>}
                    </td>
                    <td className="sub">{g.builtInBenchmark ? 'built-in' : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {tab === 'compare' && (
          <div className="panel-body">
            <div className="grid two" style={{ marginBottom: 12 }}>
              <PartPicker kind="gpu" label="part A" value={diffA} onChange={setDiffA} />
              <PartPicker kind="gpu" label="part B" value={diffB} onChange={setDiffB} />
            </div>
            {(() => {
              const a = data.gpus.get(diffA);
              const b = data.gpus.get(diffB);
              if (!a || !b) return <div className="empty">Pick two parts.</div>;
              const ia = deriveGpuIndex(a, data.anchorGpu, ANCHOR_RAM).index;
              const ib = deriveGpuIndex(b, data.anchorGpu, ANCHOR_RAM).index;
              const rows: { field: string; a: string | number; b: string | number; delta?: string }[] = [
                { field: 'raster index', a: ia.raster.toFixed(1), b: ib.raster.toFixed(1), delta: `${(((ib.raster - ia.raster) / ia.raster) * 100).toFixed(1)}%` },
                { field: 'RT index', a: ia.rt.toFixed(1), b: ib.rt.toFixed(1) },
                { field: 'architecture', a: a.architecture, b: b.architecture },
                { field: 'shaders', a: a.shaders ?? '—', b: b.shaders ?? '—' },
                { field: 'boost clock', a: a.boostClockMHz ?? '—', b: b.boostClockMHz ?? '—' },
                { field: 'VRAM', a: a.vramGB != null ? `${a.vramGB}GB ${a.vramType ?? ''}` : 'shared', b: b.vramGB != null ? `${b.vramGB}GB ${b.vramType ?? ''}` : 'shared' },
                { field: 'bus width', a: a.memBusBits ? `${a.memBusBits}-bit` : '—', b: b.memBusBits ? `${b.memBusBits}-bit` : '—' },
                { field: 'bandwidth', a: a.memBandwidthGBs ?? '—', b: b.memBandwidthGBs ?? '—' },
                { field: 'TDP', a: a.tdpW ? `${a.tdpW}W` : '—', b: b.tdpW ? `${b.tdpW}W` : '—' },
                { field: 'mesh shaders', a: a.caps.meshShaders ? 'yes' : 'no', b: b.caps.meshShaders ? 'yes' : 'no' },
                { field: 'ray tracing', a: a.caps.rayTracing ? 'yes' : 'no', b: b.caps.rayTracing ? 'yes' : 'no' },
                { field: 'DX feature level', a: a.caps.dxFeatureLevel, b: b.caps.dxFeatureLevel },
                { field: 'upscaling', a: a.caps.upscaling.join(', ') || '—', b: b.caps.upscaling.join(', ') || '—' },
                { field: 'driver status', a: a.driverStatus, b: b.driverStatus },
                { field: 'launched', a: a.launchDate ?? '—', b: b.launchDate ?? '—' },
              ];
              return (
                <div className="table-wrap">
                  <table className="data">
                    <thead>
                      <tr><th>field</th><th>{a.fullName}</th><th>{b.fullName}</th><th className="n">delta</th></tr>
                    </thead>
                    <tbody>
                      {rows.map((r) => {
                        const differs = String(r.a) !== String(r.b);
                        return (
                          <tr key={r.field} style={differs ? { background: 'var(--surface-2)' } : undefined}>
                            <td className="sub">{r.field}</td>
                            <td className="mono" style={{ color: differs ? 'var(--ink)' : 'var(--faint)' }}>{r.a}</td>
                            <td className="mono" style={{ color: differs ? 'var(--ink)' : 'var(--faint)' }}>{r.b}</td>
                            <td className="n sub">{r.delta ?? ''}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              );
            })()}
          </div>
        )}

        {tab === 'provenance' && (
          <div className="panel-body">
            <div className="note bad" style={{ marginBottom: 12 }}>
              <b>Read this before trusting a number.</b> The specification sources this pipeline is
              designed around — Wikipedia's MediaWiki API, vendor spec pages, the Vulkan hardware
              database and OpenBenchmarking — are blocked by this environment's egress policy. The
              catalogue you are looking at was seeded from model knowledge and is tagged as such —
              recalled figures, not measurements. The fetch and table-parsing stages are built and
              tested, but the layer that maps parsed specification rows onto catalogue records is not
              written yet, so harvesting alone does not replace this seed.
            </div>
            <dl className="kv" style={{ marginBottom: 14 }}>
              <dt>GPU anchor</dt><dd>{data.anchorGpu.fullName} = index 100</dd>
              <dt>CPU anchor</dt><dd>{data.anchorCpu.fullName} = index 100</dd>
              <dt>anchor memory</dt><dd>{ANCHOR_RAM.totalGB}GB · {ANCHOR_RAM.channels}ch · {ANCHOR_RAM.speedMTs} MT/s CL{ANCHOR_RAM.timings?.cl}</dd>
            </dl>
            <div className="note">{ANCHORS.rationale}</div>
            <div className="note" style={{ marginTop: 10 }}>
              Sources whose terms restrict automated collection — UserBenchmark, PassMark, Geekbench,
              Notebookcheck, TechPowerUp and YouTube — are declared in the manifest and never fetched.
              Two of them would also actively damage the model: UserBenchmark's composite deliberately
              down-weights multi-core performance, so calibrating against it would fit a known distortion.
            </div>
          </div>
        )}
      </div>
    </>
  );
}
