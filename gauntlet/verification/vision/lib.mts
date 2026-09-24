// Shared helpers for the vision test generators: SVG building blocks, a jittered single-stroke "handwriting"
// font, and PNG rendering through the headless Chromium that Gauntlet already uses for browser checks.
import type { Rng } from '../../src/core/types.ts';

export type Pt = [number, number];

export const FONT = "'DejaVu Sans', 'Liberation Sans', Arial, sans-serif";

export function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

export function r1(n: number): string {
  return (Math.round(n * 10) / 10).toString();
}

export function text(x: number, y: number, s: string, opts: { size?: number; anchor?: 'start' | 'middle' | 'end'; weight?: number | string; fill?: string; rotate?: number; baseline?: string } = {}): string {
  const rot = opts.rotate ? ` transform="rotate(${opts.rotate} ${r1(x)} ${r1(y)})"` : '';
  return `<text x="${r1(x)}" y="${r1(y)}" font-family="${FONT}" font-size="${opts.size ?? 16}" text-anchor="${opts.anchor ?? 'start'}" font-weight="${opts.weight ?? 400}" fill="${opts.fill ?? '#1f2937'}"${opts.baseline ? ` dominant-baseline="${opts.baseline}"` : ''}${rot}>${esc(s)}</text>`;
}

