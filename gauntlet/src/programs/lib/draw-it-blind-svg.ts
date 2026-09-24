/**
 * Draw It Blind — a small, dependency-free SVG reader.
 *
 * Understands what models actually emit: basic shapes, paths (all commands,
 * including arcs), attributes in any order, presentation attributes, inline
 * style="", simple <style> rules (tag / .class / #id), inherited group
 * styles, gradients (first stop colour), every transform function, nested
 * groups, <defs> (not rendered) and viewBox scaling. Each visible shape is
 * reduced to a kind (circle, square, triangle, star, ...), a colour and a
 * bounding box in 400×400 canvas coordinates. It never throws.
 */
import { CANVAS, EXTENDED_PALETTE, PALETTE } from './draw-it-blind-scene.ts';
import type { PaletteId, PaletteName } from './draw-it-blind-scene.ts';

export type RGB = [number, number, number];
export type Point = [number, number];
/** SVG affine matrix [a, b, c, d, e, f]: x' = a·x + c·y + e, y' = b·x + d·y + f. */
export type Matrix = [number, number, number, number, number, number];

export type DrawnKind = 'circle' | 'ellipse' | 'square' | 'rectangle' | 'quad' | 'triangle' | 'star' | 'polygon';

export interface DrawnShape {
  kind: DrawnKind;
  tag: string;
  rgb: RGB;
  color: PaletteName;
  cx: number;
  cy: number;
  w: number;
  h: number;
  /** Triangles only: apex direction in degrees (SVG axes), when the triangle is clearly isosceles. */
  angle?: number;
}

export interface ParseOptions {
  /** Which palette drawn colours are mapped onto (default 'basic'). */
  palette?: PaletteId;
}

export interface ParsedSvg {
  /** True when an <svg> element was found. */
  found: boolean;
  /** Shape elements encountered (visible or not). */
  elements: number;
  shapes: DrawnShape[];
  ignored: Array<{ tag: string; reason: string }>;
}

// ─────────────────────────────────────────────────────────────────────────────
// Colours
// ─────────────────────────────────────────────────────────────────────────────

const NAMED_SRC =
  'aliceblue:f0f8ff antiquewhite:faebd7 aqua:00ffff aquamarine:7fffd4 azure:f0ffff beige:f5f5dc bisque:ffe4c4 black:000000 ' +
  'blanchedalmond:ffebcd blue:0000ff blueviolet:8a2be2 brown:a52a2a burlywood:deb887 cadetblue:5f9ea0 chartreuse:7fff00 ' +
  'chocolate:d2691e coral:ff7f50 cornflowerblue:6495ed cornsilk:fff8dc crimson:dc143c cyan:00ffff darkblue:00008b ' +
  'darkcyan:008b8b darkgoldenrod:b8860b darkgray:a9a9a9 darkgreen:006400 darkgrey:a9a9a9 darkkhaki:bdb76b darkmagenta:8b008b ' +
  'darkolivegreen:556b2f darkorange:ff8c00 darkorchid:9932cc darkred:8b0000 darksalmon:e9967a darkseagreen:8fbc8f ' +
  'darkslateblue:483d8b darkslategray:2f4f4f darkslategrey:2f4f4f darkturquoise:00ced1 darkviolet:9400d3 deeppink:ff1493 ' +
  'deepskyblue:00bfff dimgray:696969 dimgrey:696969 dodgerblue:1e90ff firebrick:b22222 floralwhite:fffaf0 forestgreen:228b22 ' +
  'fuchsia:ff00ff gainsboro:dcdcdc ghostwhite:f8f8ff gold:ffd700 goldenrod:daa520 gray:808080 green:008000 greenyellow:adff2f ' +
  'grey:808080 honeydew:f0fff0 hotpink:ff69b4 indianred:cd5c5c indigo:4b0082 ivory:fffff0 khaki:f0e68c lavender:e6e6fa ' +
  'lavenderblush:fff0f5 lawngreen:7cfc00 lemonchiffon:fffacd lightblue:add8e6 lightcoral:f08080 lightcyan:e0ffff ' +
  'lightgoldenrodyellow:fafad2 lightgray:d3d3d3 lightgreen:90ee90 lightgrey:d3d3d3 lightpink:ffb6c1 lightsalmon:ffa07a ' +
  'lightseagreen:20b2aa lightskyblue:87cefa lightslategray:778899 lightslategrey:778899 lightsteelblue:b0c4de ' +
  'lightyellow:ffffe0 lime:00ff00 limegreen:32cd32 linen:faf0e6 magenta:ff00ff maroon:800000 mediumaquamarine:66cdaa ' +
  'mediumblue:0000cd mediumorchid:ba55d3 mediumpurple:9370db mediumseagreen:3cb371 mediumslateblue:7b68ee ' +
  'mediumspringgreen:00fa9a mediumturquoise:48d1cc mediumvioletred:c71585 midnightblue:191970 mintcream:f5fffa ' +
  'mistyrose:ffe4e1 moccasin:ffe4b5 navajowhite:ffdead navy:000080 oldlace:fdf5e6 olive:808000 olivedrab:6b8e23 ' +
  'orange:ffa500 orangered:ff4500 orchid:da70d6 palegoldenrod:eee8aa palegreen:98fb98 paleturquoise:afeeee ' +
  'palevioletred:db7093 papayawhip:ffefd5 peachpuff:ffdab9 peru:cd853f pink:ffc0cb plum:dda0dd powderblue:b0e0e6 ' +
  'purple:800080 rebeccapurple:663399 red:ff0000 rosybrown:bc8f8f royalblue:4169e1 saddlebrown:8b4513 salmon:fa8072 ' +
  'sandybrown:f4a460 seagreen:2e8b57 seashell:fff5ee sienna:a0522d silver:c0c0c0 skyblue:87ceeb slateblue:6a5acd ' +
  'slategray:708090 slategrey:708090 snow:fffafa springgreen:00ff7f steelblue:4682b4 tan:d2b48c teal:008080 thistle:d8bfd8 ' +
  'tomato:ff6347 turquoise:40e0d0 violet:ee82ee wheat:f5deb3 white:ffffff whitesmoke:f5f5f5 yellow:ffff00 yellowgreen:9acd32';
