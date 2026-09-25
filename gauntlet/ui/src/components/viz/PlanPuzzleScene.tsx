/**
 * Drawings of the Shortest Plans puzzles (jugs, coins, crossings, Hanoi,
 * sliding tiles, lights out, pancakes, key-and-door maze, traffic jam) at one
 * step of a plan. Pieces keep their identity between steps so CSS transitions
 * move them smoothly (disabled under prefers-reduced-motion in viz.css).
 */
import { useId, useMemo } from 'react';
import type { CSSProperties } from 'react';
import type { PlanFrame, PlanPuzzle, PlanState } from '../../../../src/presenter/visuals/planning.ts';
import { cx } from '../ui.tsx';

type Of<K extends PlanState['kind']> = Extract<PlanState, { kind: K }>;

function Jugs({ z, s, mark }: { z: Extract<PlanPuzzle, { kind: 'jugs' }>; s: Of<'jugs'>; mark: number[] }) {
  const maxCap = Math.max(...z.caps);
  const W = 120;
  const gap = 60;
  const H = 200;
  const width = z.caps.length * W + (z.caps.length - 1) * gap + 40;
  const uid = useId().replace(/:/g, '');
  return (
    <svg className="vz-scene-svg" viewBox={`0 0 ${width} ${H + 70}`} role="img" aria-label={`Jugs: ${s.v.map((v, i) => `${v} of ${z.caps[i]} litres`).join(', ')}`}>
      {z.caps.map((cap, i) => {
        const h = (cap / maxCap) * H;
        const x = 20 + i * (W + gap);
        const y = 10 + H - h;
        const hit = s.v[i] === z.amount;
        return (
          <g key={i} className={cx('vz-jug', mark.includes(i) && 'marked', hit && 'hit')}>
            <clipPath id={`${uid}-jug-${i}`}>
              <rect x={x} y={y} width={W} height={h} rx="14" />
            </clipPath>
            <rect className="vz-jug-glass" x={x} y={y} width={W} height={h} rx="14" />
            <g clipPath={`url(#${uid}-jug-${i})`}>
              <rect className="vz-jug-water" x={x} y={y} width={W} height={h} style={{ transform: `scaleY(${s.v[i]! / cap})` }} />
            </g>
            <rect className="vz-jug-rim" x={x} y={y} width={W} height={h} rx="14" />
            <text className="vz-jug-v" x={x + W / 2} y={y + h / 2 + 12} textAnchor="middle">
              {s.v[i]} L
            </text>
            <text className="vz-jug-cap" x={x + W / 2} y={H + 44} textAnchor="middle">
              {cap} L jug
            </text>
          </g>
        );
      })}
    </svg>
  );
}

function Coins({ s, mark }: { s: Of<'coins'>; mark: number[] }) {
  const R = 30;
  const step = 76;
  const width = s.v.length * step + 20;
  return (
    <svg className="vz-scene-svg" viewBox={`0 0 ${width} 120`} role="img" aria-label={`Coins: ${s.v.map((h) => (h ? 'heads' : 'tails')).join(', ')}`}>
      {mark.length > 0 && <rect className="vz-coin-window" x={10 + mark[0]! * step} y="8" width={mark.length * step} height="92" rx="16" />}
      {s.v.map((h, i) => (
        <g key={i} className={cx('vz-coin', h ? 'heads' : 'tails', mark.includes(i) && 'marked')}>
          <circle cx={10 + i * step + step / 2} cy="54" r={R} />
          <text x={10 + i * step + step / 2} y="64" textAnchor="middle">
            {h ? 'H' : 'T'}
          </text>
          <text className="vz-coin-n" x={10 + i * step + step / 2} y="114" textAnchor="middle">
            {i + 1}
          </text>
        </g>
      ))}
    </svg>
  );
}

