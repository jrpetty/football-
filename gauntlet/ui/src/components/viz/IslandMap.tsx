/**
 * Illustrated Survival Island map: terrain drawn as shapes (sea, sand, grass,
 * forest, palms, rocks, the summit, the spring, the cave, berry bushes), the
 * castaway gliding along its path with a trail behind it, fog of war over
 * unexplored tiles, campfires, shelters, the signal pile / fire, the ship on
 * the horizon when it passes, and the time of day and weather over the scene.
 * Pure SVG; the static terrain is memoised, so stepping costs almost nothing.
 */
import { memo, useMemo } from 'react';
import type { IslandSimFrame, IslandSimWorld, XY } from '../../types.ts';
import { berryColour } from './SimIcon.tsx';

const U = 40;

const BASE: Record<string, string> = {
  ',': '#7fbf5c',
  T: '#3f8c47',
  P: '#8fc766',
  '*': '#74b155',
  '^': '#9ea5ad',
  A: '#8c857c',
  S: '#7fbf5c',
  C: '#8a8f97',
};

function Palm({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <path d="M20 34 C19 26 21 20 24 13" stroke="#8a5a2b" strokeWidth="3.2" fill="none" strokeLinecap="round" />
      <path d="M24 13 C18 9 12 11 9 15 C14 12 19 12 24 13Z" fill="#2f8a3a" />
      <path d="M24 13 C29 8 35 9 37 13 C32 11 28 11 24 13Z" fill="#37993f" />
      <path d="M24 13 C23 7 26 4 30 4 C27 7 25 9 24 13Z" fill="#2c7f36" />
      <circle cx="23" cy="15" r="1.8" fill="#6b3f1a" />
    </g>
  );
}

function Pines({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <path d="M12 32 L20 10 L28 32Z" fill="#1f6a33" />
      <path d="M22 35 L29 16 L36 35Z" fill="#257a3a" />
      <rect x="19" y="31" width="2" height="4" fill="#6b4524" />
      <rect x="28" y="34" width="2" height="3" fill="#6b4524" />
    </g>
  );
}

