/**
 * "How far up the ladder": the Horizon tier's signature visual. A mountain with a ten-rung ladder up its
 * face; every model is a climber token parked on the highest level it solved reliably, and each rung shows
 * one dot per model (filled = solved reliably, ring = solved sometimes / partial credit, empty = failed).
 * The summit is level 10: built for models that do not exist yet.
 *
 * Only recorded results are drawn (ladderClimbs in src/presenter/visuals/horizon.ts). Levels that were
 * not run show as dashed dots, never as failures.
 */
import { useEffect, useState, type CSSProperties } from 'react';
import { usePrefersReducedMotion } from '../../hooks.ts';
import { cx } from '../ui.tsx';
import type { Climb } from '../../../../src/presenter/visuals/horizon.ts';
import './horizon.css';

export interface LadderClimber {
  id: string;
  label: string;
  color: string;
  baseline?: boolean;
  climb: Climb;
}

function initials(label: string): string {
  const words = label.replace(/[()]/g, ' ').split(/[\s-]+/).filter(Boolean);
  const pick = words.find((w) => /\d/.test(w));
  const first = words[0]?.[0]?.toUpperCase() ?? '?';
  return pick ? `${first}${pick.replace(/[^\d.]/g, '').slice(0, 3)}` : words.slice(0, 2).map((w) => w[0]!.toUpperCase()).join('');
}