const NAMED = new Map(NAMED_SRC.split(' ').map((kv) => kv.split(':') as [string, string]));

export function hexToRgb(hex: string): RGB | null {
  let h = hex.replace('#', '').trim();
  if (h.length === 3 || h.length === 4) h = h.slice(0, 3).split('').map((c) => c + c).join('');
  else if (h.length === 8) h = h.slice(0, 6);
  if (!/^[0-9a-f]{6}$/i.test(h)) return null;
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}

function hslToRgb(h: number, s: number, l: number): RGB {
  const hh = (((h % 360) + 360) % 360) / 360;
  const f = (n: number) => {
    const k = (n + hh * 12) % 12;
    const a = s * Math.min(l, 1 - l);
    return Math.round(255 * (l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))));
  };
  return [f(0), f(8), f(4)];
}

/** Parses any CSS colour. Returns null for none/transparent/unknown; alpha is separate. */
export function parseColor(value: string | undefined, currentColor?: string): { rgb: RGB; alpha: number } | null {
  if (!value) return null;
  const v = value.trim().toLowerCase().replace(/\s*!important$/, '');
  if (v === 'none' || v === 'transparent' || v === '') return null;
  if (v === 'currentcolor') return currentColor && currentColor.toLowerCase() !== 'currentcolor' ? parseColor(currentColor) : { rgb: [0, 0, 0], alpha: 1 };
  if (v.startsWith('#')) {
    const rgb = hexToRgb(v);
    if (!rgb) return null;
    const h = v.slice(1);
    const alpha = h.length === 8 ? parseInt(h.slice(6, 8), 16) / 255 : h.length === 4 ? parseInt(h[3]! + h[3]!, 16) / 255 : 1;
    return { rgb, alpha };
  }
  const fn = v.match(/^(rgba?|hsla?)\(([^)]*)\)$/);
  if (fn) {
    const parts = fn[2]!.split(/[\s,/]+/).filter(Boolean);
    const num = (p: string | undefined, scale: number) => (p === undefined ? NaN : p.endsWith('%') ? (parseFloat(p) / 100) * scale : parseFloat(p));
    const alphaRaw = parts[3];
    const alpha = alphaRaw === undefined ? 1 : alphaRaw.endsWith('%') ? parseFloat(alphaRaw) / 100 : parseFloat(alphaRaw);
    if (fn[1]!.startsWith('rgb')) {
      const rgb = [num(parts[0], 255), num(parts[1], 255), num(parts[2], 255)].map((x) => Math.max(0, Math.min(255, Math.round(x))));
      if (rgb.some((x) => Number.isNaN(x))) return null;
      return { rgb: rgb as RGB, alpha: Number.isNaN(alpha) ? 1 : alpha };
    }
    const hue = parseFloat(parts[0] ?? '');
    const sat = num(parts[1], 1);
    const light = num(parts[2], 1);
    if ([hue, sat, light].some((x) => Number.isNaN(x))) return null;
    return { rgb: hslToRgb(hue, sat, light), alpha: Number.isNaN(alpha) ? 1 : alpha };
  }
  const named = NAMED.get(v);
  return named ? { rgb: hexToRgb(named)!, alpha: 1 } : null;
}

type Lab = [number, number, number];

export function rgbToLab([r, g, b]: RGB): Lab {
  const lin = (c: number) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  const R = lin(r);
  const G = lin(g);
  const B = lin(b);
  const x = (0.4124 * R + 0.3576 * G + 0.1805 * B) / 0.95047;
  const y = 0.2126 * R + 0.7152 * G + 0.0722 * B;
  const z = (0.0193 * R + 0.1192 * G + 0.9505 * B) / 1.08883;
  const f = (t: number) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
  return [116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))];
}

const anchorsOf = (palette: typeof PALETTE) => palette.flatMap((p) => p.anchors.map((hex) => ({ name: p.name, lab: rgbToLab(hexToRgb(hex)!) })));
const ANCHORS: Record<PaletteId, Array<{ name: PaletteName; lab: Lab }>> = { basic: anchorsOf(PALETTE), extended: anchorsOf(EXTENDED_PALETTE) };

/** Name of the palette colour whose nearest anchor is closest (CIE76 ΔE). */
export function nearestPalette(rgb: RGB, palette: PaletteId = 'basic'): PaletteName {
  const lab = rgbToLab(rgb);
  const anchors = ANCHORS[palette];
  let best = anchors[0]!;
  let bestD = Infinity;
  for (const a of anchors) {
    const d = (a.lab[0] - lab[0]) ** 2 + (a.lab[1] - lab[1]) ** 2 + (a.lab[2] - lab[2]) ** 2;
    if (d < bestD) {
      bestD = d;
      best = a;
    }
  }
  return best.name;
}

