import React, { useMemo, useState } from 'react';
import { BuildEditor, GamePicker } from '../components/BuildEditor.tsx';
import { ExplainPanel, FpsFigure, Legend, LimiterTag, fmt } from '../components/Parts.tsx';
import { useApp } from '../store.ts';
import { BuildLibrary } from '../components/Library.tsx';
import { buildQuoteHtml, exportCsv, printQuote } from '../export.ts';
import { machineReport } from '../../core/analysis.ts';
import { estimate } from '../../core/engine.ts';
import { futureProofing } from '../../core/queries.ts';
import type { Resolution } from '../../core/types.ts';
import { RESOLUTIONS } from '../../core/types.ts';

export function BuildAnalyser() {
  const { builds, setBuilds, games, setGames, data } = useApp();
  const build = builds[0];
  const [open, setOpen] = useState<string | null>(null);
  /**
   * The screen's resolution IS the build's resolution — one value, not two.
   *
   * This was a separate `useState` seeded from the build. Because a useState
   * initialiser runs once, changing the target resolution in the editor on the
   * left moved the build but not the table on the right, so the two controls on
   * this one screen sat there disagreeing: the user said 4K, the editor agreed,
   * and the frame-rate table went on answering the 1440p question with nothing
   * to indicate it.
   */
  const res: Resolution = build?.target.resolution ?? '1440p';
  const setRes = (r: Resolution) =>
    setBuilds([{ ...build, target: { ...build.target, resolution: r } }, ...builds.slice(1)]);

  const rows = useMemo(
    () => games.map((gameId) => ({ gameId, est: estimate(build, gameId, res, data) })),
    [build, games, res, data],
  );

  const fp = useMemo(() => futureProofing(build, data), [build, data]);
  const report = useMemo(() => machineReport(build, data, { fps: rows.find((r) => r.est.status === 'ok')?.est.avgFps }), [build, data, rows]);

  const quote = () => {
    const cpu = data.cpus.get(build.cpuId);
    const gpu = data.gpus.get(build.gpuId);
    if (!cpu || !gpu) return;
    printQuote(
      buildQuoteHtml({
        title: `${build.label ?? 'Build'} — performance`,
        buildName: build.label || `${cpu.brand} / ${gpu.brand}`,
        cpu: cpu.fullName,
        gpu: gpu.fullName,
        ram: `${build.ram.totalGB}GB ${build.ram.type ?? ''} ${build.ram.channels}-channel @ ${build.ram.speedMTs} MT/s`,
        storage: build.storage,
        rows: rows
          .filter((r) => r.est.status === 'ok')
          .map((r) => ({
            game: data.games.get(r.gameId)?.name ?? r.gameId,
            resolution: res,
            fps: fmt(r.est.avgFps),
            low: fmt(r.est.low1PctFps),
          })),
        powerW: report ? Math.round(report.power.totalW) : undefined,
        psuW: report?.power.recommendedPsuW,
        yearlyCost: report ? `£${report.power.costPerYearGBP(15).toFixed(0)}` : undefined,
        seeded: true,
      }),
    );
  };

  const ok = rows.filter((r) => r.est.status === 'ok');
  const blocked = rows.length - ok.length;
  const target = build.target.refreshHz;
  const clearing = ok.filter((r) => (r.est.avgFps ?? 0) >= target).length;
  const geomean = ok.length
    ? Math.exp(ok.reduce((s, r) => s + Math.log(Math.max(r.est.avgFps ?? 1, 1)), 0) / ok.length)
    : 0;

  return (
    <>
      <div className="page-head">
        <h1>Analyse one build in detail</h1>
        <p>
          One build, measured across your game selection. Every figure carries an uncertainty band and
          opens its full working — click any number.
        </p>
      </div>

      <div className="grid rail">
        <div>
          <BuildEditor build={build} onChange={(b) => setBuilds([b, ...builds.slice(1)])} />

          <BuildLibrary current={build} onLoad={(b) => setBuilds([{ ...b, id: build.id }, ...builds.slice(1)])} />

          <div className="panel">
            <div className="panel-head"><h2 className="micro">forward risk</h2></div>
            <div className="panel-body">
              <div className="stat" style={{ marginBottom: 10 }}>
                <span className="v">{fp.score}<span style={{ fontSize: 12, color: 'var(--faint)' }}>/100</span></span>
                <span className="k">composite — read the factors, not the score</span>
              </div>
              {fp.factors.map((f) => (
                <div key={f.factor} style={{ marginBottom: 7, paddingBottom: 7, borderBottom: '1px solid var(--line)' }}>
                  <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                    <span className={`dot ${f.severity === 'ok' ? 'measured' : f.severity === 'watch' ? 'spec-derived' : f.severity === 'risk' ? 'extrapolated' : 'gate-blocked'}`} />
                    <span style={{ fontSize: 12 }}>{f.factor}</span>
                    <span className="spacer" style={{ marginLeft: 'auto' }} />
                    <span className="tag">{f.severity}</span>
                  </div>
                  <div style={{ color: 'var(--muted)', fontSize: 11.5, marginTop: 2 }}>{f.detail}</div>
                  <div style={{ color: 'var(--faint)', fontSize: 11, marginTop: 2 }}>{f.evidence}</div>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div>
          <div className="panel">
            <div className="panel-head">
              <h2 className="micro">results</h2>
              <span className="spacer" />
              <div className="toggle-row">
                {RESOLUTIONS.map((r) => (
                  <button key={r} className={`toggle${r === res ? ' on' : ''}`} onClick={() => setRes(r)}>
                    {r}
                  </button>
                ))}
              </div>
              <button className="btn" style={{ marginLeft: 8 }} onClick={quote} title="Printable customer-facing sheet">
                quote
              </button>
              <button
                className="btn"
                onClick={() =>
                  exportCsv(
                    'rigcheck-build.csv',
                    rows.map((r) => ({
                      game: data.games.get(r.gameId)?.name ?? r.gameId,
                      resolution: res,
                      status: r.est.status,
                      avg_fps: r.est.avgFps?.toFixed(1) ?? '',
                      low_1pct: r.est.low1PctFps?.toFixed(1) ?? '',
                      low_01pct: r.est.low01PctFps?.toFixed(1) ?? '',
                      smoothness: r.est.smoothness?.verdict ?? '',
                      limiter: r.est.limiter ?? '',
                      uncertainty_pct: r.est.uncertainty ? (r.est.uncertainty * 100).toFixed(0) : '',
                    })),
                  )
                }
              >
                CSV
              </button>
            </div>
            <div className="panel-body">
              <div className="grid three" style={{ marginBottom: 12 }}>
                <div className="stat">
                  <span className="v">{fmt(geomean)}</span>
                  <span className="k">geomean fps · {ok.length} titles</span>
                </div>
                <div className="stat">
                  <span className="v">
                    {clearing}<span style={{ color: 'var(--faint)', fontSize: 13 }}>/{ok.length}</span>
                  </span>
                  <span className="k">clear {target}Hz target</span>
                </div>
                <div className="stat">
                  <span className="v" style={{ color: blocked ? 'var(--blocked)' : 'var(--ink)' }}>{blocked}</span>
                  <span className="k">will not run</span>
                </div>
              </div>
              <Legend />
            </div>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>title</th>
                    <th>archetype</th>
                    <th className="n">avg fps</th>
                    <th className="n">1% low</th>
                    <th>limiter</th>
                    <th className="n">headroom</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map(({ gameId, est }) => {
                    const g = data.games.get(gameId);
                    const isOpen = open === gameId;
                    const head = est.avgFps != null ? est.avgFps / target - 1 : null;
                    return (
                      <React.Fragment key={gameId}>
                        <tr>
                          <td>
                            {g?.name ?? gameId}
                            {g?.fpsCap && <span className="tag engine-cap" style={{ marginLeft: 6 }}>cap {g.fpsCap}</span>}
                          </td>
                          <td className="sub">{g?.archetype}</td>
                          <td className="n">
                            <FpsFigure estimate={est} onExplain={() => setOpen(isOpen ? null : gameId)} />
                          </td>
                          <td className="n">{fmt(est.low1PctFps)}</td>
                          <td><LimiterTag limiter={est.limiter} /></td>
                          <td className="n" style={{ color: head == null ? 'var(--faint)' : head >= 0 ? 'var(--measured)' : 'var(--extrapolated)' }}>
                            {head == null ? '—' : `${head > 0 ? '+' : ''}${(head * 100).toFixed(0)}%`}
                          </td>
                        </tr>
                        {isOpen && (
                          <tr>
                            <td colSpan={6} style={{ background: 'var(--bg)' }}>
                              <ExplainPanel estimate={est} title={`${g?.name} @ ${res}`} />
                            </td>
                          </tr>
                        )}
                      </React.Fragment>
                    );
                  })}
                  {rows.length === 0 && (
                    <tr><td colSpan={6} className="empty">No titles selected.</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>

          <div className="panel">
            <div className="panel-head"><h2 className="micro">titles</h2></div>
            <div className="panel-body">
              <GamePicker selected={games} onChange={setGames} />
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