export function svgDoc(width: number, height: number, body: string, background = '#ffffff'): string {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><rect width="${width}" height="${height}" fill="${background}"/>${body}</svg>`;
}

// ─────────────────────────────────────────────────────────────────────────────
// Shapes (spot the difference, count & locate)
// ─────────────────────────────────────────────────────────────────────────────

export type ShapeKind = 'circle' | 'square' | 'triangle' | 'star' | 'hexagon' | 'cross' | 'heart';
export const SHAPES: ShapeKind[] = ['circle', 'square', 'triangle', 'star', 'hexagon', 'cross', 'heart'];

export const COLORS: Record<string, string> = {
  red: '#E53935',
  blue: '#1E6FD9',
  green: '#2E9D44',
  yellow: '#F4C20D',
  purple: '#8E24AA',
  orange: '#F57C00',
  black: '#222222',
};

function poly(points: Pt[]): string {
  return points.map(([x, y]) => `${r1(x)},${r1(y)}`).join(' ');
}

/** SVG for one shape centred at (cx, cy) with "radius" r. `rotation` in degrees; `dot` adds a white centre dot. */
export function shape(kind: ShapeKind, cx: number, cy: number, r: number, fill: string, opts: { rotation?: number; dot?: boolean; stroke?: string } = {}): string {
  const stroke = opts.stroke ?? 'rgba(0,0,0,0.55)';
  const common = `fill="${fill}" stroke="${stroke}" stroke-width="2" stroke-linejoin="round"`;
  let el: string;
  switch (kind) {
    case 'circle':
      el = `<circle cx="${r1(cx)}" cy="${r1(cy)}" r="${r1(r * 0.92)}" ${common}/>`;
      break;
    case 'square':
      el = `<rect x="${r1(cx - r * 0.8)}" y="${r1(cy - r * 0.8)}" width="${r1(r * 1.6)}" height="${r1(r * 1.6)}" ${common}/>`;
      break;
    case 'triangle': {
      const pts: Pt[] = [0, 120, 240].map((a) => [cx + r * Math.sin((a * Math.PI) / 180), cy - r * Math.cos((a * Math.PI) / 180) + r * 0.12]);
      el = `<polygon points="${poly(pts)}" ${common}/>`;
      break;
    }
    case 'star': {
      const pts: Pt[] = [];
      for (let i = 0; i < 10; i++) {
        const rr = i % 2 ? r * 0.45 : r;
        const a = (i * 36 * Math.PI) / 180;
        pts.push([cx + rr * Math.sin(a), cy - rr * Math.cos(a) + r * 0.05]);
      }
      el = `<polygon points="${poly(pts)}" ${common}/>`;
      break;
    }
    case 'hexagon': {
      const pts: Pt[] = [0, 60, 120, 180, 240, 300].map((a) => [cx + r * 0.95 * Math.cos((a * Math.PI) / 180), cy + r * 0.95 * Math.sin((a * Math.PI) / 180)]);
      el = `<polygon points="${poly(pts)}" ${common}/>`;
      break;
    }
    case 'cross': {
      const a = r * 0.32;
      const b = r * 0.9;
      const pts: Pt[] = [[-a, -b], [a, -b], [a, -a], [b, -a], [b, a], [a, a], [a, b], [-a, b], [-a, a], [-b, a], [-b, -a], [-a, -a]].map(([x, y]) => [cx + x, cy + y]);
      el = `<polygon points="${poly(pts)}" ${common}/>`;
      break;
    }
    case 'heart': {
      const s = r / 16;
      const d = `M ${r1(cx)} ${r1(cy + 14 * s)} C ${r1(cx - 22 * s)} ${r1(cy)} ${r1(cx - 16 * s)} ${r1(cy - 18 * s)} ${r1(cx)} ${r1(cy - 8 * s)} C ${r1(cx + 16 * s)} ${r1(cy - 18 * s)} ${r1(cx + 22 * s)} ${r1(cy)} ${r1(cx)} ${r1(cy + 14 * s)} Z`;
      el = `<path d="${d}" ${common}/>`;
      break;
    }
  }
  if (opts.rotation) el = `<g transform="rotate(${r1(opts.rotation)} ${r1(cx)} ${r1(cy)})">${el}</g>`;
  if (opts.dot) el += `<circle cx="${r1(cx)}" cy="${r1(cy + (kind === 'triangle' ? r * 0.18 : 0))}" r="${r1(r * 0.2)}" fill="#ffffff" stroke="rgba(0,0,0,0.55)" stroke-width="1.5"/>`;
  return el;
}

// ─────────────────────────────────────────────────────────────────────────────
// Handwriting: a single-stroke block font, jittered per glyph and per point
// ─────────────────────────────────────────────────────────────────────────────

/** Points on an elliptical arc; angles in degrees, 0 = right, 90 = down (SVG coordinates). */
export function arc(cx: number, cy: number, rx: number, ry: number, a0: number, a1: number, n = 14): Pt[] {
  const out: Pt[] = [];
  for (let i = 0; i <= n; i++) {
    const a = ((a0 + ((a1 - a0) * i) / n) * Math.PI) / 180;
    out.push([cx + rx * Math.cos(a), cy + ry * Math.sin(a)]);
  }
  return out;
}

const dot = (x: number, y: number): Pt[] => arc(x, y, 0.035, 0.035, 0, 360, 8);

interface Glyph {
  w: number;
  strokes: Pt[][];
}

/** Glyph box: x from 0 to w, y from 0 (cap height) to 1 (baseline). */
export const GLYPHS: Record<string, Glyph> = {
  '0': { w: 0.56, strokes: [arc(0.28, 0.5, 0.26, 0.5, -90, 270, 20)] },
  '1': { w: 0.5, strokes: [[[0.08, 0.22], [0.3, 0], [0.3, 1]], [[0.06, 1], [0.5, 1]]] },
  '2': { w: 0.6, strokes: [[...arc(0.29, 0.27, 0.26, 0.26, 195, 380), [0.04, 1], [0.6, 1]]] },
  '3': { w: 0.58, strokes: [[...arc(0.28, 0.25, 0.24, 0.24, 200, 450), ...arc(0.28, 0.74, 0.27, 0.26, -90, 155)]] },
  '4': { w: 0.62, strokes: [[[0.12, 0], [0.04, 0.64], [0.62, 0.64]], [[0.46, 0.28], [0.46, 1]]] },
  '5': { w: 0.58, strokes: [[[0.54, 0], [0.12, 0], [0.08, 0.45], ...arc(0.29, 0.7, 0.27, 0.3, -125, 150)]] },
  '6': { w: 0.58, strokes: [[[0.5, 0.02], [0.3, 0.16], [0.13, 0.42], [0.05, 0.7], ...arc(0.3, 0.73, 0.25, 0.27, 180, 535)]] },
  '7': { w: 0.6, strokes: [[[0.02, 0], [0.6, 0], [0.24, 1]]] },
  '8': { w: 0.56, strokes: [arc(0.28, 0.25, 0.21, 0.24, -90, 270), arc(0.28, 0.74, 0.27, 0.26, -90, 270)] },
  '9': { w: 0.58, strokes: [arc(0.29, 0.28, 0.25, 0.27, 0, 360, 18), [[0.54, 0.26], [0.5, 1]]] },
  '+': { w: 0.6, strokes: [[[0.3, 0.32], [0.3, 0.88]], [[0.02, 0.6], [0.58, 0.6]]] },
  '-': { w: 0.56, strokes: [[[0.02, 0.6], [0.54, 0.6]]] },
  '*': { w: 0.52, strokes: [[[0.04, 0.4], [0.48, 0.84]], [[0.48, 0.4], [0.04, 0.84]]] },
  '/': { w: 0.56, strokes: [[[0.02, 0.6], [0.54, 0.6]], dot(0.28, 0.38), dot(0.28, 0.82)] },
  '=': { w: 0.56, strokes: [[[0.02, 0.5], [0.54, 0.5]], [[0.02, 0.72], [0.54, 0.72]]] },
  '(': { w: 0.3, strokes: [arc(0.36, 0.5, 0.3, 0.56, 125, 235)] },
  ')': { w: 0.3, strokes: [arc(-0.06, 0.5, 0.3, 0.56, -55, 55)] },
  '?': { w: 0.54, strokes: [[...arc(0.27, 0.25, 0.23, 0.23, 190, 425), [0.27, 0.56], [0.27, 0.72]], dot(0.27, 0.95)] },
  x: { w: 0.5, strokes: [[[0.04, 0.5], [0.16, 0.56], [0.25, 0.75], [0.34, 0.94], [0.48, 1]], [[0.44, 0.5], [0.06, 1]]] },
  '.': { w: 0.14, strokes: [dot(0.06, 0.96)] },
  ',': { w: 0.14, strokes: [[[0.08, 0.9], [0.02, 1.12]]] },
  ':': { w: 0.14, strokes: [dot(0.06, 0.45), dot(0.06, 0.95)] },
  // Variants: European 1 (long flag, no foot), crossed 7, closed 4.
  '1e': { w: 0.44, strokes: [[[0.02, 0.4], [0.36, 0], [0.3, 1]]] },
  '7e': { w: 0.6, strokes: [[[0.02, 0.02], [0.6, 0], [0.24, 1]], [[0.16, 0.5], [0.56, 0.5]]] },
  '4c': { w: 0.62, strokes: [[[0.44, 1], [0.44, 0], [0.02, 0.66], [0.62, 0.66]]] },
  a: { w: 0.52, strokes: [arc(0.26, 0.76, 0.21, 0.24, -10, 350, 14), [[0.48, 0.52], [0.48, 1]]] },
  n: { w: 0.5, strokes: [[[0.06, 0.5], [0.06, 1]], [[0.06, 0.68], ...arc(0.27, 0.72, 0.21, 0.2, 180, 360, 8), [0.48, 1]]] },
  A: { w: 0.62, strokes: [[[0, 1], [0.31, 0], [0.62, 1]], [[0.13, 0.62], [0.49, 0.62]]] },
  B: { w: 0.58, strokes: [[[0.05, 1], [0.05, 0]], [[0.05, 0], [0.32, 0], ...arc(0.32, 0.24, 0.2, 0.24, -90, 90, 8), [0.05, 0.48]], [[0.05, 0.48], [0.34, 0.48], ...arc(0.34, 0.74, 0.22, 0.26, -90, 90, 8), [0.05, 1]]] },
  C: { w: 0.6, strokes: [arc(0.33, 0.5, 0.31, 0.5, 320, 40, 16)] },
  D: { w: 0.6, strokes: [[[0.05, 0], [0.05, 1]], [[0.05, 0], [0.22, 0], ...arc(0.22, 0.5, 0.35, 0.5, -90, 90, 12), [0.05, 1]]] },
  E: { w: 0.54, strokes: [[[0.54, 0], [0.05, 0], [0.05, 1], [0.54, 1]], [[0.05, 0.5], [0.44, 0.5]]] },
  F: { w: 0.52, strokes: [[[0.52, 0], [0.05, 0], [0.05, 1]], [[0.05, 0.5], [0.42, 0.5]]] },
  G: { w: 0.64, strokes: [arc(0.33, 0.5, 0.31, 0.5, 320, 40, 16), [[0.36, 0.58], [0.64, 0.58], [0.64, 0.92]]] },
  H: { w: 0.6, strokes: [[[0.05, 0], [0.05, 1]], [[0.55, 0], [0.55, 1]], [[0.05, 0.5], [0.55, 0.5]]] },
  I: { w: 0.3, strokes: [[[0, 0], [0.3, 0]], [[0.15, 0], [0.15, 1]], [[0, 1], [0.3, 1]]] },
  J: { w: 0.56, strokes: [[[0.54, 0], [0.54, 0.7], ...arc(0.3, 0.7, 0.24, 0.3, 0, 165, 10)]] },
  K: { w: 0.58, strokes: [[[0.05, 0], [0.05, 1]], [[0.56, 0], [0.05, 0.56]], [[0.2, 0.42], [0.58, 1]]] },
  L: { w: 0.5, strokes: [[[0.05, 0], [0.05, 1], [0.5, 1]]] },
  M: { w: 0.72, strokes: [[[0.02, 1], [0.08, 0], [0.36, 0.62], [0.64, 0], [0.7, 1]]] },
  N: { w: 0.6, strokes: [[[0.05, 1], [0.05, 0], [0.55, 1], [0.55, 0]]] },
  O: { w: 0.68, strokes: [arc(0.34, 0.5, 0.33, 0.5, -90, 270, 20)] },
  P: { w: 0.56, strokes: [[[0.05, 1], [0.05, 0], [0.3, 0], ...arc(0.3, 0.25, 0.24, 0.25, -90, 90, 8), [0.05, 0.5]]] },
  Q: { w: 0.68, strokes: [arc(0.34, 0.5, 0.33, 0.5, -90, 270, 20), [[0.4, 0.72], [0.7, 1.06]]] },
  R: { w: 0.58, strokes: [[[0.05, 1], [0.05, 0], [0.3, 0], ...arc(0.3, 0.25, 0.24, 0.25, -90, 90, 8), [0.05, 0.5]], [[0.28, 0.5], [0.58, 1]]] },
  S: { w: 0.58, strokes: [[...arc(0.3, 0.26, 0.25, 0.25, 330, 90, 12), ...arc(0.3, 0.75, 0.27, 0.25, -90, 150, 12)]] },
  T: { w: 0.6, strokes: [[[0, 0], [0.6, 0]], [[0.3, 0], [0.3, 1]]] },
  U: { w: 0.6, strokes: [[[0.05, 0], [0.05, 0.68], ...arc(0.3, 0.68, 0.25, 0.32, 180, 0, 12), [0.55, 0]]] },
  V: { w: 0.62, strokes: [[[0, 0], [0.31, 1], [0.62, 0]]] },
  W: { w: 0.8, strokes: [[[0, 0], [0.2, 1], [0.4, 0.35], [0.6, 1], [0.8, 0]]] },
  X: { w: 0.58, strokes: [[[0, 0], [0.58, 1]], [[0.58, 0], [0, 1]]] },
  Y: { w: 0.6, strokes: [[[0, 0], [0.3, 0.5], [0.6, 0]], [[0.3, 0.5], [0.3, 1]]] },
  Z: { w: 0.6, strokes: [[[0.02, 0], [0.58, 0], [0.02, 1], [0.6, 1]]] },
};

/** Smooth polyline through the points (Catmull-Rom converted to cubic Béziers). */
export function smoothPath(pts: Pt[]): string {
  if (pts.length === 1) return `M ${r1(pts[0]![0])} ${r1(pts[0]![1])} l 0.1 0.1`;
  let d = `M ${r1(pts[0]![0])} ${r1(pts[0]![1])}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[Math.max(0, i - 1)]!;
    const p1 = pts[i]!;
    const p2 = pts[i + 1]!;
    const p3 = pts[Math.min(pts.length - 1, i + 2)]!;
    const c1: Pt = [p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6];
    const c2: Pt = [p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6];
    d += ` C ${r1(c1[0])} ${r1(c1[1])} ${r1(c2[0])} ${r1(c2[1])} ${r1(p2[0])} ${r1(p2[1])}`;
  }
  return d;
}

