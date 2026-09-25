/**
 * Needle in a Haystack: the whole document as one long strip (start → end),
 * with a pin for every needle coloured by what the model did, the current
 * needle's parts joined by an arc (multi-hop chains, sums) and its near-miss
 * decoys marked as diamonds. `revealed` hides pins the replay has not reached.
 */
import { cx } from '../ui.tsx';
import type { NeedleModel, NeedleOutcome, NeedleView } from './vizModel.ts';
import { OUTCOME_LABEL } from './vizModel.ts';
import './viz.css';

const X0 = 24;
const X1 = 976;
const x = (depth: number) => X0 + (Math.max(0, Math.min(100, depth)) / 100) * (X1 - X0);

export const outcomeClass = (o: NeedleOutcome) => (o === 'found' ? 'good' : o === 'decoy' || o === 'old-value' ? 'warn' : 'bad');

export function NeedleStrip({ model, current, revealed, compact, cursor }: { model: NeedleModel; current?: NeedleView; revealed?: Set<string>; compact?: boolean; cursor?: number }) {
  const H = compact ? 62 : 176;
  // Pin labels that would touch their left neighbour move up a row.
  const raised = new Set<string>();
  if (!compact) {
    let lastX = -Infinity;
    let lastRaised = false;
    for (const n of [...model.needles].sort((a, b) => a.depth - b.depth)) {
      const px = x(n.depth);
      const clash: boolean = px - lastX < 46 && !lastRaised;
      if (clash) raised.add(n.id);
      lastRaised = clash;
      lastX = px;
    }
  }
  const barY = compact ? 38 : 104;
  const barH = compact ? 16 : 22;
  const words = model.words;
  const ticks = [0, 25, 50, 75, 100];
  const pinTop = compact ? 12 : 52;
  const cur = current;
  return (
    <svg className={cx('vz-strip', compact && 'compact')} viewBox={`0 0 1000 ${H}`} role="img" aria-label={`The document from start to end with ${model.total} needles: ${model.correct} found`}>
      <rect className="vz-strip-doc" x={X0} y={barY} width={X1 - X0} height={barH} rx={4} />
      {/* Paragraph texture: faint lines so the bar reads as a document. */}
      {!compact &&
        Array.from({ length: 48 }, (_, i) => <line key={i} className="vz-strip-para" x1={X0 + 8 + i * 19.6} x2={X0 + 8 + i * 19.6} y1={barY + 5} y2={barY + barH - 5} />)}
      {cursor !== undefined && <rect className="vz-strip-readfill" x={X0} y={barY} width={Math.max(0, x(cursor) - X0)} height={barH} rx={4} />}
      {cursor !== undefined && (
        <g className="vz-strip-cursor" style={{ transform: `translateX(${x(cursor) - X0}px)` }}>
          <line x1={X0} x2={X0} y1={barY - 6} y2={barY + barH + 6} />
        </g>
      )}
      {/* Current needle: its decoys (diamonds) and its parts joined by an arc. */}
      {cur && !compact && (
        <g className="vz-strip-cur">
          {cur.decoyDepths.map((d, i) => (
            <g key={`d${i}`} className="vz-decoy" transform={`translate(${x(d)} ${barY + barH / 2})`}>
              <path d="M0 -11 L11 0 L0 11 L-11 0Z" />
              <text y={barH + 14} textAnchor="middle">
                decoy
              </text>
            </g>
          ))}
          {cur.depths.length > 1 &&
            [...cur.depths]
              .sort((a, b) => a - b)
              .slice(1)
              .map((d, i, rest) => {
                const from = [...cur.depths].sort((a, b) => a - b)[i]!;
                const mid = (x(from) + x(d)) / 2;
                const lift = Math.min(60, 14 + Math.abs(x(d) - x(from)) * 0.12);
                return <path key={`a${i}`} className="vz-chain" d={`M${x(from)} ${barY} Q${mid} ${barY - lift} ${x(d)} ${barY}`} data-last={i === rest.length - 1 || undefined} />;
              })}
          {cur.depths.map((d, i) => (
            <circle key={`p${i}`} className={cx('vz-part', outcomeClass(cur.outcome))} cx={x(d)} cy={barY + barH / 2} r={7} />
          ))}
        </g>
      )}
      {/* Every needle's pin at the depth where it becomes answerable. */}
      {model.needles.map((n) => {
        const shown = !revealed || revealed.has(n.id);
        const isCur = cur?.id === n.id;
        const px = x(n.depth);
        return (
          <g key={n.id} className={cx('vz-pin', shown ? outcomeClass(n.outcome) : 'hidden', isCur && 'current')}>
            <title>{shown ? `${n.id} at ${Math.round(n.depth)}%: ${OUTCOME_LABEL[n.outcome]}` : `${n.id} at ${Math.round(n.depth)}%: not reached yet`}</title>
            <line x1={px} x2={px} y1={pinTop + 10} y2={barY} />
            <circle cx={px} cy={pinTop} r={isCur ? 12 : 9} />
            {!compact && (
              <text x={px} y={pinTop - (raised.has(n.id) ? 36 : 16)} textAnchor="middle">
                {n.id}
              </text>
            )}
          </g>
        );
      })}
      {!compact &&
        ticks.map((t) => (
          <text key={t} className="vz-strip-tick" x={x(t)} y={H - 4} textAnchor={t === 0 ? 'start' : t === 100 ? 'end' : 'middle'}>
            {t === 0 ? 'Start' : t === 100 ? `End${words ? ` · ${words.toLocaleString('en-US')} words` : ''}` : `${t}%`}
          </text>
        ))}
    </svg>
  );
}
