/**
 * Draw It Blind — scoring a drawing against its target scene.
 *
 * Every (target, drawn) pair gets a similarity in [0, 1]:
 *   0.30 · shape type + 0.30 · colour + 0.25 · position + 0.15 · size.
 * The best one-to-one assignment (maximum total similarity) is found exactly
 * with a DP over subsets of targets (≤ 10 targets, any number of drawn shapes).
 * Unmatched targets score 0; each unmatched drawn shape costs a small penalty.
 */
import { CANVAS } from './draw-it-blind-scene.ts';
import type { SceneShape, ShapeType } from './draw-it-blind-scene.ts';
import type { DrawnKind, DrawnShape } from './draw-it-blind-svg.ts';

export const WEIGHTS = { type: 0.3, color: 0.3, position: 0.25, size: 0.15 } as const;
/** Pairs below this similarity are not considered matches at all. */
export const MIN_PAIR = 0.25;
/** A pair at or above this similarity counts as "rebuilt". */
export const REBUILT = 0.75;
export const EXTRA_PENALTY = 0.03;
export const EXTRA_CAP = 0.15;
/** Centre distance (px) at which the position score reaches 0. */
export const POSITION_FALLOFF = 160;
const MAX_DRAWN = 60;

const TYPE_SIMILARITY: Record<ShapeType, Partial<Record<DrawnKind, number>>> = {
  circle: { circle: 1, ellipse: 0.6 },
  square: { square: 1, rectangle: 0.6, quad: 0.5 },
  rectangle: { rectangle: 1, square: 0.6, quad: 0.5 },
  triangle: { triangle: 1, polygon: 0.3 },
  star: { star: 1, polygon: 0.3 },
};

export interface PairScore {
  total: number;
  type: number;
  color: number;
  position: number;
  size: number;
  /** Centre distance in pixels. */
  distance: number;
}

const ratio = (a: number, b: number) => (a <= 0 || b <= 0 ? 0 : Math.min(a, b) / Math.max(a, b));

export function pairScore(t: SceneShape, d: DrawnShape): PairScore {
  const type = TYPE_SIMILARITY[t.type][d.kind] ?? 0;
  const color = t.color === d.color ? 1 : 0;
  const distance = Math.hypot(t.cx - d.cx, t.cy - d.cy);
  const position = Math.max(0, 1 - distance / POSITION_FALLOFF);
  const size = (ratio(t.w, d.w) + ratio(t.h, d.h)) / 2;
  const total = WEIGHTS.type * type + WEIGHTS.color * color + WEIGHTS.position * position + WEIGHTS.size * size;
  return { total, type, color, position, size, distance };
}

export interface MatchedPair {
  target: SceneShape;
  drawn: DrawnShape | null;
  score: PairScore | null;
}

export interface MatchResult {
  pairs: MatchedPair[];
  extras: DrawnShape[];
  /** Mean pair similarity over targets (unmatched = 0). */
  geometric: number;
  extraPenalty: number;
  /** geometric − extraPenalty, clamped to [0, 1]. */
  match: number;
  rebuilt: number;
}

/** Exact maximum-weight assignment via DP over target subsets. */
export function matchShapes(targets: readonly SceneShape[], drawnAll: readonly DrawnShape[]): MatchResult {
  const drawn = drawnAll.length <= MAX_DRAWN ? [...drawnAll] : [...drawnAll].sort((a, b) => b.w * b.h - a.w * a.h).slice(0, MAX_DRAWN);
  const n = targets.length;
  const M = 1 << n;
  const sim = targets.map((t) => drawn.map((d) => pairScore(t, d)));

  let best = new Float64Array(M).fill(-Infinity);
  best[0] = 0;
  const choice: Int8Array[] = [];
  const from: Int32Array[] = [];
  for (let i = 0; i < drawn.length; i++) {
    const next = best.slice();
    const ch = new Int8Array(M).fill(-1);
    const pm = new Int32Array(M);
    for (let mask = 0; mask < M; mask++) pm[mask] = mask;
    for (let mask = 0; mask < M; mask++) {
      if (best[mask] === -Infinity) continue;
      for (let t = 0; t < n; t++) {
        if (mask & (1 << t)) continue;
        const s = sim[t]![i]!.total;
        if (s < MIN_PAIR) continue;
        const nm = mask | (1 << t);
        const v = best[mask]! + s;
        if (v > next[nm]! + 1e-12) {
          next[nm] = v;
          ch[nm] = t;
          pm[nm] = mask;
        }
      }
    }
    best = next;
    choice.push(ch);
    from.push(pm);
  }

  let mask = 0;
  for (let m = 1; m < M; m++) if (best[m]! > best[mask]! + 1e-12) mask = m;
  const assignment = new Array<number>(n).fill(-1);
  for (let i = drawn.length - 1; i >= 0; i--) {
    const t = choice[i]![mask]!;
    if (t >= 0) {
      assignment[t] = i;
      mask = from[i]![mask]!;
    }
  }

  const used = new Set(assignment.filter((i) => i >= 0));
  const pairs: MatchedPair[] = targets.map((target, t) => {
    const i = assignment[t]!;
    return i >= 0 ? { target, drawn: drawn[i]!, score: sim[t]![i]! } : { target, drawn: null, score: null };
  });
  const extras = drawnAll.filter((d) => !used.has(drawn.indexOf(d)));
  const geometric = n === 0 ? 0 : pairs.reduce((a, p) => a + (p.score?.total ?? 0), 0) / n;
  const extraPenalty = Math.min(EXTRA_CAP, extras.length * EXTRA_PENALTY);
  return {
    pairs,
    extras,
    geometric,
    extraPenalty,
    match: Math.max(0, Math.min(1, geometric - extraPenalty)),
    rebuilt: pairs.filter((p) => (p.score?.total ?? 0) >= REBUILT).length,
  };
}

/** Canvas-relative description of a position (for replay captions). */
export function where(cx: number, cy: number): string {
  const col = cx < CANVAS / 3 ? 'left' : cx > (2 * CANVAS) / 3 ? 'right' : 'centre';
  const row = cy < CANVAS / 3 ? 'top' : cy > (2 * CANVAS) / 3 ? 'bottom' : 'middle';
  return row === 'middle' && col === 'centre' ? 'centre' : `${row}-${col}`;
}