export interface Hand {
  rng: Rng;
  /** 0 = neat, 1 = messy. */
  mess: number;
  ink: string;
  strokeWidth: number;
  /** Per-writer glyph choices, e.g. { '1': '1e', '7': '7e' } for a European 1 and a crossed 7. */
  variants?: Record<string, string>;
  /** Gap between glyphs in cap heights (default 0.2; below 0 letters touch or overlap). */
  gap?: number;
  /** Forward slant (default 0.12). */
  slant?: number;
}

/** Split long straight segments so per-point wobble bends them a little, like a real pen line. */
function densify(stroke: Pt[]): Pt[] {
  const out: Pt[] = [stroke[0]!];
  for (let i = 1; i < stroke.length; i++) {
    const [ax, ay] = stroke[i - 1]!;
    const [bx, by] = stroke[i]!;
    const n = Math.max(1, Math.ceil(Math.hypot(bx - ax, by - ay) / 0.25));
    for (let k = 1; k <= n; k++) out.push([ax + ((bx - ax) * k) / n, ay + ((by - ay) * k) / n]);
  }
  return out;
}

/** Draw one glyph with its box's top-left at (x, y) and cap height `size`. Returns [svg, advance]. */
export function glyph(h: Hand, ch: string, x: number, y: number, size: number): [string, number] {
  if (ch === ' ') return ['', size * 0.42];
  const g = GLYPHS[h.variants?.[ch] ?? ch];
  if (!g) throw new Error(`No handwriting glyph for "${ch}"`);
  const m = h.mess;
  const rot = ((h.rng.next() * 2 - 1) * (4 + 6 * m) * Math.PI) / 180;
  const sc = size * (1 + (h.rng.next() * 2 - 1) * (0.04 + 0.08 * m));
  const dx = (h.rng.next() * 2 - 1) * size * 0.03 * (1 + m);
  const dy = (h.rng.next() * 2 - 1) * size * (0.03 + 0.05 * m);
  const slant = (h.slant ?? 0.12) + (h.rng.next() * 2 - 1) * 0.05;
  const cxg = g.w / 2;
  // Smooth wobble: a low-frequency displacement field (neighbouring points move together, so lines bend instead of
  // kinking), plus a tiny independent tremor.
  const amp = 0.028 * (1 + m);
  const [f1, f2, f3, f4] = [4 + h.rng.next() * 3, 4 + h.rng.next() * 3, 4 + h.rng.next() * 3, 4 + h.rng.next() * 3];
  const [p1, p2, p3, p4] = [h.rng.next() * 7, h.rng.next() * 7, h.rng.next() * 7, h.rng.next() * 7];
  const paths: string[] = [];
  for (const stroke of g.strokes) {
    const pts = densify(stroke).map(([px, py]): Pt => {
      const jx = px + amp * (Math.sin(f1 * py + p1) + Math.sin(f2 * px + p2)) / 2 + (h.rng.next() * 2 - 1) * 0.004;
      const jy = py + amp * (Math.sin(f3 * px + p3) + Math.sin(f4 * py + p4)) / 2 + (h.rng.next() * 2 - 1) * 0.004;
      const sx = jx + (1 - jy) * slant;
      const rx = (sx - cxg) * Math.cos(rot) - (jy - 0.5) * Math.sin(rot) + cxg;
      const ry = (sx - cxg) * Math.sin(rot) + (jy - 0.5) * Math.cos(rot) + 0.5;
      return [x + dx + rx * sc, y + dy + ry * sc];
    });
    const width = h.strokeWidth * (0.9 + h.rng.next() * 0.25);
    paths.push(`<path d="${smoothPath(pts)}" fill="none" stroke="${h.ink}" stroke-width="${r1(width)}" stroke-linecap="round" stroke-linejoin="round"/>`);
  }
  return [paths.join(''), (g.w + (h.gap ?? 0.2) + (h.rng.next() * 2 - 1) * 0.04 * (1 + m)) * size];
}

