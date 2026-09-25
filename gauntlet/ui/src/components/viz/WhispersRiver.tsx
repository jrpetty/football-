/**
 * Chain of Whispers: every fact is a lane flowing left to right through the
 * rewrites. A solid green lane is a fact still intact; a dashed amber lane
 * means its wording drifted (it no longer counts); the lane ends with a red
 * cross in the round it disappeared. Columns the replay has not reached are
 * faded, and the current column is highlighted.
 */
import { cx } from '../ui.tsx';
import type { WhispersModel } from './vizModel.ts';
import './viz.css';

export function WhispersRiver({ model, current, compact, onPick }: { model: WhispersModel; current: number; compact?: boolean; onPick?: (col: number) => void }) {
  const nf = model.facts.length;
  const labelW = compact ? 0 : 230;
  const headH = compact ? 8 : 60;
  const laneH = compact ? 12 : nf > 14 ? 28 : 34;
  const W = 1000;
  const cols = model.columns.length;
  const step = (W - labelW - 100) / Math.max(1, cols - 1);
  const colX = (c: number) => labelW + 50 + c * step;
  const band = Math.min(96, step * 0.86);
  const H = headH + nf * laneH + 8;
  const laneY = (f: number) => headH + f * laneH + laneH / 2;
  return (
    <svg className={cx('vz-river', compact && 'compact')} viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${nf} facts flowing through ${cols - 1} rewrites; ${model.survived} survived`}>
      {!compact &&
        model.columns.map((c, i) => (
          <g key={i} className={cx('vz-rcol', i === current && 'current', i > current && 'future')} onClick={onPick ? () => onPick(i) : undefined}>
            <rect x={colX(i) - band / 2} y={4} width={band} height={H - 8} rx={8} className="vz-rcol-bg" />
            {/* Line 1: "Start", then "Cycle n" centred over its short + long pair. Line 2: short / long. */}
            {(c.round === 0 || c.kind === 'summary') && (
              <text x={c.round === 0 ? colX(i) : colX(i) + step / 2} y={24} textAnchor="middle" className="vz-rcol-t">
                {c.round === 0 ? 'Start' : `Cycle ${c.cycle}`}
              </text>
            )}
            <text x={colX(i)} y={48} textAnchor="middle" className="vz-rcol-s">
              {c.round === 0 ? 'story' : c.kind === 'summary' ? 'short' : 'long'}
            </text>
          </g>
        ))}
      {model.facts.map((f, fi) => {
        const y = laneY(fi);
        const st = model.status[fi]!;
        const segs = [];
        for (let c = 1; c < cols; c++) {
          const s = st[c]!;
          const prev = st[c - 1]!;
          if (prev === 'lost') continue;
          const future = c > current;
          if (s === 'lost') {
            segs.push(
              <g key={c} className={cx('vz-rdeath', future && 'future')}>
                <line x1={colX(c - 1)} x2={colX(c) - 12} y1={y} y2={y} className={cx('vz-rlane', prev === 'changed' && 'changed', future && 'future')} />
                <path d={`M${colX(c) - 7} ${y - 7} l14 14 M${colX(c) + 7} ${y - 7} l-14 14`} />
              </g>,
            );
          } else segs.push(<line key={c} x1={colX(c - 1)} x2={colX(c)} y1={y} y2={y} className={cx('vz-rlane', s === 'changed' && 'changed', future && 'future')} />);
        }
        const now = st[Math.min(current, cols - 1)]!;
        return (
          <g key={f.id} className={cx('vz-rfact', `is-${now}`)}>
            <title>{`${f.label}: ${f.canonical}`}</title>
            {!compact && (
              <text x={labelW} y={y + 6} textAnchor="end" className="vz-rlabel">
                {f.label.length > 24 ? `${f.label.slice(0, 23)}…` : f.label}
              </text>
            )}
            {segs}
            <circle cx={colX(0)} cy={y} r={compact ? 3 : 5} className="vz-rsrc" />
          </g>
        );
      })}
    </svg>
  );
}