function Crossing({ z, s, mark }: { z: Extract<PlanPuzzle, { kind: 'crossing' }>; s: Of<'crossing'>; mark: number[] }) {
  const side = (start: boolean) =>
    z.people.map((p, i) => ({ p, i })).filter(({ i }) => s.start[i] === start);
  const chip = ({ p, i }: { p: (typeof z.people)[number]; i: number }) => (
    <li key={p.name} className={cx('vz-person', mark.includes(i) && 'marked')}>
      <b>{p.name}</b>
      <small className="tnum">{[p.time !== undefined ? `${p.time} min` : '', p.weight !== undefined ? `${p.weight} kg` : ''].filter(Boolean).join(' · ')}</small>
    </li>
  );
  const Lantern = () => (
    <span className="vz-lantern" aria-label={z.vehicle === 'gondola' ? 'gondola' : 'lantern'}>
      <svg viewBox="0 0 24 24" aria-hidden="true">
        {z.vehicle === 'gondola' ? <path d="M4 3h16M12 3v4M6 7h12v11a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2zM6 12h12" /> : <path d="M9 3h6M10 3v2M14 3v2M7 7h10l-1 12H8zM12 11v4" />}
      </svg>
    </span>
  );
  return (
    <div className="vz-cross">
      <div className={cx('vz-bank', s.lanternStart && 'has-lantern')}>
        <div className="vz-bank-k">
          {z.places[0]} {s.lanternStart && <Lantern />}
        </div>
        <ul>{side(true).map(chip)}</ul>
      </div>
      <div className={cx('vz-bridge', z.vehicle)} aria-hidden="true">
        <span>{z.vehicle === 'gondola' ? 'cable' : 'bridge'}</span>
        <small>
          max {z.cap} people{z.maxWeight ? ` · ${z.maxWeight} kg` : ''}
        </small>
      </div>
      <div className={cx('vz-bank', 'far', !s.lanternStart && 'has-lantern')}>
        <div className="vz-bank-k">
          {z.places[1]} {!s.lanternStart && <Lantern />}
        </div>
        <ul>{side(false).map(chip)}</ul>
      </div>
    </div>
  );
}