/** Handwrite a string left-to-right; returns [svg, width]. Use "*" for ×, "/" for ÷, "-" for minus. */
export function handwrite(h: Hand, s: string, x: number, y: number, size: number): [string, number] {
  let out = '';
  let cx = x;
  for (const ch of s) {
    const [svg, adv] = glyph(h, ch, cx, y + Math.sin(cx / 90) * size * 0.04 * h.mess, size);
    out += svg;
    cx += adv;
  }
  return [out, cx - x];
}

/** A hand-drawn straight line (fraction bar, underline, radical roof). */
export function handLine(h: Hand, x1: number, y1: number, x2: number, y2: number): string {
  const n = Math.max(2, Math.ceil(Math.hypot(x2 - x1, y2 - y1) / 40));
  const pts: Pt[] = [];
  for (let i = 0; i <= n; i++) pts.push([x1 + ((x2 - x1) * i) / n + (h.rng.next() * 2 - 1) * 1.5, y1 + ((y2 - y1) * i) / n + (h.rng.next() * 2 - 1) * 2 * (1 + h.mess)]);
  return `<path d="${smoothPath(pts)}" fill="none" stroke="${h.ink}" stroke-width="${r1(h.strokeWidth)}" stroke-linecap="round"/>`;
}

/** Lined notebook paper background with a red margin. */
export function paper(width: number, height: number, lineGap = 64, top = 90): string {
  let s = `<rect width="${width}" height="${height}" fill="#fdfcf7"/>`;
  for (let y = top; y < height - 10; y += lineGap) s += `<line x1="0" y1="${y}" x2="${width}" y2="${y}" stroke="#b9d3ee" stroke-width="2"/>`;
  s += `<line x1="90" y1="0" x2="90" y2="${height}" stroke="#f2a1a1" stroke-width="2.5"/>`;
  return s;
}