function Grass({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`} stroke="#4f8f36" strokeWidth="1.6" strokeLinecap="round" fill="none">
      <path d="M10 28 l2 -6 M13 28 l0 -7 M16 28 l-2 -5" />
      <path d="M25 17 l2 -6 M28 17 l0 -7 M31 17 l-2 -5" />
    </g>
  );
}

function Rocks({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <path d="M7 31 L11 20 L19 17 L24 24 L22 32Z" fill="#7d858f" stroke="#5a616b" strokeWidth="1" />
      <path d="M21 32 L25 23 L32 22 L35 30 L32 34Z" fill="#8b939c" stroke="#5a616b" strokeWidth="1" />
    </g>
  );
}

function Summit({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <path d="M2 36 L20 5 L38 36Z" fill="#6f665d" stroke="#4d463f" strokeWidth="1" />
      <path d="M14 15 L20 5 L26 15 L23 13 L20 16 L17 13Z" fill="#f2efe8" />
    </g>
  );
}

function Spring({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <ellipse cx="20" cy="21" rx="14" ry="10" fill="#2f8fd8" stroke="#1f6aa8" strokeWidth="1.2" />
      <ellipse cx="15" cy="18" rx="5" ry="2" fill="#9fd6ff" opacity="0.8" />
    </g>
  );
}

function Cave({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <path d="M4 34 C4 14 12 6 20 6 C28 6 36 14 36 34Z" fill="#6b7078" />
      <path d="M12 34 C12 22 16 17 20 17 C24 17 28 22 28 34Z" fill="#1a1d22" />
    </g>
  );
}

function Bush({ x, y, colour, skull }: { x: number; y: number; colour: string; skull: boolean }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <circle cx="20" cy="22" r="12" fill="#2f7a32" />
      <circle cx="13" cy="18" r="7" fill="#378a39" />
      <circle cx="27" cy="18" r="7" fill="#378a39" />
      {[
        [14, 20],
        [22, 16],
        [26, 24],
        [18, 27],
        [11, 25],
      ].map(([bx, by]) => (
        <circle key={`${bx}-${by}`} cx={bx} cy={by} r="2.6" fill={colour} stroke="rgba(0,0,0,.4)" strokeWidth=".6" />
      ))}
      {skull && (
        <g transform="translate(24 2)">
          <circle cx="7" cy="7" r="8" fill="#d03b3b" stroke="#fff" strokeWidth="1.2" />
          <path d="M7 2.5a4 4 0 0 0-4 4c0 1.4.7 2.4 1.7 3v2h4.6v-2c1-.6 1.7-1.6 1.7-3a4 4 0 0 0-4-4z" fill="#fff" />
          <circle cx="5.6" cy="6.6" r="1" fill="#d03b3b" />
          <circle cx="8.4" cy="6.6" r="1" fill="#d03b3b" />
        </g>
      )}
    </g>
  );
}

function Hut({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <path d="M5 33 L20 9 L35 33Z" fill="#b07a3a" stroke="#5e3a14" strokeWidth="1.4" />
      <path d="M9 27 L31 27 M12 22 L28 22 M15 17 L25 17" stroke="#7d5220" strokeWidth="1" />
      <path d="M16 33 L20 25 L24 33Z" fill="#2c1a0a" />
    </g>
  );
}

function Flame({ x, y, big = false }: { x: number; y: number; big?: boolean }) {
  const s = big ? 1.5 : 1;
  return (
    <g transform={`translate(${x + 20} ${y + 24}) scale(${s})`}>
      <circle r="17" fill="url(#im-glow)" />
      <path d="M-7 10 L7 6 M-7 6 L7 10" stroke="#6b3f1e" strokeWidth="2.4" strokeLinecap="round" />
      <path className="im-flame" d="M0 -14 C5 -7 9 -4 9 2 A9 9 0 0 1 -9 2 C-9 -2 -7 -5 -4 -7 C-4 -4 -2 -2 -1 -2 C-1 -6 -3 -9 0 -14Z" fill="#f26b1d" />
      <path d="M0 -3 C2 0 4 2 4 4 A4 4 0 0 1 -4 4 C-4 2 -2 0 0 -3Z" fill="#ffd23d" />
    </g>
  );
}

function Logs({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`} fill="#8a5530" stroke="#4e2c12" strokeWidth=".9">
      <rect x="6" y="28" width="28" height="5" rx="2" />
      <rect x="9" y="23" width="22" height="5" rx="2" />
      <rect x="13" y="18" width="14" height="5" rx="2" />
    </g>
  );
}

