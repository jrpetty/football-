import { useMemo, useState } from 'react';
import { useApp } from '../store.ts';
import { deriveGpuIndex, deriveCpuIndex } from '../../core/indices.ts';
import { ANCHOR_RAM } from '../../core/catalogue.ts';
import { fmt } from '../components/Parts.tsx';
import { ANCHORS } from '../../core/constants.ts';

type Tab = 'gpu' | 'cpu' | 'game' | 'provenance';

export function DataExplorer() {
  const { data } = useApp();
  const [tab, setTab] = useState<Tab>('gpu');
  const [q, setQ] = useState('');
  const [sort, setSort] = useState<'index' | 'name' | 'date'>('index');

  const gpuRows = useMemo(() => {
    const rows = [...data.gpus.values()].map((g) => ({
      g,
      idx: deriveGpuIndex(g, data.anchorGpu, ANCHOR_RAM),
    }));
    rows.sort((a, b) =>
      sort === 'index' ? b.idx.index.raster - a.idx.index.raster
      : sort === 'date' ? (b.g.launchDate ?? '').localeCompare(a.g.launchDate ?? '')
      : a.g.fullName.localeCompare(b.g.fullName));
    return rows.filter((r) => !q || r.g.fullName.toLowerCase().includes(q.toLowerCase()));
  }, [data, q, sort]);

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
            {(['gpu', 'cpu', 'game', 'provenance'] as Tab[]).map((t) => (
              <button key={t} className={`toggle${tab === t ? ' on' : ''}`} onClick={() => setTab(t)}>
                {t === 'gpu' ? `GPUs (${data.gpus.size})` : t === 'cpu' ? `CPUs (${data.cpus.size})` : t === 'game' ? `games (${data.games.size})` : 'provenance'}
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
                style={{ width: 180 }}
              />
            </>
          )}
        </div>

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

        {tab === 'provenance' && (
          <div className="panel-body">
            <div className="note bad" style={{ marginBottom: 12 }}>
              <b>Read this before trusting a number.</b> The specification sources this pipeline is
              designed around — Wikipedia's MediaWiki API, vendor spec pages, the Vulkan hardware
              database and OpenBenchmarking — are blocked by this environment's egress policy. The
              catalogue you are looking at was seeded from model knowledge and is tagged as such.
              The harvest and parse pipeline is built and wired; running <span className="mono">npm run harvest</span> in an
              unrestricted environment replaces the seed with sourced data and no code changes.
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