export function HorizonLadder({
  climbers,
  levels = 10,
  captions = [],
  mode = 'inspector',
  highlight,
  title,
}: {
  climbers: LadderClimber[];
  levels?: number;
  /** Optional short caption per level (index 0 = level 1), e.g. "3×3 · 8 moves". */
  captions?: Array<string | null>;
  mode?: 'inspector' | 'slide';
  /** A level to highlight (the case open in the inspector). */
  highlight?: number;
  title?: string;
}) {
  const reduced = usePrefersReducedMotion();
  const [up, setUp] = useState(reduced);
  useEffect(() => {
    if (reduced) return setUp(true);
    const id = requestAnimationFrame(() => requestAnimationFrame(() => setUp(true)));
    return () => cancelAnimationFrame(id);
  }, [reduced]);

  // Geometry (SVG user units). Ladder runs bottom (level 1) to top (level N).
  const W = 1000;
  const H = mode === 'slide' ? 700 : 560;
  const top = 70;
  const ground = H - 58;
  const step = (ground - 40 - top) / levels;
  const yOf = (level: number) => (level === 0 ? ground : ground - 40 - (level - 0.5) * step);
  const ladderX = 250;
  const ladderW = Math.max(220, Math.min(460, 70 + climbers.length * 62));
  const colX = (i: number) => ladderX + 40 + ((ladderW - 80) * (climbers.length === 1 ? 0.5 : i / (climbers.length - 1)));
  const real = climbers.filter((c) => !c.baseline);
  const topHeight = Math.max(0, ...real.map((c) => c.climb.height));
  const summitX = ladderX + ladderW / 2;

  return (
    <div className={cx('hz-ladder', `hz-${mode}`)}>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${title ?? 'Horizon ladder'}: ${real.map((c) => `${c.label} reached level ${c.climb.height}`).join('; ')}`}>
        <defs>
          <linearGradient id="hz-sky" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="var(--hz-sky-top)" />
            <stop offset="1" stopColor="var(--hz-sky-bottom)" />
          </linearGradient>
          <linearGradient id="hz-rock" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="var(--hz-rock-top)" />
            <stop offset="1" stopColor="var(--hz-rock-bottom)" />
          </linearGradient>
          <linearGradient id="hz-rock2" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="var(--hz-far-top)" />
            <stop offset="1" stopColor="var(--hz-far-bottom)" />
          </linearGradient>
          <filter id="hz-glow" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="6" result="b" />
            <feMerge>
              <feMergeNode in="b" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>
        <rect x="0" y="0" width={W} height={H} rx="18" fill="url(#hz-sky)" />
        {/* far range */}
        <path d={`M0 ${ground} L90 ${H * 0.42} L190 ${H * 0.55} L300 ${H * 0.3} L420 ${H * 0.48} L560 ${H * 0.36} L700 ${H * 0.52} L820 ${H * 0.28} L1000 ${H * 0.46} L1000 ${ground} Z`} fill="url(#hz-rock2)" opacity="0.8" />
        {/* the mountain the ladder climbs */}
        <path d={`M40 ${ground} L${summitX - 150} ${top + 160} L${summitX - 60} ${top + 90} L${summitX} ${top - 12} L${summitX + 70} ${top + 110} L${summitX + 170} ${top + 70} L${summitX + 330} ${ground - 120} L${W - 30} ${ground} Z`} fill="url(#hz-rock)" />
        <path d={`M${summitX - 60} ${top + 90} L${summitX} ${top - 12} L${summitX + 70} ${top + 110} L${summitX + 36} ${top + 84} L${summitX + 8} ${top + 104} L${summitX - 22} ${top + 76} Z`} className="hz-snow" />
        {/* summit flag: level N */}
        <g transform={`translate(${summitX} ${top - 12})`}>
          <line x1="0" y1="0" x2="0" y2="-46" className="hz-pole" />
          <path d="M0 -46 L40 -36 L0 -26 Z" className={cx('hz-flag', topHeight >= levels && 'won')} />
        </g>
        <rect x="0" y={ground} width={W} height={H - ground} className="hz-ground" />

        {/* ladder */}
        <g className="hz-rails">
          <line x1={ladderX} y1={ground + 4} x2={ladderX} y2={yOf(levels) - 26} />
          <line x1={ladderX + ladderW} y1={ground + 4} x2={ladderX + ladderW} y2={yOf(levels) - 26} />
        </g>
        {Array.from({ length: levels }, (_, i) => {
          const level = i + 1;
          const y = yOf(level);
          const reached = real.some((c) => c.climb.height >= level);
          return (
            <g key={level} className={cx('hz-rung', reached && 'reached', highlight === level && 'hl')}>
              <line x1={ladderX} y1={y} x2={ladderX + ladderW} y2={y} />
              <text x={ladderX - 18} y={y + 7} textAnchor="end" className="hz-lv">
                {level}
              </text>
              {captions[i] && (
                <text x={ladderX + ladderW + 22} y={y + 7} className="hz-cap">
                  {captions[i]}
                </text>
              )}
              {climbers.map((c, ci) => {
                const r = c.climb.rungs[i];
                const state = !r || r.attempts === 0 ? 'none' : r.reliable ? 'full' : r.mean > 0 ? 'some' : 'fail';
                return <circle key={c.id} cx={colX(ci)} cy={y} r={state === 'full' ? 9 : 8} className={cx('hz-dot', `s-${state}`)} style={{ ['--c' as string]: c.color } as CSSProperties} />;
              })}
            </g>
          );
        })}
        <text x={ladderX - 18} y={ground + 34} textAnchor="end" className="hz-lv base">
          start
        </text>
        <text x={summitX} y={top - 70} textAnchor="middle" className="hz-summit">
          Level {levels}: the horizon
        </text>

        {/* climbers */}
        {climbers.map((c, ci) => {
          const h = up ? c.climb.height : 0;
          const x = colX(ci);
          const y = h === 0 ? ground + 26 : yOf(h);
          return (
            <g key={c.id} className={cx('hz-climber', c.baseline && 'baseline')} style={{ transform: `translate(${x}px, ${y}px)`, ['--c' as string]: c.color, transitionDelay: `${200 + ci * 140}ms` } as CSSProperties}>
              <circle r="21" className="hz-tok" filter={c.baseline ? undefined : 'url(#hz-glow)'} />
              <text y="6" textAnchor="middle" className="hz-tok-t">
                {c.baseline ? '?' : initials(c.label)}
              </text>
            </g>
          );
        })}
      </svg>
      <ol className="hz-board" aria-label="Highest level solved reliably">
        {[...climbers]
          .sort((a, b) => Number(!!a.baseline) - Number(!!b.baseline) || b.climb.height - a.climb.height || (b.climb.score ?? 0) - (a.climb.score ?? 0))
          .map((c) => (
            <li key={c.id} className={cx(c.baseline && 'baseline')} style={{ ['--c' as string]: c.color } as CSSProperties}>
              <i className="hz-sw" aria-hidden="true" />
              <span className="hz-who">
                <b>{c.baseline ? 'Random guessing' : c.label}</b>
                <small>
                  {c.climb.score === null ? 'not run' : `test score ${Math.round(c.climb.score * 100)}`}
                  {c.climb.best > c.climb.height ? ` · once solved level ${c.climb.best}` : ''}
                </small>
              </span>
              <span className="hz-h">
                <small>Level</small>
                <b className="tnum">{c.climb.height}</b>
                <small>of {levels}</small>
              </span>
            </li>
          ))}
      </ol>
      <ul className="hz-legend" aria-label="Colour key">
        <li>
          <i className="hz-lg s-full" /> Solved reliably
        </li>
        <li>
          <i className="hz-lg s-some" /> Solved sometimes, or partly
        </li>
        <li>
          <i className="hz-lg s-fail" /> Failed
        </li>
        <li>
          <i className="hz-lg s-none" /> Not run
        </li>
      </ul>
    </div>
  );
}
