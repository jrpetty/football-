/** Tests × contestants matrix, grouped by category, cells shaded on a sequential ramp by mean score. */
import { memo, useMemo, useState } from 'react';
import type { CaseResultLite, RunManifest } from '../types.ts';
import { fmtPct, fmtScore100 } from '../format.ts';
import { useMeta } from '../context.tsx';
import { cx } from './ui.tsx';
import { Icon } from './icons.tsx';

interface Cell {
  mean: number | null;
  n: number;
  scored: number;
  errors: number;
  pending: number;
}

const STEPS = 9;
export function seqStep(score: number | null): number {
  if (typeof score !== 'number' || !Number.isFinite(score)) return -1;
  return Math.max(0, Math.min(STEPS - 1, Math.floor(score * STEPS - 1e-9)));
}

export function SeqLegend() {
  return (
    <span className="scale-legend" aria-label="Colour scale: 0 to 100">
      0
      <span className="ramp" aria-hidden="true">
        {Array.from({ length: STEPS }, (_, i) => (
          <span key={i} style={{ background: `var(--seq-${i})` }} />
        ))}
      </span>
      100
    </span>
  );
}

export const ResultsMatrix = memo(function ResultsMatrix({ manifest, results, onCell }: { manifest: RunManifest; results: CaseResultLite[]; onCell: (testId: string, contestantId: string) => void }) {
  const { cat, categories } = useMeta();
  const [q, setQ] = useState('');

  const cells = useMemo(() => {
    const m = new Map<string, Cell & { sum: number }>();
    for (const r of results) {
      const k = `${r.contestantId}|${r.testId}`;
      let c = m.get(k);
      if (!c) m.set(k, (c = { mean: null, n: 0, scored: 0, errors: 0, pending: 0, sum: 0 }));
      c.n++;
      if (typeof r.score === 'number') {
        c.scored++;
        c.sum += r.score;
      }
      if (r.status === 'error' || r.status === 'timeout') c.errors++;
      if (r.status === 'pending-human') c.pending++;
    }
    for (const c of m.values()) c.mean = c.scored ? c.sum / c.scored : null;
    return m;
  }, [results]);

  const groups = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const order = new Map(categories.map((c, i) => [c.id, i]));
    const byCat = new Map<string, RunManifest['tests']>();
    for (const t of manifest.tests ?? []) {
      if (needle && !t.name.toLowerCase().includes(needle) && !t.id.toLowerCase().includes(needle)) continue;
      const arr = byCat.get(t.category) ?? [];
      arr.push(t);
      byCat.set(t.category, arr);
    }
    return [...byCat.entries()].sort((a, b) => (order.get(a[0]) ?? 99) - (order.get(b[0]) ?? 99));
  }, [manifest.tests, categories, q]);

  const reps = manifest.settings?.repeats ?? 1;
  const cons = manifest.contestants ?? [];

  return (
    <div className="matrix-wrap">
      <div className="lb-toolbar">
        <div className="search" style={{ width: 240 }}>
          <Icon.Search />
          <input className="input sm" placeholder="Filter tests…" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Filter tests" />
        </div>
        <span className="spacer" />
        <span className="muted" style={{ fontSize: '0.8rem' }}>
          Mean score ×100 over cases × repeats · click a cell to inspect
        </span>
        <SeqLegend />
      </div>
      <div className="table-wrap scroll-shadow">
        <table className="table matrix">
          <thead>
            <tr>
              <th className="m-test">Test</th>
              {cons.map((c) => (
                <th key={c.id} className="m-col" title={c.label}>
                  <span className="m-col-head">
                    <span className="sw" style={{ background: c.color }} />
                    <span className="ellipsis">{c.label}</span>
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {groups.length === 0 && (
              <tr>
                <td colSpan={cons.length + 1}>
                  <div className="chart-empty">No tests match.</div>
                </td>
              </tr>
            )}
            {groups.map(([catId, tests]) => {
              const info = cat(catId);
              return [
                <tr key={`h-${catId}`} className="m-group">
                  <td colSpan={cons.length + 1}>
                    <span className="m-group-label" style={{ ['--cat' as string]: info.color }}>
                      {info.name}
                      <span className="muted">
                        {tests.length} test{tests.length === 1 ? '' : 's'}
                      </span>
                    </span>
                  </td>
                </tr>,
                ...tests.map((t) => (
                  <tr key={t.id}>
                    <td className="m-test">
                      <div className="m-test-name ellipsis" title={t.name}>
                        {t.name}
                      </div>
                      <div className="m-test-meta">
                        <span className="mono">{t.id}</span> · {t.caseIds.length} case{t.caseIds.length === 1 ? '' : 's'}
                        {reps > 1 ? ` × ${reps}` : ''}
                        {t.kind === 'program' ? ' · sim' : ''}
                      </div>
                    </td>
                    {cons.map((c) => {
                      const cell = cells.get(`${c.id}|${t.id}`);
                      const expected = t.caseIds.length * reps;
                      const step = seqStep(cell?.mean ?? null);
                      const label = cell
                        ? `${c.label} on ${t.name}: ${cell.mean === null ? 'no score' : fmtPct(cell.mean, 1)} over ${cell.scored} scored of ${expected}${cell.errors ? `, ${cell.errors} errors` : ''}${cell.pending ? `, ${cell.pending} awaiting review` : ''}`
                        : `${c.label} on ${t.name}: no results`;
                      return (
                        <td key={c.id} className="m-cell">
                          <button type="button" className={cx('mcell', step >= 0 ? `s${step}` : 'empty', cell && cell.n < expected && 'partial')} onClick={() => onCell(t.id, c.id)} aria-label={label} title={label} disabled={!cell}>
                            <span className="mv">{cell ? (cell.mean === null ? (cell.pending ? 'review' : '—') : fmtScore100(cell.mean)) : '—'}</span>
                            {cell && cell.errors > 0 && (
                              <span className="m-flag err" aria-hidden="true">
                                !
                              </span>
                            )}
                            {cell && cell.pending > 0 && cell.mean !== null && (
                              <span className="m-flag pend" aria-hidden="true">
                                …
                              </span>
                            )}
                          </button>
                        </td>
                      );
                    })}
                  </tr>
                )),
              ];
            })}
          </tbody>
        </table>
      </div>
      <div className="card-foot row wrap" style={{ gap: 16 }}>
        <span className="row" style={{ gap: 6 }}>
          <span className="m-flag err static">!</span> errors or timeouts in this cell
        </span>
        <span className="row" style={{ gap: 6 }}>
          <span className="m-flag pend static">…</span> awaiting human review
        </span>
        <span className="row" style={{ gap: 6 }}>
          <span className="partial-key" /> incomplete (fewer results than cases × repeats)
        </span>
      </div>
    </div>
  );
});
