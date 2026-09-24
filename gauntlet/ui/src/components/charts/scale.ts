/** Scales, ticks and text measurement for the hand-made SVG charts. */

/**
 * Categorical palette for series that have no colour of their own (replay
 * series, suggested contestant colours). Validated with the dataviz
 * validator against the app's dark (#0c1118) and light (#ffffff) surfaces:
 * first 6 slots pass every adjacent gate; first 3 pass all-pairs.
 */
export const SERIES_PALETTE = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767'];

/** De-emphasis / "other" colour. */
export const NEUTRAL = '#7f8b9d';

export type Scale = ((v: number) => number) & { domain: [number, number]; range: [number, number] };

export function linearScale(domain: [number, number], range: [number, number]): Scale {
  const [d0, d1] = domain;
  const [r0, r1] = range;
  const span = d1 - d0 || 1;
  const f = ((v: number) => r0 + ((v - d0) / span) * (r1 - r0)) as Scale;
  f.domain = domain;
  f.range = range;
  return f;
}

export function logScale(domain: [number, number], range: [number, number]): Scale {
  const l0 = Math.log10(domain[0]);
  const l1 = Math.log10(domain[1]);
  const span = l1 - l0 || 1;
  const [r0, r1] = range;
  const f = ((v: number) => r0 + ((Math.log10(Math.max(v, 1e-12)) - l0) / span) * (r1 - r0)) as Scale;
  f.domain = domain;
  f.range = range;
  return f;
}

function niceNum(range: number, round: boolean): number {
  const exp = Math.floor(Math.log10(range));
  const f = range / 10 ** exp;
  let nf: number;
  if (round) nf = f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10;
  else nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
  return nf * 10 ** exp;
}

/** "Nice" linear ticks covering [min, max]. */
export function niceTicks(min: number, max: number, count = 5): { ticks: number[]; min: number; max: number; step: number } {
  if (!Number.isFinite(min) || !Number.isFinite(max)) return { ticks: [0, 1], min: 0, max: 1, step: 1 };
  if (min === max) {
    const pad = Math.abs(min) * 0.1 || 1;
    min -= pad;
    max += pad;
  }
  const range = niceNum(max - min, false);
  const step = niceNum(range / Math.max(1, count - 1), true);
  const nMin = Math.floor(min / step) * step;
  const nMax = Math.ceil(max / step) * step;
  const ticks: number[] = [];
  for (let v = nMin; v <= nMax + step / 2; v += step) ticks.push(+v.toFixed(10));
  return { ticks, min: nMin, max: nMax, step };
}

/** Ticks for a log axis: powers of ten, plus 2× and 5× when the span is short. */
export function logTicks(min: number, max: number): number[] {
  const e0 = Math.floor(Math.log10(min));
  const e1 = Math.ceil(Math.log10(max));
  const decades = e1 - e0;
  const mults = decades <= 1 ? [1, 2, 5] : decades <= 3 ? [1, 3] : [1];
  const ticks: number[] = [];
  for (let e = e0; e <= e1; e++) {
    for (const m of mults) {
      const v = m * 10 ** e;
      if (v >= min * 0.999 && v <= max * 1.001) ticks.push(v);
    }
  }
  return ticks;
}

let ctx: CanvasRenderingContext2D | null = null;
const cache = new Map<string, number>();
let family: string | null = null;

/** Measure rendered text width in px (canvas-backed, cached). */
export function measureText(text: string, sizePx: number, weight = 500): number {
  const key = `${weight}|${sizePx}|${text}`;
  const hit = cache.get(key);
  if (hit !== undefined) return hit;
  try {
    if (!ctx) ctx = document.createElement('canvas').getContext('2d');
    if (!family) family = getComputedStyle(document.body).fontFamily || 'sans-serif';
    if (ctx) {
      ctx.font = `${weight} ${sizePx}px ${family}`;
      const w = ctx.measureText(text).width;
      if (cache.size > 4000) cache.clear();
      cache.set(key, w);
      return w;
    }
  } catch {
    /* fall through */
  }
  return text.length * sizePx * 0.58;
}

export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}

export function overlap(a: Box, b: Box): number {
  const x = Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x));
  const y = Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));
  return x * y;
}

export const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

/** Truncate a label to fit a pixel width. */
export function fitText(text: string, maxW: number, sizePx: number, weight = 500): string {
  if (measureText(text, sizePx, weight) <= maxW) return text;
  let lo = 0;
  let hi = text.length;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    if (measureText(text.slice(0, mid) + '…', sizePx, weight) <= maxW) lo = mid;
    else hi = mid - 1;
  }
  return lo <= 0 ? '' : text.slice(0, lo) + '…';
}