function Hanoi({ z, s, mark }: { z: Extract<PlanPuzzle, { kind: 'hanoi' }>; s: Of<'hanoi'>; mark: number[] }) {
  const pegW = 220;
  const diskH = 26;
  const H = z.disks * diskH + 70;
  const width = z.pegs.length * pegW;
  const pos = new Map<number, { x: number; y: number }>();
  s.v.forEach((peg, pi) => peg.forEach((d, k) => pos.set(d, { x: pi * pegW + pegW / 2, y: H - 30 - (k + 1) * diskH })));
  return (
    <svg className="vz-scene-svg" viewBox={`0 0 ${width} ${H + 10}`} role="img" aria-label="Pegs and disks">
      {z.pegs.map((p, i) => (
        <g key={p} className={cx('vz-peg', i === z.target && 'target')}>
          <rect x={i * pegW + pegW / 2 - 5} y="24" width="10" height={H - 54} rx="4" />
          <rect x={i * pegW + 14} y={H - 30} width={pegW - 28} height="8" rx="4" />
          <text x={i * pegW + pegW / 2} y={H + 4} textAnchor="middle">
            Peg {p}
            {i === z.target ? ' (goal)' : ''}
          </text>
        </g>
      ))}
      {Array.from({ length: z.disks }, (_, k) => k + 1).map((d) => {
        const p = pos.get(d)!;
        const w = 50 + (d / z.disks) * (pegW - 70);
        return (
          <g key={d} className={cx('vz-disk', mark.includes(d) && 'marked')} style={{ transform: `translate(${p.x - w / 2}px, ${p.y}px)`, ['--d' as string]: d / z.disks } as CSSProperties}>
            <rect width={w} height={diskH - 4} rx="10" />
            <text x={w / 2} y={diskH - 10} textAnchor="middle">
              {d}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

/** Tile identities across frames, so each physical tile animates to its new square. */
function tileIds(frames: PlanFrame[]): number[][] {
  const out: number[][] = [];
  let ids: number[] = [];
  frames.forEach((f, fi) => {
    const v = (f.state as Of<'sliding'>).v;
    if (fi === 0) ids = v.map((_, i) => i);
    else {
      const prev = (frames[fi - 1]!.state as Of<'sliding'>).v;
      const a = prev.indexOf('_');
      const b = v.indexOf('_');
      ids = ids.slice();
      [ids[a], ids[b]] = [ids[b]!, ids[a]!];
    }
    out.push(ids);
  });
  return out;
}

const TILE_COLORS: Record<string, string> = { R: 'red', Y: 'yellow', B: 'blue', G: 'green' };

function Sliding({ z, frames, idx }: { z: Extract<PlanPuzzle, { kind: 'sliding' }>; frames: PlanFrame[]; idx: number }) {
  const ids = useMemo(() => tileIds(frames), [frames]);
  const s = frames[idx]!.state as Of<'sliding'>;
  const mark = frames[idx]!.mark;
  const at = ids[idx]!;
  const tiles = s.v.map((label, cell) => ({ label, cell, id: at[cell]! })).filter((t) => t.label !== '_').sort((a, b) => a.id - b.id);
  return (
    <div className="vz-slide-wrap">
      <div className="vz-tiles" style={{ ['--r' as string]: z.rows, ['--c' as string]: z.cols } as CSSProperties} role="img" aria-label={`Tiles: ${s.v.join(' ')}`}>
        {tiles.map((t) => (
          <div
            key={t.id}
            className={cx('vz-tile', TILE_COLORS[t.label] && `c-${TILE_COLORS[t.label]}`, mark.includes(t.cell) && 'marked', z.goal[t.cell] === t.label && 'home')}
            style={{ transform: `translate(${(t.cell % z.cols) * 100}%, ${Math.floor(t.cell / z.cols) * 100}%)` }}
          >
            <span>{t.label}</span>
          </div>
        ))}
      </div>
      <div className="vz-goal-mini" aria-label="Goal position">
        <small>Goal</small>
        <div className="vz-goal-grid" style={{ ['--c' as string]: z.cols } as CSSProperties}>
          {z.goal.map((g, i) => (
            <i key={i} className={cx(g === '_' && 'blank', TILE_COLORS[g] && `c-${TILE_COLORS[g]}`)}>{g === '_' ? '' : g}</i>
          ))}
        </div>
      </div>
    </div>
  );
}

function Lights({ z, s, mark }: { z: Extract<PlanPuzzle, { kind: 'lights' }>; s: Of<'lights'>; mark: number[] }) {
  return (
    <div className="vz-lamps" style={{ ['--c' as string]: z.cols } as CSSProperties} role="img" aria-label={`${s.v.filter(Boolean).length} lamps on`}>
      {s.v.map((on, i) => (
        <span key={i} className={cx('vz-lamp', on && 'on', mark.includes(i) && 'pressed')} />
      ))}
    </div>
  );
}

function Pancakes({ z, s, mark }: { z: Extract<PlanPuzzle, { kind: 'pancakes' }>; s: Of<'pancakes'>; mark: number[] }) {
  const n = z.start.length;
  const H = 34;
  return (
    <div className="vz-stack" style={{ height: n * H + 20 }} role="img" aria-label={`Stack from top: ${s.v.join(', ')}`}>
      {mark.length > 0 && <div className="vz-spatula" style={{ top: mark.length * H + 4 }} aria-hidden="true" />}
      {s.v.map((size, pos) => ({ size, pos }))
        .sort((a, b) => a.size - b.size)
        .map(({ size, pos }) => (
          <div
            key={size}
            className={cx('vz-pancake', mark.includes(pos) && 'marked', size === pos + 1 && (!s.up || !s.up[pos]) && 'home')}
            style={{ transform: `translate(-50%, ${pos * H}px)`, width: `${30 + (size / n) * 62}%` }}
          >
            {s.up && <i className={cx('vz-burnt', s.up[pos] ? 'up' : 'down')} aria-label={s.up[pos] ? 'burnt side up' : 'burnt side down'} />}
            <span className="tnum">{size}</span>
          </div>
        ))}
    </div>
  );
}

function Maze({ z, frames, idx }: { z: Extract<PlanPuzzle, { kind: 'maze' }>; frames: PlanFrame[]; idx: number }) {
  const cols = z.grid[0]!.length;
  const s = frames[idx]!.state as Of<'maze'>;
  const trail = new Set(frames.slice(0, idx + 1).map((f) => {
    const m = f.state as Of<'maze'>;
    return m.r * cols + m.c;
  }));
  return (
    <div className="vz-maze" style={{ ['--c' as string]: cols, ['--r' as string]: z.grid.length } as CSSProperties} role="img" aria-label={`Robot at row ${s.r + 1}, column ${s.c + 1}; keys ${s.keys || 'none'}`}>
      {z.grid.flatMap((row, r) =>
        [...row].map((ch, c) => {
          const key = /[a-z]/.test(ch);
          const door = /[A-Z]/.test(ch) && ch !== 'S' && ch !== 'E';
          const have = key ? s.keys.includes(ch) : door ? s.keys.includes(ch.toLowerCase()) : false;
          return (
            <span key={`${r}-${c}`} className={cx('vz-mz', ch === '#' ? 'wall' : 'floor', trail.has(r * cols + c) && 'trail', key && 'key', door && 'door', have && 'have', ch === 'E' && 'exit', ch === 'S' && 'start')}>
              {ch !== '#' && ch !== '.' ? ch : ''}
            </span>
          );
        }),
      )}
      <span className="vz-robot" style={{ transform: `translate(${s.c * 100}%, ${s.r * 100}%)` }} aria-hidden="true" />
    </div>
  );
}

function Traffic({ z, s, mark }: { z: Extract<PlanPuzzle, { kind: 'traffic' }>; s: Of<'traffic'>; mark: number[] }) {
  const ids = [...new Set(z.grid.join('').replace(/\./g, ''))];
  const box = (id: string) => {
    let r0 = 9;
    let c0 = 9;
    let r1 = -1;
    let c1 = -1;
    s.v.forEach((row, r) =>
      [...row].forEach((ch, c) => {
        if (ch !== id) return;
        r0 = Math.min(r0, r);
        c0 = Math.min(c0, c);
        r1 = Math.max(r1, r);
        c1 = Math.max(c1, c);
      }),
    );
    return { r0, c0, h: r1 - r0 + 1, w: c1 - c0 + 1 };
  };
  const moved = new Set(mark.map((m) => s.v[Math.floor(m / 6)]![m % 6]));
  return (
    <div className="vz-traffic" role="img" aria-label="Traffic grid">
      <div className="vz-exit" style={{ top: `${(z.grid.findIndex((r) => r.includes(z.target)) / 6) * 100}%` }} aria-hidden="true">
        exit
      </div>
      {ids.map((id) => {
        const b = box(id);
        return (
          <div
            key={id}
            className={cx('vz-car', id === z.target && 'target', moved.has(id) && 'marked')}
            style={{ left: `${(b.c0 / 6) * 100}%`, top: `${(b.r0 / 6) * 100}%`, width: `${(b.w / 6) * 100}%`, height: `${(b.h / 6) * 100}%` }}
          >
            <span>{id}</span>
          </div>
        );
      })}
    </div>
  );
}

export function PlanPuzzleScene({ puzzle, frames, idx }: { puzzle: PlanPuzzle; frames: PlanFrame[]; idx: number }) {
  const f = frames[Math.min(idx, frames.length - 1)]!;
  const s = f.state;
  switch (puzzle.kind) {
    case 'jugs':
      return <Jugs z={puzzle} s={s as Of<'jugs'>} mark={f.mark} />;
    case 'coins':
      return <Coins s={s as Of<'coins'>} mark={f.mark} />;
    case 'crossing':
      return <Crossing z={puzzle} s={s as Of<'crossing'>} mark={f.mark} />;
    case 'hanoi':
      return <Hanoi z={puzzle} s={s as Of<'hanoi'>} mark={f.mark} />;
    case 'sliding':
      return <Sliding z={puzzle} frames={frames} idx={idx} />;
    case 'lights':
      return <Lights z={puzzle} s={s as Of<'lights'>} mark={f.mark} />;
    case 'pancakes':
      return <Pancakes z={puzzle} s={s as Of<'pancakes'>} mark={f.mark} />;
    case 'maze':
      return <Maze z={puzzle} frames={frames} idx={idx} />;
    case 'traffic':
      return <Traffic z={puzzle} s={s as Of<'traffic'>} mark={f.mark} />;
  }
}