/** White-ish fills are invisible on the white canvas (backgrounds, erasers). */
export function isNearWhite(rgb: RGB): boolean {
  const [L, a, b] = rgbToLab(rgb);
  return L > 93 && Math.hypot(a, b) < 10;
}

// ─────────────────────────────────────────────────────────────────────────────
// Geometry
// ─────────────────────────────────────────────────────────────────────────────

export const IDENTITY: Matrix = [1, 0, 0, 1, 0, 0];

export function multiply(m1: Matrix, m2: Matrix): Matrix {
  const [a1, b1, c1, d1, e1, f1] = m1;
  const [a2, b2, c2, d2, e2, f2] = m2;
  return [a1 * a2 + c1 * b2, b1 * a2 + d1 * b2, a1 * c2 + c1 * d2, b1 * c2 + d1 * d2, a1 * e2 + c1 * f2 + e1, b1 * e2 + d1 * f2 + f1];
}

export function apply(m: Matrix, [x, y]: Point): Point {
  return [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];
}

function nums(s: string): number[] {
  return (s.match(/[-+]?(?:\d+\.?\d*|\.\d+)(?:e[-+]?\d+)?/gi) ?? []).map(Number);
}

export function parseTransform(value: string | undefined): Matrix {
  let m: Matrix = IDENTITY;
  if (!value) return m;
  for (const t of value.matchAll(/(matrix|translate|scale|rotate|skewx|skewy)\s*\(([^)]*)\)/gi)) {
    const a = nums(t[2]!);
    const deg = (x: number) => (x * Math.PI) / 180;
    let next: Matrix = IDENTITY;
    switch (t[1]!.toLowerCase()) {
      case 'matrix':
        if (a.length >= 6) next = [a[0]!, a[1]!, a[2]!, a[3]!, a[4]!, a[5]!];
        break;
      case 'translate':
        next = [1, 0, 0, 1, a[0] ?? 0, a[1] ?? 0];
        break;
      case 'scale':
        next = [a[0] ?? 1, 0, 0, a[1] ?? a[0] ?? 1, 0, 0];
        break;
      case 'rotate': {
        const r = deg(a[0] ?? 0);
        const rot: Matrix = [Math.cos(r), Math.sin(r), -Math.sin(r), Math.cos(r), 0, 0];
        const cx = a[1] ?? 0;
        const cy = a[2] ?? 0;
        next = multiply(multiply([1, 0, 0, 1, cx, cy], rot), [1, 0, 0, 1, -cx, -cy]);
        break;
      }
      case 'skewx':
        next = [1, 0, Math.tan(deg(a[0] ?? 0)), 1, 0, 0];
        break;
      case 'skewy':
        next = [1, Math.tan(deg(a[0] ?? 0)), 0, 1, 0, 0];
        break;
    }
    m = multiply(m, next);
  }
  return m;
}

function length(v: string | undefined, ref = CANVAS): number {
  if (v === undefined) return 0;
  const n = parseFloat(v);
  if (!Number.isFinite(n)) return 0;
  return v.trim().endsWith('%') ? (n / 100) * ref : n;
}

function ellipsePoints(cx: number, cy: number, rx: number, ry: number, steps = 24): Point[] {
  return Array.from({ length: steps }, (_, i) => {
    const t = (i / steps) * Math.PI * 2;
    return [cx + rx * Math.cos(t), cy + ry * Math.sin(t)] as Point;
  });
}

interface SubPath {
  points: Point[];
  vertices: Point[];
  curved: boolean;
}

