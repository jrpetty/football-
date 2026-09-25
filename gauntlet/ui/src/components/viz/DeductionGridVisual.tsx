/** Deduction Grid: the solution as a row of houses × categories, with the model's answer laid over the asked rows. */
import type { CSSProperties } from 'react';
import { gridHeadline, type GridVisual } from '../../../../src/presenter/visuals/grid.ts';
import { cx } from '../ui.tsx';
import { VizFrame, type VizMode } from './VizFrame.tsx';

function House({ label }: { label: string }) {
  return (
    <div className="vz-house">
      <svg viewBox="0 0 100 46" preserveAspectRatio="none" aria-hidden="true">
        <path d="M4 44 L4 20 L50 3 L96 20 L96 44 Z" />
      </svg>
      <span className="tnum">{label}</span>
    </div>
  );
}

export function DeductionGridVisual({ v, mode }: { v: GridVisual; mode: VizMode }) {
  // Exact-answer test: anything short of every cell right scores zero.
  const tone = v.answered && v.right === v.total ? 'good' : 'bad';
  const asked = new Set(v.asked);
  const order = [...v.asked, ...v.categories.map((_, i) => i).filter((i) => !asked.has(i))];
  return (
    <VizFrame
      mode={mode}
      tone={tone}
      eyebrow={v.question ? `Asked: ${v.question}` : 'Logic grid'}
      headline={gridHeadline(v)}
      big={`${v.right}/${v.total}`}
      bigSub={v.right === v.total ? 'cells right' : 'cells right · all needed'}
      legend={[
        { tone: 'good', label: 'Model right' },
        { tone: 'bad', label: 'Model wrong (correct value underneath)' },
        { tone: 'key', label: 'Rest of the solution, from the answer key' },
      ]}
    >
      <div className="vz-grid-scroll">
        <div className="vz-grid" style={{ ['--n' as string]: v.n } as CSSProperties} role="table" aria-label="Solution grid">
          <div className="vz-grid-corner" role="columnheader" />
          {v.positions.map((p) => (
            <div key={p} role="columnheader">
              <House label={/^\d+$/.test(p) ? `${v.noun === 'position' || v.noun === 'place' ? '#' : ''}${p}` : p} />
            </div>
          ))}
          {order.map((ci) => {
            const cat = v.categories[ci]!;
            const isAsked = asked.has(ci);
            return (
              <div key={cat.name} className="vz-grid-row" role="row" style={{ display: 'contents' }}>
                <div className={cx('vz-grid-cat', isAsked && 'asked')} role="rowheader" title={cat.name}>
                  {cat.name.replace(/\s*\(.*\)\s*$/, '')}
                  {isAsked && <small>asked</small>}
                </div>
                {v.rows[ci]!.map((cell, i) => {
                  if (!isAsked) {
                    return (
                      <div key={i} className="vz-cell key" role="cell">
                        {cell.truth}
                      </div>
                    );
                  }
                  return (
                    <div key={i} className={cx('vz-cell', cell.ok ? 'good' : 'bad')} role="cell" style={{ ['--i' as string]: i } as CSSProperties}>
                      {cell.ok ? (
                        <span className="vz-cell-v">{cell.truth}</span>
                      ) : (
                        <>
                          <s className="vz-cell-model">{cell.model || 'nothing'}</s>
                          <span className="vz-cell-fix">{cell.truth}</span>
                        </>
                      )}
                    </div>
                  );
                })}
              </div>
            );
          })}
        </div>
      </div>
    </VizFrame>
  );
}
