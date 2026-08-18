import React, { useMemo, useState } from 'react';
import { BuildEditor } from '../components/BuildEditor.tsx';
import { Delta, ExplainPanel, FpsFigure, Legend, fmt } from '../components/Parts.tsx';
import { makeBuild, useApp } from '../store.ts';
import { compareBuilds } from '../../core/queries.ts';
import type { Resolution } from '../../core/types.ts';
import { RESOLUTIONS } from '../../core/types.ts';

export function ComparisonMatrix() {
  const { builds, setBuilds, games, resolutions, setResolutions, data } = useApp();
  const [baseline, setBaseline] = useState<string>(builds[0]?.id ?? '');
  const [open, setOpen] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);

  const base = builds.some((b) => b.id === baseline) ? baseline : builds[0]?.id;

  const matrix = useMemo(
    () => compareBuilds(builds, games, resolutions, data, { baselineBuildId: base }),
    [builds, games, resolutions, data, base],
  );

  const cell = (buildId: string, gameId: string, res: Resolution) =>
    matrix.cells.find((c) => c.buildId === buildId && c.gameId === gameId && c.resolution === res);

  return (
    <>
      <div className="page-head">
        <h1>Comparison Matrix</h1>
        <p>
          N-way, not pairwise. Deltas smaller than the combined uncertainty of the two builds are struck
          through — at that size the model cannot tell them apart, and saying otherwise would be the
          opaque confidence this tool exists to replace.
        </p>
      </div>

      <div className="panel">
        <div className="panel-head">
          <span>builds — {builds.length}</span>
          <span className="spacer" />
          <div className="toggle-row" style={{ marginRight: 8 }}>
            {RESOLUTIONS.map((r) => (
              <button
                key={r}
                className={`toggle${resolutions.includes(r) ? ' on' : ''}`}
                onClick={() =>
                  setResolutions(
                    resolutions.includes(r) ? resolutions.filter((x) => x !== r) : [...resolutions, r],
                  )
                }
              >
                {r}
              </button>
            ))}
          </div>
          <button className="btn" onClick={() => setEditing(!editing)}>
            {editing ? 'done' : 'edit builds'}
          </button>
          <button
            className="btn primary"
            onClick={() => setBuilds([...builds, makeBuild({ label: `build ${builds.length + 1}` })])}
          >
            + add build
          </button>
        </div>

        {editing && (
          <div className="panel-body">
            <div className="grid three">
              {builds.map((b) => (
                <BuildEditor
                  key={b.id}
                  build={b}
                  compact
                  onChange={(nb) => setBuilds(builds.map((x) => (x.id === b.id ? nb : x)))}
                  onRemove={builds.length > 1 ? () => setBuilds(builds.filter((x) => x.id !== b.id)) : undefined}
                />
              ))}
            </div>
          </div>
        )}

        <div className="panel-body" style={{ paddingBottom: 6 }}>
          <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
            <span style={{ fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--faint)' }}>
              baseline
            </span>
            {builds.map((b) => (
              <button
                key={b.id}
                className={`toggle${b.id === base ? ' on' : ''}`}
                onClick={() => setBaseline(b.id)}
              >
                {b.label ?? b.id}
              </button>
            ))}
          </div>
        </div>

        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>title</th>
                <th>res</th>
                {builds.map((b) => (
                  <th key={b.id} className="n" colSpan={2}>
                    {b.label ?? b.id}
                    <div className="sub" style={{ fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>
                      {data.cpus.get(b.cpuId)?.brand} · {data.gpus.get(b.gpuId)?.brand}
                    </div>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {games.map((gameId) =>
                resolutions.map((res) => {
                  const key = `${gameId}|${res}`;
                  const isOpen = open === key;
                  return (
                    <React.Fragment key={key}>
                      <tr>
                        <td>{data.games.get(gameId)?.name ?? gameId}</td>
                        <td className="sub mono">{res}</td>
                        {builds.map((b) => {
                          const c = cell(b.id, gameId, res);
                          if (!c) return <td key={b.id} colSpan={2} className="n">—</td>;
                          return (
                            <React.Fragment key={b.id}>
                              <td className="n">
                                <FpsFigure
                                  estimate={c.estimate}
                                  showBand={false}
                                  onExplain={() => setOpen(isOpen ? null : key)}
                                />
                              </td>
                              <td className="n" style={{ paddingLeft: 0 }}>
                                {b.id === base ? (
                                  <span className="sub">base</span>
                                ) : (
                                  <Delta value={c.delta} noise={c.deltaWithinNoise} />
                                )}
                              </td>
                            </React.Fragment>
                          );
                        })}
                      </tr>
                      {isOpen && (
                        <tr>
                          <td colSpan={2 + builds.length * 2} style={{ background: 'var(--bg)' }}>
                            <div className="grid two">
                              {builds.map((b) => {
                                const c = cell(b.id, gameId, res);
                                return c ? (
                                  <ExplainPanel
                                    key={b.id}
                                    estimate={c.estimate}
                                    title={`${b.label ?? b.id} — ${data.games.get(gameId)?.name} @ ${res}`}
                                  />
                                ) : null;
                              })}
                            </div>
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  );
                }),
              )}
              {games.length === 0 && (
                <tr><td colSpan={2 + builds.length * 2} className="empty">Select titles in the Build Analyser.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      <div className="panel">
        <div className="panel-head">summary</div>
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>build</th>
                <th>cpu</th>
                <th>gpu</th>
                <th className="n">geomean fps</th>
                <th className="n">mean fps</th>
                <th className="n">blocked</th>
              </tr>
            </thead>
            <tbody>
              {matrix.summary.map((s) => {
                const b = builds.find((x) => x.id === s.buildId)!;
                return (
                  <tr key={s.buildId}>
                    <td>{b.label ?? b.id}{s.buildId === base && <span className="tag" style={{ marginLeft: 6 }}>baseline</span>}</td>
                    <td className="sub">{data.cpus.get(b.cpuId)?.fullName}</td>
                    <td className="sub">{data.gpus.get(b.gpuId)?.fullName}</td>
                    <td className="n" style={{ fontSize: 14 }}>{fmt(s.geomeanFps, 1)}</td>
                    <td className="n">{fmt(s.meanFps, 1)}</td>
                    <td className="n" style={{ color: s.gamesBlocked ? 'var(--blocked)' : 'var(--faint)' }}>
                      {s.gamesBlocked}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div className="panel-body">
          <Legend />
          <div className="note" style={{ marginTop: 8 }}>
            Geometric mean is the honest aggregate across titles with very different absolute frame rates —
            an esports title at 400fps would otherwise dominate an AAA title at 45fps.
          </div>
        </div>
      </div>
    </>
  );
}
