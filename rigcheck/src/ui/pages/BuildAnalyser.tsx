import React, { useMemo, useState } from 'react';
import { BuildEditor, GamePicker } from '../components/BuildEditor.tsx';
import { ExplainPanel, FpsFigure, Legend, LimiterTag, fmt } from '../components/Parts.tsx';
import { useApp } from '../store.ts';
import { estimate } from '../../core/engine.ts';
import { futureProofing } from '../../core/queries.ts';
import type { Resolution } from '../../core/types.ts';
import { RESOLUTIONS } from '../../core/types.ts';

export function BuildAnalyser() {
  const { builds, setBuilds, games, setGames, data } = useApp();
  const build = builds[0];
  const [open, setOpen] = useState<string | null>(null);
  const [res, setRes] = useState<Resolution>(build?.target.resolution ?? '1440p');

  const rows = useMemo(
    () => games.map((gameId) => ({ gameId, est: estimate(build, gameId, res, data) })),
    [build, games, res, data],
  );

  const fp = useMemo(() => futureProofing(build, data), [build, data]);

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
        <h1>Build Analyser</h1>
        <p>
          One build, measured across your game selection. Every figure carries an uncertainty band and
          opens its full working — click any number.
        </p>
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'minmax(280px, 340px) minmax(0, 1fr)' }}>
        <div>
          <BuildEditor build={build} onChange={(b) => setBuilds([b, ...builds.slice(1)])} />

          <div className="panel">
            <div className="panel-head">forward risk</div>
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
              <span>results</span>
              <span className="spacer" />
              <div className="toggle-row">
                {RESOLUTIONS.map((r) => (
                  <button key={r} className={`toggle${r === res ? ' on' : ''}`} onClick={() => setRes(r)}>
                    {r}
                  </button>
                ))}
              </div>
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
            <div className="panel-head">titles</div>
            <div className="panel-body">
              <GamePicker selected={games} onChange={setGames} />
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
