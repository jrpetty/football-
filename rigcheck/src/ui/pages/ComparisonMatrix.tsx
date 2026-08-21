import React, { useMemo, useState } from 'react';
import { BuildEditor } from '../components/BuildEditor.tsx';
import { Delta, ExplainPanel, FpsFigure, Legend, fmt } from '../components/Parts.tsx';
import { makeBuild, useApp } from '../store.ts';
import { exportCsv, exportJson, matrixToRows } from '../export.ts';
import { compareBuilds } from '../../core/queries.ts';
import type { Resolution } from '../../core/types.ts';
import { RESOLUTIONS } from '../../core/types.ts';

export function ComparisonMatrix() {
  const { builds, setBuilds, games, resolutions, setResolutions, data } = useApp();
  const [baseline, setBaseline] = useState<string>(builds[0]?.id ?? '');
  const [open, setOpen] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [heatmap, setHeatmap] = useState(true);

  const base = builds.some((b) => b.id === baseline) ? baseline : builds[0]?.id;

  /**
   * Keyed on the fields the engine actually reads, not on the builds array.
   *
   * `builds` gets a new identity on every keystroke in a build's name field, so
   * renaming a build re-ran the entire matrix — up to 400 estimates per
   * character typed — to produce identical numbers.
   */
  // Everything except `label`, which is the one field the engine never reads.
  // Enumerating the fields it DOES read would silently go stale the next time
  // one is added; excluding the single irrelevant one cannot.
  const engineSig = JSON.stringify(builds.map(({ label: _label, ...rest }) => rest));
  const matrix = useMemo(
    () => compareBuilds(builds, games, resolutions, data, { baselineBuildId: base }),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- engineSig stands in for builds
    [engineSig, games, resolutions, data, base],
  );

  const cell = (buildId: string, gameId: string, res: Resolution) =>
    matrix.cells.find((c) => c.buildId === buildId && c.gameId === gameId && c.resolution === res);

  /**
   * Heat is computed per ROW (one game at one resolution), not across the whole
   * table. A global scale would just colour every esports row hot and every 4K
   * row cold, which says nothing — the useful comparison is between builds
   * playing the same game.
   */
  const rowHeat = (gameId: string, res: Resolution) => {
    const vals = builds
      .map((b) => cell(b.id, gameId, res)?.estimate.avgFps)
      .filter((v): v is number => v != null);
    return { min: Math.min(...vals), max: Math.max(...vals) };
  };
  const heatStyle = (fps: number | undefined, range: { min: number; max: number }) => {
    if (!heatmap || fps == null || !Number.isFinite(range.min) || range.max === range.min) return undefined;
    const t = (fps - range.min) / (range.max - range.min);
    return { background: `color-mix(in srgb, var(--chart-series) ${(t * 22).toFixed(0)}%, transparent)` };
  };

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
          <h2 className="micro">builds — {builds.length}</h2>
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
          <button className={`toggle${heatmap ? ' on' : ''}`} style={{ marginRight: 6 }} onClick={() => setHeatmap(!heatmap)}>
            heatmap
          </button>
          <button className="btn" onClick={() => exportCsv('rigcheck-matrix.csv', matrixToRows(matrix, data))}>
            CSV
          </button>
          <button className="btn" onClick={() => exportJson('rigcheck-matrix.json', matrix)}>
            JSON
          </button>
          <button className="btn" onClick={() => setEditing(!editing)}>
            {editing ? 'done' : 'edit builds'}
          </button>
          <button
            className="btn primary"
            /* Clone the baseline rather than inserting a fixed default pair.
               The reason to add a build is almost always "the same machine but
               with one part changed"; starting from a hardcoded Ryzen 5 3600 and
               RTX 3060 meant retyping both parts to compare one. */
            onClick={() => {
              const from = builds.find((b) => b.id === base) ?? builds[0];
              const seed = makeBuild({ label: `build ${builds.length + 1}` });
              setBuilds([...builds, from ? { ...from, id: seed.id, label: seed.label } : seed]);
            }}
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
                          const heat = heatStyle(c.estimate.avgFps, rowHeat(gameId, res));
                          return (
                            <React.Fragment key={b.id}>
                              <td className="n" style={heat}>
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
        <div className="panel-head"><h2 className="micro">summary</h2></div>
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
