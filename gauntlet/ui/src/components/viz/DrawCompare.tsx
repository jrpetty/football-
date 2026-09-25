/**
 * Draw It Blind: the original next to the redrawing, with three ways to look.
 *  - Side by side: a line joins each original shape to the drawn shape it was
 *    matched with, labelled with that pair's score; missing shapes are ringed.
 *  - Onion skin: the drawing over the original with an opacity slider.
 *  - Difference: identical pixels turn black, differences glow.
 */
import { useState } from 'react';
import { cx } from '../ui.tsx';
import type { DrawModel } from './vizModel.ts';
import { pairTone } from './vizModel.ts';
import './viz.css';

export const svgUrl = (svg: string) => `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;

type Mode = 'side' | 'onion' | 'diff';

export function DrawCompare({ model, focus, hoverShape, onHover, compact }: { model: DrawModel; focus?: number | null; hoverShape?: number | null; onHover?: (i: number | null) => void; compact?: boolean }) {
  const [mode, setMode] = useState<Mode>('side');
  const [mix, setMix] = useState(50);
  const C = model.canvas;
  const GAP = 150;
  const W = C * 2 + GAP;
  const hi = hoverShape ?? focus ?? null;
  // Score badges sit in the gap between the pictures, where each line crosses it, nudged apart so none overlap.
  const badgeY = new Map<number, number>();
  {
    const at = model.pairs
      .filter((p) => p.drawn)
      .map((p) => ({ i: p.target.i, y: (p.target.cy + p.drawn!.cy) / 2 }))
      .sort((a, b) => a.y - b.y);
    let last = -Infinity;
    for (const b of at) {
      const y = Math.max(b.y, last + 34);
      badgeY.set(b.i, y);
      last = y;
    }
    const overflow = last - (C - 16);
    if (overflow > 0) for (const [k, v] of badgeY) badgeY.set(k, v - overflow);
  }
  if (!model.targetSvg || !model.drawnSvg) return <div className="vz-missing">The two pictures were not recorded for this result.</div>;
  return (
    <div className={cx('vz-draw', compact && 'compact')}>
      {!compact && (
        <div className="vz-seg" role="tablist" aria-label="How to compare">
          {(
            [
              ['side', 'Side by side'],
              ['onion', 'Onion skin'],
              ['diff', 'Difference'],
            ] as Array<[Mode, string]>
          ).map(([m, l]) => (
            <button key={m} type="button" role="tab" aria-selected={mode === m} className={cx(mode === m && 'on')} onClick={() => setMode(m)}>
              {l}
            </button>
          ))}
        </div>
      )}
      {mode === 'side' && (
        <svg className="vz-draw-svg" viewBox={`0 -34 ${W} ${C + 40}`} role="img" aria-label={`Original and redrawn picture, ${model.pairs.filter((p) => p.drawn).length} of ${model.pairs.length} shapes matched`}>
          <text x={C / 2} y={-12} textAnchor="middle" className="vz-draw-cap">
            Original
          </text>
          <text x={C + GAP + C / 2} y={-12} textAnchor="middle" className="vz-draw-cap">
            Redrawn from its own words
          </text>
          <image href={svgUrl(model.targetSvg)} x={0} y={0} width={C} height={C} />
          <image href={svgUrl(model.drawnSvg)} x={C + GAP} y={0} width={C} height={C} />
          <rect x={0} y={0} width={C} height={C} className="vz-draw-frame" />
          <rect x={C + GAP} y={0} width={C} height={C} className="vz-draw-frame" />
          {model.extras.map((e, i) => (
            <rect key={`x${i}`} className="vz-extra" x={C + GAP + e.cx - e.w / 2 - 4} y={e.cy - e.h / 2 - 4} width={e.w + 8} height={e.h + 8} rx={6}>
              <title>Extra shape nobody asked for</title>
            </rect>
          ))}
          {model.pairs.map((p) => {
            const t = p.target;
            const on = hi === t.i;
            const dim = hi !== null && !on;
            const tone = pairTone(p.score);
            if (!p.drawn)
              return (
                <g key={t.i} className={cx('vz-pair missing', on && 'on', dim && 'dim')} onMouseEnter={() => onHover?.(t.i)} onMouseLeave={() => onHover?.(null)}>
                  <circle cx={t.cx} cy={t.cy} r={Math.max(t.w, t.h) / 2 + 8} />
                  <text x={t.cx} y={t.cy + Math.max(t.w, t.h) / 2 + 30} textAnchor="middle">
                    not drawn
                  </text>
                </g>
              );
            const dx = C + GAP + p.drawn.cx;
            const midX = C + GAP / 2;
            const midY = badgeY.get(t.i) ?? (t.cy + p.drawn.cy) / 2;
            return (
              <g key={t.i} className={cx('vz-pair', `tone-${tone}`, on && 'on', dim && 'dim')} onMouseEnter={() => onHover?.(t.i)} onMouseLeave={() => onHover?.(null)}>
                <title>{`#${t.i + 1} ${t.color} ${t.type} → ${p.drawn.color} ${p.drawn.kind}: ${Math.round(p.score * 100)}%`}</title>
                <polyline points={`${t.cx},${t.cy} ${midX - 28},${midY} ${midX + 28},${midY} ${dx},${p.drawn.cy}`} />
                <circle cx={t.cx} cy={t.cy} r={on ? 7 : 5} />
                <circle cx={dx} cy={p.drawn.cy} r={on ? 7 : 5} />
                <g transform={`translate(${midX} ${midY})`} className="vz-pair-badge">
                  <rect x={-27} y={-15} width={54} height={30} rx={15} />
                  <text y={6} textAnchor="middle">
                    {Math.round(p.score * 100)}%
                  </text>
                </g>
                <g transform={`translate(${t.cx - Math.max(t.w, t.h) / 2 - 4} ${t.cy - Math.max(t.h, t.w) / 2 - 4})`} className="vz-num">
                  <circle r={13} />
                  <text y={5} textAnchor="middle">
                    {t.i + 1}
                  </text>
                </g>
              </g>
            );
          })}
        </svg>
      )}
      {mode !== 'side' && (
        <div className="vz-onion">
          <div className={cx('vz-onion-stack', mode === 'diff' && 'diff')}>
            <img src={svgUrl(model.targetSvg)} alt="Original picture" />
            <img src={svgUrl(model.drawnSvg)} alt="Redrawn picture" style={mode === 'onion' ? { opacity: mix / 100 } : undefined} />
          </div>
          {mode === 'onion' ? (
            <label className="vz-onion-ctl">
              <span>Original</span>
              <input type="range" min={0} max={100} value={mix} onChange={(e) => setMix(Number(e.target.value))} aria-label="Blend between the original and the redrawing" />
              <span>Redrawn</span>
            </label>
          ) : (
            <p className="vz-onion-note">Black means the two pictures are identical there; anything bright is a difference.</p>
          )}
        </div>
      )}
    </div>
  );
}