/** SVG spec F.6.5: endpoint arc → sampled points. */
function arcPoints(x1: number, y1: number, rxIn: number, ryIn: number, phiDeg: number, fa: number, fs: number, x2: number, y2: number): Point[] {
  let rx = Math.abs(rxIn);
  let ry = Math.abs(ryIn);
  if (rx === 0 || ry === 0 || (x1 === x2 && y1 === y2)) return [[x2, y2]];
  const phi = (phiDeg * Math.PI) / 180;
  const cos = Math.cos(phi);
  const sin = Math.sin(phi);
  const dx = (x1 - x2) / 2;
  const dy = (y1 - y2) / 2;
  const x1p = cos * dx + sin * dy;
  const y1p = -sin * dx + cos * dy;
  const lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
  if (lambda > 1) {
    rx *= Math.sqrt(lambda);
    ry *= Math.sqrt(lambda);
  }
  const num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
  const den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
  const coef = (fa === fs ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
  const cxp = (coef * rx * y1p) / ry;
  const cyp = (-coef * ry * x1p) / rx;
  const cx = cos * cxp - sin * cyp + (x1 + x2) / 2;
  const cy = sin * cxp + cos * cyp + (y1 + y2) / 2;
  const ang = (ux: number, uy: number, vx: number, vy: number) => Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
  const theta1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
  let dtheta = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
  if (!fs && dtheta > 0) dtheta -= 2 * Math.PI;
  if (fs && dtheta < 0) dtheta += 2 * Math.PI;
  const steps = Math.max(4, Math.ceil((Math.abs(dtheta) / (Math.PI * 2)) * 24));
  const out: Point[] = [];
  for (let i = 1; i <= steps; i++) {
    const th = theta1 + (dtheta * i) / steps;
    out.push([cx + rx * cos * Math.cos(th) - ry * sin * Math.sin(th), cy + rx * sin * Math.cos(th) + ry * cos * Math.sin(th)]);
  }
  return out;
}

/** Parses path data into subpaths (sampled outline points + line vertices). */
export function parsePath(d: string): SubPath[] {
  const subpaths: SubPath[] = [];
  let i = 0;
  const numRe = /[\s,]*([-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)/y;
  const flagRe = /[\s,]*([01])/y;
  const readNum = (): number | null => {
    numRe.lastIndex = i;
    const m = numRe.exec(d);
    if (!m) return null;
    i = numRe.lastIndex;
    return Number(m[1]);
  };
  const readFlag = (): number | null => {
    flagRe.lastIndex = i;
    const m = flagRe.exec(d);
    if (!m) return null;
    i = flagRe.lastIndex;
    return Number(m[1]);
  };
  let cur: Point = [0, 0];
  let start: Point = [0, 0];
  let lastCtrl: Point | null = null;
  let lastCmd = '';
  let sp: SubPath | null = null;
  const open = () => {
    if (sp && sp.points.length > 1) subpaths.push(sp);
    sp = { points: [cur], vertices: [cur], curved: false };
  };
  const lineTo = (p: Point) => {
    if (!sp) open();
    sp!.points.push(p);
    sp!.vertices.push(p);
    cur = p;
  };
  const curveTo = (pts: Point[], end: Point) => {
    if (!sp) open();
    sp!.points.push(...pts);
    sp!.vertices.push(end);
    sp!.curved = true;
    cur = end;
  };
  const bez = (p0: Point, c1: Point, c2: Point, p3: Point): Point[] =>
    Array.from({ length: 8 }, (_, k) => {
      const t = (k + 1) / 8;
      const u = 1 - t;
      return [
        u * u * u * p0[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t * t * t * p3[0],
        u * u * u * p0[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t * t * t * p3[1],
      ] as Point;
    });
  const quad = (p0: Point, c: Point, p2: Point): Point[] =>
    Array.from({ length: 8 }, (_, k) => {
      const t = (k + 1) / 8;
      const u = 1 - t;
      return [u * u * p0[0] + 2 * u * t * c[0] + t * t * p2[0], u * u * p0[1] + 2 * u * t * c[1] + t * t * p2[1]] as Point;
    });

  let guard = 0;
  while (i < d.length && guard++ < 100_000) {
    const ch = d[i]!;
    if (/[\s,]/.test(ch)) {
      i++;
      continue;
    }
    let cmd: string;
    if (/[MmLlHhVvCcSsQqTtAaZz]/.test(ch)) {
      cmd = ch;
      i++;
    } else if (lastCmd && /[-+.\d]/.test(ch)) {
      // Implicit repetition; a repeated moveto becomes a lineto.
      cmd = lastCmd === 'M' ? 'L' : lastCmd === 'm' ? 'l' : lastCmd;
    } else {
      i++;
      continue;
    }
    const rel = cmd === cmd.toLowerCase();
    const C = cmd.toUpperCase();
    const ox = rel ? cur[0] : 0;
    const oy = rel ? cur[1] : 0;
    const need = (n: number): number[] | null => {
      const out: number[] = [];
      for (let k = 0; k < n; k++) {
        const v = readNum();
        if (v === null) return null;
        out.push(v);
      }
      return out;
    };
    if (C === 'Z') {
      if (sp) {
        cur = start;
        subpaths.push(sp);
        sp = null;
      }
      lastCmd = cmd;
      lastCtrl = null;
      continue;
    }
    if (C === 'M') {
      const a = need(2);
      if (!a) break;
      cur = [ox + a[0]!, oy + a[1]!];
      start = cur;
      open();
    } else if (C === 'L') {
      const a = need(2);
      if (!a) break;
      lineTo([ox + a[0]!, oy + a[1]!]);
    } else if (C === 'H') {
      const a = need(1);
      if (!a) break;
      lineTo([(rel ? cur[0] : 0) + a[0]!, cur[1]]);
    } else if (C === 'V') {
      const a = need(1);
      if (!a) break;
      lineTo([cur[0], (rel ? cur[1] : 0) + a[0]!]);
    } else if (C === 'C') {
      const a = need(6);
      if (!a) break;
      const c1: Point = [ox + a[0]!, oy + a[1]!];
      const c2: Point = [ox + a[2]!, oy + a[3]!];
      const e: Point = [ox + a[4]!, oy + a[5]!];
      curveTo(bez(cur, c1, c2, e), e);
      lastCtrl = c2;
    } else if (C === 'S') {
      const a = need(4);
      if (!a) break;
      const c1: Point = lastCtrl && /[CcSs]/.test(lastCmd) ? [2 * cur[0] - lastCtrl[0], 2 * cur[1] - lastCtrl[1]] : cur;
      const c2: Point = [ox + a[0]!, oy + a[1]!];
      const e: Point = [ox + a[2]!, oy + a[3]!];
      curveTo(bez(cur, c1, c2, e), e);
      lastCtrl = c2;
    } else if (C === 'Q') {
      const a = need(4);
      if (!a) break;
      const c: Point = [ox + a[0]!, oy + a[1]!];
      const e: Point = [ox + a[2]!, oy + a[3]!];
      curveTo(quad(cur, c, e), e);
      lastCtrl = c;
    } else if (C === 'T') {
      const a = need(2);
      if (!a) break;
      const c: Point = lastCtrl && /[QqTt]/.test(lastCmd) ? [2 * cur[0] - lastCtrl[0], 2 * cur[1] - lastCtrl[1]] : cur;
      const e: Point = [ox + a[0]!, oy + a[1]!];
      curveTo(quad(cur, c, e), e);
      lastCtrl = c;
    } else if (C === 'A') {
      const rx = readNum();
      const ry = readNum();
      const rot = readNum();
      const fa = readFlag();
      const fs = readFlag();
      const x = readNum();
      const y = readNum();
      if ([rx, ry, rot, fa, fs, x, y].some((v) => v === null)) break;
      const e: Point = [ox + x!, oy + y!];
      curveTo(arcPoints(cur[0], cur[1], rx!, ry!, rot!, fa!, fs!, e[0], e[1]), e);
    }
    if (!/[CcSsQqTt]/.test(C)) lastCtrl = null;
    lastCmd = cmd;
  }
  const tail = sp as SubPath | null;
  if (tail && tail.points.length > 1) subpaths.push(tail);
  return subpaths;
}

function dedupe(pts: Point[]): Point[] {
  const out: Point[] = [];
  for (const p of pts) {
    const q = out[out.length - 1];
    if (!q || Math.hypot(p[0] - q[0], p[1] - q[1]) > 0.5) out.push(p);
  }
  while (out.length > 2 && Math.hypot(out[0]![0] - out[out.length - 1]![0], out[0]![1] - out[out.length - 1]![1]) <= 0.5) out.pop();
  return out;
}

/** Drops vertices where the outline continues straight on (turn < ~4°). */
function dropCollinear(pts: Point[]): Point[] {
  let cur = pts;
  let changed = true;
  while (changed && cur.length > 3) {
    changed = false;
    for (let i = 0; i < cur.length; i++) {
      const a = cur[(i - 1 + cur.length) % cur.length]!;
      const b = cur[i]!;
      const c = cur[(i + 1) % cur.length]!;
      const cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
      const dot = (b[0] - a[0]) * (c[0] - b[0]) + (b[1] - a[1]) * (c[1] - b[1]);
      if (Math.abs(Math.atan2(cross, dot)) < 0.07) {
        cur = cur.filter((_, k) => k !== i);
        changed = true;
        break;
      }
    }
  }
  return cur;
}

export function bboxOf(pts: Point[]): { x0: number; y0: number; x1: number; y1: number } {
  let x0 = Infinity;
  let y0 = Infinity;
  let x1 = -Infinity;
  let y1 = -Infinity;
  for (const [x, y] of pts) {
    x0 = Math.min(x0, x);
    y0 = Math.min(y0, y);
    x1 = Math.max(x1, x);
    y1 = Math.max(y1, y);
  }
  return { x0, y0, x1, y1 };
}

function segmentsIntersect(p1: Point, p2: Point, p3: Point, p4: Point): boolean {
  const d = (a: Point, b: Point, c: Point) => (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
  const d1 = d(p3, p4, p1);
  const d2 = d(p3, p4, p2);
  const d3 = d(p1, p2, p3);
  const d4 = d(p1, p2, p4);
  return d1 * d2 < 0 && d3 * d4 < 0;
}

function roundness(pts: Point[]): { cv: number; aspect: number } {
  const b = bboxOf(pts);
  const cx = (b.x0 + b.x1) / 2;
  const cy = (b.y0 + b.y1) / 2;
  const w = b.x1 - b.x0;
  const h = b.y1 - b.y0;
  // Normalise to a unit circle so ellipses look round too; aspect is reported separately.
  const r = pts.map(([x, y]) => Math.hypot((x - cx) / (w / 2 || 1), (y - cy) / (h / 2 || 1)));
  const mean = r.reduce((a, v) => a + v, 0) / r.length;
  const sd = Math.sqrt(r.reduce((a, v) => a + (v - mean) ** 2, 0) / r.length);
  return { cv: mean ? sd / mean : 1, aspect: Math.min(w, h) / (Math.max(w, h) || 1) };
}

/** Classifies a closed outline given its corner vertices. */
export function classifyPolygon(raw: Point[]): DrawnKind | null {
  const deduped = dedupe(raw);
  if (deduped.length < 3) return null;
  const pts = dropCollinear(deduped);
  const n = pts.length;
  const b = bboxOf(pts);
  const aspect = Math.min(b.x1 - b.x0, b.y1 - b.y0) / (Math.max(b.x1 - b.x0, b.y1 - b.y0) || 1);
  if (n === 3) return 'triangle';
  if (n === 4) {
    const axisAligned = pts.every((p, k) => {
      const q = pts[(k + 1) % 4]!;
      const dx = Math.abs(q[0] - p[0]);
      const dy = Math.abs(q[1] - p[1]);
      return dx <= 0.08 * Math.hypot(dx, dy) || dy <= 0.08 * Math.hypot(dx, dy);
    });
    if (!axisAligned) return 'quad';
    return aspect >= 0.85 ? 'square' : 'rectangle';
  }
  if (n === 5) {
    const edges = pts.map((p, k) => [p, pts[(k + 1) % 5]!] as [Point, Point]);
    const crosses = edges.some((e, k) => edges.some((f, m) => Math.abs(k - m) > 1 && Math.abs(k - m) < 4 && segmentsIntersect(e[0], e[1], f[0], f[1])));
    return crosses ? 'star' : 'polygon';
  }
  if (n === 10 || n === 8 || n === 12) {
    const cx = pts.reduce((a, p) => a + p[0], 0) / n;
    const cy = pts.reduce((a, p) => a + p[1], 0) / n;
    const r = pts.map(([x, y]) => Math.hypot(x - cx, y - cy));
    const even = r.filter((_, k) => k % 2 === 0);
    const odd = r.filter((_, k) => k % 2 === 1);
    const mean = (xs: number[]) => xs.reduce((a, v) => a + v, 0) / xs.length;
    const ratio = Math.min(mean(even), mean(odd)) / Math.max(mean(even), mean(odd));
    if (n === 10 && ratio < 0.8) return 'star';
    if (ratio < 0.8) return 'polygon';
  }
  if (n >= 12) {
    const { cv } = roundness(pts);
    if (cv < 0.06) return aspect >= 0.85 ? 'circle' : 'ellipse';
  }
  return 'polygon';
}

/**
 * Apex direction of an isosceles triangle (degrees, SVG axes: -90 = up), or
 * null when no vertex is clearly the apex (e.g. near-equilateral).
 */
export function triangleApexAngle(raw: Point[]): number | null {
  const pts = dropCollinear(dedupe(raw));
  if (pts.length !== 3) return null;
  const len = (a: Point, b: Point) => Math.hypot(a[0] - b[0], a[1] - b[1]);
  // The apex is the vertex whose two sides are (most nearly) equal.
  const scores = pts.map((p, i) => {
    const a = len(p, pts[(i + 1) % 3]!);
    const b = len(p, pts[(i + 2) % 3]!);
    return { i, diff: Math.abs(a - b) / Math.max(a, b) };
  });
  scores.sort((x, y) => x.diff - y.diff);
  if (scores[1]!.diff - scores[0]!.diff < 0.08) return null;
  const apex = pts[scores[0]!.i]!;
  const cx = (pts[0]![0] + pts[1]![0] + pts[2]![0]) / 3;
  const cy = (pts[0]![1] + pts[1]![1] + pts[2]![1]) / 3;
  return (Math.atan2(apex[1] - cy, apex[0] - cx) * 180) / Math.PI;
}

function classifyCurved(points: Point[]): DrawnKind {
  const { cv, aspect } = roundness(points);
  if (cv < 0.08) return aspect >= 0.85 ? 'circle' : 'ellipse';
  return 'polygon';
}

// ─────────────────────────────────────────────────────────────────────────────
// Document walk
// ─────────────────────────────────────────────────────────────────────────────

type Style = Record<string, string>;

interface Rule {
  tag: string | null;
  id: string | null;
  classes: string[];
  specificity: number;
  order: number;
  decls: Style;
}

function parseDecls(text: string): Style {
  const out: Style = {};
  for (const decl of text.split(';')) {
    const k = decl.indexOf(':');
    if (k < 0) continue;
    const key = decl.slice(0, k).trim().toLowerCase();
    const val = decl.slice(k + 1).trim().replace(/\s*!important$/i, '');
    if (key) out[key] = val;
  }
  return out;
}

function parseStylesheet(css: string): Rule[] {
  const rules: Rule[] = [];
  let order = 0;
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, '');
  for (const m of clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const decls = parseDecls(m[2]!);
    for (const selRaw of m[1]!.split(',')) {
      const last = selRaw.trim().split(/[\s>+~]+/).pop() ?? '';
      if (!last || last.includes(':') || last.includes('[')) continue;
      const tag = last.match(/^([a-zA-Z][\w-]*)/)?.[1]?.toLowerCase() ?? null;
      const id = last.match(/#([\w-]+)/)?.[1] ?? null;
      const classes = [...last.matchAll(/\.([\w-]+)/g)].map((c) => c[1]!);
      if (!tag && !id && classes.length === 0 && last !== '*') continue;
      rules.push({ tag, id, classes, specificity: (id ? 100 : 0) + classes.length * 10 + (tag ? 1 : 0), order: order++, decls });
    }
  }
  return rules.sort((a, b) => a.specificity - b.specificity || a.order - b.order);
}

function parseAttrs(src: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of src.matchAll(/([^\s=/>"']+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>/]+))/g)) {
    out[m[1]!.toLowerCase()] = m[2] ?? m[3] ?? m[4] ?? '';
  }
  return out;
}

const STYLE_PROPS = ['fill', 'stroke', 'stroke-width', 'opacity', 'fill-opacity', 'stroke-opacity', 'visibility', 'display', 'color'];
const INHERITED = ['fill', 'stroke', 'stroke-width', 'fill-opacity', 'stroke-opacity', 'visibility', 'color'];
const NON_RENDERED = new Set(['defs', 'clippath', 'mask', 'pattern', 'symbol', 'marker', 'lineargradient', 'radialgradient', 'filter', 'title', 'desc', 'metadata', 'text', 'foreignobject', 'script', 'style']);
const CONTAINERS = new Set(['g', 'svg', 'a', 'switch']);
const SHAPES = new Set(['circle', 'ellipse', 'rect', 'polygon', 'polyline', 'path', 'line']);

interface Frame {
  tag: string;
  matrix: Matrix;
  style: Style;
  opacity: number;
  hidden: boolean;
}

/** Extracts the SVG markup from a model reply (```svg block, any block containing <svg, or raw markup). */
export function extractSvg(text: string): string | null {
  const blocks = [...text.matchAll(/```[\w-]*[^\n]*\n([\s\S]*?)```/g)].map((m) => m[1]!);
  const unterminated = text.match(/```[\w-]*[^\n]*\n([\s\S]*)$/);
  if (blocks.length === 0 && unterminated) blocks.push(unterminated[1]!);
  const candidates = [...blocks.reverse(), text];
  for (const c of candidates) {
    const start = c.search(/<svg[\s>]/i);
    if (start < 0) continue;
    const end = c.toLowerCase().lastIndexOf('</svg>');
    return end > start ? c.slice(start, end + 6) : `${c.slice(start)}</svg>`;
  }
  return null;
}

export function parseSvg(markup: string, opts: ParseOptions = {}): ParsedSvg {
  const result: ParsedSvg = { found: false, elements: 0, shapes: [], ignored: [] };
  try {
    walk(markup, result, opts.palette ?? 'basic');
  } catch (err) {
    result.ignored.push({ tag: 'svg', reason: `parse error: ${(err as Error).message}` });
  }
  return result;
}

function walk(markup: string, result: ParsedSvg, palette: PaletteId): void {
  let src = markup.replace(/<!--[\s\S]*?-->/g, '').replace(/<\?[\s\S]*?\?>/g, '').replace(/<!DOCTYPE[^>]*>/gi, '');
  const css: string[] = [];
  src = src.replace(/<style[^>]*>([\s\S]*?)<\/style\s*>/gi, (_, body: string) => {
    css.push(body.replace(/<!\[CDATA\[|\]\]>/g, ''));
    return '';
  });
  const rules = parseStylesheet(css.join('\n'));

  // Gradients → their first stop colour.
  const gradients = new Map<string, string>();
  for (const g of src.matchAll(/<(linearGradient|radialGradient)\b([^>]*)>([\s\S]*?)<\/\1\s*>/gi)) {
    const id = parseAttrs(g[2]!).id;
    const stop = g[3]!.match(/<stop\b([^>]*)>/i);
    if (id && stop) {
      const attrs = parseAttrs(stop[1]!);
      const color = parseDecls(attrs.style ?? '')['stop-color'] ?? attrs['stop-color'];
      if (color) gradients.set(id, color);
    }
  }

  const stack: Frame[] = [{ tag: '#root', matrix: IDENTITY, style: { fill: 'black' }, opacity: 1, hidden: false }];
  let nonRendered = 0;
  let rootSeen = false;
  const tagRe = /<(\/?)([a-zA-Z][\w:.-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(\/?)\s*>/g;
  let m: RegExpExecArray | null;
  while ((m = tagRe.exec(src)) !== null) {
    const closing = m[1] === '/';
    const tag = m[2]!.toLowerCase().replace(/^svg:/, '');
    const selfClosing = m[4] === '/';
    if (closing) {
      if (NON_RENDERED.has(tag)) nonRendered = Math.max(0, nonRendered - 1);
      else if (CONTAINERS.has(tag) && stack.length > 1) stack.pop();
      continue;
    }
    if (NON_RENDERED.has(tag)) {
      if (!selfClosing) nonRendered++;
      continue;
    }
    const attrs = parseAttrs(m[3]!);
    const parent = stack[stack.length - 1]!;
    const style = computeStyle(tag, attrs, parent.style, rules);
    const hidden = parent.hidden || style.display === 'none' || style.visibility === 'hidden';
    const opacity = parent.opacity * (style.opacity !== undefined ? clamp01(parseFloat(style.opacity)) : 1);

    if (CONTAINERS.has(tag)) {
      let matrix = multiply(parent.matrix, parseTransform(attrs.transform));
      if (tag === 'svg') {
        if (!rootSeen) {
          rootSeen = true;
          result.found = true;
          matrix = multiply(viewportMatrix(attrs), matrix);
        } else {
          matrix = multiply(multiply(parent.matrix, [1, 0, 0, 1, length(attrs.x), length(attrs.y)]), viewportMatrix(attrs, false));
        }
      }
      if (!selfClosing) stack.push({ tag, matrix, style: inherit(style), opacity, hidden });
      continue;
    }
    if (!SHAPES.has(tag) || nonRendered > 0) continue;
    result.elements++;
    if (result.elements > 800) continue;
    if (tag === 'line') {
      result.ignored.push({ tag, reason: 'line' });
      continue;
    }
    if (hidden) {
      result.ignored.push({ tag, reason: 'hidden' });
      continue;
    }
    const paint = resolvePaint(style, opacity, gradients);
    if (!paint) {
      result.ignored.push({ tag, reason: 'no visible fill or stroke' });
      continue;
    }
    const matrix = multiply(parent.matrix, parseTransform(attrs.transform));
    for (const g of geometry(tag, attrs)) {
      const pts = g.points.map((p) => apply(matrix, p));
      let kind: DrawnKind | null;
      if (tag === 'circle' || tag === 'ellipse') kind = roundness(pts).aspect >= 0.85 ? 'circle' : 'ellipse';
      else if (g.curved) kind = classifyCurved(pts);
      else kind = classifyPolygon(g.vertices.map((p) => apply(matrix, p)));
      const b = bboxOf(pts);
      const w = b.x1 - b.x0;
      const h = b.y1 - b.y0;
      if (!kind || !(w >= 2 && h >= 2)) {
        result.ignored.push({ tag, reason: 'degenerate' });
        continue;
      }
      if (w * h >= 0.85 * CANVAS * CANVAS) {
        result.ignored.push({ tag, reason: 'background' });
        continue;
      }
      if (isNearWhite(paint)) {
        result.ignored.push({ tag, reason: 'white' });
        continue;
      }
      const shape: DrawnShape = { kind, tag, rgb: paint, color: nearestPalette(paint, palette), cx: (b.x0 + b.x1) / 2, cy: (b.y0 + b.y1) / 2, w, h };
      if (kind === 'triangle') {
        const angle = triangleApexAngle(g.vertices.map((p) => apply(matrix, p)));
        if (angle !== null) shape.angle = angle;
      }
      result.shapes.push(shape);
    }
  }
}

function clamp01(x: number): number {
  return Number.isFinite(x) ? Math.max(0, Math.min(1, x)) : 1;
}

function inherit(style: Style): Style {
  const out: Style = {};
  for (const k of INHERITED) if (style[k] !== undefined) out[k] = style[k]!;
  return out;
}

function computeStyle(tag: string, attrs: Record<string, string>, inherited: Style, rules: Rule[]): Style {
  const style: Style = { ...inherited };
  for (const p of STYLE_PROPS) if (attrs[p] !== undefined) style[p] = attrs[p]!;
  const classes = (attrs.class ?? '').split(/\s+/).filter(Boolean);
  for (const r of rules) {
    if (r.tag && r.tag !== tag) continue;
    if (r.id && r.id !== attrs.id) continue;
    if (!r.classes.every((c) => classes.includes(c))) continue;
    Object.assign(style, r.decls);
  }
  if (attrs.style) Object.assign(style, parseDecls(attrs.style));
  return style;
}

function resolvePaint(style: Style, opacity: number, gradients: Map<string, string>): RGB | null {
  const resolve = (v: string | undefined) => {
    if (!v) return null;
    const url = v.match(/url\(\s*['"]?#([^)'"]+)['"]?\s*\)/i);
    return parseColor(url ? gradients.get(url[1]!) : v, style.color);
  };
  const fill = resolve(style.fill ?? 'black');
  const fillAlpha = fill ? fill.alpha * clamp01(parseFloat(style['fill-opacity'] ?? '1')) * opacity : 0;
  if (fill && fillAlpha >= 0.15) return fill.rgb;
  const stroke = resolve(style.stroke);
  const strokeWidth = style['stroke-width'] === undefined ? 1 : parseFloat(style['stroke-width']);
  const strokeAlpha = stroke ? stroke.alpha * clamp01(parseFloat(style['stroke-opacity'] ?? '1')) * opacity : 0;
  if (stroke && strokeAlpha >= 0.15 && strokeWidth > 0) return stroke.rgb;
  return null;
}

function viewportMatrix(attrs: Record<string, string>, isRoot = true): Matrix {
  const vb = attrs.viewbox ? nums(attrs.viewbox) : [];
  const width = parseFloat(attrs.width ?? '');
  const height = parseFloat(attrs.height ?? '');
  if (vb.length === 4 && vb[2]! > 0 && vb[3]! > 0) {
    const targetW = isRoot ? CANVAS : Number.isFinite(width) ? width : vb[2]!;
    const targetH = isRoot ? CANVAS : Number.isFinite(height) ? height : vb[3]!;
    return multiply([targetW / vb[2]!, 0, 0, targetH / vb[3]!, 0, 0], [1, 0, 0, 1, -vb[0]!, -vb[1]!]);
  }
  if (isRoot && Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0 && !/%/.test(attrs.width! + attrs.height!)) {
    return [CANVAS / width, 0, 0, CANVAS / height, 0, 0];
  }
  return IDENTITY;
}

interface Geo {
  points: Point[];
  vertices: Point[];
  curved: boolean;
}

function geometry(tag: string, a: Record<string, string>): Geo[] {
  switch (tag) {
    case 'circle': {
      const r = length(a.r);
      if (r <= 0) return [];
      const pts = ellipsePoints(length(a.cx), length(a.cy), r, r);
      return [{ points: pts, vertices: pts, curved: true }];
    }
    case 'ellipse': {
      const rx = length(a.rx);
      const ry = length(a.ry ?? a.rx);
      if (rx <= 0 || ry <= 0) return [];
      const pts = ellipsePoints(length(a.cx), length(a.cy), rx, ry);
      return [{ points: pts, vertices: pts, curved: true }];
    }
    case 'rect': {
      const x = length(a.x);
      const y = length(a.y);
      const w = length(a.width);
      const h = length(a.height);
      if (w <= 0 || h <= 0) return [];
      const pts: Point[] = [
        [x, y],
        [x + w, y],
        [x + w, y + h],
        [x, y + h],
      ];
      return [{ points: pts, vertices: pts, curved: false }];
    }
    case 'polygon':
    case 'polyline': {
      const n = nums(a.points ?? '');
      const pts: Point[] = [];
      for (let k = 0; k + 1 < n.length; k += 2) pts.push([n[k]!, n[k + 1]!]);
      return pts.length >= 3 ? [{ points: pts, vertices: pts, curved: false }] : [];
    }
    case 'path':
      return parsePath(a.d ?? '').map((sp) => ({ points: sp.points, vertices: sp.vertices, curved: sp.curved }));
  }
  return [];
}

/** Strips scripts, event handlers and foreign content so a model's SVG is safe to store and show. */
export function sanitizeSvg(svg: string): string {
  let s = svg
    .replace(/<script[\s\S]*?<\/script\s*>/gi, '')
    .replace(/<foreignObject[\s\S]*?<\/foreignObject\s*>/gi, '')
    .replace(/\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '')
    .replace(/((?:xlink:)?href\s*=\s*["'])\s*(?:javascript|data|https?):[^"']*/gi, '$1#');
  if (!/\sxmlns=/.test(s.slice(0, s.indexOf('>') + 1))) s = s.replace(/<svg\b/i, '<svg xmlns="http://www.w3.org/2000/svg"');
  return s;
}