function Bottle({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x + 12} ${y + 10}) rotate(35 8 10)`}>
      <path d="M6 0h4v5l3 4v12H3V9l3-4z" fill="#6fc3d8" stroke="#1f6f86" strokeWidth="1" />
      <rect x="5" y="12" width="6" height="6" fill="#f3ead2" />
    </g>
  );
}

function Ship({ x, y, label }: { x: number; y: number; label: string }) {
  return (
    <g className="im-ship" transform={`translate(${x} ${y})`}>
      <path d="M0 22 H56 L48 34 H8Z" fill="#7a3b22" stroke="#3e1c0e" strokeWidth="1.2" />
      <path d="M26 2 V22" stroke="#5a3a1a" strokeWidth="2" />
      <path d="M27 4 L44 20 H27Z" fill="#f4f1e8" />
      <path d="M25 6 L12 20 H25Z" fill="#e2ddd0" />
      <rect x="-6" y="-22" width={Math.max(70, label.length * 8.2)} height="18" rx="9" fill="rgba(5,8,13,.78)" />
      <text x={-6 + Math.max(70, label.length * 8.2) / 2} y="-9" textAnchor="middle" fontSize="12" fontWeight="700" fill="#fff">
        {label}
      </text>
    </g>
  );
}

const Terrain = memo(function Terrain({ world, poisonKnown }: { world: IslandSimWorld; poisonKnown: boolean }) {
  const n = world.size;
  const tiles: Array<{ x: number; y: number; ch: string }> = [];
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) tiles.push({ x, y, ch: world.terrain[y]?.[x] ?? '~' });
  const land = tiles.filter((t) => t.ch !== '~');
  const inner = land.filter((t) => t.ch !== '.');
  const bush = new Map(world.bushes.map((b) => [`${b.at[0]},${b.at[1]}`, b]));
  return (
    <g>
      <rect width={n * U} height={n * U} fill="url(#im-sea)" />
      <rect width={n * U} height={n * U} fill="url(#im-waves)" />
      <g fill="#5ec0dd" opacity="0.55">
        {land.map((t) => (
          <circle key={`s${t.x},${t.y}`} cx={t.x * U + U / 2} cy={t.y * U + U / 2} r={U * 0.95} />
        ))}
      </g>
      <g fill="#ecd294">
        {land.map((t) => (
          <rect key={`b${t.x},${t.y}`} x={t.x * U - 5} y={t.y * U - 5} width={U + 10} height={U + 10} rx={U * 0.45} />
        ))}
      </g>
      <g>
        {inner.map((t) => (
          <rect key={`i${t.x},${t.y}`} x={t.x * U - 1} y={t.y * U - 1} width={U + 2} height={U + 2} rx={U * 0.28} fill={BASE[t.ch] ?? '#7fbf5c'} />
        ))}
      </g>
      <g>
        {tiles.map((t) => {
          const x = t.x * U;
          const y = t.y * U;
          const k = `g${t.x},${t.y}`;
          switch (t.ch) {
            case 'P':
              return <Palm key={k} x={x} y={y} />;
            case 'T':
              return <Pines key={k} x={x} y={y} />;
            case ',':
              return <Grass key={k} x={x} y={y} />;
            case '^':
              return <Rocks key={k} x={x} y={y} />;
            case 'A':
              return <Summit key={k} x={x} y={y} />;
            case 'S':
              return <Spring key={k} x={x} y={y} />;
            case 'C':
              return <Cave key={k} x={x} y={y} />;
            case '*': {
              const b = bush.get(`${t.x},${t.y}`);
              return <Bush key={k} x={x} y={y} colour={berryColour(b?.colour)} skull={!!b?.poison && poisonKnown} />;
            }
            default:
              return null;
          }
        })}
      </g>
    </g>
  );
});

export interface IslandMapProps {
  world: IslandSimWorld;
  frame: IslandSimFrame;
  trail: XY[];
  /** The ship this step: passing by, sounding its horn (signal seen) or coming to rescue. */
  ship: 'pass' | 'ack' | 'rescue' | null;
  poisonKnown: boolean;
  /** Show every tile (the finale): no fog. */
  reveal?: boolean;
  /** Token glide time. */
  moveMs: number;
}

export function IslandMap({ world, frame, trail, ship, poisonKnown, reveal = false, moveMs }: IslandMapProps) {
  const n = world.size;
  const W = n * U;
  const weather = world.weather[frame.day] ?? 'clear';
  const phase = frame.phase;
  const fogTiles = useMemo(() => {
    if (reveal) return [];
    const out: XY[] = [];
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (frame.explored[y]?.[x] !== '1') out.push([x, y]);
    return out;
  }, [frame.explored, n, reveal]);
  const seen = (p: XY) => reveal || frame.explored[p[1]]?.[p[0]] === '1';
  const [px, py] = frame.pos;
  const trailPts = trail.map(([x, y]) => `${x * U + U / 2},${y * U + U / 2}`).join(' ');
  const night = phase === 3;
  const tint = night ? { fill: '#0a1646', op: 0.52 } : phase === 2 ? { fill: '#ff7a2a', op: 0.13 } : phase < 0 ? { fill: '#ffb36b', op: 0.12 } : null;
  const wx = weather === 'storm' ? { fill: '#1b2230', op: 0.35 } : weather === 'rain' ? { fill: '#3a4a60', op: 0.18 } : weather === 'cloudy' ? { fill: '#8a96a6', op: 0.14 } : weather === 'hot' ? { fill: '#ffc93a', op: 0.1 } : null;
  const shipLabel = ship === 'rescue' ? 'RESCUE BOAT!' : ship === 'ack' ? 'Signal seen!' : 'Supply ship';
  // Top-right: clear of the day badge in the top-left corner.
  const shipX = W - 150;
  return (
    <svg className="island-map" viewBox={`0 0 ${W} ${W}`} role="img" aria-label={`Island map, day ${frame.day}. Castaway at column ${px + 1}, row ${py + 1}.`}>
      <defs>
        <linearGradient id="im-sea" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#1b5fa6" />
          <stop offset="1" stopColor="#2476c0" />
        </linearGradient>
        <pattern id="im-waves" width="48" height="28" patternUnits="userSpaceOnUse">
          <path d="M4 14 q6 -6 12 0 t12 0" stroke="rgba(255,255,255,.18)" strokeWidth="1.6" fill="none" />
        </pattern>
        <pattern id="im-rain" width="14" height="14" patternUnits="userSpaceOnUse" patternTransform="rotate(18)">
          <path d="M7 0 V9" stroke="rgba(190,220,255,.55)" strokeWidth="1.3" />
        </pattern>
        <radialGradient id="im-glow">
          <stop offset="0" stopColor="#ffb13b" stopOpacity="0.75" />
          <stop offset="1" stopColor="#ffb13b" stopOpacity="0" />
        </radialGradient>
        <filter id="im-fog" x="-5%" y="-5%" width="110%" height="110%">
          <feGaussianBlur stdDeviation="7" />
        </filter>
      </defs>

      <Terrain world={world} poisonKnown={poisonKnown} />

      {!frame.bottleFound && seen(world.bottle) && <Bottle x={world.bottle[0] * U} y={world.bottle[1] * U} />}
      {frame.shelters.map(([x, y]) => (
        <Hut key={`h${x},${y}`} x={x * U} y={y * U} />
      ))}
      {frame.fires.map(([x, y]) => (
        <Flame key={`f${x},${y}`} x={x * U} y={y * U} />
      ))}
      {frame.signal === 'built' && <Logs x={world.summit[0] * U} y={world.summit[1] * U} />}
      {frame.signal === 'lit' && <Flame x={world.summit[0] * U} y={world.summit[1] * U - 6} big />}

      {trail.length > 1 && (
        <g className="im-trail">
          <polyline points={trailPts} fill="none" stroke="rgba(255,255,255,.85)" strokeWidth="3" strokeDasharray="2 7" strokeLinecap="round" strokeLinejoin="round" />
        </g>
      )}

      {tint && <rect width={W} height={W} fill={tint.fill} opacity={tint.op} pointerEvents="none" />}
      {wx && <rect width={W} height={W} fill={wx.fill} opacity={wx.op} pointerEvents="none" />}
      {(weather === 'rain' || weather === 'storm') && <rect className="im-rain" width={W} height={W} fill="url(#im-rain)" opacity={weather === 'storm' ? 0.9 : 0.6} pointerEvents="none" />}
      {night && (
        <g fill="#fff" opacity="0.8" pointerEvents="none">
          {[
            [0.08, 0.06],
            [0.3, 0.04],
            [0.62, 0.07],
            [0.9, 0.05],
            [0.2, 0.93],
            [0.78, 0.94],
            [0.95, 0.4],
            [0.04, 0.55],
          ].map(([sx, sy]) => (
            <circle key={`${sx}`} cx={sx! * W} cy={sy! * W} r="1.8" />
          ))}
        </g>
      )}

      <g filter="url(#im-fog)">
        {fogTiles.map(([x, y]) => (
          <rect key={`q${x},${y}`} x={x * U - 4} y={y * U - 4} width={U + 8} height={U + 8} fill="var(--im-fog, #0c1422)" opacity="0.97" />
        ))}
      </g>

      <g className="im-token" style={{ transform: `translate(${px * U}px, ${py * U}px)`, transitionDuration: `${moveMs}ms` }}>
        <circle className="im-pulse" cx={U / 2} cy={U / 2} r={U * 0.62} fill="none" stroke="#fff" strokeWidth="2" />
        <circle cx={U / 2} cy={U / 2} r={U * 0.42} fill={frame.alive ? '#ef4444' : '#5b6270'} stroke="#fff" strokeWidth="3" />
        <circle cx={U / 2} cy={U / 2 - 6} r="4.2" fill="#fff" />
        <path d={`M${U / 2 - 7} ${U / 2 + 10} q7 -14 14 0z`} fill="#fff" />
      </g>

      {ship && <Ship x={shipX} y={ship === 'rescue' ? U * 0.9 : U * 0.55} label={shipLabel} />}
    </svg>
  );
}