// ─────────────────────────────────────────────────────────────────────────────
// Rendering
// ─────────────────────────────────────────────────────────────────────────────

export interface ImageSpec {
  file: string;
  svg: string;
  width: number;
  height: number;
}

/** Render SVGs to PNG files with headless Chromium (device scale 1, no animation). */
export async function renderAll(images: ImageSpec[], outDir: string): Promise<void> {
  const { getBrowser } = await import('../../src/scoring/browser.ts');
  const { writeFileSync, mkdirSync } = await import('node:fs');
  const { join } = await import('node:path');
  const browser = await getBrowser();
  if (!browser) throw new Error('Headless Chromium is not available (install playwright-core and a Chromium build)');
  mkdirSync(outDir, { recursive: true });
  const context = await browser.newContext({ deviceScaleFactor: 1 });
  const page = await context.newPage();
  for (const img of images) {
    await page.setViewportSize({ width: img.width, height: img.height });
    await page.setContent(`<!doctype html><html><head><style>html,body{margin:0;padding:0;background:#fff}svg{display:block}</style></head><body>${img.svg}</body></html>`);
    const png = await page.screenshot({ type: 'png', clip: { x: 0, y: 0, width: img.width, height: img.height } });
    writeFileSync(join(outDir, img.file), png);
  }
  await context.close();
  await browser.close();
}

/** Cross out a region with two pen strokes (a strike-through a price, a word or a whole line). */
export function crossOut(h: Hand, x1: number, y1: number, x2: number, y2: number): string {
  const mid = (y1 + y2) / 2;
  return handLine(h, x1 - 4, mid + 4, x2 + 4, mid - 4) + handLine(h, x1 - 2, mid - 6, x2 + 6, mid + 3);
}
