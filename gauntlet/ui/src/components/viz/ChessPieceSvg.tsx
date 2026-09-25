/**
 * Chess pieces drawn as inline SVG (original shapes, no font glyphs), so they
 * look identical on Windows, macOS and Linux and scale crisply on video.
 * `piece` is one of k q r b n p; `color` 'w' or 'b'.
 */
import type { CSSProperties } from 'react';

const BASE = 'M24 80h52a5 5 0 0 1 5 5v2a5 5 0 0 1-5 5H24a5 5 0 0 1-5-5v-2a5 5 0 0 1 5-5Z';

/** Body shapes on a 100×100 grid (the base is shared). */
const SHAPES: Record<string, { body: string[]; circles?: Array<[number, number, number]>; detail?: string[] }> = {
  p: {
    body: ['M40 50h20l-2 6c0 10 4 17 12 24H30c8-7 12-14 12-24Z', 'M36 44h28a3 3 0 0 1 0 6H36a3 3 0 0 1 0-6Z'],
    circles: [[50, 31, 13]],
  },
  r: {
    body: ['M36 46h28l4 34H32Z', 'M27 20h10v8h6v-8h14v8h6v-8h10v18a6 6 0 0 1-6 6H33a6 6 0 0 1-6-6Z'],
    detail: ['M34 72h32'],
  },
  b: {
    body: ['M43 57h14c3 9 6 16 10 23H33c4-7 7-14 10-23Z', 'M36 50h28a3 3 0 0 1 0 7H36a3 3 0 0 1 0-7Z', 'M50 17c13 10 17 22 11 33H39c-6-11-2-23 11-33Z'],
    circles: [[50, 13, 5]],
    detail: ['M55 27 46 39'],
  },
  n: {
    body: ['M30 80c-1-16 7-24 14-31-8 4-15 6-20 1-5-6 2-15 12-23l3-11 8 8c18 0 29 17 27 37l-2 19Z'],
    circles: [],
    detail: ['M38 33.5h.1', 'M29 47l4-3'],
  },
  q: {
    body: ['M22 32l8 25 6-29 7 29 7-33 7 33 7-29 6 29 8-25-6 40H28Z', 'M28 70h44l-1 10H29Z'],
    circles: [
      [22, 28, 5],
      [36, 24, 5],
      [50, 19, 5],
      [64, 24, 5],
      [78, 28, 5],
    ],
  },
  k: {
    body: ['M30 79c-7-16-2-33 12-32l8 9 8-9c14-1 19 16 12 32Z', 'M44 34h12v14H44Z', 'M46 9h8v8h8v8h-8v9h-8v-9h-8v-8h8Z'],
    detail: ['M34 68h32'],
  },
};

export function ChessPieceSvg({ piece, color, className, style, title }: { piece: string; color: 'w' | 'b'; className?: string; style?: CSSProperties; title?: string }) {
  const s = SHAPES[piece.toLowerCase()];
  if (!s) return null;
  const fill = color === 'w' ? '#fbf8f1' : '#23272e';
  const stroke = color === 'w' ? '#1b1d21' : '#0a0b0d';
  const detail = color === 'w' ? '#1b1d21' : '#e9e2d0';
  return (
    <svg viewBox="0 0 100 100" className={className} style={style} role={title ? 'img' : undefined} aria-hidden={title ? undefined : true} focusable="false">
      {title && <title>{title}</title>}
      <g fill={fill} stroke={stroke} strokeWidth={3.2} strokeLinejoin="round" strokeLinecap="round">
        <path d={BASE} />
        {s.body.map((d, i) => (
          <path key={i} d={d} />
        ))}
        {(s.circles ?? []).map(([cx, cy, r], i) => (
          <circle key={`c${i}`} cx={cx} cy={cy} r={r} />
        ))}
      </g>
      {s.detail && (
        <g fill="none" stroke={detail} strokeWidth={3.4} strokeLinecap="round">
          {s.detail.map((d, i) => (
            <path key={i} d={d} strokeWidth={d.endsWith('.1') ? 6 : 3.4} />
          ))}
        </g>
      )}
    </svg>
  );
}

export { PIECE_NAME, PIECE_VALUE } from './chessPieceInfo.ts';
