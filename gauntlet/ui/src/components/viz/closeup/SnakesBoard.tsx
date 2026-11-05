/**
 * Exact Maths: Ladder — the snakes-and-ladders board drawn as a real board (rows that snake back and forth),
 * with ladders, snakes and traps, and an exact fraction drawn as a fraction. Shared by the Level close-up and
 * the answer-vs-truth maths card.
 */
import { useId } from 'react';
import type { Fraction, SnakesBoard } from '../../../../../src/presenter/visuals/closeup.ts';
import { cx } from '../../ui.tsx';

const S = 40;

export function SnakesBoardStrip({ b, compact }: { b: SnakesBoard; compact?: boolean }) {
  const uid = useId().replace(/:/g, '');
  const squares = b.finish + 1; // 0..finish (the last is the goal)
  // Rows of 8 to 12 squares; pick the width that puts the fewest ladders and snakes inside a single row (a
  // ladder along a row reads as a rail, not a climb).
  const sameRow = (c: number) => [...b.ladders, ...b.snakes].filter(([x, y]) => Math.floor(x / c) === Math.floor(y / c)).length;
  const cols = squares <= 16 ? squares : [9, 10, 11, 8, 12].reduce((best, c) => (sameRow(c) < sameRow(best) ? c : best), squares <= 36 ? 9 : 10);
  const rows = Math.ceil(squares / cols);
  const at = (i: number) => {
    const r = Math.floor(i / cols);
    const c = r % 2 === 0 ? i % cols : cols - 1 - (i % cols);
    return { x: c * S, y: (rows - 1 - r) * S, cx: c * S + S / 2, cy: (rows - 1 - r) * S + S / 2 };
  };
  const traps = new Set(b.traps);
  const feet = new Map(b.ladders.map(([f, t]) => [f, t]));
  const heads = new Map(b.snakes.map(([h, t]) => [h, t]));
  return (
    <svg className={cx('lc-board', compact && 'compact')} viewBox={`-4 -4 ${cols * S + 8} ${rows * S + 8}`} role="img" aria-label={`Board: squares 0 to ${b.finish}; ladders ${b.ladders.map(([f, t]) => `${f} to ${t}`).join(', ') || 'none'}; snakes ${b.snakes.map(([h, t]) => `${h} down to ${t}`).join(', ') || 'none'}; traps on ${b.traps.join(', ') || 'none'}.`}>
      <defs>
        <marker id={`${uid}-tip`} viewBox="0 0 10 10" refX="5" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
          <path d="M0 0 L10 5 L0 10 z" className="lc-snake-tip" />
        </marker>
      </defs>
      {Array.from({ length: squares }, (_, i) => {
        const p = at(i);
        const kind = i === b.finish ? 'goal' : i === 0 ? 'start' : traps.has(i) ? 'trap' : feet.has(i) ? 'foot' : heads.has(i) ? 'head' : 'plain';
        return (
          <g key={i} className={cx('lc-sq', `k-${kind}`)}>
            <rect x={p.x + 1} y={p.y + 1} width={S - 2} height={S - 2} rx="5" />
            <text x={p.x + 5} y={p.y + 12} className="lc-sq-n">
              {i === b.finish ? `${i}+` : i}
            </text>
            {kind === 'trap' && <path className="lc-trap-x" d={`M${p.cx - 7} ${p.cy - 3} l14 14 M${p.cx + 7} ${p.cy - 3} l-14 14`} />}
            {kind === 'goal' && (
              <text x={p.cx} y={p.cy + 9} textAnchor="middle" className="lc-sq-goal">
                WIN
              </text>
            )}
            {kind === 'start' && (
              <circle cx={p.cx + 6} cy={p.cy + 6} r="6.5" className="lc-counter" />
            )}
          </g>
        );
      })}
      {b.ladders.map(([f, t]) => {
        const a = at(f);
        const z = at(t);
        const dx = z.cx - a.cx;
        const dy = z.cy - a.cy;
        const len = Math.hypot(dx, dy) || 1;
        const nx = (-dy / len) * 3.6;
        const ny = (dx / len) * 3.6;
        const rungs = Math.max(2, Math.round(len / 14));
        return (
          <g key={`l${f}`} className="lc-ladder">
            <line x1={a.cx + nx} y1={a.cy + ny} x2={z.cx + nx} y2={z.cy + ny} />
            <line x1={a.cx - nx} y1={a.cy - ny} x2={z.cx - nx} y2={z.cy - ny} />
            {Array.from({ length: rungs }, (_, k) => {
              const u = (k + 0.5) / rungs;
              return <line key={k} className="rung" x1={a.cx + dx * u + nx} y1={a.cy + dy * u + ny} x2={a.cx + dx * u - nx} y2={a.cy + dy * u - ny} />;
            })}
          </g>
        );
      })}
      {b.snakes.map(([h, t]) => {
        const a = at(h);
        const z = at(t);
        const mx = (a.cx + z.cx) / 2 + (a.cy - z.cy) * 0.25;
        const my = (a.cy + z.cy) / 2 + (z.cx - a.cx) * 0.25;
        return (
          <g key={`s${h}`} className="lc-snake">
            <path d={`M${a.cx} ${a.cy} Q${mx} ${my} ${z.cx} ${z.cy}`} markerEnd={`url(#${uid}-tip)`} />
            <circle cx={a.cx} cy={a.cy} r="4.5" className="lc-snake-head" />
          </g>
        );
      })}
    </svg>
  );
}

export function SnakesLegend({ b }: { b: SnakesBoard }) {
  return (
    <ul className="lc-board-legend">
      <li>
        <i className="sw spin" /> Spinner 1–{b.faces}
      </li>
      <li>
        <i className="sw ladder" /> {b.ladders.length} ladder{b.ladders.length === 1 ? '' : 's'} up
      </li>
      <li>
        <i className="sw snake" /> {b.snakes.length} snake{b.snakes.length === 1 ? '' : 's'} down
      </li>
      <li>
        <i className="sw trap" /> {b.traps.length} trap{b.traps.length === 1 ? '' : 's'}: you lose
      </li>
    </ul>
  );
}

/** A fraction drawn as a fraction; `marks` colours digits (true = matches, false = differs). */
export function FractionView({ f, tone, size = 'lg' }: { f: Fraction; tone?: 'good' | 'bad' | 'key' | 'plain'; size?: 'md' | 'lg' }) {
  const p = String(f.p);
  const q = String(f.q);
  const long = Math.max(p.length, q.length);
  return (
    <span className={cx('lc-frac', tone && `t-${tone}`, size, long > 18 ? 'xlong' : long > 10 && 'long')} aria-label={`${p} over ${q}`}>
      <span className="tnum">{p}</span>
      <span className="tnum">{q}</span>
    </span>
  );
}

/** "0.2271310246" with the leading digits that agree with the key in green and the first differing one in red. */
export function DecimalCompare({ text, same, tone }: { text: string; same: number; tone: 'good' | 'bad' | 'key' }) {
  const [whole, dec = ''] = text.split('.');
  return (
    <span className={cx('lc-dec', `t-${tone}`)}>
      <span className="tnum">{whole}.</span>
      {[...dec].map((d, i) => (
        <i key={i} className={tone === 'key' ? undefined : i < same ? 'ok' : i === same ? 'no' : 'dim'}>
          {d}
        </i>
      ))}
      <span className="lc-dec-more">…</span>
    </span>
  );
}
