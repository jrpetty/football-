/**
 * The overview heat-map: models × jobs, each square the verdict's colour with its icon and the score, so a
 * business owner can scan who is ready for what in five seconds. It is a real table (screen readers read it as
 * one); it scrolls inside its own box on a phone. Models are ordered by how many jobs they are ready for.
 */
import { useMemo } from 'react';
import { cx } from '../../components/ui.tsx';
import { ExplainIcon } from '../../components/viz/ExplainIcon.tsx';
import { ModelMonogram } from '../../components/viz/ModelBadge.tsx';
import { SUITABILITY_LABEL, SUITABILITY_ORDER, type BusinessJobsView, type JobModelResult } from '../../../../src/core/business-jobs.ts';
import { SuitabilityBadge, SuitabilityIcon, pct } from './parts.tsx';

export function JobHeatmap({ view, onPick, compact }: { view: BusinessJobsView; onPick?: (jobId: string) => void; compact?: boolean }) {
  const cell = (jobId: string, modelId: string): JobModelResult | undefined => view.results[jobId]?.find((r) => r.modelId === modelId);
  const models = useMemo(() => {
    const weight = (id: string) =>
      view.jobs.reduce((s, j) => {
        const r = cell(j.id, id);
        return s + (r ? (3 - SUITABILITY_ORDER.indexOf(r.verdict)) * 100 + (r.score ?? 0) : 0);
      }, 0);
    return view.models.slice().sort((a, b) => weight(b.id) - weight(a.id));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view]);
  if (!models.length) return <p className="bj-empty">No model has Business results yet. Run the Business tests to fill this in.</p>;
  return (
    <div className={cx('bj-heat-wrap', compact && 'compact')}>
      <div className="bj-legend" aria-hidden="true">
        {SUITABILITY_ORDER.map((v) => (
          <SuitabilityBadge key={v} verdict={v} size="sm" />
        ))}
      </div>
      <div className="bj-heat-scroll" tabIndex={0} role="region" aria-label="Every model on every job (scrolls sideways)">
        <table className="bj-heat">
          <caption className="sr-only">Suitability of every model for every business job, with the score</caption>
          <thead>
            <tr>
              <th scope="col" className="corner">
                Model
              </th>
              {view.jobs.map((j) => (
                <th key={j.id} scope="col" title={j.name}>
                  <span className="bj-heat-job">
                    <ExplainIcon name={j.icon} />
                    <span>{j.short}</span>
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {models.map((m) => (
              <tr key={m.id}>
                <th scope="row">
                  <span className="bj-heat-model">
                    <ModelMonogram label={m.label} color={m.color} size="sm" />
                    <span>{m.label}</span>
                  </span>
                </th>
                {view.jobs.map((j) => {
                  const r = cell(j.id, m.id);
                  const v = r?.verdict ?? 'no-data';
                  const text = `${m.label} for “${j.name}”: ${SUITABILITY_LABEL[v]}${r?.score !== null && r?.score !== undefined ? `, ${pct(r.score)} right` : ''}`;
                  const body = (
                    <>
                      <SuitabilityIcon verdict={v} />
                      <span className="tnum">{r?.score !== null && r?.score !== undefined ? pct(r.score) : '–'}</span>
                    </>
                  );
                  return (
                    <td key={j.id} className={cx('bj-heat-cell', `v-${v}`)}>
                      {onPick ? (
                        <button type="button" onClick={() => onPick(j.id)} title={text} aria-label={text}>
                          {body}
                        </button>
                      ) : (
                        <span title={text} aria-label={text} role="img">
                          {body}
                        </span>
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
